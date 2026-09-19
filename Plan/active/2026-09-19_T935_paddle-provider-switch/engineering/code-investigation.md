# PaddleOCR provider selector — code investigation

Investigation date: 2026-09-19  
Scope: read-only review of the current PaddleOCR v6 detector/recognizer, ONNX Runtime provider selection, routing, preferences/UI, lifecycle, and tests. No source files were changed.

## Decision-relevant findings

1. **The selector should be PaddleOCR-specific, not a rewrite of the global hardware preference.** The existing `translation_hardware_accelerator` controls all on-device ONNX work and has no GPU value. Reusing it would change page detection, panel detection, segmentation, inpainting, and other OCR behavior together with this experiment.
2. **The selected provider must be applied to both PaddleOCR v6 recognizer and the PaddleOCR line-splitting detector in the Paddle OCR path.** A mixed detector/recognizer route would make measurements and failures ambiguous. General page detection and the mask-only Paddle detector used with non-Paddle OCR should retain their existing route.
3. **An explicit Paddle session API is safer than adding another boolean to `createSessionWithFallback`.** The explicit path must not consult `HardwareDiscoveryEngine.activeRoute`, `resolveRoute()`, or `ModelRoutingEngine.isAcceleratorAttemptAllowed()` to decide whether a user-requested temporary provider is attempted.
4. **CPU is the safe default and the honest fallback.** The requested, registered, executed, and fallback/error states must be separate. A QNN registration or first-run failure must never be reported as QNN merely because QNN was requested.
5. **Changing the preference must rebuild the recognition engine.** `EngineLane.ensureEnginesBuiltFor` currently does not include hardware/provider selection, so changing a selector while a reader engine is alive would otherwise leave old sessions in use.

## Evidence classification

| Finding | Evidence | Classification |
|---|---|---|
| `PaddleOcrV6SmallEngine` and `PaddleOcrV6DetEngine` both call `OnnxRuntimeProvider.createSessionWithFallback(..., useAccelerator = true)` and have no provider argument | Both engine source files | **VERIFIED** |
| Paddle engine `executionProviderLabel` is populated by the session-open sink, not by a successful `run` | `OnnxRuntimeProvider.openSessionWithHonestLabel`; both Paddle engines | **VERIFIED** |
| Paddle engines do not call `ModelRoutingEngine.recordSuccessfulInference` after `session.run` | Both `recognizeWithConf`/`detectLines` implementations; only unrelated segmenter usage found | **VERIFIED** |
| `createSessionWithFallback` reads `HardwareDiscoveryEngine.activeRoute` before asking the options builder to resolve a route | `OnnxRuntimeProvider.createSessionWithFallback` and `createSessionOptionsWithRegistration` | **VERIFIED** |
| A normal Paddle session can be forced to CPU before the route used to build QNN options is resolved | Early `!canUseAccelerator` branch in the same method | **STRONG INFERENCE** (the exact branch depends on process route/status state) |
| The current generic function has no per-Paddle provider preference to resolve | Call sites and `TranslationPreferences` | **VERIFIED** |
| Global selector choices are `AUTO`, `QUALCOMM_NPU`, and `CPU_XNNPACK`; GPU is absent | `TranslationPreferences.TranslationHardwareAccelerator`, `SettingsTranslationScreen` | **VERIFIED** |
| QNN HTP is likely incompatible with the current Paddle models | Paddle assets/JSON contain `t_f32`; ORT documentation says HTP supports quantized models | **STRONG INFERENCE**; strict device create+run remains **UNKNOWN** |
| The connected SM8650 mapping is `soc_model=57`, `htp_arch=75` | `DeviceCapability` mapping | **VERIFIED** as source mapping; actual device acceptance is **UNKNOWN** |
| Presence of QNN `.so` files in merged APK native libs proves runtime availability/execution | Build artifacts only show packaging | **CONTRADICTION**; packaging is not execution proof |

## Current Paddle data flow

```mermaid
flowchart TD
    A[RoiPageRecognitionEngine.initialize] --> B[PaddleOcrV6SmallEngine.initialize]
    A --> C[PaddleOcrV6DetEngine.initialize]
    B --> D[createSessionWithFallback(useAccelerator=true)]
    C --> D2[createSessionWithFallback(useAccelerator=true)]
    D --> E[read HardwareDiscoveryEngine.activeRoute]
    D2 --> E2[read HardwareDiscoveryEngine.activeRoute]
    E --> F[ModelRoutingEngine gate]
    E2 --> F2[ModelRoutingEngine gate]
    F -->|allowed| G[options builder calls resolveRoute separately]
    F2 -->|allowed| G2[options builder calls resolveRoute separately]
    F -->|denied| H[CPU session, no QNN attempt]
    F2 -->|denied| H2[CPU session, no QNN attempt]
    G --> I[session.open; provider label sink]
    G2 --> I2[session.open; provider label sink]
    I --> J[session.run; no execution-proof recording]
    I2 --> J2[session.run; no execution-proof recording]
```

`RoiPageRecognitionEngine` creates the Paddle recognizer when the selected OCR model is `PADDLEOCR_V6_SMALL`, and creates the Paddle detector for vertical-line splitting. It can also create a Paddle detector for an inpainting mask when the OCR model is not Paddle. Those are different scopes and must not accidentally share the temporary selector.

## The automatic-route bug and why the preference can appear ignored

`OnnxRuntimeProvider.createSessionWithFallback` currently does approximately:

```kotlin
val route = HardwareDiscoveryEngine.activeRoute
val canUseAccelerator = useAccelerator &&
    ModelRoutingEngine.isAcceleratorAttemptAllowed(modelName, route)
if (useAccelerator && !canUseAccelerator) {
    // open CPU immediately
}
// otherwise createSessionOptionsWithRegistration(useAccelerator = canUseAccelerator)
```

The options builder later calls `HardwareDiscoveryEngine.resolveRoute()` when `useAccelerator` is true. `activeRoute` is initialized to `CPU_XNNPACK`; it is not the same operation as resolving the route. Consequently:

- the gate, model status lookup, and any failure accounting can use the stale/default CPU route;
- the options builder can independently resolve a QNN route after the gate has passed;
- a model status or circuit-breaker decision can take the early CPU branch before QNN options are ever built;
- the `route` used to decide the attempt is not a single consistent snapshot of the route used to register the session.

This is a **VERIFIED code defect** in the generic automatic path. The exact user-visible result is state-dependent, therefore the claim that every normal OCR run skips route resolution would be too strong. The safe statement is: **normal Paddle OCR has no provider preference, and `createSessionWithFallback` can resolve/route-gate inconsistently; an early CPU path can make a requested accelerator appear ignored.** A generic repair should resolve once and pass the resolved route through the entire operation, but that does not provide the temporary explicit selector needed here.

## Provider options and backend requirements already in the code

### CPU

- CPU/XNNPACK is the existing automatic CPU path.
- `useAccelerator = false` builds the CPU/XNNPACK registration path.
- A failed accelerator registration/session creation is already labelled CPU by `openSessionWithHonestLabel` when it falls back.

### QNN GPU

The current registration builder uses:

```text
backend_type = gpu
session.disable_cpu_ep_fallback = 1
```

The strict setting is important: if QNN does not support the graph, ORT must fail instead of silently partitioning unsupported work to CPU. ORT's QNN documentation states that the GPU backend can handle float32/float16, so this is the more plausible first experiment for the current Paddle assets; actual session creation and inference are still **UNKNOWN** until run on the target device.

### QNN HTP/NPU

The current HTP builder/probe uses:

```text
backend_type = htp
session.disable_cpu_ep_fallback = 1
```

It can additionally provide `htp_performance_mode`, `soc_model`, and `htp_arch`. The existing probe tries generic HTP first, then known SoC/architecture combinations. For the source mapping of the target SM8650, the values are:

```text
soc_model = 57
htp_arch  = 75
```

Context caching is supported through session options such as `ep.context_enable=1` and `ep.context_file_path`; it should not be enabled for this temporary selector unless cache ownership/invalidation is designed explicitly. HTP support for the current Paddle model is **UNKNOWN**: the asset JSON contains `t_f32`, while the official ORT QNN documentation says HTP requires quantized models. Do not present HTP as available until strict session creation and a real inference succeed.

Official requirements used for this assessment:

- [ONNX Runtime QNN Execution Provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html): backend selection, HTP quantization constraints, fixed-shape requirement, strict CPU fallback, context cache, and SSR behavior.
- [ONNX Runtime Android build guidance](https://onnxruntime.ai/docs/build/android.html): QNN Android/Snapdragon packaging/build requirements.
- [Official QNN provider source](https://github.com/microsoft/onnxruntime/blob/main/onnxruntime/core/providers/qnn/qnn_execution_provider.cc): accepted backend types and default backend libraries.

The APK build artifacts include QNN libraries for arm64 variants, but that is only **VERIFIED packaging evidence**, not proof that the device can load, partition, or execute the Paddle graph.

## Provider provenance and fallback

The current `executionProviderLabel` is useful as a registration/session label, but it is not an execution proof. `openSessionWithHonestLabel` sends the label only after session creation; neither Paddle engine records a successful run. The implementation should carry at least:

| Field | Meaning |
|---|---|
| `requestedProvider` | User selection: CPU, QNN GPU, or QNN HTP |
| `registeredProvider` | EP that successfully opened the session, or CPU after fallback |
| `executedProvider` | Set to the registered accelerator only after a real successful `run`; CPU otherwise |
| `fallbackReason` | Registration, session-create, first-run, unsupported-op, SSR, or other error |
| `availability` | Available, CPU fallback, or unavailable/untested, with diagnostic detail |

For a strict accelerator request, the recommended behavior is:

1. Attempt the requested QNN backend with CPU EP fallback disabled.
2. If registration/session creation fails, close that attempt, create CPU, and report `requested=qnn_*`, `registered=cpu`, `executed=cpu`, plus the reason.
3. If the first real accelerator `run` fails, recreate a CPU session under the existing native lock and report the same honest fallback. A later-run failure should follow the same guarded recreation policy, or explicitly report unavailable if runtime fallback is not implemented.
4. Never infer accelerator execution from the presence of QNN libraries, a requested value, or a successful `OrtSession` constructor alone.

The explicit experiment path should not trip the global `HardwareDiscoveryEngine` circuit breaker or mutate global automatic model-routing state. A temporary Paddle failure should not demote unrelated page detection or other OCR engines.

## Scope of the selector

| Component | Temporary Paddle selector? | Reason |
|---|---:|---|
| Paddle v6 recognizer | Yes | The feature is a Paddle OCR provider experiment. |
| Paddle v6 line-splitting detector | Yes, same selected provider | Detector and recognizer must be measured/fail together. |
| General `OnnxPageTextDetector` | No | It is shared normal reader infrastructure and already uses global routing. |
| Panel detector / bubble segmenter | No | Avoid changing unrelated inference. |
| Paddle detector used only for non-Paddle inpainting mask | No (retain existing route) | Otherwise selecting Paddle hardware would silently affect MLKit/MangaOCR flows. |
| AOT/other ONNX engines | No | Preserve normal manga behavior. |

Snapshot the selection once when `RoiPageRecognitionEngine` builds the Paddle OCR pair and pass the same value to both Paddle engines. Do not read a mutable preference independently during each run.

## Persistence, UI, and lifecycle evidence

- `TranslationPreferences` has `TranslationHardwareAccelerator { AUTO, QUALCOMM_NPU, NNAPI, CPU_XNNPACK }`, default `AUTO`; this is a global setting and does not expose GPU. **VERIFIED.**
- Add a separate Paddle-specific preference, default CPU. A temporary/debug-only translation-settings row is the least surprising UI. If shipped outside debug, label it explicitly as a temporary PaddleOCR v6 provider selector and explain CPU fallback.
- `SettingsTranslationScreen` currently observes the global setting directly and does not cause a recognition-engine rebuild for a new provider preference.
- `EngineLane.ensureEnginesBuiltFor` rebuilds on OCR model/language/inpainting/reading-order changes, but not provider selection. **VERIFIED.**
- Include the Paddle provider in that rebuild key, or synchronously call the existing `closeEngines()` when the preference changes. Rebuild before the next native inference; do not swap sessions while `RoiPageRecognitionEngine.nativeGuard` is held by another run.
- `RoiPageRecognitionEngine.close()`/`freeNativeSessions()` already provide the safe teardown boundary. The provider snapshot should be released with the recognition engine.

## Focused verification to add before implementation is considered complete

1. Pure mapping tests: CPU, QNN GPU, and QNN HTP map to the exact backend/options above; no implicit `resolveRoute()` call.
2. Explicit-path tests: no `activeRoute` or `ModelRoutingEngine.isAcceleratorAttemptAllowed` gate is consulted for a user-selected Paddle provider; global circuit breaker is untouched.
3. Failure/provenance tests: QNN registration/session-create failure and first-run failure produce CPU fallback with requested/registered/executed fields and a reason.
4. Pairing tests: Paddle recognizer and Paddle line detector receive the same provider; non-Paddle inpainting mask remains on the old route.
5. Lifecycle tests: changing the provider invalidates/rebuilds `EngineLane` sessions; unchanged settings reuse the engine.
6. Device matrix on the connected SM8650: CPU baseline, QNN GPU real inference, QNN HTP strict create+real inference, and repeat-run/SSR behavior. Report each as confirmed or untested; do not infer from packaging.

## Bottom line

The smallest safe design is a CPU-default Paddle-only preference backed by an explicit strict-provider session factory, applied symmetrically to Paddle v6 recognition and its line detector, with guarded engine recreation and honest post-run provenance. The existing automatic route function should be repaired separately for its stale `activeRoute` snapshot, but it should not be the mechanism for this temporary selector.
