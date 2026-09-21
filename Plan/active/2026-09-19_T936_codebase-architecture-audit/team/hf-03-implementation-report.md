# HF-03 page-translation latency implementation report

Date: 2026-09-21
Branch: `t936/perf-manual-latency`

## Outcome

Items 1, 3, and 4 are implemented. Item 2 (native-lane interactive priority) was
cancelled after the required stop-gate probe falsified its prefetch-contention premise;
the measured delay is absorbed by item 3's memory-first publication change. The
HF-03 AUTO-lane bitmap-ownership hotfix is implemented in `68034b4`: lazy cleaned-image
flushes now complete before the AUTO boundary recycles the source bitmap, and a failed
cleaned publication preserves the translated overlay on the original image instead of
poisoning inpaint/render truth.

The post-hotfix Standard full suite is green at 2,045/2,045. The final serial Dev full
suite completed 2,045 tests with one occurrence of the already tracked,
load-sensitive `StandardLaneMultiPageCompletionTest` residual (`TRANSLATED` expected,
`ERROR` observed); the class passed 3/3 warm isolated runs, so no further production
change was made for that unrelated residual family. The post-hotfix Dev and Standard
flavor compiles and `assembleDevDebug` are green. The APK contains the live segmentation
and OCR runtime assets and none of the excluded model-repository documentation or stale
segmentation asset.

## Commit map

| Commit | Scope |
| --- | --- |
| `293e16b` | Ticket and research-plan artifacts |
| `4ba13c6` | Free Google endpoint span-ID envelope batching |
| `b717184` | Memory-first active-store persistence and lazy flush |
| `6bdaf74` | Admission-time stage truth |
| `68034b4` | Serialize lazy cleaned-bitmap release and preserve original-image fallback |
| (this report) | Ticket status update, evidence, and implementation report |

## Item 1 — free-endpoint span-ID envelope batching

`GoogleTranslator` now plans one escaped `span data-id="bN"` envelope per page (with
chunking at the 5,000-source-character cap), reconstructs translations by strict ID
sequence, and falls back to the existing per-block path on every missing, duplicate,
reordered, malformed, or otherwise anomalous envelope. The fallback does not retry the
envelope and does not relax request spacing or add concurrency. The request governor is
charged once per page/envelope. HTTP failures retain the existing retry/backoff policy;
HTML/CAPTCHA challenge bodies are classified as quota failures and trip the breaker
instead of being parsed as translations.

Focused behavioral suite:

```text
.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.translator.providers.GoogleTranslatorEnvelopeTest --no-parallel --max-workers=1
6 tests, 0 failures, 0 errors
```

The same provider tests were included in the later full Dev and Standard passes.

## Item 2 — native priority stop-gate (cancelled)

The temporary native queue-wait probe recorded `queueMs=0`; the trace contained no
AUTO/native-prefetch activity, and the ONNX sessions were already warm/cached. Therefore
the 2.65-second pre-detect and 0.98-second pre-inpaint gaps were not executor contention.
Tracing placed both waits in synchronous durable publication through
`updatePageGuarded -> persistArtifactMutationLocked` under the store mutex. Approximately
290 ms of the latter interval was observed as GC. No priority queue, preemption, or native
executor change was made. The ticket now records: **cancelled — mechanism absorbed into
item 3**. GC is retained as a watch item only; no GC tuning was attempted.

## Item 3 — lazy persistence

The active reader store now publishes a complete live snapshot first and queues disk work
behind `StorePersistenceScheduler`; `CleanedPublication` follows the same deferred path.
The scheduler serializes scheduled flushes and explicit barriers, performs slow file/SAF
work outside the store mutex, then uses the existing artifact bridge for durable writes.

The four ticket invariants are preserved verbatim in behavior:

1. **Store-first hydration:** live entries are consulted before disk, preserving HF-01
   keying and immediate reader visibility.
2. **Generation-checked flush:** queued work carries the store generation and is discarded
   when the store is defunct or generation-invalid.
3. **Atomic writes:** the existing temp-plus-rename artifact/manifest path remains the sole
   durable writer; the change moves its timing, not its atomicity discipline.
4. **Batch-end durability barrier:** explicit close/session flush drains lazy tasks and
   artifact mutations before completion is declared, preserving T930/T934 durability.

Focused behavioral suites:

```text
.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.storage.ChapterTranslationStoreLazyPersistenceTest --no-parallel --max-workers=1
6 tests, 0 failures, 0 errors

.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.storage.ChapterTranslationStorePersistenceTest --no-parallel --max-workers=1
2 tests, 0 failures, 0 errors

.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.storage.ChapterTranslationStoreDefunctTest --no-parallel --max-workers=1
6 tests, 0 failures, 0 errors

.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.pipeline.CleanedImagePublisherTest --no-parallel --max-workers=1
6 tests, 0 failures, 0 errors
```

## Item 4 — truth honesty

OCR now publishes `RUNNING` and emits the reading-stage event before chapter enumeration,
source decode, ONNX admission, or engine/session side work. Translation publishes
`RUNNING` before sorting, diagnostics, governor admission, and provider preparation.
Engine initialization failures are reflected as an OCR failure snapshot instead of leaving
the reader at a false pre-stage state. The feedback ordering contract remains
Reading Text -> Cleaning Bubbles -> Translating Text -> Finishing Page.

Focused behavioral suites:

```text
.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.reader.viewer.ReaderTranslationFeedbackTest --no-parallel --max-workers=1
16 tests, 0 failures, 0 errors

.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.reader.viewer.ReaderManualOutcomeTruthTest --no-parallel --max-workers=1
15 tests, 0 failures, 0 errors
```

The unit contract verifies admission ordering. Device tap-to-truth timing remains a
device/orchestrator measurement; no unsupported device latency number is claimed here.

## HF-03 hotfix — AUTO lazy cleaned-bitmap ownership and fallback truth

The device evidence in `team/hf-03-evidence/` and `%TEMP%/opencode/live_capture2.txt`
showed `CleanedImagePublisher` failing with `Can't compress a recycled bitmap` on the
AUTO lane. The failing ownership sequence was the lazy JPEG enqueue in
`CleanedPublication` followed by the eager recycle at `PreparedPageBoundary`: the flush
worker still owned the same bitmap when the AUTO boundary recycled it. Inpaint had
already completed, but the publication exception then poisoned inpaint/render truth and
left the reader with no cleaned image.

The recycle audit found the active boundary as the only lazy race. The deferred reader
release in `SinglePageHttpRenderPhase` already flushes before recycling; retry and ONNX
resume paths are synchronous, and batch/held-bitmap paths do not use the lazy publisher.
The fix therefore adds the existing `StorePersistenceScheduler.flush()` ownership
barrier before the active boundary recycles a bitmap when lazy persistence is enabled.
Probe stores retain their synchronous behavior.

Lazy cleaned-publication failure now records `originalImageFallback=true`, keeps the
translated blocks, clears the cleaned-image name, restores inpaint/render to `READY`,
and clears the publication error. `PageDisplayProjection` and the reader overlay
binding treat this state as display-ready, so the original page image remains visible
with the translated overlay. Retry/reset paths clear the fallback marker. Successful
cleaned publication also clears it.

Behavioral coverage added in `68034b4`:

```text
.\gradlew.bat :app:testStandardDebugUnitTest --tests eu.kanade.translation.scheduling.PreparedPageRuntimeBoundaryTest --tests eu.kanade.translation.model.PageDisplayProjectionTest --no-parallel --max-workers=1
18 tests, 0 failures, 0 errors
```

The runtime-boundary test asserts the exact `flush -> recycle` ordering under lazy
AUTO pressure. The display-projection test asserts that a cleaned-publication failure
does not surface an error, keeps inpaint/render ready, and binds the translated blocks
over the original image.

## Verification

Environment: `JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr`.
Dev tasks temporarily used `app/src/standard/google-services.json` copied to
`app/google-services.json`; every command removed the temporary file in a `finally` block,
and the file is absent from the final worktree.

Baseline compile both flavors:

```text
.\gradlew.bat :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin --no-parallel --max-workers=1
BUILD SUCCESSFUL (20m 5s)
```

Baseline full Dev gate (before the AUTO bitmap hotfix):

```text
.\gradlew.bat :app:testDevDebugUnitTest --no-parallel --max-workers=1
BUILD SUCCESSFUL; 2,043 tests, 0 failures, 0 errors, 0 skipped
```

Baseline full Standard gate (before the AUTO bitmap hotfix):

```text
.\gradlew.bat :app:testStandardDebugUnitTest --no-parallel --max-workers=1
BUILD SUCCESSFUL; 2,043 tests, 0 failures, 0 errors, 0 skipped
```

The full suites are load-sensitive. Earlier attempts were preserved rather than hidden:

| Run | Failure signature | Isolation outcome |
| --- | --- | --- |
| Full Dev attempt 1 | `BatchDispatchResumeWiringTest`: run stayed in `TRANSLATE` after 1/2 pages with the test's explicit load-induced typed-pause message | 3/3 isolated green |
| Full Standard attempt 1 | `StandardLaneMultiPageCompletionTest`: expected `TRANSLATED`, got `ERROR` | 3/3 isolated green |
| Full Dev attempt 2 | `BatchDispatchResumeWiringTest` plus `StandardPipelineCoexistenceTest` | each affected class 3/3 isolated green |
| Full Standard attempt 2 | `BatchDispatchResumeWiringTest` plus `StandardPipelineCoexistenceTest` | each affected class 3/3 isolated green |
| Full Standard attempt 3 | `BatchDispatchResumeWiringTest`: run remained `TRANSLATE`, `pagesTranslated=1` | 3/3 isolated green |
| Baseline final full passes | none | Dev and Standard both 2,043/2,043 green |

### Post-hotfix verification addendum

The post-hotfix flavor compile completed successfully:

```text
.\gradlew.bat :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin --no-parallel --max-workers=1
BUILD SUCCESSFUL in 2m 40s
```

The required StandardLane isolation policy was satisfied before the full gates:

```text
.\gradlew.bat :app:testDevDebugUnitTest --tests eu.kanade.translation.coexistence.StandardLaneMultiPageCompletionTest --no-parallel --max-workers=1
```

Runs 1, 2, and 3 were each green (2 tests, 0 failures, 0 errors). Their XML files are
`hf03-hotfix-standardlane-isolation-1.xml`, `hf03-hotfix-standardlane-isolation-2.xml`,
and `hf03-hotfix-standardlane-isolation-3.xml`.

The post-hotfix full gates were:

```text
.\gradlew.bat :app:testStandardDebugUnitTest --no-parallel --max-workers=1
BUILD SUCCESSFUL; 2,045 tests, 0 failures, 0 errors, 0 skipped

.\gradlew.bat :app:testDevDebugUnitTest --no-parallel --max-workers=1
2045 tests completed, 1 failed: StandardLaneMultiPageCompletionTest.fresh standard batch translates every page of a multi-page chapter
```

The Dev failure is the same load-sensitive `expected:<TRANSLATED> but was:<ERROR>`
family observed before this hotfix; the class was green in all three required isolated
runs. The full-run XML is preserved as
`full-dev-HF03-hotfix-StandardLaneMultiPageCompletionTest-failure-final.xml`; it has
no new page-error reason string, only the existing native-lane trace. No assertion or
production change was made to hide this residual.

The XML reports for all listed failures and isolation retries are retained under
`team/hf-03-evidence/`. These are existing coexistence/load-sensitive families; no
production change was made in response because the affected classes passed in isolation.

Post-hotfix APK build and inspection:

```text
.\gradlew.bat :app:assembleDevDebug --no-parallel --max-workers=1
BUILD SUCCESSFUL (1m 56s)
APK: app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk
```

`jar tf` inspection of all five Dev APK outputs (including the universal APK) produced
the same counts:

```text
best_int8.onnx: 0
assets/models/ocr/**/*.md|yml|gitattributes: 0
assets/models/segmentation/manga109_bubble_int8.onnx: 1
assets/models/ocr/**/inference.onnx: 2
  assets/models/ocr/paddle-v6-small/inference.onnx
  assets/models/ocr/paddle-v6-small/det/inference.onnx
```

The assemble log contains existing D8 Kotlin-metadata warnings, but the task completed
successfully and the APK inspection passed. No `app/google-services.json` remains.

## Anomalies and scope notes

- This repository uses `dev` and `standard` product flavors, so verification used
  flavor-qualified Gradle tasks rather than ambiguous generic `Debug` task names.
- Item 2's requested implementation was intentionally not performed after its stop-gate
  failed; its mechanism is addressed by the item 3 publication-path change.
- No NNAPI/hardware-routing, asset source, session-gating, HF-01 keying, or durability
  contract outside the requested persistence timing seam was changed.
