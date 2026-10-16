---
title: "P2.5 source-fingerprints — results and evidence"
kind: spec
comments: none
---

# P2.5 implementation results

Ticket `phase-2-contracts/tickets/source-fingerprints/index.md` + accepted mechanism gate (reply on thread `2d687a02`), base `2efde3b5`. The runBlocking/ConcurrentHashMap `LazySourceFingerprints` Map is replaced by a same-file serial suspending read-through cache (`SourceFingerprintCache`) — misses wait on a coroutine `Mutex` (cancellable, no JVM monitor, no parked carrier thread), compute runs in the waiter's context, computed-null installs UNKNOWN once per run, unknown keys return null with zero I/O, thrown errors and cancellation install nothing (retryable), and the caller's activity is re-checked (`ensureActive`) after compute exits and before installation. No Deferred registry, no new file in production, no encompassing artifact/native lock, no callback-framework rewrite, no PageDecode loop change (G1 deferred per settled B), `BatchExpectedFingerprints` and all run-identity paths untouched. Sole implementation/Gradle owner; no commits/pushes/P2.6; report SHA256 unchanged `EFC11B90…88DDD`; the eleven status-M restorations kept their empty content diffs and were never rewritten or staged.

# Exact change (6 production files, 3 test files)

| Path | Δ | Content |
| --- | --- | --- |
| `pipeline/batch/BatchChapterTranslator.kt` | 77/30 | Cache class replaces `LazySourceFingerprints` in place (Mutex + HashMap; `keys`/`size` cheap; `get`; `resolveAll`); ctor site :423; alias leg uses suspending `get` (Cancellation rethrow + per-page skip + all-pages precondition preserved); pre-resolve persisted evidence before the synchronous planner: the revision calls `persistedEvidencePageKeys(store, orderedStreams)` after the alias/rekey block; this same-file production helper reads `store.state.value` at invocation, selects currently persisted keys in canonical stream order, and selects none for a pristine chapter. `resolveAll` builds the immutable plain evidence map consumed by the planner (BatchResumePlanner itself: ZERO edits); stale keys absent from current streams remain unresolved, keeping the planner's null baseline; lane-workers/coordinator lambdas now `suspend`; standard-tail source observation moved BEFORE `tryAcquirePageStageLease` (settled C — under-lease live terminal recheck and all fence evidence unchanged); unused `Dispatchers` import removed. |
| `pipeline/batch/BatchLaneWorkers.kt` | 3/3 | Context property → `suspend (String) -> String?`; `currentSourceIdentityMatches` and `naturalOrderPredecessorTerminal` → suspend (compile-driven widening; :199 call site was the non-suspend predecessor helper). |
| `pipeline/batch/ChapterProfileBatchCoordinator.kt` | 3/3 | Context property → suspend; `admissionSourceSha`, `freshSourcePairsForPages` → suspend; direct :710/:885 reads already suspend contexts. |
| `pipeline/batch/StandardLaneWorker.kt` | 3/3 | Context property + delegate type + `standardPageTerminalAtTranslate` → suspend. |
| `pipeline/batch/PreflightWorker.kt` | 2/2 | `freshSourcePairsForPages`, `admissionSourceSha` context types → suspend. |
| `pipeline/batch/FinalizeWorker.kt` | 1/1 | `freshSourcePairsForPages` context type → suspend. |
| `test/…/coexistence/LazySourceFingerprintPushThroughTest.kt` | 47/41 | The two direct `LazySourceFingerprints` constructions migrated to `SourceFingerprintCache` cases (on-demand/caching + cheap-keys/size + computed-null→UNKNOWN); new fixtures close streams explicitly via `use`. The unrelated N1–5 OCR slot-offset test is byte-unchanged. |
| `test/…/pipeline/batch/SourceFingerprintCacheTest.kt` | new | 9 tests, `runTest` + virtual time, no sleeps: repeat-lookup reuse; concurrent SAME-key single compute; distinct-key serialization with a cancellable waiter (cancel exits the mutex wait, in-flight compute undisturbed); computed-null → UNKNOWN installed once; unknown key → null, no compute, keys/size cheap; **ordinary thrown error → nothing installed, retry recomputes and succeeds**; cancellation during compute → nothing installed, retry works; **compute that returns normally after caller cancellation (NonCancellable fixture) is NOT installed** — the post-compute `ensureActive` checkpoint is actually exercised; `resolveAll` evidence policy incl. computed-null→UNKNOWN and `resolveAll(empty) = empty`. |

Revision scope note: the new regression test calls the production evidence-selection helper after a real ChapterTranslationStore.rekeyPages move, and verifies rekeyed natural keys, canonical ordered-stream order despite a different persisted insertion order, and zero selection for a pristine chapter. The full translateBatch/lane bootstrap harness remains outside this slice; the test exercises the actual extracted selection path that production calls. No new production file or test framework was added. The existing standard-tail code still reads source evidence before acquiring the translation lease and retains its under-lease live recheck.

# Validation

| Run | Shell | Exit | Result (XML authoritative) |
| --- | --- | --- | --- |
| Baseline (9 suites, fresh) | inline in `4f54b8bc`'s parent log | 0/0 | **41/0 per flavor** (both gradle runs BUILD SUCCESSFUL; XML copied to `pre-baseline/{dev,std}/xml`, 9 files each) |
| Dev compile of slice | local | 0 | full module compile incl. all callers — signature widening complete (2 compile-driven widenings, listed above) |
| Post pass 1 | `4f54b8bc` | dev 1 | 50 completed, 3 failed — my own new cache tests (two test-side signal-direction deadlocks, one wrong expected literal); stopped before std; evidence in log + `post-change/dev/xml` |
| Post pass 2 | `d40cee74` | 1/1 | 50 completed, 1 failed per flavor (serialize-order test assertion, pre-append); spotless flagged LiteRT **+ BatchChapterTranslator** (unused `Dispatchers` import left by the runBlocking removal); evidence in log + `post-change/{dev,std}/xml-fix` |
| Post pass 3 (FINAL) | `0bbf7911` | **0/0** | **50/0 per flavor** fresh (`post-change/{dev,std}/xml-fix2`) |
| `:app:spotlessCheck` (read-only, full inventory parse) | `0bbf7911` | 1 | **LiteRT only** — the recorded debt; my import nit resolved; no truncation |

Encoding: all edits via Write/Edit tools + byte-safe checks; no PowerShell text rewriting; no encoding incidents this slice. Failures from passes 1–2 preserved in the shell logs and xml/xml-fix dirs (not overwritten).

# Limits

- Focused suites + fresh full module compile per flavor (the accepted verification plan; no separate duplicate compile tasks); full-suite and parity runs remain the parent's gate.
- PageDecode read-loop checkpoint deferred (settled B); the in-code KDoc documents that an in-flight blocking `InputStream.read` cannot be interrupted and `use{}` cleanup follows the read's actual exit.
- Miss serialization is a deliberate simplicity choice — no wall-time claim is made anywhere.
- LiteRT wrapping debt, ArtifactOpenProbe ledger, JDK/device items: unchanged, deferred.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Baseline XML: `scratch/phase-2-contracts/source-fingerprints/pre-baseline/{dev,std}/xml`
- Post XML (final): `scratch/phase-2-contracts/source-fingerprints/post-change/{dev,std}/xml-fix2` (+ failure evidence in `xml`, `xml-fix`)
- Logs: `commands/…/{4f54b8bc-b7b9-4e47-b5e2-5e1da45ce1ef,d40cee74-9653-4ae7-9c88-67ac2c7ff3e7,0bbf7911-80e7-43fc-949e-3dd6d6d1ff94}/output.log`
- Ticket: `artifacts/phase-2-contracts/tickets/source-fingerprints/index.md`

# Revision after review

This section supersedes earlier statements above where they described the stale pre-rekey selection or said that production-path regression coverage was absent.

## Review corrections

- persistedEvidencePageKeys(store, orderedStreams) now reads store.state.value when called. Production calls it after the alias/rekey block, then passes its canonical ordered selection to SourceFingerprintCache.resolveAll before constructing the unchanged synchronous BatchResumePlanner.
- The regression invokes the same production helper after a real store.rekeyPages move from legacy URL keys to page_1 / page_2. It verifies the Moved outcome and current natural keys, canonical ordering when persisted insertion order differs, and an empty selection for a pristine chapter.
- The same-key cache test now waits for the first compute-entry signal before starting the second request. It advances the scheduler while the first compute is held, verifies both calls remain incomplete and the compute count is one, then releases the first and checks both results.
- No additional production file, general test framework, persistence-internal edit, PageDecode change, planner edit, or run-identity change.

## Exact revision paths

Production content changes (6):

- app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt
- app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt
- app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt
- app/src/main/java/eu/kanade/translation/pipeline/batch/FinalizeWorker.kt
- app/src/main/java/eu/kanade/translation/pipeline/batch/PreflightWorker.kt
- app/src/main/java/eu/kanade/translation/pipeline/batch/StandardLaneWorker.kt

Test content changes (3):

- app/src/test/java/eu/kanade/translation/coexistence/LazySourceFingerprintPushThroughTest.kt
- app/src/test/java/eu/kanade/translation/pipeline/batch/BatchResumeEvidenceSelectionTest.kt (new; 3 tests)
- app/src/test/java/eu/kanade/translation/pipeline/batch/SourceFingerprintCacheTest.kt (new; 9 tests)

The eleven previously reported status-M restorations remain empty content diffs and were not rewritten or staged. The architecture report remains untouched at SHA256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD.

## Revision runs

RED, before the selection fix:

- Scope: one DevDebug BatchResumeEvidenceSelectionTest selector. The retained XML is scratch/phase-2-contracts/source-fingerprints/revision/red/dev/xml/TEST-eu.kanade.translation.pipeline.batch.BatchResumeEvidenceSelectionTest.xml. It reports 3 tests, 1 failure, 0 errors. The real rekey completed, then production selection returned [] where ["page_1", "page_2"] was expected.
- The one-shot RED invocation did not retain a Traycer shell ID or full launcher string. Its exact Gradle selector was :app:testDevDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.BatchResumeEvidenceSelectionTest"; the XML is the preserved run evidence.
- Fixture false start: an initial local fixture call inverted the onlineKeys and onDiskKeys arguments and returned PageRekeyOutcome.Noop. That attempt was not counted as RED. After checking the existing fixture, the call was corrected to map legacy URL keys (onlineKeys) to natural page keys (onDiskKeys); the verified Moved outcome and failing selection above are the genuine RED.

GREEN, both flavors:

- Traycer shell f2b2d64c-3c08-4edc-a9a2-454b9916b027, command description P2.5 revision GREEN: regression + focused set both flavors + spotless full parse.
- The exact focused Gradle invocations were:
./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.BatchResumeEvidenceSelectionTest" --tests "eu.kanade.translation.pipeline.batch.SourceFingerprintCacheTest" --tests "eu.kanade.translation.coexistence.LazySourceFingerprintPushThroughTest" --tests "eu.kanade.translation.pipeline.batch.BatchResumeAndDisplayContractTest" --tests "eu.kanade.translation.pipeline.batch.StandardPipelineCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.Stage7FinalizeCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.Stage7FinalizeResumeCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.OcrPreflightCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.recovery.BatchResumeGateDeciderTest" --tests "eu.kanade.translation.coexistence.StandardLaneMultiPageCompletionTest" --tests "eu.kanade.translation.scheduling.TranslationPipelineConcurrencyTest" --rerun --console=plain
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.BatchResumeEvidenceSelectionTest" --tests "eu.kanade.translation.pipeline.batch.SourceFingerprintCacheTest" --tests "eu.kanade.translation.coexistence.LazySourceFingerprintPushThroughTest" --tests "eu.kanade.translation.pipeline.batch.BatchResumeAndDisplayContractTest" --tests "eu.kanade.translation.pipeline.batch.StandardPipelineCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.Stage7FinalizeCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.Stage7FinalizeResumeCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.OcrPreflightCoordinatorTest" --tests "eu.kanade.translation.pipeline.batch.recovery.BatchResumeGateDeciderTest" --tests "eu.kanade.translation.coexistence.StandardLaneMultiPageCompletionTest" --tests "eu.kanade.translation.scheduling.TranslationPipelineConcurrencyTest" --rerun --console=plain
- Both Gradle test tasks exited 0. XML totals: 11 suites, 53 tests, 0 failures, 0 errors, 0 skipped for each flavor. Preserved at scratch/phase-2-contracts/source-fingerprints/revision/green/dev/xml and scratch/phase-2-contracts/source-fingerprints/revision/green/std/xml.
- The same shell also ran ./gradlew.bat :app:spotlessCheck --console=plain. Before the subsequent EOF-only formatting correction, its full formatter inventory listed LiteRT and the trailing blank line in BatchChapterTranslator.kt; the shell wrapper exited 0 while recording SPOTLESS_EXIT=1. The GREEN test XML and logs were retained.

Read-only spotless recheck after the EOF correction:

- First launch, Traycer shell 5a23e02e-98e1-4423-8afd-fac9eea0bc23: command .\gradlew.bat :app:spotlessCheck, run under Git Bash, exited 127 before Gradle because the PowerShell-style path was not a Git Bash command.
- Actual recheck, Traycer shell d8244cb7-93b6-476f-b35c-02aa912739bf: command ./gradlew :app:spotlessCheck, exited 1 on the existing LiteRT formatting debt only. The complete retained inventory at commands/699de408-00ac-4fe5-a903-b541c23063cc/d8244cb7-93b6-476f-b35c-02aa912739bf/output.log names only src/main/java/eu/kanade/translation/engines/inpainting/litert/LiteRTMangaInpaintingEngine.kt; all diff hunks are for that file, and the full 49-line log has no truncation marker. No tests were run in either spotless launch.

## Evidence and disposition

- RED XML: scratch/phase-2-contracts/source-fingerprints/revision/red/dev/xml/TEST-eu.kanade.translation.pipeline.batch.BatchResumeEvidenceSelectionTest.xml
- GREEN XML: scratch/phase-2-contracts/source-fingerprints/revision/green/{dev,std}/xml/
- GREEN combined log: commands/699de408-00ac-4fe5-a903-b541c23063cc/f2b2d64c-3c08-4edc-a9a2-454b9916b027/output.log
- Post-EOF spotless log: commands/699de408-00ac-4fe5-a903-b541c23063cc/d8244cb7-93b6-476f-b35c-02aa912739bf/output.log
- The failed Git Bash launch log is retained at commands/699de408-00ac-4fe5-a903-b541c23063cc/5a23e02e-98e1-4423-8afd-fac9eea0bc23/output.log.
- No focused tests were rerun after the EOF-only edit. No commits, pushes, full-suite/P2.6 runs, report edits, or staging.

# Next

Revision response returned to the coordinating agent for independent re-review. No commits/pushes or P2.6 runs.
