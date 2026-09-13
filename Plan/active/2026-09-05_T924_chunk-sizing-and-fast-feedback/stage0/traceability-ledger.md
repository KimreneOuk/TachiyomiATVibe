# T924 Stage 0 — Live traceability ledger

Date: 2026-09-05 · Baseline: `adbe643` (verified) · Catalog:
`stage0/requirements-catalog.md` (ID definitions, sources, verification routes).

This file is the single place stage exits are recorded against
(delivery-readiness-audit §Control mechanism). One row per catalog ID.

## Column conventions

- **Code owner/path**: existing mechanisms cite the real file. Paths are
  relative to `app/src/main/java/eu/kanade/translation/` unless prefixed
  otherwise (reader-view files are relative to `eu/kanade/tachiyomi/`).
  Code not yet written is `TBD-Stage-N`, where N follows
  final-target-migration §4: WP1→1, WP2→1/3, WP3→2, WP4→3, WP5→4, WP6→5,
  WP7→6, WP8/WP9→7, WP10-WP12→8.
- **Test/evidence ID**: existing tests cite the real file relative to
  `app/src/test/java/eu/kanade/translation/`. New tests are `TBD-Stage-N`.
- **Accepted result / reviewer**: empty until a stage exit records them.
- **Status** (`SPECIFIED` / `NEEDS-DIRECTOR-DECISION` / `MEASUREMENT-GATED` /
  `IMPLEMENTED` / `VERIFIED-EXIT`): currently the Stage-0 specification
  status from the catalog; transitions to IMPLEMENTED when code lands and
  VERIFIED-EXIT when a reviewer closes the row with evidence.

## Ledger

| Requirement ID | Normative sentence (short) | Source | Work package | Code owner/path | Test/evidence ID | Accepted result | Reviewer | Status |
|---|---|---|---|---|---|---|---|---|
| T924-R001 | Durable phase sequence: validate/freeze → hash/reuse → OCR preflight → analysis → profile freeze → global envelope plan → sequential translation → inpaint/render/commit → close | profile-preflight-requirements §pipeline; design §1, §3 | WP1-WP6 | TBD-Stage-3 (new ChapterProfileBatchCoordinator); retains pipeline/batch/BatchChapterTranslator.kt shell | TBD-Stage-3/5 (integration/resume) | | | SPECIFIED |
| T924-R002 | All required pages READY/TEXTLESS and checkpointed before any paid analysis; OCR/persistence failure stops pre-analysis | profile-preflight-requirements §pipeline; design §3.5 | WP2, WP4 | TBD-Stage-3 (coordinator phase gate); builds on pipeline/SinglePageOnnxPhase.kt | TBD-Stage-3 (provider-fake zero-call test) | | | SPECIFIED |
| T924-R003 | 200-page chapter stress validation with bounded memory and device evidence | profile-preflight-requirements §pipeline; design §5, §14 | WP10 | n/a (measurement) | TBD-Stage-8 (device 200-page trace) | | | MEASUREMENT-GATED |
| T924-R004 | Bounded whole-page analysis chunks with overlap, page-order execution, independent validated persistence, resolvable evidence refs, hierarchical reconciliation | profile-preflight-requirements §analysis; design §6.1-6.3 | WP3, WP5 | pure planner half landed: translator/contextual/AnalysisChunkPlanner.kt `plan`/`evidenceResolves` (commit `73f6933`); persistence/provider half TBD-Stage-5 (WP5) | translator/contextual/AnalysisChunkPlannerGoldenTest (17, incl. 100-seed permutation invariance) | Pure planner half: whole-page core windows (1..16 core, ≤2 adjacent overlap, page-order, textless skip reported), deterministic `chunk-<ordinal>-<corpus8>` ids, contributing corpus fingerprints over the contributing set in order, `evidenceResolves` V1/V9 pure subset (page/block membership + blockId prefix guard). Validated persistence + hierarchical reconciliation remain WP5. Gap-4 mapping DONE wave-3 (`c2705c8`): `translator/contextual/AnalysisChunkMapping.kt` (pure, core-then-context order, status init VALID/INVALID, `evidenceBoundaryError`) + `AnalysisChunkMappingTest` (6, incl. DTO boundaries + canonical round-trip) | wave2-review.md ACCEPT-WITH-FIXES; wave3-review.md ACCEPT-WITH-FIXES — F2 consolidation + gap-4 mapping landed (`c2705c8`) | IMPLEMENTED |
| T924-R005 | Profile distinguishes canonical terms, entities/aliases, gender/pronouns incl. UNKNOWN/CONFLICTING, evidence/confidence, scene tone, range-scoped narrative | profile-preflight-requirements §profile; design §4.3, §6.2 | WP1, WP5 | TBD-Stage-1/4 (ChapterTranslationProfile DTO) | TBD-Stage-2 (schema tests) | | | SPECIFIED |
| T924-R006 | Summaries complement, never replace, structured entity/term records | profile-preflight-requirements §profile; design §6.3 | WP5 | TBD-Stage-4 | TBD-Stage-2 (schema validation) | | | SPECIFIED |
| T924-R007 | Envelope carries capped relevant profile subset + range-safe scene context + gap-free history with provenance + stable page/block IDs | profile-preflight-requirements §requests; design §7 | WP3, WP6 | TBD-Stage-2 (matcher); prompt extension of translator/contextual/TranslationPrompts.kt | TBD-Stage-2 (matcher tests); baseline translator/contextual/TranslationPromptsTest.kt | | | SPECIFIED |
| T924-R008 | Envelope admission uses hard token/output budgets plus structural blocks/pages/source/ID limits; token checks never looser than structural | README §constraints; design §8 | WP3 | pure admission half landed: translator/contextual/GlobalEnvelopePlanner.kt `plan` (commit `73f6933`); baseline translator/contextual/StreamingChunkPlanner.kt retirement for AI Batch still open (later stage) | translator/contextual/GlobalEnvelopePlannerGoldenTest (16, incl. golden fixture `app/src/test/resources/t924/golden/envelope-plan-small.json` byte-equality + pinned planFingerprint) | Pure admission half: hard token/output caps (estimator v1 16384 in / 8192 out) plus structural 32 blocks / 8 pages, oversized single page rejected whole, exact-once coverage in canonical order, planner re-verifies its own plan (`validationError`/coverage/budgets) before returning success. StreamingChunkPlanner retirement + provider-level checks = later stages | wave2-review.md ACCEPT-WITH-FIXES; ceiling VALUES stay PROPOSED-GATE (R033); F2 hasher consolidation DONE wave-3 (`c2705c8`: `PlannerFingerprints` deleted, four envelope builders + public `canonicalFingerprint` on the `StageFingerprints` core, goldens byte-valid un-re-pinned, 16/16 green) | IMPLEMENTED |
| T924-R009 | Strict ID ownership/coverage validation: canonical IDs, one output per ID, missing/duplicate/unknown/blank/malformed, echo/refusal, exact cardinality | profile-preflight-requirements §requests; design §9.1 | WP3, WP6 | retains translator/contextual/ContextualResponseParser.kt (extended failure class) | baseline translator/contextual/ContextualResponseParserTest.kt; TBD-Stage-2 fixtures | | | SPECIFIED |
| T924-R010 | Deterministic whole-page split/backoff; fragments never commit; static conservative before adaptation | profile-preflight-requirements §requests; design §9 | WP3, WP6 | TBD-Stage-2 (taxonomy+split); above translator/retry/AiTranslationRetryController.kt | TBD-Stage-2 (fault injection) + TBD-Stage-5 (provider) | | | SPECIFIED |
| T924-R011 | Analysis+translation share one 15-RPM aggregate Batch sub-limit beneath shared provider bucket; Manual/Auto interactive priority preserved | profile-preflight-requirements §investigate; design §11-12; final-target-migration §8.6 | WP5, WP6 | extends translator/ProviderRequestGovernor.kt (add Batch sub-limit; current default 60 RPM) | baseline translator/ProviderRequestGovernorTest.kt + ProviderRequestGovernorReservationTest.kt; TBD-Stage-4 (shared-limit integration) | | | SPECIFIED |
| T924-R012 | Forced translation independently reuses valid detection/OCR decoupled from inpaint readiness | design §10 | WP3 | model/PageWorkPlanner.kt force branch: `runOcr = !ocrEvidenceValid` + new `forceOcrEvidenceMatches` (commit `732f7ff`); sole caller pipeline/SinglePageOnnxPhase.kt:279 (evidence pass-through); pipeline/batch/BatchResumePlanner.kt supplies batch expectations unchanged | model/PageWorkPlannerForceReuseTest (16 tests) + PageWorkPlannerTest (10, non-force regression anchor) | Force reuse decoupled from inpaint readiness; supplied detection/OCR/source evidence validated, missing expectations pass through batch-style; only OCR-ready+valid+inpaint-not-ready case changed, non-force path byte-identical; SinglePageOnnxPhase evidence-provider wiring deferred to S3 (review-ratified SAFE, F-5 dormant) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-R013 | Sparse streamed URL keys migrate to downloaded filename identities without equal-count precondition | design §10; README §origin | WP1 | rework in TranslationManager.kt (current rekey requires equal counts); store rekey path | baseline ChapterTranslationStoreRekeyTest.kt; TBD-Stage-1 (sparse rekey) | | | SPECIFIED |
| T924-R014 | Chapter-level preparation phases with (done,total) counters; no fake per-page analysis states; paused state names phase + resume action | design §13 | WP4, WP5 | extends model/TranslationProgressSnapshot.kt + pipeline/batch/TranslationBatchEvent.kt + tracker/sheet | baseline pipeline/batch/TranslationBatchEventContractTest.kt + TranslationBatchProgressReducerTest.kt; TBD-Stage-3/4 | | | SPECIFIED |
| T924-R015 | UI explains Batch preparation vs Manual/Auto immediacy; foreground notification + Stop All continue through OCR/analysis | design §13 | WP4, WP5 | TBD-Stage-3 (progress sheet/notification); foreground service shell pipeline/batch/BatchChapterTranslator.kt | TBD-Stage-3 (UI/integration) | | | SPECIFIED |
| T924-R016 | Per-page reader refresh preserved on all paths | profile-preflight-requirements §preserve; final-target-migration §5 | WP4-WP12 | retained per-page refresh (store/ChapterTranslationStore.kt emissions) | baseline coexistence/D2ManualBatchInterleavingTest.kt; TBD per stage (regression) | | | SPECIFIED |
| T924-R017 | Semantic invalidation matrix: OCR-model/source → downstream; analyzer/profile-policy/canon → profile+translations not OCR; font → layout; inpaint → inpaint/layout | design §10; final-target-migration §3 | WP1, WP3 | TBD-Stage-1 (fingerprints); baseline artifact/StageFingerprintsTest.kt | TBD-Stage-1 (fingerprint matrix); baseline artifact/StageFingerprintsTest.kt | | | SPECIFIED |
| T924-R018 | Android 8+ lifecycle: process recreation, foreground notification, queue restore w/o auto-start, thermal, low memory | profile-preflight-requirements §preserve; design §14 | WP10 | n/a (evaluation); service shell pipeline/batch/BatchChapterTranslator.kt | baseline ChapterTranslatorQueueRestoreTest.kt; TBD-Stage-8 (device matrix) | | | SPECIFIED |
| T924-R019 | No silent scope broadening; non-AI preflight and settings-snapshot/UX decisions stay out of this delivery | profile-preflight-requirements §closing; delivery-audit #17 | WP0 | n/a (process) | Review per stage exit | | | SPECIFIED |
| T924-R020 | Feature flag with rollback; default only after evidence gates; WP12 only after rollback window | final-target-migration §4 Stage 8; design §Recommendation | WP4, WP10, WP11, WP12 | TBD-Stage-3 (flag definition; specs in T924-FF namespace) | TBD-Stage-8 (rollback demonstration) | | | SPECIFIED |
| T924-R021 | Every stage compiles, preserves old behavior behind flag, leaves valid resume boundary | final-target-migration §4 | WP1-WP9 | n/a (stage discipline) | Review + CI + TBD resume test per stage | | | SPECIFIED |
| T924-R022 | Instrument tokens/attempts/failure classes/accepted blocks/subset size/corrections/phase time; evidence stored with device/OS/commit/provider/config/fixture identity | design §12, §14; delivery-audit evidence note | WP5, WP6, WP10 | TBD-Stage-4/5 (metrics); TBD-Stage-8 (evidence records) | TBD-Stage-8 (provider/device records) | | | SPECIFIED |
| T924-R023 | Analysis premium justified by measured provider evaluation vs baseline before default | design §12, §15 | WP10, WP11 | n/a (evaluation) | TBD-Stage-8 (provider evaluation vs baseline) | | | MEASUREMENT-GATED |
| T924-R024 | Every change cites requirement IDs + tests in this ledger; stage exits record finish commit, IDs, evidence, deviations, rollback, verdict | delivery-audit §control mechanism; README reading order | WP0 | this ledger | Review per stage exit | | | SPECIFIED |
| T924-R025 | Stage kickoff records base commit + reverifies load-bearing assumptions; line citations are navigation hints | plan-completeness-audit §finding 5 | WP0 | n/a (process) | Review (stage report) | | | SPECIFIED |
| T924-R026 | Exact versioned schemas + canonical serialization + unknown-version behavior + crash-safe publication for run/checkpoint/chunk/profile/envelope/layout/color-style records | delivery-audit #1-2; design §4.2; README open #1 | WP1 | TBD-Stage-1 (DTOs; specs in T924-SC namespace); extends artifact/ChapterArtifactManifest.kt + artifact/ChapterArtifactStore.kt | baseline artifact/AtomicChapterDocumentsTest.kt + LegacyArtifactMigrationTest.kt; TBD-Stage-1 (golden serialization) | | | NEEDS-DIRECTOR-DECISION |
| T924-R027 | Analyzer/translator provider-model relationship, analysis schemas, field limits, evidence syntax, typed error/retryability contract decided pre-provider-stages | delivery-audit #7, #9; design §15.1 | WP5 | TBD-Stage-4 (typed provider API; specs in T924-AP namespace) | TBD-Stage-4 (provider fixtures) | | | NEEDS-DIRECTOR-DECISION |
| T924-R028 | First-release authority inputs (user/series canon), absent-authority fingerprint/display, glossary UX + promotion workflow decided; candidates-only until then | delivery-audit #10; README open #5; design §6.4 | WP1, WP5 | TBD-Stage-4 (series canon storage is new; no user/series glossary exists today); legacy store/ChapterGlossaryStore.kt + translator/contextual/ChapterGlossaryBuilder.kt untouched | TBD-Stage-2/4 (authority schema tests) | | | NEEDS-DIRECTOR-DECISION |
| T924-R029 | Mixed malformed-response retention (commit now vs candidates until recovery) decided; design MISSING_ONLY proposal is default pending decision | README open #6; design §9.2; delivery-audit #8 | WP3, WP6 | TBD-Stage-2 (taxonomy) | TBD-Stage-2 (fault-injection) | | | NEEDS-DIRECTOR-DECISION |
| T924-R030 | Profile-change invalidation policy for completed machine translations decided | README open #7; design §15.7 | WP1, WP3 | TBD-Stage-1 (fingerprint policy) | TBD-Stage-2 (fingerprint tests) | | | NEEDS-DIRECTOR-DECISION |
| T924-R031 | Non-AI Batch OCR-preflight adoption decided; until then non-contextual Batch unchanged | README open #8; design §15.8; delivery-audit #17 | WP4 | n/a (decision); legacy lane pipeline/batch/SequentialBatchCoordinator.kt untouched meanwhile | baseline pipeline/batch/SequentialBatchCoordinatorTest.kt + Phase0BatchTranslationCharacterizationTest.kt | | | NEEDS-DIRECTOR-DECISION |
| T924-R032 | Provider/model/credential RPM+TPM table + rolling-window 15-RPM sub-limit implementation accepted (tunable during flagged evaluation) | README open #2; delivery-audit #12; design §12 | WP5, WP6, WP10 | extends translator/ProviderRequestGovernor.kt (QuotaPolicy per provider) | TBD-Stage-4/5 (governor integration); TBD-Stage-8 (provider eval) | | | MEASUREMENT-GATED |
| T924-R033 | Initial budgets (32 blocks/8 pages, 50% analysis reserve, 1-page/10% overlap, split depth 3 / 4 leaves / 8 attempts) confirmed by instrumentation before freezing | README open #3; design §6.1, §8-9; final-target-migration §7 | WP3, WP10 | TBD-Stage-2 (planner constants) | TBD-Stage-5/8 (provider + device) | | | MEASUREMENT-GATED |
| T924-R034 | Native reader-priority/anti-starvation thresholds device-tuned; Batch cannot be starved forever; interactive latency preserved | README open #11; design §5 | WP4, WP10 | TBD-Stage-3 (native admission in front of quarantine; builds on pipeline/EngineLane.kt + recognition/RoiPageRecognitionEngine.kt guard) | TBD-Stage-3/8 (contention device test) | | | MEASUREMENT-GATED |
| T924-R035 | Skip analysis for textless / no-work / compatible frozen profile; small-chapter threshold needs measured value + acceptance | design §3.6, §12; README open #4; delivery-audit #11 | WP3, WP5 | TBD-Stage-2 (corpus manifest skip rules) | TBD-Stage-2 (skip tests); TBD-Stage-8 (threshold) | | | MEASUREMENT-GATED |
| T924-R036 | Persisted layout is separate milestone (WP8/9), not coupled to first cutover; DISPLAY_READY redefinition only after all reader paths hydrate durable plans | final-target-migration §1, §4 Stage 7; README scope #11 | WP8, WP9 | TBD-Stage-7 | TBD-Stage-7 (state/UI integration) | | | SPECIFIED |
| T924-R037 | Versioned immutable source-image-space draw-plan DTO + compatibility fingerprint (font asset, typeface/style, measurement flags, planner version, platform shaping); never serialize BlockLayout; stroke width stays layout-affecting | final-target-migration §1.1-1.2, §3, §8.2-8.3 | WP8 | rendering/LayoutDrawPlanProjection.kt `projectToDrawPlan`/`rehydrate`/`encodeToCanonicalJson` (over the S1 DTO artifact/ChapterDrawPlan.kt; through shared `ArtifactDocumentJson` only) + rendering/DrawPlanFingerprint.kt (thin wrapper over `StageFingerprints.layoutCompatibilityFingerprint`, thinness test-pinned; pinned font/paint constants; conservative SDK-bucket `platformShapingKey`); rendering/TextLayoutPlanner.kt ZERO diff (commit `1d3fcb1`) | rendering/LayoutDrawPlanProjectionTest (5) + DrawPlanFingerprintTest (7); whole rendering package 25 suites / 222 tests / 0 failures | WP8 scope landed (`1d3fcb1`): gate-7.1 round trip EXACT (`toRawBits` floats, byte-identical re-encode, real-planner 7-block corpus), render-order reconstruction via the planner's documented sort, durable mask-content hash replaces page-local `planGeometryId`, stale-input rehydrate skips (never mis-draws); BlockLayout never serialized; stroke width stays layout-affecting. WP9 landed wave-3 (`74302ec`): publication/hydration wiring, gate-7.1/7.2 oracle files (`DrawPlanDtoRoundTripTest` 7 / `DrawPlanCompatibilityTest` 14), font-digest loaders installed (`BatchRenderJoin.kt` + `TranslationOverlayView.kt`, cached in `PersistedLayoutRuntime`); the real digest hex value + on-device rows fold into the Stage-7 device rows | wave2-review.md ACCEPT-WITH-FIXES; WP9 delivered wave-3 (wave3-review.md ACCEPT-WITH-FIXES) | VERIFIED-EXIT |
| T924-R038 | Layout publication CAS preconditions + stale hydration rejection via bind generation | final-target-migration §2 | WP9 | landed wave-3 `74302ec`: pipeline/batch/BatchRenderJoin.kt `publishPersistedLayout` (authority, artifact page-version, candidate generation, dependency fingerprint, OCR block identity fences; content-addressed sidecar names + compat fp; ONE `publishSidecarPointers` whole-manifest CAS; Committed façade update; Rejected WARN non-fatal); bind-generation stale defense rendering/TextLayoutCoordinator.kt | PersistedLayoutHydrationTest (stale bind delivery dropped) + DrawPlanDtoRoundTripTest (full store round trip through the publication transaction) | Fence set reviewer-verified complete (wave3-review §4); race/device legs owed at Stage 7 | wave3-review.md ACCEPT-WITH-FIXES (fixes landed) | VERIFIED-EXIT |
| T924-R039 | Reader async planning retained as fallback (Manual/Auto, legacy, corrupt/missing, rollback); 12-page cache holds hydrated objects/paths only | final-target-migration §4 Stage 7, §8.4 | WP9 | landed wave-3 `74302ec`: reader bind rendering/TextLayoutCoordinator.kt `hydrate` lambda (planner counter 0 on hydrated hits); cache contract rendering/ReaderTextLayoutCache.kt KDoc (HYDRATED/prepared draw objects only, never DTO bytes) | PersistedLayoutHydrationTest (hydrated bind / LRU eviction + rebind / process-restart rebind, planner counter 0; fallback ×4 + corrupt) | JVM legs PASS; on-device Pager/Webtoon holder legs + restart/LRU on real holders owed at Stage 7 | wave3-review.md ACCEPT-WITH-FIXES (fixes landed) | VERIFIED-EXIT |
| T924-R040 | Layout invalidation graph: text→layout; geometry/mask/dims→layout(+inpaint); font→layout; cleaned→color; color-only→no reflow; SSIV transforms→no invalidation | final-target-migration §3 | WP8, WP9 | landed wave-3 `74302ec`: 12-row typed matrix in rendering/PersistedLayoutHydrator.kt (font digest, planner/stroke/stroke-color versions, platform shaping key, page/bind dims, sample size, stored compat fp, translation-content change, F5 loss; SSIV excluded = repeated hydration stable) + batch-side stage CAS | DrawPlanCompatibilityTest (14, named-reason matrix incl. color-only change + repeated-hydration stability) + DrawPlanDtoRoundTripTest (user-edit invalidates, OCR identity untouched) | JVM matrix PASS; `platformShapingKey()` on-device cross-SDK exercise owed at Stage 7 (JVM pins the mechanism with a synthetic key) | wave3-review.md ACCEPT-WITH-FIXES (fixes landed) | VERIFIED-EXIT |
| T924-R041 | No baked translated raster persisted; vector draw-plan only | final-target-migration §1 | WP8 | n/a (exclusion); overlay draws vector text ui/reader/viewer/TranslationOverlayView.kt | Review + visual tests TBD-Stage-7 | | | SPECIFIED |
| T924-R042 | Manual/Auto unchanged except consuming compatible OCR checkpoints and valid persisted layouts | final-target-migration §5; profile-preflight-requirements §pipeline | WP4-WP12 | reader path TranslationPipeline.kt (INTERACTIVE); coexistence harness tests | baseline coexistence/NormalMangaIsolationTest.kt + D2ManualBatchInterleavingTest.kt; per-stage regression TBD | | | SPECIFIED |
| T924-R043 | Non-contextual engines + legacy Batch lanes retain current paths/coordinator | design §header, §2 | WP12 (boundary), all | retained pipeline/batch/SequentialBatchCoordinator.kt, pipeline/batch/BatchLaneWorkers.kt | baseline pipeline/batch/SequentialBatchCoordinatorTest.kt + BatchTerminalExitTest.kt; per-stage regression TBD | | | SPECIFIED |
| T924-R044 | Superseded small-first/Fast/token-overflow direction NOT implemented; chunk-sizing-options + batch-architecture-overview are history only | README reading order #8; final-target-migration §6 | WP0 (review gate) | n/a (exclusion) | Review (no code from superseded direction) | | | SPECIFIED |
| T924-R045 | OCR checkpoint: atomic CAS validate (generation, page version, lease token, source identity, dependency fingerprints) → publish origin-neutral checkpoint → preserve committed display → close/rebase BATCH candidate → then release lease; failure keeps prior manifest + Batch ownership | design §4.2, §5; final-target-migration §2; delivery-audit Stage 3 exit | WP2 | TBD-Stage-1/3 (checkpointOcr in artifact/ChapterArtifactStore.kt + store/ChapterTranslationStore.kt; exact contract in T924-ST/TX namespace); origin checks exist at ChapterArtifactStore.kt candidate-write guard | baseline ChapterArtifactStoreTest.kt + ChapterTranslationStorePhase3Test.kt; TBD-Stage-3 (failure-injection at every publication boundary) | | | SPECIFIED |
| T924-INV-01 | Page atomicity: no committed envelope splits a page; oversized single page rejected; targeted repair stays in frozen page transaction | README §constraints; design §8-9 | WP3, WP6, WP10 | planner half landed: translator/contextual/GlobalEnvelopePlanner.kt + AnalysisChunkPlanner.kt (chunking never splits a page; a page over any cap alone still gets its own single chunk; envelope oversized single page `Rejected` whole) (commit `73f6933`); retry-tree/repair half TBD-Stage-6 (WP6) | translator/contextual/GlobalEnvelopePlannerGoldenTest (page-atomicity rows: single-envelope ownership, per-page block contiguity, oversized rejections for blocks/input/output tokens) + AnalysisChunkPlannerGoldenTest (core sets partition corpus exactly once) | Planner property half proven (never split; oversized rejected, never partially shaped); durable-commit/provider fault half pending WP6 | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | IMPLEMENTED |
| T924-INV-02 | One Batch provider envelope in flight | README §constraints; design §3.12; final-target-migration §4 Stage 5 | WP4, WP6, WP7 | TBD-Stage-5 (coordinator dispatch); overlap scheduler TBD-Stage-6 | TBD-Stage-5/6 (provider fake trace) | | | SPECIFIED |
| T924-INV-03 | Hard token ceilings always enforced; structural limits only stricter | README §constraints; design §8 | WP3, WP6 | planner half landed: translator/contextual/GlobalEnvelopePlanner.kt (input/output token caps always backstops beside structural 32 blocks/8 pages; envelope closure on token cap) + AnalysisChunkPlanner.kt (token cap with farthest-first overlap shedding staying in budget) (commit `73f6933`); request-time enforcement TBD-Stage-6 (WP6) | GlobalEnvelopePlannerGoldenTest (token-budget close, estimator arithmetic, 4096-envelope schema bound) + AnalysisChunkPlannerGoldenTest (token-cap flush + overlap shedding) | Hard ceilings enforced in both planners with structural-never-looser admission; ceiling VALUES remain PROPOSED-GATE experiments per R033 (measured constants, never flags) | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | IMPLEMENTED |
| T924-INV-04 | Only contiguous fully committed pages advance frontier; PARTIAL/malformed never feed history; missing page fences context | profile-preflight-requirements §preserve; design §2, §9.7, §11 | WP3, WP6 | retains pipeline/batch/BatchContextFrontier.kt (VERIFIED: PARTIAL non-advance guard) | baseline pipeline/batch/BatchContextFrontierTest.kt; TBD-Stage-5 (fragmented resume case) | | | SPECIFIED (current guard VERIFIED) |
| T924-INV-05 | One decoded page at a time in OCR; bitmaps released per page; no cross-phase handoffs; leases not held across phases | profile-preflight-requirements §preserve; design §5 | WP4, WP10 | TBD-Stage-3 (OCR sprint worker; decode/analyze in pipeline/SinglePageOnnxPhase.kt, memory budget util/TranslationMemoryBudget.kt) | TBD-Stage-3/8 (device: bitmap count, peak memory) | | | SPECIFIED |
| T924-INV-06 | Candidate/committed separation: partial/malformed never renders or replaces last committed display | profile-preflight-requirements §preserve; design §9.2; final-target-migration §2 | WP2, WP6 | retains store/ChapterTranslationStore.kt display promotion + artifact/ChapterArtifactStore.kt | baseline ChapterTranslationStorePhase3Test.kt + coexistence/D3ReaderOwnedPageAcrossBatchTest.kt; TBD-Stage-5 | | | SPECIFIED |
| T924-INV-07 | User edits + last committed display authoritative through re-plan/resume/invalidation; excluded from Batch re-translation | profile-preflight-requirements §layers; design §4.4, §11 | WP1, WP5, WP6, WP9 | store/ChapterTranslationStore.kt guarded writes; TBD-Stage-5 (dispatch exclusion) | baseline ChapterTranslationStorePatchPageGraceTest.kt + TranslationRequestGenerationFenceTest.kt; TBD-Stage-5/7 | | | SPECIFIED |
| T924-INV-08 | One origin owns page/stage; writes validate generation/version/lease-token/origin/dependency; Manual attaches to Batch (never preempts) | profile-preflight-requirements §preserve; design §4.2, §5 | WP1, WP2, WP4, WP6 | retains store/PageStageLeaseTable.kt (VERIFIED) + artifact/ChapterArtifactStore.kt origin guard (VERIFIED) | baseline coexistence/D1OriginPriorityTest.kt + ChapterArtifactStoreTest.kt; TBD-Stage-3 (stale Batch write rejected) | | | SPECIFIED (VERIFIED) |
| T924-INV-09 | Frozen profile immutable for run; no silent mutation; corrections are separate candidates | profile-preflight-requirements §layers; design §3.9, §4.4; final-target-migration §2 | WP1, WP5, WP6 | TBD-Stage-4 (profile publication); translator/contextual/ChapterGlossaryBuilder.kt excluded from new path | TBD-Stage-2/4 (freeze immutability golden) | | | SPECIFIED |
| T924-INV-10 | No auto-promotion of model facts into series canon; candidates only | design §6.4, §15; final-target-migration §8.5 | WP5, WP11 | TBD-Stage-4 (series-update candidate emission) | TBD-Stage-4 (candidate-only schema test) | | | SPECIFIED |
| T924-INV-11 | Manual/Auto never wait for chapter OCR/analysis/profile | profile-preflight-requirements §pipeline; design §11 | WP4, WP6 | retains store/PageStageLeaseTable.kt attach semantics (VERIFIED) + TranslationPipeline.kt interactive priority | baseline coexistence/D2ManualBatchInterleavingTest.kt + D3ReaderOwnedPageAcrossBatchTest.kt; TBD-Stage-3 (reader during OCR page) | | | SPECIFIED (VERIFIED) |
| T924-INV-12 | Batch native admission: one page, release lane between pages, yield to interactive, bounded anti-starvation; foreground notification + Stop All persist | profile-preflight-requirements §preserve; design §5, §13 | WP4, WP10 | TBD-Stage-3 (priority admission; native guard pipeline/EngineLane.kt); priority note: provider governor priority ≠ native mutex | TBD-Stage-3/8 (contention device test; reader wait distribution + starvation bound) | | | SPECIFIED |
| T924-INV-13 | Reuse/invalidation by semantic fingerprints only; transaction identities never hashed; envelope-policy-only change never invalidates compatible translation | profile-preflight-requirements §preserve; design §4.4 | WP1, WP3, WP6 | TBD-Stage-1 (PageOcrContentFingerprint/OcrCorpusFingerprint/ProfileContentFingerprint); baseline artifact/StageFingerprintsTest.kt | baseline artifact/StageFingerprintsTest.kt; TBD-Stage-1 (determinism + transaction-identity exclusion) | | | SPECIFIED |
| T924-INV-14 | No future-narrative leakage: identity facts may inform earlier dialogue; RANGE_SCOPED/AVAILABLE_FROM facts apply only from evidence point | profile-preflight-requirements §profile; design §4.3 | WP3, WP5, WP6 | TBD-Stage-2 (range policy) + TBD-Stage-5 (request assembly) | TBD-Stage-2 (range-policy fixtures) | | | SPECIFIED |
| T924-INV-15 | OCR checkpoint origin-neutral; fresh Manual/Auto/Batch candidate starts from it; lease released only after publication | design §4.2, §5, §11; final-target-migration §2 | WP2, WP4 | TBD-Stage-1/3 (checkpointOcr; artifact/ChapterArtifactStore.kt) | TBD-Stage-3 (fresh-candidate-from-checkpoint race/crash tests) | | | SPECIFIED |
| T924-INV-16 | Native serialization: no concurrent detector/OCR/inpaint across pages; no inpaint during OCR preflight; no cross-page OCR fan-out | design §5; final-target-migration §8.7 | WP4, WP7 | pipeline/EngineLane.kt + recognition/RoiPageRecognitionEngine.kt (VERIFIED single native guard); TBD-Stage-6 scheduler | TBD-Stage-6 (scheduler determinism) + device traces | | | SPECIFIED |
| T924-INV-17 | Gender set/promoted only from explicit or corroborated strong evidence; weak cues stay notes; UNKNOWN/CONFLICTING survive | profile-preflight-requirements §profile; design §4.3, §6.3 | WP3, WP5, WP6 | TBD-Stage-2/4 (evidence-strength model) | TBD-Stage-2 (weak-evidence non-promotion tests) | | | SPECIFIED |
| T924-INV-18 | Explicit-scene context never creates chapter-wide replacement rules | profile-preflight-requirements §profile; design §7.2 | WP3, WP5, WP6 | TBD-Stage-5 (scene-scope request assembly) | TBD-Stage-2 (scene-scoped fixture, e.g. explicit-scene disambiguation) | | | SPECIFIED |
| T924-INV-19 | Authority order: user-confirmed > series canon > frozen chapter facts > rolling context > local inference | profile-preflight-requirements §layers; design §6.4 | WP3, WP5, WP6 | TBD-Stage-2 (precedence) | TBD-Stage-2 (precedence tests) | | | SPECIFIED |
| T924-INV-20 | No glossary mutation/version bump during frozen-profile run; glossary is legacy compatibility only | design §2, §4.4 | WP5, WP6 | excludes store/ChapterGlossaryStore.kt + translator/contextual/ChapterGlossaryBuilder.kt from new path (VERIFIED: live version reuse gate exists today) | baseline coexistence/D5GlossaryAwareReuseTest.kt (legacy); TBD-Stage-4 (frozen-run assertion) | | | SPECIFIED |
| T924-INV-21 | One root RequestRetryBudget across parent + children; no child escapes attempt/depth/leaf caps; ceiling wins | design §9.5 | WP3, WP6 | extends translator/retry/AiTranslationRetryController.kt + translator/retry/TranslationRetry.kt | baseline translator/retry/TranslationRetryTest.kt + AiTranslationRetryControllerTest.kt; TBD-Stage-2 (child-escape fault tests) | | | SPECIFIED |
| T924-INV-22 | Unknown/extra lines never overwrite; parent becomes AMBIGUOUS_PROTOCOL and its targets are discarded before smaller retry | design §9.2, §9.4 | WP3, WP6 | retains translator/contextual/ContextualResponseParser.kt (extended) | baseline translator/contextual/ContextualResponseParserTest.kt; TBD-Stage-2 (extra-line fixtures) | | | SPECIFIED |
| T924-INV-23 | Legacy PROBE/old-path semantics intact until WP12 post-rollback-window; normal manga must not regress | README §constraints; final-target-migration §6 | WP12, all | retains legacy PROBE in pipeline/batch/BatchLaneWorkers.kt + SequentialBatchCoordinator.kt until Stage 8 | baseline coexistence/NormalMangaIsolationTest.kt + T918CancelledBatchRestartTest.kt; per-stage regression TBD | | | SPECIFIED |
| T924-INV-24 | Persisted phase transitions; startup resumes first incomplete phase; death loses at most active page; RUNNING artifacts recover retryable | design §3, §5, §10 | WP1, WP2, WP4, WP5, WP6 | TBD-Stage-1/3 (run record + recovery; specs in T924-ST/TX namespace) | TBD-Stage-3/4/5 (fault injection at every phase boundary) | | | SPECIFIED |
| T924-INV-25 | Translation commits carry profile content fingerprint + source-block identity; stale profile/source/page commits fail | design §4.4; final-target-migration §2 | WP1, WP6 | TBD-Stage-5 (commit provenance) via store/ChapterTranslationStore.kt CAS patches | baseline ChapterTranslationStorePatchPageGraceTest.kt; TBD-Stage-5 (stale-commit rejection) | | | SPECIFIED |
| T924-SC-01 | Every new durable document is a `@Serializable` data class with `schemaVersion` (default = its `SCHEMA_VERSION` constant) and `kind` discriminator; wrong kind/version fails semantic validation and is treated as absent-for-planning | contracts-schemas-fingerprints §1 | WP1 | artifact/ChapterRunRecord.kt, PageOcrCheckpoint.kt, AnalysisChunkResult.kt, ChapterTranslationProfile.kt, EnvelopePlan.kt, ChapterDrawPlan.kt (each with `validationError()`/`isSemanticallyValid`) | artifact/ChapterRunRecordSchemaTest (7 tests) | Six versioned DTOs landed (`bc94045`) with schemaVersion+kind and semantic validation; missing schemaVersion = current version per ratified deviation WP1-1 (kotlinx-default precedent, wrong/future kind+version still fail) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-SC-02 | All schema-level caps are named constants + pure-function checks; an over-bound document fails validation, is preserved, never partially consumed; (T) values are tunable, not product constants | contracts-schemas-fingerprints §1 | WP1 | DTO validation in the six artifact DTOs (frozenConfig ≤64KB, chunk core 1..16 / overlap ≤2, terms/entities/relationships ≤128, entities/terms ≤512, scenes ≤256/32, narrative ≤2000, conflictNotes ≤32×500, evidenceRefs ≤512, envelope ≤4096, blocks ≤256, `inpaintMaskRevision ≥ CURRENT_INPAINT_REVISION`, `producedByOrigin ∈ {BATCH, READER_ADHOC}`) | artifact/ChapterRunRecordSchemaTest (7); DTO `validationError()` | All contract bounds + iff-validation implemented (fact canonical forms, scope/gender conditioning, scene participant resolution, `ocrArtifactRefs == core+overlap`, VALID chunk carries no failure reason) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-03 | `SidecarPointer {fileName, schemaVersion, contentFingerprint 64-hex}` generalizes the `GlossaryPointer` pattern; `ProfilePointer` adds `version` + `profileInputFingerprint` | contracts-schemas-fingerprints §1.8 | WP1 | artifact/ChapterArtifactManifest.kt `SidecarPointer`/`ProfilePointer`/`isSha256Hex()` (+`toSidecarPointer()`) | artifact/ChapterArtifactStoreTest (45, incl. v2-fixture tests) | Pointers landed (`bc94045`); flat ProfilePointer mirrors GlossaryPointer precedent per ratified WP1-2, manifest fields keep contract types | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-04 | Manifest gains additive defaulted fields `activeRun`/`ocrCheckpoints`/`analysisChunks`/`profile`/`envelopePlan`/`layoutPlans`/`colorPreparations`; `SCHEMA_VERSION` 2→3; new code reads v2+v3, writes v3; future guard refuses >3 read-only | contracts-schemas-fingerprints §1.8; decision 7.2 | WP1 | artifact/ChapterArtifactManifest.kt (7 fields appended at declaration end, `SCHEMA_VERSION = 3`); artifact/ChapterArtifactStore.kt `parseManifest`/`normalizeSupportedSchema` | ChapterArtifactStoreTest gate-1.1 tests + fixture `app/src/test/resources/t924/manifest-v2.json` (loads + survives write cycle rewritten v3) | v3 bump landed per decision 7.2 (`bc94045`); v2 loads normalize in memory only, bytes rewritten only inside publications; strictly-`>` guard set grep-verified | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-05 | Manifest pointer updates stay single-publication atomic: sidecar bytes durable before the manifest; a crash between leaves at most an orphan sidecar, prior manifest authoritative | contracts-schemas-fingerprints §1.8, §4 | WP1 | artifact/ChapterArtifactStore.kt `publishSidecarPointers`/`publishActiveRun` (sidecars first, ONE manifest publication) | artifact/SidecarCrashPublicationTest (8, FakeChapterDocumentIo fault injection) | Sidecar-then-pointer publication landed (`bc94045`); fault at sidecar write/rename/manifest publish leaves prior manifest authoritative | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-06 | All new durable documents serialize through one shared Json (`ignoreUnknownKeys`, `encodeDefaults`); bespoke instances forbidden; declaration-order fields; round-trip `encode→decode→encode` byte-identical | contracts-schemas-fingerprints §2 | WP1 | artifact/ChapterDocumentIo.kt shared `ArtifactDocumentJson`; `AtomicChapterDocuments.json` aliases it (behavior-identical) | ChapterRunRecordSchemaTest (v1 example round-trips byte-identically); review F-8 bespoke-Json audit | Single shared instance for old + new durable documents (review-verified); remaining private Json instances are pre-existing non-durable (`ModelIdentityCache`, `LegacyFlatFileDecoder`) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-08 | Composite input fingerprints use the existing `StageFingerprints` encoding (length-prefixed fields, indexed list elements, `<null>` literal, enums by name, floats via `toRawBits()`, SHA-256 lowercase hex); delimiter-collision resistance extends to every new builder | contracts-schemas-fingerprints §2 | WP1 | artifact/StageFingerprints.kt (pre-existing `fingerprintIndexed`/`appendField` core reused byte-identical by all 7 new builders) | artifact/SemanticFingerprintTest (forgery fixtures: FP-02 single-field + two-block split; `profileInputFingerprint` + `translationProvenanceFingerprint` per F-3) | All 7 new builders on the shared encoding core with distinct labels (no aliasing); forgery fixtures for FP-02/04/06 landed (`5f66174`). Wave-3 F2: envelope builders consolidated onto the same core (`c2705c8`, goldens byte-valid un-re-pinned) — one encoding core now serves Stage-1 fingerprints + envelope planning | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-SC-12 | Version comparison is integer `schemaVersion` vs the reader's constant; `>` reader is unknown-version per the behavior table; `≤` readable, every historical version shipped accepted | contracts-schemas-fingerprints §3 | WP1 | artifact/ChapterArtifactStore.kt sealed `RunRecordRead`/`OcrCheckpointRead`; future-guard set (ChapterArtifactStore.kt + ChapterArtifactDeletion.kt:112) | ChapterRunRecordSchemaTest (7, incl. unknown-newer-version unusable with bytes preserved untouched) | Sealed reads landed; all guards strictly `> SCHEMA_VERSION` (=3), no equality checks anywhere (review grep-verified) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-13 | Unknown newer-version sidecars: unusable for planning, bytes preserved untouched, never deleted/quarantined/overwritten; manifest refused read-only; corrupt OCR checkpoint page re-enters OCR planning | contracts-schemas-fingerprints §3 | WP1 | artifact/ChapterArtifactStore.kt `RunRecordRead.UnsupportedVersion`/`OcrCheckpointRead.UnsupportedVersion` (bytes preserved); `Absent` = corrupt quarantined via `AtomicChapterDocuments.quarantineCorrupt` | ChapterRunRecordSchemaTest; SidecarCrashPublicationTest (corrupt-pointer case) | UnsupportedVersion returns unusable + preserved; retention keeps bytes while a pointer references them; corrupt quarantined `<name>.corrupt` (review-verified) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-17 | Corrupt artifacts (parse failure, bound violation, kind mismatch, failed referential validation) quarantined `.corrupt`, treated absent-for-planning, reclaimed by retention only when no pointer references them | contracts-schemas-fingerprints §3 | WP1 | artifact/ArtifactRetention.kt (`.corrupt`-sibling preservation; new pointers retention-reachable) + `AtomicChapterDocuments.quarantineCorrupt` | SidecarCrashPublicationTest (corrupt-pointer case + fault rows) | `.corrupt` siblings of pointer-reachable sidecars preserved until unreferenced (ratified WP1-6); sidecars absent-for-planning on parse/kind/bound failure | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-19 | The ONLY sidecar publication mechanism is `AtomicChapterDocuments.publish`/`publishJson`; the ONLY manifest-update mechanism is `ChapterArtifactStore` `publishManifestInternal`; no direct `ChapterDocumentIo.write` for durable documents | contracts-schemas-fingerprints §4 | WP1 | artifact/ChapterArtifactStore.kt (only `documents.publishJson` + `publishManifestInternal` used) | Reviewer clause verification (s1-review SC-19/20/22) + SidecarCrashPublicationTest (8) | No new atomicity primitives (Director rule 4 verified); checkpoint transaction composes the same primitives | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-20 | Publication order: publish every immutable sidecar first, then ONE atomic manifest publication installs all pointers; orphans reclaimed exclusively by `ArtifactRetention` reachability sweeps; fault at each window leaves prior manifest authoritative | contracts-schemas-fingerprints §4 | WP1 | artifact/ChapterArtifactStore.kt `publishSidecarPointers`; artifact/ArtifactRetention.kt (`reachablePaths` covers new pointers) | SidecarCrashPublicationTest (8) + CheckpointOcrTransactionTest (5 fault rows) | Sidecars-first-then-one-manifest ordering holds for all seven sidecar kinds incl. OCR checkpoints; crash windows fault-covered (`bc94045` + `1dfd7f5`) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-21 | Content-addressed sidecar naming: new dirs `runs/ ocr/ analysis/ profiles/ envelopes/ layout/ color/` under `X_artifacts/`, names `f-<sha256(fp)>.json`, page-scoped kinds nest under injective `pageSegment(pageKey)`, all in `managedDirectories`, `isSafeSegment` validated | contracts-schemas-fingerprints §4 | WP1 | artifact/ChapterArtifactLayout.kt (7 dirs + content-addressed builders + `managedDirectories`); per-sidecar `isSafeSegment`+`isManagedPath` validation in `publishSidecarPointers` (ChapterArtifactStore.kt:717-723) | artifact/ChapterArtifactLayoutTest (11, incl. new SC-21 managed-directory test) | Seven managed dirs + content-addressed idempotent names + per-sidecar segment validation (review-verified) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-SC-22 | Store transactions installing per-page state keep the rejection invariant: any precondition or publication failure leaves the prior manifest authoritative with typed failure reporting | contracts-schemas-fingerprints §4 | WP1 | artifact/ChapterArtifactStore.kt (`@Synchronized` + `staleManifestRejection` idiom reused verbatim; every failure returns `TransactionOutcome.Rejected`) | CheckpointOcrTransactionTest (rejection + fault tests assert prior manifest authoritative) | Rejection invariant held across CLOSE/REBASE/adopt; rejections before or after sidecar writes leave prior manifest authoritative (review-verified) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-FP-01 | Global exclusion list: generation ids, page versions, sidecar file names, monotonic versions as validity keys, timestamps, attempt/retry counts, envelope ids, `userEditedAt`, display state, `activeCandidateGenerationIds` are never fingerprint inputs | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt (all 7 new builders exclude by construction — signature audit; `profileContentFingerprint` zeroes `version`/`frozenAtEpochMs`/`sourceRunId` + blanks `contentFingerprint` in a hashing copy only) | artifact/SemanticFingerprintTest (exclusion proofs for FP-02 and FP-05) | No builder accepts any excluded field; proven by test for FP-02/FP-05, by construction elsewhere (review signature-audit verified) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-FP-02 | `PageOcrContentFingerprint`: fixed-order pure OCR-content key (corpus schema version, pageKey, naturalPageIndex, source sha256/dims/orientation, detection fp or `SKIPPED`, ocrFingerprint, ordered blocks NFC text + `toRawBits` geometry + label, textless, ordered mask boxes + `inpaintMaskRevision`); excludes translation, `userEditedAt`, colors, score, versions, file names | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `pageOcrContentFingerprint` + `pageOcrContentBlocks` (8-allowed-field mapping); facade ChapterTranslationStore.kt `pageOcrContentFingerprint` | SemanticFingerprintTest (user-edit/translation/color/score exclusion; NFC/CRLF normalization; delimiter-forgery fixtures) | Builder landed (`5f66174`); consumes the persisted `ocrFingerprint` value per ratified FP I-1; user edits provably do not change the key; facade pageKey pinned per F-1 | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-FP-03 | `OcrCorpusFingerprint`: corpus schema version, expected page count + trusted flag, ordered per-page content fingerprints, explicit `naturalOrderProven` field; unprovable order hashes sorted pageKey order | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `ocrCorpusFingerprint` | SemanticFingerprintTest (200-page synthetic corpus golden, byte-stable) | (pageKey, fp) pairs hashed for order-provability per ratified FP I-2; deterministic stable sort inside builder for unproven order | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-FP-04 | `ProfileInputFingerprint`: ocr corpus fp, source/target language, analysis schema + prompt versions, analyzer provider/model/credential signature, analyzer policy fp, user- and series-authority fps with explicit `ABSENT` literal (absence is a value, never an empty-string collision) | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `profileInputFingerprint` | SemanticFingerprintTest (`ABSENT` ≠ `""` ≠ absent-of-different-call; delimiter-forgery fixture per F-3) | All contract inputs hashed; authority absence encoded as explicit literal; forgery fixture landed (`5f66174`) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed | VERIFIED-EXIT |
| T924-FP-05 | `ProfileContentFingerprint`: SHA-256 over canonical re-encoded JSON (SC-10) with `version`/`frozenAtEpochMs`/`sourceRunId` excluded; identical inputs produce identical fp; monotonic `version` never sole validity key | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `profileContentFingerprint` (hashing copy over shared `ArtifactDocumentJson`; stored bytes never mutated) | SemanticFingerprintTest (version-only bump equal; fact/scene/provenance change different; decode→encode round trip equal; golden `e18be544…`) | Operational fields zeroed in hashing copy only; golden fixture byte-stable; canonical re-encode determinism proven | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-FP-06 | `TranslationProvenanceFingerprint`: profile content fp, translator signature + protocol version, prompt version, source/target language, per contributing page OCR content fp + ordered stable block ids + per-block source-text hashes actually sent; envelope policy/plan fingerprints NOT inputs (matrix row 7) | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `translationProvenanceFingerprint` + `sourceExcerptHash` (SC-09 NFC/LF-normalized SHA-256) | SemanticFingerprintTest (NFC normalization + case-sensitivity for `sourceExcerptHash`; delimiter-forgery fixture per F-3) | Envelope-policy-only change provably does not enter provenance; rolling-context/profile-subset fps recorded separately per contract | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed | VERIFIED-EXIT |
| T924-FP-07 | `LayoutCompatibilityFingerprint`: existing `StageFingerprints.layout` inputs + font asset name/sha256, typeface/style `BOLD`, paint measurement flags, layout planner version, `platformShapingKey`, stroke policy version, decode sample size, source page dims; no SSIV/pan/zoom/orientation inputs | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `layoutCompatibilityFingerprint` (first 7 params = existing `layout` input order) | SemanticFingerprintTest (17-field single-change sensitivity) | All contract additions hashed; each single change gives a different fingerprint; presentation transforms cannot be inputs (review-verified); `platformShapingKey` bucketing = open decision 7.5, fingerprint accepts supplied policy value | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-FP-08 | `ColorStyleFingerprint`: color estimator version, consumed image identity (`CLEANED_IMAGE` file + `inpaintRevision`, or `ORIGINAL_SOURCE` sha256, as an explicit kind field), per-block geometry fingerprints consumed by estimation, page dims; no font/planner-version/translated-text inputs | contracts-schemas-fingerprints §5 | WP1 | artifact/StageFingerprints.kt `colorStyleFingerprint` | SemanticFingerprintTest (cleaned identity/revision/geometry/dims sensitivity; original-source modes distinct from each other and from cleaned mode) | Explicit `DisplayBaseKind` mode field prevents mode aliasing (ratified FP I-3); color = pixels + geometry only | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-FP-09 | Determinism/equivalence gate: (a) repeated computation identical across processes for every builder; (b) FP-01 exclusions proven; (c) length-prefix forgery fixtures; (d) golden fixtures for a 200-page synthetic corpus + frozen profile stable | contracts-schemas-fingerprints §5 | WP1 | artifact/SemanticFingerprintTest.kt (the gate; goldens for profile content + 200-page corpus) | SemanticFingerprintTest (21 tests): per-builder determinism, exclusions, forgery (FP-02/04/06), NFC/CRLF, goldens | 21/21 green (stage final run 357/0); goldens single-machine — second-process/device re-run deferred to Stage-2 exit audit (review test-gap 3, ratified) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-TX-01 | `checkpointOcr` is one guarded store transaction under the held page lease: validate snapshot, CAS full write identity, install origin-neutral `PageOcrCheckpoint` pointer, close/rebase BATCH candidate, preserve committed display; only then may the caller release the lease | contracts-state-transactions §2 | WP2 | artifact/ChapterArtifactStore.kt `checkpointOcr` (`@Synchronized` + `staleManifestRejection`) + facade ChapterTranslationStore.kt `checkpointOcr`; composes `publishSidecarPointers` | artifact/CheckpointOcrTransactionTest (12 tests) | Single atomic publication landed (`1dfd7f5`); every rejection leaves prior manifest authoritative; lease release stays a strictly-later caller step | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-TX-02 | Exact CAS inputs captured from one `PageSnapshot` under the store mutex: generation, pageVersion, leaseToken, candidateGenerationId, artifactPageVersion, dependencyFingerprint, sourceIdentity, priorOcrFingerprints, canonical `PageOcrContentFingerprint` | contracts-state-transactions §2 | WP2 | facade rejection ladder ChapterTranslationStore.kt `checkpointOcr` (:983-1050); artifact-level pageVersion + snapshot-fingerprint CAS (`checkpointOcr`) | CheckpointOcrTransactionTest (stale candidate identity rejections leave prior manifest authoritative) | Full ladder enforced at both layers; ratified D3: explicit `sourceOrientation`/`sourceSha256` inputs, incomplete identity fails closed | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-02.1 | Inputs 2/3/4 (pageVersion, leaseToken, candidateGenerationId) non-null mandatory in the standard branch; input 4 null exactly when no active candidate exists (TX-03.1); no `patchPage`-style grace clause | contracts-state-transactions §2 TX-02.1 | WP2 | store ladder: null `expectedDependencyFingerprint` rejects outright (ChapterArtifactStore.kt:461-463, cites C2); facade duplicate (ChapterTranslationStore.kt:1030-1032); lease mandatory in BOTH branches (:1021) | CheckpointOcrTransactionTest (identity fencing rows); OcrCheckpointRestartReuseTest (m1_ lease-less checkpoint rejected) | No-grace fail-closed direction verified; `patchPage` grace clause not copied; lease required in standard AND adopt branches (review-verified) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-03 | CLOSE vs REBASE: CLOSE is the default (CANCELLED record + candidate cleared + checkpoint preserved); REBASE closes G and opens successor G′ (`dependencyFingerprint` = checkpoint content fingerprint) in ONE manifest publication and never mutates the closed record | contracts-state-transactions §2 | WP2 | artifact/ChapterArtifactStore.kt `OcrCheckpointMode { CLOSE, REBASE }` branches; G′ candidate swap fingerprint-seeded | CheckpointOcrTransactionTest (close installs pointer + clears candidate in one publication; rebase closes G, opens G′ seeded with checkpoint fingerprint, stale writer fenced) | CLOSE/REBASE implemented (`1dfd7f5`); REBASE publishes CANCELLED(G)+ACTIVE(G′) in one `publishSidecarPointers` call (review-verified) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-03.1 | ADOPT-COMMITTED branch (no active candidate; added per stage0-review F-1): Batch holds the lease, a committed bundle is required and input 9 must equal the committed OCR content fingerprint via the same canonical builder; publish checkpoint sidecar+pointer only; drift rejects; any active candidate present means the standard branch is required | contracts-state-transactions §2 TX-03.1 | WP2 | ChapterArtifactStore.kt adopt branch + `committedOcrContentFingerprint` (same `StageFingerprints.pageOcrContentFingerprint` builder as producer side); facade `pageOcrContentFingerprint` (pageKey pinned per F-1) | CheckpointOcrTransactionTest (adopt happy path, content-drift rejection, candidate-present rejection, no-bundle rejection); OcrCheckpointRestartReuseTest (Batch adopt-committed over reader-committed page) | Both sides canonicalize through the same builder (review-verified); content drift fails closed ("committed OCR content drift"); committed bundle byte-identical after adopt | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-TX-04 | After a committed `checkpointOcr` the immutable OCR page snapshot is owned by the manifest-reachable checkpoint pointer, not by any candidate generation; later `cancelCandidate` cannot remove it | contracts-state-transactions §2 | WP2 | `checkpointOcr` branches touch only the `ocrCheckpoints` pointer + branch-defined `ocr` record; CLOSE replaces the page `ocr` record with a generation-less READY/TEXTLESS record (snapshot retention-reachable, `cancelCandidate` idiom) | CheckpointOcrTransactionTest (exact manifest deltas per branch; committed/display untouched) | Snapshot ownership transferred to the manifest (ratified interpretation: candidate stage records stripped like `cancelCandidate`, snapshot kept reachable per TX-03 warning) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-05 | Candidate lifecycle state machine incl. new `(adopt) (none) → —` transition: checkpoint pointer installed, candidate stays null, promotion remains the only committed-writer path | contracts-state-transactions §2 | WP2 | adopt branch (candidate stays null, nothing cleared); `activeCandidateGenerationIds` invariants kept | CheckpointOcrTransactionTest (adopt tests + CLOSE/REBASE candidate transitions) | `(none)→adopt` transition implemented; at most one live generation per page preserved | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-06 | Ordering: validate → publish snapshot → install pointer + close/rebase candidate → THEN release lease; lease release is a strictly-later caller step; `PageStageLeaseTable` unchanged | contracts-state-transactions §2 | WP2 | facade never touches the lease table (reads `pageLeases` only, ChapterTranslationStore.kt:1021-1022); store/PageStageLeaseTable.kt absent from all diffs | OcrCheckpointRestartReuseTest (M1 harness order: lease → mergeOcr → checkpointOcr → releasePageStageLease) | Lease-after-publication ordering proven by M1 flow; TX-06 clause review-verified | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-07 | `checkpointOcr` MUST NOT change the committed display pointer or the committed bundle in any branch | contracts-state-transactions §2 | WP2 | no `checkpointOcr` branch writes `committed`/display pointer (ChapterArtifactStore.kt CLOSE/REBASE/adopt); adopt touches only `page.ocr` + pageVersion | CheckpointOcrTransactionTest (committed identity asserted); ChapterTranslationStorePersistenceTest (F-2: user-edited committed block survives CLOSE + adopt with bundle identity + `userEditedAt` intact); D5GlossaryAwareReuseTest extension | Committed bundle + displayState identity asserted in all branches; gate-1.5 user-edit-authority evidence produced (F-2 landed) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed | VERIFIED-EXIT |
| T924-TX-08 | On any compare rejection or publication failure: manifest stays on prior state, typed failure recorded, caller logs WARN, Batch retains ownership long enough to report within a bounded stage budget | contracts-state-transactions §2 | WP2 | ChapterTranslationStore.kt `rejectedCheckpoint` (WARN log + typed `CheckpointOcrResult.Rejected(reason)`); artifact rejections return before `publishManifestInternal` | every CheckpointOcrTransactionTest rejection asserts its reason string | Typed `Rejected(reason)` + WARN on every path; ownership bounded per existing stage-budget norm (teardown integration is S3 wiring) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-09 | API surface (PROPOSAL): one `ChapterArtifactStore` transaction + one `ChapterTranslationStore` facade method; no `PageStageLeaseTable` changes | contracts-state-transactions §2 | WP2 | facade (raw identity inputs, builds the DTO) + artifact transaction (snapshot + checkpoint, derives sidecar names internally) — ratified deviation TX D1 | API exercised by CheckpointOcrTransactionTest + OcrCheckpointRestartReuseTest | D1 facade/artifact split ratified: store layer owns `ChapterArtifactLayout` naming; contract semantics unchanged | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-10 | Reuse consumption: a fresh candidate of ANY origin seeds from the checkpoint when its content fingerprint matches current source/OCR evidence; corrupt/future-version reads quarantine non-destructively; planner evidence inputs read checkpoints | contracts-state-transactions §2 | WP2 | artifact/ChapterArtifactStore.kt `readOcrCheckpoint` (sealed `OcrCheckpointRead`); M1 restart reuse; planner-level consumption (PageWorkPlanner/BatchResumePlanner extension) deferred to S3 (review-ratified) | OcrCheckpointRestartReuseTest (3 m1_ tests: double process restart, READER_ADHOC consume post-CLOSE, Batch TX-03.1 adopt-committed, drift refusal) | M1 milestone proven: OCR → checkpoint → release → restart ×2 → reader-adhoc commit → Batch adopt-committed; store-level origin-neutral reuse only (planner claim correctly not made) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-11 | Crash-point × expected-manifest-state table B0-BX: never a checkpoint pointer + an active candidate owning the same payload, never a committed display change, never a pointer at a missing file | contracts-state-transactions §2 | WP2 | `publishSidecarPointers` composition over `AtomicChapterDocuments.publishJson` + `publishManifestInternal`; retention sweep rides `TransactionOutcome.Committed.deletedFiles` | CheckpointOcrTransactionTest fault rows (5): sidecar write fail, snapshot rename fail, manifest publish fail, manifest temp write fail, stale manifest snapshot | CheckpointOcr-intersecting B rows fault-injected; failures leave at most an orphan sidecar + prior manifest authoritative; adopt publishes one idempotent sidecar so no orphan risk (review F-7) | s1-review.md ACCEPT-WITH-FIXES; fixes landed | VERIFIED-EXIT |
| T924-TX-12 | Required fault-injection tests: `FakeChapterDocumentIo` seams at each boundary; store-level fencing (released lease, changed token/candidate generation/dependency fingerprint); origin neutrality; committed display preservation | contracts-state-transactions §2 | WP2 | artifact/CheckpointOcrTransactionTest.kt (12) + OcrCheckpointRestartReuseTest.kt (3), over `FakeChapterDocumentIo`/`FakeUniFile` fault seams | CheckpointOcrTransactionTest (12) + OcrCheckpointRestartReuseTest (3): stale token/candidate/dependency-fp fencing, lease-less rejection, drift refusal, crash rows | Fault-injection gate passed; stage final verification 48 classes / 357 tests / 0 failures post-fixes (reviewer independently reproduced 353 pre-fix) | s1-review.md ACCEPT-WITH-FIXES; fixes F-1..F-3 landed in `1dfd7f5`/`5f66174` | VERIFIED-EXIT |
| T924-FF-00 | Every T924 flag is a boolean `Preference` on `TranslationPreferences` with a `translation_`-prefixed key through the existing DI module; no new flag framework, no remote-config | feature-flags-stage-gates §1.1 | WP4, WP8 | domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt `translationBatchProfilePipeline()` :248 + `translationBatchPersistedLayout()` :255 (commit `7c301bc`) | translation/T924FeatureFlagsTest (3: flags default OFF, settable on the preference, read ON from a pre-seeded store) | Both flags landed default OFF on the existing preference mechanism; no new framework introduced | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded in implementation-sequence.md §Wave-2 review obligations | VERIFIED-EXIT |
| T924-FF-01a | Flag consulted at exactly one runtime decision point: coordinator construction inside `BatchChapterTranslator`; ON constructs `ChapterProfileBatchCoordinator`, OFF constructs `SequentialBatchCoordinator` unchanged | feature-flags-stage-gates §1.3 | WP4 | pipeline/batch/BatchChapterTranslator.kt `runBatchPass1` dispatch (single flag read; branch on `ChapterProfileBatchCoordinator.dispatchKind`); call site changed to `runBatchPass1(...)`, nothing else in the shell changed (commit `5427b35`) | pipeline/batch/OcrPreflightFlagOffMidRunTest (case 1: `dispatchKind(false) == LEGACY_SEQUENTIAL`, `dispatchKind(true) == PROFILE_PIPELINE`) | Single per-run consultation landed; flag ON/OFF branch exactly as specified. Wave-3 F3 gate (`c2705c8`): dispatch kind resolves through `profilePipelineDispatchKind(flagOn, contextualAiParity = isAi)` — flag ON + non-AI stays verbatim legacy `SequentialBatchCoordinator`, never creates a run record (R-F3a, re-gate deliberately if the flagged coordinator later serves non-AI); OFF branch byte-identical; `ProfilePipelineDispatchGateTest` 4/4 (wave3-review item 5 PASS) | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-FF-01b | Default OFF; while OFF the legacy coordinator, PROBE handoff and StreamingChunkPlanner behave byte-for-byte as at baseline | feature-flags-stage-gates §1.3 | WP4 | default `false` at domain TranslationPreferences.kt :248; OFF branch constructs SequentialBatchCoordinator with the IDENTICAL argument list (expression moved verbatim); pipeline/batch/SequentialBatchCoordinator.kt zero diff | Phase0BatchTranslationCharacterizationTest + full coexistence/* suites green UNMODIFIED (required scope 33 classes / 132 tests / 0 failures; wave-2 integration run 102 classes / 792 tests / 0 failures) | Byte-for-byte legacy proof: no argument altered, legacy path adds one pure preference read + one branch; default OFF so every existing test run exercised the OFF path | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-FF-01c | Flag OFF: runtime never REQUIRES any Stage-1+ artifact to display, translate, resume a legacy run or hydrate the reader; new sidecars write-only | feature-flags-stage-gates §1.3 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt `decideResume` `DropToLegacy` (pure decision; leaves all T924 sidecars untouched); reader layout fallback unchanged (FF-02 OFF) | OcrPreflightFlagOffMidRunTest (case 2: manifest byte-equal before/after the decision, checkpoints untouched, `committed == null` everywhere) | Flag-off resume decision proven artifact-neutral; FF-01c clause satisfied for the shell slice | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-FF-01d | First act of a flagged run persists the flag state into the run record; the flag is read ONCE per run; mid-run settings changes never re-read it | feature-flags-stage-gates §1.3 | WP4 | artifact/ChapterRunRecord.kt `RunConfigSnapshot.flagProfilePipeline` (optional field appended per SC additive rule, no version bump — orchestrator D1 fix in `5427b35`; participates in `frozenRunConfigFingerprint`: a flag flip is a config change starting a new runId) + compat `phaseCounters["flagProfilePipeline"]` key for pre-field records | OcrPreflightFlagOffMidRunTest (case 2: interrupted flagged run's record carries durable `flagProfilePipeline=1`) | D1 closed: flag frozen as a snapshot field inside the config fingerprint; single per-run read happens at the dispatch point; per-page checkpoint reuse unaffected by the flag value (FP-01 exclusion) | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-FF-01e | Flag flipped OFF while a flagged run is in flight: (1) in-flight run safely completes under its started path; (2) no new flagged run / queue-restore re-entry — finished if final displays committed, else dropped to legacy with new-path sidecars untouched; (3) never-published run simply restarts legacy | feature-flags-stage-gates §1.3 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt typed `decideResume` (`TreatAsFinished`/`DropToLegacy`/`RunFlaggedPath`); production entry = the dispatch itself | OcrPreflightFlagOffMidRunTest (cases 2-4: interrupted+flip, null-record × flag both values, COMPLETE record × flag both values) | All three cases encoded + proven; case 2a unreachable in this slice (preflight never commits final displays). F1 obligation: wire `decideResume` into the production resume path + dispatch-level flag-off-mid-run test at S5 (gap 1) | wave2-review.md ACCEPT-WITH-FIXES; F1/gap-1 recorded in implementation-sequence.md §Wave-2 review obligations | VERIFIED-EXIT |
| T924-FF-01f | Kill switch / rollback window: flag OFF always a complete rollback; default-on only after Stage-8 gates; legacy path retained 2 stable releases / 6 weeks after default-on; WP12 cleanup only after the window | feature-flags-stage-gates §1.3 | WP10-WP12 | TBD-Stage-8 | TBD-Stage-8 (rollback demonstration, gate 8.6) | NOT-YET-IMPLEMENTED — pending Stage 8; until then the OFF-default flag is the standing kill switch | | SPECIFIED |
| T924-FF-01g | Visibility: no user-visible switch during Stages 3-7; flag exists in code, settable via the preference store only (debug/InMemory in tests), so evaluation traffic is explicit | feature-flags-stage-gates §1.3 | WP4, WP11 | `SettingsTranslationScreen` untouched (no UI added in wave 2); flag code-only at domain TranslationPreferences.kt | translation/T924FeatureFlagsTest (settable/readable on the preference without any UI) | No settings surface added; clause satisfied for Stages 3-7 as specified | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-FF-02a | FF-02 dispatch points: (1) Batch-side `BatchRenderJoin` render body becomes LAYOUT_PREPARE orchestration when ON; (2) reader-side `TextLayoutCoordinator` binding prefers a valid persisted plan, else async planner | feature-flags-stage-gates §1.4 | WP9 | landed wave-3 `74302ec`: publication pipeline/batch/BatchRenderJoin.kt `publishPersistedLayout` (TX-23 fences) + rendering/LayoutPlanPublication.kt; hydration rendering/PersistedLayoutHydrator.kt + rendering/TextLayoutCoordinator.kt `hydrate` param + ui/reader/viewer/TranslationOverlayView.kt wiring; flag seam rendering/PersistedLayoutRuntime.kt | rendering/DrawPlanDtoRoundTripTest (7) + DrawPlanCompatibilityTest (14) + PersistedLayoutHydrationTest (10) | Publication + hydration landed, FF-02-gated default OFF; FF-02 OFF zero-diff (`TextLayoutPlanner.kt`, `ReaderPageImageView.kt`, both goldens). `PersistedLayoutReaderBridge` has zero production install sites — FF-02 ON without Stage-7 wiring behaves as OFF for reading (fail-safe, never wrong draw; reviewer item 6 VERIFIED); production bridge install + on-device rows owed at Stage 7 | wave3-review.md ACCEPT-WITH-FIXES (fixes landed) | VERIFIED-EXIT |
| T924-FF-02b | FF-02 OFF (or ON with missing/invalid/corrupt plan, or Manual/Auto/legacy data): reader always falls back to runtime `TextLayoutPlanner.planPage` | feature-flags-stage-gates §1.4 | WP9 | landed wave-3 `74302ec`: typed `HydratedLayout` outcomes (never partial) in rendering/PersistedLayoutHydrator.kt + caller-side typed fallback rendering/TextLayoutCoordinator.kt:100-109; gap-7 hydration-loss contract = F5 closed; rendering/TextLayoutPlanner.kt retained, ZERO diff | PersistedLayoutHydrationTest (fallback green ×4 + corrupt leg; FF-02-off parity) + DrawPlanDtoRoundTripTest (`unresolvable inputs are a typed LOSSY, never a silently partial draw`) | Gap-7 closed: lossy `rehydrate` detected by typed `Lossy` outcome (count mismatch), mandatory planner fallback on every non-usable outcome incl. bridge-null/throwing source; device Pager/Webtoon legs owed at Stage 7 | wave3-review.md ACCEPT-WITH-FIXES (fixes landed) | VERIFIED-EXIT |
| T924-FF-02c | Mid-run flip OFF: new pages publish no plans; already-published plans stay on disk, ignored by readers, invalidated by the normal matrix; no special recovery | feature-flags-stage-gates §1.4 | WP9 | TBD-Stage-7 | TBD-Stage-7 | NOT-YET-IMPLEMENTED — pending WP9/Stage 7 | | SPECIFIED |
| T924-FF-02d | DISPLAY_READY completion redefined only when all reader paths can hydrate the durable plan (gate 7.8, Pager AND Webtoon) | feature-flags-stage-gates §1.4 | WP9, WP7 | TBD-Stage-7 | gate 7.8 TBD-Stage-7 | NOT-YET-IMPLEMENTED — explicitly out of S2; moves to S7 per implementation-sequence | | SPECIFIED |
| T924-FF-02e | FF-02 rollback window same formula as T924-FF-01f; reader async-planner fallback may be simplified only after the window closes | feature-flags-stage-gates §1.4 | WP11, WP12 | TBD-Stage-8 | TBD-Stage-8 | NOT-YET-IMPLEMENTED — pending Stage 8 | | SPECIFIED |
| T924-FF-10 | Flags persist in the preference store; queue restore must NOT auto-start a flagged run; flag state for an interrupted run is re-derived from the run record, not queue entry contents | feature-flags-stage-gates §1.5 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt (executes only via `runPass1` reached through `BatchChapterTranslator` admission; `decideResume` signature carries no queue input) | OcrPreflightFlagOffMidRunTest (case 5: construction + decision only — zero OCR, no `activeRun` pointer, no checkpoints, no lease) | No-auto-start proven; flag provenance re-derived from run record + current flag only. Wave-3 gap-2 (`c2705c8`): `ChapterTranslatorQueueRestoreTest` +199 — real interrupted flagged pass (durable record + p1 checkpoint), restore-merge keeps the entry, `decideResume` OFF→`DropToLegacy` / ON→`RunFlaggedPath` as decision only, no side effects (byte-equal manifest, no leases); F1/gap-1 production `restoreQueue` wiring still owed at S5 | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-ST-01.5 | A phase transition publishes `{lastCompleted = P, activePhase = P+1}` in ONE pointer move; no durable "P+1 running" write before P+1's first sidecar other than this pointer | contracts-state-transactions ST-01.5 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt RUN_SNAPSHOT + OCR_PLAN transitions via artifact/ChapterArtifactStore.kt `publishActiveRun` (separate M1/M2 publications; per-page counters ride best-effort advisory publications, ST-06) | pipeline/batch/OcrPreflightCoordinatorTest (4: happy-path publications + mid-run death/restart re-entry) | One-pointer-publication-per-transition held; after each committed publication the facade manifest snapshot is refreshed so the next whole-manifest CAS stays valid; R1 (per-page counter publication cost) owed to gates 3.4/3.6 measurement | wave2-review.md ACCEPT-WITH-FIXES; R1 recorded | VERIFIED-EXIT |
| T924-ST-03 | RUN_SNAPSHOT freezes the complete run configuration with a content fingerprint at run start; all later phases validate against it | contracts-state-transactions ST-03 | WP4 | artifact/ChapterRunRecord.kt `frozenConfig` + `frozenRunConfigFingerprint` (length-prefixed SHA-256 over canonical config JSON) + `orderedSourceDigest`; builder `ChapterProfileBatchCoordinator.frozenRunConfig` (commit `5427b35`) | OcrPreflightCoordinatorTest (frozenConfig + fingerprint + hex digests asserted on the happy path) | Freeze landed; flag now inside the frozen config per the D1 fix (flag flip = new runId); FF-01d satisfied through this row | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-ST-03.1 | Freeze semantics: settings changes apply NEXT run; a resume never silently adopts new settings into an existing frozen configuration; the run continues under its fingerprint or is ended | contracts-state-transactions ST-03.1 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt runId selection (fingerprint match continues the recorded runId; mismatch starts a new run id) | OcrPreflightCoordinatorTest (case 2: simulated process restart mid-preflight — same runId, only remaining pages re-OCRed) | Freeze semantics proven across process restart; adoption-by-resume impossible through the fingerprint gate | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-ST-05 | OCR_PLAN persists nothing; the plan is a pure recomputable function of source identities + store state; only the phase-transition publication is durable | contracts-state-transactions ST-05 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt (plan recomputed in-memory from ordered pages + store state) | OcrPreflightCoordinatorTest (restart recomputes the plan; happy path publishes only the transition) | No plan sidecar written; recomputability proven by the restart case | wave2-review.md ACCEPT-WITH-FIXES; obligations recorded | VERIFIED-EXIT |
| T924-ST-06 | OCR_PREFLIGHT serial loop: for each required page in natural order, one at a time — lease → decode/OCR → `checkpointOcr` → release; any unresolved OCR/persistence failure stops the phase before paid analysis | contracts-state-transactions ST-06 | WP4 | pipeline/batch/ChapterProfileBatchCoordinator.kt `runPass1` / `checkpointPage` / `reusableCheckpointFingerprint` (reuse skip gated on usable checkpoint + source-sha equality, UNKNOWN re-runs fail-closed; native handoff + batch lease released per page in `finally` AFTER the checkpoint CLOSE attempt; `yield()` between pages; checkpoint REJECTED or worker exception = FAILED stop, ST-06 terminal) (commit `5427b35`) | OcrPreflightCoordinatorTest (4: serial `maxInFlight == 1` + per-page handoff/lease release, 3 origin-neutral checkpoints, outcome PAUSED with STOP_REASON, counters, corpus fingerprint equals the `StageFingerprints.ocrCorpusFingerprint` oracle, pages stay render-PENDING; mid-run death; changed-source re-OCR; empty no-op) | Serial preflight shell landed; terminal PAUSED stop-not-finished (reconciler keeps OCR-ready pages pending, no completion redefinition, no committed display touched); `ocrCorpusFingerprint` stored at completion (FP-03 consumed). R2+gap-9 DONE wave-3 (`2cc209e`): durable failure ledger wired (constructor recorder + default writer mirroring `persistDurableStageFailure`; REJECTED + worker-exception triggers; post-teardown write ordering; cap 3 = INTERRUPTED manual-retry vocabulary; best-effort per ST-06) + `OcrPreflightRejectedMidRunDurabilityTest` 2/2 incl. 4 restart cycles; `74302ec` generation-less failure records (store fix, `cancelCandidate` can no longer strip them); review fix `a56f232` checkpoint success clears the stale OCR ledger entry (F-W3-1) | wave2-review.md ACCEPT-WITH-FIXES; wave3-review.md ACCEPT-WITH-FIXES (fixes landed, items 1-3 SAFE/HONEST) | VERIFIED-EXIT |

## Usage at stage exit

For each stage exit, fill `Accepted result` and `Reviewer` for every row whose
work package closes in that stage, set `Status` to IMPLEMENTED (code + test
landed) or VERIFIED-EXIT (reviewer verdict recorded), and append a dated note
below with finish commit, deviations and rollback state (per T924-R024,
T924-R025). Rows in NEEDS-DIRECTOR-DECISION or MEASUREMENT-GATED status block
their named work package's exit until resolved.

## Stage exit log

### Stage 1 — 2026-09-05 — COMPLETE, VERIFIED-EXIT

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  base `adbe643`, HEAD `1dfd7f5`; commits `732f7ff` (T924-R012), `bc94045`
  (WP1 schemas/publication), `5f66174` (semantic fingerprints), `1dfd7f5`
  (`checkpointOcr` + review fixes). Working tree clean. Not merged to main,
  no pushes. One rollback point per commit.
- Rows closed VERIFIED-EXIT: T924-R012; T924-SC-01..06, SC-08, SC-12, SC-13,
  SC-17, SC-19..SC-22; T924-FP-01..FP-09; T924-TX-01..TX-12 (TX-02.1 and
  TX-03.1 rows added per stage0-review F-1, contract §2).
- Review: `evidence/stage1/s1-review.md` — ACCEPT-WITH-FIXES. Fixes landed:
  F-1 (facade content-fingerprint pageKey pin, `ChapterTranslationStore.kt`),
  F-2 (gate-1.5 user-edit-authority tests), F-3 (delimiter-forgery fixtures
  for FP-04/FP-06). All 15 implementer deviations ratified (WP1-1..7,
  FP I-1..I-3, TX D1..D3, gate naming F-4). Director safety rules 1-7 PASS;
  R012 `SinglePageOnnxPhase` evidence-wiring deferral ruled SAFE.
- Verification: orchestrator post-fix run 48 classes / 357 tests /
  0 failures / 0 skipped (`:app:compileStandardDebugKotlin` + targeted
  `testStandardDebugUnitTest` suites); reviewer-independent rerun 353/0
  pre-fix. Gate oracle-name mapping recorded in exit report (F-4).
- Rollback state: `pipeline/**`, `translator/**`,
  `store/PageStageLeaseTable.kt` untouched; no feature flag touched;
  Manual/Auto non-force planning byte-identical; pre-change v2 manifests
  load under new code and future `> 3` guard preserves read-only in old
  builds (decision 7.2).
- Deliberately deferred (not gaps): R012 evidence-provider wiring into
  `SinglePageOnnxPhase` and TX-10 planner-level checkpoint consumption (both
  S3); FP-09 golden second-machine re-run (Stage-2 exit audit); CANCELLED
  generation-record retention = sweep (`cancelCandidate` semantics, ratified
  TX D2); hex-format locale pinning (pre-existing, separate L1 if wanted).

### Wave 2 (S2 WP8 slice / S3 WP4 shell / S4 pure planners) — 2026-09-05 — S4 COMPLETE-PENDING-F2-F7; S2/S3 progress records only

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  wave-2 commits `7c301bc` (FF-01/FF-02 flag accessors, default OFF),
  `1d3fcb1` (S2/WP8 layout projection + fingerprint), `73f6933` (S4 pure
  planners), `5427b35` (S3/WP4 coordinator shell + D1 fix). Orchestrator-owned
  commits; wave-2 integration run 102 classes / 792 tests / 0 failures,
  independently reproduced by the reviewer.
- Rows closed VERIFIED-EXIT: T924-FF-00, FF-01a, FF-01b, FF-01c, FF-01d,
  FF-01e, FF-01g, FF-10; T924-ST-01.5, ST-03, ST-03.1, ST-05, ST-06;
  T924-R037 (WP8 scope; WP9 items noted in the row). Rows moved to
  IMPLEMENTED (pure planner half, later-WP halves noted in-row): T924-R004,
  T924-R008, T924-INV-01, T924-INV-03. Rows appended SPECIFIED (explicitly
  NOT-YET-IMPLEMENTED, pending their stages): T924-FF-01f, FF-02a, FF-02b,
  FF-02c, FF-02d, FF-02e.
- FP consumption recorded without editing Stage-1 rows: T924-FP-07 consumed
  via rendering/DrawPlanFingerprint.kt (thinness test-pinned over
  `StageFingerprints.layoutCompatibilityFingerprint`); T924-FP-08 estimator
  version input via `RenderColorEstimator.COLOR_ESTIMATOR_VERSION = 1`;
  T924-FP-03 consumed by the coordinator (`ocrCorpusFingerprint` at preflight
  completion) and by all three planners.
- Review: `evidence/wave2-review.md` ACCEPT-WITH-FIXES, no blocker/major;
  obligations transcribed in `implementation-sequence.md` §Wave-2 review
  obligations (F1-F3, F5, gaps 1-9, R1/R2, D4) and binding on the named
  consuming-stage kickoffs.
- Rollback state: FF-01/FF-02 default OFF; FF-01 OFF path byte-for-byte legacy
  (`SequentialBatchCoordinator.kt` untouched); `TextLayoutPlanner.kt` zero
  diff; `artifact/**` untouched except the additive optional
  `RunConfigSnapshot.flagProfilePipeline` field (no version bump).
- Open at wave-2 close: S3 remainder (R2 durable failure-ledger wiring,
  gap-9 test, device gates 3.4/3.6 incl. R1 counter-cost measurement; R013
  sparse rekey per implementation-sequence §S3); S5-owned F1/F2/F3/D4 and
  gaps 1-5/8; WP9-owned gaps 6-7 + gate-7.1/7.2 oracle files; F7
  analysis-chunk golden fixture in flight via a separate fix; S4 deferred
  sceneRefs/profileSubsetRefs and the WP5 `PlannedAnalysisChunk` mapping.

### Wave 3 (S5 preconditions / S3 remainder / WP9) — 2026-09-06 — ACCEPT-WITH-FIXES, fixes landed; S2 + S3 exits COMPLETE-PENDING-DEVICE-GATES

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  base `ee858f9`, wave-3 commits `c2705c8` (S5 preconditions: F2 hasher
  consolidation with goldens unmodified, F3 non-AI dispatch gate, gap-2
  queue-restore test, gap-4 `AnalysisChunkMapping`), `2cc209e` (S3 durable
  failure ledger R2 + gap-9 test), `74302ec` (WP9 layout publication +
  hydration, gap-6 JVM mechanism, gap-7 hydration-loss contract + the
  orchestrator generation-less ledger fix in `ChapterArtifactStore.kt`),
  `a56f232` (review fix F-W3-1: checkpoint success clears the stale OCR
  ledger entry + comment). Final verification 834 tests / 0 failures;
  reviewer independent reproduction 833/0 pre-fix.
- Rows closed/updated this wave: T924-ST-06 (R2+gap-9 DONE); T924-R004 +
  T924-R008 + T924-SC-08 (F2 + gap-4); T924-FF-01a (F3); T924-FF-10
  (gap-2); T924-FF-02a, FF-02b, T924-R038, T924-R039, T924-R040 (WP9 JVM
  legs; device legs owed at Stage 7); T924-R037 WP9-remains note closed.
- T924-TX-23 has no Stage-0 catalog row; consumption recorded here:
  the `publishPersistedLayout` CAS precondition set landed in `74302ec`
  (authority / artifact page-version / candidate generation / dependency
  fingerprint / OCR block identity fences + content-addressed sidecar names
  + compat fp + ONE atomic `publishSidecarPointers` publication; reviewer
  §4 completeness PASS).
- Review: `evidence/wave3-review.md` ACCEPT-WITH-FIXES — no blocker/major;
  F-W3-1 fixed in `a56f232`; F-W3-3 (split Plan trees) closed by the
  workspace consolidation into this canonical tree (2026-09-06);
  F-W3-2 (LOW comment reword, `ChapterProfileBatchCoordinator.kt:240-242`)
  and F-W3-4/5/6 NOTEs recorded.
- Still OWED (explicitly NOT marked done): F1/gap-1 `decideResume`
  production wiring; gap-3 request-builder core-then-context pin + schemas
  §1.3 note; D4 real model identity; bridge production install + all
  wp9-report §6 device rows incl. the gap-6 digest hex value; gates
  3.4/3.6 device measurement incl. R1 counter-cost measurement.
- Exits written: `evidence/stage3/exit-report.md`,
  `evidence/stage2/exit-report.md` (both COMPLETE-PENDING-DEVICE-GATES);
  `evidence/stage4/exit-report.md` verdict COMPLETE (F2 landed).

### Wave 4 (S5 slice A: analysis provider / chunk persistence / Batch 15-RPM sublimit + review + fixes) — 2026-09-06 — ACCEPT-WITH-FIXES, all fixes landed; slice B (profile reconcile/freeze) NEXT

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  wave-4 commits `66fda24` "feat(translation): T924-S5 slice A analysis
  provider + chunk persistence + Batch 15-RPM sublimit" (16 files,
  +3583/−47; parent `a56f232`) and `f1cdde6` "fix(translation): T924 wave-4
  review fixes — stale-prefix gate, shared sublimit gate, durable coverage,
  validator tightening" (8 files, +183/−10). Code paths below relative to
  `app/src/main/java/eu/kanade/translation/`, tests to
  `app/src/test/java/eu/kanade/translation/`.
- Slice A deliverables (commit `66fda24`):
  1. `translator/analysis/` client — `AnalysisWire.kt`,
     `AnalysisRequestBuilder.kt`, `AnalysisResponseValidator.kt`,
     `AnalysisChunkExecutor.kt`: typed protocol T924-AP-01..08 (`promptText`
     forbidden), V1-V9 validation incl. V8 evidence-hash real recompute,
     refusal-first, chapter-only authority (series/user keys dropped and
     reported), ≤2 classified attempts with exactly-one identical reissue on
     malformed and none on refusal. Tests: `analysis/AnalysisChunkValidationTest`,
     `analysis/AnalysisRequestOrderTest` (core-then-context order pin,
     gap-3/F4 discharged).
  2. `pipeline/batch/AnalysisChunkPublication.kt` — one validated chunk = one
     `publishSidecarPointers` transaction (SC-20): content-addressed
     `analysis/f-<sha>.json` sidecar first, then one atomic `analysisChunks`
     pointer append in chunk-ordinal order (ST-08 resume = persisted-prefix
     skip). Test: `pipeline/batch/AnalysisChunkPublicationTest`.
  3. `pipeline/batch/ChapterProfileBatchCoordinator.kt` — ANALYSIS_PLAN →
     ANALYSIS_CHUNKS only after the COMPLETE preflight barrier; corpus
     re-derived from durable checkpoints, fingerprint drift = typed PAUSED;
     MISSING_ONLY commits the empty-valid subset (DR-A Option 1);
     runner==null = typed CONFIGURATION pause (no transport in this slice —
     deviation RATIFIED); terminal stays PAUSED, `decideResume`/COMPLETE
     untouched (wave-2 F1 honored). Test:
     `pipeline/batch/ChapterAnalysisPhaseCoordinatorTest`
     (+ `OcrPreflightCoordinatorTest`,
     `OcrPreflightRejectedMidRunDurabilityTest` updated for the gate).
  4. `translator/ProviderRequestGovernor.kt` — BatchProviderSublimit
     (15 RPM rolling 60 s, credential-wide model-agnostic key,
     minimumSpacingMs=0, maxInFlight=1, RPM-only v1) + nested
     BatchRequestSublimitGate, all-or-nothing admission; interactive requests
     structurally skip the gate. Test:
     `translator/ProviderGovernorBatchSublimitTest`.
  5. `pipeline/batch/BatchChapterTranslator.kt:653-689` — run snapshot freezes
     real provider/model identity `<engine>:<model>` + 16-hex one-way
     credential signature (LM Studio: base URL; else API key) — owed item D4
     discharged (wave-2 review obligation).
- Review: `evidence/wave4-review.md` — ACCEPT-WITH-FIXES. Both
  CRITICAL-direction checks passed: V8 is a real recompute (not an echo
  check) and `release(usage=null)` does NOT refund the rolling-window
  reservation, so the 15-RPM sublimit is real. All four §6 deviations
  RATIFIED (no transport / no corpus sidecar / typed-counter failures /
  4-line sidecar-name delegate); §7 risks RATIFIED with extensions.
- Wave-4 findings and fixes (ALL fixed in `f1cdde6`):
  - F-W4-1 MEDIUM — FIXED: `validatePersistedPrefix` identity validation
    (chunkId + corePageKeys + contributingCorpusFingerprint per ordinal;
    unreadable sidecar or longer-than-plan prefix = mismatch) → typed PAUSED
    "analysis prefix stale" (`ChapterProfileBatchCoordinator.kt`);
    regression test `cross-run corpus change pauses typed instead of
    skipping a stale chunk prefix` (`ChapterAnalysisPhaseCoordinatorTest`).
  - F-W4-2 LOW — FIXED: `SharedBatchRequestSublimitGate` singleton;
    `AnalysisChunkExecutor` defaults to it (`ProviderRequestGovernor.kt`,
    `analysis/AnalysisChunkExecutor.kt`) — no split 15-RPM pools at wiring
    time.
  - F-W4-3 LOW — FIXED: additive durable `AnalysisChunkResult.coverage`
    (COMPLETE/MISSING_ONLY, defaulted field — no version bump;
    `artifact/AnalysisChunkResult.kt`, persisted via
    `AnalysisChunkPublication.kt`) so slice-B reconcile treats MISSING_ONLY
    as pending.
  - F-W4-4 LOW — FIXED: evidence-hash regex `^e:([0-9a-f]{16})$` (bare
    16-hex rejected) + missing term kind is V3-fatal
    (`analysis/AnalysisResponseValidator.kt`); two new fatal-path tests
    (`analysis/AnalysisChunkValidationTest`).
  - F-W4-5 NOTE — FIXED: dead type-prefixed `knownIds` membership in
    relationship V1 removed; resolution = existing canon + pending ids
    (`analysis/AnalysisResponseValidator.kt`).
- Verification: at `66fda24` targeted 4-package suites (`translator.*`,
  `pipeline.batch.*`, `coexistence.*`, `artifact.*`) 545/0 — orchestrator
  AND reviewer independently (reviewer JUnit XML tally classes=76 tests=545
  failures=0 errors=0 skipped=0); full `eu.kanade.translation.*` tree
  1664/0. After `f1cdde6`: targeted 548/0 (545 + 3 new tests); full tree
  1667/0.
- Consumption recorded here without editing catalog rows (wave-3
  precedent): T924-R004 validated-persistence half landed (hierarchical
  reconciliation remains slice B); T924-R011 BatchProviderSublimit landed
  (RPM-only v1; shared-limit integration under real transport owed at
  provider-package wiring); T924-SC-20/ST-08 exercised by
  `AnalysisChunkPublication` + the now identity-validated prefix skip;
  T924-ST-10/TX-22 untouched (profile freeze = slice B).
- Still OWED after wave 4 (explicitly NOT marked done): slice B — profile
  reconcile/freeze (`ProfileReconciler`/`ProfileFreezer`, coverage-aware
  reconcile consuming F-W4-3's coverage field, freeze publication
  ST-10/TX-22, skip rules R035, DB-10 bypass instrumented-disabled); 
  provider-package transport wiring under the SHARED governor + gate
  instances (F-W4-2 acceptance condition); `providerKey` `lmstudio` vs
  governor `lm_studio` spelling alignment before any code compares the two
  (wave-4 review §6); wave-2 F1 `decideResume` production wiring (slice
  publishes zero COMPLETE runs); device gates unchanged from wave 3.
- Rollback state: FF-01 default OFF unchanged; no production transport
  (runner==null typed CONFIGURATION pause — production never persists an
  analysis chunk in this slice); Manual/Auto untouched; committed display
  untouched; only additive schema change is the defaulted
  `AnalysisChunkResult.coverage` field.

### Wave 5 (S5 slice B: profile reconcile / freeze / zero-OCR frozen-profile reuse + review + fixes) — 2026-09-06 — ACCEPT-WITH-FIXES, all fixes landed; Stage 5 COMPLETE pending provider-package transport wiring

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  wave-5 commits `d08bfad` "feat(translation): T924-S5 slice B profile
  reconcile + freeze (ST-09/ST-10, TX-22) with zero-OCR frozen-profile
  reuse" (10 files, +2209/−29; parent `f1cdde6`) and `28a75c5` "fix
  (translation): T924 wave-5 review fixes — oversized-alias drop +
  post-demotion participant remap" (4 files, +101/−11). Code paths below
  relative to `app/src/main/java/eu/kanade/translation/`, tests to
  `app/src/test/java/eu/kanade/translation/`.
- Slice B deliverables (commit `d08bfad`):
  1. `translator/contextual/ProfileReconciler.kt` — pure deterministic
     reconcile of the persisted `AnalysisChunkResult` set into
     `ChapterTranslationProfile` content: MISSING_ONLY chunks excluded from
     canon (pending-never-canon, wave-4 F-W4-3); conflicts retained as
     CONFLICTING `unresolvedFacts` (§6.3), never averaged; chapter-only
     scope, zero series promotion (§6.4); documented sort keys + `f-`/`s-`
     id assignment; validate-every-chunk-record gate (invalid record /
     ordinal gap / non-VALID ⇒ typed `Rejected`); bound-safe demotion.
     Test: `translator/contextual/ProfileReconcilerTest`.
  2. `pipeline/batch/ProfileFreezePublication.kt` — T924-TX-22 ONE
     `publishSidecarPointers` transaction: FP-05 recomputed-and-verified
     before any byte; version monotonic per chapter; content-addressed
     `profiles/f-<sha>.json` sidecar first + manifest `ProfilePointer` in
     the same M2; supersede = new file + new pointer;
     `readReusableFrozenProfile` = five-gate reuse read (ST-30
     absent-never-partially-trusted). Test:
     `pipeline/batch/ProfileFreezePublicationTest`.
  3. `pipeline/batch/ChapterProfileBatchCoordinator.kt` — ST-05/OCR_PLAN
     skip rule (:114) at run start: a compatible frozen profile (every
     checkpoint revalidated against the CURRENT source sha, ST-04; one
     changed page kills reuse) ⇒ the entire run through analysis is
     skipped with ZERO OCR + ZERO provider calls, terminal PAUSED
     `PROFILE_FROZEN_REUSE_REASON` (T924 fast-feedback core). After
     complete chunks: PROFILE_RECONCILE record → reconcile over the
     durable chunk list (re-read from the manifest; unreadable/invalid =
     typed pause) → freeze → PROFILE_FROZEN record → PAUSED
     `PROFILE_FROZEN_STOP_REASON`. COMPLETE + `decideResume` untouched
     (wave-2 F1). FP-04 computed with the same policy-fingerprint helper +
     provenance constants as the analysis identity — freeze-time and
     reuse-time identity equal by construction. Test:
     `pipeline/batch/ChapterProfileFreezeCoordinatorTest`.
  4. Tests (+30): reconciler determinism/conflict/exclusion/no-promotion/
     ordering/gate; freeze one-transaction/FP-05-mismatch/version/
     supersede/unfrozen-reads; coordinator full-pass freeze,
     resume-after-freeze skips everything (zero re-OCR + zero chunk
     executions, `profileReused=1`), FP-04 invalidation on target-language
     change re-freezes v2, corrupt-sidecar heal via byte-identical
     content-addressed re-freeze (ST-10 crash-b); FP-05 golden fixture
     `app/src/test/resources/t924/golden/t924-profile-golden-v1.json`
     (self-verifying hash `345def24…f63386`; version-only bump ⇒ SAME
     fingerprint) via `artifact/ProfileContentFingerprintGoldenTest`.
  5. Flagged (reviewer-RATIFIED): 4-line additive
     `ChapterArtifactStore.profileSidecarName` accessor
     (`ChapterArtifactLayout.profileFile` convention pre-existed).
- Review: `evidence/wave5-review.md` — ACCEPT-WITH-FIXES. Reviewer
  independent rerun 578/0 matched the implementer exactly. Both
  CRITICAL-direction checks passed: the reuse probe runs BEFORE the OCR
  loop, revalidates every durable checkpoint against the CURRENT source
  sha (ST-04) and returns before the runner seam is ever touched — zero
  provider calls by control flow, proven by recording fakes at the real
  seams; the FP-05 golden is genuinely self-verifying. All 7 deviations
  RATIFIED.
- Wave-5 findings and fixes (ALL fixed in `28a75c5`):
  - F-W5-1 MEDIUM — FIXED: `boundedAliases` lacked a length filter — an
    overlong entity alias (slice-A validator had no per-item entity-list
    cap) persisted VALID and deterministically wedged the freeze as
    PERSISTENCE_REJECTED on every resume. Fix: alias > `MAX_NAME_CHARS`
    (post-NFC) dropped with an "oversized alias dropped" note
    (`translator/contextual/ProfileReconciler.kt`); the validator now
    V4-rejects overlong `sourceNames`/`titles` items
    (`analysis/AnalysisResponseValidator.kt`) — the source gap closed.
  - F-W5-2 LOW — FIXED: scene participant remap was computed over
    PRE-demotion fact types — could point at a demoted NARRATIVE_STATE
    fact. Fix: remap keys on the FINAL post-demotion ENTITY_IDENTITY
    facts; unresolvable participants drop (§1.4)
    (`translator/contextual/ProfileReconciler.kt`).
  - Reviewer note carried for the completion stage: an all-MISSING_ONLY
    chunk set freezes a VALID EMPTY profile (contract-consistent, pinned
    by tests) — the completion stage should be aware when wiring
    skip-to-FINALIZE.
- Verification: at `d08bfad` targeted suites 578/0 — implementer,
  orchestrator AND reviewer independently (JUnit XML tally tests=578
  failures=0); full `eu.kanade.translation.*` tree 1697/0. After
  `28a75c5`: targeted 581/0; full tree 1700/0.
- Consumption recorded here without editing catalog rows (wave-3/4
  precedent): the slice-B obligations from the wave-4 ledger are LANDED in
  `d08bfad` (profile reconcile/freeze, coverage-aware reconcile consuming
  F-W4-3's `coverage` field, ST-10/TX-22 freeze publication, R035
  compatible-frozen-profile skip rule); T924-INV-09 freeze-immutability
  mechanism landed (version-monotonic supersede = new file + new pointer,
  prior bytes pinned untouched); T924-R035's frozen-profile clause landed
  (the small-chapter threshold stays MEASUREMENT-GATED); T924-R004's
  hierarchical-reconciliation half is now satisfied at chapter scope.
- Still OWED after wave 5 (explicitly NOT marked done): provider-package
  transport wiring — now THREE acceptance conditions (shared
  `SharedProviderRequestGovernor.instance` + `SharedBatchRequestSublimitGate.instance`
  wiring; `lmstudio` vs `lm_studio` spelling alignment; F-W5-1
  validator-side entity-list caps — the third already LANDED in `28a75c5`,
  carried as a condition, not a gap); DB-10 small-chapter bypass
  instrumented-disabled (unchanged); gates 4.1-4.8 incl. ≥1 real-provider
  evaluation ≥95% structured acceptance; wave-2 F1 `decideResume`
  production wiring (owed to S6, the first COMPLETE-publishing stage);
  device gates unchanged from wave 3.
- Rollback state: FF-01 default OFF unchanged; the reuse-skip path is
  read-only until it pauses (no OCR, no provider calls, no writes before
  the terminal record); freeze touches only the profile sidecar, the
  manifest profile pointer and run records — no page state, no display
  (TX-07 by construction); committed display untouched; zero schema-version
  changes in this wave (all profile DTO bounds pre-existing from Stage 1);
  read-only surfaces reviewer-verified (`ChapterArtifactStore.kt` diff =
  exactly the flagged 4-line accessor).

### Wave 6 (S6 slice A: envelope dispatch ST-11 → ST-12 + review + fixes) — 2026-09-06 — ACCEPT, first clean accept of T924, fixes landed; slice B NEXT

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  wave-6 commits `2e99ffb` "feat(translation): T924-S6 slice A envelope
  dispatch — TX-21 revalidation, TX-20 provenance commits, DR-A retention,
  crash-resumable translation" (12 files, +2621/−27; parent `28a75c5`) and
  `f2ccb95` "fix(translation): T924 wave-6 review fixes — drift-path lease
  release + ST-11 plan-reuse pin" (2 files, +73). Code paths below relative
  to `app/src/main/java/eu/kanade/translation/`, tests to
  `app/src/test/java/eu/kanade/translation/`.
- Slice A deliverables (commit `2e99ffb`; ENVELOPE_PLAN ST-11 → TRANSLATE
  ST-12):
  1. `pipeline/batch/EnvelopePlanPublication.kt` — T924-SC-20/SC-10: the
     plan sidecar + the manifest `envelopePlan` pointer in ONE
     `publishSidecarPointers` transaction; SC-10 fingerprint
     recomputed-and-verified before any byte; byte-identical republication
     idempotent; `readValidatedPlan` = ST-30 never-partially-trusted.
     Test: `pipeline/batch/EnvelopePlanPublicationTest`.
  2. `pipeline/batch/ProfileEnvelopeExecutor.kt` — serial
     one-envelope-in-flight dispatch (T924-INV-02) riding the EXISTING
     `translateAiChunkWithAdaptiveRetry` under
     `SharedBatchRequestSublimitGate` (DR-C: one allowance for ALL Batch
     traffic). T924-TX-21 per envelope: BATCH lease reacquire (never
     preempts MANUAL); fresh-snapshot identity compare vs plan-time inputs
     (pageVersion, candidateGenerationId, dependencyFingerprint,
     artifactPageVersion, sourceFingerprint, per-block OCR fingerprints +
     source texts); user-edited blocks dropped; manual-completed pages
     skipped and never revoked; drift ⇒ deterministic suffix re-plan
     (committed history untouched); `MAX_CONSECUTIVE_REPLANS = 8` livelock
     guard. Test: `pipeline/batch/ProfileEnvelopeDispatchTest`.
  3. T924-TX-20 — FIRST legacy-merge-path touch, additive-nullable,
     reviewer-verified byte-identical for legacy:
     `TranslationStagePatch` trailing nullable `profileContentFingerprint`
     + `envelopePlanFingerprint` (`TranslationStageContracts.kt`); both-null
     fast path before any manifest read; non-null validated against the
     manifest frozen-profile/envelope-plan pointers; mismatch rejects the
     WHOLE patch before any block mutation; rejected commits never advance
     the frontier. Merge ladder in `ChapterTranslationStore.kt`; test:
     `pipeline/batch/TranslationProvenanceMergeTest`.
  4. DR-A Option 1 retention (the recorded Director default, unchanged):
     refusal ⇒ whole-response discard + TERMINAL; AMBIGUOUS_PROTOCOL ⇒
     nothing commits (`fullyCovered` = ALL planned block ids present —
     page atomicity at the executor); MISSING_ONLY ⇒ only fully-covered
     pages commit; retained pages commit BEFORE a typed pause.
  5. `pipeline/batch/ChapterProfileBatchCoordinator.kt` — PROFILE_FROZEN →
     ENVELOPE_PLAN → TRANSLATE from BOTH the freeze and the zero-OCR reuse
     paths; checkpoint adoption for block-less resumed pages
     (identity-fenced M1 lease+merge, pending pages only, failure = typed
     `CorpusDrift`); ST-11 plan reuse without republication when the
     fingerprint matches (ST-11 resume rule); translator null = typed
     CONFIGURATION pause with the plan already durable; terminal PAUSED
     TRANSLATE_STOP_REASON; COMPLETE never published (wave-2 F1 still owed
     — render is Stage 7); `decideResume` byte-untouched.
  6. `pipeline/batch/BatchChapterTranslator.kt` — the FF-01 branch passes
     ONLY the resolved contextualTranslator; the legacy OFF branch stays
     byte-identical.
  7. Tests (+16): publication round-trip/mismatch/idempotence/NotUsable;
     TX-20 legacy-null identity + stale-profile/stale-plan zero-mutation
     rejections; dispatch full-run wire ids + one-in-flight + sublimit
     15-RPM 16th-defer; TX-21 real mid-run drift suffix re-plan + user-edit
     never overwritten; no-work skip; AMBIGUOUS whole-discard; terminal
     retention; refusal; process-death resume (zero re-OCR, committed
     never re-sent).
- Review: `evidence/wave6-review.md` — ACCEPT, the first clean accept of
  T924 (no CRITICAL/HIGH; all nine dimensions pass). Reviewer-independent
  rerun 597/0 matched exactly + legacy-adjacent suites green. All 5
  deviations RATIFIED (incl. FP-06 durable home kept OWED, the TX-21.2
  checkpoint-fingerprint approximation, the checkpoint-adoption design
  addition).
- Wave-6 findings and dispositions (4 LOW/NOTE; 2 fixed in `f2ccb95`,
  2 carried):
  - F-W6-1 LOW — FIXED: the TX-21 drift/lost-page early-return paths held
    the transient BATCH lease; release now happens before both early
    returns (`pipeline/batch/ProfileEnvelopeExecutor.kt`).
  - F-W6-2 LOW — FIXED: the coordinator-level ST-11 plan-reuse branch was
    untested; coordinator-level pin added — run 1 fails terminally before
    any commit, the unchanged run 2 reuses the plan with zero republication
    (same pointer fingerprint, file mtime+bytes unchanged, zero re-OCR,
    drains) (`pipeline/batch/ProfileEnvelopeDispatchTest.kt`).
  - F-W6-3 NOTE — NOT fixed, pre-existing: store `mergeTranslationLocked`
    applies per-block and commits a page when ANY block applied even if
    others rejected — unreachable in this slice (the full M4 ladder
    rejects the whole patch first); flagged for Stage 7 layout commits
    where per-block CAS + page atomicity interact again; consider
    store-level strict mode (reject when `rejectedTargets.isNotEmpty()`).
  - F-W6-4 LOW — carried: the `lmstudio` vs `lm_studio` bucket-key
    spelling now has a SECOND site (the envelope work builder derives the
    DR-D backend from `providerKey.substringBefore(':')`); the
    provider-package alignment must cover BOTH sites (shared mapping
    helper or a backend-agnostic bucket key).
- Verification: at `2e99ffb` targeted 597/0 — implementer, orchestrator AND
  reviewer independently (baseline 581 + 16 new; JUnit XML tally
  tests=597 failures=0); full `eu.kanade.translation.*` tree 1716/0.
  After `f2ccb95`: targeted 598/0; full tree 1717/0.
- Consumption recorded here without editing catalog rows (wave-3/4/5
  precedent): T924-TX-20/INV-25's provenance-commit clause LANDED in its
  validity-critical half (stale-profile/plan identity enforced at commit;
  the FP-06 RECORDING side stays owed — see still-owed below); T924-INV-02's
  dispatch half landed (one envelope in flight at the executor; the overlap
  scheduler stays Stage 7); DR-A Option 1 flipped into code as the recorded
  Director default.
- Still OWED after wave 6 (explicitly NOT marked done): slice B —
  profile-subset prompt enrichment (`TranslationPrompts.kt`), gap-free
  `BatchContextFrontier` history, the FIRST old-vs-new A/B measurement,
  gates 5.1-5.8 full matrix; FP-06 durable provenance home + the
  reuse-invalidation matrix row 6 (retrans-validation/evidence stage);
  F-W6-3 store strict-mode consideration (Stage 7 layout commits);
  provider-package transport wiring — spelling alignment now covering
  BOTH sites, DB-10 bypass instrumented-disabled, gates 4.1-4.8 incl.
  ≥1 real-provider evaluation ≥95% structured acceptance; wave-2 F1
  `decideResume` production wiring — now expected at Stage 7's completion
  work, not S6 slice A (this slice published zero COMPLETE runs).
- Rollback state: FF-01 default OFF unchanged; OFF branch byte-identical;
  TX-20 rejections never advance the frontier and never touch the
  committed display; zero schema-version changes in this wave (the patch
  fields are additive-nullable); `ChapterArtifactStore.kt` diff is +7
  additive lines (reviewer-verified).

### Wave 7a (S6 slice B: profile-aware prompt enrichment + gap-free rolling context + review + fixes) — 2026-09-06 — ACCEPT, second clean accept of T924, fixes landed; STAGE 6 COMPLETE; Stage 7 in progress

- Branch `t924/batch-profile-pipeline` (worktree `TachiyomiAT-t924-impl`),
  wave-7a commits `65e4a25` "feat(translation): T924-S6 slice B
  profile-aware prompt enrichment + gap-free rolling context (design §7)"
  (7 files, +1835/−65) and `1ae5874` "fix(translation): T924 wave-7a
  review fixes — per-candidate context reserve + default-deny range
  fences" (3 files, +39/−8). Code paths below relative to
  `app/src/main/java/eu/kanade/translation/`, tests to
  `app/src/test/java/eu/kanade/translation/`.
- Slice B deliverables (commit `65e4a25`):
  1. `translator/contextual/ProfileSubsetMatcher.kt` — pure
     envelope-source scan for canonical forms/aliases/titles/terms;
     scene participants + same-form gender/pronoun/relationship facts;
     entity ids (`[f-N]` source → target) for alias linking; caps 24
     facts / 4 scenes in deterministic frozen-profile order; range
     fences (RANGE_SCOPED overlap, AVAILABLE_FROM ≤ envelope first
     page, chapter-wide always); CONFLICTING gender emits a
     do-not-guess note.
  2. `translator/contextual/TranslationPrompts.kt` — ADDITIVE enriched
     builders, 128/0 byte-additive (reviewer-verified):
     `profileIdentityGenderRules` (referent → profile gender → source
     evidence → singular-they, pinned order),
     `profileAwareGlossaryPrefix`, `profileAwareRollingPrefix` with the
     verbatim pronoun-marking rule (prior target-language pronouns are
     TRANSLATIONS, never canonical gender evidence). Rides the EXISTING
     `chunk.glossary`/`rollingContext` wire fields — zero provider/
     builder changes; legacy functions byte-identical (pinned by the
     legacy suites).
  3. `pipeline/batch/ProfileEnvelopeExecutor.kt` — the `frozenProfile`
     seam (null = legacy prompt shape, degraded-but-correct, pinned);
     execution-time token recompute per envelope over the ACTUAL
     payload with whole-page-boundary splits only (sequential
     sub-batches, one-in-flight structural); a single token-oversized
     page = typed pause with ZERO provider calls; bounded deterministic
     context trim (the frontier keeps full history).
  4. Observability — counters `promptShapeEnriched`/`promptShapeLegacy`,
     `profileSubsetFactsMax`, `rollingContextPagesMax`,
     `envelopeSplits` + per-envelope logcat lines — sufficient for the
     Director's flag-off/on A/B from records + logcat (with the F-W7-3
     caveat below).
  5. Tests (+15): matcher determinism/caps/fences/entity-id; prompt
     rule order + fence text; coordinator-level through the real
     planner/freeze/publication/retry stack — enriched glossary reaches
     the provider, gap-free rolling advance, whole-page split under
     real estimator pressure, oversized zero-call pause, legacy shape
     without a profile.
- Review: `evidence/wave7-review.md` — ACCEPT, the second clean accept
  of T924. Reviewer independent rerun 613/0. All 5 deviations RATIFIED;
  the wave-6 D2 timing flake did not reproduce on the reviewer run (no
  flake observed).
- Wave-7a findings and dispositions (4 LOW/NOTE; 2 fixed in `1ae5874`,
  2 documented/no-action):
  - F-W7-1 LOW — FIXED: the full-range rolling-context reserve was not
    a strict upper bound (AVAILABLE_FROM first-page shift + cap-tail
    asymmetry); the reserve is now recomputed per CANDIDATE sub-batch
    in `splitForTokenFit` (the matcher is pure and dispatch is
    sequential, so no cross-envelope ordering risk)
    (`pipeline/batch/ProfileEnvelopeExecutor.kt`).
  - F-W7-2 LOW — FIXED: `usableAt` lenient null defaults (RANGE_SCOPED
    without a range / AVAILABLE_FROM without a page were treated
    usable); both now default-DENY, with a pin test that malformed
    scoped forms still text-match yet stay excluded
    (`translator/contextual/ProfileSubsetMatcher.kt`).
  - F-W7-3 LOW — documented, NOT changed: the `promptShape*` and split
    counters count BUILT chunks and PLANNED splits, not sent requests —
    recorded as a caveat for the gate 5.7 device measurements.
  - F-W7-4 NOTE — no action: dense single-block pages trim the rolling
    pairs entirely (the source side alone exceeds the 1500-token cap);
    the frontier keeps full history; per-line pair truncation is the
    candidate remedy if the Director's A/B shows identity drift on
    dense chapters.
- Verification: at `65e4a25` targeted 613/0 — implementer, orchestrator
  AND reviewer independently (baseline 598 + 15 new; one timing flake
  of `D2ManualBatchInterleavingTest` on the orchestrator's first
  full-suite run — isolated + full rerun green); full
  `eu.kanade.translation.*` tree 1732/0. After `1ae5874`: targeted
  614/0; full tree 1733/0.
- Consumption recorded here without editing catalog rows (wave-3..6
  precedent): T924-R007's envelope clause LANDED — the capped relevant
  profile subset + range-safe scene context + gap-free history, with
  `ProfileSubsetMatcher` realizing the row's TBD-Stage-2 matcher seam
  and the prompt extension of `TranslationPrompts.kt`; T924-INV-14's
  range policy landed (RANGE_SCOPED overlap / AVAILABLE_FROM
  first-page / chapter-wide fences, default-DENY after F-W7-2);
  T924-R010's deterministic whole-page split clause landed at the
  executor (execution-time recompute, whole-page-boundary splits only,
  typed pause instead of fragments); T924-INV-04's gap-free
  rolling-history clause exercised at the coordinator level (gap-free
  rolling advance test).
- Still OWED after wave 7a (explicitly NOT marked done; STAGE 6 is
  COMPLETE — slice A + slice B): Stage 7 (in progress) — wave-2 F1
  `decideResume` production wiring at the first COMPLETE-publishing
  stage (NOW DUE in Stage 7); overlap scheduler (T924-INV-16 scheduler
  half / T924-INV-02 overlap half); render/completion with gate 7.8
  device evidence before any DISPLAY_READY activation; F-W6-3 store
  strict-mode consideration at Stage 7 layout commits; bridge
  production install + wp9-report §6 device rows; FP-06 durable
  provenance home + the reuse-invalidation matrix row 6;
  provider-package transport wiring (spelling alignment BOTH sites,
  DB-10 small-chapter bypass threshold decision at Stage 8, gates
  4.1-4.8); device gates: the FIRST old-vs-new A/B at gate 5.7 (read
  the counters per the F-W7-3 caveat) + remaining 5.1-5.8 device legs.
- Rollback state: FF-01 default OFF unchanged; OFF branch
  byte-identical; the `frozenProfile = null` seam keeps the legacy
  prompt shape (degraded-but-correct, pinned); legacy prompt functions
  byte-identical (pinned by the legacy suites); zero schema-version
  changes in this wave (128/0 byte-additive prompt builders); no
  committed-display surface touched.

### Wave 7b + 7c (Stage 7: overlap/render/finalize + F1; wave-7c: provider-package analysis transport + V8 echo fix) — 2026-09-06 — implemented SOLO (subagent provider broken), NO independent review — Stage 7 COMPLETE; APK built

- PROCESS DEVIATION, Director-ordered: the W7b implementer agent died
  mid-run (~79 min, "Model request failed"); every Agent launch after
  that failed instantly (`Model provider is not configured:
  builtin:zai-coding-plan`). Under "Okay just do everything your own"
  the orchestrator completed Stage 7 solo and implemented wave-7c solo.
  `3dd1181` and `571b9f1` therefore carry NO independent review — review
  debt logged as owed (top process debt once the provider is restored).
- Wave 7b commit `3dd1181` "feat(translation): T924-S7 inpaint overlap,
  persisted-layout install, FINALIZE/COMPLETE, F1 decideResume" (18
  files, +1763/−75) — full deliverable list in
  `evidence/stage7/progress-report.md`: OverlapScheduler (ST-13, gate-6.5
  counters, Never-rule-1 serial native lane inside the remote window,
  DR-C preserved via WindowSignallingGate wrapping), per-page
  persisted-layout publication (T924-TX-23, FF-02, evidence-fenced,
  async-planner fallback), reader bridge install behind FF-02 default OFF
  (consult-before-planner, non-Resolved always falls back),
  `runFinalizeAndComplete` (FINALIZE record → drainSerial → layout sweep
  → stranded sweep → NonCancellable flush/retention → COMPLETE), the
  T924-specific stranded predicate (legacy `hasRenderedResult` terminal
  is wrong for T924 pages), F1 `resumeCompletedOutcome` production
  wiring (COMPLETE short-circuit, OFF branch byte-identical), gate 7.8
  encoded OFF (`GATE_7_8_DISPLAY_READY_COMPLETION_ENABLED = false`).
  Bring-up fixes folded in: overlap counters moved to the FINALIZE record
  (COMPLETE records with 36 keys were silently dropped by the durable
  store's `MAX_PHASE_COUNTER_KEYS = 32` cap); missing
  `isTextlessTerminal` import; ST-12 semantic pinned (missing planned
  block = typed pause, never drained COMPLETE).
  Verified: targeted 873/0; full tree 1739/0 (one StandardLane timing
  flake under load — isolated + rerun green).
- Wave-7c commit `571b9f1` "feat(t924-w7c): engine-backed analysis
  transport, V8 echo-hash wire, providerKey spelling alignment" (12
  files, +450/−6): V8 infeasibility fixed at the REQUEST side (per-block
  `excerptHash` = 16-hex `sourceExcerptHash` prefix computed at build
  time; model echoes verbatim; validator recomputes — an LLM cannot
  compute SHA-256); `AnalysisEngineTransport` (identity triple from
  engine hooks, AP-01 framing, typed failures only, one raw attempt per
  call — admission/retry stay in `AnalysisChunkExecutor` behind the
  shared 15-RPM gate); `AiTranslator` 4 open analysis hooks (default =
  typed CONFIGURATION/TERMINAL pause) overridden by Gemini + the
  OpenAI-compatible family (DeepSeek/OpenRouter/LM Studio endpoint +
  headers; LM Studio credential = base URL, T924-FP-04 never a raw
  key); F-W6-4 providerKey engine part now from `analysisBackendId`
  (governor spelling `lm_studio`, one spelling for provenance AND
  admission keys); `analysisChunkRunner` production-wired into
  `ChapterProfileBatchCoordinator` (no more test-only seam). Verified:
  full tree 1872/0 across 255 suites (orchestrator-run only).
- Build artifact: `assembleStandardDebug` BUILD SUCCESSFUL at `571b9f1`;
  arm64-v8a APK (302 MB) + 4 ABI variants under
  `TachiyomiAT-t924-impl/app/build/outputs/apk/standard/debug/`. Flags
  compiled default-OFF — behavior-identical to main until Director
  device gates pass.
- Still OWED after waves 7b/7c: independent review of BOTH commits
  (top process debt); device gates 7.5/7.8 + bridge device rows +
  first gate-5.7 A/B (Director's verification, F-W7-3/F-W7-4 caveats);
  FP-06 durable provenance home + reuse-invalidation row 6; DB-10
  threshold (Stage 8); F-W6-3 store strict-mode reconsideration after
  the bridge is device-verified.
- Rollback state: FF-01/FF-02 default OFF unchanged; OFF branches
  byte-identical; zero schema-version changes; committed-display
  surfaces untouched; one rollback commit per slice (`3dd1181`,
  `571b9f1`).

### Device fix (legacy admission CAS terminal-poison) — 2026-09-06 — commit `73bbb0c`, 1875/0, APK reinstalled

- Director device session (flags OFF): Chapter-21 batch failed with ZERO
  provider calls — stale gate identity (pageVersion 27→50 via ungated
  writes) → translate-admission CAS reject → misclassified
  PROTOCOL/TERMINAL for the whole envelope → anchor durably
  FAILED_TERMINAL, siblings cancelled. Full chain + fix layers recorded in
  `evidence/stage7/progress-report.md` ("Device fix" section).
  `BatchWriteGateHealTest` pins the heal + the T917 fence. Legacy-lane
  finding, NOT a T924 regression; fix lives in shared gate code (both
  lanes covered). Open: enumerate the +23 ungated writer if it recurs;
  provider-busy trace misattribution during store-only stalls noted.

### Device fix 2 (retry affordance vs app restart) — 2026-09-06 — commit `aace869`, 1877/0, APK reinstalled 22:00

- T918 follow-up field defect: sheet Retry vanished after app restart.
  Three stacked post-restart defects fixed (compute default phase
  ERROR→FINISHED; projector durableStateHint fallback; MangaScreenModel
  observes durable-ERROR chapters). Details in `evidence/stage7/
  progress-report.md` ("Device fix 2"). Field note: Director's post-fix
  24-page legacy run completed success (~5.5 min, 208s provider,
  114s overlap savings).

### Device fix 3 (dead-end READY_WITH_WARNINGS) — 2026-09-06 — commit `25fe9fc`, 1880/0, APK built (install pending — USB dropped)

- Field defect: Chapter-21 run ended `PERSISTENCE_REJECTED` (163-item
  envelope provider contract failure after 436s; anchor failure-persist
  CAS-rejected by the drift writer) → ChapterTranslator nonDurable branch
  set READY_WITH_WARNINGS → badge rendered as completed, sheet
  "Ready (Warnings)", no Retry anywhere, 4/26 pages rendered.
- Director rule: READY_WITH_WARNINGS requires every expected page
  displayable. Fixes: reconciler PARTIAL-without-render = stranded/failed;
  PERSISTENCE_REJECTED resolves ERROR (nonDurable flag preserved);
  artifactStatus softener restricted to placeholder-only shortfalls;
  sheet Retry truth + FINISHED phase + MangaScreenModel surface extended
  to durable RWW. Details in `evidence/stage7/progress-report.md`
  ("Device fix 3").
- Open from the trace: 163-item/436s translate-envelope contract
  fragility; ungated drift writer (bites failure-persist path);
  page-3 native inpaint 428s contract failure + cleaned_persist failure.
