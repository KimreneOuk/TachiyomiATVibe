# Documentation Audit — T908 ride-along

Date: 2026-08-30 · Branch: `optimize_translation_finishing_page` · Mode: read-only audit (this file is the only artifact written)

Scope: docs accuracy/coverage vs the post-T909 dismantled `eu.kanade.translation` package, Plan/ hygiene, and roles/ contracts.

Method: every doc was grepped for the T909-deleted symbols (`RenderQuality`, `renderQuality`, `SharedProviderRequestAdmission`, `getActivePageKeys`, `translatorStart`, `openChapterTranslationStore`, `openActiveChapterTranslationStore`, `observeActiveStore`, `getBatchTracker`, `getCompanionImageDirForChapter`, `tryRenderStandalone`, `antiAliasInset`, `getContextualTranslator`, `ChapterTranslator.translateChapter` wrapper) against `git show HEAD:` content; every `.kt` path and test name referenced in living docs was verified with `find`/`grep`; every named class/function in `docs/architecture/batch-translation-pipeline.md` was verified in source.

Commit-ordering context: the last doc-touching commit is `73c126c` (dead-code removal; landed the RenderQuality doc fix). All 20 T909 dismantle phases (`fadf6e7`…`a26aa0b`) and the dead-import prunes (`c568eec`, `340762a`) landed AFTER it with no doc updates. Docs therefore describe the post-dead-code but PRE-dismantle package layout.

---

## 1. Inventory

### docs/ root (markdown)

| File | Size | Lines | Last touched | Subject |
|---|---|---|---|---|
| TRANSLATION_MODULE.md | 77.3 KB | 1,102 | Aug 29 (73c126c) | Translation package map, 19 memory contracts, test table |
| APK_SIZE_CLEANUP_AUDIT.md | 35.8 KB | 392 | Jul 18 | Historical APK-size audit |
| DATA_FLOW.md | 23.1 KB | 610 | Aug 29 (73c126c) | Clean-architecture data flows incl. translation pipeline |
| SOURCE_CODE_LAYOUT.md | 22.3 KB | 801 | Aug 17 | Normalized repo tree |
| MODULE_MAP.md | 20.2 KB | 443 | Aug 17 | 13-Gradle-module reference |
| TRANSLATION_REMEDIATION_PHASE_PLAN.md | 19.7 KB | 655 | Jul 18 | Historical phased remediation plan |
| ocr-engine-notes.md | 16.6 KB | 280 | Jul 18 | OCR engine research |
| ARCHITECTURE.md | 12.4 KB | 196 | Aug 17 | High-level architecture |
| build-and-install.md | 10.2 KB | 283 | Aug 18 | Build instructions |
| TRANSLATION_OVERLAY.md | 3.2 KB | 33 | Aug 17 | Reader overlay contract |
| Untitled-1.txt | 2.3 KB | — | Jul 18 | **Junk**: CLIXML build-log fragment (from a different machine path). Delete candidate. |

### docs/ subdirectories

- `architecture/` — batch-translation-pipeline.md (74 lines), translation-overlay-rendering.md (281 lines)
- `project_context/` — implementing.md, knowledge_base.md, planning.md (process guidance; zero code references)
- `experimental_notes/` — 6 research notes + README (self-described investigation artifacts)
- `superpowers/` — plans/ (7 files, Aug 19), specs/ (**empty**)
- `roles/` — only 4 of the 6 role files (see §6)

### Other READMEs (checked, not audited in depth)

Root `README.md` (5.3 KB, upstream marketing — no technical claims audited), plus READMEs in `companion_server/`, `i18n/`, `macrobenchmark/`, `overlay_lab/`, `tools/aot_corpus/`, `app/src/debug/jniLibs/arm64-v8a/`, `app/src/main/assets/models/ocr/paddle-v6-small{,/det}`.

### Plan conventions

No `Plan/README.md`. The task-folder convention lives only in AGENTS.md §"Persistent work" (`Plan/active/<YYYY-MM-DD>_T<ID>_<topic>/`, README = task contract). Six unnumbered design docs sit loose in `Plan/` root (all dated 2026-07-12).

---

## 2. Per-doc verdict table

| Doc | Verdict | Basis |
|---|---|---|
| docs/TRANSLATION_MODULE.md | **STALE-REFS + MISSING-COVERAGE** | 2 dead class names (`BatchCoordinator`, `RevisionPlanner`), 2 test-table rows for nonexistent tests, 1 renamed test, "Still ~1.65k lines" debt item now wrong (1,051), package map missing 9 subpackages added/expanded by T909 |
| docs/DATA_FLOW.md | **STALE-REFS (minor) + MISSING-COVERAGE** | Flow entry point `ChapterTranslator.translate(chapter)` does not exist; new subpackages unmentioned; RenderQuality fix from 73c126c confirmed landed (HEAD section 6 is clean) |
| docs/MODULE_MAP.md | **MISSING-COVERAGE** | translation/ subtree (line 77-90) lists only the pre-dismantle packages; pipeline/, pipeline/batch/, manager/, store/, artifact/, legacy/, batch/, webtoon/, segmentation/, remote/ absent |
| docs/SOURCE_CODE_LAYOUT.md | **MISSING-COVERAGE** | app/src/main/java tree (from line 99) omits `eu/kanade/translation/` entirely (~190 files); its root-file tree is accurate (all 15 claimed root files verified present) |
| docs/ARCHITECTURE.md | ACCURATE (thin) | Named classes verified (ChapterTranslator, TranslationManager, detector/ocr/translator/inpainting/recognition/rendering paths); intentionally high level, no dismantle coverage |
| docs/architecture/batch-translation-pipeline.md | **ACCURATE** | All 15 checked symbols exist today (`translateBatch` TranslationPipeline.kt:840, `runPass1` SequentialBatchCoordinator.kt:35, `NativeRunQuarantine`, `tryRender` (now pipeline/batch/BatchLaneWorkers.kt:223), `batchExpectedFingerprints`, `planChapter`, `parseBatch`, `ChapterGlossaryBuilder` (object, not class), etc.); only gap: no mention of the pipeline/batch/ extraction |
| docs/architecture/translation-overlay-rendering.md | **ACCURATE** | androidTest path verified: `app/src/androidTest/java/eu/kanade/translation/rendering/PageTextRendererInstrumentedTest.kt` exists |
| docs/TRANSLATION_REMEDIATION_PHASE_PLAN.md | **STALE-REFS (historical)** | References `ChapterTranslationStorePersistTest` (renamed) and `BatchOomPolicyTest` (never existed on this branch); acceptable if stamped historical |
| docs/TRANSLATION_OVERLAY.md | ACCURATE (spot check) | Overlay contract prose matches current reader behavior; no dead symbols |
| docs/APK_SIZE_CLEANUP_AUDIT.md, ocr-engine-notes.md, build-and-install.md, project_context/*, experimental_notes/* | Not fully audited | Out of audit scope; no translation-structure claims spot-checked fail |

**Primary question answered:** the "partially fixed" RenderQuality text is **fully fixed at HEAD**. `git show HEAD:docs/TRANSLATION_MODULE.md` section 6 (lines 194-201) reads "New rendered images are saved as `.rendered.png`" with no `RenderQuality.SIZE_LIMITED` / `renderQuality` / `renderedWidth` / `renderedHeight` text. All 8 audited docs have **zero** references to any T909-deleted symbol at HEAD. The T909-side concern is closed; what remains stale is the *structural* narration, not the deleted symbols.

---

## 3. Stale-reference fix list (concrete)

| # | Doc : line | Stale text | Current truth |
|---|---|---|---|
| 1 | TRANSLATION_MODULE.md : 867 | "`BatchCoordinator` owns one serialized native lane…" | No `BatchCoordinator` type exists. Interfaces are `NativeLaneWorker` / `TranslatorLaneWorker` / `RenderJoinWorker` (batch/BatchCoordinatorInterfaces.kt); the implementation is `batch/SequentialBatchCoordinator.runPass1`. |
| 2 | TRANSLATION_MODULE.md : 893 | "`RevisionPlanner` preserves reading order, emits at most 20 target IDs…" | No `RevisionPlanner` symbol anywhere. Pass-2 planning now lives in `translator/AiTranslationRetryController` (1,010 L), `translator/AiTranslationRetryPlanner`, `translator/StreamingChunkPlanner`, and `pipeline/batch/BatchLaneWorkers`. |
| 3 | TRANSLATION_MODULE.md : 1000 | Test-table row `ChapterTranslationStorePersistTest` | Renamed: `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePersistenceTest.kt`. |
| 4 | TRANSLATION_MODULE.md : 996 | Test-table row `model/ChapterTranslatedPredicateTest` | File does not exist anywhere. `isChapterTranslated` is at TranslationManager.kt:587 and **no test references it** — the documented coverage is gone, not renamed. |
| 5 | TRANSLATION_MODULE.md : 1012 | Test-table row `scheduling/TranslationLifecyclePolicyTest` | File does not exist (scheduling/ tests: AutoWindowStateTest, CancelSyncStoreWriteTest, NativeRunQuarantineTest, PreparedPageBoundaryTest, PreparedPageRuntimeBoundaryTest, RollingAutoCoordinatorTest, TranslationPipelineConcurrencyTest, TranslationStreamRegistryTest). |
| 6 | TRANSLATION_MODULE.md : 1026-1062 | "Remaining debt #1: TranslationPipeline.kt — partial split… Still ~1.65k lines… Cleanest remaining extraction — PageBitmapIO" | TranslationPipeline.kt is now 1,051 lines; the listed extraction targets already exist as pipeline/PageDecode.kt, pipeline/PageStoreWriter.kt, pipeline/CleanedPublication.kt, pipeline/EngineLane.kt, pipeline/MemoryGovernance.kt (T909 phases). Section must be rewritten or marked superseded by T909. |
| 7 | TRANSLATION_MODULE.md : 10-113 | Package map ends at data/detection/inpainting/model/ocr/recognition/rendering/runtime/scheduling/translator/util | Missing 9 subpackages: pipeline/ (+ pipeline/batch/), manager/, store/, artifact/, batch/, legacy/, webtoon/, segmentation/, remote/. translator/ list shows ~19 files vs ~45 actual (e.g. AiTranslationRetryController, ProviderRequestGovernor, ContextualRequestBuilder, StreamingChunkPlanner missing). model/ list missing 8 files. |
| 8 | DATA_FLOW.md : 201 | Flow starts "`ChapterTranslator.translate(chapter)`" | No such entry point. Actual: `TranslationManager.translateChapter(manga, chapter)` (TranslationManager.kt:419) → `ChapterTranslator.queueChapter` (ChapterTranslator.kt:477) → `translateChapterInternal` (:508). |
| 9 | TRANSLATION_REMEDIATION_PHASE_PLAN.md : 35,72,90,95,136,167 | `ChapterTranslationStorePersistTest` | Renamed to `ChapterTranslationStorePersistenceTest`. |
| 10 | TRANSLATION_REMEDIATION_PHASE_PLAN.md : 178 | `app/src/test/java/eu/kanade/translation/batch/BatchOomPolicyTest.kt` | Never exists on this branch (`BatchOomPolicy.kt` has no dedicated test). |
| 11 | TRANSLATION_MODULE.md : 1015 | `reader/ReaderPageWarmWindowTest` | Exists but at `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindowTest.kt` — shorthand is ambiguous, minor. |

---

## 4. Coverage gaps (undocumented current architecture)

### 4.1 Zero-mention components (no hit in any living doc; all extracted by T909)

Sorted by size — the most important first:

| Component | Lines | Role |
|---|---|---|
| pipeline/batch/BatchLaneWorkers.kt | 1,560 | The actual native/translation/render lane engine for batches |
| pipeline/SinglePageOnnxPhase.kt | 1,083 | Reader single-page ONNX path |
| translator/AiTranslationRetryController.kt | 1,010 | AI retry + Pass-2 control |
| scheduling/RollingAutoCoordinator.kt | 1,003 | Rolling auto-translate coordination |
| artifact/ChapterArtifactStore.kt | 998 | Durable per-chapter artifact store (was itself a dismantled god-file) |
| translator/ProviderRequestGovernor.kt | 638 | Provider request admission/budget |
| pipeline/SinglePageHttpRenderPhase.kt | 547 | HTTP-render single-page path |
| artifact/LegacyArtifactMigration.kt | 501 | Legacy → artifact migration |
| manager/ChapterDataResetController.kt | 486 | Reset/delete flows (T909 phase 19) |
| artifact/LegacyArtifactRescue.kt | 479 | Legacy artifact rescue |
| batch/TranslationBatchProgressTracker.kt | 448 | Batch progress tracking |
| recognition/VerticalLineOcr.kt | 412 | Vertical-text OCR |
| manager/BatchProgressProjector.kt | 314 | Progress projection |
| pipeline/batch/BatchRenderJoin.kt | 312 | Render join (doc names `tryRender` but never this file) |
| artifact/ChapterDocumentIo.kt | 310 | Atomic document IO |
| artifact/LegacyChapterMigrationSource.kt | 301 | Legacy migration source |

Also zero-mention: manager/TranslationRequestCoordinator (218), manager/DurableChapterStatusResolver (170), manager/ReaderTeardownCoordinator (176), manager/CleanedImageLifecycleController (213), store/PageStageLeaseTable (148), store/StorePersistenceScheduler (146), store/StoreStatusProjector (126), store/ChapterGlossaryStore (118), pipeline/EngineLane (299), pipeline/PageStoreWriter (256), pipeline/CleanedPublication (242), pipeline/PageDecode (206), pipeline/MemoryGovernance (124), pipeline/batch/BatchResumePlanner (287), pipeline/batch/BatchWriteGate (251), pipeline/batch/HeldBitmapRegistry (68), artifact/ChapterArtifactManifest (283), artifact/ArtifactContracts (212), artifact/StageFingerprints (192), artifact/ChapterArtifactDeletion (195), artifact/ArtifactRetention (114), artifact/ModelIdentityCache (113), legacy/LegacyFlatFileDecoder (80), ActiveChapterStoreRegistry (192).

Single-mention (named but not explained): SequentialBatchCoordinator, BatchContextFrontier, PageWorkPlanner, TranslationQueueStore, TranslationBatchProgressTracker — all only in the 74-line batch-translation-pipeline.md.

### 4.2 Structural narration that still describes pre-dismantle monoliths

- TRANSLATION_MODULE.md package map + "Remaining debt" (see fix items 6-7): narrates TranslationPipeline/TranslationManager/ChapterTranslationStore as the still-to-split monoliths; T909 moved 7,618 lines into 24+ components.
- MODULE_MAP.md translation/ subtree: pre-dismantle package list.
- DATA_FLOW.md §4 "Translation Managers & Coordination" (~line 316): shows only TranslationManager → ChapterTranslator → ChapterTranslationStore → TranslationSession; the entire manager/, store/, artifact/, pipeline/ layer beneath it is invisible.
- ARCHITECTURE.md §4 (line 128-160): same four names, no subpackage layer.
- batch-translation-pipeline.md is the ONLY living doc that matches current code, and even it predates the pipeline/batch/ extraction (accurate by luck of stable names).

---

## 5. Plan/ hygiene findings

- `git status --porcelain Plan/` → **clean**; every Plan file is tracked and committed.
- 31 folders under `Plan/active/` (plus 6 loose design docs in `Plan/` root). No empty folders (minimum 1 file).
- Naming convention drift: only T901-T910 follow the AGENTS.md `YYYY-MM-DD_T<ID>_<topic>` pattern; 21 legacy folders (2026-07-12 … 2026-08-24) predate it, including dateless `translation-quality/`.
- **No archive convention exists**: there is no `Plan/archive/`, `Plan/completed/`, or completion marker scheme. Completed T904-T910 all sit in `active/` indefinitely. T909 is the only task that declares completion in-place (`campaign-complete.md`, "Status: COMPLETE (manual smoke pending)").
- **Commit recording is inconsistent**: only T909 records its full commit range (`b8a0a74`…`43f8de2`, 36 commits); T908's executive-summary names `73c126c`. T904-T907, T910 READMEs record no landing commits — tracing "what did T90x change?" requires git archaeology.
- Stale/thin folders (README-only, no reports): `2026-07-17-deterministic-revision-flagging`, `2026-07-17-panel-aware-block-sorting`, `2026-08-16-extension-lifecycle-and-update-fix`, `2026-08-17-serialization-extension-abi-fix`, `2026-08-17-translation-pipeline-efficiency-audit`, `2026-08-18-webtoon-tuning`, and `2026-08-28_T907_batch-download-autostart-recovery` (README only — abandoned-looking).
- `Plan/active/2026-08-28_T908_.../discarded-worktree-snapshots/` holds ~180 KB of workspace debris (2 directories + 4 .patch files) inside a task folder — should live outside Plan/ or in git notes/stash, not a permanent task record.
- `docs/superpowers/specs/` is an empty directory; `docs/Untitled-1.txt` is CLIXML build-log junk.

---

## 6. roles/ vs AGENTS.md

AGENTS.md §Roles references role files as `roles/repository-steward.md`, `roles/product-lead.md`, `roles/technical-lead.md`, `roles/delivery-lead.md`, `roles/reviewer.md`, `roles/implementer.md`. Reality is three inconsistent locations:

1. `roles/` at repo root — **does not exist** (every AGENTS.md role-file pointer is currently broken).
2. `docs/roles/` (actual, on this branch) — only **4 of 6** files: implementer.md, repository-steward.md, reviewer.md, technical-lead.md. **Missing: product-lead.md, delivery-lead.md.**
3. Local (unmerged) branch `design_ai_org_roles` — a **complete 7-file set** under a third path, `docs/ai_org/roles/`: delivery-lead, design-lead, implementer, product-lead, repository-steward, reviewer, technical-lead. Per the T908 executive summary this branch was kept deliberately for its unique docs commits; the missing role contracts are sitting there, unlanded.

The 4 existing `docs/roles/` files match their AGENTS.md role names/purposes (reviewer.md is titled "Reviewer / Failure-mode Auditor" — consistent).

---

## 7. Recommendations (priority order)

1. Land the `design_ai_org_roles` role files (or merge them into `docs/roles/`) and fix AGENTS.md's `roles/<file>.md` pointers to the real path — currently every role-file reference in AGENTS.md is a dead link and two roles have no file on this branch at all.
2. Rewrite TRANSLATION_MODULE.md's package map + "Remaining debt" for the post-T909 layout (fix items 1-7); until then add a banner: "package map predates T909 dismantle — see Plan/active/2026-08-29_T909_god-file-dismantling/campaign-complete.md".
3. Add a short subsection (or new docs/architecture/translation-package-map.md) covering the new subpackages — highest value: pipeline/batch/BatchLaneWorkers, manager/TranslationRequestCoordinator + DurableChapterStatusResolver, store/ lease/persistence trio, artifact/ lifecycle (ChapterArtifactStore + LegacyArtifactMigration), translator/AiTranslationRetryController + ProviderRequestGovernor.
4. Re-create or formally retire the lost `isChapterTranslated` test coverage (fix item 4) — the doc claims coverage that no longer exists.
5. Plan/ hygiene pass: define an archive convention (e.g. move completed T90x folders to `Plan/archive/`, require a "landed commits:" line in each task README), decide the fate of the 21 legacy unnumbered folders and the 7 README-only stale ones, and evict `discarded-worktree-snapshots/` + `docs/Untitled-1.txt`.
