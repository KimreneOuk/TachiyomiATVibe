# T917 Phase 3 — Reviewer Acceptance Verification (parts A + B: D5, grace, D9, D6)

**VERDICT: ACCEPT-WITH-NOTES** — D5, the §4 grace fold-in, D9 and D6 are implemented to
`engineering/phase3-design.md` with fences preserved and end-state oracles; no blocking defect
found. The D9 resolve-on-outcome semantics deviates from design §3.2's own sentence for
in-app cancellations (finding 1, judged defensible — keep, but record the deviation), and D5's
one-pass convergence claim is optimistic when a repair itself matures the glossary map
(finding 2, bounded and converging). Conditions for the checkpoint are in §6.

Scope reviewed: `66494b0..a07d68d` (9 commits; Part A = e9cee3d/241d9c7/58360c8/7e9d080,
Part B = be3c4d5/0eaf7e1/8dd55e4/dd3364e/a07d68d). Code-level adversarial pass; the full
sweep/soak re-run is the Main Leader's gate job (per assignment). All line refs are HEAD of
`a07d68d`.

---

## 1. Deep dive — A: D5 glossary-aware reuse gate

### 1.1 Absence-of-record = version 0 (attack: spurious oscillation / re-billing of pre-D5 pages)

VERIFIED safe, with one bounded cascade (see §1.5). Gate input chain:
`BatchResumePlanner.buildBatchPagePlans` passes `currentGlossaryVersion = if (isAi)
store.currentGlossaryVersion() else null` (BatchResumePlanner.kt:103), where the accessor
returns the manifest glossary version only for ARTIFACTS authority and a published pointer
(ChapterGlossaryStore.kt:37-40), else null = gate off. The planner downgrade fires only on
`stage == TRANSLATION && decision == REUSE && current != null && current > (recorded ?: 0)`
(PageWorkPlanner.kt:299-309). A pre-D5 page (null stamp) in a glossary chapter is repaired
exactly once: repair commit stamps the live version (BatchResumePlanner.kt:77-80), after
which `current > recorded` is false. In a glossary-less or legacy-authority chapter the gate
cannot arm at all, so a null stamp never re-bills anything. The `?: 0` fallback at the stamp
points can only lower the recorded value (most repairable direction); it cannot freeze a page
(freeze would require stamping a value HIGHER than the page actually saw — see the
claimed-but-unseen case in §1.4) and cannot permanently re-bill (repair stamps the real
version; version is monotonic and never decreases).

### 1.2 Gate-OFF paths byte-identical (standard lane, glossary-less, legacy authority)

VERIFIED. The planner's only behavioral delta is the gated `if` block (PageWorkPlanner.kt:290-309);
`val raw` → `var raw` is otherwise unused. With `currentGlossaryVersion == null` (standard lane
by the `if (isAi)` at BatchResumePlanner.kt:103; legacy authority / no pointer by the accessor)
the block is dead and every decision, including reasons and retry metadata, is the pre-D5
value. The legacy compat entry `PageWorkPlanner.plan` (:43-48) does not pass the new input and
defaults to null — unchanged. Test (d) pins this with a matured glossary present and a
standard lane (D5GlossaryAwareReuseTest.kt:343-367: REUSE/VALID_ARTIFACT preserved, zero
TRANSLATION RUNs). TERMINAL_COMPLETE exemption: textless pages return before `raw`
(PageWorkPlanner.kt:232-242) and SKIPPED maps to TERMINAL_COMPLETE (:284-285), both before the
gate — pinned by test (a)'s p2 oracle (:268-270). Durable-failure pages return before the gate
(:216-231), so the gate never overrides FAILED_RETRYABLE/FAILED_TERMINAL.

### 1.3 Stamp points `?: 0` (attack: permanent freeze / permanent re-bill)

VERIFIED with accepted-drift caveat. Batch stamps at commit-provenance time inside the
guarded write (`BatchWriteGate.update → stampBatchProvenance`, BatchWriteGate.kt:104), i.e.
under the store mutex, before the chunk-level fold (BatchLaneWorkers.kt:555). Manual/auto
stamps post-fold, immediately after the contextual `updateGlossary` block
(SinglePageHttpRenderPhase.kt:364-371). The accessor is a pure in-memory read — it has no
failure mode; `?: 0` fires only for null (legacy authority / no pointer), and stamp 0 is
harmless in both directions (gate off while null persists; one designed repair if a pointer
appears later — that is precisely a page that never saw any glossary). The PARTIAL/paused
manual path also stamps (:341-357 reaches :368), but a PARTIAL page never plans REUSE
(PageWorkPlanner.kt:266-267 → RUN/PARTIAL_ARTIFACT), so the stamp is inert until a later
successful pass re-stamps. Caveat (documented in-code, accepted by design §1.3): a
concurrent mode's fold between request build and commit is claimed by the stamp but unseen by
the page — that page skips repair for those versions until the next maturation. Rare,
self-limiting, direction-safe (never re-bills, only can under-repair).

### 1.4 D1 lease-over-plan corollary interaction

VERIFIED clean. The glossary stamp is payload on the committed `PageTranslation`
(SinglePageHttpRenderPhase.kt:368 → patch lambda at :580 returns `pageTranslation`); no
expected-precondition field was added, so `patchPage`'s fence chain
(ChapterTranslationStore.kt:557-585: generation, pageVersion, artifactPageVersion,
candidateGeneration, dependencyFingerprint, blockFingerprints, lease token — including the
inverse "lease required" arm at :583-584) is untouched by D5. The Phase-2 boundary refresh
(SinglePageHttpRenderPhase.kt:566-575) still waives exactly the three plan-identity fields;
the stamp rides the same patch payload as `translationFingerprint`. A drained/late commit
carrying a stamp is still rejected on a lost lease — no new bypass.

### 1.5 Convergence (attack: REUSE→RUN→REUSE oscillation)

VERIFIED convergent; the design's "worst case = one extra full-chapter pass"
(phase3-design §1.2) is optimistic in one narrow case. Oscillation is impossible: the version
is a content-change counter (`updateGlossary` publishes only `if (glossary != updated)`,
ChapterGlossaryStore.kt:63) and monotonic, so `RUN` re-fires only when the glossary map
actually changed. Two bounded effects the design text under-weights:

- **Repair self-maturation (second pass):** the batch folds each chunk's pairs AFTER its page
  commits (BatchLaneWorkers.kt:545-555). If a repair changes a repaired rendering's majority
  enough to add/flip a glossary entry (the builder qualifies an entry at ≥3 recurrences and
  ≥80% recall, ChapterGlossaryBuilder.kt:14-17, 97-105), the version bumps mid/after the
  pass, and pages stamped before that bump plan RUN once more; that pass re-folds stable
  content, the equality gate holds the version, and the following run REUSEs. Worst realistic
  cost: two paid passes per maturation event, strictly converging.
- **Chunk-suffix partial cascade:** later chunks stamp the version already bumped by earlier
  chunks' folds, so a following run re-repairs only the earlier-stamped prefix. Bounded by
  chunk count, converges with the map.

Because the map tracks only high-consistency terms (and a repair enforces consistency), the
map is far more stable than raw text; perpetual re-billing would require persistent
majority-rendering flip-flops, which the 80% recall threshold makes implausible. Test (b)
pins the no-oscillation property for the identical-refold case (D5GlossaryAwareReuseTest.kt:278-307);
it does not pin the pair-changing repair case — see finding 2 and condition 2.

---

## 2. Deep dive — B: patchPage candidate-grace (§4 fold-in)

VERIFIED fail-direction-preserving. The dependency clause now requires a live candidate
(ChapterTranslationStore.kt:584: `expected.dependencyFingerprint != null &&
artifactManifest?.pages?.get(pageKey)?.candidate != null && ...`), matching `publishLocked`'s
grace (:1608). A candidate-LESS record has nothing real to compare against — the expected
value there is the `StageFingerprints.pageSnapshot` fallback armed by snapshots — so the
clause previously produced only false rejects. Every other fence stays fully armed and
ordered before the patch: generation :557, pageVersion :558-559, artifactPageVersion :560-561,
candidateGenerationId :562-563, blockFingerprints :578-580, lease token :581-584 (including
"lease token required" when expected is null and a lease exists). A REAL dependency change
with a live candidate still rejects.

Caller regression check (relaxation can only reduce rejections; each caller re-audited):
`CleanedPublication.kt:144` (full precondition; its dependency expected is the same snapshot
fallback family — previously false-rejectable, now correctly waived), `PageStoreWriter.kt:119`
(timeout marker; fences intact), `patchBlock` wrapper :734-735 (block fingerprint still
enforced in-lambda), and the boundary commit SinglePageHttpRenderPhase.kt:577. Guarded stage
patches go through `updatePageGuarded`/`pageWriteRejection`, which always had the grace.
Regression test is end-state on a real ARTIFACTS store: candidate-less registration between
capture and commit → Accepted while the MANUAL lease is held; the identical patch after
lease release → Rejected via "page lease token" (ChapterTranslationStorePatchPageGraceTest.kt:141-190).
The pre-fix RED failing through the dependency clause before the lease clause (log §3 step 3)
is itself consistent with the defect shape. No weakening found.

---

## 3. Deep dive — C: D9 attempt ledger

### 3.1 Fail-open writes, fail-closed cap, zero billing on AUTO refusal

VERIFIED. `recordStartLocked` never blocks MANUAL/BATCH; only AUTO with
`consecutive >= MAX_CONSECUTIVE_UNRESOLVED (3)` is refused (ChapterAttemptLedger.kt:74-82).
Ledger write failures are fail-open at all three call sites (SinglePageHttpRenderPhase.kt:233-240;
BatchLaneWorkers.kt:1314-1325; RollingAutoCoordinator `recordAutoAttemptStart`
:353-367 with `.getOrDefault(true)`) — the paid call proceeds. The refusal path is the
opposite direction: `recordAutoAttemptStart` returns a typed `ChunkCompletionOutcome.Paused`
BEFORE the provider call (RollingAutoCoordinator.kt:368-384), the consumer feeds it to
`deferTranslationRetry` (:494-503) — zero provider billing, and the D9 test asserts the
refusal leaves entries untouched (D9AttemptLedgerTest.kt:385-388). Note the billing guard
does not depend on the durable-failure write succeeding: even if `applyAttemptCapPause`
fails (fail-open, TranslationManager.kt:572-576), the persisted ledger counter alone refuses
AUTO at record time.

### 3.2 Cap never binds the user

VERIFIED. MANUAL entries are always admitted (ChapterAttemptLedger.kt:74 guards only
`AttemptOrigin.AUTO`); BATCH likewise. The explicit force path clears the counter AND the
INTERRUPTED durable failure (`clearAttemptCapForManualRetry` → `clearCapLocked` +
`removeInterruptedCapFailureLocked`, ChapterTranslationStore.kt:442-468), wired at every
force-resume point of SinglePageOnnxPhase (:306, :413, :433, :460) and asserted in
D9AttemptLedgerTest.kt:394-400. Capped-page durable failure is `category=INTERRUPTED`,
`nextEligibleRetryAtEpochMs=null`, page PARTIAL, queue entry QUEUE→PAUSED
(TranslationManager.kt:566-582; D9AttemptLedgerTest.kt:357-369). A user-initiated BATCH run
after a cap still bills — correct: that is the user speaking, the same channel as force.

### 3.3 Startup reconcile bounded

VERIFIED. `reconcileAttemptLedgersForStartup` (TranslationManager.kt:540-586) iterates only
the caller-supplied set — `persistedQueueChapterIds() ∪ pending request keys ∪
pendingRequestStore.load()` (:439-445) — and the default resolver returns activeStores only
(:543-545, `?: return@forEach`), so the effective set is bounded and never a library scan;
v2.1's fictional sweep remains absent. Ingestion is per-entry
(`consumeAtStartupLocked`, ChapterAttemptLedger.kt:122-133): each pending entry adds +1 to
its page's counter, entries clear, counters persist in the same file.

### 3.4 Retention/reset sweep and bound

VERIFIED. The sidecar is exempt from retention pruning (`add(layout.attemptLedgerFileName)`
in the reachable set, ArtifactRetention.kt:108-111) and swept by chapter delete: the deletion
plan removes the whole `<base>_artifacts` root (ChapterArtifactDeletion.kt:75), which contains
`attempts/`; the directory is also in `managedDirectories` (ChapterArtifactLayout.kt:108) and
OCR reset delegates to full deleteTranslation (ChapterDataResetController.kt:134-136).
Publication is crash-safe temp/validate/rename with a future-schema read-only rule
(ChapterArtifactStore.kt:197-212). Bound: `(entries + entry).takeLast(MAX_ENTRIES=64)`
(ChapterAttemptLedger.kt:90-92) — evict-oldest, O(1) append, bounded file.
Note (LOW): a translation-only chapter reset (`resetChapterTranslationData`,
ChapterDataResetController.kt:194-214) transforms pages but does not clear the ledger
counters or an INTERRUPTED durable failure; the designed escape (explicit force) remains the
recovery path.

### 3.5 Resolve-on-outcome vs CancellationException — the assignment's judgment question

FINDING 1 (MEDIUM, semantics deviation from design §3.2's letter — recommend keep + record,
with a cheap exact-semantics fix available). Facts (all VERIFIED):
- All three lanes rethrow `CancellationException` WITHOUT resolving: manual
  (SinglePageHttpRenderPhase.kt:246-248), batch (BatchLaneWorkers.kt:1330-1333), auto
  (RollingAutoCoordinator.kt:397-399).
- A true process death cannot execute a catch block; therefore every CE that reaches these
  catches is an IN-APP cancellation (user stop, reader close/chapter switch, graceful scope
  teardown, D6 drain-grace expiry).
- AUTO in-app cancels mostly RESOLVE anyway: D6's drain lets the in-flight call finish and
  commit within 90 s, and completion resolves. The exposure is therefore concentrated in
  (a) the MANUAL lane (no drain), and (b) drain-grace expiry.
- Consequence: e.g. three consecutive manual stop/cancel mid-call cycles with no completion
  in between produce three pending entries; the next startup reconcile (store active) counts
  3 → cap → chapter PAUSED with the truthful "repeatedly interrupted before completing;
  manual retry required" + AUTO refused. Recoverable via explicit force; the user is never
  blocked; no billing on refusal.

Judgment: this contradicts §3.2's sentence "Only process death mid-call leaves an entry", and
the implementation log's "process-death analogue" phrasing conflates the two. It is,
however, coherent with D9's money-guard purpose (each cycle is a possibly-billed call with no
committed result), the failure mode is soft, visible, truthful and recoverable, and the cap
requires an unusual user pattern (three consecutive mid-call cancels, no completions, app
restart in between). I recommend KEEP for the checkpoint, with two follow-ups: (1) record the
deviation in phase3-design §3.2 / the Phase-5 copy pass (the pause copy is already accurate
for user cancels); (2) if the Director wants exact §3.2 semantics, the minimal correct change
is to resolve the entry in the in-process CE handlers (a real process death by definition
cannot reach them), which restores the design sentence while still counting true deaths.
Decision belongs to the Director as a one-line contract note; not checkpoint-blocking.

---

## 4. Deep dive — D: D6 reserve + drain-not-cancel + typed pause

### 4.1 Reserve math in `evaluate`

VERIFIED against design §2.1. While `bucket.waiters` holds any INTERACTIVE waiter
(ProviderRequestGovernor.kt:452-454), a BACKGROUND waiter sees
`effectiveRequestsPerMinute = requestsPerMinute - 1` (:456-457) and
`effectiveTokensPerMinute = floor(tokens * (1 - fraction)).coerceAtLeast(0)` (:458-461);
INTERACTIVE waiters always evaluate against the full window (the reserve only applies under
`waiter.metadata.priority == BACKGROUND`, :455). `tokenLimit = maxOf(effectiveTokens, tokenCost)`
(:462) keeps a single oversized request admissible — pinned by
ProviderRequestGovernorReservationTest.kt:258-270. `nextEligibleAt` receives the same reduced
`tokenLimit` (:476, signature widened to Long :510-516), so defer estimates are honest —
pinned exactly (`nextEligibleRetryAtEpochMs == window start + windowMs`,
ReservationTest.kt:194). Already-admitted reservations are never revoked (only `prune`
removes window-expired reservations, :552-556) — pinned by ReservationTest.kt:232-249. Cold
window: no interactive waiter → `backgroundReserveApplies` false → unchanged behavior; the
fraction is validated `(0, 1]` at construction (:97-99). Pre-existing anti-starvation
(`selectWaiter` starving-background preemption) is unchanged and composes: selection order is
untouched, the reserve still shapes that admission. Liveness bound: a background waiter can
be held at the reserve only while the interactive waiter remains enqueued; `waitOrDefer`
removes deferred waiters (:525), bounded by `maxForegroundWaitMs`.

### 4.2 Drain-not-cancel

VERIFIED with one bounded trade-off. `consumeTranslations` wraps the translate+commit in
`withContext(NonCancellable) { withTimeout(drainGraceMs) { ... } }`
(RollingAutoCoordinator.kt:439-459): the timeout is INNER, so expiry throws
TimeoutCancellationException (cancellation-class) which `runAutoAttempt` rethrows without
resolving (entry stays unresolved — the design's "intentionally counted as consumed"); the
NOT-yet-started work still cancels — after the drained item, the loop's next
`ensureActive()` (:424) throws on the cancelled window and the consumer exits; stale items
are dropped by `isWorkCurrent` before any ledger write. Completion path: the PAGE commit
happens inside `translatePreparedPage` under the store's full fence chain (generation /
pageVersion / lease / write-gate — fails closed on a lost lease), while the coordinator's
slot bookkeeping after the block stays `isWorkCurrent`-guarded (:461) — a drained result
commits, a lost page cannot be corrupted. Exactly one call drains per consumer; the global
cancel/stop path joins (`jobsToJoin.joinAll`, `awaitTermination`, TranslationScheduler.kt:948-949),
so joins now mean drain. `PROVIDER_DRAIN_GRACE_MS = 90_000L` is public for the test's bound
assertion (:1073-1077); `drainGraceMs` is the last, defaulted ctor param and the only
production construction site uses named args (TranslationScheduler.kt:195-200) — no caller
churn. Trade-off (LOW-MEDIUM, finding 4): 90 s aligns `ONNX_PHASE_TIMEOUT_MS`
(TranslationPipeline.kt:121) but the auto chain can legitimately run ONNX (90 s) plus the
HTTP+render phase (up to 120 s, :479) sequentially; a cancel arriving early in a long call
can hit the grace bound, leaving the entry unresolved (counted) and the page to re-run —
cost-honest but could contribute to finding 1's cap for slow providers under frequent
reader-close patterns. Accept; note for D8's stall measurement.

### 4.3 §2.2a swallow fix

VERIFIED narrow and correct. `translateSinglePage` now captures the phase result
(TranslationPipeline.kt:476-492) and maps `ChunkCompletionOutcome.Paused` 1:1 to the new
`SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs)` (TranslationExecutor.kt:138-145;
pipeline :497-505). No other variant changed behavior: the timeout path still marks the page
timed-out (pre-existing store-level truth), Failed paths throw into the existing catch,
`return SinglePageOutcome.Completed` remains the tail exactly as before — so no NEW
misreport was introduced; the fix strictly narrows the previous Completed-swallow to the
non-Paused variants it already covered. `PersistenceRejected`-as-value still reports
Completed (pre-existing, unchanged — see finding 5, LOW). The scheduler writes the outcome to
`manualOutcomes` (:633) whose only consumer-facing `when`s are else-guarded
(TranslationScheduler.kt:640-658), and `manualOutcomes` currently has no other production
consumer (Phase-5 hook) — the new variant cannot misroute anything today. Pinned end-to-end
by D6ForegroundFairnessTest.kt:189-232 (INTERACTIVE/BACKGROUND priorities observed at the
transport, typed Paused in `manualOutcomes`, governor retry epoch bounded, no paid-call
retry). §2.2b was verification-only as logged: the Paused branch already feeds
`deferTranslationRetry`/paused bookkeeping (RollingAutoCoordinator.kt:494-503).

---

## 5. Deviations (Part A 1-5, Part B 1-5) — plausibility and oracle strength

VERIFIED plausible; none weaken an oracle below its contract:

- **A1 (planner-seam tests, no fake ContextualTextTranslator full-graph):** accepted. The
  harness's memory-only store has no manifest, so the gate would be dead code there; the
  RUN↔paid-call mapping is independently pinned by the D2/D3 transport oracles. Cost: D5
  asserts decisions, not billing counters — the log says so plainly. Plausible and adequate.
- **A2/A3 (updatePageGuarded fixture, PENDING render fixture):** keep D5 isolated from the §4
  fix (which has its own suite) — correct test hygiene; the candidate stays live without
  coupling to display promotion.
- **A4 (store-level `currentGlossaryVersion()`):** necessary wiring, `internal`, delegating —
  matches the collaborator pattern.
- **A5 (test (d) counts TRANSLATION-stage decisions only):** correct — p0's LAYOUT legitimately
  plans RUN (free native work) under the PENDING-render fixture; the isolation claim is about
  paid TRANSLATION work.
- **B2 (drain commit shape = translation-terminal, not render READY):** accepted. Render
  promotion requires `BitmapFactoryCleanedImageProbe.displayBaseIsValid`, undecodable on the
  JVM. The §2.3 contract — drained call finishes (cancelledCalls==0), result durably
  committed (ocr/translation/inpaint READY + cleaned metadata), D9 entry consumed, exactly
  one paid call, grace expiry leaves PENDING + entry unresolved (D6DrainNotCancelTest.kt:219-324)
  — is fully proven. The untested sliver (render promotion of a drained result) uses the same
  fenced commit path D2 already proves, so the oracle set still proves §2.3.
- **B3 (public companion + last defaulted param):** verified compile-clean (named-arg call
  site); the two pre-existing constants made `private const` — no leakage.
- **B4 (fixture-only edits to committed RED files):** RED was captured against the committed
  RED versions; the corrections align status shapes and exception surfacing with what the
  production seam guarantees — assertion semantics unchanged. Acceptable.
- **B5 (§2.2b verification-only):** confirmed by code — no behavioral diff in that branch.

Standard checklist follows.

---

## 6. Standard acceptance checklist

| Item | Status | Evidence |
|---|---|---|
| A Scope | VERIFIED | `git diff 66494b0..a07d68d --stat`: 29 files, all translation pipeline/store/planner/scheduler/governor/artifact + tests + Plan logs; `-- "*.gradle*" "*.toml" gradle/` diff EMPTY; no dependency changes |
| B Two-vocabulary stamps | VERIFIED | Only origin-enum-valued stamps added: `AttemptOrigin.valueOf(origin.name)` (SinglePageHttpRenderPhase.kt:238), `AttemptOrigin.BATCH/AUTO` constants (BatchLaneWorkers.kt:1320, RollingAutoCoordinator.kt:365); no raw "MANUAL"/"AUTO" string stamps introduced; D5 stamp is an int, vocabulary-free |
| C Tests assert end-state oracles | VERIFIED | 0 log/stdout assertions across all six new test files (grep); oracles are durable ledger contents, counters, decisions, patch results, typed outcomes, queue state, paid-call counts (file:line in §1-§5) |
| D NormalMangaIsolationTest untouched | VERIFIED | `git log --oneline -3 -- <file>`: last touch 45879f9 (Phase 1); green (1/0/0) in current XML results |
| E Sweep integrity spot check | VERIFIED (spot) | `app/build/test-results/testStandardDebugUnitTest/` XMLs present for all new suites with counts exactly as claimed: D5=4, D9=3, D6Drain=3, D6Fairness=1, GovernorReservation=5, Grace=2, isolation=1 — 0 failures, 0 errors, 0 skipped (matches log's 1238 = 1232+6 part A; 1250 = 1238+12 part B arithmetic). Full-sweep re-run and 100-run soak remain the Main Leader's gate job (not re-run here per assignment) |
| F No @Disabled/@Ignore added | VERIFIED | `git diff 66494b0..a07d68d -- app/src/test | grep -c "@Disabled\|@Ignore"` → 0 |
| G RED-before-GREEN credibility | STRONG INFERENCE | Log excerpts name exact defect-shaped messages (e.g. "expected:<RUN> but was:<REUSE>", "expected:<Paused> but was:<Completed>", "expected:<READY> but was:<PENDING>") matching the seams the GREEN commits add; guards that were green-at-RED are identified as such. I did not re-execute the RED states (would require checkout of intermediate commits; not required — the RED claims are internally consistent with the final code) |
| H Memory/boundedness | VERIFIED | 64-entry evict-oldest ledger (ChapterAttemptLedger.kt:90-92); capped `consecutiveUnresolved` map (per-page Ints); no per-page maps beyond store state; drain holds at most the existing semaphore permits for ≤90 s |

---

## 7. Findings

1. **MEDIUM / design-semantics deviation (keep, record; Director-visible one-liner)** — D9
   resolve-on-outcome leaves the entry on ANY in-app CancellationException (manual
   SinglePageHttpRenderPhase.kt:246-248, batch BatchLaneWorkers.kt:1330-1333, auto
   RollingAutoCoordinator.kt:397-399), so user cancels count as consumed attempts at startup;
   three consecutive mid-call cancels without a completion can trip the PAUSED cap. Soft,
   visible, truthful, force-recoverable; AUTO is largely protected by the D6 drain. Analysis
   and both remediation options in §3.5. Not checkpoint-blocking.
2. **MEDIUM / design-limitation (bounded, converging)** — D5 convergence "worst case = one
   extra full-chapter pass" (phase3-design §1.2) under-counts: a repair that itself
   adds/flips a glossary entry bumps the version (chunk folds at BatchLaneWorkers.kt:545-555
   run after page stamps), producing one bounded second pass (plus a chunk-prefix partial
   cascade). Strict convergence still holds via the equality gate (ChapterGlossaryStore.kt:63).
   Cost wording should be corrected in the design note; no code change required. Test (b)
   does not pin the pair-changing variant — condition 2.
3. **LOW / expected** — stamp claims concurrently folded pairs it did not see (design §1.3
   accepted drift, implemented as coded at SinglePageHttpRenderPhase.kt:368-371): that page
   under-repairs until the next maturation. Direction-safe.
4. **LOW-MEDIUM / design-limitation (accept; informs D8)** — drain grace 90 s can expire
   while the auto chain is still within its own legitimate phase budgets (ONNX 90 s + HTTP
   120 s, TranslationPipeline.kt:121/:479); expiry leaves the D9 entry unresolved (counted)
   and cancels the call. Slow provider + frequent reader-close patterns could contribute to
   finding 1's cap. Measured stall data (Phase 6) should re-validate the bound.
5. **LOW / pre-existing, unchanged** — `ChunkCompletionOutcome.PersistenceRejected` /
   Failed-as-value from the HTTP phase still surface as `SinglePageOutcome.Completed` in
   `manualOutcomes` (TranslationPipeline.kt:505-509 tail). The §2.2a fix deliberately maps
   only Paused; the residual swallow predates this range and is store-visible (page remains
   non-terminal). Candidate for the Phase-5 UI-truth pass.
6. **NOTE / expected** — translation-only chapter reset does not clear ledger counters or an
   INTERRUPTED cap failure (ChapterDataResetController.kt:194-214); explicit force remains
   the recovery path, consistent with the design's single escape hatch.

No CRITICAL or HIGH findings. No defect invalidates a §5 oracle or a §7 outcome contract.

---

## 8. Conditions for `checkpoint/t917-p3-done`

1. Gate sweep (Main Leader): repeat the 1250/0 full `eu.kanade.translation.*` run from a
   clean build, plus the 100-run determinism soak covering `coexistence.*`,
   `ProviderRequestGovernorReservationTest`, and `NormalMangaIsolationTest` (Phase-2 gate
   precedent; condition H of phase2-verification).
2. Record finding 2's corrected cost wording ("up to two paid passes per glossary maturation,
   strictly converging") in phase3-design §1.2 (or the PHASE-LOG entry), and record finding 1
   as a Director-visible one-line contract note: "D9 counts in-app cancellations as consumed
   attempts (not only process death); exact §3.2 semantics available as a follow-up if the
   Director prefers." No code change required for the checkpoint.
3. Carry findings 3-6 into the Phase 4/5 backlog (5 explicitly belongs to the Phase-5 UI-truth
   pass; 4 to Phase-6 measurement).

Accept with the conditions above.
