# T917 Phase Log

Format per PLAN.md §2: each gate records what landed, the checkpoint tag, and test/toolchain status.

## Phase 0 — Baseline & stewardship — DONE (2026-09-01)

- Baseline commit on `main`: `9263b5f` — T914–T916 records, v2.1 superseded banner, v3.0 draft, T917 README/PLAN, doc cross-references.
- Anchor tag: `checkpoint/t916-audit-baseline` (annotated, on `main`).
- Work branch created and active: `t917/coexistence-v3`.
- Toolchain baseline: `./gradlew --version` → Gradle **8.12**, launcher JVM **21.0.10** (JetBrains Runtime via Android Studio).
  - **Environment note for all specialists:** `JAVA_HOME` is not set globally in this shell. Required export before any Gradle invocation:
    `export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- Module unit-test baseline: **deferred to Phase 1 start** (first Gradle test run of `:app` testDebugUnitTest for the translation packages; result recorded here before any production change).

### Gate
- Tag: `checkpoint/t917-p0-done`
- Exit criteria met: baseline tag ✔ · work branch ✔ · toolchain recorded ✔

## Phase 1 — Harness and failing tests — ACCEPTANCE IN PROGRESS

- Deliverables on branch `t917/coexistence-v3` (uncommitted at this entry): `coexistence/` harness (TranslationCoexistenceHarness / CoexistenceBarrier / FakeEngines), tests D2 (2 cases), D3, NormalMangaIsolationTest; D4 update to `TranslationManagerAutoArbitrationTest`; engineering notes (design + implementation log); review/phase1-verification.md.
- Verification (Main Leader independent + Implementer sweep): 1231 translation tests run; exactly 4 intentional RED, each failing fast with a defect-naming assertion — D2 batch→manual = C-01 (silent return, wait-and-attach missing); D3 = C-02 (skipped-never-rescanned → stranded); D4 = "same-chapter auto re-armed while the chapter batch was still queued (batch-lifetime suppression guard missing)". NormalMangaIsolationTest GREEN; neighbors GREEN; zero unexpected failures reported.
- **D4 contract-change callout (PLAN §5, Reviewer condition 3):** the test formerly named "manager keeps auto window active while the chapter batch is queued" is renamed to "manager suppresses same-chapter auto while the chapter batch is queued" and its assertions INVERTED. This is deliberate: the existing test encoded the behavior audit finding C-03/M-06 identified as the defect (auto re-arms during batch with no batch-lifetime gate in the re-arm path, `TranslationScheduler.kt:158-160`). The adopted D4 Recommendation (draft §6 D4) selects suppression as the contract; Phase 2 will make this test green by adding the batch-active guard. Recorded as a contract change, not a test fiddle.
- Reviewer verdict: ACCEPT-WITH-NOTES (`review/phase1-verification.md`) with binding conditions: (1) fix D2.2/D3 green-path ordering inversion — **DONE** (commit `d2f981b`; RED oracles byte-identical, re-run 3-red/1-green, no timeouts); (2) determinism soak 10 → 100 runs — **DONE** (see below); (3) D4 callout in this log — **DONE** (this entry).

### Determinism soak (Reviewer condition 2)
- Method: 100 consecutive Gradle invocations with per-task `--rerun` (forces real test execution — supersedes the earlier 10-run evidence, which could not prove execution vs up-to-date skips).
- Command: `./gradlew :app:testStandardDebugUnitTest --rerun --tests "eu.kanade.translation.coexistence.*"`; log: `engineering/phase1-determinism-soak.log`.
- Result: **100/100 identical outcome signature** — every run: 4 tests completed, 3 failed (D2 both methods, D3), 1 passed (NormalMangaIsolationTest); wall-clock 34–44 s; zero deviations, zero timeouts.

### Gate — Phase 1
- Tag: `checkpoint/t917-p1-done`
- Exit criteria met: harness merged ✔ · D2/D3/D4 red for the documented C-01/C-02/C-03 reasons ✔ · isolation + baseline green (1231 tests, only intentional reds failing) ✔ · determinism 100/100 ✔ · scope: test code only, no production changes ✔
- Deferred to Phase 2 gate: soak re-run against the GREEN (post-fix) test set to prove determinism of the passing choreography.

## Phase 2 — Intent-loss fixes (D1–D4) — ACCEPTANCE IN PROGRESS

- Part A commits: `d99a93b` (D1 three-origin leases + MANUAL-evicts-AUTO + D1OriginPriorityTest), `dd9f17b` (D4 suppression guard on manager auto-entry methods). Part B commits: `48ddee1` (D2 wait-and-attach: typed SinglePageOutcome, bounded store-observation attach, cancellation-safe), `37c0902` (D3 defer-and-rescan: listener-deferred pages, race-free lease-release waiters, in-pass COMPLETED-path rescan ≤2 attempts with re-acquired batch identity), `58afd34` (log).
- Reviewer: **ACCEPT-WITH-NOTES** (`review/phase2-verification.md`). Deep dive confirmed all four attack scenarios against the commit-fence change are fenced (lease-token re-check under the store mutex is airtight); sweep integrity XML-verified (161 classes / 1232 / 0 failed / 0 skipped).
- **D1 corollary — Director-visible contract record (Reviewer condition 1):** *Lease-over-plan precedence* is adopted as long-term semantics — while a boundary still owns the page lease, run-level plan identity (`candidateGenerationId`, `dependencyFingerprint`, `artifactPageVersion`) is advisory for its commit; the writer fences (generation, pageVersion, lease token, block fingerprints) stay fully armed, and every non-holder remains fail-closed. Reviewer recommendation over the narrower batch-side skip-re-planning alternative; adopted under the standing "Recommendation" decision pattern. The Director may override before Phase 3 (D5 reuse is built on this commit semantics); default stands otherwise.
- Gate condition status: (1) contract record — DONE (this entry); (2) clean-build sweep `:app:clean` + full translation suite → **1232/0, BUILD SUCCESSFUL (6m24s)** ✔ + 100-run determinism soak covering D1/D2/D3/isolation — IN PROGRESS (`engineering/phase2-determinism-soak.log`); (3) backlog — DONE (recorded in PLAN §5).
- Gate tag `checkpoint/t917-p2-done` WITHHELD until the soak completes identical.

### Phase 3/4 backlog (Reviewer findings, non-blocking)
- LOW: `patchPage` lacks the `record.candidate != null` grace that `publishLocked` has (`ChapterTranslationStore.kt:461-463` vs `:1483-1485`) — align at root with a regression test (batch registration between manual capture and commit, lease held → accepted).
- LOW (note): a manifest-only mutation without a generation bump can fail-closed reject the boundary commit (conservative direction: a retried page, never corruption) — acceptable; revisit if observed in practice.
