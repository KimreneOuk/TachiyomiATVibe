---
kind: spec
title: "Phase 0 device baseline — availability, capture protocol and missing evidence"
comments: none
---

# Observed availability

Current sequencing decision: the user authorized proceeding with cleanup and deferring phone testing. This artifact remains the unperformed measurement protocol. Missing device/native evidence no longer blocks verified dead-code removal or host test repair; it remains required before accepting relevant native-lifetime/scheduling/performance changes and final release validation. No measurement is waived or reported as passed.

Host SDK platform-tools exist at `C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Actual commands during Phase 0:

```powershell
& 'C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe' version
& 'C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

ADB reported 1.0.41 / 37.0.0-14910828 and an empty attached-device list. The ADB daemon started on port 5037. No device was connected, installed to, or measured. No latency, throughput, memory or occupancy distribution exists from this run.

The previously documented OnePlus PKG110 / Snapdragon 8 Gen 3 measurements are historical evidence, not a current baseline at the pinned revision. Do not reuse their figures as new observations or extrapolate host unit-test time into device performance.

# Existing tools and limitations

| Existing source | Useful for | Limitation |
| --- | --- | --- |
| `scripts/trace_stream.ps1` / `.sh` | Streaming app trace collection for a specified device/package | Script clears logcat before capture; preserve needed existing logs first. A trace stream alone does not define representative workloads. |
| `androidTest/.../diagnostics/PaddleOcrLiteRtOnDeviceTest.kt` | Packaged OCR bucket/detector checks | Native OCR benchmark, not complete reader/batch latency or cancellation ownership verification. |
| `androidTest/.../litert/LiteRTOnDeviceVerificationTest.kt` | Synthetic LiteRT inpaint backend/warmup/5 measured runs | Five patch samples are not representative full-page p95; no full app/HTTP/store benchmark. |
| `androidTest/.../inpainting/PageInpaintingEngineOnDeviceVerificationTest.kt` | Inpainting engine device behavior | Needs packaged models and supported actual hardware/configuration. |
| `androidTest/.../rendering/RenderColorEstimatorInstrumentedTest.kt` | Android bitmap/style behavior | Focused correctness, not total processing performance. |
| `androidTest/.../diagnostics/JournalMicrobenchInstrumentedTest.kt` | App-private journal storage distributions | Tagged `ManualMeasurementBench`; excluded by default runner configuration as documented in the test. It is a deliberately selected measurement, not an ordinary green-suite guarantee. |
| `androidTest/.../diagnostics/ManualMeasurementBench.kt` | Measurement annotation | Annotation only, not an executable full-page benchmark. |

These paths are under `app/src/androidTest/java/eu/kanade/translation`. No instrumentation has been run in Phase 0.

# Reproducibility contract for actual measurements

Before measurement record device model/SoC, Android/build, RAM, package/flavor/APK hash, repo revision, model asset hashes, OCR/inpaint/provider configuration, source workload identities/page hashes, reading mode, cold/warm state, battery/power and thermal state. Preserve existing data and committed translations; use dedicated benchmark content/chapter/store where reset is necessary.

Use the same representative workloads before/after a refactor: ordinary manga, vertical/long-strip pages, text-dense pages, textless pages and already-committed refresh/resume. Bind user-visible baseline scenarios to the behavior inventory:

1. Manual current-page request: page and stage timing including provider and commit.
2. Existing auto flow across consecutive pages: current/next-page readiness, native/provider overlap and retained allocations.
3. Chapter batch: end-to-end throughput and a separately recorded interrupted/resumed run.
4. Cancellation/chapter replacement/engine teardown: actual native exit and resource retention, preserving store fences.
5. Low-memory/failed publication/refresh: correctness probes separately from performance samples.

Warmup and cold-start samples are reported separately. Aim for at least 30 warm measured page samples per comparable workload where practical; report actual n and raw distributions. Smaller samples remain exploratory and cannot support confident p95/noise estimates. Repeat under comparable thermal/storage conditions; do not accept arbitrary percentage thresholds without observed noise bands.

Capture end-to-end/stage durations, next-page readiness, native admission wait/active/idle time, batch pages per unit time, peak process/native/bitmap memory and reader responsiveness. Include failure/skipped/deferred counts; never silently omit slow failed pages. Host duration and device duration are different measurements.

Provider setup must be fixed and explicit. Prefer an existing local/non-billable configuration for repeated experiments; do not trigger paid provider traffic without the selected workload/call budget being known. Do not change OCR/LiteRT algorithms, model assets or inference settings while measuring an architectural comparison.

# Completion status

Protocol and device availability probe: recorded. Actual device measurements: **pending — no ADB-connected device**. Required missing inputs: supported reference device connection and representative chapter/provider setup. These are external inputs; host-baseline closure under the revised gate does not claim the measurements happened.

The user subsequently instructed **Proceed and defer phone testing for now**. Host baseline is closed and Phase 1 verified cleanup may proceed; device/native validation remains deferred for relevant later changes and release. No repeated connection prompts are needed until hardware or a changed instruction is supplied.
