# Investigation - Unified Translation Pipeline Recovery

## Method

Authority followed `AGENT.md` and `docs/project_context/`:

1. Live code and call paths.
2. Existing tests and build configuration.
3. Git history and runtime-relevant state transitions.
4. Current docs and active plans only as intended direction.

Content searches used the repository grep tooling. No production files were
edited during investigation.

## Observed entry paths

### Manual

- `ReaderViewModel.translateSinglePage` resolves the stream and calls
  `TranslationScheduler.translatePage`.
- `TranslationScheduler` calls `TranslationExecutor.translateSinglePage`.
- `TranslationPipeline.translateSinglePage` runs a fused native phase through
  `translateSinglePageOnnx`, then a permit-free translation/render phase through
  `translateSinglePageHttpRender`.
- Relevant entry points:
  - `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:1821`
  - `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt:381`
  - `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:617`

### Auto

- Reader auto windows are ordered and deduplicated by `TranslationScheduler`.
- They call the same single-page executor methods as manual translation.
- The scheduling loop is at
  `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt:85-269`.

### Chapter pre-translation

- `MangaScreenModel.confirmChapterTranslation` calls
  `TranslationManager.translateChapter`.
- `ChapterTranslator` owns the queue, shared store, ordered page streams, tracker,
  and `TranslationPipeline.translateBatch` invocation.
- `translateBatch` supplies batch-only OCR/inpaint/translation/render adapters to
  `BatchCoordinator`.
- Relevant entry points:
  - `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt:883-891`
  - `app/src/main/java/eu/kanade/translation/TranslationManager.kt:189-192`
  - `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt:379-477`
  - `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:769`

## Confirmed findings

### F1 - AI inactivity flush can consume results without committing a page

`TranslationPipeline` drains `StreamingChunkPlanner.flushRemaining()` but passes
only `finalChunk` into `InactivityFlusher`. The callback invokes
`translateChunkAi(..., emptySet(), ...)`. Validation, store persistence, progress,
and rendering are performed only for `completedPages`.

- Drain: `TranslationPipeline.kt:1475-1477`
- Empty completion set: `TranslationPipeline.kt:1370-1374`
- Commit loop: `TranslationPipeline.kt:1084-1103`

An inpaint longer than 250 ms makes this path likely. Provider output may mutate a
detached/transient page while the store remains `translationStatus=RUNNING`.

### F2 - The current native schedule is page-interleaved

`BatchCoordinator.runPass1` holds one semaphore around both `runOcr` and
`runInpaint` for a page. The next page cannot enter OCR until current-page inpaint
leaves the permit.

- Semaphore and page job: `BatchCoordinator.kt:59-98`
- Same-page inpaint: `BatchCoordinator.kt:103-130`
- Next-page admission only after `withPermit` exits: `BatchCoordinator.kt:148-153`

Actual ordering is `OCR 0 -> inpaint 0 -> OCR 1 -> inpaint 1`, not an all-OCR
phase.

### F3 - Coordinator translation events do not prove provider dispatch

The coordinator emits `translationRequested`, calls
`translatorWorker.translate`, then emits `translationFinished`. For contextual
AI, that worker can only call `planner.accept`; the provider request occurs later
on a size, inactivity, or final flush.

- Coordinator event boundary: `BatchCoordinator.kt:73-85`
- Planner acceptance: `TranslationPipeline.kt:1401-1418`
- Provider call: `TranslationPipeline.kt:1028-1037`

Existing coordinator tests therefore validate an adapter callback, not actual
provider timing or durable translation completion.

### F4 - The claimed backpressure invariant is not implemented

On a full channel, the coordinator performs inpaint and calls
`releaseNativeResources`, but the suspending `translationChannel.send(item)` is
still inside `nativeLanePermit.withPermit`. The production release helper also
does not own the `BatchOcrHandle.decoded.bitmap`, so its comment overstates what
it releases.

- Full-channel path: `BatchCoordinator.kt:114-125`
- Production release adapter: `TranslationPipeline.kt:1330-1344`

The replacement design must enqueue only after the bitmap and native admission
are actually released.

### F5 - A capacity-2 provider queue cannot satisfy the requested OCR sweep

With one serialized remote provider lane, a slow request fills a capacity-2
queue and blocks the OCR producer. Completing chapter OCR independently of
provider throughput requires a chapter-bounded queue of small page references,
not full page images or a fixed two-item bottleneck.

This is a design consequence of `BatchCoordinator.kt:59`, not a separate runtime
bug.

### F6 - Current whole-page version checks are too coarse for independent stages

`ChapterTranslationStore.patchPage` rejects any page-version mismatch. If
translation and inpaint update one page concurrently, an otherwise valid stage
patch can be rejected because the other stage changed unrelated fields.

- Snapshot/precondition: `ChapterTranslationStore.kt:54-65`
- Whole-page version rejection: `ChapterTranslationStore.kt:141-160`
- Block patch delegates to whole-page patch: `ChapterTranslationStore.kt:184-201`

The new schedule needs stage-owned atomic merges or a verified rebase operation
that checks generation and relevant block/draft/edit preconditions against the
latest page under the store mutex.

### F7 - Reader construction can cancel the batch queue

The master translation preference defaults to false and `changes()` emits its
current value immediately. `ReaderViewModel` reacts to false by calling
`cancelAllPageTranslations(cancelBatchQueue = true)` and stopping engines.
Manga-screen pre-translation does not require that preference to be enabled.

- Preference default: `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt:56`
- Immediate emission: `core/common/src/main/kotlin/tachiyomi/core/common/preference/AndroidPreference.kt:60-65`
- Reader cancellation: `ReaderViewModel.kt:538-548`
- Batch start without the preference gate: `MangaScreenModel.kt:802-823,883-891`

### F8 - Reader state repair can rewrite live batch pages

Reader attachment calls `sweepStrandedPageStatus`, which changes every old
`PENDING`/`RUNNING` stage to `CANCELLED` after the per-page timeout without
checking active batch generation ownership.

- Sweep: `ReaderViewModel.kt:2189-2241`
- Reader-open invocation: `ReaderViewModel.kt:2268-2281`

### F9 - App-wide trim forwarding uses severity as a simple numeric range

`App.onTrimMemory` forwards every level. `TranslationManager` and
`ChapterTranslator` compare `level >= TRIM_MEMORY_RUNNING_LOW`, which also
matches numerically higher lifecycle levels such as UI hidden/background.

- Forwarding: `app/src/main/java/eu/kanade/tachiyomi/App.kt:211-215`
- Manager: `TranslationManager.kt:156-161`
- Batch cancellation/requeue: `ChapterTranslator.kt:225-242`

The user selected continued background execution except under explicit critical
memory handling.

### F10 - Structured revision is implemented only by Gemini

`ContextualTextTranslator.translateContextualStructured` defaults to an empty
batch. Gemini overrides it; OpenRouter, DeepSeek, and LM Studio only implement
the mutating contextual method. Pass 2 calls the structured method, so those
providers receive no correction results.

- Empty default: `TranslationContextChunkPlanner.kt:293-307`
- Gemini override: `GeminiTranslator.kt:43-98`
- Other provider implementations:
  - `OpenRouterTranslator.kt:42`
  - `DeepSeekTranslator.kt:41`
  - `LmStudioTranslator.kt:44`

Google, DeepL, and ML Kit implement plain `TextTranslator`; they cannot
contextually review a draft and must not claim semantic revision support.

### F11 - Manual and auto can persist Pass-1 flags but cannot start revision

The single-page HTTP/render path sends contextual providers through the Pass-1
prompt and persists the resulting `needsRevision` value, but it has no Pass-2 or
standalone review invocation. Manual and auto therefore produce reviewable drafts
only when the provider emitted a flag; they cannot resolve those flags without a
later batch path.

- Single-page contextual call: `TranslationPipeline.kt:2204-2262`
- Single-page validation/persistence path: `TranslationPipeline.kt:2268-2335`
- Batch-only revision body: `TranslationPipeline.kt:1544-1712`

### F12 - The current UI has revision progress but no revision action

`ChapterTranslationAction` exposes only `START`, `DETAILS`, `CANCEL`, and
`DELETE`. The translated indicator menu offers translate and delete, while the
manga and reader progress surfaces already render the `REVISING` phase.

- Action and menu: `ChapterTranslationIndicator.kt:40-45,184-224`
- Manga progress: `TranslationProgressSheet.kt:88-133`
- Reader progress: `TranslationSettingsSheet.kt:207-243`

The chapter translation indicator is currently rendered only for downloaded
chapters (`MangaChapterListItem.kt:181-190`). A partial manual/auto chapter may
therefore need reader-sheet access and a state-independent eligibility projection
before the manga-screen action can be shown reliably.

### F13 - Current revision eligibility is flagged-only

`RevisionPlanner.collectTargets` accepts only blocks with `needsRevision`, no
user edit, and non-blank source text. Legacy page JSON defaults
`needsRevision=false`, and standard providers do not set it, so a flagged-only
action cannot review every existing chapter.

- Planner predicate: `RevisionPlanner.kt:157-167,201-202`
- Backward-compatible block defaults: `PageTranslation.kt:277-278`

The selected design therefore needs explicit `FLAGGED` and `ALL_TRANSLATED`
scopes. `ALL_TRANSLATED` is a user-authorized reviewer scope, not an automatic
backfill or page-JSON migration.

### F14 - Draft provider and language pair are not durably recoverable

Page JSON persists OCR engine identity but not the text provider or language
pair. `ChapterTranslationSummary` currently stores expected count, terminal
outcome, unresolved count, and time only. A cold legacy chapter cannot safely
assume current global language preferences describe its existing drafts.

- Page metadata: `PageTranslation.kt:9-30`
- Summary schema: `ChapterTranslationSummaryStore.kt:17-30`
- In-memory language pair: `Translation.kt:20-21`

New summaries must record optional source/target and draft-provider metadata.
When absent, preflight must require explicit language confirmation. The selected
reviewer is always shown and may differ from the draft provider.

### F15 - Panel metadata is persisted but omitted from provider input

OCR blocks already carry `panelIndex`, `panelAssignment`, and containment, but
`TranslationPrompts.idMappedSourceLine` serializes only `id|text`. The model
therefore receives a flat ordered list despite durable panel assignments.

- Persisted block metadata: `PageTranslation.kt:237-261`
- Current flat line serializer: `TranslationPrompts.kt:13-15`

The selected minimal change keeps current sorting and emits one compact panel
header when reliable owned-panel membership changes. Unassigned/spanning blocks
remain explicit and are never forced into a panel or treated as a speaker.

### F16 - No provider currently uses tool calling or strict K/C/U

All contextual providers use plain text completion. Pass 1 expects
`ID|text|[OK]/[FLAG]`; Pass 2 currently expects `ID|Corrected Text`. There is no
tool/function schema and no keep/correct/unresolved decision protocol.

- Pass-1 and Pass-2 prompts: `TranslationPrompts.kt:118-160`
- Gemini text generation: `GeminiTranslator.kt:47-93`
- OpenAI-compatible HTTP path: `OpenAiCompatibleTranslator.kt`

Strict K/C/U is therefore a new Pass-2-only protocol. It does not replace
Pass-1 self-reporting.

## Existing reusable seams

- Native serialization and timeout quarantine:
  `TranslationPipeline.withNativeLane`, `NativeRunQuarantine`.
- OCR-only and inpaint-only batch helpers already exist as `analyzePage` and
  `inpaintPage`; manual/auto currently use the fused `processSinglePage` path.
- Atomic safe cleaned-image publication: `persistCleanedBitmap` and
  `CleanedImagePublisher`.
- Shared state owner: `ChapterTranslationStore`, active per chapter through
  `ActiveChapterStoreRegistry`.
- Pure validation and revision components:
  `TranslationBlockValidation`, `RevisionPlanner`, `RevisionMerger`, and
  `RevisionCommitter`.
- Reader text-only refresh: `PageView.overlayFingerprint` from
  `PageTranslation.overlayContentFingerprint`.
- Compute classification: `TranslatorComputeClass` distinguishes remote I/O from
  local ML Kit compute.
- Existing transient retry helper: `withTranslationRetry` exists but current
  production searches found no provider call site using it.

## Test gaps

- `BatchCoordinatorTest` asserts adapter event order, not provider invocation or
  store persistence.
- `InactivityFlusherTest` verifies timer/chunk behavior but not the lost
  `completedPages` integration.
- No focused test covers reader entry with the master preference false while a
  chapter batch is active.
- No focused test protects active batch generations from the stranded-page sweep.
- Memory forwarding tests verify delivery, not policy by Android trim category.
- No provider parity test requires every contextual provider to return a
  structured Pass-2 result.
- No test covers a standalone review of manual/auto, partial, standard-provider,
  or legacy drafts.
- No pure UI contract test covers preflight staleness, review scope, capability
  rejection, or exact K/C/U result accounting.

## Rejected or corrected assumptions

- Opening the reader does not inherently create a second store; same-chapter
  attachment is cache-first and can observe the active store. Cancellation and
  stale-state repair are the interfering paths.
- The latest cleanup commit `f079702` did not introduce the functional failures.
  The batch scheduling/result-loss code came from `e868323`; app-wide memory
  forwarding came from `7853d9a`.
- Reusing the current fused single-page function unchanged would preserve less
  code duplication but cannot implement the selected chapter-wide OCR barrier.
  The shared unit must therefore be stage operations, not the current full-page
  wrapper.
- Standard translators cannot perform a meaningful contextual self-review.
  Capability parity means shared validation/retry, not a fake Pass 2.

## Validation performed

- Verified symbols, call paths, store preconditions, lifecycle entry points, and
  relevant tests by reading live code.
- Verified commit attribution with `git log`, `git show`, and `git blame`.
- Attempted focused Gradle tests, but Gradle could not start because `JAVA_HOME`
  was unset and no `java` executable was on `PATH`.

## Validation not performed

- No automated test executed successfully.
- No APK build, emulator run, device profiling, provider request, or runtime log
  capture was performed.
