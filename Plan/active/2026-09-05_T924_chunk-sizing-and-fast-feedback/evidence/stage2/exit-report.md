# T924 Stage 2 exit report — persisted layout (WP8 projection + WP9 publication/hydration)

Date: 2026-09-06 · Verdict: **COMPLETE-PENDING-DEVICE-GATES**

Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`.
Slice reports: `evidence/stage2/wp8-report.md` (WP8, wave 2),
`evidence/stage2/wp9-report.md` (WP9, wave 3).
Reviews: `evidence/wave2-review.md` (WP8 scope ACCEPT-WITH-FIXES,
WP8 deviations 1–8 ratified) + `evidence/wave3-review.md` (WP9
ACCEPT-WITH-FIXES, fixes landed; special items 4–6 PASS/VERIFIED).

## Commit map

| Commit | Content |
|---|---|
| `7c301bc` | Base carry (not S2 scope): FF-01/FF-02 flag accessors, default OFF (`translationBatchPersistedLayout()` :255) |
| `1d3fcb1` | WP8: `rendering/LayoutDrawPlanProjection.kt` (pure projection/rehydrate, canonical JSON through shared `ArtifactDocumentJson` only), `rendering/DrawPlanFingerprint.kt` (T924-FP-07 thin wrapper, pinned constants, SDK-bucket `platformShapingKey`), `RenderColorEstimator` version input; `TextLayoutPlanner.kt` ZERO diff |
| `74302ec` | WP9: FF-02a(1) batch publication — `BatchRenderJoin.publishPersistedLayout` (TX-23 fence set, one `publishSidecarPointers` transaction) + `rendering/LayoutPlanPublication.kt` + additive store sidecar plumbing (`SidecarRead`, content-addressed sidecar names, manifest `layoutPlans`/`colorPreparations` pointers v3); FF-02a(2) reader hydration — `rendering/PersistedLayoutHydrator.kt` (typed `HydratedLayout`, 12-row invalidation matrix), `TextLayoutCoordinator` opt-in `hydrate` param with typed fallback, `TranslationOverlayView` wiring, `PersistedLayoutReaderBridge` seam (deviation D2); gap-6 JVM mechanism — font digest loaders installed (`BatchRenderJoin` :571, `TranslationOverlayView` :82, cached in `PersistedLayoutRuntime`); gap-7 hydration-loss contract (F5); + orchestrator generation-less ledger fix (disclosed; stage-3 content, see `evidence/stage3/exit-report.md`) |
| `a56f232` | Review fix F-W3-1 (durable-failure ledger success-path clear — stage-3 scope, listed for range completeness `ee858f9..HEAD`) |

## Gates status (JVM gate-oracle legs 7.1–7.7 green; on-device rows owed)

31 gate-oracle tests, all green: `DrawPlanDtoRoundTripTest` (7) +
`DrawPlanCompatibilityTest` (14) + `PersistedLayoutHydrationTest` (10).

| Gate | Status | Evidence |
|---|---|---|
| 7.1 round-trip (≤0.5 px) | PASS (JVM) | `DrawPlanDtoRoundTripTest`: canonical plan byte-stable through the real publication transaction; rehydration reproduces planner geometry bit-for-bit (`toRawBits`) — budget met exactly |
| 7.2 compatibility matrix mismatch ⇒ replan-never-mis-draw | PASS (JVM) | `DrawPlanCompatibilityTest` (14): every mismatch names its reason; unpinned/changed digest, versions, dims, sample size rows |
| 7.3 invalidation — user-edit authority | PASS (JVM) | `DrawPlanDtoRoundTripTest` :334 — user-edited translation invalidates hydration, OCR identity untouched |
| 7.4 stale hydration rejected (bind generation) | PASS (JVM) | `PersistedLayoutHydrationTest` :281 — stale bind-generation delivery dropped |
| 7.5 / 7-5b stored compat fingerprint mismatch | PASS (JVM) | `DrawPlanCompatibilityTest` :260; on-device 7.5 re-run on new-path pages owed at S7 |
| 7.6 restart/LRU rehydrate, planner-invocation counter == 0 | JVM legs PASS | `PersistedLayoutHydrationTest` ×3 (:97, :115, :142 — hydrated bind / LRU eviction + rebind / process-restart rebind); Pager/Webtoon real-holder legs OWED |
| 7.7 fallback green (Manual/Auto/legacy/corrupt/FF-off) | JVM legs PASS | `PersistedLayoutHydrationTest` ×4 + corrupt leg (:172, :181, :202, :208; round-trip :390); FF-02 OFF zero-diff (`TextLayoutPlanner.kt`, `ReaderPageImageView.kt`, both golden fixtures) |
| 7.8 DISPLAY_READY redefinition | **NOT IN STAGE 2** | Explicitly moves to S7 per `implementation-sequence.md` |

## On-device rows + Stage-7 bridge wiring OWED (wp9-report §6)

1. Gate 7.x on-screen stroke/AA parity — hydrated draw vs live-planned draw
   pixel comparison (0.5 px tolerance), Pager + Webtoon.
2. Gate 7.6 holder legs — real Pager/Webtoon holders bind a hydrated plan
   across activity recreate / LRU pressure with planner-counter
   instrumentation.
3. `PersistedLayoutReaderBridge` production install site (holder /
   ReaderViewModel context, Stage-7 wiring). Until then `hydrate` resolves
   null ⇒ planner fallback ⇒ FF-02 ON behaves as OFF for reading —
   fail-safe at every layer (review item 6 VERIFIED); publication itself
   runs independently of the bridge and is live.
4. `platformShapingKey()` on-device exercise — two SDK buckets reject each
   other's plans (JVM pins the mechanism with a synthetic key, SDK_INT = 0).
5. Gap 6 completion — the real `res/font/animeace.ttf` digest hex value
   recorded/pinned from the device resource (loaders + cache mechanism
   landed in `74302ec`).

## Deviations

Ratified: WP8 deviations 1–8 under `evidence/wave2-review.md` §5 (zero-diff
planner, oracle-file naming, blank-id projection, always-projected lines,
render-order reconstruction, SDK buckets, pure `fontAssetSha256`, `%02x`);
WP9 D1–D3 + R1/R2 under `evidence/wave3-review.md` (D2 bridge seam
fail-safe VERIFIED with publication running; D3 additive store changes, all
148 artifact tests green; R1 guarded Injekt read; R2 publication failure =
WARN + skip, republication churn never staleness). Review NOTEs F-W3-4
(hydrator check-10 reliance on F5 count contract) and F-W3-5 (unconditional
font-loader install, FF-02-OFF behavior preserved) recorded, no action.

## Verification

- Wave-3 final run: `:app:compileStandardDebugKotlin` exit 0; targeted
  suites **834 tests / 0 failures** (post-fix), reviewer-independent
  reproduction **833/0** pre-fix (JUnit XML tally, classes=107), including
  all 31 gate-oracle tests and the 148 `artifact.*` suites.
- WP8 wave-2 evidence: rendering package 25 suites / 222 tests / 0 failures;
  wave-2 integration run 102 classes / 792 tests / 0 failures
  (reviewer-reproduced).
- Scope: `git diff ee858f9..HEAD -- rendering/TextLayoutPlanner.kt
  ui/reader/viewer/ReaderPageImageView.kt` = 0 lines; both golden fixtures
  zero-diff; `SequentialBatchCoordinator.kt` untouched.

## Rollback state

FF-02 default OFF is the kill switch: `publishPersistedLayoutIfEnabled`
returns before any store mutation when OFF, the overlay never installs
`hydrate`, legacy behavior byte-for-byte. Published plans are ignored, not
deleted. Rollback = revert `74302ec` (then `1d3fcb1`); `a56f232` is
stage-3-scope ledger code.
