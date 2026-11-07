# Desktop agent handoff: LiteRT colorizer conversion

## Goal
Continue both manga colorizers toward Android LiteRT GPU execution. User-approved precision is **INT8 weights with float activations and float32 graph I/O**. Conversion and CPU interpreter success do not prove GPU support. Do not claim device GPU success until the app creates and runs the graph on LiteRT GPU.

## Start on a fresh clone

```bash
git clone --branch workbench/colorizer-litert-int8 https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe/model_workbench/colorizer_litert
ROOT_WIN="$(cygpath -m "$PWD")"
MSYS_NO_PATHCONV=1 docker build -t sana-converter -f "$ROOT_WIN/docker/Dockerfile" "$ROOT_WIN"
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/fetch_models.py sana_color_v2 sana_base mcv2_onnx
```

The fetcher uses pinned public Hub repositories in `sources.json`, downloads each repository into its own `download/` directory, verifies required files, verifies known sizes and SHA-256 values, and never executes downloaded code. Downloads and generated artifacts are ignored by Git. Use `python -I`; do not run an interpreter or build tool from a downloaded model directory. The model repos are public; if Hub access fails, report the error rather than substituting another revision.

## Current environment and progress

- Workbench scripts and this context are being packaged under `model_workbench/colorizer_litert/`; only this directory is intended for the task branch. Uncommitted app changes on `ui-experimentation` are not included.
- The prior machine had 15.6 GB physical RAM and Docker limited to about 7.58 GiB. Large Sana direct quantization and MCV2 static ONNX calibration were killed with exit 137. Use the stronger desktop for model conversion and validation.
- Observed working environment: Linux container, Python 3.11, Torch 2.14.1, LiteRT Torch 0.10.0, LiteRT runtime 2.3.0, TorchAO 0.18.0, Diffusers 0.42.1, Transformers 5.22.1. `requirements-sana.lock.txt` and pinned base image digest capture the observed Sana environment.
- Sana v2 wrapper parity passed exactly (`max_abs_diff=0`). A 2.375 GB DiT FP32 TFLite and a 605 MB weight-only INT8 graph were produced, but both are from the old positional path; the INT8 graph has generic input names and is **not app-contract-ready**. No VAE graphs or device GPU test have been completed.
- In this workbench, the Sana exporters now use named dictionary inputs; signature inspection maps raw interpreter tensor names to semantic `serving_default` keys and normalizes dtypes. The contract assembler matches by semantic name. These latest changes were copied but have **not been verified with py_compile or a full export**.
- MCV2 has a separate dynamic INT8 ONNX CPU candidate (~31.6 MB, ~39–42 dB PSNR vs FP32 on the host; about 7x slower on host x86). It does not satisfy LiteRT GPU. Static calibration OOMed. There is no ONNX→LiteRT GPU engine yet.
- `step4_validate.py` currently validates FP16 graph outputs via CPU LiteRT interpreter and uses synthetic plus model-result pages. It does not establish Android GPU support or comprehensively validate the new INT8 candidates.

## First actions

1. Check available RAM/disk; read `README.md`, `restructure_spec.md`, `sources.json`, and current scripts.
2. Run syntax checks and lightweight gates:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I -m compileall -q /work/scripts
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/probe_named_signature.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/step1_wrapper_check.py
```

3. Fix any failures before expensive conversion. Export the named DiT FP32 baseline, then FP16 and channelwise weight-only INT8; check semantic input/output names, exact shape and float32 boundaries, artifact size/hash, and parity. Export VAE FP32 baselines and FP16/INT8-weight candidates next; reject INT8 VAE if quality or GPU support is inferior.
4. Extend the full pipeline validation to compare FP16 and INT8-weight graph sets on the same inputs and write a machine-readable report. Do not reuse the positional FP32/INT8 files currently present on the previous machine as contract-ready artifacts.
5. For MCV2, inspect model ops and dynamic shape constraints. First test FP32 ONNX→LiteRT conversion at fixed dimensions and compare against ONNX using identical app preprocessing. Only then explore weight-only INT8 and GPU compatibility. Preserve aspect ratio and line-art merge behavior.
6. After artifact/quality gates, update Android integration in the main repo: Sana should attempt verified INT8-weight GPU graphs before FP16 GPU fallback; MCV2 needs a distinct LiteRT GPU `PageColorizationEngine`, with ONNX CPU clearly identified as fallback only. Add exact sizes/hashes/provisioning only for verified graphs.
7. Build/test, install on the authorized device, and record the actual selected backend/precision, GPU compile/run result, output range/dimensions, same-input quality, and latency. A CPU run alone does not pass.

## Provenance

- Sana coloring source: `masterofaudio2077/sana-manga-coloring-v2`, revision `4d69287bf27cea4f8f20262a0830bfe55183e32e`; EMA step 15000.
- Base transformer + VAE: `Efficient-Large-Model/Sana_600M_1024px_diffusers`, revision `28f3af7689de15f3883d5863059a2fca0aa9b829`.
- Separate MCV2 ONNX: `Faridzar/manga-colorization-v2-onnx`, revision `5515e06d31b08ffd107af686cba5e98e95e8d4cf`.
- Do not commit downloaded weights/generated `.tflite` or `.onnx`, and do not publish model binaries to an external release without user approval.
