# Chapter-profile Batch Translation architecture

> **STATUS (2026-09-12):** SHIPPED, with two deltas from this document:
> (1) it landed as ONE coordinator (`ChapterProfileBatchCoordinator`) with
> TWO engine lanes — the AI lane below plus a STANDARD-engine per-page lane
> sharing the same OCR preflight and FINALIZE (Director's Phase-4 design);
> (2) the legacy `SequentialBatchCoordinator` was subsequently deleted
> (zero-legacy). Completion semantics are translation-terminal without an
> in-pass render; display rides the overlay, candidate snapshots, and FF-02
> persisted layouts. Execution history: `../implementation-sequence.md`.

Date: 2026-09-05 · HEAD: `adbe643` · Status: proposed architecture; no
implementation or runtime/device tests.

This design replaces the earlier progressive-OCR chunk proposal **for AI Batch
Translation only**. Manual/Auto reader translation and non-contextual translation
engines retain their current latency-oriented paths. File citations below are
relative to `app/src/main/java/`; paths beginning with `pipeline/`, `model/`,
`translator/`, `artifact/`, or `store/` are under `eu/kanade/translation/`.

## 1. Feasibility verdict

**VERIFIED:** The architecture fits the repository, but it is a substantial Batch
orchestration change rather than an extension to `StreamingChunkPlanner`. The
code already persists OCR text, geometry, fingerprints, and a durable inpaint
mask before inpainting (`pipeline/SinglePageOnnxPhase.kt:818-952`;
`model/PageTranslation.kt:53-95`). It already has page leases, generation/version
write fences, candidate/committed display separation, strict Batch response
validation, bounded retries, a shared provider governor, and a gap-free context
frontier.

**RECOMMENDATION:** Replace progressive OCR/admission in the contextual-AI Batch
path with durable phases:

```text
validate local chapter + freeze run configuration
    -> hash and plan reusable stages
    -> full bounded OCR preflight
    -> structured hierarchical chapter analysis
    -> reconcile and freeze ChapterProfile
    -> globally plan whole-page envelopes
    -> execute envelopes sequentially with live revalidation
    -> inpaint/render/commit per page
    -> reconcile and close
```

Keep `BatchChapterTranslator` as the lifecycle shell and retain the store,
`PageWorkPlanner`, `BatchContextFrontier`, provider governor, strict parser/retry
controller, render join, and native helpers. Introduce a new phase coordinator;
do not force the current `SequentialBatchCoordinator.runPass1()` to model this
state machine. Its central loop deliberately couples OCR, planner admission,
PROBE retention, translation, and inpaint (`pipeline/batch/SequentialBatchCoordinator.kt:617-735`).

| Difficulty | Work |
|---|---|
| Easy/local | Reuse existing OCR payload shape; release decoded pages after each OCR; add planner metrics. |
| Moderate | Global envelope planner; relevant-profile matcher; frozen run configuration; strict analysis schemas; profile-aware translation prompt; preparation progress. |
| Invasive | New durable chapter/profile artifacts and recovery state; hierarchical reconciliation; replacement of AI Batch coordinator; series/user glossary authority; sparse stream-to-download identity repair. |

Phase-scoped coexistence also needs a store transaction, so it is not a lease-only
change: current OCR persistence advances a BATCH-origin candidate, and candidate
writes reject a different origin (`artifact/ChapterArtifactStore.kt:346-399`).

The previous 30–40-block soft-cap design is not sufficient for these goals. Its
structural limit remains useful inside the new global planner, but its progressive
PROBE mechanism becomes unnecessary for AI Batch.

## 2. Current-code impact map

| Component | Current responsibility | Proposed impact |
|---|---|---|
| `pipeline/batch/BatchChapterTranslator.kt` | Batch generation, engine setup, hashing, collaborators, reconciliation and teardown (`:282-446,570-795`) | Retain shell. Freeze a complete run configuration, load durable run/profile state, invoke the new phased coordinator. Bound source-hash fan-out. |
| `pipeline/batch/SequentialBatchCoordinator.kt` | Progressively OCRs until token overflow/PROBE, then translates/inpaints/renders (`:617-735`) | Retain for legacy/non-contextual lanes. Replace for contextual AI Batch with `ChapterProfileBatchCoordinator`. |
| `pipeline/batch/BatchLaneWorkers.kt` | OCR/inpaint helpers, AI admission, provider execution, persistence | Split reusable workers by phase. Remove AI planner ownership and decoded `nativeHandoff` across an OCR barrier. Preserve guarded writes, translation merge, inpaint and render behavior. |
| `pipeline/SinglePageOnnxPhase.kt` | Separately supports `analyzePage` and `inpaintPage`; OCR merge occurs first | Reuse. OCR sprint calls analyze, persists, then releases page bitmap. Inpaint later re-decodes and reads durable mask. |
| `model/PageWorkPlanner.kt` / `pipeline/batch/BatchResumePlanner.kt` | Stage decisions from evidence; ordered translation dependency snapshot | Retain stage rules, but plan OCR first and re-plan from fresh store state after OCR/profile freeze. Fix forced OCR reuse through evidence inputs. |
| `translator/contextual/StreamingChunkPlanner.kt` | Discovers envelopes while pages arrive | Replace in new AI Batch with a pure global planner over a lightweight OCR manifest. Retain for Manual/Auto/legacy callers as applicable. |
| `pipeline/batch/BatchContextFrontier.kt` | Contiguous natural-order rolling context; PARTIAL cannot advance | Retain as execution authority. Global planning chooses membership; rolling context is attached immediately before dispatch. |
| `translator/retry/AiTranslationRetryController.kt` | Frozen envelope, strict accounting, one whole retry plus missing-only retries | Retain core. Add whole-page structural split/backoff above it; promote only independently complete pages. |
| `translator/contextual/ContextualResponseParser.kt` | Validates canonical IDs, blanks, duplicates, unknown and missing outputs (`:104-188`) | Retain. Add page-level completeness result and explicit failure class used by split/backoff. |
| `artifact/ChapterArtifactManifest.kt` / `ChapterArtifactStore.kt` | Page artifacts, generation metadata and simple glossary pointer | Extend schema with run/profile/analysis pointers. Publish immutable sidecars before atomically updating manifest. |
| `store/ChapterGlossaryStore.kt` / `ChapterGlossaryBuilder.kt` | Incremental chapter term→target hints mined from machine translations | Treat as legacy compatibility. Do not mutate it during a frozen-profile run. Replace Batch authority with structured profile content. |
| `ProviderRequestGovernor.kt` | Shared provider/model/credential quota with interactive priority | Keep the provider-wide bucket and add a 15-RPM Batch/background sub-limit shared by analysis and translation. Configure provider-wide limits separately. |
| progress models/tracker/sheet/service | Per-page OCR/translate/inpaint/render/display plus buffered counts | Extend with chapter-level preparation phases and `(done,total)` work, without inventing fake per-page analysis states. |

## 3. Revised Batch state machine

### 3.1 Durable states

1. **ADMISSION/DOWNLOAD** — require a finalized local directory/archive. The
   current Batch explicitly fails when local chapter files are absent
   (`ChapterTranslator.kt:583-635`). Interrupted downloader `.tmp` files are not
   a valid Batch source.
2. **RUN_SNAPSHOT** — capture source/target languages, OCR model, reading order,
   inpaint mode, provider/model/settings, protocol versions, analysis policy,
   series/user glossary fingerprints and envelope policy. Settings changes apply
   next run; “apply now” means stop, create a new generation, and re-plan.
3. **SOURCE_VALIDATION** — natural page identities, trusted count and source
   hashes. Replace the current one-coroutine-per-page hashing fan-out
   (`BatchChapterTranslator.kt:394-401`) with a small bounded IO worker count.
4. **OCR_PLAN** — evaluate detection/OCR reuse using current source and stage
   fingerprints. Do not let ordered translation gaps rewrite OCR decisions.
5. **OCR_PREFLIGHT** — process every required page; READY and TEXTLESS are valid
   completion. Persist and checkpoint each page before releasing its lease and
   bitmap. Any unresolved OCR/persistence failure stops before paid analysis.
6. **ANALYSIS_PLAN** — build a lightweight immutable corpus manifest from
   persisted OCR only. If there is no translatable work, skip provider analysis.
   Reuse a matching frozen profile when its content fingerprint matches.
7. **ANALYSIS_CHUNKS** — execute bounded structured extraction requests in page
   order and persist each validated result independently.
8. **PROFILE_RECONCILE** — deterministic normalization/pre-merge followed by one
   or more bounded master reconciliation requests when conflicts require model
   judgment. Validate all evidence references.
9. **PROFILE_FROZEN** — atomically publish an immutable `ChapterProfile` and
   attach its fingerprint/version to the run. No canonical mutation after this
   point.
10. **ENVELOPE_PLAN** — compute an ordered whole-page plan from all pending blocks,
    structural budgets, scene boundaries and profile-subset estimates.
11. **TRANSLATE** — before each envelope, reacquire affected page leases and
    revalidate current store state. Manual/Auto may have completed work since
    global planning; skip it and deterministically re-plan the remaining suffix.
    Attach the frozen profile subset and current gap-free rolling history.
12. **NATIVE/RENDER** — retain the current one-envelope-in-flight behavior:
    remote translation may overlap serial inpaint; render waits for the relevant
    translation and native gates (`SequentialBatchCoordinator.kt:452-613`).
13. **FINALIZE** — durable reconciliation, flush, lease cleanup, artifact retention
    and foreground progress completion.

Phase transitions are persisted. Startup resumes the first incomplete phase rather
than inferring chapter phase solely from page statuses.

## 4. Artifact and data model

### 4.1 Existing OCR artifact

`PageTranslation` already persists block text/geometry, source/detection/OCR
fingerprints, dimensions, textless/readiness states and `inpaintMaskBoxes`.
`allTextDetections` and bitmaps are transient; the mask is their durable inpaint
equivalent (`model/PageTranslation.kt:78-98,159-171`). This is sufficient as the
source for a lightweight corpus manifest. Add stable OCR artifact identity and
natural page index where missing; do not duplicate full page snapshots inside the
profile.

### 4.2 New immutable sidecars

```text
ChapterRunRecord
  runId, state, frozenRunConfigFingerprint
  orderedSourceDigest, OCR-corpus fingerprint
  analysisPolicyFingerprint, profilePointer
  envelopePolicyFingerprint, completed phase counters

AnalysisChunkResult
  schema/protocol, chunkId, core page range, context-overlap range
  OCR artifact IDs and source digest
  entities[], terms[], relationships[], scenes[], narrativeSummary
  ambiguity/conflict notes, evidence refs, analyzer provenance

ChapterTranslationProfile
  schema, monotonic version, content fingerprint
  run-input fingerprints and analyzer provenance
  canonical entities/terms
  range-scoped scenes and narrative context
  unresolved/conflicting facts
  series-update candidates

EnvelopePlan
  plan fingerprint, ordered envelopes
  whole-page membership, stable block IDs
  estimated input/output/structure costs
  scene/profile references

PageOcrCheckpoint
  pageKey, natural index, canonical OCR-content fingerprint
  source/detection/OCR fingerprints, immutable OCR page-snapshot pointer
  prior committed-display reference, producing provenance and timestamp
```

Store these as immutable JSON sidecars and add manifest pointers. The current
manifest has only page records and a vocabulary-only `GlossaryPointer`
(`artifact/ChapterArtifactManifest.kt:16-60,276-300`), so this is a schema change,
not a reinterpretation of the existing glossary JSON.

`checkpointOcr` is a guarded store transaction. While Batch still holds the
lease, it writes and validates the immutable OCR snapshot, compares generation,
lease token, page/artifact version, candidate ID and dependency fingerprint, then
atomically installs the checkpoint pointer, closes/rebases the BATCH candidate,
preserves the prior committed display, and returns the new page snapshot/write
identity. Only after success may the caller release the lease. On any publication
or compare failure, the manifest stays on the prior state and Batch retains
ownership long enough to report a persistence failure. A new Manual/Auto/Batch
candidate starts from the checkpoint as its reusable native base.

### 4.3 Structured fact representation

Every canonical fact carries `factId`, type, canonical source/target forms,
aliases, confidence, evidence references `(pageKey, stableBlockId, source excerpt
hash)`, scope, provenance and conflict state. Entity gender is an enum:
`MALE`, `FEMALE`, `UNKNOWN`, `CONFLICTING`; pronouns are separate nullable facts.
Evidence has strength (`EXPLICIT`, `STRONG_CONTEXTUAL`, `WEAK`) and the master may
promote gender only from explicit or corroborated strong evidence. Weak name or
speech-style cues remain notes.

Scene records use page/block ranges, tone/register flags, participants and concise
narrative context. Facts additionally declare applicability:

- `CANONICAL_CHAPTER_WIDE`: identity/spelling/gender supported later but safe to
  apply earlier;
- `RANGE_SCOPED`: tone, relationships at that point, revelations and plot state;
- `AVAILABLE_FROM`: a narrative fact usable only from its evidence point onward.

This permits later canonical identity evidence without leaking later plot events.

### 4.4 Provenance and invalidation

Do not fingerprint candidate IDs, page versions, or sidecar filenames: those are
transaction identities and can change without semantic OCR change. Define a
canonical `PageOcrContentFingerprint` from natural page identity, source hash,
detection/OCR configuration, ordered stable block IDs, normalized source text,
geometry, textless state and mask revision. `OcrCorpusFingerprint` hashes those
page content fingerprints in natural order.

`ProfileInputFingerprint` includes the OCR-corpus fingerprint, source/target
languages, analysis schema/prompt/model/provider policy, and input user/series
authority fingerprints. `ProfileContentFingerprint` hashes the validated canonical
profile. Translation provenance includes the profile content fingerprint,
translator signature, prompt/protocol version and page source-block identity.
Record rolling-context/profile-subset/envelope-plan fingerprints separately for
reproducibility; changing envelope policy alone does not automatically invalidate
an otherwise compatible machine translation. A monotonic version is useful
operational metadata but must not be the sole validity key. User-edited blocks and
the last committed display remain authoritative through every invalidation.

The current glossary version is updated incrementally after translation and can
cause later repair (`PageWorkPlanner.kt:290-309`; `BatchLaneWorkers.kt:621-629`).
Frozen-profile Batch must stop doing this. Possible corrections are stored as
separate candidates for a future run.

## 5. Memory and concurrency model

For the 200-page stress case, use one native page at a time:

```text
open source -> decode one sampled page -> analyze(detector + ROI OCR)
-> persist OCR/mask -> recycle bitmap/pools -> checkpoint OCR -> release lease
-> next page
```

`RoiPageRecognitionEngine.analyze()` already keeps detector and per-ROI OCR under
one native guard (`recognition/RoiPageRecognitionEngine.kt:267-284`), while the
pipeline has one native admission wrapper (`pipeline/EngineLane.kt:117-146`).
Running detector and OCR for different pages concurrently would fight shared
sessions/delegates and is not supported safely by current ownership. Engine warmth
comes from reusing the initialized engine serially, not parallel inference.

**RECOMMENDATION:** Initially keep decode + analyze + OCR persistence sequential.
At most, add a measured one-item compressed-byte/IO prefetch later; do not prefetch
a second decoded bitmap. Existing decode/analyze preflights use heap/system-memory
checks and sampling (`util/TranslationMemoryBudget.kt:25-31,299-363`).

Reader responsiveness is a scheduler requirement, not merely a measurement. Add
priority admission in front of the native quarantine: Batch may admit at most one
page, releases the lane between pages, checks cancellation and pending interactive
work, and yields to Manual/Auto waiters. Add a bounded starvation rule so repeated
reader arrivals cannot suspend Batch forever; the exact time/turn threshold is a
device-tuned parameter. The present provider governor priority does not affect the
native mutex.

Do **not** inpaint during OCR preflight. It shares the same native guard, requires
another memory-heavy pass, disrupts detector/OCR locality, and provides no input to
chapter analysis. After profile freeze, retain remote-translation versus serial
inpaint overlap. Because OCR releases its bitmap, inpaint deliberately re-decodes;
`inpaintPage` already supports that using the durable mask
(`BatchLaneWorkers.kt:1096-1153`).

Do not carry 200 `DecodedPage` handoffs or hold 200 page leases. Current
progressive code returns decoded handoffs after OCR (`BatchLaneWorkers.kt:1016-1028`)
and holds page ownership until later stages. The new OCR worker must release the
decoded page immediately. It must then use a new atomic **OCR checkpoint** store
transaction that retains the validated OCR/mask artifact while closing or rebasing
the BATCH candidate, and only then release the phase lease. Releasing today's lease
alone is insufficient: the candidate remains BATCH-origin and later writes from a
different origin can be rejected for provenance mismatch
(`artifact/ChapterArtifactStore.kt:346-399`). Translation/inpaint later open a
fresh candidate/lease and fresh write identity.

OOM/decode deferral produces a durable failed/retryable page as today. Two
recognition OOMs create a diagnostic failure in `analyzePage`
(`SinglePageOnnxPhase.kt:857-889`), while the outer Batch OOM policy may abort after
repeated failures. Cancellation checks between pages, releases the current bitmap/
lease in `finally`, flushes durable OCR and preserves completed preflight pages.
Process death loses only the active bitmap/native call; startup recovery marks
RUNNING artifacts retryable and resumes from persisted pages.

## 6. Chapter-analysis design

### 6.1 Chunk planning

Plan analysis chunks by estimated tokens **and** structural output, using whole
pages. Reserve at least half the provider context initially for instructions,
existing canon and structured output; the exact ratio is a runtime tuning input.
Do not use fixed 20-page groups. Cap input OCR tokens, contributing pages, blocks,
candidate count estimate and requested output tokens.

Use small boundary overlap, initially up to one adjacent contributing page or 10%
of the input budget, whichever is smaller. Mark pages as `core` versus `context`;
the model extracts primary records for core pages and may cite overlap evidence.
The master deduplicates by evidence and normalized source forms. This avoids paying
for large repeated windows.

### 6.2 Extraction schema

Each chunk returns strict structured records, not only prose:

- terms: source form, proposed canonical target, aliases, entity/type, evidence;
- entities: identity, aliases/titles, gender/pronoun facts with evidence strength,
  relationships and conflicts;
- scenes: bounded page/block range, participants, genre/tone/register, explicit/
  intimate/violent/comedic/serious context;
- narrative: concise range-scoped situation and relevant revelations;
- unresolved questions and candidate equivalences.

All referenced pages/blocks must exist in the chunk or overlap. Unknown references,
duplicate record IDs, missing required fields, overlong fields and invalid enums
fail validation. Empty `promptText()` currently swallows provider errors for some
providers (`translator/providers/GeminiTranslator.kt:123-133`), so analysis needs
a typed structured provider API with the normal retry/governor contract.

### 6.3 Master reconciliation

First normalize and pre-merge deterministically: Unicode form, exact source
aliases, shared evidence IDs, compatible types and explicit user/series IDs. Then
send bounded conflict sets plus evidence snippets to a master model. It must choose,
retain ambiguity, or mark conflict; it cannot invent an evidence reference.

For the Reina example, later explicit identity/spelling/gender evidence can merge
earlier variants into a female `Reina Alstella` entity. A weak male inference is
retained as rejected/conflicting evidence, not averaged into a guess. If the
candidate graph itself exceeds a safe request, reconcile hierarchically by entity
components, then perform a small final index pass.

Summaries remain range-scoped supporting context and never substitute for entity/
term records.

### 6.4 Authority hierarchy

Use this order:

```text
user-confirmed facts
  > compatible established series canon
  > high-confidence frozen chapter facts
  > rolling source+translation context
  > local inference
```

No user or series glossary exists in current application source; only an
incremental chapter vocabulary map was found. Add series storage as a separate
manga/source-scoped artifact. Initial implementation should emit series-update
candidates but never auto-promote model-derived gender, relationship, or spelling.
Promotion can later require user confirmation or corroboration across chapters.

## 7. Translation request contract

Every envelope carries:

1. **Relevant frozen profile subset.** A local matcher scans current source text
   for canonical forms, aliases, titles and terms, then adds scene participants and
   directly related facts. Include entity IDs so the model can link aliases. Cap
   this subset; full series/chapter profiles are never blindly repeated.
2. **Range-safe scene context.** Include only the current scene/range and facts
   available at that point, plus chapter-wide canonical facts. Explicit-scene
   context guides lexical meaning; it never creates a global replacement rule.
3. **Gap-free rolling history.** Carry recent source text, accepted target text,
   resolved entity IDs and compact unresolved reference state. Mark prior English
   pronouns as translations, not canonical gender evidence.
4. **Current structured OCR blocks.** Stable natural page/block IDs and source
   text remain the response authority.

Prompt rules separate identity from gender: resolve the referent first; use
profile gender only when established; otherwise use strong current/rolling source
evidence. If unresolved, prefer a name/title, sentence restructuring, or natural
singular “they”. The current prompt merely tells the model to infer pro-drop
subjects and maintain pronouns (`translator/contextual/TranslationPrompts.kt:76-106`);
it has no evidence or uncertainty model.

## 8. Global envelope planner

The planner operates on persisted lightweight page manifests and produces whole-
page envelopes before provider translation. It prefers scene boundaries but may
cross one when budget efficiency requires it. It never splits a page.

Admission uses all of:

- hard provider context ceiling and safety margin;
- profile subset + rolling-context estimate;
- response reserve derived from expected target expansion and framing;
- maximum translatable blocks;
- maximum contributing pages;
- source token/character count;
- stable-ID/line structural count;
- optional scene-boundary penalty, not a correctness fence.

**Initial recommendation:** conservative static limits and instrumentation, not
adaptive growth. Use a provider-independent structural ceiling of approximately
32 blocks and 8 contributing pages as a starting experiment, with token/output
checks always stricter. Do not automatically double those limits for a 16K model;
larger context does not prove better structured-output reliability. These numbers
are **ASSUMPTIONS requiring provider tests**, not accepted product constants.

At execution, attach actual rolling context and recompute token fit. If it no
longer fits, split at a whole-page boundary before sending. A single token-oversized
page remains rejected because page atomicity is invariant.

## 9. Malformed and partial response strategy

**VERIFIED current strength:** Batch uses canonical `pN_bN` IDs and strict parsing.
It detects missing, duplicate, unknown, blank and malformed lines, checks exact
cardinality and ownership, rejects echoed source and structural refusals, freezes
the retry tree, and permits rolling context only for `Complete`
(`ContextualResponseParser.kt:104-188`;
`translator/retry/AiTranslationRetryController.kt:112-216,385-509,651-727`).
It currently performs one identical whole-envelope repair followed by up to two
missing-only requests; it does not reduce the page envelope after structural
failure.

Proposed policy:

1. Validate transport, protocol version, exact stable IDs, one output per ID,
   page/block ownership, nonblank sanitized text and refusal/echo checks.
2. Classify the failure before retaining anything:
   - `MISSING_ONLY`: no unknown IDs, duplicates, conflicts, malformed lines,
     framing/version error, refusal or ownership error. Commit only the contiguous
     complete-page prefix. Later independently complete pages may persist as
     non-display candidates for reuse after the gap; they do not advance context.
   - `AMBIGUOUS_PROTOCOL`: unknown IDs, any duplicate/conflict, malformed framing/
     lines, wrong protocol or ownership ambiguity. Discard parent-attempt target
     values and retry from frozen source/profile context at smaller size.
   - `TERMINAL_REFUSAL/CONTENT`: retain no affected page unless an earlier page was
     already committed in a prior independent transaction.
   Accepted blocks from an incomplete page never render or advance context.
3. On structural failure, avoid an identical large whole retry when complexity is
   above the conservative threshold. Split remaining pages near half structural
   weight and retry children sequentially under the same bounded attempt tree.
4. Subject to the failure taxonomy in item 2, preserve independently complete
   pages only for `MISSING_ONLY`; re-plan the remaining whole pages. Unknown extra
   lines never overwrite anything and make the parent response
   `AMBIGUOUS_PROTOCOL`, so its translations are discarded before smaller retry.
5. One root `RequestRetryBudget` covers the parent and every child/targeted request.
   Start with the existing maximum of 8 actual attempts
   (`translator/retry/TranslationRetry.kt:40-72`), maximum split depth 3 and no
   more than 4 leaf groups; the attempt ceiling wins. Child calls must receive the
   same budget rather than construct new ones.
6. If a single whole page still fails, allow bounded missing-block repair within
   the same frozen page/profile/context transaction; never commit fragments as
   separate envelopes. Exhaustion pauses at that page.
7. Advance `BatchContextFrontier` only through contiguous fully committed pages.
   PARTIAL/malformed candidates never feed rolling history.

Here, page atomicity means that initial/global envelopes and durable commits never
split a page. A targeted repair may send only its missing stable IDs, but it remains
inside the same frozen page transaction and cannot independently commit or alter
context.

This is deterministic backoff. Provider/model adaptation can follow only after
instrumentation shows stable success distributions.

## 10. Exact resume/reuse behavior

| Interruption point | Durable survivor | Resume action |
|---|---|---|
| During source hashing | Previously stored pages; incomplete run phase | Recompute missing hashes with bounded IO. |
| Mid OCR page | Prior committed OCR pages; current bitmap lost | Recover RUNNING page to retryable, revalidate its source, resume OCR sprint. |
| After OCR preflight | Full OCR corpus/masks and fingerprints | Skip recognition; resume analysis planning. |
| Mid analysis chunk | Earlier validated chunk results | Retry only missing/invalid chunk under request-attempt policy. |
| After chunks, during master | Chunk evidence survives; no frozen profile pointer | Re-run reconciliation; do not translate. |
| Profile frozen, before translation | Immutable profile and envelope-plan inputs | Reuse profile; rebuild/validate envelope plan and frontier. |
| Mid translation | Complete per-page commits, incomplete candidates, durable attempt ledger | Revalidate pages/profile fingerprint; resume at first unresolved gap without repaying valid fragments. |
| Mid inpaint/render | OCR/profile/translation plus any valid cleaned artifact | Resume only missing native/layout stages. |

Source/settings/config change invalidates only dependent phases. OCR model/source
changes invalidate OCR, analysis, profile, translation and downstream stages.
Analyzer/profile-policy or series/user canon change invalidates profile and Batch
translations, not OCR. Font/layout change invalidates layout only. Inpaint change
invalidates inpaint/layout, not analysis/translation.

Forced translation must accept current source/config evidence and independently
reuse valid detection/OCR; current force logic couples OCR reuse to inpaint
readiness (`model/PageWorkPlanner.kt:30-41`). Sparse streamed URL keys must migrate
to downloaded filename identities without requiring a full-sized store; current
rekey requires equal online/disk/store page counts (`TranslationManager.kt:1267-1281`).
Both are prerequisites for reliable reuse claims.

## 11. Manual/Auto coexistence

Manual/Auto retain immediate per-page translation. They do not wait for chapter
OCR/profile analysis.

During OCR sprint, Batch acquires one page lease, persists and checkpoints OCR,
then releases it.
A reader request for that exact active page attaches/waits under current lease
semantics; Manual does not preempt Batch (`store/PageStageLeaseTable.kt:75-99`).
Other pages remain available. Once OCR commits, Manual/Auto should reuse it
immediately when source/OCR configuration evidence matches.

After profile freeze, Manual/Auto may translate a page with the reader path. Before
dispatching a planned Batch envelope, Batch reacquires and live-revalidates every
page; user edits/manual completion remain authoritative and are excluded. The
frozen profile is not rewritten from that result. A missing natural-order page
still fences rolling context as today.

Analysis and Batch translation requests use `BACKGROUND`; Manual/Auto use
`INTERACTIVE` (`TranslationPipeline.kt:410-416`). Both use the same provider/model/
credential quota bucket. Add one aggregate Batch allowance of 15 RPM beneath it;
analysis and translation consume that same Batch allowance rather than receiving
15 RPM each. Existing interactive reserve applies only while an interactive
waiter exists and cannot preempt an admitted request
(`translator/ProviderRequestGovernor.kt:443-468`).

## 12. Request pacing and cost

The current default is 60 RPM/60K TPM, one-second spacing and one in-flight request
per bucket (`ProviderRequestGovernor.kt:66-84,655-673`). The intended 15-RPM Batch
norm is not implemented. Use two nested policies: (1) a provider/model/credential
bucket representing the real total provider quota, shared with Manual/Auto; and
(2) a 15-RPM aggregate Batch sub-limit covering analysis plus translation. For a
Gemini free credential whose total quota is 15 RPM, both limits are 15 and reader
priority consumes the shared total. Other providers may have a higher verified
provider-wide limit while Batch remains capped at 15. These are rolling windows,
not forced four-second sleeps.

Analysis requests add real cost:

```text
progressive AI Batch cost ~= translation envelopes + retries
profile AI Batch cost     ~= analysis chunks + master reconciliation
                            + translation envelopes + retries
```

For a 200-page chapter, call count depends on OCR density, not page count. If `B`
is pending blocks and the structural cap is 32, translation requires at least
`ceil(B/32)` successful envelopes, potentially more from page/token boundaries.
If analysis corpus needs `A` extraction chunks and `M` reconciliation calls, the
profile premium is `A+M` on the first matching run and zero on a profile-resuming
run. Prompt/profile subset tokens repeat per translation envelope.

The analysis earns its cost only if it reduces malformed retries, terminology
repairs, inconsistent retranslations, or manual corrections enough to outweigh
`A+M`. Instrument analysis/translation input-output tokens, attempts, structural
failure type, accepted blocks per attempt, profile-subset size, correction rate
and elapsed phase time. Skip provider analysis for textless chapters, no remaining
translation work, or a compatible frozen profile. Consider a product threshold
for very small chapters after measurements; do not claim cost savings before data.

Compared with progressive batching, full OCR preflight delays first render and
adds analysis calls, but enables stable chapter-wide facts, scene-aware global
planning, no OCR probe, deterministic resume, and structural-risk-aware envelopes.
It keeps memory bounded because OCR artifacts—not bitmaps—accumulate.

## 13. UX and progress

Extend existing progress infrastructure rather than replacing it. Add a
chapter-level preparation state alongside existing per-page `BatchPhase` counts:

```text
Preparing chapter / validating sources
OCR extraction       162 / 200
Chapter analysis       6 / 10
Building profile
Planning translation
Translating / Rendering
Finalizing
```

Current progress only has `FIRST_PASS/FINALIZING/FINISHED` and per-page
OCR/TRANSLATE/INPAINT/RENDER/DISPLAY
(`model/TranslationProgressSnapshot.kt:42-65`;
`pipeline/batch/TranslationBatchEvent.kt:6-59`). Add chapter-phase counters rather
than pretending an analysis request belongs to one page. Existing buffered counts
remain usable for translation.

UI must explain that Batch performs chapter preparation for consistency, while
Manual/Auto remains immediate. Paused states identify the failing phase and the
resume action. Foreground notification continues through OCR/analysis and retains
Stop All behavior.

## 14. Testing plan

### Pure/schema tests

- profile/analysis serialization, future-schema preservation and crash-safe pointer publication;
- authority precedence, alias merging, evidence referential integrity and deterministic fingerprints;
- male/female/unknown/conflicting gender; weak evidence never promotes gender;
- later strong gender evidence informs canonical identity without earlier plot leakage;
- scene ranges for comedy → argument → explicit scene → aftermath → action;
- explicit-scene “come/cum” disambiguation without chapter-wide replacement;
- CJK omitted subject, unresolved referent, name/title/restructured/they fallback;
- aliases, honorific variants and contradictory Reina/Raina candidates;
- relevant-subset matcher including missed-alias allowance and size cap;
- global planner exact token/output/blocks/pages/scene boundaries and oversized single page;
- strict translation and analysis validation: missing, duplicate, unknown, wrong ownership, blank, malformed, truncated and conflicting IDs;
- page-safe retention and deterministic whole-page split/backoff.

### Integration/resume tests

- 200-page normal stress chapter with one decoded page retained at a time;
- text-heavy chapter, mostly textless chapter, and one extremely dense page;
- cancellation/process death during validation, every OCR boundary, analysis
  chunk, master reconciliation, profile publication, envelope translation,
  inpaint, render and final flush;
- OCR complete/profile incomplete; profile complete/fragmented translation;
- pages 1–2 and 4 complete, 3 and 5–6 missing, including a shared envelope and
  gap-free frontier advancement;
- malformed large response splits smaller without duplicate paid work;
- partial page never renders/advances context; independently valid preceding page does;
- source/OCR/analyzer/profile/translator/inpaint/layout configuration invalidation matrix;
- disk full, SAF permission loss and rejected OCR/profile/translation publication;
- reader Manual/Auto during active OCR page, another OCR page, after OCR commit,
  after profile freeze and during Batch provider request;
- Manual completion/user edit between global plan and dispatch;
- shared governor under simultaneous analysis/background and reader interactive requests at 15 RPM;
- retry/accounting across analysis and translation request trees;
- sparse streaming reader translations → completed download → filename rekey → Batch reuse;
- download rekey while another chapter Batch is active;
- Android 8 lifecycle, process recreation, foreground notification, queue restore
  without auto-start, thermal throttling and low-memory conditions.

### Required device measurements

On representative 6-GB devices and relevant CPU/GPU/NPU routes, measure peak Java,
native and graphics memory; bitmap count; OCR throughput after warm-up; temperature;
analysis/translation request counts and token usage; structured success by blocks/
pages/output size; retry rate; time to first and final render; reader interaction
latency during OCR. Do not enable detector/OCR concurrency, inpaint overlap during
preflight, adaptive growth, or higher structural limits without these results.

## 15. Open decisions and evidence classification

### VERIFIED from current source

- OCR/masks are durable before inpaint; resumed inpaint can re-decode.
- Native analyze/inpaint are serialized through shared ownership guards.
- Progressive AI Batch couples OCR, PROBE admission, translation and inpaint.
- Strict response validation and bounded whole/missing retries already exist.
- Partial/malformed output does not advance rolling context.
- Only a vocabulary-only incremental chapter glossary exists; it mutates during
  translation and uses a numeric version repair gate.
- Provider governor is shared; current default is 60 RPM, not 15.
- Progress lacks analysis/profile phases.
- Forced OCR reuse and sparse stream-to-download migration have the documented gaps.

### Architectural recommendations

- Adopt this design as the target AI Batch architecture; retire the progressive
  PROBE mechanism only in that path.
- Use serial one-page OCR preflight, add an origin-neutral OCR checkpoint, release
  bitmap and lease per page, add reader-priority native admission between pages,
  and defer inpaint until translation phase.
- Persist analysis/profile/run sidecars and freeze by content fingerprint.
- Use static multi-dimensional envelope limits with deterministic structural
  split/backoff before considering adaptation.
- Keep the shared provider quota bucket with interactive reader priority and add
  one 15-RPM Batch allowance shared by analysis and translation.
- Never auto-promote model-derived facts into established series canon initially.

### Assumptions/inferences

- Chapter analysis will improve consistency/gender/semantic quality enough to pay
  for its calls. This is plausible but unmeasured.
- Approximate starting limits of 32 blocks/8 pages and 50% analysis input budget
  are experiments, not proven defaults.
- One-page/10% analysis overlap is enough to protect boundary scenes.
- Persisted OCR corpus size is acceptable for 200 pages; source suggests lightweight
  JSON but no stress measurement establishes the disk/parse cost.

### Decisions still required

1. Exact profile/analysis schema and whether the master reconciliation model must
   match the translation model/provider.
2. Provider/model-specific shared quota table and TPM limits above the accepted
   15-RPM aggregate Batch sub-limit.
3. Initial structural/page/block/output budgets after instrumentation.
4. Whether small chapters skip provider analysis and at what measured threshold.
5. User-facing management for user and series canon, and promotion workflow.
6. Whether complete pages from a structurally mixed response may commit immediately
   or remain candidates until every child split succeeds.
7. Whether profile changes intentionally retranslate compatible prior machine
   translations automatically, or only affect unfinished/explicitly refreshed pages.
8. Whether non-AI Batch receives full OCR preflight without chapter analysis, or
   retains the existing coordinator unchanged.
9. Exact OCR checkpoint transaction: close/rebase the BATCH candidate while
   retaining reusable stage records and protecting committed display.

### Runtime/device questions

- Real 200-page OCR wall time, thermal behavior and peak total/native memory.
- Exact native reader-priority/starvation thresholds that preserve interactive
  latency while allowing Batch progress.
- Provider-specific structured reliability versus blocks/pages/output size.
- Analysis quality for conflicting CJK identity/gender evidence and mixed scenes.
- Actual correction/retry savings versus analysis token/request premium.

## Recommendation

This architecture should replace the previous chunk-oriented design for contextual
AI Batch, subject to a staged delivery. First establish durable phase/profile
schemas, phase-scoped lease/reuse rules, and a pure global planner with strict tests.
Then add serial OCR preflight and profile analysis behind a feature gate. Only after
device/provider measurements should it become the default. Keep Manual/Auto and
legacy Batch paths unchanged throughout the evaluation.
