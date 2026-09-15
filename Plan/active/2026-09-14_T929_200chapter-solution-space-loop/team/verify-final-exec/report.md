# Adversarial verification — EXECUTION_ORDER.md Phase 0 (items 0.1–0.4)

2026-09-14 · Verifier: red-team pass over Phase 0 of
`Plan/active/2026-09-14_T929_200chapter-solution-space-loop/report/EXECUTION_ORDER.md`
against the evidence base
`Plan/active/2026-09-14_T931_test-suite-bottleneck-audit/report/DIRECTOR_REPORT.md`.
Read-only method: git status/ls-files, grep, full file reads. Gradle/tests NOT run.
Every claim cites file:line. Paths are repo-root-relative.

Headline: **all four items are directionally correct; none is feasible exactly as
written.** Two have wrong mechanisms (0.4 latch; 0.3 "tools source set"), one
drops real coverage (0.2), one breaks a committed static guard as-is (0.1).
No attack overturns the plan's core claims (CI double-run, rig writes into Plan/,
sleep costs, probe is a genuine canary).

---

## 0.1 Promote `ManualRenderProbeBaseline.kt` as fast canary

**VERDICT: NEEDS-ADJUSTMENT** — valuable and safe to promote, but "promote
as-is" fails the repo's committed `checkTestRunBlocking` static guard. One-line
cure required (`= runBlocking<Unit> {`), matching the documented house cure.

### Evidence

| Attack question | Finding | Evidence |
|---|---|---|
| Untracked? | Yes, only source file untracked under app/ | `git status --porcelain`: `?? app/src/test/java/eu/kanade/translation/coexistence/ManualRenderProbeBaseline.kt` |
| Machine-local fixtures? | NONE. Fully in-memory: mockk Context, InMemoryPreferenceStore, `FakeChapterDocumentIo` artifact store, ByteArrayInputStream reader stream. No Plan/ path, no absolute path anywhere in the file | harness `create` TranslationCoexistenceHarness.kt:205-250; `artifactAuthorityStore` :871-914 (FakeChapterDocumentIo); `registerReaderStream` :1024-1028 |
| assumeTrue guards needed? | Not needed — no external fixture to be absent. Grep for absolute paths in app/src/test: empty | grep `C:\\\|/Users/\|/home/` → 0 hits |
| Deterministic? | Yes by design: "no sleeps, no polling — CompletableDeferred gates and StateFlow.first{} only"; all awaits inside `withTimeout` | TranslationCoexistenceHarness.kt:112-115, :156 (`AWAIT_TIMEOUT_MS = 10_000L`) |
| Writes files outside temp? | No. No File I/O in the probe at all | ManualRenderProbeBaseline.kt:1-32 (whole file) |
| Real canary or always-pass? | REAL: fails fast via `error()` if the manual job never registers; fails via `withTimeout` if render never reaches READY | :21-22 (`capturedManualJob` → harness :1042-1052 `error(...)` if unregistered), :23-25 (`state.first { renderStatus == READY }`) |
| Runtime / CI cost? | Bounded worst case 2×10s on a RED graph; happy path event-driven, expected seconds. The "10s+ per CI run" attack does NOT survive | harness :156 (10_000 ms), probe :22-25 |
| Code changes required to promote? | YES — expression-body `= runBlocking {` at :11 matches the guard regex and the allowlist is EMPTY | see below |

### The blocking defect: static guard

- ManualRenderProbeBaseline.kt:11 is
  `fun \`simple manual tap renders under artifact authority (baseline)\`() = runBlocking {`.
- The committed guard task scans src/test for exactly this pattern
  (app/build.gradle.kts:219 regex `=\s*runBlocking\s*(\{|$)`, "fun"-prefix test
  :230) and throws GradleException (:239-244); wired into `check` (:248-250).
- The allowlist is empty by policy: app/config/runblocking-allowlist.txt
  "STATUS (T910, 2026-08-29): EMPTY — every prior entry was cured (converted to
  runBlocking<Unit> …)" and forbids "new entries without an audit note".
- Cure is documented in the allowlist header itself: declare
  `runBlocking<Unit>` (or end the lambda with Unit). One-line change.

### SIDE FINDING (hidden coupling for GATE P0)

The guard is already red at HEAD independent of Phase 0: tracked, clean test
files match the pattern — D8StallWatchdogTest.kt:36,109,179 and
TranslationRequestGenerationFenceTest.kt:192 (verified against the committed
regex; app/build.gradle.kts is tracked/clean per full `git status`). CI never
runs `check` (workflows run `spotlessCheck assemble… test…` only,
build_pull_request.yml:44), so this goes unnoticed. **GATE P0 ("full suite
green") must be defined over test tasks, not `check`, or Phase 0 fails on day
one for pre-existing reasons.**

---

## 0.2 CI de-dup: drop `testReleaseUnitTest`, keep `testStandardReleaseUnitTest`

**VERDICT: NEEDS-ADJUSTMENT** — the double-run is real, but the chosen
replacement silently drops ALL dev-flavor CI coverage. The actual waste is the
standard variant running twice; the dev run is not duplicate work.

### Evidence

| Attack question | Finding | Evidence |
|---|---|---|
| Both workflows affected? | Yes, lines verified | build_pull_request.yml:44, build_push.yml:39 |
| Flavors | `standard` + `dev` on dimension "default" | app/build.gradle.kts:104-116 |
| Flavor-specific test source set? | NO — only `app/src/test` exists (no testDev/testStandard) | `ls app/src` → androidTest, debug, dev, main, standard, test |
| Tests referencing BuildConfig / dev-only code? | NO — grep BuildConfig in app/src/test: 0 hits; grep FirebaseConfig in app/src/test: 0 hits | grep output |
| What does `testReleaseUnitTest` cover? | It is the build-type aggregate over BOTH flavors' release unit-test tasks (standard + dev), incl. dev-variant main compilation. T931 asserts the aggregate semantics | DIRECTOR_REPORT.md:31-35; AGP build-type aggregate convention |
| What is lost by dropping it? | (a) compilation of the dev flavor's only Kotlin source, app/src/dev/java/mihon/core/firebase/FirebaseConfig.kt (a 13-line no-op stub — low severity TODAY); (b) the shared suite's compile+run against the dev classpath; (c) parity with the documented local dev workflow | app/src/dev tree; CLAUDE.md:17 (`./gradlew :app:testDevDebugUnitTest`) |
| Identical source set for standard? | Yes — test sources are shared; only the MAIN classpath differs per flavor | ls app/src (single test dir) |

### Concrete adjustment

Replace the line with **`testDevReleaseUnitTest testStandardReleaseUnitTest`**
(explicit, provably identical coverage to today, still removes the duplicate
standard run — the actual 3–7 min waste), or keep `testReleaseUnitTest` alone
if the aggregate semantics are trusted. `testStandardReleaseUnitTest` alone is
the one option that loses coverage. Note app/src/dev contains no test sources,
so "hides a dev-variant test-source compile break" does NOT apply — the loss is
dev-flavor *main* compile coverage.

---

## 0.3 Evict `Page15MockRig.kt` → "tools/dev source set"

**VERDICT: NEEDS-ADJUSTMENT** — eviction is justified and the T931 claims all
verify, but the named mechanism does not exist, and the obvious alternative
(separate Gradle module) is compile-infeasible due to `internal` visibility.

### Evidence

| Attack question | Finding | Evidence |
|---|---|---|
| Tracked? | YES — despite self-declaring "NOT committed, NOT a regression test" | `git ls-files` → tracked, clean; Page15MockRig.kt:21-22 |
| Fixture dependency | Walks up to 5 parents from `user.dir` for `Plan/active/2026-08-30_T912_text-layout-renderer/engineering/fixtures` | :44-55, path at :49 |
| Skip guards? | PARTIAL: `run` and `planningCost` skip via `Assumptions.assumeTrue(fixturesPresent())`; **`randomizedStress` has NO guard and runs in CI today** (fully synthetic, fixed seed 20260901L, zero assertions) | :275, :719 vs :747-748, :750 |
| Writes into Plan/? | YES — `outDir = fixtureDir/rig-out`, mkdirs + 4 SVG writes (only when fixtures present, i.e. local machines) | :57-58, :276, :417-418, :662-663 |
| What does it assert? | NOTHING. All three @Test methods are println diagnostics; can only "fail" via uncaught exception. Not a canary — the plan does not claim it is | full read |
| Does a tools source set/module exist? | NO. settings.gradle.kts modules :41-53 contain none; root `tools/` is Python + ONNX only (aot_conversion, mangaocr_lab, …); app/build.gradle.kts sourceSets :99-102 only remaps preview/benchmark res | settings.gradle.kts:41-53; `find tools -maxdepth 2` |
| Does the rig reference test-private or internal code? | It uses four `internal` PRODUCTION symbols: `AdaptiveBandPlanner` (AdaptiveBandPlanner.kt:60), `AdaptiveResult` (:30), `TextLayoutTuning` (TextLayoutPlanner.kt:373, called :165), `MaskTextRegionPlanner` (MaskTextRegionPlanner.kt:64, called :517); calls `fitAdaptiveBands` at :979. Internal = visible from the :app test compilation, INVISIBLE from any separate module | visibility grep |

### Mechanism ranking (safest first)

1. **Move the file to a non-compiled location** (e.g. `tools/dev/Page15MockRig.kt`
   + a README noting it must be dropped back under app/src/test to run). Zero
   new build surface; cost: the 1,018-line rig stops compiling continuously and
   bit-rots silently against the internal APIs — acceptable for a self-declared
   scratch probe with zero assertions.
2. **In-module dedicated source set with a dev-only task** — keeps it compiling
   but adds sourceSet/compilation surface to :app's build script (the module
   everything depends on). NOT "zero risk"; needs its own review.
3. **Separate Gradle module — BLOCKED**: cannot see `internal` symbols above
   without widening production visibility (a real regression vector).

Also fix the stale KDoc (:21-22 "NOT committed") during the move.

---

## 0.4 De-flake three sites

**VERDICT: NEEDS-ADJUSTMENT (per site).** Site 1's named mechanism
("park-detection latch") is infeasible; site 2's "awaitUntil" fits only half
the cases; site 3's `job.join()` is type-correct but is a restructure, not a
swap, and does not remove all sleeps.

### Site 1 — TranslationRequestGenerationFenceTest.kt:244 (150 ms lock race)

- Confirmed: `synchronized(mutationLock) { callback.start(); Thread.sleep(150);
  manager.cancelTranslationRequest(10L) }` at :241-246; lock is a plain `Any`
  injected by the fixture at :288.
- **A park-detection latch CANNOT be built**: the callback blocks on a raw
  `synchronized` monitor inside production TranslationManager — there is no
  hook where a CountDownLatch could be counted down, and a plain monitor object
  cannot observe park events. Any latch fix requires editing production code,
  which Phase 0's "zero behavior risk" forbids.
- Correct deterministic fix: `awaitUntil { callbackThread.state == Thread.State.BLOCKED }`
  before cancelling while holding the lock. Once BLOCKED on the monitor the test
  holds, the in-lock cancel strictly happens-before the callback's critical
  section. Deterministic and bounded. (This file has no awaitUntil helper; two
  identical private copies already exist elsewhere and could be shared.)
- Impact of today's sleep: on a slow machine the intended interleave degrades
  to "callback runs after everything" — the assertions at :249-251 still hold in
  both orders, so this is silent coverage loss, not a flaky failure. The test
  guards cancel-vs-callback fencing, so a botched "fix" that just deletes the
  sleep converts it to always-green. No other sleeps in the file (full read).

### Site 2 — MangaScreenModelMultiSelectBatchTest.kt:410 (250 ms blind settle)

- Confirmed: `settleProbe() = Thread.sleep(250)` at :409-411, exactly 2 call
  sites: :376 (positive, after `awaitUntil { admitted.isCaptured }` at :375) and
  :394 (negative: `verify(exactly = 0) { … }`). No other blind sleeps in the
  file (:453 is awaitUntil's own 10 ms poll).
- `awaitUntil` exists in-file at :447-455 — the plan's "existing awaitUntil" is
  factually right but fits only the positive case. It CANNOT express the
  negative case (awaiting an absence), and there is no virtual dispatcher to
  drain: `Dispatchers.setMain(mainThreadSurrogate)` where the surrogate is
  `Executors.newSingleThreadExecutor().asCoroutineDispatcher()` (:92, :156) — a
  REAL thread, so `advanceUntilIdle` is unavailable.
- Correct fix: deterministic quiesce — join the model's scope children
  (`model.screenModelScope` is voyager's public scope; MangaScreenModel launches
  all probe work there, MangaScreenModel.kt:183,208,219) inside a
  `withTimeout(5_000)`, then verify. Caveat: children snapshot races a late
  nested launch; a two-stage "quiesce, check, quiesce" or keeping one bounded
  awaitUntil on the positive observable is pragmatic.

### Site 3 — DownloadCacheRenewalGuardTest.kt:100-215 (sleep/poll → job.join())

- `renewalJob` IS a joinable `kotlinx.coroutines.Job?` — reflection-read with
  `as? Job` at :110-113; production field DownloadCache.kt:97.
- join() is sound after `invalidateCache()` (:154,:171) because
  invalidateCache → renewCache assigns the field synchronously
  (DownloadCache.kt:308-313 → :324). The INITIAL renewal (constructed cache,
  tests' first `pollDownloaded`) races the init scope — a bounded "field
  appears" wait must REMAIN; join alone does not replace :112-117.
- The unconditional `Thread.sleep(100)` at :121-122 exists because
  `invokeOnCompletion` → `notifyChanges()` → non-cancellable
  `_changes.send` + `updateDiskCache` run AFTER job completion
  (DownloadCache.kt:415-432). Current assertions read only the in-memory index,
  which is updated INSIDE the job body, so the sleep is droppable for tests 1-2
  — but that equivalence must be verified, not assumed.
- Where the "~16s" lives: test 3's first session burns its full 10_000 ms
  negative deadline BY DESIGN (:189-192 + :135-145 loop), plus disk-file polls
  (:125-133, :204-210). Removing it requires RESTRUCTURING `pollDownloaded` to
  "await one completed renewal, then assert once" (valid: the empty-index
  renewal succeeds and commits a truthful empty index), not a literal join swap.
  Disk polling cannot be joined. Renewal's 30 s source-init wait
  (DownloadCache.kt:330-336) is neutralized by mocked-initialized managers.
- Other sleeps in the file, all enumerated: :115, :119, :122, :130, :142, :209.
  Class-level `@Timeout(60s)` at :31 bounds everything.

---

## Attacks that survive scrutiny (ranked by regression risk to the app)

1. **0.4 site 1 mechanism is wrong and the test is high-value.**
   "Park-detection latch" cannot exist without editing production
   TranslationManager (raw `synchronized` on a fixture-injected `Any`,
   TranslationRequestGenerationFenceTest.kt:241, :288). A lazy fallback
   ("just delete the sleep") silently neuters the cancel-vs-callback fencing
   regression test because both interleaves satisfy :249-251. Use
   thread-state awaitUntil (BLOCKED) instead.
2. **0.1 as written breaks a committed guard; the guard is already red at
   HEAD.** Promotion must include the one-line `runBlocking<Unit>` cure
   (allowlist policy forbids new entries without an audit note,
   app/config/runblocking-allowlist.txt). Separate side finding:
   checkTestRunBlocking already fails at HEAD on 4+ tracked methods
   (D8StallWatchdogTest.kt:36,109,179; TranslationRequestGenerationFenceTest.kt:192);
   CI never runs `check`, so define GATE P0 over test tasks and fix or
   consciously accept the pre-existing guard debt.
3. **0.2 drops dev-flavor CI coverage.** `testStandardReleaseUnitTest` alone
   stops CI from compiling app/src/dev (FirebaseConfig.kt stub) and from
   running the suite against the dev classpath; CLAUDE.md:17 makes dev-flavor
   tests the documented local workflow, so CI drifts from it. Low severity
   today (13-line no-op stub), grows if T930 lands flavor-adjacent code. Use
   `testDevReleaseUnitTest testStandardReleaseUnitTest`.
4. **0.3's "tools source set" is new build surface that does not exist, and the
   module variant is compile-blocked by `internal` visibility**
   (AdaptiveBandPlanner.kt:60, TextLayoutPlanner.kt:373, MaskTextRegionPlanner.kt:64,
   AdaptiveBandPlanner.kt:30). "Zero risk" only holds for the
   move-to-non-compiled-location variant, which accepts bit-rot.
5. **0.4 site 2: awaitUntil is the wrong primitive for 2 of the affected
   assertions** (negative verifies; real-thread Main surrogate, :92/:156 — no
   virtual time to drain). Wrong replacement = 5 s timeout per test or no
   added determinism.
6. **Minor, surviving:** the promoted probe duplicates scenarios already
   covered by D-suite tests using the same harness (tapManual path), so its
   marginal value is "one fast smoke", not new coverage; and the harness's DBG
   printlns (e.g. TranslationCoexistenceHarness.kt:272, :516-518, :577) will
   add CI log noise. Neither blocks promotion.

### Attacks that did NOT survive

- "Probe needs machine fixtures / is a probe that always passes / costs 10s+
  per run" — refuted (in-memory harness; real failure oracles; event-driven,
  bounded 2×10 s worst case).
- "Rig eviction hides a failing canary" — refuted (rig asserts nothing).
- "Rig is untracked" — T931's framing is stale: it IS tracked
  (`git ls-files`); only the probe is untracked. Eviction is a move, not a
  deletion of an untracked file.
- "CI runs the suite twice" — CONFIRMED as claimed (build_pull_request.yml:44,
  build_push.yml:39, aggregate semantics per DIRECTOR_REPORT.md:31-35).
