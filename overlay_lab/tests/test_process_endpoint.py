"""Tests for POST /api/process — the spec's structured-layers endpoint.

Contract per the spec:
  POST /api/process (multipart: file=<image>)
  -> 200 JSON:
    {
      "image_dimensions": {"width": W, "height": H},
      "layers": {
        "detector": {"bubbles": [[x1,y1,x2,y2],...], "bubble_text": [...], "free_text": [...]},
        "segmentation": {"bubble_masks": [[[x,y],...],...]},
        "panel": {"frames": [[x1,y1,x2,y2],...]}
      },
      "ocr_data": [{"id":1, "box":[x1,y1,x2,y2], "raw_text":"...", "translated_text":"..."}],
      "warnings": ["..."]  # non-empty if a model failed/missing (NO silent fallback)
    }

No-fallback rule: a missing/failed model makes its layer null AND adds a
warning string. The endpoint never silently returns empty data for a layer
that should have run.
"""
import glob
import io
from pathlib import Path

import pytest
from PIL import Image
from fastapi.testclient import TestClient


@pytest.fixture(scope="module")
def client():
    from backend.main import app
    return TestClient(app)


@pytest.fixture(scope="module")
def sample_bytes():
    REPO_ROOT = Path(__file__).resolve().parents[2]
    pages = sorted(glob.glob(str(REPO_ROOT / "tools" / "Okiraku*" / "page-003.jpg")))
    if not pages:
        pytest.skip("Okiraku page-003.jpg not found")
    return Path(pages[0]).read_bytes()


# ── response shape ────────────────────────────────────────────────────


def test_process_returns_200_with_spec_shape(client, sample_bytes):
    r = client.post("/api/process", files={"file": ("page-003.jpg", sample_bytes, "image/jpeg")})
    assert r.status_code == 200, r.text[:300]
    d = r.json()
    assert "image_dimensions" in d
    assert "width" in d["image_dimensions"] and "height" in d["image_dimensions"]
    assert "layers" in d
    assert "ocr_data" in d


def test_image_dimensions_correct(client, sample_bytes):
    r = client.post("/api/process", files={"file": ("p.jpg", sample_bytes, "image/jpeg")})
    img = Image.open(io.BytesIO(sample_bytes))
    d = r.json()
    assert d["image_dimensions"]["width"] == img.width
    assert d["image_dimensions"]["height"] == img.height


# ── detector layer ────────────────────────────────────────────────────


def test_detector_layer_has_three_box_lists(client, sample_bytes):
    r = client.post("/api/process", files={"file": ("p.jpg", sample_bytes, "image/jpeg")})
    det = r.json()["layers"]["detector"]
    if det is None:
        pytest.skip("detector model missing (layer null)")
    for key in ("bubbles", "bubble_text", "free_text"):
        assert key in det, f"missing detector.{key}"
        for box in det[key]:
            assert len(box) == 4, f"detector.{key} box must be [x1,y1,x2,y2]"


# ── segmentation layer ────────────────────────────────────────────────


def test_segmentation_layer_has_polygons(client, sample_bytes):
    r = client.post("/api/process", files={"file": ("p.jpg", sample_bytes, "image/jpeg")})
    seg = r.json()["layers"]["segmentation"]
    if seg is None:
        pytest.skip("seg model missing (layer null)")
    assert "bubble_masks" in seg
    for poly in seg["bubble_masks"]:
        assert isinstance(poly, list)
        assert len(poly) >= 3, "degenerate polygon"
        for pt in poly:
            assert len(pt) == 2


# ── panel layer ───────────────────────────────────────────────────────


def test_panel_layer_has_frames(client, sample_bytes):
    r = client.post("/api/process", files={"file": ("p.jpg", sample_bytes, "image/jpeg")})
    panel = r.json()["layers"]["panel"]
    if panel is None:
        pytest.skip("panel model missing (layer null)")
    assert "frames" in panel
    for box in panel["frames"]:
        assert len(box) == 4


# ── ocr_data ──────────────────────────────────────────────────────────


def test_ocr_data_entries_have_required_fields(client, sample_bytes):
    r = client.post("/api/process", files={"file": ("p.jpg", sample_bytes, "image/jpeg")})
    ocr = r.json()["ocr_data"]
    for entry in ocr:
        assert "id" in entry
        assert "box" in entry and len(entry["box"]) == 4
        assert "raw_text" in entry
        assert "translated_text" in entry


# ── no silent fallback ────────────────────────────────────────────────


def test_warnings_present_when_expected(client, sample_bytes):
    """warnings array always exists (possibly empty). If any layer is null,
    warnings MUST be non-empty (no silent skip)."""
    r = client.post("/api/process", files={"file": ("p.jpg", sample_bytes, "image/jpeg")})
    d = r.json()
    assert "warnings" in d
    layers = d["layers"]
    any_null = any(v is None for v in (layers["detector"], layers["segmentation"], layers["panel"]))
    if any_null:
        assert len(d["warnings"]) > 0, "null layer but no warning = silent fallback (forbidden)"
