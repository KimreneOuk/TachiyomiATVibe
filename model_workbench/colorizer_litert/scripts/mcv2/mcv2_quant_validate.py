"""Validates the int8-quantized manga-colorization-v2 generator against fp32.

Usage:
  python -I mcv2_quant_validate.py --fp32 <fp32.onnx> --int8 <int8.onnx>
      [--dynamic <dyn.onnx>] --page <page.jpg> ... --out-dir <dir>

Runs each candidate on the app's input contract ([1,5,704,512], ch0 gray,
zero hints), compares raw outputs (PSNR / max-abs / mean-abs), times median
CPU latency, and writes side-by-side decode PNGs plus a UTF-8 JSON report.
ASCII-only console output (cp1252 host).
"""

import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

HEIGHT, WIDTH = 704, 512


def preprocess(path: Path) -> np.ndarray:
    image = Image.open(path).convert("L").resize((WIDTH, HEIGHT), Image.LANCZOS)
    gray = np.asarray(image, dtype=np.float32) / 255.0
    tensor = np.zeros((1, 5, HEIGHT, WIDTH), dtype=np.float32)
    tensor[0, 0] = gray
    return tensor


def decode(rgb_planar: np.ndarray) -> np.ndarray:
    """The app's decode: raw output planes clamped to [0,1] -> HWC uint8."""
    planes = np.clip(rgb_planar.reshape(3, HEIGHT, WIDTH), 0.0, 1.0)
    return (np.transpose(planes, (1, 2, 0)) * 255.0 + 0.5).astype(np.uint8)


def run_latency(session: ort.InferenceSession, name: str, tensor: np.ndarray, runs: int = 3) -> float:
    input_name = session.get_inputs()[0].name
    session.run(None, {input_name: tensor})  # warmup
    times = []
    for _ in range(runs):
        started = time.perf_counter()
        session.run(None, {input_name: tensor})
        times.append(time.perf_counter() - started)
    return float(np.median(times))


def psnr(reference: np.ndarray, candidate: np.ndarray) -> float:
    mse = float(np.mean((reference - candidate) ** 2))
    return float(10.0 * np.log10(1.0 / max(mse, 1e-12)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fp32", type=Path, required=True)
    parser.add_argument("--int8", type=Path, required=True)
    parser.add_argument("--dynamic", type=Path)
    parser.add_argument("--page", type=Path, action="append", required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    arguments = parser.parse_args()

    arguments.out_dir.mkdir(parents=True, exist_ok=True)
    candidates = {"fp32": arguments.fp32, "int8": arguments.int8}
    if arguments.dynamic:
        candidates["dynamic"] = arguments.dynamic

    report = {"pages": [], "sizes_bytes": {tag: path.stat().st_size for tag, path in candidates.items()}}

    for page in arguments.page:
        tensor = preprocess(page)
        outputs = {}
        latencies = {}
        # One live session at a time: parallel arenas would exhaust the host's
        # commit budget on this 512x704 UNet.
        for tag, path in candidates.items():
            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            input_name = session.get_inputs()[0].name
            outputs[tag] = session.run(None, {input_name: tensor})[0].astype(np.float32)
            latencies[tag] = run_latency(session, tag, tensor)
            del session

        reference = outputs["fp32"]
        page_report = {
            "page": str(page),
            "latency_seconds": latencies,
            "variants": {},
        }
        for tag, output in outputs.items():
            if tag == "fp32":
                continue
            entry = {
                "psnr_db": psnr(reference, output),
                "max_abs": float(np.abs(reference - output).max()),
                "mean_abs": float(np.abs(reference - output).mean()),
            }
            page_report["variants"][tag] = entry

        # Side-by-side decode strip: fp32 | int8 (| dynamic).
        strip_parts = [decode(outputs["fp32"])]
        for tag in ("int8", "dynamic"):
            if tag in outputs:
                strip_parts.append(decode(outputs[tag]))
        strip = np.concatenate(strip_parts, axis=1)
        strip_path = arguments.out_dir / f"{page.stem}_compare.png"
        Image.fromarray(strip).save(strip_path)

        report["pages"].append(page_report)
        for tag, entry in page_report["variants"].items():
            print(
                "%s %s: psnr=%.2f dB max_abs=%.4g mean_abs=%.4g "
                "(fp32 %.2fs vs %s %.2fs)"
                % (
                    page.stem, tag, entry["psnr_db"], entry["max_abs"], entry["mean_abs"],
                    latencies["fp32"], tag, latencies[tag],
                )
            )

    report_path = arguments.out_dir / "mcv2_quant_report.json"
    with open(report_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    print("REPORT %s" % report_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
