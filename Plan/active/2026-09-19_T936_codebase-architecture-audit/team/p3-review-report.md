# T936 Phase 3 — Independent Review Report

Date: 2026-09-20
Reviewer: Independent Reviewer (adversarial verification; every claim re-derived from git
history, call-site census, source reads, APK, and test XMLs — implementer claims not trusted)
Branch reviewed: `t936/phase3-coexistence-pipeline` @ `e4bec7b`, base `main` @ `5bcb592`
(merge-base verified). Net: 44 files, +1,491/−1,144. Scope: `app/src/**` + Plan/ +
`i18n-at/…/strings.xml` — nothing else.

## Verdict

**PASS WITH NOTES**

No blocking issues. The gate is complete (call-site census closed), the quiescent transition
is sound (timeout never admits; join is lock-free; retries are observable), the deletions are
exactly the proven-unreachable machinery, and the test delta reconciles to +5 with zero
weakened assertions. Two notes: the report's ledger half-misstates where D3's checkpoint-reuse
coverage lives, and one ledger entry (P5OutcomeProjectionTest) is missing.

---

## Item 1 — Gate completeness (scoping §2.3 checklist): PASS

Independent call-site census of `translatePage` / `translateBatch` / `updateAutoWindow` /
`requestAutoWindow` across `app/src/main` — every production entry point verified:

| §2.3 surface | Routing verified |
| --- | --- |
| Reader manual (3 sites) | `ReaderViewModel.translateSinglePage` pre-gates (`requestReaderSession`, L2254: `BATCH_ACTIVE` → `Dialog.BatchReaderSwitch`, `PAUSING_IN_PROGRESS` → toast, `READER_ACTIVE` → proceed), then the three `translatePage` sites funnel through `manager.translatePage` → `ReaderTeardownCoordinator.translatePage` → **`TranslationScheduler.translatePage:681` gate** (`Rejected` → typed `recordManualOutcome` + log + return, no pipeline entry). Double-gated. |
| Reader auto window | **`TranslationManager.updateAutoWindow` gate (L1641)** + **`TranslationScheduler.updateAutoWindow` gate (L199)**; `Rejected` → logged, no window update. |
| Dormant `requestAutoWindow` | Manager `requestAutoWindow` (L1601) has **zero production callers**; scheduler `requestAutoWindow` remains `@Deprecated` (T922 §10.7). Quarantined-unrouted, NOT newly-gated — exactly per ticket. |
| Reader lifecycle | `onCleared`, chapter switch, background, stop-all, toggle-off all reach `ReaderTeardownCoordinator`, which delegates: `finishSession(READER_SESSION)` (L83/109/179), `abortPausingToBatch()` (L78/104), `finishSession()` (L177). No hidden second state. |
| Retry / re-arm | Manual retry re-enters `translateSinglePage` (gated); batch retry → `manager.startTranslation` → **gated at L777**; auto re-arm → `updateAutoWindow` (gated). |
| Batch pause/start | `admitBatchSession` (= `requestBatchSession`) wired at all five manager batch seams: `admitRestoredPendingTranslation` (695), `startTranslation` (777), `requeueTranslation` (792), `translateChapter` (861), `translateChaptersInternal` (942 — multi-chapter). UI/service callers (`MangaScreenModel`, `TranslationForegroundService`) hit these manager seams by construction (files correctly unchanged). Pause is ownership-neutral by design: a PAUSED batch keeps `BATCH_SESSION` (`finishSession` guards require no-active AND none-paused, L254/1871). |
| Batch chapter actions | Cancel/replace via `removeFromTranslationQueue` (L1871, releases session when drained); replacement re-starts through gated `translateChapter`; same-chapter re-entry during unwind remains protected by the pre-existing `inFlightChapterIds` claim (unchanged parity with main). |
| `ReaderTeardownCoordinator` | Delegates admission/ownership to the coordinator (see lifecycle row). |

**Bypass hunt:** the census found no ungated path to `TranslationScheduler.translatePage`,
`pipeline.translateBatch`, or `updateAutoWindow`. Remaining uncalled-by-UI sites are
intra-session internal workers (`SinglePageHttpRenderPhase:347`, `BatchLaneWorkers:1053`,
`ChapterTranslator:847`, `TranslationPipeline:1204`) that execute inside an already-admitted
session. `switchBatchToReader` is the only production confirmed-switch (manager L364);
`requestReaderSession(confirm=true)` has no production caller outside it — no non-joined
switch path exists.

**Kept foreign-owner denial (documented deviation) — assessed sound.** The
`PageStageLeaseTable` foreign-origin denial is genuinely unreachable through the gate (all
entries verified above) and fail-closed by construction (denial → typed rejection / counted
skip → incomplete-corpus PAUSED). Keeping it mirrors the deliberately kept `BatchWriteGate`
foreign-owner refusal; the code comment documents the rationale. Harmless defense-in-depth;
the deviation is explicitly reported in the implementation report as the ticket requires.

## Item 2 — Quiescent transition soundness: PASS

(a) **Join lock-freedom:** UI confirm → `ReaderViewModel` launch → `manager.switchReaderSession`
= `withContext(Dispatchers.IO)` → `coordinator.switchBatchToReader`. The coordinator's monitor
is held only across atomic state flips; `pauseBatch()` and `joinBatch(…)` are invoked OUTSIDE
any lock; `cancelTranslatorJobAndJoinForSession` touches only translator fields and
`job.join()` (suspension) — no manager, reader-teardown, store, or artifact mutex anywhere on
the path (traced end-to-end).

(b) **Timeout never admits:** the join loop admits only when `joinBatch` returns true. The
session-join wrapper (`cancelTranslatorJobAndJoinForSession`) on timeout returns false
WITHOUT discarding the job (`sessionSwitchJobs` retained, `invokeOnCompletion` cleanup), so
each retry re-joins the SAME job; admission occurs only after genuine `job.join()` completion
(i.e. `finally`/NonCancellable flush/lease release ran). The delete-path
`cancelTranslatorJobAndJoinResult` keeps its historical log-and-proceed semantics.

(c) **2s-default callers unchanged:** `BATCH_JOIN_TIMEOUT_MS = 2_000L` retained;
`TranslationManager:1388` (rekey), `ChapterDataResetController:142/246` — all default-arg
call sites, unchanged (grep-verified).

(d) **No silent livelock:** every timeout attempt fires `onJoinTimeout` → WARN
"reader admission waiting for batch unwind after N ms" (manager L369-374); state remains
`PAUSING` (reader-visible); `TranslationSessionCoordinatorQuiescenceTest` asserts the exact
timeout list and retry count.

(e) **Pause-vs-cancel preserved:** `pauseForSessionSwitch` flips TRANSLATING→QUEUE +
`isPaused`, retaining queue entries and tracker progress (no removal); a dismissed switch or
reader teardown mid-switch restores `BATCH_SESSION` (`abortPausingToBatch` /
`CancellationException` handler), leaving the batch unimpeded — covered by
QuiescenceTest tests 3 and 4.

UI switch flow per spec Model B: `ReaderActivity` AlertDialog with confirm
(`confirmBatchReaderSwitch` → joined switch) / dismiss (batch continues), using the +7 new
session strings.

## Item 3 — Deletion correctness: PASS

- Acceptance greps re-run independently: `attachToOwnerTerminal|AttachedUnresolved|
  deferredPages|ocrDeferred|ATTACH_TIMEOUT_MS|leaseReleaseWaiters|awaitPageLeaseRelease|
  completeLeaseReleaseWaitersLocked` → **0 hits in `app/src`**.
- **S8 removal:** the bounded gap-rescan loop is deleted from
  `ChapterProfileBatchCoordinator`; OCR_PREFLIGHT publication, checkpoint reuse, source
  identity checks, failure ledger, and incomplete-corpus PAUSED remain (grep + diff
  verified). `StandardPipelineCoexistenceTest` diff is **comment-only** — the fresh-run event
  assertions are untouched, matching the ticket's prediction that sequencing does not change.
- **Batch deferral:** `deferredPages` map, `ocrDeferred` listener hook + override, and the
  externally-completed SKIP_ALL special case all removed; BATCH OCR denial is now a logged
  fail-closed skip.
- **Pipeline:** `attachToOwnerTerminal`, `AttachedUnresolved`, `ATTACH_TIMEOUT_MS`, and both
  denied-lease→attach redirects removed; denial is now typed `SinglePageOutcome.Rejected`.
- **UI truth:** `ATTACHED_UNRESOLVED` replaced by `BATCH_SESSION_SWITCH` ("Batch translation
  is pausing; confirm switch to reader.", NONE retry, REVIEW+DETAILS); `attached(BATCH)` and
  `Rejected(owner=BATCH)` both map to the switch truth.
- **PageStageLeaseTable:** MANUAL-evicts-AUTO retained; same-origin attach/detach/release
  bodies retained (lost only the now-dead waiter-completion calls); waiter map + helper
  removed (the T924 D2 follow-up the file itself promised); foreign-owner denial kept as
  documented defense (see Item 1).
- **Zero-diff checks honored:** `BatchWriteGate`, `BatchRenderJoin`, `translator/analysis/*` —
  zero diff. `OverlapScheduler` — 7 lines, comment-only. `EngineLane` — comment-only.
- Minor: `SinglePageOutcome.Attached` is retained but now has **no producer** in main (only
  consumers). Dead case, harmless; cleanup candidate for a later cosmetic pass.

## Item 4 — Test ledger + delta: PASS WITH NOTE

Executed delta 2,020 → 2,025 = **+5**, reconciled exactly by `@Test` annotation arithmetic
across all 19 changed test files:

| File | Δ | Verification |
| --- | ---: | --- |
| `TranslationSessionCoordinatorTest` (new) | +7 | Matches the P3-01 ticket test list incl. no-bypass and teardown symmetry; tests-first honored (commit 41c61b6 precedes fe241ca). |
| `TranslationSessionCoordinatorQuiescenceTest` (new) | +4 | Join-success/timeout-retry/cancellation-restore/abort — all coordinator-level, assertions verified line-by-line. |
| `D2ManualBatchInterleavingTest` | −2 | Deleted; interleaving impossible under the gate. |
| `D3ReaderOwnedPageAcrossBatchTest` | −1 | Deleted; cross-origin choreography impossible. |
| `D9AttemptLedgerTest` | −1 | Exactly the attach-waiting case removed (`attach-waiting manual writes zero ledger entries`); ledger/death-cycle cases retained. |
| `ManualAttachOnBatchTraceTest` | −1 | Deleted; attach trace replaced by coordinator admission tests. |
| `P5OutcomeProjectionTest` | −1 | `attached unresolved outcome…` deleted (tested the deleted `AttachedUnresolved` truth); retained attached-outcome test rewritten field-for-field onto `BATCH_SESSION_SWITCH` — equal strength. **Omitted from the report's ledger table** (prose only) — see Note 2. |
| `D6ForegroundFairnessTest` | 0 (rewritten) | Reader-only rewrite keeps both oracles: INTERACTIVE priority observation and window-exhaustion → single typed pause with no retry loop. Only the impossible batch-pairing assertions are gone. |
| `D6DrainNotCancelTest` / `D7EngineEpochStopRaceTest` | 0 | Mechanical constant-split updates, equal assertion strength (`grace == ONNX+SINGLE`; `grace >= chainBudget`). |
| `StandardPipelineCoexistenceTest` | 0 | Comment-only. |
| 6 TranslationManager* fixtures, harness, `ReaderManualOutcomeTruthTest` | 0 | Real-coordinator fixture init + session-aware truth strings; no weakened assertions. |

Deleted-behavior coverage audit: D2/D3/ManualAttach behaviors are impossible under the gate
(gate completeness independently verified, Item 1). **Note 1 (inaccuracy):** the report
claims D3's checkpoint-reuse coverage "remains covered by
TranslationSessionCoordinatorQuiescenceTest and OcrCheckpointRestartReuseTest" — the second
half is true (`OcrCheckpointRestartReuseTest`, 429 lines, unchanged: checkpointOcr commit
ordering, reuse by Manual/Auto, canonical revision assertions all present), but
**QuiescenceTest contains no checkpoint/zero-re-OCR assertion** (it is a pure state-machine
test with fake pause/join lambdas). Ticket P3-02's requested "join-success admits reader +
reuses OCR checkpoints" integration assertion was not implemented. The underlying reuse
machinery is covered by the unchanged restart/reuse/resume suites, so this is a coverage-book
inaccuracy, not a correctness gap.

## Item 5 — Constant split: PASS

`RollingAutoCoordinator.PROVIDER_DRAIN_GRACE_MS = 210_000L` — own constant, same value as the
removed `ATTACH_TIMEOUT_MS` (90s + 120s). D6Drain asserts equality with the component sum;
D7's lower-bound invariant uses the same sum. Values unchanged, both suites green.

## Item 6 — Fixture commits: PASS

`0bc4419` (4 test files) + `c06d5de` (1 test file) — test-only diffs. The
`Unsafe.allocateInstance` manager fixtures (which bypass Kotlin field initializers) now
install a **real `TranslationSessionCoordinator()`** via `setField` — no always-admit stub,
so the tests using those fixtures exercise the genuine gate. The
`TranslationManagerAutoArbitrationTest` lifecycle additions (`finishSession` before batch
queue / after drain) are semantic adaptations to mutual exclusion, documented in comments.

## Item 7 — Safety invariants & Phase-2 discipline: PASS

- `app/src/main/assets/` diff: empty (all model/OCR assets byte-identical to main; also
  verified present in the APK, Item 8).
- NNAPI/hardware-routing files (`HardwareDiscoveryEngine`, `OnnxRuntimeProvider`,
  `AOTInpainting`, `PaddleOcrSessionFactory`, `OnnxBubbleSegmenter`, `QnnDiagnostics`,
  `TranslationPreferences`, `domain/`): zero diff.
- Phase-2 lock discipline intact: admission (coordinator) precedes all lease/scheduler work;
  the coordinator monitor is never held across the join or any document I/O; per-name
  document/open locks remain leaf-level; `ChapterTranslator` diff is +66/−5 with
  `NonCancellable` teardown/flush/claim code untouched (only a log string parameterized).

## Item 8 — Evidence: PASS

- **Test XMLs on disk: 2,025 tests × both flavors, 292 files each, 0 failures / 0 errors /
  0 skipped.** New suites present and green in both flavors (CoordinatorTest 7,
  QuiescenceTest 4); `BatchDispatchResumeWiringTest` green (flake-ledger member).
- **APK** (`app-dev-universal-debug.apk`, 362,175,089 bytes): 2,042 entries; best_int8 0;
  OCR docs 0; manga109 1; inference.onnx 2; aot-512 1; aot.onnx 1.
- **strings.xml:** exactly +7 entries, all `reader_batch_switch_*` session-switch messaging.
- **Flake ledger (4 entries):** consistent with this campaign's known load-flake family —
  three of the four (`BatchDispatchResumeWiringTest`, `StandardPipelineCoexistenceTest`,
  `StandardLaneMultiPageCompletionTest`) are the exact suites this reviewer independently
  reproduced and isolation-cleared during the Phase 2 review; the fourth
  (`ProfileEnvelopePromptEnrichmentTest` TempDir-cleanup IOException) is a new benign
  environment signature with a plausible isolation-3/3 record. None are storage/session
  suites. Not independently re-run (no discrepancy found; XML verification sufficed).
- **Git:** clean tree; no secrets; `app/google-services.json` absent and untracked; per-commit
  scope exact (1 scoping / 4 tickets / 1 tests-first contract / 6 routing / 9 quiescent /
  23 removal / 4+1 fixtures / 1 report).

---

## Notes (non-blocking)

1. **Ledger inaccuracy (report):** "Durable OCR checkpoint reuse remains covered by
   TranslationSessionCoordinatorQuiescenceTest and OcrCheckpointRestartReuseTest" — the
   QuiescenceTest half is false (no checkpoint assertion there; ticket P3-02's integration
   assertion was not implemented). Coverage exists only via the unchanged
   `OcrCheckpointRestartReuseTest` and resume suites. Recommend adding the store-backed
   reuse-through-switch test in a follow-up (Phase 4 candidate) or correcting the ledger.
2. **Ledger omission (report):** `P5OutcomeProjectionTest` (−1, attach-unresolved truth) is
   not in the disposition table. Verified legitimate: the tested outcome was deleted; the
   rewritten assertion is field-for-field equal strength.
3. **Dead case retained:** `SinglePageOutcome.Attached` has no remaining producer (its
   consumers in scheduler/UI truth are unreachable). Harmless; fold into a later cosmetic
   cleanup.
4. **Session-rejection reason naming:** a join that succeeds after
   `abortPausingToBatch()` raced it returns `Rejected(PAUSING_IN_PROGRESS)` while the state
   is actually `BATCH_SESSION`. Semantically the outcome is correct (batch keeps the session,
   reader is not admitted) but the reason label is imprecise for future UI use.

## Conclusion

Phase 3 delivers the session mutual-exclusion design exactly as scoped: one admission owner,
all §2.3 entry points gated (census-verified, no bypass), a quiescent transition that never
mistakes timeout for quiescence, and deletion strictly limited to the machinery the gate
made unreachable — with BatchWriteGate, OverlapScheduler, same-origin leases, and
translator/analysis untouched. Merge-ready from this reviewer's standpoint, subject to the
Director's gates and the two ledger corrections in Notes 1-2.
