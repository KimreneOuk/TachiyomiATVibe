---
title: "Step 1 structural rebuild: one reader queue, owned native work and verified purge"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Authority and deliverables

**Planning only.** Baseline is `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`; production, tests, models and build files remain unchanged. No Gradle run, race reproduction or device measurement has been performed for this plan.

The user's follow-up establishes a new reader product policy: one common page runner, CURRENT_PAGE / PREFETCH_NEXT, exactly one next page, in-place promotion and native preparation finished before HTTP. This supersedes the earlier plan's requirement to keep manual and auto scheduling distinct. Batch scheduling remains separate; batch callback-capability refactoring and persistence internals remain deferred.

| Required output | Concrete document |
| --- | --- |
| 1. File deletion / inlining / consolidation manifest | [Production manifest](production-manifest), [test manifest](test-manifest), final cross-checked accounting below |
| 2. Unified reader queue signatures and transition rules | [Unified API contract below](#unified-reader-api-contract), [reader caller map and lifecycle contracts](reader-queue) |
| 3. Single-page native ownership and block join | [Registered native work and stable block join](single-page) |
| 4. Typed outcomes, package boundaries and blocking bridge removal | [Boundary and bridge edits](boundaries-and-bridges) |
| Ordered small compiling commits and gates | [Commit sequence](commits) |
| Source/accounting cross-check | [Completed source review](final-review); three corrections incorporated |
| Independent mechanism critique and disposition | [Independent review](independent-review), [primary correction ledger](critique-response) |

**Review status:** **planning and independent recheck complete.** The source/accounting review, reader caller map and amended mechanism critique are delivered. All nine critique findings have a source-qualified disposition; the independent reviewer found no remaining textual mechanism blockers. The [correction ledger](critique-response) records explicit child drainage, null-image outcomes, same-waiter native priorities, owner-supplied publication deletion guards, request-scoped provider provenance and C05 publication safety. This is review of a plan, not verification of implemented behavior or authorization to modify production. Baseline builds, race tests and device measurements remain required and unexecuted.

# Settled direction and explicit planning assumptions

1. Manual tap and auto viewport are two submission triggers for the same `TranslationExecutor.translateSinglePage` entry. Auto is not another executor, page lease owner or durable lifecycle.
2. Auto ON targets visible N and next N+1. Use actual reader page ordering, including double-page insertion/webtoon arrangements and unavailable streams; do not manufacture N+1 at chapter end. The old configurable ahead-window setting is retired for reader auto; this is a requested behavior change, not dead-code discovery.
3. Promotion changes priority of an existing compatible page operation; it does not cancel/restart, reacquire its lease, reopen the source, redo OCR or re-charge an already performed provider call. Explicit forced retry is a separate next attempt when it is incompatible with the current attempt.
4. Reuse the existing MANUAL lease origin for new reader operations, regardless of trigger, and keep the AUTO enum/old persisted origin evidence compatible. Queue trigger/priority facts are transient and distinct from lease authority. Do not add or remove store ownership rules just to rename reader scheduling.
5. Requests are deduplicated by reader-session generation, store/chapter identity and canonical page key. Configuration/source revision and forced-retry compatibility are checked at admission; a stale attempt cannot authorize a new-config result.
6. At most two automatic page operations are active; at most one occupies native admission. Explicit manual requests can remain in a bounded pending queue; a full queue returns a typed rejection, never silently drops an acknowledged tap.
7. An auto-origin old CURRENT_PAGE loses viewport demand when the viewport moves; absent explicit manual demand it becomes speculative and is pruned outside N/N+1. Explicit manual demand survives this prune. Auto OFF clears speculative demands, preserves explicit requests and already committed display.
8. Cancelling a running native request abandons its result and drains actual exit. It cannot interrupt C++ inference or release its engine/bitmap/admission early. New foreground work waits for real native availability.
9. Required storage/publication barriers remain before HTTP, but run outside native admission. The native lane is not held while awaiting remote provider translation. Rendering retains its existing device memory/compute gate.
10. Queue facts are request intent/priority/outcome only. Display truth continues through `PageLifecycle`, `PageDisplayProjection`, `PageView` and the authoritative store. No replacement durable state machine.

Default behavior for source/memory/provider deferral is explicit typed non-success with cooldown/event-driven reconciliation; do not spin retry loops on every scroll callback. Missing stream resolution is not permanent terminal failure. These edge rules must be implemented/tested in the queue document, not delegated to UI guesswork.

## Unified reader API contract

Co-locate the following request-only types in `scheduling/ReaderTranslationQueue.kt`; they do not replace any durable page model:

```kotlin
enum class ReaderPriority { CURRENT_PAGE, PREFETCH_NEXT }
enum class ReaderTrigger { MANUAL_TAP, AUTO_VIEWPORT }
data class ReaderSessionToken(val chapterIdentity: String, val readerGeneration: Long)
data class ReaderPageWork(
    val pageKey: String,
    val readerPageIndex: Int,
    val streamFn: (() -> InputStream)?,
)

class ReaderTranslationQueue(
    private val executor: TranslationExecutor,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val storeResolver: TranslationStoreResolver,
    private val nowEpochMs: () -> Long,
    private val cancellationWaitMs: Long = 2_000L,
) {
    fun bindSession(
        session: ReaderSessionToken,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        computeClass: TranslatorComputeClass,
    )

    fun submitCurrent(
        session: ReaderSessionToken,
        work: ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission

    fun updateViewport(
        session: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        isAutoEnabled: Boolean,
        resolve: (Int) -> ReaderPageWork?,
    )

    fun setAutoEnabled(session: ReaderSessionToken, enabled: Boolean)
    fun cancelPage(session: ReaderSessionToken, pageKey: String): Boolean
    fun outcomes(session: ReaderSessionToken): StateFlow<Map<String, SinglePageOutcome>>
    suspend fun cancelSession(session: ReaderSessionToken): ReaderDrainResult
    suspend fun closeAndDrain(): ReaderDrainResult
}
```

`ReaderSubmission` has Queued / Promoted / Existing / Rejected typed variants; `ReaderDrainResult` distinguishes completed drain from native work still quarantined after the navigation wait budget. These types live in the same file. The queue binds existing Manga/Chapter/HttpSource and store resolution through the reader session at session opening; it does not put Android reader-page objects, bitmaps or open streams into its pending map.

The core entry map/deques and priority mutation use one short synchronous control lock; no I/O, suspension, bitmap allocation, store mutation or inference runs while it is held. Capture the viewport revision, resolve page factories outside the control lock, then install only if the session/revision still matches. Allocate/register lazy worker jobs under the control lock, then start outside it. All stage work runs through the one existing `translateSinglePage` call with MANUAL lease origin, captured reader stream factory and live priority source. Inject worker/native/disk dispatchers; tests control the worker dispatcher with `StandardTestDispatcher`.

Add optional trailing `readerStreamFn` and owner-neutral live request-priority source parameters to `TranslationExecutor.translateSinglePage`; update its seven explicit fake implementation files and the pipeline override in the compiling migration commit. Preserve current defaults for other callers. Final `TranslationExecutor` exposes one reader execution entry; its separate public auto prepare/translate methods are removed after old auto callers retire. Internal guarded preparation helpers remain shared by the runner.

| Event | Queue transaction | Execution consequence |
| --- | --- | --- |
| Auto OFF, tap N | Add explicit demand for N, priority CURRENT_PAGE. | One common runner; no N+1 demand. |
| Auto ON, viewport N | Resolve actual N and N+1; add viewport demands; promote existing N; prune only speculative demands outside that window. | Two bounded automatic operations; foreground pending work chosen before prefetch. |
| Swipe to running prefetched N+1 | Update demand/priority token and remove obsolete old viewport demand. | Same parent Job, operation, lease and provider/native work; zero restart. |
| Tap an already running viewport page | Add explicit demand and promote compatible work. | Future auto/window prune cannot cancel that explicit request. |
| Incompatible force/config/source request | Record one replacement attempt behind the currently owned generation; cancel/drain or settle it according to explicit force semantics. | Never merge a forced retry into stale results or silently discard it as a duplicate. |
| Auto OFF | Remove automatic demands; abandon speculative operations with no explicit demand. | Existing committed display and explicit manual jobs survive. |
| Chapter/session switch | Invalidate old token and resolver callbacks first; abandon old work; await bounded control drain and retain actual native drain handle if outstanding. Full workflow teardown awaits that handle before store/engine disposal and before completing its existing acknowledgement APIs. | No stale publication; no premature engine/session disposal. |
| Provider/native wait promotion | Update the live priority source and wake/reprioritize the same WAITING admission entry under the gate lock; grant is the non-preemptive selection boundary. | Existing selected native/HTTP work is not cancelled or charged again. |

Already display-ready pages are skipped using the existing projection unless an explicit force retry asks for work. A fulfilled viewport demand does not manufacture another translation on every scroll callback. Terminal outcome retention is bounded (reuse the current 32-outcome cap); automatic queue membership is bounded by N/N+1, and explicit pending capacity is finite with typed rejection when full.

Bind the existing `TranslatorComputeClass` with the admitted configuration. For REMOTE_IO, two page operations can overlap network/native work. For LOCAL_COMPUTE, retain a single common reader execution permit around the entire page runner, replacing the old coordinator's compute semaphore without retaining its channels. A compute-class/configuration epoch change invalidates compatibility and drains active reader operations before rebinding; it cannot leave a remote operation bypassing the permit while a local generation runs. Batch/native/device budgets remain separate protected constraints.

The source caller audit in the reader-queue document supplies the exact manager/controller/ViewModel methods and old fields to remove. Any deviation from these shared contracts must be reconciled before implementation, rather than leaving two conflicting API designs.

# What is and is not a purge

| Category | Decision |
| --- | --- |
| Declaration-only private functions and test-only orphan helpers | Remove exact verified islands; preserve live descendants. |
| Disconnected adaptive/shared-cell renderer | Recommended removal as one coherent inactive island, after unchanged active planner/output baseline. Keep active `TextLayoutPlanner` and `CollisionRelaxation` code. No rendering algorithm replacement in Step 1. |
| One-caller under-100-line policy | The exact requested name-pattern scan found two files: co-locate the sole-caller `BatchResumePolicy` with its pure gate, keeping its function/body; retain shared two-caller `MemoryPressurePolicy`. No arbitrary inlining quota. |
| Old auto coordination and mirrored reader UI state | Delete after their behavior has replacement queue/projection coverage; these files are active at the baseline and are not baseline dead code. |
| Tests of retired auto handoffs | Merge/rewrite their useful invariants into shared queue/ownership tests before deleting their old implementation-specific files. |
| Callback-heavy batch tests | Keep. Constructor shape is not evidence of brittleness; batch execution is not being refactored here. Narrow hash-callback suspension requires appropriate importer/fake updates only. |
| Tests with sleeps/default dispatchers/timeouts | Classify as candidates, not proven flakes. Virtualize control-plane scheduling with injected dispatchers/time; retain dedicated physical-thread/native-exit probes. |
| Store, journal, display and active layout tests | Keep as regression boundaries; update imports/API fakes only where the changed surface requires it. |
| OCR/LiteRT internals and dormant ONNX backend ladder | Defer retirement; this step changes the orchestration boundary, not model execution or fallback internals. |

# Primary inventory / accounting rules

Measured source-file scopes:

| Scope | Files |
| --- | --- |
| `app/src/main/java/eu/kanade/translation` Kotlin/Java | **290** |
| Same directory, all regular files | 291 (includes one non-source file) |
| `app/src/test/java/eu/kanade/translation` Kotlin/Java | **318** |
| `app/src/testDebug/java/eu/kanade/translation` | 1 |
| `app/src/androidTest/java/eu/kanade/translation` | 6 |

The first production manifest's 296 counted the six domain translation files in addition to the requested 290-file app scope. Keep these scopes separate. Fixtures are source files, not individual test cases. Method deletion, package moves and file renames do not reduce file count.

The chosen final forecast below counts whole-file removals minus genuinely new files across both the translation subtree and affected reader UI paths. It does not claim a measured build-time gain or 0% future flakiness. Expected compiling cleanup outcomes must be checked against the final queue caller audit; temporary migration adapters are removed before declaring Step 1 complete.

## Whole-file deletion / consolidation table

| Exact repository path | Action / replacement | File delta |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecovery.kt` | Delete test-only orphan with its test. | −1 production |
| `app/src/main/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlanner.kt` | Delete disconnected rendering island, paired with exact TLP/CollisionRelaxation member purge. | −1 production |
| `app/src/main/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlanner.kt` | Same coherent island purge. | −1 production |
| `app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumePolicy.kt` | Co-locate private pure object in `BatchResumeGateDecider.kt`; retain body and gate tests. | −1 production |
| `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` | Delete after common queue replaces registration, scheduling, stop/drain and tracing. | −1 production |
| `app/src/main/java/eu/kanade/translation/scheduling/AutoWindowState.kt` | Delete auto slot/window snapshot hierarchy after projection/queue migration; do not recreate it under a new name. | −1 production |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationUiState.kt` | Merge useful UI mapping into existing shared page/reader projection callers; delete mirrored auto DTO/state hierarchy. | −1 production outside translation subtree |
| `app/src/test/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecoveryTest.kt` | Delete with retired orphan helper. | −1 test |
| `app/src/test/java/eu/kanade/translation/engines/rendering/AdaptiveBandPlannerTest.kt` | Delete with disconnected rendering island. | −1 test |
| `app/src/test/java/eu/kanade/translation/engines/rendering/MaskTextRegionPlannerTest.kt` | Same paired purge. | −1 test |
| `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTest.kt` | Merge preserved safety/deferral/display cases into new reader queue tests; retire old configurable-window/channel policy. | −1 test |
| `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTraceTest.kt` | Merge into common queue/trace tests; one operation trace with one terminal. | −1 test |
| `app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt` | Merge duplicate-registration/session fencing into queue tests. | −1 test |
| `app/src/test/java/eu/kanade/translation/scheduling/AutoWindowStateTest.kt` | Replace with N/N+1 priority/prune and existing display-readiness coverage. | −1 test |
| `app/src/test/java/eu/kanade/translation/workflow/TranslationManagerAutoArbitrationTest.kt` | Merge manager/session admission and same-page promotion coverage. | −1 test |
| `app/src/test/java/eu/kanade/translation/coexistence/ReaderManualPreemptsAutoLeaseTest.kt` | Replace revoke-and-restart assertion with same-job/lease/no-duplicate-work promotion test. | −1 test |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationUiStateTest.kt` | Merge useful copy/readiness cases into existing overlay/P5 projection tests. | −1 test outside translation subtree |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationLifecycleTest.kt` | Merge N/N+1 demand, session invalidation and stream teardown coverage. | −1 test outside translation subtree |

**New production files:** `app/src/main/java/eu/kanade/translation/scheduling/ReaderTranslationQueue.kt`, `.../pipeline/execution/PageNativeOperation.kt`, `.../model/SinglePageOutcome.kt` (**+3**). All helper types are co-located with these owners or existing estimator/governor files.

**New test files:** `app/src/test/java/eu/kanade/translation/scheduling/ReaderTranslationQueueTest.kt`, `.../pipeline/SinglePageNativeOwnershipTest.kt`, `.../architecture/TranslationPackageBoundaryTest.kt`, `.../pipeline/batch/SourceFingerprintCacheTest.kt` (**+4**).

**Move/rename, net zero:** telemetry/normalization files move to diagnostics; rename stateful `ReaderAutoTranslationPageResolver.kt` to `ReaderPageWorkResolver.kt` after removing old auto owner-version coupling. Keep its stream invalidation/generation guards. Keep `TranslationScheduler.kt` as a collapsed compatibility facade, not a second scheduler implementation. Keep `PreparedPageBoundary.kt` guarded pure helpers.

| Forecast scope | Removed | Added | Net |
| --- | --- | --- | --- |
| Production including affected reader UI | 7 | 3 | **−4** |
| Unit tests including affected reader UI | 11 | 4 | **−7** |
| Combined | 18 | 7 | **−11 files** |
| Requested translation production subtree | 6 | 3 | **290 → 287** |
| Requested translation unit-test subtree | 9 | 4 | **318 → 313** |

Every test-file deletion is after its paired helper retirement or useful replacement coverage. No test is deleted because it failed or uses callbacks. Method-level removals, unchanged XML preference keys, source-package moves and test-method consolidation add **zero** to the file reduction.

The production manifest contains the exact TLP/CollisionRelaxation dead-member graph and the sole-caller policy table. Optional method-only OCR and persistence candidates are explicitly deferred to honor this step's ownership constraints. The root's chosen actions/counts take precedence over earlier manifest drafts; compile/reference/parity gates can veto an item, in which case record the changed forecast rather than silently compensating with another deletion.

# Preservation and release gates

- `ChapterTranslationStore` remains the live state, lease, generation, version and guarded publication authority; no new mutable store replicas.
- Existing committed display survives cancellation, stale candidate, failed forced refresh and rejected persistence.
- Native quarantine, engine guards and actual completion precede native resource/session disposal.
- Every compatible promotion preserves operation/job identity and native/provider work already performed.
- N HTTP and N+1 native overlap is demonstrated; native occupancy remains one and operation/memory bounds remain respected.
- Diagnostic wording does not control presentation actions/severity; engine and presentation implementation imports from pipeline are zero.
- Test deletion requires retired-helper evidence or equivalent replacement coverage; no failed test is simply removed to make the gate green.
- Pure planner/store/journal/layout/display tests pass on each commit; native lifecycle, real-thread and reader/batch integration outcomes pass at their affected phase gates.
- Benchmark the changed reader ordering on reference Snapdragon hardware before rollout. Same-page HTTP delay introduced by waiting for inpaint is an explicit tradeoff to assess, not an unexplained regression allowance.
- Coordinate with OCR/LiteRT owner through a review of engine admission/teardown and captured engine interfaces. No model weights, tensor shapes, detector predicates, recognizer algorithms or backend fallback changes are included.

# Relation to earlier plans

The [earlier maintainability plan](../maintainability-refactor-plan) remains the source for batch capability refactoring, persistence initialization, native correctness foundations and later measured change-isolation goals. This Step 1 plan takes precedence for reader scheduling, native-before-HTTP ordering and retirement of the old auto coordinator/slot UI. Its outcome/package and fingerprint tasks are pulled forward as specified by the user. The earlier preflight review remains evidence of baseline liveness, not a prohibition on deleting active code once replaced by the new flow.
