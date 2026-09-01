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
