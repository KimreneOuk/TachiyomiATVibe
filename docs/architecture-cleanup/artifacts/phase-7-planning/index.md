---
kind: spec
title: "Phase 7 — Cohesive Persistence Internals: comprehensive architecture audit & slice plan"
comments: none
---

# Phase 7 persistence audit (read-only, 17 Oct 2026)

Scope: `app/src/main/java/eu/kanade/translation/persistence/` — `chapter/ChapterTranslationStore.kt` (5,213 lines), `journal/` (6 files), `artifact/` (engine + contracts, 21 files), `internal/` (5 files), `queue/` (2 files, out of core scope). No source was modified; no builds executed.

## 1. Executive summary

The persistence layer already has the right *boundaries* — journal, artifact engine, lease table, drain coordinator, status projector, attempt ledger are all real extracted collaborators — but the facade `ChapterTranslationStore` remains a 5,213-line orchestrator that personally owns ten co-located responsibilities, and the **first durable write still blocks a thread under the suspending store mutex** (`ensureArtifactStoreLocked` → `runBlocking(Dispatchers.IO)` at ChapterTranslationStore.kt:4182). The durable journal (epoch/segment framing, commit sequences, capture barrier, recovery reducer, snapshots/GC) is a second, *shadow* authority that today runs alongside — not behind — the artifact manifest, with the store manually interleaving the two on every mutation. Phase 7 should therefore be **mechanic extraction + admission de-blocking + dead-code purge**, not a redesign: the single live state/publication authority stays the store facade; capture, artifact publication, bulk mutation, status ledger, and open/recovery mechanics move behind it as store-delegating collaborators (the established `PageStageLeaseTable(this)` pattern).

## 2. Inventory

| File | KB | Role |
| --- | --- | --- |
| `chapter/ChapterTranslationStore.kt` | 253.7 | Facade + ten co-located responsibilities (see §3) |
| `artifact/ChapterArtifactEngine.kt` | 117.8 | Durable manifest transactions: load/recover, candidate open/persist/promote/cancel/demote/delete, OCR checkpoint, run records, sidecars, CAS + stale-manifest retry, coalescing, retention |
| `journal/ChapterJournalWriter.kt` | 69.1 | Per-epoch binary WAL writer: credit semaphores, command loop, commit sequences, terminal lag/defunct/payload markers, `ChapterJournalRecord` schema |
| `journal/ChapterJournalRecovery.kt` | 32.4 | Deterministic replay reducer (epoch ordering, winner fencing, bulk rekey deferral, artifact identity re-verification) |
| `journal/ChapterJournalSnapshot.kt` | 22.1 | CRC-framed snapshot format, durable marker protocol, snapshot manager + GC (dormant), file storage |
| `journal/ChapterJournalFormat.kt` | 11.8 | Binary framing v2: magic/version/epoch header, frame seq vs commit seq, CRC, TERMINAL_LAG/DEFUNCT ordering rules |
| `journal/ChapterJournalCompaction.kt` | 8.9 | Vector-frontier ordering, snapshot selection, GC authorization rules |
| `journal/ChapterJournalCaptureGate.kt` | 5.9 | Chapter-scoped producer barrier + capture coordinator registry (WeakHashMap) |
| `internal/PageStageLeaseTable.kt` | 12.1 | Lease records, MANUAL→AUTO eviction, same-origin attach/detach, NonCancellable release |
| `internal/StoreWriteDrainCoordinator.kt` | 7.2 | Debounced lazy-write drain (250 ms), 2 s defunct join, drain scope lifecycle |
| `internal/ChapterAttemptLedger.kt` | 6.9 | Paid-attempt ledger (fail-open, AUTO cap) |
| `internal/ChapterStoreEngineMode.kt` | 1.1 | Memory / LazyDurable / Durable mode seam |
| `chapter/ActiveChapterStoreRegistry.kt` | 11.1 | Chapter-keyed store registry + per-key opening locks + process-wide writer registry |
| `chapter/StoreStatusProjector.kt` | 15.3 | Chapter `Translation.State` projection from journal snapshot ⊕ manifest ⊕ live flows |
| `chapter/CleanedImagePublisher.kt` | 8.4 | File-then-commit publication, retain-on-uncertainty, never delete under committed display |
| `chapter/ChapterPageReconciler.kt` | 5.0 | Stranded/unexpected page reconciliation |
| `chapter/TranslationStageContracts.kt` | 9.3 | Lease/patch/fingerprint vocabulary (`PageStageLease`, `StagePatch*`, `PageWriteOrigin`) |
| `artifact/CommitPoint.kt` | 1.7 | Named mandatory durable publication points |
| remaining artifact files | — | Manifest/layout/IO/fingerprints/profile/run record/checkpoint/retention/deletion/display projection |

## 3. Decomposition of `ChapterTranslationStore.kt`

Ten co-located responsibilities (line anchors approximate):

1. **Live page-state cache + committed-display authority** — `pages` (`PersistentMap`, :366), `_state`/`display` flows (:446/:454), `committedDisplay` + `CommittedPageDisplay` (:381, :462), `promoteDisplayIfReadyLocked` (:4208), `displaySnapshotLocked` (:4238), `displayFingerprintOf` (:4256), `restorePageLocked` (:3820), init seeding (:1542–1597).
2. **Fenced single-page write paths** — `patchPage` (:1966), `updatePageGuarded` (:2103), `updatePage` (:3072), `applyStagePatch` (:2466), `mergeOcr/Translation/Render` (+ `*Locked` merge bodies, :2508–:2938), `checkpointOcr` (:2580), `reconcileBatchTranslationPageVersionDrift` (:2180), `pageWriteRejection` (:3008), `stageIdentityRejection` (:2940), `ocrIdentityRejection` (:3053), `translationProvenanceRejection` (:2870), `PatchPrecondition`/`PatchResult`/`PageSnapshot` (:471–:522), `AttemptArtifactLease`/`CertifiedArtifactProgress` (:113–:153).
3. **Page-stage leasing** — delegation shell over `PageStageLeaseTable` (:1895–:1958).
4. **Artifact publication bridge** — `persistArtifactMutationLocked` (:3832–:4160: registration CAS, candidate open/cancel, persist vs fused immediate promotion, failure publication, journal capture handoff), `admitMutationLocked` (:1851), `ensureArtifactStoreLocked` (:4167), `classifyMutation` (:190), `persistDurableStageFailure` (:2321), cap-failure removal (:1829).
5. **Lazy memory-first write queue** — `lazyPersistenceEnabled` (:1268), `PendingLazyMutation`/`LazyPersistenceTask` (:1286–:1299), `enqueueLazyPersistence` (:1332), `reservePendingLazyJournalCredits` (:1383), `flushLazyMutationsLocked` (:1422), `flushPendingLazyMutationsWithJournalCapture` (:1472) + `StoreWriteDrainCoordinator`.
6. **Journal capture + epoch lifecycle** — `reserveJournalCredit` (:876), `captureLegacyPersistedLocked`/`Deletion`/`RekeyState` (:934/:991/:1029), `finishBulkJournalCaptureLocked` (:1053), `BulkJournalCapture`/`JournalFlushBatch` (:591/:605), `journalInventorySnapshot` (:1130), `ensureJournalWriter` (:780), `journalChapterIdentity`/`journalCaptureCoordinator` (:568/:576), `withJournalCapturePermit` (:669), `captureJournalSnapshotForCompaction` (:678), `replayJournalForRecovery` (:751), `seedJournalFromManifest` (:1164), `retainStagedJournalCredit` (:258), `ChapterJournalSeedDiagnostics` (:268).
7. **Bulk mutations & re-key transactions** — `replaceAll` (:3146), `rekeyPages` (:3213), `preRegisterPages` (:3529), `clearTransientQueuePages` (:3657), `deletePage` (:3103), `PageRekeyPlan`/`Outcome`/`PlanResult` (:609–:649), `createPageRekeyPlanLocked` (:4268), `rekeyedArtifactManifest` (:4421), placeholder/collision rules (:4366–:4405), `pageRekeyMutex` (:652).
8. **Status projection state** — `journalStatusSnapshot` (:397) + `recordJournalStatus{PageMutation,PageDeletion,Rekey,Registration,Replacement}Locked` + `clearJournalStatusFailureLocked` (:1623–:1722), `unverifiedManifestFallbackPageKeys` (:410), `journalInventoryTrustEstablished` (:418), delegation to `StoreStatusProjector` + `statusProjectionInputs` (:1603–:1620).
9. **Attempt ledger facade** — `recordAttemptStart`/`autoAttemptAllowed`/`resolveAttempt`/`consumeUnresolvedAttemptsAtStartup`/`applyAttemptCapPause`/`clearAttemptCapForManualRetry` (:1745–:1849).
10. **Lifecycle, defunct, close, retention, open factory** — `markDefunct` (:1526), `beginGeneration`/`invalidateGeneration`/`withGeneration` + `GenerationContext` (:1884–:1963), `flush`/`closeAndFlush`/`close` (:4797–:4819), retention reconciliation (:4822–:4847), companion `openArtifact`/`openArtifactSuspend`/`openArtifactOnly`/`withArtifactOpenLock`/`lazy`/`probeArtifactManifest`/`readReusableProfile` (:4855–:5141).

## 4. Lock order & concurrency map

**Documented store-side order** (all evidence in-source):

1. Journal credit reservation (`reserveJournalCredit` → `ensureJournalWriter`) happens **before** `mutex.withLock` in every write path (patchPage :1981, updatePageGuarded :2110, drift reconcile :2186, stage failure :2329, stage patch :2471, checkpointOcr :2596, deletePage :3105, replaceAll :3153, rekeyPages :3220). Writer creation allocates the epoch under `journalWriterCreationLock` (plain monitor) on the persistence dispatcher, **not** under the store mutex.
2. Capture permit (`withJournalCapturePermit` = producer gate entry) → store mutex (lazy flush :1476–:1478; capture snapshot :690).
3. Store mutex → artifact engine calls. The engine documents its own rule: "facade Mutex → per-name document lock; document/open locks never acquire the facade Mutex" (ChapterArtifactEngine.kt:2464–2467).
4. Store mutex → drain-coordinator queue handoff is synchronous, no second state lock (`StoreWriteDrainLockOrderTest` pins this).
5. Defunct ordering: bounded 2 s drain join **outside** the mutex → generation bump + credit release + DEFUNCT marker under the mutex (:112–:131 drain coordinator; :1526 store).

**The violation — first-write blocking initialization.** `admitMutationLocked()` (called inside `mutex.withLock` from 12 sites) → `ensureArtifactStoreLocked()` (:4167) → `runBlocking(Dispatchers.IO) { withArtifactOpenLock(...) { store.load() } }` (:4182). Consequences:
- A carrier thread blocks while the suspending store mutex is held (mutex holder cannot be preempted by lock; the thread does the waiting).
- **Deadlock window (real inversion):** the open path needs `ARTIFACT_OPEN_LOCKS` while holding the store mutex. The suspend open path (`openArtifactSuspend`) holds the same per-artifact open lock while constructing the store — it never takes the store mutex, so today the reversal needs a second store instance for the same file plus a caller that already holds the open lock and then writes through the first store; the ordering is nonetheless no longer single-direction, which is exactly what the engine comment promises never to do.
- Blocking first write also defeats the memory-first lazy design: the *first* artifact mutation pays manifest `load()` (full document read + possible backup recovery + ≤8-page retention sweep) inline under the store mutex.

**Caller-side order** (`control -> lifecycle -> store`): `TranslationManager` control locks serialize reader/batch admission and teardown; lifecycle collaborator methods evict stores (`markDefunct`) after control handoff; pipeline components then talk to the store. Two cross-boundary reach-ins exist: `PreparedPageBoundary.kt:94` takes `store.mutex.withLock` directly (pipeline→store-internal coupling), and `CleanedImagePublisher` uses the mutex-held `authorizeCleanedImageDeletion` seam and deletes strictly outside the lock (documented, intentional). `PageStageLeaseTable` layers `synchronized(pageLeases)` lock-free reads over the store mutex for mutation, with NonCancellable release/attach paths; MANUAL evicts AUTO; same-origin attach counting protects the overlap-inpaint token.

**Journal writer internals:** semaphores (regular + foreground credits, foreground reserved for MANUAL), command channel with 5 control slots, single writer coroutine, store-assigned commit sequences (`nextCommitSeq()` before mutex), free-sync timer (~durability interval), capture-barrier flush command, terminal lag/defunct/payload transitions that close credit admission. `ChapterJournalCaptureGate` parks producers under a monitor + CompletableDeferreds; the coordinator registry is chapter-identity-scoped and weakly held.

**Shadow-era duality (deliberate, must survive Phase 7):** journal is *not yet* authoritative — `openArtifactOnly` treats a trusted journal as authoritative for pages, manifest committed snapshots fill silent keys, metadata-only fallbacks stay blocked, and `seedJournalFromManifest` migrates a manifest into journal coverage with an untrusted→trusted inventory commit point. `ChapterJournalGcMode.AUTHORITATIVE` (snapshot-driven epoch deletion) is dormant by design ("No production caller enables this in E18") — Phase 7 must **not** delete or activate it; it is a future E16c/E18 capability, not dead code.

## 5. Vocabulary separation (must remain distinct)

| Axis | Live vocabulary | Durable vocabulary | Mapping seams |
| --- | --- | --- | --- |
| Stage status | `StageStatus` (PENDING/RUNNING/READY/FAILED/PARTIAL/SKIPPED/CANCELLED strings on `PageTranslation`) | `ArtifactStageStatus` (ABSENT/RUNNING/READY/TEXTLESS/SKIPPED/FAILED_RETRYABLE/FAILED_TERMINAL/STALE/CORRUPT/PARTIAL) | `toArtifactPageFallback` (durable→live), `StoreStatusProjector` |
| Provenance | `PageWriteOrigin` (MANUAL/AUTO/BATCH) lease ownership only | `ArtifactOrigin` (BATCH/READER_ADHOC/LEGACY/UNKNOWN) | `PageWriteOrigin.toArtifactOrigin()`; the `pageTranslationOrigin` string stamp parsed by `PageWorkPlanner.stageEvidence` must never receive raw lease names |
| Ownership/fencing | store generation, pageVersion, lease token, candidate generationId/dependencyFingerprint preconditions (`PatchPrecondition`) | journal commit/frame sequences, epoch coverage vectors, `SidecarPointer.contentFingerprint`, manifest pageVersion | lease acquisition snapshots both vocabularies onto `PageStageLease` |
| Display | live `display` flow = committed bundle ⊕ live candidate; `PageDisplayState` | `CommittedBundleMetadata` + `displayBase.validated`, `toArtifactDisplayProjection` | `promoteDisplayIfReadyLocked` ↔ `promoteLiveCandidateTransaction` |
| Failure | in-memory stage errors | `DurableFailureMetadata` + `FailureCategory` + attempt ledger counters | `persistDurableStageFailure`, `applyAttemptCapPause` |
| Chapter status | `Translation.State` projection | journal inventory trust + `ChapterRunState` gate | `StoreStatusProjector` (journalStatus preferred when present, manifest fallback) |

Guardrails to preserve: no new canonical page state machine; no independently mutable replica of either vocabulary (the `journalStatusSnapshot` is a live-advanced *projection*, advanced only under the store mutex by accepted mutations — this is the correct pattern and every extraction must keep it).

## 6. Dead / redundant code (verified)

1. **`ChapterTranslationStore.journalShadowLagCount`** (:872) — zero readers in `app/src` (main or test). Delete.
2. **`PageArtifactRecord.legacyVisible`** (ChapterArtifactManifest.kt:136) — serialized field with neutral default; zero reads/writes anywhere. Remove; safe because decoders tolerate unknown keys (`ignoreUnknownKeys`) in both directions; document in the slice, no schema bump.
3. **`ChapterArtifactEngine` `displayBaseProbe` constructor param** — `@Suppress("UNUSED_PARAMETER")`, genuinely unused (:76–77). Remove (constructor signature churn is internal).
4. **Sync companion `openArtifact`** (:4872, `runBlocking`) — production callers all use `openArtifactSuspend` (TranslationManager, ChapterTranslator, DurableChapterStatusResolver, SinglePageOnnxPhase); the sync variant is referenced only from ~35 JVM test sites. Move behind the test fixtures or mark clearly as test seam; do not carry a second public blocking open into the cleaned facade.
5. **Production-embedded test seams** — `journalWriterForTests`, `journalSnapshotManagerForTests`, `afterRekeyManifestPublicationBeforeLiveSwapForTests`, `attachJournalWriterForTests`, `attachJournalSnapshotManagerForTests`, `publishedPageCopyCount` (test-only counter) — live and used by tests; keep during Phase 7 but count them per roadmap rule 6 and consolidate under one test-seam object in the final slice.
6. **Duplicate durable→live projection logic** — `toArtifactPageFallback` (store file) vs `toArtifactPageFallback`/`toArtifactDisplayProjection` (artifact package) overlap in committed/display-state interpretation; consolidate into one artifact-package projection module.
7. **`publishSidecarPointers` vs `publishSidecarPointersWithStaleRetry`** — both have production callers (store rekey :3423, EnvelopePlanPublication, BatchRenderJoin); not dead, but wrapper semantics should be documented or merged behind the stale-retry default once callers are on the facade.
8. **Registry companion globals** — `clearGlobalWriters` has no main-source caller (test reset only): verify and confine to test scope.

Explicitly **not** dead (do not touch): dormant `AUTHORITATIVE` GC machinery; `ChapterJournalCompaction` rules; `WriterOrigin.STATUS_RESOLVER` (used by DurableChapterStatusResolver); `shouldPersistUpdate` placeholder-suppression (deliberate stranded-page-sweep fix, documented at :4765).

## 7. Target architecture

Principle: the **store facade remains the one live chapter-state/publication authority**; extracted collaborators are `internal`, hold a back-reference to the facade (existing pattern: `PageStageLeaseTable(store)`, `ChapterAttemptLedger(store)`, `StoreStatusProjector(store)`, `StoreWriteDrainCoordinator(store)`), and acquire *all* state through the store mutex. No collaborator may cache facade state across mutex releases (except immutable captured snapshots), so no independently mutable authority replica appears.

```
persistence/
  chapter/
    ChapterTranslationStore.kt      — slimmed facade: state/cache, committed-display authority,
                                      public API shell, delegation (~1.5–2k lines target, no quota)
    ChapterStoreJournalBridge.kt    — capture interleave: credit reservation, captureLegacy*,
                                      bulk capture frames, inventory snapshot, capture permit
    ChapterStoreLazyFlusher.kt      — lazy-queue mutation flush + journal-barrier interleave
                                      (absorbs flushLazyMutationsLocked + capture interleave)
    ChapterStorePublicationBridge.kt— admission (suspending), ensure-store (suspending),
                                      registration CAS, candidate open/persist/promote/failure
    ChapterStatusLedger.kt          — journalStatusSnapshot + recordJournalStatus* family
    ChapterBulkMutator.kt           — replaceAll / rekeyPages / preRegister / clearTransient,
                                      rekey plan + two-phase artifact transaction
    ChapterStoreOpener.kt           — open/probe/recovery factory + seed decision table
    (unchanged) ActiveChapterStoreRegistry, StoreStatusProjector, CleanedImagePublisher,
                ChapterPageReconciler, TranslationStageContracts, ChapterResetPreflight,
                TranslationFileProvider, WriterOrigin
  internal/
    (unchanged) PageStageLeaseTable, StoreWriteDrainCoordinator, ChapterAttemptLedger,
                ChapterStoreEngineMode, WriteDiagnosticFormatter
  journal/                          — unchanged (already cohesive); ChapterStoreJournalBridge
                                      lives in chapter/ because it is store-mutex choreography
  artifact/                         — unchanged engine; + single DurablePageProjection.kt
                                      (merged toArtifactPageFallback / toArtifactDisplayProjection)
```

**First-write initialization fix (separate slice, per mandate):** `admitMutationLocked()` becomes suspending `admitMutation()`; `ensureArtifactStoreLocked` becomes `ensureArtifactStore()` suspend (calls `withArtifactOpenLock` suspendingly — no `runBlocking`); `persistArtifactMutationLocked` becomes suspend. Resulting invariant: the only legal lock order involving the artifact-open lock becomes **store mutex → artifact-open lock** (never reversed), matching the engine's documented rule; epoch allocation stays before the store mutex via `reserveJournalCredit`. The first durable write under an open store keeps paying `load()` once (manifest read + recovery), but now suspends instead of blocking, and the retention sweep stays bounded to ≤8 pages.

## 8. Slice plan

Each slice: independently compiling, behavior-preserving, protected by the named existing suites plus its own additions. No vocabulary changes anywhere; `journalStatusSnapshot` remains a store-mutex-advanced projection.

**S1 — Verified dead-code purge (persistence-only).**
Remove `journalShadowLagCount`, `legacyVisible`, `displayBaseProbe` param; annotate/relocate sync `openArtifact` as test-only; confine `clearGlobalWriters` if confirmed test-only. Update temporary-adapter count.
*Accept:* compile + Spotless; `ChapterTranslationStoreJournalCaptureTest`, `ChapterArtifactEngineTest`, `ChapterTranslationStorePersistenceTest`, `PublishedPageStoreStateTest`, `ReadOnlyPageViewTest` green; a reopened v4 manifest without `legacyVisible` still decodes (new assertion).

**S2 — Suspending first-write initialization (lock-order fix).**
`admitMutation()`/`ensureArtifactStore()`/`persistArtifactMutation` suspend; remove `runBlocking` under the store mutex (ban re-introduction via review note); keep `ChapterStoreEngineMode`.
*Accept:* new test — a store in LazyDurable mode performs its first mutation without blocking a `Dispatchers.IO`-limited pool (probe thread-liveness) ; new deadlock-regression test — concurrent `openArtifactSuspend` on the same artifact while a first write runs does not deadlock; all defunct/drain/lazy tests green (lock order untouched elsewhere).

**S3 — ChapterStatusLedger extraction.**
Move `journalStatusSnapshot`, trust/fallback fields, and the six `recordJournalStatus*Locked` mutators into `ChapterStatusLedger(store)`; `StoreStatusProjector` inputs unchanged.
*Accept:* `StoreStatusProjectorRunRecordTest`, `ChapterTranslationStoreJournalCaptureTest` projection cases, queue-restoration status tests green; no public API change.

**S4 — ChapterStorePublicationBridge extraction.**
Move admission, ensure-store, registration CAS, candidate open/cancel/promote/failure orchestration and origin-resolution rules into the bridge (now suspending per S2). Preserve `lastArtifactPublicationRejectionDiagnostic` and LI-x stale-retry semantics verbatim.
*Accept:* `ChapterTranslationStoreLazyPersistenceTest`, `ChapterTranslationStorePatchPageGraceTest`, `PageSnapshotLostUpdateTest`, `MutationClassificationTest`, `CheckpointOcrTransactionTest` green; rejection diagnostic text unchanged (assert).

**S5 — ChapterStoreJournalBridge + ChapterStoreLazyFlusher extraction.**
Move credit reservation, captureLegacy*, bulk frame capture, inventory snapshot, `JournalFlushBatch` interleave, and the lazy-flush capture choreography behind the facade. `ensureJournalWriter` moves with the bridge; epoch allocation stays outside the store mutex.
*Accept:* `ChapterTranslationStoreCaptureBarrierTest`, `ChapterJournalLagCrashTest`, `StagedJournalCreditOwnershipTest`, `DefunctFlushWindowTest`, `StoreWriteDrainLockOrderTest`, `ChapterJournalWriterTest` green; capture-barrier frame ordering assertions byte-identical.

**S6 — ChapterStoreOpener extraction.**
Move companion `openArtifactSuspend`/`openArtifactOnly`/open-lock registry/`lazy`/`probeArtifactManifest` + the recovery decision table (authoritative journal vs union mode vs legacy) into `ChapterStoreOpener`; facade companion delegates. Keep the sync test open behind the opener.
*Accept:* `ChapterTranslationStoreArtifactOpenProbeTest`, `StoreOpenConcurrencyTest`, `OcrCheckpointRestartReuseTest`, `ChapterJournalRecoveryTest`, `DurableStatusWipeGateTest`, `TranslationManagerArtifactReadTest` green.

**S7 — ChapterBulkMutator extraction.**
Move `replaceAll`/`rekeyPages`/`preRegisterPages`/`clearTransientQueuePages`/`deletePage` + rekey plan machinery. Preserve: bulk journal frame semantics (one credit, one commit sequence), BULK_REKEY deferral rule in replay, placeholder-consumption/collision accounting, attach-aware lease token preservation, retired-image transfer.
*Accept:* `ChapterTranslationStoreRekeyTest`, bulk/partial-bulk capture cases, `ChapterJournalRecoveryTest` rekey cases, `ActiveChapterStoreRegistryTest` green.

**S8 — Facade consolidation + durable projection merge + documentation.**
Merge `toArtifactPageFallback`/`toArtifactDisplayProjection` into `artifact/DurablePageProjection.kt`; consolidate test seams under one object; recount files/adapters; write the persistence ownership map (which class owns which invariant) into the phase artifact.
*Accept:* full focused persistence suite per validation-audit policy in both Debug flavors; no `runBlocking` in `persistence/chapter` (grep gate); facade below the agreed structural budget without forcing further splits.

Sequencing note: S1 is prerequisite hygiene; **S2 must land before S4/S5** so the bridges are extracted in their final (suspending) shape and are not moved twice. S3/S6/S7 are order-independent after S2. Phase 7 assumes Phase 3's writer-completion/bitmap ownership outcomes are settled; nothing here re-opens them.

## 9. Invariant → slice → evidence ledger

| Completion-gate invariant | Owner after Phase 7 | Protecting tests |
| --- | --- | --- |
| Lease/generation/version fencing | facade + `PageStageLeaseTable` + bridge precondition checks (unchanged) | `PageSnapshotLostUpdateTest`, `ChapterTranslationStorePatchPageGraceTest`, `StoreWriteDrainLockOrderTest` |
| Accepted publication (candidate→commit atomicity, retain-on-uncertainty) | facade `promoteDisplayIfReadyLocked` + engine `promoteLiveCandidateTransaction` + `CleanedImagePublisher` | `CleanedImagePublisherTest`, `CheckpointOcrTransactionTest`, `ChapterArtifactEngineTest` |
| Process-death recovery | journal writer/reducer + `ChapterStoreOpener` seed table | `ChapterJournalRecoveryTest`, `OcrCheckpointRestartReuseTest`, `ChapterJournalLagCrashTest` |
| Manifest/journal consistency | CAS stale-retry, inventory capture, seed trust protocol | `ChapterArtifactEngineStaleManifestRetryTest`, capture-barrier suite, seed assertions |
| Committed-display retention | facade `committedDisplay` + display flow + `demoteCommittedDisplay` | `PublishedPageStoreStateTest`, `ReadOnlyPageViewTest`, `ChapterTranslationStorePersistenceTest` |
| No authority replicas / no new page state machine | collaborator pattern: state resolves through facade at each call | review gate per slice + §7 principle |

## 10. Risks / open questions

1. **Suspend-conversion blast radius (S2):** `admitMutation`/`persistArtifactMutation` becoming suspend touches every call site inside `mutex.withLock` blocks; mechanical but wide. Mitigation: S2 is its own slice with compile-isolated diff.
2. **Coalescing + CAS interplay:** manifest coalescing windows (batch rebuild) rely on "facade Mutex held" — the bridge must document and preserve that precondition (engine already asserts it in comments).
3. **Retention sweep cost on first write** stays inline (≤8 pages) after S2; if Phase 5's event-driven retention lands first, re-evaluate.
4. **Open question for the coordinator:** should the sync test-only `openArtifact` move to `app/src/test` fixtures in S1 (touches ~6 test files) or be annotated only, with relocation deferred to S6/S8? Default plan: annotate in S1, relocate in S6.
5. `PreparedPageBoundary`'s direct `store.mutex` use is a Phase 5-owned seam; Phase 7 should not silently change it, only document it in the ownership map.
