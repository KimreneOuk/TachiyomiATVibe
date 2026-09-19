# T936 Phase 2 — Independent Review Report

Date: 2026-09-20
Reviewer: Independent Reviewer (adversarial verification; all evidence re-derived from git,
APK, test XMLs, and an independent full Dev suite run — implementer claims not trusted)
Branch reviewed: `t936/phase2-storage-unification` @ `d9beb40`, base `main` @ `65a738e`
(merge-base verified). Net: 96 files, +1,976/−4,592.

## Verdict

**PASS WITH NOTES**

All ten priority items verified with no blocking findings. The −59 executed-test delta is
fully accounted for (ledger below). Assertion strength in every surviving T930/T934 contract
test is unchanged. Two independent full-suite observations (mine + the implementer's) confirm
the flake class is confined to pre-existing load-sensitive coexistence e2e suites, green in
isolation.

---

## Item 1 — Test-count delta accounting (−59): FULLY RECONCILED

The source-level removal is exactly **61 `@Test` methods** (zero `@ParameterizedTest`/
`@RepeatedTest`/`@TestFactory` anywhere in changed files, both sides; zero `@Disabled`;
diff-verified). Whole-tree standalone `@Test` totals: main 2,074 → HEAD 2,013 (−61).

The executed delta (2,079 → 2,020 = −59) differs from −61 because of the **pre-existing T906
quirk** documented at `app/build.gradle.kts:199-239`: Kotlin test methods with expression
bodies whose inferred return type is non-Unit (e.g. `fun x() = runBlocking { … }` returning a
value) are **silently skipped by JUnit**. Cross-checked three ways:

- Standalone-`@Test` arithmetic: executed(main) = 2,079, executed(HEAD) = 2,020, methods
  removed 61 ⇒ exactly 2 of the removed methods were silent-skips. Candidates identified: the
  two `runBlocking`-bodied cases in `TranslationManagerArtifactReadTest`
  ("corrupt flat file is quarantined without deletion", "recoverable missing legacy input is
  retried by the same manager").
- Fresh-run corroboration: my own full Dev run produced **2,020 executed / 293 XML files**,
  matching the implementer's Standard set class-for-class (e.g. `GroupCommitSliceBTest` 3 of
  5 source methods execute; identical counts in both runs) — proving the implementer's XMLs
  are a faithful full run of this code state, and the XML-vs-source gaps are the T906 quirk,
  not stale or tampered evidence.

### The −59 ledger (61 methods removed, all category (b) legacy/migration/authority)

| File | Removed | Category |
|---|---:|---|
| `LegacyArtifactMigrationTest.kt` (file deleted) | 26 | Legacy migration/rescue/cutover suite — authorized by P2-01 change #1 |
| `ChapterArtifactStoreTest.kt` (in `ChapterArtifactEngineTest` class) | 22 | All legacy: loadOrMigrate/rescue/preservation/health-gate/legacy-resync/fast-path/legacy-flat-file-immunity/incomplete-legacy-page cases — P2-01 change #2/#5 |
| `ChapterTranslationStoreArtifactMigrationTest.kt` | 8 | Legacy open/migration/cutover + 3 legacy-`_images/`-companion cases — P2-01 change #2/#4/#5 |
| `TranslationManagerArtifactReadTest.kt` | 5 | Legacy authority/quarantine/retry cases — P2-01 change #3/#5 |
| **Total** | **61** | **59 executed + 2 T906-silent-skip = executed delta −59 ✓** |

Every removed method name was enumerated and individually reviewed: zero removals are
unrelated to legacy/migration/authority semantics. Retained durability cases (corrupt-manifest
backup recovery, schema-guard future-version handling, group commit, stale-retry, coalescing,
retention bounds) all survive.

**Assertion strength across the 58 changed/renamed test files:** hunk-level diff analysis
(348 hunks). Removed assertion lines outside removed methods exist in 131 hunks; after
normalizing the `ChapterArtifactStore`→`ChapterArtifactEngine` rename and
`ManifestAuthority.X` removals, only 8 hunks needed manual review, all legitimate:
authority/`migratedFromLegacy` assertions deleted together with their deleted DTO fields, and
`artifactStore`→`artifactEngine` accessor renames with identical `shouldNotBeNull()`.
**Added-only assertion lines: 11** — 8 accessor-rename duplicates, `outcome.shouldNotBeNull()`
(strengthening), `artifact.readManifest().shouldNotBeNull()` (receiver rename), and
`io.exists("Chapter 1.json.migrated") shouldBe true` (the P2-01-directed DeletionTest flip,
see Item 5). **Zero changed expected values, zero removed assertions in surviving tests.**

## Item 2 — Concurrency-test adaptation (49a443d): PASS

Line-by-line diff of the adapted test
(`ChapterArtifactEngineTest.concurrent candidate opens serialize against the same manifest
snapshot` — class lives in file `ChapterArtifactStoreTest.kt`, see Note 3):

- Same setup (fake IO, `loadArtifact` of the legacy-shaped snapshot), same 2-thread
  `CountDownLatch` start gate, same `openCandidate` arguments (pageKey/origin/
  expectedPageVersion=0/dependencyFingerprint).
- Entry seam only change: direct engine calls → `store.withArtifactEngineLocked { … }` under
  `runBlocking`.
- Assertions identical: exactly 1 `Committed`, exactly 1 `Rejected`, rejection reason string
  byte-identical (`"stale page version: pageKey=page.jpg expected=0 actual=1"`), durable
  manifest generationId + `activeCandidateGenerationIds` checks unchanged. One assertion
  ADDED (`outcome.shouldNotBeNull()`) — strengthening, not weakening.
- Commit touches exactly this one file ✓.

**Sweep independently confirmed:** in the artifact/engine test packages only two files use
concurrency primitives — the adapted test itself and `AtomicChapterDocumentsPublicationLockTest`
(document-level publication locks across two `AtomicChapterDocuments` instances — its diff is
a comment-only update; it never assumed engine-level locking). No other test invokes the
engine concurrently; zero engine-level `@Synchronized` remains (Item 3). The report's
"exactly one test" claim holds.

## Item 3 — Lock ordering: PASS

Structural verification of the claimed invariant (facade `Mutex` → pageLeases monitor /
per-name document lock, never reverse):

- `withArtifactEngineLocked` = `mutex.withLock { artifactEngine?.let(block) }`
  (`ChapterTranslationStore.kt:195`) — all engine mutations enter through the facade mutex.
- `PageStageLeaseTable.pageLeases` is a **ConcurrentHashMap** — the many lock-free reader
  reads are safe; `synchronized(pageLeases)` blocks (store L449 defunct-clear, L2051
  cancel-retain, lease-table internals) are leaf critical sections that never suspend and
  never acquire the facade mutex inside the monitor. Waiter registration/release is
  documented and implemented inside one monitor section nested in the store mutex.
- Per-name document locks: `synchronized(artifactOpenLock(parent, fileName))` (store L2447,
  L2989) and `AtomicChapterDocuments.lockFor(name)` (ChapterDocumentIo L355/L512) wrap only
  IO reads/publishes — no facade acquisition inside.
- `@Synchronized` remaining in the artifact package: only `ModelIdentityCache` (4×,
  pre-existing, untouched this branch, leaf cache monitor) — no engine-level `@Synchronized`
  competing with the facade.
- **Two-phase retention contract survived:** `ArtifactRetention.reconcileRetention` header
  documents and implements crawl OFF the store mutex ("the minutes-long SAF crawl can run OFF
  the store mutex… deletions re-verify in phase 2", reachability re-check mid-crawl sparing);
  the phase-2 diff to this file is 3 lines (legacyLayout displayBase condition removal only).

## Item 4 — Engine modes: PASS

`ChapterStoreEngineMode` (new, store/): `Memory` (no filesystem target; in-memory mutations
with dirty probes retained — explicit Memory handling verified at store L673, L2124),
`LazyDurable(parent, fileName, fileCreator)` (engine opened on first write),
`Durable(engine)` (facade-owned). `artifactEngine` is now a read-only projection of the mode
(`(engineMode as? Durable)?.artifact`), and **zero** `artifactStore == null` /
`artifactEngine == null` branch-style remains (grep clean). The LazyDurable → Durable upgrade
(`ensureArtifactStoreLocked`, store L2433-2450) runs under the facade mutex (Locked-suffix
convention), takes only the leaf per-name open lock around the manifest load, and flips the
mode only after the engine is fully constructed — no half-open engine constructible.

## Item 5 — P2-01 correctness: PASS

- Acceptance greps re-run independently: `LegacyArtifact|LegacyChapterMigration|
  LegacyFlatFile|ManifestAuthority` → 0 hits in `app/src`; `class ChapterArtifactStore` → 0.
- **No on-disk deletion of user legacy data anywhere.** `ChapterArtifactDeletion` lost its
  entire legacy-candidate machinery (`ChapterLegacyDeletionCandidate`, identity proof,
  `candidatesFor`); it now removes only the authority document + artifact tree; result fields
  are kept as `emptyList()` for compatibility. Net effect is SAFER than main (main deleted
  identity-proven legacy sources; the branch never deletes them).
- `TranslationManager`: `decodeLegacyChapterTranslation`/quarantine/`legacyPageJson`/
  `statusFromReadablePages`/authority guards all removed (diff-verified) — artifact-era
  chapters keep identical reads; no-artifact cases resolve null.
- `DurableChapterStatusResolver`: legacy decode seam deleted; resolve is now
  `manifestProbe.exists → artifactStatus()` else `null` — artifact-only, null when no
  manifest, exactly per ticket.
- **Manifest backward-compatibility (checked beyond the checklist):** pre-phase-2 on-device
  manifests contain the now-removed `authority` key. The reader's switch from
  `LegacyFlatFileDecoder.legacyPageJson` to the shared `ArtifactDocumentJson` is safe —
  `ArtifactDocumentJson` is configured `ignoreUnknownKeys = true` (ChapterDocumentIo.kt:306),
  so existing manifests keep parsing. No runtime regression for artifact-era chapters.

## Item 6 — P2-03: PASS

- `BatchProgressProjector` → 0 hits in `app/src`. The file became
  `TranslationProgressProjection` (R089), and the substance is real consolidation, not a
  rename: `snapshotFromStore` now delegates to `store.progressSnapshot(...)`, and
  `readActiveRunRecord` uses the facade-owned `store.readActiveRunRecord()` instead of
  reaching into `store.artifactStore` internals; the `withDurablePause` re-derivation and its
  reflection bridge `withDurablePauseOf` moved into the store path (the bridge has zero
  remaining references — verified).
- `TranslationManager` progress flows through `TranslationProgressProjection`
  (TranslationManager.kt:1025-1028).
- `T934ReaderBarTruthTest`: **not in the diff — untouched, assertion-identical trivially.**
  `T934ProjectorRebuildTruthTest`: diff is exclusively type/constructor renames +
  `authority =` argument removal; all assertions byte-identical.
- `TranslationBatchProgressTracker`: empty diff — untouched ✓.

## Item 7 — P2-00: PASS

`OnnxRuntimeProvider.kt` diff is exactly the additive diagnostic inside the `environment`
lazy: capture `OrtEnvironment.getEnvironment()` in a local, `runCatching` an INFO
`[onnx_runtime] compiledProviders=…` line from `getAvailableProviders()`, WARN on query
failure, return the local. No import needed, no behavior change, once-per-process (lazy).
`HardwareDiscoveryEngine`, `AOTInpainting`, `PaddleOcrSessionFactory`, `OnnxBubbleSegmenter`,
`QnnDiagnostics`, `domain/TranslationPreferences`: **zero diff, all verified.**
(Ticket's API name `getProviders()` vs implemented `getAvailableProviders()` — equivalent
introspection API; immaterial.)

## Item 8 — Safety invariants & scope: PASS

- `app/src/main/assets/` diff vs main: **empty** — `aot-512.onnx`, `aot.onnx`,
  `manga109_bubble_int8.onnx`, both `inference.onnx`, `inference.json`, `vocab.txt`,
  `PP-OCRv6_small_rec.txt` all tracked and byte-identical to main.
- Branch file scope: only `app/src/**` and `Plan/active/2026-09-19_T936_…/**` — nothing else.

## Item 9 — Build evidence: PASS WITH NOTES (independently reproduced)

- **APK** (`app-dev-universal-debug.apk`, 362,164,436 bytes, built 09-20 03:16 local — after
  the last code commit): 2,042 entries; `best_int8` 0; OCR `.md/.yml/.gitattributes` 0;
  `manga109_bubble_int8.onnx` 1; `inference.onnx` 2; **`aot-512.onnx` 1, `aot.onnx` 1** — all
  report claims reproduced exactly.
- **Standard suite:** on-disk XMLs (293 files) total **2,020 tests, 0 failures, 0 errors** ✓.
- **Dev suite — evidence gap closed by reviewer re-run.** At review start the Dev XML
  directory contained only 1 focused-run XML (the implementer's post-full-run focused
  invocations overwrote the full-run artifacts), and the report itself discloses the last
  full Dev run ended 2,019/2,020 with the `StandardLaneMultiPageCompletionTest`
  `expected:<TRANSLATED> but was:<ERROR>` flake. I re-ran the full Dev suite:
  **2,020 tests, 2 failures** — (a) the exact disclosed
  `StandardLaneMultiPageCompletionTest` signature, and (b)
  `BatchDispatchResumeWiringTest` `IllegalStateException: run 1 never reached
  ChapterRunState.COMPLETE within 10000ms` (the test's own message terms load-induced pauses
  legal production behavior). **Both pass in isolation immediately afterward (verified,
  0 failures).** Both are coexistence e2e suites — the same pre-existing load-sensitive class
  disclosed in Phase 1; **no failure in any storage/legacy/artifact suite** in either my run
  or the implementer's, confirming the report's flake-policy claim. Fresh Dev full-run XMLs
  (2,020/2, both flaky classes subsequently green) now sit on disk alongside the green
  Standard set, restoring the evidence the focused runs had overwritten.

## Item 10 — Git hygiene: PASS

Working tree clean; 9 commits, each scoped to exactly its ticket (per-commit file counts: 5
plan docs / 1 diagnostic / 55 P2-01 / 9 + 67 + 5 P2-02 split / 1 test seam / 6 P2-03 / 1
report). No secrets in the branch diff (only hits are test-fixture strings like
`https://secret.invalid/prompt` asserting redaction). `app/google-services.json`: absent from
worktree, never committed (verified again after my own gradle runs, whose finally-cleanup
removed it).

---

## Notes (non-blocking)

1. **Report inaccuracies (cosmetic, should be corrected in the Phase-2 close-out):**
   (a) "Modified 30 test files" — the actual diff modifies **56** test files plus 1 rename
   (`BatchProgressProjectorDurableReconstructionTest` →
   `TranslationProgressProjectionDurableReconstructionTest`, itself unmentioned) + 1 deletion
   + 2 additions (`ArtifactStoreTestFixture`, `ArtifactPublicationTestCompat`). The extra 26
   files are mechanical type-rename/seam adaptations; all were diffed and are clean.
   (b) The report presents the P2-02 split as commits 1-3 then the test-seam commit then
   P2-03; actual history interleaves: `6195c38` (P2-03, 02:24) precedes `49a443d`
   (test seam, 02:47). No content impact.
2. **T906 silent-skip quirk dominates test accounting** (build.gradle.kts:199-239): 13-15
   real test methods per flavor never execute (present on main too — cancels in deltas, but
   it means XML totals ≠ source test counts everywhere). Recommend the Director schedule the
   T906 fix (`runBlocking<Unit>`/explicit return types) so future audits don't have to
   re-derive this.
3. **Test class/file naming divergence:** the engine test class is `ChapterArtifactEngineTest`
   inside file `ChapterArtifactStoreTest.kt` (likewise `EngineManifestCoalescing`/
   `EngineRetireActiveRun`/`EngineStaleManifestRetry`). Valid Kotlin, but it made the
   report's "ChapterArtifactEngineTest" references land on files that greps by filename miss,
   and it cost this review real time. Rename files to match classes in a later cosmetic
   phase.
4. **Flake records are a sample, not a census:** my single Dev re-run surfaced
   `BatchDispatchResumeWiringTest` as an additional member of the load-sensitive coexistence
   class, absent from the report's list (the report's own Phase-1 precedent says failures
   vary between attempts). Consider quarantining/stabilizing the coexistence e2e suites
   (Phase 3 candidate) rather than re-litigating per-phase.
5. **User-visible behavior changes authorized by P2-01, restated for the Director's record:**
   pre-artifact chapters appear untranslated and need retranslation; chapter deletion no
   longer removes identity-proven legacy sources (strictly safer); legacy flat files are
   never read again.

## Conclusion

Phase 2 is a faithful, behavior-preserving-for-artifact-era implementation of P2-00..P2-03
with the authorized test-seam adaptation. The test delta is fully accounted, contract-test
assertion strength is intact, the lock discipline holds structurally, and all hard safety
invariants verified independently. Merge-ready from this reviewer's standpoint, subject to
the Director's process gates and the cosmetic close-out corrections in Note 1.
