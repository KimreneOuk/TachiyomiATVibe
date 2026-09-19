# Ticket P2-02: Merge the dual chapter stores into one engine with one lock

**Phase:** 2 | **Risk:** High (concurrency + durability core) | **Type:** Structural refactor,
behavior-preserving | **Mandatory pre-code scoping memo to orchestrator**

## Verified evidence (main @ `65a738e`)

- Facade: `ChapterTranslationStore.kt` (2,753 lines) holds `artifactStore:
  ChapterArtifactStore?` (L112) and its own `mutex = Mutex()` (L119); store operations
  wrap artifact-engine calls under the facade mutex while the engine ALSO uses internal
  `synchronized` blocks → double locking + DTO mapping between legacy store models and
  artifact snapshot models.
- Engine: `ChapterArtifactStore.kt` (2,249 lines) + pure data classes in `artifact/`
  (manifest, layout, contracts, run record, checkpoints, fingerprints — ~3,500 lines of
  DTOs that are NOT part of the merge).
- The artifact test suite is the durability contract and MUST stay green unchanged in
  assertion strength: `ChapterArtifactStoreTest`, `ChapterArtifactStoreStaleManifestRetryTest`
  (T934 seams), `AtomicChapterDocumentsPublicationLockTest`, `CheckpointOcrTransactionTest`,
  `ChapterCommitPointContractTest`, `GroupCommitSliceB/C` tests, `SidecarCrashPublicationTest`,
  `ChapterArtifactStoreRetireActiveRunTest`, `ChapterArtifactStoreManifestCoalescingTest`,
  `ChapterArtifactSchemaGuardCacheTest`, `ChapterArtifactDeletionTest`, `ChapterRunRecordSchemaTest`.

## Goal state

ONE storage engine. `ChapterTranslationStore` remains the single public seam every caller
already uses (ReaderViewModel, coordinators, managers — callers must NOT need changes beyond
imports if any). The `ChapterArtifactStore` engine internals fold into the unified store:
- One lock discipline (the facade's `Mutex`) around all mutable store + engine state; the
  engine's parallel `synchronized` machinery is removed, with a written lock-ordering note
  proving no path can hold two independent locks (or self-deadlock on reentry).
- No DTO round-trip: internal paths use artifact models directly; legacy store-side
  duplicate models are deleted as they become unreferenced.
- `artifactStore` nullable field disappears (the engine is no longer optional). Where the
  code currently branches on `artifactStore == null` (memory-only/no-op mode), preserve that
  MODE as an explicit engine configuration instead of a null engine.
- The `artifact/` package keeps pure data/IO classes (manifest schema, layout, document IO,
  contracts); the ChapterArtifactStore CLASS itself is dissolved. Its file may split into
  focused internal files inside the unified store's package rather than one giant file —
  your choice, but public API of ChapterTranslationStore stays stable.
- Durability semantics preserved exactly: T930 group commit, T934 stale-manifest retry
  seams, NonCancellable teardown flush behavior, SAF publication locking.

## Mandatory scoping memo (BEFORE any code change)

Send me (orchestrator) a short memo first: (a) the engine's lock inventory
(synchronized blocks/mutexes) with call paths, (b) the null-artifactStore mode's entry
points and proposed configuration replacement, (c) the list of public seams on
ChapterTranslationStore that delegate today, (d) your commit split plan (you are
pre-authorized to split this ticket into up to 3 sequential commits: lock unification /
engine inlining / duplicate-model cleanup). Wait for my ack before touching code.

## STOP-gates

- If you find a caller that uses ChapterArtifactStore DIRECTLY (not via the facade), list
  it in the memo — do not silently reroute.
- If any artifact contract test requires semantic change to pass, STOP — that is a
  behavior regression, not a refactor.

## Verification

1. Full both-flavor unit suites green, zero weakened assertions (reviewer will diff test files).
2. `assembleDevDebug` green.
3. `git grep -n 'class ChapterArtifactStore'` returns nothing.
4. Lock-note committed in the report.

## Commit(s)

`refactor(translation): merge ChapterArtifactStore into unified ChapterTranslationStore (1/N..n/N)`
