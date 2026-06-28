"""Parity tests for inpaint-stage free-text Paddle refine.

Covers the Python mirror of ``AOTInpainting.refineFreeTextBoxes`` without loading
ONNX: ``detect_paddle_lines`` is monkeypatched with deterministic line boxes.
"""

from server import FREE_TEXT_REFINE_PAD, PADDLE_INPAINT_BOX_THRESH, PADDLE_INPAINT_CROP_PAD, PADDLE_INPAINT_THRESH
from server import TextLine, refine_free_text_boxes


def test_refine_free_text_boxes_backprojects_paddle_lines(monkeypatch):
    calls = []

    def fake_detect(crop, thresh, box_thresh):
        calls.append((crop.shape[:2], thresh, box_thresh))
        return [TextLine([2, 3, 12, 18], 0.91)]

    monkeypatch.setattr("server.detect_paddle_lines", fake_detect)

    import numpy as np

    rgb = np.zeros((100, 120, 3), dtype=np.uint8)
    refined, line_count, fallback_count = refine_free_text_boxes(rgb, [[20, 30, 50, 70]])

    # Crop origin = (20-12, 30-12) = (8,18); line box is back-projected to page,
    # then expanded by the free-text-only guard band to catch glyph tails.
    assert refined == [[10 - FREE_TEXT_REFINE_PAD, 21 - FREE_TEXT_REFINE_PAD, 20 + FREE_TEXT_REFINE_PAD, 36 + FREE_TEXT_REFINE_PAD]]
    assert line_count == 1
    assert fallback_count == 0
    assert calls == [((64, 54), PADDLE_INPAINT_THRESH, PADDLE_INPAINT_BOX_THRESH)]
    assert PADDLE_INPAINT_CROP_PAD == 12


def test_refine_free_text_boxes_falls_back_to_detector_box(monkeypatch):
    def fake_detect(crop, thresh, box_thresh):
        return []

    monkeypatch.setattr("server.detect_paddle_lines", fake_detect)

    import numpy as np

    rgb = np.zeros((80, 80, 3), dtype=np.uint8)
    refined, line_count, fallback_count = refine_free_text_boxes(rgb, [[5, 6, 25, 26]])

    assert refined == [[5, 6, 25, 26]]
    assert line_count == 0
    assert fallback_count == 1
