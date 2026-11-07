#!/usr/bin/env python3
"""Quantize the manga-colorization-v2 generator to int8 for the CPU EP.

Takes the published fp16 ONNX (same source as convert_colorizer_fp32.py),
widens it to fp32 in memory, then quantizes with one of two strategies:

--mode dynamic (default): int8 per-channel weights, fp32 activations. No
calibration, so it runs within any machine's memory; the output is
bit-for-bit deterministic for a given source file.

--mode static: full QDQ with unsigned-int8 activations calibrated MinMax on
the pinned pages under assets/colorizer_calib/ (app preprocessing: Rec.601
gray /255 into channel 0, hint channels zero). ARM64's fast path, but the
ORT MinMax calibrator materializes every intermediate as a graph output and
needs far more memory than this machine has — it OOM-kills even inside the
conversion container, so dynamic is what ships unless that changes.
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import (
    CalibrationDataReader,
    CalibrationMethod,
    QuantFormat,
    QuantType,
    quantize_dynamic,
    quantize_static,
)
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from convert_colorizer_fp32 import widen_graph

CALIB_HEIGHT = 704
CALIB_WIDTH = 512
DEFAULT_CALIB_DIR = Path(__file__).resolve().parent / "assets" / "colorizer_calib"


def preprocess_page(path: Path, height: int, width: int) -> np.ndarray:
    """The app's input contract: [1,5,H,W], ch0 gray in [0,1], hints zero."""
    image = Image.open(path).convert("L").resize((width, height), Image.LANCZOS)
    gray = np.asarray(image, dtype=np.float32) / 255.0
    tensor = np.zeros((1, 5, height, width), dtype=np.float32)
    tensor[0, 0] = gray
    return tensor


class PageCalibrationReader(CalibrationDataReader):
    """Feeds the pinned pages in sorted order so scales never depend on IO order."""

    def __init__(self, input_name: str, pages: list[Path]):
        self.input_name = input_name
        self.tensors = [preprocess_page(page, CALIB_HEIGHT, CALIB_WIDTH) for page in pages]
        self.index = 0

    def get_next(self):
        if self.index >= len(self.tensors):
            return None
        tensor = self.tensors[self.index]
        self.index += 1
        return {self.input_name: tensor}


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="path to the fp16 generator .onnx")
    parser.add_argument("--output", type=Path, required=True, help="path of the int8 .onnx to write")
    parser.add_argument(
        "--calibration-dir",
        type=Path,
        default=DEFAULT_CALIB_DIR,
        help="directory of B&W calibration pages (sorted order is the calibration order)",
    )
    parser.add_argument(
        "--no-arena",
        action="store_true",
        help="run the calibrator session with the CPU arena disabled; the MinMax "
        "augmented graph materializes every intermediate as an output, and the "
        "arena high-water mark of that live set exceeds this machine's commit "
        "budget while the true live set fits",
    )
    parser.add_argument(
        "--mode",
        choices=("dynamic", "static"),
        default="dynamic",
        help="dynamic: int8 weights, fp32 activations (no calibration memory). "
        "static: QDQ with calibrated u8 activations (needs a large-memory host)",
    )
    return parser.parse_args()


def disable_cpu_arena() -> None:
    """Forces every InferenceSession in this process onto the lean allocator.

    The MinMax calibrator creates its session internally, so the only seam is
    the InferenceSession constructor itself.
    """

    import onnxruntime as ort

    class ArenaFreeSession(ort.InferenceSession):
        def __init__(self, *args, **kwargs):
            options = ort.SessionOptions()
            options.enable_cpu_mem_arena = False
            options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
            options.log_severity_level = 3
            kwargs.pop("sess_options", None)
            super().__init__(*args, sess_options=options, **kwargs)

    ort.InferenceSession = ArenaFreeSession


def main() -> int:
    arguments = parse_arguments()
    if arguments.no_arena:
        disable_cpu_arena()

    import onnx

    model = onnx.load(str(arguments.source))
    widened = widen_graph(model.graph)
    onnx.checker.check_model(model)
    buffer = io.BytesIO()
    onnx.save(model, buffer)
    buffer.seek(0)

    widened_path = arguments.output.with_suffix(".fp32.tmp.onnx")
    widened_path.write_bytes(buffer.read())

    if arguments.mode == "dynamic":
        quantize_dynamic(
            model_input=str(widened_path),
            model_output=str(arguments.output),
            weight_type=QuantType.QInt8,
            per_channel=True,
            reduce_range=False,
            op_types_to_quantize=["Conv", "MatMul", "Gemm"],
        )
        summary = f"widened {widened} fp16 tensors, dynamic int8 weights"
    else:
        pages = sorted(
            path for path in arguments.calibration_dir.iterdir()
            if path.suffix.lower() in {".png", ".jpg", ".jpeg"}
        )
        if not pages:
            print(f"no calibration pages found in {arguments.calibration_dir}", file=sys.stderr)
            return 1

        session = ort.InferenceSession(str(widened_path), providers=["CPUExecutionProvider"])
        input_name = session.get_inputs()[0].name
        reader = PageCalibrationReader(input_name, pages)

        quantize_static(
            model_input=str(widened_path),
            model_output=str(arguments.output),
            calibration_data_reader=reader,
            quant_format=QuantFormat.QDQ,
            calibrate_method=CalibrationMethod.MinMax,
            activation_type=QuantType.QUInt8,
            weight_type=QuantType.QInt8,
            per_channel=True,
        )
        summary = (
            f"widened {widened} fp16 tensors, calibrated on {len(pages)} pages "
            f"({CALIB_WIDTH}x{CALIB_HEIGHT})"
        )

    onnx.checker.check_model(onnx.load(str(arguments.output)))
    widened_path.unlink()
    print(f"{summary} -> {arguments.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
