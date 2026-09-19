# Paddle OCR provider switch (temporary device experiment)

## User request

Expose a temporary in-app setting that lets the user choose CPU, Qualcomm QNN GPU, or Qualcomm QNN HTP/NPU for PaddleOCR v6, then install it on the connected Snapdragon device and verify the actual provider used. The setting must never claim an accelerator if ONNX Runtime actually serves the session on CPU.

## Scope

- Inspect the current PaddleOCR v6 session/provider path, settings UI, persistence, and diagnostics.
- Verify current Android/ONNX Runtime/Qualcomm requirements from primary documentation.
- Add a temporary provider selector for the OCR path, preserving CPU as the safe default.
- Attempt GPU and HTP/NPU sessions explicitly; expose honest unavailable/error/fallback state.
- Add focused tests for preference mapping, provider selection, and provenance.
- Build/install and run one small real-device check per selectable provider when the device is reachable.

## Non-goals

- No broad production rollout of accelerator routing.
- No automatic batch-size promotion.
- No destructive uninstall or data reset.
- No claim of GPU/NPU support based only on host or session-registration success.

## Acceptance criteria

1. CPU remains the default and existing translation behavior remains available.
2. Settings show CPU, Qualcomm GPU, and Qualcomm HTP/NPU as explicit temporary choices.
3. The selected route is passed to the PaddleOCR v6 detector/recognizer sessions.
4. Logs expose requested route, actual registered/executed route, and any fallback/error.
5. If an accelerator cannot create/execute the session, the app reports that route as unavailable or falls back only with an explicit CPU label.
6. Focused tests pass; device results are recorded as CONFIRMED or UNTESTED, never inferred.

## Current evidence

- The connected device is a Snapdragon/SM8650 Android device at `192.168.100.223:38875`.
- The current normal run reports `model=paddle_ocr`, `registeredProvider=cpu`, `ocr=cpu`, and `batched=false`.
- The worktree contains unrelated existing handoff/untracked files and Japanese PaddleOCR catalog edits; preserve them.

## Primary references to verify

- ONNX Runtime Android execution-provider documentation.
- ONNX Runtime QNN execution-provider documentation.
- Qualcomm AI Engine Direct/QNN Android requirements for GPU and HTP backends.
