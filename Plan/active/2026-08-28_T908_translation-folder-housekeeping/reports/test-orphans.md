# T908 — Translation Test-Suite Orphan Audit

Auditor: T908 test-suite orphan auditor (static analysis only; no gradle runs).
Checkout state at audit time: branch `t907/fix`, HEAD `2085c03`.
NOTE: the workspace moved under this audit — it started on
`optimize_translation_finishing_page` with the T907 files untracked and the
three T907 production files dirty; the T907 implementer committed
`b65a21f` (README) + `2085c03` (fix + tests) mid-audit. All verdicts below are
against the post-commit state, which is a superset of what was asked.

Scope: 130 Kotlin files under `app/src/test/java/eu/kanade/translation/`
(127 `*Test.kt` + 2 helpers) plus the then-untracked
`app/src/test/java/eu/kanade/tachiyomi/ui/manga/EnqueueTranslationDownloadsTest.kt`.

---

## Summary

- **Orphaned tests: 0.** All 191 `import eu.kanade.translation.*` statements
  across the 127 translation test files resolve to live production symbols.
  The suite compiles against `app/src/main` as-is.
- **Untracked files verdict: in-flight T907 work, not orphans.** Both paths
  were committed as `2085c03` during this audit; every import resolves to
  current production code.
- **T906 drift:** all four T906 *fix* rounds are present on this branch
  (re-applied under different hashes); all *audit-only* second-pass deletion
  recommendations (U-items) remain open, including the 8 silently-skipped
  `RollingAutoCoordinatorTest` methods, re-confirmed empirically via `javap`.
- **Duplicate coverage:** no file-level copy-paste duplicates. Multi-file
  subject groups are complementary; two thin overlaps already flagged by T906
  remain; one new duplication is the copy-pasted `uninitializedManager`
  Unsafe fixture (3rd copy).
- **Unused test utilities: none.** All 3 non-test helper files are referenced.
- **Stale fixtures: 2 files** — `emission_report.json` × 2 under
  `app/src/test/resources/corpus/`, unreferenced by any test.

---

## 1. T906 findings drift (Plan/active/2026-08-27_T906_test-suite-audit/review/)

The T906 audits targeted `56179d7` (an ancestor of HEAD) and its fix rounds
ran on separate worktree branches (`1a0db7b`, `ce75b7b`, `2bd73b3`, `ba24abc`)
that are **not** ancestors of HEAD. However, the same fixes exist in this
branch's history under re-applied hashes:

| Area | Original fix hash | Hash on this branch | Commit subject |
|---|---|---|---|
| 1 | `1a0db7b` | `47f0046` | "test(translation): drive real display counter, prune redundant batch tests" |
| 2 | `ce75b7b` | `3091c61` | "test: rename stale artifact-store test names to match asserted contracts" |
| 3 | `2bd73b3` | `710b80f` (+ follow-up `c924bbd`) | "test(translation): revive silently skipped manager lifecycle tests and stabilize teardown latches" |
| 4 | `ba24abc` | `aef6c9b` | "test: rewrite AOT bubble-fill checks to run + flat-fill policy" |

### Resolved (verified on current checkout)

- **Area 1 A1-10 (MEDIUM, mirror counter)** — `countPages` mirror is gone from
  `batch/DisplayReadyStageCountTest.kt`; the file now drives the real
  `TranslationBatchProgressTracker.computeSnapshot(...)` (line 115). RESOLVED.
- **Area 1 A1-09 (tautological `standard engine translation pathway` test)** —
  absent from `batch/ChunkTranslationPayloadTest.kt`. RESOLVED.
- **Area 1 A1-06 (`legacy blockId-keyed merge` test)** — absent from
  `batch/BatchTranslateBlockMergeTest.kt`. RESOLVED.
- **Area 2 Finding 1 (stale "schema one" name)** — test is now
  `committed-only artifact fixture rehydrates with no legacy flat file`
  (`ChapterTranslationStoreArtifactMigrationTest.kt:251`). RESOLVED.
- **Area 2 Finding 2 (stale resync name + dead `failWrites` setup)** — test is
  now `reopen with a changed legacy identity returns the prior manifest
  unchanged` (`artifact/ChapterArtifactStoreTest.kt:495`) without the
  `failWrites` toggle. The remaining `io.failWrites` uses at :653/:657 belong
  to a *different*, legitimate `recordDurableFailure → NotStored` test.
  RESOLVED.
- **Area 3 F1 (arbitration test never executed + stale Unsafe fixture)** —
  `TranslationManagerAutoArbitrationTest.kt:75` is `runBlocking<Unit>`, the
  fixture injects `pendingTranslationRequestsState`, `pendingRequestWriteVersions`,
  `pendingRequestMutationLock` (follow-up `c924bbd`), `pendingRequestStore`,
  `context`, and masks `TranslationForegroundService.Companion` via
  `mockkObject` (:108). RESOLVED.
- **Area 3 F2 (1 s teardown latches)** — all fixed `await(1, TimeUnit.SECONDS)`
  waits are gone (grep count 0); replaced by `withTimeout(5_000)` condition
  polling (`TranslationManagerReaderTeardownTest.kt:88,93,99,177`). RESOLVED.
- **Area 4 H1 (AotReportBubbleFillTest tests 1–3)** — rewritten as
  policy-compliant "runs + collapses to one flat fill (never which color)"
  smoke checks; dead `val insetPx = 5` deleted from
  `inpainting/AotReportBubbleFill.kt` (the name survives only as the
  `smoothMaskedComponent` parameter, :205/:221, as the report predicted);
  duplicate `normalizeBaseUrl` test deleted from `AiModelFetcherParseTest`.
  RESOLVED.

### Still open (verified on current checkout)

All of these were **audit-only** recommendations awaiting Director approval;
none were acted on.

| T906 ref | Item | Verified evidence on HEAD |
|---|---|---|
| A1-U1 | `BatchOomPolicyTest` (4 tests) + dead production `BatchOomPolicy` | Both exist (`batch/BatchOomPolicyTest.kt`, `batch/BatchOomPolicy.kt`); sole production reference is the `MemoryPressurePolicy.kt:18` style comment |
| A1-U2 | `isTransientRateOrServerError` shim + its 2 tests | Shim at `translator/TranslationRetry.kt:275`; tests at `translator/TranslationRetryTest.kt:104,114` |
| A1-U3 | 3 negative `TranslationBatchEventContractTest` tests subsumed by exact-set test | Present at `batch/TranslationBatchEventContractTest.kt:33,40,47` |
| A1-U4 | `envelopes preserve natural page order` duplicate | Present at `batch/BatchEnvelopeLimitsTest.kt:25` |
| A1-U5 | 2 duplicate-clause planner tests | Present at `translator/AiTranslationRetryPlannerSinglePageTest.kt:55,78` |
| A2-F1 | `CBZ entry names are valid targets` (identical path to :17 test) | Present at `ChapterTranslationStoreRekeyTest.kt:56` |
| A3-U1 | 3 result-identical overlay "tier" tests | Present at `tachiyomi/ui/reader/viewer/ReaderTranslationOverlayBindingTest.kt:44,59,74` |
| A3-U3 | **8 silently-skipped `RollingAutoCoordinatorTest` methods (HIGH)** | RE-CONFIRMED EMPIRICALLY: `javap` over the compiled class shows all 8 with non-void JVM returns (`int` / `AutoTranslationSnapshot`); file untouched since `56179d7`; 0 `runBlocking<Unit>` among 31 bare `runBlocking {` |
| A3-F3/F4/F5/F6/F7 | Coverage gaps (store round-trips; STARTING-before-commit ordering; paused affordances; auto-delete protection; overlay positive path) | Still no test references `acknowledgeTranslationRequests`/`persistPendingStartingAcknowledgement`; no direct `TranslationQueueStore` test; overlay file still has 5 empty-binding tests, no positive case |
| A4-U1/U2 | 2 compute-class routing tests duplicated by `TranslatorComputeClassTest` | Present at `translator/Checkpoint2IntegrationTest.kt:14,20` |
| A4-U3 | `large sparse geometry...` with wall-clock timeout gate | Present at `segmentation/MaskGeometryTest.kt:47` |
| A4-U4 | `exceeding the configured span budget...` duplicate | Present at `segmentation/MaskGeometryStressTest.kt:60` |
| A4-U5 | `unionMasks empty inputs return empty` trivial test | Present at `inpainting/BubbleCleanerMathTest.kt:78` |
| A4-U6/U7 | 2 low-value `DirectBufferPoolTest` tests | Present at `domain/src/test/.../DirectBufferPoolTest.kt:47,154` |

**Highest-value still-open item:** A3-U3 — 8 `RollingAutoCoordinatorTest`
methods count as suite members but have never executed (Jupiter 5.x skips
non-void `@Test` methods). Their expectations are unvalidated.

---

## 2. Orphaned tests (imports vs production tree)

Method: extracted every `import eu.kanade.translation.*` line from all 127
test files under `app/src/test/java/eu/kanade/translation/` (191 imports),
then verified each imported symbol against
`app/src/main/java/eu/kanade/translation/` by grepping for class/object/
interface declarations, top-level `fun`/`val` declarations, and — in a second
pass — extension functions/properties (`fun Receiver.name`, `val Receiver.name`).

**Result: 191 / 191 resolve. Zero orphaned tests.**

The only first-pass false positives were extension members declared in
`model/PageTranslationState.kt` and `model/PageTranslationOwnership.kt`
(`isTextlessTerminal`, `detachedCopy`, `displayImageName`,
`hasRecognizedTranslation`, `isTranslationDisplayReady`), all of which exist.
Notably, production `ReaderViewModel.kt` and `ChapterTranslationStore.kt`
import the same extension set, corroborating the resolution.

No test file imports a symbol that exists only in git history.

---

## 3. Untracked files verdict

Both paths were **in-flight T907 work** (per
`Plan/active/2026-08-28_T907_batch-download-autostart-recovery/README.md`,
"IMPLEMENTATION AUTHORIZED"), committed as `2085c03`
("fix(translation): auto-start batch downloads and self-clear download-failure
state", 5 files, +335/−9) while this audit ran. **Keep; not stale leftovers.**

### 3a. `app/src/test/java/eu/kanade/translation/TranslationManagerDownloadFailureRecoveryTest.kt`

- 209 lines, 5 tests. KDoc self-identifies: "T907: DOWNLOAD_FAILED pending
  requests must be self-clearing."
- Exercises four production methods that exist in the modified
  `TranslationManager.kt`: `queueTranslationAfterDownload` (:256),
  `markTranslationDownloadFailed` (:292), `clearStaleDownloadFailedRequest`
  (:314), `startTranslationAfterDownloadIfRequested` (:417).
- All imports resolve: `TranslationRequestPhase.DOWNLOAD_FAILED` /
  `WAITING_FOR_DOWNLOAD` (`model/TranslationRequestState.kt:14,16`),
  `TranslationScheduler(executor, storeResolver, immediateStoreResolver)`
  (ctor matches `scheduling/TranslationScheduler.kt:51-55`),
  `TranslationPendingRequestStore.phase(Long)` (:37),
  `TranslationForegroundService.start`, `ChapterTranslator.queueState /
  isRunning / queueChapter`, `Translation.State.QUEUE`.
- The Unsafe `uninitializedManager` fixture sets exactly the manager fields
  that exist today, including `pendingRequestMutationLock` (:125) — same
  hardened pattern as `TranslationManagerAutoArbitrationTest` (post-`c924bbd`),
  which the file's comment explicitly mirrors.
- Matches T907 Slice 2 ("DOWNLOAD_FAILED phase must be self-clearing —
  retry or re-download advances it, manual/auto entry clears stale state").

### 3b. `app/src/test/java/eu/kanade/tachiyomi/ui/manga/EnqueueTranslationDownloadsTest.kt`

- 73 lines, 3 tests. KDoc self-identifies: "T907: the translation-driven
  enqueue bridge must guarantee the downloader runs..." (Slice 1).
- Sole production dependency is the internal seam
  `enqueueTranslationDownloads(downloadManager, manga, chapters)` — declared at
  `MangaScreenModel.kt:1691`, called at `MangaScreenModel.kt:1038`. All imports
  (`DownloadManager`, `Download`, `HttpSource`, domain models) exist.
- The package location (`eu.kanade.tachiyomi.ui.manga`) is required: the
  tested function is `internal`/package-scoped in that package. This is why an
  otherwise translation-scoped test lives outside
  `eu/kanade/translation/` — deliberate, not misplaced.

### 3c. Cross-check against the three modified production files

- `MangaScreenModel.kt` (+42): wires `queueTranslationAfterDownload` +
  `enqueueTranslationDownloads` into the download-await pipeline (:1030-1043) —
  Slice 1.
- `TranslationManager.kt` (+17): the four methods/phases the recovery test
  drives — Slice 2.
- `ReaderViewModel.kt` (+3): `:2133` calls
  `translationManager::clearStaleDownloadFailedRequest` on reader entry —
  Slice 2's "manual entry must never be gated by a stale failed state".

All three diffs correspond 1:1 to README contract items; no scope creep.

---

## 4. Duplicate coverage

No whole-file copy-paste duplicates exist: all 128 test classes have distinct
nominal subjects (`FooTest` → `Foo` mapping is injective). Multi-file subject
groups (same production class exercised by several test files) are:

| Production subject | Test files | Verdict |
|---|---|---|
| `ChapterTranslationStore` | 8 files use it as harness (batch progress trio, `Phase0...Characterization`, `CancelSyncStoreWrite`, `PreparedPageRuntimeBoundary`, `RollingAutoCoordinator`, `BatchTranslateBlockMerge`) + 5 dedicated root-family tests (Persistence/Defunct/Phase3/Rekey/ArtifactMigration) | **Complementary.** Each file pins a different contract (progress model, cancel-sync ordering, runtime boundary, rekey, migration, durability). Confirms T906 Area 2's near-pair analysis. |
| `TranslationScheduler` | `TranslationManagerAutoArbitrationTest`, `...ReaderTeardownTest`, `...DownloadFailureRecoveryTest` | **Complementary** (fixture usage; distinct manager contracts: arbitration, teardown, download recovery). |
| `StreamingChunkPlanner` | `translator/StreamingChunkPlannerTest` (15 tests) + `batch/BatchEnvelopeLimitsTest` (3 tests) | **Thin overlap.** `single dense page remains one envelope` duplicates `complete dense page stays intact in its batch envelope` / `single small page yields one chunk...`; `dense chapter packs... without static caps` re-pins the same no-cap arithmetic. T906 A1-U4 already recommends deleting the middle test; the whole file is a candidate for fold-in. |
| `TranslatorComputeClass` | `translator/TranslatorComputeClassTest` + routing tests in `Checkpoint2IntegrationTest` (+ enum used as fixture in 2 coordinator tests) | **Partially redundant** — exactly T906 A4-U1/U2 (delete the 2 `Checkpoint2` routing tests, keep its unique streaming tail-flush test). Still open. |
| `MaskGeometry` | `MaskGeometryTest` (8) + `MaskGeometryStressTest` (3) | **Complementary** (functional geometry vs scale/determinism). T906 A4-U3/U4 single-test candidates still open. |
| `AiModelFetcher` | `AiModelFetcherTest` + `AiModelFetcherParseTest` | **Complementary after fix** — the duplicated `normalizeBaseUrl` test was deleted by the T906 area-4 fix; ParseTest now holds only parse coverage. |

**New duplication introduced by T907:** `TranslationManagerDownloadFailureRecoveryTest`
copy-pastes the `uninitializedManager` + `setField` Unsafe-reflection fixture
(~45 lines) from `TranslationManagerAutoArbitrationTest` (and a third variant
lives in `TranslationManagerReaderTeardownTest`). The copy is faithful
(includes the `pendingRequestMutationLock` fix from `c924bbd`), but this is
now the third hand-maintained copy of a reflection fixture that breaks
whenever `TranslationManager` gains a required field. Recommendation: extract
a shared `TranslationManagerTestHarness` in the test tree (needs a follow-up
task; not urgent).

---

## 5. Unused test utilities

Exactly three non-`*Test.kt` helper files exist under `app/src/test/java`;
**all are referenced — none unused**:

| Helper | Referenced by |
|---|---|
| `com/hippo/unifile/FakeUniFile.kt` | `ChapterTranslationStoreArtifactMigrationTest`, `TranslationManagerArtifactReadTest` |
| `eu/kanade/translation/artifact/FakeChapterDocumentIo.kt` | `ChapterArtifactStoreTest`, `AtomicChapterDocumentsTest`, `ChapterArtifactDeletionTest`, `TranslationManagerArtifactReadTest` |
| `eu/kanade/translation/batch/BatchStageInvocationCounters.kt` | `Phase0BatchTranslationCharacterizationTest` |

(The duplicated-copy hazard of the in-file `uninitializedManager` fixture is
covered in §4; it is over-duplicated, not unused.)

---

## 6. Stale fixtures

`app/src/test/resources/` contains only `corpus/`:
`corpus/aot/` (42 `real_*` fixture dirs × 7 files + 1 stray file) and
`corpus/aot_sub512/` (4 dirs × 7 files + 1 stray file) = 324 files. The 42+4
dir counts match `AotCorpusGateTest`'s defensive size assertions
(`EXPECTED_CORPUS_SIZE=42`; sub-512 sides 300/400/480/511).

**Stale (unreferenced): 2 files**

- `app/src/test/resources/corpus/aot/emission_report.json` (1.3 KB)
- `app/src/test/resources/corpus/aot_sub512/emission_report.json`

No test source references the name `emission_report` (grep across
`app/src/test`), and `AotCorpusGateTest.corpusPages()` /
`sub512Fixtures()` enumerate **directories only**
(`listFiles { f -> f.isDirectory }`), so both files are inert at runtime.
They are byproducts of the corpus generator
(`tools/aot_corpus/emit_corpus_outputs.py`, dated 2026-08-17).
Recommendation: delete both, or move them under the Plan folder if the
emission report has archival value. ~2.6 KB — cosmetic.

No referenced resource is missing (only `corpus/aot` and `corpus/aot_sub512`
are loaded). `domain/src/test` has no resources folder.

---

## Method

1. Read all four T906 review reports; mapped their baseline (`56179d7`) and
   fix commits against this branch (`git merge-base --is-ancestor`, `git log
   -- <file>`), then re-verified ~20 concrete dispositions/claims on disk via
   targeted grep (test names, fixture fields, latch patterns, dead
   production code).
2. Re-confirmed the T906 A3-U3 silent-skip claim **empirically** with
   `javap` (Android Studio JBR) over the already-compiled
   `app/build/tmp/kotlin-classes/standardDebugUnitTest/.../RollingAutoCoordinatorTest.class`:
   all 8 named methods have non-void JVM return types. No build was run.
3. Orphan scan: scripted extraction of all 191 `import eu.kanade.translation.*`
   lines from the 127 translation test files; each symbol checked against the
   production tree with declaration-pattern greps (classes/objects/interfaces,
   top-level fun/val, extension fun/val in a second pass).
4. Untracked-file audit: full reads of both files, symbol-by-symbol
   resolution against the three modified production files, and contract
   comparison against the T907 README (slices 1/2 map 1:1).
5. Duplicate coverage: grouped tests by nominal subject and by shared
   `eu.kanade.translation` imports; compared test-name inventories of the
   overlapping planner/geometry/fetcher families.
6. Utility/fixture scan: enumerated non-`*Test.kt` files under
   `app/src/test/java`, grepped each name across the tree; enumerated
   `app/src/test/resources`, grepped resource paths and enumerated loaders'
   directory-filter logic.

All work read-only; the single write is this report.
