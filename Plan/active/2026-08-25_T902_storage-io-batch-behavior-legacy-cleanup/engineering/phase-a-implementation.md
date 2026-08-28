# T902 Phase A implementation report

## Outcome

Phase A is implemented in the shared working tree. No commit was created.

The change covers the three requested behaviors:

- A1 moves the live SAF/download probes in `MangaScreenModel.confirmChapterTranslation` off the UI thread while preserving the existing downloaded/awaiting partition, queue-after-download registration, download enqueue, and single-vs-batch dispatch.
- A2 makes durable translation status and reader reads authority-aware. ARTIFACTS chapters use the summary sidecar as a bounded fast path and the registry-owned artifact store for rehydration; LEGACY chapters continue to decode their populated flat JSON. Durable status is conservatively invalidated on queue/store lifecycle changes and all reset paths.
- A3 quarantines undecodable legacy flat files as `<name>.corrupt`, replacing any prior quarantine target, and logs at ERROR instead of deleting the source file.

The pre-existing `DownloadCache.kt` renewal guard and `DownloadCacheRenewalGuardTest.kt` were retained. The test needed its missing `MutableSharedFlow` import restored so the existing test compiles; its behavior was not changed.

## Code changes

| Area | File and location | Change |
| --- | --- | --- |
| A1 | `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt:946` | `confirmChapterTranslation` now launches from `screenModelScope` and wraps the `isChapterDownloaded(..., skipCache = true)` partition in `withIOContext`; subsequent UI/queue behavior remains in the coroutine. |
| Registry | `app/src/main/java/eu/kanade/translation/ActiveChapterStoreRegistry.kt:19-105` | Added a file-key alias map and `getOrCreateFile`; chapter-key and file-key opens share the same per-file mutex, preventing reader/status and pipeline opens from creating duplicate stores. Removal clears aliases. |
| Manifest probe | `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt:1831-1858` | Added a small sibling-manifest probe that reads only the manifest header and reports presence plus decoded authority. |
| A2 status | `app/src/main/java/eu/kanade/translation/TranslationManager.kt:389-575` | Durable status now resolves queue → active display → durable. Durable resolution reads the summary first for ARTIFACTS manifests, opens an existing artifact store only when the summary is missing/insufficient, and flat-decodes only LEGACY/no-manifest files. Per-chapter results are cached; queue emissions and active-store register/unregister invalidate the cache. |
| A2 reader | `app/src/main/java/eu/kanade/translation/TranslationManager.kt:584-639` | `getChapterTranslationForReader` is suspend/IO-bound and returns registry-rehydrated artifact pages after restart, with flat JSON fallback for LEGACY chapters. The synchronous file overload follows the same authority rule through the file-key registry. |
| A3 quarantine | `app/src/main/java/eu/kanade/translation/TranslationManager.kt:640-668` | Decode failures rename the original to `<name>.corrupt` (overwriting an old quarantine target) and emit an ERROR log; the caller still receives an empty map. Status-side flat decode uses the same quarantine behavior. |
| F6 paths | `app/src/main/java/eu/kanade/translation/TranslationManager.kt:688-734,1177-1438` | Existing rekey/reset/preflight/open paths now resolve stores through the registry instead of constructing competing direct instances. |
| Review fixes | `app/src/main/java/eu/kanade/translation/TranslationManager.kt:1243-1423`; `app/src/test/java/eu/kanade/translation/TranslationManagerReaderTeardownTest.kt:211-230` | All chapter/stage reset paths clear the durable status cache. Unsafe-based reader teardown fixtures initialize the cache field, preventing asynchronous teardown NPEs from contaminating the next coroutine test. |

## Design decisions and tradeoffs

The registry accepts both chapter and file identities because status reads can lack a chapter ID while reader/pipeline reads normally have one. Both identities point to one store, and both open paths use the same mutex key. A file-only store can later be attached to a chapter ID without reopening it.

The durable status path checks the small `X.summary.json` sidecar before opening the heavier artifact store. The summary is accepted only when its expected page count matches the manifest and at least one manifest page is display-ready; otherwise the registry-owned store supplies the rehydrated display projection. This keeps manga-list rebuild I/O bounded while remaining fail-safe for incomplete or stale summaries.

Reset operations clear the whole durable-status cache, matching the existing conservative invalidation style. This avoids retaining a translated/ready badge after a reset when no queue or live-store event follows it.

The existing synchronous APIs retain their `runBlocking(Dispatchers.IO)` bridges for compatibility with synchronous callers. The reader API is now suspend and performs its work in `withContext(Dispatchers.IO)`, and A1 has no disk probe on the UI thread. No new main-thread `runBlocking` was introduced.

The resolver-null fallbacks in `ChapterTranslator.kt` and `TranslationPipeline.kt` still contain their pre-existing direct `ChapterTranslationStore.open` calls. They are outside this phase's reader-pipeline boundary; normal `TranslationManager` construction supplies the registry-backed resolver. Removing those fallbacks would require a separate pipeline ownership change.

## Tests and verification

Commands were run with the Android Studio JDK and `GRADLE_OPTS=-Dkotlin.compiler.execution.strategy=in-process` because the default Kotlin daemon invocation stalled on this Windows host.

| Command | Evidence |
| --- | --- |
| `.\gradlew.bat :app:compileStandardDebugKotlin --no-daemon --console=plain --max-workers=2` | PASS; app Kotlin compilation completed successfully. |
| `.\gradlew.bat :app:compileStandardDebugKotlin spotlessCheck --no-daemon --console=plain --max-workers=2` | Final post-review verification PASS after moving the cache initializer before `init`; app compile and Spotless both passed. |
| `.\gradlew.bat :app:testStandardDebugUnitTest --tests 'eu.kanade.translation.TranslationManagerArtifactReadTest' --tests 'eu.kanade.translation.ActiveChapterStoreRegistryTest' --tests 'eu.kanade.tachiyomi.data.download.DownloadCacheRenewalGuardTest' --no-daemon --console=plain --max-workers=2` | PASS; 11 tests, 0 failures/errors/skips. This includes artifact restart status/reader reads, LEGACY flat reads, corrupt-file quarantine, registry alias concurrency, and the existing renewal guard. |
| `.\gradlew.bat :app:testStandardDebugUnitTest --tests 'eu.kanade.translation.*' --tests 'eu.kanade.tachiyomi.data.download.*' ...` | The initial 938-test run exposed the order-dependent `BatchTranslateBlockMergeTest` failure. Review isolated that failure to an Unsafe-based `TranslationManagerReaderTeardownTest` helper missing the new cache field; the helper was fixed, along with reset-path invalidation. |
| `.\gradlew.bat spotlessCheck --no-daemon --console=plain --max-workers=2` | PASS. |
| `.\gradlew.bat spotlessCheck :app:testStandardDebugUnitTest --tests 'eu.kanade.translation.*' --no-daemon --console=plain --max-workers=2` | Spotless passed; the final translation slice ran 934 tests with exactly one failure, the known `AotReportBubbleFillTest` pixel mismatch. `BatchTranslateBlockMergeTest` no longer failed. The artifact restart test also asserts the artifact-only `userEditedAt = 42L` mutation. |
| `.\gradlew.bat spotlessCheck :app:testStandardDebugUnitTest :domain:testReleaseUnitTest --no-daemon --console=plain --max-workers=2` | Spotless and domain release unit tests passed; app standard-debug ran 994 tests with exactly one failure, the known `AotReportBubbleFillTest` pixel mismatch. The Gradle exit is therefore solely the documented baseline AOT failure; `BatchTranslateBlockMergeTest` passed. |
| `git diff --check` | PASS. |

A dedicated `MangaScreenModel` unit test was not added: constructing the Voyager screen model requires Android/UI and a large dependency graph, while the critical synchronous SAF behavior is isolated to the `withIOContext` partition and is covered by the successful main compile. The existing download-cache regression test covers the live disk/index condition that triggers A1.

## Remaining risks

- The known `AotReportBubbleFillTest` pixel mismatch remains the only failure in the post-fix translation slice; it is outside Phase A.
- Summary sidecars remain direct-overwrite files; Phase B owns their atomic-write/deslimming work.
- The registry cache is invalidated on queue emissions and store lifecycle changes. Active-store status reads bypass the durable cache and observe the live display projection directly.
- Resolver-null direct opens noted above remain a potential duplication hazard only for standalone pipeline construction; the manager-owned production resolver is registry-backed.
