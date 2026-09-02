# T917 Phase 4 — Design Note (D7, D8, D10, D11)

Audience: Implementer. Scope: design only, no code. Line citations are HEAD of `t917/coexistence-v3`
post-Phase-3 (`a07d68d`, D1–D6/D9 landed). Evidence labels per `docs/roles/technical-lead.md`.
Unmeasured numbers are `[TARGET]` per D13. Implementation order: D7 → D8 → D10 → D11 LAST.

## 0. Contradictions / refinements vs prior docs (flagged first)

1. **Draft D7 / audit H-04 name "stop **or completing** batch" as engine-close triggers — the
   "completing" half is FALSE at HEAD.** VERIFIED: the only production call of
   `pipeline.closeEngines()` is `ChapterTranslator.stop` (`ChapterTranslator.kt:314`). Natural
   batch completion never closes engines; the cached instances survive and the rebuild gate
   (`EngineLane.ensureEnginesBuiltFor`, EngineLane.kt:264-298) reuses them for the next run.
   Residual D7 scope is therefore the **stop paths only** (complete inventory in §1.1). PLAN §3
   Ph4's wording "EngineLane.closeEngines/ChapterTranslator.stop" is correct; the draft's
   "stop/complete" is not — deviation recorded here per Director visibility.
2. **Draft D7's engine-close race is reachable ONLY through the HTTP translate phase — native
   work cannot race a close.** VERIFIED: `closeEngines` tears down only when the native lane is
   momentarily idle (`tryRunExclusive`, EngineLane.kt:235-242; NativeRunQuarantine.kt:31-39), and
   any engine rebuild runs INSIDE the permit under `engineRebuildMutex`
   (SinglePageOnnxPhase.kt:222-226, BatchChapterTranslator.kt:236-238) — ordered after a timed-out
   predecessor's real exit by construction. The HTTP translate phase runs OUTSIDE the permit, and
   `SinglePageHttpRenderPhase` captures `activeTranslator` by reference (:191) and admits the race
   in its own comment: "The old translator may be closed mid-flight, causing this page to fail and
   retry with the new instance — an accepted trade-off without the complexity of drain logic"
   (SinglePageHttpRenderPhase.kt:143-147). Phase 4 D7 therefore targets that phase (and that
   comment); no native-side epoch machinery is needed.
3. **Phase 3 already covers the PROVIDER-call half of "drain-not-close" (D6); D7's residual is the
   ENGINE half.** VERIFIED: `RollingAutoCoordinator.consumeTranslations` wraps translate+commit in
   `withContext(NonCancellable) { withTimeout(drainGraceMs) }` (`drainGraceMs` ctor seam,
   `PROVIDER_DRAIN_GRACE_MS = 90_000`, RollingAutoCoordinator.kt:81/:441-459/:1077). That protects
   an in-flight provider call from cancel; it does NOT protect the in-flight call from the
   translator instance being **closed** underneath it. D7 builds beside it, not over it.
4. **PLAN §3 Ph4 (D8): "result timer unchanged" — REFUSED for one adjacent defect found during
   verification.** VERIFIED: when the 90 s native timer fires, the single-page boundary maps the
   `null` lane result to **`SinglePageOutcome.Completed`** (TranslationPipeline.kt:440-469, the
   `?: return SinglePageOutcome.Completed` at :469) while `markPageTimedOut` concurrently writes a
   FAILED placeholder into the store (PageStoreWriter.kt:100-137). The scheduler's
   `manualOutcomes` map therefore records a LIE on every native timeout — exactly the §7
   "silent outcome" defect class D8 exists to end, on the same lines. Additionally the placeholder
   message hardcodes the wrong timer: "Translation timed out after 120 s"
   (`SINGLE_PAGE_TIMEOUT_MS / 1000`, PageStoreWriter.kt:126) for a 90 s ONNX timeout. Both fixes
   are folded into D8 §2 (small, same files as the watchdog); the 90 s value itself is unchanged.
   Also stale ref: audit cites `TranslationPipeline.kt:117`; the constant now sits at :121.
5. **PLAN §3 Ph4 (D10): "batch trigger cross-checks source page list vs downloads" — REFINED: the
   source page list is not available offline in general.** VERIFIED: the domain `Chapter` carries
   no page count (domain/chapter/model/Chapter.kt:10-24 — no pages field); the only offline source
   of truth is a live `Download.pages` (source `Page` list fetched at download-queue time,
   Download.kt:23). The cross-check is therefore defined **exactly when a `Download` object exists**
   for the chapter (queued/active/stopped-with-error); otherwise the count is UNKNOWN and D10's
   honesty obligation degrades to labeling (§3.3). The task prompt's "silently skipped **or fail
   the whole batch**" question resolves to: silent subset, never a batch failure — a batch on a
   partially-downloaded chapter enumerates whatever files exist (ChapterTranslator.kt:602-623;
   util/ChapterPages.kt:22-74) and completes over the subset; only a fully absent chapter
   directory fails cleanly (ChapterTranslator.kt:581-594). `expectedPageCount` is derived from that
   same enumeration and stamped `trusted=true` (ChapterTranslationStore.kt:1376-1377, :1559-1564)
   — the M-08 lie is structural, and `expectedPageCountTrusted` is the exact flag that encodes it.
6. **Draft D11 Recommendation ("decouple persistence from the store mutex") — THREE corrections
   from verified code, and a staging recommendation (defer the write-behind core; §4.5):**
   - (a) The store mutex is **per-chapter** (one `ChapterTranslationStore` per chapter via
     `ActiveChapterStoreRegistry`). The draft's "one chapter's slow SAF flush delays every other
     writer's commit **for that chapter**" is the exact truth; the CROSS-MODE/CROSS-CHAPTER
     latency coupling the draft also asserts flows through a different lock: ARTIFACTS commits run
     **while the native permit is held** on the ONNX paths (VERIFIED:
     `translateSinglePageOnnx` runs inside `withNativeLane`, TranslationPipeline.kt:440-459, and
     itself persists the cleaned image + page commit via `persistCleanedBitmap` →
     `store.patchPage` — SinglePageOnnxPhase.kt:619-637 → CleanedPublication.kt:144 — plus a
     `store.flush()` under the permit, SinglePageOnnxPhase.kt:505). D11's highest-value,
     lowest-risk slice is the permit-held publication, not the mutex.
   - (b) The D9 ledger is deliberately synchronous under the store mutex
     (ChapterTranslationStore.kt:364-390; phase3-design §6: "deferral would recreate the crash
     window D9 exists to close"). A write-behind manifest writer MUST NOT delay ledger writes, and
     `resolveAttempt` must not be allowed to report "verified" before the page's durable
     publication actually landed — otherwise D11 widens the exact H-10 window D9 closed.
   - (c) `publishLocked`'s failure contract is synchronous and load-bearing: a failed publication
     rejects the commit and restores the in-memory page
     (`ARTIFACT_PUBLICATION_FAILED`, ChapterTranslationStore.kt:616-619, :655-658). Write-behind
     makes publication failure asynchronous — the store needs a NEW degraded-publication contract
     before the mutex can be decoupled. That, plus (b), is why §4 recommends the staged version
     and routes the deviation to the Director (the adopted Recommendation is deferred, not
     silently dropped).
7. **Phase-3 Reviewer finding 4 (phase3-verification.md §4.2, LOW-MEDIUM, accepted at the P3
   checkpoint) is folded into Phase 4 D7/D8 scope.** VERIFIED against code: D6's provider drain
   grace `PROVIDER_DRAIN_GRACE_MS = 90_000` (RollingAutoCoordinator.kt:1077) can expire while the
   auto chain is still inside its own legitimate budgets — ONNX up to 90 s
   (TranslationPipeline.kt:121) plus HTTP+render up to 120 s (SINGLE_PAGE_TIMEOUT_MS at :479),
   sequential — so a legitimate long call is cut off cancellation-class mid-chain, its D9 entry
   stays unresolved (counted as a consumed attempt), and the page re-runs later (the finding
   notes this can contribute to the D9 cap for slow providers under frequent reader-close
   patterns). Phase 4 reconciles the bound with the chain budget (§1.6) and scopes the D8
   watchdog to the lane so a legitimately long chain can never read as "stalled" (§2.2).

## 1. D7 — epoch-based engine lifetime + drain-not-close

### 1.1 Today (VERIFIED)

- **Every engine-close call site** (exhaustive; `pipeline.closeEngines` has exactly one production
  caller):
  1. `TranslationManager.clearQueue()` → `translator.clearQueue(); translator.stop()`
     (TranslationManager.kt:688-692). `stop()` with null reason falls through the
     `if (reason != null && !closeEngines) return` gate (ChapterTranslator.kt:312) into
     `pipeline.closeEngines()` (:314). Reached from:
     - notification **ACTION_STOP** (`TranslationForegroundService.kt:65-68`, on the main thread);
     - `removeFromTranslationQueue` when the last queue item goes
       (TranslationManager.kt:1620-1633, stop at :1628).
  2. Reader "Stop" button → `stopAllTranslation()` → `translatorStop("user stop",
     closeEngines = true)` (ReaderViewModel.kt:2301-2316, stop at :2313) — after
     `cancelAllPageTranslationsOffMain(cancelBatchQueue = true)` already cancelled scheduler page
     jobs.
  3. Translation toggle off → `translatorStop("translation disabled", closeEngines = true)`
     (ReaderViewModel.kt:640).
  4. Reader-stop trio does NOT close engines: `stopReaderTranslations` passes
     `closeEngines = false` (ReaderTeardownCoordinator.kt:67-79) and the :312 gate skips the close.
- **What close does**: `enginesClosed = true` immediately; clears `inFlightPageKeys`; then
  `nativeRunQuarantine.tryRunExclusive { close both engines }` — closes NOW only if the native lane
  is idle, else logs "engine close deferred" and relies on the next admitted invocation's rebuild
  path to close the old instances (EngineLane.kt:229-248, rebuild-close at :280/:291, flag reset
  :295-297).
- **The race**: the HTTP translate phase runs outside the permit. While an AUTO or MANUAL page is
  mid-provider-call, the native lane IS idle, so ACTION_STOP's close runs immediately and closes
  `textTranslator` under the in-flight call (providers close their executors/pools in `close()`;
  audit H-09: OpenAiCompatibleTranslator.kt:205-208 et al.). The captured `activeTranslator`
  (SinglePageHttpRenderPhase.kt:191) then fails; the phase's comment declares this an accepted
  trade-off (:143-147). `clearQueue()` never touches rolling auto (audit H-09 asymmetry, still
  true — TranslationManager.kt:688-692 touches only translator + pending requests), so the racing
  work is typically a **cross-chapter AUTO** page the user never asked to stop.
- **No retry actually exists for MANUAL**: a failed HTTP phase throws → `markPageFailed` →
  boundary rethrows → the manual job ends FAILED (the comment's "fail and retry" is at best the
  AUTO path's `deferTranslationRetry`). The user's tap is lost on Stop.

### 1.2 Change spec

- **`engineEpoch: AtomicLong` on `EngineLane`** (avoids collision with the quarantine's
  `generation` and the scheduler's `chapterCancellationEpochs`). Incremented ONLY in
  `closeEngines()` — the moment the cached instances are (or may soon be) torn down. Rebuilds do
  NOT bump it: they are permit-ordered and produce a coherent new pair. New accessor
  `fun currentEngineEpoch(): Long`.
- **Translator borrow registry** on `EngineLane`: `translatorUseCount: AtomicInteger` with
  `beginTranslatorUse()` / `endTranslatorUse()` (try/finally at the SINGLE page boundary that
  borrows the translator: `translateSinglePageHttpRender`, capturing at the :191 site). Bounded:
  one int. This is what makes "in-flight reader work" observable to the stop path.
- **Drain-not-close in `closeEngines()`** (signature gains `drainGraceMs: Long =
  ENGINE_DRAIN_GRACE_MS` as the LAST ctor param of `EngineLane` with a production default, plus a
  `drainScope: CoroutineScope?` injected by `TranslationPipeline` = `nativeRunScope`):
  1. `enginesClosed = true`; epoch++ (unchanged semantics: no new borrow may capture).
  2. If `translatorUseCount == 0`: attempt `tryRunExclusive` close immediately (today's behavior,
     fast path).
  3. Else: launch on `drainScope` a ONE-SHOT bounded drain: `withTimeout(grace)` await
     `translatorUseCount == 0` (a `CompletableDeferred` signalled by `endTranslatorUse` — event-
     driven, no polling), then `tryRunExclusive` close. Grace expiry closes anyway. The stop path
     stays NON-BLOCKING (ACTION_STOP runs on main; nothing joins the drain).
- **Epoch guard + exactly-one retry** in `translateSinglePageHttpRender`:
  - capture `epochAtCapture = engines.currentEngineEpoch()` next to `activeTranslator` (:191).
  - Wrap the translate attempt: if the HTTP call failed AND `engines.currentEngineEpoch() !=
    epochAtCapture` AND not yet retried → re-capture `textTranslator` (the rebuild gate guarantees
    the next admitted invocation rebuilt; the retry's own native needs are nil — this phase is
    HTTP-only), re-read the glossary snapshot from the store, retry ONCE inside the same
    ledger-wrapped call (§1.3). A second epoch mismatch → the honest typed failure (no loop).
  - The retry consumes no extra lease/generation rights: the boundary still holds its page lease
    and its captured commit precondition; all store fences stay armed.
- **Retire the comment** at SinglePageHttpRenderPhase.kt:143-147, replacing it with the epoch/drain
  contract (the PLAN Ph4 exit criterion).
- The rebuild-path close (:280/:291) is intentionally left as-is: it already runs under the
  permit, and the epoch guard is its safety net for the in-flight HTTP window it can still race.

### 1.3 D6 / D9 interactions

- **D6**: an AUTO page draining under `NonCancellable` (RollingAutoCoordinator.kt:441-459) that
  hits an engine close now transparently retries once against the rebuilt translator instead of
  failing; its drained commit semantics (commit even though the window is gone) are unchanged.
- **D9**: the epoch retry lives INSIDE the existing ledger wrap (`runLedgerWrapped`,
  SinglePageHttpRenderPhase.kt:232-253) — ONE entry covers original + retry; `resolveAttempt`
  fires on the final outcome exactly as today. A retry that is itself cancelled leaves the entry
  unresolved (counted), matching phase-3 semantics. The close path must NOT touch the ledger.

### 1.4 Test plan (write FIRST, failing)

New seam needs: `drainGraceMs` ctor param on `EngineLane` (harness passes a short value);
`drainScope` injection; the harness already has the ENGINE_CLOSE barrier point and fake
`TextTranslator.close()` counters (phase1-harness-notes §2, §1.2(2)). RED must fail by assertion,
never timeout.

1. `coexistence/D7EngineEpochStopRaceTest.kt`:
   a. AUTO page parked at PROVIDER_START; fire `manager.clearQueue()` (ACTION_STOP analogue);
      release the barrier → the call completes, terminal translation-state committed, paid calls
      ≤ 2 (1 when the drain wins, 2 when grace expired), `cancelledCalls == 0`, D9 entry resolved,
      and the SECOND call (if any) landed on the rebuilt translator instance (fake exposes an
      instance id). RED today: the parked call FAILS when `closeEngines` closes the fake
      translator mid-call → terminal-state and cancelledCalls assertions fail.
   b. Grace path: `drainGraceMs` large → assert close happened only after `endTranslatorUse`
      (close-order event log via fake `close()` counter vs barrier arrival).
   c. Grace-expiry path: `drainGraceMs = 0` → close proceeds under the call; epoch retry fires
      exactly once (paid calls == 2); a SECOND mid-retry close (epoch moves again) → typed
      failure, still ≤ 2 retry-eligible attempts, no loop.
   d. Native-safety guard: with a native call parked at NATIVE_ACQUIRE, `closeEngines` does NOT
      close (tryRunExclusive fails; log path) — pins the idle-lane contract.
   e. P3-finding-4 bound relationship (pure, virtual-clock-free): assert
      `PROVIDER_DRAIN_GRACE_MS >= ATTACH_TIMEOUT_MS` (the chain budget) — RED at the constant's
      current 90_000 (phase3-verification.md finding 4), GREEN after §1.6's alignment.
2. Non-regression: `NormalMangaIsolationTest` untouched and green; existing stop tests
   (`ChapterTranslatorTerminalExitsTest`) green with unchanged expectations.

### 1.5 Risks

| Risk | Sev | Mitigation |
|---|---|---|
| Drain coroutine closes engines AFTER a rebuild already replaced them (close kills NEW engines) | HIGH | Close path re-checks: only close the instances captured at close time (snapshot the two references at `closeEngines()` entry, close those exact objects under tryRunExclusive); `enginesClosed` flag stays the rebuild authority. Test 1b asserts order. |
| Retry masks a real provider failure as success-loop | MED | Exactly-one retry, gated on epoch delta only; second mismatch fails honestly (test 1c). |
| `translatorUseCount` leak on an exception path wedges the drain | MED | `endTranslatorUse` in `finally` at the single capture site; drain expiry bounds the wait regardless. |
| Stop latency feels slower on main thread | LOW | Nothing blocks: drain is fire-and-forget on `nativeRunScope`; immediate-close fast path preserved for the common idle case. |
| §1.6 drain-grace alignment extends the worst-case reader-stop join (90 s → 210 s) while a call is genuinely mid-flight | MED | Only while a call is legitimately still running; joins run on IO dispatchers (ReaderTeardownCoordinator.kt:92-103), never main; HTTP has its own transport timeouts under the 120 s ceiling; the alternative (cutting healthy calls) bills real money and feeds the D9 cap (P3 finding 4). Phase 6 re-validates the bound. |
| Memory/Android 8 | LOW | One AtomicLong + one AtomicInteger + one deferred; no new APIs. |

### 1.6 Drain grace vs chain budget — reconciling phase3-verification finding 4

phase3-verification.md finding 4 (relayed to this phase): D6's
`PROVIDER_DRAIN_GRACE_MS = 90_000` (RollingAutoCoordinator.kt:1077) equals the ONNX phase budget
alone, but the chain it drains (`executor.translatePreparedPage` inside
`withContext(NonCancellable) { withTimeout(drainGraceMs) }`, RollingAutoCoordinator.kt:441-459)
legitimately runs ONNX (≤ 90 s, TranslationPipeline.kt:121) PLUS HTTP+render (≤ 120 s,
SINGLE_PAGE_TIMEOUT_MS at :479) sequentially. A cancel landing early in a long, healthy call
expires the grace cancellation-class → the call is killed mid-chain, the D9 entry stays
unresolved (counted), the page re-runs — a wasted paid call and a potential contributor to the
D9 cap under frequent reader-close patterns (finding 1's scenario). Phase 4 resolves the two
grace bounds as follows:

1. **Provider drain (D6 constant, aligned here):** set
   `PROVIDER_DRAIN_GRACE_MS = ATTACH_TIMEOUT_MS` (TranslationPipeline.kt:123-130 — already the
   "owner's own bounded phase chain" constant: ONNX + HTTP+render, 210 s `[TARGET, aligned]`).
   Rationale: the D2 attach wait was already sized to exactly this chain, so a drained call can
   never be cut by a bound SHORTER than its own legitimate budget; expiry then implies a genuinely
   hung call, and cancellation-class expiry remains the correct outcome (HTTP cancellation is
   safe — phase3-design §2.3). Deriving the bound dynamically from the chain's remaining budget
   is DECLINED: it needs phase-token plumbing across the boundary for a case the static bound
   already covers. Known cost, accepted and documented: the worst-case join on
   reader-stop (`jobsToJoin.joinAll`/`awaitTermination`, TranslationScheduler.kt:948-949) extends
   from 90 s to 210 s — only while a call is genuinely still running, on IO dispatchers
   (ReaderTeardownCoordinator.kt:92-103), never the main thread. This is a one-line constant
   change plus the D6DrainNotCancelTest bound assertion (that test pins `90_000`, so the edit is
   a CONTRACT-CHANGE test edit — record it in PHASE-LOG.md per the D4 precedent). Phase 6
   measurement re-validates both bounds (§2.4).
2. **Engine drain (D7's own `ENGINE_DRAIN_GRACE_MS = 5_000 [TARGET]`) stays SHORT — deliberately
   NOT budget-aligned.** Different semantics: expiry does not fail or bill anything — it closes
   the (already-stopped system's) engines and the epoch guard transparently retries the racing
   page against the rebuilt instance exactly once (§1.2), so the cut is invisible to the user and
   bounded to one extra paid call in the rare grace-expiry case. Holding engines open for up to
   210 s after an explicit Stop to spare that rare retry would delay native-memory release
   (recognition engine teardown) — the bounded-memory constraint outranks the rare retry.
   Documented here so the asymmetry between the two graces reads as a decision, not an oversight.

## 2. D8 — occupancy watchdog + honest stall state (no native kill)

### 2.1 Today (VERIFIED)

- `ONNX_PHASE_TIMEOUT_MS = 90_000` (TranslationPipeline.kt:121; used :441, :688 single-page,
  BatchChapterTranslator.kt:228 batch — engine setup). It is a **result-invalidation** timer, not
  an occupancy bound: `NativeRunQuarantine.run` selects invocation vs `onTimeout` INSIDE the
  admission lock; on timeout it bumps the generation, fires `onTimeout`, and then
  `awaitExitAndLogLate` **suspends inside `admission.withLock` until the native call really
  exits** (NativeRunQuarantine.kt:19, 47-80, 82-96). A hung ONNX call holds the stove
  indefinitely; the timer only prevents a late result from being used. There is no kill and there
  must be none (JNI abort risk — draft D8).
- When the timer fires today, for the caller: `withNativeLane` returns `null` → the boundary
  returns **`Completed`** (TranslationPipeline.kt:469; also :449 for the "already in flight"
  rejection — same lie shape) while `markPageTimedOut` writes the FAILED placeholder with the
  wrong "120 s" message (PageStoreWriter.kt:126) and `invalidateGeneration` (PageStoreWriter.kt:117).
  `onPageStuck` → `scheduler.markPageJobStuck` evicts the abandoned job so later taps can be
  admitted (TranslationManager.kt:225-230; TranslationScheduler.kt:747-757) — but the job's
  `inFlightPageKeys` entry only clears at REAL native exit (TranslationPipeline.kt:447/:467), so a
  re-tap during the stuck window hits "already in flight" → `null` → **another silent
  `Completed`** with nothing happening on screen.
- The reader's truth during the stuck window: page stage statuses RUNNING (store StateFlow), no
  stall signal anywhere, no bound on admission waits for queued callers.

### 2.2 Change spec

- **Occupancy signal from the quarantine** (no new locks): `NativeRunQuarantine` gains an optional
  observer invoked inside `admission.withLock` at grant and in the exit `finally`:
  `onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long)` /
  `onLaneReleased(token: Long)`. Token = the run generation (already unique per invocation,
  NativeRunQuarantine.kt:48).
- **Watchdog** on `TranslationPipeline` (owns the quarantine): on occupied, launch a ONE-SHOT
  `delay(NATIVE_STALL_THRESHOLD_MS)`; if the same token still holds the lane at fire time,
  publish `NativeStallState(pageKey, startedAtEpochMs, stalledAtEpochMs)` to a
  `MutableStateFlow<NativeStallState?>`; `onLaneReleased` cancels the pending timer and clears.
  `NATIVE_STALL_THRESHOLD_MS = ONNX_PHASE_TIMEOUT_MS` (90_000) `[TARGET, aligned]` — i.e. "stall"
  means: the result timer has fired (or is due) and the lane is STILL held. One StateFlow, one
  timer per occupancy — bounded memory; no polling loops; Android 8 safe.
  - **Stalled native call vs legitimately long chain (phase3-verification finding 4):** the
    watchdog is scoped to LANE occupancy only — it observes the native permit, which the HTTP+
    render tail never holds (the §0.2 asymmetry). A chain running ONNX (≤ 90 s) plus HTTP+render
    (≤ 120 s) sequentially therefore cannot trip the watchdog from its legitimate HTTP tail, and
    its legitimate native occupancy is exactly the budget the threshold is derived from — the
    same budget data (`ONNX_PHASE_TIMEOUT_MS`, and `ATTACH_TIMEOUT_MS` for the chain total) that
    §1.6 aligns the provider drain grace to. `NativeStallState` carries the phase tag
    (`NATIVE_LANE`) so Phase 5 copy can never render a healthy long chain as stalled, and the
    §2.3 graph tests assert no emission during a legitimately long HTTP-only tail.
- **Typed stall outcome, no new PageTranslation status** (M-07 vocabulary discipline; phase-3
  precedent of typed outcomes):
  - `TranslationPipeline` exposes `val nativeStall: StateFlow<NativeStallState?>`;
    `TranslationManager` re-exposes it for the reader/notification (Phase 5 renders it; the
    cancel affordance already exists as the per-page cancel).
  - `SinglePageOutcome.Stalled(pageKey: String, stalledSinceEpochMs: Long)` added beside
    `Paused` (TranslationExecutor.kt:134-156). The manual boundary checks `nativeStall.value`
    BEFORE admission and returns `Stalled` instead of queueing an unbounded native wait — the
    "refuses new promises" clause. Mapped into `manualOutcomes` like `Paused`
    (TranslationPipeline.kt:497-505 → TranslationScheduler.kt:633).
- **Honest timeout mapping** (§0.4 fixes): `withNativeLane` returning `null` no longer becomes
  `Completed`. Distinguish the two null shapes at :440-469: timeout (`TimedOut`) →
  `SinglePageOutcome.Failed` with reason "native phase timed out" (new typed variant if absent —
  mirroring `Rejected(owner, reason)` shape; lands in `manualOutcomes`), "already in flight" →
  `Rejected(owner, "page already translating")` (visible rejection, §7-compliant). The batch side
  is unchanged (its timeout path already types an engine-setup abort, BatchChapterTranslator.kt:232-251).
- **Message fix**: `markPageTimedOut` gains a `timeoutMs` parameter so the placeholder message
  names the timer that actually fired (PageStoreWriter.kt:126). Batch's engine-setup timeout keeps
  its typed abort reason.
- **No native kill**: nothing in this design cancels, aborts, or interrupts the in-flight native
  invocation (unchanged: NativeRunQuarantine waits for real exit, NonCancellable).

### 2.3 Test plan (write FIRST, failing)

Seam needs: injectable `stallThresholdMs` (watchdog ctor/param, test uses a small value) and the
watchdog scope (harness test scope); otherwise existing fakes suffice. Timing assertions use the
virtual clock only in the pure unit suite; graph tests stay barrier-driven.

1. `translator`-style pure unit: `NativeStallWatchdogTest` (virtual clock, governor-test pattern):
   occupied → no emission before threshold; emission at threshold with the right token/pageKey;
   release before threshold → timer cancelled, no emission; release after emission → state
   cleared; a SECOND occupancy while stalled replaces (never accumulates) state.
2. `coexistence/D8StallWatchdogTest.kt` (graph):
   a. Native call parked at NATIVE_ACQUIRE past the (short) threshold → `nativeStall` emits;
      a manual tap during the stall returns `Stalled` in `manualOutcomes` and performs ZERO native
      admissions; release → stall clears, next tap proceeds normally.
   b. Native timeout (short injected `timeoutMs`) → boundary outcome is the typed FAILED/Rejected
      variant (RED today: `Completed`), store page FAILED placeholder, message names the fired
      timer, re-tap during the residual native window gets `Rejected("page already translating")`
      instead of silent `Completed`.
   c. Non-regression: a normal (sub-threshold) native call never emits stall; isolation test
      untouched.

### 2.4 Risks

| Risk | Sev | Mitigation |
|---|---|---|
| Watchdog misfires on slow-but-legal 90 s work | MED | Threshold == the result timer's own value `[TARGET, aligned]`; a fire therefore coincides with an already-invalidated result — false-positive surface is a stalled lane that genuinely IS occupied (the honest state). Legitimate HTTP-tail time never reaches the watchdog (lane-scoped, §2.2; finding 4 distinction). **Phase 6 MUST re-validate this bound AND §1.6's aligned drain grace against measured p50/p95/p99 chain durations before either number is cited anywhere user-facing (D13).** |
| New `SinglePageOutcome` variants break scheduler `when` exhaustiveness | LOW | Scheduler's mapping is else-guarded (Phase-3 precedent with `Paused`); add explicit branches + assert in `manualOutcomes`. |
| Stall flow leaks a stale state after scope death | LOW | Watchdog jobs are children of the pipeline scope; `onLaneReleased` in the quarantine's `finally` is the single clearer; process death clears by construction. |
| Reader renders nothing new (copy is Phase 5) | LOW | Accepted: Phase 4 delivers the typed signal + refusals; `manualOutcomes` already projects into UI state maps. |

## 3. D10 — partial-download admission

### 3.1 Today (VERIFIED)

- The Manga-screen trigger partitions requested chapters by
  `downloadManager.isChapterDownloaded(..., skipCache = true)` (MangaScreenModel.kt:~1088-1104) —
  which is a **directory-exists check** (DownloadCache.kt:150-171; DownloadManager.kt:215-221). A
  half-downloaded chapter therefore lands in the "downloaded" partition and is admitted directly
  (`translateChaptersIfCurrent`, same file). Chapters in the awaiting partition get
  `queueTranslationAfterDownload` (WAITING_FOR_DOWNLOAD) and are admitted only by the downloader's
  post-finalization handoff (Downloader.kt:782-818) — that path implies a COMPLETE download and is
  not the defect.
- The batch then enumerates the directory (ChapterTranslator.kt:602-623),
  pre-registers the found keys, and stamps `expectedPageCount = foundCount`,
  `expectedPageCountTrusted = true` (ChapterTranslationStore.kt:1376-1377; also :1559-1564) —
  progress truth is computed against that self-derived total (StoreStatusProjector.kt:64-104).
  Result: a silently partial "success" at 100%. (Audit M-08; the draft §10 open question — can
  admission race an active downloader — resolves to YES: an actively-downloading chapter has a
  partial dir and passes `isChapterDownloaded`.)

### 3.2 Change spec — admission

- New `pipeline/batch/BatchAdmissionProbe.kt` (pure, store-free):
  `evaluate(chapterId, downloadedPageCount, sourcePageList: List<Page>?): BatchAdmissionDecision`
  with exactly three outcomes:
  - `Complete` — a `Download` object exists and `pages.size == downloadedPageCount`;
  - `Partial(known)` — `Download` exists, mismatch: carries `expectedSourcePageCount = pages.size`
    and `downloadedPageCount`;
  - `UnknownCount` — no `Download` object (downloader restarted since; offline truth unavailable).
  Source of the `Download`: `downloadManager.queueState` lookup by chapter id (the list the UI
  already observes; no new network calls — a `fetchPageList` in admission is explicitly OUT, it
  adds latency/cost/failure modes to a local operation).
- Wiring: the trigger partition (MangaScreenModel) consults the probe for every "downloaded"
  candidate BEFORE `translateChaptersIfCurrent`:
  - `Complete` → admit unchanged.
  - `Partial` / `UnknownCount` → present the PLAN-mandated choice: "finish the download first" or
    "translate what exists (N of M / N of unknown)". "Finish" = the EXISTING
    `queueTranslationAfterDownloadIfCurrent` path (fenced WAITING + download attach,
    MangaScreenModel.kt:1106; TranslationRequestCoordinator.kt:91-102) — no new machinery. The
    dialog is functional Phase-4 structure with truthful literal text; final copy is Phase 5
    (D13: no unmeasured numbers in copy).
  - Multi-chapter groups: the probe runs per chapter; the group admits the complete ones and
    routes partials through the same dialog per chapter (no silent mixed admission).

### 3.3 Change spec — page-level PARTIAL semantics and truth

- **Subset admission records its honesty in the manifest**: additive nullable
  `PartialBatchInfo` on `ChapterArtifactManifest` (the D5 additive-nullable precedent — tolerated
  both directions by `ignoreUnknownKeys`): `{ expectedSourcePageCount: Int?, missingPageCount:
  Int, determinedFrom: DOWNLOAD_CROSSCHECK | UNKNOWN, recordedAtEpochMs }`. Missing pages are NOT
  registered as page records (no fake stages, no fake failures, no D9 entries — pages never
  attempted must never look attempted); instead the manifest carries the delta. On a later batch
  run after the download completes, the existing resume/rekey machinery (Downloader REKEY,
  Downloader.kt:785-799) plus the planner's registration path (:1565-1591) picks the new pages up
  and the info is cleared when `missingPageCount == 0` is re-derived from a full cross-check.
- **expectedPageCount truth**: when the cross-check is known, `preRegisterPages` is called with
  the probe's `expectedSourcePageCount` so the trusted total is the SOURCE total (progress shows
  37/40, not 40/40); when `UnknownCount`, keep the found count but the manifest records
  `determinedFrom = UNKNOWN` — the progress label is Phase 5's to render honestly; the durable
  record already stops claiming trust it does not have.
- **Terminal state**: the chapter completes TRANSLATED over its real pages (unchanged
  reconciliation); the partial label travels in `PartialBatchInfo` (queue/notification/drawer
  rendering is Phase 5). No new `StageStatus`, no new `Translation.State` (the §7 "every page
  reaches exactly one terminal state" holds over the pages that exist; the missing pages are
  documented absence, not stranded work).
- **D9 interplay**: no ledger writes for never-attempted pages (nothing to resolve); the crash
  cap counts only real attempts — unaffected.
- **Mid-batch downloader race**: admitted subset + pages landing mid-batch — the batch is a
  point-in-time traversal (unchanged); newly-landed pages are picked up by the NEXT batch run via
  the planner. Documented behavior, not new code.

### 3.4 Test plan (write FIRST, failing)

Harness needs: `mockkStatic(ChapterPagesKt)` already stubs enumeration (phase1-harness-notes
§1.2(3)); the probe is pure (no harness cost). The dialog itself is UI — its logic lands in the
screen model with the decision typed; graph tests assert the probe + the manifest truth, and a
lightweight screen-model-level test asserts the routing (pattern: existing MangaScreenModel-free
manager tests; if the screen model is not JVM-testable, the routing function is extracted pure and
tested there — Implementer to note the seam in the phase report).

1. `coexistence/D10PartialDownloadAdmissionTest.kt`:
   a. Probe: `Complete` / `Partial(4, downloaded=2)` / `UnknownCount` matrix (pure).
   b. Subset run: dir with 2 pages, `Download.pages` of 4 → batch completes; manifest carries
      `PartialBatchInfo(expected=4, missing=2, DOWNLOAD_CROSSCHECK)`; `expectedPageCount == 4`
      trusted; tracker/reconciliation totals 2 terminal, zero stranded/failed. RED today:
      expectedPageCount == 2, no partial info (the lie, pinned).
   c. UnknownCount run: proceeds over 2 pages, `determinedFrom = UNKNOWN`, no crash, no fake
      expected total.
   d. Finish-first routing: probe Partial → WAITING_FOR_DOWNLOAD request attached (existing
      fenced path), zero batch work started for that chapter.
   e. Post-completion re-run: after the "download" fills (fixture adds pages + rekey), next batch
      translates the previously-missing pages and clears the partial info.
   f. D9 guard: zero ledger entries for missing pages across all runs.

### 3.5 Risks

| Risk | Sev | Mitigation |
|---|---|---|
| Download object exists but `pages == null` (page list not yet fetched) | MED | Treat as `UnknownCount` (honest degradation, test 1c). |
| Manifest schema addition breaks older readers | LOW | Additive nullable + `ignoreUnknownKeys` (D5 precedent, exercised both directions in Phase 3). |
| Dialog blocks multi-chapter throughput | LOW | Per-chapter decision; complete chapters of the group admit immediately (no group-wide gate). |
| `expectedPageCount` semantics change disturbs existing progress tests | MED | The trusted-total override applies ONLY when a `Download` cross-check exists; complete-chapter behavior byte-identical (test 1b asserts the new shape, neighbors assert the old one). |

## 4. D11 — persist durable state outside the store mutex

### 4.1 Today (VERIFIED)

- Every ARTIFACTS commit holds the per-chapter store mutex across synchronous document I/O:
  `patchPage` / `updatePageGuarded` / `persistDurableStageFailure` → `publishLocked` →
  `persistArtifactMutationLocked` (ChapterTranslationStore.kt:551/:616/:639-662/:678ff →
  :1500-1719), which performs up to FOUR `AtomicChapterDocuments.publish` chains per commit
  (registration manifest :1578, candidate open :1642, candidate persist :1675, promotion :1697) —
  each chain = temp write + read-back validate + `.bak` rotation + rename
  (ChapterDocumentIo.kt:189-208), over SAF/UniFile with **no fsync** (write+flush only,
  :106-113). Also under the mutex: `updateGlossary` (glossary sidecar + manifest publish,
  ChapterGlossaryStore.kt:62-98) and the D9 ledger (one publish per paid call,
  ChapterTranslationStore.kt:364-390).
- The permit coupling (the cross-chapter, cross-mode half): the ONNX phase commits under the
  native permit (§0.6a). The audit's lock-order survey found no opposite-order acquisition
  (STRICT_AUDIT M-11) — no deadlock risk in reordering, only latency.
- Failure contract is synchronous: a publish failure rejects the commit and restores the in-memory
  page (`ARTIFACT_PUBLICATION_FAILED`, ChapterTranslationStore.kt:616-619, :655-658).
- Existing write-behind precedent: the LEGACY authority path already persists via a debounced
  scheduler (250 ms debounce, 2 s bounded join on `markDefunct`; StorePersistenceScheduler
  companion) — the codebase already accepts "disk behind memory" for legacy stores.
- Drain/durability window today: crash AFTER `Accepted` returns keeps the work (publish
  completed); crash before it loses only the attempt (D9 ledger records it, phase-3 semantics).

### 4.2 The full Recommendation shape (specified for the record — follow-up task)

For completeness (this is what "D11 fully adopted" would build):
- In-memory commit under the mutex (validation + manifest/page mutation + display promotion —
  unchanged); file publication via a single per-store ordered writer coroutine consuming a
  latest-wins per-file map (targets are O(1): `X.manifest.json`, glossary sidecar) with a
  sequence/epoch stamp per publication so a stale write can never land after a newer one;
  crash-consistency unchanged (each publication keeps the temp/validate/rename chain); the store's
  eviction (`markDefunct`) joins the writer with the existing 2 s bound as the durability point;
  `resolveAttempt` moves to AFTER the page's publication watermark advances (bounded await; on
  expiry the entry stays unresolved = counted, fail toward accounting); a new
  degraded-publication contract replaces the synchronous `ARTIFACT_PUBLICATION_FAILED` rollback
  (commit stands, store flagged, next mutation re-attempts, eviction flush failure logged as
  durable-loss warning).

### 4.3 What blocks the full shape in THIS phase

1. It changes the store's commit-failure contract (synchronous rollback → async degradation) —
   the fence Phase-2/3 verification repeatedly relied on (fail-closed commit paths,
   phase2-verification §1.2-1.4; phase3 grace work §4).
2. It re-couples D9 accounting to a new watermark invariant — a silent regression there is a
   paid-cost defect (H-10 class), exactly what the phase-3 gate just closed.
3. The harm it removes is [TARGET]-unmeasured: the same-chapter mutex coupling is real but
   serializes work that is already sequential (batch) or one manual commit; the cross-chapter
   coupling flows through the permit (§4.4 removes it directly). D13's discipline applies to
   risk as well as claims: no measurement says the write-behind machinery is warranted yet.

### 4.4 What lands in Phase 4 instead (Alternative-A slice — the smallest safe version)

1. **Remove the permit-held publication window** (draft D11 Alternative A, second clause; PLAN
   Ph4 "remove native-permit-held flush where a low-risk window exists"): in
   `translateSinglePageOnnx`, move the cleaned-image persistence + page commit
   (SinglePageOnnxPhase.kt:619-637 via CleanedPublication.persistCleanedBitmap →
   `store.patchPage`) and the `store.flush()` (:505) OUT of the `withNativeLane` block — the
   cleaned bitmap already crosses the permit boundary by design (OnnxPhaseResult carries it;
   the TranslationPipeline :471/:728 `persistOnnxCleanedImage` sites are already outside the
   permit). The permit then covers ONLY native compute (decode/OCR/inpaint), which is the
   documented asymmetry (TranslationPipeline.kt:114-121). No store-semantics change: same
   synchronous publish contract, same fences — just not while holding the stove. The one
   subtlety: the resume paths' persist-under-permit (renderResumedPage) move out with the same
   rule; the boundary's commit precondition is captured before the persist regardless.
2. **Document the remaining coupling as accepted**: same-chapter commits serialize behind one
   SAF flush; no fsync; power-loss window per ChapterDocumentIo's contract (§4.3) — a draft-D12
   style truth note recorded in the implementation log for Phase-5/6 copy and measurement.
3. **Fences that stay under the mutex** (explicit, for the Reviewer): precondition validation
   (generation/pageVersion/lease/candidate/dependency/block fingerprints), in-memory
   pages/manifest mutation + pageVersion bumps, display promotion, D9 ledger writes and the
   cap-pause, glossary version bump — ALL unchanged and ALL still under the store mutex. Nothing
   about the commit protocol changes in Phase 4; only WHO holds the native permit while it runs.

### 4.5 Recommendation: defer the write-behind core of D11 to a follow-up task

Recommendation: **land §4.4 now; defer §4.2 behind a Phase-6 measurement trigger.** This is a
Director-visible deviation from the adopted D11 Recommendation (staged adoption, deviating from
PLAN §1's blanket adoption) and must be surfaced with the phase report — the draft's own Default
("Alternative A: document, plus removing the permit-holding flush if a low-risk window exists")
is what §4.4 implements, and the draft's Alternative A was the option built for exactly the risk
profile §4.3 names. If the Director reaffirms the full Recommendation instead, §4.2 is the spec
and §4.6's steps (a)–(c) are its commit plan (behind the Reviewer sign-off PLAN already requires);
do NOT attempt it silently.

### 4.6 Test plan + commit plan (both variants)

If deferred (recommended) — write FIRST, failing:
1. `coexistence/D11PermitFreeCommitTest.kt`: manual boundary parked at a COMMIT barrier (store
   patch in flight) while a SECOND page awaits native admission → the second admission must
   succeed while the first commit is still parked. RED today (the permit spans the commit,
   TranslationPipeline.kt:440-459 → SinglePageOnnxPhase.kt:619). GREEN = §4.4(1).
2. Neighbor guards: existing store suites (publish/rejection fences) green unchanged — proof the
   commit protocol did not move.

If the Director reaffirms full D11 — in order, each compiling (steps a–c slot in after the §6
commit list's item 7, before the phase-log commit):
a. `t917(p4): d11 tests` (red: watermark, degraded-publication, resolve-after-publish,
   eviction-flush join), b. `t917(p4): d11 ordered writer behind a store flag (default OFF —
   zero behavior change)`, c. `t917(p4): d11 enable write-behind + ledger watermark ordering`,
   each gated by the full translation sweep and the Reviewer acceptance PLAN §5 requires.

### 4.7 Risks

| Risk | Sev | Mitigation |
|---|---|---|
| Permit-scope move changes resume/error paths' ordering | MED | The bitmap already crosses the boundary for the fresh path; resume paths get the identical rule + their existing suites; D11PermitFreeCommitTest + ChapterTranslatorTerminalExitsTest as fences. |
| Deferral leaves the audit finding open | LOW | M-11's user-facing harm is reduced (permit decoupled) and the remainder is documented (§4.4(2)); Phase 6 measures before machinery. Director owns the staged-adoption call. |
| (Full-D11 branch) watermark bug silently unresolves/resolves D9 entries | HIGH (if built) | Ledger ordering is its own commit behind a default-OFF flag; D9AttemptLedgerTest extended with publication-watermark cases before enabling. |

## 5. Harness capability summary (Phase 4 additions)

Existing (phase1-harness-notes + Phase 3): PROVIDER/NATIVE/RENDER/ENGINE_CLOSE barriers; fake
`TextTranslator` with per-page counters and instance identity (add an id field for D7's
second-instance assertion — test-infra only); `storeOverride` seam; `drainGraceMs` ctor seam
(RollingAutoCoordinator); governor injection; scope-kill process-death; `FakeChapterDocumentIo`.
Phase 4 needs exactly: `EngineLane.drainGraceMs` + `drainScope` ctor seams (D7), injectable
`stallThresholdMs` + watchdog scope (D8), `Download.pages` fixture (D10 — pure data), and the
injected `timeoutMs` the lane already takes (D8b). No Robolectric, no new dependencies; RED fails
by named assertion (phase-3 bridge pattern) never by timeout.

## 6. Sequencing, gate, constraints

**Commit order (branch `t917/coexistence-v3`, every commit compiling, tags per PLAN §2):**
1. `t917(p4): d7 tests` (red — includes the §1.4.1e budget-bound test)
2. `t917(p4): d7 engine epoch + borrow drain + boundary retry` (retires the :143-147 comment)
3. `t917(p4): d7 provider drain-grace budget alignment` (P3 finding 4: `PROVIDER_DRAIN_GRACE_MS`
   → `ATTACH_TIMEOUT_MS`; flips test 1e green; updates the D6DrainNotCancelTest bound assertion —
   contract-change test edit, recorded in PHASE-LOG.md)
4. `t917(p4): d8 tests` (red)
5. `t917(p4): d8 stall watchdog + honest timeout/stalled outcomes + timer message fix`
6. `t917(p4): d10 tests` (red)
7. `t917(p4): d10 admission probe + partial-batch truth + trigger routing`
8. `t917(p4): d11 permit-free commit + coupling documentation` (deferred variant — recommended;
   full-D11 steps (a)–(c) per §4.6 only on Director reaffirmation)
9. `t917(p4): phase log + design-note deviation record` (D7 "complete" scope, D8 Completed-on-
   timeout fold-in, D10 offline-count refinement, D11 staged adoption, P3-finding-4 grace
   alignment — Director-visible)

Rationale: D7 first (isolated to engine lifecycle + one phase class; D8's "already in flight"
fix touches the same boundary and benefits from the epoch existing); the finding-4 alignment
rides immediately after D7 (it touches the constant D7's tests reference); D8 second (watchdog is
additive; its boundary fix rides D7's touched lines); D10 third (independent surface); D11 last
and smallest (riskiest per PLAN §5). Gate: full `eu.kanade.translation.*` sweep + 100-run
determinism soak on `coexistence.*` + touched suites (now including D6DrainNotCancelTest and
D9AttemptLedgerTest, whose grace-expiry semantics §1.6 touches); `NormalMangaIsolationTest`
green and UNTOUCHED at every step; Reviewer acceptance before `checkpoint/t917-p4-done` (PLAN
requires Reviewer support for Phase 4 and explicitly for D11).

**Binding constraints check (all mechanisms above):** bounded memory — one AtomicLong, one
AtomicInteger, one bounded StateFlow, one manifest field, O(1) writer targets (full-D11 variant);
no polling loops (deferred/event-driven waits only); Android 8.0+ — no new APIs beyond
coroutines/Atomic classes; reader stability — the stove is never killed, stop paths stay
non-blocking, and normal-manga behavior is untouched by construction (D7/D8 act only inside
active translation work; D10 only at explicit batch triggers; D11 only reorders permit scope).
