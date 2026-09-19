---
kind: ticket
title: "Ticket 06 Paddle batching validation and staged activation"
status: 2
comments: none
---

# Ticket 06 validation and staged activation

This ticket wires the reviewed Paddle leaf planner and bucket executor into the
existing page analysis boundary. The detector, per-region geometry, page native
quarantine, generation fences, leases, checkpoints, and non-Paddle OCR paths
remain authoritative.

## Batch-call serialization decision

`PaddleOcrV6SmallEngine.recognizeBucketBatch` calls the reviewed executor, whose
buffer pool intentionally permits two input buffers but only one output buffer.
The integration therefore **serializes every batch call at the page-adapter
seam with one `Mutex` per Paddle page coordinator/engine session**. Calls are
not allowed to overlap and are not permitted to rely on the executor's
allocation-failure downgrade when the output pool is occupied. This preserves
the supported `maxOutputBuffers = 1` memory contract and keeps the planner's
single-thread confinement explicit. `RoiPageRecognitionEngine.nativeGuard`
continues to cover the complete page analysis; the adapter does not acquire a
second native guard per microbatch and does not claim in-flight ORT
preemption.

## Page and ownership invariants

- A planner is created for exactly one `(pageId, generation)` and rejects every
  other leaf identity. A batch is submitted only after all its leaves belong to
  that planner, so no tensor contains leaves from two pages or generations.
- Results are mapped by the planner's input order and leaf identity before the
  page result is marked OCR-ready. Any cancellation or execution failure calls
  planner cancellation/release and publishes no partial page result.
- Manual, automatic, and chapter mode identity is carried into the page policy
  and page log; their cross-page admission remains owned by the existing
  schedulers. This adapter drains one page independently, so the chapter path
  streams pages and never retains chapter crops, while look-ahead metadata
  cannot enter this page's tensor.
- The existing B1 single-crop call remains the Paddle fallback for allocation
  downgrade. MangaOCR, MLKit, and all durable translation envelopes are
  untouched.

## Evidence to record at handoff

The implementer handoff will include the page-scoped batch sequence trace,
proof that all leaves resolve before commit, no-cross-page assertions,
mode-priority tests, cancellation/resume results, changed files, commands and
test counts, and explicit `CONFIRMED`/`FAILED`/`UNTESTED` labels.

## Ticket 06 implementation outcome

The production path remains B1. `PADDLE_BATCHING_STAGED` and
`PADDLE_BATCHING_REQUESTED_BATCH` are available only as build-time staged/debug
controls. A requested accelerator or unapproved batch is resolved to the
explicit CPU B1 emergency configuration until a device profile confirms that
provider/width/batch cell. The device policy also forces that emergency path
when thermal severity exceeds 2. The rolling-p95 governor requires two high
windows before one-step downgrade (B8 -> B4 -> B2 -> B1) and three low windows
before one-step recovery.

Follow-up (a) is resolved: the detector-missing, non-tall whole-region plan
sets `filterConfidence=false`, matching the old inline path's `isUsable()`-only
behavior. The healthy detector path keeps its confidence filter. The regression
fixture `VerticalLineOcrPlanContractTest` proves that a 0.49-confidence usable
text is retained only on the degraded path.

## Device gate and evidence

The required single connection attempt on 2026-09-19 returned:

`cannot connect to 192.168.100.223:45317: No connection could be made because the target machine actively refused it. (10061)`

Therefore no device-dependent result is claimed. All 32 provider x batch x
width matrix cells, B1 parity, PSS delta, thermal, responsiveness, manual
page-boundary wait, and 50-page/full-corpus sustained-run measurements are
`UNTESTED`. There are no approved/promoted combinations. Host compilation and
JVM policy/parity/lifecycle tests are not Qualcomm evidence.

| Provider | B1/640 | B1/1600 | B2/640 | B2/1600 | B4/640 | B4/1600 | B8/640 | B8/1600 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CPU | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED |
| QNN GPU | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED |
| QNN HTP | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED |
| NNAPI | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED |

Every accelerator matrix cell uses strict no-CPU-fallback session options and
is rejected unless post-inference provenance is recorded and
`lastBatchTelemetry.downgradeReason == null`. Registration alone never counts
as provenance. Long runs hold a partial wake lock and foreground service;
PSS sampling remains 75 ms.

## Ready-to-run reference-device sequence

Run this sequence from the repository root once the SM8650 returns. The APK
must be built from the committed post-ticket SHA; compare the certificate and
package metadata before and after installation. The benchmark keeps package
`app.kanade.tachiyomi.at.debug` and writes only under its external files tree.

```powershell
$serial = "192.168.100.223:45317"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect $serial
& .\gradlew.bat :app:assembleDevBenchmark --no-daemon
$apk = "app\build\outputs\apk\dev\benchmark\app-dev-arm64-v8a-benchmark.apk"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs $apk
& $adb -s $serial shell dumpsys package app.kanade.tachiyomi.at.debug | Select-String "dataDir|versionName|signatures"
& $adb -s $serial install -r $apk
& $adb -s $serial shell dumpsys package app.kanade.tachiyomi.at.debug | Select-String "dataDir|versionName|signatures"
# If install -r returns Failure [-99], use the data-preserving fallback:
& $adb -s $serial push $apk /data/local/tmp/paddle-benchmark.apk
& $adb -s $serial shell pm install --force-non-staged --full --user 0 -r -d /data/local/tmp/paddle-benchmark.apk

$pkg = "app.kanade.tachiyomi.at.debug"
$component = "$pkg/eu.kanade.translation.benchmark.PaddleBenchmarkActivity"
$base = "/sdcard/Android/data/$pkg/files/paddle-benchmark"
$matrix = "$base/reference-sm8650-paddle-matrix"
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $component `
    --ei pageLimit 0 `
    --ei matrixIterations 20 `
    --ez includeDownloadedCorpus false `
    --ez includeExternalCorpus false `
    --ez matrixMode true `
    --es outputDir $matrix
& $adb -s $serial shell "ls $matrix/complete.marker"
& $adb -s $serial pull "$matrix" benchmarks\paddle-ocr-v6\results\reference-sm8650-paddle-matrix
```

After the matrix, repeat with `--ez parityMode true` and output
`$base/reference-sm8650-b1-parity`, then repeat the page limits `1`, `10`,
`50`, and `0` for the sustained-run gate. Promote a cell only after a fresh
post-commit artifact has matching commit metadata, exact B1 output parity,
zero unexplained fallback, PSS/output-memory, thermal, responsiveness, and
manual page-boundary evidence.
