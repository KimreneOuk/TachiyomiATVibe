# T909 — God-File Dismantling Master Plan

Owner: Delivery Lead (planner) · Date: 2026-08-29 · Status: awaiting Director go/no-go per phase
Synthesizes: `pipeline-investigation.md`, `store-manager-investigation.md`, `second-tier-triage.md` (evidence lives there; not restated here).
All commands run from repo root. Test FQCNs verified against `app/src/test/java/eu/kanade/translation/`.

---

## 1. EXECUTIVE SUMMARY

**What this is:** 20 committed phases (3 waves) + 2 optional phases that dismantle 6 god-files
into named, test-pinned components using behavior-preserving moves only.

**Total realistic yield:** ~7,600 god-file lines moved out in the committed plan
(≈ 9,100 if both optional phases are later approved). This is mass moved out of god-files;
repo LOC stays roughly neutral (moves + thin delegation stubs).

| God-file | Now | After committed plan | Notes |
|---|---|---|---|
| `translation/TranslationPipeline.kt` | 5,300 | ~450–650 | facade + `translateBatch` shell until Phase 20, then thin delegator |
| `translation/ChapterTranslationStore.kt` | 2,552 | ~2,010 | state + fenced-mutation core remains |
| `translation/TranslationManager.kt` | 2,056 | ~900 | facade + wiring + queue entry + delete/reset delegation |
| `artifact/ChapterArtifactStore.kt` | 1,499 | ~1,070 | live candidate lifecycle; legacy machine isolated, retirable later |
| `recognition/RoiPageRecognitionEngine.kt` | 1,517 | ~890 | orchestration core remains |
| `translator/ProviderRequestGovernor.kt` | 828 | ~640 | governor + types remain |

**Phase count / shape:** 20 phases — 12 at LOW/LOW-MED risk, 6 MED, 2 elevated
(Phase 19 MED-HIGH; Phase 20 MED→HIGH but decomposed into 6 independently revertible commits).
Every phase: own branch, targeted tests → full suite, rollback = revert its commits.
The two elevated phases each carry a prerequisite investment (characterization test / manual smoke).

**Cost:** ~31–32 agent-days committed; +~8 for the optional phases.

**After-state (target layout, new packages in `eu.kanade.translation`):**

```
eu/kanade/translation/
├── TranslationPipeline.kt              # facade: TranslationExecutor overrides, callbacks, companion constants
├── TranslationManager.kt               # facade: DI singleton, init wiring, queue entry, delegation
├── ChapterTranslationStore.kt          # core: page state, fenced mutation, generation, defunct
├── ChapterTranslator.kt                # UNCHANGED (LEAVE verdict)
├── pipeline/
│   ├── PageDecode.kt / MemoryGovernance.kt      # Phase 1
│   ├── PageStoreWriter.kt                        # Phase 6
│   ├── CleanedPublication.kt                     # Phase 7
│   ├── EngineLane.kt                             # Phase 10
│   ├── SinglePageHttpRenderPhase.kt              # Phase 12
│   ├── SinglePageOnnxPhase.kt                    # Phase 14
│   └── batch/                                    # Phase 20 (6 commits)
│       ├── HeldBitmapRegistry.kt  BatchWriteGate.kt  BatchResumePlanner.kt
│       └── BatchRenderJoin.kt  BatchLaneWorkers.kt  BatchChapterTranslator.kt
├── artifact/
│   ├── ChapterArtifactStore.kt          # slimmed live authority
│   ├── LegacyArtifactRescue.kt          # Phase 2 — retirable legacy machine
│   ├── LegacyChapterMigrationSource.kt  # Phase 3b — store-side legacy input gathering
│   └── ChapterArtifactManifestReader.kt # Phase 3b
├── legacy/
│   └── LegacyFlatFileDecoder.kt         # Phase 3a — one Json, decode + quarantine
├── store/
│   ├── ChapterGlossaryStore.kt          # Phase 8
│   ├── StoreStatusProjector.kt          # Phase 15
│   ├── PageStageLeaseTable.kt           # Phase 17
│   └── StorePersistenceScheduler.kt     # Phase 17
├── manager/
│   ├── TranslationRequestCoordinator.kt          # Phase 9
│   ├── BatchProgressProjector.kt                 # Phase 11
│   ├── DurableChapterStatusResolver.kt           # Phase 13
│   ├── CleanedImageLifecycleController.kt        # Phase 16
│   ├── ReaderTeardownCoordinator.kt              # Phase 18
│   └── ChapterDataResetController.kt             # Phase 19
├── recognition/
│   ├── RoiPageRecognitionEngine.kt      # slimmed core
│   ├── VerticalLineOcr.kt               # Phase 5a
│   └── OcrBlockDeduplication.kt         # Phase 5b — shared geometry home
└── translator/
    └── ProviderFailureClassification.kt # Phase 4
(only if optional phases approved: store/StageMergeEngine.kt; inpainting/{AotSessionProvider,
 AotNeuralCandidateRunner, AotClassicalFill}.kt)
```

---

## 2. GLOBAL RULES

1. **Behavior-preserving moves only.** No logic edits, no signature drift, no string/provenance
   changes, no "simplifications" mixed in. Moved bodies must be char-identical modulo
   package/imports/visibility. Reviewers diff for this explicitly.
2. **One phase = one branch (off `optimize_translation_finishing_page`) = one reviewable commit
   series.** Merge only after gates are green. Rollback = `git revert` of that phase's commits.
   A failed gate means revert, not hotfix-forward.
3. **One commit touches one source file.** Where a phase spans two files (Phase 3) or ships
   sub-extractions (Phases 5, 17, 20), each commit is single-file and independently revertible;
   the mixing is justified in the phase entry.
4. **Gate order: targeted tests first, then full suite:** `./gradlew :app:testDebugUnitTest`.
   Targeted form: `./gradlew :app:testDebugUnitTest --tests "<FQCN>"`.
5. **No phase mixes two god-files** (exception: Phase 3, justified below).
6. **Test seams move, never copy.** Delegating stubs stay at old qualified names in the same
   commit when tests reference them (`ChapterTranslationStore.artifactImageProbe`, etc.).
7. **Characterization before risk:** Phase 19 is blocked until a `deleteTranslation` ordering
   characterization test exists; optional Phase 22 until an AOT corpus harness is green.
8. **Manual smoke** (reader single-page translate, batch translate, resume mid-batch) after
   Phases 12, 14, 20 — the pipeline body has no direct unit tests (pipeline report, blind spot B1).
9. **At most one phase in flight per source file**; disjoint-file phases may lane-parallel (Q3).

---

## 3. PHASED ROADMAP (ordered by risk × value × independence)

Ordered to start with cheap, high-yield, independent moves that also de-risk later phases
(decode/memory types feed the ONNX phase; the artifact-package legacy neighborhood receives
Phase 3's moves; EngineLane unblocks both single-page phases).

### Wave 1 — foundations (LOW risk, independent, ~7 days)

**Phase 1 — Pipeline decode/memory utilities.** 1.5 d · yield ~390 · risk LOW · deps: none
- `TranslationPipeline.kt` L4986–5072, 5117–5299 → `pipeline/PageDecode.kt` + `pipeline/MemoryGovernance.kt`
  (incl. `DecodedPage`, `LowMemory*` exceptions; engine reads injected as a getter).
- De-risks: moves `DecodedPage` early so the `nativeHandoff` cast sites (batch phase) stabilize; cheapest high-yield cut.
- Gate: full suite (no direct tests exist; compile is the risk — members become `internal` top-level fns).
- Rollback: revert 1 commit.

**Phase 2 — ChapterArtifactStore legacy rescue split.** 1.5 d · yield ~430 · risk LOW · deps: none
- `ChapterArtifactStore.kt` L1035–1463 → `artifact/LegacyArtifactRescue.kt` (internal class, same deps).
- Top pick of the second-tier triage: highest churn in tier (9/9), crisp seam, dedicated migration
  tests already pin it; isolates the legacy obligation where it can age and be retired.
- Gate: `artifact.ChapterArtifactStoreTest`, `artifact.LegacyArtifactMigrationTest`,
  `ChapterTranslationStoreArtifactMigrationTest`, then full suite.
- Optional commit 2b (same file/gates): retention sweep L917–1012 → `artifact/ArtifactRetention.kt` (~120). Skip if review bandwidth is tight.
- Rollback: revert 1–2 commits.

**Phase 3 — Legacy decode consolidation (two single-file commits; justified mix).** 1.25 d · yield ~285 · risk LOW · deps: none (lands best after Phase 2 creates the artifact legacy neighborhood)
- 3a: `TranslationManager.kt` L242, 764–787, 921–949 → `legacy/LegacyFlatFileDecoder.kt` (~50).
  Kills one of two duplicate `Json { ignoreUnknownKeys = true }` instances.
- 3b: `ChapterTranslationStore.kt` companion L2213–2244, 2263–2532 → `artifact/LegacyChapterMigrationSource.kt`
  + `artifact/ChapterArtifactManifestReader.kt` (~235); `open`/`openArtifact`/`lazy` stay as delegating stubs.
- Justification for mixing: same legacy family, same two gate files, each commit single-file.
- Gate: `TranslationManagerArtifactReadTest`, `ChapterTranslationStoreArtifactMigrationTest`,
  `artifact.ChapterArtifactStoreTest`, then full suite.
- Rollback: revert per commit.

**Phase 4 — Provider failure classification split.** 0.5 d · yield ~190 · risk LOW · deps: none
- `ProviderRequestGovernor.kt` L642–827 → `translator/ProviderFailureClassification.kt`
  (shared infrastructure used by ~10 files that never touch the governor; ~1 h of pure moves).
- Gate: `translator.ProviderRequestGovernorTest` (pins `RetryAfterParser.parseMillis`), full suite (10 consumer imports).
- Rollback: revert 1 commit.

**Phase 5 — RoiPageRecognitionEngine split (two commits).** 2 d · yield ~630 · risk LOW-MED · deps: none
- 5a: vertical/multi-line OCR L~1044–1386 → `recognition/VerticalLineOcr.kt` (~350; pure `(RoiOcrEngine, Bitmap, flags)` inputs).
- 5b: block dedup + parent-bubble geometry L~920–1043, 1388–1517 → `recognition/OcrBlockDeduplication.kt` (~280);
  becomes the shared geometry home ending the duplication with `OnnxPageTextDetector` (see §5).
- Why this early: churn 8 since 08-01 — shrink it before more OCR feature work lands in 1,517 lines.
- Gate: `recognition.BoxGeometryTest`, full suite (plus batch characterization tests indirectly).
- Rollback: revert per commit.

### Wave 2 — pipeline mechanics + manager subsystems (LOW-MED, ~8 days)

**Phase 6 — Pipeline store-patch writer.** 1 d · yield ~240 · risk LOW · deps: none
- `TranslationPipeline.kt` L482–660, 5074–5115 → `pipeline/PageStoreWriter.kt`
  (stateless over injected resolver + store guarded-write API; used identically by all paths).
- Gate: `ChapterTranslationStorePersistenceTest`, `ChapterTranslationStorePhase3Test`,
  `PostOcrStageSemanticsTest`, full suite. Rollback: revert 1 commit.

**Phase 7 — Pipeline cleaned publication.** 1 d · yield ~230 · risk LOW · deps: none
- `TranslationPipeline.kt` L839–870, 4230–4259, 4294–4428 → `pipeline/CleanedPublication.kt`
  (wraps already-extracted `CleanedImagePublisher`; `currentInpaintingMode` passed as param).
- Gate: `CleanedImagePublisherTest`, full suite. Rollback: revert 1 commit.

**Phase 8 — Store glossary collaborator.** 1 d · yield ~100 · risk LOW-MED · deps: none
- `ChapterTranslationStore.kt` L216–219, 1917, 1990–2085 → `store/ChapterGlossaryStore.kt`
  (delegates under the store mutex; keep the LEGACY read fallback decision explicit — keep it).
- Gate: `ChapterTranslationStoreArtifactMigrationTest` (glossary cases L358–377, 477–490), full suite.
  Rollback: revert 1 commit.

**Phase 9 — Manager request coordinator.** 1.5 d · yield ~210 · risk LOW-MED · deps: none
- `TranslationManager.kt` L81–87, 110–125, 256–426 → `manager/TranslationRequestCoordinator.kt`
  (owns store + state + write-versions + mutation lock; version-fence protocol moves intact).
- Gate: `TranslationManagerPendingAcknowledgementTest`, `TranslationManagerDownloadFailureRecoveryTest`,
  `TranslationManagerAutoArbitrationTest`, full suite. Rollback: revert 1 commit.

**Phase 10 — Pipeline EngineLane (+ dead-code delete).** 2 d · yield ~460 (incl. −32 dead) · risk MED · deps: none; must precede 12/14
- `TranslationPipeline.kt` L219–295, 297–472, 3446–3491 → `pipeline/EngineLane.kt`
  (8 engine-cache fields + `withNativeLane`, `closeEngines`, `ensureEnginesBuiltFor`, signatures, factories;
  pipeline keeps `val engines: EngineLane`). Deletes dead `getContextualTranslator` (§5).
- Must preserve defensive-init semantics: invalid config must not crash eager construction (blind spot B6).
- Gate: `TranslationPipelineConcurrencyTest`, `TranslationManagerAutoArbitrationTest`,
  `TranslationManagerReaderTeardownTest`, full suite. Rollback: revert 1 commit.

**Phase 11 — Manager progress projector.** 1.5 d · yield ~190 · risk MED · deps: none
- `TranslationManager.kt` L654–701, 1349–1525 → `manager/BatchProgressProjector.kt`
  (flow graph only, no locks). Gate: `TranslationManagerArtifactReadTest` L119–190,
  `TranslationManagerAutoArbitrationTest`, full suite. Rollback: revert 1 commit.

### Wave 3 — single-page phases + remaining cuts (~14 days)

**Phase 12 — Pipeline single-page HTTP+render phase.** 1.5 d · yield ~415 · risk MED · deps: 6, 7, 10
- `TranslationPipeline.kt` L3815–4228 → `pipeline/SinglePageHttpRenderPhase.kt`
  (outcome typing already `ChunkCompletionOutcome`; PARTIAL retry predicate moves with it).
- Gate: `AiTranslationRetryPlannerSinglePageTest`, `CancelSyncStoreWriteTest`,
  `PreparedPageRuntimeBoundaryTest`, full suite + manual smoke (§2 rule 8). Rollback: revert 1 commit.

**Phase 13 — Manager durable status resolver.** 1 d · yield ~120 · risk MED · deps: 11
- `TranslationManager.kt` L127–145, 712–762, 882–919 → `manager/DurableChapterStatusResolver.kt`.
  All 11 `durableStatusCache` clear sites must route through it (risk §6.8).
- Gate: `TranslationManagerArtifactReadTest`, `TranslationManagerDownloadFailureRecoveryTest`, full suite.
  Rollback: revert 1 commit.

**Phase 14 — Pipeline single-page ONNX phase.** 2 d · yield ~670 · risk MED · deps: 10, 12
- `TranslationPipeline.kt` L3493–3813, 4430–4984 → `pipeline/SinglePageOnnxPhase.kt`
  (`OnnxPhaseResult` moves with it; bitmap recycle/ownership points verbatim — blind spot B3).
- Gate: as Phase 12 + `RollingAutoCoordinatorTest`, full suite + manual smoke. Rollback: revert 1 commit.

**Phase 15 — Store status projector.** 0.75 d · yield ~60 · risk MED · deps: none
- `ChapterTranslationStore.kt` L320–328, 1919–1988 → `store/StoreStatusProjector.kt`
  (beside `batch/BatchProgressReconciler`; needs an internal consistent-snapshot accessor).
- Gate: `ChapterTranslationStoreArtifactMigrationTest` L294–357, `TranslationManagerArtifactReadTest` L119–190, full suite.
  Rollback: revert 1 commit.

**Phase 16 — Manager cleaned-image lifecycle.** 1.5 d · yield ~150 · risk MED · deps: none
- `TranslationManager.kt` L75–79, 1116–1254, 1938–1968 → `manager/CleanedImageLifecycleController.kt`
  (SAF/threading constraints documented at L1116–1126 move with it).
- Gate: `CleanedImagePublisherTest`, `TranslationManagerArtifactReadTest` L192 (freshness), full suite.
  Rollback: revert 1 commit.

**Phase 17 — Store lease table + persistence scheduler (two commits, one file).** 1.5 d · yield ~145 · risk MED · deps: none
- 17a: L159–171, 379–477 → `store/PageStageLeaseTable.kt` (~90; dual locking + `NonCancellable`
  wrappers preserved — behavior depends on them).
- 17b: L2133–2204 → `store/StorePersistenceScheduler.kt` (~55; `markDefunct` join semantics exact).
- Gate: `ChapterTranslationStorePhase3Test`, `ChapterTranslationStoreDefunctTest`,
  `ChapterTranslationStorePersistenceTest`, full suite. Rollback: revert per commit.

**Phase 18 — Manager reader teardown coordinator.** 1 d · yield ~100 · risk MED · deps: none
- `TranslationManager.kt` L428–464, 1970–2040 → `manager/ReaderTeardownCoordinator.kt`
  (owns `readerTeardownMutex`; runBlocking bridges move with their dispatcher comments).
- Gate: `TranslationManagerReaderTeardownTest`, `ChapterTranslationStoreDefunctTest`, full suite.
  Rollback: revert 1 commit.

**Phase 19 — Manager reset/delete controller.** 2.5 d · yield ~330 · risk MED-HIGH · deps: none (recommend after 17 for store-API stability)
- `TranslationManager.kt` L1527–1904 → `manager/ChapterDataResetController.kt` (pure move;
  the copy-paste dedupe is explicitly OUT of scope, see §4).
- Prerequisite: write a `deleteTranslation` ordering characterization test first (region unpinned —
  store/manager report B.6). Do not start without it.
- Gate: new characterization test, full suite mandatory. Rollback: revert 1 commit.

**Phase 20 — Pipeline batch internals (six commits, ordered).** 6 d · yield ~2,240 · risk MED→HIGH · deps: 1–14 landed
- 20.1 `pipeline/batch/HeldBitmapRegistry.kt` (L1690–1712 + constants ~90) — gate: full suite.
- 20.2 `BatchWriteGate.kt` (L1442–1605, 3437–3444, ~190) — gate: `ChapterTranslatorQueueRestoreTest`, full suite.
- 20.3 `BatchResumePlanner.kt` (L1607–1688, 1323–1441, ~230) — gate: `batch.BatchResumeGateDeciderTest`, full suite.
- 20.4 `BatchRenderJoin.kt` (`tryRender` L1732–1901 + renderJoin L3177–3214, ~230) — gate: `batch.DisplayReadyStageCountTest`, full suite.
- 20.5 `BatchLaneWorkers.kt` (nativeWorker, translatorWorker, `translateChunkAi`, `completeChunklessPage`, ~1,270) —
  gate: `batch.SequentialBatchCoordinatorTest`, `batch.BatchTranslateBlockMergeTest`,
  `batch.Phase0BatchTranslationCharacterizationTest`, full suite.
- 20.6 shell → `pipeline/batch/BatchChapterTranslator.kt`; `TranslationPipeline` delegates — gate: full suite + manual smoke
  (batch translate, resume mid-batch, pause/stop).
- Rollback: revert per commit; the closure web moves only as conversions closure→class, never restructured (hotspot #2).

### Optional — separate Director approval (default: not approved now)

**Phase 21 — Store stage-merge engine (S7).** 3 d · yield ~420 · risk HIGH · deps: 17
- `ChapterTranslationStore.kt` merge machinery L651–1098 → `store/StageMergeEngine.kt` over an internal
  `StoreWriteBackend`. Deepest mutex/identity entanglement in the codebase. Only after new
  merge-semantics characterization tests. See Q1.

**Phase 22 — AOT inpainting split (three commits).** 5 d incl. harness · yield ~1,110 · risk MED-HIGH · deps: none, but harness first
- `AOTInpainting.kt` → `inpainting/AotSessionProvider.kt` (~230), `AotNeuralCandidateRunner.kt` (~470),
  `AotClassicalFill.kt` (~410), in that order (least state-coupled first).
- Prerequisite: characterization pass over the aot corpus (no test imports `AOTInpainting`).
  Memory-sensitive engine — reader-stability constraint applies. See Q2.

---

## 4. WHAT WE ARE NOT DOING

LEAVE verdicts (second-tier triage):
- **`rendering/TextLayoutPlanner.kt` (1,378)** — pure stateless single-domain math; 4 JVM test files +
  instrumented pixel-identity pin it; the only seam (CJK ~280 L) shares `TextMeasurer`/tokenize with the core,
  so yield is low and coupling real; regression = visible mis-render on normal manga.
- **`scheduling/RollingAutoCoordinator.kt` (1,003)** — recently extracted, one machine, strict
  mutex/generation discipline easier to review whole; quiet (churn 3). Revisit only if `reconcilePass` keeps growing.
- **`ChapterTranslator.kt` (759)** — one coherent lifecycle under 800 lines; highest recent churn is
  feature work on a stable shape, not accretion. Queue-persistence extraction only if it passes ~1,000 lines.

Investigator candidates rejected as churn > value:
- **Store eviction-contract redesign** (`ChapterStoreEvictionCoordinator`, `close(forEviction)`) — a
  behavior-changing contract redesign, not a pure move; out of dismantling scope.
- **Reset-flow dedupe** (copy-paste branch pairs inside the M6 region) — logic change; revisit only
  after Phase 19 lands and is stable.
- **Registry-key duality "fix"** (`TranslationDocument.registryKey` vs `UniFile.registryKey()`) — latent
  hazard, but fixing it changes lookup behavior; preserve semantics verbatim in all moves.
- **Vestigial flat-file plumbing removal** (store ctor `translationFile`/`fileCreator`, `persistLocked`,
  `dirty`, `DEFAULT_FILE_NAME`) — ctor is positionally pinned by tests and `persistCount` is a test seam;
  tiny yield; revisit after the legacy machine retires.
- **`nativeHandoff: Any?` typing** — plausible later hygiene commit; not in this plan (Phase 1 keeps
  the cast sites compiling when `DecodedPage` moves).

---

## 5. INTERLEAVED QUICK WINS (ride along with the phase touching that region)

1. **Dead `getContextualTranslator`** (pipeline L4261–4292, ~32 lines, zero callers — verified repo-wide):
   delete inside the **Phase 10** commit (EngineLane) per the pipeline report's candidate 4. Delete, don't move.
2. **`ChapterTranslator.translateChapter` pass-through wrapper** (L508–511, ~4 lines, pure delegation):
   separate micro-commit on the **Phase 20** branch — justified because that branch's gate already
   includes `ChapterTranslatorQueueRestoreTest`. Same review scope, own commit.
3. **OnnxPageTextDetector ↔ RoiPageRecognitionEngine geometry duplication** (documented in
   `BoxGeometryTest`'s header): **Phase 5b** makes `OcrBlockDeduplication.kt` the shared home; switching
   `OnnxPageTextDetector` to it is a follow-up micro-commit on the Phase 5 branch — shrinks a second file
   for free, pinned by the same geometry test.
4. **Nothing else rides along.** Investigators verified no dead code in `ChapterArtifactStore`,
   `AOTInpainting`, `ProviderRequestGovernor` (the `parseRetryAfterMillis` shim is live), and the
   pipeline already had a verified-dead sweep (commit `73c126c`). Do not hunt for more dead code
   during moves — scope discipline.

---

## 6. RISKS & MITIGATIONS

1. **kotlinx.serialization / Json config drift.** No `@Serializable` declarations live in any moved
   region; decode relies on `model/PageTranslation` and `ChapterArtifactManifest` serializers staying put.
   Two duplicate `Json { ignoreUnknownKeys = true }` instances exist (Store companion, Manager).
   *Mitigation:* never move serializers; Phase 3 consolidates decode into one decoder with one Json
   config; unknown-field tolerance pinned by `ChapterTranslationStoreArtifactMigrationTest` (removed-field cases).
2. **`internal` visibility breaks / test-pinned seams.** `internal` is module-scoped so package moves
   compile, but tests reference qualified names (`ChapterTranslationStore.artifactImageProbe` in
   ArtifactMigrationTest L64/108 and ArtifactReadTest L35/39, `.probeArtifactManifest` L129–160,
   `acknowledgePendingTranslationState`, `quarantineCorruptDocument`, `terminalSnapshotCacheSize`).
   `artifactImageProbe` is process-global mutable test state — a copied seam forks it.
   *Mitigation:* delegating stubs kept at old qualified names in the same commit; move, never copy,
   the seam; grep test refs before finalizing each phase.
3. **Coroutine scope / lock entanglement.** `nativeRunScope`/quarantine lifecycle; `NonCancellable`
   flush and lease-release ordering; nested `withNativeLane` (do not "simplify" nesting);
   Manager `runBlocking` bridges with documented dispatcher constraints; `scheduler` field-init order
   (deferred lambda capture — eager conversion opens an NPE window); dual locking on `pageLeases`
   (mutex + `synchronized`). *Mitigation:* verbatim moves; keep declaration order and comments;
   review diff must show moved blocks char-identical modulo package/imports.
4. **Test pinning gaps.** No test constructs `TranslationPipeline`; nothing imports `AOTInpainting`;
   `deleteTranslation`/reset flows and the batch-internal half are pinned only end-to-end.
   *Mitigation:* characterization tests are hard prerequisites for Phases 19 and 22; manual smoke
   gates after Phases 12, 14, 20; full suite after every phase; targeted-store tests
   (`Phase3Test`, `DefunctTest`, `PersistenceTest`) cover the store API the pipeline writes through.
5. **Bitmap lifecycle across the permit boundary + cancellation protocol.** `OnnxPhaseResult.cleanedBitmap`
   handoff with recycle-in-`finally` and exact held-bitmap registry balance; `CancellationException`
   rethrow discipline at every catch. *Mitigation:* Phase 14 moves recycle/ownership points verbatim;
   Phase 20.4 keeps recycle sites with `tryRender`; smoke includes a low-memory decode pass.
6. **External API drift + positional ctor pinning.** `SINGLE_PAGE_TIMEOUT_MS` read by
   `ReaderViewModel.kt:2525`; `permitHolderPageKeySnapshot()` used by TranslationManager L1340/1464;
   four `@Volatile` callbacks wired by the Manager; `ChapterTranslationStore` ctor pinned positionally
   by `ActiveChapterStoreRegistryTest`/`CleanedImagePublisherTest`. *Mitigation:* signatures frozen;
   constants stay reachable on the `TranslationPipeline` companion (re-export if the declaration moves);
   no ctor reorder in any phase.
7. **Merge collisions with active feature work.** `RoiPageRecognitionEngine` (churn 8) and
   `ChapterTranslator` (churn 11) are being modified. *Mitigation:* shrink RoiEngine early (Phase 5);
   at most one phase in flight per file; rebase each branch onto the current head at start; short-lived branches.
8. **Stale durable-status resurrection.** The 11 `durableStatusCache` invalidation sites are protocol,
   not detail — missing one resurrects stale TRANSLATED states. *Mitigation:* Phase 13 routes all
   clears through the resolver; grep-audit of clear sites is an explicit review step;
   `ArtifactReadTest` + `DownloadFailureRecoveryTest` gate the cached-retry semantics.

---

## 7. OPEN QUESTIONS FOR THE DIRECTOR

**Q1 — Phase 21 (store stage-merge engine, −420, HIGH): approve now or defer?**
Recommend: **defer** (default no-go). Take it only after Phases 17–20 have landed cleanly and new
merge-semantics characterization tests exist. The store core is the last entangled region; forcing it
into this campaign buys 420 lines at the highest regression risk in the plan.

**Q2 — Phase 22 (AOT inpainting split): fund the characterization harness as a precursor, or drop from T909?**
Recommend: **fund the harness** as its own mini-task after Phase 20, and green-light the split only on
a green harness. This is the memory-sensitive engine (reader-stability constraint); splitting it
without a corpus-pinned harness is the one place this plan could regress normal reading.

**Q3 — Scheduling: strict serial, or two parallel lanes?**
Recommend: **two parallel lanes on disjoint files** — a pipeline lane (1 → 6 → 7 → 10 → 12 → 14 → 20)
and a store/manager/artifact lane (2 → 3 → 8 → 9 → 11 → 13 → 15 → 16 → 17 → 18 → 19), with waves 1–2
freely interleaved across lanes. Never two phases on the same file concurrently, and do not run
Phase 19 and Phase 20 simultaneously (both highest-risk; review bandwidth is the bottleneck).
Halves wall-clock from ~31 agent-days to ~16 calendar weeks at one phase-in-review at a time.

---

## Director decision checklist

Go/no-go per phase. Default recommended approval envelope: **all of Waves 1–2 (Phases 1–11) plus
Phases 12–14** — ~4,100 lines, LOW/MED risk, fully gated — with Phases 15–20 approved after Wave 1–2
land and Q1–Q3 are answered.
