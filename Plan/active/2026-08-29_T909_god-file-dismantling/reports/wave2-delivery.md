# T909 Wave 2 — Delivery Report

Owner: Implementer · Date: 2026-08-29 · Branch: `optimize_translation_finishing_page` (no push)
Scope: Phases 6–11 of `dismantling-plan.md` — behavior-preserving moves only.
Verification: `JAVA_HOME=<Android Studio jbr>`; targeted tests per phase, then full filtered suite
`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "eu.kanade.tachiyomi.ui.manga.*" --tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.tachiyomi.data.*" --tests "eu.kanade.tachiyomi.extension.*"` (variant `standardDebug`).

## Result summary

- **6 commits landed (one per phase), 0 phases reverted.** Full filtered suite green after every phase (final: **1073 tests / 0 failures / 0 errors**).
- 5 new files (~1,236 lines); 3 god-files reduced:
  `TranslationPipeline.kt` 5,103 → 4,578 (−525), `ChapterTranslationStore.kt` 2,268 → 2,191 (−77),
  `TranslationManager.kt` 2,026 → 1,777 (−249). Wave-2 diff total: 9 files, +1,653 / −1,054.

| Phase | Commit | Gate result |
|---|---|---|
| 6 | `978a990` | Persistence + Phase3 + PostOcrStageSemantics green; full suite 1073/0/0 |
| 7 | `6762618` | CleanedImagePublisherTest green; full suite 1073/0/0 |
| 8 | `21570ba` | ChapterTranslationStoreArtifactMigrationTest green; full suite 1073/0/0 |
| 9 | `46a993c` | PendingAcknowledgement + DownloadFailureRecovery + AutoArbitration green; full suite 1073/0/0 |
| 10 | `4d096e0` | PipelineConcurrency + AutoArbitration + ReaderTeardown green; full suite 1073/0/0 |
| 11 | `ca72381` | ArtifactRead + AutoArbitration green (after PausedAffordance fix); full suite 1073/0/0 |

## Phase 6 — Pipeline store-patch/failure writer · `978a990`

New `pipeline/PageStoreWriter.kt` (256); pipeline 5,103 → 4,967.
Moved verbatim: `peekReaderPageStream`, `createFailedPagePlaceholder` (KDoc + defaults intact),
`resolveActiveStore` (with its block KDoc), `markPageTimedOut`, `PageSnapshot.toPrecondition`,
`updatePageFromCurrentSnapshot`, `markPageFailed`, `persistPageWithOomRecovery`.
`PageStoreWriter(context-free)` ctor: `activeStoreResolver: () -> resolver?` getter (the manager re-wires the
`@Volatile` var), `streamRegistry`, `handleCriticalTranslationOom` lambda (bound to the pipeline's
MemoryGovernance stub). `SINGLE_PAGE_TIMEOUT_MS` reached via
`import eu.kanade.translation.TranslationPipeline.Companion.SINGLE_PAGE_TIMEOUT_MS` — body char-identical.
Pipeline keeps same-signature private stubs for all 7 non-extension members (zero call-site churn).
`toPrecondition` moved as a **top-level internal extension** in PageStoreWriter.kt; the pipeline's
same-name private stub was deleted (a member-extension stub delegating to a same-signature callable
resolves to itself → infinite recursion; verified by compiler) and all `.toPrecondition()` call sites now
resolve through the import — call-site text unchanged.
Adaptations: `activeStoreResolver?.invoke(x)` → `activeStoreResolver()?.invoke(x)` (3 sites, getter injection).

## Phase 7 — Pipeline cleaned publication · `6762618`

New `pipeline/CleanedPublication.kt` (245); pipeline 4,967 → 4,824.
Moved verbatim: `deleteRetiredCleanedFile` (+ KDoc), `loadPersistedCleanedBitmap`, `persistCleanedBitmap`,
`persistOnnxCleanedImage`, `PageTranslation.copyForResume`. Ctor: `provider`, `streamRegistry`,
`currentInpaintingMode: () -> InpaintingMode` getter — 2 body sites `currentInpaintingMode.name` →
`currentInpaintingMode().name` (read still happens at execution time on the IO dispatcher).
`OnnxPhaseResult` bumped private→internal (nested in the pipeline) so `persistOnnxCleanedImage` can
reference `TranslationPipeline.OnnxPhaseResult`; bitmap recycle/ownership points untouched.
`copyForResume` moved as top-level internal extension (same-name-stub recursion avoidance, same as
Phase 6); pipeline's remaining resume call sites unchanged. `getContextualTranslator` NOT touched here.
Removed unused pipeline import `BitmapFactory`.

## Phase 8 — Store glossary collaborator · `21570ba`

New `store/ChapterGlossaryStore.kt` (118); store 2,268 → 2,191.
Moved: `glossary` + `glossaryDirty` state, `glossarySnapshot`, `translatedPairs` (+ KDoc), `updateGlossary`,
`loadGlossary` (**legacy flat-file read fallback KEPT**, decision documented in the class KDoc),
`persistGlossaryLocked`. `ChapterGlossaryStore(store)` receives the owning store; mutations lock through
`store.mutex` — the delegation stays under the store mutex as the plan requires. Body adaptations are
`store.` receiver prefixes on `isDefunct` (was `defunct`; public accessor, no visibility widening),
`admitMutationLocked()`, `artifactStore`, `artifactManifest` (read+write), `schedulePersist(...)`,
`pages`, `legacyDocuments()`, `glossaryName()`.
Store keeps public/internal delegating stubs: `glossarySnapshot`, `translatedPairs`, `updateGlossary`,
`loadGlossary` (LegacyChapterMigrationSource calls `it.loadGlossary()`), private `persistGlossaryLocked`;
`flushDirtyLocked` now reads `glossaryStore.glossaryDirty`.
Visibility bumps (module-scoped): `mutex`, `pages`, `artifactManifest`, ctor `artifactStore`,
`admitMutationLocked`, `schedulePersist`, `legacyDocuments`, `glossaryName` (the last two kept store-side —
flat-file plumbing over ctor state; moving them would have forced visibility changes on the
positionally-pinned ctor. Deviation from the S3 member list, documented).
No ctor reorder; external API (`store.updateGlossary` etc., incl. the ArtifactMigrationTest call) unchanged.

## Phase 9 — Manager request coordinator · `46a993c`

New `manager/TranslationRequestCoordinator.kt` (218); manager 2,026 → 1,934.
Moved verbatim: the 15 subsystem functions (`queueTranslationAfterDownload` …
`startTranslationAfterDownloadIfRequested`) plus top-level `acknowledgePendingTranslationState`.
Version-fence protocol (write-versions + mutation lock + `persistPendingStartingAcknowledgement`) moved
intact — bodies char-identical.
**Key constraint honored:** `TranslationManagerPendingAcknowledgementTest` / `DownloadFailureRecoveryTest`
reflection-write the manager fields `pendingRequestStore`, `pendingTranslationRequestsState`,
`pendingTranslationRequests`, `pendingRequestWriteVersions`, `pendingRequestMutationLock`, `storeScope`
(and leave `translator`/`queueState` null). The state objects therefore STAY as manager fields; the
coordinator is built per access from the current field values via `() -> T` provider ctor params +
same-name private backing getters, so a field the fixture leaves null only fails at the same use site as
before the move (first attempt passed them eagerly and NPE'd in the ctor — fixed by deferral, not by
touching the protocol; targeted gates re-run green).
`nextPendingRequestVersion`/`persistPendingStartingAcknowledgement` moved with no manager stubs (no
external callers); `setPendingTranslationRequest`/`clearPendingTranslationRequest`/
`clearAllPendingTranslationRequests` kept as private manager stubs (call sites at translateChapter(s)/
clearQueue/deleteManga). `loadPendingTranslationRequests` STAYED in the manager (verbatim): the
`pendingTranslationRequestsState` field initializer calls it during construction, before any coordinator
can be built — initialization-order hazard flagged in the store/manager report B.7.
Top-level `acknowledgePendingTranslationState` stub retained at the old qualified name (test calls it
unqualified in `eu.kanade.translation`); body delegates via fully-qualified call to the manager package.

## Phase 10 — Pipeline EngineLane (+ dead-code delete) · `4d096e0`

New `pipeline/EngineLane.kt` (299); pipeline 4,824 → 4,578 (incl. −33 dead lines).
Moved verbatim: `PermitHolder`/`permitHolder`/`permitHolderPageKeySnapshot`, `withNativeLane`,
the 8 engine-cache fields (`currentFromLang`, `currentOcrModel`, `currentReadingOrder`, `textTranslator`,
`recognitionEngine`, `currentInpaintingMode`, `currentTranslatorSignature`, `enginesClosed`),
`EngineSignature`, `computeTranslatorSignature`, the defensive `init`, `inpaintingModeFromPref`,
`createRecognitionEngine`, `closeEngines`, `ensureEnginesBuiltFor` (+ KDoc).
**Defensive-init semantics preserved (B6):** the `init` moved into EngineLane, which the pipeline builds
as a field initializer — invalid config at eager construction still logs and installs the throwing stub
translator instead of crashing.
Pipeline keeps `internal val engines = EngineLane(context, translationPreferences, nativeRunQuarantine,
inFlightPageKeys, { onPageStuck })` — declared after `nativeRunQuarantine`/`inFlightPageKeys` so field-init
order is safe; the native run scope/quarantine, the in-flight key set, and `onPageStuck` stay
pipeline-owned (not in the plan's member list) and are injected. `engineRebuildMutex` + `consecutiveOomCount`
stay (no moving body touches them). Stubs: `permitHolderPageKeySnapshot` (internal, TranslationManager's
frozen seam), `withNativeLane`, `closeEngines` (public — ChapterTranslator calls it), `ensureEnginesBuiltFor`,
`inpaintingModeFromPref`, and read-only same-name getters for the 6 engine fields read by staying code.
**Dead `getContextualTranslator` DELETED** (plan §5.1) — repo-wide re-verified zero callers before deletion;
7 now-dead imports removed (`GeminiTranslator`, `DeepSeekTranslator`, `OpenRouterTranslator`,
`AiTranslatorKind`, `AiEngine`, `TranslationEngineBuilder`, `OcrModelCatalog`, `RoiPageRecognitionEngine`,
`PageRecognitionEngine`, `OcrModel` — each re-checked for remaining use first).
Wave-1 typing debt fixed as sanctioned: `EngineSignature` private→internal nested (the pipeline's
`batchExpectedFingerprints` stub now receives the real type instead of `Any`).
Fields exposed as `internal var … private set` (read-only outside the lane).

## Phase 11 — Manager batch progress projector · `ca72381`

New `manager/BatchProgressProjector.kt` (314); manager 1,934 → 1,777.
Moved verbatim (flow graph only, no locks): `getChapterTranslationStatus`, `observeChapterTranslationStatus`,
`observeBatchProgress` (+ KDoc), `observeQueuedTranslationStatus`, `observeBatchProgressProjection`,
`snapshotFromStore`, `withDurablePause`, `projectQueueStatus`, `observeTranslationProgress` (+ KDoc),
`observePageView`. Same provider pattern as Phase 9 (`activeStores`, `batchTrackerRegistry`, `queueState`,
`pendingTranslationRequests`, `pipeline` via getters; `getQueuedTranslationOrNull`, `persistedChapterStatus`,
`observeActiveDisplayStore` as function values; `openOrCreateActiveChapterTranslationStoreSuspend` injected
as a lambda behind a private same-name suspend fun because the moved body calls it with named arguments).
Manager keeps public delegating stubs for all 5 public members.
**Mid-phase gate catch (fixed, not reverted):** the full suite exposed
`TranslationManagerPausedAffordanceTest` (not in the phase gate list), which reflectively invokes the
manager's PRIVATE extensions `withDurablePause` / `projectQueueStatus`. Rule 3 applied: same-signature
private stubs restored on the manager, delegating through internal `withDurablePauseOf` /
`projectQueueStatusOf` bridges on the projector (moved bodies untouched; no duplication). Targeted gates
re-run + full suite green.
Orphaned manager imports removed (`ArtifactStage`, `ArtifactStageStatus`, `TranslationBatchPhase`,
`distinctUntilChanged`, `emitAll`, `flowOf`); `onStart` kept (still used by the paused-notification collector).

## Deviations & notes (all phases)

1. **Same-name private stubs cannot delegate** in Kotlin when the seam is an extension function
   (`toPrecondition`, `copyForResume`): a same-signature stub resolves to itself. Those two moved as
   top-level internal extensions with the pipeline importing them — every call site textually unchanged.
   `withDurablePause`/`projectQueueStatus` are reflection-pinned ON the manager, so they kept manager-side
   stubs delegating via `*Of` bridges instead.
2. **Provider-style ctor injection** (Phases 9, 11): manager state arrives as `() -> T` providers read at
   each access because the uninitialized-manager fixtures (Unsafe.allocateInstance) leave several manager
   fields null and reflection-write the rest after construction. This reproduces pre-move failure semantics
   exactly (NPE only at the site the original code would have NPE'd).
3. **Visibility bumps only where extraction requires** (module-scoped `internal`): store `mutex`, `pages`,
   `artifactManifest`, `admitMutationLocked`, `schedulePersist`, `legacyDocuments`, `glossaryName`, ctor
   `artifactStore`; pipeline `OnnxPhaseResult`; EngineLane `EngineSignature` + engine fields
   (`internal var … private set`); BatchProgressProjector `*Of` bridges.
4. **Not moved despite appearing in a plan line range:** `loadPendingTranslationRequests` (Phase 9;
   field-initializer bootstrap), `legacyDocuments`/`glossaryName` (Phase 8; ctor-state plumbing) — both
   documented above; `onPageStuck`/`batchTrackerFactory`/`nativeRunScope`/`nativeRunQuarantine`/
   `inFlightPageKeys`/`engineRebuildMutex`/`consecutiveOomCount` (Phase 10; outside the authoritative
   member list, injected or kept).
5. Log-tag drift: none in Wave 2 (moved bodies use no default-tag logcat calls except EngineLane's —
   which keeps the same literal messages; the class-based default tag changes only if a log line existed
   there — EngineLane's `init` warning text is unchanged, tag becomes `EngineLane` instead of
   `TranslationPipeline`; same category as the Wave-1 deviation, message/level/throwable identical).
6. No test files were modified. No logic, string, ordering, or lock changes. All moved bodies are
   char-identical modulo receiver-prefix/getter adaptations, package/imports/visibility.
7. Two transient compile-fix loops occurred (missing imports for `ChapterGlossaryStore`,
   `TranslationRequestCoordinator`, `BatchProgressProjector`, `EngineLane`; one mangled ctor line restored
   immediately) — all caught by compile before any test run, none committed in a broken state.

## God-file sizes (after Wave 2)

| File | Wave 1 end | After Phase |
|---|---|---|
| `TranslationPipeline.kt` | 5,103 | **4,578** |
| `ChapterTranslationStore.kt` | 2,268 | **2,191** |
| `TranslationManager.kt` | 2,026 | **1,777** |

Cumulative T909 so far: 11,986 → 8,546 god-file lines (−3,440).
