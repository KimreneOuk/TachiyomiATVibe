# APK Size and Cleanup Audit

Date: 2026-06-28

Scope: read-only audit of APK size drivers, dead code, unused libraries, logs, optimization opportunities, and repository cleanliness. No production code was changed.

Verification pass: the findings were cross-checked by specialized read-only review passes for APK/dependencies, stale comments, race conditions, performance/memory, and dead code/logging. No major section was invalidated. Corrections from verification are folded into this document: ML Kit native costs were raised in priority, Conscrypt/image-decoder removability was narrowed, the scheduler suffix-match race was downgraded to a hardening item, Gemini was downgraded from leak to allocation churn, and additional stale-doc references were added.

## Executive summary

The current APK size is mostly caused by bundled AI models and native libraries, not app resources.

Measured debug artifacts already present under `app/build/outputs/apk/`:

| APK | Size |
| --- | ---: |
| `standard/debug/app-standard-universal-debug.apk` | 352 MB |
| `standard/debug/app-standard-arm64-v8a-debug.apk` | 189 MB |
| `standard/debug/app-standard-armeabi-v7a-debug.apk` | 173 MB |
| `standard/debug/app-standard-x86-debug.apk` | 195 MB |
| `standard/debug/app-standard-x86_64-debug.apk` | 195 MB |

Likely biggest wins:

1. Do not distribute the universal APK. Use per-ABI APKs or AAB delivery.
2. Move ONNX/OCR/inpainting models out of the base APK and download them on demand.
3. Reduce ML Kit standalone dependencies or move non-default OCR/translation models to on-demand delivery, especially `com.google.mlkit:translate` and its large `libtranslate_jni.so`.
4. Drop x86/x86_64 release ABIs if emulator support is not needed for public distribution.
5. Remove tracked local/private artifacts: prefs, logs, backup downloads.

## APK size drivers

### 1. Bundled ONNX model assets: about 124 MB raw

Packaged under `app/src/main/assets/models/` and shipped to every user:

| File | Approx size | Notes |
| --- | ---: | --- |
| `app/src/main/assets/models/ocr/decoder_init.onnx` | 23.7 MB | Manga OCR decoder init |
| `app/src/main/assets/models/inpainting/aot.onnx` | 22.0 MB | AOT inpainting model |
| `app/src/main/assets/models/ocr/decoder_step.onnx` | 21.7 MB | Manga OCR decoder step |
| `app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx` | 20.2 MB | PaddleOCR recognition |
| `app/src/main/assets/models/ocr/encoder.onnx` | 16.3 MB | Manga OCR encoder |
| `app/src/main/assets/models/detection/detector-v4-s_int8.onnx` | 10.6 MB | Text detector |
| `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx` | 9.4 MB | PaddleOCR detector |

Impact: highest. These assets account for roughly two-thirds of a per-ABI debug APK (about 124 MB of 173-195 MB, or 64-72%) and are unaffected by R8.

Recommended direction:

- Introduce model download-on-demand through the existing model-store/fetcher flow.
- Only download the selected OCR/inpainting backend.
- Keep a small manifest/checksum catalog in the APK instead of shipping all model binaries.
- Consider quantizing remaining FP ONNX models.

### 2. ONNX Runtime native library

Dependency: `app/build.gradle.kts` uses `libs.onnxruntime.android`.

Evidence:

- `app/build.gradle.kts:292` includes ONNX Runtime Android.
- `libonnxruntime.so` is about 13.6-21.6 MB per ABI: about 13.6 MB on `armeabi-v7a`, 18.3 MB on `arm64-v8a`, and about 21 MB on x86/x86_64.
- Universal APK duplicates native libraries for all four ABIs.

Impact: high. Necessary for local ONNX inference, but expensive.

Recommended direction:

- Prefer ABI-specific release artifacts.
- Disable universal release APK distribution.
- Evaluate whether all four ABIs are required.

### 3. ML Kit standalone OCR and translation

Dependency evidence in `app/build.gradle.kts`:

- `libs.mlkit.text.recognition`
- `libs.mlkit.text.recognition.japanese`
- `libs.mlkit.text.recognition.korean`
- `libs.mlkit.text.recognition.chinese`
- `libs.mlkit.text.translate`

Impact: high. Standalone ML Kit bundles native libraries and model assets into the APK. The major cost is native code: `libtranslate_jni.so` is about 11-17 MB per ABI and `libmlkit_google_ocr_pipeline.so` is about 6-11 MB per ABI, together exceeding `libonnxruntime.so` on every ABI. OCR model assets under `assets/mlkit-google-ocr-models/` are comparatively small, around 4 MB.

Recommended direction:

- Decide whether ML Kit is required as an always-installed backend.
- Evaluate `com.google.mlkit:translate` specifically; `libtranslate_jni.so` is the single largest ML Kit cost and the second-largest native library in the APK.
- Consider Play Services ML Kit variants if Google Play Services dependency is acceptable.
- Otherwise split ML Kit script support into on-demand modules or remove unused scripts.

### 4. Universal APK and four release ABIs

Evidence in `app/build.gradle.kts`:

- `supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")`
- `isUniversalApk = true`

Impact: high. Universal debug APK is 352 MB because it includes all native libraries for all ABIs.

Recommended direction:

- Set universal release APK generation/distribution to false unless explicitly needed.
- For sideload distribution, publish `arm64-v8a` as the primary APK.
- Keep x86/x86_64 only for internal emulator builds if needed.

### 5. Other native libraries

Largest native contributors after ONNX Runtime are the ML Kit libraries `libtranslate_jni.so` and `libmlkit_google_ocr_pipeline.so`. Smaller contributors include image decoder, Conscrypt, SQLite, libarchive, and QuickJS. All multiply by ABI in universal builds.

Recommended direction:

- Conscrypt is actively inserted as the primary security provider at startup and supports TLS 1.3 on Android 8/9; it is removable only if minSdk is raised to Android 10/API 29 or higher.
- Image decoder is explicitly re-added after transitive exclusion and supports reader image formats; removal would need reader-format validation.
- Do not remove archive/QuickJS libraries without tracing feature usage; they support core reader/source functionality.

## Build configuration observations

- Release config already enables `isMinifyEnabled = true` and `isShrinkResources = true`.
- Resource configuration restriction appears only in the `dev` flavor: `resourceConfigurations.addAll(listOf("en", "en_XA", "ar_XB", "xxhdpi"))`.
- R8 will reduce dex/resources, but it will not materially reduce bundled ONNX assets or native `.so` files.
- `kotlin-reflect` appears to be included and should be validated for actual usage before removal.

## Repository cleanliness findings

### 1. Tracked private preference files

`prefs-device.xml` is tracked and contains private device/application data, including an API key and local endpoint information. `prefs.xml` also contains device-specific state such as storage URI/trusted-extension data. The key value should not be copied into documentation.

Risk: security incident.

Recommended direction:

- Rotate/revoke the exposed API key.
- Remove `prefs-device.xml` and `prefs.xml` from git tracking.
- Add `prefs*.xml` to `.gitignore`.
- Scrub history if this repository has been shared.

### 2. Tracked logs

Tracked files under `logs/` include large crash/logcat files:

- `logs/OnePlus-PKG110-Android-16_2026-05-29_201953.logcat`
- `logs/crash-log-20260529-191427.txt`

Recommended direction:

- Remove `logs/` from git tracking.
- Add `/logs/`, `*.logcat`, and `crash-log-*.txt` to `.gitignore`.

### 3. Tracked backup/download artifacts

`backup-external-data/Download/` contains tiny tracked extension APK files that look like partial or local artifacts.

Recommended direction:

- Remove `backup-external-data/` from git tracking unless it is intentional test data.
- Add it to `.gitignore` if it is local device backup state.

### 4. Tracked local helper scripts

Root-level helper scripts such as `_build.bat`, `_build_install.bat`, `_compile.bat`, and `_verify.bat` are tracked. If these are machine-local workflow helpers rather than shared project tooling, remove them from git tracking and add an ignore rule.

### 5. Large ignored local artifacts

Large ignored/generated items are present locally:

- `app/build/` is about 4 GB.
- `logcat.txt` is about 100 MB.
- Several root-level build/install logs are present.
- `tools/inpaint-debug-viewer/debug-output/` contains debug images.

Recommended direction:

- These are safe cleanup candidates after confirming no active debugging session depends on them.
- Run Gradle clean only when build artifacts are not needed for comparison.

### 6. Git history/model binary bloat

The ONNX model binaries are also tracked in git, not just packaged into the APK. If model download-on-demand is implemented, pair it with removing model binaries from normal git history or moving them to Git LFS to reduce clone/history size.

## Dead code and optimization findings

### High-confidence dead code

Candidate removals after build verification:

| File | Finding |
| --- | --- |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | Dead `decodePageBitmap` duplicate; code uses `decodePageBitmapForTranslation` / `decodePageBitmapAtSize` instead. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | Dead `shortHash` wrapper. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | Dead `recoverHeapAfterOnnxPressure`; replaced by newer memory reclamation paths. |
| `app/src/main/java/eu/kanade/translation/recognition/MlKitFullPageRecognitionEngine.kt` | Class is not instantiated; production path uses `RoiPageRecognitionEngine`. |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationLifecyclePolicy.kt` | Not wired into scheduler; only tests reference it. Decide whether to wire or remove. |
| `domain/src/main/java/tachiyomi/domain/translation/validation/PreferenceValidator.kt` | Validation helper appears unused. |
| `domain/src/main/java/tachiyomi/domain/translation/KeystoreApiKeyManager.kt` | Not wired or instantiated; product decision because it may represent planned secure key storage. |

### Hot-path optimization

`app/src/main/java/eu/kanade/translation/inpainting/FastMarchingMethod.kt` builds an `offsets` list inside dilation but does not read it. Removing that allocation should preserve behavior and reduce hot-path overhead.

### Logging cleanup

There are many unconditional `LogPriority.INFO` logs in translation/OCR/inpainting hot paths. The existing `translationDiagnostics()` gate is already present but applied inconsistently; the fix is to extend existing gating rather than invent a new mechanism. Representative files:

- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/PageInpaintingEngine.kt`
- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/ocr/MangaOcrEngine.kt`
- `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6SmallEngine.kt`
- `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt`

Recommended direction:

- Keep warnings/errors unconditional.
- Gate verbose per-page timing and route logs behind the existing `translationDiagnostics()` preference.
- Avoid string interpolation work on every translated page unless diagnostics are enabled.

## Potential unused or reducible dependencies

Do not remove these without dependency-tree and compile validation:

| Dependency area | Candidate action | Risk |
| --- | --- | --- |
| ONNX Runtime | Keep, but avoid universal duplication and move models out of APK | Required for local OCR/detection/inpainting |
| ML Kit OCR/Translate | Split/remove unused scripts or use Play Services variants | Feature behavior and offline support may change |
| Conscrypt | Actively used for TLS 1.3 on Android < 10; removal requires raising minSdk to API 29+ | Network/TLS regressions on Android 8/9 otherwise |
| Image decoder native lib | Explicitly re-added for reader image support; validate only with full reader-format tests | Reader image support regressions possible |
| `kotlin-reflect` | Search actual reflection usage and remove if unused | Runtime reflection failure if hidden usage exists |

## Suggested implementation order

1. Security/hygiene first: rotate leaked key, untrack prefs/logs/backup artifacts/local helper scripts, update `.gitignore`.
2. Distribution fix: stop publishing universal APK; prefer arm64 split or AAB.
3. Model delivery: implement model manifest + download-on-demand for ONNX/Paddle/AOT assets.
4. ML Kit decision: keep all bundled, reduce scripts, use Play Services, or on-demand module.
5. Race-condition fixes: serialize translation manager lifecycle maps and make in-flight page keys thread-safe before broader refactors.
6. Performance/memory fixes: coalesce translation-store persistence, wire app-level memory pressure, and reduce store-state fan-out.
7. Low-risk code cleanup: remove dead wrappers/classes and unused inpainting allocation.
8. Logging cleanup: gate page-level INFO logs behind `translationDiagnostics()`.
9. Validate release size with a fresh `assembleStandardRelease` and APK Analyzer/bundletool.

## Auto/manual/pre-translate performance and memory findings

Scope: auto-translate from reader page changes, manual single-page translate, and pre-translate/batch from manga screen. No code changes were made.

### Most likely performance bottlenecks

| File | Affected flow | Finding | Impact | Recommended direction |
| --- | --- | --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | Auto, manual, pre-translate | `updatePage()` can synchronously persist the entire chapter JSON on each durable stage update. A batch can write OCR, inpaint, translate, and render updates per page, each re-encoding the growing map. | High: O(chapter²) disk/JSON work, SAF IPC cost, delayed state emissions. | Coalesce/debounce disk persistence, flush on chapter close/completion, or move to per-page/incremental records. Keep in-memory `StateFlow` live. |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` and `TranslationPipeline.kt` | Auto/manual AI, pre-translate AI | Glossary persistence is done per chunk/page. | Medium: small files but repeated SAF temp+rename work. | Fold glossary into the same coalesced store flush; persist on completion or debounce. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | Auto/manual single-page | Single-page glossary rebuild scans all translated pairs in the chapter every page. | Medium: O(chapter²) block scans across auto-translation of long chapters. | Maintain an incremental chapter glossary accumulator/snapshot instead of rebuilding from all stored pairs. |
| `app/src/main/java/eu/kanade/translation/TranslationManager.kt` and `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt` | Manga chapter list / pre-translate status | `isChapterTranslated` locates and decodes full translation JSON per chapter status check. | Medium-high: slow manga screen refresh on many translated/downloaded chapters. | Write a tiny sidecar marker/cache keyed by chapter/file mtime, or short-circuit streaming decode. |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | All flows | Every `updatePage()` emits a full copied chapter map through `StateFlow`. | Medium: CPU/GC churn and O(chapter²) fan-out during active translation. | Emit changed-page deltas or persistent immutable snapshots; skip emit when no structural change occurred. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | Auto/manual while reader is open | `observeLiveTranslationStore` scans chapter pages on every store emission and updates warm-window/reader state. | Medium: reader jank during bursty translation updates. | Add `.conflate()` / short debounce to store collection; limit work to warm-window pages. |
| `app/src/main/java/eu/kanade/translation/batch/TranslationBatchProgressTracker.kt` | Pre-translate | Tracker writes stage status to store and recomputes full snapshots on each transition plus tick. | Medium: tracker writes multiply the full-chapter `persistLocked()` bottleneck once pages have blocks, roughly doubling/tripling stage-write pressure in batch. | Make tracker a read-only projection from store state; dirty-flag and recompute on a conflated tick. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | Pre-translate render | Cleaned and rendered outputs are PNG encoded; rendered output likely does not need lossless PNG. | Medium CPU/storage cost. | Keep cleaned images lossless, but consider WebP/JPEG for final rendered images with validation. |
| `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt` | Manual/auto fallback for archive chapters | Single-page archive fallback enumerates/sorts all archive entries and closures; batch already uses shared `ArchiveReader`. | Low-medium. | Reuse archive reader or provide a single-page lookup path when page key is known. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | Auto-translate | Auto window request construction runs on each page selection; scheduler dedups work, but request building still allocates/sorts during fast scroll. | Low. | Add a short 150-250 ms debounce before auto-window submission. |

### Memory leak and memory-pressure risks

| File | Finding | Affected flow | Severity | Recommended direction |
| --- | --- | --- | --- | --- |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` and `app/src/main/java/eu/kanade/translation/TranslationManager.kt` | Memory-pressure forwarding appears wired only through reader activity/viewmodel. Batch pre-translation from manga screen can run without reader open, so `TranslationManager.onMemoryPressure()` may never fire. | Pre-translate, background translation | High | Register an application-level `ComponentCallbacks2` that forwards `onTrimMemory` to the singleton `TranslationManager`. |
| `app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt` | `close()` is empty and contextual `GenerativeModel` instances can be allocated per chunk; verification suggests this is more likely allocation churn than a sustained leak because the SDK model wrapper is GC-reclaimable. | Gemini auto/manual/pre-translate | Low | Cache contextual models by token-limit bucket where possible; validate with heap/thread dump before treating as leak. |
| `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt` | `close()` returns without closing sessions if `nativeGuard.tryLock()` fails. This avoids SIGSEGV but can orphan old native ONNX sessions until finalization. Concrete trigger: permit-free downscale retry can hold `nativeGuard` while `closeEngines()` acquires the permit. | Close/rebuild under active native work | Medium | Queue a deferred close after native guard becomes available. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | `closeEngines()` returns early when permit cannot be acquired, leaving old engines alive until later rebuild/finalization. | Memory pressure/config-change during active native work | Low-medium | Queue deferred close or wait bounded time for watchdog/permit release. |
| `app/src/main/java/eu/kanade/translation/TranslationManager.kt` and `batch/TranslationBatchProgressTracker.kt` | Completed batch trackers may remain in `batchTrackers` until cancel/delete/new tracker for same chapter. | Pre-translate many chapters | Low | Dispose tracker after normal batch completion once final snapshot is emitted. |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` | `translation.tmp` files can be orphaned if process dies during persist, especially on SAF providers that suffix duplicate temp names. | Disk hygiene | Low | Sweep `translation*.tmp` on open/persist start or use unique temp names with cleanup. |

### Memory handling already done well

- Single `translatorPermit` serializes the primary ONNX-heavy stages so normally only one page bitmap/tensor set is active there; permit-free downscale retry can briefly hold a second bitmap, though native execution remains guarded by `nativeGuard`.
- `withLeakProofPermit()` watchdog prevents permanent translation deadlock when native/HTTP work ignores cancellation.
- `BitmapPool` and `DirectBufferPool` are bounded and lock/atomic guarded; direct buffers are force-cleaned on release-all paths.
- `TranslationMemoryBudget` performs decode/analyze/inpaint preflight checks and sample-size decisions.
- Batch held-cleaned-bitmap registry is bounded by both count and byte ceiling, and recycles in `finally`.
- ONNX `OrtSession.Result`/tensor lifetimes are generally closed in `finally`.
- `RoiPageRecognitionEngine.nativeGuard` intentionally avoids closing native sessions while an ONNX run is active.
- Reader stream registry and live translation jobs are cleared/cancelled on chapter change/background paths.

### Suggested diagnostics before performance/memory fixes

- Add temporary timings around `ChapterTranslationStore.persistLocked()`: pages count, encoded bytes, elapsed ms, write reason.
- Count `updatePage()` calls per page/stage during a 40+ page batch.
- Add timings/counters for `snapshotPages()` and reader `observeLiveTranslationStore` collection duration.
- Log `isChapterTranslated` find/decode time per chapter on manga screen open.
- In a batch with reader closed, trigger memory pressure and verify whether `TranslationManager.onMemoryPressure()` logs fire.
- Heap/thread dump after large Gemini batch plus config changes; inspect `GenerativeModel` and SDK threads.
- Track `Debug.getNativeHeapAllocatedSize()` across forced close/rebuild during native OCR/inpaint work.

## Race-condition and concurrency findings

Primary likely sources after reviewing coroutine, store, scheduler, native-session, pool, and reader-state paths:

1. Unsynchronized lifecycle maps in `TranslationManager`.
2. `TranslationPipeline` shared state touched by watchdog, close, batch, and single-page paths.

Other possible sources considered: store file persistence, scheduler queues, direct/bitmap pools, ONNX native session close, queue restore/persist, and reader page state mutation. Several of those areas are already well guarded; see “race-safe areas” below.

### High priority race risks

| File | Risk | Failure scenario | Recommended direction |
| --- | --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/TranslationManager.kt` | `activeTranslationStores`, `activeStoreJobs`, and `batchTrackers` are plain mutable maps accessed from multiple coroutine scopes/dispatchers. Their `.toMap()` StateFlow snapshot updates also iterate these maps while other threads can mutate them. | Concurrent register/unregister/open can corrupt a `HashMap`, lose a store registration, or leave reader and pipeline using different store instances for the same chapter. | Use a `Mutex` around compound lifecycle operations or switch to `ConcurrentHashMap` plus locking for check-then-act paths; `ConcurrentHashMap` alone does not fix `openOrCreateActiveChapterTranslationStore` TOCTOU. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | `inFlightPageKeys` is a mutable set, but normal worker, watchdog `onForceRelease`, and `closeEngines()` can mutate it from different threads; the comment claiming permit serialization is false because watchdog runs on `Dispatchers.Default` outside the permit. | Concurrent `remove`/`clear` can corrupt the set or break dedup, causing stuck pages to be permanently skipped or duplicate translates to run. | Use `ConcurrentHashMap.newKeySet<String>()` or serialize all mutations onto one dispatcher; update the comment claiming permit serialization. |

### Medium priority race risks

| File | Risk | Failure scenario | Recommended direction |
| --- | --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | `ensureEnginesBuiltFor()` can run from batch before acquiring the translation permit while a single-page HTTP translation still uses the previous translator reference outside the permit. The HTTP path captures a local `activeTranslator` and treats mid-flight close as retryable, so the verified impact is retry churn rather than data loss; native session hazards are bounded by `RoiPageRecognitionEngine.nativeGuard`. | A rebuild can close the cached translator under an in-flight HTTP call, causing retryable “translator closed” failures. | Acquire the permit for rebuild/close operations or move batch engine ensuring inside the permit-protected section; also fix nearby KDoc claiming every close site holds the permit. |
| `app/src/main/java/eu/kanade/translation/TranslationManager.kt` | Multiple per-chapter store collectors write into one `_activeStoreState`. | If chapter A and B are active during transitions/batch, last-writer-wins can expose the wrong chapter page map to the translation-settings queue list. Verified impact is UI-only because per-page render state uses the per-chapter store directly. | Key aggregate state by chapter ID or remove/limit the aggregate to the actively observed chapter. |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | Batch lanes share mutable `PageTranslation` objects through `translationRegistry`; only render-vs-render is guarded by `renderMutexes`, while Lane A/Lane B field writes are unguarded. | One lane can mutate blocks/status while another reads or renders, causing missed renders or incorrect progress/status snapshots. | Treat `PageTranslation` as immutable snapshots between lanes, or guard all per-page field mutation with a per-page mutex. |
| `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt` plus store creation paths in `TranslationPipeline.kt` / `ChapterTranslator.kt` | Two `ChapterTranslationStore` instances for the same file have independent mutexes and use the same `translation.tmp` name. | If shared-store resolution fails and a fallback store opens the same file, concurrent persists can overwrite/corrupt `translation.json`. | Enforce one process-wide store per file or use per-file global locks/unique temp names. |

### Low priority race or ordering risks

| File | Risk | Recommended direction |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` | `currentChapterTranslation` and `consecutiveOomCount` are plain vars used across suspending IO paths, unlike nearby volatile engine fields. Verification found `consecutiveOomCount` is mostly permit-confined; `currentChapterTranslation` is the more genuinely racy field because it can be cleared in permit-free HTTP cleanup. | Mark `@Volatile` or confine them to the permit-protected coroutine context. |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` | Reservation eviction uses structured prefix plus suffix matching for escaped storage keys. Verification found this is currently correct given the final colon-delimited key segment and `:` to `_` escaping, so this is a clarity/hardening item rather than a live correctness bug. | Prefer explicit component parsing for robustness/readability. |
| `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt` | `restoreQueue()` and immediate user queue persistence can interleave at startup. | Serialize queue persistence or gate queue mutations until restore completes; failure mode is lost user additions, not queue-file corruption. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | Reader cancel path and store collector can both mutate `ReaderPage` translation fields. | Prefer a single source of truth from the store flow for page translation state. |

### Race-safe areas observed

These areas appear intentionally guarded and should not be refactored casually:

- `ChapterTranslationStore` internal `Mutex` for `pages`, persistence, glossary, and snapshot emission; caveat: `translatedPairs()` reads the persistent map without the mutex.
- `TranslationScheduler` concurrent maps/sets and atomic generation counters.
- `TranslationStreamRegistry` `ConcurrentHashMap` use.
- `TranslationPipeline.withLeakProofPermit()` release-once `AtomicBoolean` watchdog design.
- `RoiPageRecognitionEngine` native session lifecycle: `initMutex`, `nativeGuard`, volatile flags, and “leak instead of freeing during native run” close behavior.
- `DirectBufferPool` and `BitmapPool` lock/atomic guarded structural mutation.
- `AOTInpainting` scratch-buffer synchronization.
- `TranslationBatchProgressTracker` store-mutex based transitions; caveat: its `finished` flag is a plain var shared with the tick coroutine.

### Suggested diagnostics before fixes

- Add temporary debug logs around `TranslationManager` register/unregister/open paths including `chapterId`, thread name, store identity hash, and active map sizes.
- Add diagnostic logging around `ensureEnginesBuiltFor()` rebuild decisions including whether the permit is held, old/new signature, and in-flight page key count.
- Add store identity/path logging in fallback `ChapterTranslationStore.open(...)` paths to catch multiple instances for one `translation.json`.
- Stress test by rapidly opening/closing reader chapters while auto-translate and batch translate are active.

## Stale or conflicting comment/doc findings

These are documentation/comment-only issues that can mislead future maintainers. They should be cleaned before or alongside code cleanup.

### High priority contradictions

| File | Problem | Recommended action |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/ocr/RoiOcrEngine.kt` and `docs/ocr-engine-notes.md` | Comments say tall CJK crops rotate 90° clockwise, but `RoiPageRecognitionEngine.rotateCcw` uses `postRotate(-90f)` and another nearby comment says clockwise fails. Verification also found a stale `rotated=90cw` example log in `docs/ocr-engine-notes.md`. | Update wording/examples to counter-clockwise, or remove direction detail from the interface KDoc. |
| `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6DetEngine.kt` | KDoc says detector preprocessing writes BGR planes, but the tensor write loop writes R, G, B planes. Verification found another stale normalize KDoc saying “BGR plane order”; `inference.yml` says BGR, so docs/code/model expectations must be reconciled. | Verify actual validated model channel order; then update either KDoc or code so all references match. |
| `app/src/main/java/eu/kanade/translation/inpainting/PageInpaintingEngine.kt` | Comment says strict no-fallback QUALITY behavior, but code allows QUALITY→FAST fallback when the user preference enables it. | Rewrite comment to state default throw behavior plus preference-gated fallback. |
| `docs/TRANSLATION_MODULE.md` | Contract #15c says QUALITY without initialized neural inpainter throws unconditionally. | Update to include `translation_inpaint_quality_fallback` preference behavior. |
| `docs/TRANSLATION_MODULE.md` | Contract #4 says `MangaOcrEngine.reclaimPooledMemory()` clears `kCachePool`/`vCachePool`, but implementation is a no-op; clearing happens in force-release/close paths. Verification found Contract #10 and `RoiOcrEngine` KDoc repeat the same stale assumption. | Correct the contract/KDoc to describe actual buffer lifecycle. |
| `app/src/main/java/eu/kanade/translation/inpainting/PageInpaintingPlanner.kt` and `docs/TRANSLATION_MODULE.md` | Planner KDoc says contract #14a is preserved while code masks only non-blank/read OCR blocks; contract describes every OCR block. | Update contract #14a or remove the “contract preserved” claim. |
| `docs/ocr-engine-notes.md` | Says confidence blanking was removed, but `RoiPageRecognitionEngine` still drops Paddle reads below `OCR_MIN_CONFIDENCE`. | Update note to explain the current confidence threshold behavior. |
| `docs/ocr-engine-notes.md` | Says Paddle det is strictly Stage-2 OCR refinement and inpaint logic is unchanged, but AOT inpainting uses det for free-text erase-mask refinement. | Document both det uses: OCR splitting and inpaint free-text refinement. |
| `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt` | KDoc/comment claims every `close()` call site is guarded by the translator permit and that rebuild path holds it; batch `ensureEnginesBuiltFor()` can call `recognitionEngine.close()` before the permit. | Update KDoc to describe permit as primary defense and `nativeGuard` as the backstop for unguarded close/rebuild paths. |
| `docs/TRANSLATION_MODULE.md` | References dead `recoverHeapAfterOnnxPressure` and says it calls `reclaimPooledMemory()`; function is unused and its body calls stronger native-buffer release instead. | Replace with live `reclaimTranslationMemory` / `forceReleaseNativeBuffers` behavior. |
| `docs/TRANSLATION_MODULE.md` | Lists private `TranslationPipeline.shortHash` as the FNV-1a digest site, but the wrapper is dead and code calls `ShortHash.hash(...)` directly. | Update docs to reference `ShortHash.hash`. |

### Medium priority misplaced or stale KDoc

| File | Problem | Recommended action |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt` | Two adjacent KDoc blocks precede `recognizeMultiLine`; the first describes an older vertical-only splitter while the second describes current horizontal+vertical behavior. | Remove the stale first KDoc block. |
| `app/src/main/java/eu/kanade/translation/inpainting/SmartBubbleTextCleaner.kt` | KDoc for `fillSolidBoxes` appears above `fillContained`, and its usage description is stale. | Move it above `fillSolidBoxes` and update it to describe fallback usage. |
| `docs/TRANSLATION_MODULE.md` | Contract #16e says FAST free-text path is `fillSolidBoxes`/Telea, but current route uses `LegacyFreeTextInpainter` / `PushPullGradient`; Telea is fallback. | Update contract #16e. |
| `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt` | Comment says the tiered pipeline “replaces” older cleaners, but those cleaners are still active fallbacks. | Change to “primary path with retained fallbacks”. |
| `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6SmallEngine.kt` | Comment says preprocessing fixes are needed for vertical manga text, while docs say they improved horizontal text and vertical required other splitting logic. | Reword to avoid claiming these fixes solved vertical OCR. |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationLifecyclePolicy.kt` | KDoc claims the policy is consumed by `TranslationScheduler`, but verification confirms scheduler does not reference it. | Either wire it into the scheduler or update KDoc to state it is currently test-only/incomplete. |

### Low priority wording cleanups

| File | Problem | Recommended action |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt` | Padding comment implies the gate is tied to ink-gap heuristic, but code gates by engine preference. | Reword around horizontal-line engines receiving context padding. |
| `docs/TRANSLATION_MODULE.md` | Test coverage table references `inpaintTelea` under `BubbleMaskBuilderTest`, while implementation lives in `FastMarchingMethod`. | Clarify as `FastMarchingMethod.inpaintTelea` exercised by that test. |

## Validation not performed

- No release build was generated during this audit.
- No code was compiled after findings because no implementation changes were made.
- Dependency removal was not tested; all dependency notes are candidates requiring compile/runtime validation.
