# T924 wave 3, slice B — S3 remainder: durable failure ledger (R2 + gap 9)

Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline` (base HEAD `ee858f9`). No commits made (per instruction). Owner: Implementer, slice B.

## 1. Legacy idiom anchors (READ-ONLY study)

| Anchor | Location |
|---|---|
| `persistUnexpectedBatchStageFailure` (the legacy durable-failure idiom) | `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:854` |
| Call site after a non-COMPLETED pass (`unexpectedStage != null`), before reconciliation; marks `durableFailurePageKeys` so teardown takes the release-only branch | `BatchChapterTranslator.kt:726-746` |
| Teardown branch split: durable-record pages get `releasePageStageLease` only; pages WITHOUT a record get `cancelPageStageWork` (B0 candidate cancel) | `BatchChapterTranslator.kt:816-822` (finally) |
| One-charge-per-attempt guard (`attemptCharged`), increments `attemptCount`+`retryCount` | `app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt:142` (`recordAttemptFailure`) |
| Attempt cap constant `MAX_CONSECUTIVE_UNRESOLVED = 3` + `consecutiveUnresolved` map | `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactManifest.kt:359,369` (`ChapterAttemptLedgerDocument`) |
| Durable failure ledger storage: `ChapterArtifactManifest.durableFailures: Map<String, DurableFailureMetadata>`, key `"$pageKey:${stage.name}"` | `ChapterArtifactManifest.kt:36`; `ArtifactContracts.kt:200` (`DurableFailureMetadata`) |
| Atomic ledger write API (page patch + manifest failure record in ONE publication) | `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:688` (`persistDurableStageFailure`); batch precedent `pipeline/batch/BatchWriteGate.kt:139-205` (`persistAiFailure`) |
| Cap vocabulary precedent (INTERRUPTED-class, manual-retry-only at cap) | `ChapterTranslationStore.kt:415` (`applyAttemptCapPause`) |
| Ledger read | `ChapterTranslationStore.kt:368` (`durableFailuresSnapshot`); key format `store/StoreStatusProjector.kt:49-54` |
| `cancelCandidate` strips candidate-owned stage failure records | `artifact/ChapterArtifactStore.kt:1343-1378` (`isCandidateOwned` / `candidateFailureKeys`) |

## 2. Recorder design

`pipeline/batch/ChapterProfileBatchCoordinator.kt` (only edited production file):

- New constructor-injected recorder (default = real writer, so production wiring needs no shell change and `BatchChapterTranslator.kt` stays untouched):
  `failureRecorder: suspend (PreflightStageFailure) -> Unit` (line 107), default `{ persistDurablePreflightFailure(store, failure, nowEpochMs) }` — companion writer at line ~627. `PreflightStageFailure(pageKey, kind, reason)` + `PreflightFailureKind { CHECKPOINT_REJECTED, OCR_WORKER_FAILED }` at lines 69-84.
- Trigger points: `CheckpointOcrResult.Rejected` branch and the worker `catch` in `runPass1` set `pendingFailure` (typed store rejection reason verbatim, or `"$SimpleClassName: $message"` exception identity); the record is written in the page's `finally`.
- Ordering (TX-06 intact): releaseNativeHandoff → `store.cancelPageStageWork(pageKey, BATCH)` (B0 candidate teardown) → `recordPageFailure` → `releaseBatchLease` → `listener.ocrFinished`. Lease release stays strictly after the checkpoint attempt. Terminal semantics unchanged: the pass still returns `FAILED` anchored at the page; PAUSED stop-reason path untouched; the writer is best-effort (a rejected ledger write is logged, the FAILED outcome stands).
- Default writer mirrors the legacy idiom: `store.persistDurableStageFailure` publishes the page patch (`ocrStatus = FAILED`, `errorMessage = reason`, one `recordAttemptFailure()` charge — `attemptCharged` guard) and the `durableFailures["p2:OCR"]` record in one manifest publication. Precondition is built from a fresh `store.snapshot(pageKey)` (the `applyAttemptCapPause` pattern).

## 3. Cap semantics

`DurableFailureMetadata.retryCount` counts consecutive unresolved preflight attempts per page; read via `durableFailuresSnapshot()["$pageKey:OCR"]`, incremented while `< ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED (3)`. At the cap the count STOPS (never unlimited) and the record is re-stamped `FailureCategory.INTERRUPTED` with `"...; attempt cap reached (3 consecutive unresolved preflight attempts); manual retry required"` — the `applyAttemptCapPause` vocabulary. Category below cap: `PROTOCOL` for checkpoint rejections, `TRANSIENT` for worker exceptions. Status stays `FAILED_RETRYABLE`, `nextEligibleRetryAtEpochMs = null`.

## 4. Gap-9 test evidence (PARTIAL — see §7)

New `app/src/test/java/eu/kanade/translation/pipeline/batch/OcrPreflightRejectedMidRunDurabilityTest.kt` (329 lines, 2 tests, harness idioms copied from `OcrPreflightCoordinatorTest`; rejection injected via a stale `candidateGenerationId`, which `checkpointOcr` rejects with the typed reason `"candidate generation changed"`):

1. `checkpoint rejected mid run stays restartable and records the failure durably` — 3-page run, p2 rejects once: FAILED anchor p2; p1 checkpoint durable; durable record for p2 (retryCount 1, PROTOCOL, typed reason); p2 visibly FAILED; B0 teardown; simulated restart (`openArtifact`); next attempt re-OCRs only p2/p3 (p1 reused, `COUNTER_REUSED == 1`); full corpus + corpus fingerprint.
2. `repeated checkpoint rejections respect the consecutive unresolved attempt cap` — 4 restart cycles always rejecting p2: retryCount 1→2→3, then capped at 3 with INTERRUPTED + manual-retry message.

## 5. Diff summary / counts

- `ChapterProfileBatchCoordinator.kt`: +177/-? (types, recorder ctor param + default writer, `pendingFailure` wiring, `recordPageFailure`, companion `persistDurablePreflightFailure` + key helper). File now 720 lines.
- New test file (above): 2 tests. My other owned files untouched. No add/commit (per instruction).

## 6. Verification state

- `:app:compileStandardDebugKotlin` **BUILD SUCCESSFUL** (after foreign-file churn settled; zero errors in my files throughout).
- `:app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*"`: every PRE-EXISTING test in both packages passed; exactly my 2 new tests FAILED on the last full run, and still fail after one fix cycle. Stopped per orchestrator hard deadline.

## 7. Residual defects (open, precisely localized — for takeover)

1. **p2.errorMessage null after a successful ledger write** (page patch side): the atomic write lands the `durableFailures` record and `ocrStatus = FAILED`, but `errorMessage` reads null afterward. Suspect: `persistLiveCandidate` stores the failure as a candidate-owned `StageArtifactRecord` (`withStage`) and a status-projection/snapshot path regenerates the page without the message. Needs store-side diagnosis (store is READ-ONLY for this slice).
2. **Ledger record not visible to a restarted store**: in the cap test, cycle 2 (fresh `openArtifact` over the same documents) reads `durableFailuresSnapshot()["p2:OCR"] == null` — retryCount restarts at 1 instead of advancing to 2, so the cap never trips across restarts. The in-memory manifest carries the record (test 1 reads it on the same store), so either `publishManifestInternal` dropped `durableFailures` on that path or `openArtifact`'s manifest read filters them. Same suspected store read/write-side gap; both are store-level (foreign), not coordinator-level — the coordinator passes the metadata through `persistDurableStageFailure` exactly like `applyAttemptCapPause`/`persistAiFailure`.

## 8. Deviations / risks

- Deviation D1: the recorder is a constructor lambda **with a real default writer** (not a no-op): the store is already injected, so no shell callback was required and the production path is wired in this slice. Tests may still override it.
- Deviation D2: ledger write happens AFTER the B0 candidate cancel, not before: `cancelCandidate` strips candidate-owned stage records (`ChapterArtifactStore.kt:1357-1362`), so a record written first is destroyed by the teardown. Post-cancel order also matches the legacy shell (persist runs after the coordinator returns). Side effect: the post-cancel failure write re-opens a fresh BATCH candidate carrying the failed page snapshot — the same behavior the legacy `persistUnexpectedBatchStageFailure` path produces on candidate-less pages.
- Risk R-A: until defect §7.2 is fixed, the cap is enforced within a process lifetime only; across restarts the counter restarts (bounded per attempt, but not cumulative). Never unlimited within a run.
- Risk R-B: a successful later checkpoint does not clear a stale OCR failure record (no store API clears an arbitrary durable failure on success — `clearAttemptCapForManualRetry` is INTERRUPTED+TRANSLATION-scoped). Consecutive counts are therefore conservative (may cap early), and stale records linger for later-stage cleanup. Store-API gap recorded, not edited.
- Risk R-C: shared-worktree gradle races (3 concurrent agents) caused repeated infra failures (torn classes.jar, kotlin-daemon kills); WP9's completion marker was used as the gate for the final test attempts, per orchestrator directive.
