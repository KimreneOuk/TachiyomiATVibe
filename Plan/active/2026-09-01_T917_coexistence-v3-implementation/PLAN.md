# T917 Implementation Plan

**Status:** APPROVED-FOR-PREP (Director adopted all v3.0 recommendations on 2026-09-01; delegation begins on Director's go).
**Process level:** L2/L3 — Technical Lead design notes where flagged, Implementer per phase, Reviewer acceptance per phase, Main Leader synthesis at gates.

---

## 1. Decision record (binding)

All v3.0 draft §6 decisions are adopted at their **Recommendation**:

D1 three-origin leases (`MANUAL`/`AUTO`/`BATCH`) · D2 manual **wait-and-attach** on batch-owned pages · D3 batch **defer-and-rescan** of reader-owned pages · D4 **suppress same-chapter auto for batch lifetime** · D5 **glossary-aware translation reuse gate** · D6 **interactive reservation + visible pauses + drain-not-cancel** · D7 **epoch + drain engine lifetime** · D8 **90 s result timer + occupancy watchdog → visible "stalled"** (no native kill) · D9 **durable attempt ledger + crash-loop cap** · D10 **admission check for partial downloads** · D11 **persist outside the store mutex** · D12 factual corrections (no alternatives) · D13 numbers are `[TARGET]` until measured.

---

## 2. Checkpoint & rollback policy (Director requirement — binding)

**Anchors:**
- `main` is the rollback anchor. It receives only baseline docs/plan commits and phase-result merges.
- All implementation happens on branch **`t917/coexistence-v3`** (created at baseline).

**Tag scheme (annotated):**
- `checkpoint/t916-audit-baseline` — pre-implementation state (already placed).
- `checkpoint/t917-p<N>-start` / `checkpoint/t917-p<N>-done` — before/after each phase.
- Any risky step inside a phase may add `checkpoint/t917-p<N>-<step>`.

**Per-turn rule ("safe checkpoint every turn"):**
1. Before work: `git status` must be clean at the last checkpoint tag.
2. After every meaningful step (compiling state): commit with message `t917(pN): <what>`.
3. At every turn end: working tree either committed or explicitly reported as WIP; never leave uncommitted behavior changes.
4. Phase gate: full unit-test run of the touched modules must pass **before** `p<N>-done` is tagged; the tag message records the test command and result.

**Rollback procedures:**
- Undo last step: `git reset --hard checkpoint/t917-p<N>-<step>` (on the work branch).
- Abandon a whole phase: `git reset --hard checkpoint/t917-p<N>-start`.
- Abandon everything: `git switch main` (work branch simply deleted; `main` was never touched).
- Post-merge defect: `git revert` on `main` (no history rewrite).
No force-push, no history rewrite on `main`, ever.

---

## 3. Phases

### Phase 0 — Baseline & stewardship (Main Leader + Repository Steward)
- Commit all T914–T916 records, v3.0 draft, superseded banner on `main`; place baseline tag; create work branch. *(Done in this turn.)*
- Record toolchain baseline: `./gradlew --version`; at Phase-1 start run the touched-module unit tests and record baseline results in `PHASE-LOG.md`.
- **Exit:** baseline tag exists; work branch exists; toolchain status recorded.

### Phase 1 — Harness and failing tests (Implementer, guided by Technical Lead note)
- Build the **deterministic interleaving harness** (v3.0 §8.1) with controllable barriers at: native acquire/release, provider start/end, render join, durable commit, engine close/rebuild, reconciliation. Real components, not mock-collaborators (audit H-05).
- Write **failing** tests for D2 (batch→manual and manual→batch at every barrier; paid-call counts), D3 (reader-owned page across a full batch; final terminal state + progress truth), D4 (same-chapter auto suppressed during batch — this *updates* `TranslationManagerAutoArbitrationTest`, which currently requires the opposite).
- Also land the **normal-manga isolation test** (§8) early — it gates all later phases.
- **Exit:** harness merged; new tests red for the right reason (assert on current defective behavior); baseline green tests still green.
- Tag: `checkpoint/t917-p1-done`. Reports: `engineering/phase1-harness-notes.md`, `review/phase1-verification.md`.

### Phase 2 — Intent-loss fixes (Implementer; Technical Lead note first for D1)
- **D1** three-origin lease model: `PageStageLeaseTable` gains `MANUAL`/`AUTO`/`BATCH`; update all acquisition call sites; define per-resource priority in one table (stove, wallet, lease). Design note required.
- **D2** wait-and-attach: `TranslationPipeline` single-page boundary attaches to batch-owned pages instead of returning false; `TranslationScheduler.translatePage` completes the manual intent with the batch result; `ReaderViewModel` surfaces "Translating · background job" chip state. No silent return path remains (audit C-01).
- **D3** defer-and-rescan: `BatchLaneWorkers` records pending-handback instead of skip; `SequentialBatchCoordinator` rescans after lease release within the pass; reconciliation only after rescan; `BatchWriteGate` handback identity defined (audit C-02).
- **D4** suppression guard: batch-active gate (queue entry in `QUEUE|TRANSLATING|PAUSED` retained state) on the manager's auto-entry methods — `updateAutoWindow`, `requestAutoWindow`, and the `reconcileAutoWindow` admission guard. (Correction per `engineering/phase2-design.md` §0: `openTranslationSession` only opens the reader's display store and must NOT be gated; mechanism differs from the original wording here, behavior contract unchanged.) Flip the Phase-1 test to green.
- **Exit:** Phase-1 D2/D3/D4 tests green; paid-call-count assertions exact; no regression in existing suite. Tag: `checkpoint/t917-p2-done`. Reports: `engineering/phase2-origins.md`, `review/phase2-verification.md`.

### Phase 3 — Consistency & money (Implementer)
- **D5** glossary-aware reuse: translation fingerprint input adds `glossaryVersion` (+ chunk-context identity) in `PageDecode.batchExpectedFingerprints`; `PageWorkPlanner` REUSE comparison reads it; resume planning bounds the extra calls.
- **D6** wallet fairness: interactive reservation share in `ProviderRequestGovernor`; reader-stream manual path wired to interactive priority (currently BACKGROUND); pause reasons surfaced; `shutdownAutoCoordinator` drains in-flight provider calls instead of cancelling mid-request.
- **D9** attempt ledger: durable "attempt started" record before each paid call; startup reconcile counts unresolved attempts; N-strike cap pauses chapter as "needs attention"; v2.1's fictional startup sweep stays deleted.
- **Exit:** D5 test (manual-early + batch-later → chosen repair policy), D6 cross-chapter starvation test, D9 simulated-death test — all green. Tag: `checkpoint/t917-p3-done`.

### Phase 4 — Lifecycle safety (Implementer; Technical Lead notes for D7 and D11)
- **D7** epoch + drain in `EngineLane.closeEngines`/`ChapterTranslator.stop`: close waits a bounded grace for reader work or hands it an epoch guard with exactly-one retry against the rebuilt engine. The "accepted trade-off" comment in `SinglePageHttpRenderPhase` is retired.
- **D8** occupancy watchdog: stall threshold flips reader states to "Translation stalled" (cancel affordance); result timer unchanged; no native kill.
- **D10** admission check: batch trigger cross-checks source page list vs downloads; choice dialog (finish download / translate subset, labeled partial); truthful `expectedPageCount`.
- **D11** persist outside the store mutex (riskiest — last, behind the ordered-writer design note): in-memory commit under lock; single ordered manifest writer; epoch guard against stale publication; remove native-permit-held flush where a low-risk window exists.
- **Exit:** D7 stop-race test green at every barrier; stall watchdog test; partial-download test; full suite green. Tag: `checkpoint/t917-p4-done`.

### Phase 5 — UI truth & copy (Product Lead defines, Implementer lands)
- Fold the **state→surface→copy appendix** (Director briefing table) into the v3.0 draft: chip/status/notification/progress mapping for every §7 outcome; visibility budget rules (silent self-healing, visible decisions).
- Progress totals from terminal states only (`BatchProgressReconciler`, notification); D12 copy corrections; D13 user-facing copy stops citing unmeasured numbers.
- **Exit:** UI-truth assertions green (UI state == durable state for each mode pair). Tag: `checkpoint/t917-p5-done`.

### Phase 6 — Measurement & promotion (Reviewer + Main Leader)
- Execute the §8.3 measurement protocol (device tiers, corpus, warm/cold, p50/p95/p99); record results.
- Full §8 oracle run (all phases' tests + isolation test).
- Main Leader synthesis → **Director sign-off** → flip v3.0 draft status to canonical → merge work branch to `main` → tag `checkpoint/t917-release`.

---

## 4. Delegation map

| Phase | Lead role | Support | Context to preload |
|---|---|---|---|
| 0 | Main Leader | Repository Steward | — (done) |
| 1 | Implementer | Technical Lead (harness design note) | role, README, PLAN §3 Ph1, draft §8, audit H-05 |
| 2 | Technical Lead (D1 note) → Implementer | Reviewer (acceptance) | role, README, PLAN §3 Ph2, draft §6 D1–D4, audit C-01..C-03, H-01 |
| 3 | Implementer | Reviewer | role, README, PLAN §3 Ph3, draft §6 D5/D6/D9, audit H-07, M-09/M-10, H-10 |
| 4 | Technical Lead (D7, D11 notes) → Implementer | Reviewer | role, README, PLAN §3 Ph4, draft §6 D7/D8/D10/D11, audit H-04/H-08/H-09, M-08/M-11 |
| 5 | Product Lead (success criteria) → Implementer | Reviewer (UI truth) | role, README, PLAN §3 Ph5, draft §7, audit M-04 UI rows |
| 6 | Reviewer (protocol run) | Main Leader (synthesis) | role, README, PLAN §3 Ph6, draft §8.3 |

## 5. Risk register

| Risk | Mitigation |
|---|---|
| D4 flips an existing test's meaning | Test change reviewed as contract change, not test fiddling; called out in `PHASE-LOG.md` |
| Harness flakiness poisons all phases | Phase 1 exit requires harness determinism (100 consistent local runs); barriers only, no sleeps |
| D11 (store mutex decoupling) is the riskiest change | Scheduled last; behind a design note + Reviewer sign-off; isolated to `ChapterTranslationStore` + document writer |
| Paid-provider tests must not call real providers | Harness barrier stubs the transport; governor tests use fake windows |
| Gradle/toolchain drift | Phase 0 records toolchain state; phase gates re-run module tests |
| Scope creep into downloader/reader fixes | README scope 'Out' list is binding |
| P2 carry-over (Reviewer, non-blocking): `patchPage` vs `publishLocked` candidate-grace asymmetry | Align in Phase 3/4 with regression test (batch registration between manual capture and commit, lease held → accepted) |

## 6. Definition of done (T917)

All §8 tests green including normal-manga isolation; every §7 outcome demonstrable in UI with truth assertions; D13 measurements recorded; `PHASE-LOG.md` complete; v3.0 draft promoted to canonical by Director sign-off and merged to `main` with `checkpoint/t917-release`.
