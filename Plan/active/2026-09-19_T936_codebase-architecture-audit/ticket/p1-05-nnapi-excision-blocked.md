# Ticket P1-05: NNAPI excision — BLOCKED (audit premise contradicted by artifact inspection)

**Phase:** Pulled OUT of Phase 1 | **Status:** Blocked pending runtime evidence | **Type:** Investigation + deferred deletion

## Why this is blocked

The audit (DIRECTOR_REPORT §3.1 and the trace spec §"Corrected Evidence") claims the NNAPI
subsystem is dead because *"the onnxruntime-android-qnn artifact does not compile the NNAPI
execution provider"*. **Direct inspection of the pinned artifact contradicts this:**

Pinned dependency (`gradle/libs.versions.toml:42`):
`com.microsoft.onnxruntime:onnxruntime-android-qnn:1.28.0`

Inspection of the exact cached AAR (2026-09-19):

```
onnxruntime-android-qnn-1.28.0.aar
├── classes.jar                     → contains ai/onnxruntime/providers/NNAPIFlags.class  (Java API present)
└── jni/arm64-v8a/libonnxruntime.so → contains "NnapiExecutionProvider" provider string   (EP compiled in)
                                        contains "Xnnpack", "Qnn"
```

The 1.27.0 AAR in the Gradle cache shows the same. If the NNAPI EP were absent, the string
would not be embedded in the provider registration table.

## Why blind excision would be dangerous

1. **NNAPI may be the only accelerated route on non-Qualcomm devices.**
   `HardwareDiscoveryEngine.resolveRoute()` AUTO order is QNN HTP → NNAPI → CPU
   (`HardwareDiscoveryEngine.kt:175-177`). `probeNnapi()` (`:271-289`) performs a strict
   end-to-end session probe — if it succeeds on e.g. a MediaTek device, the NNAPI route
   latches and is live. Deleting the route regresses such devices to CPU XNNPACK.
2. **The strict-NNAPI inpainting candidate may initialize.** `AOTInpainting.kt:232` gates on
   `OrtProvider.NNAPI in providers` — a runtime check against exactly the EP we just
   confirmed is compiled in. On a device where `getProviders()` includes NNAPI, the
   `fixed_nnapi` session init at `AOTInpainting.kt:219-278` succeeds.
3. The contrary "proof" is a stale UI comment (`SettingsTranslationScreen.kt:101-102`),
   which is an assertion, not runtime evidence.

This is the second false "dead code" claim in this exact domain (`aot-512.onnx` was the
first). Deletion must wait for on-device truth.

## Required evidence before any excision ticket is opened

One debug run per representative device (minimum: one Snapdragon device; ideally one
non-Qualcomm device), capturing logcat:

```
[hardware_discovery] ... probe OK/failed ...      ← does the NNAPI probe pass?
[hardware_discovery] Latched NNAPI route          ← does AUTO ever latch NNAPI?
[inpaint] route=nnapi init=ok ...                 ← does the fixed NNAPI session initialize?
```

or a one-shot debug dump of `OrtEnvironment.getProviders()` added to diagnostics.

## Decision for the Director (recommendation: Option A)

- **Option A (recommended):** Defer NNAPI excision out of Phase 1. Land a small diagnostic
  (log `getProviders()` once at ORT init) in Phase 1 or 2, gather device evidence, then
  excise in a later phase only if confirmed dead. Zero regression risk, small delay.
- **Option B:** Excise now per the audit (delete `NnapiCapabilityGate.kt`, `NnapiHealthMonitor.kt`,
  `StrictNnapiFallback.kt`, `CheckNnapi.java`, AOTInpainting NNAPI branches + 3 test files,
  plus the wider plumbing: `HardwareRoute.NNAPI`, `probeNnapi()`,
  `TranslationHardwareAccelerator.NNAPI`, OnnxRuntimeProvider NNAPI registration,
  QnnDiagnostics NNAPI benchmark). Accepts the risk of silently regressing acceleration on
  any device where the EP is live.

## Full reference inventory (for whichever ticket proceeds later)

- Contained (self-referential + AOTInpainting + own tests):
  `inpainting/aot/{NnapiCapabilityGate,NnapiHealthMonitor,StrictNnapiFallback}.kt`,
  `runtime/onnx/CheckNnapi.java` (1-line compile guard, zero refs),
  tests `{NnapiCapabilityGateTest,NnapiHealthMonitorTest,StrictNnapiFallbackTest}.kt`
- Wider plumbing: `HardwareDiscoveryEngine` (`HardwareRoute.NNAPI`, `nnapiProbeRunner`,
  `probeNnapi()`), `OnnxRuntimeProvider` (`:475,:551,:594`), `PaddleOcrSessionFactory:19`,
  `PaddleOcrProviderTestConfiguration:22`, `OnnxBubbleSegmenter:327`,
  `domain TranslationPreferences` (`TranslationHardwareAccelerator.NNAPI` — persisted enum,
  needs unknown-value fallback if removed), `QnnDiagnostics:351-397`,
  `TranslationTrace.NNAPI`, `TranslationPipelineDiagnostics:909`,
  `TranslationMemoryBudget.nnapiMemorySnapshot()`, `HardwareDiscoveryEngineTest`,
  `BenchmarkDeviceMetadata:125`

## Commit

(none — this ticket produces evidence or a diagnostic, not deletions)
