# T912 slice 5 — independent verification review

Scope: commit `a67c3aa` ("T912 slice 5 — adaptive bands and one-line Android
shaping") on `codex/text-layout-renderer`, reviewed against
`engineering/architecture.md` revision 2 ("Line breaking contract", "Slice 5:
adaptive bands and the Android shaping contract", "Exact planner contract"
(PositionedLine/HardClip/BlockLayout/NonDraw), "Global budgets and candidate
limits", "Failure/fallback matrix", "Compatibility and acceptance invariants"
1–10) and `review/slice-3-verification.md` (prior baseline). Reviewer examined
the commit, current file contents, and baseline `3a4b367` for behavior
comparisons, and ran both gates. Working tree clean at HEAD = `a67c3aa`.

## Verdict

**ACCEPT.** No blocking findings. The line-breaking contract, the adaptive
band algorithm, the shaping contract, and the page budgets are implemented
faithfully and pinned by non-vacuous tests; the slice-3 vertical-partition
ride-along fix is correct for both axes and the horizontal path is
behavior-identical to baseline; the legacy unmasked path is byte-unchanged
(`TextLayoutPlannerTest` 31 unmodified and green; renderer legacy/vertical
branches and `draw()` untouched vs `3a4b367`). All 14 documented deviations
were verified against primary evidence: 14/14 sanctioned, several with
NOTE-level caveats recorded below. The one substantive caveat (deviation 12:
adaptive placement is obstacle-blind, so visible pixel overlap with
earlier-placed unclipped neighbors is possible in the slice-5 state) is a
staged limitation that belongs to slice 7 by the architecture's own
sequencing, and is documented by the implementer — it must be re-verified at
the slice-7/slice-8 gates.

## Findings

### BLOCKING

None.

### MEDIUM

None.

### NOTE

1. **NOTE — `applyBestHyphenTrial` can hyphenate the WRONG token when the
   eligible token's string occurs earlier inside an ineligible token.**
   `TextLineBreaker.kt:164,166-171` insert via `text.replaceFirst(token, …)`,
   which replaces the first *substring* occurrence, not the first token
   occurrence. Example: `"ABCDEFGH0 ABCDEFGH"` — token 1 is ineligible
   (contains `0`), token 2 is eligible, but `replaceFirst("ABCDEFGH", …)`
   mutates token 1 into `"ABC-DEFGH0"`. Consequences are bounded: render-only,
   deterministic, block-own text only, persisted data untouched
   (`TextLayoutPlanner.kt:667` keeps `layout.text` = the chosen text), and the
   shaped result still passes band validation or falls back. Classification:
   low-likelihood cosmetic defect in a render-only trial. Evidence that would
   confirm: a test with an eligible token whose string is a prefix-substring
   of a preceding ineligible token. Options: replace by token index/offset
   instead of string match; fold into slice-7 hardening or a follow-up.
2. **NOTE — Overflow-removal signal also fires when the baseline legitimately
   fits exactly at the minimum font.** `TextLineBreaker.kt:174`
   (`baselineOverflows = baselineFont <= minFontPx`) treats
   "baseline fitted AT the floor" as overflow, so such a trial is accepted on
   any strictly positive font gain (`:178`), not only ≥15%. The injected
   fitter floors at `minFontPx` precisely when nothing fits
   (`AdaptiveBandPlanner.kt:427,449`), so the common case is correct; the edge
   is a baseline whose true fit equals the floor. Impact: a marginal trial
   acceptance in a rare corner; no containment or identity risk. Nuance of
   sanctioned deviation 1.
3. **NOTE — "byte-for-byte" horizontal-path claim is a slight overstatement
   in the letter, correct in substance.** The `MaskTextRegionPlanner.kt`
   counting/fill passes were restructured into an axis `if`-expression
   (`:217-227`, `:237-250`); the horizontal arithmetic (`max(span.start,
   slab.start)` / `min(span.endExclusive, slab.endExclusive)` with
   `start < end`) and the horizontal predicate
   (`span.start < slab.endExclusive && slab.start < span.endExclusive`) are
   semantically identical to baseline, and all 12 pre-existing planner tests
   plus every integration fixture pass unchanged. Behavior-identical; not
   textually byte-identical.
4. **NOTE — `BlockLayout.hardClip` is written but never read by the
   renderer.** `PageTextRenderer` composes the structural clip from
   `maskGeometry`/`planGeometryId`/`maskComponentId`/`cellRect`
   (`PageTextRenderer.kt:46-58,77-79`), not from `hardClip`; the planner sets
   `hardClip` in exactly one place as a 1:1 mirror
   (`TextLayoutPlanner.kt:630-633`), verified equal in tests
   (`TextLayoutPlannerSlice5Test.kt:84`,
   `TextLayoutPlannerMaskMetadataTest.kt:113`). No divergence risk today
   (single write site); a later slice should either consume `hardClip` or the
   mirror remains contract documentation.
5. **NOTE — Center-nearest band selection uses the BLOCK center X only.**
   `AdaptiveBandPlanner.kt:354-371` with `centerX = blockCenterX`
   (`TextLayoutPlanner.kt:579`); architecture says "nearest the block/cell
   center". Either reading satisfies the contract; the choice is deterministic
   and the cell content center is used as the alignment-(b) y-anchor
   (`:170,177-178`). Recorded so the reading is explicit.
6. **NOTE — Band fitting is not strictly monotonic in font, so the bounded
   7-step binary search can in principle miss a feasible larger font.**
   Interval/alignment selection can differ between fonts, so the classic
   binary-search precondition is only approximately met. This is inherent to
   the mandated "bounded binary search" and fails safe (falls back to the
   stroke-inset rectangle or a smaller font). Expected behavior, no action.
7. **NOTE — Adaptive `extentOf` (union bbox of un-clipped conservative
   occupancies) can extend past the hard slab, so later-placed blocks may be
   pushed/over-constrained by a phantom footprint** (`TextLayoutPlanner.kt:1403-1416`;
   inflation = stroke + AA + gap/2 per side, `AdaptiveBandPlanner.kt:283-284,301-306`).
   Already flagged by the implementer as a remaining risk; watch slice-8
   real-page diffs. Classification: intended conservatism.
8. **NOTE — Whitespace-only forced lines (e.g. `"  \n"`) produce non-empty
   no-ink positioned lines that reserve 2 StaticLayouts each and must still
   validate against their band.** Identical semantics to legacy `cjkWrap`
   (newline flush does not `trimEnd` in either, `TextLineBreaker.kt:97-99` vs
   `TextLayoutPlanner.kt:1947-1949`); reservation over-counts, never
   under-counts. Expected behavior.

## Visible-overlap analysis (review point 7) — the four scenarios

Eligibility boundary verified first: adaptive runs ONLY for nonblank chosen
text, non-vertical (`!isVertical`), in an optimized SPAN-MODE cell
(`cellPlan.optimized && cellPlan.componentId != null && spans.isNotEmpty()`)
— `TextLayoutPlanner.kt:561-566`. Bounds-mode cells, groupless masks,
beyond-8 members, tie members, vertical text, and unmasked blocks all take the
legacy path. The adaptive attempt never consults `placedObstacles`
(deviation 12); later blocks DO use the adaptive layout's `extentOf` union
bbox as an obstacle (`:554`, `:1403-1416`).

**(a) Two adaptive layouts on DIFFERENT components of one mask — visible
overlap IMPOSSIBLE.** Each layout's paint is clipped to its own component
path (WINDING rect union over that component's spans,
`PageTextRenderer.kt:87-98`) then its own slab (`:77-78`). Distinct
components of one RLE have disjoint pixel sets (union-find partition), so
ink(A) ∩ ink(B) = ∅ regardless of bounding-box interleaving. Outward stroke
is clipped away at the component boundary by the same clip pair.

**(b) Two adaptive layouts on adjacent cells of ONE component — visible
overlap IMPOSSIBLE.** Slabs are half-open and disjoint with an unowned dead
zone (`MaskTextRegionPlanner.kt:189-206`); each paint is clipped to
component ∩ own slab, and both share the same component path, so the
intersection is exactly the disjoint slab interiors. The planner-level
"overlap" the implementer mentions is between the un-clipped conservative
occupancies only — a planning signal, not paint; slice 7 owns making even the
planning occupancies disjoint.

**(c) Adaptive vs legacy masked neighbor WITHOUT cells (tie/beyond-8/
groupless member) — visible overlap POSSIBLE; bounded only by the adaptive
side's component+slab clip.** Such a member is placed with a legacy region
and no metadata, and its placement branch sets `clipRect = null`
(`TextLayoutPlanner.kt:928-947`), discarding the clip net exactly as in
baseline (slice-3 NOTE 6). If the legacy member places AFTER the adaptive
block, the grow/clip stage sees the adaptive union bbox as an obstacle, but
the `regionOverride != null` branch still re-centers into its region and
clears any computed clip, so the avoidance is best-effort. If it places
BEFORE, the adaptive block never saw it. Either ordering can produce visible
overlap. This is the slice-3 staged limitation plus adaptive obstacle-blind
placement; slice 7's post-anchor safety (eight finite candidates, hard
disjoint clips, else `NonDraw`) is the mandated closure.

**(d) Adaptive vs unmasked legacy block — depends on placement order.**
- Unmasked legacy placed BEFORE the adaptive block: the adaptive block is
  obstacle-blind, and the legacy block is unclipped
  (`HardClip(null,null,null)`, no `clipRect` unless its own clip net fired
  against obstacles it knew about) — visible overlap POSSIBLE whenever the
  geometries intersect; nothing structural prevents it in the slice-5 state.
- Adaptive placed BEFORE the unmasked legacy block: AVOIDED. The legacy
  placement treats `extentOf(adaptive)` as a fixed obstacle; that bbox is the
  union of per-line `[leftPx, leftPx+advance] × [topPx, topPx+lineH]` rects
  inflated by stroke + AA + gap/2, which is a superset of the painted ink
  (shape width `layoutWidthPx` only adds shaping guard, and ink stops at the
  advance; vertical ink lies within `[topPx, topPx+lineH]`), so growth moves
  away and, for `regionOverride == null`, the clip net can produce a real
  `clipRect` (`:838-876`). Residual asymmetry: the legacy block's own extent
  omits stroke — pre-existing baseline behavior, unchanged here.

Summary: structural containment holds wherever BOTH sides are clipped
(a, b). Where one side is an unclipped legacy block (c, and d in the
legacy-first ordering), visible overlap is possible until slice 7. This
matches the architecture's staging ("For two layouts that do not have
provably disjoint hard cells, the final safety pass must select disjoint
rectangular hard clips or return non-draw") and the implementer's documented
deviation 12; it is NOT silent contract drift, but it must be the first thing
re-checked at the slice-7 review and the slice-8 device gate.

## Per-acceptance-criterion verification

| # | Criterion / test-list item | Result | Evidence |
|---|---|---|---|
| 1 | Forced newline / whitespace / CJK per-glyph / atomic Latin / source-hyphen break | PASS | `TextLineBreaker.kt:51-83` (tokenizer mirrors legacy `cjkWrap`, hyphen ends run but stays attached), `:91-124` (prewrap); tests `TextLineBreakerTest.kt:24-71` |
| 2 | prewrap ≡ cjkWrap content parity; corpus meaningful | PASS | 18 texts (newlines, blank, whitespace-only, leading/mid hyphens, CJK-mixed, 50-char atomic, punctuation) × 3 fonts × 3 widths = 162 exact list equalities (`TextLineBreakerTest.kt:74-104`); parity also holds by construction (identical token/append/trim semantics, `TextLineBreaker.kt:91-124` vs `TextLayoutPlanner.kt:1915-1966`) |
| 3 | Trial eligibility exactly ASCII `[A-Z]{8,}` letters-only | PASS | `TextLineBreaker.kt:131-134` (length ≥ 8 AND all chars in `A..Z` — subsumes digit/`. / @ _ :`/hyphen bans since the tokenizer only ever attaches a hyphen at run end, `:71-77`); matrix test `TextLineBreakerTest.kt:108-124` |
| 4 | Trial set exactly {baseline, one balanced break, two balanced breaks}; 3-letter minimum; ≤ 2 insertions/block | PASS | Candidates built only at `floor(len/2)` / `floor(len/3),floor(2len/3)` clamped into feasible ranges (`TextLineBreaker.kt:163-171,192-208`); loop over `listOfNotNull(oneBreak, twoBreak)` (`:176`); len-8 two-break refusal pinned (`TextLineBreakerTest.kt:153-168`), len-9 two-break pin (`:170-175`), ≤2 insertions pinned (`:218-223`) |
| 5 | Acceptance ONLY on overflow removal or ≥ 15% font gain; persisted text never mutated | PASS | `TextLineBreaker.kt:173-183` (`(baselineOverflows && trialFont > baselineFont) || trialFont >= 1.15f * baselineFont`); boundary pinned: 22.9/20 rejected, 23.0/20 accepted (`TextLineBreakerTest.kt:189-200`); overflow accept pinned (`:179-187`); immutability pinned (`:225-232`) + integration (`TextLayoutPlannerSlice5Test.kt:104-120`) |
| 6 | Determinism of the breaker/planner | PASS (STRONG INFERENCE) | No randomness, no map-iteration-dependent output, stable sorts; same inputs replayed by the 162-case corpus and exact-counter pins |
| 7 | Band = one continuous interval surviving intersection across EVERY covered row ∩ hard slab | PASS | `AdaptiveBandPlanner.kt:319-331` (intersection across every row in the clamped range; a missing row fails the band); x-slab baked into the slab-intersected cell spans (`TextLayoutPlanner.kt:1636-1645`), y-slab by the containment gate (`:243-245`); hole-row narrowing pinned (`AdaptiveBandPlannerTest.kt:99-131`) |
| 8 | Center-nearest interval selection | PASS | `AdaptiveBandPlanner.kt:354-371` (nearest block-center X; ties wider, then leftmost) — reading NOTE 5 |
| 9 | Bounded search ≤ 7 / ≤ 3 / ≤ 3; counters measure what deviation 5 claims | PASS | Font loop capped at `MAX_FONT_BINARY_STEPS` (`:129`), alignment loop at 3 (`:172`), fixed-point at 3 (`:233`); `fontSteps` counts main-search evaluations, `alignmentsTried`/`fixedPointPasses` describe the winning attempt only (`:189-196,141-148`); exact pins `Stats(6,1,1)`, `Stats(6,1,2)`, `Stats(6,2,1)`, `fontSteps=7`, `fontSteps=1` (`AdaptiveBandPlannerTest.kt:81,115,222,226-243`) |
| 10 | Per-line acceptance: `layoutWidthPx = max(1, ceil(advance + 2*SHAPING_GUARD))` + exact span containment; integer floor positions | PASS | `AdaptiveBandPlanner.kt:289` (formula; guard = `ceil(stroke/2 + AA)`, `TextLayoutTuning.kt:359-360`), `:292` (`layoutWidthPx <= band.width`), `:294-295` (`floor` placement), `:297-300` (every pixel column span-owned on every covered row); formulas re-derived and pinned in `AdaptiveBandPlannerTest.kt:88-95,120-130,196-209` and `TextLayoutPlannerSlice5Test.kt:91-101` |
| 11 | >24 lines or non-consumable text → fallback; vertical slab gate (deviation 2) | PASS | 24-line cap fails the alignment/font (`AdaptiveBandPlanner.kt:236-237`); unvalidatable line fails the font (`:292,299`); all fonts → null → EXISTING legacy `placeBlock` retry (`TextLayoutPlanner.kt:594-625`); pins: `AdaptiveBandPlannerTest.kt:177-193`, `TextLayoutPlannerSlice5Test.kt:197-213`. Deviation 2 assessed SANCTIONED (below) |
| 12 | Budgets: legacy horizontal = 2, positioned line = 2, vertical = 0; reserved in placement (renderOrdinal) order | PASS | `TextLayoutPlanner.kt:504-512,585-591,597-611`; reservation happens inline as each Draw is appended (placement order == render order); pins: 257-block NonDraw, adaptive-reserves-2/line discriminating test (255 vs 256 draws), vertical-zero test (`TextLayoutPlannerSlice5Test.kt:124-195`) |
| 13 | Page caps 256 lines / 512 StaticLayouts with legacy retry then `NonDraw(STATIC_LAYOUT_BUDGET_EXHAUSTED)`; identity intact; `POSITIONED_LINE_BUDGET_EXHAUSTED` unemitted | PASS | Adaptive budget overflow falls through to the legacy retry (`:585-596`), legacy overflow emits the NonDraw with identity + null renderOrdinal + consecutive ordinals (`:597-609`; `TextLayoutPlannerSlice5Test.kt:124-146`); 256-line retry exercised end-to-end by the 11-strip fixture (`:215-253`, block 10 Draw in legacy form with cellRect); `POSITIONED_LINE_BUDGET_EXHAUSTED` has no construction site (grep: declaration only, `TextLayoutPlanner.kt:180`) |
| 14 | Renderer shaping contract: one fill+stroke StaticLayout pair per line at `layoutWidthPx`, ALIGN_NORMAL / includePad(false) / SIMPLE / HYPHENATION_NONE / setMaxLines(1); `lineCount == 1` at bind, fail closed | PASS | `PageTextRenderer.kt:146-167`; bind-time drop via `buildShaped` null → `mapNotNull` (`:57,117-126`); draw composes clips once then saves/translates/strokes/fills/restores per line (`:68-85,175-185`) |
| 15 | `draw()` allocation-free; rebind/clear cannot expose stale prepared objects | PASS (code proof) | All construction in `bind()`/`buildShaped`/`prepare*`; `drawPositioned` uses only save/translate/draw/restoreToCount (`:175-185`); `bind()` begins with `clear()` and dropped layouts never enter `prepared` (`:37-66`); `clear()` empties and `draw()` early-returns on zero page dims |
| 16 | Legacy/vertical renderer paths byte-identical vs `3a4b367` | PASS | Diff hunks touch only `bind` (null-shaped drop), `buildShaped` (added positioned branch; legacy branches semantically identical), `drawLayout` (added case), and NEW members; `draw()`, `prepareHorizontal/Vertical`, `drawHorizontal/Vertical`, `buildStatic`, `alignmentFor` untouched (diff verified) |
| 17 | `PositionedLine`/`HardClip`/`BlockLayout` verbatim vs architecture; defaults preserve constructors; hardClip mirrors wired metadata 1:1 | PASS | `TextLayoutPlanner.kt:83-102,143-161` match architecture.md:118-138 field-for-field; all new fields defaulted; mirror at `:630-633` asserted in tests (see #14 row evidence) |
| 18 | Adaptive as obstacle uses deterministic measure; crowded-page invariant holds for unmasked pages | PASS | `extentOf` union bbox of conservative occupancies (`:1403-1416`) — deterministic; unmasked-vs-unmasked obstacle math untouched (legacy `extentOf` branches byte-identical); unmasked pages never enter the adaptive path (eligibility requires a span-mode optimized cell) |
| 19 | Thin/medium gain; concavity/hole; stroke inset (ceil at font 30 = 5); 104/103 wrap-boundary inset pin | PASS | `AdaptiveBandPlannerTest.kt:57-96` (font 10 > rect floor, full-width band), `:99-131` (strip [0,20)), `:134-143` (`strokeInsetPx(30f,1f) == 5f`, guard/inset/AA pins), `:146-174` (104px ⇒ 1 line vs 103px ⇒ 2 lines — any other inset flips) |
| 20 | Deterministic ALL-CAPS gating (integration) | PASS | `TextLayoutPlannerSlice5Test.kt:104-120`: `"A"×16` → lines `["AAAAAAAA-","AAAAAAAA"]`, `layout.text`/`input.translation` unchanged |
| 21 | Slice-3 fix: axis-aware span counting/fill correct for BOTH axes; horizontal unchanged; vertical fixture pins exact values | PASS | `MaskTextRegionPlanner.kt:213-253` — horizontal arm arithmetically identical to baseline (NOTE 3); vertical arm assigns the row whole by `span.y ∈ [slab.start, slab.endExclusive)`, x untouched, matching `slabRect` (`:338-343`) and `spansInNearestInterval` row projection (`:369-408`); counting and fill agree per axis (one count per hit both passes). Fixture: slabs [0,49)×[0,100)/[51,100)×[0,100), dead rows exactly {49,50}, exhaustive 10,000-pixel 0/1 ownership check (`MaskTextRegionPlannerTest.kt:325-361`); under the baseline code the fixture fails (rows 0–48 would own only x<49, rows 51–99 only x≥51, and dead rows 49/50 would be owned) |
| 22 | Metadata fixture update keeps identity/disjointness/dead-zone assertions; strict cross-cell font ordering kept | PASS | `TextLayoutPlannerMaskMetadataTest.kt:104-125` — replaced only the legacy per-cell font-equality block with positioned-line contract assertions; shared geometry instance, ids, exact slabs, disjointness, dead columns, text map, and `plan[2].font < plan[1].font` retained |
| 23 | Legacy byte-identity: unmasked path unchanged; `TextLayoutPlannerTest` 31 unmodified and green | PASS | `git diff 3a4b367 a67c3aa --name-only` does not list `TextLayoutPlannerTest.kt`; suite green 31/31 (gate run below); planner hunks confined to contract types, `TextLayoutTuning`, `planPage` loop, `extentOf` head, `SharedCellPlan.spans`, and the predicate extraction — `placeBlock`/`computeRects`/`buildMaskRegions`/`cjkWrap` untouched |
| 24 | Tests non-vacuous; deviations 7/11/14 honesty | PASS | See "Test-quality assessment" and deviation table |

## Assessment of the 14 documented deviations

| # | Deviation | Assessment |
|---|---|---|
| 1 | `applyBestHyphenTrial` takes `minFontPx` | **SANCTIONED.** The contract's "removes overflow" rule is only decidable if the trial knows the floor; the injected fitter returns the floor exactly when nothing fits (`AdaptiveBandPlanner.kt:427,449`), making `baselineFont <= minFontPx` the overflow signal (`TextLineBreaker.kt:174`). Pinned by tests. Edge nuance recorded as NOTE 2. |
| 2 | Vertical slab-containment gate (`AdaptiveBandPlanner.kt:240-245`) | **SANCTIONED — correct implementation, not drift.** Architecture requires a band to survive "intersection across every covered component row AND the hard cell slab". The x-slab is baked into the slab-intersected cell spans, but the row-clamp (`floor(yTop)..ceil(yTop+lineH)-1` clamped to span rows) would otherwise accept stacks extending beyond the slab's y-range that the slab clip would then silently erase. The gate supplies exactly the missing y-slab containment (±0.001 slack) and fails through the ladder (alignment → font → legacy rectangle), never producing a NonDraw or truncation. Without it the "exact span containment" acceptance claim would be false for vertical partitions. |
| 3 | `AdaptiveResult.anchorX/anchorY` | **SANCTIONED.** The contract mandates "originX/originY = the alignment anchor"; the extra fields are the mechanism, and `BlockLayout.originX/originY` are set from them (`TextLayoutPlanner.kt:670-671`), pinned (`TextLayoutPlannerSlice5Test.kt:81-82`, `AdaptiveBandPlannerTest.kt:83,218-222`). |
| 4 | `layoutHeightPx = max(1, ceil(lineH))` | **SANCTIONED.** Architecture defines the width formula exactly and leaves height open; `ceil(lineH)` is the planner-side analogue of the StaticLayout height and is what the renderer's single line occupies. |
| 5 | Stats semantics (main-search font steps; winning attempt's alignments/passes) | **SANCTIONED.** The reading is well-defined and deterministic; failed-font attempts are unobservable in a successful result. The architecture's caps still bound ALL work (≤7 fonts × ≤3 alignments × ≤3 passes each). Exact pins prove the counters are not smoke (`AdaptiveBandPlannerTest.kt:81,115,222,226-243`). |
| 6 | Trial selection: first eligible token; largest fitted font wins; ties earlier/fewer insertions | **SANCTIONED.** Deterministic refinement consistent with the exact trial set; the ≤2-per-block cap is structural given one token and at most two breaks. Pinned (`TextLineBreakerTest.kt:128-151,211-223`). The `replaceFirst` substring hazard is NOTE 1 (orthogonal to this policy). |
| 7 | 3-letter minimum as deterministic clamping of split points | **SANCTIONED (honest).** For eligible lengths the balanced points never violate the minimum (len ≥ 8 ⇒ one-break valid; len ≥ 9 ⇒ two-break), so the clamp is a guard, and the observable behavior (len-8 two-break refusal) is pinned. The report's statement that the shift path is not directly observable is accurate. |
| 8 | `MAX_BAND_INTERVALS = 64` | **SANCTIONED.** Matches the task's "small guard, e.g. 64"; exceeding it fails the band (fail-safe), `AdaptiveBandPlanner.kt:343`. |
| 9 | Occupancy inflation = stroke + AA + gap/2 per side | **SANCTIONED.** Literal reading of "inflated by stroke, AA guard, and half collision gap"; un-clipped by construction; exact rects pinned (`AdaptiveBandPlannerTest.kt:93-95,126-130`). |
| 10 | Empty positioned lines build no StaticLayouts; non-empty ≠1-line drops the whole layout | **SANCTIONED.** Without the empty-line carve-out any translation containing a blank forced line would fail closed entirely. Reservation still charges 2 per line including blanks (`TextLayoutPlanner.kt:585-591`), so the budget never under-counts. The fail-closed property is preserved for every non-empty line (`PageTextRenderer.kt:150-151`). |
| 11 | 65th-distinct-pair cap as a seam test on an extracted predicate | **SANCTIONED (verified verbatim).** `componentAssignmentAllowed` (`TextLayoutPlanner.kt:1723-1724`) is logically identical to the removed inline condition (`!(a in s) && s.size >= 64` ≡ `!(a in s || s.size < 64)`). Unreachable through `planPage`: distinct `(group,component)` pairs ≤ converted components ≤ `maxComponentsPerPage` (64) = the assignment cap, and budgets are hard-defaulted (no injection point) — the claim checks out. Member outcome of the capped path (Draw + slab `cellRect`, no ids) is unchanged slice-3 code. A true integration fixture would require an injectable component budget; reasonable to defer. |
| 12 | Adaptive placement ignores obstacles | **SANCTIONED AS STAGED — the slice's main caveat.** Slice 7 owns post-anchor collision/final safety (architecture "Slice 7"; failure matrix); later blocks still treat the adaptive union bbox as an obstacle, and hard clips bound adaptive paint structurally. Consequence today: visible overlap IS possible against earlier-placed unclipped legacy neighbors (scenarios c and d-legacy-first above). Not silent drift — documented by the implementer — but it must be re-verified at slice 7 and watched at the slice-8 device gate. |
| 13 | Adaptive font bounds `FIT_MIN/MAX_FONT_PX*scale`, integer candidates | **SANCTIONED.** Mirrors `binarySearchFontSize`'s integer search over the same float bounds (`AdaptiveBandPlanner.kt:123-125`); fitted fonts pinned (8/10/13/72 in tests). |
| 14 | Instrumented one-line assertions via ink-bottom instead of a bind-drop fixture | **SANCTIONED (honest).** With `setMaxLines(1)` a second line is structurally impossible and `lineCount == 1` cannot observe a failure on-device, so a bind-drop fixture is indeed non-constructible through this API; the pixel tests pin "no second line DRAWN" (ink-bottom bounds) plus alignment/containment/alpha-gap. The `lineCount` check remains as the mandated defensive guard. Caveat: the fail-closed branch is therefore untestable by construction — acceptable, recorded. |

## Test-quality assessment (non-vacuousness)

- `TextLineBreakerTest` (19): exact token sequences, eligibility matrix with
  boundary lengths and every banned character class, 162-comparison parity
  corpus, exact split points (5 / 3+6 / 4), len-8 two-break refusal,
  gain boundary 22.9-reject vs 23.0-accept, overflow-accept at the floor,
  first-token-only, insertion cap, immutability. None vacuous.
- `AdaptiveBandPlannerTest` (9): hand-computed exact layouts (width 76 at
  left 12; strip width 19 at left 0), exact `Stats` triples, font-step
  extremes (7 and 1), 104/103 wrap-boundary pair that flips on any wrong
  inset, hole-row narrowing, alignment-ladder rescue (`alignmentsTried = 2`),
  24-line and non-consumable nulls. None vacuous.
- `TextLayoutPlannerSlice5Test` (8): full positioned-line contract on a
  real `planPage` run (hardClip tuple, per-line formula, slab placement,
  occupancy ≥ line box); ALL-CAPS integration with persisted-text immutability;
  257-block NonDraw with identity/ordinal checks; discriminating
  2-per-line reservation test (would produce 256 draws if reservation were
  wrong, expects 255); vertical-zero test; 24-cap legacy fallback; 11-strip
  256-line retry with exact line-content equality against
  `prewrap(text, 8f, 292f)` (which also pins the inset-derived wrap width);
  65th-pair predicate semantics. None vacuous; the budget tests exercise the
  real retry/fallback branches, not assertions on constants.
- `MaskTextRegionPlannerTest` (+1): the vertical fixture pins exact slab
  rects and dead rows and performs an exhaustive per-pixel ownership count;
  it fails under the pre-fix code (verified by analysis of the baseline
  arithmetic), so it genuinely pins the fix. Slice-3 NOTE 1 is CLOSED.
- `PageTextRendererInstrumentedTest` (+5): component containment with thick
  stroke/combining mark/emoji-ZWJ/punctuation and a hole; two layouts with a
  dead column and a measured ≥1px alpha gap; source hyphen and overwide
  atomic token shaped at an intentionally-too-narrow width with no second
  line; fractional translate+scale containment; left-aligned shaping inside
  the planned width. Compile-gated only (no device), consistent with slices
  1–3; device proof belongs to the slice-8 gate.
- Coverage gaps (NOTE-level): no test for the `replaceFirst` substring
  hazard (NOTE 1); center-alignment shaping is not separately pinned (left
  is); whitespace-only positioned lines unpinned (NOTE 8).

## Scope discipline and invariants

- `git show a67c3aa --name-status`: exactly the 12 allowed files (2 NEW main
  sources, 2 modified main sources + MaskTextRegionPlanner ride-along, 4 test
  files + instrumented test, report). `TranslationOverlayView`, pipeline,
  batch/download/drawer/progress files: absent from the diff.
- No slice-6/7 creep: no free-text widening (`label == 2` / 1.25 / 1.50
  absent from the new code), no final-safety retry ladder, no neck logic
  (grep clean). `POSITIONED_LINE_BUDGET_EXHAUSTED`,
  `NO_DISJOINT_POST_ANCHOR_PLACEMENT`, `INVALID_RENDER_METADATA`,
  `MASK_OR_WORK_BUDGET_EXHAUSTED` remain declared-but-unemitted.
- Invariants 1–5, 7–9: unchanged code paths + green contract tests
  (`PageLayoutPlanContractTest` 6 unmodified). Invariant 6 (positioned lines
  render as exactly one StaticLayout line at the planner's integer width):
  verified at `PageTextRenderer.kt:158-167` with the bind-time fail-closed
  drop. Invariant 10 (budgets checked before allocation; `draw()`
  allocation-free): verified — the 256/512 checks precede each reservation
  and the legacy NonDraw precedes `placeBlock`
  (`TextLayoutPlanner.kt:585-611`); `draw()` allocates nothing.

## Verification commands run (reviewer-executed)

1. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   → **BUILD SUCCESSFUL in 35s.** JUnit XML aggregate: **160 tests,
   0 failures, 0 errors, 0 skipped across 18 suites** — TextLineBreakerTest 19,
   AdaptiveBandPlannerTest 9, TextLayoutPlannerSlice5Test 8,
   MaskTextRegionPlannerTest 13, TextLayoutPlannerMaskMetadataTest 8,
   TextLayoutPlannerTest 31 (unmodified), PageLayoutPlanContractTest 6,
   PageTextRendererDirectionTest 8, TextLayoutPlannerStrokeTest 4,
   ComponentClipCacheTest 6, MaskGeometryTest 7, MaskGeometryStressTest 2,
   MaskGeometryOrderedRleTest 9, BubbleSegmentationDecoderTest 8,
   RenderColorEstimator 22 (7+5+7+3). Matches the implementation report's
   counts exactly.
2. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   → **BUILD SUCCESSFUL in 27s.** (No device available; instrumented pixel
   proofs remain compile-gated, deferred to the slice-8 device gate.)
3. `git show a67c3aa --stat` / `--name-status` (scope), `git status --short`
   (clean at HEAD = a67c3aa), `git diff 3a4b367 a67c3aa` per file (hunk maps
   for PageTextRenderer.kt, MaskTextRegionPlanner.kt, TextLayoutPlanner.kt;
   empty diffs for TranslationOverlayView and TextLayoutPlannerTest).

## Relation to prior reviews

- Slice-3 NOTE 1 (vertical partition branch untested): **CLOSED** by the
  ride-along fixture, which also flushed out and pinned the real latent
  x/y-slab intersection bug — exactly the location NOTE 1 predicted.
- Slice-3 NOTE 2 (64-distinct-pair cap path untested): partially addressed by
  the extracted-predicate seam test (deviation 11); a true integration
  fixture still requires an injectable component budget — carried forward as
  an optional slice-7/8 hardening item.
- Slice-3 NOTE 6 (legacy members can visually collide with optimized
  siblings): still open and now EXTENDED by adaptive obstacle-blind
  placement (scenarios c/d); slice 7 owns closure.
