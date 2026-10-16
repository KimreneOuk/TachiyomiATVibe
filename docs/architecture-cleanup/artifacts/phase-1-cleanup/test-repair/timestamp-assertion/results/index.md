---
kind: spec
title: "P1.3a timestamp assertion repair — results and evidence"
comments: none
---

# P1.3a implementation results

Base: `architecture-cleanup` @ `a9c38fb5` (coordinator's reviewed P1.2 commit). Test-only change; production sink `app/src/debug/java/eu/kanade/translation/diagnostics/TranslationTraceFileSink.kt` untouched. Only editable source edited: `app/src/testDebug/java/eu/kanade/translation/diagnostics/TranslationDebugMeasurementTest.kt`. No commits/pushes/subagents; one Gradle owner.

# Production contract pinned before repair (read-only)

`TranslationTraceFileSink.prefixTimestamp` (lines 69-80): every unprefixed entry is written as `ts=<yyyy-MM-dd'T'HH:mm:ss.SSS> <payload>` (`SimpleDateFormat`, `Locale.US`, device-default timezone); lines already starting with `ts=` pass through unchanged. `appendDirect` (line 106) replaces `\n`/`\r` with spaces in the full (prefixed) line before appending. The Phase 0 XML confirmed the real shape: `ts=2026-10-14T23:10:30.806 event=run_start rid=r1` (device-local 23:10 vs 16:10 UTC — timezone must not be asserted).

# Exact change

`git diff --numstat`: **24 insertions, 1 deletion** in the single test file (52 → 75 lines); `git diff --check` exit 0. No import changes (`Regex` is default-imported).

1. Repaired `file sink preserves queue order and sanitizes embedded line breaks` (old lines 23-27): the direct unprefixed `readLines() shouldBe listOf(...)` expectation was replaced with — (a) `timestampedLines.size shouldBe 3`; (b) per-line validation that the output starts with `ts=dddd-dd-dd'T'dd:dd:dd.ddd ` via anchored `Regex("""^ts=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3} """)`, failing with the offending line if absent; (c) `removePrefix` of **only the validated match**; (d) exact full-payload list assertion preserving order and the newline-sanitization expectation (`event=stage_end stage=ocr forged=true`). No wall-clock, timezone, or substring-membership assertion anywhere.
2. Added the ticket-permitted optional case `file sink passes through already timestamped lines without re-prefixing` (lines 41-54): logs a fixed historical `ts=2000-01-01T00:00:00.000 …` line and asserts exact verbatim output — exercises the production `startsWith("ts=")` passthrough branch (sink lines 71-72) and guards against double-prefixing. The fixed timestamp is test input, not a clock assertion.
3. `session restart closes previous session and completion is idempotent` untouched; both `try { … } finally { sink.close() }` lifecycles preserved; no new files, dispatchers, clock interfaces, or production seams.

# Validation executed

All runs: `./gradlew.bat :app:test<Variant>UnitTest --tests "eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest" --rerun --console=plain --stacktrace` from repo root, DevDebug then StandardDebug sequentially per shell. `--rerun` forced fresh compilation + execution in every run.

| Run | Shell log (epic commands dir) | Exit | Result |
| --- | --- | --- | --- |
| Pre-change DevDebug | `8d255766-a7e0-4e6a-8ae6-4f435aea4fec` | 1 (expected) | BUILD FAILED in 55s; 2 tests, 1 failed — repaired method, `AssertionFailedError at TranslationDebugMeasurementTest.kt:23`; lifecycle test passed |
| Pre-change StandardDebug | same shell | 1 (expected) | BUILD FAILED in 56s; identical failure signature |
| Post-change DevDebug | `9f8a7a9d-49bc-4c73-a5aa-b397994c621b` | 0 | fresh `compileDevDebugUnitTestKotlin`; BUILD SUCCESSFUL in 59s; **3 tests / 0 failures / 0 skipped** |
| Post-change StandardDebug | same shell | 0 | fresh `compileStandardDebugUnitTestKotlin`; BUILD SUCCESSFUL in 26s; **3 tests / 0 failures / 0 skipped** |

Repaired-method comparison: the failing method moved from 2-variant `AssertionFailedError` (expected unprefixed, actual `ts=`-prefixed — XML failure text preserved) to passing in both variants with **stronger** assertions than the original. The 2→3 per-variant test delta is the optional passthrough case; the lifecycle test passed before and after. The other **five Phase 0 baseline failure methods are untouched** (different classes/packages; P1.3b not authorized and not started). No other tests were run; no retries — each variant ran exactly once per phase. (A first launch, shell `7d1aaca7`, died at exit 127 from a malformed `.\gradlew.bat` command in git-bash before Gradle started; relaunched as `8d255766` — no test execution, not a retry.)

No compiler warnings or errors reference `TranslationDebugMeasurementTest` in either post-change run.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Actual diff: `scratch/phase-1-cleanup/test-repair/timestamp-assertion/p1.3a-actual.diff`
- Pre-change XML: `scratch/phase-1-cleanup/test-repair/timestamp-assertion/pre-change/devdebug/TEST-…TranslationDebugMeasurementTest.xml` and `…/standarddebug/…` (failure messages show the actual `ts=`-prefixed lines)
- Post-change XML: `scratch/phase-1-cleanup/test-repair/timestamp-assertion/post-change/devdebug/…` and `…/standarddebug/…` (both `tests="3" failures="0" errors="0"`)
- Phase 0 cross-reference: `scratch/phase-0-baseline/narrow-triage-test-results/2026-10-14T12-43-14Z/` recorded the same mismatch in both flavors; left untouched
- User report re-hashed after all work: SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` — byte-identical

`git status`: exactly the one modified test file + the untracked user report. HEAD remains `a9c38fb5`.

# Limits and notes

- Narrow scope by ticket: only this class, only DevDebug + StandardDebug variants; no broad suites.
- The timestamp regex asserts format only; it cannot catch a wrong-but-well-formed timestamp (e.g., timezone skew) by design — asserting clock values is prohibited by the ticket.
- The sink's dropped-lines marker path (prefixed `trace_lines_dropped` record) is not exercised; pre-existing behavior, out of scope.
- `--rerun` recompiles the variant's test sources; other variants (release) untested, consistent with the debug-only sink (it lives in `src/debug`).

# Next

Slice complete; stopping for fresh Gemini/Antigravity review of the actual diff. P1.3b (mirror replacements) NOT started — awaits separate authorized scope per the ticket's preservation gate.
