# Design - Data Flow, Continuation, and Selective Restart

Status: Proposed architecture; implementation not started.

Date: 2026-07-18

Depends on: `initial-findings.md` and the scheduling invariants in `plan.md`.

## Design decision

Manual translation, automatic reader translation, and manga-screen
pre-translation will not own separate pipelines or separate page state. They
become three request sources for one manager-owned chapter work controller and
one durable chapter artifact store.

This is the central rule that makes continuation safe:

> Entry path selects scope and priority. Durable artifact validity selects work.

Therefore, switching from manual/auto work to pre-translation does not copy,
convert, or restart data. Pre-translation expands desired scope to the full
chapter, adopts every valid artifact already committed, and schedules only
missing or invalid branches.

## System boundaries

### ChapterWorkController

One active controller exists per chapter identity. All entry points submit an
intent:

```text
ManualPage(pageKey)
AutoWindow(orderedPageKeys)
PretranslateChapter(orderedExpectedPageKeys)
Continue(scope)
Reset(scope, resetKind, manualEditPolicy)
Cancel(scope)
```

The controller owns:

- active chapter generation and expected page-set revision;
- a barrier epoch separate from stage reset epochs;
- desired page scope and entry-path priorities;
- the detect/OCR phase and all-OCR barrier;
- translation, inpaint, and render branch scheduling;
- deduplication of equivalent work;
- cancellation and safe handoff between intents;
- progress and terminal reconciliation.

UI, reader, and manga screen observe immutable controller/store snapshots. They
do not mutate page status or decide resume stages.

### Process-wide resource arbitration

Chapter controllers share process-wide bounded lanes:

| Lane | Capacity | May overlap |
| --- | ---: | --- |
| Native detect/OCR/inpaint | 1 | Remote provider, bounded render |
| Remote provider | 1 per admission policy | Native lane, bounded render |
| Local ML Kit | Shared with native/local compute | Remote provider, bounded render |
| Render | 1 initially | Remote provider and native inpaint after barrier |

Detect/OCR and inpaint use the same process-wide native permit, so they never
run concurrently even across different chapter/manual/auto jobs. The chapter
barrier additionally prevents a chapter's inpaint phase from starting before
all its expected OCR results are terminal.

Render never acquires the native-stage permit. It uses a separate memory-budget
gate so overlap is allowed but can wait under memory pressure.

## Canonical data flow

```text
SourcePageIdentity
  |
  v
OcrArtifact
  | stable block IDs + erase-mask identity
  +------------------------+
  |                        |
  v                        v
TranslationArtifact       InpaintArtifact
  |                        |
  +-----------+------------+
              |
              v
         RenderArtifact

Chapter-derived artifacts:
  glossary, summary, revision report, progress history
```

Every child artifact stores its parent identity. Status alone never proves
reusability.

## Durable storage model

Use one authoritative versioned chapter JSON document plus external versioned
cleaned-image files. Do not create independent authoritative OCR, translation,
inpaint, and render JSON files: SAF file-operation cost and cross-file commit
windows would make stage consistency harder. Stage independence comes from
artifact records and atomic relevant-field merges inside the store, not from
separate files.

Chapter-list summary remains a small rebuildable sidecar for fast status reads.
It carries the authoritative document revision and is ignored/rebuilt when that
revision does not match. Glossary and bounded latest revision report live in the
authoritative document so they cannot survive a reset independently by mistake.
Legacy glossary/summary sidecars are imported once and then treated as migration
inputs or caches, never equal authorities.

### Chapter document

Replace the raw top-level page map with a versioned envelope while retaining a
legacy decoder:

```kotlin
ChapterTranslationDocument(
    schemaVersion,
    documentRevision,
    chapterIdentity,
    expectedPageSetRevision,
    expectedPageKeys,
    pages: Map<PageKey, PageArtifactRecord>,
    derived: ChapterDerivedRecord,
)
```

`chapterIdentity` uses stable database/source identifiers where available. A
sanitized chapter title remains a filesystem label, not logical identity.

`expectedPageSetRevision` fingerprints ordered source-page identities. A same-
count replacement or reorder therefore changes the page set.

The in-memory store uses persistent collections and stage-level patch batching
to avoid deep-copying/encoding the whole chapter for every block response.
Durability flushes are debounced at safe stage commits and forced at barriers,
cancellation, reset, and terminal reconciliation.

### SourcePageIdentity

```kotlin
SourcePageIdentity(
    pageKey,
    logicalPageIndex,
    sourceLocatorFingerprint,
    byteLength,
    contentFingerprint,
    width,
    height,
)
```

The content fingerprint may be collected while buffering/decoding so source
bytes are not read an extra time. For providers where stable ETag/download
identity exists, that identity may supplement but not silently replace content
validation unless its stability is proven.

For split-tall pages, the chapter expected set contains logical pages. Slice
artifacts are internal children, and the logical OCR result becomes terminal
only after all expected slices are accounted for and back-mapped.

### PageArtifactRecord

```kotlin
PageArtifactRecord(
    source: SourcePageIdentity?,
    ocr: OcrArtifact?,
    translation: TranslationArtifact?,
    inpaint: InpaintArtifact?,
    render: RenderArtifact?,
    attempts: StageAttemptRecord,
    resetEpochs: StageResetEpochs,
)
```

Each artifact is immutable after acceptance. A new successful stage creates a
new artifact identity and atomically replaces its predecessor. Mutable runtime
objects are detached from store state.

### OcrArtifact

```kotlin
OcrArtifact(
    artifactId,
    sourceIdentity,
    configSignature,
    status,
    blocks: List<OcrBlock>,
    readingOrder: List<BlockId>,
    eraseMask,
    detectionDiagnostics,
    error,
    committedAt,
)
```

OCR structural blocks never contain generated translation as owned mutable
state. `OcrBlock` owns source text, geometry, panel/bubble metadata, segmentation
mask, and suppression flags.

Stable `blockId` is generated from the OCR artifact's normalized structural
fingerprint plus a deterministic collision ordinal. It remains stable for the
lifetime of that OCR artifact. A fresh incompatible OCR artifact invalidates
children rather than guessing block mappings.

Reading-order changes reorder `blockId` references, not the canonical block
list. Watermark filtering marks a block suppressed; it never removes the block
and shifts downstream indices.

### TranslationArtifact

```kotlin
TranslationArtifact(
    artifactId,
    parentOcrArtifactId,
    configSignature,
    status,
    values: Map<BlockId, TranslationValue>,
    error,
    committedAt,
)
```

```kotlin
TranslationValue(
    text,
    origin,          // GENERATED or MANUAL
    providerDraft,
    needsRevision,
    userEditedAt,
)
```

Generated and manual values are distinguishable. Provider responses merge by
`blockId` with edit preconditions. A response cannot overwrite a manual edit
made after the request snapshot.

`PARTIAL` is durable. Continue requests only missing/rejected generated values.
Contextual providers may resend a whole page for context, but commit applies
only to still-eligible block IDs and preserves manual values. Partial data is a
resume draft, not a display-ready page, until every required non-suppressed block
has usable text.

### InpaintArtifact

```kotlin
InpaintArtifact(
    artifactId,
    parentOcrArtifactId,
    eraseMaskFingerprint,
    configSignature,
    cleanedImageName,
    cleanedImageFingerprint,
    status,
    error,
    committedAt,
)
```

Cleaned images remain versioned companion files. Publication is two-phase:

1. write a new unique temporary file;
2. encode and verify non-empty readable output;
3. rename/copy to a unique versioned final name;
4. stage-merge metadata using OCR/mask/reset-epoch preconditions;
5. after accepted merge, delete the previous version;
6. after rejected merge, delete the unpublished new version.

Translation commits do not participate in inpaint preconditions.

### RenderArtifact

```kotlin
RenderArtifact(
    artifactId,
    parentOcrArtifactId,
    parentTranslationArtifactId,
    parentInpaintArtifactId,
    configSignature,
    blockRenderData: Map<BlockId, BlockRenderData>,
    layoutFailures,
    status,
    error,
    committedAt,
)
```

Render failure never changes translation or inpaint status. Parent validation
may separately invalidate a missing/corrupt inpaint file, but render itself does
not own that decision.

### Runtime attempt state

Durable artifacts and runtime work state are separated:

```kotlin
StageAttempt(
    workId,
    sessionId,
    stage,
    state,           // QUEUED, RUNNING, TERMINAL
    startedAt,
    lastHeartbeatAt,
    failureCount,
    error,
)
```

On process restart, a persisted `RUNNING` attempt whose `sessionId` is not the
current process becomes `ABANDONED`; it does not invalidate already committed
artifacts. Work planning then schedules only missing artifacts.

Errors and retry counters are separate for OCR, translation, inpaint, and
render. Serialization and exhaustion behavior must match documented policy.

## Artifact validity

Work planning evaluates these predicates:

```text
validOcr =
  OCR READY/TEXTLESS
  and source identity matches
  and OCR config signature matches
  and OCR schema supported

validTranslation =
  validOcr
  and translation parent == current OCR artifact
  and translation config signature matches
  and stored values address valid block IDs

displayableTranslation =
  validTranslation
  and every required, non-suppressed OCR block has a usable translation

validInpaint =
  validOcr
  and inpaint parent == current OCR artifact
  and mask fingerprint matches
  and inpaint config signature matches
  and cleaned file exists, is non-empty, and is readable

validRender =
  displayableTranslation
  and validInpaint
  and render parents match current artifacts
  and render config signature matches
```

Invalid child data may remain temporarily for diagnostics or rollback, but it is
never displayed or counted as complete. Replacement/cleanup removes it after a
new artifact is accepted or an explicit reset commits.

A durable `PARTIAL` translation is reusable but not displayable by default.
Inpainting erases OCR regions independently of provider completion; displaying a
cleaned page with missing translations would expose blank holes. Render waits
until every required non-suppressed block has a usable translation. A future
partial-display feature would need explicit source-pixel restoration or
selective inpainting and is outside this design.

## Work planning

One pure planner creates independent branch requirements:

```kotlin
PageWorkPlan(
    ocr: WorkDecision,
    translation: WorkDecision,
    inpaint: WorkDecision,
    render: WorkDecision,
)
```

`WorkDecision` is `Reuse`, `Run`, `Retry`, `Blocked`, or `NotApplicable`, with an
explicit reason.

Decision table:

| Current durable state | OCR | Translation | Inpaint | Render |
| --- | --- | --- | --- | --- |
| Nothing valid | Run | After OCR | After barrier | After both branches |
| OCR valid only | Reuse | Run | Run after barrier | After both branches |
| OCR + translation valid | Reuse | Reuse | Run after barrier | After inpaint |
| OCR + inpaint valid | Reuse | Run | Reuse | After translation |
| Both branches valid | Reuse | Reuse | Reuse | Run if invalid |
| Everything valid | Reuse | Reuse | Reuse | Reuse |
| OCR textless | Reuse | N/A | N/A | N/A |
| OCR failed/retry exhausted | Failed | Blocked | Blocked | Blocked |

The planner is used identically by manual, auto, and pre-translation entry
paths. No UI path carries a `force` Boolean that secretly means “erase all
downstream state.”

## Continuation behavior by entry path

### Manual or auto work halfway, then pre-translation Continue

Pre-translation submits the full ordered chapter scope to the existing
controller.

The controller performs a safe scope expansion:

1. freeze admission of new downstream native work;
2. allow the currently running native operation to reach a safe boundary, or
   cancel it if it cannot publish safely;
3. retain every accepted OCR, translation, inpaint, render, and manual-edit
   artifact;
4. merge the full expected page list into controller scope;
5. compute a new work plan from store artifacts;
6. enter/return to OCR phase for every page lacking valid OCR;
7. allow already-running remote provider work to continue when its token still
   matches current OCR/config/reset epochs;
8. prohibit new inpainting until the expanded full-chapter OCR barrier opens;
9. after the barrier, run only missing inpaint and translation branches;
10. render pages as their two branches become eligible.

Examples:

| Existing manual/auto state | Pre-translation continuation |
| --- | --- |
| Page 1 OCR running | Let it commit; OCR remaining pages; no inpaint before barrier |
| Page 1 remote translation running | Keep request; OCR remaining pages concurrently |
| Page 1 inpaint running | Drain/cancel safely; freeze further inpaint; OCR remaining pages; resume after barrier |
| Page 1 render running | Drain safely; establish OCR phase; later render missing pages |
| Pages 1-5 fully rendered | Reuse them; OCR remaining pages; do not rebuild 1-5 |
| Pages 1-5 OCR + translated, no inpaint | Reuse OCR/translation; OCR remainder; inpaint all eligible after barrier |
| Pages 1-5 OCR + inpaint, translation partial | Reuse OCR/inpaint/valid drafts; OCR remainder; translate missing IDs |
| Some pages failed OCR | Retry according to OCR policy; persist final failure so barrier cannot deadlock |

Completed inpaint from earlier manual/auto work is reusable. The rule prohibits
starting a new inpaint job before the expanded barrier; it does not delete valid
inpaint completed before pre-translation scope existed.

Scope expansion increments the barrier epoch but does not invalidate unchanged
OCR artifacts or compatible provider requests. This distinction is required:
expanding one-page manual scope to full-chapter scope must stop new inpainting,
but must not discard a valid translation response merely because more pages were
added. Inpaint/render work captures barrier epoch; remote translation commits
depend on OCR/config/reset identity, not barrier scope.

### Pre-translation already active, then manual request

The manual request attaches to the same page work and observer:

- no duplicate OCR/provider/inpaint job is launched;
- existing work may be marked UI-priority, but phase ordering is unchanged;
- during OCR phase, manual request cannot force inpaint before the barrier;
- if the page is already terminal, its artifact is displayed immediately;
- explicit manual reset becomes a reset intent and follows reset transaction
  rules.

### Pre-translation already active, then auto request

Auto scope is absorbed by the chapter scope. No duplicate work is launched.
Reader opening does not cancel chapter work. It observes the same store and may
display any valid completed page.

### Continue after cancellation or process death

Cancel stops work; it does not delete accepted artifacts. Continue reopens the
store, marks abandoned attempts, validates artifact/file/config identities, and
schedules the minimum missing branches.

Crash windows resolve as follows:

| Crash point | Resume behavior |
| --- | --- |
| Before OCR commit | OCR only reruns for that page |
| After OCR commit, before queue | Translation/inpaint planned from durable OCR |
| Provider returned, before merge | Translation reruns; OCR/inpaint retained |
| Partial translation merged | Only missing/rejected block IDs continue |
| Cleaned temp written, before merge | Temp/orphan cleaned; inpaint reruns |
| Inpaint metadata committed | Inpaint reused after file verification |
| Both branches committed, before render | Render only |
| Render running at death | Parent artifacts retained; render only |

## User continuation and restart choices

### Continue existing work - default

No data is deleted. Planner validates artifacts and presents a preview:

```text
Reused: 12 OCR, 7 translations, 4 cleaned pages, 3 rendered pages
Will run: 8 OCR, 13 translations, 16 inpaints, 17 renders
Blocked: 1 missing source page
```

Continue retries failed stages according to policy without rebuilding valid
parents. Exhausted stages appear blocked and require explicit “retry failed
stage” confirmation, not full restart.

### Retry failed work

Clears attempt exhaustion/error for selected failed stages but retains valid
artifacts and parents. Examples:

- translation retry retains OCR and inpaint;
- inpaint retry retains OCR and translation;
- render retry retains OCR, translation, and inpaint;
- OCR retry has no valid OCR artifact, so downstream work remains blocked and is
  rebuilt only after new OCR succeeds.

### Restart translation

Scope: page or chapter.

Options:

- preserve manual edits, clear generated drafts;
- clear generated drafts and manual edits.

Invalidates translation, render, provider-derived glossary, revision flags, and
derived summary/report. Retains OCR and inpaint. Next work is translation then
render.

### Restart inpainting

Invalidates inpaint and render and schedules deletion of cleaned files. Retains
OCR, translation, and manual edits. If OCR for full requested scope is already
valid, the OCR barrier is immediately satisfied; otherwise inpaint waits until
missing OCR completes.

### Restart OCR

Invalidates OCR and every child artifact. This necessarily clears translation,
inpaint, render, generated glossary/revision state, and companion images.

Manual translations cannot be safely retained automatically across changed OCR
geometry/text. Optional future remapping requires explicit user review and is
outside initial implementation.

### Delete all translation data

Invalidates and deletes chapter document, glossary, summary, revision report,
cleaned/versioned images, temporary files, and reader caches after all work is
cancelled and joined.

## Reset and commit concurrency

Every stage work item captures:

```kotlin
StageWorkToken(
    chapterIdentity,
    pageKey,
    stage,
    workId,
    sessionGeneration,
    barrierEpoch?,
    stageResetEpoch,
    parentArtifactIds,
    configSignature,
)
```

Commit validates only stage-relevant fields plus reset epoch and active ownership.

`sessionGeneration` changes on controller replacement, full cancellation, or
destructive chapter restart. `barrierEpoch` changes when expected OCR scope or
OCR validity changes. OCR and translation tokens do not require a stable barrier
epoch. Inpaint and phase-gated render tokens do.

Reset transaction:

1. block admission for affected page/stages;
2. cancel and join affected work and dependent render work;
3. increment affected stage reset epochs;
4. atomically remove/invalidate selected artifacts and chapter-derived data;
5. publish store snapshot;
6. delete returned files;
7. clear streams and layout cache;
8. replan if user requested immediate restart.

A provider response arriving after translation reset fails the translation reset
epoch check. An inpaint result arriving after OCR reset fails both parent OCR and
inpaint reset checks. Unrelated translation completion does not invalidate an
inpaint commit.

## Configuration-change handling

Configuration signatures map changes to minimum invalidation:

| Change | Invalid artifacts |
| --- | --- |
| Source bytes/page identity | OCR, translation, inpaint, render |
| Detector/OCR model or source language | OCR, translation, inpaint, render |
| Reading order only | Translation context/order and render; canonical OCR blocks retained |
| Target language/provider/model/prompt schema | Generated translation and render |
| Inpainting mode/model/mask schema | Inpaint and render |
| Render/font/layout schema | Render only |
| Manual block edit | That block's generated/revision/render data as required |

Preference changes during active work update the desired configuration epoch.
Old in-flight commits are rejected when their captured signature no longer
matches. Controller replans using the new signature.

Manual values are not silently erased by provider/config changes. They remain
marked with their original target-language/config provenance and require user
choice if incompatible with the new target language.

## Progress model

Progress is artifact-based and expected-key based:

```kotlin
ChapterProgress(
    expectedPages,
    ocrTerminal,
    ocrReady,
    ocrFailed,
    translationTerminal,
    translationReady,
    inpaintTerminal,
    inpaintReady,
    renderTerminal,
    renderReady,
    reusedArtifacts,
    runningStage,
)
```

Queue acceptance is never counted as stage success. Failed pages count as
terminal but not ready. Reused artifacts count immediately. Progress cannot
exceed expected pages and cannot mark a chapter translated from one placeholder
entry.

## Edge-case decisions

### OCR barrier revocation

An open barrier belongs to one expected-page-set revision and OCR configuration.
It is revoked when any expected page loses valid OCR, the expected page set
changes, or source/OCR configuration changes.

Revocation stops admission of new inpaint and render work. Any active inpaint
reaches a safe boundary or is cancelled and joined. Only then may detect/OCR
resume, preserving the rule that inpaint never overlaps detect/OCR. Translation
may continue when its parent/config token remains valid. A successor barrier
opens after the revised expected set is terminal again.

Resetting translation, inpaint, or render alone does not revoke the OCR barrier.
Resetting OCR for one expected page does.

### Page set changes during a run

Expected ordered page set is frozen by revision. If loader/source reveals a
different set before the OCR barrier, controller closes current admissions,
starts a successor session, and reuses artifacts whose source identities still
match. It does not open the old barrier and start inpaint against an incomplete
new chapter.

If change is discovered after barrier, stop new inpaint/render at safe boundary
and replan under a successor page-set revision using barrier revocation above.

### Duplicate or renamed page keys

Logical identity includes ordered index and source locator/content identity.
Duplicate display filenames do not share one record. Rename with identical
content may be conservatively treated as new unless a stable source identity
proves equivalence.

### Missing/corrupt source image

Persist OCR `FAILED` for that expected key. It satisfies terminal barrier
accounting, blocks downstream stages for that page, and allows other pages to
continue. Retry can target only that page later.

If a source identity changes while Phase 2 is decoding for inpaint, the inpaint
commit is rejected, the OCR barrier is revoked, and the controller returns to
detect/OCR after active inpaint drains. An old OCR mask is never applied to new
source bytes.

### User skips one page

Per-page user skip is an explicit terminal `USER_SKIPPED` OCR outcome for the
current expected-set revision. It satisfies barrier accounting and schedules no
downstream work. It is distinct from `CANCELLED`, which means work stopped and
remains resumable.

### OCR succeeds with no text

Persist `TEXTLESS` plus source/config identity. It satisfies barrier and needs no
translation, inpaint, or render. A future source/OCR signature change invalidates
the textless result.

### Partial OCR or slice failure

OCR artifact is not `READY` until all logical-page components are accounted for.
If policy permits a partial logical result, it must have an explicit `PARTIAL`
contract and downstream eligibility rules; initial implementation treats it as
terminal `FAILED` to avoid incomplete erase/mapping data.

### Partial provider response

Accept validated block-ID values, mark artifact `PARTIAL`, preserve existing
manual/generated valid values, and queue only missing IDs on Continue. Never
shift values by response order. Inpaint may still complete independently, but
reader keeps displaying the source image until translation becomes displayable;
it never displays a cleaned page with untranslated blank regions.

### Provider offline, rate-limited, or credentials invalid

OCR and inpaint continue according to the schedule. Translation records a
stage-specific retryable failure or blocked reason. Cleaned output may remain
durable but hidden until translation is displayable. Continue later retries only
translation, then render.

### User edits while provider runs

Per-block edit timestamp/version is part of merge precondition. Stale provider
value for edited block is rejected; independent blocks may still merge.

### User edits while render runs

Edit changes translation artifact identity or block revision. Old render commit
fails parent/version precondition and render is rescheduled without rerunning
OCR or inpaint.

### Reset while work runs

Reset disables affected admission first, cancels/joins work, increments reset
epoch, then invalidates storage. Any late commit is rejected. File deletion never
precedes store invalidation.

Cancellation is dependency-scoped: translation reset cancels translation and
dependent render but may let independent inpaint continue; inpaint reset cancels
inpaint and render but may let remote translation continue; OCR reset cancels
both downstream branches, revokes the barrier, and returns the controller to OCR
phase after native work drains.

### Cancel before OCR barrier

No inpaint starts. Completed OCR/translation artifacts remain. Running OCR is
either committed atomically or abandoned with no partial artifact.

### Cancel after OCR barrier

Stop launching new inpaint/render jobs. Current operation reaches safe boundary
or is cancelled. Accepted artifacts remain and Continue resumes missing work.

### Missing cleaned image with READY metadata

File validation marks inpaint invalid and render invalid. Continue reruns only
inpaint then render. Translation and OCR remain valid.

### Orphan companion files

Versioned files not referenced by any accepted artifact are deleted during
store open/maintenance after a safety age. Active temporary/work-token names are
excluded.

### Store read or schema failure

Never delete the only file. Keep last-known-good in memory when possible,
quarantine/copy corrupt bytes for diagnosis, expose storage error, and require
explicit recovery. A transient SAF open failure is retryable and does not create
an empty replacement document.

### Storage full or permission loss

Stage that cannot commit durable output becomes failed/blocked. Do not report
success from an in-memory-only result. Existing previous artifacts remain
referenced until replacement commit succeeds.

### Process death during JSON replacement

Use target-specific unique temp plus verified replacement/backup strategy.
On reopen choose the newest fully decodable valid document and clean stale temp
files later.

### Multiple chapters active

Each has its own controller/store/generation. Global native and provider
admission preserve process memory bounds. Inpaint from one chapter never runs
concurrently with detect/OCR from another because both require the same global
native permit.

### Reader opens during pre-translation

Reader attaches to active controller/store. It does not cancel batch work or run
stranded-page repair on controller-owned attempts. Valid page artifacts become
displayable as soon as their render contract permits.

### Reader closes or chapter changes

Reader observation detaches. User-started pre-translation continues. Auto/manual
scope may be released according to scheduler policy without cancelling the
shared controller if another intent still owns the work.

### App enters background or memory pressure rises

Stop new bitmap-heavy work at safe boundaries, release pools, retain durable
artifacts, and resume from work plans later. Remote provider cancellation policy
must avoid committing after configuration/reset epochs change.

### Render overlaps inpaint

Render page N may load/use one bounded cleaned bitmap while native lane inpaints
page N+1. A global memory budget can delay render without blocking inpaint or
changing correctness. Bitmap ownership is explicit; neither lane recycles a
bitmap owned by the other.

### Render/layout failure

Persist render-specific failure with affected block IDs and diagnostics.
Translation/inpaint remain ready. Retry render only unless independent parent
validation detects stale/missing data.

### Legacy positional block data

Legacy translated chapters are preserved and remain readable. Opening a legacy
file does not delete it, rewrite it, or automatically rerun the chapter.

A `LegacyArtifactAdapter` creates an in-memory compatibility view:

- stored source filename, dimensions, blocks, geometry, masks, and OCR status
  become one synthetic legacy OCR artifact;
- deterministic block IDs are generated from stored text/geometry/order plus a
  collision ordinal;
- existing block translations attach to those IDs with provenance
  `LEGACY_UNKNOWN` unless a durable manual-edit marker proves `MANUAL`;
- an existing readable cleaned file becomes a legacy inpaint artifact;
- stored colors/render status become a legacy render artifact when their parent
  data and cleaned file are usable;
- legacy glossary and summary sidecars are imported as derived data/cache, not
  treated as independent authority.

Legacy configuration provenance is unknown because old data does not reliably
record source language, target language, provider/model, prompt schema, OCR
model signature, or source content hash. The adapter must not fabricate those
facts by stamping current preferences as historical truth.

Compatibility classifications:

| Classification | Behavior |
| --- | --- |
| `LEGACY_DISPLAYABLE` | Keep displaying existing translated result; no automatic work |
| `LEGACY_RESUMABLE` | Reuse structural/translated data that can be mapped safely; schedule only missing downstream work |
| `LEGACY_PARTIAL` | Preserve draft, but keep source image displayed until all required translations are usable |
| `LEGACY_STALE` | Preserve old bytes for recovery; do not use as current parent artifact |
| `LEGACY_CORRUPT` | Quarantine/report; never silently delete or overwrite |

When pre-translation encounters completed legacy pages with unknown translation
settings, preflight offers:

1. **Keep existing legacy pages and continue missing work** - default. Existing
   completed pages remain unchanged; new/missing pages use current settings.
   UI reports that chapter may contain mixed legacy/current provenance.
2. **Refresh generated legacy translations with current settings** - invalidates
   generated legacy translation and render while preserving usable OCR/inpaint
   and manual edits where safe.
3. **Restart OCR and dependent work** - rebuilds every selected legacy artifact
   under current source/configuration identity.

The default avoids surprise data loss and expensive mass reprocessing. Unknown
legacy translation pairs are not injected into a new provider glossary/context
unless the user chooses to keep them and explicitly accepts current-language
compatibility.

If a legacy cleaned image is readable, it may remain displayable even when its
old inpaint mode/schema is unknown. It becomes stale only when replacement is
required by explicit restart, missing/corrupt file, known incompatible mask, or
source change. A legacy `null` mode is labeled unknown, not falsely equal to the
current mode.

If OCR must be rebuilt, generated legacy translations cannot be mapped onto the
new OCR artifact automatically. Geometry/text may have changed. The old complete
display may remain available during a non-destructive compatibility refresh
until the replacement transaction succeeds, but new children are built from the
new OCR identity. An explicit user-selected Restart OCR/Delete action instead
follows its confirmed invalidation semantics and may stop displaying the old
result immediately. Manual edits are exported for explicit review/remapping
rather than silently assigned to guessed blocks.

Migration is lazy:

1. read legacy data through adapter without writing;
2. continue displaying compatible legacy result;
3. on first accepted mutation, write one complete versioned chapter envelope
   containing both imported legacy artifacts and new artifacts;
4. keep a recoverable legacy backup until the new envelope is verified readable;
5. rebuild summary cache from the new authoritative document;
6. clean old sidecars only after successful migration and retention policy.

This supports mixed chapters: pages translated manually/automatically by an old
version, pages newly completed by pre-translation, and untouched pages may
coexist. Every page carries provenance so later Continue/Restart decisions stay
explicit.

## UI contract

Before Continue/Restart, backend returns immutable preflight:

```kotlin
ContinuationPreflight(
    scope,
    reusableCounts,
    plannedWorkCounts,
    blockedPages,
    staleReasons,
    manualEditCount,
    filesToDelete,
    resetCascade,
)
```

UI displays backend decisions; it does not recompute dependencies.

Recommended action list:

1. Continue existing work.
2. Retry failed work.
3. Restart translation.
4. Restart inpainting.
5. Restart OCR and dependent work.
6. Delete all translation data.

Advanced details may show per-stage counts and stale reasons. Per-page actions
use the same contracts with page scope.

## Required scenario tests

### Entry-path continuation

- manual OCR complete, then chapter pre-translation;
- manual translation in flight, then chapter pre-translation;
- manual inpaint in flight, then chapter pre-translation;
- auto window partially complete, then chapter pre-translation;
- pre-translation active, then manual request for queued page;
- pre-translation active, then reader auto window requests same pages;
- cancel pre-translation, reopen reader, Continue;
- process death at every stage boundary, then Continue.
- completed legacy manual/auto pages, then current pre-translation Continue;
- legacy inpaint running-state metadata after process death;
- mixed legacy/current pages with current missing pages.

### Selective restart

- translation restart retains OCR/inpaint and clears render/derived text data;
- inpaint restart retains OCR/translation and deletes exact cleaned files;
- OCR restart cascades every child artifact;
- manual edits preserved versus explicitly cleared;
- late provider/inpaint/render commits rejected after reset;
- page reset does not invalidate unrelated pages.

### Identity and mapping

- same filename with changed bytes;
- page reorder with same page count;
- duplicate filenames;
- OCR block sorting without identity change;
- watermark suppression without list-index shift;
- provider partial/out-of-order/missing/duplicate block IDs;
- concurrent manual edit and provider response;
- config change during each stage.
- deterministic legacy block-ID synthesis with duplicate text/geometry;
- legacy OCR rebuild never guesses generated translation mapping;
- manual legacy edit export/remap requires explicit acceptance;
- legacy unknown target language does not silently seed current glossary.

### Barrier and concurrency

- remote translation overlaps later OCR;
- stalled provider never blocks OCR;
- no inpaint launch before every expected OCR terminal;
- OCR failure still permits barrier after durable terminal record;
- cancellation before barrier launches zero inpaint work;
- render page N overlaps inpaint page N+1;
- render page N never starts before its own two branch terminals;
- global native permit prevents cross-chapter OCR/inpaint overlap;
- ML Kit never overlaps detect/OCR or inpaint.

### Storage recovery

- corrupt JSON does not delete source;
- SAF transient read failure does not write empty replacement;
- concurrent chapter persistence uses distinct temp files;
- crash before/after cleaned-image metadata merge;
- orphan version cleanup;
- storage-full and permission-loss replacement failure;
- legacy map migration preserves valid translations.
- legacy open performs no write;
- verified envelope migration keeps recoverable legacy backup;
- failed migration leaves legacy source and sidecars untouched;
- summary cache revision mismatch rebuilds from authoritative document.

## Implementation constraint

Do not implement UI choices first. First freeze artifact, planner, reset,
controller, and preflight contracts with pure tests. UI then consumes those
contracts. This prevents three entry paths from recreating independent resume
logic.
