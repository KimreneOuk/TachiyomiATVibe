---
title: "Step 1: package contracts, typed rejection and suspending bridges"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Exact boundary relocation manifest

Paths are relative to `app/src/main/java/eu/kanade/translation/` unless stated otherwise. These are planned moves, not production changes already made. Moves reduce reverse imports; they do not reduce the number of source files or create separate Gradle modules.

| Source | Target / edit | Callers updated in the same compiling commit |
| --- | --- | --- |
| `pipeline/NativeVisionTelemetry.kt` | **Move** to `diagnostics/NativeVisionTelemetry.kt`; only package/import changes, preserve API/log fields. | `engines/vision/ocr/PaddlePageOcrCoordinator.kt`, `pipeline/SinglePageOnnxPhase.kt`, `pipeline/SinglePageHttpRenderPhase.kt`, and `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt`. Same-package phase references must receive explicit imports. `TranslationPipeline.kt` does not call this helper. |
| `pipeline/adaptive/DeviceStageNormalization.kt` | **Move** to `diagnostics/DeviceStageNormalization.kt`; it measures trace work denominators, not runtime scheduling policy. | `engines/vision/ocr/RoiPageRecognitionEngine.kt`, `pipeline/TranslationPipeline.kt`, `pipeline/SinglePageOnnxPhase.kt`, `pipeline/SinglePageHttpRenderPhase.kt`, `pipeline/batch/BatchRenderJoin.kt`; update any same-package adaptive tests/references revealed by compile search. |
| `pipeline/execution/TranslationExecutor.kt` — `SinglePageOutcome` declaration | **Extract/move type** into **new** `model/SinglePageOutcome.kt`, together with `PageRejectionKind`. Keep `TranslationExecutor`, stage listener/events, internal `PreparedPage` and batch completion contract in their current execution ownership unless a caller migration requires otherwise. | `presentation/TranslationUiTruth.kt`, `scheduling/TranslationScheduler.kt` / new reader queue, `pipeline/TranslationPipeline.kt`, and the outcome-using tests below. No presentation import of `pipeline.execution` remains. |
| `presentation/TranslationUiTruth.kt` | Remove pipeline and auto-slot implementation imports. Render page truth from unchanged `PageDisplayProjection` / `PageView`, model-owned outcome and minimal reader queue facts; replace string-match rejection mapping. | Reader projections/controller/UI callers migrate in the reader-queue commit. Preserve batch-owner precedence and last committed display during refresh. |
| `pipeline/TranslationPipeline.kt` | Delete `REASON_TRANSLATION_NOT_SAVED`; use typed `PERSISTENCE_FAILED` and retain detail only in diagnostics. Map each existing rejected producer explicitly. | Store `LeaseAcquisition.Denied` remains a persistence boundary; map owner/reason at the execution boundary without making presentation parse its reason. |
| **new** `app/src/test/java/eu/kanade/translation/architecture/TranslationPackageBoundaryTest.kt` | Fast source-level check for both `engines/** → pipeline/**` and `presentation/** → pipeline/**`. | Checked-in production Kotlin/Java under those packages; include explicit imports, aliases, wildcard imports and fully qualified executable references. |

Also update `app/src/test/java/eu/kanade/translation/pipeline/NativeVisionTelemetryTest.kt` (move to `diagnostics/NativeVisionTelemetryTest.kt` with the implementation, or add an explicit new-package import while retaining its package) and the normalization references in `pipeline/adaptive/AdaptiveKnobControllerTest.kt`. These same-package test references will not be found by an import-only search.

`context → pipeline.batch.BatchContextFrontier` is a verified separate reverse dependency. Moving it to `context` is sound but is **not required by the two Step 1 import rules**; defer it to the batch/context boundary work rather than slipping another batch ownership migration into this step.

# Typed rejected outcome

```kotlin
package eu.kanade.translation.model

enum class PageRejectionKind {
    SESSION_DENIED,
    ALREADY_OWNED,
    SOURCE_UNAVAILABLE,
    STORE_UNAVAILABLE,
    PERSISTENCE_FAILED,
    CANCELLED,
    OTHER,
}

// Existing other SinglePageOutcome variants remain.
data class Rejected(
    val owner: PageWriteOrigin?,
    val kind: PageRejectionKind,
    val diagnosticDetail: String? = null,
) : SinglePageOutcome
```

The `Rejected` declaration lives inside the existing sealed interface in the new file; the snippet shows its constructor contract. `kind` has no default, so every producer/fake must choose semantics at compile time. Do not leave a string constructor whose default classification silently preserves the original fragility.

| Existing producer | New typed mapping |
| --- | --- |
| Reader session admission failure in `TranslationScheduler:319` | `SESSION_DENIED`, actual owner when known, original detail diagnostic-only. |
| Lease denial in `TranslationPipeline:517` | `ALREADY_OWNED` when a concrete owner is known; `STORE_UNAVAILABLE` only when store absence is established; otherwise `OTHER` with diagnostic detail. Do not infer categories by matching human sentences. |
| Residual same-page native membership `TranslationPipeline:572/:632` | `ALREADY_OWNED`. |
| `TranslationCompletionOutcome.PersistenceRejected` mapping `TranslationPipeline:742` | `PERSISTENCE_FAILED`. This is a post-attempt publication rejection, not a claim that admission never started. |

UI mapping remains exhaustive and contains fixed safe/localizable copy:

1. If the owner is BATCH, retain the existing batch-session switch truth first.
2. `PERSISTENCE_FAILED` retains `NOT_SAVED`: WARNING severity and explicit Retry/Details, even if diagnostic wording changes.
3. Other admission kinds get their own stable copy/actions; `OTHER` gets generic unavailable copy, never interpolated exception/store strings.
4. A ready committed display takes precedence during failed refresh; a rejection must not hide a valid image.

Update exact import users in unit tests: `coexistence/AutoProviderCallDrainsNotCancelsTest.kt`, `coexistence/P5HonestOutcomeTypingTest.kt`, `coexistence/RenderPermitReacquisitionTest.kt`, `scheduling/TranslationSchedulerTraceTest.kt`, `scheduling/TranslationSchedulerManualOutcomeTest.kt`, `scheduling/RollingAutoCoordinatorTraceTest.kt`, `scheduling/RollingAutoCoordinatorTest.kt`, `scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt`, `scheduling/CancelSyncStoreWriteTest.kt`, `scheduling/CancelBatchLeaseSkipTest.kt`, `workflow/TranslationSessionCoordinatorTest.kt`, `presentation/P5OutcomeProjectionTest.kt`. The old auto tests get the import update while they still compile; remove/merge only after their replacement queue coverage exists.

`P5OutcomeProjectionTest:240/:259` currently constructs raw sentence reasons. Replace with kind constructors; add a diagnostic-wording variation that leaves label, severity, accessibility and retry actions unchanged. A type relocation alone is not sufficient.

## Architecture check mechanism

Resolve the source root from the Gradle project directory, not the caller's process cwd. Read production `.kt` and `.java` files only; tokenize away comments and string literals before matching executable fully qualified names. KDoc references in `NativeStallWatchdog` / `AnalysisEngineTransport` are documentation, not imports. Do not count those as dependency violations.

The forbidden package check examines parsed imports and executable qualified names, including aliases and wildcard imports. Keep the parser/check functions in the same test file; a new analyzer library or Gradle module is unnecessary. Unit fixtures include ordinary/aliased/wildcard imports, executable FQ references, multiline comments and strings containing package text. Demonstrate a planted forbidden import is rejected, then remove the planted violation. Zero exceptions for pipeline implementation imports; moving `SinglePageOutcome` is the required contract correction.

# Ordered chapter replacement without blocking

| File | Planned code change |
| --- | --- |
| `workflow/TranslationManager.kt:1065–1078` | Change `fun cancelRunningChapterForReplace(chapterId: Long)` to `suspend fun`; directly await page cancellation, directly await `clearTransientQueuePages`, then remove the queue entry. Delete both local `runBlocking` blocks. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt:1311` | Keep call inside existing `launchNonCancellable` coroutine. Preserve cancel → clear transient state → replace sequencing; no fire-and-forget launch. |
| `workflow/ChapterQueueConflictDetectionTest.kt` and affected Manga screen tests | Keep conflict choice semantics; update mocks to suspend/coEvery/coVerify where required. Add a gated ordering assertion to the existing replacement workflow test area. |

The caller runs on IO because the local `launchNonCancellable` helper is implemented through `launchIO`. This is an IO carrier-blocking cleanup, not a demonstrated main-thread ANR in that path. The reader queue provides separate immediate intent cancellation and actual drain completion; a bounded wait cannot authorize destruction of a still-active native engine or allow stale publication.

Scheduler cancellation bridges (`TranslationScheduler:584/:637`) disappear or become suspending compatibility wrappers when the reader queue takes over. Keep the off-main durable status/cancellation regression contracts. The ID-less `getChapterTranslation` compatibility bridge and the store first-write `runBlocking` are separate work; do not change persistence initialization as part of this step.

# Suspending single-flight source fingerprint cache

Current `LazySourceFingerprints` (`pipeline/batch/BatchChapterTranslator.kt:1203–1228`) is a `Map` whose `get` runs disk I/O inside `ConcurrentHashMap.computeIfAbsent` + `runBlocking(IO)`. The name and lazy intent do not make that synchronous interface safe. Replace it **in the same source file** with a suspend-only cache; do not introduce another micro-file.

```kotlin
internal class SourceFingerprintCache(
    private val streams: Map<String, () -> InputStream>,
    private val batchScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val compute: suspend (() -> InputStream) -> String?,
) {
    val keys: Set<String> get() = streams.keys
    suspend fun get(pageKey: String): String?
    suspend fun resolve(pageKeys: Collection<String>): Map<String, String>
    fun resolvedSnapshot(): Map<String, String>
}
```

Use a short coroutine `Mutex` only to look up/install a lazy `Deferred` per key. Its producer is a child of the **batch run scope**, not whichever waiter won the cache race. Open/read/close source streams only after releasing that mutex, on injected IO dispatcher. All waiters share the producer; cancellation of one waiter does not cancel work requested by other waiters. Whole batch cancellation cancels producers. Remove failed/cancelled entries by deferred identity when eligible to retry; retain the baseline unknown-hash sentinel on a computed null so it stays fail-closed. Cache lifetime is one frozen batch run/stream mapping; never reuse hashes after rekey/reset/new run.

Attach identity-checked cleanup to producer completion, so an exceptionally completed producer is removed even if every waiter has gone away. Cleanup runs under a narrow NonCancellable cache mutex section and never removes a newer replacement. A cancelled `get` waiter must not evict a still-running/successful producer. If the batch scope itself is cancelled, no replacement computation is admitted in that dead scope. A thrown computation failure permits retry only under the existing retry policy; a computed null remains the baseline fail-closed unknown sentinel for this run. Null is not necessarily a deterministic empty stream: current hashing may return null for unavailable/unreadable data, and a null-to-retry policy change is outside this bridge cleanup. Test producer-failure cleanup without surviving waiters, cancelled waiter with successful producer, and run cancellation separately.

`resolvedSnapshot()` is a plain immutable snapshot with no disk work. `keys`, size and run identity metadata must not trigger all-page resolution. No `Map.get`, `.values`, `.entries` or `containsValue` implementation may hide suspend I/O.

## Exact propagation required by existing synchronous callers

This is the narrow callback **suspension** migration required to eliminate the bridge, not the postponed 24/39-parameter capability refactor.

| File | Exact edit |
| --- | --- |
| `pipeline/batch/BatchChapterTranslator.kt` | Replace cache creation at `:422`; make hash accesses at `:433/:757` suspend. Preserve explicit wider rekey identity scan only when legacy source keys require it. Before constructing the resume planner, resolve hashes for persisted pages that its current reuse/context plan actually inspects; pass the resulting plain map. Inject `cache::get` into fresh-page admission consumers. |
| `pipeline/batch/recovery/BatchResumePlanner.kt` | Keep `PageWorkPlanner` pure. Constructor accepts resolved hash evidence, never an I/O-performing Map. Move required resolution into the suspending batch caller; do not call a suspending cache from property initialization `batchPagePlans = buildBatchPagePlans()`. New pages are resolved at worker admission. |
| `pipeline/batch/StandardLaneWorker.kt` | Change `StandardLaneWorkerContext.freshSourceShaByPageKey` and its property to `suspend (String) -> String?`; resolve inside the already-suspending worker entry before building pure `BatchPlannerInput`. |
| `pipeline/batch/BatchLaneWorkers.kt` | Change constructor `freshSourceShaByPageKey(:110)` to suspend. Resolve at suspending worker entry for the `:179/:1075/:1244` consumers. Make `currentSourceIdentityMatches` a pure comparison of **passed-in resolved hash** and page evidence; do not call the cache from non-suspending filters/predicates. Thread the awaited local evidence into the textless and normal translation commit paths before their guarded mutations. Update the three injecting lambdas in `BatchChapterTranslator(:660/:842/:889)` in the same commit. |
| `pipeline/batch/ChapterProfileBatchCoordinator.kt` | Change fresh-source callback to suspend; make `admissionSourceSha` and `freshSourcePairsForPages` suspend; resolve required chunk/page hashes **before** entering `buildEnvelopeDispatchWorkLocked` or another store/document critical section; pass resolved evidence into the locked validation path. Revalidate existing generation/version/identity evidence on entry. |
| `pipeline/batch/PreflightWorker.kt` | Make context `admissionSourceSha` and `freshSourcePairsForPages` suspend; current checkpoint helpers are already suspending. Resolve before guarded checkpoint acceptance. |
| `pipeline/batch/FinalizeWorker.kt` | Make context `freshSourcePairsForPages` suspend; resolve terminal/run-wide evidence before guarded closure. Preserve the existing requirement that COMPLETE proves the full eligible corpus. |

Keep `BatchExpectedFingerprints` (`pipeline/planning/PageWorkPlan.kt:74`) unchanged: it is configuration evidence, not the source hash cache. Pure planning remains a synchronous transformation over already obtained inputs. Do not add eager hashing of a pristine whole chapter merely to keep a Map interface. Existing wider scans for legacy alias repair or complete-run identity are load-bearing prerequisites and stay explicit; this step makes them suspend rather than promising to remove them.

If the requested narrow suspension migration uncovers another non-suspending caller, add it to this manifest and change its boundary in the **same** commit. Do not ship an interim blocking adapter. Tests using completed lambdas remain simple; failure/unknown callbacks must preserve strict source checks rather than fall back to an old recorded hash.

# Regression gates

- Boundary checker rejects both forbidden package edges and ignores documentation/string examples.
- Typed rejection mapping preserves BATCH precedence, warning/retry for unsaved results, accessibility copy and committed-display precedence regardless of diagnostic text.
- Replacement workflow waits for cancellation and transient state clearing before replacement admission; no blocking coroutine bridge remains on the changed path.
- Same-key hash requests compute once, different keys do not serialize on a map bucket/monitor, one cancelled waiter does not poison the other, failed producers can retry, whole run cancellation drains/closes streams, and cache reset invalidates stale hashes.
- Completed batch reuse, legacy URL rekey, source replacement under same key, context seeding, checkpoint adoption and terminal corpus closure remain fail-closed.
- No I/O is newly performed while holding a cache mutex or a store/artifact lock; resolve before protected revalidation.

Add `pipeline/batch/SourceFingerprintCacheTest.kt`. Extend existing `BatchResumeAndDisplayContractTest`, `StandardPipelineCoordinatorTest`, `Stage7FinalizeResumeCoordinatorTest`, durable-key alias tests and the coordinator/preflight/finalize tests identified in the test manifest. The cache unit tests can use `runTest`/controlled dispatchers; the broader recovery tests remain because they exercise real publication outcomes.
