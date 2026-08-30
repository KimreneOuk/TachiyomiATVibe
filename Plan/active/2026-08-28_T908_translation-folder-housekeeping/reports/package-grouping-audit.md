# Package Grouping Audit — `eu.kanade.translation` post-T909

Date: 2026-08-30 · Branch: `optimize_translation_finishing_page` · T908
Method: read-only inventory (`wc -l`), import-graph greps, test-tree diff vs main tree.
No gradle, no source changes. Working tree was clean at audit time.

**Headline:** the dismantle did NOT leave a messy root. The 15 root files are a
deliberate facade layer (hubs + tiny helpers), and `store/` + `manager/` are
consumed *only* by their root hubs (verified by import grep). The real grouping
defects are narrower: a **dual `batch/` vs `pipeline/batch/` home**, a
**one-file `legacy/` package in a cycle with `artifact/`**, two **misfit value
types**, an **empty `presentation/` dir**, and **zero test parity for
`pipeline/` + `pipeline/batch/`**.

---

## 1. Main tree (200 files, 47,324 lines)

```
eu/kanade/translation/                          lines  files  role
├── ROOT                                       5,903     15  facade hubs + tiny helpers (see §3)
├── artifact/                                  3,932     14  artifact persistence + legacy format trio
│     ChapterArtifactStore 998, LegacyArtifactMigration 501, LegacyArtifactRescue 479,
│     ChapterArtifactManifest 283, ChapterDocumentIo 310, LegacyChapterMigrationSource 301,
│     ChapterArtifactDeletion 195, StageFingerprints 192, ArtifactContracts 212,
│     ChapterArtifactLayout 151, ArtifactRetention 114, ModelIdentityCache 113,
│     ChapterArtifactManifestReader 47, CleanedImageProbe 36
├── batch/                                     2,174     10  batch STATE/progress/diagnostics + coordinator IFs
│     SequentialBatchCoordinator 650, TranslationBatchProgressTracker 448,
│     BatchCoordinatorInterfaces 261, BatchTranslationDiagnostics 225,
│     BatchProgressReconciler 210, BatchContextFrontier 109, TranslationBatchTrackerRegistry 142,
│     BatchResumeGateDecider 49, TranslationBatchEvent 52, BatchOomPolicy 28
├── data/                                        195      2  TranslationProvider 174, TranslationFont 21
├── detection/                                   677      4  ONNX panel/text detection
│     OnnxPanelDetector 276, OnnxPageTextDetector 238, PanelAssignment 155, Detection 8
├── inpainting/                                5,576     21  AOT inpainting + bubble cleaner (cohesive)
├── legacy/                                       80      1  LegacyFlatFileDecoder 80  ← one-file package, see §5
├── manager/                                   1,577      6  TranslationManager's extracted collaborators
│     ChapterDataResetController 486, BatchProgressProjector 314, CleanedImageLifecycleController 213,
│     TranslationRequestCoordinator 218, DurableChapterStatusResolver 170, ReaderTeardownCoordinator 176
├── model/                                     2,138     17  value types/projections (cohesive)
├── ocr/                                       1,845     13  OCR engine primitives (Paddle/MlKit/DB)
├── pipeline/                                  2,759      7  single-page phases + lane/governance
│     SinglePageOnnxPhase 1083, SinglePageHttpRenderPhase 547, EngineLane 299,
│     CleanedPublication 244, PageStoreWriter 256, PageDecode 206, MemoryGovernance 124
│   └── batch/                                 3,170      6  batch EXECUTION workers
│         BatchLaneWorkers 1560, BatchChapterTranslator 692, BatchRenderJoin 312,
│         BatchResumePlanner 287, BatchWriteGate 251, HeldBitmapRegistry 68
├── presentation/                                  0      0  EMPTY directory (untracked fs clutter)
├── recognition/                               1,934      7  page-level recognition engines
│     RoiPageRecognitionEngine 951, VerticalLineOcr 412, OcrBlockDeduplication 242,
│     MlKitFullPageRecognitionEngine 194, BoxGeometry 94, ReadingOrderSorter 98,
│     PageRecognitionEngine 33 (interface)
├── remote/                                      296      4  RemotePageTranslationEngine 251, Exception 34,
│                                                           BackendPageKey 5, InferenceBackend 6
├── rendering/                                 1,947      3  TextLayoutPlanner 1378, RenderColorEstimator 316,
│                                                           PageTextRenderer 253
├── runtime/onnx/                              1,215      6  OnnxRuntimeProvider 326, OnnxModelStore 339,
│                                                           HardwareDiscoveryEngine 228, QnnDiagnostics 177, ...
├── scheduling/                                3,155      9  TranslationScheduler 1014, RollingAutoCoordinator 1003,
│                                                           TranslationStreamRegistry 328, TranslationExecutor 172, ...
├── segmentation/                                696      4  bubble segmenter + mask geometry (cohesive)
├── store/                                       538      4  chapter-store helpers (imported ONLY by root store)
│     StorePersistenceScheduler 146, PageStageLeaseTable 148, StoreStatusProjector 126,
│     ChapterGlossaryStore 118
├── translator/                                6,025     37  provider translators + retry + chunking (cohesive)
├── util/                                        943      8  TranslationMemoryBudget 495, BlockSorter 92, ...
└── webtoon/                                     549      2  SeamStitcher 297, SlidingDetector 252
```

## 2. Test tree (134 files, 22,783 lines)

Mirrored packages: root(23), artifact(9), batch(16), inpainting(17), model(13),
ocr(6), recognition(2), remote(1), rendering(7), runtime/onnx(4), scheduling(9),
segmentation(3), translator(17), util(5), webtoon(3).

**No test package exists for:** `data/`, `detection/`, `legacy/`, `manager/`,
`pipeline/`, `pipeline/batch/`, `store/` (detection/manager/store are covered
indirectly via other packages' tests; pipeline* is covered by *nothing* — see §7).

## 3. Root files — keep-or-move judgment (15 files, 5,903 lines)

| File | Lines | Judgment | Reason |
|---|---|---|---|
| TranslationPipeline.kt | 1,051 | **KEEP** | facade hub; pipeline/ + pipeline/batch/ are its extracted mechanics. Cohesive hub-and-spoke. |
| ChapterTranslationStore.kt | 2,025 | KEEP (optional move → store/) | hub of store/ (all 4 store/ files import-chain to it; nothing else imports store/). Dual-home is conceptual only — no name collision. Moving = 57 files touched (imports incl. tests). Not worth churn now. |
| TranslationManager.kt | 1,233 | **KEEP** | hub of manager/ (only importer). Same pattern as above; no ambiguity. |
| ChapterTranslator.kt | 755 | **KEEP** | queue/orchestration translator; distinct role from pipeline/batch/BatchChapterTranslator (batch executor). Naming pairing is understandable. |
| ActiveChapterStoreRegistry.kt | 192 | KEEP | registry keyed to ChapterTranslationStore lifecycle. |
| TranslationStageContracts.kt | 239 | **KEEP** | stage patch contracts consumed by root + scheduling/. |
| CleanedImagePublisher.kt | 97 | KEEP | publisher half of cleaned-image lifecycle (manager/ holds the controller half). Noted, not worth a move. |
| MemoryPressurePolicy.kt | 75 | KEEP | memory concern is spread over 5 packages (this, TranslationMemoryPressureForwarder, pipeline/MemoryGovernance, util/TranslationMemoryBudget, batch/BatchOomPolicy) but each has a distinct role; documented in §6. |
| TranslationQueueStore.kt | 77 | KEEP (optional → store/) | small prefs store; "Store" suffix home is split with store/ but no collision. |
| TranslationPendingRequestStore.kt | 62 | KEEP (optional → store/) | same as above. |
| TranslationSession.kt | 37 | KEEP (optional → model/) | pure value types (TranslationPageId/WorkKind/PageRequest/Session); model/ would be its natural home. Cosmetic. |
| ChapterResetPreflight.kt | 25 | KEEP | pairs with manager/ChapterDataResetController; tiny. |
| PostOcrStageSemantics.kt | 23 | **KEEP** | tiny stage-semantics helper. |
| PageTranslationKey.kt | 6 | KEEP | 6-line key function; util/ would work, trivial either way. |
| TranslationMemoryPressureForwarder.kt | 6 | **KEEP** | 6-line app-callback bridge, documented as such. |

**Verdict:** root is not clutter. It is a facade layer with clear
hub→subpackage dependency direction (root trio → manager/, store/, pipeline*).
No root file has a same-named twin in a subpackage; the "dual-home" risk is
naming-adjacent (ChapterTranslator vs BatchChapterTranslator), not structural.

## 4. Subpackage cohesion

| Package | Cohesion | Notes |
|---|---|---|
| artifact/ | Good | One concern (artifact persistence) *plus* 3 Legacy* files (1,281 lines). The legacy format concern thus lives in TWO packages (see §5). |
| batch/ | Mixed | = batch state/progress/diagnostics + coordinator interfaces. All live consumers: pipeline/batch/ (constructs SequentialBatchCoordinator — the ONLY constructor site), root trio, manager/BatchProgressProjector, scheduling/, and UI (presentation/manga/components/TranslationProgressSheet.kt imports batch types directly). Dependency direction is strictly pipeline.batch → batch (batch/ never imports pipeline*). Coherent as a "state vs execution" seam, but invisible from the names. |
| pipeline/batch/ | Good | Execution workers only. Zero direct unit tests. |
| data/ | Weak name, tolerable content | TranslationProvider = translation storage-dir provider (disk layout); TranslationFont = enum consumed ONLY by SettingsTranslationScreen (UI). Not "data access". Also collides transposedly with external `eu.kanade.tachiyomi.data.translation` (see §8). |
| detection/ | Good except 1 file | Onnx* detectors + PanelAssignment cohere. Detection.kt (8-line value type) does NOT belong (see §5) and creates a `model → detection` import edge plus a `detection ↔ recognition` package cycle. |
| legacy/ | Misfit | One file, 80 lines, LIVE code ("moved verbatim from TranslationManager, T909 Phase 3a" per its own KDoc). Package cycle with artifact/: legacy imports artifact.{AtomicChapterDocuments, ChapterDocumentIo, UniFileChapterDocumentIo}; artifact.{LegacyChapterMigrationSource, ChapterArtifactManifestReader} import legacy.LegacyFlatFileDecoder. Consumers: TranslationManager, manager/DurableChapterStatusResolver, artifact/×2. |
| manager/ | Good | TranslationManager's extracted collaborators only. BatchProgressProjector is arguably a batch/-concern (progress is otherwise in batch/), noted but not worth a move. |
| ocr/ vs recognition/ | Acceptable split, colliding names | ocr/ = engine primitives (Paddle/MlKit/DB post-process); recognition/ = page-level engines + geometry/order. Name collisions: `RoiOcrEngine` (ocr/, 58) vs `RoiPageRecognitionEngine` (recognition/, 951); `VerticalLineOcr` sits in recognition/. Defensible layering; do not merge. |
| remote/ | Cohesive but isolated | Zero main-source consumers outside remote/ (grep for RemotePageTranslationEngine/InferenceBackend: only its own files + its test). Unwired/dead — grouping-clean; dead-code status already tracked in T908 `reports/dead-code.md`. |
| store/ | Good | Effectively private helpers of ChapterTranslationStore (sole importer). |
| translator/ | Good | 37 files, one concern (providers + retry + chunking). Internal naming quirks in §6. |
| presentation/ | Empty dir | No files; untracked filesystem residue. Delete the directory. |

## 5. Misfit list

1. **`legacy/` package (1 file, 80 lines)** — undercooked single-file package,
   cycles with artifact/, and misleads by name: it is live migration code, not
   deletable legacy. Right home: `artifact/` (alongside LegacyArtifactMigration/
   Rescue/MigrationSource). Do NOT move artifact/Legacy* into legacy/ — that
   grows the wrong side.
2. **`batch/` + `pipeline/batch/` dual home** — 16 "Batch*" files across two
   packages. Same concern split across the seam: `BatchResumeGateDecider`
   (batch/) vs `BatchResumePlanner` (pipeline/batch/); progress tracking in
   batch/ (tracker, reconciler) vs progress projection in manager/ (projector).
   It is a coherent migration seam (state vs execution) but undiscoverable —
   nothing in the names says "batch/ = state, pipeline/batch/ = workers".
3. **`detection/Detection.kt`** — 8-line value type imported by
   model/PageTranslation.kt (a *model → leaf-package* edge), recognition/×2,
   webtoon/, +2 tests. It is a shared domain value type; belongs in `model/`.
4. **`data/` package name** — holds a storage-layout provider and a UI font
   enum; neither is "data access". Transposed-name confusion with
   `eu.kanade.tachiyomi.data.translation`.
5. **`presentation/` empty directory** — untracked residue.
6. **`pipeline/` + `pipeline/batch/` (5,929 lines)** — no test imports
   `eu.kanade.translation.pipeline*` at all (verified: zero files in
   app/src/test). Largest grouping/parity gap in the module.

## 6. Naming consistency (notable only)

- **`AITranslator` vs `Ai*`** in the SAME package (translator/): AITranslator.kt
  vs AiModelFetcher, AiTranslatorKind, AiTranslationRetryController (1,010),
  AiTranslationRetryPlanner. Casing inconsistency, nothing more.
- **Retry naming trio** in translator/: `TranslationRetry` (279, legacy
  single-page retry), `AiTranslationRetryController` (1,010),
  `AiTranslationRetryPlanner` (39) — three retry nouns, easy to grab the wrong one.
- **Resume split**: `BatchResumePlanner` (pipeline/batch/) vs
  `BatchResumeGateDecider` (batch/) — one concern, two suffixes, two packages.
- **Phase vs Stage**: pipeline/ files say `Phase` (SinglePageOnnxPhase,
  SinglePageHttpRenderPhase); everything else in the module says `Stage`
  (PageStage, OcrStagePatch, StageFingerprints, PostOcrStageSemantics).
  Two vocabularies for the same concept.
- **Store suffix is not owned by store/**: store/ has 4 Stores, root has 3,
  artifact/ChapterArtifactStore, runtime/onnx/OnnxModelStore,
  pipeline/PageStoreWriter. Consistent-per-role usage, but store/ the package ≠
  all Stores. Accepted pattern; noted for expectations.
- Consistent elsewhere: Planner (PageWorkPlanner, PageInpaintingPlanner,
  TextLayoutPlanner, StreamingChunkPlanner...), Coordinator (4), Controller (3),
  Projector→Projection pairing (BatchProgressProjector/StoreStatusProjector →
  PageDisplayProjection/TranslationUiProjection), Gate (3), Registry (4).
  No action needed.

## 7. Test-tree parity — misplaced/missing

Misplaced (class-under-test package ≠ test folder):
- `scheduling/CancelSyncStoreWriteTest.kt` — imports root
  `eu.kanade.translation.ChapterTranslationStore`; the other five
  ChapterTranslationStore*Test files live at test root. Wrong folder.
- `batch/ChunkTranslationPayloadTest.kt`, `batch/BatchEnvelopeLimitsTest.kt` —
  under-test classes live in `translator/` (BaseTranslator,
  StreamingChunkPlanner, TranslationContextChunk). Defensible as topical
  "batch-translation semantics" tests, but package parity says translator/.
- `scheduling/TranslationPipelineConcurrencyTest.kt` — named for root
  TranslationPipeline but deliberately tests raw ConcurrentHashMap semantics
  (its own KDoc explains this); mild, keep.

Missing (main package with no mirroring test package): `pipeline/`,
`pipeline/batch/` (**no test references these packages at all**), `data/`,
`legacy/`, `store/`, `manager/` (the last three are exercised indirectly via
root tests; pipeline* is not exercised anywhere directly).

## 8. Cross-module (translation code outside `eu/kanade/translation`)

- `eu/kanade/tachiyomi/data/translation/` — TranslationForegroundService (234,
  Android service, manifest-registered: stays by platform convention) +
  BatchTranslationForegroundPolicy (10, depends only on
  translation.model.Translation). Not egregious. The confusion is the package
  NAME mirroring `eu.kanade.translation.data` transposed.
- `ui/reader/` auto-translation set (ReaderAutoTranslationPageResolver 234,
  ReaderAutoTranslationLifecycle 26, ReaderAutoTranslationUiState 176,
  ReaderTranslationFeedback 250, ReaderTranslationOverlayBinding 43,
  TranslationOverlayView 184 — ~913 lines): reader-owned lifecycle indirection,
  documented as such (retains no page lists; fences stale resolvers). Legitimate
  UI integration; no core translation logic lives here. **No egregious cases found.**
- `presentation/manga/components/TranslationProgressSheet.kt` imports
  `eu.kanade.translation.batch.*` directly (UI → batch-state types). Minor
  layering smell; if R2 lands, it just follows the new package.
- Normal DI/integration sites (App.kt, AppModule, Downloader, MangaScreenModel,
  ReaderActivity/ViewModel, DownloadPageLoader) — expected.

## 9. Costed recommendations (ordered by value)

| # | Action | Churn | Behavior | Risk | Reason |
|---|---|---|---|---|---|
| R1 | Move `legacy/LegacyFlatFileDecoder.kt` → `artifact/`; delete `legacy/` | 5 files (its importers) | **Pure move + import updates** | Low | Kills a 1-file package AND the artifact↔legacy package cycle; puts all legacy-format code in one home. |
| R2 | Merge `batch/` → `pipeline/batch/` (10 files + 16 test files) | 23 main files + tests touch imports (incl. 1 UI file) | **Pure move + import updates**; seam unchanged (direction already one-way) | Low–Med | Removes the module's only true dual-home; reunites Resume Planner/GateDecider; makes "batch" greppable in one place. Highest navigation win. If skipped, at minimum document the state-vs-execution seam in both package KDocs. |
| R3 | Move `detection/Detection.kt` → `model/` | 5–6 files | **Pure move + import updates** | Low | Removes model→detection edge; value type belongs with the model types that carry it. |
| R4 | Delete empty `presentation/` directory | 0 files | No code | None | Untracked residue. |
| R5 | Move `scheduling/CancelSyncStoreWriteTest.kt` → test root | 1 file | No production code | None | Package parity with the other five ChapterTranslationStore tests. |
| R6 (optional) | Move root stores (`TranslationQueueStore`, `TranslationPendingRequestStore`, `ActiveChapterStoreRegistry`) → `store/` | ~15 files | Pure move | Low | Only if R2/R1 land anyway; unifies the "Store" home. Skip otherwise — no collision today. |
| R7 (optional) | Rename `AITranslator` → `AiTranslator` | 3–4 files | Compile-time rename only | Low | Casing consistency within translator/. Cosmetic. |
| — | Explicitly DO NOT: move artifact/Legacy* → legacy/; merge ocr/ into recognition/; move root facade trio (Pipeline/Manager/Store/Translator) into subpackages; move remote/ | — | — | — | Would add churn without fixing a discoverability cost, break a defensible primitives/orchestration split, or relocate live-but-unwired code that dead-code tracking already covers. |

Verification gate for R1–R5: full unit test suite (no gradle was run in this
audit; moves are mechanical but touch shared imports).
