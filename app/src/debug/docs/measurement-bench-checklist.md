# Translation measurement bench checklist

Run on one mid/low-tier device and, if available, one flagship. Use the same network and reading interaction. Session records hash `chapter_id`; pass an opaque token, never a title or URL.

## Reading sessions and trace pull

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'
$debugAppId = 'app.kanade.tachiyomi.vibe.debug'
$receiver = "$debugAppId/eu.kanade.translation.diagnostics.TranslationMeasurementSessionReceiver"
$action = 'eu.kanade.translation.action.MEASUREMENT_SESSION'
function Start-MeasurementSession([string]$ChapterId, [int]$Pages) { adb shell am broadcast -n $receiver -a $action --es phase start --es engine ml_kit --es chapter_id $ChapterId --ei page_count $Pages }
function End-MeasurementSession { adb shell am broadcast -n $receiver -a $action --es phase end }
.\gradlew :app:installDevDebug
```

For Session 1, run `Start-MeasurementSession 'NORMAL_OPAQUE_ID' 20`, read the chapter, then run `End-MeasurementSession`. Repeat with the optional ~200-page chapter. For Session 2, repeat those same chapters and capture `STORE_FLUSH`, `STORE_COMMIT`, `LEASE_WAIT`, `NATIVE_QUEUE`, and `PREPARED_QUEUE`. Collect at least three sessions/samples. The HUD shows active page spans, lane occupancy, and budget breaches.

```powershell
adb exec-out run-as $debugAppId cat files/translation-trace/translation-trace.log > .\translation-trace.log
adb exec-out run-as $debugAppId cat files/translation-trace/translation-trace.log.1 > .\translation-trace.log.1
```

## Gates and manual benches

The deterministic lane test uses integer synthetic times and no wall clock. The replay bench is disabled by default; it writes and fully replays a synthetic 200-page framed file under `@TempDir`, prints median/p10/p90, and has no timing threshold.

```powershell
.\gradlew :app:compileDevReleaseUnitTestKotlin
.\gradlew :app:testDevReleaseUnitTest --tests 'eu.kanade.translation.diagnostics.TranslationLaneSplitAttributionTest'
.\gradlew :app:testDevReleaseUnitTest --tests 'eu.kanade.translation.diagnostics.SyntheticJournalReplayBenchTest' -PrunSyntheticJournalReplayBench
.\gradlew :app:connectedDevDebugAndroidTest -PincludeManualMeasurementBenches -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.translation.diagnostics.JournalMicrobenchInstrumentedTest
adb exec-out run-as $debugAppId cat files/journal-microbench-results.txt > .\journal-microbench-results.txt
```

The Android microbench is excluded from normal `androidTest` runs. It measures 2 KiB/8 KiB append throughput, 120 file-fsync samples, 120 directory-fsync samples, and five free-only sync groups at a 1 s cadence on `context.filesDir`. Timings are data only. The four existing renderer/bitmap instrumented tests remain useful; fold their occasional run into this checklist before capture so they do not warm the device during a measurement.

```powershell
.\gradlew :app:connectedDevDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewInstrumentedTest,eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewLifecycleInstrumentedTest,eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayViewRenderingInstrumentedTest,eu.kanade.translation.rendering.RenderColorEstimatorInstrumentedTest"
```

Transcribe each result to `MEASUREMENTS.md`. Record commit, device/Android, engine, chapter/pages, thermal and battery start→end, and median/p10/p90 for page latency, stage spans, per-flush storage, append throughput, file/directory fsync, and free-group sync. Derive paid per-line sync as median file-fsync × observed lines/page; compute manifest-entry size as bytes ÷ entries.

```markdown
| Date | Device / Android | Commit | Engine / opaque chapter / pages | Thermal + battery start→end | Page latency median/p10/p90 | Stage spans median/p10/p90 | STORE_FLUSH / STORE_COMMIT | Queue waits | Manifest bytes / entries | Append 2K / 8K B/s | File fsync p10/median/p90 | Directory fsync p10/median/p90 | Free-group sync p10/median/p90 | Notes |
|---|---|---|---|---|---|---|---|---|---|---:|---|---|---|---|
| YYYY-MM-DD | model / Android | abc1234 | ML Kit / opaque-id / 20 | none→light / 80%→76% | … | … | … | … | … | … | … | … | … | … |
```
