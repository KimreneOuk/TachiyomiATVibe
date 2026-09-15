# T932 — Production-code legacy/dead sweep (app/src/main)

Scope: `app/src/main` (Java/Kotlin), flavor dirs `app/src/dev` + `app/src/standard` checked.
Method: reference-proved (T924 D2 style) — every candidate grepped repo-wide for
constructors/callers in `app/src/main`, with `app/src/test` consulted only to
detect test-only liveness. No Gradle runs. Read-only except this report.

## Verdict summary

| Bucket | Count | Approx. lines | Meaning |
|---|---|---|---|
| DEAD (delete-safe now) | 6 | ~370 | zero production callers; one is test-only |
| MIGRATION (Director decision) | 11 | ~1,570 | runs only for pre-artifact / flag-era on-disk state |
| LIVE-MISLABELED (must NOT delete) | 6 | n/a | named "legacy", on today's hot path |
| STALE-DOC (cosmetic) | 6 | ~0 code | comments referencing removed machinery |
| UPSTREAM (Mihon, merge-cost caveat) | 5 | n/a | inherited, live or comment-only |

Key structural finding: **the legacy flat-file WRITE lane is already gone.**
`StorePersistenceScheduler.persistLocked()` (`store/StorePersistenceScheduler.kt:74-85`)
contains no flat JSON write — it only returns `authority == ARTIFACTS`.
`ChapterGlossaryStore.persistGlossaryLocked()` (`store/ChapterGlossaryStore.kt:114-131`)
writes only through the artifact store and returns false (error log) for legacy
authority. What remains of the legacy lane is **read/rescue/migration only**.

## When is authority != ARTIFACTS in practice? (basis for all MIGRATION rows)

1. **Fresh chapter, never translated** — no manifest file at all
   (`artifactManifest == null`); `ChapterGlossaryStore.loadGlossary:101-112`
   falls into the legacy branch and finds no `X.glossary.json` (harmless miss).
2. **Chapter translated by a pre-artifact app version** — flat `X.json` exists,
   no `X.manifest.json`. First open runs the full migration+rescue
   (`LegacyChapterMigrationSource.openInternal:40-129` →
   `ChapterArtifactStore.loadOrMigrate:121-224`), then authority flips to
   ARTIFACTS one-way (`LegacyArtifactRescue.rescueLegacy:115-127`). After that
   the legacy bytes are never consulted for pages again.
3. **Crash mid-rescue** — manifest left in LEGACY staging authority; next open
   re-runs `rescueLegacy` (`ChapterArtifactStore.kt:198-203`).

Consequence: every MIGRATION path executes at most once per old chapter, on
first open after upgrade, or never on fresh installs.

## Inventory

| # | Symbol / file:line | Bucket | Evidence (who references; execution path) | Trigger (if MIGRATION) / Action |
|---|---|---|---|---|
| 1 | `AnalysisChunkMapping.kt` (whole file, 90 lines) — `toAnalysisChunkResult:39`, `evidenceBoundaryError:85` | **DEAD** | grep in app/src/main: only the declaring file. Sole other reference is its own test `AnalysisChunkMappingTest.kt` | Delete file + its test |
| 2 | `TranslationScheduler.requestAutoWindow` `scheduling/TranslationScheduler.kt:291-494` + private helper `logAutoDecision:642-656` | **DEAD** | `@Deprecated` (line 300-303) whose own KDoc says "No live callers remain". Grep `requestAutoWindow` in main: only `TranslationManager.kt:1591` delegation and a doc mention (`TranslationExecutor.kt:36`). `logAutoDecision` callers all inside 330-428 (inside the dead method) | Delete both; ~223 lines incl. KDoc. Its own deprecation note schedules removal as a separate reviewed change |
| 3 | `TranslationManager.requestAutoWindow` `TranslationManager.kt:1575-1593` | **DEAD** | Zero callers: grep shows only the definition + doc mention | Delete |
| 4 | `TranslationPendingRequestStore.add(chapterId, phase, reason)` `TranslationPendingRequestStore.kt:106-124` | **DEAD** | KDoc itself says "Legacy phase/reason write kept for compatibility". Both call sites (`manager/TranslationRequestCoordinator.kt:360, 479`) use the record overload | Delete overload |
| 5 | `ResumeOrdering.forwardFirstThenBackfill` `util/ResumeOrdering.kt:29-44` | **DEAD (test-only)** | Zero main callers; only `ResumeOrderingTest.kt` + `Phase0BatchTranslationCharacterizationTest.kt`. File KDoc: "Legacy page-order helpers." `naturalOrder` in the same file IS live (`ChapterTranslator.kt:698`) | Delete function + its tests |
| 6 | `ChapterArtifactStore.LoadResult.resyncedFromLegacy` `artifact/ChapterArtifactStore.kt:101` | **DEAD (compat field)** | 16 references, every one writes `resyncedFromLegacy = false`; no producer of `true`. KDoc: "Retained compatibility field; one-way rescue never performs resync" | Remove field, touch all construction sites |
| 7 | `LegacyArtifactMigration` (whole file, 501 lines) — `migrateChapter:91`, `migratePage:105`, promotion/geometry helpers | **MIGRATION** | Sole production caller `ChapterArtifactStore.kt:206` (initial manifest build when no manifest exists) and `LegacyArtifactRescue.kt:49, 209` | Trigger: chapter with pre-artifact flat `X.json` (or rescue staging manifest) opened first time after upgrade. If deleted: old chapters never migrate — reader shows them untranslated; no crash (fresh installs unaffected) |
| 8 | `LegacyArtifactRescue` (whole file, 489 lines) — `rescueLegacy:37`, `reconcileLegacyPreservation:140`, `verifyLegacyArtifactHealth:185`, `cleanupOpenedChapterJunk:330` | **MIGRATION** | Constructed `ChapterArtifactStore.kt:89-91`; driven from `loadOrMigrate` (:128, :203, :223) and `LegacyChapterMigrationSource.kt:232, 249, 262` | Trigger: same as #7; also deletes preserved legacy inputs (`X.json.migrated*`, `X.glossary.json`) after a later-version health gate. If deleted: pre-artifact chapters orphaned; preserved legacy sources never reclaimed |
| 9 | `LegacyChapterMigrationSource` (whole file, 416 lines) — `openInternal:40`, `migrateArtifactManifest:149` | **MIGRATION (refactor-only, not plain delete)** | ALL store opens go through it: `ChapterTranslationStore.open:2598`, `openArtifact:2606`, `artifactMigrationLock:2611`. Legacy-gathering parts (legacy bytes read `:59-97`, glossary read `:188-210`, companion probe `:341-365`) execute only when `!isArtifactAuthoritative` (`:55-56`) | Trigger: flat `X.json` present and manifest not ARTIFACTS. Action: if migration is dropped, `openInternal` collapses to "probe manifest → open store"; deletion requires refactor of the store factories, not a file delete |
| 10 | `LegacyFlatFileDecoder` (whole file, 80 lines) | **MIGRATION** | Callers: `TranslationManager.kt:317, 1162, 1307, 1310, 1314`, `DurableChapterStatusResolver.kt:98`, `ChapterArtifactManifestReader.kt:25` (reuses its `ignoreUnknownKeys` Json for manifest decode) | Trigger: manifest absent/not ARTIFACTS (`DurableChapterStatusResolver.kt:143-160` else-branch; `TranslationManager.getChapterTranslationForReader:1213-1216`). If deleted: must inline the Json config used by the manifest reader (live), and old flat chapters lose status/decode |
| 11 | Legacy flat decode branches in `TranslationManager` — `statusFromReadablePages:1160-1163`, `decodeLegacyChapterTranslation:1303-1308`, `quarantineCorrupt*:1309-1315`, flat branches in `getChapterTranslationForReader:1209-1216` and `getChapterTranslation(file):1228-1234` | **MIGRATION** | Execute only when `manifestProbe.exists == false` or `authority == LEGACY` (grepped: `authority != ManifestAuthority.LEGACY` guards) | Trigger: pre-artifact chapter read for reader/durable status. Deleting orphans old data (no crash) |
| 12 | `DurableChapterStatusResolver` legacy branch `manager/DurableChapterStatusResolver.kt:143-160` (else → `decodeLegacyChapterStatus`) | **MIGRATION** | Branch reached only when no manifest document exists (`manifestProbe.exists == false`) | Same as #11 |
| 13 | Glossary flat read fallback: `ChapterGlossaryStore.loadGlossary:101-112` (legacy branch `:110-111`) + `ChapterTranslationStore.legacyDocuments():2498-2499` + `glossaryName():2501-2502` | **MIGRATION** | `legacyDocuments()`/`glossaryName()` have exactly ONE caller each: `ChapterGlossaryStore.kt:110-111` (grep-proved). Branch runs when authority != ARTIFACTS (case 1/2 above) | Trigger: `X.glossary.json` sibling of a pre-artifact chapter. If deleted: old glossaries dropped (translation quality-only impact, no crash) |
| 14 | `RunConfigSnapshot.flagProfilePipeline` `artifact/ChapterRunRecord.kt:60-72` | **MIGRATION (schema-compat)** | Writer: `ChapterProfileBatchCoordinator.kt:3225` (freezes `true`). Production readers: NONE (grep `.flagProfilePipeline`: only a comment at :308). Still participates in `frozenRunConfigFingerprint` "by construction". Decode is safe regardless (`AtomicChapterDocuments` Json uses `ignoreUnknownKeys`, `ChapterDocumentIo.kt:208`) | Trigger: run records written during the FF-01 flag era. Removing the field changes recomputed run fingerprints for those records → batch resume re-plans (no crash). Tests assert the field (`StandardPipelineCoexistenceTest.kt:84`, `StandardPipelineCoordinatorTest.kt:615`) |
| 15 | `ChapterArtifactLayout.contextDirectoryName` `artifact/ChapterArtifactLayout.kt:50-52` (+ managedDirectories entry `:161`) | **MIGRATION (retention sweep)** | No writer anywhere (grep `context/` in translation code: only these lines). KDoc: "no longer written, still swept". `ArtifactRetention.sweepDirectory:29-50` reclaims pre-refactor `X_artifacts/context/` files via managedDirectories | Trigger: `X_artifacts/context/` dirs from the scene-checkpoint era. Removing the declaration stops their reclamation (files linger until manual delete; harmless) |
| 16 | `LegacyArtifactRescue.cleanupOpenedChapterJunk` `:330-336` — `X.summary.json` + 0-byte `X.json` deletion | **MIGRATION** | `X.summary.json` has NO writer in the codebase (grep `summary.json`: only this deletion + a stale KDoc). Deletes only pre-historic sidecars | Trigger: chapters from the summary-sidecar era. Deletion of the machine (with #8) leaves those files orphaned |
| 17 | `StageArtifactRecord.legacyPayloadReference` `artifact/ArtifactContracts.kt:189` + reader `PageWorkPlanner.kt:483` | **MIGRATION** | Sole writer: `LegacyArtifactMigration.legacyStage:493`. Sole reader: `PageWorkPlanner.kt:483` (`record.artifactFileName != null || record.legacyPayloadReference != null`) | Trigger: manifests produced by migration. Once #7 is gone the field is never non-null → planner clause folds to `artifactFileName != null` |
| 18 | `ModelDeployment` legacy-cache rule `util/ModelDeployment.kt:19, 60` | **MIGRATION (tiny, keep)** | Missing/legacy stamp on on-disk model cache → "always re-copy once" | Trigger: model caches from before stamping. Cost of keeping: ~10 lines; recommend keep |
| 19 | `ChapterArtifactLayout.legacyCompanionImageFile` `artifact/ChapterArtifactLayout.kt:89-90` (`X_images/`) | **LIVE-MISLABELED — CRITICAL, do not delete** | Write side (live pipeline): `pipeline/CleanedPublication.kt:215`, `SinglePageOnnxPhase.kt:680`, `SinglePageHttpRenderPhase.kt:602`, `BatchLaneWorkers.kt:154` via `TranslationProvider.getCompanionImageDir:145-152`. Validation: `ChapterArtifactStore.promoteLiveCandidate:1221` REJECTS promotion unless the file exists in `X_images/`; `LegacyArtifactRescue:233`. Reader consumption: `ReaderViewModel.kt:855` + `DownloadPageLoader.kt:189` → `CleanedImageLifecycleController.getCleanedImageStream:182-210` → `provider.findPageCleanedImage:161-164`. Rekey rename: `TranslationManager.kt:1362-1372`; deletion/retire: `CleanedImageLifecycleController.kt:74, 109, 138, 172, 193`; `ChapterDataResetController.kt:416, 464` | `X_images/` is the live cleaned-image store. Deletion would break rendering of every translated page. Rename/re-home candidate only |
| 20 | `ContextualRequestProtocol.LEGACY` (+ `ContextualRequestBuilder.buildLegacy:76`) | **LIVE-MISLABELED (naming)** | `LEGACY` is the single-page prompt shape; `BATCH_V1` is the batch-envelope shape. Both live: `SinglePageHttpRenderPhase.kt:305`, `AiTranslator.kt:64`, `ContextualRequestBuilder.kt:105, 115-116`, `StreamingChunkPlanner.kt:240` default. Rename candidate (e.g. `SINGLE_PAGE`), not deletion |
| 21 | `ProfileEnvelopeExecutor.splitForTokenFit` identity split when `frozenProfile == null` (`pipeline/batch/ProfileEnvelopeExecutor.kt:436`) + `promptShapeLegacy` counter (`:133, 149, 875`) | **LIVE-MISLABELED (reachable degraded mode)** | `frozenProfile` is null whenever the frozen-profile sidecar fails the reuse probe: `ChapterProfileBatchCoordinator.kt:1264-1290` logs "envelope prompt shape=legacy (degraded-but-correct)". So the no-frozen-profile shape IS reachable at HEAD. **Relevant to the 8k plan**: token-fit splitting silently does NOT apply in degraded runs; the identity split returns all held pages as one batch |
| 22 | `DisplayBaseReference.legacyLayout` `artifact/ArtifactContracts.kt:146` | **LIVE-MISLABELED (semantic drift)** | Set `true` for EVERY fresh committed bundle with a cleaned image: `ChapterArtifactStore.kt:1243` (`legacyLayout = pageSnapshot.cleanedImageName != null`), and by migration (`LegacyArtifactMigration.kt:217, 276`). Consumers use it as "fileName lives in `X_images/`, outside the managed tree": `ArtifactRetention.kt:93` (skip as reachable path), `LegacyArtifactRescue.kt:232`, `LegacyChapterMigrationSource.kt:304`. The `!legacyLayout && fileName != null` combination at ArtifactRetention:93 has no producer (effectively dead branch, harmless). Rename candidate: `companionImageLayout` |
| 23 | `TextLayoutPlanner` `legacyFitW/H` params `rendering/TextLayoutPlanner.kt:1463-1490` | **LIVE-MISLABELED (naming)** | "Legacy rectangle fit" = the pre-rescue plan's font fit, deliberately kept as font-size head-room (`maxFont` of OCR fit and legacy fit). Live rendering behavior; rename candidate only |
| 24 | `SequentialBatchCoordinator` mentions `pipeline/batch/BatchLaneWorkers.kt:83`, `ChapterProfileBatchCoordinator.kt:89` | **STALE-DOC** | Class deleted; both lines are comment-only (grep: no symbol) | Delete comment fragments |
| 25 | `X.summary.json` "existing sidecar convention" KDoc `ChapterArtifactLayout.kt:9-10` | **STALE-DOC** | No writer exists anywhere (see #16) | Fix KDoc |
| 26 | FF-01 narration comments `BatchChapterTranslator.kt:616-618, 1108, 1116`, `OverlapScheduler.kt:22`, `ChapterProfileBatchCoordinator.kt:88, 123, 310, 3195, 3087` | **STALE-DOC** (historical, accurate) | Comment-only; flag machinery already removed | Optional trim |
| 27 | `StorePersistenceScheduler.kt:25` "vestigial legacy-ctor probes" | **STALE-DOC** | Comment describing removed ctor forms | Cosmetic |
| 28 | `ReaderViewModel.kt:2251` "lazy-HTTP fallback was unreachable dead code" | **STALE-DOC** | Comment about already-removed code | Cosmetic |
| 29 | `BasePreferences.ExtensionInstaller.LEGACY` `domain/base/BasePreferences.kt:27` (+ `ExtensionInstallerPreference.kt:30,38`, `ExtensionInstaller.kt:156`) | **UPSTREAM (live)** | Mihon extension installer option, actively dispatched | Leave (upstream-merge cost) |
| 30 | `BackupTracking.kt:13` `@Deprecated mediaId`; `Backup.kt:10` / `BackupManga.kt:35` commented legacy proto fields; `WebtoonLayoutManager.kt:25` `@Deprecated("Deprecated in Java")`; `Chapter.kt:8` "TODO: Remove when all deps are migrated"; `ExtensionApi.kt:79` legacy `index.min.json` fallback | **UPSTREAM** | All Mihon-inherited; ExtensionApi fallback is live upstream behavior | Leave |

## Explicitly checked and found NOT dead (guard rails)

- `ResumeOrdering.naturalOrder` — live (`ChapterTranslator.kt:698`).
- `RetryAfterParser` — live internally (`ProviderFailureClassification.kt:34`) + tests; `parseRetryAfterMillis` live (`GeminiTranslator.kt:209`).
- `Task<T>.await()` (`util/TaskExtensions.kt`) — live (`ocr/TextRecognizer.kt:10`, `MLKitTranslator.kt:10`).
- `QueuedChapterView` / conflict detection (`model/ChapterQueueConflictDetection.kt`) — live (`TranslationManager.kt:953, 972`).
- `forwardTranslationMemoryPressure` — live (`App.kt:253`).
- `BatchCoordinatorInterfaces` symbols (incl. `toChunkCompletionOutcome`, used in-file at `:74`) — live.
- `ChapterDrawPlan`, `AnalysisWire`, `AutoWindowState`, `PageTranslationOwnership`, `PageTranslationKey`, `PostOcrStageSemantics` — all declarations referenced.
- Flavor dirs: `app/src/dev` / `app/src/standard` contain only `FirebaseConfig` variants — live (`App.kt:85, 143, 148`).
- `res/` quick pass: no legacy-named resources in `app/src/main/res` (strings live in the `i18n` module — out of prod scope).
- `generations/` + `glossary/` layout dirs — live (`ChapterArtifactStore.kt:253-255, 1269`).

## Top 10 deletions by line count

| Rank | Item | Bucket | Lines saved |
|---|---|---|---|
| 1 | `LegacyArtifactMigration.kt` | MIGRATION | 501 |
| 2 | `LegacyArtifactRescue.kt` | MIGRATION | 489 |
| 3 | `LegacyChapterMigrationSource.kt` | MIGRATION (refactor) | 416 (net less after open-path rewrite) |
| 4 | `TranslationScheduler.requestAutoWindow` + `logAutoDecision` | DEAD | ~223 |
| 5 | `AnalysisChunkMapping.kt` | DEAD | 90 |
| 6 | `LegacyFlatFileDecoder.kt` | MIGRATION | 80 |
| 7 | `TranslationManager` legacy decode wrappers/branches (1160-1163, 1209-1216, 1228-1234, 1303-1315) | MIGRATION | ~60 |
| 8 | `TranslationPendingRequestStore.add(chapterId, phase, reason)` | DEAD | 19 |
| 9 | `TranslationManager.requestAutoWindow` | DEAD | 19 |
| 10 | `ResumeOrdering.forwardFirstThenBackfill` (+2 test files) | DEAD | 16 main |

DEAD subtotal ≈ 367 lines, deletable immediately (plus ~90 lines of tests for
AnalysisChunkMapping/ResumeOrdering, owned by the test-tools report).
MIGRATION subtotal ≈ 1,560 lines, gated on the Director's decision: **are any
real devices still carrying pre-artifact (flat `X.json` / `X.glossary.json` /
`X_artifacts/context/` / flag-era run-record) state?** If the answer is no —
or orphaning that data is acceptable — the entire migration machine can go in
one reviewed batch, with the store open path (`openInternal`) rewritten to
"probe manifest → open" and `PageWorkPlanner.kt:483` simplified.
