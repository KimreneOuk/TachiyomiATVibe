# Plan - Translation Stage Recovery

Status: Proposed architecture; implementation not started. Detailed artifact,
continuation, reset, and edge-case design is recorded in `design.md`.

Date: 2026-07-18

Supersedes the scheduling, retry, and selective-deletion portions of
`Plan/active/2026-07-16-unified-translation-pipeline/`. Revision-quality work
outside those seams remains unchanged.

## Goal

Make detect/OCR, translation, inpaint, and render independently resumable while
enforcing one chapter-level scheduling invariant:

> No inpainting job may start until every expected chapter image has reached a
> terminal detect/OCR result.

After that barrier, inpainting may run concurrently with remote translation and
with rendering of other eligible pages.

## Non-negotiable scheduling rules

1. Detect and OCR are one native stage for scheduling and persistence.
2. Detect/OCR processes expected pages in deterministic reading order.
3. Detect/OCR and inpainting never overlap within one chapter run.
4. Each durable OCR result may immediately queue remote translation after the
   page bitmap and native resources are released.
5. Remote translation may run while later pages perform detect/OCR.
6. The all-OCR barrier opens only after every expected page has a terminal OCR
   result for the active generation.
7. Inpainting starts only after the all-OCR barrier opens.
8. After the barrier, serial inpainting may overlap remote translation.
9. Render may overlap inpainting of another page.
10. A page may render only after its own translation and inpaint branches have
    reached compatible terminal states.
11. Local ML Kit translation uses local compute. It runs after the all-OCR
    barrier and is serialized with inpainting on the native/local-compute lane.
12. Queues carry immutable references only. They never retain bitmaps, streams,
    ONNX values, recognizers, or mutable store-owned pages.

## Meaning of an OCR result

The barrier counts a page only when its OCR stage is terminal for the current
generation:

- `READY`: durable OCR snapshot and current mask identity exist;
- `TEXTLESS`: OCR completed successfully and found no translatable text;
- `FAILED`: detect/OCR reached a terminal failure with a persisted stage error;
- `CANCELLED`: only terminal when the entire active generation is being
  cancelled; it does not allow the same generation to continue into inpainting.

`PENDING` and `RUNNING` never satisfy the barrier.

A failed page does not deadlock the chapter. It satisfies terminal accounting
but is not eligible for translation, inpainting, or rendering. Other OCR-ready
pages may continue after the barrier.

## Pipeline architecture

### Phase 0 - Admission and snapshot

Before work starts:

1. resolve the ordered expected page-key list;
2. open one chapter store and begin a new run generation;
3. snapshot source/configuration identities;
4. compute each page's required branches from durable artifact validity;
5. cancel or reject conflicting chapter/manual jobs;
6. create chapter progress accounting from the expected key list, not from
   whichever pages later succeed.

No stage is selected from status strings alone. Selection uses artifact
validity, parent identity, configuration identity, and terminal state.

### Phase 1 - Detect/OCR producer

One ordered producer owns the native lane:

```text
for each expected page:
  if current OCR artifact is reusable:
    publish terminal OCR accounting from durable snapshot
    queue translation reference if translation branch needs work
  else:
    decode source
    run detection + OCR
    build canonical OCR snapshot and erase-mask snapshot
    atomically commit OCR artifact
    release bitmap, detector output, OCR resources, and native pools
    publish terminal OCR accounting
    queue translation reference if translation branch needs work

when every expected page is terminal:
  close OCR production
  open all-OCR barrier
```

The producer does not wait for provider completion. The translation queue must
be chapter-bounded by small references and must not backpressure OCR. A chapter
has a finite page count, so an unbounded coroutine channel is acceptable only
when its element type is proven small and immutable; otherwise use capacity
equal to expected eligible pages.

### Concurrent remote-translation consumer

One ordered provider consumer starts before the OCR producer:

```text
consume OcrReadyPageRef
re-read/detach OCR artifact under its captured identity
run one provider request at a time
validate complete/partial result
atomically merge translation-owned fields
signal page translation branch terminal
offer page to render join
```

Remote provider slowness must not hold the native lane, prevent later OCR, or
retain page image memory. Provider admission must be shared with manual, auto,
batch, and revision callers according to existing product policy.

### All-OCR barrier

Barrier state is explicit and generation-owned:

```text
terminalOcrKeys == expectedPageKeys
```

The barrier is not inferred from queue emptiness, coroutine launch count, page
map size, or `awaitAll()` over work that did not persist terminal results.

Before opening it, reconcile every expected key against the store. Any worker
that exited without a durable terminal OCR state is converted to `FAILED` with
a stage-specific error. Then barrier opens once.

No inpaint worker may be created, launched, or enter a permit before this point.

### Phase 2 - Inpaint sweep

After the barrier opens, one ordered inpaint producer uses the native lane:

```text
for each OCR-ready page requiring inpaint:
  validate OCR artifact and mask parent identity
  decode/reload source as needed
  run inpaint
  publish versioned cleaned image
  atomically merge inpaint-owned fields
  release bitmap and native resources
  signal page inpaint branch terminal
  offer page to render join
```

Only one inpainting job runs at a time. This preserves the native memory bound.
Remote translation continues independently.

For ML Kit, local translation and inpainting are operations on the same
serialized local-compute/native lane after the barrier. Recommended per-page
order is translation then inpaint so both remain deterministic and no local
translation overlaps inpaint.

### Concurrent render lane

Render has a separate bounded lane and may overlap inpaint for another page:

```text
inpaint page N+1
render page N
remote translate page N+2
```

Render eligibility is per page:

```text
translation branch terminal and displayable
AND
inpaint branch terminal and cleaned artifact valid
AND
both artifacts still reference current OCR identity
```

Render for page N cannot overlap inpaint for page N because the page's inpaint
branch is not terminal yet. Render for page N may overlap inpaint for page N+1.

The render lane must not acquire the native-stage permit. It gets a detached
snapshot and a bounded bitmap/file handle. Use one render worker initially;
increase concurrency only after device memory measurements prove safe.

### Final reconciliation

Chapter completion waits for:

- detect/OCR producer termination;
- all-OCR barrier publication;
- inpaint sweep termination;
- translation consumer termination;
- all scheduled render joins to reach terminal state;
- store flush and derived-summary publication.

Reconciliation iterates every expected page key and repairs only pages owned by
the active generation. It never treats queue acceptance as provider success.

## Per-page artifact model

### Source identity

Persist a compact source identity sufficient to invalidate same-name changes:

- page key;
- byte length when known;
- content hash, or another stable provider/download identity when hashing would
  require an extra full decode read;
- original dimensions;
- source revision/schema version.

### OCR artifact

OCR owns immutable structural data:

- `ocrArtifactId`;
- source identity;
- OCR/detector configuration signature;
- source-language and model/schema signature;
- canonical block list with stable `blockId`;
- source text and geometry;
- panel/bubble association;
- segmentation masks and erase-mask boxes;
- OCR diagnostics and terminal status;
- OCR-specific error.

Detection is not offered as an independent reset choice because live execution
fuses detection and OCR and their persisted geometry is one structural artifact.

### Translation artifact

Translation owns:

- parent `ocrArtifactId`;
- translation configuration signature;
- target language, provider family, model, prompt/schema revision;
- `blockId` to translated text mapping;
- validation and revision flags;
- translation terminal status and translation-specific error.

Translation never sorts, removes, or replaces the canonical OCR block list.
Reading order is a view over stable block IDs. Watermarks are marked suppressed
instead of deleting structural blocks.

### Inpaint artifact

Inpaint owns:

- parent `ocrArtifactId` and erase-mask fingerprint;
- inpainting configuration/mode/schema signature;
- versioned cleaned-image name and identity;
- terminal status and inpaint-specific error.

Publishing uses stage-relevant merge preconditions. An unrelated translation
commit must not reject valid inpaint publication.

### Render artifact

Render owns:

- parent OCR, translation, and inpaint artifact identities;
- render configuration/schema signature;
- block-ID keyed colors and render metadata;
- layout diagnostics when layout is computed;
- render terminal status and render-specific error.

`READY` must have one documented meaning. Recommended meaning: every eligible
translated block has a usable overlay layout. If layout remains reader-only,
rename the persisted batch stage to describe color preparation and do not call
it rendered.

## Stage-state and retry model

Replace coarse `NextStage` selection with independent branch decisions:

```text
PageWorkPlan(
  runOcr: Boolean,
  runTranslation: Boolean,
  runInpaint: Boolean,
  runRender: Boolean,
)
```

Planner applies dependency rules:

- invalid OCR forces OCR, translation, inpaint, and render rebuild;
- valid OCR with invalid translation runs translation and later render;
- valid OCR with invalid inpaint runs inpaint and later render;
- valid OCR with both branches valid but invalid render runs render only;
- fully valid artifacts schedule nothing;
- textless or OCR-failed pages schedule no downstream branch.

Manual retry defaults to the minimum failed/invalid branch set. A separate
explicit “restart all processing” action may force OCR and every dependent
artifact. Any stage failure no longer implies full detect/OCR restart.

Retry counts and errors are stage-specific. Retry exhaustion policy must clearly
choose whether it survives process restart; comments, serialization, and tests
must agree.

## Selective data reset UX

Expose dependency-aware actions, not independent raw checkboxes.

### Reset translation data

Keep:

- OCR/detection structure and masks;
- valid cleaned image and inpaint metadata.

Clear:

- translated text and translation status/error;
- automated revision flags and provider-derived glossary entries;
- render artifact/status;
- chapter summary/revision report derived from cleared text.

Next run schedules translation only, then render. It does not rerun detect/OCR
or inpainting.

User-authored edits need explicit handling. Recommended confirmation offers:

- preserve manual edits and reset generated translation only;
- clear manual edits too.

### Reset inpaint data

Keep:

- OCR/detection structure and masks;
- translation and user edits.

Clear:

- cleaned-image files and inpaint metadata/status/error;
- render artifact/status;
- affected chapter summary state.

Next run waits for no OCR because OCR is already durable, then enters the
already-satisfied barrier and schedules inpaint plus render. Translation is not
repeated.

### Reset OCR data

Clear OCR and all dependent artifacts:

- detection/OCR blocks, masks, signatures, and diagnostics;
- translation and automated revision state;
- inpaint files and metadata;
- render data;
- glossary, summary, and revision report.

This is necessarily cascading. Retaining translation or inpaint without its
parent OCR identity is invalid.

### Delete everything

Cancel and join all chapter jobs, invalidate generation, mark store defunct,
then remove JSON, glossary, summary, revision report, cleaned images, and orphan
temporary/versioned files.

### Reset transaction ordering

Every reset follows:

1. identify page/chapter scope;
2. cancel and join affected work;
3. increment generation or artifact revision;
4. atomically invalidate store artifacts and derived metadata;
5. publish new state;
6. delete files returned by accepted invalidation;
7. clear reader streams/layout caches;
8. optionally schedule the minimal new `PageWorkPlan`.

Late commits from older generations are rejected. Reset UI shows the computed
cascade before confirmation.

## Cancellation and recovery

- Chapter cancellation prevents Phase 2 from starting if the OCR barrier has
  not opened.
- Cancellation closes producers/consumers, joins jobs, persists terminal
  cancellation for active work, and retains already accepted durable artifacts.
- Restart computes work from artifact validity. It does not convert every
  failure into full OCR.
- Reader stranded-page repair must query active generation ownership before
  changing a page.
- Missing cleaned files invalidate only inpaint and render when OCR remains
  current.
- Changed translation settings invalidate translation and render, not OCR or
  inpaint.
- Changed inpainting mode invalidates inpaint and render, not OCR or translation.
- Changed OCR/source settings invalidate every dependent artifact.

## Persistence hardening

1. Wire production translation, inpaint, and render code to stage-specific
   merges.
2. Remove placeholder generation/fingerprint work references.
3. Use target-specific unique temp filenames for chapter JSON.
4. Never delete source JSON merely because one read failed.
5. Preserve last-known-good data or quarantine corrupt files for diagnosis.
6. Avoid delete-then-write through a stale SAF handle.
7. Treat glossary, summary, revision report, and companion images as named
   derived artifacts in deletion/reset APIs.
8. Split the shared page `errorMessage` into stage-specific errors while
   preserving backward-compatible decoding.

## Implementation checkpoints

### CP0 - Freeze contracts and baseline

- Add this plan and scheduling invariants to focused tests.
- Record current dirty-worktree exclusions.
- Configure Java/Android tooling before claiming test success.
- Capture baseline tests for coordinator, lifecycle policy, store, cleaned-image
  publication, planner flush, and reader retry.

Exit: tests demonstrate current failures or are marked expected-failing without
changing production behavior.

### CP1 - Artifact identity and work planner

- Add stable OCR block IDs and artifact/configuration identities.
- Introduce independent `PageWorkPlan` decisions.
- Add backward-compatible legacy snapshot interpretation.
- Add stage-specific errors and consistent retry semantics.

Exit: pure tests cover every valid/invalid artifact combination and reset
cascade.

### CP2 - Store merge and reset transactions

- Wire or revise stage-specific merge contracts.
- Add atomic page/chapter artifact invalidation APIs.
- Return exact companion files requiring cleanup.
- Invalidate glossary/summary/revision data correctly.
- Harden JSON temp, read-failure, and SAF replacement behavior.

Exit: race tests prove unrelated branch commits coexist and stale-generation
writes fail.

### CP3 - Correct OCR producer and barrier

- Replace remote-ref accumulation with immediate post-OCR queueing.
- Release bitmap/native resources before queue send.
- Implement explicit expected-key terminal OCR barrier.
- Ensure failed OCR pages cannot deadlock the barrier.
- Prohibit inpaint worker launch before barrier.

Exit: event-order tests prove translation may start before barrier and no
inpaint event can start before barrier under success, failure, cancellation, or
provider stall.

### CP4 - Translation and inpaint branch execution

- Run one remote translation consumer during Phase 1 and Phase 2.
- Serialize ML Kit after barrier with local/native work.
- Start ordered inpaint sweep only after barrier.
- Fix missing-file and mode-change inpaint adapter behavior.
- Commit both branches through stage merges.

Exit: remote translation overlaps OCR and inpaint; inpaint never overlaps OCR;
ML Kit never overlaps native inpaint.

### CP5 - Real render join and render lane

- Replace empty callbacks and direct premature `tryRender()` calls.
- Track per-page translation/inpaint terminal signals.
- Add one bounded render worker independent of native permit.
- Permit render page N while inpainting page N+1.
- Define and report layout failures consistently.

Exit: tests cover translation-first, inpaint-first, failure, partial translation,
textless, cancellation, and stale-parent cases.

### CP6 - AI planner/admission repair

- Carry `completedPages` through inactivity flush.
- Use one actual mutex for planner acceptance, flush, and provider request
  ordering where required.
- Wire shared provider admission consistently across translation paths.
- Persist successful translated blocks before waiting for render.

Exit: inactivity and size-driven flush races cannot strand or double-complete a
page.

### CP7 - Retry and selective-reset UX

- Make normal retry execute minimum invalid branches.
- Add translation, inpaint, OCR, and everything reset actions.
- Show dependency cascade and manual-edit choice.
- Clear reader streams and overlay cache after accepted reset.
- Keep UI logic thin; store/pipeline owns dependency rules.

Exit: page and chapter UI tests prove each action retains and removes the exact
artifact set.

### CP8 - Reconciliation, migration, and cleanup

- Make reconciler expected-key and generation aware.
- Guard stranded-page repair with active ownership.
- Migrate or lazily interpret legacy page JSON.
- Remove duplicate/dead contracts only after production parity tests pass.
- Resolve split-tall behavior explicitly: wire it or remove unsupported claims.

Exit: restart, process death, partial legacy state, and same-name source-change
tests produce deterministic minimal work.

### CP9 - Validation

- Run focused unit tests after each checkpoint.
- Run `spotlessCheck`.
- Run required assemble and release unit-test tasks from `AGENTS.md`.
- Run device tests for memory, cancellation, reader lifecycle, SAF storage, and
  provider stalls on supported low-memory target hardware.
- Review final diff against unrelated dirty worktree changes.

Exit: all automated validation passes and remaining device-only gates are
reported explicitly.

## Required scheduling tests

Event tests must assert these partial orders:

```text
ocrCommit(page N) before translateStart(page N)
translateStart(page N) may occur before allOcrBarrier
ocrTerminal(all expected pages) before allOcrBarrier
allOcrBarrier before every inpaintStart
inpaintDone(page N) before renderStart(page N)
translationTerminal(page N) before renderStart(page N)
render(page N) may overlap inpaint(page N+1)
```

Also prove:

- stalled remote provider does not block later OCR;
- failed OCR page still permits barrier after terminal persistence;
- cancellation before barrier starts no inpainting;
- cancellation after barrier stops new inpainting safely;
- translation failure does not rerun OCR;
- inpaint failure does not rerun OCR or translation;
- render failure does not rerun OCR, translation, or inpaint unless parent
  validation independently fails;
- local ML Kit never overlaps detect/OCR or inpaint;
- queued references hold no bitmap, stream, or mutable `PageTranslation`.

## Acceptance criteria

- No inpainting job starts before every expected image has terminal detect/OCR
  state for the active generation.
- Remote translation can start after each page's OCR commit while later pages
  continue detect/OCR.
- Inpainting can run concurrently with remote translation after the OCR barrier.
- Rendering can run concurrently with inpainting of another page.
- Same-page rendering waits for both valid translation and valid inpaint
  terminal results.
- A downstream failure retries only invalid downstream branches when OCR remains
  current.
- Selective resets preserve valid independent artifacts and cascade through all
  dependent artifacts.
- Late work cannot restore data invalidated by cancel or reset.
- Changed source/configuration identities invalidate exactly their dependent
  artifacts.
- Store read failure never silently deletes the only persisted chapter copy.
- Memory ownership remains bounded for Android devices with at least 6 GB RAM.

## Out of scope

- detector, OCR, translator, or inpainting model-quality changes;
- multiple simultaneous inpaint workers;
- multiple simultaneous provider requests;
- independent deletion of detection while retaining OCR;
- broad reader or manga-screen redesign outside focused retry/reset controls;
- changes to semantic revision protocol unrelated to artifact invalidation.

## Validation status

Plan only. No production code has been changed by this task. Earlier focused
test execution could not start because `JAVA_HOME` was unset and no `java`
executable was available.
