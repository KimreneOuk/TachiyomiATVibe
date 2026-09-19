# Ticket P3-03: Remove cross-origin coexistence machinery proven unreachable by the session gate

**Phase:** 3 (after P3-01 + P3-02 verified green) | **Risk:** Medium-High (deletion in live
coordination code) | **Type:** Deletion + test disposition

## Evidence base

Scoping report §1 (lease table disposition table, BatchWriteGate analysis, deferred-pages/S8,
OverlapScheduler same-origin contract) and §5 (test disposition table). Everything below is dead
ONLY IF the session gate of P3-01/P3-02 is correctly routing all §2.3 entry points.

## Changes

1. **Split the drain-grace constant first**: `RollingAutoCoordinator` currently reuses
   `ATTACH_TIMEOUT_MS` as `PROVIDER_DRAIN_GRACE_MS` (RollingAutoCoordinator.kt:1353-1359). Give AUTO
   drain grace its own constant with the same value; update D6DrainNotCancelTest:189-204 and
   D7EngineEpochStopRaceTest:242-256 assertions to the new name (mechanical rename, values unchanged).
2. **Delete the attach path**: `TranslationPipeline.attachToOwnerTerminal` (733-793),
   `SinglePageOutcome.AttachedUnresolved`, the denied-lease→attach redirect (484-486), and
   `ATTACH_TIMEOUT_MS` itself. Reader-side denial now only comes from the session gate (typed).
   Update TranslationUiTruth:598-607 mapping (attach-wait message) to session-aware messaging
   ("Batch translation is pausing…" / switch offer).
3. **Delete batch deferral machinery**: `deferredPages`/`ocrDeferred` (BatchChapterTranslator:547-569,
   BatchLaneWorkers:326-341) and the S8 bounded gap rescan (ChapterProfileBatchCoordinator:724-804).
   Under mutual exclusion no foreign reader owner can exist mid-batch; incomplete corpus at
   preflight keeps the existing PAUSED path. `OCR_PREFLIGHT` publication and checkpoint reuse stay.
4. **Lease table**: remove the MANUAL-on-BATCH denial/attach branch (foreign-origin deny at
   PageStageLeaseTable:99-103) and the release-waiter remnant (272-283; sole producer already
   deleted). KEEP: MANUAL-evicts-AUTO (reader-session policy, D1), same-origin attach/detach
   (164-231), generation/version fencing, NonCancellable releases, origin-wide teardown.
5. **BatchWriteGate**: keep whole (identity/generation/owner-proof/durable-failure machinery). The
   foreign-owner fail-closed branch stays — unreachable but harmless defense-in-depth.
6. **Test disposition** (scoping §5 table — behavior-based, not filename-based):
   - DELETE/REWRITE interleaving choreography: D2ManualBatchInterleavingTest, D3ReaderOwnedPageAcrossBatchTest
     (preserve its checkpoint-reuse assertions inside the P3-02 session-transition test if not already),
     D6ForegroundFairnessTest (rewrite as reader-only provider-window test), D9 attach-waiting case
     (403-466; keep ledger/death-cycle), ManualAttachOnBatchTraceTest (replace with session admission trace).
   - PRESERVE unchanged: D1 (reader policy), D5, D6DrainNotCancel, D7, D8, D10, D11, NormalMangaIsolation
     (reroute through gate only if it asserts lease observation), StandardPipeline*, T918CancelledBatchRestart,
     DisplayTailDrain, BatchLeaseFlipHeal, BatchWriteGateHeal, T934*, MilestoneM2/M3/M5/M6.
   - Ledger required in report: every deleted/rewritten test with the behavior it covered and where
     that coverage now lives (or why the behavior no longer exists).
7. Update `StandardPipelineCoexistenceTest` event-sequence ONLY if the S8 removal changes observable
   sequencing (it should not for a complete fresh run).

## STOP-gates

- Before each deletion, re-verify unreachability under the session gate (grep for remaining callers).
  Any live caller found = STOP + report.
- Any surviving test needing assertion changes beyond the enumerated mechanical renames = STOP + report.

## Verification

Full both-flavor suites green; `git grep attachToOwnerTerminal|AttachedUnresolved|deferredPages|ocrDeferred`
→ 0 in app/src; `assembleDevDebug` green.

## Commit(s)

`refactor(translation): remove cross-origin attach/defer/rescan machinery under session mutual exclusion`
