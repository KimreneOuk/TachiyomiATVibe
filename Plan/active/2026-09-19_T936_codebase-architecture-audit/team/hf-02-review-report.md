# HF-02 — Independent Hotfix Review Report

Date: 2026-09-20
Reviewer: Independent Reviewer (adversarial verification; plumbing traced end-to-end, lease/
cancellation logic re-derived, testcase-level XML inspection, collateral diff-scan)
Branch reviewed: `t936/hotfix-manual-ux-truth` @ `c44e660`, base `main` @ `c0c71c4` (includes
HF-01). 3 commits; 18 files, +459/−52.

## Verdict

**PASS WITH NOTES**

All six decisive questions verified. The batch-framing guard is airtight, stage publishes
reuse the single-Mutex store seam without claiming completion, the AUTO/MANUAL cancellation
guard cannot leak, deadlock, or block legitimate stop-all, the three new tests are alive and
pin invariants, and — notably — the HF-01 blocking finding (dead T906 test) is remediated on
this branch (both its tests now execute; counts reconcile exactly). Notes are documentation
gaps only.

---

## 1. Session gating plumbing — PASS

- Chain verified end-to-end: `TranslationSessionCoordinator.state` (StateFlow) →
  `ReaderViewModel.translationSessionState` (direct getter) → `ReaderActivity`
  `collectAsState()` → `isBatchSession = (state == BATCH_SESSION)` → `ReaderAppBars` →
  `BottomReaderBar`.
- **Hot-updating:** `collectAsState()` on a StateFlow emits on every state change, so the
  bar clears batch framing the moment BATCH_SESSION ends (e.g. `finishSession` on
  `onBatchClosed`) and re-shows it if a batch session starts — no polling, no stale frame.
- **No recomposition storm:** StateFlow is conflated and distinct-until-changed by contract;
  the reader subscribes to a 4-value enum and derives one Boolean per recomposition. The
  existing `translationBatchProgress` flow is untouched.
- **`readerBarLine` guard airtight:** `if (!isBatchSession) return null` short-circuits ALL
  kinds — rebuild/restore/resume variants included — so a manual reader can never be handed
  batch framing from the truth helper. Defense in depth: the reader's only call site
  (`BottomReaderBar`) is itself double-gated (`translationBatchProgress?.takeIf
  { isBatchSession }` for the counter block, the request-phase batch icon, and an explicit
  `isBatchSession = true` argument inside the gated block). The `isBatchSession = true`
  DEFAULT on the helper is safe: census shows the only production caller is BottomReaderBar
  (explicit), everything else is tests.

## 2. Stage truth store reaches — PASS

- `SinglePageOnnxPhase` publishes the **inpaint RUNNING** snapshot before native ONNX
  inpaint (with an accurate comment: the recognition result was held locally, so the shared
  store stayed on OCR RUNNING until the terminal commit — exactly the reported "silent
  stillness").
- `SinglePageHttpRenderPhase` adds `publishLiveStage` and publishes **translate RUNNING,
  retry-translate RUNNING, render RUNNING, retry-render RUNNING**.
- **Store discipline:** every publish goes through the pre-existing
  `updatePageFromCurrentSnapshot` seam — the same single-Mutex, precondition-checked write
  path all store mutations use (Phase-2 discipline untouched; the store diff itself only
  adds origin/reason parameters to `fastCancelInFlightStagesInMemory`).
- **Durability:** the published snapshots carry `RUNNING` statuses — they never claim
  COMPLETE, so they cannot be durable-persisted as complete; an interrupted mid-flight
  snapshot is handled by the existing interrupted-stage→retryable restart machinery. HF-01
  keying respected: each publish sets `sourceFileName = pageKey` (the canonical key).
- **Write amplification:** five small snapshot emissions per manual page — negligible
  against native OCR/inpaint/provider work; no new flush pressure.
- **Label mapping covers every publish point:** OCR→Reading Text (pre-existing), inpaint→
  Cleaning Bubbles, translate→Translating Text, render→Finishing Page — pinned by the new
  mapping test.

## 3. Lease-protected cancellation — PASS (sharpest edge, verified)

- **The guard:** `fastCancelInFlightStagesInMemory(origin, reason)` — when the caller
  declares an origin (AUTO), a page whose `pageLeaseOwner != origin` is skipped (kept
  RUNNING). `markPageCancelled(requiredOrigin)` gets the same guard for the durable write.
- **(a) No lease leak:** the guard only *skips* cancellation — it acquires no lease and
  never waits. Lease lifecycle is untouched (pre-existing NonCancellable release in the
  holder's finally); if the MANUAL holder dies, the existing release path runs exactly as
  before and a later AUTO cancel finds no owner and proceeds.
- **(b) No deadlock:** the check is a lock-free `pageLeaseOwner` read inside an already
  monitor-cheap fast path (the store diff's own comment: the lease query takes only the
  lease table's monitor, never the store mutex). Cancel-all iterates and skips — reader
  close cannot hang waiting on a manual page.
- **(c) Legitimate stop-all preserved:** reader-directed cancels pass `origin = null`
  (guard bypassed — everything cancellable cancels, pre-existing behavior) with attributed
  reasons threaded through every entry point: `Translation disabled by user` (toggle-off),
  `Manual stop requested from reader`, `reader backgrounded`, `Reader translation stop`;
  defaults preserve the historical strings where no caller reason exists. The scheduler's
  `pageCancellationReasons` map attributes BEFORE cancelling, is consumed (removed) in the
  job's finally, and lands in the durable page error via `markPageCancelled(reason)` —
  mid-flight cancellations no longer collapse to "Translation cancelled". AUTO-path
  cancels are explicitly tagged `AUTO` + "Auto translation cancelled".
- Attributed reasons verified wired: toggle-off (ReaderViewModel), teardown/manager
  signatures, scheduler finally-block durable write.

## 4. Tests alive + strong — PASS

All three new tests proven **executing** in the fresh Standard XML (testcase names present,
0 failures):

- `ReaderBarTruthTest.reader bar hides batch framing outside a batch session` — statement
  body; asserts `readerBarLine(snapshot, isBatchSession = false)` is null. Pins the guard,
  not implementation.
- `ReaderTranslationFeedbackTest.manual page stage transitions remain observable at every
  stage` — statement body; pins the four-stage snapshot→label mapping (invariant table).
- `CancelSyncStoreWriteTest.auto cancellation does not cancel a page owned by a manual
  lease` — `= runTest { }` (JVM TestResult = Unit — executes; distinct from the T906
  `runBlocking` hazard). Pins the invariant: MANUAL lease held + AUTO cancel → page stays
  RUNNING; lease released in `finally` (no leak in the test itself).
- No existing test weakened: the three files only gained tests (+1/+1/+1); existing
  `readerBarLine(snapshot)` assertions keep their batch-framing semantics via the default.

**Bonus — HF-01 blocker remediated here:** `ReaderPageTranslationKeyTest` now declares
`runBlocking<Unit>` and its XML shows **both tests executing** (including the previously
dead "observation emits non-null" test). Test-count arithmetic reconciles exactly:
2,026 (HF-01 executed) + 1 (revived) + 3 (new) = **2,030**.

## 5. Flake documentation — PASS

The three named flake classes (`BatchDispatchResumeWiringTest`,
`StandardPipelineCoexistenceTest`, `StandardLaneMultiPageCompletionTest`) are precisely the
load-sensitive coexistence family this reviewer independently reproduced and isolation-
cleared in Phases 2-4; each documented with 3/3 isolation per the policy. The additionally
documented `MangaScreenModelTranslationDrawerTest` init-timeout (Standard-only) is a
first-appearance member of the same UI-under-load class, also 3/3 isolated, with a clean
final Standard rerun. No new failure signature in any touched manual/reader/store surface.

## 6. No collateral — PASS

Diff-scan: `TranslationSessionCoordinator.kt` NOT in the diff (session transition semantics
untouched); store terminal-commit durability paths unchanged (only fast-cancel attribution
parameters added; stage publishes reuse the existing snapshot seam); HF-01 keying respected
(`sourceFileName = pageKey` everywhere) and the HF-01 regression test revived. Assets,
string resources, hardware routing: untouched except the additive `logCompiledProviders`
diagnostic (INFO-only, no routing).

## Evidence notes (non-blocking)

1. **Dev full-suite green not evidenced on disk:** the Dev test-results directory holds a
   single focused-run XML (the full-run XMLs were overwritten by later isolation runs), and
   the report does not claim a final green full-Dev rerun — its two full Dev runs hit the
   documented load flakes, cleared 3/3 in isolation. Standard full green IS on disk:
   **2,030 tests, 293 files, 0 failures / 0 errors** (fresh, 23:54). Same evidence-hygiene
   gap as Phase 3; acceptable for a hotfix whose touched surfaces were covered by focused
   runs in both flavors, but a final clean Dev full run is recommended before the device
   install verdict.
2. **Report count figure stale:** the report cites "2,026 tests" for the final full Standard
   rerun; the on-disk XML shows **2,030** (the reconciled count above). The evidence is
   green either way; the figure should be corrected.
3. Task 4 (device latency quantification) is honestly marked N/A-from-local and deferred to
   the orchestrator's device repro — correct call, no fabricated data.

## Conclusion

Manual readers now get per-page stage truth, never batch framing, attributed cancellation,
and AUTO cannot kill their in-flight work — with the single-Mutex store discipline, HF-01
keying, session semantics, and durability contracts all preserved. Merge-ready from this
reviewer's standpoint once the orchestrator captures the device logcat verification and,
ideally, lands a final clean Dev full run.
