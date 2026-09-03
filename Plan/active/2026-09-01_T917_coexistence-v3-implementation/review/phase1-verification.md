# T917 Phase 1 — Reviewer Verification Record

VERDICT: ACCEPT-WITH-NOTES

Scope accepted: harness + RED tests are real, deterministic, and fail for the documented
reasons; the isolation gate is GREEN. Three binding conditions below must be closed before
`checkpoint/t917-p1-done` is tagged (one is a test-choreography edit that preserves today's
RED evidence; none changes production code).

## Checklist

| Item | Verdict | Evidence |
|---|---|---|
| A. Scope | VERIFIED | `git status --short`: only `M app/src/test/java/eu/kanade/translation/TranslationManagerAutoArbitrationTest.kt` + untracked `app/src/test/.../coexistence/` (6 .kt files) and `Plan/active/2026-09-01_T917_*/engineering/`. `git diff --stat HEAD`: 1 file, +48/−33, under app/src/test. Zero production changes. |
| B. Fakes only at sanctioned externals | VERIFIED | ONNX: `FakeRecognitionEngine` (FakeEngines.kt:94-120) + batch ctor lambdas (harness :313-374). Transport: `FakeTransportTranslator` (FakeEngines.kt:138-173). Disk/render shims: `mockkObject(PageDecode)` + `RenderColorEstimator` (harness :785-800), `cleanedPublication` mock (:285, :447). Prefs: `InMemorySharedPreferences` (:152-154). Relaxed Context/provider/sourceManager mocks are the sanctioned uninitializedManager recipe (:507-526). All shims beyond note §1.2 are documented — see Deviations. |
| B. Reflection centralized, renames fail loudly | VERIFIED | All field wiring in one factory `create()` as (name,value) lists (harness :228-247, :430-456, :508-526); `setField` walks hierarchy then throws `NoSuchFieldException` (:564-577). Two named one-off sets also fail loudly (`batchTrackerFactory` :461-466, `translationJob` :743). |
| C. Determinism mechanics | VERIFIED | grep for `sleep(`/`while (!`/`advanceUntilIdle`/`runTest(` over coexistence/ + arbitration test: zero hits. Gates are `CompletableDeferred` (CoexistenceBarrier.kt:63-124) and `StateFlow.first{}` (:87-92); awaits bounded by `withTimeout` (AWAIT_TIMEOUT_MS=10s, NEGATIVE_PROBE_MS=2s, harness :134-141); all scopes real `Dispatchers.IO/Default` (harness :193, :460, :506, :741, :744); arm-before-launch discipline in tests (D2 test :50-60). |
| D. Target-contract oracles | PARTIAL | D2.1 oracle is the target contract and would turn green: manual must still be running while batch owns the page (D2 :74-85, negative join probe), lease BATCH while parked (:62-64), end-state via store/lease/transport counts (:98-106). D4 oracle correct and green-able: `autoSnapshot` stays null during queue lifetime, re-arms after drain (arbitration diff, negative `autoWindowReArmed()` probes + final re-arm). RED messages name C-01/C-02/D4 — re-executed by this Reviewer: D2.1 C-01, D2.2 C-02 (stranded p1), D3 C-02 (stranded p1), D4 message quoted in log §3.4 all reproduced verbatim from JUnit XML. CRITICAL SUB-CHECK FAILS for D2.2/D3 — see Finding 1. |
| E. Isolation test real + GREEN | VERIFIED | Re-ran `coexistence.*`: NormalMangaIsolationTest passed (0 failures). Real oracles: no coordinator (negative probe, :70-81), no store opened (`observeActiveDisplayStore` null :85-88, `selectActiveStore` empty :89-94), and decode/transport positive controls that would catch any stray decode (arrivalsOf(NATIVE_ACQUIRE,"p0")==1 :103-105, totalCalls()==1 :106-109 — a disabled-chapter decode would bump the "p0" count because the enumeration stub is global). The per-key `disabled-*` checks (:99-102) are vacuous but harmless. |
| F. D4 is a documented contract change | PARTIAL | Rename + intent unambiguous (KDoc in diff cites draft §6 D4, M-06, RED rationale); PLAN §5 row exists (PLAN.md:104). BUT the required `PHASE-LOG.md` callout is missing — PHASE-LOG.md (17 lines) has no Phase-1 entry. See Finding 3. |
| G. Determinism evidence | PARTIAL | Log §6: 10/10 identical (4 tests, 3 failed, isolation passed). My independent run reproduced the exact signature. The recorded acceptance bar is 100 consistent runs (PLAN.md:105; note §2) — 10 is below it. See Finding 2. |
| H. Run commands reproducible | VERIFIED | `:app:testStandardDebugUnitTest` is a real task (flavors `standard`/`dev`, app/build.gradle.kts:106-111); filters correct; JAVA_HOME export recorded (note §4; PHASE-LOG.md Phase 0). Reviewer executed two of the logged commands successfully (~40s, ~32s). |

## Findings

1. **HIGH / defect — D2.2 and D3 cannot turn green as choreographed (checklist D sub-check).**
   Both tests await `batch.reconciliation` and assert `strandedPages` empty / chapter
   TRANSLATED / tracker 2/2 **while the reader-owned page is still parked at PROVIDER_END,
   lease held, render not committed** (D2 :147-163 before release :166; D3 :101-127 before
   release :131). The adopted Phase-2 design is "rescans after lease release within the pass;
   reconciliation only after rescan" (PLAN.md:59) — the rescan can only run after the manual
   releases, so in the green state the reconciliation await hits the 10 s bound and the test
   fails by timeout instead of passing. The tests' own doc comments state the contract
   (D3 :24-26) that the choreography then violates. Fix is choreography-only and preserves
   today's RED evidence: move the reconciliation/tracker assertions to after
   `release(PROVIDER_END,"p1")` + `joinAll` — today the reconciliation completes early (plain
   skip, no waiting), so the await still returns the stranded result and the failure message is
   byte-identical, only later in the test body. Re-run the red evidence after the edit.
2. **MEDIUM / design-limitation — determinism run count below the recorded bar.** Log §6 ran
   10/10 identical; PLAN.md:105 and note §2 require 100 consistent local runs before the tag.
   Condition: run the remaining loop (single bash for-loop, ~1 min per invocation) and record
   the result in the log before tagging.
3. **MEDIUM / defect (documentation) — D4 contract-change callout missing from PHASE-LOG.md.**
   PLAN.md:104 requires it ("called out in `PHASE-LOG.md`"); the file has no Phase-1 entry yet.
   Condition: add the Phase-1 gate entry (what landed, D4 contract-change callout, test command
   + result per PLAN §2) before tagging.
4. **LOW / expected — withClue text inaccuracy.** D2 :175-176 says "manual translated p0 once"
   but the manual tapped p1 (p0 was the batch's free page); the assertions themselves
   (`callsFor("p0")==1`, `callsFor("p1")==1`) are correct. Message-only; may be fixed with the
   Finding-1 edit.
5. **LOW / design-limitation — green-state latency.** Negative probes add fixed cost when the
   tests flip green: D2.1 3×2 s, D4 2×2 s. Acceptable; no action.

## Deviations from the design note the implementation log missed

All are documented in harness/fake KDoc but absent from phase1-implementation-log.md §4:

- **Manual entry point**: note §3.1 specified `manager.translatePage`; the harness drives
  `scheduler.translatePage` directly because `readerTeardown` is a computed property with no
  backing field (harness :498-504, :694-703). The C-01 defect path is downstream of both, so
  the red test still targets the right defect. Acceptable; record in the log.
- **`CapturingJobMap` replaces the real `activePageJobs` map** (harness :474-483) to capture
  the manual job at registration without polls — a test-observation shim on a production field
  the note did not list.
- **Fresh-precondition read in `publishCleanedThroughStore`** (harness :609-618): production
  uses the worker's captured precondition across a ~100 ms encode window; the harness re-reads
  at patch time because instant fakes make the stale-precondition rejection deterministic.
  Sound rationale, but it means precondition-rejection behavior is not exercised by this
  harness — relevant when Phase 2/3 touch guarded writes.
- **Per-page lane-serialization gates** (`transportStarted`/`nativeStageDone`, harness
  :181-191, :336-350, :645-650): an ordering mechanism the note's §2 did not define; enforces
  the production-typical publish→paid-call order per page. Purely choreographic (no production
  collaborator is faked). Acceptable.
- **Bitmap stand-in** is `mockk(relaxed)` with stubbed `byteCount` (FakeEngines.kt:36-46), not
  the note's Unsafe Bitmap — the log §4.5 does mention this one; listed for completeness
  because `HeldBitmapRegistry.holdCleaned` reads `byteCount` unguarded.

## Conditions for `checkpoint/t917-p1-done`

1. Close Finding 1 (re-order D2.2/D3 reconciliation asserts after manual release+join; verify
   RED messages unchanged by re-running the coexistence filter).
2. Close Finding 2 (determinism loop extended to 100 identical runs; record count in log §6).
3. Close Finding 3 (PHASE-LOG.md Phase-1 gate entry incl. the D4 contract-change callout).

Reviewer note on G: 10 runs are insufficient against the plan's own bar; the 100-run
requirement stands as written and is cheap to satisfy mechanically.
