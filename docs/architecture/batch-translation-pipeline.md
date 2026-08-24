# Batch translation pipeline (post-2026-08 deslimming)

Chapter batch translation runs on one scheduler for every translator class:

```
MangaScreenModel → TranslationManager.translateChapter → ChapterTranslator (queue)
  → TranslationPipeline.translateBatch → SequentialBatchCoordinator.runPass1
```

- **Native lane** (serialized process-wide via `NativeRunQuarantine`): OCR
  admission is sequential and owns one active image chunk. For contextual AI,
  `StreamingChunkPlanner` determines a token-adaptive whole-page envelope;
  exactly one overflow page may be retained as an OCR-only probe. The probe
  receives no AI, inpaint, or render work and cannot admit another page until
  the preceding chunk is terminal. The OCR barrier then releases the actual
  chunk, whose native inpaint branch shares the page-local decoded handoff and
  releases it after inpaint or cancellation. Inpaint-only resume continues to
  decode independently.
- **Translation lane** (single ordered consumer): after an AI chunk's OCR
  barrier, its provider request and native inpaint branch run concurrently.
  AI requests never divide a page's source blocks; the planner packs complete
  pages until the calculated provider budget would be exceeded. A dense page
  is sent alone after auxiliary context is trimmed; only source text exceeding
  the provider budget fails. Standard remote translators retain bounded
  lookahead, while local compute remains page-serial with native work.
- **Render join**: per page, awaits both translation and native prerequisites,
  then calls `tryRender` (idempotent). The next chunk is not admitted until all
  current pages render or reach their documented terminal failure path.

Context continuity is intentionally simple: the chapter glossary
(`ChapterGlossaryBuilder`, statistical, from committed pairs) plus a bounded
natural-order rolling window of recent source⇒target pairs
(`BatchContextFrontier` / `updateRollingContext`), injected via
`TranslationPrompts.contextPrefix`. Fragmented resume seeds only the contiguous
completed predecessor prefix; later completed pages remain reusable but cannot
feed an earlier missing page. Once traversal reaches them they fold into the
frontier. A non-textless terminal gap blocks later AI admission.

Resume/reuse: per-stage fingerprints (`batchExpectedFingerprints`) are shared
by reader and batch (no batch-only inputs), so a page translated in the reader
with current fingerprints is REUSED by a later batch. `PageWorkPlanner` decides
per stage; `planChapter` keeps translation gap-free in natural order.

Failure semantics: a page whose output is a structural refusal fails alone;
provider rate limiting delays and retries the same page-atomic envelope,
whereas invalid batch responses are fail-closed in
`ContextualResponseParser.parseBatch` (`strictValidation=true`,
`framingRecovered` marks exact-id responses without protocol framing).

Queue semantics: `ChapterTranslator.stop()` maps interrupted chapters to QUEUE
(resumable); one chapter's unexpected failure marks that chapter ERROR and the
queue continues. Queue membership/order persists in
`TranslationQueueStore` (SharedPreferences); statuses rehydrate as QUEUE and
require an explicit Start.

Background hosting: `TranslationForegroundService` is started after the manager
starts a non-empty batch queue. It retains the existing `ChapterTranslator`
lifecycle, publishes the active chapter's page progress, and stops itself when
no queued or translating work remains. Its notification stop action delegates
to `TranslationManager.clearQueue()`; it does not own pipeline work.

Removed on purpose (2026-08, see `Plan/active/2026-08-23-batch-translation-deslimming/`):
scene-context engine/delta parser/prefix planner (never received model input),
per-page context checkpoints, ambiguity-prior and analytical-mode prefs,
GlossaryExtractor's extra provider call, `PageAtomicChunkPlanner`, the
serialized AI-only while-loop. The streaming planner's page-atomic packing is
not a restoration of that planner. Do not reintroduce a second scheduler for
the AI path.

Gemini uses the direct `generateContent` API with explicit thinking control:
Disabled is the default where the selected model supports it, with Auto and
Low available in settings. Unsupported thinking fields are omitted; a rejected
thinking configuration retries once without that field. Gemini's HTTP status
and retry delay are logged without request text or credentials.
