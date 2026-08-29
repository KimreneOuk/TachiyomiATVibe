# T909 Wave 1 — Delivery Report

Owner: Implementer · Date: 2026-08-29 · Branch: `optimize_translation_finishing_page` (no push)
Scope: Phases 1–5 of `dismantling-plan.md` — behavior-preserving moves only.
Verification: `JAVA_HOME=<Android Studio jbr>`; targeted tests then full filtered suite
`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "eu.kanade.tachiyomi.ui.manga.*" --tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.tachiyomi.data.*" --tests "eu.kanade.tachiyomi.extension.*"` (repo's working variant is `standardDebug`).

## Result summary

- **7 commits landed, 0 phases reverted.** Full filtered suite green (1073 tests, 0 failures/errors) after every phase.
- 9 new files created (~1,958 lines); 6 god-files reduced 13,752 → 11,986 lines (−1,766).
- Wave-1 diff total: 15 files, +2,055 / −1,669.

| Phase | Commit(s) | Gate result |
|---|---|---|
| 1 | `b8a0a74` | full suite 1073/0/0 |
| 2 (rescue) | `a1f5afc` | 3 targeted gates + full suite green |
| 2b (retention, optional) | `066df4e` | 3 targeted gates + full suite green |
| 3a | `66d55f9` | TranslationManagerArtifactReadTest + full suite green |
| 3b | `9d6153b` | 4 targeted gates + full suite green |
| 4 | `afc1e28` | ProviderRequestGovernorTest + full suite green |
| 5a | `4e0591d` | BoxGeometryTest + full suite green |
| 5b | `0f7c4e1` | BoxGeometryTest green; full suite green (1073/0/0) |

## Phase 1 — Pipeline decode/memory utilities · `b8a0a74`

Files: new `translation/pipeline/PageDecode.kt` (206), new `translation/pipeline/MemoryGovernance.kt` (124); `TranslationPipeline.kt` 5,300 → 5,107 (−224 body, +13 stubs/imports).

Moved: `forceReleaseNativeBuffers`, `preflightAnalyzeGate`, `preflightInpaintGate`, `reclaimTranslationMemory`, `logDecodeDecision`, `toMiB`, `handleCriticalTranslationOom` → `MemoryGovernance` (internal object); `decodePageBitmapAtSize`, `decodePageBitmapForTranslation`, `computeSourceFingerprint`, `batchExpectedFingerprints`, `DecodedPage`, `LowMemoryDecodeDeferredException`, `LowMemoryRecognitionDeferredException` → `PageDecode.kt`. `OnnxPhaseResult` stayed (moves with the Phase 14 ONNX phase); `persistPageWithOomRecovery` stayed (candidate 2/Phase 6); `getChapterPages` stayed.

Mechanics: engine reads injected as getters (`recognitionEngine: () -> PageRecognitionEngine`); `batchExpectedFingerprints` takes the four engine-cache values as params (signature typed `Any` because the private `EngineSignature` type cannot be exposed; body verbatim). Pipeline keeps same-signature private delegating stubs (plus the public `forceReleaseNativeBuffers()` used by ChapterTranslator), so zero call-site churn. Removed now-unused imports (`coil3.imageLoader`, `java.security.MessageDigest`, `StageFingerprints`).

Deviations: (a) moved helpers are members of internal objects rather than bare top-level functions — the repo's `logcat` extension needs a receiver, and the receiver class name becomes the default log tag (precedent: `batch/BatchOsmPolicy`-style extractions); (b) consequently the default log tag for moved log lines changes from `TranslationPipeline` to `MemoryGovernance`/`PageDecode` (log messages, levels, and throwables unchanged).

## Phase 2 — Legacy rescue machine · `a1f5afc` (+ optional 2b · `066df4e`)

2a: new `artifact/LegacyArtifactRescue.kt` (479); `ChapterArtifactStore.kt` 1,499 → 1,098. Moved: `rescueLegacy`, `reconcileLegacyPreservation`, `verifyLegacyArtifactHealth`, `LegacyArtifactHealthResult`, `deletePreservedLegacyInput`, `cleanupOpenedChapterJunk`, `PreservationResult`, `preserveLegacyInput`, `preservedResult`, `artifactGraphIsComplete`, `identityOf`, `preservationTargetName`, `attachGlossaryIfNeeded`, `refuseFutureDocument`, `MAX_PRESERVATION_RENAME_ATTEMPTS`.

- `internal class LegacyArtifactRescue(io, layout, store)` built lazily by the store (`by lazy` — avoids `this`-escape in ctor); bodies verbatim, with same-name private delegating stubs for the 9 store-side helpers it shares with the live path.
- Locking preserved: the store's `@Synchronized` entry points (`loadOrMigrate`, `reconcileLegacyPreservation`, `verifyLegacyArtifactHealth`) stay as public delegating stubs — tests (`ChapterArtifactStoreTest`) and `ChapterTranslationStore` still call `store.reconcileLegacyPreservation`/`store.verifyLegacyArtifactHealth`.
- Visibility bumps private→internal (store): `publishManifestInternal`, `stampChapterKey`, `readManifestDocument`, `backupName`, `displayBaseIsValid`, `stagePayloadIsValid`; `PageArtifactRecord.stage` member-extension → top-level `internal` in the same file (member extensions can't be called cross-class).
- Deviation from triage member list: `futureBackupPresent` stayed in the store — it is part of the manifest-publication gate used by `publishManifestInternal` AND `recordDurableFailure`; moving it would have inverted the dependency for no isolation gain.

2b (optional, first landed green so executed): new `artifact/ArtifactRetention.kt` (114); store 1,098 → 998. Moved `reconcileRetention` body, `sweepDirectory`, `isRetained`, `retainedImageGenerations`, `reachablePaths`, `RetentionResult` (top-level public — was public nested). `ArtifactRetention(io, layout)` is stateless (no store dep); the store keeps the `@Synchronized` public stub with the lifecycle-contract KDoc.

Gates both: `ChapterArtifactStoreTest`, `LegacyArtifactMigrationTest`, `ChapterTranslationStoreArtifactMigrationTest` → green; full suite → green.

## Phase 3a — Manager legacy decode · `66d55f9`

New `legacy/LegacyFlatFileDecoder.kt` (80); `TranslationManager.kt` 2,056 → 2,026. Moved `decodeLegacyChapterStatus`, `statusFromReadablePages`, `decodeLegacyChapterTranslation`, `quarantineCorruptTranslationFile`, `quarantineCorruptDocument`, and the manager's `Json { ignoreUnknownKeys = true }` instance (§6.1 consolidation; the decoder now owns the single instance).

- Manager keeps: `private val legacyPageJson = LegacyFlatFileDecoder.legacyPageJson` — a real field because `TranslationManagerArtifactReadTest` reflection-writes it via Unsafe/setField (a getter-only property would break the seam); same Json config, so the field write stays behaviorally neutral.
- `quarantineCorruptDocument` stays as an `internal` delegating member on the manager (test seam, ArtifactReadTest:254). `UniFile.registryKey()` untouched.

Gate: `TranslationManagerArtifactReadTest` → green; full suite → green.

## Phase 3b — Store companion legacy block · `9d6153b`

New `artifact/LegacyChapterMigrationSource.kt` (301) + `artifact/ChapterArtifactManifestReader.kt` (47); `ChapterTranslationStore.kt` 2,552 → 2,268.

- Source moved: `openInternal` (whole, incl. the flat decode + errorMessage migration), `ArtifactLoad`, `migrateArtifactManifest` (+ KDoc), `legacyIdentityOf`, `legacyGlossaryName`, `CleanedFileValidation`, `cleanedFileValidationOf`, `IMAGE_DIMENSION_TOLERANCE_PX`, `ARTIFACT_MIGRATION_LOCKS`/`artifactMigrationLock`, and the companion `legacyPageJson` (now initialized from `LegacyFlatFileDecoder`'s single instance — §6.1 fully consolidated to one Json in the app).
- Reader moved: `ArtifactManifestProbe` (top-level internal) + both `probeArtifactManifest` overloads; the store companion keeps delegating stubs (Manager calls `ChapterTranslationStore.probeArtifactManifest` 5×; ArtifactReadTest 129–160).
- Kept at old qualified names (tests reference): `open`/`openArtifact` (delegating to `LegacyChapterMigrationSource.openInternal`), `lazy`, `artifactImageProbe` (`@Volatile internal var` — the source reads it through a private getter so probe injection still lands), `DEFAULT_FILE_NAME`, probe stubs, and a private `artifactMigrationLock` stub (used by `ensureArtifactStoreLocked`).
- `loadGlossary` bumped private→internal (moved `openInternal` calls it after store construction).

Gates: `TranslationManagerArtifactReadTest`, `ChapterTranslationStoreArtifactMigrationTest`, `artifact.ChapterArtifactStoreTest`, `artifact.LegacyArtifactMigrationTest` → green; full suite → green.

## Phase 4 — Provider failure classification · `afc1e28`

New `translator/ProviderFailureClassification.kt` (195); `ProviderRequestGovernor.kt` 828 → 638. Moved verbatim: `RetryAfterParser`, `parseRetryAfterMillis`, `classifyHttpFailure`, `classifyHttpFailureWithRetryAfterMillis`, `classifyProviderFailure`, `safeAdd` (with the TranslationRetry KDoc).

Same package as all ~10 consumers → **zero import edits needed** anywhere; `ProviderFailure`/`ProviderFailureException`/`ProviderFailureKind`/`ProviderFailureRetryability` stay in the governor file (same package). Removed 3 now-unused governor imports (`ZonedDateTime`, `DateTimeFormatter`, `ceil`).

Gate: `translator.ProviderRequestGovernorTest` (pins `RetryAfterParser.parseMillis`) → green; full suite → green.

## Phase 5 — RoiPageRecognitionEngine split (two commits)

5a · `4e0591d`: new `recognition/VerticalLineOcr.kt` (412); engine 1,517 → 1,174. Moved: `cropBitmap`, `recognizeMultiLine`, `recognizeSingleLine`, `recognizeDetColumns`, `recognizeHeuristicColumns`, `rotateCcw`, `recognizeVerticalColumnPerChar`, `detectVerticalGlyphRows`, `detectVerticalColumns`, plus the 8 ink-gap/det/confidence constants with their comments. Engine keeps same-signature private stubs for `cropBitmap` (3 analyze call sites) and `recognizeMultiLine` (3 call sites); `language` passed as a param and the `closed`-field checks injected as `isClosed: () -> Boolean` (2 body sites, cancellation checkpoint still live-checked).

5b · `0f7c4e1`: new `recognition/OcrBlockDeduplication.kt` (242); engine 1,174 → 953. Moved: `RecognizedBlock` + `RecognizedAnalyzeResult` (top-level internal, same package → engine call sites compile unchanged), `suppressCrossLabelDuplicates`, `dedupeTextDetections`, `removePostOcrDuplicateBlocks`, `findParentBubble`, `parentContainmentScore`, `normalizeOcrText`, `isTextBoxDuplicate`, `trimParentBbox`, `selectParentBubble`, `MIN_PARENT_TEXT_OVERLAP_FRACTION`. Engine keeps 5 same-signature private stubs (analyze call sites untouched). All pure over `Detection`/bbox data — no engine state. This file is now the shared geometry home (plan §5.3); switching `OnnxPageTextDetector` to it is the sanctioned follow-up micro-commit, not done here (scope discipline).

Gates: `recognition.BoxGeometryTest` → green after each commit; full filtered suite → green after 5a; green after 5b (final run).

## Deviations & notes (all phases)

1. Log-tag drift for moved logging call sites (Phase 1 only): `MemoryGovernance`/`PageDecode` instead of `TranslationPipeline` — inherent to receiver-based default tags; documented above.
2. Stubs added: every moved public/test seam retains a same-signature delegating stub at the old qualified name (`open`/`openArtifact`/`lazy`/`probeArtifactManifest`/`artifactImageProbe`/`quarantineCorruptDocument`/`reconcileLegacyPreservation`/`verifyLegacyArtifactHealth`/`reconcileRetention`, plus private in-class stubs so god-file call sites stayed untouched). Stubs are deletable in later waves.
3. Visibility bumps only where extraction requires (all within `:app`, module-scoped `internal`): 6 store helpers + `loadGlossary` + `PageArtifactRecord.stage`; no signature or behavior drift anywhere.
4. `futureBackupPresent` not moved (see Phase 2) — deliberate scope call.
5. One transient incident during 5b: a scripted edit accidentally removed `assignPanels` from the engine; caught by compile and fully reverted (`git checkout`) before redoing the edit — never committed.
6. No test files were modified; no logic, string, or ordering changes were introduced.

## God-file sizes (before → after Wave 1)

| File | Before | After |
|---|---|---|
| `TranslationPipeline.kt` | 5,300 | 5,103 |
| `ChapterTranslationStore.kt` | 2,552 | 2,268 |
| `TranslationManager.kt` | 2,056 | 2,026 |
| `recognition/RoiPageRecognitionEngine.kt` | 1,517 | 953 |
| `artifact/ChapterArtifactStore.kt` | 1,499 | 998 |
| `translator/ProviderRequestGovernor.kt` | 828 | 638 |
