# Progress — batch translation deslimming

Status: all four phases implemented. Net diff (app/src + domain/src):
49 files, +295 / −3,492 lines.

## Phase 1 — dead code (+ one real bug)

- Deleted: InactivityFlusher, DynamicPageChunker (+test), TranslationWorkItem,
  observeAllTranslationWork/workMetadata, RollingContextPacket, unwired
  ReaderViewModel.startCurrentChapterTranslation/cancelCurrentChapterTranslation,
  BatchTranslationProtocol envelope markers (kept VERSION + id helpers),
  AITranslator's packet layer (AITranslatorResponseParser, ParsedAiChunkResponse,
  translateChunkWithRollingContext, buildPromptWithRollingContext).
- BUG FOUND (pre-existing, verified failing on HEAD): ContextualResponseParser.parseBatch
  stored "content was valid" in `strictValidation`, so invalid batches
  (missing/duplicate/unknown ids) passed `isStructurallyValid`. Fixed:
  strictValidation=true, framingRecovered=contentValid. 6 parser tests green.
- Retry policy unified (pulled forward from Phase 3): structural failures no
  longer rethrow-fail the envelope; translateAiChunkWithAdaptiveRetry always
  failure-splits (allowFailureSplit param deleted; was LM-Studio-only).

## Phase 2 — scene context system removed

- Deleted: SceneContextEngine, SceneContextDeltaParser, ScenePrefixPlanner,
  SceneContextEngineTest, replaySceneCheckpoints, per-page scene commit +
  correction invalidation, ambiguityPrior, per-page context checkpoint files
  (layout write path + manifest field + store commit fn).
- Model: batchSceneCheckpoint / batchContextCheckpointHash / batchContextComplete
  removed from PageTranslation, plans, projections, snapshots, tracker,
  pageSnapshot fingerprint. Old persisted JSON tolerated (ChapterDocumentIo
  ignoreUnknownKeys). Legacy context/ artifact dirs still swept by retention.
- Prefs: batchRelationshipAmbiguityPrior + translationAnalyticalMode deleted
  (domain enum, pipeline usage, settings UI entry; analytical mode had no UI).
- Rolling context is now ALWAYS-ON recent translated pairs (both batch via
  updateRollingContext and reader single-page via store.translatedPairs()) +
  chapter glossary. Reader/batch translation fingerprints unified (prior removed)
  → reader-adhoc pages are REUSED by batch when fingerprints match (test pins it;
  old behavior forced retranslation).
- translateChunkAi commit loop: per-page commit in natural order; a structural
  refusal fails ONLY that page (prefix-stop cascade deleted).

## Phase 3 — one scheduler

- The AI batch now runs through SequentialBatchCoordinator.runPass1 like every
  other translator: native lane (OCR→inpaint) overlaps the ordered translation
  lane; the planner's final chunk flushes after the pass. The hand-rolled
  serialized while-loop (zero native/HTTP overlap) and PageAtomicChunkPlanner
  (+test) are deleted.
- 5 stale-cap tests (verified failing on pristine HEAD) aligned to current
  contracts: coordinator lookahead MAX_NATIVE_LOOKAHEAD_PAGES (=6), LM Studio
  caps via constraintsFor (28 blocks/4 pages), envelope coverage assertions.

## Phase 4 — queue semantics + polish

- ChapterTranslator.stop(): interrupted chapters → QUEUE (resumable), not ERROR.
- One chapter's unexpected failure marks that chapter ERROR and the queue
  continues (was: stop() killed the whole queue).
- stampBatchProvenance: explicit BatchStage parameter (guardedBatchUpdate
  threads it) — no more description.contains("ocr") string dispatch.
- TranslationContextChunkPlanner DEFAULT profile ceiling 8,192 → 32,768 tokens
  (cloud providers; envelope page/block caps still bound request size).
  Budget-sensitive tests now size fixtures relative to MAX_CONTEXT_TOKENS.

## Verification

- Per-phase focused runs green: eu.kanade.translation.{model,artifact,batch,translator}.*,
  root-package classes, eu.kanade.tachiyomi.ui.reader.*, :domain:testReleaseUnitTest.
- An earlier full-filter run hung once with idle JVMs (daemon state; daemons
  restarted; every subsequent run, including root-package classes, completed).
- Final tier-2 gate: spotlessCheck + :app:testStandardDebugUnitTest +
  :domain:testReleaseUnitTest (result recorded in final report).

## Known behavior changes (intentional)

1. Previously batch-translated pages retranslate once after upgrade (their
   translation fingerprints embedded the deleted prior); native stages (OCR,
   inpaint — the expensive part) remain reusable. Reader-adhoc pages keep matching.
2. Old persisted scene checkpoints/glossary-extractor output ignored; legacy
   context/ artifact files are swept by retention.
3. i18n strings for the deleted ambiguity-prior setting remain (unused, harmless).

## Full-suite gate blocker — RESOLVED 2026-08-24

`eu.kanade.translation.scheduling.RollingAutoCoordinatorTest` hung
indefinitely (blocks `:app:testStandardDebugUnitTest` as a whole task).
Root-caused with thread dumps + an instrumented probe: the test
`older same-spec snapshot build cannot overwrite newer stage` awaited a
gated resolver call that no code path can make in that state — after
prepare parks, the visible slot resolves from transient slotStates
(RollingAutoCoordinator buildSnapshot slotStates-first contract) and with
aheadTarget=0 no resolver-backed lookup exists; `buildStarted.await()`
waits forever. Deterministic since the test's creation (efb8dee); not a
coordinator bug. Fix: give the window one ahead page so listener-driven
snapshot builds must consult the resolver. Class now 22/22 green (first
green run on this machine). Full evidence chain:
investigation-rolling-auto-hang.md in this folder.

Other pre-existing failure verified on HEAD: AotReportBubbleFillTest
"reportBubbleFill preserves diagonal components" (pixel-color off-by-small in
inpainting fill; expected -16711165, got -16645372). Also unrelated.

Everything else green with the refactor applied: translation.{model, artifact,
batch, translator, root-package}.*, scheduling.{AutoWindowState,CancelSync-
StoreWrite,NativeRunQuarantine,TranslationStreamRegistry}Test, ocr.*,
recognition.*, inpainting.* (except the pre-existing failure above),
ui.reader.*, spotlessCheck, :domain:testReleaseUnitTest.
