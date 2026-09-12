# T924 final-target reconciliation and staged migration

> **STATUS (2026-09-12):** the migration below COMPLETED — the profile
> pipeline shipped, survived its A/B, and the legacy coordinator was then
> deleted (zero-legacy). Its staged-migration caution about "a new Batch
> coordinator" resolved as one coordinator with two engine lanes. FF-02
> (persisted layout) remains staged OFF per §1.4's independence rule.
> Execution history: `../implementation-sequence.md`.

Date: 2026-09-05  
Source baseline: `adbe643`  
Status: architecture only; no implementation authorization

This record reconciles the accepted contextual-AI Batch target with the current
code and `chapter-profile-batch-design.md`. The target should replace the old
progressive OCR/PROBE orchestration for contextual AI Batch, but the persisted
layout work is a second contract migration and must not be coupled to the first
coordinator cutover.

## 1. Verdict and required modifications

The OCR-preflight, frozen-profile and global-envelope design fits the existing
store, lease, governor and rolling-frontier architecture. It requires a new Batch
coordinator and new durable artifacts, but it does not require replacing those
safety mechanisms.

Persisted layout is also feasible. `TranslationOverlayView` states that overlay
coordinates remain in source-image space and SSIV owns pan, zoom and orientation
transforms (`TranslationOverlayView.kt:26-31`). The reader passes stored page
dimensions into the overlay (`ReaderPageImageView.kt:143-153,297-305`), and the
planner derives scale only from the supplied page dimensions and decode sample
size (`TextLayoutPlanner.kt:579-613,630-655`). It is therefore not intrinsically
viewport dependent.

The final target needs these modifications:

1. Do not serialize `BlockLayout` directly. It embeds a mutable
   `TranslationBlock`, mask objects, planner occupancy/debug structures and
   component-local IDs (`TextLayoutPlanner.kt:85-160`). Persist a versioned,
   immutable draw-plan DTO containing only stable block identity, source-image
   coordinates, line placement, font metrics identity, orientation/alignment and
   structural clip references.
2. Do not claim device-independent layout from coordinates alone. Planning uses
   Android `Paint` text measurement with the bundled typeface and paint flags
   (`TranslationOverlayView.kt:33-66`). The fingerprint must include font asset
   identity, typeface/style, measurement flags, planner version and any platform
   text-shaping compatibility value established by tests.
3. Rename the current logical stage to `LAYOUT_PREPARE`, but introduce separate
   durable sub-results for color/style preparation and geometry layout. Current
   Batch “render” only runs `RenderColorEstimator.recomputeFor`, copies colors,
   and marks `renderStatus=READY` (`BatchRenderJoin.kt:196-235`).
4. Do not make persisted layout a prerequisite for the new OCR/profile pipeline
   feature flag. Add it after that pipeline is resumable and measurable. Until
   the layout stage lands, retain current reader-side asynchronous planning as a
   compatibility fallback.

No source evidence supports persisting a baked translated raster. The overlay
already draws planned vector text (`TranslationOverlayView.kt:241-309`), so that
part of the target is accepted.

## 2. New races and ownership requirements

### OCR checkpoint ownership

The mandatory OCR checkpoint/rebase remains the first implementation gate.
Current Batch OCR writes into a BATCH candidate. The transaction must atomically
validate generation, page version, lease token, source identity and dependency
fingerprint; publish an origin-neutral OCR checkpoint; preserve committed display;
and close/rebase the Batch candidate before releasing the lease. Releasing only
the lease would expose reusable data under stale writer identity.

### Layout publication race

Layout planning must begin from a store snapshot and commit with compare-and-swap
preconditions for:

- candidate generation and page version;
- translation content fingerprint and stable block IDs;
- OCR geometry/mask fingerprint and page dimensions;
- color/style preparation fingerprint where consumed;
- cleaned-image name and inpaint revision only where the planned result actually
  depends on them;
- layout policy and planner/font-metrics version.

If a user edits a block, a reader/manual run replaces the candidate, settings
change, or inpaint/color preparation advances while layout is running, the stale
layout commit must be rejected. This mirrors the current render patch's checks for
generation, page version, lease, cleaned image, inpaint revision, block
fingerprints, candidate generation and dependency fingerprint
(`BatchRenderJoin.kt:214-235`; `ChapterTranslationStore.kt:809,941,1045-1083`).

### Reader hydration race

The current coordinator drops stale asynchronous plans with a bind generation
(`TextLayoutCoordinator.kt:57-104`). Persisted-layout hydration must keep that
same stale-bind defense. A valid durable plan may populate the in-memory cache,
but its delivery must still match the currently bound page/content generation.

### Profile and translation ownership

A frozen profile must be immutable for a run. Later correction candidates may not
rewrite it. Translation commits must carry the profile content fingerprint and
source-block identity; malformed/partial results cannot advance
`BatchContextFrontier`. Changing only envelope policy must not invalidate an
otherwise identical translation.

## 3. Exact layout dependency graph

The source-backed dependency graph should be represented as separate artifacts:

```text
source image + OCR/detection configuration
        -> OCR blocks + geometry + mask

OCR source text + frozen profile + rolling context + translation policy
        -> translated block text

source image + OCR mask + inpaint mode/algorithm
        -> cleaned image

cleaned image pixels + block geometry + color-estimator version
        -> block text color / contrast style

translated text + block geometry/mask clips + page dimensions
+ reading/writing direction + font/measurement identity
+ font scale/layout preferences + planner algorithm version
        -> persisted layout geometry

persisted layout geometry + current paint-only style + SSIV image transform
        -> Canvas draw
```

Current code couples color estimation and the `renderStatus` commit, but the
planner consumes block geometry/text and a `TextMeasurer`; it does not consume the
cleaned bitmap (`TextLayoutPlanner.kt:579-613`). The draw path reads
`block.textColor`, derives the contrasting stroke color, and applies layout font
size/stroke width (`TranslationOverlayView.kt:241-277`). Therefore:

- translated-text changes invalidate layout, not OCR or cleaned image;
- geometry/mask/page-dimension changes invalidate layout and may invalidate
  inpaint;
- font or measurement changes invalidate layout;
- cleaned-image/inpaint changes invalidate color preparation;
- a color-only change should invalidate draw/style preparation, not line breaks;
- stroke width is currently produced by the planner and affects fit/occupancy, so
  it is geometry-affecting and cannot yet be treated as paint-only
  (`TextLayoutPlanner.kt:555-569,1005,1341-1348`);
- SSIV zoom, pan, holder size and orientation are presentation transforms and
  should not invalidate source-image-space layout.

The persisted DTO should store source-image coordinates. Hydration may rebuild
Android `Path` objects from durable mask/component geometry; those `Path` objects
remain transient. The current overlay already builds component paths at bind time
and verifies geometry dimensions (`TranslationOverlayView.kt:137-174`).

## 4. Minimum buildable migration

Each stage must compile, preserve old behavior behind a feature flag, and leave a
valid resume boundary.

### Stage 1 — semantic contracts and crash-safe storage

Add versioned run records, OCR checkpoints, analysis chunks, frozen profile,
envelope plan and persisted-layout DTO/pointers. Add semantic fingerprints and
compare-and-swap store transactions. Readers and providers still use existing
behavior. The OCR checkpoint transaction is mandatory before orchestration work.

### Stage 2 — pure planning and validation

Add the OCR corpus manifest, hierarchical analysis chunker, deterministic
pre-merge, relevant-profile matcher, global multi-budget envelope planner,
malformed-response taxonomy and shared root retry ledger. Unit-test these without
native engines or provider calls.

### Stage 3 — OCR-preflight coordinator

Behind a contextual-AI-Batch feature flag, run one decoded page at a time:
recognize, persist, checkpoint/rebase, release bitmap and lease, then yield to
interactive native demand. Do not add inpaint or cloud analysis here. Verify
process-death resume and 200-page memory/thermal behavior.

### Stage 4 — analysis and frozen profile

Route typed structured analysis and master reconciliation through the existing
shared provider governor at Batch/background priority. Persist each validated
chunk and atomically freeze the reconciled profile. Resume from the first invalid
artifact. Do not auto-promote model findings into series canon.

### Stage 5 — global profile-aware translation

Dispatch whole-page envelopes in narrative order with the frozen profile subset,
range-safe scene context and gap-free rolling source/translation history. Add
strict validation, deterministic split/backoff and one shared root attempt budget.
Retain one Batch provider request in flight.

### Stage 6 — translation/inpaint overlap

After profile freeze, schedule serial local inpaint during remote translation
latency. Reuse the durable mask and re-decode the source. Keep page leases and
candidate/committed publication rules. Measure whether overlap helps on device.

### Stage 7 — persisted `LAYOUT_PREPARE`

Split color preparation from layout geometry internally. Calculate and commit a
versioned persisted draw plan only after translation is complete and required
geometry exists. Change reader binding to prefer a valid persisted plan, retain
the current async planner as fallback, and keep the 12-page cache only for hydrated
objects/paths. Update completion semantics only when all reader paths can hydrate
the durable plan.

### Stage 8 — defaulting and cleanup

Run provider, lifecycle, memory, thermal, Pager and Webtoon tests. Make the new
path default only after evidence. Retire old contextual-AI PROBE code after the
feature flag rollback window; do not delete shared legacy mechanisms earlier.

## 5. Components to retain

Keep these responsibilities and evolve their contracts narrowly:

- `BatchChapterTranslator`: foreground-service shell, teardown and exit paths;
- `ChapterTranslationStore`: serialization point, leases, candidate/committed
  display and compare-and-swap patches;
- `PageWorkPlanner`, `BatchResumePlanner`: reuse decisions, extended for semantic
  artifacts and the forced-OCR reuse fix;
- `BatchContextFrontier`: gap-free context authority;
- `ProviderRequestGovernor`: shared provider/model/credential quota and priority;
- `ContextualResponseParser`: strict ID checks, extended with failure taxonomy;
- native engine/session ownership and quarantine guards;
- `BatchRenderJoin` concepts for joining translation and inpaint, though its
  current color-only “render” body becomes `LAYOUT_PREPARE` orchestration;
- `TranslationOverlayView`: Canvas presentation and SSIV transforms;
- `TextLayoutPlanner`: planning algorithm, invoked earlier by Batch and retained
  as reader fallback during migration;
- foreground service, queue, notifications and per-page reader refresh.

Manual/Auto scheduling remains untouched except that it may consume compatible
origin-neutral OCR checkpoints and valid persisted layouts.

## 6. Old assumptions to retire

For contextual AI Batch only, retire after the replacement path is proven:

- progressive OCR as the discovery mechanism for cloud-envelope boundaries;
- physical PROBE-page handoff and the OCR barrier tied to it;
- token-overflow-only flushing and the small-first/Fast-mode idea;
- mutable incremental chapter glossary as canonical truth during a run;
- `renderStatus=READY` meaning color estimation alone;
- reader cache miss/process restart necessarily invoking `TextLayoutPlanner` for
  a valid Batch-prepared page.

Retain logical look-ahead, hard token ceilings, one envelope in flight, page
atomicity, gap-free context and legacy PROBE behavior for paths that still use the
old coordinator.

## 7. Constants requiring measurement

No numeric value below should be frozen as a product constant before measurement:

- analysis input/output budget, page/block/candidate caps and overlap;
- envelope block/page/source/output/ID-complexity caps;
- split depth, leaf count and total root request attempts (existing eight-attempt
  behavior is only a conservative starting point);
- provider/model/credential total RPM and TPM;
- aggregate Batch/background 15-RPM rolling-window implementation details;
- OCR/native reader-priority turn and anti-starvation thresholds;
- bounded queue capacity and any compressed-byte prefetch;
- OCR checkpoint flush/batching policy and artifact size limits;
- font/platform compatibility boundary for persisted layout;
- layout serialization size, hydration latency and in-memory cache size;
- small-chapter analysis bypass threshold;
- profile-subset and rolling-history budgets.

## 8. Rejected or narrowed proposals

1. **Reject one giant delivery.** The completion-contract and reader-storage
   migration substantially increase blast radius beyond the coordinator change.
2. **Reject serializing runtime layout objects.** Persist a stable DTO and rebuild
   transient `Path`, `Paint` and cache objects.
3. **Narrow “paint/style separation.”** Text/stroke colors can be paint-only, but
   stroke width currently participates in planner fit and collision bounds and
   must remain in the layout fingerprint.
4. **Narrow “no reader-time planning” to valid Batch-prepared pages.** Manual/Auto,
   legacy data, corrupt/missing layouts and feature-flag rollback still need the
   current asynchronous planner fallback.
5. **Reject automatic permanent canon promotion.** Persist model discoveries as
   candidates until a later authority/UX design confirms them.
6. **Reject independent analysis and translation RPM pools.** Both consume the
   same Batch sublimit and provider quota; Manual/Auto remain interactive priority.
7. **Reject inpaint during OCR preflight and cross-page OCR fan-out.** Current
   native ownership is serialized, and neither change helps chapter analysis.

## 9. Convergence decision

This final target should replace the previous chunk-oriented contextual-AI Batch
design through the staged migration above. Architecture discovery is complete
enough to begin Stage 1 implementation planning. Remaining decisions are schema
and measured constants, not reasons to reopen the orchestration direction.

