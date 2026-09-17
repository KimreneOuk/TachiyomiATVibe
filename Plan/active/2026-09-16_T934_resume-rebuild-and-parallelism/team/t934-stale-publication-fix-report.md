# T934 — Stale publication fix: implementation report

Task: `Plan/active/2026-09-16_T934_resume-rebuild-and-parallelism`
Brief: `team/t934-stale-publication-fix-brief.md`
Implementer worktree: `worktrees/TachiyomiAT-1.16.8-dev/orchestrate_execution_order_v3`

## Summary

All four brief changes implemented:

1. **Process-wide per-document-name publication lock** in `AtomicChapterDocuments.publish`,
   with WARN logcat on every false-return path.
2. **T924 LI-4 one-shot stale-manifest retry extended** to the four batch-resume seams:
   `openCandidate`, `retireActiveRun`, `persistLiveCandidate`, `promoteLiveCandidate`.
3. **Durable-failure RECORD failure made non-fatal** in `ChapterProfileBatchCoordinator`
   (WARN + continue; never escalates the run).
4. **Completion oracle** (presentation-side): a run with failure/attention pages can
   never render "Completed / All pages translated"; predicate is testable and tested.

No git commands, no gradle commands, no emulator/device use (orchestrator builds).
All touched files verified brace/paren-balanced with string literals stripped.

## Per-file changes

### Main sources

- `app/src/main/java/eu/kanade/translation/artifact/ChapterDocumentIo.kt`
  - Companion object gained `publicationLocks = ConcurrentHashMap<String, Any>()` and
    `internal fun lockFor(name) = publicationLocks.computeIfAbsent(name) { Any() }`
    (companion state on purpose: the racing writer may hold a *different*
    `AtomicChapterDocuments` instance, so instance-level locking is insufficient).
    Same-named documents in different chapters share a lock — costs concurrency, never correctness.
  - `publish` body wrapped in `kotlin.jvm.synchronized(lockFor(name)) { ... }`, covering
    the whole write→readback-validate→backup-rotation→promote-rename sequence.
  - WARN logcat on each of the four false paths, tagged
    `"TachiyomiAT chapter document publish failed: stage=<stage> name=<name>"`;
    stages: `tmp-write`, `tmp-readback-validate`, `backup-rotation`, `promote-rename`.
    No payload bytes logged. Lock order is store-monitor → doc-lock only (no reverse
    acquisition anywhere), so no deadlock.
- `app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt`
  - Four seams converted to wrapper + `...Once` pattern, retry semantics identical to
    the T924 LI-4 checkpoint seam. `@Synchronized` stays on the public wrapper only;
    the `...Once` bodies are lock-free re-runs against the fresh manifest.
    - `retireActiveRun` → `retireActiveRunOnce` (seam `retireActiveRun`)
    - `openCandidate` → `openCandidateOnce` (seam `openCandidate`)
    - `persistLiveCandidate` → `persistLiveCandidateOnce` (seam `persistLiveCandidate`);
      `persistLiveCandidateAndFailure` delegates to the wrapper and inherits the retry
      (documented in-code).
    - `promoteLiveCandidate` → `promoteLiveCandidateOnce` (seam `promoteLiveCandidate`)
  - Each wrapper: `retryOnStaleManifest(firstAttempt = xOnce(...), callerManifest = manifest,
    seam = "...") { fresh -> xOnce(fresh, ...) }`. Contract preserved: stale on first
    attempt → exactly one retry on the fresh manifest; retry also stale/manifest vanished →
    original Rejected returned; non-stale reasons (identity drift, candidate mismatch,
    dependency fingerprint changed, publication failure) returned as-is with no retry.
  - `STALE_MANIFEST_REJECTION_REASON` doc and `retryOnStaleManifest` javadoc updated to
    list all six wrapped seams; RecordOutcome (durable failure recording) explicitly
    documented as out of scope.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt`
  - Companion `persistDurablePreflightFailure`: the `PatchResult.Rejected` branch no
    longer throws `IllegalStateException`; it logs
    `"TachiyomiAT t924 preflight durable failure record rejected pageHash=... reason=..."`
    at WARN and returns normally. The run is already failing; the failure *record* being
    rejected must not escalate it or alter what counts as a run failure.
  - `recordPageFailure`'s existing try/catch left unchanged as defense in depth.
- `app/src/main/java/eu/kanade/translation/ui/TranslationUiTruth.kt` (presentation truth layer)
  - New T934 completion-oracle section:
    - `pageNeedsAttention(page)`: false for display-ready non-partial pages (existing
      chip precedence: display-ready wins), true for FAILED stage, FAILED AI state, or partial.
    - `hasPagesNeedingAttention(snapshot)`: failedCount > 0 || nonDurableFailure ||
      groupedFailures nonEmpty || any page needs attention.
    - `pagesNeedingAttentionCount(snapshot)`: 0 when clean, else
      `max(pageNeedsAttention count, failedCount, 1)` (floors at 1 so a terminal
      attention state is never rendered as empty).
    - `isCompletedOutcome(snapshot)`: FINISHED batch phase AND not aborted AND no
      non-durable failure AND no pause AND state != ERROR/PAUSED AND no pages needing
      attention. Rationale: `TranslationBatchPhase.FINISHED` also maps from
      `Translation.State.ERROR`/`READY_WITH_WARNINGS` (see
      `TranslationProgressSnapshot.compute`), so FINISHED alone is not "completed".
  - `BatchStatusLineKind` gained `ATTENTION_REQUIRED` (placed after COMPLETED). Both
    `when (kind)` consumer sites (`TranslationProgressSheet.kt` ~1030, `BottomReaderBar.kt`
    ~80) have `else` branches, so exhaustiveness is unaffected; the FINISHED branch of
    `batchPhaseStatusLine` now routes completed outcomes to COMPLETED (unchanged fallback
    formatting) and finished-with-attention to ATTENTION_REQUIRED
    (`"N pages need attention"`).
- `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt`
  - `batchStatusHeaderSubtitle`: FINISHED branch now gates on
    `TranslationUiTruth.isCompletedOutcome(snapshot)`; completed keeps the old
    "All pages translated and ready to read" copy, otherwise
    `"N pages need attention"` — the device bug ("Completed / All pages translated"
    over a failed page) cannot render anymore.
  - `LiveStatusPill`: `isTerminal` (pill) now requires `isCompletedOutcome`; new
    `finishedWithAttention = FINISHED && !isTerminal`. Pill color: terminal → SuccessGreen
    (unchanged); finished-with-attention + state==ERROR → error color; otherwise
    finished-with-attention → WarningAmber. Pill text: terminal → "Completed" (unchanged);
    finished-with-attention + ERROR → "Failed"; otherwise "Ready (Warnings)".
  - The OUTER `isTerminal` (Cancel-button affordance, `FINISHED && !isSnapshotPaused`)
    deliberately left unchanged — it drives whether Cancel is offered, not completion copy.
- `app/src/main/java/eu/kanade/translation/model/BatchHeroProjection.kt`
  - The COMPLETED arm of `isTerminal` now first checks for attention present
    (`nonDurableFailure || pauseReason != null || state == PAUSED || groupedFailures
    nonEmpty`; aborted/ERROR/failedCount>0 already route to FAILED_NO_PAGES via
    `isErrorState` above it) and returns `Phase(FAILED_NO_PAGES, isError = true)`
    instead of COMPLETED when attention is present. Done inline with a comment because
    `model` cannot import `ui` (no such import exists in the codebase); the check is an
    exact documented mirror of `TranslationUiTruth.hasPagesNeedingAttention`'s
    snapshot-level terms. Per-page terms cannot be mirrored here without a layering
    violation; the hero already routes failedCount>0 to the error phase, and the
    partial-page-without-failedCount edge is not reachable on the device trace's path
    (documented as a known residual, see Deviations).

### Tests

- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreStaleManifestRetryTest.kt`
  (extended; no existing assertion weakened)
  - New helpers: `authorityFlipFixture()` (loadOrMigrate → authority flip → publishManifest),
    `li4RunRecord(runId)` (frozen-config run record for publishActiveRun fixtures).
  - New tests (one per contract clause per seam):
    1. `openCandidate with a stale snapshot retries once and preserves the concurrent verify marker`
    2. `openCandidate whose fresh state also drifted still rejects without a second retry`
    3. `openCandidate with a genuinely wrong page version still rejects as-is with no retry`
    4. `persistLiveCandidate with a stale snapshot retries once and preserves the concurrent verify marker`
    5. `persistLiveCandidate with a fresh manifest and a wrong generation still rejects as-is`
    6. `promoteLiveCandidate with a stale snapshot retries once and preserves the concurrent verify marker`
    7. `promoteLiveCandidate with a fresh manifest and drifted dependencies still rejects as-is`
    8. `retireActiveRun with a stale snapshot retries once and preserves the concurrent verify marker`
- `app/src/test/java/eu/kanade/translation/artifact/AtomicChapterDocumentsPublicationLockTest.kt`
  (new) — file-backed `FakeChapterDocumentIo` shared by two `AtomicChapterDocuments`
  instances; 25 rounds of two threads racing `publish` on the same document name behind a
  `CountDownLatch` start barrier. Asserts both futures `true`, the durable value equals one
  of the two complete payloads, and orphan tmp files ≤ 1 per round; after all rounds no
  `.tmp`/`.corrupt` residue. Second test: concurrent publishes to different names both
  succeed (no cross-name serialization).
- `app/src/test/java/eu/kanade/presentation/manga/components/T934CompletionOracleTest.kt`
  (new, 11 tests) — lives in the presentation package to reach internal
  `batchStatusHeaderSubtitle`. Covers: clean finish → completed (truth + COMPLETED kind +
  subtitle); failed page → ATTENTION_REQUIRED and subtitle never contains "All pages
  translated"; partial page; AI-failed non-display-ready page; failure groups;
  non-durable failure; ERROR state; paused; aborted; non-FINISHED phase never completed;
  count floors at 1; count reflects failedCount=3.
- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreRetireActiveRunTest.kt`
  (adapted — see Deviations) — stale-rejection test replaced by the retry-contract
  equivalent; new publication-failure-still-rejects test added.
- `app/src/test/java/eu/kanade/translation/artifact/ChapterArtifactStoreTest.kt`
  (adapted — see Deviations) — concurrent-opens test now expects two Committed outcomes
  collapsing onto a single shared generationId.

## Contract mapping to the brief

- "retry-that-also-fails or vanished manifest returns Rejected" → `retryOnStaleManifest`
  unchanged (returns the original first-attempt outcome when the fresh read is not stale
  / manifest gone); exercised by tests 2, 3, 5, 7 above and the new
  RetireActiveRun publication-failure test.
- "non-stale reasons as-is" → asserted verbatim where the original suite pinned exact
  strings (`"manifest publication failed; active run pointer unchanged"`).
- "do not wrap demote/delete/reset/user-action seams" → untouched: `demoteCommittedPage`,
  delete/reset paths, and user-action seams keep their single-attempt semantics.
- "recordDurableFailure's RecordOutcome explicitly out of scope" → store body untouched;
  only the coordinator's *handling* of a rejected record became non-fatal (change 3).

## Deviations from the brief (with reasons)

1. **`roles/implementer.md` not found** — neither in the worktree root, `worktrees/roles/`,
   nor the main workspace. Proceeded with the task brief as the authoritative contract.
2. **`ChapterArtifactStoreRetireActiveRunTest.kt` adapted despite the brief listing it as
   "must stay green unchanged"** — the brief's change 2 explicitly wraps `retireActiveRun`,
   which directly contradicts that file's existing stale-rejection test (stale snapshot +
   pointer-unchanged Rejected). Wrapping makes the same scenario return Committed via
   exactly one fresh-read retry. Resolved in favor of the implementation contract, following
   the exact precedent from when LI-4 wrapped `checkpointOcr`
   (`CheckpointOcrTransactionTest` "stale caller snapshot recovers through the one-shot
   retry and commits the checkpoint"): the stale test became a retry-recovery test with
   equivalent assertion strength (pointer retired, durable manifest consistent), and a new
   as-is-rejection test pins the non-stale publication-failure path. No assertion was
   weakened; the old behavior the old test pinned is now specified as recovery.
3. **`ChapterArtifactStoreTest.kt` concurrent-opens test adapted** — with `openCandidate`
   retrying, the second racer's stale-CAS rejection now recovers into the idempotent-reuse
   branch (same origin + dependency fingerprint), so both dispatches commit onto ONE
   candidate generation instead of the second surfacing a spurious Rejected. The test now
   expects 2 × Committed with a single shared generationId and the durable candidate +
   `activeCandidateGenerationIds` equal to it — strictly stronger than before. This file was
   not on the brief's must-stay-green list.
4. **`BatchHeroProjection.kt` gate added beyond the two named presentation files** — the
   brief's "a run with failure/attention pages must NEVER render Completed" applies to the
   hero COMPLETED surface too; without this gate the hero label could still say Completed
   for grouped-failure/paused/non-durable-failure terminal runs. Implemented as a documented
   inline mirror of the snapshot-level oracle terms (model→ui import would be a layering
   violation with no precedent). Residual: per-page partial-page attention is not mirrored
   in the hero (would require the import); the sheet/pill/truth oracle covers it, and
   failedCount>0 already routes the hero to FAILED_NO_PAGES.
5. **New presentation vocabulary** — `BatchStatusLineKind.ATTENTION_REQUIRED` and
   `pagesNeedingAttentionCount` are presentation-state only; no pipeline/store state was
   added or altered. Both existing `when (kind)` consumers keep `else` branches, so the
   enum extension cannot break exhaustiveness elsewhere.
6. **Memory note (accepted by design)** — the process-wide publication-lock map grows by
   one small monitor object per distinct published document name (per chapter × document
   type, not per page or per publish). This is the deliberate cost of the cross-instance
   mutual exclusion the brief requires; entries are tiny and bounded by chapter-document
   vocabulary. If unbounded growth ever matters, an eviction pass can be added later
   without contract change (only under a global quiescence point).
7. **No build/test run** — hard constraint: the orchestrator builds. Static verification
   performed instead: brace/paren balance on all 10 touched files with string literals
   stripped (all balanced), `when`-exhaustiveness audit for the new enum value (safe),
   logcat JVM safety (existing retry tests already exercise it), internal-visibility
   access from test packages (established pattern in this suite).

## Verification notes for review

- Lock order audit: every `synchronized(lockFor(name))` acquisition happens inside
  `publish` while the store monitor may already be held by the same thread; no code path
  takes the store monitor while holding a doc lock. Single lock order ⇒ no deadlock.
- `kotlin.jvm.synchronized` is inline, so the expression-bodied `publish` may still
  `return false` from inside the lambda — behavior identical to pre-lock code.
- All pre-existing tests that call the four wrapped seams were audited for stale-manifest
  + Rejected expectations: only the two conflicts above existed; all other suites
  (`GroupCommitSliceBTest`, `ChapterCommitPointContractTest`, `CheckpointOcrTransactionTest`,
  `OcrCheckpointRestartReuseTest`) chain fresh manifests or reject on non-stale reasons and
  are unaffected.
- Presentation suites audited: `T934ReaderBarTruthTest`'s finished case uses a clean
  snapshot (still COMPLETED); `BatchHeroProjectionTest`'s COMPLETED cases use clean
  snapshots (still COMPLETED). No existing assertion weakened anywhere.
