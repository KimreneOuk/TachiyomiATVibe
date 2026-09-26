#!/usr/bin/env python3
"""Export the upstream YOLO26 panel detector and dynamically quantize its ONNX graph."""

from __future__ import annotations

import argparse
from pathlib import Path

from _onnx_export import export_dynamic_int8, transpose_detect_output_to_channels_last


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    export_dynamic_int8(arguments.source, arguments.output, task="detect", image_size=640)
    transpose_detect_output_to_channels_last(arguments.output)
    print(f"Wrote {arguments.output} ({arguments.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
