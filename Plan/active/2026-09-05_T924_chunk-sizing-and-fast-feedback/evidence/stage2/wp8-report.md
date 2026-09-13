# T924 Stage 2 — WP8 report (persisted layout track: projection + fingerprint)

Slice owner: Implementer (WP8 only; WP9 explicitly NOT in this slice) · 2026-09-05
Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `7c301bc`.
**Uncommitted by directive** (orchestrator owns commits). No `git add`/`git commit` performed.
MAIN worktree untouched except this report.

## 1. Scope delivered

| # | Item | File |
|---|---|---|
| 1 | Serializable projection of the runtime layout result + rehydration | NEW `app/src/main/java/eu/kanade/translation/rendering/LayoutDrawPlanProjection.kt` |
| 2 | T924-FP-07 assembling wrapper + rendering-owned fingerprint constants + conservative platformShapingKey | NEW `app/src/main/java/eu/kanade/translation/rendering/DrawPlanFingerprint.kt` |
| 3 | `RenderColorEstimator` version id (T924-FP-08 input) | EDIT `rendering/RenderColorEstimator.kt` — +10 lines, additive `const val COLOR_ESTIMATOR_VERSION = 1` (`:48`), no behavior change |
| 4 | Tests | NEW `rendering/LayoutDrawPlanProjectionTest.kt` (5), NEW `rendering/DrawPlanFingerprintTest.kt` (7) |

**`rendering/TextLayoutPlanner.kt` has ZERO diff** (verified `git diff --stat` empty). The
task allowed additive changes; zero changes is the strongest possible
"planning behavior byte-identical" guarantee — the projection lives in a
separate pure object consuming the planner's output.

No dispatch/publication/hydration was added (that is WP9). FF-02
(`TranslationPreferences.translationBatchPersistedLayout()`, domain `:255`) was
read-only verified to exist; never consulted by this slice.

## 2. Contract anchors re-verified (worktree, file:line)

| Contract item | Anchor (verified this slice) |
|---|---|
| Durable DTO (schemas contract §1.6/§1.7, landed Stage 1) | `artifact/ChapterDrawPlan.kt` — `DrawPlanRect` :20, `DrawPlanFontIdentity` :32, `DrawPlanMaskComponentRef` :52, `DrawPlanPositionedLine` :65, `DrawPlanAlign` :74, `DrawPlanBlock` :78 (per-block validationError :103), `PageLayoutDrawPlan` :120 (SCHEMA_VERSION/KIND/MAX_BLOCKS :161-167), `ColorStylePreparation` :208 |
| T924-FP-07 builder signature (Stage 1 substrate) | `artifact/StageFingerprints.kt` `layoutCompatibilityFingerprint` :395 — 17 params: 7 legacy `layout` inputs then font asset name/sha256, typeface/style, paint flags, planner version, platformShapingKey, stroke policy version, decode sample size, page width/height (`toRawBits` :430-431) |
| T924-FP-08 builder (for the estimator version id) | `StageFingerprints.colorStyleFingerprint` :442 (`colorEstimatorVersion` first input) |
| Text normalization / excerpt hash (used for mask content hash) | `StageFingerprints.normalizeText` :493, `sourceExcerptHash` :501 |
| Runtime layout types | `rendering/TextLayoutPlanner.kt` — `BlockLayout` :118-170 (embeds mutable `TranslationBlock` :119, page-local `planGeometryId` :137, `maskComponentId` :138, occupancy structures :156-162, `maskUsable` :169), `PositionedLine` :85, `TextAlign` :74, `InputIdentity` :177, `LayoutResult` :209, `PageLayoutPlan` :224, `plan` :579, `planPage` :599, placement sort (score desc, index asc) :636-639, `STROKE_WIDTH_FRACTION` :556, `MIN_STROKE_PX` :557, `computeStrokeWidth` :3734 |
| Font/paint identity the DTO must mirror | `ui/reader/viewer/TranslationOverlayView.kt` :35-49 — `R.font.animeace` forced `Typeface.BOLD`, fill+stroke paints `ANTI_ALIAS_FLAG or SUBPIXEL_TEXT_FLAG` (read-only; not edited) |
| Canonical serialization (T924-SC-06) | `artifact/ChapterDocumentIo.kt` `ArtifactDocumentJson` :207 (`ignoreUnknownKeys=true; encodeDefaults=true`) — the ONLY Json used by the projection's encode/decode |
| FF-02 accessor (exists, untouched) | `domain/.../TranslationPreferences.kt` `translationBatchPersistedLayout()` :255, default OFF |
| platformShapingKey policy | implementation-sequence §S2 ("platformShapingKey = SDK-int conservative"); flags doc §1.4 / contracts §7.5 recommendation (start maximally conservative) |
| Gate 7.1 round-trip budget | flags doc §2.7 row 7.1: ≤ 0.5 px at 1× for line origins/extents; exact equality for IDs/Line numbers are current-state anchors, not baseline citations (finding-5 rule).

## 3. Diff summary

```
NEW  rendering/LayoutDrawPlanProjection.kt   (object, 234 lines)
NEW  rendering/DrawPlanFingerprint.kt        (object, ~150 lines)
EDIT rendering/RenderColorEstimator.kt       (+10, additive constant only)
NEW  test rendering/LayoutDrawPlanProjectionTest.kt  (5 tests)
NEW  test rendering/DrawPlanFingerprintTest.kt       (7 tests)
     rendering/TextLayoutPlanner.kt          (NO diff)
     artifact/**, pipeline/**, domain/**     (NOT touched)
```

Working tree also carries the two parallel agents' in-flight files
(`pipeline/batch/*`, `translator/contextual/*`); never touched. Two gradle
wait-retry cycles consumed on their mid-edit compile breaks and one on
`classes.jar` file-lock contention, per protocol; all resolved by waiting.

## 4. Projection design (gate 7.1 round-trip)

`LayoutDrawPlanProjection` (all pure, planner untouched):

- `projectToDrawPlan(results, pageWidth, pageHeight, decodeSampleSize,
  fontIdentity, platformShapingKey)` :64 — iterates
  `PageLayoutPlan.resultsInInputOrder`; every `LayoutOutcome.Draw` becomes a
  `DrawPlanBlock` in input order; constants
  `LAYOUT_PLANNER_VERSION`/`STROKE_POLICY_VERSION`/`STROKE_COLOR_POLICY_VERSION`
  come from `DrawPlanFingerprint`.
- `projectBlock` :86 — direct field mapping; `TextAlign`↔`DrawPlanAlign`
  name-for-name; `clipRect`/`cellRect` FloatRect→DrawPlanRect componentwise;
  `positionedLines` keep text/leftPx/topPx/layoutWidthPx/layoutHeightPx
  (Ints exact); `maskComponentRef` = {sha256 over `MaskGeometry.stableKey`,
  componentId} when both maskGeometry and maskComponentId exist
  (`maskGeometryContentHash` :52 — durable replacement for page-local
  `planGeometryId`); `lines` always projected; `maskUsable` direct.
- `rehydrate(plan, blocks, maskGeometryResolver?)` :140 — resolves each plan
  block to its input `TranslationBlock` (index-validated id match first,
  then first id match; blank id falls back to inputIndex; unresolved id =
  skip, caller falls back per T924-FF-02b — never mis-draws), then returns
  `BlockLayout` values in RENDER order (planner's own deterministic sort,
  score desc then input index, TextLayoutPlanner.kt:636-639).
- `encodeToCanonicalJson`/`decodeFromCanonicalJson` :221/:225 — through the
  shared `ArtifactDocumentJson` only (T924-SC-06; no bespoke Json).

Round-trip exactness: floats carry their bits end-to-end (DTO is all-Float;
kotlinx emits round-trippable decimal literals), Ints exact, strings exact —
gate-7.1's ≤ 0.5 px budget is met with margin ZERO (exact equality), asserted
via `toRawBits()` comparisons in tests.

Excluded by contract (schemas contract §1.6) and honestly degraded, never
silently wrong:

| Excluded runtime field | Rehydrate behavior |
|---|---|
| `TranslationBlock` payload (colors/translation/masks) | by reference from caller-supplied OCR-snapshot blocks — exactly the planner's input model |
| `PositionedLine.conservativeOccupancy` / `BlockLayout.conservativeOccupancy` | planning-only; rehydrated layouts are draw-terminal (WP9), restored as the un-inflated line rect |
| `MaskGeometry` object / `planGeometryId` | `maskGeometry = null` unless WP9 supplies `maskGeometryResolver`; `maskComponentId` + `cellRect` restored into `hardClip`; pixel paths rebuild at hydration (final-target §3) |

## 5. FP-07 field mapping table (fingerprint param → source)

`DrawPlanFingerprint.layoutCompatibilityFingerprint` (:121) delegates 1:1 to
`StageFingerprints.layoutCompatibilityFingerprint`; thinness is pinned by a
test asserting byte-equality with the direct call.

| StageFingerprints param | Source at the rendering boundary |
|---|---|
| translationArtifactId | caller (WP9 wiring; OCR/layout artifact id) |
| cleanedImageArtifactIdOrOriginalSourceId | caller (WP9 wiring) |
| layoutEngineVersion | caller (layout engine identity) |
| fontIdentity | caller (font identity string) |
| fontScalePreferences | caller (user font-scale prefs) |
| stylePreferences | caller (style prefs) |
| outputDimensions | caller (layout output dims) |
| fontAssetName | `DrawPlanFingerprint.FONT_ASSET_NAME` = `"font/animeace.ttf"` (:55; mirrors `R.font.animeace`) |
| fontAssetSha256 | `DrawPlanFingerprint.fontAssetSha256(bytes)` (:99) — SHA-256 lowercase hex over the bundled asset bytes; caller reads `res/font/animeace.ttf` (Android-bound) once |
| typefaceStyle | `DrawPlanFingerprint.TYPEFACE_STYLE` = `"BOLD"` (:58) |
| paintMeasurementFlags | `DrawPlanFingerprint.PAINT_MEASUREMENT_FLAGS` = `"ANTI_ALIAS\|SUBPIXEL_TEXT"` (:61) |
| layoutPlannerVersion | `DrawPlanFingerprint.LAYOUT_PLANNER_VERSION` = 1 (:38; also persisted in `PageLayoutDrawPlan.layoutPlannerVersion`) |
| platformShapingKey | `DrawPlanFingerprint.platformShapingKey()` (:68) = `sdkShapingBucket(Build.VERSION.SDK_INT)` — raw SDK int + nearest `Build.VERSION_CODES` name (e.g. `sdk34-UPSIDE_DOWN_CAKE`, `sdk36-BAKLAVA`); unknown future SDK keeps the highest known name but its own int, so keys never collide (conservative, decision 7.5) |
| strokePolicyVersion | `DrawPlanFingerprint.STROKE_POLICY_VERSION` = 1 (:46; version of `STROKE_WIDTH_FRACTION`/`MIN_STROKE_PX`, TextLayoutPlanner.kt:556-557) |
| decodeSampleSize | planner `sampleSize` input (`scale = 1/sampleSize`) |
| sourcePageWidth/Height | planner `pageWidth`/`pageHeight` (floats, `toRawBits`) |

SSIV pan/zoom/holder-size/orientation: NO parameter exists on the wrapped
builder and none is added — presentation transforms are structurally not
inputs (final-target §3); the thinness test proves no hidden input.

Also delivered: `RenderColorEstimator.COLOR_ESTIMATOR_VERSION = 1` (:48) — the
T924-FP-08 `colorEstimatorVersion` input, consumed by
`StageFingerprints.colorStyleFingerprint` and `ColorStylePreparation`.

## 6. Verification

Commands (worktree, `JAVA_HOME` = Android Studio JBR):

- `./gradlew :app:compileStandardDebugKotlin` — BUILD SUCCESSFUL (after
  foreign-agent mid-edit breaks cleared; 5 wait-retry cycles total, none on my
  files).
- `./gradlew :app:testStandardDebugUnitTest --tests
  "eu.kanade.translation.rendering.*"` — BUILD SUCCESSFUL.
  **25 rendering suites, 222 tests, 0 failures, 0 errors, 0 skipped.**

New suites:

| Test | Count | Covers |
|---|---|---|
| `LayoutDrawPlanProjectionTest` | 5 | gate 7.1: real-planner round trip (project → serialize → decode → re-validate → re-encode byte-identical → rehydrate) over 7 representative blocks — multi-line positioned lines, shared-mask pairs (maskComponentRef + cellRect), clip-path LEFT anchor (clipRect), vertical TTB CJK columns, sub-pixel fractional floats, mixed alignment (asserted ≥ 2 distinct anchors); render-order preservation; non-draw exclusion + input ordinals; stroke width varies with decode sample size and survives; stale-input rehydration skips (never mis-draw); distinct mask contents → distinct durable hashes |
| `DrawPlanFingerprintTest` | 7 | 14-field sensitivity matrix (every FP-07 param change flips); wrapper thinness (byte-equal to direct StageFingerprints call); wrapper-pinned font/paint constants proven fingerprint-relevant; determinism (32 repeats + equal-input equality); SDK bucket table (26→O, 27→O_MR1, 34→UPSIDE_DOWN_CAKE, 35→VANILLA_ICE_CREAM, 36→BAKLAVA; distinct int ⇒ distinct key; unknown future int no collision); font digest shape/content sensitivity; version constants pinned |

Regression anchor: ALL pre-existing rendering tests pass unchanged
(23 suites, 210 tests, incl. `TextLayoutPlannerTest` 31,
`TextLayoutPlannerMaskMetadataTest` 10, `TextLayoutPlannerStrokeTest` 4,
`TextLayoutCoordinatorTest` 14) — planning behavior byte-identical.

## 7. Deviations (explicit, none silent)

1. **TextLayoutPlanner.kt zero-diff instead of additive edits.** Projection
   placed in a new object rather than extending the planner. Stronger
   byte-identical guarantee; same contract surface.
2. **Test file names.** Stage-0 WP8 names (`artifact/DrawPlanDtoRoundTripTest`,
   `rendering/DrawPlanCompatibilityTest`) are Stage-7 exit oracles spanning
   store publication + hydration wiring (WP9). This slice delivers the
   round-trip + compatibility substance in
   `rendering/LayoutDrawPlanProjectionTest`/`DrawPlanFingerprintTest`;
   publication-level DTO tests belong with the WP9 store wiring.
3. **Blank `stableBlockId` projects as-is (empty), plan then fails DTO
   validation.** Never invent ids (provable-only rule). Real OCR snapshots
   carry `pN_bN`; blank id means "unpublishable plan", surfaced by
   `validationError`, not silently remapped.
4. **`lines` always projected** (contract lists it for legacy stacked/vertical
   mode). Maximal fidelity; `encodeDefaults=true` already writes them.
5. **Render-order reconstruction in rehydrate** via the planner's documented
   sort (score desc, inputIndex asc) — the DTO intentionally stores input
   order with `inputIndex` per block; ordering is derivable, not persisted.
6. **`sdkShapingBucket` = raw SDK int + nearest VERSION_CODES name.** Name
   precision below API 26 is coarse (app minSdk 26 anyway); the raw int makes
   every distinct SDK distinct, satisfying decision 7.5's "maximally
   conservative" start.
7. **`fontAssetSha256` takes bytes, not Context.** Android-free pure digest;
   the asset read is a one-line caller concern (ui/ is outside my ownership —
   noted for WP9 wiring).
8. **`%02x` default-locale hex formatting** matches the pre-existing shared
   encoding core (Stage-1 risk item); kept consistent deliberately.

## 8. Remaining risks

1. **JSON float round-trip** relies on kotlinx's shortest-round-trippable
   float literals (verified by tests on this JVM); platform JSON behavior is
   JVM-deterministic, risk low.
2. **Render-order derivation** is coupled to the planner sort at
   TextLayoutPlanner.kt:636-639; `LAYOUT_PLANNER_VERSION` must bump if that
   sort ever changes (documented in DrawPlanFingerprint KDoc context).
3. **Production font digest not yet recorded** — needs one Android-side read
   of `res/font/animeace.ttf` (WP9 wiring) to publish real `assetSha256`;
   tests pin the digest function, not the asset bytes.
4. **`conservativeOccupancy` reconstruction** is the un-inflated line rect.
   Safe while hydrated layouts stay draw-terminal (WP9 contract); if a future
   path re-plans hydrated layouts, occupancy must be recomputed, not reused.
5. **Parallel-agent churn**: shared-worktree builds remain contention-prone
   (`classes.jar` locks, daemon cache collisions); final green run executed
   after their files stabilized.

## 9. WP9 handoff (not done here, by scope)

FF-02 dispatch in `BatchRenderJoin`, `PersistedLayoutHydrator`,
`TextLayoutCoordinator` preference + stale-bind defense,
`ReaderTextLayoutCache` hydrated-only storage, CAS publication
(T924-TX-23), and the `layoutPlans`/`colorPreparations` pointer writes use:
`LayoutDrawPlanProjection.projectToDrawPlan/rehydrate`,
`DrawPlanFingerprint.{drawPlanFontIdentity, fontAssetSha256,
layoutCompatibilityFingerprint, platformShapingKey}`,
`RenderColorEstimator.COLOR_ESTIMATOR_VERSION`. Gates 7.3-7.8 remain WP9's.
