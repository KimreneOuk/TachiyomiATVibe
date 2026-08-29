# T910 — checkTestRunBlocking static guard

Date: 2026-08-28
Branch: `optimize_translation_finishing_page` (no commits; changes left in working tree)

## What was added

### 1. Guard task — `app/build.gradle.kts`

- `app/build.gradle.kts:192-199` — comment block explaining the quirk (T906 audit,
  area3-lifecycle-ui-tests.md section U3).
- `app/build.gradle.kts:201` — `val testRunBlockingAllowlist = file("config/runblocking-allowlist.txt")`.
- `app/build.gradle.kts:203-244` — `tasks.register("checkTestRunBlocking")`
  (group `verification`):
  - inputs: all `app/src/test/**/*.kt` + the allowlist file.
  - pure Kotlin `doLast` file walk (no shell-outs; works on Windows/Unix).
  - a file is exempt when its path relative to `app/` (forward slashes) contains any
    allowlist entry as a substring.
- `app/build.gradle.kts:247-249` — `tasks.named("check") { dependsOn("checkTestRunBlocking") }`.

### 2. Exact pattern matched

Per line, regex (verbatim from the task spec):

```
=\s*runBlocking\s*(\{|\s*$)
```

i.e. `= runBlocking {` or `= runBlocking` at end of line. A match is reported only when
the text before the match contains `fun` — so only function expression bodies are
defective; bare statement forms (`runBlocking { ... }`) and `val x = runBlocking { ... }`
are safe (the method itself returns void) and are not flagged. The cure form
`= runBlocking<Unit> { ... }` does not match the regex at all (`<Unit>` sits between
`runBlocking` and `{`), so fixed code passes by construction.

Failure output per offender: `file:line` + one-line defect/cure explanation,
then `GradleException` with the total count.

Example:

```
src/test/java/eu/kanade/translation/scheduling/RunBlockingGuardProbeTest.kt:6: expression-body `= runBlocking` lets the
compiler infer the test's JVM return type; a non-Unit result is silently skipped by JUnit.
Declare runBlocking<Unit> (or extend app/config/runblocking-allowlist.txt with an audit note).
```

### 3. Allowlist — `app/config/runblocking-allowlist.txt` (new, checked in)

Header explains why (silent JUnit skips for non-void inferred returns, found by the
T906 bytecode sweep) and the cure (`runBlocking<Unit>`). Entries (path substrings):

| Entry | Status |
| --- | --- |
| `scheduling/RollingAutoCoordinatorTest.kt` | 31 bare matches; 8 methods confirmed silently skipped (T906 U3). **Remove after T909/T910 deadlock investigation resolves the 8 skipped tests and every method declares `runBlocking<Unit>`.** |
| `translation/TranslationManagerReaderTeardownTest.kt` | 2 bare matches; currently Unit-inferring and running (broken ones already fixed at 2bd73b3). Remove under T910 once converted. |
| `translation/TranslationManagerAutoArbitrationTest.kt` | 1 bare match; same status/removal. |
| `translation/TranslationManagerDownloadFailureRecoveryTest.kt` | 1 bare match (line 120); same status/removal. |
| `core/migration/MigratorTest.kt` | 6 bare matches; same status/removal. |

Note: the task brief named only RollingAutoCoordinatorTest.kt as seeded entry, but the
pattern also occurs in the four other files above; all current occurrences had to be
allowlisted for the guard to pass on the current tree (as required). Format: one path
substring per line, `#` comments/blank lines ignored. Conversion of the non-Rolling
files is low-risk style work for T910; the entries say so explicitly.

## Verification

All runs with `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.

(a) Current tree passes:

```
$ ./gradlew :app:checkTestRunBlocking --console=plain
> Task :app:checkTestRunBlocking
BUILD SUCCESSFUL in 38s
10 actionable tasks: 1 executed, 9 up-to-date
```

(b) Probe file `app/src/test/java/eu/kanade/translation/scheduling/RunBlockingGuardProbeTest.kt`
containing `fun \`t\`() = runBlocking { 1 }` (plus a safe block form and a
`runBlocking<Unit>` form) — task FAILED and named the defective line(s):

```
* What went wrong:
Execution failed for task ':app:checkTestRunBlocking'.
> checkTestRunBlocking found 2 risky expression-body runBlocking test declaration(s):
  src/test/java/eu/kanade/translation/scheduling/RunBlockingGuardProbeTest.kt:6: ...
  src/test/java/eu/kanade/translation/scheduling/RunBlockingGuardProbeTest.kt:8: ...
BUILD FAILED in 8s
```

Line 6 is the planted defect. Line 8 (`fun f(): Unit = runBlocking { println(1) }`) is
also the bare expression form and is flagged by design — the regex from the spec matches
it; the accepted style is `runBlocking<Unit>`. Line 10 (`= runBlocking<Unit> { 1 }`) was
correctly NOT flagged. After deleting the probe file: `BUILD SUCCESSFUL in 8s` again.

(c) No other task broke; wiring confirmed:

```
$ ./gradlew :app:tasks --all --console=plain | grep -i checktest
checkTestRunBlocking - Fails when a unit test uses the expression-body `= runBlocking {` form, ...

$ ./gradlew :app:check --dry-run --console=plain | grep -iE "checkTestRunBlocking|^:app:check "
:app:checkTestRunBlocking SKIPPED
:app:check SKIPPED
```

(`check`'s task graph includes `checkTestRunBlocking`.)

## Constraints honored

- No test source modified; `RollingAutoCoordinatorTest` untouched.
- No commits; working tree contains exactly `M app/build.gradle.kts` and new
  `app/config/runblocking-allowlist.txt` (plus this report).
- Cross-platform: pure Kotlin DSL, `kotlin.text`/`java.io` only; paths normalized to
  forward slashes before allowlist matching.
