# Translation measurement and benchmark checks

The D4 procedure and confidence labels are maintained in the epic artifact `batch-d4-measurement/measurement-protocol`. Device-backed sessions remain parked until the user resumes them; this file records the implementation-side collection commands only. No live-device results are claimed here.

## Debug trace collection (when a device session is resumed)

The HUD was removed. The 512-record debug buffer remains a bounded tail with no current reader; the debug file sink is the durable trace collection path. The session receiver writes ordered start/end markers to that same file.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'
$debugAppId = 'app.kanade.tachiyomi.vibe.debug'
$receiver = "$debugAppId/eu.kanade.translation.diagnostics.TranslationMeasurementSessionReceiver"
$action = 'eu.kanade.translation.action.MEASUREMENT_SESSION'
function Start-MeasurementSession([string]$Engine, [string]$ChapterId, [int]$Pages) { adb shell am broadcast -n $receiver -a $action --es phase start --es engine $Engine --es chapter_id $ChapterId --ei page_count $Pages }
function End-MeasurementSession { adb shell am broadcast -n $receiver -a $action --es phase end }
./gradlew :app:installDevDebug
```

Follow the D4 artifact for the fixed corpus (three or more local chapters, each at least 100 pages), workflow/configuration matrix, paired run order, 30-session sample size, thermal/power rejection rules, first-render capture, and statistics. Do not use the old 20-page examples or treat benchmark-engine results as end-to-end reader/batch results.

Pull both rotated trace segments and repeat the pull until the matching end marker is present:

```powershell
adb exec-out run-as $debugAppId cat files/translation-trace/translation-trace.log > .\translation-trace.log
adb exec-out run-as $debugAppId cat files/translation-trace/translation-trace.log.1 > .\translation-trace.log.1
```

The benchmark matrix JSON uses one cell array tagged by engine (`detector`/`recognizer`) and workflow context (`auto_reader_follow`/`batch`). Every cell retains requested configuration, expected and actual registered provider, session creation, per-page timing samples, and evidence. Detector samples use full-page inputs; recognizer samples use crop batches. A mismatched registration is a failed/invalid cell.

## Deterministic and manual benchmarks

The synthetic lane test uses integer times and no wall clock. The replay bench is disabled by default; it writes and fully replays a synthetic 200-page framed file under `@TempDir`, prints median/p10/p90, and has no timing threshold.

```powershell
./gradlew :app:compileDevReleaseUnitTestKotlin
./gradlew :app:testDevReleaseUnitTest --tests 'eu.kanade.translation.diagnostics.TranslationLaneSplitAttributionTest'
./gradlew :app:testDevReleaseUnitTest --tests 'eu.kanade.translation.diagnostics.SyntheticJournalReplayBenchTest' -PrunSyntheticJournalReplayBench
./gradlew :app:connectedDevDebugAndroidTest -PincludeManualMeasurementBenches -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.translation.diagnostics.JournalMicrobenchInstrumentedTest
adb exec-out run-as $debugAppId cat files/journal-microbench-results.txt > .\journal-microbench-results.txt
```

The Android microbench is excluded from normal `androidTest` runs. It measures 2 KiB/8 KiB append throughput, 120 file-fsync samples, 120 directory-fsync samples, and five free-only sync groups at a 1 s cadence on `context.filesDir`. Timings are data only. The four existing renderer/bitmap instrumented tests remain useful; run them before a device capture so they do not warm the device during a measurement:

```powershell
./gradlew :app:connectedDevDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewInstrumentedTest,eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewLifecycleInstrumentedTest,eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewRenderingInstrumentedTest,eu.kanade.translation.rendering.RenderColorEstimatorInstrumentedTest"
```
