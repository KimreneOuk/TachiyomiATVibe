# Translation Subsystem Coexistence — Specification v3.0

**Version:** 3.0 (CANONICAL)
**Date:** 2026-09-01 (draft); 2026-09-03 (promoted to canonical)
**Status:** CANONICAL. Supersedes v2.1, which was rejected by the T916 strict audit. All decision points D1–D13 were resolved and implemented across T917 Phases 1–5; the §8 verification suite is green (198 suites / 1446 tests, XML-verified) and §8's on-device end-to-end verification passed on a real chapter (T917 Phase 6, findings F1/F6 fixed and re-verified). §8.3's multi-tier measurement protocol was **descoped for this release by Director decision (2026-09-03)**: every performance figure remains `[TARGET]` until that protocol runs.
**Provenance:** T914 (actual-behavior investigation), T915 (adversarial verification — 9.6/9.8 scores later overturned), T916 Round 1 + Round 2 strict audits (`Plan/active/2026-09-01_T916_translation-coexistence-strict-audit/STRICT_AUDIT_REPORT.md`), T917 implementation + verification (`Plan/active/2026-09-01_T917_coexistence-v3-implementation/PHASE-LOG.md`).
**Supersedes:** v2.1 "Post-Peer-Review Hardened" (same filename), whose central claims were contradicted by live code, by existing tests, or by nothing at all.

---

## 0. How to read this document

Every material statement carries exactly one confidence label:

| Label | Meaning |
|---|---|
| `[VERIFIED]` | Confirmed against live code and/or tests in T916, with file:line evidence in the audit report. |
| `[DECISION]` | Open decision for the Director. Each has a **Recommendation**, at least one **Alternative**, and a **Default** that applies if no decision is made. |
| `[TARGET]` | Aspiration. Never measured. Must not be called an SLO until §8's measurement protocol produces numbers. |
| `[UNVERIFIED]` | Believed true but not yet proven by code trace or test. Needs evidence before it may be relied on. |

This document deliberately separates **what the system does**, **what we must decide**, and **what we wish were true**. v2.1's failure was merging the three.

Global constraints (unchanged, binding): Android 8.0+, bounded memory on ≥6 GB RAM devices, reader stability first, and **normal (translation-disabled) manga must not regress**.

---

## 1. Purpose

Specify how three translation modes coexist on one device without lost user intent, duplicate paid work, lying progress state, or reader regression:

1. **Batch** — background pre-translation of whole chapters (the "overnight factory").
2. **Rolling auto** — viewport-driven translation a few pages ahead of the reader (the "smart assistant").
3. **Manual** — a direct user tap on one specific page (the "on-demand button").

---

## 2. Mental model

- **One bulletin board** (`ChapterTranslationStore` per chapter, via `ActiveChapterStoreRegistry`): all modes publish finished work to the same live state; the reader observes it without polling. `[VERIFIED]`
- **One stove** (`NativeRunQuarantine`): on-device detection/OCR/inpainting runs one inference at a time, process-wide, because of memory ceilings. Modes take turns. `[VERIFIED]`
- **A shared notebook** (chapter glossary): terminology accumulated across pages, shared by batch *and* single-page paths. `[VERIFIED]`
- **A shared wallet and tap line** (`SharedProviderRequestGovernor`): one rate window and cooldown per provider credential, shared by all chapters and all modes — currently **without** an interactive reservation. `[VERIFIED]`

The contract below defines what happens when these four shared resources are contested.

---

## 3. Component map (corrected paths)

v2.1 cited wrong package paths (T916 L-02). Canonical locations:

| Component | Path |
|---|---|
| TranslationManager | `app/src/main/java/eu/kanade/translation/TranslationManager.kt` |
| TranslationScheduler | `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` |
| RollingAutoCoordinator | `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` |
| NativeRunQuarantine | `app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt` |
| TranslationPipeline / single-page phases | `app/src/main/java/eu/kanade/translation/pipeline/…` |
| Batch coordinator / workers / planner | `app/src/main/java/eu/kanade/translation/pipeline/batch/…` |
| ChapterTranslationStore / write gates | `app/src/main/java/eu/kanade/translation/store/…`, `…/ChapterTranslationStore.kt` |
| ChapterTranslator / BatchChapterTranslator | `app/src/main/java/eu/kanade/translation/…` |
| Foreground service | `app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt` |
| Reader arbitration entry | `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` |

---

## 4. What is true today (verified baseline)

The new contract is built on this baseline, not on wishes.

### 4.1 The native stove
- Single-permit, documented-fair FIFO (`kotlinx-coroutines` 1.10.1, `Mutex`). `[VERIFIED]`
- Result-invalidation timeout is **90 s** (`ONNX_PHASE_TIMEOUT_MS`, `TranslationPipeline.kt:117`). On timeout the result is discarded and the generation bumped, but **the lane stays occupied until the native call really exits** (`NativeRunQuarantine.kt:19,71-96`). There is no forced kill of a hung inference. `[VERIFIED]`
- Consequence: any "≤2.5 s worst case" acquisition claim is unsupported (§6 D8).

### 4.2 Ownership vocabulary
- The page lease table distinguishes only `BATCH` vs `READER_ADHOC`; **manual and rolling auto are the same origin** and cannot be prioritized against each other at the lease layer (T916 H-01). `[VERIFIED]`

### 4.3 Storage and commits
- Manifest writes go `.tmp → flush → rename`, with `.bak` rotation: crash-safe against process death, **not** power-loss durable (no fsync) (`ChapterDocumentIo.kt:106-208`). `[VERIFIED]`
- The store mutex is held across the manifest publication; one chapter's slow (SAF) flush delays every other writer's commit for that chapter, and ONNX-path commits run while holding the native permit, coupling disk latency into the stove (T916 M-11). `[VERIFIED]`
- On promotion failure the candidate that already persisted stays durable while only the in-memory projection rolls back. `[VERIFIED]`
- Cleaned companions are JPEG quality **90**, version derived from the live version (not a fixed `.1`), fingerprint is SHA-256 over the **full** source stream (T916 M-03). `[VERIFIED]`
- Archive reading is genuinely memory-mapped (`ArchiveReader`, `Os.mmap`). The v2.1 claim holds. `[VERIFIED]`

### 4.4 Fingerprint and reuse gates
- Translation fingerprint = `configuration(TRANSLATION, translatorSignature, fromLang, toLang)` where the signature covers engine category, provider, key hash, base URL, model, sampling, reading order, languages (`PageDecode.kt:165-169`, `EngineLane.kt:121-133`). `[VERIFIED]`
- **It excludes `glossaryVersion` and any chunk-context identity.** `glossaryVersion` is persisted but never read by any stage decision (`PageWorkPlanner` has zero glossary references). Manual and batch outputs are fingerprint-identical under equal config (T916 H-07). `[VERIFIED]`
- Stale-result rejection at commit (generation + page version + fingerprint preconditions) exists and survives audit (T915 V-01/V-02 mechanisms). `[VERIFIED]`
- The batch write gate rejects writes without a matching batch identity — which is exactly why reader-owned pages currently strand at reconciliation (T916 C-02). `[VERIFIED]`

### 4.5 Glossary
- The notebook is genuinely shared: the single-page path builds glossary context from the store and folds its pairs back; batch seeds its stats from `store.translatedPairs()`, which includes manual results. The defect is the reuse gate (§6 D5), not context sharing. `[VERIFIED]`

### 4.6 Provider wallet
- `SharedProviderRequestGovernor`: application-lifetime, bucketed by backend+model+credential, **chapter-agnostic**. Interactive priority exists but is bounded (30 s), wired only for the reader-stream manual path (`translateSinglePageFromStream` remains BACKGROUND). A foreground waiter deferred beyond 15 s is paused; cooldowns gate every tap including force; `clearCooldown` has no production callers (T916 M-10, M-07). `[VERIFIED]`

### 4.7 Cancellation, eviction, restart
- Evicted out-of-window auto work that already started **runs to completion and commits durably** (window-un-gated patch). Same-chapter auto shutdown (batch start) **cancels an in-flight provider call mid-request**, wasting it; the batch then re-translates that page (T916 M-09). `[VERIFIED]`
- `ACTION_STOP` → `clearQueue` → `stop()` → `closeEngines()` when the native lane is momentarily idle; the HTTP translate phase runs outside the quarantine, and `SinglePageHttpRenderPhase.kt:143-144` states the page **will fail and retry** if closed mid-flight. `clearQueue` does not touch rolling auto (T916 H-09). `[VERIFIED]`
- There is **no startup RUNNING→PENDING sweep** (v2.1 §4.2 invented it). The only heal is the reader-open stranded sweep (writes CANCELLED, skips current-generation and batch-retained chapters). Attempt counters are charged only on failure paths, so process death mid-provider-call leaves no record — a crash loop re-bills without bound (T916 H-10). `[VERIFIED]`
- v2.1's "Kotlin Mutex strict FIFO / manual becomes the immediate next waiter" is **half-true**: the mutex is fair, but FIFO only orders *already-waiting* coroutines; batch re-acquires between pages, so a fresh manual waiter can wait many pages. No priority exists (T916 H-03, weakened). `[VERIFIED]`

### 4.8 Reader plumbing
- Warm-window trigger is a CONFLATED channel and prepared-work capacity is tier-based **2/4/6**, not "Channel(1)" (T916 M-05). No main-thread suspension anywhere on this path. `[VERIFIED]`
- `ReaderActivity` is `launchMode="singleTask"` (not "singleTop / singleTask"). `[VERIFIED]`
- Textless pages: zero detected bubbles marks downstream stages skipped when OCR is READY; it does **not** set `ocrStatus = TEXTLESS` (T916 M-03). `[VERIFIED]`

---

## 5. Defect register carried from v2.1

Full evidence in the audit report. "Addressed by" points at the decision that must own it.

| ID | One line | Addressed by |
|---|---|---|
| C-01 | Manual tap on batch-owned page is silently swallowed | D2 |
| C-02 | Reader-owned page is skipped by batch, never rescanned; reconciliation strands it | D3 |
| C-03 | Rolling auto re-arms during same-chapter batch (tested policy) contradicting "batch owns chapter" | D4 |
| H-01 | Lease vocabulary cannot express MANUAL vs AUTO priority | D1 |
| H-02 | Duplicate work beyond the native guard (prepare/provider/render overlap) | D1, D5 |
| H-03 | No priority at the native lane; FIFO ≠ "immediate next waiter" | D8 |
| H-04/H-09 | Shared engines closed under reader work on batch stop/complete | D7 |
| H-05 | No full-path coexistence integration test | §8 |
| H-06 | No authoritative state machine / terminal outcomes | §7 |
| H-07 | Translation reuse is glossary-blind → permanent cross-mode terminology inconsistency | D5 |
| H-08 | 90 s lane-occupancy reality vs "≤2.5 s" promise | D8 |
| H-10 | No startup recovery as described; unbounded crash-loop re-billing | D9 |
| M-05/M-06 | v2.1 misdescribes warm-window mechanism and handback premise | §4 (corrected) |
| M-07 | §8.3.2 misattributes pause mechanism; FAILED_RETRYABLE is manifest-level, not stage-level | §4 (corrected) |
| M-08 | Partial download yields a silently partial "successful" batch | D10 |
| M-09 | Evicted auto work commits; auto shutdown wastes a paid mid-flight call | D6, D11 |
| M-10 | Provider governor can pause manual cross-chapter; "without starvation" false | D6 |
| M-11 | Store commit couples modes to disk latency; not power-loss durable; rollback story incomplete | D12 |
| M-02 | All v2.1 numbers were unmeasured | D13, §8 |

---

## 6. The coexistence contract — open decision points

Each decision states the rule to adopt, a recommendation, at least one alternative, and the default if the Director defers. Defaults are chosen for **safety and truthfulness**, not convenience.

### D1 — Origin model `[DECISION]`
**Finding:** H-01, H-02.
**Rule to adopt:** three origins — `MANUAL`, `AUTO`, `BATCH` — as first-class lease identities, enabling explicit priority per resource (stove, wallet, lease) and per-stage dedup identity `(chapter, page, sourceFingerprint, settingsFingerprint, stage, generation)`.
- **Recommendation:** adopt three origins. It is the minimum vocabulary that can express the promises users already believe.
- **Alternative A:** keep `READER_ADHOC` for manual+auto and define an explicit *join protocol* (manual subscribes to the auto result for that page, with completion signaling and identical UI outcomes). Lower churn; keeps one origin, but every priority rule must then live outside the lease layer and be tested there.
- **Alternative B:** keep `READER_ADHOC` and declare manual == auto permanently, removing all priority language from user-facing copy. Cheapest; honest; weakest UX.
- **Default if deferred:** Alternative B (no priority promises at all).

### D2 — Manual tap on a batch-owned page (no more silent loss) `[DECISION]`
**Finding:** C-01. **Never silence. This is non-negotiable regardless of choice.**
- **Recommendation: wait-and-attach.** Manual attaches to the batch-owned page's in-flight work: UI shows "translating (background job)", the user's intent completes with the batch result, bounded by the stove's timeout semantics. No paid work is wasted; user wins visibly.
- **Alternative A: preempt.** Manual cancels batch work for that page at the next stage boundary, takes the lease, and batch reschedules the page. Fastest felt response; costs a possibly wasted paid call if cancellation lands mid-provider; requires boundary-safe cancellation semantics (D7's epoch guard).
- **Alternative B: visible rejection.** Return "already queued by background translation" as a toast/inline state; no attach, no preempt. Cheapest to build; UX inferior but truthful.
- **Default if deferred:** Alternative B (visible rejection).

### D3 — Batch meets a reader-owned page (defer, rescan, tell the truth) `[DECISION]`
**Finding:** C-02.
- **Recommendation: defer-and-rescan.** Batch records the page as pending-handback, observes lease release, and **rescans within the same pass** before reconciliation. A page only reaches a terminal state after rescan; skipped pages are impossible in the final result.
- **Alternative A: end-of-batch sweep.** Reconciliation marks reader-owned pages `SKIPPED_RETRYABLE` and a second mini-pass re-runs them after the main pass. Simpler wiring (no lease-release wakeup), one extra pass over few pages.
- **Alternative B: truthful skip.** No rescan. Batch completes with an explicit skipped list surfaced in progress/UI ("19/20 — 1 skipped: you were reading it — tap to translate"). No new machinery, but the user does the integration work.
- **Default if deferred:** Alternative B (truthful skip).

### D4 — Rolling auto vs same-chapter batch (pick exactly one contract) `[DECISION]`
**Finding:** C-03. The two contracts are mutually exclusive; v2.1 claimed one and shipped+tested the other.
- **Recommendation: suppress same-chapter auto for the batch lifetime.** Add a batch-active guard to the re-arm path, update the existing test that currently requires re-arm. Matches v2.1's stated intent, gives batch the deterministic ownership it needs (D3), and is the simplest mental model.
- **Alternative: true concurrent ownership.** Keep the current tested behavior (auto re-arms during batch) and define stage-level co-ownership: lease arbitration per page per stage, dedup via the D1 work identity, and explicit "who commits" rules. Preserves current UX on low-end networks where batch may be stalled and auto is fresher; highest complexity and the burden of proving exactly-once per page.
- **Default if deferred:** suppression (safest; behavior change must ship with the updated test).

### D5 — Terminology consistency: the reuse gate must know the notebook `[DECISION]`
**Finding:** H-07. Today, manual-then-batch chapters permanently keep mixed character names; no later run repairs them.
- **Recommendation:** add `glossaryVersion` (and chunk-context identity) to the translation reuse gate: a page whose stored translation predates the current glossary version becomes eligible for re-translation on the next batch. Cost: additional paid calls after the glossary matures; bounded by resume planning.
- **Alternative A: accept inconsistency, sell the fix.** Keep the gate as is; document cross-mode terminology drift as accepted; add an explicit user action "Re-translate chapter with updated terminology" so the cost is user-controlled and visible.
- **Alternative B (partial mitigation): chapter-priming.** Force glossary stabilization earlier (e.g., prime the notebook from the first N pages before any commit elsewhere). Reduces, but does not eliminate, drift.
- **Default if deferred:** Alternative A (accepted drift + explicit re-translate action).

### D6 — Provider wallet: foreground never starves silently `[DECISION]`
**Finding:** M-10, M-09, M-01.
- **Recommendation: interactive reservation + visible pause.** Reserve a share of each provider's token window for reader-originated requests; batch throttles while a foreground waiter is present; if a manual request is ever paused (>15 s window slide, cooldown), the UI states why and offers retry. Additionally define a **drain-vs-cancel policy**: auto shutdown cancels only not-yet-started calls; in-flight calls finish and commit (currently inverted in one path — M-09).
- **Alternative A: per-mode buckets.** Separate budgets for batch vs reader. Cleaner isolation, worse peak utilization on small quotas.
- **Alternative B (cheapest): keep the single bucket**, keep the 15 s deferral, but make every deferral/pause visible with reason + retry. No fairness guarantee; starvation becomes honest instead of impossible.
- **Default if deferred:** Alternative B.

### D7 — Shared engine lifetime (stop must not break reading) `[DECISION]`
**Finding:** H-04, H-09. Today's behavior is code-admitted: stopping batch can fail one in-flight reader page ("accepted trade-off").
- **Recommendation: epoch + drain.** Engine close requires (a) no in-flight reader work **or** a bounded drain grace (a few seconds), and (b) an epoch guard so any work racing the close retries against the rebuilt engine exactly once and never commits stale state. Removes the admitted failure while keeping close responsive.
- **Alternative A: per-origin engines.** Separate engine instances per origin; closing batch's engines can never touch reader work. Prohibitive for on-device ONNX (memory doubles); feasible for cloud translators only — would split the contract into native vs cloud, adding complexity.
- **Alternative B: formalize today's trade-off.** Keep close-immediate, but guarantee the code-comment's promise: the racing reader page auto-retries against the new engine, the retry is visible, and it is documented as expected behavior, not a defect. Cheapest; users still see one failed-then-fixed page on Stop.
- **Default if deferred:** Alternative B (explicit, tested, documented trade-off).

### D8 — Stall bounds and the honest SLO story `[DECISION]`
**Finding:** H-08, H-03.
- **Recommendation: two-tier timeout + honest UI.** Keep the 90 s result-invalidation timer; add a **lane-occupancy watchdog** that, if the stove is held beyond a threshold, flips reader translation states to a visible "translation stalled — background job may be stuck" and refuses new promises. Replace v2.1's "≤2.5 s worst case" with measured numbers from §8 before any SLO claim. Do **not** attempt to kill hung native calls (JNI abort risks corrupting the process — the reason the current design waits).
- **Alternative A: priority admission at the stove.** A small admission controller so manual waits behind at most one native stage, not a queue. Real improvement; new scheduler component to build and prove.
- **Alternative B: document-only.** Keep behavior; rewrite the doc's numbers to match reality (worst case unbounded; typical measured later). No new code.
- **Default if deferred:** Alternative B.

### D9 — Restart and crash-loop cost accounting `[DECISION]`
**Finding:** H-10.
- **Recommendation: attempt ledger.** Write a durable "attempt started (page, provider, generation)" record **before** each paid call; on restart, unresolved attempts count as consumed; after N consecutive unverified attempts on the same page (e.g., 3), the chapter pauses with "needs attention" instead of silently re-billing. Startup recovery is then describable truthfully (reader-open CANCELLED sweep + ledger), and v2.1's invented RUNNING→PENDING sweep is dropped.
- **Alternative A: cheap cap.** No ledger; cap automatic re-runs per page per day and expose the counter. Much less machinery; the "attempt in flight at death" remains unrecorded, so the cap is approximate.
- **Alternative B: accept silent re-billing**, document it. Not recommended with paid providers.
- **Default if deferred:** Alternative A.

### D10 — Batch admission and partial downloads `[DECISION]`
**Finding:** M-08. Today a half-downloaded chapter translates as a silently partial "success".
- **Recommendation:** admission checks the source page list against downloaded files; on mismatch, the user chooses: "queue the missing downloads, then translate" or "translate the downloaded 37/40 pages only" (result labeled partial, with the missing list). 
- **Alternative A: hard gate.** Refuse batch until download completes (simplest; annoying on flaky networks).
- **Alternative B: silent subset, truthful label.** Keep auto-proceed on the subset but always label the chapter "partial" and count missing pages in progress truthfully.
- **Default if deferred:** Alternative B.

### D11 — Cross-mode latency coupling in commits `[DECISION]`
**Finding:** M-11. One chapter's slow SAF flush delays other modes' commits, and commits under the native permit couple disk into the stove.
- **Recommendation:** decouple persistence from the store mutex: commit in-memory under the lock (fast, ordered), publish the manifest through a single ordered writer outside the lock, with the existing rename chain for crash safety and the epoch guard for stale publication. Accept the already-existing crash window (disk behind memory briefly) as documented behavior.
- **Alternative A: keep synchronous persistence** (current), but document the latency coupling and slow-SD-card risk, and stop holding the native permit across the flush where feasible.
- **Alternative B: no change**, document only. 
- **Default if deferred:** Alternative A (document, plus removing the permit-holding flush if a low-risk window exists).

### D12 — Storage truth fixes (non-negotiable corrections, no alternatives) `[VERIFIED]`
Adopted as-is (v2.1 was wrong): JPEG quality 90; full-stream SHA-256; versioned cleaned filenames derived from live version; textless semantics per §4.8; correct package paths (§3); `singleTask`; CONFLATED trigger + tier capacity 2/4/6; startup behavior per §4.7; manifest-level vs stage-level failure vocabulary per M-07. These corrections are binding text in v3.0.

### D13 — Performance claims `[DECISION]`
**Finding:** M-02.
- **Recommendation:** every number in v2.1 (60 FPS, <100 ms textless, ≤2.5 s, ten-pages-<600 ms) is reclassified `[TARGET]` until §8's protocol produces measured numbers per device tier with percentiles; until then, user-facing copy must not cite them.
- **Alternative: delete the numbers** from all documents until measured. Strictest.
- **Default if deferred:** Recommendation (reclassify, keep as targets).

---

## 7. Terminal outcome and UI-truth contract (fills H-06)

Regardless of D1–D13 defaults, these hold in v3.0:

1. **Every user intent reaches a visible terminal outcome**: completed · attached-to-owner (with progress) · queued-behind (position or "waiting") · rejected (with reason) · failed (with reason). Silent loss is a defect class, not a trade-off.
2. **Every page in a batch reaches exactly one terminal state**: translated · reused-valid · skipped-with-reason (only under D3 Alternative B/A) · failed-retryable · failed-permanent · cancelled. Progress totals and the notification are computed from these states, never from optimism.
3. **Stale work can never publish**: commit paths validate generation + work identity + lease/epoch; late results are discarded, counted, and never shown.
4. **UI state and durable state may disagree only transiently** (in-flight commit); a test must exist for each mode pair proving convergence.

---

## 8. Verification plan (fills H-05)

1. **Deterministic interleaving harness** with controllable barriers at: native acquire/release, provider start/end, render join, durable commit, engine close/rebuild, reconciliation. All Collision tests run real components (manager, scheduler, pipeline, store, batch coordinator, rolling coordinator), not mocks-of-collaborators.
2. **Required tests (block canonical status):**
   - D2 matrix: batch→manual and manual→batch at every barrier; assert manual intent is never lost and paid-call counts match the policy.
   - D3: reader-owned page across an entire batch; assert final page terminal state + progress truth.
   - D4: whichever contract is chosen — suppression or co-ownership — encoded as the updated test.
   - D5: glossary-maturity reuse (manual early page + batch later) asserting the chosen consistency policy.
   - D7: stop/complete batch while reader work sits at each barrier; assert engine-use safety per the chosen policy.
   - D6: batch draining the window while manual waits cross-chapter; assert the chosen fairness behavior and visibility.
   - D9: simulated process death mid-provider-call; assert ledger/cap behavior.
   - Normal-manga isolation: translation-disabled chapters never enter arbitration, storage observation, or extra decode paths.
3. **Measurement protocol (D13):** device tiers (low/mid/high from the 6 GB class), fixed corpus, warm/cold states, p50/p95/p99, before any number returns to user-facing or SLO status.

## 9. Rollout order

1. Apply D12 corrections + D13 reclassification (doc-only, immediately).
2. Resolve D2, D3, D4 (the three user-visible intent rules) → write failing tests first (§8).
3. Land D1/D5 (origin vocabulary + reuse gate) with those tests.
4. Land D6, D7, D9 (wallet fairness, engine lifetime, restart ledger).
5. D8, D10, D11 follow; rewrite user-facing copy only after §8 numbers exist.

## 10. Open questions needing runtime evidence `[UNVERIFIED]`

- Can batch admission actually race an active downloader session (D10), or does the UI prevent it today?
- Real-world frequency of hung native inferences (drives D8's watchdog thresholds).
- Observed provider window sizes per backend in the wild (drives D6's reservation ratio).

---

## Appendix A — Traceability

C-01→D2 · C-02→D3 · C-03→D4 · H-01/H-02→D1 · H-03/H-08→D8 · H-04/H-09→D7 · H-05→§8 · H-06→§7 · H-07→D5 · H-10→D9 · M-01/M-09→D6/D11 · M-02→D13 · M-03→D12 · M-07→D12 · M-08→D10 · M-10→D6 · M-11→D11 · v2.1 storage/§9 defects→D12/§8.

## Appendix B — Disposition of v2.1's own claims

True and kept: mmap archive reading; shared store/bulletin board; single stove concept; glossary sharing across modes; stale-result commit guards; crash-safe rename chain; 8-step deletion teardown `[SURVIVED_T915, not re-audited in T916]`.
False and corrected: "manual preempts and attaches"; "batch skips and rescans"; "auto stands down during batch"; "≤2 contenders"; "strict FIFO → immediate next waiter"; "manual jobs uninterrupted on Stop"; "Channel(1)"; "startup RUNNING→PENDING"; "transactional with in-lock disk flush + full rollback"; "quality 92"; "first-64KiB fingerprint"; "zero bubbles sets ocrStatus=TEXTLESS"; "singleTop/singleTask"; every unmeasured number presented as verified SLO; the "roadmap" of already-merged fixes; the risk register that contained zero coexistence findings.

*End of specification. This document is canonical as of 2026-09-03.*
