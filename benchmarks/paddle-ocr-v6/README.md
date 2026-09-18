# Paddle OCR v6 Android B1 benchmark

This is the benchmark-only `devBenchmark` variant of the original `app`
module. It reuses the app's `PaddleOcrV6SmallEngine`, `OnnxModelStore`, ONNX
Runtime QNN artifact, model assets, provider libraries, configured download
storage, and selected device ABI. Release and normal debug variants do not
compile `app/src/benchmark`.

The variant keeps the installed app's application ID and debug signing
configuration. On the reference device that ID is
`app.kanade.tachiyomi.at.debug`; installing the benchmark APK with `-r`
updates that package in place and preserves its existing app data directory.
Never uninstall the package to solve a signing error: a mismatched signature
must fail the install without wiping user data.

## Build, signature check, and install

From the repository root:

```powershell
$serial = "192.168.100.223:45317"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& .\gradlew.bat :app:assembleDevBenchmark --no-daemon
$apk = "app\build\outputs\apk\dev\benchmark\app-dev-arm64-v8a-benchmark.apk"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs $apk
& $adb -s $serial shell dumpsys package app.kanade.tachiyomi.at.debug | Select-String "dataDir|versionName|signatures"
& $adb -s $serial install -r $apk
& $adb -s $serial shell dumpsys package app.kanade.tachiyomi.at.debug | Select-String "dataDir|versionName|signatures"
```

The APK must report the same certificate as the installed package before it is
installed. On the reference device both report SHA-256 certificate
`d314b00ca115ff3b7415336426e274689c3a951429e4709273a4a3ba93ac4762`.
Some Oplus/QTI builds return `Failure [-99]` from `adb install -r` while
using a staged install. The equivalent data-preserving fallback is:

```powershell
& $adb -s $serial push $apk /data/local/tmp/paddle-benchmark.apk
& $adb -s $serial shell pm install --force-non-staged --full --user 0 -r -d /data/local/tmp/paddle-benchmark.apk
```

The installed package remains `app.kanade.tachiyomi.at.debug` and the
benchmark Activity is
`eu.kanade.translation.benchmark.PaddleBenchmarkActivity`.

## Corpus selection

The primary corpus is the app's already configured download tree. The runner
enumerates source/manga/chapter directories and `.cbz` chapters through the
app's `StorageManager` and streams one page at a time. No chapter data is
packaged or copied. The committed
`app/src/benchmark/assets/benchmark/fixtures.json` contains only two generated
fallback pages, one for each production recognition width bucket.

The existing real-page corpus is secondary and optional. Push it to the
installed app's external files directory only when explicitly requested:

```powershell
$corpus = "/sdcard/Android/data/app.kanade.tachiyomi.at.debug/files/paddle-corpus"
& $adb -s $serial shell "mkdir -p $corpus"
& $adb -s $serial push tools\aot_corpus\real_corpus $corpus
```

## Headless runs

Each command starts the benchmark directly; no UI or onboarding is needed.
`pageLimit=0` means all pages visible to the selected corpus sources.

```powershell
$pkg = "app.kanade.tachiyomi.at.debug"
$component = "$pkg/eu.kanade.translation.benchmark.PaddleBenchmarkActivity"
$base = "/sdcard/Android/data/$pkg/files/paddle-benchmark"

& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $component --ei pageLimit 1 --es outputDir "$base/run-1"
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $component --ei pageLimit 10 --es outputDir "$base/run-10"
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $component --ei pageLimit 50 --es outputDir "$base/run-50"
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $component --ei pageLimit 0 --es outputDir "$base/run-full"
```

Downloaded chapters are enabled by default. To run the secondary pushed
corpus too, add `--ez includeExternalCorpus true`; to use only deterministic
fixtures, add `--ez includeDownloadedCorpus false --ez includeExternalCorpus false`.
Poll for `complete.marker`, then pull the results:

```powershell
& $adb -s $serial shell "ls $base/run-full/complete.marker"
& $adb -s $serial pull "$base/run-full" benchmarks\paddle-ocr-v6\results\run-full
```

## Exact B1 parity mode

Run the committed 640/1600 fixtures through both the existing per-crop
`recognizeWithConf` path and the `recognizeBucketBatch(..., maxBatch=1)` wrapper
in the same initialized session. The runner compares real text and raw
single-precision confidence bits; a mismatch fails the run with page/region/
leaf/stage identity and blocks B4/B8 or accelerator promotion.

```powershell
$parity = "$base/reference-sm8650-b1-parity"
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $component `
    --ei pageLimit 0 `
    --ez includeDownloadedCorpus false `
    --ez includeExternalCorpus false `
    --ez parityMode true `
    --es outputDir $parity
& $adb -s $serial shell "ls $parity/complete.marker"
& $adb -s $serial pull "$parity" benchmarks\paddle-ocr-v6\results\reference-sm8650-b1-parity
```

The parity artifact records the exact reference/B1 text, confidence values and
raw confidence bits for every fixture crop. This crop-only benchmark does not
invoke Detector v4; the detector-present/absent and fallback-heavy matrix is
covered by the deterministic JVM suite.

Every run emits:

- `paddle_benchmark.json`: device/SoC/API/ABI/RAM, thermal state, model
  SHA-256 digests, ORT/provider metadata, session creation, per-stage timings,
  two warm runs, actual B1 size, and PSS/Java-heap samples;
- `paddle_samples.csv`: one row per page/crop and width bucket;
- `paddle_benchmark_report.md`: concise human summary with `CONFIRMED` and
  `UNTESTED` evidence labels.

PSS uses `Debug.getMemoryInfo()` on a dedicated daemon sampler at 75 ms. The
last sample is taken after the run, and peak Java heap and thermal status are
reported with the PSS peak.
