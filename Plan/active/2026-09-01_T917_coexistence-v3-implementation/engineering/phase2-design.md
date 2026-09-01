# T917 Phase 2 — Design Note: D1–D4 (intent-loss fixes)

Audience: Implementer. Scope: production design only; no code in this phase.
Evidence labels: [VERIFIED] = read in this checkout at `t917/coexistence-v3` HEAD.
Line numbers are from the live files. Everything below is VERIFIED unless labeled otherwise.

## 0. Corrections to the delegation / prior docs (read first)

- `TranslationPipeline.kt` lives at `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
  — NOT under `pipeline/`. [VERIFIED]
- `BatchProgressReconciler.kt` lives at `app/src/main/java/eu/kanade/translation/pipeline/batch/`
  — NOT under `store/`. [VERIFIED]
- PLAN.md:60 says the D4 guard goes in "`TranslationManager.openTranslationSession`".
  [CONTRADICTION]: `openTranslationSession` (:1334-1353) only opens/returns a `TranslationSession`
  (store handle for the reader); it creates no auto coordinator. Blocking it would deny the reader
  its display store. The auto window is established exclusively via `updateAutoWindow`
  (manager :1372-1390 → scheduler :139-208) and the legacy `requestAutoWindow` (:1355-1358 →
  scheduler :247+). The guard therefore goes on the manager's auto-entry methods (§4); behavior
  contract unchanged, mechanism differs from PLAN wording.

---

## 1. D1 — three-origin lease model

### 1.1 Type change (minimal diff)

`PageWriteOrigin` (`TranslationStageContracts.kt:14-17`) becomes:

```kotlin
enum class PageWriteOrigin { MANUAL, AUTO, BATCH }   // READER_ADHOC deleted
```

Two-vocabulary rule (keeps durable data and planner parsing stable):
- **Lease layer** uses `PageWriteOrigin` (3 values). `PageStageLease.origin` (:169),
  `LeaseAcquisition.Denied.owner` (:185), `PageLeaseRecord.origin` (PageStageLeaseTable.kt:46).
- **Durable provenance** keeps `ArtifactOrigin { READER_ADHOC, BATCH }` (ArtifactContracts.kt:61)
  and the `pageTranslationOrigin` string stamp. `ChapterTranslationStore.toArtifactOrigin()`
  (:1637-1639) maps `MANUAL|AUTO -> READER_ADHOC`, `BATCH|null -> BATCH`. `SinglePageHttpRenderPhase`
  (:158) stamps via the same mapping (see 1.3). Do NOT stamp `"MANUAL"`/`"AUTO"` strings:
  `PageWorkPlanner.stageEvidence` parses the stamp with `ArtifactOrigin.valueOf` inside
  `runCatching` (PageWorkPlanner.kt:419-421); unmapped names silently degrade provenance to
  UNKNOWN and can shift D5-adjacent reuse evidence.

### 1.2 Lease acquisition matrix (PageStageLeaseTable.tryAcquirePageStageLease :59-110)

| Requested ↓ / Owner → | none | MANUAL | AUTO | BATCH |
|---|---|---|---|---|
| MANUAL | Granted (new token) | re-entry (existing :72-87) | **Granted, evicts AUTO: new record + new token** | Denied(owner=BATCH) → **D2 attach** |
| AUTO | Granted | Denied(owner=MANUAL) | re-entry | Denied(owner=BATCH) |
| BATCH | Granted | Denied(owner=MANUAL) | Denied(owner=AUTO) | re-entry |

- Same-origin re-entry is preserved verbatim — it is load-bearing for the AUTO prepare→translate
  handoff (acquire :550 / release :621 / re-acquire :694) and is today's double-tap safety.
- MANUAL-evicts-AUTO is the one new rule (H-01's priority, lease layer). It is safe by existing
  fencing: the evicted AUTO holder's guarded writes fail on `expected.leaseToken != pageLeases[key].token`
  (ChapterTranslationStore.kt:459-462) — fail-closed, never corrupts. The AUTO side already treats
  a lost/stale page as "try again" (`translatePreparedPage` stale-reference null, :660-665, :710;
  `prepareSinglePage` null on denial :550), and the scheduler already hides future auto work for a
  manual page (arbitratedResolver :149-157), so eviction only hits an already-running AUTO stage.
  AUTO release/cancel stays origin-checked (:112-134) so it cannot remove MANUAL's lease.
- Release paths unchanged otherwise; `releaseAllPageLeases(origin)` (:137-143) is per-origin already.

### 1.3 Call-site inventory (every origin site in main; post-change origin)

| Site | Today | Post-change |
|---|---|---|
| `TranslationPipeline.acquireReaderPageLease` :441-457 | hard-coded READER_ADHOC | gains `origin: PageWriteOrigin` param |
| `runSinglePageBoundary` :377 (via `translateSinglePage` :315 and `translateSinglePageFromStream` :336) | READER_ADHOC | **MANUAL** (both are reader-tap/foreground intents; scheduler :590 is the tap, stream-peek shares the boundary) |
| `prepareSinglePage` :550 (called only by RollingAutoCoordinator :590/:605) | READER_ADHOC | **AUTO** |
| `translatePreparedPage` :694 (called only by RollingAutoCoordinator :358/:371) | READER_ADHOC | **AUTO** |
| Legacy auto resume path `TranslationScheduler.kt:341` `executor.translateSinglePage(...)` | (same boundary → READER_ADHOC) | passes **AUTO** (new `origin` arg, see 1.4) |
| `releaseReaderPageLease` :459-462 | READER_ADHOC | takes the boundary's origin (param) |
| `releaseBatchPageLease` :465-467 | BATCH | unchanged |
| Batch acquisition `BatchLaneWorkers.kt:754` | BATCH | unchanged |
| Reader stranded sweep `ReaderViewModel.kt:2563`/:2594 | READER_ADHOC | **AUTO** (reader-side automatic maintenance; it already skips on denial :2564 and skips batch-retained chapters :2532-2534) |
| Stamp `SinglePageHttpRenderPhase.kt:158` | READER_ADHOC.name | map(boundary origin) → `"READER_ADHOC"` (§1.1) |
| Stamp `BatchResumePlanner.kt:73` | BATCH.name | unchanged |
| `toArtifactOrigin` :1637-1639 | 2-way | 3-way mapping (§1.1) |

### 1.4 Signatures that change (exact)

1. `TranslationExecutor.translateSinglePage(...)` (TranslationExecutor.kt:41-48): return type
   `Unit` → `SinglePageOutcome` (D2 type, §2.4) + new param `origin: PageWriteOrigin = MANUAL`
   (default keeps every existing call site compiling; scheduler :341 passes AUTO, :590 keeps MANUAL).
   Implementations: TranslationPipeline :315 (only). `translateSinglePageFromStream` :50-58 keeps
   `Unit` (it delegates to the same boundary, so it attaches too; its outcome is currently
   unobserved — Phase 5 may surface it).
2. `TranslationPipeline.acquireReaderPageLease(store, chapter, pageKey, origin)` :441 — returns
   the acquisition (see §2), not Boolean.
3. `runSinglePageBoundary(...)` :367 — private; gains `origin` param and returns `SinglePageOutcome`.
4. `TranslationPipeline.translateSinglePageHttpRender` / `OnnxPhaseResult` — gain `origin` so the
   :158 stamp maps the real origin (translatePreparedPage :767 also feeds it).
5. `ChapterTranslationStore` — no signature change (lease API :402-419 already origin-typed).

No other signatures change. `PageStageLeaseTable` keeps its dual-locking discipline untouched.

---

## 2. D2 — wait-and-attach at the denied-lease site (C-01)

### 2.1 Site

`runSinglePageBoundary` :377 (`if (!acquireReaderPageLease(...)) return`) + `acquireReaderPageLease`
:449-455 (log-and-false). Scheduler finally removes the job silently :603-606.

### 2.2 Completion signal — store StateFlow stage observation (chosen)

The boundary, on `Denied`, does NOT acquire; it observes the owner's terminal commit:
`store.state.first { snap -> page terminal }` where terminal = `hasRenderedResult || isTextlessTerminal
|| isStageFailed` (same predicates BatchProgressReconciler.kt:88-89 uses). Chosen over
"lease-release + result read" because: (a) the owner commits its terminal stage BEFORE releasing the
lease (batch: render commit → `releaseBatchLease`, BatchWriteGate.kt:231-234; manual: commit →
boundary finally :431), so the state event carries the outcome; (b) release+read has a TOCTOU (a new
writer may acquire before the read) and alone distinguishes neither READY from FAILED nor
textless-terminal; (c) `state` is the reader's existing observation channel — zero new plumbing.
Lost-wakeup risk of StateFlow conflation is nil for terminal states (they are last-write).

### 2.3 Bound, cancellation, priority

- Wait bound: `ATTACH_TIMEOUT_MS = ONNX_PHASE_TIMEOUT_MS + SINGLE_PAGE_TIMEOUT_MS`
  (:117 = 90 s + :96 = 120 s) — exactly the owner's own bounded phase chain. On bound: typed
  `AttachedUnresolved` (no re-call, no state write; stall visibility is D8/Phase 4).
  Rationale "tie to store/native timeout semantics": the attach can never outwait the owner's
  own timers by construction; pathological native hang (H-08) still terminates the wait.
- Cancellation (reader leaves page / chapter switch / Stop): scheduler cancels the job; the wait
  is a suspending `first{}` → CancellationException propagates; the boundary returns the attached-
  cancelled outcome without touching the page. **The scheduler finally must skip
  `markPageCancelled` (:612-625) when the job never owned the page** — otherwise it would flip the
  BATCH-owned page's stages to CANCELLED. Gate it on the observed outcome (e.g. outcome is
  attach-family), not on a new flag.
- Wallet: the attach wait runs OUTSIDE `withProviderRequestPriority(INTERACTIVE)` (:323) — a
  passive observer holds no interactive reservation (D6 Phase 3 will formalize).

### 2.4 Typed outcome (no silent removal)

```kotlin
sealed interface SinglePageOutcome {
    data object Completed : SinglePageOutcome                                  // ran itself
    data class Attached(val owner: PageWriteOrigin) : SinglePageOutcome        // owner reached terminal state; result observed
    data class AttachedUnresolved(val owner: PageWriteOrigin, val reason: String) : SinglePageOutcome // bound hit / owner failed before terminal
    data class Rejected(val owner: PageWriteOrigin?, val reason: String) : SinglePageOutcome // defunct store etc.
}
```

- `translatePage` (:568-630) keeps launching/removing the job (bookkeeping), but stores the outcome
  in a bounded map `manualOutcomes: ConcurrentHashMap<String, SinglePageOutcome>` (cap 32, evict-oldest;
  cleared on chapter switch via existing teardown) — the Phase-5 hook the ReaderViewModel maps to the
  "Translating · background job" chip. No UI work now; the reader already sees the owner's live stage
  states through the store flow.
- The `finally` reconcile poke (:611) stays.
- **Exactly-once**: the attach path performs zero native/provider/render work — observation only.
  The paid-call oracles (`callsFor(p0) == 1`, D2 test :101-103) are structurally guaranteed.

---

## 3. D3 — batch defer-and-rescan (C-02)

### 3.1 Deferral record (in the coordinator context)

- `BatchLaneWorkers.runOcrStage` Denied branch (:754-761): instead of a bare `return null`, also emit
  a new NOOP-default listener event `BatchScheduleListener.ocrDeferred(pageKey, owner)`.
- `SequentialBatchCoordinator` (ctor :28-33) wraps its listener with an internal recorder that
  collects `deferredPages: LinkedHashMap<String, PageWriteOrigin?>` — this is the pending-handback
  record, coordinator-local, lifetime = one `runPass1`. Null-ref pages WITHOUT the event
  (resume-skip SKIP_ALL, BatchLaneWorkers :772-798) are NOT deferred — this distinction is exactly
  what keeps `SequentialBatchCoordinatorTest` ("page without an OCR reference…", :372-407) green.
- `NativeLaneWorker.runOcrStage` signature (BatchCoordinatorInterfaces.kt:32-38) is UNCHANGED.

### 3.2 Wakeup on lease release — waiter registry (chosen)

`PageStageLeaseTable` gains a race-free waiter map (not SharedFlow — a tryEmit before subscribe is
lost; not store-`state` observation — release-without-write paths exist, e.g. translatePreparedPage
stale-return :710 → finally :794):

```kotlin
private val leaseReleaseWaiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()
suspend fun awaitPageLeaseRelease(pageKey: String, timeoutMs: Long): Boolean
```

- `awaitPageLeaseRelease`: under `synchronized(pageLeases)`, if `pageLeases[pageKey] == null` return
  true immediately; else register a `CompletableDeferred`, then `withTimeoutOrNull(timeoutMs) { await() }`.
- `releasePageStageLease` :112-120 / `cancelPageStageWork` :123-134 / `releaseAllPageLeases` :137-143
  complete+remove matching waiters (under the existing mutex block; `complete()` is non-suspending,
  no lock-hold concern). Waiters removed in `finally` — bounded memory, no polling, no threads.
- Store stub: `ChapterTranslationStore.awaitPageLeaseRelease` (same-pattern delegator as :402-419).
  Per-wait bound `LEASE_HANDBACK_WAIT_MS = SINGLE_PAGE_TIMEOUT_MS` (120 s).

### 3.3 In-pass rescan placement

In `runPass1`, after the main loop (`while (cursor < orderedPages.size || retainedProbe != null)`,
:464-540) and only on the COMPLETED path (`stoppingOutcome == null`, i.e. before the outcome build
:581-584):

```
for (pageKey in deferredPages.keys.toList())            // original order
    repeat(RESCAN_MAX_ATTEMPTS = 2) {
        if (awaitLeaseHandback(pageKey)) {              // injected ctor lambda, default null
            val outcome = runOcr(pageKey) → one-page processChunk(page)   // reuse existing locals :60,:109
            completedPassPageKeys += outcome.completedPageKeysForPass()
            if (page no longer deferred) break
        }
    }
```

- Coordinator ctor gains `awaitLeaseHandback: (suspend (String) -> Boolean)? = null`;
  `BatchChapterTranslator` (:507-511) passes `{ store.awaitPageLeaseRelease(it, LEASE_HANDBACK_WAIT_MS) }`.
  Default null ⇒ existing coordinator tests (runTest, fake workers, no events) behave exactly as today.
- **Batch identity**: the rescan re-enters the REAL `nativeWorker.runOcrStage`, which re-attempts
  `tryAcquirePageStageLease(BATCH)` (:754) and writes a fresh `batchWriteIdentities[pageKey]`
  (:764-771) — every guarded write (`BatchWriteGate.guardedBatchUpdate` :85-113) has valid identity.
  No stale identity is ever reused.
- Reconciler argument: with the rescan in-line, `BatchProgressReconciler.reconcile` (:613-617) runs
  only after every deferred page was re-offered; a handback page is terminal from its own owner's
  commit (hasRenderedResult → doneCount :89) or was re-run by the batch. The stranding branches
  (:79-86 "stranded by a prior run", :94-101 "cancelled or non-terminal") become unreachable for
  the C-02 case; the :620-631 stranded cleanup write (rejected by the write gate for a never-acquired
  page, BatchWriteGate :91-92) stops firing for it.
- **Bound / reader holds forever**: per-page 2 attempts × 120 s wait. Pathological case (reader
  parked inside a hung native call, H-08 — the one unbounded reader hold): the page falls through to
  reconciliation as today (stranded → chapter ERROR :105-109) with the rescan attempts logged —
  truthful (the batch really did not finish the chapter), and stall *visibility* is D8's job, not D3's.
  D4 guarantees the rescan cannot contend with same-chapter auto while it waits.
- PAUSED/FAILED/PERSISTENCE_REJECTED stop paths (:542-573) do NOT rescan — deferred tail pages remain
  pending under `reconcilePaused` semantics (BatchProgressReconciler :152-176), unchanged truthfulness.
- Sub-case (not exercised by Phase-1 tests): lease released WITHOUT a terminal commit (reader
  cancels mid-flight → CANCELLED states). The rescan re-plans the page and the batch translates it
  — the page still ends translated; exactly-once holds per intent (manual cancelled → 0 batch vs
  manual calls for it = the batch's 1).

---

## 4. D4 — batch-lifetime same-chapter auto suppression (C-03/M-06)

- **Batch-active signal (exact existing fields)**: `isBatchTranslationRetained(chapterId)`
  (TranslationManager.kt:649-656) = queue entry with status `QUEUE|TRANSLATING|PAUSED`
  over `translator.queueState` (ChapterTranslator.kt:145). PAUSED is included deliberately: a paused
  batch still owns the chapter and will resume (same reasoning as the sweep guard, ReaderViewModel :2532).
- **Guard placement (manager level — see §0 contradiction)**:
  1. `TranslationManager.updateAutoWindow` (:1372-1390): early-return when
     `isBatchTranslationRetained(identity.chapterId)`.
  2. `TranslationManager.requestAutoWindow` (:1355-1358): same early-return (legacy auto entry;
     its reservation loop launches real work at TranslationScheduler.kt:341).
  3. `TranslationManager.reconcileAutoWindow` (:1395-1397): pass
     `admissionGuard = { id -> !isBatchTranslationRetained(id) }` into the scheduler's existing
     hook (TranslationScheduler.kt:229-235) — a stale window cannot be re-admitted mid-batch.
  4. `translateChapter`'s one-shot `shutdownAutoCoordinator(chapterId)` (:681) is unchanged and
     remains what retires an already-live window at admission.
  Optional hardening (not required for green): mirror a `batchActiveCheck: (Long) -> Boolean?`
  lambda into the scheduler guard set :158-160 for future direct-scheduler callers.
- **Lifetime**: suppression is a pure predicate read at each entry — active from queue admission
  until the entry drains or leaves the retained set; no state to clean up, no epochs.
- **Recovery**: the reader's next `updateAutoWindow` after the predicate turns false arms normally
  (the D4 test asserts exactly this at `queue.value = emptyList()`, TranslationManagerAutoArbitrationTest
  :176-186). No drain notification is added; a reader parked without further page events stays
  suppressed until its next window update (accepted; visible as "auto idle while batch queued").
- Test-fixture note: the D4 test drives real manager methods with a mocked translator
  (:111-117), so manager-level guarding needs NO new fixture wiring — this is why the manager level
  was chosen over a scheduler-injected lambda.

---

## 5. Priority & non-goals (this phase)

| Resource | Rule | Phase 2 |
|---|---|---|
| Page/stage lease | MANUAL > AUTO > BATCH; MANUAL-vs-BATCH = wait-and-attach (never preempt; attach cannot duplicate paid work); MANUAL-vs-AUTO = evict at boundary, fenced fail-closed; same-origin re-entry kept | **CHANGED (D1-D3)** |
| Native stove | single-permit fair FIFO; no priority, no kill (H-03/H-08) | UNCHANGED (D8 → Phase 4) |
| Provider wallet / interactive reservation | UNCHANGED; attach waits hold no INTERACTIVE reservation | UNCHANGED (D6 → Phase 3) |
| Store mutex / persistence / publish chain | UNCHANGED | UNCHANGED (D11 → Phase 4) |
| UI, copy, ReaderViewModel surfaces | typed outcomes only; no UI/copy | UNCHANGED (Phase 5) |
| Engine lifetime (stop/close under reader work) | UNCHANGED | UNCHANGED (D7 → Phase 4) |

---

## 6. Test-impact map

Phase-2 production changes make the 4 RED tests green:

| RED test (blocking assertions) | Turned green by | Satisfied assertions |
|---|---|---|
| `D2ManualBatchInterleavingTest` test 1 (:37-113): manualWaited probe :74-85; lease BATCH :62-64; post-release owner-null :99, calls==1 :102, render READY :105 | D1 (origin param) + D2 attach wait (§2.2-2.3): job suspends on `state.first{terminal}` while batch owns; completes only after batch commit; zero provider calls | all, incl. all three barriers PROVIDER_START/END/RENDER |
| `D2ManualBatchInterleavingTest` test 2 (:116-195): stranded empty :171, TRANSLATED :174, terminals :183-184, calls :187-188 | D3 rescan (§3.3): batch defers p1 (owner now MANUAL after D1), rescans after the manual's release+commit; p1 is terminal → SKIP_ALL path counts done; batch pays only p0 | all |
| `D3ReaderOwnedPageAcrossBatchTest` (:38-153): stranded empty :123, TRANSLATED :126, tracker 2/2 :132-133, p1 READY :142, calls :145-146 | same D3 mechanics; reconciliation runs after rescan (test choreography already releases before awaiting reconcile, :108-117) | all |
| `TranslationManagerAutoArbitrationTest` "manager suppresses…" (:86-191): re-arm stays null :159-165, reconcile doesn't resurrect :168-172, drains-recovery :176-186 | D4 guard (§4.1-4.3) | all |
| `NormalMangaIsolationTest` | untouched paths (no arbitration entries added); must stay GREEN | all |

Legitimate behavior/compile changes to neighbor tests (record in PHASE-LOG.md):
- **Compile fix (required)**: `PageWriteOrigin.READER_ADHOC` is deleted — D2 test :140 and D3 test :70
  assert `pageLeaseOwner == READER_ADHOC`; change to `MANUAL` (same intent: the reader's manual owns
  the page). Red reasons otherwise unchanged.
- `SequentialBatchCoordinatorTest`: UNCHANGED — its fake worker returns null without the new
  `ocrDeferred` event (:376-386), and `awaitLeaseHandback` defaults null. All other fake-worker tests
  (:409-439+) unaffected.
- `ChapterTranslatorTerminalExitsTest`: UNCHANGED (no denied leases in its scenarios; no signature it
  touches changes).
- All other `TranslationManagerAutoArbitrationTest` tests: UNCHANGED (paused-chapter predicates :42-74
  read, not written, by D4).
- Full sweep expectation: the 1231-test baseline (phase1-implementation-log §5: 1231 completed / 4
  failed) → 1231 completed / 0 failed after Phase 2, plus the D2/D3 assertion edits above.

---

## 7. Risks & sequencing for the Implementer

Suggested commit order (each compiling + committing per PLAN §2; matches the suggested D1→D4→D2→D3):

1. `t917(p2): D1 origins` — enum + §1.3 sites + artifact mapping + MANUAL-evicts-AUTO rule + the two
   test compile fixes. Tests still RED with identical reasons (D2.1 manualWaited false; D2.2/D3
   stranded p1; D4 re-arm true). Run coexistence + coordinator + arbitration filters.
2. `t917(p2): D4 suppression` — manager guard (§4). D4 test green; smallest isolated change; proves
   the contract-flip machinery before the larger diffs.
3. `t917(p2): D2 wait-and-attach` — outcome type + executor signature + boundary attach + scheduler
   outcome map + cancel-path guard. D2 test 1 green at all three barriers; paid-call oracles exact.
   Watch: the first-execution post-release render path flagged in phase1-implementation-log §4.6.
4. `t917(p2): D3 defer-and-rescan` — listener event + waiter map + rescan loop + translator wiring.
   D2 test 2 + D3 green. Widest surface; do last; keep the SKIP_ALL-vs-deferred distinction intact.
5. `t917(p2): sweep` — full `eu.kanade.translation.*` run; PHASE-LOG entry incl. D4 contract-change
   callout (PLAN §5 row); tag `checkpoint/t917-p2-done`.

Key risks:
- **D1 rename blast radius**: all READER_ADHOC sites are enumerated in §1.3 — grep-verified complete.
  The lease-table dual locking and store mutex are NOT touched.
- **Attach wait on the reader path**: suspension only, cancellable, bounded (210 s worst case);
  no decode/native work; interactive wallet reservation released before waiting.
- **Bounded memory / Android 8 / reader stability**: new state per store = a small waiter map
  (entries removed on completion/release); scheduler map capped at 32; no new threads, dispatchers,
  channels, or platform APIs (plain coroutines). Normal manga: no path added before the
  translation-enabled gates — the isolation test gates every commit.
- **Do NOT** let the D3 rescan run on stop paths, or treat resume-skip nulls as deferrals — both
  break existing green tests by design (§6).
