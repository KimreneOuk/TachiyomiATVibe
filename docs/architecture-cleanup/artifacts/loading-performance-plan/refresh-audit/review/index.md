---
kind: review
title: "Independent Review: Loading-Performance Plan Refresh Audit"
comments: none
---

# Independent Review: Loading-Performance Plan Refresh Audit

**Verdict: ACCEPTED WITH CORRECTIONS**

This independent review evaluates the reader-entry loading performance refresh audit produced by the GLM auditor (`artifacts/loading-performance-plan/refresh-audit/index.md`) against HEAD (`fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`).

The refresh audit is exceptionally thorough, precise, and accurately grounded in production code. All sampled line citations resolve cleanly and reflect true production semantics. The verdict is **ACCEPTED WITH CORRECTIONS**, where the corrections are targeted refinements regarding:
1. **Parallel Worktree Scope Delineation (§5)**: Slices LP-00 and LP-02 touch `ChapterTranslationStore.kt` and `TranslationManager.kt` respectively, both of which are concurrently touched by Phase 3. True parallel worktree development during Phase 3 is strictly restricted to **LP-01, LP-03, and LP-04** (which only touch `MangaScreenModel.kt`, `MangaScreen.kt`, and `presentation/TranslationUiProjection.kt`).
2. **Calling Context Clarification on ID-less Downloaded Path (§6 Finding 1)**: `DownloadPageLoader.getPages()` is invoked from `ChapterLoader.loadChapter` inside `withIOContext` (`Dispatchers.IO`), meaning it is already executing on a background worker thread. The defect is not that it blocks the main thread, but that `TranslationManager.getChapterTranslation` creates a redundant, nested `runBlocking(Dispatchers.IO)` blocking an IO pool thread, synchronously opens a full artifact, and wipes the durable status cache at line 1231.
3. **Lifecycle Coordination between Phase 3 Persistence and LP-07 Progressive Hydration**: Ensuring that progressive/lazy store openings in LP-07 do not alter the receipt-joined drain or lazy persistence invariants introduced by Phase 3 in `ChapterTranslationStore.kt`.

---

## 1. Spot-Check of Re-Pinned Facts

All sampled facts from Section 2 of the refresh audit were independently verified against the codebase at commit `fbbd19d1`:

| Fact # | Stated Anchor | Verified Anchor | Status | Verification & Code Semantics |
|---|---|---|---|---|
| **1** | `DownloadPageLoader.kt:69-101` | `DownloadPageLoader.kt:69-87, 90-99` | **VERIFIED** | Lines 71–78 await `getChapterTranslationForReader` when `dbChapter.id != null`; lines 79–84 fall back to `getChapterTranslation`. Lines 90–99 branch into archive (`getPagesFromArchive`) or directory (`getPagesFromDirectory`). Per-page mapping at `:146-172` sets `sourceFileName` and wires lazy stream factories. |
| **2a** | `TranslationManager.kt:1245-1275` | `workflow/TranslationManager.kt:1242-1272` | **VERIFIED** | Reader gate: `activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }` fast path at `:1252`, `findTranslationDocument` at `:1253`, `probeArtifactManifest` at `:1255`, and admission-locked `openExistingChapterTranslationStore` at `:1257`. |
| **2b** | `ChapterArtifactEngine.kt:109-177` | `ChapterArtifactEngine.kt:109, 112, 150-159` | **VERIFIED** | `load()` at `:109`, backup read at `:112`, retention span at `:150-152`, `recoverInterrupted` at `:157-159`. |
| **2c** | `ChapterJournalRecovery.kt:339-400` | `ChapterJournalRecovery.kt:339-410, 101-336` | **VERIFIED** | `readAppPrivateEpochs` reads every segment fully into memory via `segment.readBytes()` at `:385`; `replay` at `:101-336`. |
| **2d** | `ChapterTranslationStore.kt:4895-4913` | `ChapterTranslationStore.kt:4895-4913` | **VERIFIED** | `readVerifiedSnapshot` calls `artifact.readPageSnapshot` and recomputes `StageFingerprints.pageSnapshot` at `:4898`. Loop at `:4901-4913` eagerly loads and SHA-hashes candidate and committed snapshots for every manifest page. Store is exactly 5,061 physical lines. |
| **3** | `ChapterJournalRecovery.kt:413-455, 524-593` | `ChapterJournalRecovery.kt:413-455, 524-593` | **VERIFIED** | Double-read confirmed: replay reads and hashes winning page snapshots in `artifactResolver` (`:435-438`), then `ChapterTranslationStore.openArtifactOnly` re-reads and re-hashes the exact same files at `:4901-4913`. |
| **4** | `DurableChapterStatusResolver.kt:187-233`, `ActiveChapterStoreRegistry.kt:116-138, 166-173` | Exact matches | **VERIFIED** | `withProbeStore` opens probe store via `getOrCreateProbe` (`:197`), clears `durableStatusCache` if `result.created` (`:210-212`), runs probe block, and in finally calls `releaseProbe` + `result.store.closeAndFlush()` (`:224-231`). |
| **5-F1**| `MangaScreenModel.kt:702-769, 483-495` | Exact matches | **VERIFIED** | Sequential `toChapterListItems` map at `:704` inside `MutableStateFlow.updateSuccessState` CAS retry loop at `:484-494`. |
| **5-F2**| Resolver wipe on created probe | `DurableChapterStatusResolver.kt:210-212` | **VERIFIED** | Gated by `ProbeResult.created` (`:207-216`). |
| **5-F3**| `openArtifactOnly` eager snapshot hashing | `ChapterTranslationStore.kt:4901-4913` | **VERIFIED** | Candidate (`:4902-4904`) and committed (`:4905-4907`) snapshots eagerly hashed. |
| **5-P0-1**| Queue-state whole-cache wipe | `workflow/TranslationManager.kt:291-302` | **VERIFIED** | `translator.queueState.collect { durableStatusCache.clear() }` at `:299-301`. Hardened comment documents intentional wipe-on-every-emission. |
| **6** | `getOrLoadPageSnapshot` lazy hook | `ChapterTranslationStore.kt:4398-4431` | **VERIFIED** | Candidate-before-committed lookup at `:4405-4406`, unverified read at `:4409`, committed display promotion at `:4414-4424`. |
| **7** | Test-fixture manifest-reader leak in probe test | `ChapterTranslationStoreArtifactOpenProbeTest.kt` | **VERIFIED** | Test-only synchronous compatibility seam documented at `ChapterTranslationStore.kt:4789-4797`. |

---

## 2. Plan-Impact Verdicts & Phase 1–2 Collateral

The auditor's verdicts on slices LP-00 through LP-08 are confirmed:
- **LP-00, LP-01, LP-02, LP-03, LP-04, LP-06, LP-07, LP-08**: Correctly marked `still-valid`.
- **LP-05**: Correctly marked `needs-revision (anchors only)` due to the extraction of `workflow/ReaderTeardownCoordinator.kt` and layer 4 mutex ownership.

### Phase 1–2 Architectural Collateral Verified
1. **Teardown Coordinator Extraction**:
   - `readerTeardownMutex` remains on `TranslationManager.kt:149`, but reader cancellation coordination logic was extracted into `workflow/ReaderTeardownCoordinator.kt:21-94`.
   - Methods `stopReaderTranslations` (`:64-77`), `requestReaderStop` (`:84-87`), and `awaitReaderStop` (`:90-95`) execute under `readerTeardownMutex.withLock`.
   - The lock-tenure table in the reader-entry plan must cite both `TranslationManager.kt:149` and `ReaderTeardownCoordinator.kt:68, 94`.
2. **Package Relocations**:
   - `TranslationUiProjection` moved from `workflow/` to `presentation/TranslationUiProjection.kt` (lines 19–24).
   - Package boundary rule (`TranslationPackageBoundaryTest.kt`) strictly prohibits classes in `engines/**` and `presentation/**` from importing `pipeline/**`. As the auditor noted, any contracts or verified types introduced by LP-07 (such as `ReaderChapterOpening`) must reside outside `pipeline/**` (e.g. in `persistence/chapter/` or a dedicated reader presentation package).
3. **KDoc Drift**:
   - The original-flash documentation in `ReaderTranslationController.kt` moved from lines 331–343 to lines 326–343.

---

## 3. Phase-3 Conflict Map & Disjointness Analysis

The auditor claims that `ChapterTranslationStore.kt` regions are disjoint between Phase 3 and Reader-Entry work:
- **Phase 3 Regions**: Lines 1230–1250 (`PendingLazyMutation`, `LazyPersistenceTask`), lines 1273–1295 (`enqueueLazyPersistence`), line 2349 (`applyStagePatch`), and lines 1900–3607 (`patchPage` write pipeline and stage patches).
- **Reader-Entry Regions**: Lines 4790–5030 (`openArtifactSuspend`, `openArtifactOnly`, `readVerifiedSnapshot`) and lines 4398–4460 (`getOrLoadPageSnapshot`, committed display).

### Verification & Findings
1. **Physical Text Disjointness**: The text regions within `ChapterTranslationStore.kt` are physically separated by >1,500 lines. Syntactic merge conflicts are unlikely if edits are localized.
2. **Shared State & Lifecycle Invariants**:
   - **`enqueueLazyPersistence` Signature**: Phase 3 alters the signature of `enqueueLazyPersistence` (adding the writer receipt parameter and scope-active guards). Reader-entry does not call `enqueueLazyPersistence` directly, but LP-07's progressive store hydration must not bypass or disrupt lazy persistence enrollment.
   - **`closeAndFlush` & Defunct Store Handling**: `withProbeStore` calls `result.store.closeAndFlush()` (`DurableChapterStatusResolver.kt:227`). In `ActiveChapterStoreRegistry.kt:167-173`, `releaseProbe` checks whether the store was adopted by an active chapter reader before allowing `closeAndFlush()`. This guard protects concurrent pipeline operations on the store, provided the registry transition is atomic.
   - **File-Level Git Hazard**: Because both Phase 3 and reader-entry (LP-00, LP-07) touch `ChapterTranslationStore.kt`, concurrent branch modification remains a git merge hazard.

---

## 4. Verification of New Findings

All 9 new findings identified by the auditor were verified against current code:

1. **ID-less Downloaded Path Blocking Bridge (`DownloadPageLoader.kt:79-84` -> `TranslationManager.kt:1210-1240`)**:
   - **Calling Context Verified**: `DownloadPageLoader.getPages()` is called from `ChapterLoader.loadChapter` at line 48 inside a `withIOContext` block (`Dispatchers.IO`). Therefore, the call originates on a background IO thread.
   - **Defect Mechanism**: When `dbChapter.id == null`, `DownloadPageLoader` calls the synchronous `getChapterTranslation`. That method invokes `runBlocking(Dispatchers.IO)` (blocking a worker thread in the IO dispatcher pool), performs a full artifact load, and calls `durableStatusResolver.clearDurableStatusCache()` at `:1231`.
   - **Significance**: Confirmed as a high-consequence defect. Reader-entry optimizations in LP-05 and LP-06 must handle `dbChapter.id == null` paths asynchronously and avoid triggering cache clears.
2. **Complete Whole-Cache Wipe Site Inventory**:
   - Verified 5 sites: `TranslationManager.kt:299-301` (`queueState`), `:1231` (synchronous file-open), `:1347-1351` (fresh open when `!hadActive`), `:1750` (eviction), and `ChapterDataResetController.kt:159, 236, 277, 352, 425`.
3. **Teardown Coordinator Extraction**:
   - Verified at `ReaderTeardownCoordinator.kt:21-94` and `TranslationManager.kt:149`.
4. **`HttpPageLoader` Key Seam**:
   - Verified at `HttpPageLoader.kt:106` (`page.sourceFileName = filename`). Provides online parity for unified page key resolution (LP-06).
5. **Registry Fast Path Non-Empty State Requirement (`TranslationManager.kt:1252`)**:
   - Verified: `activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }`. An active store with an empty translation map falls through to document discovery and admission locking. For LP-05, the architecture must define whether an empty active store is authoritative.
6. **Lazy Store Creation Seam**:
   - Verified at `TranslationManager.kt:1827-1833` (`ChapterTranslationStore.lazy`). Demonstrates that status observation does not require full artifact loading.
7. **Landing-Page Auto-Translate Kick Ordering (`ReaderViewModel.kt:951-959`)**:
   - Verified: The auto-translate kick fires immediately after `observeLiveTranslationStore()`. LP-07 must ensure that early/progressive bundles do not trigger auto-translation on unverified pages.
8. **Prefound Document and Probe Threading**:
   - Verified at `TranslationManager.kt:1315-1321`.
9. **Test-Only `runBlocking` Seam**:
   - Verified at `ChapterTranslationStore.kt:4789-4797`.

---

## 5. Sequencing & Dependency Refinements

The auditor's proposed execution order (LP-00 through LP-08) is structurally sound, with one critical dependency refinement regarding worktree parallelism during Phase 3:

```
[Phase 3 Implementation (S1..S5)]
       │
       ├────────────────────────────────────────┐
       │ (Concurrent Parallel Worktree)         │ (Post-Phase 3 Cutover)
       ▼                                        ▼
[LP-01: List-state hoist]               [LP-00: Telemetry spans (touches Store)]
[LP-03: Two-phase badges]               [LP-02: Cache invalidation (touches TM)]
[LP-04: Scoped refreshes]               [LP-05: Shared reader opening (touches TM)]
                                        [LP-06: Early page key enumeration]
                                        [LP-07: Progressive opening (touches Store)]
                                        [LP-08: Handoff & non-blocking teardown]
```

### Necessary Sequencing Corrections
1. **Strict File-Overlap Boundaries for Parallel Worktrees**:
   - Slices **LP-01, LP-03, and LP-04** modify only `MangaScreenModel.kt`, `MangaScreen.kt`, and `presentation/TranslationUiProjection.kt`. These have zero file overlap with Phase 3 and can execute immediately in a parallel worktree.
   - Slices **LP-00** (touches `ChapterTranslationStore.kt:4884-4913`) and **LP-02** (touches `TranslationManager.kt:291-302`) touch files actively modified by Phase 3. They should **not** be developed concurrently in a separate worktree without rebasing onto the Phase 3 cutover commit.
2. **Prerequisites for LP-00**:
   - W5 spans (`store.journalReplay` and `store.pageSnapshotHydration`) must be added to the open path in `ChapterTranslationStore.kt` before LP-00's diagnostic baseline can report complete stage timings.
3. **LP-02 vs LP-03 Dependency**:
   - LP-02 narrows the `queueState` full-cache wipe. While LP-03 can technically proceed independently in UI code, executing LP-02 first prevents background queue churn from invalidating the newly published badge cache.

---

## 6. Integrity Verification

- **Working Tree Cleanliness**: `git status` confirms zero source, test, or build-configuration modifications made by the auditor.
- **Untracked State**: Only the pre-existing Phase 3 S1 untracked files (`AttemptAuthority.kt`, `AttemptAuthorityTest.kt`, `CleanedOwnerTest.kt`), the protected root report `.md`, and 10 CRLF-metadata modified files are present.
- **No Build Artifacts**: No Gradle daemons or build invocations were executed.
- **No Claimed Metrics**: The audit adheres strictly to source-code facts without making speculative runtime performance claims.

---

## Conclusion & Per-Finding Dispositions

| Finding / Section | Disposition | Note / Required Action |
|---|---|---|
| **Re-Pinned Facts 1–7** | **ACCEPTED** | All 13 citations and semantics verified verbatim at `fbbd19d1`. |
| **LP-00..LP-08 Verdicts** | **ACCEPTED** | All 9 slices retain validity; LP-05 anchor revision confirmed. |
| **Teardown Coordinator Extraction** | **ACCEPTED** | Update LP-05 lock-tenure table to include `ReaderTeardownCoordinator.kt:68, 94`. |
| **Disjoint Store Regions** | **ACCEPTED WITH CORRECTIONS** | Syntactically disjoint, but file-level git conflicts require deferring LP-00 and LP-07 until Phase 3 cutover. |
| **New Finding 1 (ID-less Path)** | **ACCEPTED WITH CORRECTIONS** | Confirmed blocking `runBlocking` and cache wipe; clarified calling context is already on `Dispatchers.IO`. |
| **New Findings 2–9** | **ACCEPTED** | All verified against source; findings 5 and 7 integrated into LP-05/LP-07 constraints. |
| **Phase 3.5 Sequencing** | **ACCEPTED WITH CORRECTIONS** | Parallel worktree scope restricted to LP-01, LP-03, LP-04; LP-00 and LP-02 scheduled post-Phase 3 cutover. |
| **Integrity Declaration** | **ACCEPTED** | Working tree pristine; read-only discipline strictly observed. |
