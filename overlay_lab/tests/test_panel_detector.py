"""Tests for the YOLO26 panel detector (faithful port of OnnxPanelDetector.kt).

Contract:
  OnnxPanelDetector(model_path) — loads int8 ONNX, CPU session.
  detector.detect(pil_image) -> list[DetectionBox]
    DetectionBox reused from companion_server.inference.detector
    label 0 = frame, label 1 = text
    Boxes in ORIGINAL-image coordinates (not 640x640 letterbox space).
    Letterbox grey-114 preprocessing, per-class NMS (conf 0.5, iou 0.45).

Constants (match OnnxPanelDetector.kt companion object):
  IMG_SIZE=640, CONF_THRESHOLD=0.5, IOU_THRESHOLD=0.45, PAD_COLOR=114 (grey).
"""
import glob
from pathlib import Path

import pytest
from PIL import Image

from backend.inference.panel_detector import OnnxPanelDetector, IMG_SIZE, CONF_THRESHOLD, IOU_THRESHOLD
from backend import config


REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_PAGE = sorted(glob.glob(str(
    REPO_ROOT / "tools" / "Okiraku*" / "page-003.jpg"
)))


@pytest.fixture(scope="module")
def detector():
    if not config.PANEL_MODEL.exists():
        pytest.skip("panel detector model not on disk")
    return OnnxPanelDetector(config.PANEL_MODEL)


@pytest.fixture(scope="module")
def sample_image():
    if not SAMPLE_PAGE:
        pytest.skip("Okiraku page-003.jpg not found")
    return Image.open(SAMPLE_PAGE[0]).convert("RGB")


# ── constants match Kotlin ────────────────────────────────────────────


def test_constants_match_kotlin():
    assert IMG_SIZE == 640
    assert CONF_THRESHOLD == 0.5
    assert IOU_THRESHOLD == 0.45


# ── detect returns DetectionBoxes in original coords ──────────────────


def test_detect_returns_detection_boxes(detector, sample_image):
    boxes = detector.detect(sample_image)
    assert isinstance(boxes, list)
    # page-003 has multiple panels — expect several frames
    frames = [b for b in boxes if b.label == 0]
    assert len(frames) >= 1, "expected at least 1 frame box on page-003"


def test_boxes_in_original_coords(detector, sample_image):
    w, h = sample_image.size
    boxes = detector.detect(sample_image)
    for b in boxes:
        assert 0 <= b.x1 <= w, f"x1={b.x1} out of bounds (w={w})"
        assert 0 <= b.y1 <= h, f"y1={b.y1} out of bounds (h={h})"
        assert 0 <= b.x2 <= w, f"x2={b.x2} out of bounds (w={w})"
        assert 0 <= b.y2 <= h, f"y2={b.y2} out of bounds (h={h})"
        assert b.x2 > b.x1 and b.y2 > b.y1, "degenerate box"


def test_labels_are_frame_or_text_only(detector, sample_image):
    boxes = detector.detect(sample_image)
    for b in boxes:
        assert b.label in (0, 1), f"unexpected label {b.label}"


def test_scores_above_threshold(detector, sample_image):
    boxes = detector.detect(sample_image)
    for b in boxes:
        assert b.score >= CONF_THRESHOLD, f"score {b.score} below threshold {CONF_THRESHOLD}"


# ── NMS dedupes high-IoU overlaps ─────────────────────────────────────


def test_nms_dedupes_overlaps(detector, sample_image):
    """If NMS works, no two same-class boxes overlap > IOU_THRESHOLD."""
    boxes = detector.detect(sample_image)

    def iou(a, b):
        ix1, iy1 = max(a.x1, b.x1), max(a.y1, b.y1)
        ix2, iy2 = min(a.x2, b.x2), min(a.y2, b.y2)
        iw, ih = ix2 - ix1, iy2 - iy1
        if iw <= 0 or ih <= 0:
            return 0.0
        inter = iw * ih
        union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return inter / union if union > 0 else 0.0

    for cls in (0, 1):
        same = [b for b in boxes if b.label == cls]
        for i in range(len(same)):
            for j in range(i + 1, len(same)):
                ov = iou(same[i], same[j])
                assert ov <= IOU_THRESHOLD, (
                    f"NMS failed: two label-{cls} boxes overlap IoU={ov:.3f} > {IOU_THRESHOLD}"
                )
