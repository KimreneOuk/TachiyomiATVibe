# HF-04 implementation report: batch page-key and dependency-fingerprint rejection

## Result

HF-04 is implemented on `t936/hotfix-batch-rejection`. The device failure was
not an unpadded-key derivation bug: the batch path preserves the exact source
name (`001.jpg`). The rejection was a lazy-persistence ordering bug. OCR
preflight could snapshot the live store before the queued registration had been
flushed to the durable artifact manifest, then validate a stale candidate
generation/fingerprint. The resulting manifest miss and fingerprint cascade
were surfaced as a generic provider outage.

Implementation commit: `0a38c0a fix(translation): close lazy preflight page-key barrier`.
Ticket commit: `aa092ad docs(plan): HF-04 batch page-key fingerprint rejection ticket`.

## Investigation and stop-gate result

The source-name trace was checked through `ChapterTranslator`, page ordering and
pre-registration, `BatchChapterTranslator`, `BatchLaneWorkers`,
`PreflightWorker`, and `ChapterTranslationStore`:

| Boundary | Observed key behavior |
| --- | --- |
| Chapter/page enumeration | `entry.name` is retained verbatim; natural sorting does not rewrite `001.jpg`. |
| Batch pre-registration | The live page and pending artifact registration use the exact `001.jpg` key. |
| OCR preflight/checkpoint | The checkpoint lookup uses the same page key, but the lazy queued registration could still be absent from the durable manifest at snapshot time. |
| Candidate writes/reconciliation | The candidate identity is deterministic for the content and generation; validation rejects a stale expected fingerprint rather than silently accepting it. |

Therefore the padded-name theory was falsified and no key-normalization change
was made. The device `page missing: pageKey=001.jpg` message came from the
artifact-manifest checkpoint boundary. HF-03's memory-first lazy publication
made the durability ordering visible: preflight needed a barrier before taking
its expected candidate identity.

## Production changes

1. `PreflightWorker.checkpointPage` now detects lazy persistence, calls the
   store's `flush()` outside the store mutex, and only then reads the fresh
   snapshot. The flush worker remains serialized by the existing scheduler;
   checkpoint is not called from inside the flush task, avoiding a self-wait
   deadlock.
2. For lazy persistence only, preflight uses the post-flush candidate generation,
   artifact page version, and dependency fingerprint. Synchronous stores retain
   the prior reference-based validation semantics, including deliberate stale
   reference rejection tests.
3. Durable protocol failures now map to explicit UI truth instead of the
   provider-outage fallback: manifest mismatch, page fingerprint mismatch, and
   checkpoint rejection each have a distinct category. Provider outage text is
   still used when there is no durable protocol failure (for example governor or
   breaker admission failure).
4. The store and manager progress projections now consider retryable durable
   protocol failures when selecting a pause reason, not only translation-stage
   failures.

No HF-01 keying, session, durability contract, provider, NNAPI, or asset code
outside this seam was changed.

## Regression coverage

- `ChapterTranslationStoreLazyPersistenceTest.zero padded page keeps one candidate dependency across repeated lazy flushes`
  verifies `001.jpg` remains one page identity and its candidate generation and
  dependency fingerprint remain stable after repeated lazy flushes.
- `OcrPreflightCoordinatorTest.lazy preflight drains exact zero padded registration before checkpoint`
  verifies the exact padded key is present in the manifest and checkpoint after
  the lazy preflight barrier.
- `TranslationManagerPausedAffordanceTest` verifies a page-manifest rejection
  is rendered as `Page manifest mismatch: page missing: pageKey=001.jpg` and
  does not use provider-outage wording.

Focused verification:

```text
./gradlew.bat :app:testStandardDebugUnitTest --no-parallel --max-workers=1 \
  --tests eu.kanade.translation.ChapterTranslationStoreLazyPersistenceTest \
  --tests eu.kanade.translation.pipeline.batch.OcrPreflightCoordinatorTest \
  --tests eu.kanade.translation.TranslationManagerPausedAffordanceTest
```

Green: 17 tests, 0 failures/errors/skips.

```text
./gradlew.bat :app:testDevDebugUnitTest --no-parallel --max-workers=1 \
  --tests eu.kanade.translation.pipeline.batch.OcrPreflightRejectedMidRunDurabilityTest
```

Green: 2 tests, 0 failures/errors/skips. This specifically confirms that the
lazy-only identity refresh does not weaken the synchronous stale-reference
rejection contract.

## Full flavor gates

The required flavor-qualified tasks were used because this repository has `dev`
and `standard` product flavors; generic `Debug` task names are ambiguous.
Both full suites ran with one worker and no parallel execution:

```text
./gradlew.bat :app:testDevDebugUnitTest --no-parallel --max-workers=1
```

Final clean run: BUILD SUCCESSFUL; 2,047 tests, 0 failures, 0 errors, 0
skipped.

```text
./gradlew.bat :app:testStandardDebugUnitTest --no-parallel --max-workers=1
```

BUILD SUCCESSFUL in 4m17s; 2,047 tests, 0 failures, 0 errors, 0 skipped.

The Dev build temporarily copied
`app/src/standard/google-services.json` to `app/google-services.json` and
removed it in a `finally` block. The file is absent after verification and is
not part of the branch.

## Flake ledger and preserved evidence

An earlier full Dev run before the final clean gate exposed two known
load-sensitive test families. Neither reproduced in three isolated reruns, and
the final full Dev run was green. The XML evidence is preserved under
`team/hf-04-gate-evidence/`:

| Test | Failure signature | Run | Isolation outcome |
| --- | --- | --- | --- |
| `BatchDispatchResumeWiringTest.re-dispatch over a finished chapter costs zero work and republishes nothing` | `run 1 never reached COMPLETE within 10000ms` (observed state `TRANSLATE`) | Earlier full Dev run | `batch-dispatch-isolation-1.xml`, `-2.xml`, `-3.xml`: 3/3 green. |
| `StandardLaneMultiPageCompletionTest.fresh standard batch translates every page of a multi-page chapter` | Expected `TRANSLATED`, observed `ERROR` | Earlier full Dev run | `standard-lane-isolation-1.xml`, `-2.xml`, `-3.xml`: 3/3 green. |

The two signatures match the pre-existing load-sensitive translation test
family tracked during HF-02, not the padded-page or lazy-checkpoint failure
signature. The preserved failing XMLs are `dev-batch-dispatch-failure.xml`
and `dev-standard-lane-failure.xml`; all six isolation XMLs are retained for
review.

## APK inspection

```text
./gradlew.bat :app:assembleDevDebug --no-parallel --max-workers=1
```

BUILD SUCCESSFUL in 3m06s. APK inspected:

`app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk`

The archive contains the required live entries:

```text
assets/models/ocr/paddle-v6-small/inference.onnx
assets/models/ocr/paddle-v6-small/det/inference.onnx
assets/models/segmentation/manga109_bubble_int8.onnx
```

Checks returned zero matches for `best_int8.onnx` and zero `.md`, `.yml`, or
`.gitattributes` entries under `assets/models/ocr/`.

## Working-tree hygiene

The only uncommitted files before this report commit are the preserved XML
evidence files and this report. `app/google-services.json` is absent; no build
outputs or unrelated source changes are staged.
