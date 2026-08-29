# T909 Wave 4 — Delivery Report

Owner: Implementer · Date: 2026-08-29 · Branch: `optimize_translation_finishing_page` (no push)
Scope: Phases 15–19 of `dismantling-plan.md` — behavior-preserving moves only.
Verification: `JAVA_HOME=<Android Studio jbr>`; per phase: targeted tests → full filtered suite
`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*" --tests "eu.kanade.tachiyomi.ui.manga.*"
--tests "eu.kanade.tachiyomi.ui.reader.*" --tests "eu.kanade.tachiyomi.data.*" --tests "eu.kanade.tachiyomi.extension.*"`
(variant `standardDebug`). Regions located by declaration name (line numbers had drifted from the plan).

## Result summary

- **7 commits landed (Phases 15, 16, 17a, 17b, 18, 19-test, 19-move); 0 phases reverted.**
  Full filtered suite green after every phase; final run **1076 tests / 0 failures / 0 errors**
  (1073 pre-existing + 3 new Phase-19 characterization tests).
- 4 new main files + 1 new test file; both Wave-4 god-files reduced:
  - `ChapterTranslationStore.kt` 2,191 → 2,025 (−166 net; ~272 gross moved out across Phases 15/17a/17b)
  - `TranslationManager.kt` 1,704 → 1,240 (−464 net; ~657 gross moved out across Phases 16/18/19)
- **~920 lines moved out of the god-files** (plus 296 lines of prerequisite characterization test).
- Char-identity verified per phase by scripted line-by-line diff of the moved regions against the
  pre-move commit: only package/imports/visibility deltas (`private`→`internal`, nested→top-level data
  class, same-name accessor shims). No logic edits, renames inside moved bodies, reorderings, or
  comment changes. No ctor reorders (store ctor params unchanged, only `private`→`internal` visibility).
- Known pinned seams all intact: `terminalSnapshotCacheSize`, `acknowledgePendingTranslationState`,
  `quarantineCorruptDocument`, `protectedChapterIds`, `permitHolderPageKeySnapshot()`,
  `ChapterTranslationStore.artifactImageProbe` / `.probeArtifactManifest`, `persistCount`.

| Phase | Commit(s) | Gates | Yield |
|---|---|---|---|
| 15 | `cb9eedb` | Migration+ArtifactRead green; full 1073/0/0 | store −52; new `StoreStatusProjector.kt` (126) |
| 16 | `df29156` | CleanedImagePublisher+ArtifactRead green; full 1073/0/0 | manager −87; new `CleanedImageLifecycleController.kt` (187) |
| 17a | `7e4db0c` | Phase3+Defunct+Persistence+Migration green; full 1073/0/0 | store −72; new `PageStageLeaseTable.kt` (148) |
| 17b | `abcc40d` | Phase3+Defunct+Persistence green; full 1073/0/0 | store −42; new `StorePersistenceScheduler.kt` (146) |
| 18 | `59dfa09` | ReaderTeardown+Defunct green; full 1073/0/0 | manager −65; new `ReaderTeardownCoordinator.kt` (176) |
| 19 (test) | `a4044f4` | New characterization test green on UNMOVED code (own commit, before the move) | +296 test lines |
| 19 (move) | `02655c7` | Characterization+ArtifactRead+DownloadFailureRecovery green; full suite MANDATORY: **1076/0/0** | manager −312; new `ChapterDataResetController.kt` (486) |

## Phase 15 — Store status projector · `cb9eedb`

Moved verbatim: `artifactStatus()` (incl. its KDoc and the `BatchProgressReconciler` padding/reconciliation
tail), `durableFailure`, `durableFailuresSnapshot` → `internal class StoreStatusProjector(store)` +
`internal class StoreStatusInputs` in `store/`.
Consistent-snapshot accessor (plan spec / investigation S4): the store exposes
`internal fun statusProjectionInputs(): StoreStatusInputs`, capturing (manifest, state flow, display flow)
in the same read order as the pre-move body. The flows are handed over **by reference** so `.value` still
reads at the moved body's original points — the projection is byte-for-byte behavior-identical (no locking
added; `artifactManifest` still read once at the top). The projector resolves its bare reads through
same-name accessors built on that accessor. Store keeps same-signature stubs (`artifactStatus`,
`durableFailure`, `durableFailuresSnapshot`) at the old qualified names — `ChapterTranslator`,
`DurableChapterStatusResolver`, `BatchProgressProjector`, and the migration/artifact-read tests resolve
them there. Three now-unused store imports removed.

## Phase 16 — Manager cleaned-image lifecycle · `df29156`

Moved verbatim: `MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP`, `ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS`,
`isFreshOrphanedCleanedImage` (top-level, → `manager` package), `scheduleRetiredCleanedImageCleanup`
(with its SAF/threading KDoc — registry delete callback is SAF binder I/O, must never run on the caller's
thread), `sweepOrphanedCleanedImages`, `retireChapterCompanionImages`, `retirePageCompanionImage`,
`getCleanedImageStream` → `internal class CleanedImageLifecycleController` (provider-lambda ctor:
`applicationScope`/`streamRegistry`/`provider`, resolved per call — Phase 9/13 convention).
`isFreshOrphanedCleanedImage` keeps a delegating stub at `eu.kanade.translation` (ArtifactReadTest L192
pinned seam). Manager keeps same-signature private stubs for the four lifecycle fns (named-arg call site
in `init`'s `onBatchClosed` still compiles) and a public stub for `getCleanedImageStream`.

## Phase 17a — Page-stage lease table · `7e4db0c`

Moved verbatim (incl. the Phase-3 lifecycle-contract comment block): `PageLeaseRecord`
(private→`internal` top-level-in-class), the `pageLeases` map + `nextLeaseToken`, and the five lease
members `tryAcquirePageStageLease` / `releasePageStageLease` / `cancelPageStageWork` /
`releaseAllPageLeases` / `pageLeaseOwner` → `internal class PageStageLeaseTable(store)`.
**DUAL locking preserved verbatim**: store-mutex `withLock` paths and `synchronized(pageLeases)`
lock-free readers both moved untouched; `NonCancellable` wrappers on release/cancel/releaseAll moved
untouched. The map is SHARED, never copied: the table owns it (`val pageLeases`) and the store keeps a
same-name getter (`private val pageLeases get() = pageStageLeaseTable.pageLeases`) so all ten store-side
readers (`markDefunct`, `patchPage`, `mergeOcrLocked`, `stageIdentityRejection`, `pageWriteRejection`,
`updatePage`, `deletePage`, `clearTransientQueuePages`, `persistArtifactMutationLocked`, `snapshotLocked`)
are char-identical and `synchronized(...)` still locks the same instance. Store keeps the five public
members as delegating stubs (pipeline, `ReaderViewModel`, Phase3/Migration tests resolve there).
`snapshotLocked`/`cancelArtifactCandidateLocked` widened `private`→`internal` for the table's shims.

## Phase 17b — Store persistence scheduler · `abcc40d`

Moved verbatim (bodies incl. all inline comments): `persistLocked`, `flush`, `flushDirtyLocked`,
`closeAndFlush`, `close`, `reconcileArtifactRetention`, `reconcileArtifactRetentionLocked`,
`schedulePersist`, plus `persistScope` and the constants `PERSIST_DEBOUNCE_MS` / `PERSIST_JOIN_TIMEOUT_MS`
→ `internal class StorePersistenceScheduler(store)`, constructed **eagerly** by the store (ctor resolves
no store state — glossary-store precedent) so the debounce scope is one stable instance and cancel/join
semantics are unchanged.
**`markDefunct` join semantics exact**: `markDefunct` did not move; its bounded
`withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) { persistJob.join() }` is char-identical, reading `persistJob`
(still a store field) and the join timeout via a same-name companion delegating val to the scheduler's
internal const. `dirty` and `persistJob` stay store fields (non-moved bodies in
`replaceAll`/`rekeyPages`/`clearTransientQueuePages`/`markDefunct` write them directly); the scheduler
reaches them via internal var-shims. `persistCount` stays a store field with `private set` dropped (the
scheduler increments it) — DefunctTest/PersistenceTest seams unchanged. `translationFile`/`fileCreator`/
`artifactParent` widened `private`→`internal` (positional ctor order untouched). Store keeps stubs for
`flush`/`closeAndFlush`/`close`/`reconcileArtifactRetention`/`schedulePersist` (incl. `markPageDirty`
named-arg caller in the glossary delegate)/`persistLocked`.

## Phase 18 — Reader teardown coordinator · `59dfa09`

Moved verbatim: `stopReaderTranslations` (runBlocking-bridge/IO-scope comment moved with it),
`requestReaderStop` (CoroutineStart.DEFAULT semantics — undispatched-prefix regression test),
`awaitReaderStop` (Dispatchers.IO fence + comment), `translatePage`, `cancelPageTranslation`,
`cancelPageTranslations`, `cancelAllPageTranslations` (bug-4 durable-write-before-markDefunct comment
moved verbatim, incl. the synchronous `runBlocking` bridge), `cancelAllPageTranslationsOffMain`
→ `internal class ReaderTeardownCoordinator`.
**Mutex ownership seam**: the plan says the coordinator owns `readerTeardownMutex`, but
`TranslationManagerReaderTeardownTest:262` reflection-writes that exact field on the manager (Phase-13
`durableStatusCache` situation). Resolution: the field stays on the manager; the coordinator resolves it
per call through a provider, so both stop paths still serialize on the same (possibly swapped) instance.
Same-name shims for `translatorStop(reason, closeEngines = false)` (named arg preserved), and
function-shaped ctor lambdas for `isBatchTranslationRetained` / `unregisterActiveTranslationStore` /
`disposeBatchTracker` / `clearAllPendingTranslationRequests`. Manager keeps public same-signature stubs.

## Phase 19 — Reset/delete controller (two commits) · `a4044f4` + `02655c7`

### Characterization test (committed BEFORE the move; plan §2 rule 7)

`TranslationManagerDeleteResetOrderingTest` (296 lines, `runBlocking<Unit>` form, style-matched to
`TranslationManagerReaderTeardownTest`: Unsafe-allocated manager + reflection-set fields + mockk
collaborators feeding a shared event log). Three tests pin, on the UNMOVED code:

1. **`deleteTranslation` strictly sequenced teardown/deletion**: preamble `findTranslationFile`
   (document capture) → `cancelAutoTranslations` → `cancelPageTranslations` → `removeFromQueue` →
   `cancelTranslatorJobAndJoin` → `disposeBatchTracker` → `markDefunct` → eviction's
   `clearDurableStatusCache` → `clearChapter` → deletion `findTranslationFile` → `findCompanionImageDir`
   (retirement) → final `clearDurableStatusCache`. Writing the test surfaced the exact sub-order inside
   `unregisterActiveTranslationStore` (`markDefunct` BEFORE its cache clear), which is now pinned.
2. **`resetChapterTranslationData`/`resetChapterData`**: cache clear first → cancel auto → cancel pages →
   queue removal → batch join → `clearChapter` → per-page `updatePageFromCurrentSnapshot` +
   `demoteCommittedDisplay` → explicit `storeFlush` → second `storeFlush` from `reconcileBatchProgress`
   (the double-flush shape is pinned).
3. **`resetOcrData` (+ `deletePageTranslation` alias)**: cache clear → `cancelPageTranslation` →
   `clearPage` → `deletePage` → `storeFlush` → one companion-dir scan → four
   `retirePageCompanionImage` events (persisted name + fixed publication names; the registry invokes
   delete callbacks, which is itself pinned by their absence from the synchronous stream).

The observable `clearDurableStatusCache` positions come from an event-logging `ConcurrentHashMap`
subclass installed in the reflection-set `durableStatusCache` field.

### The move

`internal class ChapterDataResetController` (486 lines) received all 11 declarations verbatim
(`deleteTranslation`, `deletePageTranslation`, `chapterResetPreflight`, `resetChapterTranslationData`,
`resetChapterInpaintData`, `resetChapterOcrData`, `resetChapterData`, `resetTranslationData`,
`resetInpaintData`, `resetOcrData`, `reconcileBatchProgress` — the two private helpers' only callers were
inside the region). **Pure move verified: all 379 region lines verbatim** (scripted diff vs `a4044f4`);
the copy-paste dedupe between active-store/open-store branches stayed OUT of scope per plan §4.
Manager keeps nine public same-signature stubs plus a per-access provider-lambda wiring for 15
collaborators (scheduler, teardown stubs, image-lifecycle stubs, durable resolver, registry, stream
registry, provider, `findTranslationDocument`, `openExistingChapterTranslationStore`).
Gates: the new characterization test pins the identical event sequences through the controller;
TranslationManagerArtifactReadTest + TranslationManagerDownloadFailureRecoveryTest green; full suite
**mandatory** gate green (1076/0/0).

## Deviations from plan

1. **Phase 15 consistent-snapshot accessor**: implemented as `ChapterTranslationStore.statusProjectionInputs()`
   handing the flows **by reference** (not a frozen value snapshot) so the moved body's `.value` reads stay
   at their original points — a value-freezing accessor would have been a behavior change, violating §2
   rule 1. Documented in the accessor's KDoc.
2. **Phase 17a**: the `pageLeases` map stays shared through `PageStageLeaseTable.pageLeases` with a
   same-name store getter, rather than splitting read paths — required to keep ten store-side readers and
   `synchronized(pageLeases)` char-identical. `PageLeaseRecord` widened `private`→`internal`;
   `snapshotLocked`/`cancelArtifactCandidateLocked` widened `private`→`internal`.
3. **Phase 17b**: `dirty`/`persistJob` remain store fields (non-moved bodies write them) with scheduler
   var-shims; `persistCount` loses `private set`; three legacy-ctor probes widen visibility. All
   function bodies verbatim.
4. **Phase 18**: `readerTeardownMutex` field stays on the manager (reflection seam, deviation noted in
   code header); the coordinator receives it per call — serialization semantics identical.
5. **Phase 19**: none beyond the plan's own two-commit shape; characterization test committed separately
   before the move as required.

## Reverted phases

None. No gate failed post-fix; the only red runs were (a) two compile-fix cycles within Phase 15/16/17b
and (b) the characterization test's first run, where the test's own expectations were corrected to pin
the ACTUAL current order (that is the test's job) before its green commit.

## Notes for the Director

- Manual smoke obligations from Wave 3 (reader single-page translate) are unchanged by this wave — no
  pipeline body moved here; all moved regions are store-internal subsystems and manager flows covered by
  the targeted suites above.
- God-file scoreboard after Wave 4: `ChapterTranslationStore` 2,025 (target ~2,010 — remaining mass is
  the fenced-mutation/merge core reserved for optional Phase 21), `TranslationManager` 1,240
  (target ~900 — remaining mass is facade/wiring, queue entry, and the LEGACY decode stubs).
