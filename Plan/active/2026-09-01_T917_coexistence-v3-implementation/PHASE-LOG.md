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
- Reviewer verdict: ACCEPT-WITH-NOTES (`review/phase1-verification.md`) with binding conditions: (1) fix D2.2/D3 green-path ordering inversion — IN PROGRESS (resubmitted to Implementer); (2) determinism soak extended 10 → 100 runs — PENDING (runs on final choreography after condition 1); (3) D4 callout in this log — DONE (this entry).
- Gate tag `checkpoint/t917-p1-done` is WITHHELD until conditions 1–2 are met and recorded here.
