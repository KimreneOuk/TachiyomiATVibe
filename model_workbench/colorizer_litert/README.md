# Colorizer LiteRT workbench

This workbench contains the pinned conversion scripts and setup needed to continue Sana Coloring v2 and MCV2 conversion on a clean clone. Model weights and converted graphs are downloaded/generated locally and are intentionally excluded from Git.

## Clone and prepare

```bash
git clone --branch workbench/colorizer-litert-int8 https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe/model_workbench/colorizer_litert
```

Docker Desktop is required. From Git Bash, build the pinned Sana converter image and fetch the public model inputs:

```bash
ROOT_WIN="$(cygpath -m "$PWD")"
MSYS_NO_PATHCONV=1 docker build -t sana-converter -f "$ROOT_WIN/docker/Dockerfile" "$ROOT_WIN"
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/fetch_models.py sana_color_v2 sana_base
```

This downloads to separate `download/coloring_repo_v2/` and `download/sana_base/` directories. The downloader checks required files, pinned byte sizes, and known SHA-256 digests. The Sana source and base model are public Hugging Face repositories pinned in `sources.json`; no login should be needed. Add `mcv2_onnx` to the fetch command for the separate MCV2 ONNX path.

To prepare its ONNX CPU conversion tools, build the optional image:

```bash
MSYS_NO_PATHCONV=1 docker build -t colorizer-litert-mcv2 -f "$ROOT_WIN/docker/Dockerfile.ort" "$ROOT_WIN"
```

The MCV2 source lands at `download/onnx_color/manga-colorize-fp16.onnx`. The current converter creates FP32 ONNX or dynamic-INT8 ONNX; static calibration is memory-heavy and has failed on the previous machine. For example:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" colorizer-litert-mcv2 python -I /work/scripts/mcv2/convert_colorizer_fp32.py --source /work/download/onnx_color/manga-colorize-fp16.onnx --output /work/download/onnx_color/manga_colorize_fp32.onnx
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" colorizer-litert-mcv2 python -I /work/scripts/mcv2/convert_colorizer_int8.py --mode dynamic --source /work/download/onnx_color/manga-colorize-fp16.onnx --output /work/download/onnx_color/manga_colorize_int8_dynamic.onnx
```

This is ONNX CPU conversion only; it is not yet the required MCV2 LiteRT GPU pipeline.

## Resume Sana conversion

Run one command at a time and retain the console output and `export_progress.log`:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I -m py_compile /work/scripts/common.py /work/scripts/step1_wrapper_check.py /work/scripts/step2a_export_dit.py /work/scripts/step2b_export_vae.py /work/scripts/step3_refs.py /work/scripts/step4_validate.py /work/scripts/assemble_sana_contract.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/probe_named_signature.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/step1_wrapper_check.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/step2a_export_dit.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/step2b_export_vae.py both
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/step3_refs.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/step4_validate.py
MSYS_NO_PATHCONV=1 docker run --rm -v "$ROOT_WIN:/work" sana-converter python -I /work/scripts/assemble_sana_contract.py --signatures-dir /work/exported/signatures --out /work/exported/graph_signatures.json
```

The prior machine had 15.6 GB RAM and a 7.58 GiB Docker memory limit; large Sana quantization and MCV2 static calibration were OOM-killed there. Use a larger-memory machine for conversion. Recheck existing files before resuming; the old `tmp_dit_fp32.tflite` and `dit_int8_weight_only.tflite` use positional names and are not suitable for the Android name-bound contract. Do not delete or overwrite other data while cleaning stale generated exports.

## Acceptance gate

The selected target is INT8 weights with floating-point activations and float32 graph I/O. CPU interpreter parity is only a graph validation step. Before wiring or selecting any artifact in the app, verify actual `CompiledModel` GPU compilation and execution on Android, output range/dimensions, same-input quality, selected backend/precision, and latency. No current Sana or MCV2 LiteRT artifact has passed that device gate. Keep CPU/ONNX results distinct from GPU results and do not publish model binaries externally without approval.

See [HANDOFF.md](HANDOFF.md) for task state, known blockers, and app integration scope; [restructure_spec.md](restructure_spec.md) for Sana graph details; and [sources.json](sources.json) for model provenance.
