# T936 Phase 2 implementation report

Branch: `t936/phase2-storage-unification` (based on `main` at `65a738e`). No push was performed. The phase plan commit, the six ticket implementation commits, the authorized P2-02 test-seam commit, and this report are kept separate.

## P2-00 — ONNX provider diagnostic

Commit `049a050` (`feat(diagnostics): log compiled ONNX runtime execution providers once at init`) adds one additive initialization log in `OnnxRuntimeProvider` after the shared `OrtEnvironment` is obtained. The provider query is wrapped in `runCatching`; the line is emitted once per process and does not change provider selection, NNAPI routing, or fallback behavior. No other NNAPI or hardware-routing code was changed.

## P2-01 — legacy flat-file elimination

Commit `f6b574d` (`refactor(translation): eliminate legacy flat-file translation subsystem and authority enum`) removes runtime reads and migration logic for legacy flat translation files. Legacy files are not deleted from disk.

### Seam inventory captured before code changes

The baseline (`65a738e`) was searched with `git grep` over `app/src`.

- Legacy production sources were `LegacyArtifactMigration.kt`, `LegacyArtifactRescue.kt`, `LegacyChapterMigrationSource.kt`, and `LegacyFlatFileDecoder.kt`.
- `ManifestAuthority` was an admission/read switch in `TranslationManager`, `DurableChapterStatusResolver`, reader projection, batch admission/projection, attempt ledger, status projection, deletion, and coexistence tests.
- Flat-file reads entered through `TranslationManager`, `DurableChapterStatusResolver`, `ChapterTranslationStore`/glossary open, and the migration source.
- `legacyCompanionImageFile` was limited to legacy migration/health paths and tests; it was not part of the artifact-only display contract.
- `loadOrMigrate(...)` was a migration/test setup seam. Migration/cutover tests were removed or rewritten; artifact durability tests remain artifact-backed.

Baseline `ManifestAuthority.LEGACY` constructions/defaults were:

- `ChapterArtifactManifest.kt:53`: the enum-valued default `authority = ManifestAuthority.LEGACY`.
- `LegacyArtifactRescue.kt:52`: explicit rescue construction with `authority = ManifestAuthority.LEGACY`.
- `ChapterArtifactStoreTest.kt:499` and authority guards in the D5/D6/D7/D9/D10 coexistence fixtures: migration/cutover assumptions.

The exact acceptance search after the change was:

```text
git grep -n -E 'LegacyArtifact|LegacyChapterMigration|LegacyFlatFile|ManifestAuthority' -- app/src
```

It returned no output (`acceptance grep clean`). The remaining `_images` references are `TranslationProvider` naming/API helpers, not artifact-store legacy reads.

### Implementation and test disposition

- Deleted four production legacy sources listed above.
- Deleted `app/src/test/java/eu/kanade/translation/artifact/LegacyArtifactMigrationTest.kt` (1 test file).
- Added `app/src/test/java/eu/kanade/translation/artifact/ArtifactStoreTestFixture.kt` (1 helper).
- Modified 30 test files. They seed artifact stores, preserve the T930/T934 durability assertions, adapt artifact-only lifecycle behavior, or remove migration/authority assumptions. The modified files were `ChapterTranslationStoreArtifactMigrationTest`, `ChapterTranslationStorePatchPageGraceTest`, `TranslationManagerArtifactReadTest`, `TranslationManagerDeleteResetOrderingTest`, `AtomicChapterDocumentsPublicationLockTest`, `ChapterArtifactDeletionTest`, `ChapterArtifactSchemaGuardCacheTest`, `ChapterArtifactStoreManifestCoalescingTest`, `ChapterArtifactStoreRetireActiveRunTest`, `ChapterArtifactStoreStaleManifestRetryTest`, `ChapterArtifactStoreTest`, `ChapterCommitPointContractTest`, `ChapterRunRecordSchemaTest`, `CheckpointOcrTransactionTest`, `GroupCommitSliceBTest`, `SidecarCrashPublicationTest`, D5/D6/D7/D9/D10 coexistence tests plus `TranslationCoexistenceHarness`, `ChapterContextDurableSnapshotTest`, `ResetRetiresActiveRunTest`, `T934ProjectorRebuildTruthTest`, `DisplayTailDrainTest`, `EnvelopePlanPublicationTest`, `DrawPlanDtoRoundTripTest`, `StoreStatusProjectorRunRecordTest`, and `GroupCommitSliceCTest`.
- `ChapterArtifactDeletion` removes only the artifact manifest/tree. Legacy flat files and user data remain untouched.
- The pure in-memory mutation path still marks dirty state so existing flush/persist retry contracts remain intact.

## P2-02 — unified chapter store and lock discipline

The mandatory scoping memo was sent before implementation and ACKed. The approved split was:

1. `59c92ce` — `refactor(translation): introduce engine modes and reroute direct artifact-engine callers (1/3)`.
2. `98cbb5d` — `refactor(translation): unify locking and inline artifact engine (2/3)`.
3. `cc0b6fa` — `refactor(translation): remove duplicate store DTO bridge (3/3)`.
4. `49a443d` — `test(translation): adapt engine concurrency contract tests to facade-level serialization` (the extra commit was explicitly authorized after the contract-test seam failure).

### Lock inventory and ordering

- `ChapterTranslationStore.mutex` is the single facade lock for mutable store and artifact-engine state. Mutations use facade-owned seams such as `withArtifactEngineLocked` and the existing `mutex.withLock` paths.
- `PageStageLeaseTable` uses `synchronized(pageLeases)` for lease-table mutations and lock-free reader snapshots. When nested with a mutation, the order is facade `Mutex` → `pageLeases` monitor.
- `AtomicChapterDocuments`/artifact document publication uses a per-name document lock (`synchronized(lockFor(name))`, including artifact-open paths) for individual document publication/open operations. The order is facade `Mutex` → per-name document lock; document/open locks never acquire the facade `Mutex`.
- Pipeline-local coordination mutexes remain scoped to their own scheduler/render/rebuild responsibilities and delegate storage mutations through the facade; they are not alternate storage-engine ownership locks.
- The two-phase retention contract is unchanged: candidate crawl occurs outside the facade lock; the manifest is re-read and candidates are re-verified/deleted under the facade lock.

This gives the required invariant: facade `Mutex` → page leases or per-name document lock, with no reverse acquisition and no engine-level `@Synchronized` competing with the facade lock. T930 group commit, T934 stale-manifest retry, SAF publication locking, and `NonCancellable` teardown flush behavior were retained.

### Engine modes, seams, and implementation

The nullable-engine behavior is represented explicitly by `ChapterStoreEngineMode`:

- `Memory`: no filesystem target; mutations remain in memory and retain dirty probes.
- `LazyDurable`: a filesystem target exists and the artifact engine is opened on first write.
- `Durable`: the facade owns the opened `ChapterArtifactEngine`.

The seven direct typed artifact callers were rerouted through facade-owned seams: `BatchProgressProjector` (removed by P2-03), `AnalysisChunkPublication`, `BatchRenderJoin`, `ChapterProfileBatchCoordinator`, `EnvelopePlanPublication`, `ProfileFreezePublication`, and `StoreStatusProjector`. Commit 1 retained the engine synchronization while this reroute landed; commit 2 removed the engine-level synchronization only after no external typed callers remained. The artifact engine was renamed/focused as `ChapterArtifactEngine`, and the duplicate store-side DTO bridge was removed in commit 3. The public `ChapterTranslationStore` seam remains the caller-facing storage entry point.

### Authorized concurrency-test adaptation

The first full Dev run exposed a real seam regression in `ChapterArtifactEngineTest.concurrent candidate opens serialize against the same manifest snapshot()`: two direct engine calls could enter concurrently after engine-level synchronization was removed. The production paths were already routed safely through the facade. The test was adapted to launch the same concurrent candidate opens through `ChapterTranslationStore.withArtifactEngineLocked`, preserving the same manifest snapshot, expected page version, candidate outcomes, rejection reason, and durable-manifest assertions.

The artifact/engine test sweep found exactly one test that asserted engine-level concurrent serialization. The manifest-coalescing, stale-manifest-retry, and OCR checkpoint tests simulate concurrent writers sequentially and did not assume engine-level locking, so they were not changed for this seam move.

| Test | Old seam | New seam | Assertion status |
|---|---|---|---|
| `ChapterArtifactEngineTest.concurrent candidate opens serialize against the same manifest snapshot` | Direct concurrent `ChapterArtifactEngine.openCandidate` calls | Concurrent calls through `ChapterTranslationStore.withArtifactEngineLocked` | Assertions unchanged; expected values and invariant unchanged |

The focused test and the entire `eu.kanade.translation.artifact.*` package passed after this adaptation.

The P2-02 class acceptance search also returned no output:

```text
git grep -n 'class ChapterArtifactStore' -- app/src
```

## P2-03 — unified progress projection

Commit `6195c38` (`refactor(translation): derive batch progress from unified store projection, drop BatchProgressProjector`) removes the redundant `BatchProgressProjector` and makes `TranslationManager` consume the unified store projection through `TranslationProgressProjection`/store state. `StoreStatusProjector` remains the store-owned status projection. `TranslationBatchProgressTracker` was not touched. The required search:

```text
git grep -n 'BatchProgressProjector' -- app/src
```

returned no output. T934 reader-bar and projector-rebuild assertions were retained.

## Verification

All Gradle commands used `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `--no-parallel --max-workers=1`, and flavor-qualified tasks. The repository has `dev` and `standard` product flavors, so generic `:app:*Debug*` task names are ambiguous. Dev builds required a temporary copy of `app/src/standard/google-services.json` to `app/google-services.json`; each command removed it in `finally`, and it is absent from the final worktree.

### Compile and focused tests

```text
.\gradlew :app:compileDevDebugUnitTestKotlin :app:compileStandardDebugUnitTestKotlin --no-parallel --max-workers=1
BUILD SUCCESSFUL

.\gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.artifact.ChapterArtifactEngineTest.concurrent candidate opens serialize against the same manifest snapshot' --no-parallel --max-workers=1
BUILD SUCCESSFUL

.\gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.artifact.*' --no-parallel --max-workers=1
BUILD SUCCESSFUL

.\gradlew :app:testDevDebugUnitTest --tests eu.kanade.translation.ChapterTranslationStoreDefunctTest --tests eu.kanade.translation.ChapterTranslationStorePersistenceTest --tests eu.kanade.translation.manager.BatchProgressProjectorDurableReconstructionTest --tests eu.kanade.translation.TranslationManagerDeleteResetOrderingTest --no-parallel --max-workers=1
.\gradlew :app:testDevDebugUnitTest --tests eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest --tests eu.kanade.translation.coexistence.BatchDispatchResumeWiringTest --no-parallel --max-workers=1
BUILD SUCCESSFUL (both focused invocations)
```

### Full-suite flake records

The storage/legacy suites were not the source of any remaining failure. The failures below are the same load-sensitive end-to-end translation/coexistence pattern seen in Phase 1; each was retried in isolation three times and passed.

- Dev full run (2,020 tests): `StandardLaneMultiPageCompletionTest.fresh standard batch translates every page of a multi-page chapter` failed with `expected:<TRANSLATED> but was:<ERROR>`. Dev isolation retry: 3/3 green.
- Standard full run (2,020 tests): the same `StandardLaneMultiPageCompletionTest...` failed with `expected:<TRANSLATED> but was:<ERROR>`, and `StandardPipelineCoexistenceTest.flagged standard lane runs the real shell end-to-end with full OCR before translate` failed with `expected:<COMPLETE> but was:<TRANSLATE>`. Standard isolation retries: 3/3 green for each test.
- Final Dev full run (2,020 tests): `StandardPipelineCoexistenceTest...` again failed with the same `expected:<COMPLETE> but was:<TRANSLATE>` signature. Dev isolation retry after this run: 3/3 green.

No failure reproduced in a legacy/storage-related suite, and no assertion was weakened. The final full Standard pass was rerun after all isolation checks:

```text
.\gradlew :app:testStandardDebugUnitTest --no-parallel --max-workers=1
BUILD SUCCESSFUL in 3m 41s (2,020 tests)
```

### Assemble and APK inspection

```text
.\gradlew :app:assembleDevDebug --no-parallel --max-workers=1
BUILD SUCCESSFUL in 3m 32s
```

Inspected `app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk` as a ZIP. Results:

```text
best_int8.onnx: 0 entries
assets/models/ocr/**/*.md|yml|gitattributes: 0 entries
assets/models/segmentation/manga109_bubble_int8.onnx: 1 entry
assets/models/ocr/**/inference.onnx: 2 entries
  assets/models/ocr/paddle-v6-small/inference.onnx
  assets/models/ocr/paddle-v6-small/det/inference.onnx
```

The assemble emitted pre-existing D8 Kotlin metadata warnings but completed successfully. `app/google-services.json` is absent after the build.

## Final state and anomalies

- NNAPI/hardware-routing code remains untouched except for the single P2-00 diagnostic line. Required model assets and OCR runtime assets were not deleted.
- Legacy user files remain on disk; the app no longer reads them.
- The authorized concurrency-test adaptation is a separate fourth P2-02 commit, so the branch has nine commits after the final report commit rather than the original eight-commit estimate.
- Generic Debug Gradle task names are not valid unqualified verification targets in this flavorized repository; all verification used the documented `DevDebug`/`StandardDebug` equivalents.
