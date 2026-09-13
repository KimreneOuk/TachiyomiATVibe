# T924 Stage 2 progress record — WP8 landed, WP9 open (NOT an exit)

Date: 2026-09-05 · Progress record only; Stage 2 has NOT exited.
Slice report: `evidence/stage2/wp8-report.md`. Commit: `1d3fcb1`
(orchestrator-committed; implementer work left uncommitted by directive).

## What landed (WP8 — layout projection + fingerprint)

- `rendering/LayoutDrawPlanProjection.kt` (NEW): pure projection
  runtime `LayoutResult`/`PageLayoutPlan` to the S1 `PageLayoutDrawPlan` DTO
  and back (`projectToDrawPlan`/`rehydrate`); canonical JSON through the
  shared `ArtifactDocumentJson` only (T924-SC-06); durable mask-content hash
  replaces the page-local `planGeometryId`; render-order reconstruction via
  the planner's documented sort; stale-input rehydrate skips (never
  mis-draws, FF-02b-directional).
- `rendering/DrawPlanFingerprint.kt` (NEW): T924-FP-07 assembling wrapper —
  thinness test-pinned byte-equal to the direct
  `StageFingerprints.layoutCompatibilityFingerprint` call; pinned
  font/paint constants; conservative SDK-bucket `platformShapingKey`
  (decision 7.5); `fontAssetSha256` pure digest.
- `rendering/RenderColorEstimator.kt` (+10, additive): `COLOR_ESTIMATOR_VERSION = 1`
  — the T924-FP-08 estimator version input.
- `rendering/TextLayoutPlanner.kt` ZERO diff — planning behavior
  byte-identical (strongest possible guarantee; FF-02 default OFF, accessor
  read-only verified, never consulted).
- Tests: `LayoutDrawPlanProjectionTest` (5) + `DrawPlanFingerprintTest` (7);
  rendering package 25 suites / 222 tests / 0 failures; wave-2 integration
  run 102 classes / 792 tests / 0 failures (reviewer-reproduced).
- Ledger rows: T924-R037 VERIFIED-EXIT (WP8 scope); FF-00/FF-02-flag-accessor
  rows per the wave-2 ledger update.

## What remains for the Stage 2 exit (WP9 + gates)

- WP9: FF-02 dispatch in `BatchRenderJoin` (LAYOUT_PREPARE orchestration),
  `PersistedLayoutHydrator`, `TextLayoutCoordinator` preference +
  stale-bind-generation defense, `ReaderTextLayoutCache` hydrated-only
  storage, CAS publication (T924-TX-23), `layoutPlans`/`colorPreparations`
  pointer writes.
- Production `assetSha256(res/font/animeace.ttf)` recorded + pinned (gap 6);
  `platformShapingKey()` exercised once on-device (gap: JVM SDK_INT=0);
  hydration-loss fallback contract test (gap 7 / F5);
  `LAYOUT_PLANNER_VERSION` bump discipline if the planner sort changes.
- Gate rows 7.1-7.7 at exit: 7.1 round-trip substance is done (exact, beyond
  the ≤0.5 px budget) but the Stage-7 exit-oracle files
  `DrawPlanDtoRoundTripTest`/`DrawPlanCompatibilityTest` are WP9's to
  deliver; 7.3-7.7 (invalidation, stale hydration, restart/LRU rehydrate on
  Pager AND Webtoon, fallback correctness) are open. Gate 7.8 (DISPLAY_READY
  redefinition) is explicitly NOT in Stage 2 — it moves to S7.

Obligations binding the WP9 kickoff are recorded in
`implementation-sequence.md` §Wave-2 review obligations (wave-2 review
ACCEPT-WITH-FIXES).
