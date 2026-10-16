---
kind: review
title: "Loading-performance plan refresh audit — re-pinned at fbbd19d1 for reader-entry execution"
comments: none
---

# Refresh audit: loading-performance-plan vs current HEAD

Read-only source audit. Every line citation below was re-read at the stated revision. FACT = source-verified at the cited lines. INFERENCE = labeled reasoning, not source fact. No builds, tests, measurements, device runs, commits or pushes were performed. No performance numbers are claimed.

## 1. STATE

| Item | Value |
| --- | --- |
| Repo / branch | `C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE-inpainting-modes-redesign`, branch `architecture-cleanup` |
| HEAD audited | `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec` ("test: close manifest readers and await terminal scheduler traces") |
| Working tree | NOT pristine. Phase 3 slice S1 in flight: untracked `app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt`, `AttemptAuthorityTest.kt`, `CleanedOwnerTest.kt`. ~10 modified entries (`TranslationPipeline.kt`, `TranslationScheduler.kt`, 8 test files) are **content-identical** — `git diff HEAD --stat` is empty (CRLF-restoration artifacts only). One untracked root report `.md` (owner's, protected — untouched). |
| Audit target | All citations are HEAD content; the S1 untracked files were read where relevant and are marked "S1 untracked". |
| Read-only declaration | No source/test/config edits, no Gradle runs, no device contact, no subagents, no commits/pushes. |

Plan documents refreshed: [overall plan](..), [reader-entry](../reader-entry), [chapter-list](../chapter-list), [validation](../validation-and-delivery), [independent-review](../independent-review).

## 2. RE-PINNED FACTS TABLE

Verdicts: VERIFIED (same lines/semantics) / MOVED (new lines, same semantics) / CHANGED (semantics altered by Phases 1–2) / OBSOLETE.

| # | Fact (as parked, old anchor) | Verdict at fbbd19d1 | Current anchor(s) |
| --- | --- | --- | --- |
| 1 | Reader entry awaits full translation before page list (`DownloadPageLoader.kt:69-101`) | VERIFIED (internal drift) | Await `getChapterTranslationForReader` at `DownloadPageLoader.kt:69-87`; buildPageList/archive/directory branch `:90-99`; per-page map `:146-172` (`sourceFileName` `:165`, lazy translated-stream factory `:148`/`:167-170`, `status = READY` `:171`) |
| 2a | Gate = full store open (`TranslationManager.kt:1245-1275`) | MOVED | `TranslationManager.getChapterTranslationForReader` `:1242-1272`: registry fast path `:1252` (returns only **non-empty** `state.value`), memoized `findTranslationDocument` `:1253` (memoization at `DurableChapterStatusResolver.kt:127-149`), `probeArtifactManifest` `:1255`, admission-locked open `:1257`→`:1283`→`getOrCreate`+`openArtifactSuspend` `:1328-1336` |
| 2b | Full open = manifest primary+backup, retention sweep, interrupted recovery (`ChapterArtifactEngine.kt:109-177`) | VERIFIED | `ChapterArtifactEngine.load()` `:109` (backup read `:112`); `store.retention` span `:150-152`; `store.recoverInterrupted` `:157-159` |
| 2c | Journal replay over ALL epochs (`ChapterJournalRecovery.kt:339-400`, `:101-335`) | MOVED (+10 / +1 lines) | `readAppPrivateEpochs` `:339-410` (every segment fully read into memory, `segment.readBytes()` `:385`); `replay` `:101-336` |
| 2d | EAGER per-page snapshot hydration for EVERY manifest page incl. trusted-journal pages, SHA-256 recompute per page (`ChapterTranslationStore.kt:4895-4913`) | VERIFIED (exact) | `readVerifiedSnapshot` `:4895-4899` (read + `StageFingerprints.pageSnapshot` hash `:4898`); loop `:4901-4913`; authoritative-journal merge `:4927-4950`. Store now 5,061 physical lines (~4,805 non-blank). `openArtifactSuspend` `:4800-4816`, `openArtifactOnly` `:4857`, `ARTIFACT_OPEN_LOCKS` `:4823`, `withArtifactOpenLock` `:4830-4850` |
| 3 | Double-read: replay reads winning page snapshots, then manifest loop re-reads them (`ChapterJournalRecovery.kt:552-593`, `:416-455`; store `:4901-4913`) | VERIFIED (resolver MOVED −3) | Winner application + `artifactStateMatches` `:524-593` (snapshot verify `:552-561`, resolver consult `:590-592`); `artifactResolver` `:413-455` (per-reference re-read + re-hash `:435-438`); manifest loop re-read unchanged at store `:4901-4913` |
| 4 | Cold HTTP/local probe open→closeAndFlush→reopen; probe release wipes durable status cache (`DurableChapterStatusResolver.kt:187-233`; `ActiveChapterStoreRegistry.kt:166-173`, `:210-212`) | VERIFIED (exact) | `withProbeStore` `:187-233`: `getOrCreateProbe` `:197`, created→whole-cache wipe `:210-212`, `releaseProbe`+`closeAndFlush` `:224-231`. Registry: `getOrCreateProbe` `:116-138` (reused probe → `owned = true` `:124-127`), `releaseProbe` `:166-173`, `ProbeResult.created` `:207-216` |
| 5-F1 | `toChapterListItems` O(chapters) I/O inside `MutableStateFlow.update` CAS-retry (`MangaScreenModel.kt:702-769`, observe `:483-495`) | VERIFIED (exact) | Combine `:474-495` with `updateSuccessState { ... toChapterListItems(manga) }` `:484-494` (`updateSuccessState` inline `:173-178`); `toChapterListItems` `:702-769` (sequential map `:704`, per-downloaded-chapter status `:723-733`, observer starts inside `:750-757`); initial-await overlap path `:525-528` |
| 5-F2 | Every list probe wipes whole durable status cache | VERIFIED | Created-probe wipe resolver `:210-212`, gated by `ProbeResult.created` `:207-216`; recovery rationale `:205-209` |
| 5-F3 | `openArtifactOnly` eagerly hashes candidate+committed snapshots for ALL manifest pages | VERIFIED | Store `:4901-4913` (candidate `:4902-4904`, committed `:4905-4907`) |
| 5-P0-1 | `TranslationManager.kt:291-302` clears entire durable status cache on every `translator.queueState` emission | VERIFIED (exact; comment hardened) | `:291-302`, collect at `:299-301`; comment now explicitly states "Wipe on every emission" and repeats the conflated remove→re-add hazard |
| 6 | `getOrLoadPageSnapshot` lazy-hydration hook exists (`ChapterTranslationStore.kt:4398-4431`) | VERIFIED (exact) | `:4398-4431`: candidate-before-committed `:4405-4406`, read WITHOUT expected-fingerprint verification `:4409`, committed-display promotion `:4414-4424` |
| 7 | Store-baseline-investigation: 4 ArtifactOpenProbe failures were a test-fixture manifest-reader leak (fixed, test-only); production handle behavior unproven | VERIFIED (not re-litigated) | `ChapterTranslationStoreArtifactOpenProbeTest.kt` exists; the sync `openArtifact` runBlocking seam is documented test-only (`ChapterTranslationStore.kt:4789-4797` "Synchronous compatibility seam retained for existing JVM tests"). No runtime claims made. |

### Phase 1–2 collateral that MOVED/CHANGED parked anchors (not fact drift — plan-text drift)

| Parked anchor | Verdict | Current state |
| --- | --- | --- |
| Lock-table layer 4: `readerTeardownMutex` `TranslationManager.kt:148-149` | CHANGED | Mutex now `:149` only, and teardown is extracted into `workflow/ReaderTeardownCoordinator.kt` (`:21` provider, `withLock` `:68`, `:94`; wired `TranslationManager.kt:726`). The [reader-entry](../reader-entry) lock tenure table must cite both files. |
| `TranslationUiProjection` (workflow package) `:19-24` | MOVED (package) | Now `presentation/TranslationUiProjection.kt` `:19-24` (null→`NOT_TRANSLATED` collapse unchanged). P2.2 reorganization. |
| `ReaderTranslationController.kt:331-343` original-flash doc | MOVED (−5) | KDoc documenting the unfixed flash path now `:326-343`. |
| `ReaderTranslationController.kt:287-295` warm-window `getOrLoadPageSnapshot` | VERIFIED (exact) | `:287-295` (call at `:290`). |
| `awaitReaderStop` `ReaderTranslationController.kt:655` | VERIFIED (exact) | `:655` inside `cancelTranslationForChapter` `:649-690`. |
| `ReaderViewModel.kt:918-948` status→publish→observe | VERIFIED (exact) | `vm.translationStatus` `:918-929`, `vm.stateUpdate` `:931-947`, `observeLiveTranslationStore()` `:948`. |
| `resolvePageKey` `ReaderViewModel.kt:1813` | VERIFIED (exact) | `:1813` (controller delegate `ReaderTranslationController.kt:206`). |
| `ChapterLoader` `requestedPage` override `:60-69` | MOVED (−4) | `loadChapter` `:35-70`; override now `:58-62`. |
| `ReaderActivity.kt:174-194` hierarchy-before-async-init | VERIFIED (exact) | `:174-194`. |
| `observeTranslationProgress` eager terminal open `MangaScreenModel.kt:578-586` | VERIFIED (exact) | `:578-586` now calls `openOrCreateActiveChapterTranslationStoreSuspend` (suspend variant; same eager-open semantics). |
| `reconstructDurableTerminalSnapshot` `TranslationManager.kt:1177-1209` | MOVED (−3) | `:1174`; projection read-through `TranslationProgressProjection.kt:431`. |
| `TranslationProgressProjection.kt:285-298` queue/active-store/persisted legs | VERIFIED | `getChapterTranslationStatus` `:278-298` (queue `:285-286`, active-store display gate `:287-295`, persisted leg + `?: NOT_TRANSLATED` `:296-297`). |
| Rekey transaction `TranslationManager.kt:1560-1570` | MOVED (−3) | Admission lock `:1557`, `hasStoreForFile` guard `:1561` (recheck `:1600`). |
| `openOrCreate...Impl` `:1795-1842` | MOVED (−3) | `:1792-1839` (fast path `:1803-1807`, lazy store creation `:1827-1833`). |
| `ChapterList.Item.translationState` default `MangaScreenModel.kt:1957`; UI readers `MangaScreen.kt:313-314` | VERIFIED (exact) | `:1957`; `:313-314`. |
| `P1-1` premise: only `artifactStatus()` exists, writer registry diagnostic-only (`ActiveChapterStoreRegistry.kt:237-279`) | VERIFIED | `artifactStatus()` `ChapterTranslationStore.kt:4637`; `registerWriter` doc "for diagnostics and inspection" `:232-239`. |

Phase 2 confirmations (brief, all FACT): NativeVisionTelemetry lives in `diagnostics/` (imports e.g. `ReaderTranslationController.kt:16`); `TranslationPackageBoundaryTest` exists at `app/src/test/java/eu/kanade/translation/architecture/TranslationPackageBoundaryTest.kt`; `cancelRunningChapterForReplace` is suspend (`TranslationManager.kt:1066`); `computeSourceFingerprint` is suspend (`batch/BatchChapterTranslator.kt:159-162` — no runBlocking under a map lookup).

## 3. PLAN-IMPACT TABLE

| Slice | Verdict | Anchor refresh / revision needed |
| --- | --- | --- |
| LP-00 diagnostics/baseline | still-valid | Existing spans confirmed (`store.readManifest`/`recoverBackup`/`retention`/`recoverInterrupted` `ChapterArtifactEngine.kt:110-159`; `download.getPages` nanos `DownloadPageLoader.kt:47-115`; `translation.getForReader` `TranslationManager.kt:1249`). The two missing spans (`store.journalReplay`, `store.pageSnapshotHydration`) are still absent from the open path — the P1-4 decision-gate deliverable stands unchanged. |
| LP-01 list-state fix | still-valid | All F1 anchors verified exactly (`MangaScreenModel.kt:474-495`, `:702-769`, `:525-528`). No Phase 1–2 file touched this region. |
| LP-02 cache versions / invalidation narrowing (incl. P0-1) | still-valid | P0-1 site `TranslationManager.kt:291-302` unchanged. Invalidation-matrix audit scope now pinned: `clearDurableStatusCache` call sites = resolver `:72-75` (the protocol), TM `:299-301` (queueState), `:1231` (sync `getChapterTranslation` file-open), `:1347-1351` (fresh open only, `hadActive`-guarded), `:1750` (eviction), and `ChapterDataResetController.kt:159/236/277/352/425`. |
| LP-03 publish-rows-before-badges | still-valid | Eager terminal open `MangaScreenModel.kt:578-586` confirmed; P1-5 anchors all verified (`:1957` default; writers `:635-644`, `:649-672`, `:675-681`, `:765`; readers `MangaScreen.kt:313-314`, `:831`; projection collapse `presentation/TranslationUiProjection.kt:19-24`). |
| LP-04 scoped refreshes | still-valid | Combine fan-out inputs `MangaScreenModel.kt:474-481` unchanged (global download/queue/translation/request flows). |
| LP-05 shared reader opening | needs-revision (anchors only) | R1 anchors: `ReaderViewModel.kt:918-948` ✓, resolver `:187-233` ✓, registry `:63-152` ✓, manager open paths `:1242-1272`/`:1792-1839` ✓. Revision: lock-table layer 4 must be re-pinned to `TranslationManager.kt:149` + `ReaderTeardownCoordinator.kt:21-94` (new file since plan). |
| LP-06 early page enumeration / landing key | still-valid | `ChapterLoader.kt:58-62` override; duplicate key derivation `ReaderViewModel.kt:1813` vs `DownloadPageLoader.kt:146-172`. Helpful new seam: `HttpPageLoader.kt:106` already sets `sourceFileName` for `resolvePageKey` parity (see §6). |
| LP-07 verified landing-page bundle | still-valid (with sequencing note) | Memoization requirement unchanged: replay `:413-455`/`:524-593` → loop `:4901-4913`; hook caveat `:4398-4431` verified verbatim. Sequencing note: the store's open region (`:4800-5030`) is stable vs Phase 3, but see §5 conflict map — land after Phase 3's attempt-authority cutover to avoid concurrent edits to `ChapterTranslationStore.kt`. |
| LP-08 validation | still-valid | Gates unchanged; flash-baseline anchor moves to `ReaderTranslationController.kt:326-343`. |

No slice is obsolete. Nothing in Phases 1–2 altered the open-path cost structure the plan targets (FACT: 2d/3 verified exact).

## 4. W1–W5 WIN MAP REFRESH

| Win | Verdict | Re-pinned basis |
| --- | --- | --- |
| W1 visible-first hydration | still-valid | Eager loop `:4901-4913` + journal gating `:4914-4955` unchanged; `getOrLoadPageSnapshot` safety caveats (`:4405-4406` candidate-first, `:4409` unverified read, `:4414-4424` promotion) verified verbatim. Phase 3 does not touch the open region. |
| W2 metadata-only status projection | still-valid | `StoreStatusProjector.artifactStatus()` `:91` still consumes hydrated state/display; `ChapterTranslationStore.lazy` (`TranslationManager.kt:1827-1833`) is an existing seam showing manifest-less stores already carry status shape. |
| W3 bounded-parallel snapshot reads | still-valid (still experiment) | Loop remains sequential inside `withContext(Dispatchers.IO)` `:4806`; no concurrency change landed. |
| W4 probe-reopen elimination | still-valid | `withProbeStore` release/close `:224-231` unchanged; `getOrCreateProbe` reuse semantics `:116-138` unchanged. |
| W5 telemetry spans | still-valid | `store.journalReplay` / `store.pageSnapshotHydration` spans still absent (open path `:4884-4913` has no ReaderEntryTrace begin/end); remains prerequisite for LP-00's decision gate. |

## 5. PHASE-3 CONFLICT MAP AND PHASE 3.5 ORDERING

### Shared files / regions

| File | Phase 3 (approved plan + S1 in flight) regions | Reader-entry (this plan) regions | Collision |
| --- | --- | --- | --- |
| `ChapterTranslationStore.kt` | Patch paths: `PatchPrecondition`/`PatchResult` types `~:1230-1250`, `applyStagePatch` `:2349`, fenced write regions under `withJournalCapturePermit` `:1900-3607`, `enqueueLazyPersistence` receipt `:1273-1295` (CleanedPublication handoff); S1 `AttemptAuthority` consumes `PatchPrecondition` | Open/verify region `:4790-5030` (`openArtifactSuspend`, `openArtifactOnly`, memoization), `getOrLoadPageSnapshot` `:4398-4431`, display/committed helpers `:4390-4460` | **Disjoint regions, one file.** Textual merge risk only; LP-07 adds new helpers rather than editing patch paths. Sequence store edits, don't parallelize them. |
| `TranslationManager.kt` | Pipeline wiring (`batchTrackerFactory` `:285`), scheduler/lane coordination | `:291-302` (P0-1), `:1242-1353` (reader open path), `:1746-1839` (registry open/evict), `:149`+teardown coordinator | Low — different regions; both sides touch the file. |
| `ReaderTranslationController.kt` | Not in Phase 3 scope (Phase 4 will touch it) | LP-05/07/08 heavy here | None vs Phase 3; real overlap arrives with Phase 4 — finish LP-07/08 before Phase 4 starts (matches "Phase 3.5" framing). |
| `SinglePageOnnxPhase.kt`, `SinglePageHttpRenderPhase.kt` (split at `:662` = inpaint-join/`persistOnnxCleanedImage` region, confirmed `:662-684`), `TranslationPipeline.kt`, `EngineLane.kt`, `CleanedPublication.kt`, `execution/PreparedPageBoundary.kt`, `AttemptAuthority.kt` (S1 untracked) | Entire files | None | **No overlap.** Reader-entry work does not enter pipeline/**. |
| Package boundary (P2.2) | `TranslationPackageBoundaryTest` forbids engines/** and presentation/** importing pipeline/** | LP-07's `ReaderChapterOpening` contract and verified-page types must live outside `pipeline/**` (e.g. persistence/chapter or a reader-facing package) | Design constraint, not a collision. |

### Recommended Phase 3.5 order (smallest first; each slice = files touched + regression gate)

| # | Slice | Files | Regression gate |
| --- | --- | --- | --- |
| 1 | LP-00 spans + counters (`store.journalReplay`, `store.pageSnapshotHydration`, list pass counters) | `ChapterTranslationStore.kt` `:4884-4913` (span begin/end only), `diagnostics/ReaderEntryTrace.kt`, `MangaScreenModel` counters | Trace assertion tests (pattern: SchedulerTraceTest); zero behavior change; existing suites green |
| 2 | LP-01 list-state hoist | `MangaScreenModel.kt` only | CAS-contention test (one mapping per structural change); `ChapterTranslationSnapshotRegistryTest` |
| 3 | LP-02 P0-1 narrowing + P1-1 observed revision + P1-2 probe refcounting + P2-3 alias LRU | `DurableChapterStatusResolver.kt`, `ActiveChapterStoreRegistry.kt`, `TranslationManager.kt:291-302`, `ChapterDataResetController.kt` | `DurableStatusWipeGateTest` + new mixed-activity + interleaving + refcount tests (per validation table) |
| 4 | LP-03 two-phase badges + P1-5 single owner + P2-8 eager-open removal | `MangaScreenModel.kt:578-586`, `MangaScreen.kt`, `presentation/TranslationUiProjection.kt` | New derived-badge test; no eager terminal opens from list path |
| 5 | LP-04 scoped refreshes | `MangaScreenModel.kt:474-481` | Unrelated-emission ⇒ zero new reads |
| 6 | LP-05 shared reader opening + P1-3 status derivation | `ReaderViewModel.kt:918-948`, `DurableChapterStatusResolver.kt:187-233`, `ActiveChapterStoreRegistry.kt`, `TranslationManager.kt:1242-1353`, `ReaderTeardownCoordinator.kt` | Single-open parity on probe-then-open path; reader status updates while opening lock held |
| 7 | LP-06 early enumeration + landing key + `ChapterPageKeyResolver` (P2-4) | `ChapterLoader.kt:58-62`, `DownloadPageLoader.kt:146-172`, `ArchivePageLoader`, `ReaderViewModel.kt:1813`, `HttpPageLoader.kt:106` | Key/landing parity fixtures (odd names, zero padding, archive order) |
| 8 | LP-07 progressive opening + P1-4 memoization + 5-layer lock tenure | `ChapterTranslationStore.kt` `:4790-5030`/`:4398-4460` (new helpers), `ReaderTranslationController.kt`, `ActiveChapterStoreRegistry.kt` | `ChapterTranslationStoreArtifactOpenProbeTest` + new lock-tenure + lifecycle + memoization tests |
| 9 | LP-08 handoff + non-blocking teardown (P2-5) | `ReaderTranslationController.kt:649-690`, `:326-343` region, `TextLayoutCoordinator` | Flash-baseline parity; chapter-switch no-deadlock test |

INFERENCE on ordering: slices 2–5 touch no Phase 3 file and could run in a parallel worktree during Phase 3; slices 1, 6, 8 edit `ChapterTranslationStore.kt`/`TranslationManager.kt` and should follow Phase 3's attempt-authority cutover so the store file is never edited by two phases at once. That is the whole point of parking them as "Phase 3.5".

## 6. NEW FINDINGS (facts only; no runtime/perf claims)

1. **Blocking bridge on the id-less downloaded path (FACT):** `DownloadPageLoader.kt:79-84` falls back to the synchronous `TranslationManager.getChapterTranslation` (`:1210-1240`) when `dbChapter.id == null`; that method wraps a **full artifact open in `runBlocking(Dispatchers.IO)`** (`:1217`) and additionally wipes the durable status cache on file-store open (`:1231`). Reader-entry LP-05/LP-06 should cover this leg too (it sits on the same `getPages` path as fact 1).
2. **Complete whole-cache wipe site inventory (FACT):** `TranslationManager.kt:299-301` (queueState, P0-1), `:1231` (sync file-open), `:1347-1351` (fresh active open, `hadActive`-guarded — recovery invalidation), `:1750` (eviction), `ChapterDataResetController.kt:159/:236/:277/:352/:425` (reset/delete paths). The parked invalidation-matrix audit ("audit all sites") can start from this list.
3. **Teardown coordinator extraction (FACT, plan-affecting):** layer 4 of the lock tenure table is now split across `TranslationManager.kt:149` and `workflow/ReaderTeardownCoordinator.kt:21-94` — the P1-3/P2-5 lock-table text and tests must target both.
4. **HttpPageLoader key seam (FACT, helpful to P2-4):** `HttpPageLoader.kt:106` sets `sourceFileName` precisely so `resolvePageKey()` works online — an existing second parity point for the unified `ChapterPageKeyResolver`; parity fixtures should include the online loader, not only download/archive.
5. **Empty-state fast-path fallthrough (FACT, LP-05 input):** `TranslationManager.kt:1252` returns the registry store only when `state.value` is **non-empty**; a registered-but-empty store re-enters document discovery/probe/open and, on fresh open, triggers the `:1347-1351` wipe. A shared-opening design must define whether an empty registered store is authoritative for the reader.
6. **Lazy-store creation seam (FACT, W2 input):** `TranslationManager.kt:1827-1833` already constructs `ChapterTranslationStore.lazy` without a manifest for fresh chapters — evidence that status/open can be decoupled from artifact materialization, relevant to W2 and LP-05's "truly lightweight status hint".
7. **Auto-translate kick ordering (FACT, LP-07/08 input):** `ReaderViewModel.kt:951-959` documents a post-`observeLiveTranslationStore` landing-page auto-translate kick covering all `loadChapter` paths — the early-bundle handoff must be defined relative to this kick (an unverified early bundle must not become a translate trigger).
8. **Prefound document/probe threading (FACT, unchanged but worth recording):** `getChapterTranslationForReader` already passes `prefoundDocument`/`preflightManifestProbe` into the open (`TranslationManager.kt:1315-1321`) so the downloaded path does not repeat SAF walks — the double-open in fact 4 remains conditional to cold HTTP/local entry, exactly as parked.
9. **Test-only runBlocking seam confirmed (FACT):** `ChapterTranslationStore.openArtifact` `:4789-4797` is explicitly "retained for existing JVM tests" — consistent with the store-baseline-investigation qualification; no production caller was found by search.

## 7. EXPLICIT NON-GOALS

- No production/test/config edits, no builds or test runs, no measurements or performance numbers, no device contact, no commits/pushes (read-only audit upheld; working tree untouched).
- Chapter-list findings (F1–F3, P0-1) re-pinned only; their design is not re-opened here.
- The store-baseline-investigation verdict is accepted as written and not re-litigated; no runtime handle-safety claims are made in either direction.
- Phase 3 design judgments (attempt authority, overlap, memory-first) are out of scope; this audit only maps file/region overlap.
- No re-planning of Phases 4–8; the Phase 3.5 ordering above is a sequencing recommendation for the coordinator, not an authorized schedule.

## 8. Independent review outcome

Gemini 3.8 Flash/high (Antigravity) reviewed this audit: **ACCEPTED WITH CORRECTIONS** — [review artifact](review). All 13 sampled citations resolved and matched production semantics; integrity confirmed (no edits, no Gradle runs, no invented metrics). Two corrections adopted into the execution picture:

1. **Calling context for new finding 1:** `DownloadPageLoader.getPages()` runs under `ChapterLoader.loadChapter` withIOContext (Dispatchers.IO), so the nested `runBlocking(Dispatchers.IO)` in `getChapterTranslation` blocks an **IO carrier thread** (not main). The defect stands: synchronous full artifact open + durable-cache wipe on the id-less downloaded leg.
2. **Parallel-worktree boundary narrowed:** during Phase 3, only **LP-01, LP-03, LP-04** (files: `MangaScreenModel.kt`, `MangaScreen.kt`, `presentation/TranslationUiProjection.kt`) are safe in a parallel worktree. **LP-00 and LP-02** touch `ChapterTranslationStore.kt` / `TranslationManager.kt`, which Phase 3 is concurrently modifying, and must be scheduled after Phase 3's attempt-authority cutover. Store regions are syntactically disjoint (>1,500 lines apart) but file-level git conflicts still require worktree scoping.
