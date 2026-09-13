# T924 LI Wave B — evidence report (LI-3, LI-4)

Code worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, HEAD `2a9f12f` (verified clean before work). No commits made, per instructions. All paths below are relative to `app/src/main/java/eu/kanade/translation/` unless noted.

## LI-3 — reader/manual cancel writes no longer punch through BATCH-held leases

Fix shape: call-site gating only — the store write primitives are untouched.

### New store query

- `ChapterTranslationStore.kt:549-565` — NEW `fun hasActiveBatchStageLease(pageKey: String): Boolean`: true while the page holds an ACTIVE page-stage lease acquired by `PageWriteOrigin.BATCH`. Implemented over the existing lock-free reader `PageStageLeaseTable.pageLeaseOwner` (`store/PageStageLeaseTable.kt:187-189`, `synchronized(pageLeases)`); takes the lease table's own monitor, never the store mutex, so it is safe inside `fastCancelInFlightStagesInMemory`'s lock-free path. KDoc documents the why (current-snapshot cancel writes carry the batch's own lease token and sail through the fence) and the benign TOCTOU vs a concurrent acquire/release.

### Gated cancel writers (scheduling/TranslationScheduler.kt)

- `scheduling/TranslationScheduler.kt:808-833` — `markPageCancelled(store, pageKey)` (the private helper behind EVERY scheduler cancel write) now skips pages where `store.hasActiveBatchStageLease(pageKey)` is true, logging INFO `"TachiyomiAT cancel skipped: page holds an active BATCH stage lease writer=markPageCancelled pageKey=..."` (bounded volume: one line per skipped page per cancel). This single gate covers all audited call sites:
  - per-page cancel button: `cancelPageTranslation` (:926 pre-fix numbering) → `runBlocking { markPageCancelled(...) }`;
  - `markChapterCancelledAsync` (:856) — auto-cancel path, itself invoked from :542/:566;
  - `markChapterCancelledSync` (:878) — reader-stop / master-toggle paths (:1037/:1091);
  - detached-session paths :410/:456 and :753.
  Deliberately NOT counted/behavior changes: the `flipped` counters are untouched (they count attempted keys, as before); lease-free pages take the identical write path as before.
- The gate sits AFTER the existing peek (no entry / already terminal → return), so terminal pages behave exactly as before.

### Gated in-memory fast cancel (ChapterTranslationStore.kt)

- `ChapterTranslationStore.kt:762-806` — `fastCancelInFlightStagesInMemory` now skips BATCH-leased running pages (preserving them UNCHANGED in the rebuilt map — an earlier draft dropped them; the test caught it, see Tests) and logs INFO `"... writer=fastCancelInFlightStagesInMemory pageKey=..."`. New early return `if (flipped == 0) return 0` avoids republishing an identical map when every running page was skipped (behavior-neutral: StateFlow conflates equal values anyway). Covers the scheduler's direct fastCancel call paths (:535/:556/:873 pre-fix numbering).
- This method mutates outside the store mutex by design; the guard reads the lease map under its existing monitor per the audit's minimal-change instruction. The documented confinement is unchanged (no main-thread claim exists; it is documented as the non-blocking reader-UI path).

### Chapter-level stop claim — VERIFIED

The user's chapter-level "stop translation" does NOT rely on these writes: it cancels the batch translator job itself (`ChapterTranslator.stop`/`cancelTranslatorJob`, `ChapterTranslator.kt:447-452` → `translationJob.cancel()`; the batch runs inside that job, `ChapterTranslator.kt:371/:678`). `BatchChapterTranslator` teardown releases every BATCH lease on all exits (`pipeline/batch/BatchChapterTranslator.kt:348/:845/:901/:941/:954`), so skipped per-page cancel writes never strand batch-owned page state. FF-01 OFF (legacy) lane behavior beyond the specified skip is unchanged: the legacy batch lane holds the same BATCH-origin leases (`pipeline/batch/BatchLaneWorkers.kt:851`), which is exactly what the specified skip protects; AUTO/MANUAL-origin leases are not skipped (auto cancel semantics preserved).

## LI-4 — stale-manifest CAS no longer fails the flagged lane's first publication

Fix shape: one-shot stale retry INSIDE `artifact/ChapterArtifactStore.kt`; caller-visible contract stays "Committed or Rejected"; the >8-page open path stays asynchronous and the background verify is untouched.

- `artifact/ChapterArtifactStore.kt:1615-1619` — NEW `STALE_MANIFEST_REJECTION_REASON = "stale manifest snapshot"`; `staleManifestRejection` (:1621-1629) now builds its reason from the constant — byte-identical reason strings.
- `artifact/ChapterArtifactStore.kt:1631-1635` — NEW `TransactionOutcome.staleManifestRejectionOrNull()`: the rejection reason iff the outcome was rejected BY the stale-manifest CAS specifically (prefix match). Every other rejection reason (identity drift, publication failure, future-schema guard) is never retried.
- `artifact/ChapterArtifactStore.kt:1637-1668` — NEW `retryOnStaleManifest(firstAttempt, callerManifest, seam, retry)`: on a stale CAS rejection, re-reads the durable manifest ONCE (`readManifest()`; vanished manifest → original rejection returned), WARN-logs `"TachiyomiAT artifact stale manifest retried once: seam=... chapter=... staleUpdatedAt=... freshUpdatedAt=..."` (updatedAtEpochMs is the manifest's only monotone marker; there is no numeric generation on `ChapterArtifactManifest`), and retries the publication ONCE against the FRESH manifest. A retry that also fails returns its outcome as today. Exactly one retry — no loop.
- `artifact/ChapterArtifactStore.kt:330-402` — `publishActiveRun` is now a `@Synchronized` wrapper; the verbatim body moved to private `publishActiveRunOnce` (:360-402). The rebuild IS a re-derivation: the body's `updatePointers = { current -> current.copy(activeRun = ...) }` installs the pointer onto whatever manifest it is given, so the retry merges the run pointer onto the FRESH manifest — the concurrent publication's changes are never reverted. Callers unaffected except the race stops surfacing.
- `artifact/ChapterArtifactStore.kt:449-540` — same treatment for `checkpointOcr` (wrapper) + private `checkpointOcrOnce` (:512-..., body verbatim). The retry re-runs the WHOLE transaction against the fresh manifest, so genuine concurrent drift still rejects — with a non-stale reason on the retry attempt — and the CLOSE/REBASE/adopt rebuild (`manifest.copy(pages = manifest.pages + ..., ocrCheckpoints + ..., durableFailures - ...)`) is derived from the fresh manifest. On the stale path the first attempt rejected BEFORE any sidecar publication (the CAS check is the first statement of the body), so the retry publishes sidecars exactly once — the TX-11/B1-B2 orphan guarantees are unchanged.
- `publishSidecarPointers` and all other publishers intentionally unchanged (generalization = LI-8, see below).

## Tests (RED-first; new files under `app/src/test/java/eu/kanade/translation/`)

### T1/T2 — LI-3 (`CancelBatchLeaseSkipTest.kt`, new; 5 tests)

Level tested: the narrowest REAL seams — the real `TranslationScheduler.markPageCancelled`/`markChapterCancelledSync`/`markChapterCancelledAsync`/`cancelPageTranslation` and the real `ChapterTranslationStore.fastCancelInFlightStagesInMemory`, over a real store with a real BATCH lease acquired via `tryAcquirePageStageLease` (same harness idiom as `CancelSyncStoreWriteTest`).
- `markChapterCancelledSync leaves a BATCH-leased page untouched and cancels the lease-free page` (:100) — RED CONFIRMED: `expected:<"RUNNING"> but was:<"CANCELLED">` (the cancel wrote through the batch lease) → GREEN.
- `per-page cancel leaves a BATCH-leased page untouched` (:122) — RED CONFIRMED: `expected:<"RUNNING"> but was:<"CANCELLED">` → GREEN.
- `markChapterCancelledAsync leaves a BATCH-leased page untouched` (:148) — RED CONFIRMED: same → GREEN.
- `fastCancelInFlightStagesInMemory flips only the lease-free page` (:166, T2) — RED CONFIRMED twice over: first `expected:<"RUNNING"> but was:<"CANCELLED">`, and after the first guard draft `expected:<"RUNNING"> but was:<null>` — the draft's skip branch dropped the leased page from the rebuilt state map entirely (neither `put` reached). This failure is exactly why the guard now preserves the page UNCHANGED in the map. Final: `flipped shouldBe 1`, leased page RUNNING, free page CANCELLED.
- `a released (expired) lease no longer blocks the cancel write` (:134) — companion, GREEN BOTH BEFORE AND AFTER (legacy behavior preserved: acquire → release → cancel flips the page). This was the only pre-fix-green test in the file, confirming the RED set is lease-specific.

### T3/T4 — LI-4 (`artifact/ChapterArtifactStoreStaleManifestRetryTest.kt`, new; 3 tests)

Level tested: real `ChapterArtifactStore` over `FakeChapterDocumentIo` (idioms from `ChapterArtifactStoreRetireActiveRunTest` / `CheckpointOcrTransactionTest`); the concurrent writer mirrors the REAL LI-4 writer — a verify-style republish of `legacyMigration = VERIFIED` + bumped `updatedAtEpochMs` behind the caller's back (`LegacyArtifactRescue.kt:264-274`).
- T3 `publishActiveRun with a stale snapshot retries once and preserves the concurrent verify marker` (:75) — RED CONFIRMED: `Rejected(reason=stale manifest snapshot: chapter=Chapter 1)` (XML line captured) → GREEN: Committed, `activeRun` pointer installed on the BUMPED manifest, `legacyMigration.health == VERIFIED` preserved, `updatedAtEpochMs == 3L` (the publication's own stamp).
- T4 `checkpointOcr with a stale snapshot retries once and preserves the concurrent verify marker` (:264) — RED CONFIRMED: same Rejected reason → GREEN: Committed, checkpoint pointer installed, BATCH candidate cleared, `pageVersion + 1`, verify marker preserved.
- `stale snapshot whose fresh state also drifted still rejects without a second retry` (:292) — negative contract: after a concurrent CLOSE of the caller's candidate, the stale caller (old pageVersion, old generation) rejects on the retry with the REAL drift reason (`"stale page version"`, not the stale CAS), exactly one retry, concurrent checkpoint pointer untouched, stale sidecar never reaches disk.
- Pre-fix RED run (both new files, `7 tests completed, 6 failed`): the 2 artifact tests failed with `Rejected(reason=stale manifest snapshot: chapter=Chapter 1)`; the 4 lease tests failed with RUNNING→CANCELLED / flipped-count mismatches; the released-lease companion passed.

### Amended pre-fix tests (contract updated on the changed seams; intent preserved)

- `artifact/SidecarCrashPublicationTest.kt:231-248` — `stale manifest snapshots are rejected before any sidecar is written` → renamed `stale manifest snapshots recover through the one-shot retry`: same scenario (caller copy mutated to `updatedAtEpochMs = 2L`), now asserts Committed + run-record sidecar + durable pointer. The old "no sidecar on stale rejection" protection remains covered by the file's fault-injection tests (sidecar write failure, rename failure — untouched and green).
- `artifact/CheckpointOcrTransactionTest.kt:481-504` — same rename/pivot for the checkpoint seam: asserts the retry commits the CLOSE from the FRESH manifest (pointer installed, candidate cleared, version+1, both sidecars durable). Rejection-path protections remain covered by the file's `stale candidate identity rejections` and fault-injection tests (untouched, green).

## Suite results

Required targeted batch (one run, 117 tests, 116 passed): all of Stage7FinalizeCoordinatorTest, Stage7FinalizeResumeCoordinatorTest, BatchDispatchResumeWiringTest, OcrPreflightFlagOffMidRunTest, ProfilePipelineDispatchGateTest, AnalysisChunkValidationTest, BatchPostPassProjectionTest, StoreStatusProjectorRunRecordTest, ResetRetiresActiveRunTest, ChapterArtifactStoreRetireActiveRunTest, plus the `*Scheduler*/*Cancel*/*Lease*/*Checkpoint*/*ArtifactStore*` grep set (MangaScreenModelCancelledBatchReconciliationTest, ChapterArtifactStoreTest, CancelBatchLeaseSkipTest, CancelSyncStoreWriteTest, D6DrainNotCancelTest, T918CancelledBatchRestartTest, OcrCheckpointRestartReuseTest, OverlapSchedulerTest, TranslationSchedulerTraceTest, Checkpoint2IntegrationTest) — except one flake, see below.

FINAL sweep `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`: **BUILD SUCCESSFUL — 242 suite XMLs, 1782 tests, 0 failures, 0 errors, 0 skipped** (wave-A baseline 240/1774/0 + exactly the 2 new suites / 8 new tests). `MangaScreenModelCancelledBatchReconciliationTest` (the one required suite outside the sweep's package filter) re-ran green together with `TranslationSchedulerTraceTest` after the sweep.

**Flake note (pre-existing, not caused by this wave):** in the first targeted batch run, `TranslationSchedulerTraceTest > cancelled manual intent closes exactly once as cancelled` failed once with `java.util.ConcurrentModificationException` thrown from the TEST's own `lines()` helper (`TranslationSchedulerTraceTest.kt:252`) — it filters a plain `capturedLines` ArrayList that the logcat capture appends to from scheduler threads while `awaitCondition` polls it: an unsynchronized test-harness race. The tested code path cannot touch this wave's changes — that test constructs `TranslationScheduler(executor, { null })` (store resolver returns null), so `markPageCancelled` returns at `storeResolver.resolve(chapterId) ?: return` BEFORE the new lease gate. Re-ran the suite 3/3 green standalone, green again in a dedicated re-run, and green in the final full sweep. Candidate tiny follow-up: make `capturedLines` a `CopyOnWriteArrayList` in that test.

## Deviations from the spec

1. **Two pre-existing tests amended** (above) — they pinned the OLD stale-rejection contract on exactly the two seams the spec changes; leaving them would guarantee a red sweep. Both keep their file's protective intent via the untouched fault-injection/identity-rejection tests, and the amendment is recorded here.
2. **`flipped` counters untouched** — `markChapterCancelledSync/Async` still count lease-skipped keys as "attempted". The spec requires the WRITE to be skipped, not the attempt accounting; changing counters would alter observable legacy behavior beyond the specified skip. The INFO skip log carries the observability instead.
3. **"stale generation → fresh generation" log uses `updatedAtEpochMs`** — `ChapterArtifactManifest` has no numeric generation; its durable-identity marker is whole-object equality and `updatedAtEpochMs` is the monotone field the real writer (verify) bumps. Logged as `staleUpdatedAt=... freshUpdatedAt=...`.
4. **`fastCancelInFlightStagesInMemory` early-returns 0 when every running page was skipped** — avoids rebuilding/republishing an identical state map; neutral (StateFlow conflates equal values), keeps the fast path allocation-cheap.
5. **No negative retry test for `publishActiveRun`** — its only manifest-dependent precondition is the CAS itself (record validation is manifest-independent), so a still-failing retry is only reachable via IO fault injection; the shared `retryOnStaleManifest` helper's "return the retry outcome as-is" behavior is pinned by the checkpointOcr negative test, which exercises the same helper.

## Discoveries relevant to the LI-8 follow-up (façade/CAS hardening)

- The manifest CAS is WHOLE-OBJECT EQUALITY against a disk re-read (`staleManifestRejection`), not a generation counter — every publisher that accepts a caller-held manifest is exposed to the same façade-cached-copy race the background verify triggers. `publishSidecarPointers` (used by `AnalysisChunkPublication`, `EnvelopePlanPublication`, `ProfileFreezePublication`, layout/color pointers) and the smaller publishers (`retireActiveRun`, `recordDurableFailure`, `materializeLegacyCommittedSnapshot`, `recoverInterruptedStages`, `demote/promote/persistLiveCandidate`, `openCandidate`) were intentionally left unchanged per scope. Of these, `recordDurableFailure` is notable: it has NO stale check at all and derives from the caller's manifest — a stale caller can silently revert a concurrent field there (pre-existing, out of scope).
- The generalization is mechanical: the same `retryOnStaleManifest` helper + `...Once` extraction applied per publisher, with the rebuild already lambda-derived (`updatePointers(current)`) in the `publishSidecarPointers` family. The `AnalysisChunkPublicationTest:194` stale-rejection test is the pinned old contract that would need the same amendment treatment at that time.
- The façade (`ChapterTranslationStore`) refreshes its cached `artifactManifest` only on ITS OWN committed publications; it never learns about the background verify's republish. A façade-level "re-read on Rejected" would duplicate the fix; the store-level retry is the right layer because the store owns the disk truth. If the flagged lane later grows MORE first-publication seams, prefer re-using `retryOnStaleManifest` over façade cache invalidation.
- The verify's republish bumps `updatedAtEpochMs` (and `legacyMigration`), which is what breaks equality; a cheap hardening option for LI-8 is a monotone `manifestRevision: Long` counter on the manifest (bumped by `publishManifestInternal`) so CAS/retry logging and future conflict reports stop relying on timestamp equality.
- Bounded INFO skip-log volume for LI-3 holds: one line per skipped page per cancel action (user-initiated or teardown), never per-frame.
