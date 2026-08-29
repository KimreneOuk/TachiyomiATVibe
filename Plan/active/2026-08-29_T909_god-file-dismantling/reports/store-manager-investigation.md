# T909 — Store/Manager Investigation: ChapterTranslationStore.kt + TranslationManager.kt

Owner: Technical Lead investigator · Date: 2026-08-28 · READ-ONLY evidence
Branch base: `optimize_translation_finishing_page`

Measured sizes (drift vs README): `ChapterTranslationStore.kt` = **2,552** lines (matches),
`TranslationManager.kt` = **2,056** lines (README said 2,083 — file shrank slightly).
All paths relative to `app/src/main/java/eu/kanade/translation/` unless prefixed `test:`.

---

## PART A — ChapterTranslationStore.kt (2,552 lines)

### A.1 Declaration map

Top-level (outside the class):

| Lines | Declaration | Responsibility | Class |
|---|---|---|---|
| 1–65 | imports | — | — |
| 68–84 | `sealed interface MutationAdmission` | Admission result for page mutations; `Rejected.Code` carries `LEGACY_RESCUE_REQUIRED/FAILED`, `ARTIFACT_PUBLICATION_FAILED`, `STORE_DEFUNCT`, `FENCE_REJECTED` | CORE contract (legacy-named codes remain in the admission vocabulary) |
| 86–98 | `class ChapterTranslationStore(...)` ctor | `translationFile`/`fileCreator` retained **only as source-compatible test seams** (comment 87–89: "artifact-only persistence never invokes this legacy flat-file creator") | ctor |

Class body, in file order:

| Lines | Member | Responsibility | Class |
|---|---|---|---|
| 99–153 | state fields | `mutex`, `pages` (PersistentMap), `_state`/`_display` StateFlows, `committedDisplay`, `artifactManifest`, `retiredCleanedImages`, `pendingArtifactPageRegistrations`, `pendingExpectedPageCount(+Trusted)`, `pageLeases`, `generation`, `nextPageVersion`; public `state`/`display`/`currentGeneration` | CORE state |
| 159–171 | `CommittedPageDisplay`, `PageLeaseRecord` | frozen display bundle; lease record | CORE |
| 173 | `resetPreflight()` | delegates to `chapterResetPreflight()` (its own file) | CORE |
| 175–207 | `PageSnapshot`, `PatchPrecondition`, `PatchResult` | writer-identity snapshots, preconditions, accepted/rejected results | CORE contract |
| 209–214 | `GenerationContext` | coroutine-context element for `withGeneration` (used by Pipeline) | CORE |
| 216–224 | glossary + persistence fields | `glossary`, `persistScope`, `dirty`, `glossaryDirty`, `persistJob` | CORE (glossary) / semi-vestigial persistence |
| 236–262 | `defunct`, `markDefunct()`, `isDefunct`, `persistCount` | eviction guard (set by Manager `unregisterActiveTranslationStore`); joins in-flight persist ≤2 s then cancels; `persistCount` is an `internal` test seam | CORE lifecycle |
| 264–314 | `init` | seeds pages, committed display from artifact snapshots then display-ready compat seed | CORE |
| 316–333 | `snapshot`, `durableFailure`, `durableFailuresSnapshot`, `ensureArtifactAuthorityForMutation` | read API + admission gate | CORE |
| 335–371 | `admitMutationLocked` | defunct check; artifact-authority fast path; memory-only grant; otherwise `ensureArtifactStoreLocked()` rescue → `LEGACY_RESCUE_*` codes | CORE w/ LEGACY naming |
| 373–380 | `beginGeneration`, `invalidateGeneration`, `withGeneration` | generation fencing | CORE |
| 379–477 | **lease subsystem** | `tryAcquirePageStageLease` (387–438), `releasePageStageLease` (440–448), `cancelPageStageWork` (451–462), `releaseAllPageLeases` (465–471), `pageLeaseOwner` (473–475) | CORE, cohesive |
| 482–587 | `patchPage`, `updatePageGuarded` | fenced whole-page mutation with artifact publish + rollback | CORE |
| 595–649 | `persistDurableStageFailure`, `updatePageFromCurrentSnapshot` | failure-persisting mutation; compat convenience | CORE |
| 651–697 | `patchBlock`, `applyStagePatch` | block fingerprint patch; stage-patch dispatch | CORE |
| 711–829 | `mergeOcr/Translation/Inpaint/Render` (+ `*Locked` impls 716–814, 831–890, 892–928, 930–978) | per-stage merges with identity fencing (OCR 716–814 is the largest, 99 lines) | CORE |
| 980–1090 | `stageIdentityRejection`, 4× `*StagePatch.toPrecondition`, `pageWriteRejection`, `ocrIdentityRejection`, `rejectedStage` | shared identity-check helpers | CORE |
| 1100–1129 | `updatePage` | **compatibility path**: legacy-shaped unwfenced writer, generation-context aware, refuses when a lease is active | CORE compat |
| 1131–1256 | `deletePage`, `replaceAll`, `rekeyPages` | destructive whole-store ops + download re-key | CORE |
| 1258–1393 | `preRegisterPages`, `clearTransientQueuePages` | batch baseline registration; queue-clear on cancel | CORE lifecycle |
| 1395–1432 | `nextVersion`, `ownedPage`, `publishLocked`, `restorePageLocked` | versioning + single publication/rollback choke point | CORE |
| 1434–1626 | `persistArtifactMutationLocked` | **the artifact writer bridge**: registration/expected-count manifest publication (1476–1498), candidate fencing (1500–1527), candidate open/persist/promote (1528–1625) | CORE |
| 1628–1678 | `ensureArtifactStoreLocked`, `toArtifactOrigin`, `sourceIdentity` | lazy artifact-authority creation (calls `loadOrMigrate` with an **empty** `LegacyChapterSnapshot`, 1639–1649); LEGACY→ARTIFACTS cutover for fresh stores (1654–1669) | CORE |
| 1690–1747 | `promoteDisplayIfReadyLocked`, `displaySnapshotLocked`, `displayFingerprintOf`, `appendField` | committed-display promotion + SHA-256 display fingerprint | CORE |
| 1749–1792 | `resolveDisplayPage`, `committedDisplayPage`, `mayDeleteCleanedImage`, `referencedCleanedImageNames`, `drainRetiredCleanedImage(s)` | reader-facing display + cleaned-image retention queries | CORE |
| 1794–1852 | `demoteCommittedDisplay`, `cancelArtifactCandidateLocked`, `demoteArtifactPageLocked`, `deleteArtifactPageLocked` | user-reset demotion; artifact candidate/page cancel-demote-delete | CORE |
| 1854–1915 | `rekeyArtifactPagesLocked`, `snapshotLocked`, `PageSnapshot.toPrecondition`, `rejected`, `snapshotPages` | artifact re-key; snapshot plumbing | CORE |
| 1917 | `glossarySnapshot` | glossary read | glossary |
| 1919–1988 | `artifactStatus()` | 70-line durable status projection (reconciles `BatchProgressReconciler`, expected-page padding, durable failures) | CORE projection |
| 1990–2001 | `translatedPairs` | glossary source pairs | glossary |
| 2003–2079 | `updateGlossary`, `loadGlossary`, `persistGlossaryLocked` | glossary durability. **`loadGlossary` keeps a LEGACY fallback** (2058–2059): `legacyDocuments().readValidated(glossaryName())` when no artifact authority | glossary + LEGACY |
| 2081–2085 | `legacyDocuments()`, `glossaryName()` | LEGACY helpers (flat-file sibling document I/O, `.glossary.json` naming) | **LEGACY** |
| 2087–2103 | `isQueueVisibleTransient`, `isQueueTransient`, `cancelIfTransient` | transient-stage classification | CORE |
| 2105–2131 | `shouldPersistUpdate` (`internal`) | durable-vs-transient write gate; documented history of the stranded-page bug | CORE |
| 2133–2144 | `persistLocked()` | **vestigial**: increments `persistCount`, then only asserts `authority == ARTIFACTS`; flat JSON is never rewritten (comment 2135–2143) | CORE (degenerated) |
| 2146–2204 | `flush`, `flushDirtyLocked`, `closeAndFlush`, `close`, `reconcileArtifactRetention(Locked)`, `schedulePersist` | flush/close lifecycle; debounce job; retention sweep | CORE lifecycle |
| 2206–2552 | **companion object** | see below | mixed |

Companion object detail:

| Lines | Member | Class |
|---|---|---|
| 2207–2211, 2525 | constants (`PERSIST_DEBOUNCE_MS`, `PERSIST_JOIN_TIMEOUT_MS`, `DEFAULT_FILE_NAME`, `IMAGE_DIMENSION_TOLERANCE_PX`) | CORE |
| 2213–2218 | `legacyPageJson = Json { ignoreUnknownKeys = true }` | shared serializer config (used by both probe and legacy decode) |
| 2220–2244 | `ArtifactManifestProbe`, `probeArtifactManifest` ×2 (`internal`) | CORE probe (artifact manifest header read) |
| 2247–2256 | `open(translationFile)`, `openArtifact(parent, fileName)` (`internal`) | factory facades |
| 2258–2332 | `openInternal` | **LEGACY decode**: reads flat JSON bytes (2263–2270), decodes `Map<String, PageTranslation>` and migrates `errorMessage` into per-stage error fields (2271–2299), then runs `migrateArtifactManifest` under the migration lock (2304–2318); calls `loadGlossary()` on the result (2330) |
| 2334–2340 | `ArtifactLoad` data class | migration result carrier |
| 2342–2464 | `migrateArtifactManifest` | **LEGACY rescue orchestration**: builds `LegacyPageFacts` (2374–2381), reads `.glossary.json` sidecar (2382–2398), calls `ChapterArtifactStore.loadOrMigrate` (2400–2416), post-cutover `reconcileLegacyPreservation` + `verifyLegacyArtifactHealth` (2418–2441), rehydrates committed/live/retired pages (2442–2462) |
| 2466–2475 | `legacyIdentityOf` | **LEGACY** SHA-256 identity of flat file/glossary |
| 2477–2478 | `legacyGlossaryName` | **LEGACY** |
| 2480–2515 | `CleanedFileValidation`, `cleanedFileValidationOf` | **LEGACY** bounded cleaned-image decode probe (existence/emptiness/bytes/dimensions) |
| 2517–2523 | `artifactImageProbe` (`@Volatile internal var`) | test seam for the probe — **tests assign it directly** |
| 2527–2532 | `ARTIFACT_MIGRATION_LOCKS`, `artifactMigrationLock` | per-chapter migration lock map (also used by `ensureArtifactStoreLocked`, 1639) |
| 2534–2550 | `lazy()` | factory for write-lazy artifact stores |

**LEGACY inventory (Store).** Everything still reachable, but only from chapter-open paths:
- Companion: `openInternal` flat decode 2263–2302; `migrateArtifactManifest` 2352–2464; `legacyIdentityOf` 2466–2475; `legacyGlossaryName` 2477–2478; `cleanedFileValidationOf` 2491–2515. Total ≈ **230 lines**.
- Instance: `legacyDocuments()` 2081–2082 + `glossaryName()` 2084–2085 + `loadGlossary` fallback 2058–2059 (≈8 lines).
- Callers: `open()`/`openArtifact()` are invoked from TranslationManager (7 sites, see B.1), ChapterTranslator.kt:245,543,553 and TranslationPipeline.kt:3538; tests invoke `open`/`openArtifact` via `ChapterTranslationStoreArtifactMigrationTest` and `TranslationManagerArtifactReadTest`. The heavy lifting already lives in `artifact/LegacyArtifactMigration.kt` (501 lines, called from `ChapterArtifactStore.loadOrMigrate` at ChapterArtifactStore.kt:136,1047,1216) — what remains in the Store is the *legacy-input gathering* (bytes, identity, glossary sidecar, cleaned-image probe) plus the health-verification orchestration.

### A.2 State & coupling

Single coarse `mutex` (99) guards: `pages`, `committedDisplay`, `artifactManifest`, `pendingArtifactPageRegistrations`, `pendingExpectedPageCount(Trusted)`, `glossary`, `dirty`, `glossaryDirty`, `generation`, `nextPageVersion`, `nextLeaseToken`. Exceptions:
- `pageLeases` (ConcurrentHashMap, 135) is guarded by the mutex in lease/patch paths **and** by `synchronized(pageLeases)` in `markDefunct` (254), `pageLeaseOwner` (473–475), `clearTransientQueuePages` (1380–1384) — dual locking discipline that any extraction must preserve.
- `retiredCleanedImages` (127) is a lock-free ConcurrentHashMap drained by the Pipeline/Manager.
- `defunct` (237) is `@Volatile`, written under mutex, read lock-free by every mutator (9 entry checks: 336, 392, 488, 563, 602, 679, 1101, 1132, 1158, 1201, 1267, 1332, 1800, 2004).
- `persistScope`/`persistJob` (221–224, 2192–2204): debounced flush; `markDefunct` joins it with a 2 s timeout (240–250). After Phase-artifact cutover, `flushDirtyLocked` only handles the **glossary** dirty flag (2152–2160) — page `dirty` is dead weight (persistLocked 2133–2144 always returns the authority check).
- Regions touching `artifactManifest`: admission (335–371), every publish (1405–1626), demote/delete/cancel/rekey (1814–1878), snapshots (1880–1894), `artifactStatus` (1919–1988), glossary persist (2003–2079), init (via constructor).
- Store-owned cross-file contract: `GenerationContext` (209–214) is placed by `withGeneration` (479–480) and consumed by Pipeline writers through `updatePage`'s context check (1105–1127).

### A.3 Boundary analysis (Store side)

Leaks out of the Store:
1. **Lifecycle/eviction concern inside a persistence class.** `markDefunct`/`defunct` (227–259) exist because the *Manager* evicts stores on delete/chapter-change; every mutator carries a defunct gate. A cleaner contract: the Manager owns eviction; the store exposes only a rejected-write sink. (Behavior-preserving extraction cannot change this now; flag for the planner.)
2. **Migration-time file forensics live in the store companion** (identity hashing, glossary sidecar read, bounded image probe, health verification — 2342–2464). These are open-time concerns of the artifact layer, not runtime store state; they belong next to `artifact/LegacyArtifactMigration.kt`.
3. **`probeArtifactManifest` (2226–2244) is a pure artifact-layer read** (manifest header decode) duplicated as a companion of a page-state class; the Manager calls it 4× (B.1).
4. **Vestigial flat-file plumbing retained for shape**: ctor `translationFile`/`fileCreator` (89–90), `persistLocked` (2133–2144), `dirty` flag, `DEFAULT_FILE_NAME` (2211). Comment 2135–2143 states the flat file is "a migration-time read/recovery source only".

Cleaner boundary: `ChapterTranslationStore` = in-memory page/committed-display state + fenced mutation + lease table + display projection, persisting through a narrow `ArtifactWriterBridge` interface; open/migrate/probe move to the artifact package as `ChapterStoreOpener` + legacy rescue orchestrator; glossary becomes a collaborator; eviction stays Manager-side via the existing defunct flag until a later contract change.

### A.4 Extraction candidates (ranked: low risk × high line yield)

| # | Candidate | Source lines | Destination | Est. net delta | Risk |
|---|---|---|---|---|---|
| S1 | Legacy open/migration companion block: `openInternal` legacy decode 2263–2302, `ArtifactLoad` 2334–2340, `migrateArtifactManifest` 2342–2464, `legacyIdentityOf` 2466–2478, `CleanedFileValidation`+`cleanedFileValidationOf` 2480–2515, `legacyPageJson` 2213–2218, lock map 2527–2532 | ≈ 260 | new `artifact/LegacyChapterMigrationSource.kt` (beside `LegacyArtifactMigration.kt`); keep `open/openArtifact/lazy` in the Store companion delegating to it; keep or co-move `artifactImageProbe` seam | **−220 … −240** | LOW — pure move; heavily test-pinned via public factories |
| S2 | `probeArtifactManifest` + `ArtifactManifestProbe` 2220–2244 | 25 | `artifact/ChapterArtifactManifestReader.kt` (keep deprecated delegating companion funs for tests) | −15 | LOW |
| S3 | Glossary subsystem: fields 216–219, `glossarySnapshot` 1917, `translatedPairs` 1990–2001, `updateGlossary` 2003–2047, `loadGlossary` 2049–2060, `persistGlossaryLocked` 2062–2079, `legacyDocuments`/`glossaryName` 2081–2085 | ≈ 135 | `ChapterGlossaryStore` collaborator holding glossary + dirty flag; store delegates under its mutex; **drop or keep the LEGACY read fallback explicitly** (2058–2059) | −100 | LOW-MED — needs internal accessors for `artifactStore`/`artifactManifest` under the store mutex |
| S4 | `artifactStatus()` 1919–1988 (+ `durableFailure`/`durableFailuresSnapshot` 320–328) | ≈ 80 | status projector next to `batch/BatchProgressReconciler` (already imported at 23, used at 1979) | −60 | MED — reads `pages`, `display`, `artifactManifest`; needs an internal consistent-snapshot accessor |
| S5 | Lease subsystem 159–171, 379–477 | ≈ 115 | `PageStageLeaseTable` (state class + table) | −90 | MED — dual locking (mutex + `synchronized(pageLeases)`) must be preserved; `Phase3Test` pins behavior |
| S6 | Retention/close machinery 2146–2204 (+ `persistLocked`, `dirty` bookkeeping) | ≈ 75 | `StorePersistenceScheduler` | −55 | MED — `markDefunct` join (240–250) and `close`/`closeAndFlush` callers (registry, Manager probe) depend on exact semantics |
| S7 | Stage-merge machinery 651–1098 + `publishLocked`/`pageWriteRejection` | ≈ 500 | `StageMergeEngine` over an internal `StoreWriteBackend` | −420 | HIGH — deepest mutex/identity entanglement; do last |

Realistic phased yield before S7: **≈ −540 lines**; with S7 ≈ **−960**.

### A.5 Extraction order (Store)

1. **Phase S-A: S1+S2** (legacy companion → artifact package). Gate: `ChapterTranslationStoreArtifactMigrationTest`, `TranslationManagerArtifactReadTest`, `ChapterArtifactStoreTest`, `LegacyArtifactMigrationTest`, then full suite. Keep companion delegating stubs so `ChapterTranslationStore.artifactImageProbe` / `.probeArtifactManifest` seams still resolve (tests assign them directly — see A.7). One commit; revert = `git revert`.
2. **Phase S-B: S3** glossary collaborator. Gate: `ChapterTranslationStoreArtifactMigrationTest` (glossary tests at 358–377, 477–490), pipeline glossary tests (`test:.../translator`, `ocr` dirs use `translatedPairs` semantics via store API — run full suite).
3. **Phase S-C: S4** status projector. Gate: `ChapterTranslationStoreArtifactMigrationTest` (baseline/warn tests 294–357), `TranslationManagerArtifactReadTest` 119–190.
4. **Phase S-D: S5+S6** lease table + persistence scheduler. Gate: `ChapterTranslationStorePhase3Test`, `ChapterTranslationStoreDefunctTest`, `ChapterTranslationStorePersistenceTest`.
5. **Phase S-E: S7** stage-merge engine. Gate: `ChapterTranslationStorePhase3Test` (merge/lease/fence tests), plus reader smoke via full suite.

### A.6 Test coverage map (Store)

| Test (test:eu/kanade/translation/) | Lines | Pins |
|---|---|---|
| `ChapterTranslationStoreArtifactMigrationTest.kt` | 729 | the entire legacy/migration region S1: non-destructive migration (202), removed-field tolerance (232), committed-only rehydrate (251), baseline publication (294–356), lazy glossary (358), cutover + no legacy resync (378), manifest fast path (411), cleaned-byte/dimension probes (424–452), corrupt JSON (477), candidate resume/cancel/failure (491–567), released-callback fencing (568), promotion stream retention (626), lazy cutover (675). Sets `ChapterTranslationStore.artifactImageProbe` (64, 108) |
| `ChapterTranslationStorePhase3Test.kt` | 258 | committed-display promotion (49–94), leases + fenced writes (95–177), OCR merge preservation (178–220), candidate cancel vs committed (221), textless terminal (241) → protects S5/S7 |
| `ChapterTranslationStoreDefunctTest.kt` | 131 | `markDefunct` semantics + no-op mutators; uses `persistCount` seam (59, 70, 128) → protects lifecycle region S6/defunct |
| `ChapterTranslationStorePersistenceTest.kt` | 38 | failed-persist-stays-dirty via `persistCount` (33, 36) → protects `flushDirtyLocked`/`schedulePersist` |
| `ChapterTranslationStoreRekeyTest.kt` | 65 | `rekeyPages` (four cases) → protects 1196–1256 + `rekeyArtifactPagesLocked` |
| `ActiveChapterStoreRegistryTest.kt`, `CleanedImagePublisherTest.kt` | — | construct `ChapterTranslationStore(null, null)` **positionally** — ctor signature is test-pinned |
| `test:.../artifact/ChapterArtifactStoreTest.kt`, `LegacyArtifactMigrationTest.kt` | — | pin the artifact side `loadOrMigrate` contract that S1 code calls |

Unpinned Store regions (no direct tests found): `updatePage` compatibility path details (1100–1129) beyond defunct no-op; `updateGlossary` rejection logging; `deletePage` (only via Manager reset tests); `replaceAll` beyond defunct no-op.

### A.7 Blind spots (Store)

- **No `@Serializable` declarations in this file.** Decode relies on `model/PageTranslation` (@Serializable, model/PageTranslation.kt:8) and `artifact/ChapterArtifactManifest`. Moving decode code inside the app module is serialization-safe; `legacyPageJson` config (`ignoreUnknownKeys = true`, comment 2213–2217 explains unknown-field tolerance) must be replicated exactly wherever the decoder lands.
- **`internal` is module-scoped, not package-scoped**: `probeArtifactManifest`, `openArtifact`, `ArtifactManifestProbe`, `artifactImageProbe`, `persistCount`, `shouldPersistUpdate` can move package within `:app` freely, **but tests reference them by qualified name** (`ChapterTranslationStore.artifactImageProbe` in ArtifactMigrationTest:64,108 and ArtifactReadTest:35,39; `ChapterTranslationStore.probeArtifactManifest` in ArtifactReadTest:129–131,160). Either keep delegating seams or update tests in the same commit.
- **Constructor is positionally pinned** by tests (A.6). Do not reorder/remove `translationFile`/`fileCreator` in a pure-move phase.
- **Dual lock discipline** on `pageLeases` (A.2) and the `NonCancellable` wrappers (441, 452, 466) — moving lease code without them changes cancellation behavior.
- `GenerationContext` identity check (`context?.store === this`, 1107/1119) couples the patch path to `withGeneration` users in Pipeline; the element must stay in the same class as `withGeneration`.
- Companion mutable global `artifactImageProbe` (2522–2523) is process-global test state; two tests reset it in `@Before`/`@After` — a moved copy would fork the seam.

---

## PART B — TranslationManager.kt (2,056 lines)

### B.1 Declaration map

File-level:

| Lines | Declaration | Class |
|---|---|---|
| 75–76 | `MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP`, `ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS` | CORE (image lifecycle) |
| 78–79 | `isFreshOrphanedCleanedImage` (`internal`) — pinned by `TranslationManagerArtifactReadTest:192` | CORE |
| 81–87 | `acknowledgePendingTranslationState` (`internal`) — pinned by `TranslationManagerPendingAcknowledgementTest` | CORE (pending requests) |

Class `TranslationManager` (89; DI singleton — `di/AppModule.kt:132`):

| Lines | Group | Members | Class |
|---|---|---|---|
| 95–96 | engines | `pipeline`, `translator` | CORE |
| 99 | stream registry | `streamRegistry` (DI singleton held here so delete can evict reader closures) | CORE |
| 105–108 | scopes/locks | `applicationScope`, `readerTeardownMutex` | CORE |
| 110–125 | **pending-request state** | `pendingRequestStore` (own file `TranslationPendingRequestStore.kt`), `pendingTranslationRequestsState`, `pendingRequestWriteVersions`, public `pendingTranslationRequests`, `pendingRequestMutationLock` | CORE subsystem |
| 127–145 | value types + cache | `DurableChapterKey`, `DurableStatus`, `TranslationDocument` (registryKey = `"${parent.filePath ?: parent.uri}:$fileName"`, 142), `durableStatusCache` | CORE (status resolver) |
| 153–159 | **scheduler** | `scheduler` (own file `scheduling/TranslationScheduler.kt`) wired with `storeResolver` reading `activeStores` — declared **before** `activeStores` (240); safe only because the lambda defers | CORE |
| 161–230 | `init` | pipeline callback wiring: `activeStoreResolver` (163–172), `onBatchClosed` → orphan sweep (173–185), deliberately-unwired `activeStoreUnregister` (186–190), `onPageStuck` (194–198), `batchTrackerFactory` (201–203), rehydration collectors: durableStatusCache clear (207–209), paused-notification projection (210–228), `restoreQueue` (229) | CORE wiring |
| 236–254 | delegates | `markPageJobStuck`, registries `activeStores` (240, own file `ActiveChapterStoreRegistry.kt`), `batchTrackerRegistry` (241, own file `batch/TranslationBatchTrackerRegistry.kt`), **`legacyPageJson` (242, LEGACY)**, `storeScope` (245), `isRunning`/`queueState`/`isAnyBatchTranslationActive` | CORE |
| 256–426 | **pending-request subsystem** | `queueTranslationAfterDownload` (256), `acknowledgeTranslationRequests` (263–282), `markTranslationRequestPreparing` (285), `markTranslationDownloadFailed` (292), `cancelTranslationRequest` (301), `clearStaleDownloadFailedRequest` (314), `hasPendingTranslationRequest` (322), `isChapterTranslationProtected` (330), `protectedChapterIds` (341), `setPendingTranslationRequest` (354), `clearPendingTranslationRequest` (368), `clearAllPendingTranslationRequests` (376), `nextPendingRequestVersion` (385), `persistPendingStartingAcknowledgement` (393–406), `loadPendingTranslationRequests` (408), `startTranslationAfterDownloadIfRequested` (417–426) | CORE subsystem ≈ 240 lines, fully cohesive |
| 428–464 | **reader teardown** | `stopReaderTranslations` (428), `requestReaderStop` (447), `awaitReaderStop` (453–464) | CORE ≈ 40 |
| 466–540 | queue queries + engine controls | `isTranslating`, `isPageActive`, `getTranslationProgress`, `isTranslationActive`, `translatorStop`, `onMemoryPressure`, `startTranslation`, `pauseTranslation`, `requeueTranslation`, `clearQueue`, `getQueuedTranslationOrNull`, `isBatchTranslationActive`, `isBatchTranslationRetained` | CORE |
| 542–652 | queue entry | `translateChapter` (542), `translateChapters` (556), `markTranslationQueueFailureIfAcknowledged` (572), `translateChapterPreflight` (593), `evictStaleQueuedChapters` (613), `cancelRunningChapterForReplace` (638, runBlocking bridge 643–650) | CORE |
| 654–710 | status facade | `getChapterTranslationStatus` (654–674), `observeChapterTranslationStatus` (676–701), `isChapterTranslated` (704–710) | CORE |
| 712–762 | **durable status resolver** | `persistedChapterStatus` + cache (712–735, runBlocking 721), `resolveDurableChapterStatus` (737–762): manifest probe → `withProbeStore` → `artifactStatus()`; **LEGACY fallback** `decodeLegacyChapterStatus` at 760 | CORE + LEGACY |
| 764–787 | **LEGACY decode** | `decodeLegacyChapterStatus` (764–780, quarantines on failure), `statusFromReadablePages` (782–787) | **LEGACY** |
| 789–849 | reader/chapter reads | `getChapterTranslation(name…)` (789–809), `getChapterTranslationForReader` (811–832; **LEGACY fallback** at 831), `getChapterTranslation(file)` (834–849; runBlocking; **LEGACY fallback** at 848) | CORE + LEGACY |
| 851–919 | store open helpers | `openExistingChapterTranslationStore` (851–880), `findTranslationDocument` (882–892), `withProbeStore` (894–919, probe adoption + `closeAndFlush` release) | CORE |
| 921–951 | **LEGACY decode + quarantine** | `decodeLegacyChapterTranslation` (921–934), `quarantineCorruptTranslationFile` (936–946), `quarantineCorruptDocument` (`internal`, 948–949), `UniFile.registryKey()` ext (951) | **LEGACY** ≈ 32 lines |
| 953–1008 | store facade | `hasTranslationStore` (954), `rekeyTranslationForCompletedDownload` (961–1008, runBlocking join 989) | CORE |
| 1010–1114 | **registry ownership** | `registerActiveTranslationStore` (1010), `unregisterActiveTranslationStore` (1016–1021, marks store defunct), `openOrCreateActiveChapterTranslationStore` (1030, runBlocking), `…Suspend` (1055), shared impl (1080–1114: probe → open/openArtifact/**lazy**) | CORE ≈ 100 |
| 1116–1254 | **cleaned-image lifecycle** | `scheduleRetiredCleanedImageCleanup` (1127–1172), `sweepOrphanedCleanedImageos`→`sweepOrphanedCleanedImages` (1174–1204), `retireChapterCompanionImages` (1206–1229), `retirePageCompanionImage` (1231–1254) | CORE ≈ 140 |
| 1256–1345 | session + auto window + trackers | `openTranslationSession` (1256), auto-window delegates (1277–1319), `observeActiveDisplayStore` (1322), `selectActiveStore` (1329), `createBatchTracker` (1331), `disposeBatchTracker` (1343), `terminalSnapshotCacheSize` (`internal` test seam, 1347) | CORE |
| 1349–1525 | **progress projection** | `observeBatchProgress` (1355–1375), `observeQueuedTranslationStatus` (1377–1390), `observeBatchProgressProjection` (1392–1451), `snapshotFromStore` (1453), `withDurablePause` (1467), `projectQueueStatus` (1484), `observeTranslationProgress` (1517), `observePageView` (1521) | CORE ≈ 175 |
| 1527–1904 | **delete + reset flows** | `deleteTranslation` (1527–1590, load-bearing 6-step ordering documented 1544–1557; `ChapterArtifactDeletionPlan` capture 1536–1543), `deletePageTranslation` (1592), `chapterResetPreflight` (1596), `resetChapterTranslationData` (1614), `resetChapterInpaintData` (1644), `resetChapterOcrData` (1658), `resetChapterData` (1662–1701), `resetTranslationData` (1703–1776), `resetInpaintData` (1778–1843), `resetOcrData` (1845–1889), `reconcileBatchProgress` (1891–1904) | CORE ≈ 370 lines; heavy copy-paste between active-store and open-store branches (1677–1699 vs 1686–1698; 1710–1736 vs 1738–1772; 1786–1799 vs 1801–1821) |
| 1906–1936 | manga delete / queue removal | `deleteManga` (1906), `cancelQueuedTranslation` (1919), `removeFromTranslationQueue` (1923–1936) | CORE |
| 1938–1968 | image streams | `getCleanedImageStream` (streamRegistry-backed) | CORE (image lifecycle) |
| 1970–2040 | page-job controls | `translatePage` (1970), `cancelPageTranslation` (1978), `cancelPageTranslations` (1987–1996), `cancelAllPageTranslations` (2004–2029, runBlocking ordering fix documented 2010–2017), `cancelAllPageTranslationsOffMain` (2036) | CORE (teardown) |
| 2042–2056 | `statusFlow` | queue-status merge for the foreground service | CORE |

**LEGACY inventory (Manager).** ~60 lines, all still reachable:
- `legacyPageJson` field 242 (duplicate of the Store companion Json).
- `decodeLegacyChapterStatus` 764–780 + `statusFromReadablePages` 782–787 — reached from `resolveDurableChapterStatus:760` when the manifest probe is absent (pure flat-file chapter).
- `decodeLegacyChapterTranslation` 921–934 — reached from `getChapterTranslationForReader:831` (manifest `LEGACY` authority) and `getChapterTranslation(file):848`.
- `quarantineCorruptTranslationFile` 936–946 + `quarantineCorruptDocument` 948–949 — called from both decode paths on failure.
- These do NOT touch `ChapterTranslationStore.openInternal`; they are an independent second decode path over the same flat format (two `Json { ignoreUnknownKeys = true }` instances: Store 2218, Manager 242).

### B.2 State & coupling

- **Registries and owners**: `activeStores` (240, `ActiveChapterStoreRegistry`) — owned by the Manager; mutated by register/unregister (1010–1021), open-or-create (1080–1114), eviction in teardown (459–461, 1995, 2006–2024), deleteTranslation (1563). `batchTrackerRegistry` (241) — created/disposed by `createBatchTracker`/`disposeBatchTracker` (1331–1345), observed by `observeBatchProgressProjection` (1395), disposed in `deleteTranslation` (1562) and `cancelPageTranslations` (1992). `pendingRequestStore` + `pendingTranslationRequestsState` + `pendingRequestWriteVersions` (110–118) — owned exclusively by the pending-request subsystem (256–426) under `pendingRequestMutationLock`. `scheduler` (153) — owns page/auto job maps; the Manager only delegates. `durableStatusCache` (145) — written by `persistedChapterStatus` (733) and **cleared from 9 scattered sites**: 208, 845, 878, 911, 1013, 1020, 1589, 1669, 1705, 1780, 1847.
- **Scopes/locks**: `applicationScope` (105) — init collectors, teardown bridges (432, 448, 1137); `storeScope` (245) — tracker reducer jobs (1339); `readerTeardownMutex` (108) — serializes `stopReaderTranslations`/`awaitReaderStop` (433, 457); `pendingRequestMutationLock` (125) — versions+publication+durable write atomicity (266, 359, 369, 377, 394).
- **Manager→Store touches** (all via the Store's very wide public surface): `state`/`display` flows, `artifactStatus()`, `durableFailuresSnapshot()`, `clearTransientQueuePages`, `demoteCommittedDisplay`, `updatePageFromCurrentSnapshot`, `deletePage`, `rekeyPages`, `beginGeneration`, `flush`, `resetPreflight`, `drainRetiredCleanedImages`, `mayDeleteCleanedImage`, `referencedCleanedImageNames`, `markDefunct`, plus companion `probeArtifactManifest`/`open`/`openArtifact`/`lazy`.
- **Manager→Pipeline wiring** (init 161–229) sets `activeStoreResolver`, `onBatchClosed`, `onPageStuck`, `batchTrackerFactory` (declared TranslationPipeline.kt:256,264,476,480) — bidirectional: the Pipeline later calls back into Manager-owned factories.

### B.3 Boundary analysis (Manager side)

1. **The Manager is simultaneously facade, subsystem owner, and legacy decoder.** Scheduler/registry/translator delegation is facade; pending-request state machine, durable-status cache, reset flows, image lifecycle are real subsystems embedded inline.
2. **Store lifecycle leak**: Manager decides eviction ordering (`deleteTranslation` 1544–1564 documents a 6-step load-bearing order; `cancelAllPageTranslations` 2010–2017 another). The Store cooperates by exposing `markDefunct` — an eviction protocol smeared across both files. A cleaner boundary: a single `ChapterStoreEvictionCoordinator` owning the ordering, with the Store exposing only `close(forEviction: Boolean)`.
3. **Legacy decode duplication**: Manager decodes flat JSON independently of the Store's `openInternal` (B.1) — same format, same `ignoreUnknownKeys`, different failure handling (Manager quarantines via `AtomicChapterDocuments.quarantineCorrupt`; Store logs and starts empty, 2295–2299). Both belong in one legacy module beside `artifact/LegacyArtifactMigration.kt`.
4. **Registry-key duality (latent hazard, do not "fix" in a move)**: `TranslationDocument.registryKey` = `parent:fileName` (142) vs `UniFile.registryKey()` = `filePath ?: uri` (951). `getChapterTranslation(file):839` keys by the latter, `openExistingChapterTranslationStore` by the former — the same file can occupy two `fileStores` slots. Flag to planner; preserve semantics in extraction.
5. **Reset flows bypass the fenced write API** (`updatePageFromCurrentSnapshot`) and then `demoteCommittedDisplay` — they are Store clients with their own ordering rules (cancel → clear stream → mutate → demote → flush → reconcile). Extract as a unit, don't inline into the Store.

### B.4 Extraction candidates (ranked)

| # | Candidate | Source lines | Destination | Est. net delta | Risk |
|---|---|---|---|---|---|
| M1 | LEGACY decode + quarantine: 242, 764–787, 921–949 | ≈ 60 | `legacy/LegacyFlatFileDecoder.kt` (package `eu.kanade.translation.legacy`) — one Json instance, both decode fns, quarantine | **−50** | LOW — pinned by `TranslationManagerArtifactReadTest:201–259` (legacy resolve, quarantine, quarantine-race tests) |
| M2 | Pending-request subsystem: 81–87, 110–125, 256–426 (+ call sites 546, 561, 549/551, 564/566, 1264, 514, 2027) | ≈ 260 | `TranslationRequestCoordinator` (owns store+state+versions+lock; Manager delegates) | **−210** | LOW-MED — cohesive, pinned by 3 test files; the version-fence protocol (393–406) must move intact |
| M3 | Durable status resolver: 127–145, 712–762, 882–919 (+ cache clears) | ≈ 150 | `DurableChapterStatusResolver` (cache + probe + findTranslationDocument + withProbeStore) | −120 | MED — 11 scattered cache-invalidation call sites must route through it |
| M4 | Progress projection: 1349–1525 + `getChapterTranslationStatus`/`observeChapterTranslationStatus` 654–701 | ≈ 240 | `BatchProgressProjector` taking (queueState, registry, stores, scheduler-resolver) | −190 | MED — flow graph only, no locks; pinned by ArtifactReadTest status tests + AutoArbitrationTest |
| M5 | Cleaned-image lifecycle: 75–79, 1116–1254, 1938–1968 | ≈ 190 | `CleanedImageLifecycleController` (streamRegistry + provider) | −150 | MED — SAF/threading constraints documented at 1116–1126 must move with it |
| M6 | Delete + reset flows: 1527–1904 | ≈ 370 | `ChapterDataResetController` (pure move; dedupe of the 3 copy-paste branch pairs is a **separate later** behavior-risking change) | −330 | MED-HIGH — `deleteTranslation` ordering is load-bearing; weakest direct test coverage of the big groups |
| M7 | Reader/page teardown: 428–464, 1970–2040 | ≈ 130 | `ReaderTeardownCoordinator` (owns `readerTeardownMutex`) | −100 | MED — pinned by `TranslationManagerReaderTeardownTest` (4 tests incl. undispatched-prefix regression) |

Realistic yield M1+M2 ≈ **−260** in the first two phases; total identified ≈ **−1,150**.

### B.5 Extraction order (Manager)

1. **Phase M-A: M1** legacy decoder (+ optionally merge with Store phase S-A into one legacy package commit — recommend keeping them as separate commits per file). Gate: `TranslationManagerArtifactReadTest` (full), full suite.
2. **Phase M-B: M2** request coordinator. Gate: `TranslationManagerPendingAcknowledgementTest`, `TranslationManagerDownloadFailureRecoveryTest`, `TranslationManagerAutoArbitrationTest`, full suite.
3. **Phase M-C: M4** progress projector. Gate: `TranslationManagerArtifactReadTest` 119–190, `TranslationManagerAutoArbitrationTest`.
4. **Phase M-D: M3** status resolver. Gate: same as M-C plus `TranslationManagerDownloadFailureRecoveryTest` (uses cached-status retry semantics, test 261).
5. **Phase M-E: M5** image lifecycle. Gate: `CleanedImagePublisherTest`, `TranslationManagerArtifactReadTest:192` (freshness), full suite.
6. **Phase M-F: M7** teardown coordinator. Gate: `TranslationManagerReaderTeardownTest`, `ChapterTranslationStoreDefunctTest` (eviction interplay).
7. **Phase M-G: M6** reset/delete controller. Gate: full suite mandatory (no dedicated test file); recommend adding a characterization test for `deleteTranslation` ordering *before* this phase.

### B.6 Test coverage map (Manager)

| Test | Lines | Pins |
|---|---|---|
| `TranslationManagerArtifactReadTest.kt` | 274 | durable status via artifact authority (119–190), `isFreshOrphanedCleanedImage` (192), **LEGACY flat-file resolve (201)**, **quarantine without deletion (211), quarantine copy preservation (225), quarantine race (242)**, recoverable missing-input retry (261) → protects M1 and M3/M4 status paths; resets `ChapterTranslationStore.artifactImageProbe` (35, 39) |
| `TranslationManagerReaderTeardownTest.kt` | 281 | reader-stop returns before cleanup yet preserves active batch store (35), paused store preserved (111), teardown off caller thread (141), no undispatched scheduler prefix (192) → protects M7 |
| `TranslationManagerDownloadFailureRecoveryTest.kt` | 209 | DOWNLOAD_FAILED phase transitions, restart persistence via durable store, download→translate advancement (66–120) → protects M2 |
| `TranslationManagerPendingAcknowledgementTest.kt` | 21 | `acknowledgePendingTranslationState` immediate publication (11) → protects M2 |
| `TranslationManagerAutoArbitrationTest.kt` | 224 | paused = durable-but-inactive (40), auto window stays active while queued (75); constructs a real store (76) → protects M2/M4 arbitration inputs |
| `ActiveChapterStoreRegistryTest.kt` | 183 | registry semantics (probe adoption, keep-existing) → protects the registry the Manager drives |

Unpinned Manager regions: `deleteTranslation` exact ordering, all `reset*` flows, `evictStaleQueuedChapters`, `rekeyTranslationForCompletedDownload`, `getCleanedImageStream`, `deleteManga`, `statusFlow` paused-projection collector (210–228). Phases touching these (M6, parts of M5) have the least protection.

### B.7 Blind spots (Manager)

- **No `@Serializable` declarations here either**; both `legacyPageJson` instances decode `Map<String, PageTranslation>` (model package serializer). Moving within the module is safe; do not touch `model/PageTranslation`.
- **`internal` seams referenced by tests**: `acknowledgePendingTranslationState` (PendingAcknowledgementTest), `isFreshOrphanedCleanedImage` (ArtifactReadTest:192), `quarantineCorruptDocument` (ArtifactReadTest quarantine tests), `terminalSnapshotCacheSize` (batch/TranslationBatchTrackerRegistryTest). Module-scoped, so package moves are fine, but signatures must not change.
- **Field-initialization order**: `scheduler` (153) captures `activeStores` (240) inside lambdas (deferred — safe today). Any extraction that converts these to eager property reads or reorders declarations introduces an NPE window. Same for `init` (161–229) depending on `pipeline`/`translator` (95–96).
- **runBlocking bridges** at 643–650, 721, 836, 989, 1037, 2018 — each carries a documented dispatcher/thread constraint (e.g. 428–431, 987–988, 1048–1054). Extraction must move the comments and the `Dispatchers.IO` context together.
- **`durableStatusCache` invalidation is protocol, not detail**: the 11 clear sites exist because opens/rescues/deletes change durable truth (comments at 843–845, 909–911, 730–732). A resolver extraction that misses one clear site resurrects stale TRANSLATED states — exactly the bug class the cache guards against.
- **DI singleton identity**: `TranslationManager` is a singleton (AppModule.kt:132) and holds `streamRegistry` (99) precisely so delete can evict reader closures. Sub-components extracted from it must keep receiving the same instances (no new DI entries needed if constructed by the Manager).

---

## PART C — Cross-file boundary (the contract to establish)

1. **Open/migrate/probe** (Store companion 2206–2552 + Manager 712–762, 882–919) → one artifact-package module: `ChapterStoreOpener` (factories), `LegacyChapterMigrationSource` (legacy input gathering), `LegacyFlatFileDecoder` (Manager-side decode/quarantine; may absorb the Store's flat decode for a single implementation). Precedent: `artifact/LegacyArtifactMigration.kt` already owns the mapping rules.
2. **Eviction protocol** (Store `markDefunct`/defunct gating + Manager ordering in `deleteTranslation`/`cancelAll*`) → documented in one coordinator; Store keeps the defunct flag (contract unchanged) in the dismantling phases.
3. **Durable read API** for the Manager: today the Manager consumes 16+ Store members. The minimal clean set is: `state`, `display`, `snapshot()`, `artifactStatus()`, `durableFailuresSnapshot()`, `resetPreflight()`, plus the mutation API it already uses. Everything else (`drainRetiredCleanedImages`, `mayDeleteCleanedImage`, `referencedCleanedImageNames`) belongs to the image-lifecycle component talking to the Store through a narrow `CleanedImageRetention` interface.
4. **Queue/lifecycle vs reader-teardown vs decode-path groupings**: Manager splits into facade + {RequestCoordinator, ProgressProjector, StatusResolver, TeardownCoordinator, ResetController, ImageLifecycle}; Store splits into state+patch core + {LeaseTable, GlossaryStore, StatusProjector, PersistenceScheduler, StageMergeEngine}, with open/migration in the artifact package.

Combined realistic yield: **≈ −1,700 to −2,100 lines** across both files (from 4,608), with the first two phases (legacy moves, both sides) delivering ≈ −270 at the lowest risk and strongest test protection.

## PART D — Recommended phase sequence (both files, global)

| Phase | Content | Primary gates | Rollback |
|---|---|---|---|
| 1 | M1 Manager legacy decoder | TranslationManagerArtifactReadTest | revert 1 commit |
| 2 | S1+S2 Store legacy companion → artifact pkg | ChapterTranslationStoreArtifactMigrationTest + artifact tests | revert 1 commit |
| 3 | M2 request coordinator | PendingAcknowledgement + DownloadFailureRecovery + AutoArbitration | revert 1 commit |
| 4 | S3 glossary collaborator | ArtifactMigrationTest (glossary cases) | revert 1 commit |
| 5 | M4 + M3 projections/resolver | ArtifactReadTest + AutoArbitrationTest | revert 1 commit |
| 6 | S4 status projector + M5 image lifecycle | ArtifactReadTest:119–192 + CleanedImagePublisherTest | revert 1 commit |
| 7 | S5+S6 leases + persistence scheduler; M7 teardown | Phase3 + Defunct + Persistence + ReaderTeardown tests | revert 1 commit |
| 8 | M6 reset/delete controller (add characterization test for `deleteTranslation` first) | full suite | revert 1 commit |
| 9 | (optional, separate approval) S7 stage-merge engine; reset-flow dedupe | Phase3Test + full suite | revert 1 commit |

Each phase is a pure move (no logic change), compiles green, passes targeted tests then the full suite, and is independently revertible.
