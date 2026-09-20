# T936 Phase 4 — Independent Review Report

Date: 2026-09-20
Reviewer: Independent Reviewer (adversarial verification; every extraction commit re-diffed
line-level against its origin with normalized matching; sizes, sequencing, seams, APK, and
test XMLs re-derived — implementer claims not trusted)
Branch reviewed: `t936/phase4-monolith-decomposition` @ `72fca1b`, base `main` @ `56ed8ae`
(merge-base verified). 12 commits; net 17 files, +6,721/−4,428.

## Verdict

**FAIL** (single finding, scoped to P4-02 extraction completeness — see Item 2)

Everything else passes: the zero-test-diff safety case holds, all eleven extractions that
WERE performed are verified pure moves, the coordinator/controller structure and seams meet
their tickets, safety invariants are untouched, and the evidence is green. Per the review
directive, the P4-02 relaxation-loop omission is a FAIL because the skipped span is
**provably pure-move-extractable** — the exact condition the directive defines for FAIL —
and the report does not disclose the shortfall against the ticket's enumerated spans and
size target.

---

## Item 1 — Zero-test-diff + pure-move verification: PASS

(a) `git diff main...HEAD --name-only -- app/src/test` → **empty** ✓. Only production files
and Plan docs changed; reader/rendering/batch test suites are byte-identical to main.

(b) Per-commit normalized pure-move audit. For each of the 10 extraction commits I built a
whitespace-normalized multiset of every line before (`coordinator/planner/viewmodel @ ^`)
and after (`same files` + `new worker/geometry/controller files`), additionally normalizing
`context.`/`this.` prefixes (the Context data-class pattern), then reviewed everything that
still did not match:

- **7 worker commits:** 88-96% of removed lines reappear verbatim; every remaining net-new
  line is structural — `…Context` data classes (explicit state passing, per ticket),
  delegate properties (`private val store … get() = store`), factory functions
  (`private fun preflightWorker() = PreflightWorker(…)`), continuation lambdas that forward
  to the coordinator's own next-phase methods, qualified-name updates
  (`ChapterProfileBatchCoordinator.COUNTER_ENVELOPES_PENDING to fresh.envelopes.size` —
  spot-verified in EnvelopeDispatcher.kt:453, `MAX_DISPLAY_TAIL_DRAIN_PASSES` in
  RecoveryWorker.kt:433), and signature re-wrapping. **Zero logic edits, no reordering, no
  dropped branches, no default-value changes found.**
- **f48c280 (MaskGeometryClustering):** 6 net-new / 20 net-removed; the single content delta
  is a KDoc wording tweak. Textbook pure move.
- **b949493 (FontFittingAlgorithms):** the only "logic-ish" deltas are
  statement-body→expression-body conversions of identical expressions
  (`computeStrokeWidth` = `max(MIN_STROKE_PX * scale, fontSizePx * STROKE_WIDTH_FRACTION)`
  on both sides) plus moved constants. Verified mechanical.
- **cc80183 (controller):** net-new lines are 14 back-bridged delegate properties
  (`set(value) { owner.translationStoreJob = value }` — moved bodies keep read/writing
  ViewModel state), controller-class scaffolding, and delegation; the removed-side lines are
  the original declarations. `eventChannel.trySend(Event.RefreshTranslationPages(…))` sites
  moved with only the `ReaderViewModel.Event.` qualification (5 sites verified in the
  controller).

Safety case supported end-to-end: pure moves + **zero test modifications** + full suites
green (Item 6) — the batch ordering/resume/display-tail/T934 contracts that pin behavior
were not touched and pass.

## Item 2 — P4-02 extraction completeness: **FAIL** (the one blocking finding)

Measured outcomes: `TextLayoutPlanner.kt` 3,605 → **3,470 raw / 3,272 non-blank** (−333
lines; ticket target "< ~1,500" missed by >2×). Extracted: `MaskGeometryClustering.kt`
(114 lines) and `FontFittingAlgorithms.kt` (270 lines; contains the CJK wrapping kernels —
`cjkWrap`, `binarySearchFontSize`, `shouldRenderVertical`, vertical orientation/glyph
mappings — so the CJK half of the audit's span list IS done). What was **not** extracted is
the ticket's enumerated collision-relaxation machinery, which I assessed for extractability
as the directive requires:

- `resolvePostAnchorPlacement` (L1611-1975, ~365 lines) takes **25 explicit parameters**;
  `growIntoFreeSpaceIfNeeded` (L2425-2675, ~250 lines) takes 15; both mutate ONLY locals
  (`var bx/by/bw/bh/sw/sh/font`), and the class-body `val` hits in the planner are nested
  data-class constructors, not instance mutable state.
- `placed: List<PlacedFootprint>` is used read-only inside the span (collision checks and
  iteration — Kotlin read-only `List`); `RescueBudget` is never mutated inside the relaxation
  span (decrements live at external call sites); `containedReflowRescue` operates purely on
  its passed block/spans/region parameters.
- External references are pure helpers (`inkRectOf`, `containedIn`, `overflows`), the
  `TextMeasurer` interface, and companion/tuning constants — all carried trivially by the
  same Context-object pattern this phase applied ten times, including across a 4,256-line
  coordinator. **There is no shared mutable state with planner session/DTO-assembly context.**

**Conclusion: the relaxation loop is provably pure-move-extractable and was not extracted.**
Per the directive's decision rule this is a FAIL. Specific spans requiring extraction
(current line numbers in `TextLayoutPlanner.kt`):

1. L1481-1531 — `hardCellsDisjoint`, `footprintCollides`, `translateRect`, `translateLayout`, `SHIFT_LEFT/RIGHT/UP/DOWN`
2. L1533-1610 — `minShiftDisplacement`
3. L1611-1975 — `resolvePostAnchorPlacement` (core relaxation resolver)
4. L1976-1990 — `freeRectDisplacement`
5. L1991-2033 — `fitIntoFreeRect`
6. L2330-2424 — `resolveMinimalDisplacementX`, `freeSpaceLeft/Right/VerticalUp/VerticalDown`
7. L2425-2675 — `growIntoFreeSpaceIfNeeded`
8. L2676-2771 — `boundedFreeTextWideningPlan`
9. L2772-2794 — `collisionFreeWidthForBand` (+ the `overflows` predicate it shares)

These move as one cohesive `CollisionRelaxation` (or equivalent) unit using the established
pattern; extracting them brings the planner to roughly 2,200-2,300 raw lines. Reaching the
ticket's ~1,500 additionally requires the remaining span-mutation/rescue-ceiling family
(`dilateSpans`, `clipSpansToRect`, `rescueCeilingSpans`, `containedReflowRescue`,
`paintEnvelopeContainedInComponent`, L1220-1460 — also parameter-pure but more coupled to
mask-session geometry), which the implementer may legitimately phase separately; the
directive's FAIL condition attaches to the relaxation loop itself.

Compounding the incompleteness, the report states "Pure rendering-kernel moves **were
completed**" and never mentions the missed span or the size target, unlike the P4-01 section
which candidly discloses its own target miss. Required remedy: either extract the listed
spans (pattern already proven in-branch) or re-ticket the remainder with an explicit,
evidence-based entanglement argument — currently none exists.

## Item 3 — P4-01 structure: PASS WITH NOTES

- Coordinator now 2,091 raw / 1,991 non-blank (above the ~900 heuristic target; disclosed in
  the report with the shared-context/router rationale — accepted per the ticket's own
  "extract what is clean" rule, unlike P4-02's silent gap).
- Workers: AnalysisWorker 550, ProfileReconciler 515, EnvelopeDispatcher 565,
  StandardLaneWorker 443, FinalizeWorker 444, RecoveryWorker 648, PreflightWorker 897 raw —
  all under 900 lines; no catch-all worker exists (each owns one phase; shared helpers stayed
  in the coordinator).
- Sequencing unchanged: `runPass1` → `preflightWorker().runPhase` with continuation lambdas
  to `runAnalysisPhase` → `ProfileReconciler.runPhase` → `runEnvelopePlanAndTranslate` →
  `EnvelopeDispatcher.runPhase` / `runStandardTranslateAndFinalize` → finalize
  (`finalizeWorker().runPhase`/`drainPhase`) → `recoveryWorker().resumePhase` for resume —
  the same named-seam order as main (verified by reading both call graphs); ordering is
  additionally pinned by the unmodified `StandardPipelineCoordinatorTest` (902 LOC) and
  resume/display-tail suites, which are green.
- Justifying comments present on the remaining routing bodies (delegation KDocs at L301/363/
  430/615 etc.).

## Item 4 — P4-03 seam quality: PASS

- Session admission lives in the controller: `requestReaderSession` (controller L728),
  `confirmBatchReaderSwitch` → `translationManager.switchReaderSession(...)` (L757/760) —
  NOT left in the ViewModel; `ReaderViewModel.confirmBatchReaderSwitch` (L1482) is a thin
  delegate preserving the P3-era public name.
- `ReaderActivity`/viewer UI files are not in the diff → the ViewModel's consumed surface is
  unchanged by construction (delegates keep names/types/flows).
- `TranslationManager` / `TranslationScheduler` / `TranslationSessionCoordinator` /
  `TranslationPipeline`: not in the diff — zero signature changes.
- Reader-side tests: zero diff (Item 1a).
- Controller placement `tachiyomi/ui/reader/` (ticket allowed either package with
  justification): report justifies via ownership of ViewModel-owned state; accepted.

## Item 5 — Safety invariants: PASS

- NNAPI/hardware-routing files and `app/src/main/assets/`: zero diff (all model/OCR assets
  byte-identical; APK presence re-verified in Item 6).
- Phase-2 lock discipline and P3 session semantics: no lock-acquiring code moved into new
  call orders — worker/controller extraction carried bodies verbatim (Item 1), the session
  gate call chain (`requestReaderSession` → `switchReaderSession`) moved as a unit with the
  same manager seams and argument order, and no new mutex/monitor appears in any extracted
  file (verified by reading the wiring). `NonCancellable` teardown paths untouched.

## Item 6 — Evidence: PASS

- **Test XMLs: 2,025 tests × both flavors, 292 files each, 0 failures / 0 errors** (fresh
  timestamps 09-20 11:28 Dev / 11:14 Standard — post-phase).
- **APK** (`app-dev-universal-debug.apk`, 363,099,464 bytes): 2,042 entries; best_int8 0;
  OCR docs 0; manga109 1; inference.onnx 2; aot-512 1; aot.onnx 1. (Report inspected the
  arm64 variant — universal re-verified here with identical results.)
- **Flake ledger:** one combined-run failure (`BatchDispatchResumeWiringTest`,
  run-1 COMPLETE-wait signature) with the required isolation audit 3/3 × both flavors and
  final full green reruns — the exact pre-existing load-flake family this reviewer
  independently reproduced and cleared in Phases 2-3. No storage/rendering regression
  indicated.
- **Git:** working tree clean; `app/google-services.json` absent; report commit contains
  only the report (1 file).
- **"Stale size figures" — resolved as a non-issue:** the report's 2,091/3,470/1,849 are
  RAW line counts and the directive's 1,991/3,272/1,647 are NON-BLANK counts of the same
  files (measured: coordinator 2,091/1,991, planner 3,470/3,272, ReaderViewModel 1,849/1,647,
  controller 1,618/1,537). Same tree, different counting method (the Phase-1 precedent).
  Recommend future reports state which counting they use.

## Conclusion

The phase's mechanics are exemplary — eleven verbatim extractions, zero test churn, green
evidence — but the phase is incomplete against its own ticket: P4-02's collision-relaxation
span, which the ticket explicitly enumerated and which is provably pure-move-extractable
(parameter-pure signatures, no shared mutable state, pattern demonstrated in-branch), was
left in a file sitting at 2.3× the ticket's size target without disclosure. Remediation is a
bounded, low-risk follow-up: extract the nine listed spans with the established
Context-object pattern (no test changes required), or produce an evidence-based
re-ticketing. Everything else is merge-ready.

---

# DELTA RE-REVIEW — Phase 4 closure (2026-09-20)

Delta under review: `72fca1b..HEAD` = `d9b5744` (extraction) + `ce714c6` (report addendum).
Verified independently against the original FAIL finding:

## 1. Span correspondence — PASS

All nine reviewer-listed spans are extracted into
`app/src/main/java/eu/kanade/translation/rendering/CollisionRelaxation.kt` (1,125 lines):
`hardCellsDisjoint` L142, `footprintCollides` L146, `translateRect`/`translateLayout`
L158/166, `SHIFT_*` L182-185, `minShiftDisplacement` L194, `resolvePostAnchorPlacement`
L272, `freeRectDisplacement` L637, `fitIntoFreeRect` L652, `resolveMinimalDisplacementX`
L695, `freeSpaceLeft/Right/VerticalUp/VerticalDown` L729-770, `growIntoFreeSpaceIfNeeded`
L790, `boundedFreeTextWideningPlan` L988, `collisionFreeWidthForBand` L1084, `overflows`
L1106. The addendum enumerates exactly this list. Nothing from the list was skipped, so no
shared-state evidence was required.

## 2. Pure-move verification of the delta — PASS

Planner diff: 84 added / 898 removed. Of the 885 moved-out lines (after excluding the 13
that the planner itself re-added verbatim as wrappers/declarations), **850 appear verbatim
in `CollisionRelaxation.kt`**. The remaining 35, individually reviewed:

- 1 diff-header artifact (review-script regex).
- 7 visibility widenings `private` → `internal` (`FinalResolution`, `FreeRectCandidate`,
  `RescueBudget`, `GrownBox`, `FreeTextWideningPlan`, `SharedCellPlan` classes,
  `inkRectOf`, `columnsFor`) — the addendum's stated, allowed mechanical adaptation.
- ~22 comment lines and 4 non-ASCII constants comparisons: comments were **ASCII-fied in
  transit** (em-dash `—` → `-`, `⇒`/`∩` → `?`). Zero code impact — no code line changed;
  note below.
- Net-new in the planner: private delegating wrappers
  (`… = CollisionRelaxation.resolvePostAnchorPlacement(...)` etc. with parameter-for-parameter
  forwarding), 6 private typealiases to the planner-owned result types, and the
  `internal object CollisionRelaxation` forwarding shims for helpers that stayed
  (`placeBlock`, `adaptiveBlockLayout`, `computeStrokeWidth`, `cjkWrap`, `inkRectOf`, …).
  No logic edits, no reordering, no dropped branches, no default-value changes.

## 3. Tests, size, scope — PASS

- `git diff 72fca1b..HEAD --name-only -- app/src/test` → **empty** (phase-wide test diff
  remains zero).
- Full Dev suite on the post-extraction tree: **2,025 tests, 292 files, 0 failures /
  0 errors** (XMLs written 12:09 local, commit at 12:11, clean tree — the run reflects
  exactly the committed state). Rendering/segmentation contract suites green within it:
  TextLayoutPlannerTest 34, Direction 8, Stroke 4, MaskGeometryDeterministicAssignment 6,
  MaskGeometryOrderedRle 9; `BatchDispatchResumeWiringTest` green (disclosed flake did not
  recur).
- `TextLayoutPlanner.kt`: 3,470 → **2,617 raw / 2,458 non-blank**; `CollisionRelaxation.kt`
  1,125 raw / 1,060 non-blank. The FAIL condition (the nine spans) is fully remediated; the
  planner remains above the ticket's ~1,500 aspiration via the explicitly-deferred
  span-mutation/rescue-ceiling family, which the original report already accepted as a
  separate decision.
- Delta scope: exactly `TextLayoutPlanner.kt` + new `CollisionRelaxation.kt` + the report
  addendum. No other files touched; working tree clean.

## FINAL PHASE VERDICT: **PASS WITH NOTES**

The original FAIL finding is fully remediated with verbatim, behavior-preserving extraction
and honest disclosure. Notes carried forward (non-blocking):

1. **Comment encoding degradation (new, cosmetic):** moved comments in
   `CollisionRelaxation.kt` lost non-ASCII characters in transit (`—` → `-`, `⇒`/`∩` →
   `?`). Zero behavioral impact; recommend the writer-side encoding fix before Phase 5 and
   a one-off comment restoration if desired.
2. **Planner size aspiration:** 2,617 raw / 2,458 non-blank vs the ticket's ~1,500 — the
   remainder is the span-mutation/rescue-ceiling family (parameter-pure but mask-session
   coupled), explicitly deferred. Re-ticket for a later phase rather than force it.
3. Carried from the original review: `SinglePageOutcome.Attached` remains producer-less dead
   code; test class/filename divergence (`ChapterArtifactEngineTest` in
   `ChapterArtifactStoreTest.kt`); raw-vs-non-blank line counting should be stated in
   reports.
