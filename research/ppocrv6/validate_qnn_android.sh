#!/usr/bin/env bash
set -euo pipefail

# Android/QNN HTP validation harness for PP-OCRv6. This is opt-in: it never
# silently falls back to CPU and refuses to run until the caller supplies an
# Android ONNX Runtime test runner and QNN libraries.
#
# Required host tools: adb, python3, onnxruntime (make_dynamic_shape_fixed).
# Required environment:
#   ORT_TEST_RUNNER  executable Android onnx_test_runner binary
#   QNN_LIB_DIR      QAIRT arm64-v8a/lib directory (must contain libQnnHtp.so)
# Optional: DEVICE_SERIAL, REMOTE_DIR, MODEL_ROOT, OUT_DIR, QNN_BACKEND,
#           QNN_SOC_MODEL, QNN_HTP_ARCH, ORT_LIB_DIR (if runner is dynamic).
#
# Inputs are deliberately not fabricated. Before RUN=1, put representative
# protobuf tensors under each generated test_data_set_0 directory.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_ROOT="${MODEL_ROOT:-${ROOT}/app/src/main/assets/models/ocr/paddle-v6-small}"
OUT_DIR="${OUT_DIR:-${ROOT}/research/qnn_android_validation}"
REMOTE_DIR="${REMOTE_DIR:-/data/local/tmp/ppocr-qnn}"
QNN_BACKEND="${QNN_BACKEND:-${REMOTE_DIR}/libQnnHtp.so}"
SERIAL_ARGS=()
if [[ -n "${DEVICE_SERIAL:-}" ]]; then SERIAL_ARGS=(-s "${DEVICE_SERIAL}"); fi

die() { echo "ERROR: $*" >&2; exit 2; }
command -v adb >/dev/null || die "adb is required"
command -v python3 >/dev/null || die "python3 is required"
[[ -n "${ORT_TEST_RUNNER:-}" && -x "${ORT_TEST_RUNNER}" ]] || die "set ORT_TEST_RUNNER to an executable Android onnx_test_runner"
[[ -n "${QNN_LIB_DIR:-}" && -d "${QNN_LIB_DIR}" ]] || die "set QNN_LIB_DIR to a QAIRT arm64-v8a/lib directory"
[[ -f "${QNN_LIB_DIR}/libQnnHtp.so" ]] || die "missing ${QNN_LIB_DIR}/libQnnHtp.so"
[[ -f "${MODEL_ROOT}/det/inference.onnx" ]] || die "missing PP-OCRv6 DET model"
[[ -f "${MODEL_ROOT}/inference.onnx" ]] || die "missing PP-OCRv6 REC model"

mkdir -p "${OUT_DIR}/fixed"
if [[ "${CLEAN:-0}" == 1 ]]; then rm -rf "${OUT_DIR}/fixtures"; fi
mkdir -p "${OUT_DIR}/fixtures"
FIXER=(python3 -m onnxruntime.tools.make_dynamic_shape_fixed)

# DET dynamic N/H/W: PP-OCRv6 config min/opt resolutions.
"${FIXER[@]}" --input "${MODEL_ROOT}/det/inference.onnx" --output "${OUT_DIR}/fixed/det_b1_32x32.onnx" --input_name x --input_shape 1,3,32,32
"${FIXER[@]}" --input "${MODEL_ROOT}/det/inference.onnx" --output "${OUT_DIR}/fixed/det_b1_640x640.onnx" --input_name x --input_shape 1,3,640,640
"${FIXER[@]}" --input "${MODEL_ROOT}/det/inference.onnx" --output "${OUT_DIR}/fixed/det_b1_736x736.onnx" --input_name x --input_shape 1,3,736,736

# REC dynamic batch/width: configured min/opt/max and a batch-8 smoke shape.
for shape_name in b1_w160 b1_w320 b1_w3200 b8_w320 b8_w3200; do
  case "${shape_name}" in
    b1_w160) shape=1,3,48,160 ;;
    b1_w320) shape=1,3,48,320 ;;
    b1_w3200) shape=1,3,48,3200 ;;
    b8_w320) shape=8,3,48,320 ;;
    b8_w3200) shape=8,3,48,3200 ;;
  esac
  "${FIXER[@]}" --input "${MODEL_ROOT}/inference.onnx" --output "${OUT_DIR}/fixed/rec_${shape_name}.onnx" --input_name x --input_shape "${shape}"
done

# Standard onnx_test_runner layout. Model files are staged; input_*.pb files
# must be supplied by the caller in each test_data_set_0 directory.
for model in "${OUT_DIR}"/fixed/*.onnx; do
  base="$(basename "${model}" .onnx)"
  mkdir -p "${OUT_DIR}/fixtures/${base}/test_data_set_0"
  cp "${model}" "${OUT_DIR}/fixtures/${base}/model.onnx"
done

adb "${SERIAL_ARGS[@]}" wait-for-device
adb "${SERIAL_ARGS[@]}" shell rm -rf "${REMOTE_DIR}"
adb "${SERIAL_ARGS[@]}" shell mkdir -p "${REMOTE_DIR}/fixtures" "${REMOTE_DIR}/bin"
adb "${SERIAL_ARGS[@]}" push "${QNN_LIB_DIR}/." "${REMOTE_DIR}/" >/dev/null
if [[ -n "${ORT_LIB_DIR:-}" ]]; then
  adb "${SERIAL_ARGS[@]}" push "${ORT_LIB_DIR}/." "${REMOTE_DIR}/" >/dev/null
fi
adb "${SERIAL_ARGS[@]}" push "${ORT_TEST_RUNNER}" "${REMOTE_DIR}/bin/onnx_test_runner" >/dev/null
adb "${SERIAL_ARGS[@]}" push "${OUT_DIR}/fixtures/." "${REMOTE_DIR}/fixtures/" >/dev/null
adb "${SERIAL_ARGS[@]}" shell chmod 755 "${REMOTE_DIR}/bin/onnx_test_runner"

DEVICE="${SERIAL_ARGS[*]}"
echo "Device:"; adb "${SERIAL_ARGS[@]}" shell getprop ro.product.model; adb "${SERIAL_ARGS[@]}" shell getprop ro.board.platform
echo "QNN backend: ${QNN_BACKEND}"
echo "Models pushed under ${REMOTE_DIR}/fixtures"
echo
echo "Exact no-fallback commands (session creation proves HTP partitioning):"
for model in "${OUT_DIR}"/fixed/*.onnx; do
  base="$(basename "${model}" .onnx)"
  echo "adb ${DEVICE} shell ${REMOTE_DIR}/bin/onnx_test_runner -e qnn -n ${base} -C 'session.disable_cpu_ep_fallback|1' -i 'backend_path|${QNN_BACKEND} profiling_level|detailed profiling_file_path|${REMOTE_DIR}/${base}.csv' ${REMOTE_DIR}/fixtures"
done

if [[ "${RUN:-0}" != 1 ]]; then
  echo
  echo "Dry run complete. Set RUN=1 after supplying input_*.pb fixtures."
  exit 0
fi

for model in "${OUT_DIR}"/fixed/*.onnx; do
  base="$(basename "${model}" .onnx)"
  adb "${SERIAL_ARGS[@]}" shell "${REMOTE_DIR}/bin/onnx_test_runner -e qnn -n ${base} -C 'session.disable_cpu_ep_fallback|1' -i 'backend_path|${QNN_BACKEND} profiling_level|detailed profiling_file_path|${REMOTE_DIR}/${base}.csv' ${REMOTE_DIR}/fixtures"
done
