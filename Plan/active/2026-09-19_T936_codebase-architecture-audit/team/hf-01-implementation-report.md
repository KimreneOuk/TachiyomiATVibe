# HF-01 implementation report

## Result

The manual-translation overlay stall was caused by a stale cached online key
surviving a page-loader rebind. The reader page had `pageIdx=3` and a cached
`translationStorageKey=3.jpg`, while the downloaded loader supplied the
canonical source name `4.jpg`; the store contained `3.jpg` but the live reader
observation looked up `4.jpg`. The same spread occurred for `5.jpg` versus
`6.jpg`. The canonical source name now wins whenever it is available, so the
manual writer and live observer use the same key.

Commits:

- `626868a docs(plan): HF-01 manual translate key-mismatch ticket`
- `f406b8f fix(translation): manual translate publishes under source file name (overlay stall)`
- report commit: `docs(plan): HF-01 implementation report`

## 1. Code trace and archaeology

### Divergent key lifecycle

| Path | Evidence | Finding |
| --- | --- | --- |
| Online page setup | `HttpPageLoader.kt:72-80` | Online pages initially receive the URL/index fallback from `onlinePageTranslationKey`. |
| Downloaded page setup | `DownloadPageLoader.kt:145-165` | Downloaded pages receive the actual directory filename (`4.jpg`, `6.jpg`) as `sourceFileName`. |
| Cache helper | `ReaderAutoTranslationPageResolver.kt:206-218` | Before HF-01, `translationStorageKey` was returned before checking the later authoritative `sourceFileName`; this preserved stale `3.jpg`/`5.jpg` keys after rebind. |
| Manual dispatch | `ReaderTranslationController.kt:727-781`, `871`, `901`, `911` | `translateSinglePage` resolves one `pageKey` and passes that exact value through each `TranslationManager.translatePage` call. No manual writer constructs `"$pageIdx.jpg"` here. |
| Orchestration/scheduler | `TranslationManager.kt:1908-1914`, `ReaderTeardownCoordinator.kt:114-120`, `TranslationScheduler.kt:680` | The resolved key is forwarded unchanged; the scheduler persists it as `PageTranslation.sourceFileName` (`TranslationScheduler.kt:618-652`, `880`). |
| Live observation | `ReaderTranslationController.kt:1449-1509` | `observePageView` resolves the same page through `resolvePageKey`, then indexes the store by that key. With a downloaded page this is the real source filename. |
| Download rekey | `TranslationManager.kt:1365-1397` | `rekeyTranslationForCompletedDownload` intentionally returns for partial/mismatched page sets (`pages.size` and online/on-disk list-size guards). Therefore a partially populated store can retain an online fallback; current reader key resolution must still prefer the active loader filename. |

The ticket's suspected controller/scheduler index construction was not found:
there are no manual-path `"$pageIdx.jpg"` or `page.index` key constructors. The
decisive defect was the cache precedence in
`resolveReaderPageTranslationKey`. HF-01 changes it to:

1. `page.sourceFileName`, then hydrated `page.translation.sourceFileName`;
2. an already cached fallback only when no source filename exists;
3. the online fallback as the final case.

The helper also updates `translationStorageKey` when a source filename becomes
authoritative, keeping subsequent writer and observer calls stable.

### Campaign archaeology

This is pre-existing behavior, not a Phase 1-5 regression:

- `sourceFileName` was introduced in `cc75e41` (2026-06-15).
- `translationStorageKey` was introduced in `a8f52b3` (2026-06-18).
- The helper/cache behavior was introduced in `efb8dee` (2026-08-16).
- At baseline `7262bf4` (the 2026-09-19 campaign baseline), the same helper
  precedence and reader paths were already present.
- P4 extraction commit `cc80183` moved the reader controller mechanically;
  comparison against `7262bf4` shows no key-derivation behavior change.

Verdict: the campaign exposed the defect on the device but did not introduce
the mismatch.

## 2. Implementation and regression tests

### Canonical-key fix

`ReaderAutoTranslationPageResolver.resolveReaderPageTranslationKey` now treats
the current source filename as authoritative over a cached online fallback.
No store write, scheduler, pipeline, or hardware-routing behavior was changed.

### Behavioral tests

Added `ReaderPageTranslationKeyTest.kt` with two tests:

1. `manual writer and reader observation share the rebound source filename`
   models index 3/source `4.jpg`, seeds stale `3.jpg`, writes the store under
   the resolved key, and asserts reader observation emits non-null data under
   `4.jpg`.
2. `writer and observer keys remain equal when page index differs from filename`
   models index 5/source `6.jpg`, seeds stale `5.jpg`, and asserts both entry
   points resolve exactly `6.jpg`.

Assertions preserve the production invariant and cover both device examples;
they do not weaken existing assertions or change unrelated tests.

## 3. Rebind cancellation diagnosis

The `JobCancellationException` entries are expected page-recycle lifecycle
cancellations, not a lost-observation defect:

- `PagerPageHolder.kt:176-226` recreates its holder scope on attach and starts
  the `observePageView(page)` collector.
- `PagerPageHolder.kt:286-296` cancels that scope on detach; detach is a normal
  ViewPager lifecycle event.
- `WebtoonPageHolder.kt:216-284` cancels old jobs during `bind` and then
  registers a fresh `observePageView(boundPage)` collector.
- `WebtoonPageHolder.kt:305-335` cancels the scope during `recycle`, after which
  the next bind re-establishes the collector.

This matches the device evidence: changing reading mode/rebinding hydrates and
shows the translation. HF-01 fixes the key used by the live collector; it does
not alter cancellation or lifecycle semantics.

## 4. P2-00 compiled-provider diagnostic

`OnnxRuntimeProvider.environment` is the lazy ORT environment used by the OCR,
segmentation, and inpainting engines. The original diagnostic wrapped both the
provider query and the log emission inside `runCatching`, so an exception in the
query/log lambda could prevent the expected line. HF-01 now:

- evaluates `OrtEnvironment.getAvailableProviders()` into
  `compiledProviders` at `OnnxRuntimeProvider.kt:14-21`;
- logs a WARN with a stable `query_failed:<ExceptionType>` value if the query
  fails; and
- emits the INFO line unconditionally at `OnnxRuntimeProvider.kt:23`:
  `[onnx_runtime] compiledProviders=...`.

This is additive diagnostic hardening only. It does not select a provider,
change NNAPI routing, or touch any hardware policy.

## 5. Verification

All commands were run from the repository root with
`JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr`,
`--no-parallel --max-workers=1`, and a temporary copy of
`app/src/standard/google-services.json` at `app/google-services.json` for
flavor tasks. The temporary file was removed after every invocation and is not
tracked.

### Focused tests and compilation

- `./gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.tachiyomi.ui.reader.ReaderPageTranslationKeyTest --no-parallel --max-workers=1`
  — **PASS**, BUILD SUCCESSFUL (205 actionable tasks; 16 executed).
- `./gradlew.bat :app:testDevDebugUnitTest --tests 'eu.kanade.tachiyomi.ui.reader.*' --tests 'eu.kanade.translation.orchestration.*' --tests 'eu.kanade.translation.scheduling.*' --no-parallel --max-workers=1`
  — **PASS**, BUILD SUCCESSFUL.
- `./gradlew.bat :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin --no-parallel --max-workers=1`
  — **PASS**, BUILD SUCCESSFUL.

### Full suites and flake ledger

Each flavor has 2,026 tests. Final clean-temp runs were green:

- `./gradlew.bat --no-daemon :app:testStandardDebugUnitTest --no-parallel --max-workers=1`
  — **PASS**, 2,026 tests, BUILD SUCCESSFUL. A fresh per-run
  `-Djava.io.tmpdir` was used after the shared Windows temp root produced a
  JUnit cleanup `AccessDeniedException` in an earlier attempt.
- `./gradlew.bat --no-daemon :app:testDevDebugUnitTest --no-parallel --max-workers=1`
  — **PASS**, 2,026 tests, BUILD SUCCESSFUL, same isolated-temp setup.

The following loaded-suite timing flakes occurred before the final green runs;
they were isolated and did not reproduce. They are the established Phase 1
pattern (end-to-end coexistence tests under full-suite load), not reader,
storage-key, or legacy-file failures:

| Test and failure signature | Full-suite run | Isolation outcome |
| --- | --- | --- |
| `BatchDispatchResumeWiringTest` — `state=TRANSLATE` before the 10,000 ms completion window | first Dev full run | Whole class passed in the combined focused isolation run |
| `StandardPipelineCoexistenceTest` — `expected:<COMPLETE> but was:<TRANSLATE>` | first and second Dev full runs | Whole class passed in focused isolation |
| `StandardLaneMultiPageCompletionTest` — `expected:<TRANSLATED> but was:<ERROR>` | subsequent Dev and Standard full runs | Dev 3/3 isolated runs passed; Standard 3/3 isolated runs passed |
| `BatchDispatchResumeWiringTest` — finished-chapter zero-work method threw its completion-window `IllegalStateException` | first clean-temp Dev full run | Whole class had already passed in focused isolation; final clean-temp Dev full run passed |

One Standard full attempt additionally failed during JUnit extension cleanup,
not a test assertion: `StandardPipelineCoordinatorTest` reported
`Failed to delete temp directory ... AccessDeniedException` for a generated
artifact. Disk space was ample; the shared temp root contained 2,198 stale
directories. Stopping the old Gradle daemon and using an isolated temporary
root produced the final green Standard run.

### APK build and inspection

- `./gradlew.bat :app:assembleDevDebug --no-parallel --max-workers=1`
  — **PASS**, BUILD SUCCESSFUL (273 actionable tasks).
- Inspected
  `app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk` with the JDK
  `jar tf` listing:

  - `assets/models/segmentation/best_int8.onnx`: **absent** (0 entries).
  - `assets/models/segmentation/manga109_bubble_int8.onnx`: **present** (1).
  - `assets/models/ocr/paddle-v6-small/inference.onnx`: **present**.
  - `assets/models/ocr/paddle-v6-small/det/inference.onnx`: **present** (2
    `inference.onnx` entries total under OCR models).
  - OCR `.md`, `.yml`, and `.gitattributes` entries: **absent** (0).

The D8 build printed existing Kotlin-metadata warnings for several classes,
including `OnnxRuntimeProvider`; assemble completed successfully and the APK
was produced.

## Scope confirmation

No assets, downloaded manga data, CBZ files, databases, NNAPI/hardware-routing
policy, or unrelated tests were modified. `app/google-services.json` is absent
after verification, and the working tree is clean before this report commit.
