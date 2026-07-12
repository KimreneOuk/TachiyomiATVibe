from __future__ import annotations

import json
from pathlib import Path

from inference.detector import DetectionBox, non_max_suppression


def test_detector_nms_matches_golden_json() -> None:
    boxes = [
        DetectionBox(10.0, 10.0, 60.0, 60.0, 1, 0.95),
        DetectionBox(12.0, 12.0, 58.0, 58.0, 1, 0.70),
        DetectionBox(100.0, 100.0, 150.0, 150.0, 2, 0.80),
        DetectionBox(200.0, 200.0, 210.0, 210.0, 2, 0.40),
    ]

    actual = [box.to_json() for box in non_max_suppression(boxes)]
    golden = json.loads(Path("tests/fixtures/detector_golden.json").read_text(encoding="utf-8"))
    assert actual == golden
