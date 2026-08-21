# Batch Translation Plan and Implementation Investigation

**Date:** 2026-08-19  
**Investigation only:** No source implementation was changed and no build or test was run.  
**Primary runtime snapshot audited:** `feat/npu-acceleration-and-hardware-discovery` at `4868cd24e7aa99d07f1fe705ff18818b52a683bb`, matching `origin/feat/npu-acceleration-and-hardware-discovery` after fetch.  
**Planning worktree compared:** `refactor_batch_translation_ux` at `8b531c279bf959e6a6e5314857a3aee4357bec89`.

## Questions investigated

1. How detection, segmentation, frame/panel, OCR, inpaint, translated text, and render-layout artifacts are stored.
2. How batch translation resumes from existing work and avoids repeating completed stages.
3. Why the batch drawer can report pages as ready while the reader still displays original images.
4. Why starting batch translation on a chapter with existing translation can hide that translation and appear to restart from scratch.
5. What the two batch-translation plans intended, what latest NPU actually contains, and what remains incomplete or contradictory.

## Branch and commit findings

The latest NPU checkout already matched its remote, so no merge or pull was required.

The newest NPU commits are primarily batch-translation changes rather than NPU changes:

| Commit | Purpose | Batch-related? |
|---|---|---|
| `4868cd2` | Add rolling glossary and micro-summary prompt support to AI translator abstractions | Yes |
| `0b32e68` | Add `ChunkBatchCoordinator` and connect it to `TranslationPipeline` | Yes |
| `0c666d9` | Require a verified cleaned image before binding translated overlays | Yes, reader/display contract |
| `50cf292` | Add `RollingContextManager` | Yes |
| `29d90f0` | Add dynamic page chunking with a nominal bubble-density threshold | Yes |
| `c7c50c1` | Make reader compare/display state recognize overlay translations | Yes, reader integration |
| `e2cf197` | Hardware discovery, QNN provider options, diagnostics, and NPU configuration | This is the actual NPU feature commit |

### Refactor/NPU divergence

The two branches share base `c7c50c1` and then diverge.

The refactor worktree contains an earlier implementation series that latest NPU does not contain:

- `eb9debc` — single-pass native execution;
- `c434188` — viewport priority scheduling;
- `8897970` — 15-RPM pacing;
- `6252cf5` — reasoning preference and thinking sanitizer;
- `7e6d447` — copy-on-write store publication;
- `f18a8fa` — UI redesign;
- `9e4e778` and `0a7a5bf` — reader/render fixes.

Latest NPU only contains cherry-picked equivalents of the later dynamic-chunking, rolling-context, clean-overlay, chunk-coordinator, and AI-abstraction series.

Therefore, the statement “the optimization and UI plan was implemented on NPU” is false. Those earlier commits exist only in the refactor worktree. They also should not be ported wholesale without review: the later `ChunkBatchCoordinator` bypasses or regresses portions of the earlier single-pass and priority work even in the final refactor tree.

## Current artifact storage model

The translation root is obtained from `StorageManager.getTranslationsDirectory()`. `TranslationProvider` creates a source directory and manga directory beneath it.

The effective layout is:

```text
Translations/
└── <sanitized source>/
    └── <sanitized manga title>/
        ├── <scanlator_?>chapter.json
        ├── <scanlator_?>chapter.glossary.json
        ├── <scanlator_?>chapter.summary.json
        └── <scanlator_?>chapter_images/
            └── <page key>.cleaned.<version>.jpg
```

The chapter JSON is a serialized `Map<String, PageTranslation>`, keyed by page filename/storage key.

### Artifact inventory

| Artifact | Durable representation | What is not durable |
|---|---|---|
| Original page | Existing downloaded chapter/archive image | Not copied into translation storage |
| Raw text detections | None | `PageTranslation.allTextDetections` is `@Transient` and disappears after serialization/process death |
| Detection summary | `detectionCount`, `ocrBlockCount`, recognition-engine string | Individual raw detector score/class records are not preserved independently |
| OCR | `TranslationBlock.text`, block geometry, direction, score, bubble association, panel association, image dimensions, OCR status | There is no separate versioned OCR artifact file; `ocrArtifactId` exists but has no producer or consumer |
| Inpaint erase mask | `PageTranslation.inpaintMaskBoxes`, a compact list of durable rectangles and labels | Original transient detections used to derive it |
| Bubble segmentation | Per-block `segmentationMask` as `BubbleMaskRle` | No separate segmentation artifact or segmenter provenance record |
| Frame/panel detection | Per-block `panelIndex`, `panelAssignment`, and `panelContainment` | Raw detected panel/frame boxes and detector output are not stored |
| Cleaned pixels | Versioned JPEG in the chapter companion image directory | There is no immutable display-bundle record joining image bytes and overlay metadata |
| Inpaint metadata | `cleanedImageName`, `inpaintStatus`, `inpaintRevision`, `inpaintingModeUsed` | No input fingerprint covering source bytes, model version, and settings |
| Translated text | `TranslationBlock.translation`; optional `userEditedAt` | No translator/model/language/prompt provenance per result |
| Chapter glossary | Sibling `.glossary.json` | The new rolling micro-summary is not persisted there |
| Render metadata | `renderStatus` plus block color/stroke fields and the source geometry/mask needed to recompute layout | Final `BlockLayout` values such as fitted font size, wrapped lines, origins, safe region, alignment, and clipping path are recomputed and not serialized |
| Chapter completion | Sibling `.summary.json` containing expected page count and terminal chapter outcome | It does not certify the exact stage/configuration versions used |
| Batch queue | Ordered chapter IDs in `translation_queue` SharedPreferences | Active stage, current page, generation, and rolling context are reconstructed elsewhere |

### Important model details

`PageTranslation` persists four independent stage statuses:

- `ocrStatus`;
- `translationStatus`;
- `inpaintStatus`;
- `renderStatus`.

It also carries `runGeneration` and `pageVersion` for rejecting stale concurrent writes. `ChapterTranslationStore` serializes the whole page map to the chapter JSON and publishes a `StateFlow` for live reader/UI observation.

Cleaned images are written under a new versioned filename, verified as non-empty, committed into the store, and only then is the previous file deleted. That publication ordering is sound and avoids exposing a filename before its bytes exist.

## Current resume and reuse behavior

Batch startup opens the existing chapter store, obtains the real ordered chapter page keys, and calls `preRegisterPages`. Pre-registration only adds missing placeholders and does not deliberately replace existing page entries.

### Confirmed ordering defect and settled requirement

The current implementation does not always begin batch work in chapter order. `ChapterTranslator` reads `chapter.lastPageRead` and passes it to `ResumeOrdering.forwardFirstThenBackfill`. A chapter last read at page 51 is therefore scheduled as pages 51 through the end, followed by pages 1 through 50. The progress tracker then assigns display indices from this rotated list, so the real page 51 can also be presented as batch page 1.

This behavior is not the intended meaning of resume. Reading position and translation progress are separate concerns.

The settled requirements are:

1. Every chapter batch has one canonical processing order: real chapter page 1 through the final page.
2. A fresh batch begins its scan at page 1.
3. Resuming an interrupted, paused, or partially completed batch scans from page 1 and selects the first page whose required translation artifact chain is incomplete or invalid.
4. Completed and valid artifacts encountered during that scan are reused or skipped; resume must not redo their completed stages.
5. `chapter.lastPageRead` must not rotate or otherwise determine the durable batch order.
6. Progress UI page numbers must always refer to real chapter page numbers, never positions in a rotated work queue.

Sequential chapter order is also a correctness requirement for rolling translation history. Glossary, micro-summary, and prior-page context must evolve from page 1 forward. Starting at an arbitrary reading position produces context without the preceding chapter history and then backfilling earlier pages makes the rolling context chronologically invalid.

Reader urgency remains a separate scheduling concern. If an open reader is allowed to prioritize its visible page, that temporary priority must not redefine the canonical batch order, translation checkpoint, displayed page indices, or rolling-history sequence. Any such exception needs an explicit context-reconstruction policy; it cannot silently reuse `lastPageRead` as batch state.

For each page, `BatchResumeGateDecider` delegates to `TranslationLifecyclePolicy.nextStage` and selects one of three paths:

### `SKIP_ALL`

Used when:

- the page is already reader-display-ready; or
- the cleaned image is current and the page only needs no more native work.

If `cleanedImageName` exists, the pipeline additionally verifies that the physical cleaned file exists and has nonzero length. A missing file downgrades the decision to `INPAINT_ONLY` when a current mask exists, otherwise `FULL`.

### `INPAINT_ONLY`

Used when:

- OCR is `READY`; and
- a current durable inpaint mask is available; but
- the cleaned image is missing, stale, or was produced with a different inpainting mode.

This path skips detection/OCR and reuses the persisted OCR blocks and erase mask.

### `FULL`

Used when:

- there is no page artifact;
- OCR is incomplete/failed/stranded;
- the durable erase mask is absent;
- the page artifact predates the current mask expectations; or
- the existing state otherwise cannot safely feed inpainting.

This path decodes and runs detection/OCR again, then later decodes the page a second time for inpainting.

### What is reused successfully

- Completed display-ready pages are normally skipped.
- A persisted OCR result with a current erase mask can skip OCR.
- A valid cleaned JPEG can skip inpainting.
- Existing translated source/target pairs seed the chapter glossary builder.
- The chapter glossary sidecar survives process restart.
- Accepted page artifacts remain in the chapter store across pause/cancel, subject to transient-state cleanup and generation checks.

### Reuse limitations

The resume decision does not include a complete artifact fingerprint. In particular, it does not validate all of:

- source image content/hash;
- OCR detector/model/version/settings;
- panel detector version;
- segmentation model/version;
- source and target languages;
- translation provider, model, temperature, prompt/protocol version;
- layout/render algorithm version;
- font configuration.

This creates both failure directions:

1. **Over-invalidation:** a legacy/missing mask forces full OCR even if source text and translation are still usable.
2. **Under-invalidation:** changing translation or OCR configuration may retain results produced under the prior configuration.

A concrete under-invalidation case is a target-language change. Batch admission does not compare the current source/target languages with provenance on the persisted page. A display-ready page remains `SKIP_ALL`, so its old-language translation can be reused unchanged under the new target-language setting.

The desired behavior needs explicit dependency fingerprints so only the changed stage and its downstream dependents are invalidated.

## Why the drawer reports ready pages that display originals

This is a direct mismatch between two readiness definitions.

### Latest NPU UI

`TranslationProgressSheet` classifies a page as “Readable” when:

```kotlin
page.ocrDone && page.translateDone
```

It explicitly excludes pages already in the final `DONE` state from the readable count, treating OCR+translation as a first tier that can supposedly be read while cleaning continues.

The string shown to the user reinforces that promise:

> Readable pages can be read now while cleaning finishes. Final pages have the original text cleaned away.

But commit `0c666d9` deliberately removed this dirty-overlay behavior. The reader now binds translated text only when `isTranslationDisplayReady` is true, which requires:

```text
cleanedImageName exists
AND inpaintStatus == READY
AND current inpaint revision
AND translationStatus == READY or PARTIAL
AND renderStatus == READY
AND at least one nonblank translated block
```

Therefore an OCR+translation-only page is intentionally not displayable. It remains the original page with its original text.

The `Read now` button is always enabled and simply opens the chapter. It does not check whether any requested page satisfies the reader’s display predicate.

### Refactor-worktree UI

The refactored drawer changed its presentation but retained the same semantic defect. Its succeeded/ready count includes either final `DONE` pages **or** pages where `ocrDone && translateDone`. It labels this total `Ready`, while `Read now` remains ungated.

Thus the exact old NPU wording quoted above is branch-specific, but the contract violation exists in both implementations: OCR plus translated text is counted as ready before the reader's cleaned-image, current-revision, and render requirements are satisfied.

### Conclusion

The reader is following its safety invariant. The batch drawer is using an obsolete Tier-1 contract and is making a false promise.

“Ready,” “Readable,” the chapter indicator, and `Read now` must all use the same display-readiness definition as the reader, or the UI must clearly call the earlier state something non-readable such as “Translated, cleaning pending.”

## Why starting batch can hide existing translated text and restart work

The behavior is explained by two independent problems: the UI and lifecycle gate do not agree on what counts as completed, and replacement work mutates the only live/persisted page record in place.

### A strictly completed page should be skipped

Batch start itself only advances the store generation; it does not clear page artifacts. `BatchResumeGateDecider` delegates to `TranslationLifecyclePolicy.nextStage`, and a page is skipped when all of the following are true:

- `hasRenderedResult` / `isTranslationDisplayReady` is true;
- the referenced cleaned image physically exists and is non-empty; and
- the recorded inpainting mode matches the current preference.

Therefore, a page satisfying the reader's complete display contract should not rerun OCR, translation, inpaint, or render merely because Start Batch was pressed. `prepareForcedRetry`, which explicitly clears statuses and `cleanedImageName`, belongs to the forced single-page retry path and is not called by the ordinary chapter-list batch action.

This distinction matters diagnostically: if a supposedly complete page reruns, at least one strict prerequisite was false at batch admission, or the page entered through another force/reset path. The batch currently emits insufficient structured diagnostics to make that reason visible to the user.

### Why a page can look completed while failing that gate

The chapter-list status is broader than reader display readiness. `TranslationManager.getChapterTranslationStatus` and `persistedChapterStatus` treat either `hasRenderedResult` **or** `hasRecognizedTranslation` as enough to expose a translated/warning state. `hasRecognizedTranslation` only proves OCR plus translated blocks; it does not require a current cleaned image, ready inpaint, ready render, or a physically present file.

Consequently, the UI can invite `Read now` or show a translated-looking state for a page that the resume gate correctly classifies as needing downstream work.

The known batch-admission outcomes are:

| Existing condition | Resume decision | Work that should run | Current visibility consequence |
|---|---|---|---|
| Fully display-ready, cleaned file exists, mode matches | `SKIP_ALL` | None | Last result remains visible |
| OCR and durable mask ready; cleaned image missing, stale, or mode changed | `INPAINT_ONLY` | Inpaint then render; translation should be reused | Existing result immediately stops satisfying display readiness |
| Cleaned image and OCR exist but translation/render is incomplete | `SKIP_ALL` internally (the lifecycle `RENDER` case is collapsed into it) | Missing translation and/or render is scheduled by later control flow | Intermediate status changes can hide the page |
| OCR incomplete/failed/stranded or durable mask unavailable | `FULL` | OCR, inpaint, missing translation, render | Old block list can be replaced before the candidate finishes |
| Metadata references a cleaned file that is absent or empty | Downgraded to `INPAINT_ONLY` or `FULL` | Rebuild from the minimum stage the remaining metadata permits | No valid cleaned pixels are available to display |

`SKIP_ALL` is therefore a misleading enum name: it represents both a true complete skip and the lifecycle policy's `RENDER` state, after which later code may still translate or render. This makes logs and tests harder to interpret.

### Exact destructive publication sequence

A common sequence is:

1. The chapter has translated blocks and perhaps an older cleaned image.
2. The chapter-list status treats any rendered result or recognized translation as enough to show `READY_WITH_WARNINGS`.
3. The user selects Translate again.
4. The resume gate checks current safety requirements.
5. If the artifact has an older `inpaintRevision`, no durable current mask, a missing cleaned file, or an incompatible mode, the page is classified `FULL` or `INPAINT_ONLY`.
6. On the `FULL` path, `analyzePage` replaces the existing live page’s block list with newly recognized blocks and sets inpaint to `PENDING`.
7. Those new blocks initially have blank translations. The shared reader store emits immediately.
8. `isTranslationDisplayReady` becomes false, so the reader removes the translated stream/overlay and falls back to the original image.
9. The replacement OCR/inpaint/translation/render pipeline continues.

The store update and reader reaction are concrete:

- `analyzePage` assigns `blocks = pageTranslation.blocks` and `inpaintStatus = PENDING` into the existing store entry. Newly recognized blocks have no translated text yet.
- The store publishes that update immediately through its `StateFlow` and schedules it for persistence because it contains OCR blocks and/or transitions away from a previously rendered result.
- `displayImageName` is derived from the entire current `isTranslationDisplayReady` predicate. Setting inpaint to `PENDING` makes it null even when the old `cleanedImageName` and JPEG still exist.
- The reader's `attachTranslatedStreamIfWarm` then resolves no translated stream. Overlay binding independently requires `isTranslationDisplayReady`, so neither the old cleaned image nor old translation overlay is used.

On `FULL`, this can be real artifact loss rather than a temporary visual switch: replacing `blocks` removes the prior translated block values from the only page record. There is no committed copy to restore if the candidate later fails or is cancelled.

On `INPAINT_ONLY`, translated blocks and the old cleaned filename normally remain, but setting `inpaintStatus` to `RUNNING` still makes the old bundle undisplayable. The old JPEG is only deleted after a newly written versioned JPEG commits successfully, so the bytes may still exist while the model deliberately makes them unreachable to the reader.

`CleanedImagePublisher` correctly provides atomic **file** publication: write and verify the new JPEG, commit its filename, then delete the previous JPEG. That safety does not extend to the full display bundle because status and block mutations have already invalidated the reader-facing record before file publication.

### Pause, cancel, and failure make the problem durable

The ordinary chapter-list Cancel action removes the queue entry and cancels the batch job, but it does not call `clearTransientQueuePages` for that chapter. The cancellation handler flushes the store. By that point, OCR blocks or the transition away from `hasRenderedResult` qualify the candidate state for persistence.

Even when `clearTransientQueuePages` is used by other teardown paths, it converts transient stages to `CANCELLED`; it does not restore the former statuses, translated blocks, cleaned pointer, or display generation. Since the old committed bundle was never retained separately, cancellation cannot roll back to it.

The next Start Batch then sees the persisted incomplete/cancelled candidate:

- OCR-ready plus a durable mask resumes at inpaint;
- incomplete OCR or missing mask resumes at full recognition;
- missing translation resumes translation through later coordinator logic.

This is why a user can observe both disappearance and apparent restart across pause/cancel/reopen even though some physical artifacts remain on disk.

This feels like a restart because the last known good visible result is destroyed before the replacement is ready.

### Architectural cause

The store has one mutable `PageTranslation` record serving two incompatible roles:

- the last committed display artifact; and
- the in-progress replacement generation.

Starting a refresh mutates that one record in place. Generation/version fencing protects against stale writes, but it does not preserve the prior displayable generation.

The generation counter is a concurrency fence, not an artifact generation model. `beginGeneration` and the surrounding `withGeneration` context prevent an older batch generation from winning later `updatePage` writes. However, the destructive `analyzePage` write uses the broad `updatePage` mutator rather than a page-version/block-fingerprint stage patch. It is generation-fenced but has no page-identity precondition within that active generation, and it intentionally replaces the only current block list. Each page still has only one block list, one status tuple, and one cleaned-image pointer.

### Intended behavior

The last known good display bundle should remain immutable and visible while replacement work runs. The store should atomically switch the active display pointer only after a new cleaned image and compatible overlay/layout metadata have both committed successfully.

Cancel, pause, or failure should discard or retain the incomplete candidate as appropriate without removing the committed display bundle.

### Required regression coverage

No current focused test proves last-known-good preservation across replacement work. The implementation needs acceptance tests for at least:

1. display-ready page + ordinary Start Batch → no worker invocation and no reader display change;
2. display-ready page + inpaint-mode change → old bundle remains visible until the replacement atomically commits;
3. display-ready page + forced full rebuild → old translated blocks remain committed while candidate OCR is running;
4. cancel during replacement OCR/inpaint/translation/render → old bundle becomes active again;
5. replacement failure → old bundle remains readable and the candidate error is reported separately;
6. metadata-only cleaned filename whose file is missing → reader reports recovery needed without claiming the old output is readable;
7. progress/status labels use the exact reader display predicate and distinguish “translated text exists” from “readable translated page.”

## Plan-versus-implementation audit

### 1. The two plans conflict on native execution

The optimization plan requires one decode and a fused OCR/inpaint lifecycle. The later chunk plan requires OCR for all pages in a chunk, then an independent inpaint phase.

Latest NPU implements the latter:

1. decode for OCR;
2. analyze and persist OCR;
3. recycle the bitmap;
4. decode again for inpainting.

This contradicts the single-pass performance goal.

The refactor worktree contains `runSinglePassNative`, but the later `ChunkBatchCoordinator` calls `runOcrStage` and `runInpaintStage` separately, bypassing that path. Thus the later implementation regresses the earlier design even where both commits exist.

### 2. Bubble-density chunking is inert in production

`DynamicPageChunker` accepts `bubbleCountByPage` and seals a chunk at 35 bubbles. The live pipeline invokes `coordinator.runPass1(orderedPages, computeClass)` without bubble counts.

Because chunks are computed before the new pages have been OCR’d, the coordinator does not yet know their bubble counts. As a result, production chunk boundaries are based only on total chapter page count. The advertised density guardrail is not operating.

Persisted OCR counts could guide resumed pages, but a correct fresh-page design needs either a preliminary lightweight estimate or chunk formation after OCR results arrive.

### 3. Viewport priority is inert on latest NPU

Latest NPU declares `activeBatchCoordinators` and exposes update/clear methods, but:

- no coordinator is inserted into that map;
- `ChunkBatchCoordinator.activePriorityQueue` is never assigned;
- the reader does not forward viewport changes to it.

In the refactor worktree, reader and manager forwarding exists and the pipeline registers the coordinator. However, the final coordinator used by the pipeline is `ChunkBatchCoordinator`, whose priority queue still is not initialized or used to poll pages. The wiring reaches an inert endpoint.

### 4. New rolling-context path is not the live path

`RollingContextManager`, `RollingContextPacket`, `ChunkTranslatorLaneWorker`, and `AITranslator.translateChunkWithRollingContext` exist.

The live anonymous translator in `TranslationPipeline` implements only `TranslatorLaneWorker`, not `ChunkTranslatorLaneWorker`. Consequently, `ChunkBatchCoordinator` takes its per-page branch and never calls `translateChunk` or updates its `RollingContextManager`.

The actual AI path still uses `StreamingChunkPlanner`, an in-memory rolling-context string, `ChapterGlossaryBuilder`, and the glossary sidecar. That older path may provide useful context, but it is separate from the feature described by the newest commit.

The new micro-summary state is not durable across pause/process death.

### 5. Copy-on-write publication is absent from NPU

Latest NPU retains:

```kotlin
private fun snapshotPages(): Map<String, PageTranslation> =
    pages.entries.associate { (key, page) -> key to page.detachedCopy() }
```

Every page update republishes a detached copy of every stored page. On large chapters with frequent per-stage emissions, this causes repeated whole-chapter copying.

The refactor worktree changes `_state.value` to the persistent map, but that implementation requires careful immutability review because exposing map entries without defensive copies changes the store’s ownership contract.

### 6. “Zero skip” is underspecified

The plan says no page with detected text may be skipped, while the implementation deliberately uses:

- `SKIPPED` for genuinely textless pages;
- `PARTIAL` when only some blocks translate;
- filtering/suppression for some detections;
- retry exhaustion and failure terminal states.

The invariant should instead be expressed at the artifact/block level: every accepted nonblank source-text block must reach a translated terminal outcome or an explicit user-visible failure; no accepted work may silently disappear.

### 7. “Reader as pure passive observer” is inaccurate

The reader observes the store, but it also mutates `ReaderPage.translation`, attaches or clears stream factories, maintains `showTranslatedImage`, holds per-page toggle state, manages warm-window eviction, and explicitly refreshes page holders.

The plan needs to account for this bridge. A `StateFlow` emission alone does not prove that the correct stream is attached or the correct image is decoded.

### 8. UI redesign is incomplete on latest NPU

Latest NPU still contains:

- the old readable/final tier model;
- a page-chip grid;
- an always-enabled `Read now` action;
- local remembered pause state rather than authoritative queue pause state.

The earlier UI redesign commit exists only in the refactor branch and does not itself solve the central readiness mismatch unless its counts/actions use `hasRenderedResult`/`isTranslationDisplayReady`.

### 9. Plan validation is insufficient

Several planned tests validate data structures or helper output without proving production wiring. Missing acceptance tests include:

- existing display-ready page → start batch → reader remains translated;
- valid completed stage → resume batch → that stage worker invocation count remains zero;
- missing cleaned file → rerun only inpaint/render when mask and translation remain valid;
- changed target language → translation/render invalidate, OCR/inpaint remain reusable;
- changed OCR model → OCR and all downstream stages invalidate;
- viewport changes → the next production page selected is the visible page;
- reported ready count exactly matches pages the reader can display;
- pause/process restart restores both artifacts and rolling-context behavior.

Some plan commands also use a test flavor inconsistent with the current repository instructions. Validation should use the authoritative AGENTS.md task matrix at implementation time.

## Intended target architecture

The goals point toward a versioned artifact dependency graph rather than one mutable page record.

```text
Source image identity
        │
        ▼
Detection / segmentation artifact
        │
        ▼
OCR artifact ───────────────┐
        │                   │
        ▼                   ▼
Inpaint artifact       Translation artifact
        │                   │
        └─────────┬─────────┘
                  ▼
          Layout/render artifact
                  │
                  ▼
        Atomic display-ready bundle
```

### Required properties

1. **Per-stage provenance**
   - Record source identity and stage-specific configuration/model/prompt versions.
   - Validate artifacts independently.

2. **Minimum-stage planning**
   - Invalidate only the changed stage and its downstream dependents.
   - Keep OCR and inpaint when only the translation target changes.
   - Keep translation when only render styling changes.

3. **Last-known-good display preservation**
   - Maintain a committed display generation separately from an in-progress candidate.
   - Never remove a readable translation merely because a replacement started.

4. **Atomic display commit**
   - Publish cleaned file, translation blocks, layout/render metadata, and readiness together.
   - Switch the reader to the new generation only after all display prerequisites are valid.

5. **One readiness contract**
   - Reader, drawer, chapter indicator, progress count, and `Read now` must use the same predicate.
   - If intermediate states remain visible, label them accurately rather than “Readable.”

6. **One real scheduler**
   - The production scheduler must actually own page selection, chunk formation, native-lane serialization, translator pacing, and per-page commit.
   - Its canonical batch order must always be chapter page 1 through the final page, with valid completed artifacts skipped and resume beginning at the first incomplete artifact chain.
   - Reader viewport urgency must be modeled separately and must not corrupt sequential rolling-history order.
   - Avoid parallel coordinator abstractions where only one is wired.

7. **Durable resume context**
   - Persist the rolling micro-summary or define a deterministic way to reconstruct it.
   - Store context/protocol version so incompatible context is discarded safely.

8. **Bounded memory and I/O**
   - Preserve the one-native-page-at-a-time constraint.
   - Decide explicitly whether the performance goal favors a single decode or chunk-wide OCR barriers; the current plans cannot require both without a hybrid design.

9. **Observable reuse**
   - Emit diagnostics explaining why each page chose `SKIP`, `TRANSLATE_ONLY`, `INPAINT_ONLY`, `RENDER_ONLY`, or `FULL`.
   - Test invocation counts, not only final status fields.

## Recommended investigation-to-design sequence

Before implementation:

1. Define the precise user-visible meaning of “ready,” “readable,” “final,” pause, cancel, retry, and retranslate.
2. Define the stage dependency/fingerprint matrix.
3. Decide how committed and candidate page generations coexist.
4. Decide whether native work is single-pass per page or OCR-barrier-per-chunk, including the memory/latency trade-off.
5. Consolidate `BatchCoordinator`, `ChunkBatchCoordinator`, streaming AI chunking, and viewport priority into one production scheduling design.
6. Specify migration behavior for existing chapter JSON and cleaned images.
7. Write end-to-end acceptance tests before changing the pipeline.

## Bottom line

The current implementation has useful durability foundations: page JSON, versioned cleaned files, durable masks, generation fencing, physical-file validation, and stage-aware resume decisions. However, it does not yet fulfill the plans’ overall promise.

The immediate user-visible defect is a contract contradiction: the drawer calls OCR+translation “Readable,” while the reader correctly refuses to display it without a cleaned image and ready render metadata.

The apparent restart is also real: invalidated pages overwrite their last displayable data in the shared store before replacement work completes. The correct fix is not merely another skip condition; it is preserving a last-known-good display generation and committing replacements atomically, backed by explicit per-stage provenance.

## Resulting canonical plan

This investigation is descriptive evidence. The implementation direction is now defined by:

- [2026-08-19-batch-translation-final-plan.md](./2026-08-19-batch-translation-final-plan.md)
- [2026-08-19-batch-artifact-lifecycle-contract.md](./2026-08-19-batch-artifact-lifecycle-contract.md)
- [2026-08-19-batch-ai-context-quality-contract.md](./2026-08-19-batch-ai-context-quality-contract.md)
- [2026-08-19-batch-translation-validation-matrix.md](./2026-08-19-batch-translation-validation-matrix.md)

Those documents supersede the initial plans wherever ordering, readiness, artifact reuse, committed/candidate storage, coordinator behavior, or batch AI context differs.
