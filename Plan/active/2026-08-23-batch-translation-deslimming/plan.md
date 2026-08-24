# Batch Translation Deslimming — 2026-08-23

Approved scope (user decision): Phases 1–4, rolling context becomes always-on recent
translated pairs (analytical-mode pref is deleted).

Context: audit found the AI batch path bypasses `SequentialBatchCoordinator` with a
fully-serialized inline loop, the Phase 6 scene-context system is inert in production
(model never told the delta grammar; `parseBatch` never extracts deltas), and several
support files are dead. Full audit in conversation 2026-08-23.

## Phase 1 — pure dead code (no behavior change)

- Delete `translator/InactivityFlusher.kt` (+ its test coverage in `Checkpoint2IntegrationTest.kt`
  if that file only covers it).
- Delete `batch/DynamicPageChunker.kt` + test; replace
  `DynamicPageChunker.targetPageCount(...)` with `SequentialBatchCoordinator.MAX_AI_ENVELOPE_PAGES`.
- Delete `TranslationWorkItem.kt` + `TranslationManager.observeTranslationWork` (no consumers).
- Delete unwired `ReaderViewModel.startCurrentChapterTranslation` / `cancelCurrentChapterTranslation`.
- Strip `BatchTranslationProtocol` to `VERSION` + id helpers (markers never rendered/required).
  Inline `renderRequest` as `joinToString` in `ContextualRequestBuilder`.
- Delete `batch/RollingContextPacket.kt`; collapse its prompt overloads to the string form.
- KEEP `TranslationContextChunkPlanner.updateRollingContext` (dead today; becomes the
  Phase 2 rolling-context implementation).

## Phase 2 — scene-context system removal

- Delete `batch/SceneContextEngine.kt`, `batch/SceneContextDeltaParser.kt`,
  `batch/ScenePrefixPlanner.kt`, `SceneContextEngineTest.kt`, related tests.
- Pipeline: remove `replaySceneCheckpoints`, per-page scene commit + correction
  invalidation in `translateChunkAi`, `ambiguityPrior`; refusal detection now fails only
  the refusing page (no prefix-stop cascade).
- Model/store: drop `batchSceneCheckpoint`, `batchContextCheckpointHash`,
  `batchContextComplete`, `ContextCheckpointState`/`BatchContextCheckpoint`,
  reason codes `READER_ADHOC_NOT_BATCH_COMPLETE` / `CONTEXT_CHECKPOINT_INVALID`.
  Verify store decode tolerance (`ignoreUnknownKeys`) for old persisted JSON.
- Fingerprints: drop prior from translation fingerprint (reader/batch fingerprints unify →
  reader-adhoc reuse unconditional).
- Rolling context: always-on recent translated pairs via `updateRollingContext` +
  `contextPrefix` (batch AND reader single-page path).
- Delete analytical-mode pref + `buildFutureContext` / `buildPastTranslations` /
  `withSlidingContext` + settings toggle.
- Delete `batchRelationshipAmbiguityPrior` pref + domain enum + settings screen entry.
- Artifact store: stop writing per-page context checkpoint files; tolerate old manifests.

## Phase 3 — AI batch through the coordinator

- Single path: `coordinator.runPass1` for ALL translators; AI flag lives in the
  translator-lane worker (StreamingChunkPlanner stays). Tail flush after runPass1.
- Delete the inline AI while-loop (TranslationPipeline ~2536–2667) and
  `batch/PageAtomicChunkPlanner.kt` + test.
- Delete `translator/GlossaryExtractor.kt` + test.
- `translateAiChunkWithAdaptiveRetry`: drop `allowFailureSplit` (always allow).
- Add/extend coordinator test asserting translation overlaps native lookahead.

## Phase 4 — queue semantics + polish

- `ChapterTranslator.stop()`: TRANSLATING → QUEUE (not ERROR).
- Chapter failure no longer `stop()`s the whole queue; chapter → ERROR, queue continues.
- `stampBatchProvenance`: explicit stage parameter instead of description string matching.
- `TranslationContextChunkPlanner` DEFAULT profile `maxContextTokens` 8192 → 32768.

## Validation

Per phase: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`
(+ reader tests when ReaderViewModel touched; domain tests when prefs touched).
Final: `spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest`.

## Out of scope (next refactor)

Viewport-first batch ordering, lifting reader-auto suppression (TranslationManager
`updateAutoWindow`), foreground service, OCR/inpaint double-decode fix.

## Progress

- [x] Phase 1
- [x] Phase 2
- [x] Phase 3
- [x] Phase 4
- [x] Final gate
