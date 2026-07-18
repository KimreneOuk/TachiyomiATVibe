# Initial Findings - Translation Stage Recovery

Status: Investigation complete; solution architecture recorded in `plan.md` and
`design.md`.

Date: 2026-07-18

Scheduling clarification: inpainting starts only after every expected image has
a terminal detect/OCR result. Remote translation may overlap detect/OCR.
Inpainting may overlap remote translation and rendering after the OCR barrier.

## Objective

Record current translation-pipeline behavior and failure modes before designing
the recovery, selective-reset, concurrency, and persistence architecture.

## Investigation scope

The review covered the live working tree, including:

- all 143 production files under
  `app/src/main/java/eu/kanade/translation/`;
- 104 translation unit-test files;
- reader and manga-screen scheduling, cancellation, retry, and observation call
  sites;
- the active unified-pipeline brief, design, checkpoints, and tests.

The working tree already contained extensive translation changes. Those changes
were treated as authoritative and were not modified during investigation.

## Current chapter flow

The implemented remote-provider schedule is:

```text
detect + OCR every page
wait for all OCR jobs
queue every remote translation request
start serial inpainting
attempt per-page render
reconcile chapter
```

The intended schedule in the active unified-pipeline brief is:

```text
detect + OCR page N
persist OCR and mask
release page bitmap and native resources
queue remote translation for page N
continue detect + OCR for page N+1
wait for all OCR terminal states
start serial inpainting
render after translation and inpainting branches are terminal
```

The current coordinator therefore delays remote translation unnecessarily. This
is an implementation regression, not a provider or coroutine limitation. Local
ML Kit translation is different: it should remain serialized after the OCR
barrier because it consumes local compute and memory that can compete with the
native lane.

Evidence:

- `BatchCoordinator.kt:52-84` accumulates `remoteRefs`, waits on
  `ocrJobs.awaitAll()`, and only then sends the references to the translation
  channel.
- `Plan/active/2026-07-16-unified-translation-pipeline/brief.md:62-65`
  requires a provider request to start before the chapter OCR barrier.
- `BatchCoordinatorWiredTest.kt:50-57` currently asserts the opposite behavior,
  so the test preserves the regression.

## Primary findings

### F1 - Resume policy cannot represent independent branch recovery

`TranslationLifecyclePolicy.NextStage` contains only `SKIP`, `RENDER`,
`INPAINT`, and `FULL`. It cannot express `TRANSLATE_ONLY` or a branch set such as
`TRANSLATE + RENDER`.

Consequences:

- a translation failure cannot be retried directly from durable OCR;
- a cleaned page with failed translation can be classified as render-ready and
  mapped to `SKIP_ALL`, leaving translation unretried;
- an inpaint-only resume can still feed the standard translator and repeat
  already completed translation;
- policy behavior depends on combinations of stage strings instead of explicit
  artifact validity.

### F2 - Manual retry intentionally resets the full pipeline

`ReaderViewModel.translateSinglePage()` treats any failed OCR, translation,
inpaint, or render status as a forced retry. `prepareForcedRetry()` then resets
all stage statuses and clears `cleanedImageName`.

This explains the reader-path detect + OCR rerun after a downstream stage
failure. For chapter pre-translation, a rerun occurs when durable OCR eligibility
is absent: OCR is not `READY`, the current inpaint mask is missing/stale, or
concurrent whole-page writes left an incompatible snapshot.

Detection is fused with OCR and has no independent durable artifact. It cannot
currently be retained, reset, or resumed separately from the OCR snapshot.

### F3 - Render join is not implemented as a join

The production `RenderJoinWorker` has empty native-branch and
translation-branch callbacks. Translation workers also call `tryRender()`
directly.

If translation finishes before inpainting, `tryRender()` can observe no cleaned
bitmap/file, mark both inpaint and render as failed, persist the false failure,
and remove the registry entry before native completion can recover it.

Render must begin only after both required branches reach compatible terminal
states. A mutex around `tryRender()` prevents simultaneous calls but does not
provide branch completion semantics.

### F4 - Stage-safe merge contracts are present but unused

The codebase defines `TranslationStagePatch`, `InpaintStagePatch`,
`RenderStagePatch`, relevant-field fingerprints, and corresponding
`ChapterTranslationStore.merge*()` operations. No production pipeline call site
uses the translation, inpaint, or render merges.

Current production behavior instead shares mutable `PageTranslation` objects
between translation and inpainting and commits whole pages or partial status
copies through legacy `updatePage()` calls. The batch work item also carries
`generation = 0L` and an empty fingerprint list.

Consequences include:

- translation and inpainting reading or mutating the same block list;
- unrelated stage updates rejecting valid cleaned-image publication through a
  whole-page-version precondition;
- late or stale work lacking effective artifact-identity checks;
- translated block text being durable only through later whole-page persistence
  in some paths;
- final whole-page writes overwriting independent concurrent stage results.

### F5 - AI inactivity flush loses completion metadata and can race

`StreamingChunkPlanner.flushRemaining()` returns a final chunk and the pages
completed by that chunk. The inactivity callback receives only `finalChunk` and
passes an empty completion set to `translateChunkAi()`.

After the inactivity flush drains the planner, the final batch flush sees no
remaining chunk and cannot recover the lost completion set. A successfully
translated page can remain `RUNNING` until reconciliation marks it failed.

`InactivityFlusher` also defines a translation mutex, but normal planner
`accept()` and size-driven provider work do not use it. Timer flush and normal
acceptance can therefore access planner state concurrently despite comments
claiming serialization.

### F6 - Resume validation and inpaint execution disagree

The outer resume gate checks cleaned-file existence and the persisted
inpainting mode. It correctly requests inpaint-only recovery when the file is
missing or the mode changed.

`runInpaintStage()` then uses metadata-only `hasDurableCleaned` checks and can
immediately skip the requested inpaint. Missing files and FAST-to-QUALITY mode
changes can therefore reach render with stale or absent output.

### F7 - Persisted data lacks artifact provenance

`PageTranslation` stores page filename, dimensions, stage statuses, blocks,
mask, cleaned-image name, and limited inpaint metadata. It does not persist:

- source image content hash, size, or modification identity;
- detector/OCR model and configuration signature;
- source-language signature;
- translator provider, target language, model, prompt, or schema signature;
- explicit parent artifact IDs for translation, inpaint, and render.

The same filename with changed bytes can reuse stale OCR. Changing source
language, target language, provider, or model can reuse stale translation.
Expected page count alone cannot detect same-count chapter page replacement.

### F8 - Block mapping is positional and mutable

Stage patches address blocks by list index plus fingerprints. Translation sorts
the block list and watermark filtering permanently removes blocks whose
translated result contains the `RTMTH` sentinel.

Any selective reset or concurrent merge based only on current list position can
apply data to the wrong OCR block after sorting, filtering, dedupe, user edits,
or future model changes. Canonical OCR identity is not yet modeled as an
immutable block ID.

### F9 - Storage cleanup and failure behavior are inconsistent

- `TranslationManager.getChapterTranslation(file)` deletes the entire chapter
  JSON after any read or deserialization exception. A transient SAF failure can
  become permanent data loss.
- `ChapterTranslationStore.open()` instead logs the failure and starts empty,
  without deleting the source file.
- every chapter store uses the sibling temp filename `translation.tmp`, allowing
  concurrent stores in one manga directory to collide;
- persistence deletes the target before rename, then may try to write through
  the deleted target handle when rename fails;
- chapter deletion removes JSON, summary, and images but leaves the glossary;
- page deletion leaves chapter summary and glossary derived from the old page
  set.

### F10 - Status and render semantics are too coarse

- all stages share one `errorMessage`, so one stage success can erase another
  stage failure;
- persisted failure comments say retry exhaustion survives reopen, but
  `attemptCount` is transient and explicitly resets on process restart;
- batch `renderStatus=READY` records color-estimation completion, while actual
  overlay layout occurs later in the reader;
- the reader calls `TextLayoutPlanner.plan()`, which discards detailed layout
  failures, so blocks can disappear while the page remains render-ready.

### F11 - Boundary validation has gaps

Detector postprocessing assumes matching output-array lengths, valid label
indices, finite box coordinates, and valid in-bounds geometry. It does not clamp
or reject malformed boxes before constructing detections.

Some apparently supported features are isolated rather than wired into the live
pipeline: split-tall-page back-mapping, the ML Kit full-page recognition fallback,
and the companion-image naming helper currently have no production callers.

### F12 - Reader repair can conflict with active ownership

`ReaderViewModel.sweepStrandedPageStatus()` changes old pending/running stages to
cancelled based only on elapsed time. It does not verify whether an active batch
generation still owns the page. A slow but valid provider/native operation can
therefore be rewritten by reader repair, then overwritten again by later legacy
batch updates.

## Selective-reset dependency constraints

Current data has this dependency shape:

```text
source bytes + OCR configuration
  detection/OCR snapshot
    blocks, geometry, panel/bubble data, segmentation masks, erase mask
      translation branch
        translated text, revision flags, glossary
      inpaint branch
        cleaned image, mode, inpaint revision
      translation branch + inpaint branch
        render colors/status and reader overlay
```

Valid reset operations therefore require cascades:

| Requested reset | Data retained | Mandatory invalidation |
| --- | --- | --- |
| Translation | OCR/masks and cleaned image | translated text, revision state, render, glossary, summary |
| Inpaint | OCR/masks and translation | cleaned files, inpaint metadata, render, summary |
| OCR | source only | OCR, translation, inpaint, render, glossary, summary |
| Everything | nothing | all page and chapter artifacts |

Independent raw checkboxes would permit invalid combinations. Example: deleting
OCR while retaining translation leaves translated block mappings without their
parent geometry/source identity. UX should expose dependency-aware actions or
show the computed cascade before confirmation.

## Architecture questions for next phase

The solution design must decide:

1. canonical persisted artifact model and stable block identity;
2. independent stage/branch state machine and retry semantics;
3. remote-provider overlap schedule versus local-compute schedule;
4. real per-page render join and terminal-state definitions;
5. atomic selective-reset transaction and file-deletion ordering;
6. source/configuration signature invalidation rules;
7. migration behavior for legacy chapter JSON;
8. chapter-derived glossary, summary, and revision invalidation;
9. active-generation ownership for cancellation, repair, and late-write rejection;
10. focused production-adapter tests required before rollout.

## Validation status

This was a read-only code investigation. Focused Gradle orchestration tests were
attempted but could not start because `JAVA_HOME` was unset and no `java`
executable was available. No build or test success is claimed.
