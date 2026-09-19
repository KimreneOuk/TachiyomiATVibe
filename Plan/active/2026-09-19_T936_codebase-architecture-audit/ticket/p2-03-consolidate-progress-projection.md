# Ticket P2-03: Consolidate progress projection into the unified store

**Phase:** 2 (execute ONLY after P2-02 lands) | **Risk:** Medium (UI-visible progress) |
**Type:** Simplification

## Corrected evidence (audit said "delete 2 redundant projectors" — reality is subtler)

- `BatchProgressProjector.kt` (manager/, 547 lines): flows-only extraction of
  TranslationManager's progress projection (TranslationManager.kt:1031-1034 delegates to
  it; T909 Phase 11 note). NOT an independent truth system — it re-derives progress from
  store registries in parallel to the store's own projection.
- `StoreStatusProjector.kt` (store/, 216 lines): the STORE's own status projection body
  (ChapterTranslationStore.kt:431 `statusProjector` delegate). This one IS the store's
  projection — after P2-02 it remains the unified store's internal projection module
  (keeping its file is fine; it is NOT deleted).
- `TranslationBatchProgressTracker.kt` (568 lines, channel event reducer for batch
  orchestration) is OUT OF SCOPE — Phase 3 decides its fate with the coordinator rework.

## Changes

1. Delete `BatchProgressProjector.kt`. Rework TranslationManager's progress projection to
   consume the unified store's projection directly (StoreStatusProjector's outputs / the
   store manifest state flow), eliminating the parallel re-derivation. The observable UI
   progress contract must not change: batch progress states, counts, and the T934
   reader-bar truth (`T934ReaderBarTruthTest`, `T934ProjectorRebuildTruthTest`) stay green
   and semantically identical.
2. If `BatchProgressReconciler.kt` (pipeline/batch, references StoreStatusProjector's
   artifactStatus) depends on fields only BatchProgressProjector produced, rewire to the
   store projection — do not duplicate logic.
3. Do not touch `TranslationBatchProgressTracker`.

## Verification

1. `git grep -l 'BatchProgressProjector'` under app/src returns nothing.
2. Full both-flavor unit suites green — specifically no weakening of
   `T934ReaderBarTruthTest`, `T934ProjectorRebuildTruthTest`, `BatchPostPassProjectionTest`,
   `TranslationBatchProgressTrackerTotalsTest`.
3. `assembleDevDebug` green.

## Commit

`refactor(translation): derive batch progress from unified store projection, drop BatchProgressProjector`
