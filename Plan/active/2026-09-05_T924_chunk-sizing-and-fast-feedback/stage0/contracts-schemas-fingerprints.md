# T924 Stage 0 — versioned artifact schemas, canonical serialization, semantic fingerprints, invalidation matrix

Date: 2026-09-05 · Baseline verified at HEAD `adbe643` · Status: Stage-0 contract specification. Production coding NOT authorized.
Owner: Technical Lead (work item B). This is the single contract for T924 artifact schemas, canonical serialization, semantic fingerprints and the invalidation matrix.

## 0. Scope boundary and namespaces

This document specifies the PageOcrCheckpoint **DTO** and sidecar/manifest **publication mechanics** only. The `checkpointOcr` **transaction semantics** (CAS ordering, close-vs-rebase, lease release order, candidate lifecycle) belong to the state/transactions contract (`contracts-state-transactions.md`, T924-ST-*/T924-TX-*). Requirements catalog: T924-R*/T924-INV-* (`requirements-catalog.md`). Provider/analysis request contract: T924-AP-* (`contracts-provider-analysis.md`). Cross-references below use those namespaces without restating their content.

Labels: claims are marked VERIFIED (checked in source at `adbe643`), RECOMMENDATION, or PROPOSAL.

### 0.1 Verified mechanism inventory (the load-bearing base this contract builds on)

| Mechanism | Evidence |
|---|---|
| Durable artifacts are kotlinx-serialization JSON via a single shared `Json { ignoreUnknownKeys = true; encodeDefaults = true }` inside `AtomicChapterDocuments`; UTF-8; compact `encodeToString` | `artifact/ChapterDocumentIo.kt:215-251` (VERIFIED) |
| Crash-safe publication = write `name.tmp` → re-read + validate bytes → rotate primary to `name.bak` → rename tmp over primary; failed promotion restores backup; `readValidated` recovers from `.bak` and quarantines a corrupt primary as `name.corrupt` (collision-safe names, bounded suffixes) | `ChapterDocumentIo.kt:200-347` (VERIFIED) |
| Store transaction order: immutable sidecar published FIRST, manifest pointer updated SECOND; a crash between leaves an orphan sidecar, never a dangling pointer; orphans are reclaimed by retention using store reachability, never filename age | `artifact/ChapterArtifactStore.kt:263-283, 807-814, 938-951` (VERIFIED) |
| Manifest pointer pattern to generalize: `publishGlossary` publishes versioned sidecar `chapter.glossary.<version>.json`, then installs `GlossaryPointer(fileName, version, versionFingerprint)` in one manifest publication | `ChapterArtifactStore.kt:197-214`; `ChapterArtifactManifest.kt:276-283` (VERIFIED) |
| Future-schema protection: manifest with `schemaVersion > 2` (primary or backup) is refused and preserved read-only; publication is skipped while a future document exists; additive nullable fields are tolerated in both directions by `ignoreUnknownKeys` (D5 precedent) | `ChapterArtifactStore.kt:85-101, 991-1013`; `ChapterArtifactManifest.kt:26-34` (VERIFIED) |
| Fingerprint style: length-prefixed fields `len:value|`, indexed list elements, `<null>` literal, SHA-256 lowercase hex over UTF-8; per-stage builders (detection/ocr/inpaint/layout/glossary/failure/committedBundle/pageSnapshot); "timestamps and queue state are never inputs" | `artifact/StageFingerprints.kt:7-15, 163-192` (VERIFIED) |
| Block identity fingerprint: `TranslationBlock.stableFingerprint()` — same length-prefix style, floats via `toRawBits()`, includes `userEditedAt` and translation text (so it is NOT a pure OCR-content key) | `model/PageTranslationOwnership.kt:26-63` (VERIFIED) |
| Legacy migration pattern: deterministic mapping, provable-only fields, nothing inferred, legacy bytes never mutated, page versions preserved | `artifact/LegacyArtifactMigration.kt:69-103` (VERIFIED) |
| CAS precondition vocabulary already in production: generation, pageVersion, artifact pageVersion, candidateGenerationId, dependencyFingerprint, block fingerprints, lease token; candidate writes reject a different origin | `ChapterTranslationStore.kt:548-627, 1095-1128`; `ChapterArtifactStore.kt:346-399, 817-839` (VERIFIED) |
| Persisted OCR artifact: `PageTranslation` blocks (text/geometry/label/score/panel/bubble/RLE mask), page dims, `decodeSampleSize`, `inpaintMaskBoxes`, stage fingerprints, `inpaintingModeUsed`, `CURRENT_INPAINT_REVISION = 10` revision gate; `allTextDetections`/bitmaps transient | `model/PageTranslation.kt:8-96, 173-186` (VERIFIED) |
| Layout: `BlockLayout` embeds a mutable `TranslationBlock`, mask geometry, page-local `planGeometryId`/`maskComponentId` (valid only within one page plan), occupancy/debug structures — NOT directly serializable; `PositionedLine`/`HardClip`/`TextAlign`/`FloatRect` are the stable pieces; planner takes `(blocks, pageWidth, pageHeight, sampleSize, renderSourceText, TextMeasurer)` and derives scale from page dims × sample size | `rendering/TextLayoutPlanner.kt:28-64, 85-170, 579-631` (VERIFIED) |
| Stroke width is produced by the planner (`computeStrokeWidth = max(MIN_STROKE_PX·scale, fontSizePx·0.12)`) and inflates fit/occupancy/collision bounds — layout-affecting, not paint-only | `TextLayoutPlanner.kt:555-569, 3733-3738, 1257, 2149` (VERIFIED) |
| Draw path: overlay uses bundled font `R.font.animeace` forced to `Typeface.BOLD`, paints `ANTI_ALIAS|SUBPIXEL_TEXT`; stroke color derived from textColor luma (<128 → white, else black); stroke width `max(2f, layout.strokeWidth)`; coordinates stay in source-image space, SSIV owns pan/zoom/orientation | `ui/reader/viewer/TranslationOverlayView.kt:29-69, 196-211, 241-311` (VERIFIED) |
| Current "render" = `RenderColorEstimator.recomputeFor(bitmap, page.blocks)` (colors) + `renderStatus=READY` only — no geometry | `pipeline/batch/BatchRenderJoin.kt:196-235` (VERIFIED) |
| Storage layout: content-addressed sidecar names `artifacts/<page>/<stage>/f-<sha256(fp)>.json`; injective page segments; `g-<sha256(genId)>` generation segments; `isSafeSegment`; `managedDirectories` bounds retention | `artifact/ChapterArtifactLayout.kt:40-158` (VERIFIED) |
| Existing test idioms: `AtomicChapterDocumentsTest` (crash-window + rollback cases on `FakeChapterDocumentIo`), `StageFingerprintsTest` (determinism, delimiter-collision resistance, order-independence), `ChapterArtifactStoreTest` (transaction rejection/crash cases) | `app/src/test/java/eu/kanade/translation/artifact/*Test.kt` (VERIFIED) |

---

## 1. Versioned DTO specifications

Rules common to every DTO in this section:

- **T924-SC-01** — Every new durable document is a `@Serializable` Kotlin data class with `schemaVersion: Int` (default = its `SCHEMA_VERSION` constant) and `kind: String` discriminator constant, following `ChapterGlossary` / `ChapterAttemptLedgerDocument` (`ChapterArtifactManifest.kt:289-347`). Test: a document missing `schemaVersion` or with a wrong `kind` fails semantic validation and is treated as absent-for-planning.
- **T924-SC-02** — All caps declared below are schema-level bounds (rejection thresholds), distinct from the measured planner/policy constants owned by T924-AP-*/planner contracts. Initial values marked (T) are tunable experiments, never product constants (final-target-migration §7). Test: a document exceeding a bound fails validation, is preserved, and is never partially consumed.

### 1.1 ChapterRunRecord

One durable record per Batch run attempt over a chapter. Published at RUN_SNAPSHOT and updated only at persisted phase transitions (transition table = T924-ST-*).

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"CHAPTER_RUN_RECORD"` | |
| runId | String | yes | nonblank; `run-<epochMs>-<hash8>` | Transaction identity. NEVER a fingerprint input (T924-FP-01). |
| state | enum ChapterRunState | yes | one of: `RUN_SNAPSHOT, SOURCE_VALIDATION, OCR_PLAN, OCR_PREFLIGHT, ANALYSIS_PLAN, ANALYSIS_CHUNKS, PROFILE_RECONCILE, PROFILE_FROZEN, ENVELOPE_PLAN, TRANSLATE, NATIVE_RENDER, FINALIZE, PAUSED, ABORTED, COMPLETE` | Names mirror design §3.1; transition semantics owned by T924-ST-*. |
| frozenConfig | RunConfigSnapshot | yes | bounded (≤ 64 KB serialized) (T) | Complete frozen settings: sourceLang, targetLang, OCR engine/model ids, detector/segmenter ids + thresholds, inpaint mode, provider/model/credential ids, protocol versions, analysis policy, envelope policy, reading-order version. |
| frozenRunConfigFingerprint | String | yes | 64 lowercase hex | Length-prefixed hash over `frozenConfig` (T924-SC-08 style). |
| orderedSourceDigest | String | yes | 64 hex | SHA-256 over ordered (pageKey, sourceSha256) pairs, length-prefixed. |
| ocrCorpusFingerprint | String? | after OCR_PLAN | 64 hex; null before corpus exists | T924-FP-03. |
| analysisPolicyFingerprint | String | yes | 64 hex | |
| envelopePolicyFingerprint | String | yes | 64 hex | Excluded from translation validity (T924-FP-06, matrix row 7). |
| profilePointer | ProfilePointer? | at PROFILE_FROZEN | | §1.8. |
| envelopePlanPointer | SidecarPointer? | at ENVELOPE_PLAN | | §1.8. |
| phaseCounters | Map<String, Int> | no | keys ≤ 32; values ≥ 0 | e.g. `ocrPagesDone`, `ocrPagesTotal`, `analysisChunksDone`. Operational only; excluded from all fingerprints. |
| createdAtEpochMs / updatedAtEpochMs | Long | yes | > 0 | Operational only; never fingerprinted. |

```json
{"schemaVersion":1,"kind":"CHAPTER_RUN_RECORD","runId":"run-1757050000000-a1b2c3d4",
 "state":"PROFILE_FROZEN",
 "frozenConfig":{"sourceLang":"ja","targetLang":"en","ocrEngine":"onnx-v3","ocrModelHash":"…",
   "detectorModelHash":"…","inpaintMode":"QUALITY","providerKey":"gemini:gemini-2.5",
   "protocolVersion":2,"analysisPolicy":{"overlapPages":1},"envelopePolicy":{"maxBlocks":32,"maxPages":8}},
 "frozenRunConfigFingerprint":"<64hex>","orderedSourceDigest":"<64hex>",
 "ocrCorpusFingerprint":"<64hex>","analysisPolicyFingerprint":"<64hex>","envelopePolicyFingerprint":"<64hex>",
 "profilePointer":{"fileName":"X_artifacts/profiles/f-<sha256>.json","schemaVersion":1,
   "contentFingerprint":"<64hex>","version":1,"profileInputFingerprint":"<64hex>"},
 "phaseCounters":{"ocrPagesDone":200,"ocrPagesTotal":200,"analysisChunksDone":10},
 "createdAtEpochMs":1757050000000,"updatedAtEpochMs":1757050900000}
```

### 1.2 PageOcrCheckpoint (DTO only; transaction semantics = T924-TX-*)

Origin-neutral durable evidence that one page's OCR stage completed, publishable by any lane, replacing BATCH-candidate-owned OCR state.

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"PAGE_OCR_CHECKPOINT"` | |
| pageKey | String | yes | nonblank | |
| naturalPageIndex | Int? | yes | ≥ 0 or null | Null only when unprovable from the key set (legacy rule, `PageArtifactRecord.naturalPageIndex` precedent). Required for corpus ordering; a checkpoint without index and without provable order is rejected. |
| sourceIdentity | SourceIdentity | yes | `isComplete == true` | sha256 + width + height + orientation (`ArtifactContracts.kt:126-135`). |
| detectionFingerprint | String? | yes (null only when detection was skipped legitimately) | 64 hex | |
| ocrFingerprint | String | yes | 64 hex | Existing `StageFingerprints.ocr(...)` value. |
| ocrContentFingerprint | String | yes | 64 hex | The semantic `PageOcrContentFingerprint`, T924-FP-02. |
| ocrPageSnapshotPointer | SidecarPointer | yes | | Immutable OCR-complete `PageTranslation` snapshot sidecar (blocks + geometry + `inpaintMaskBoxes`; no translation state changes). |
| inpaintMaskRevision | Int | yes | ≥ `CURRENT_INPAINT_REVISION` | Revision gate precedent (`PageTranslation.kt:183`); below current ⇒ checkpoint stale for inpaint reuse. |
| priorCommittedDisplay | CommittedDisplayRef? | no | | `{generationId, bundleFingerprint?, pageSnapshotFileName?}` — the committed display that must stay visible through the checkpoint (preserve rule; semantics T924-TX-*). |
| producedByOrigin | enum ArtifactOrigin | yes | `BATCH` or `READER_ADHOC`; never `UNKNOWN`/`LEGACY` | Origin-neutral consumption, origin-recorded provenance. |
| producerGenerationId | String? | no | | Transaction identity; never fingerprinted. |
| checkpointedAtEpochMs | Long | yes | > 0 | Operational only. |

```json
{"schemaVersion":1,"kind":"PAGE_OCR_CHECKPOINT","pageKey":"0001.jpg","naturalPageIndex":0,
 "sourceIdentity":{"pageKey":"0001.jpg","sha256":"<64hex>","width":1200,"height":1800,"orientation":"PORTRAIT"},
 "detectionFingerprint":"<64hex>","ocrFingerprint":"<64hex>","ocrContentFingerprint":"<64hex>",
 "ocrPageSnapshotPointer":{"fileName":"X_artifacts/artifacts/0001…/ocr/f-<sha256>.json","schemaVersion":1,"contentFingerprint":"<64hex>"},
 "inpaintMaskRevision":10,
 "priorCommittedDisplay":{"generationId":"g-1757040000000-0001jpg…","bundleFingerprint":"<64hex>"},
 "producedByOrigin":"BATCH","producerGenerationId":"g-1757050000000-…","checkpointedAtEpochMs":1757050100000}
```

### 1.3 AnalysisChunkResult

One validated structured-extraction response over a bounded page set (design §6). Response schema/prompt details = T924-AP-*; this is the persisted artifact.

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"ANALYSIS_CHUNK_RESULT"` | |
| chunkId | String | yes | `chunk-<ordinal>-<corpus8>`; deterministic from plan | Deterministic ⇒ re-planning produces the same id (testable). |
| (convention, wave-2 review F4) | — | — | Contributing-set corpus fingerprints hash the contributing set in CORE-then-CONTEXT payload order with `naturalOrderProven=true` (matches the T924-AP-03 request payload order; pinned by the AnalysisChunkPlanner golden oracle). WP5 request builders MUST reproduce this order exactly. |
| chunkOrdinal | Int | yes | ≥ 0, unique per run | |
| analysisSchemaVersion | Int | yes | | Structured-response schema used (T924-AP-* owns values). |
| corePageKeys | List\<String\> | yes | 1..16 pages (T) | Ordered, natural order. |
| contextOverlapPageKeys | List\<String\> | no | ≤ 2 adjacent pages (T) | `core ∪ overlap` = contributing set. |
| contributingCorpusFingerprint | String | yes | 64 hex | `OcrCorpusFingerprint` over the contributing page set in order (T924-FP-03). |
| ocrArtifactRefs | List\<SidecarPointer\> | yes | one per contributing page | Points at the per-page OCR snapshot sidecars. |
| terms / entities / relationships | List\<ExtractedTerm / ExtractedEntity / ExtractedRelationship\> | no | ≤ 128 entries each per chunk (T) | Shapes per T924-AP-*; stored fields must be a subset of the validated response schema. |
| scenes | List\<ProfileScene\> | no | ≤ 32 per chunk (T) | §1.4 shape. |
| narrativeSummary | String? | no | ≤ 2000 chars (T) | Range-scoped supporting context; never substitutes for structured records (design §6.3). |
| conflictNotes | List\<String\> | no | ≤ 32 × 500 chars (T) | |
| evidenceRefs | List\<EvidenceRef\> | yes | ≤ 512 (T) | Every ref must resolve to a core or overlap page and an existing stableBlockId. |
| analyzerProvenance | AnalyzerProvenance | yes | | `{providerId, modelId, promptVersion, analysisSchemaVersion, credentialFingerprint}`. |
| status | enum | yes | `VALID` / `INVALID` | Persisted validation outcome; `INVALID` carries `validationFailureReason` and is never consumed upstream. |
| validationFailureReason | String? | iff INVALID | ≤ 500 chars | |
| createdAtEpochMs | Long | yes | | Operational only. |

### 1.4 ChapterTranslationProfile (incl. structured facts)

Frozen canonical artifact. Immutable after publication; monotonic `version`; corrections never mutate it (design §4.4).

**ChapterTranslationProfile**

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"CHAPTER_TRANSLATION_PROFILE"` | |
| version | Int | yes | ≥ 1, monotonic per chapter | Operational ordering ONLY; never the sole validity key (T924-FP-05). |
| contentFingerprint | String | yes | 64 hex | `ProfileContentFingerprint`, T924-FP-05. |
| profileInputFingerprint | String | yes | 64 hex | T924-FP-04. |
| sourceRunId | String | yes | | Operational provenance. |
| analyzerProvenance | AnalyzerProvenance | yes | | |
| entities | List\<ProfileEntity\> | no | ≤ 512 (T) | |
| terms | List\<ProfileTerm\> | no | ≤ 512 (T) | |
| scenes | List\<ProfileScene\> | no | ≤ 256 (T) | |
| unresolvedFacts | List\<ProfileFact\> | no | ≤ 128 (T) | Ambiguity retained, never averaged away (design §6.3). |
| seriesUpdateCandidates | List\<ProfileFact\> | no | ≤ 128 (T) | Never auto-promoted (design §6.4). |
| correctionCandidates | List\<ProfileFact\> | no | ≤ 128 (T) | Separate candidates for a future run; never mutate the frozen content. |
| frozenAtEpochMs | Long | yes | | Operational only. |

**ProfileFact** (the fact representation; entities/terms/gender facts are typed instances)

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| factId | String | yes | unique within profile; `f-<ordinal>` or stable hash | Referential key for scenes/participants. |
| type | enum FactType | yes | `ENTITY_IDENTITY, TERM, GENDER, PRONOUN, RELATIONSHIP, TONE, NARRATIVE_STATE` | |
| canonicalSourceForm | String | for ENTITY_IDENTITY/TERM | NFC-normalized; ≤ 128 chars | |
| canonicalTargetForm | String | for ENTITY_IDENTITY/TERM | NFC-normalized; ≤ 128 chars | |
| aliases | List\<String\> | no | ≤ 32 × 128 chars | Titles/honorific variants; NFC-normalized. |
| confidence | Float? | no | 0.0..1.0 | Model confidence; never a validity key by itself. |
| evidenceStrength | enum | yes | `EXPLICIT, STRONG_CONTEXTUAL, WEAK` | Gender may be promoted only from EXPLICIT or corroborated STRONG_CONTEXTUAL (design §4.3). |
| evidenceRefs | List\<EvidenceRef\> | yes unless strength=WEAK note | ≤ 32; each `{pageKey, stableBlockId, sourceExcerptHash}` | Referential integrity validated at freeze (T924-R-* invariant). |
| scope | enum | yes | `CANONICAL_CHAPTER_WIDE, RANGE_SCOPED, AVAILABLE_FROM` | §4.3 semantics. |
| availableFrom | PageBlockRef? | iff scope=AVAILABLE_FROM | `{naturalPageIndex, stableBlockId?}` | Usable only from its evidence point onward. |
| applicableRange | PageRange? | iff scope=RANGE_SCOPED | `{firstNaturalPageIndex, lastNaturalPageIndex}` | |
| gender | enum | GENDER facts only | `MALE, FEMALE, UNKNOWN, CONFLICTING` | Pronouns are separate nullable PRONOUN facts (design §4.3). |
| provenance | enum | yes | `USER, SERIES_CANON, CHAPTER_ANALYSIS, ROLLING_CONTEXT, LOCAL_INFERENCE` | Authority hierarchy design §6.4. |
| conflictState | enum | yes | `RESOLVED, UNRESOLVED, CONFLICTING, REJECTED` | Weak rejected cues persist as REJECTED notes, not averaged guesses. |
| note | String? | no | ≤ 500 chars | Weak name/speech-style cues remain notes. |

**ProfileScene**

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| sceneId | String | yes | `s-<ordinal>` | |
| pageRange | PageRange | yes | bounded, ≤ chapter size | |
| blockRanges | List\<BlockRange\> | no | ≤ 64 | `{naturalPageIndex, firstBlockOrdinal, lastBlockOrdinal}`. |
| participants | List\<String\> | no | ≤ 16 factIds | Must resolve to entity facts. |
| toneFlags | Set\<enum\> | yes | subset of `EXPLICIT, INTIMATE, VIOLENT, COMEDIC, SERIOUS, ACTION, OTHER` | |
| register | enum | yes | `CASUAL, FORMAL, ARCHAIC, ROUGH, POLITE, OTHER` | |
| narrativeContext | String? | no | ≤ 1000 chars | Range-scoped only. |

**EvidenceRef**

| Field | Type | Required | Notes |
|---|---|---|---|
| pageKey | String | yes | Must exist in the chapter page set. |
| stableBlockId | String | yes | OCR canonical block id (e.g. `p3_b12`); must exist in that page's OCR snapshot. |
| sourceExcerptHash | String | yes | SHA-256 of the NFC-normalized source excerpt (T924-SC-09); ≤ 64 hex. |

```json
{"schemaVersion":1,"kind":"CHAPTER_TRANSLATION_PROFILE","version":1,
 "contentFingerprint":"<64hex>","profileInputFingerprint":"<64hex>","sourceRunId":"run-…",
 "analyzerProvenance":{"providerId":"gemini","modelId":"gemini-2.5","promptVersion":3,"analysisSchemaVersion":1},
 "entities":[{"factId":"f-1","type":"ENTITY_IDENTITY","canonicalSourceForm":"レイナ",
   "canonicalTargetForm":"Reina Alstella","aliases":["レイナ・アルステラ","Raina"],
   "confidence":0.93,"evidenceStrength":"EXPLICIT","scope":"CANONICAL_CHAPTER_WIDE",
   "evidenceRefs":[{"pageKey":"0180.jpg","stableBlockId":"p180_b4","sourceExcerptHash":"<64hex>"}],
   "provenance":"CHAPTER_ANALYSIS","conflictState":"RESOLVED","note":"weak male cue on p12 rejected"},
  {"factId":"f-2","type":"GENDER","gender":"FEMALE","evidenceStrength":"EXPLICIT",
   "scope":"CANONICAL_CHAPTER_WIDE","evidenceRefs":[{"pageKey":"0180.jpg","stableBlockId":"p180_b4","sourceExcerptHash":"<64hex>"}],
   "provenance":"CHAPTER_ANALYSIS","conflictState":"RESOLVED"}],
 "scenes":[{"sceneId":"s-3","pageRange":{"firstNaturalPageIndex":40,"lastNaturalPageIndex":44},
   "participants":["f-1"],"toneFlags":["EXPLICIT","SERIOUS"],"register":"ROUGH",
   "narrativeContext":"confrontation in the archive"}],
 "unresolvedFacts":[],"seriesUpdateCandidates":[],"correctionCandidates":[],"frozenAtEpochMs":1757050800000}
```

### 1.5 EnvelopePlan

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"ENVELOPE_PLAN"` | |
| planFingerprint | String | yes | 64 hex | Hash over ordered plan content (T924-SC-08). |
| planInputFingerprint | String | yes | 64 hex | Corpus slice + pending-block set + profile subset estimate + envelope policy fingerprint. |
| plannerVersion | Int | yes | ≥ 1 | Pure-planner algorithm version. |
| envelopes | List\<PlannedEnvelope\> | yes | ≤ 4096 (T) | Every pending block appears exactly once chapter-wide; a page's blocks are never split across envelopes (page atomicity invariant). |
| createdAtEpochMs | Long | yes | | Operational only. |

**PlannedEnvelope**: `envelopeId` (deterministic `e-<ordinal>`), `orderedPageKeys: List<String>` (natural order), `blockIds: List<String>` (reading order), `contributingCorpusFingerprint`, `estimatedInputTokens: Int`, `estimatedOutputTokens: Int`, `structuralBlockCount: Int`, `contributingPageCount: Int`, `sceneRefs: List<String>`, `profileSubsetRefs: List<String>` (bounded factIds), `crossesSceneBoundary: Boolean`.

### 1.6 Persisted LayoutDrawPlan (LAYOUT_PREPARE geometry sub-result)

Versioned, immutable draw-plan DTO per final-target-migration §1. It must NOT serialize `BlockLayout`, `TranslationBlock`, or mask objects (VERIFIED: `BlockLayout` embeds a mutable `TranslationBlock`, page-local `planGeometryId`/`maskComponentId`, occupancy/debug structures — `TextLayoutPlanner.kt:118-170`).

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"PAGE_LAYOUT_DRAW_PLAN"` | |
| layoutPlannerVersion | Int | yes | ≥ 1 | Planner algorithm version; fingerprint input (T924-FP-07). |
| fontIdentity | FontIdentity | yes | | `{assetName: "font/animeace.ttf", assetSha256, typefaceStyle: "BOLD", paintFlags: "ANTI_ALIAS|SUBPIXEL_TEXT"}` — mirrors the actual overlay paints (`TranslationOverlayView.kt:38-50`). |
| platformShapingKey | String | yes | | Platform text-shaping compatibility value established by device tests (final-target-migration §1.2; §7 measurement item). |
| pageWidth / pageHeight | Float | yes | > 0, finite | Source-image pixels; coordinates are ALWAYS source-image space. |
| decodeSampleSize | Int | yes | ≥ 1 | The planner consumes it (`scale = 1/sampleSize`, `TextLayoutPlanner.kt:631`). |
| strokePolicyVersion | Int | yes | ≥ 1 | Version of `computeStrokeWidth` constants (`STROKE_WIDTH_FRACTION`, `MIN_STROKE_PX`) — stroke width is layout-affecting (final-target §3, §8.3). |
| strokeColorPolicyVersion | Int | yes | ≥ 1 | Luma-threshold rule version (paint-derived contrast). |
| blocks | List\<DrawPlanBlock\> | yes | ≤ 256 per page (T) | |

**DrawPlanBlock**: `stableBlockId: String` (OCR canonical id), `inputIndex: Int` (planner input ordinal — `blockId` alone is nullable/duplicated, `InputIdentity` precedent `TextLayoutPlanner.kt:172-177`), `chosenText: String`, `isVertical: Boolean`, `originX/originY: Float`, `safeW/safeH: Float`, `fontSizePx: Float`, `strokeWidth: Float`, `drawAlign: enum CENTER/LEFT/RIGHT`, `clipRect: FloatRect?` (source-image space), `lines: List<String>?` (legacy stacked/vertical mode), `positionedLines: List<{text, leftPx: Int, topPx: Int, layoutWidthPx: Int, layoutHeightPx: Int}>?`, `cellRect: FloatRect?`, `maskComponentRef: {maskGeometryContentHash: String, componentId: Int}?` (durable reference into the page's OCR mask geometry; replaces page-local `planGeometryId`), `maskUsable: Boolean`.

Excluded by design: `TranslationBlock` objects, `textColor`/`strokeColor` (paint-only — they live in ColorStylePreparation §1.7), bitmap/mask pixels, planner occupancy/debug structures, component `Path` objects (rebuilt at hydration, final-target §1/§3).

```json
{"schemaVersion":1,"kind":"PAGE_LAYOUT_DRAW_PLAN","layoutPlannerVersion":2,
 "fontIdentity":{"assetName":"font/animeace.ttf","assetSha256":"<64hex>","typefaceStyle":"BOLD","paintFlags":"ANTI_ALIAS|SUBPIXEL_TEXT"},
 "platformShapingKey":"sdk35-shaping-bucketA","pageWidth":1200.0,"pageHeight":1800.0,"decodeSampleSize":1,
 "strokePolicyVersion":1,"strokeColorPolicyVersion":1,
 "blocks":[{"stableBlockId":"p1_b2","inputIndex":0,"chosenText":"The ritual begins tonight.",
   "isVertical":false,"originX":512.0,"originY":300.5,"safeW":300.0,"safeH":96.0,
   "fontSizePx":34.0,"strokeWidth":4.1,"drawAlign":"CENTER","clipRect":null,
   "positionedLines":[{"text":"The ritual begins tonight.","leftPx":363,"topPx":284,"layoutWidthPx":298,"layoutHeightPx":40}],
   "cellRect":{"left":340.0,"top":250.0,"right":690.0,"bottom":360.0},
   "maskComponentRef":{"maskGeometryContentHash":"<64hex>","componentId":3},"maskUsable":true}]}
```

### 1.7 ColorStylePreparation sub-result

Separately invalidatable color/style sub-result (final-target §1.3, §3). Split from geometry: color changes must not reflow (matrix row 10).

| Field | Type | Required | Constraints / caps | Notes |
|---|---|---|---|---|
| schemaVersion | Int | yes | `== 1` | |
| kind | String | yes | `"COLOR_STYLE_PREPARATION"` | |
| colorEstimatorVersion | Int | yes | ≥ 1 | `RenderColorEstimator` algorithm version. |
| cleanedImageRef | `{fileName, inpaintRevision: Int}` | yes (or explicit ORIGINAL_SOURCE kind) | | Identity of the pixels consumed (cleaned or original source). |
| pageWidth / pageHeight | Float | yes | > 0 | |
| blocks | List\<ColorStyleEntry\> | yes | ≤ 256 | Each: `{stableBlockId, inputIndex, textColor: Long (ARGB), derivedStrokeColor: Long}`. Stroke color = luma(textColor) < 128 → white else black (overlay rule, `TranslationOverlayView.kt:243-248`). |

### 1.8 Manifest pointer extensions

- **T924-SC-03** — Generalize the `GlossaryPointer` pattern into `SidecarPointer { fileName: String, schemaVersion: Int, contentFingerprint: String(64 hex) }`; `ProfilePointer : SidecarPointer` adds `version: Int` and `profileInputFingerprint: String`. PROPOSAL (shape), RECOMMENDATION (pattern reuse — VERIFIED precedent `ChapterArtifactStore.publishGlossary`).
- **T924-SC-04** — Extend `ChapterArtifactManifest` with additive nullable/map fields: `activeRun: SidecarPointer? = null`, `ocrCheckpoints: Map<String, SidecarPointer> = emptyMap()` (key = pageKey), `analysisChunks: List<SidecarPointer> = emptyList()` (chunk-ordinal order), `profile: ProfilePointer? = null`, `envelopePlan: SidecarPointer? = null`, `layoutPlans: Map<String, SidecarPointer> = emptyMap()`, `colorPreparations: Map<String, SidecarPointer> = emptyMap()` — and bump `ChapterArtifactManifest.SCHEMA_VERSION` 2 → 3. Rationale: same-version older writers are read-modify-write data-class copies that DROP unknown fields (kotlinx ignores-then-omits), so additive-within-v2 would let a rolled-back build silently strip every new pointer; the v3 bump instead triggers the VERIFIED future-schema guard (`ChapterArtifactStore.kt:85-101, 995-1013`): the old build preserves the chapter read-only instead of corrupting it. New code reads v2 and v3 and writes v3. Consequence for rollback is a Director-level tradeoff (§7.2).
- **T924-SC-05** — Manifest pointer updates remain single-publication atomic: a manifest never references a sidecar whose bytes are not already durable (verified store rule, `ChapterArtifactStore.kt:263-283`). Test: crash injection between sidecar publish and manifest publish leaves the prior manifest authoritative and at most an orphan sidecar.

---

## 2. Canonical serialization rules (before hashing)

- **T924-SC-06** — All new durable documents serialize through the shared `AtomicChapterDocuments` Json instance (`ignoreUnknownKeys = true; encodeDefaults = true`), UTF-8, compact output. Creating bespoke `Json { … }` instances for these documents is forbidden. Field order in bytes = DTO declaration order (kotlinx guarantee); new optional fields are appended at the end of the declaration so older readers' byte expectations stay stable. Test: round-trip `encode → decode → encode` is byte-identical.
- **T924-SC-07** — Maps inside documents that feed a content fingerprint (`ProfileContentFingerprint`, `planFingerprint`) are either avoided or hashed via sorted-key traversal exactly like `StageFingerprints.glossaryVersion` (`StageFingerprints.kt:112-119`). Test: permuting map insertion order does not change any content fingerprint.
- **T924-SC-08** — Composite input fingerprints (fingerprints over mixed non-DTO fields: configs, ids, sub-fingerprints) use the existing `StageFingerprints` encoding: length-prefixed fields `len:value|`, indexed list elements, `<null>` literal, enums by name, floats via `toRawBits()`, SHA-256 lowercase hex over UTF-8. Test: the delimiter-collision resistance cases in `StageFingerprintsTest` extend to every new builder.
- **T924-SC-09** — Text normalization before any hashing or canonical-form storage: Unicode NFC, CRLF/CR → LF. No case folding, no whitespace collapsing (case and spacing are semantic in CJK/source text). `sourceExcerptHash` = SHA-256 of the NFC/LF-normalized excerpt. Test: NFC-equivalent inputs (e.g. decomposed accents, CRLF) produce identical fingerprints; case-differing inputs do not.
- **T924-SC-10** — Semantic content fingerprints (`ProfileContentFingerprint`, `planFingerprint`) hash the re-encoded canonical JSON of the parsed DTO (decode then re-encode under T924-SC-06, then SHA-256). This makes semantic-equivalent artifacts hash identically regardless of input key order or default-field presence — the Stage-1 exit requirement. Operational fields listed in T924-FP-01 are zeroed/omitted by the hashing function before encoding (documented per-DTO exclusion set), NOT by mutating stored bytes.
- **T924-SC-11** — Timestamps, runIds, generationIds, candidate ids, page versions, attempt counters and file names are never inputs to any fingerprint defined here (transaction identities, design §4.4). Test: mutating only those fields leaves all semantic fingerprints unchanged.

## 3. Versioning and compatibility

- **T924-SC-12** — Version comparison is integer `schemaVersion` vs the reader's constant. A document with `schemaVersion >` the reader's is **unknown-version** and follows the per-type behavior table below. A document with `schemaVersion ≤` the reader's is readable; readers must accept every historical version they shipped.
- **T924-SC-13** — Unknown-version behavior per artifact type (testable):

| Artifact | Unknown newer version behavior | Rationale / precedent |
|---|---|---|
| Chapter manifest | Refuse and preserve read-only: never renamed, deleted, quarantined, or overwritten; publication skipped while future primary/backup exists | VERIFIED rule, `ChapterArtifactStore.kt:85-101, 995-1013` |
| Run record, profile, envelope plan, analysis chunk, layout plan, color prep sidecars | Treated as unusable for planning (artifact = ABSENT for decisions); bytes preserved untouched; never deleted or overwritten by this version; status surfaced to diagnostics | Newer schema owns the semantics; we cannot reinterpret safely |
| OCR checkpoint sidecar | Same preserve rule; re-derivation is allowed only as a NEW sidecar (fresh OCR), never by overwriting the future bytes | OCR is re-derivable; newer bytes may still be consumed by a newer app on rollback-return |
| Attempt ledger / glossary | Existing rules unchanged (`ChapterArtifactStore.kt:216-236`) | VERIFIED |

- **T924-SC-14** — Additive-optional evolution (new optional field with default, new enum value handled as data, tightened bound within caps) does NOT bump `schemaVersion`; all readers must tolerate it via `ignoreUnknownKeys`. Semantic change (field removal, type change, meaning reinterpretation, enum value split) REQUIRES a version bump plus a migration function. Test: a v1 document with an extra unknown key loads; a v2 document follows T924-SC-13.
- **T924-SC-15** — Unknown ENUM names: kotlinx decode of an unrecognized enum name fails the document parse; the document then follows the corrupt path (T924-SC-17), never a silent partial read. Preferred design for evolvable enums: reserve explicit `UNKNOWN`-style variants where a downgrade-safe reading exists.
- **T924-SC-16** — Migrations follow the `LegacyArtifactMigration` pattern (VERIFIED): pure deterministic function old→new; provable-only fields (missing provenance stays explicitly null, never defaulted); old bytes preserved; page versions preserved; golden fixtures assert the mapping table. A migration failure leaves the old document authoritative.
- **T924-SC-17** — Corrupt artifacts (parse failure, bound violation, kind mismatch, failed referential validation): manifest → backup recovery + `.corrupt` quarantine (`AtomicChapterDocuments.readValidated`/`recoverPrimaryFromBackup`, VERIFIED); sidecars → treated as ABSENT for planning, bytes quarantined where the document layer can (`.corrupt`), reclaimed by retention only when no pointer references them; corrupt OCR checkpoint ⇒ its page re-enters OCR planning (safe re-derivation); corrupt profile ⇒ run pauses at PROFILE_FROZEN boundary, never translates without a valid frozen profile. Test idiom: `AtomicChapterDocumentsTest` crash/quarantine cases + `ChapterArtifactStoreTest` rejection cases.
- **T924-SC-18** — Downgrade visibility: user-visible display authority is schema-independent. Committed display references and user edits survive every version transition and every invalidation (matrix rows 11–13); no migration or invalidation may remove the last displayable generation (`previousCommitted` retention precedent, `ChapterArtifactManifest.kt:216-218`).

## 4. Crash-safe sidecar publication mechanism

- **T924-SC-19** — The ONLY publication mechanism for the new sidecars is the existing `AtomicChapterDocuments.publish` / `publishJson` (write `.tmp` → re-read + validate → rotate `.bak` → rename), and the ONLY manifest-update mechanism is `ChapterArtifactStore`'s synchronized `publishManifestInternal` path. No direct `ChapterDocumentIo.write` for durable documents. (VERIFIED mechanisms; RECOMMENDATION to reuse unchanged.)
- **T924-SC-20** — Publication order for any state that becomes visible via the manifest: (1) publish every immutable sidecar (content-addressed names, §T924-SC-21); (2) one atomic manifest publication installs all pointers. Sidecars referenced by no manifest pointer are orphans and are reclaimed exclusively by `ArtifactRetention` store-reachability sweeps — never by filename age, never while a live pointer references them (VERIFIED retention rule, `ChapterArtifactStore.kt:938-951`). Crash windows: before (1) completes → nothing visible; between (1) and (2) → orphan files only; during (2) → `.bak` rotation semantics of T924-SC-19. Test: fault injection at each window leaves the prior manifest authoritative (extends `AtomicChapterDocumentsTest`).
- **T924-SC-21** — Content-addressed sidecar naming via `ChapterArtifactLayout` conventions: new directories under `X_artifacts/` (`runs/`, `ocr/` (checkpoints), `analysis/`, `profiles/`, `envelopes/`, `layout/`, `color/`), file names `f-<sha256Hex(contentFingerprint)>.json` via `fingerprintSegment`; page-scoped plans nest under the injective `pageSegment(pageKey)`; all new directories are added to `managedDirectories` so retention bounds them. Content-addressed names are admission-safe: equal content maps to an equal name, so re-publication is idempotent and `renameNoReplace` can be used for first admission. `isSafeSegment` validation applies to any dynamic name segment (VERIFIED helpers, `ChapterArtifactLayout.kt:120-158`).
- **T924-SC-22** — Store transactions that install per-page state (OCR checkpoint pointer install = T924-TX-* semantics) must keep the VERIFIED rejection invariant: on any precondition failure or publication failure the prior manifest stays authoritative and the caller keeps ownership long enough to report failure (`persistLiveCandidate`/`promoteLiveCandidate` rejection style, `ChapterArtifactStore.kt:346-399`).

## 5. Semantic fingerprints

All fingerprints below are SHA-256 lowercase hex over the T924-SC-08 encoding unless stated otherwise. "Semantic" = unchanged by transaction identity changes.

- **T924-FP-01 — Global exclusion list.** Never fingerprint: candidate generation ids; page versions (store or artifact); sidecar file names; monotonic versions as sole validity keys (operational metadata only); timestamps/durations; attempt/retry counts; envelope ids (they are deterministic derivatives, listed only for readability); `userEditedAt`; display state; `activeCandidateGenerationIds`. (Design §4.4; VERIFIED that page versions and generation ids are transaction identities — `ChapterArtifactStore.kt:817-839`.) Test: per-field mutation of each excluded item leaves the semantic fingerprint equal (audit Stage-1 exit).
- **T924-FP-02 — `PageOcrContentFingerprint`.** Inputs, in fixed order: corpus schema version; pageKey; naturalPageIndex; source sha256 + width + height + orientation; detection fingerprint (or explicit `SKIPPED` marker); OCR engine/model/config fingerprint (`StageFingerprints.ocr` inputs: engine version, model hash, source language, preprocessing + text-normalization versions); ordered stable block ids; per-block NFC-normalized source text; per-block geometry (`toRawBits` of x/y/width/height/angle, label); textless state; mask content (ordered `inpaintMaskBoxes` with label) + `inpaintMaskRevision`. EXCLUDED per T924-FP-01: block `translation`, `userEditedAt`, colors, `score`-derived ordering beyond ids (score is a detection artifact already covered by the detection fingerprint), candidate/page versions, file names. Note: this deliberately differs from `TranslationBlock.stableFingerprint()` (which includes translation + `userEditedAt` + colors) — a pure OCR-content key must not change when a user edits target text. Test: user edit / translation change / candidate reopen ⇒ equal; block text, geometry, mask, or OCR config change ⇒ different.
- **T924-FP-03 — `OcrCorpusFingerprint`.** Hash over: corpus schema version; expected page count (+ trusted flag); the ordered sequence of per-page `PageOcrContentFingerprint` values in natural page order (length-prefixed, indexed). A chapter with unprovable page ordering fingerprints its sorted pageKey order and records `ordered=false` as an explicit field (reusing the never-guess rule for `naturalPageIndex`). Test: page insertion/removal/reorder changes the corpus fingerprint; re-running OCR with identical outputs does not.
- **T924-FP-04 — `ProfileInputFingerprint`.** Inputs: `OcrCorpusFingerprint`; source language; target language; analysis schema version; analysis prompt version; analyzer provider/model/credential signature; analyzer policy fingerprint; user-authority fingerprint (hash of the user canon content actually admitted, or the explicit literal `ABSENT` — absence is a value, never an empty-string collision); series-authority fingerprint (same rule). Test: any single input change ⇒ different; OCR re-run with identical corpus ⇒ same.
- **T924-FP-05 — `ProfileContentFingerprint`.** Hash of the canonical re-encoded JSON (T924-SC-10) of the validated profile with operational fields (`version`, `frozenAtEpochMs`, `sourceRunId`) excluded. Uniqueness requirement: two independently frozen profiles from identical inputs and deterministic reconciliation produce identical content fingerprints; a profile whose facts/scenes/aliases differ in any hashed field produces a different fingerprint. The monotonic `version` must never be the sole validity key (design §4.4). Test: golden freeze fixtures hash-stable across processes; version-only bump ⇒ same content fingerprint.
- **T924-FP-06 — Translation provenance fingerprint** (per page / per envelope). Inputs: `ProfileContentFingerprint`; translator signature (provider/model/credential + protocol version); prompt version; per contributing page: `PageOcrContentFingerprint` + the ordered stable block ids and per-block source-text hashes actually sent; source/target language. Recorded SEPARATELY (not merged): rolling-context fingerprint, profile-subset fingerprint, envelope-plan fingerprint (reproducibility metadata, design §4.4). Envelope-policy-only change must not invalidate an otherwise compatible translation ⇒ envelope policy and envelope-plan fingerprints are NOT part of translation provenance. Test: envelope policy change ⇒ provenance equal; profile/translator/source-block change ⇒ provenance different.
- **T924-FP-07 — Layout compatibility fingerprint.** Extends `StageFingerprints.layout` (translation artifact id, cleaned-image-or-original id, layout engine version, font identity, font scale preferences, style preferences, output dimensions — VERIFIED inputs) with ALL of: font asset identity (asset name + sha256 of `R.font.animeace` bytes), typeface/style (`BOLD`), paint measurement flags (`ANTI_ALIAS|SUBPIXEL_TEXT`), planner algorithm version, `platformShapingKey` (Android text-shaping compatibility value established by device tests — exact bucketing is a §7.5 decision), stroke policy version (stroke width is geometry-affecting), decode sample size, and source-image page dimensions. Test: any single component change ⇒ different fingerprint; SSIV zoom/pan/holder-size/orientation changes ⇒ never an input (presentation transforms, final-target §3).
- **T924-FP-08 — Color/style fingerprint.** Inputs: color estimator version; consumed image identity (cleaned file name + `inpaintRevision`, or ORIGINAL_SOURCE marker + source sha256); per-block geometry fingerprints consumed by estimation; page dimensions. NOT inputs: font, planner version, translated text (color depends on pixels + geometry only). Test: inpaint re-run with changed pixels ⇒ different; translator/font change ⇒ same.
- **T924-FP-09 — Determinism and equivalence tests (gate).** For every fingerprint: (a) repeated computation over equal semantic inputs is identical across processes; (b) T924-FP-01 exclusions do not affect it; (c) length-prefix collision fixtures (T924-SC-08) pass; (d) golden fixtures for a 200-page synthetic corpus produce stable corpus/profile/plan fingerprints (audit Stage-1/2 exits).

## 6. Invalidation matrix

Legend: **INVALIDATE** — artifact not reusable; must be redone. **KEEP** — unaffected. **RE-DERIVE** — recompute the expected fingerprint; keep the artifact only when it matches (cheap validation, not redo). "committed display" = last promoted displayable generation + user edits; per T924-SC-18 it survives every row until a NEW commit replaces it.

| Input change | OCR / checkpoint | Analysis chunks | Profile | Machine translations | Inpaint / cleaned image | Color / style | Persisted layout | Committed display |
|---|---|---|---|---|---|---|---|---|
| 1. Source image bytes (re-download, different file) | INVALIDATE (source hash in detection/OCR fp) | INVALIDATE (corpus fp) | INVALIDATE (input fp) | INVALIDATE (source identity in provenance) | INVALIDATE | RE-DERIVE (new pixels) | INVALIDATE | KEEP |
| 2. Detection/OCR config (detector/segmenter model, thresholds, OCR engine/model, preprocessing/normalization, mask postprocess, reading order) | INVALIDATE | RE-DERIVE (corpus fp re-check; invalidate on mismatch) | RE-DERIVE (input fp) | RE-DERIVE (provenance) | RE-DERIVE (mask fp; re-erase if mask changed) | RE-DERIVE | RE-DERIVE (geometry fp) | KEEP |
| 3. Source/target language change | RE-DERIVE (sourceLanguage is an OCR fp input — `StageFingerprints.ocr`) | INVALIDATE | INVALIDATE | INVALIDATE | KEEP (mask unchanged) | KEEP | INVALIDATE (text changed) | KEEP |
| 4. Analyzer policy/prompt/model/provider | KEEP | INVALIDATE | INVALIDATE | DECISION D-7.1 (default: completed KEEP, pending use new profile) | KEEP | KEEP | KEEP unless its page's translation is redone | KEEP |
| 5. User/series authority content change | KEEP | KEEP (chunks are raw evidence, not authority) | INVALIDATE (input fp includes authority) | DECISION D-7.1 (same policy as row 4) | KEEP | KEEP | KEEP unless translation redone | KEEP |
| 6. Translator model/prompt/protocol | KEEP | KEEP | KEEP | INVALIDATE for reuse as Batch evidence (provenance mismatch, T924-FP-06); display never revoked (D-7.4) | KEEP | KEEP | INVALIDATE for affected pages once re-translated | KEEP |
| 7. Envelope policy only (block/page caps, scene penalty, budgets) | KEEP | KEEP | KEEP | KEEP — MUST NOT invalidate compatible translations (final-target §2; design §4.4) | KEEP | KEEP | KEEP | KEEP |
| 8. Inpaint mode/algorithm | KEEP | KEEP | KEEP | KEEP | INVALIDATE | INVALIDATE (cleaned pixels changed) | KEEP — planner does not consume the cleaned bitmap (final-target §3, VERIFIED) | KEEP |
| 9. Font asset / typeface / measurement flags / planner version / platform shaping / stroke policy | KEEP | KEEP | KEEP | KEEP | KEEP | KEEP | INVALIDATE (T924-FP-07) | KEEP |
| 10. Color estimator version | KEEP | KEEP | KEEP | KEEP | KEEP | INVALIDATE (re-estimate) | KEEP — color-only change must not reflow line breaks (final-target §3) | KEEP |
| 11. User edit to a block (target text) | KEEP (OCR content excludes user edits, T924-FP-02) | KEEP | KEEP | That block: INVALIDATE (user text authoritative); sibling blocks KEEP | KEEP | KEEP | Page INVALIDATE (text changed → re-fit) | KEEP + user edit becomes display authority on commit |
| 12. Profile content change (re-freeze with different content) | KEEP | KEEP | New frozen version; old bytes immutable | DECISION D-7.1 (open Director decision 7) | KEEP | KEEP | INVALIDATE for pages whose translation is redone | KEEP |
| 13. Settings change mid-run | KEEP — frozen snapshot unaffected; config never mutates under a run (design §3.1 RUN_SNAPSHOT) | KEEP | KEEP | KEEP (run completes under its frozen config) | KEEP | KEEP | KEEP | KEEP |

Rationale anchors: rows 1–2, 6, 9–10 follow design §10 ("Source/settings/config change invalidates only dependent phases…"); row 8 follows final-target-migration §3 (controlling: the planner does not consume the cleaned bitmap; inpaint changes invalidate color preparation, not layout — design §10's looser "inpaint/layout" wording is superseded here); rows 4–5 scope analyzer/authority invalidation to profile and downstream, never OCR; row 7 implements final-target-migration §2 ("Changing only envelope policy must not invalidate an otherwise identical translation"); row 10 vs row 9 encodes the paint/geometry split including the stroke-width exception (final-target §3, §8.3); rows 11–13 implement "User-edited blocks and the last committed display remain authoritative through every invalidation" (design §4.4). Reconciliation note: design §10 says "Font/layout change invalidates layout only" and final-target §3 keeps stroke width layout-affecting — both are honored because stroke policy lives in the layout fingerprint only (row 9), never in color (row 10).

Test form: one parametrized invalidation test per row × affected column, asserting the cell value via fingerprint equality/inequality plus planner decisions (audit Stage-5/7 exits).

## 7. Director decisions needed

**7.1 Profile-change invalidation of completed machine translations** (README open decision 7; audit blocking 4). When a re-frozen profile's content differs, must already-completed machine translations retranslate automatically? Brief: blanket retranslation maximizes consistency but repays tokens/calls for the whole chapter on any profile tweak and can loop after repeated corrections; keep-as-is risks mixed old/new terminology within a chapter. RECOMMENDATION: completed machine translations are KEEP by default (matrix rows 4/5/12); the new profile governs pending work and any page the user explicitly refreshes; profile deltas that contradict committed output are recorded as correction candidates (§1.4 `correctionCandidates`) for a future run or user action. This preserves "corrections separate, no silent profile mutation" (design §4.4) and bounds cost.

**7.2 Manifest schemaVersion 2 → 3 at pointer cutover.** T924-SC-04 recommends bumping so a rolled-back (older) build preserves the chapter read-only instead of stripping new pointers on rewrite. Consequence: during a rollback window, chapters carrying new pointers cannot be written by the old build at all (existing future-schema behavior). Alternative: stay at v2 and accept pointer-stripping risk on rollback. RECOMMENDATION: take the bump; read-only preservation is the safer failure and matches the verified guard, and the rollback window is finite (final-target Stage 8).

**7.3 Unknown-version OCR checkpoints: eager vs lazy re-derivation.** A future-schema checkpoint makes its page's OCR unusable to the current reader. RECOMMENDATION: lazy — re-derive (re-OCR) only when the page actually enters a planning frontier, never eagerly on store open; preserves open latency (T921 lesson: no heavy work on the store monitor) and bounded memory.

**7.4 Translator-signature mismatch on committed display** (e.g. provider deprecates the model mid-chapter, forcing a different model). Provenance mismatch makes completed translations non-reusable as Batch evidence (row 6), but revoking what the user already sees would be destructive. RECOMMENDATION: display is never revoked; mismatch is surfaced as provenance metadata and retranslation requires explicit user action or new pending work.

**7.5 Persisted-layout platform shaping-key breadth.** How coarse may `platformShapingKey` be (per API level vs tested bucket) before a persisted plan is accepted as compatible? This gates T924-FP-07 completeness and the Stage-7 "font/platform compatibility tests define reuse boundaries" exit. RECOMMENDATION: start maximally conservative (key includes SDK-int; plans from a different key re-plan via the async fallback), then widen buckets only with the measured device matrix final-target §7 already requires.

## 8. Conflicts

- **VERIFIED naming discrepancy (non-blocking):** `translator/retry/AiTranslationRetryController.kt` exists as a file (1028 lines) but declares no class named `AiTranslationRetryController` at HEAD — its declarations are `AiTranslationRetryPolicy` and related types (`AiTranslationRetryController.kt:41`). `chapter-profile-batch-design.md` §2 cites it as a component. Doc citations should refer to the file/policy names; no schema or fingerprint content depends on it.
- No conflict found between `chapter-profile-batch-design.md` §4.4/§10 and `final-target-migration.md` §2/§3 on fingerprint scopes or invalidation: the color-only vs stroke-width distinction, envelope-policy non-invalidation, and display-authority survival are stated consistently in both. The single genuine tension (additive-nullable manifest fields vs cross-version read-modify-write field stripping) is resolved as a recommendation in T924-SC-04 and surfaced as decision 7.2; precedence per README: unresolved, it goes to the Director, not to silent implementer choice.
