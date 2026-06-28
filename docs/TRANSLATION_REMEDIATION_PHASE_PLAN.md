# Translation Remediation Phase Plan

Date: 2026-06-28

Purpose: break the translation audit into subagent-sized implementation quests with explicit tests, validation gates, rollback criteria, and supervisor checks.

Source audit: `docs/APK_SIZE_CLEANUP_AUDIT.md`.

## Ground rules for every subagent

- Do not combine unrelated phases.
- Keep changes small and reversible.
- Add or update tests before changing behavior where practical.
- Do not remove bundled ONNX/Paddle/AOT models; model download-on-demand is out of scope.
- Do not remove Conscrypt unless minSdk is raised to API 29+; current minSdk 26 still needs it.
- Do not remove image-decoder without full reader image-format validation.
- Prefer behavior-preserving fixes before APK-size or dependency-removal work.
- Report exact files changed, tests run, and tests not run.

## Supervisor validation ladder

Use the smallest useful command first, then widen only after the subagent passes local checks.

### Fast checks

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
```

### Focused unit checks

Use for targeted packages when only one subsystem changed:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ChapterTranslationStorePersistTest" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.*" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.scheduling.*" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.inpainting.*" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ocr.*" --no-daemon
gradlew.bat :domain:test --tests "tachiyomi.domain.translation.*" --no-daemon
```

### Compile gate

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

### Full app unit gate

```powershell
gradlew.bat :app:testStandardDebugUnitTest --no-daemon
```

### APK gate

Run only for dependency/build-size work or final integration:

```powershell
gradlew.bat :app:assembleStandardDebug --no-daemon
```

## Phase 0 — Baseline and safety harness

Goal: make future changes measurable and catch regressions before optimizing.

### Quest 0A: Store persistence instrumentation tests

Target files:

- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePersistTest.kt`

Expected work:

- Add a test-only way to count persist invocations without production logging noise.
- Add tests for which `PageTranslation` updates are durable vs transient.
- Verify no-op/transient updates do not force disk writes.

Required test units:

- `updatePage_transientStatus_doesNotPersistImmediately`
- `updatePage_blocksPresent_persistsDurableSnapshot`
- `multipleDurableUpdates_canBeObservedByPersistCounter`

Supervisor acceptance:

- Tests fail on the current over-persist behavior where intended.
- Tests do not require Android device/emulator.
- Existing `ChapterTranslationStorePersistTest` still passes.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ChapterTranslationStorePersistTest" --no-daemon
```

### Quest 0B: Race/concurrency regression fixtures

Target files:

- `app/src/test/java/eu/kanade/translation/scheduling/`
- New or existing translation manager/pipeline test seam if available.

Expected work:

- Add pure-JVM tests for thread-safe key-set behavior and queue restore/add ordering where possible.
- If direct `TranslationManager` construction is too dependency-heavy, isolate small helpers rather than adding fragile integration tests.

Required test units:

- `inFlightPageKeys_concurrentRemoveAndClear_doesNotThrow`
- `queueRestore_doesNotClobberConcurrentAdd`

Supervisor acceptance:

- No sleeps longer than 250 ms.
- Use deterministic coroutine test dispatchers where available.
- Stress loops are bounded and stable.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.scheduling.*" --no-daemon
```

## Phase 1 — Highest-confidence performance fixes

Goal: speed up auto/manual/pre-translate without changing OCR/inpaint/model output.

### Quest 1A: Coalesce translation-store persistence

Target files:

- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- `app/src/test/java/eu/kanade/translation/ChapterTranslationStorePersistTest.kt`

Problem:

- `updatePage()` can rewrite full chapter JSON on many stage updates.
- Batch/pre-translate suffers O(chapter²) JSON/disk work.

Implementation direction:

- Keep in-memory state updates immediate.
- Debounce/coalesce disk persistence for durable updates.
- Provide an explicit `flush()`/`close()` path so completion and app shutdown do not lose durable state.
- Preserve current atomic write behavior: temp file then rename.

Required test units:

- `coalescedDurableUpdates_writeOnceAfterFlush`
- `flush_persistsLatestPageState`
- `open_afterFlush_restoresLatestState`
- `preRegisterPages_doesNotPersistUntilDurableChange`
- `clearTransientQueuePages_doesNotDropDurableBlocks`

Supervisor acceptance:

- Pre-translate progress still appears live through `StateFlow`.
- Disk writes are reduced in unit tests.
- Crash-safety is no worse than current temp+rename approach after flush.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ChapterTranslationStorePersistTest" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.*" --no-daemon
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

### Quest 1B: Reduce batch tracker store-write amplification

Target files:

- `app/src/main/java/eu/kanade/translation/batch/TranslationBatchProgressTracker.kt`
- `app/src/test/java/eu/kanade/translation/batch/BatchProgressReconcilerTest.kt`
- `app/src/test/java/eu/kanade/translation/batch/BatchOomPolicyTest.kt`

Problem:

- Tracker mirrors stage changes into `ChapterTranslationStore`, multiplying full-chapter persistence.

Implementation direction:

- Prefer read-only projection from store state.
- If tracker must write, write only terminal/durable transitions and coalesce.
- Avoid recomputing full snapshots on every transition when a conflated tick is enough.

Required test units:

- `transition_runningStage_doesNotPersistTrackerOnlyNoise`
- `transition_terminalStage_updatesProgressSnapshot`
- `tracker_finish_disposesOrStopsTicking`
- `tracker_reconcileFromStore_matchesExistingSnapshotSemantics`

Supervisor acceptance:

- Batch progress UI semantics remain correct.
- Store write count drops in tests from Quest 1A.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.*" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.ChapterTranslationStorePersistTest" --no-daemon
```

### Quest 1C: Conflate/debounce reader live-store collection

Target files:

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderPageWarmWindowTest.kt`

Problem:

- Reader scans chapter pages on every store emission.

Implementation direction:

- Add `.conflate()` or short debounce to live translation store collection.
- Limit work to warm-window pages where possible.
- Do not delay manual page result visibility enough to feel broken.

Required test units:

- `liveStoreBurst_coalescesReaderUpdates`
- `manualPageUpdate_visibleAfterDebounceWindow`
- `warmWindowOnly_pagesOutsideWindowNotScannedRepeatedly`

Supervisor acceptance:

- Manual translate still updates the visible page promptly.
- Auto-translate burst updates no longer trigger one scan per store emission.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.*" --no-daemon
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

## Phase 2 — Race-condition fixes

Goal: remove high-risk concurrency hazards before broader refactors.

### Quest 2A: Serialize `TranslationManager` lifecycle maps

Target files:

- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- New tests if construction seams are available.

Problem:

- `activeTranslationStores`, `activeStoreJobs`, and `batchTrackers` are plain mutable maps accessed across dispatchers.

Implementation direction:

- Use a lifecycle `Mutex` for compound operations.
- If using `ConcurrentHashMap`, still lock check-then-act store creation.
- Keep StateFlow snapshot updates consistent.

Required test units:

- `concurrentRegisterUnregister_activeStoreMapRemainsConsistent`
- `concurrentOpen_sameChapter_returnsSingleStoreInstance`
- `batchTrackerRegisterDispose_snapshotDoesNotThrow`

Supervisor acceptance:

- No `HashMap` mutation from multiple threads without protection.
- No deadlock between store mutex and manager lifecycle mutex.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

### Quest 2B: Make `inFlightPageKeys` thread-safe

Target file:

- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`

Problem:

- Mutable set is touched by worker, watchdog, and close paths.

Implementation direction:

- Replace with `ConcurrentHashMap.newKeySet<String>()`, or confine all mutations.
- Update stale comment claiming permit serialization.

Required test units:

- `inFlightPageKeys_watchdogAndFinallyConcurrentRemove_safe`
- `inFlightPageKeys_closeClearConcurrentWithRemove_safe`

Supervisor acceptance:

- No broad pipeline refactor.
- Dedup behavior preserved.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

### Quest 2C: Engine rebuild/close permit discipline

Target files:

- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`

Problem:

- Batch can call `ensureEnginesBuiltFor()` before acquiring translation permit.
- KDoc says close sites hold permit, which is not always true.

Implementation direction:

- Move batch engine ensuring inside permit-protected section, or guard rebuild/close with same lifecycle lock.
- Keep nativeGuard safety behavior.
- Update comments to match actual guarantees.

Required test units:

- `batchEnsureEngines_doesNotCloseDuringSinglePageHttpWithoutRetryPlan`
- `recognitionCloseKdocInvariant_matchesBatchRebuildPath` as comment/doc validation if no runtime seam exists.

Supervisor acceptance:

- No native session close while a native run can be active without `nativeGuard` fallback.
- Existing retry semantics preserved.

Validation:

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
```

## Phase 3 — Memory-pressure and lifecycle cleanup

Goal: make long pre-translate sessions safer on 6 GB devices.

### Quest 3A: App-level memory-pressure forwarding

Target files:

- Application startup file that owns `ComponentCallbacks2` registration.
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`

Problem:

- Batch pre-translation can run without reader activity, so reader-scoped `onTrimMemory` is insufficient.

Implementation direction:

- Register application-level memory-pressure callback.
- Forward critical/moderate trim events to `TranslationManager.onMemoryPressure()`.
- Avoid duplicate calls when reader is also open, or make repeated calls idempotent.

Required test units:

- `applicationTrimMemory_forwardsToTranslationManager`
- `duplicateTrimMemory_isIdempotent`

Supervisor acceptance:

- No Activity leak.
- No direct singleton leak beyond existing DI/scope patterns.

Validation:

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
```

### Quest 3B: Deferred native close and batch tracker disposal

Target files:

- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/batch/TranslationBatchProgressTracker.kt`

Problem:

- `close()` can leak native sessions when `nativeGuard.tryLock()` fails.
- `closeEngines()` can return early if permit unavailable.
- Completed batch trackers can linger.

Implementation direction:

- Queue a deferred close after guard/permit becomes available.
- Ensure final tracker snapshot is emitted before disposal.
- Keep leak-instead-of-crash behavior as fallback.

Required test units:

- `closeWhileNativeGuardHeld_schedulesDeferredClose`
- `closeEnginesPermitBusy_schedulesDeferredClose`
- `batchComplete_disposesTrackerAfterFinalSnapshot`

Supervisor acceptance:

- No blocking UI thread waiting for native work.
- No SIGSEGV-prone forced close.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.*" --no-daemon
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

## Phase 4 — Lower-risk hot-path cleanup

Goal: remove small overhead and stale assumptions after core safety fixes.

### Quest 4A: Logging gate cleanup

Target files:

- `TranslationPipeline.kt`
- `AOTInpainting.kt`
- `PageInpaintingEngine.kt`
- `RoiPageRecognitionEngine.kt`
- `MangaOcrEngine.kt`
- `PaddleOcrV6SmallEngine.kt`
- `PaddleOcrV6DetEngine.kt`

Problem:

- Existing `translationDiagnostics()` gate is inconsistently applied.

Implementation direction:

- Keep WARN/ERROR unconditional.
- Gate per-page INFO timing/route logs.
- Avoid expensive string interpolation before diagnostics check.

Required test units:

- Prefer compile checks over brittle log tests.
- Add small pure tests only if a logging helper is introduced.

Supervisor acceptance:

- No loss of warnings/errors.
- Diagnostic preference still enables detailed logs.

Validation:

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
```

### Quest 4B: Remove dead code and unused allocations

Target files:

- `TranslationPipeline.kt`
- `MlKitFullPageRecognitionEngine.kt`
- `FastMarchingMethod.kt`
- Possibly `PreferenceValidator.kt`, `KeystoreApiKeyManager.kt`, `TranslationLifecyclePolicy.kt` after product decision.

Problem:

- Dead helpers/classes and a hot-path unused `offsets` allocation.

Implementation direction:

- Remove private dead helpers first.
- Remove `FastMarchingMethod.offsets` allocation.
- For public/planned classes, either wire them or document/remove after explicit product decision.

Required test units:

- `FastMarchingMethod` behavior should remain covered through `BubbleMaskBuilderTest` or a new direct test.
- Existing `ShortHashTest` remains valid because `ShortHash` itself is not dead.

Supervisor acceptance:

- No removal of `ShortHash` utility.
- No removal of ML Kit dependencies in this quest.
- No public behavior change unless explicitly approved.

Validation:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.inpainting.*" --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.util.ShortHashTest" --no-daemon
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

## Phase 5 — Stale docs/comments cleanup

Goal: remove misleading documentation after behavior is fixed or confirmed.

Target files:

- `docs/TRANSLATION_MODULE.md`
- `docs/ocr-engine-notes.md`
- KDoc in OCR/inpainting/recognition files listed in `APK_SIZE_CLEANUP_AUDIT.md`

Expected work:

- Fix clockwise/counter-clockwise OCR rotation docs.
- Reconcile Paddle DET BGR/RGB comments with verified model behavior.
- Fix QUALITY fallback docs.
- Fix MangaOCR reclaim docs.
- Fix stale `recoverHeapAfterOnnxPressure` and `shortHash` references.
- Fix `TranslationLifecyclePolicy` KDoc depending on wire/remove decision.

Required test units:

- Documentation-only; no unit tests required unless comments expose behavior bug.

Supervisor acceptance:

- Comments explain why/non-obvious behavior only.
- No comment that merely restates code.
- No docs claiming behavior that tests do not cover.

Validation:

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

## Phase 6 — APK/dependency size work without model downloads

Goal: reduce APK size while preserving out-of-box OCR/inpainting models.

### Quest 6A: Distribution and ABI policy

Target file:

- `app/build.gradle.kts`

Problem:

- Universal APK is much larger because it bundles all ABIs.

Implementation direction:

- Do not publish universal APK for normal users.
- Keep `arm64-v8a` as the main sideload target.
- Keep x86/x86_64 only for emulator/internal if needed.

Required validation:

```powershell
gradlew.bat :app:assembleStandardDebug --no-daemon
```

Supervisor acceptance:

- Release/distribution docs clearly say which APK to install.
- No accidental removal of emulator debug ability unless approved.

### Quest 6B: ML Kit dependency decision

Target files:

- `app/build.gradle.kts`
- Translation/OCR backend selection code.

Problem:

- ML Kit Translate/OCR standalone dependencies add large native libraries.

Implementation direction:

- First determine actual user-facing backend requirements.
- If ML Kit Translate is unused or optional, remove/split it before OCR scripts.
- If ML Kit OCR fallback is still required, evaluate whether all Latin/Japanese/Korean/Chinese script packs are required.

Required test units:

- Backend availability summary tests if backend registry exists.
- Compile tests for settings/backend enum references.
- Manual smoke test if dependency removal changes installed OCR/translate options.

Validation:

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
gradlew.bat :app:assembleStandardDebug --no-daemon
```

Supervisor acceptance:

- No settings option points to a removed backend.
- User-visible behavior change is documented.
- APK size is measured before/after.

## Phase 7 — Final integration and regression pass

Goal: combine accepted phases safely.

Supervisor checklist:

- Re-run all translation unit tests:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --no-daemon
```

- Re-run reader tests:

```powershell
gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.*" --no-daemon
```

- Compile standard debug:

```powershell
gradlew.bat :app:compileStandardDebugKotlin --no-daemon
```

- Build APK for size comparison:

```powershell
gradlew.bat :app:assembleStandardDebug --no-daemon
```

Manual smoke scenarios:

1. Manual translate one page in reader.
2. Enable auto translate and quickly flip through pages.
3. Pre-translate a 30+ page chapter from manga screen.
4. Trigger memory pressure or background app during pre-translate.
5. Cancel translation mid-page and verify no stuck queue state.
6. Reopen app and verify translated pages restore from store.

Failure policy:

- If tests fail, stop merging phase outputs.
- Revert the smallest phase first.
- Preserve failing test output and the suspected changed files.
- Do not stack new fixes on top of a failing subagent branch until the failure is isolated.
