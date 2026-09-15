# T932 — Tests, tools/, and repo-root strays (team: test-tools)

Date: 2026-09-14 · Scope: `app/src/test`, `tools/`, repo root. Read-only;
reference-proved by grep. Prod code (`app/src/main`) referenced only for
liveness checks (another agent owns it).

---

## 1. Verdict up front

**The test suite is essentially clean.** No test exercises deleted machinery
as live API; every FF-01/SequentialBatchCoordinator mention in tests is
KDoc/history. The legacy-named tests guard the **live migration lane**
(`LegacyArtifactMigration.migrateChapter` is called from production today at
`app/src/main/.../artifact/ChapterArtifactStore.kt:206`), so they are coupled
to the prod MIGRATION decision, not independently deletable. Exactly one dead
test file exists — `Page15MockRig.kt` (zero consumers, eviction already
scheduled). All shared fakes/harnesses are load-bearing.

**tools/ is mostly active.** The three recently committed tools
(chapter downloader `e5794f2`, translation studio `6461bc4`, mangaocr lab
`7f49944`) are wired together and launched by the tracked
`launch_tools.bat`, with correct nested `.gitignore`s. Dead/scratch material
concentrates in the **July prototyping era**: 17 `prototype_*.py` +
`requantize_seg_int8.py` (zero external refs, self-contained import cluster)
and `experimental/models/manga109-segmentation-bubble/` (41 MB of tracked
model binaries referenced only by those prototypes; includes a file its own
sibling script calls quantization-corrupted).

**Repo root has one real stray**: `_gui_probe/` (untracked, 154 MB of
regenerable downloader/studio output, referenced by nothing). Plus
`ARCHITECTURE_LAYERS.md` — a byte-identical untracked copy of the T929 report
doc — and two empty dirs, one ignored log, and one DEAD tracked launcher
(`bat/manga_render_lab.bat` → targets `overlay_lab/` which does not exist).

Counts: DEAD 3 · RETIRES-WITH-CODE (conditional) 2 · SCRATCH 2 clusters ·
STALE-DOC 15 test KDoc sites + 5 doc files · LIVE-MISLABELED naming 1 family ·
hygiene items 8.

---

## 2. Section A — app/src/test (273 files)

### A.1 Legacy-subject tests — verdict per file

| Test file | Tests | Subject liveness (prod evidence) | Verdict |
|---|---|---|---|
| `artifact/LegacyArtifactMigrationTest.kt` | 27 unit tests over `LegacyArtifactMigration` | `LegacyArtifactMigration.migrateChapter` called at prod `ChapterArtifactStore.kt:206`; rescue lane at `LegacyArtifactRescue.kt:49` | **RETIRES-WITH-CODE** — delete together with the prod migration lane iff the Director's MIGRATION decision says no device carries pre-artifact state; until that decision it guards a live upgrade path |
| `ChapterTranslationStoreArtifactMigrationTest.kt` | 24 tests, two halves | Legacy halves bootstrap via `writeLegacyChapter()` (:172-182) + `ChapterTranslationStore.open`; artifact-only halves use `lazy`/`openArtifact` | **RETIRES-WITH-CODE (legacy halves only)** — 13 tests at :202, :232, :378, :411, :424, :438, :453, :461, :477, :491, :532, :568, :626 ride the legacy-open path. The 9 artifact-only tests (:251, :294, :317, :337, :358, :675, :731, :756, :767) are **LIVE** and survive, needing at most re-fixturing |
| `ChapterTranslationStoreArtifactMigrationTest.kt:184-189` | `legacyPageWithRemovedBatchContextFields` | Feeds removed `batchContextCheckpointHash`/`batchContextComplete`/`batchSceneCheckpoint` JSON keys into the live decoder | **KEEPS-GUARDING-LIVE-CODE** while any old install exists (schema tolerance for on-device page JSON). Falls under the same MIGRATION decision |
| `T924FeatureFlagsTest.kt` | 2 tests on FF-02 `translationBatchPersistedLayout` | Flag is LIVE: prod `SettingsTranslationScreen.kt:87` and `PersistedLayoutRuntime.kt:52` consume it | **KEEPS-GUARDING-LIVE-CODE** (FF-02). Its FF-01 KDoc (:13-16) is STALE-DOC only. Not deletable with FF-01 history |
| Flag-era compat field `ChapterRunRecord.flagProfilePipeline` | referenced at `StandardPipelineCoexistenceTest.kt:84`, `StandardPipelineCoordinatorTest.kt:615` | Prod field at `ChapterRunRecord.kt:71` (`Boolean? = null`), frozen counter in `ChapterProfileBatchCoordinator.kt:3133` | **RETIRES-WITH-CODE** with the prod field if prod agent classifies it DEAD (2 test refs go with it) |

Observation for the prod agent: `LegacyArtifactRescue` (prod `LegacyArtifactRescue.kt`) has
**zero direct test coverage** in `app/src/test` — only `LegacyArtifactMigration` is
unit-tested. Rescue-lane behavior is exercised only indirectly via store tests.

Also confirmed: no test references `StorePersistenceScheduler`/`legacyDocuments`
(the legacy flat-file persist lane) by name — if prod retires that lane, no test
deletion is needed, but also nothing pinning it survives.

### A.2 Stale KDoc inventory (complete list — verified, extends the known ~10)

All references are comments/KDoc; **zero** live API references to deleted
machinery exist in tests (grep `SequentialBatchCoordinator|FF-?01` — every hit
is comment text). T931's staleness audit reached the same conclusion
(`Plan/active/2026-09-14_T931_test-suite-bottleneck-audit/team/staleness/report.md` §A.1); this list is verified independently and adds 3 sites.

| # | File:line | Content | Severity |
|---|---|---|---|
| 1 | `coexistence/TranslationCoexistenceHarness.kt:102` | Build-graph KDoc still lists `→ SequentialBatchCoordinator →` as part of the "REAL production graph" | **MISLEADING** — the harness actually wires `ChapterProfileBatchCoordinator`; the only stale doc that asserts something false |
| 2 | `coexistence/StandardPipelineCoexistenceTest.kt:16` | "FF-01 ON + STANDARD engine" wording | cosmetic |
| 3 | `coexistence/StandardPipelineCoexistenceTest.kt:25` | "the legacy SequentialBatchCoordinator is NOT constructed" | cosmetic (accurate negative assertion follows) |
| 4 | `coexistence/BatchDispatchResumeWiringTest.kt:20,28` | flag-OFF case history | cosmetic |
| 5 | `coexistence/D3ReaderOwnedPageAcrossBatchTest.kt:31` | "was SequentialBatchCoordinator machinery, deleted" | cosmetic |
| 6 | `pipeline/batch/OcrPreflightQueueRestoreTest.kt:36-38` | "no legacy SequentialBatchCoordinator — those tests pinned deleted behavior" | cosmetic |
| 7 | `pipeline/batch/OcrPreflightCoordinatorTest.kt:39` | "the FF-01 flagged [ChapterProfileBatchCoordinator]" | cosmetic |
| 8 | `pipeline/batch/OcrPreflightCoordinatorTest.kt:238` | "flag frozen (FF-01d)" | cosmetic |
| 9 | `pipeline/batch/ProfilePipelineDispatchGateTest.kt:7` | "FF-01 A/B flag is gone" | cosmetic |
| 10 | `pipeline/batch/ProfileEnvelopeDispatchTest.kt:58` | "serial envelope dispatch behind FF-01" | cosmetic |
| 11 | `pipeline/batch/Stage7FinalizeCoordinatorTest.kt:60` | "deleted with the FF-01 flag" | cosmetic |
| 12 | `ChapterTranslatorQueueRestoreTest.kt:69` | "the FF-01 flag and its decideResume decision tree are gone" | cosmetic |
| 13 | `T924FeatureFlagsTest.kt:13-16` | "FF-01 … REMOVED — there is no flag to test" | cosmetic |
| 14 | `CancelBatchLeaseSkipTest.kt:24,28` | "a batch run (legacy OR flagged)", "the flagged lane's checkpointOcr" | cosmetic (flag-era naming) |
| 15 | `ChapterArtifactStoreStaleManifestRetryTest.kt:16` + `D10PartialDownloadAdmissionTest.kt:544` + `BatchPostPassProjectionTest.kt:11-20` + `StandardPipelineCoexistenceTest.kt:20,39,61,72,100` + `ChapterTranslatorQueueRestoreTest.kt:174` (`interruptedFlaggedRun`) | family of "flagged lane/flagged run" naming residue for the now-unflagged `ChapterProfileBatchCoordinator` | **LIVE-MISLABELED (naming only)** — the coordinator is the only pipeline; "flagged" is a fossil. Rename candidate, never delete |

Bucket: all STALE-DOC except the item-15 family which is a LIVE-MISLABELED
naming residue. Suggested action: rewrite #1 in the next touch; the rest can
ride along any edit of those files. No dedicated commit required.

Cross-reference for the prod agent: the same FF-01/SequentialBatchCoordinator
comment residue exists ~20 times in `app/src/main`
(e.g. `ChapterProfileBatchCoordinator.kt:88-89,310`, `BatchLaneWorkers.kt:83`,
`OverlapScheduler.kt:22`, `StoreStatusProjector.kt:70`,
`SettingsTranslationScreen.kt:76`, `PersistedLayoutHydrator.kt:232`) — their bucket.

### A.3 Dead test helpers / fakes — all shared infrastructure is LIVE

Usage counts = files referencing the symbol outside its declaring file
(grep over `app/src/test`, method: reference-proved):

| Helper | Consumers | Verdict |
|---|---|---|
| `com/hippo/unifile/FakeUniFile.kt` | 20 files | LIVE |
| `artifact/FakeChapterDocumentIo.kt` | 21 files | LIVE |
| `coexistence/FakeEngines.kt` | 14 files; every exported symbol ≥2 external refs (`FakeCoexistence` 11, `FakeTransportTranslator` 13, `callsFor` 11, `SharedTransportState` 5, `stubBitmap` 6, `textBlock` 5, `decodedPage` 3, `FakeRecognitionEngine` 3, `analyzedPage` 2, `STUB_BITMAP_BYTES` 2, `totalCalls` 2) | LIVE, no dead members |
| `coexistence/CoexistenceBarrier.kt` | 15 files | LIVE |
| `coexistence/TranslationCoexistenceHarness.kt` | 18 files; all 15 public members externally used (`installGraphicsShims` 28, `stubChapterPages` 27, `tapManual` 24, `capturedManualJob` 24, `registerReaderStream` 25, `launchBatch` 19, `transportCallsFor` 17, `removeGraphicsShims` 21, `unstubChapterPages` 11, `artifactAuthorityStore` 13, `chapterFor` 8, `createStandard` 6, `setFields` 6, `unsafeAllocate` 4, `harnessPreferences` 2) | LIVE, no dead members |
| `InMemorySharedPreferences.kt` | 10 files | LIVE |
| `pipeline/batch/BatchStageInvocationCounters.kt` | 2 files | LIVE (thin margin; fine — it is a counter) |

### A.4 Already-scheduled items (noted only, per task contract)

| Item | Evidence | Status |
|---|---|---|
| `rendering/Page15MockRig.kt` (1,018 lines) | grep `Page15MockRig` over `app/src/test`: **only its own declaration** (:41). Zero consumers | **DEAD**. Eviction already scheduled: `Plan/active/2026-09-14_T929_200chapter-solution-space-loop/report/EXECUTION_ORDER.md:56-57` ("0.3 Evict `Page15MockRig.kt` by MOVING to a non-compiled location `tools/dev/…`"). Confirmed; no re-litigation |
| `coexistence/ManualRenderProbeBaseline.kt` (untracked) | grep: zero references; file is a single baseline `@Test` wiring the harness (:11-30) | Promotion already scheduled: `EXECUTION_ORDER.md:37` ("0.1 Promote `ManualRenderProbeBaseline.kt` into the tracked suite"). Confirmed; no re-litigation |

---

## 3. Section B — tools/ inventory

Reference sources checked: `.github/workflows/*.yml` (no tools/ references at
all — CI builds only), root `README.md`/`CONTRIBUTING.md`/`docs/*`,
gradle files, `launch_tools.bat`, and cross-references between tools.
Recent commits: `e5794f2` (per-site chapter downloader GUI + launcher bat),
`6461bc4` (translation studio), `7f49944` (T927 mangaocr lab),
`7a95f9c` (ORT/HTP isolation baseline), `0a79d57` (visualizer upgrade).

| Item | Size | Referenced by | Bucket | Evidence / action |
|---|---|---|---|---|
| `tools/chapter_downloader/` | 90K | `launch_tools.bat` (tracked, launches it); README points output at translation_studio | **ACTIVE-TOOL** | commit e5794f2; nested `.gitignore` excludes `.settings.json`, `__pycache__` |
| `tools/translation_studio/` | 865K | `launch_tools.bat`; chapter_downloader README | **ACTIVE-TOOL** | commit 6461bc4 + e0915fd (layout-engine port); nested `.gitignore` excludes `demo_chapter/.studio/`, `__pycache__` |
| `tools/mangaocr_lab/` | 63M | T927 docs (`Plan/.../T927_mangaocr-batching-desktop-lab/DESIGN.md`), `models.lock.json` | **ACTIVE-TOOL** | commit 7f49944; `mangaocr_lab/.gitignore` excludes `results/`, `work/` |
| `tools/gpu_isolation/` | 15K (8 ONNX probe models) | T927 `DESIGN.md:64` | **ACTIVE-LAB** (isolation-baseline evidence, commit 7a95f9c) | keep; it is the recorded HTP/GPU baseline |
| `tools/aot_conversion/` | 12K | self-documented `REPORT.md:61` | **ACTIVE-LAB** | AOT 512 conversion script + report |
| `tools/aot_corpus/` | 51M (414 tracked files, mostly corpus PNGs) | own `README.md` (regeneration commands :47-54), `CURATION_REPORT.md` | **ACTIVE-LAB** | heavy but deliberate (T908 audit `reports/repo-structure-audit.md:68` already flagged the mass); regeneration scripts present |
| `tools/visualizer/` | 48K (pure static JS/HTML) | **nothing** — no doc/workflow/tool references; opened directly in a browser | **ACTIVE-TOOL (standalone)** | commit 0a79d57; self-contained; candidate for a one-line README pointer, not deletion |
| `tools/prototype_*.py` (17 files) | ~130K | **only each other** (`prototype_full_pipeline.py:11-13` imports the cluster); zero refs from docs/tools/CI. Some hardcode `C:\Users\ADMIN\...` HF cache paths (`prototype_full_pipeline.py:90`, `prototype_visualize_boxes.py:29`) | **SCRATCH** | July-12-era Paddle/YOLO/AOT exploration, superseded by translation_studio + mangaocr_lab. Deletion batch candidate |
| `tools/requantize_seg_int8.py` | 4K | zero external refs | **SCRATCH** | one-shot fixup for the prototype-era `best_int8.onnx` (docstring :4 says the old int8 is "quantization-corrupted"); goes with the prototype cluster |
| `tools/_studio_smoke.log` | 98K | nothing | ignored stray | matched by root `.gitignore:19` (`*.log`); regenerable smoke log — local cleanup only, no repo action |

Note: docs referencing `tools/inpaint-debug-viewer/` point at a directory that
does not exist locally (gitignored local-only dev tool, currently absent):
`docs/ocr-engine-notes.md:112`, `docs/TRANSLATION_MODULE.md:715`,
`docs/APK_SIZE_CLEANUP_AUDIT.md:172`. STALE-DOC unless the tool is
re-provisioned.

---

## 4. Section C — repo-root strays

Untracked inventory (`git status --porcelain`) + tracked oddities:

| Item | Status | What it is | Evidence | Verdict / action |
|---|---|---|---|---|
| `_gui_probe/` | untracked, **154 MB** | Downloader+studio working output: `dl_out/.studio/thumbs/` (18 jpg) and one downloaded chapter `dl_out/tonari-no-seki-..._ch1/.studio/{detections.json, inpaint/, inpaint_mask/}` — i.e. a manual GUI probe of `tools/chapter_downloader` → `tools/translation_studio` from the Sep 12-13 bring-up | zero code/doc references; only mention is T928 `README.md:20` noting it is untracked | **SCRATCH working data** — fully regenerable via the two tools. Delete locally, or add `/_gui_probe/` to `.gitignore` if it will recur |
| `ARCHITECTURE_LAYERS.md` | untracked | Accessibility copy of a Plan doc | `diff -q` byte-identical to `Plan/active/2026-09-14_T929_200chapter-solution-space-loop/report/ARCHITECTURE_LAYERS.md` | **Duplication, deliberate** (per task C.3). Risk: the root copy silently diverges when the Plan copy is edited. Either track it as the canonical location or add a "copy of …" header |
| `_t912_instrumented.log` | untracked (ignored via `.gitignore:19` `*.log`) | Old T912 instrumented-run log at repo root | nothing references it | ignored stray; local cleanup |
| `test_crops/` (root) | untracked, **empty dir** | Empty leftover (name duplicates `tools/mangaocr_lab/test_crops/`, which is the real, tracked one) | `ls` → empty; git ignores empty dirs | delete the empty dir |
| `backup-external-data/covers/` | untracked, **empty dir** | Empty scaffold for external cover backup | `ls -la` → empty | delete, or document its purpose if the backup flow is still planned |
| `kilo.json` | untracked, ignored (`.gitignore:47`) | Credentials file, per `.gitignore` comment "never commit" | correctly ignored | hygiene OK |
| `_build.bat`, `_compile.bat`, `_verify.bat`, `_build_install.bat` | **tracked** | Machine-specific dev-loop glue | hardcode `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, adb serial `192.168.100.207:37625` (`_verify.bat:5`), `C:\Users\ADMIN\...` (`_build_install.bat:3`) | **ACTIVE** (Director's build/verify loop) but not portable; hygiene: consider moving to an untracked `local/` or documenting as machine-bound |
| `launch_tools.bat` | tracked | Launcher for translation_studio + chapter_downloader | `launch_tools.bat:9-16` | **ACTIVE-TOOL** glue |
| `bat/manga_render_lab.bat` | tracked | Server helper for a "Manga Render Lab" | requires `%REPO_ROOT%\overlay_lab\backend\main.py` (:57-60); **`overlay_lab/` does not exist** at repo root; `.gitignore:44` still carries `overlay_lab/debug_*.png` | **DEAD** — its target directory is gone (superseded by tools/translation_studio, port 8765 note in `launch_tools.bat:14`). Deletion candidate + drop the stale `.gitignore` rule with it |
| `experimental/models/manga109-segmentation-bubble/` | tracked, **41 MB** (5 binaries: `best.pt`, `best.onnx`, `best_fp32.onnx`, `best_int8.onnx`, `best_int8_broken.onnx`) | Early YOLO seg models from the July prototype era | referenced ONLY by the `tools/prototype_*.py` scratch cluster (which re-downloads from HF hub anyway: `prototype_combined.py:94`); active tools use the app's own `detector-v4-s_int8.onnx` (translation_studio README:37) and never mention `experimental/` | **SCRATCH (tracked data)** — strongest repo-weight deletion candidate: 41 MB of binaries incl. a file that its own sibling script (`requantize_seg_int8.py:4`) calls quantization-corrupted. Deletion batch candidate with the prototype cluster |
| `engineering/phase4-implementation-log.md` | tracked | Historical phase-4 implementation log | zero references | archival; harmless (STALE-DOC-adjacent, no action needed) |

---

## 5. Section D — STALE-DOC (docs/ and Plan/, filename+target check only)

No doc **filename** references deleted machinery (`find docs Plan -iname
"*SequentialBatch*" -o -iname "*FF-01*" -o -iname "*FlagOff*"` → empty).
Content-level hits, for the STALE-DOC bucket:

| File | Evidence | Note |
|---|---|---|
| `docs/architecture/batch-translation-pipeline.md` | contains `SequentialBatchCoordinator` | describes deleted coordinator |
| `docs/TRANSLATION_MODULE.md` | contains `SequentialBatchCoordinator` (:715 area also references non-existent `tools/inpaint-debug-viewer/server.py`) | two stale references in one file |
| `docs/ocr-engine-notes.md:112` | references non-existent `tools/inpaint-debug-viewer/paddle_rec_parity.py` | target dir absent |
| `docs/APK_SIZE_CLEANUP_AUDIT.md:172` | references non-existent `tools/inpaint-debug-viewer/debug-output/` | target dir absent |
| `engineering/phase4-implementation-log.md` | historical log, zero refs | archival only |

---

## 6. Repo hygiene summary

1. **`_gui_probe/` (154 MB, untracked)** — regenerable downloader/studio
   probe output; delete locally or gitignore `/_gui_probe/`.
2. **`ARCHITECTURE_LAYERS.md` duplication** — byte-identical untracked copy
   of the T929 report doc; pick one canonical home.
3. **`bat/manga_render_lab.bat` is DEAD** — targets non-existent
   `overlay_lab/`; delete with the stale `.gitignore` overlay_lab rule.
4. **41 MB of prototype-era model binaries tracked** under
   `experimental/models/` — referenced only by the scratch prototype scripts;
   biggest tracked-weight win available in this scope.
5. **Two empty dirs** at root (`test_crops/`, `backup-external-data/covers/`).
6. **Ignored strays** (`_t912_instrumented.log`, `tools/_studio_smoke.log`)
   — local cleanup only.
7. **Machine-bound tracked bats** (`_*.bat`) hardcode local paths/serials —
   active but non-portable.
8. **`.gitignore` sanity is otherwise good** — tools have proper nested
   `.gitignore`s; `kilo.json` (credentials) correctly excluded.

---

## 7. Consolidated inventory table

| Item | Bucket | Evidence | Proposed action |
|---|---|---|---|
| `app/src/test/.../rendering/Page15MockRig.kt` | DEAD | zero refs (grep, declaration-only :41) | execute already-scheduled eviction (EXECUTION_ORDER 0.3) |
| `tools/prototype_*.py` (17) + `tools/requantize_seg_int8.py` | SCRATCH | refs only within cluster; hardcoded ADMIN paths | deletion batch candidate |
| `experimental/models/manga109-segmentation-bubble/` (41 MB) | SCRATCH (tracked data) | only prototype cluster references them; HF re-downloadable | deletion batch candidate |
| `bat/manga_render_lab.bat` + `.gitignore` overlay_lab rule | DEAD | target `overlay_lab/` absent (bat :57-60) | delete both |
| `LegacyArtifactMigrationTest.kt` | RETIRES-WITH-CODE | guards `LegacyArtifactMigration` (prod `ChapterArtifactStore.kt:206`) | delete only with the prod lane per Director MIGRATION decision |
| `ChapterTranslationStoreArtifactMigrationTest.kt` legacy halves (13 tests) | RETIRES-WITH-CODE | ride `ChapterTranslationStore.open` legacy path | same decision; artifact-only 9 tests stay |
| `legacyPageWithRemovedBatchContextFields` (:184-189) | KEEPS-GUARDING-LIVE-CODE | decoder tolerance for old device JSON keys | keep while old installs exist |
| `T924FeatureFlagsTest.kt` | KEEPS-GUARDING-LIVE-CODE | FF-02 flag live (prod `PersistedLayoutRuntime.kt:52`) | keep; trim FF-01 KDoc |
| `flagProfilePipeline` test refs (2) | RETIRES-WITH-CODE | compat field prod `ChapterRunRecord.kt:71` | go with prod field if prod agent marks it DEAD |
| 15 test KDoc sites (§A.2 #1-14) | STALE-DOC | comments only; #1 is misleading | rewrite #1; rest opportunistically |
| "flagged lane/run" naming family | LIVE-MISLABELED (naming) | `ChapterProfileBatchCoordinator` is the only pipeline | rename candidate, never delete |
| `ManualRenderProbeBaseline.kt` (untracked) | (scheduled) | zero refs; EXECUTION_ORDER :37 promotes it | Phase 0.1 — noted only |
| `chapter_downloader`, `translation_studio`, `mangaocr_lab` | ACTIVE-TOOL | launch_tools.bat + recent commits e5794f2/6461bc4/7f49944 | keep |
| `gpu_isolation`, `aot_conversion`, `aot_corpus` | ACTIVE-LAB | T927 DESIGN.md:64; own READMEs/REPORT | keep |
| `tools/visualizer` | ACTIVE-TOOL (standalone) | commit 0a79d57; zero doc refs | keep; optional README pointer |
| `_gui_probe/` | SCRATCH (untracked) | 154 MB regenerable output; no refs | delete locally or gitignore |
| `ARCHITECTURE_LAYERS.md` | duplication | byte-identical to T929 report copy (`diff -q`) | pick canonical home |
| `test_crops/`, `backup-external-data/covers/` (empty) | hygiene | empty dirs | remove |
| `_t912_instrumented.log`, `tools/_studio_smoke.log` | ignored strays | `*.log` rule | local cleanup |
| docs/FF-01 + inpaint-debug-viewer refs (5 files) | STALE-DOC | §5 table | cosmetic sweep |
