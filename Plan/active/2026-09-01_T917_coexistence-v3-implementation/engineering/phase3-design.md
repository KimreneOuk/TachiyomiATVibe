# T917 Phase 3 — Design Note (D5, D6, D9 + backlog fold-in)

Audience: Implementer. Scope: design only, no code. Line citations are HEAD of `t917/coexistence-v3`
post-Phase-2 (D1–D4 landed). Evidence labels per `docs/roles/technical-lead.md`.

## 0. Contradictions / refinements vs prior docs (flagged first)

1. **PLAN §3 Ph3 (D6): "wiring `translateSinglePageFromStream` to interactive priority (currently
   BACKGROUND)" — REFUSED as written.** VERIFIED: `translateSinglePageFromStream`'s only caller is
   the **auto-prefetch** loop (`scheduling/TranslationScheduler.kt:414` — auto reservations,
   `logAutoDecision`); it is not a manual path. The manual path already runs INTERACTIVE post-D2
   (`TranslationPipeline.kt:341` lease admission, `:347` boundary — both
   `withProviderRequestPriority(INTERACTIVE)`). Wiring the stream path INTERACTIVE would give AUTO
   wallet precedence against BATCH and against the D1 origin priority (manual > auto > batch) —
   opposite of M-10's intent. Decision: stream path stays BACKGROUND; the audit's "reader-stream
   manual path" label (STRICT_AUDIT M-10) was a mislabel; PLAN wording corrected by this note.
   The audit's `TranslationPipeline.kt:323` line ref is pre-Phase-2 (now :341/:347) — refresh, not
   contradiction.
2. **PLAN §3 Ph3 (D5): "fingerprint input adds glossaryVersion (+ chunk-context identity) in
   `PageDecode.batchExpectedFingerprints`" — REFINED.** Hash-embedding glossaryVersion into
   `StageFingerprints.configuration(...)` (PageDecode.kt:164-169) makes the recorded value opaque:
   absence can only mismatch (stale for every existing AI-lane page, including glossary-less
   chapters), and the standard-engine lane shares the same builder. Decision: comparable field, §1.
   Chunk-context identity is **declined** (§1.5). This satisfies draft §6 D5's adopted
   Recommendation (repairability) at strictly lower cost; deviation recorded here per Director
   visibility.
3. Draft D6 Recommendation's "batch throttles while a foreground waiter is present" is adopted
   literally; draft D9's N example (3) adopted. Phase-2 corollary (lease-over-plan precedence,
   PHASE-LOG) stands as D5's commit-semantics base — see §1.4.

## 1. D5 — glossary-aware translation reuse gate

### 1.1 Today (VERIFIED)
Translation reuse fingerprint = `configuration(TRANSLATION, translatorSignature, fromLang, toLang)`
(`pipeline/PageDecode.kt:164-169`; `EngineSignature` at `pipeline/EngineLane.kt:121-133` — engine
config only, no glossary). REUSE compares fingerprint equality + source hash
(`model/PageWorkPlanner.kt:341-347`, source at :345; evidence assembly :356-425). `glossaryVersion`
is persisted (`artifact/ChapterArtifactStore.kt:172-189`, monotonic: `max(existing, sidecar)+1`)
but read by no stage decision. Both AI paths feed the glossary: batch seeds
`ChapterGlossaryBuilder.Stats` from `store.translatedPairs()` (`pipeline/batch/BatchChapterTranslator.kt:274-280`)
and passes it into lane workers (:489); the single-page path formats `store.glossarySnapshot()`
into the request (`pipeline/SinglePageHttpRenderPhase.kt:229`) and folds its own pairs back via
`store.updateGlossary(...)` (:324-336). `updateGlossary` publishes only when content actually
changed (`store/ChapterGlossaryStore.kt:49` + :60-83) — the version is a content-change counter,
not a per-write counter. Standard (non-AI) translators never receive the glossary.

### 1.2 Rollout semantics — DECISION: absence-of-record = version 0 (targeted repair, not blanket stale-once)
Neither pure option (a)/(b) as posed:
- Gate input is a new additive nullable field `PageTranslation.translationGlossaryVersion: Int?`
  (`model/PageTranslation.kt` is `@Serializable` with nullable fields, precedent `translationFingerprint`
  :58; flows through the legacy JSON and the committed page-snapshot bridge unchanged — additive
  null tolerated both directions [STRONG INFERENCE from ChapterGlossaryStore loadGlossary fallback
  and LegacyArtifactRescue patterns]).
- Planner rule (AI lane only, artifact authority ARTIFACTS, chapter has a glossary pointer):
  translation-stage REUSE downgrades to RUN iff `currentVersion > (recorded ?: 0)`. Where
  `currentVersion = manifest.glossary?.version ?: 0` read once at plan time; `null` currentVersion
  (legacy authority, no glossary ever published) = **gate off** → REUSE unchanged.
- Consequences vs the question's two options:
  - Chapter with **no glossary** (version 0): recorded 0 == current 0 → REUSE, **zero extra paid
    calls** — the cost-flat property of option (b).
  - Chapter **with a glossary**, pages translated before D5 (all of them, incl. the H-07
    "translated while glossary was empty" pages): `0 < current` → repaired on the next batch run.
    This is option (a)'s repair behavior, applied exactly where the adopted D5 Recommendation
    demands it ("a page translated while the glossary was empty is REUSEd forever" must become
    repairable). The one-time extra call per page is not waste — those are precisely the pages
    with unrepairable terminology drift today.
  - Standard-engine chapters: gate off by construction (rule applies only to the AI lane) —
    normal-manga isolation preserved at zero code cost.
  Blanket stale-once via hash-embedding (option a implemented as PLAN §3 originally worded) would
  additionally re-bill glossary-less and standard-lane chapters once for no repair benefit.
- **Convergence (cost bound):** after a repair pass, re-translated pages re-fold the same pairs;
  `updateGlossary`'s equality gate (ChapterGlossaryStore.kt:49) means the version does NOT bump →
  the following run REUSEs everything. Worst case per glossary maturation = one extra full-chapter
  pass, strictly converging. This is the "bounded by resume planning" clause: repairs are
  per-page-stale, not chapter-wide-forced.

### 1.3 Stamp points (where the version is recorded)
Uniform rule: **stamp the live store glossary version at commit-provenance time** (after the page's
own pairs fold, before the durable write) — `store.currentGlossaryVersion()` (new accessor on
`ChapterGlossaryStore`, `manifest.glossary?.version ?: 0`).
- Batch: `BatchResumePlanner.stampBatchProvenance` TRANSLATION branch
  (`pipeline/batch/BatchResumePlanner.kt:71-74`) sets the field alongside `translationFingerprint`.
- Manual/auto single-page: `SinglePageHttpRenderPhase` stamps next to `:187` before `patchPage`
  (:538-542). Stamping post-fold avoids a guaranteed wasted repair of every manually translated
  page after each session (cost-critical choice); trade-off: pairs folded by a concurrent mode
  between request build and commit are claimed but unseen — rare, converging, accepted.

### 1.4 D1 lease-over-plan corollary interaction
The new field is **payload**, not precondition. The boundary refresh
(`SinglePageHttpRenderPhase.kt:528-542`) waives exactly three plan-identity expected fields
(`candidateGenerationId`, `dependencyFingerprint`, `artifactPageVersion`); the stamp rides the same
patch payload as `translationFingerprint` (:187 precedent) and touches no writer fence
(generation/pageVersion/leaseToken/blockFingerprints stay armed; `ChapterTranslationStore.kt:452-468`).
No new expected-precondition field is added, so the `patchPage` vs `publishLocked` candidate-grace
asymmetry (§4) is not widened.

### 1.5 Chunk-context identity — DECLINED
Rolling context is prior pages' translated text (batch frontier pairs, `translatedPairs`
SinglePageHttpRenderPhase.kt:242-252): any repair changes downstream context, so a context-aware
gate cascades — up to one extra pass **per suffix** until fixpoint (~2x cost) — while the
terminology carrier is the glossary itself (both paths feed `ChapterGlossaryBuilder`; §1.1).
Decision: glossaryVersion only; continuity-text drift across modes recorded as accepted (scoped
Alternative-A flavor, documented in the draft's D5 disposition at Phase 6).

## 2. D6 — wallet fairness + drain-not-cancel

### 2.1 Reservation (concrete)
Bucket scope unchanged: one bucket per `ProviderRequestKey` = backend+model+credential
(`translator/ProviderRequestGovernor.kt:22-41, 268`) — batch and manual on different chapters share
it (the M-10 premise). Changes, all inside the existing `mutex` (no new locks):
- Policy gains `interactiveTokenReserveFraction: Double = 0.2` (validated `0.0 < f <= 1.0`).
- In `evaluate` (:414-451): when the bucket holds ≥1 INTERACTIVE waiter, a **BACKGROUND** waiter's
  effective limits become `requestsPerMinute - 1` and `tokensPerMinute * (1 - fraction)`
  (floor 0; the existing `tokenLimit = max(tokensPerMinute, tokenCost)` at :430 still admits a
  single oversized request). INTERACTIVE waiters always evaluate against the full window
  (`selectWaiter` :502-515 already runs them first until 30 s age). `nextEligibleAt` (:474-500)
  uses the same reduced limits for background waiters so defer estimates stay honest.
- Already-admitted background reservations are **never revoked** (no preemption; consistent with
  M-09's keep-and-commit and §2.3). Batch therefore drains the window down to the reserve, then
  throttles at the next page boundary while a foreground waiter is present; it resumes the full
  window when the waiter admits or leaves.
- **Cold window:** reservations list empty → reduced limits trivially satisfied → interactive
  admits immediately; background also admits (nothing to reserve against). Zero regression for the
  single-user normal path; normal-manga isolation unaffected (no waiter, no shaping).
- Cooldowns (provider-ordered `quotaCooldownMs`, :544-555) apply to interactive too — a wallet the
  provider refused cannot be conjured; that case is covered by the visible pause below.

### 2.2 Visible pause (typed, no UI copy)
Path already typed end-to-end: governor deferral → `ProviderRequestPausedException`
(:179-191, from :345-349) → `SinglePageHttpRenderPhase` catch (:344-352) → PARTIAL page status +
`ChunkCompletionOutcome.Paused` with `failure.safeSummary` + `nextEligibleRetryAtEpochMs`. D6 work:
(a) verify the INTERACTIVE manual path's pause lands in the Phase-2 scheduler outcome map
(`manualOutcomes`, TranslationScheduler.kt:101-105 — the Phase-5 hook); (b) map the same typed
`Paused` for the auto stream path into the coordinator's existing `pausedTranslations` bookkeeping
(RollingAutoCoordinator.kt:708, already tracks paused pages). Reasons travel in the typed outcome;
copy is Phase 5 (PLAN §3 Ph5). No new state enum.

### 2.3 Drain-not-cancel (exact semantics)
Today `shutdownAutoCoordinator` (TranslationScheduler.kt:238-247) → `shutdownRetiringCoordinator`
(:1022-1032) → `RollingAutoCoordinator.shutdown()` (:221-235) → `cancelLocked()` (:253-257) cancels
the coordination job, and the consumer is a child, so cancel propagates into the in-flight
`translatePreparedPage` — the mid-request cancel M-09 names (RollingAutoCoordinator.kt:313-315).
`cancel()` (:205-218) has the same shape for reader-stop. Decision:
- **Awaits/drains:** the ONE in-flight provider call finishes and commits. Implementation: wrap the
  translate+commit section of `consumeTranslations` (:351-380) in `withContext(NonCancellable)`
  bounded by `withTimeout(PROVIDER_DRAIN_GRACE_MS = 90_000)` `[TARGET]` (aligns
  `ONNX_PHASE_TIMEOUT_MS`, TranslationPipeline.kt:117; HTTP cancellation is safe, unlike native, so
  grace expiry cancels cleanly). Channel-close drain already exists (:331-335); `awaitTermination`
  (:242-250) joins owned jobs; `shutdownRetiringCoordinator` already awaits in `scope.launch`
  (TranslationScheduler.kt:1026-1031) — `shutdownAutoCoordinator` stays non-blocking; no caller
  change.
- **Cancels:** everything not yet started. `consumeTranslations` already drops not-yet-started
  stale work (:343-345) and `evictObsolete` keeps only lane-owned slots (:697-718) — unchanged.
  Global/reader-stop auto cancellation (TranslationScheduler.kt:957-1008) already joins
  (`jobsToJoin.joinAll`, `awaitTermination`, :948-949); with NonCancellable the join now means
  "drain", not "cancel".
- **Drained result when the window is gone:** commits anyway. Justification: (1) the call is
  already paid — discarding the result guarantees a duplicate paid call later, the exact waste
  M-09 records for the inverse case; (2) the commit path is window-un-gated and fully fenced
  (generation/pageVersion/lease/write-gate) — if the page lease was lost the patch fails closed
  (ChapterTranslationStore.kt:467-468), no corruption, at most one wasted call in a rare
  ownership race; (3) D4 suppression guarantees no same-chapter auto replacement run races it.
  A drain that times out at the grace bound leaves its D9 ledger entry unresolved (§3.2) —
  intentionally counted as consumed.

## 3. D9 — durable attempt ledger + crash-loop cap

### 3.1 Storage: per-chapter ledger sidecar (not app-level, not manifest-embedded)
New: `ChapterArtifactLayout.attemptLedgerFileName` = `<base>_artifacts/attempts/ledger.json`,
added to `managedDirectories` (`artifact/ChapterArtifactLayout.kt:94-101`). Written via
`ChapterDocumentIo` publish (temp/validate/rename, the glossary-sidecar pattern,
ChapterArtifactStore.kt:172-189). Reasons: (1) **reset/delete semantics** — chapter reset/deletion
sweeps managed directories, so the ledger cannot outlive its chapter (an app-level file leaks
records and needs its own GC); (2) layout conventions already namespace mutable chapter bookkeeping
under `_artifacts/` (glossary, generations); (3) manifest-embedding would publish the whole
manifest per paid call — heavier I/O and schema churn, and Phase-4 D11 will restructure manifest
publication (do not couple them). Record: `{pageKey, providerKeyHash, origin(MANUAL/AUTO/BATCH),
generation, startedAtEpochMs}`; file capped at 64 entries, evict-oldest (bounded memory).
Writer: store-level collaborator (`store/ChapterAttemptLedger.kt`, ChapterGlossaryStore pattern);
no-op for memory-only stores (harness builds D9's store with `FakeChapterDocumentIo` +
artifact authority — harness precedent, phase1-harness-notes §1.2(3)).

### 3.2 Lifecycle
- **Write BEFORE the provider call**, at the three paid call sites: batch
  (`BatchLaneWorkers` before `textTranslator.translatePage`, :1293), auto
  (`consumeTranslations` before `executor.translatePreparedPage`, :358/:371), manual
  (`SinglePageHttpRenderPhase` before `runTranslate`, :226/:290). Failure to persist the entry is
  fail-open for translation (page proceeds) but is logged — availability over accounting.
- **Resolve on outcome:** any completed call (commit success or typed provider failure — a failed
  call is billable-but-verified) removes the entry and resets that page's
  `consecutiveUnresolved` counter. Only process death mid-call leaves an entry.
- **Unresolved-at-startup = consumed attempt:** startup reconcile reads the ledger, increments the
  per-page counter, persists it back in the same file.
- **Cap:** N=3 consecutive unresolved per page → chapter "needs attention": write
  `DurableFailureMetadata` via `recordDurableFailure` (ChapterArtifactStore.kt:203-220;
  category=INTERRUPTED-class, safeSummary="repeatedly interrupted before completing; manual retry
  required", `nextEligibleRetryAtEpochMs=null` → not auto-retryable), set page PARTIAL, chapter
  state **PAUSED** (existing `Translation.State.PAUSED(6)`, Translation.kt:48 — no new enum, no UI
  work; queue/notification already render paused + durable-failure vocabulary per M-07's two-layer
  rule). Explicit user force (`prepareForcedRetry`, model/PageTranslationState.kt:107-117) clears
  the counter and the durable failure — the cap binds auto-retry loops, never the user.
- **Startup integration:** new bounded pass `reconcileAttemptLedgersForStartup` next to
  `reconcilePendingRequestsForStartup` (TranslationManager.kt:457-527), over the bounded chapter
  set only: `persistedQueueChapterIds()` (ChapterTranslator.kt:155-157) ∪ pending request ids ∪
  active-store registry. Never a library scan. v2.1's fictional RUNNING→PENDING sweep stays
  deleted (H-10).
- **Phase-2 attach interaction:** attach-waiting manual runs NO provider call
  (`attachToOwnerTerminal` is observation-only, TranslationPipeline.kt:509-551; phase2-verification
  §C) → no ledger write. A batch/auto page mid-call at death writes the entry before the call →
  consumed at startup. Manual boundary mid-native (pre-provider) at death: no entry, page stranded
  non-terminal, healed by the existing reader-open sweep (ReaderViewModel :2531-2595) — unchanged.

## 4. Backlog fold-in — patchPage candidate-grace asymmetry: **INCLUDE in Phase 3**
`ChapterTranslationStore.kt:461-463` lacks the `record.candidate != null` grace `publishLocked`
has (:1483-1485). Align it here because (i) D5/D9 touch exactly this commit path and its planner
consumers; (ii) the D5 repair choreography (batch registration between manual capture and commit,
lease held → accepted) IS the required regression scenario — one test covers both; (iii) it is a
small, fail-direction-preserving store change (a retried page, never corruption — phase2
verification finding 3). Deferring would leave a known false-reject under the new stamp traffic.
The second backlog note (manifest-only mutation without generation bump can fail-closed reject)
stays acceptable/observed-only — no change.

## 5. Test plan — write FIRST (failing), per decision

Harness capabilities assumed (phase1-harness-notes): fake `TextTranslator` with per-page paid-call
counters; PROVIDER_START/END barriers; memory-only or FakeChapterDocumentIo stores; prefs lane
toggle. Required harness extensions (Implementer lists any new shim in the phase report):
fake `ContextualTextTranslator` exercising glossary/pairs (D5); governor injection seam into the
fake transport (D6); second store+chapter for cross-chapter D6; artifact-authority store + scope
kill to simulate process death (D9).

1. **D5** — `coexistence/D5GlossaryAwareReuseTest.kt`:
   a. manual-early on AI lane with empty glossary → pairs fold (version 1) → batch re-run plans
      RUN for that page; fake transport counts exactly one extra paid call (repair);
   b. second batch re-run with unchanged glossary → REUSE, zero paid calls (convergence /
      no-oscillation — guards the ChapterGlossaryStore.kt:49 equality property);
   c. glossary-less chapter (no pointer) re-run → REUSE, zero paid calls (grandfather rule);
   d. standard-engine chapter → planner decisions byte-identical to pre-D5 (isolation).
2. **D6** — two files:
   - `translator/ProviderRequestGovernorReservationTest.kt` (pure, fake clock/window — PLAN §5
     "governor tests use fake windows"): background stream against full window with interactive
     waiter present → background defers at the reserve line; interactive admits; cold window →
     both admit; waiter leaves → background resumes full window; no revocation of admitted
     reservations.
   - `coexistence/D6ForegroundFairnessTest.kt`: batch draining a small injected window while a
     manual tap on ANOTHER chapter waits → manual admitted before the batch's next page
     (reservation), else typed Paused with reason + retryAt ≤ bound; `coexistence/D6DrainNotCancelTest.kt`:
     auto page parked at PROVIDER_START, `shutdownAutoCoordinator`, release → call completes, no
     cancellation, commit lands (READY), ledger entry resolves, no duplicate re-translation; and
     the grace-expiry variant → entry unresolved.
3. **D9** — `coexistence/D9AttemptLedgerTest.kt`: park at PROVIDER_START, kill the scope (simulated
   death) → entry written pre-call; reopen store, run startup reconcile → unresolved consumed;
   three cycles → PAUSED + durable failure present, auto entry refused; explicit force clears;
   attach-waiting manual asserts zero ledger writes.
4. **Backlog** — `ChapterTranslationStorePatchPageGraceTest.kt` (memory store, no harness):
   candidate-less registration between capture and commit, lease held → `patchPage` accepted;
   lease lost → rejected (fail-closed regression guard).

## 6. Risks, sequencing, constraints

**Commit order (branch `t917/coexistence-v3`, each compiling, tagged per PLAN §2):**
1. `t917(p3): d5 tests` (red) → 2. `t917(p3): d5 gate+stamp` → 3. `t917(p3): patchPage grace +
regression test` → 4. `t917(p3): d9 tests` (red) → 5. `t917(p3): d9 ledger+cap+reconcile` →
6. `t917(p3): d6 tests` (red) → 7. `t917(p3): d6 reservation+typing+drain`. Rationale: D5/D9 are
store/planner-scoped (no concurrency semantics); D6 last is the only behavior under live
concurrency and benefits from the ledger existing (drain tests assert resolution). Gate: full
translation suite + 100-run soak on the new package (Phase-2 gate precedent).

**Risk table:**
- D5 mass re-translation regression (version misread per open) → test 1b is the guard; version is
  monotonic per chapter (ChapterArtifactStore.kt:175-179).
- D5 double-gating textless pages → gate applies to REUSE only; TERMINAL_COMPLETE (textless/skip)
  exempt — asserted in 1d.
- D6 batch starvation on tiny quotas → reserve ≤20% and window slides guarantee batch progress;
  background defer reason surfaced via existing diagnostics events (:561-582).
- D6 drain holds a page past shutdown → bounded by PROVIDER_DRAIN_GRACE_MS; shutdown path stays
  non-blocking; no main-thread blocking anywhere.
- D9 ledger I/O per paid call → tiny file (≤64 entries), one temp+rename; kept synchronous
  deliberately (deferral would recreate the crash window D9 exists to close); revisit under D11
  only if measurements demand.
- Memory: all new structures bounded (64-entry ledger, capped counters, no per-page maps beyond
  existing store state) — Android 8 / 6 GB class unaffected; no new APIs (epoch-ms longs only).
- Reader stability / normal-manga isolation: D5 gate is AI-lane+artifact-authority only and lives
  in batch planning + single-page stamping (no render/decode change); D6 shapes only the shared
  wallet when contention exists (cold path unchanged); D9 adds writes at already-serialized call
  boundaries. NormalMangaIsolationTest must stay green untouched — if it needs editing, the design
  leaked and that is a defect.
