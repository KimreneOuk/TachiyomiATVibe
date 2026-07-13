"""
Emit dynamic-vs-static AOT inpaint outputs for the Tier 3 corpus gate.

This script does ONLY model inference (Python: onnxruntime is Python-only, like
tools/aot_conversion/). It writes model outputs as raw binary (.bin) for the JVM
gate test to read, plus PNGs for human visual QA. The actual guard verdict (the
Kotlin AotOutputGuard) runs in the JVM test AotCorpusGateTest.kt, which reads
the .bin files back. No guard math lives here, so there is no parity mirror to
drift out of sync.

Why .bin not PNG for the test: Android unit tests stub out java.awt, so
javax.imageio is unavailable on the test classpath. The .bin format is trivial
to read with java.io.DataInputStream and carries the exact ARGB IntArray prod
feeds to AotOutputGuard, with no decoder in the loop.

Inputs:
  app/src/main/assets/models/inpainting/aot.onnx      (dynamic)
  app/src/main/assets/models/inpainting/aot-512.onnx  (static, slimmed)
  <corpus>/<page>/{page.jpg, mask.png, manifest.json}

Outputs (one folder per page):
  <out>/<page>/dynamic_out.bin   raw ARGB int32 array (see FORMAT)
  <out>/<page>/static_out.bin    raw ARGB int32 array
  <out>/<page>/mask.bin          raw ARGB int32 array (ALPHA8-style packing)
  <out>/<page>/dynamic_out.png   512x512 RGB — human visual QA only
  <out>/<page>/static_out.png    512x512 RGB — human visual QA only
  <out>/<page>/mask.png          512x512 L — human visual QA only
  <out>/<page>/manifest.json     copied through

FORMAT (.bin): big-endian, no padding
  int32 width
  int32 height
  int32[width*height]  ARGB pixels, Kotlin-Int semantics (signed 32-bit)

ARGB packing mirrors Android Bitmap.getPixels (0xAARRGGBB):
  - output pixels: alpha=0xFF, (r,g,b) from the model output clipped [0,255]
  - mask pixels:   ALPHA8-style. erase -> 0xFF000000 (alpha 255), keep -> 0x00000000.
    AotOutputGuard.maskValue = max(byte0, alpha) -> erase yields 255 (>127=masked).

Usage:
  python tools/aot_corpus/emit_corpus_outputs.py \\
      --corpus tools/aot_corpus/synthetic_corpus \\
      --out app/src/test/resources/corpus/aot
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import struct
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
DYNAMIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot.onnx"
STATIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot-512.onnx"
MODEL_INPUT_SIZE = 512
IDENTITY_PATTERN = re.compile(r"^real_\d{3}__ft_\d{3}$")
REQUIRED_INPUT_FILES = ("page.jpg", "mask.png", "manifest.json")
SUB512_FIXTURE_SPECS = (
    ("real_001__ft_001", 300),
    ("real_002__ft_001", 400),
    ("real_008__ft_003", 480),
    ("real_024__ft_001", 511),
)


def validate_corpus(corpus_dir: Path, minimum_samples: int) -> list[tuple[Path, dict[str, Any]]]:
    """Validate every input and its generator report; no skip/fallback path exists."""
    failures: list[str] = []
    samples: list[tuple[Path, dict[str, Any]]] = []
    report_path = corpus_dir / "generation_report.json"
    generation_report: dict[str, Any] | None = None
    try:
        generation_report = json.loads(report_path.read_text(encoding="utf-8"))
        if not isinstance(generation_report, dict):
            raise ValueError("root must be an object")
    except (OSError, json.JSONDecodeError, ValueError) as error:
        failures.append(f"generation_report.json: missing or invalid: {error}")
    for entry in sorted(corpus_dir.iterdir(), key=lambda path: path.name):
        if entry.name == report_path.name:
            continue
        if not entry.is_dir():
            failures.append(f"{entry.name}: unexpected non-directory entry")
            continue
        missing = [name for name in REQUIRED_INPUT_FILES if not (entry / name).is_file()]
        if missing:
            failures.append(f"{entry.name}: missing {', '.join(missing)}")
            continue
        try:
            manifest = json.loads((entry / "manifest.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            failures.append(f"{entry.name}: invalid manifest: {error}")
            continue
        identity = manifest.get("identity")
        if not IDENTITY_PATTERN.fullmatch(entry.name):
            failures.append(f"{entry.name}: identity does not match real_NNN__ft_NNN")
        if identity != entry.name or manifest.get("page") != entry.name:
            failures.append(f"{entry.name}: manifest identity/page must equal directory name")
        if manifest.get("fallback_used") is not False:
            failures.append(f"{entry.name}: fallback_used must be explicitly false")
        if manifest.get("paddle_line_count", 0) < 1:
            failures.append(f"{entry.name}: paddle_line_count must be positive")
        try:
            with Image.open(entry / "page.jpg") as page, Image.open(entry / "mask.png") as mask:
                if page.size != mask.size:
                    failures.append(f"{entry.name}: page {page.size} and mask {mask.size} differ")
                if page.width > MODEL_INPUT_SIZE or page.height > MODEL_INPUT_SIZE:
                    failures.append(f"{entry.name}: crop {page.size} exceeds model size {MODEL_INPUT_SIZE}")
                mask_array = np.asarray(mask.convert("L"), dtype=np.uint8)
                if int(np.count_nonzero(mask_array > 127)) < 16:
                    failures.append(f"{entry.name}: mask has fewer than 16 erased pixels")
        except OSError as error:
            failures.append(f"{entry.name}: unreadable image or mask: {error}")
        samples.append((entry, manifest))
    actual_identities = [entry.name for entry, _ in samples]
    if generation_report is not None:
        report_failures = generation_report.get("failures")
        report_identities = generation_report.get("samples")
        report_failure_count = generation_report.get("failure_count")
        report_sample_count = generation_report.get("sample_count")
        if not isinstance(report_failures, list):
            failures.append("generation_report.json: failures must be an array")
        elif report_failure_count != len(report_failures) or report_failure_count != 0:
            failures.append(
                f"generation_report.json: requires zero failures; "
                f"failure_count={report_failure_count!r}, failures={len(report_failures)}"
            )
        if not isinstance(report_identities, list) or not all(isinstance(value, str) for value in report_identities):
            failures.append("generation_report.json: samples must be a string array")
        else:
            if len(report_identities) != len(set(report_identities)):
                failures.append("generation_report.json: samples contains duplicate identities")
            if report_sample_count != len(report_identities):
                failures.append(
                    f"generation_report.json: sample_count={report_sample_count!r} "
                    f"but samples has {len(report_identities)} identities"
                )
            if report_identities != actual_identities:
                failures.append(
                    "generation_report.json: identity set/order does not exactly match corpus directories "
                    f"(report={report_identities!r}, actual={actual_identities!r})"
                )
    if len(samples) < minimum_samples:
        failures.append(f"corpus has {len(samples)} samples; minimum is {minimum_samples}")
    source_pages = {manifest.get("source_page") for _, manifest in samples}
    if len(source_pages) < 2:
        failures.append("corpus must contain samples from multiple source pages")
    if failures:
        for failure in failures:
            print(f"CORPUS FAILURE: {failure}", file=sys.stderr)
        raise ValueError(f"corpus validation failed with {len(failures)} failure(s)")
    return samples


def load_model(path: Path) -> ort.InferenceSession:
    if not path.is_file():
        raise FileNotFoundError(f"model not found: {path}")
    so = ort.SessionOptions()
    so.log_severity_level = 3  # silence ORT chatter
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def center_crop(array: np.ndarray, side: int) -> np.ndarray:
    """Use AotPadPath's integer centering rule; odd remainders stay right/bottom."""
    height, width = array.shape[:2]
    if width != height or side < 1 or side > width:
        raise ValueError(f"center crop requires square source and side in range: source={width}x{height}, side={side}")
    offset = (width - side) // 2
    return np.ascontiguousarray(array[offset : offset + side, offset : offset + side])


def histogram_median(values: np.ndarray) -> int:
    """Match PushPullGradient.histogramMedian: first bin with cumulative > count/2."""
    histogram = np.bincount(values.reshape(-1), minlength=256)
    return int(np.searchsorted(np.cumsum(histogram), values.size // 2 + 1))


def production_background(page: np.ndarray, mask: np.ndarray, ring: int = 8) -> np.ndarray:
    """Exact channel-wise port of PushPullGradient.localRingMedian."""
    masked_y, masked_x = np.nonzero(mask > 0.5)
    if masked_x.size:
        x1, x2 = int(masked_x.min()), int(masked_x.max())
        y1, y2 = int(masked_y.min()), int(masked_y.max())
        rx1, rx2 = max(0, x1 - ring), min(page.shape[1], x2 + ring + 1)
        ry1, ry2 = max(0, y1 - ring), min(page.shape[0], y2 + ring + 1)
        sample = page[ry1:ry2, rx1:rx2]
        keep = np.ones(sample.shape[:2], dtype=bool)
        keep[y1 - ry1 : y2 - ry1 + 1, x1 - rx1 : x2 - rx1 + 1] = False
        channels = sample[keep]
    else:
        channels = page.reshape(-1, 3)
    if channels.size == 0:
        channels = page.reshape(-1, 3)
    return np.array([histogram_median(channels[:, channel]) for channel in range(3)], dtype=np.uint8)


def tensor_inputs(page: np.ndarray, mask: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Normalize and black out the mask exactly as AOTInpainting.inpaint()."""
    page_float = page.astype(np.float32)
    mask_float = (mask > 127).astype(np.float32)
    normalized = (page_float / 127.5 - 1.0) * (1.0 - mask_float[..., None])
    return (
        np.ascontiguousarray(np.transpose(normalized, (2, 0, 1))[None, ...], dtype=np.float32),
        np.ascontiguousarray(mask_float[None, None, ...], dtype=np.float32),
    )


def production_inputs(page: np.ndarray, mask: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Build dynamic-native and static-512 inputs for the same real crop.

    The static route mirrors production: center the unscaled RGB crop over the
    channel-wise local-ring background, center the binary mask over zeros, then
    run at 512 and crop the output back. No synthetic or fake color is inserted.
    """
    if page.shape[:2] != mask.shape or page.shape[0] != page.shape[1]:
        raise ValueError(f"page/mask must be matching squares, got page={page.shape}, mask={mask.shape}")
    side = page.shape[0]
    if side > MODEL_INPUT_SIZE:
        raise ValueError(f"crop side {side} exceeds {MODEL_INPUT_SIZE}")
    dynamic_side = side + (8 - side % 8) % 8
    dynamic_page = np.zeros((dynamic_side, dynamic_side, 3), dtype=np.uint8)
    dynamic_page[:side, :side] = page
    dynamic_mask_padded = np.zeros((dynamic_side, dynamic_side), dtype=np.uint8)
    dynamic_mask_padded[:side, :side] = mask
    dynamic_image, dynamic_mask = tensor_inputs(dynamic_page, dynamic_mask_padded)
    offset = (MODEL_INPUT_SIZE - side) // 2
    background = production_background(page, (mask > 127).astype(np.float32))
    padded_page = np.empty((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, 3), dtype=np.uint8)
    padded_page[...] = background
    padded_mask = np.zeros((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE), dtype=np.uint8)
    padded_page[offset : offset + side, offset : offset + side] = page
    padded_mask[offset : offset + side, offset : offset + side] = mask
    static_image, static_mask = tensor_inputs(padded_page, padded_mask)
    return dynamic_image, dynamic_mask, static_image, static_mask


def run_model(sess: ort.InferenceSession, img: np.ndarray, mask: np.ndarray, page_arr: np.ndarray) -> np.ndarray:
    """Run inference, return output as HWC uint8 [0,255] dequantized like prod.

    Mirrors AOTInpainting.kt:577-594:
      - dequantize: (out + 1.0) * 127.5, clip [0,255]  (NOT out * 255)
      - grayscale luma-collapse when avgChroma < 15 (B&W manga crops), matching
        the isGrayscale branch in prod.
    """
    out = sess.run(None, {"image": img, "mask": mask})[0]  # 1,3,H,W float in [-1,1]
    out = np.transpose(out[0], (1, 2, 0))                  # H,W,3
    # Prod dequantize (AOTInpainting.kt:581-586)
    out = np.clip((out + 1.0) * 127.5, 0, 255)
    # Grayscale luma collapse if input was grayscale (AOTInpainting.kt:463,587-589)
    r, g, b = page_arr[..., 0], page_arr[..., 1], page_arr[..., 2]
    chroma = (np.maximum(np.maximum(r, g), b) - np.minimum(np.minimum(r, g), b))
    avg_chroma = chroma.mean()
    if avg_chroma < 15.0:
        luma = (0.299 * out[..., 0] + 0.587 * out[..., 1] + 0.114 * out[..., 2]).round().astype(np.uint8)
        return np.stack([luma, luma, luma], axis=-1)
    return out.astype(np.uint8)


def run_dynamic_native_vs_static_512(
    dynamic_session: ort.InferenceSession,
    static_session: ort.InferenceSession,
    page: np.ndarray,
    mask: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Compare the production routes and return both outputs at native crop size."""
    dynamic_image, dynamic_mask, static_image, static_mask = production_inputs(page, mask)
    side = page.shape[0]
    dynamic_padded = run_model(dynamic_session, dynamic_image, dynamic_mask, page.astype(np.float32))
    dynamic_out = np.ascontiguousarray(dynamic_padded[:side, :side])
    static_512 = run_model(static_session, static_image, static_mask, page.astype(np.float32))
    static_out = center_crop(static_512, side)
    return dynamic_out, static_out, (mask > 127).astype(np.float32)


def write_argb_bin(path: Path, hwc: np.ndarray) -> None:
    """Write a HWC uint8 RGB image as big-endian ARGB int32 .bin (see FORMAT in docstring)."""
    h, w, _ = hwc.shape
    r = hwc[..., 0].astype(np.int64)
    g = hwc[..., 1].astype(np.int64)
    b = hwc[..., 2].astype(np.int64)
    # 0xFF<<24 overflows int32; reinterpret as signed to match Kotlin Int.
    unsigned = (np.uint32(0xFF000000).astype(np.uint32) | (r << 16) | (g << 8) | b).astype(np.uint32)
    signed = unsigned.astype(np.int32)
    with open(path, "wb") as f:
        f.write(struct.pack(">i", int(w)))
        f.write(struct.pack(">i", int(h)))
        # Big-endian int32 per pixel.
        f.write(signed.astype(">i4").tobytes())


def write_mask_bin(path: Path, mask_hw: np.ndarray) -> None:
    """Write a HW {0,1} mask as ALPHA8-style ARGB int32 .bin.
    erase (1) -> 0xFF000000 (alpha 255, RGB 0). AotOutputGuard.maskValue=max(byte0, alpha)=255.
    keep (0) -> 0x00000000. maskValue=0 (<127=not masked).
    """
    h, w = mask_hw.shape
    with open(path, "wb") as f:
        f.write(struct.pack(">i", int(w)))
        f.write(struct.pack(">i", int(h)))
        # Build ARGB: alpha=255 where mask==1, else 0. RGB=0.
        alpha = (mask_hw.astype(np.uint32) * np.uint32(0xFF000000)).astype(np.int32)
        f.write(alpha.astype(">i4").tobytes())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, help="corpus dir (one subfolder per sample)")
    ap.add_argument("--out", required=True, help="new staging output dir")
    ap.add_argument("--min-samples", type=int, default=20)
    ap.add_argument(
        "--sub512-fixtures",
        action="store_true",
        help="emit the deterministic transformed real-corpus fixtures at 300,400,480,511",
    )
    ap.add_argument("--clean", action="store_true", help="replace an existing staging output")
    args = ap.parse_args()

    corpus_dir = Path(args.corpus).resolve()
    out_dir = Path(args.out).resolve()
    if not corpus_dir.is_dir():
        print(f"ERROR: corpus dir not found: {corpus_dir}", file=sys.stderr)
        return 1

    try:
        pages = validate_corpus(corpus_dir, args.min_samples)
        generation_source = json.loads(
            (corpus_dir / "generation_report.json").read_text(encoding="utf-8")
        ).get("source")
        if args.sub512_fixtures:
            page_by_identity = {entry.name: (entry, manifest) for entry, manifest in pages}
            missing_fixtures = [identity for identity, _ in SUB512_FIXTURE_SPECS if identity not in page_by_identity]
            if missing_fixtures:
                raise ValueError(f"sub-512 fixture sources missing: {missing_fixtures}")
            pages = [page_by_identity[identity] for identity, _ in SUB512_FIXTURE_SPECS]
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if out_dir.exists():
        if not args.clean:
            print(f"ERROR: output already exists (pass --clean for staging regeneration): {out_dir}", file=sys.stderr)
            return 1
        shutil.rmtree(out_dir)

    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    failures: list[dict[str, str]] = []
    print("Loading models ...")
    try:
        dyn_sess = load_model(DYNAMIC_MODEL)
        stat_sess = load_model(STATIC_MODEL)
    except Exception as error:
        failure = {"identity": "", "stage": "model_startup", "error": repr(error)}
        failures.append(failure)
        print(f"EMIT FAILURE: {json.dumps(failure, sort_keys=True)}", file=sys.stderr)
        report = {
            "emitter": "emit_corpus_outputs.py",
            "corpus": generation_source,
            "expected_count": len(pages),
            "emitted_count": 0,
            "failure_count": 1,
            "identities": [],
            "failures": failures,
        }
        try:
            (out_dir / "emission_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        except OSError as report_error:
            print(f"ERROR: could not write emission failure report: {report_error}", file=sys.stderr)
        return 1
    for page_dir, manifest_data in pages:
        page_img = page_dir / "page.jpg"
        page_mask = page_dir / "mask.png"
        manifest = page_dir / "manifest.json"
        try:
            page_arr = np.asarray(Image.open(page_img).convert("RGB"), dtype=np.uint8)
            mask_arr = np.asarray(Image.open(page_mask).convert("L"), dtype=np.uint8)
            fixture_side = next(
                (side for identity, side in SUB512_FIXTURE_SPECS if args.sub512_fixtures and identity == page_dir.name),
                page_arr.shape[0],
            )
            if args.sub512_fixtures:
                page_arr = center_crop(page_arr, fixture_side)
                mask_arr = center_crop(mask_arr, fixture_side)
            dyn_out, stat_out, mask_hw = run_dynamic_native_vs_static_512(
                dyn_sess, stat_sess, page_arr, mask_arr,
            )
        except Exception as error:
            failure = {"identity": page_dir.name, "stage": "model_inference", "error": repr(error)}
            failures.append(failure)
            print(f"EMIT FAILURE: {json.dumps(failure, sort_keys=True)}", file=sys.stderr)
            continue

        page_out = out_dir / page_dir.name
        temporary_out = out_dir / f".{page_dir.name}.tmp"
        try:
            if temporary_out.exists():
                shutil.rmtree(temporary_out)
            temporary_out.mkdir()
            # Write a complete sample privately, then rename it into visibility.
            write_argb_bin(temporary_out / "dynamic_out.bin", dyn_out)
            write_argb_bin(temporary_out / "static_out.bin", stat_out)
            write_mask_bin(temporary_out / "mask.bin", mask_hw)
            Image.fromarray(dyn_out).save(temporary_out / "dynamic_out.png")
            Image.fromarray(stat_out).save(temporary_out / "static_out.png")
            Image.fromarray((mask_hw * 255).astype(np.uint8), mode="L").save(temporary_out / "mask.png")
            emitted_manifest = dict(manifest_data)
            if args.sub512_fixtures:
                emitted_manifest.update(
                    {
                        "fixture_identity": f"{page_dir.name}__center_crop_{fixture_side}",
                        "fixture_kind": "deterministic_transformed_real_corpus_center_crop",
                        "fixture_side": fixture_side,
                        "fixture_source_identity": page_dir.name,
                        "fixture_transform": f"center_crop_from_512_to_{fixture_side}_no_resize",
                        "dynamic_route": f"native_{fixture_side}x{fixture_side}",
                        "static_route": "production_center_background_pad_zero_mask_512_crop_back",
                        "synthetic_color_used": False,
                    }
                )
            (temporary_out / "manifest.json").write_text(
                json.dumps(emitted_manifest, indent=2) + "\n", encoding="utf-8"
            )
            temporary_out.rename(page_out)
        except Exception as error:
            failure = {"identity": page_dir.name, "stage": "output_write", "error": repr(error)}
            failures.append(failure)
            print(f"EMIT FAILURE: {json.dumps(failure, sort_keys=True)}", file=sys.stderr)
            shutil.rmtree(temporary_out, ignore_errors=True)
            shutil.rmtree(page_out, ignore_errors=True)
            continue
        written += 1
        print(f"  {page_dir.name}: wrote dynamic/static/mask .bin + .png")

    report = {
        "emitter": "emit_corpus_outputs.py",
        "corpus": generation_source,
        "expected_count": len(pages),
        "emitted_count": written,
        "failure_count": len(failures),
        "identities": [page_dir.name for page_dir, _ in pages if (out_dir / page_dir.name).is_dir()],
        "failures": failures,
        "fixture_mode": "sub512_transformed_real_corpus" if args.sub512_fixtures else "native_corpus",
        "fixture_sizes": [side for _, side in SUB512_FIXTURE_SPECS] if args.sub512_fixtures else [],
    }
    try:
        (out_dir / "emission_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except OSError as error:
        print(f"ERROR: could not write emission_report.json: {error}", file=sys.stderr)
        return 1
    success = not failures and written == len(pages)
    status = "Done" if success else "FAILED"
    print(f"\n{status}. {written}/{len(pages)} sample(s) emitted to {out_dir}; failures={len(failures)}")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
