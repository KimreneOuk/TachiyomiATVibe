# T912 slice 2 — independent verification review

Scope: commit `18cea91` ("T912 slice 2 — bounded ordered RLE conversion and
intersecting clip wiring") on `codex/text-layout-renderer`, reviewed against
`engineering/architecture.md` revision 2 ("Slice 2", "Global budgets and
candidate limits", "Exact planner contract", "Compatibility and acceptance
invariants") and `review/architecture-review.md` (findings this slice resolves).
Reviewer examined the commit, current file contents, the pre-slice baseline
(`1822f3a`), and ran the gates. Working tree was clean at `18cea91`.

## Verdict

**ACCEPT.** No blocking findings. All slice-2 acceptance criteria are met with
file:line evidence; every fallback path keeps exactly one layout per nonblank
input; the six HIGH architecture-review findings that slice 2 owns are
addressed (compact identity replaces coordinate-string keys and data-class
hashing; caps precede allocations; instrumentation compiles). Residual items
are NOTE-level and mostly deferred-by-design to later slices.

## Findings

### BLOCKING

None.

### MEDIUM

None.

### NOTE

1. **NOTE — Mask-grouping identity excludes `score`; a theoretical grouping
   delta vs the pre-slice data-class grouping.** Old grouping was
   `groupBy { segmentationMask!! }` (data-class equality, `score: Float`
   included; `BubbleMaskRle.kt:5-12`); new identity is reference equality plus
   width/height/bounds/runs equality (`TextLayoutPlanner.kt:210-211`), score
   deliberately excluded (`TextLayoutPlanner.kt:129-133`). Two DISTINCT mask
   instances with identical geometry but different scores would previously be
   two single-block groups (bounds-rect regions each) and are now one group
   (midpoint partition). This is sanctioned by architecture rev 2 ("verify
   full field/run equality" over geometry-defining fields) and is unreachable
   in production: recognition shares ONE instance per bubble
   (`RoiPageRecognitionEngine.kt:397,503` use `bubbleMasks.firstOrNull`), and
   the defensive copy at `PageTranslationOwnership.kt:19-21` preserves score.
   Test-pinned both ways: `MaskGeometryOrderedRleTest` "fingerprint collision
   cannot merge distinct masks" and "equal distinct instances merge after full
   verification". Classification: expected behavior, documented.

2. **NOTE — Renderer clip-cache span budget can silently drop a
   valid-metadata layout (fail-closed, no wrong content).** `bind()` drops a
   layout (`mapNotNull null`) when `ComponentClipCache.resolve` returns null,
   which includes cache-cap rejection (`PageTextRenderer.kt:45-56`,
   `ComponentClipCache` caps at `PageTextRenderer.kt:288-290` with limits
   64 components / 100,000 spans, `PageTextRenderer.kt:247-250`). The planner's
   64-distinct-pair cap (`TextLayoutPlanner.kt:1240-1242`) aligns with the
   64-component cache cap, so only the span budget can trip in practice, and
   only for pathologically many/large components in one bind. Consequence is a
   missing block, never wrong/merged text. The explicit
   `NonDraw(INVALID_RENDER_METADATA)` representation is a later-slice artifact
   per architecture staging; for slice 2 "fails closed" is satisfied.

3. **NOTE — `rowOffsets` is an O(height+1) allocation not covered by any
   budget** (`MaskGeometry.kt:259-261`). At `MAX_DIMENSION = 1,000,000` this is
   up to ~4 MB per conversion. It is NOT a `width*height` dense allocation
   (acceptance only forbids that plus `decode()`), and it matches the legacy
   `fromSpans` pattern (`MaskGeometry.kt:391-396`), so this is consistent
   behavior, not a regression.

4. **NOTE — Bucket equality-verification scans are not charged to the
   RLE-int budget.** `geometricallyEqual` compares full `runs` lists
   (`TextLayoutPlanner.kt:210-211`) as read-only comparisons; only the
   fingerprint scan is charged (`TextLayoutPlanner.kt:165-168`). Worst case is
   bounded by the 128-reference cap; no allocation. Architecture only requires
   the verification to exist, not to be budgeted.

5. **NOTE — Instrumented pixel test `componentClipIntersectsCellRect` is
   compile-gated only.** No device/emulator was available (same as slice 1);
   the intersection proof (zero alpha in component-right-half = rect clip
   applies; zero alpha in hole = path clip still applies within the rect) is
   asserted but not executed on Android in this slice. Must be exercised at
   the slice-8 device gate. The assertions themselves are non-vacuous: the
   centered 20 px "MMMM" would paint in both probed regions absent clips.

6. **NOTE — First-appearance component-id semantics are only weakly pinned.**
   `MaskGeometryOrderedRleTest` pins ids are `0..n-1` in list order
   (`:76`) and the planner test pins L→0/R→1 for two islands
   (`TextLayoutPlannerMaskMetadataTest.kt:138-140`), but no direct test pins
   first-appearance order for 3+ components. Low risk: nothing may depend on
   cross-path id equality (documented at `MaskGeometry.kt:334-337`) and ids
   are only consumed via `componentForRectangle` on the same instance.

7. **NOTE — Identity-invariant boundary is pre-existing legacy, not a slice-2
   regression.** Blank chosen text (`TextLayoutPlanner.kt:326`) and
   sub-pixel safe rects (`TextLayoutPlanner.kt:330`) still `continue` silently,
   exactly as in `1822f3a`; the explicit `NonDraw` result model is a later
   slice per the architecture's staged contract. All MASK-related fallbacks
   added by slice 2 keep the layout (see table below).

## Per-acceptance-criterion verification

| # | Acceptance criterion | Result | Evidence |
|---|---|---|---|
| 1 | No dense page allocation: never `decode()`, never `width*height` | PASS | `fromOrderedRle`/`fromOrderedRleCore` allocate only O(runs) copies (`MaskGeometry.kt:135-137`), O(spanCount) primitive arrays (`:218-220,265-266`), O(height+1) row offsets (`:259`, see NOTE 3), O(roots) map (`:338`). No `decode()`/`rasterizeOnto` call anywhere in `MaskGeometry.kt` or `rendering/` (grep verified). The `rleIntsScanned` pre-check (`:129,163-166`) bounds the O(runs) copies. |
| 2 | Caps checked BEFORE the corresponding allocation; no coordinate-string key/path before caps | PASS | Span caps checked before Pass B arrays (`:202-207` vs `:218-220`); root count + caps before ANY `RowSpan` list / `Component` / map (`:321-326` vs `:330-348`); sweep budget checked before each comparison, mid-loop abort discards arrays (`:299-303`); `Component.stableKey`/geometry `stableKey` are LAZY and unreferenced on the T912 path (`:22,26`; grep: no production caller). Renderer: cap checks precede `create` (`PageTextRenderer.kt` cache `:288-290`); cache key is the packed Long pair (`:280,304-305`), replacing the old coordinate-string key (verified vs `1822f3a` bind). Page RLE budget checked before the per-mask `toIntArray` copies (`:129-131`). |
| 3 | One explicit result per nonblank input on every fallback | PASS (plan level; see NOTEs 2, 7) | Every mask fallback branch of `withMaskMetadata` returns the layout unchanged (groupless `:1230`, no geometry `:1231`, dims mismatch `:1232`, no region `:1233`, tied/no-overlap component `:1238`, assignment cap `:1240-1241`), and `plan()` always `placed.add`s it (`:350-360`). Test-pinned cardinalities: 3 (`TextLayoutPlannerMaskMetadataTest:73`), 1 (`:111,159,227`), 2 (`:203`), 34 (`:243`), 129 (`:264`). Empty-mask, >64-component, tie, unique-cap cases each asserted to keep results with no metadata (`:160,208,228,246`). |
| 4 | Grouping: reference cache + 64-bit fingerprint bucket + full-equality verification; collision cannot merge | PASS | Reference-identity reuse without rescan (`TextLayoutPlanner.kt:158-159`); FNV-1a fingerprint only selects a bucket (`:169-175`), group reused only after `geometricallyEqual` full field/run comparison (`:210-211`); deterministic fingerprint ⇒ equal geometries always land in the same bucket (cannot be split). Collision isolation proven with injected constant hash (`MaskGeometryOrderedRleTest:146-164`); equal-distinct-instances merge proven (`:166-177`). Caps 128/32 checked BEFORE scanning/new-group creation with groupless outcome (`:160-162,176`); groupless ids negative and never converted (`:197-198,203-208`). Fingerprint + conversion scans share the page budget (`:165-168` charges; `fromOrderedRleCore:163-166` charges again; groupless masks never convert — `geometryFor` returns null for negative ids). Partition math vs `1822f3a` unchanged except the grouping key (removed-lines diff: only `maskKey`, `groupBy`→session, `MaskGrouping` wrapper, `withMaskMetadata` wrap); per-group iteration order and cut math are identical (`TextLayoutPlanner.kt:1124-1206` vs baseline). |
| 5 | Metadata only after exact non-tied overlap assignment; renderer clipPath(component) then clipRect(cell); invalid non-null metadata fails closed | PASS | Metadata wired only after `placeBlock` (`TextLayoutPlanner.kt:348-360`); geometry-dims-vs-page check `:1232`; `componentForRectangle` returns null on tie / no overlap (`MaskGeometry.kt:68-81`, unchanged legacy code); OCR-rect clamped to geometry `:1234-1237`; 64 distinct-pair page cap before assignment `:1240-1243`; `cellRect = regionOverride` (the placement region) `:1248`; placement/font/text fields untouched (diff: `placeBlock` unchanged; only `placed.add` wrapped). `equalizeSharedMaskFonts` still present (`:1253-1280`) and its `copy` preserves the new fields — exercised with metadata assertions AFTER equalization (`TextLayoutPlannerMaskMetadataTest:62-94`). Renderer: fail-closed on missing/incomplete/out-of-range metadata and dims mismatch (`PageTextRenderer.kt:46-56`); instance-identity verification on cache hit (`:282-286`); `draw()` composes component path → cellRect → legacy clipRect (`:76-78`). |
| 6 | Ordinary unmasked manga byte-identical | PASS | Planner removed-lines diff shows no change to `plan()` ordering, `computeRects`, `placeBlock`, growth/clip math; unmasked blocks never enter the session (`:1125` skips null masks; no metadata possible → renderer `componentClip`/`cellRect` null). Renderer unmasked path: `clipRect` applied exactly as before (old code applied it only when no component clip — unmasked always has none; new code composes, but with null path/cellRect only the legacy clipRect runs). Existing suites pass unchanged: TextLayoutPlannerTest 31, PageTextRendererDirectionTest 8, TextLayoutPlannerStrokeTest 4, MaskGeometryTest 7, MaskGeometryStressTest 2 (all green in this review's run). |
| 7 | Row-crossing runs split one-span-per-row; no sorting on the ordered path | PASS | Pass A splits arithmetically at row boundaries with Long math and overflow guards (`MaskGeometry.kt:185-214`); Pass B refills identically with a drift guard `check(filled == spanCount)` (`:256`) and Int-safe `rowEnd` in Long (`:231-236`); adjacent same-row pieces merge to match `fromSpans` normalization (`:196-199` vs `:366-373`); row offsets built by counting, no sort (`:259-261`); no `sortedWith` anywhere in `fromOrderedRleCore`. Test-pinned: row-crossing split values (`MaskGeometryOrderedRleTest:40-55`), output equals its own sort (`:72-75`), query parity with `fromSpans` incl. `containsPoint` over the full page (`:58-90`). Long-overflow guards: `end > pixelCount || end > Int.MAX_VALUE` → explicit `ARITHMETIC_OVERFLOW` fallback (`:187-190`); `pixelCount` in Long (`:172`); Pass B `rowBase = row*width` cannot overflow because Pass A proved every stored position fits an Int. |
| 8 | Scope discipline: allowed files only; no slice-3+ creep | PASS | `git show 18cea91 --name-status`: exactly `MaskGeometry.kt`, `TextLayoutPlanner.kt`, `PageTextRenderer.kt`, instrumented test, three new JVM tests, implementation report. No batch/download/drawer/progress/overlay files. No `PositionedLine`/`LayoutResult`/`LayoutOutcome`/`PageLayoutPlan`/`NonDrawReason` types exist in main sources (grep). `equalizeSharedMaskFonts` intact (`TextLayoutPlanner.kt:1253`). `BubbleMaskRle.kt` untouched (constructor already validates ordered positive runs — `BubbleMaskRle.kt:15-25` — so the core's `require` contract violations are unreachable from production). |

## Test-quality assessment (review point 6)

- `MaskGeometryOrderedRleTest` (8): row-crossing values asserted exactly;
  no-sort pinned structurally; empty/dims/overflow fallbacks with budget
  counters; component-cap abort with `componentsBuilt == 0`; forced-hash
  collision isolation; equal-instance merge incl. score exclusion; 100k
  islands with wall-time (<5s) and allocation (<16MB) assertions; page-budget
  pre-scan abort with zero counting. None vacuous. The allocation assertion's
  graceful-skip path was independently verified LIVE on this host (see
  commands), so it is not a silent no-op here.
- `ComponentClipCacheTest` (6): hit identity (`assertSame`, creations==1),
  cross-instance key fail-closed with stored entry still resolvable,
  per-component independence, invalid ids, component- and span-cap
  stop-before-create with earlier entries resolvable. Non-vacuous.
- `TextLayoutPlannerMaskMetadataTest` (8): shared-geometry instance identity
  (`===`), group/component ids, pairwise-disjoint cells, per-block text
  provenance through the equalization copy, single-block cell = mask bounds,
  distinct components get distinct ids, tie → no metadata but result kept,
  empty-runs → no metadata + legacy placement assertions, >64 components and
  >32 unique masks and 129 references each pinned to full cardinality.
  Non-vacuous.
- Instrumented `componentClipIntersectsCellRect`: meaningful zero-alpha
  proofs of path∩rect intersection; compile-gated only in this slice (NOTE 5).

## Verification commands run (reviewer-executed)

1. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.segmentation.*" --tests "eu.kanade.translation.rendering.*"`
   → **BUILD SUCCESSFUL in 24s.** JUnit XML aggregate: **104 tests, 0 failures,
   0 errors, 0 skipped** across 13 suites: MaskGeometryOrderedRleTest 8,
   ComponentClipCacheTest 6, TextLayoutPlannerMaskMetadataTest 8,
   MaskGeometryTest 7, MaskGeometryStressTest 2, BubbleSegmentationDecoderTest 8,
   TextLayoutPlannerTest 31, PageTextRendererDirectionTest 8,
   TextLayoutPlannerStrokeTest 4, RenderColorEstimatorTest 7,
   RenderColorEstimatorSamplingTest 5, RenderColorEstimatorDedupTest 7,
   RenderColorEstimatorLayoutSamplingTest 3.
2. `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat :app:compileDevDebugAndroidTestKotlin`
   → **BUILD SUCCESSFUL in 22s.** (Instrumentation execution remains deferred
   to the slice-8 device gate; no emulator available, consistent with slice 1.)
3. Allocation-probe liveness check (standalone single-file Java run with the
   same JBR, repo untouched): `com.sun.management.ThreadMXBean`
   `isThreadAllocatedMemorySupported() == true` and a 1 MB allocation produced
   `deltaBytes=1000232` — confirms the 100k-islands test's `< 16 MB` assertion
   genuinely executes on this host (not silently skipped), matching the
   implementation report's claim.
4. `git show 18cea91 --name-status` / `--stat`; `git status --short` (clean);
   removed-lines diff of `TextLayoutPlanner.kt` and full diffs of
   `MaskGeometry.kt`/`PageTextRenderer.kt` against `1822f3a` (baseline for
   byte-identical claims); `BubbleMaskRle.kt` unchanged in this commit.

## Architecture-review finding closure relevant to slice 2

- Finding 3 (limits exceeded before fallback): resolved for this slice — caps
  precede arrays/components/paths (table row 2), string keys eliminated,
  compact pair cache with instance verification.
- Finding 6 (instrumentation uncompilable): compile gate green (slice 1
  outcome re-verified here).
- Findings 1/2/4/5 concern later slices' contracts (identity model, disjoint
  cells, shaping, free text); slice 2 does not pre-empt or contradict them.
