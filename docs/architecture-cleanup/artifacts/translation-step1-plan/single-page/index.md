---
title: "Step 1: registered single-page native work and stable block joins"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Baseline and scope

Plan only at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. No source, tests or build changes have been made. This document specifies the reader execution changes required by the user's new single-queue behavior. Batch callback bundling, store initialization, journals, model conversion and OCR algorithms are outside this step.

**Scheduling change authorized for this plan:** local recognition and inpainting finish before this page enters HTTP. Page N's HTTP can overlap page N+1's local preparation. This changes the old manual same-page HTTP/inpaint overlap; it is not claimed performance-neutral. Measure current-page latency and cross-page throughput before release. Keep the existing device rendering gate separate from native admission.

# Exact file edits

All production paths below start at `app/src/main/java/eu/kanade/translation/`.

| File | Exact change | Retained behavior |
| --- | --- | --- |
| `pipeline/execution/PageNativeOperation.kt` **new** | Co-locate the operation holder, registered inpaint slot, allocation ownership and native lifecycle hooks. No public `Deferred<Bitmap?>` handoff. | One owner per allocation; a bitmap can be disposed only after its actual producer/borrower exits. |
| `pipeline/execution/NativeRunQuarantine.kt` | Add an **internal `runRegistered` overload** and private shared kernel with lifecycle hooks; keep the public generic `run` signature unchanged. Record abandonment **before** its cancellation/timeout drain. Invocation `finally` drains owned child work and finalizes resources under `NonCancellable`, then completes `exited`. Observer release and admission unlock happen afterward. Keep `minusKey(Job)` and independent scope. | Existing generic `run` callers and batch behavior stay source-compatible. Internal lifecycle types are not exposed by a public Kotlin API. Cancellation still does not prove JNI completion. |
| `pipeline/EngineLane.kt` | Thread optional lifecycle into `withNativeLane`; retain rebuild mutex, quarantine and buffer-disposal gates. Capture the recognition engine used by the invocation before launching the inpaint child. | `RoiPageRecognitionEngine.nativeGuard` remains a `Mutex`; no split locks, first-class backend migration or OCR internals. |
| `pipeline/TranslationPipeline.kt` | Create `PageNativeOperation` before native admission. The common reader boundary awaits registered inpaint completion **inside** native admission, then releases native admission, drains required guarded storage tails, publishes required OCR/cleaned metadata, and enters HTTP. Capture request Job before the quarantine strips it. Keep a single reader page lease for the entire operation. | Resume, textless, forced retry, native stall, provider pause, durable-terminal-over-timeout precedence and every existing write fence. |
| `pipeline/SinglePageOnnxPhase.kt` | Pass the operation into reader-only processing; replace detached launch at `:1182` with reserved-slot → lazy child → bind → start. Replace the `:1259` checkpoint with explicit operation/caller eligibility. Move current fresh inpaint color recompute (`:1203`) out of this stage. Remove `OnnxPhaseResult.inpaintJob` after all reader callers migrate. | Batch `analyzePage`, `inpaintPage`, downscaled retry and engine algorithms remain. Source allocation recycling is transferred into the slot contract, not duplicated in two `finally` blocks. |
| `pipeline/SinglePageHttpRenderPhase.kt` | Remove late `inpaintJob.await` (`:662`) and detached-job cleanup (`:1068`). Receive an already prepared operation-owned image/durable reference. Translate an isolated working copy, then merge translations and render styles by stable work keys. Sort private provider/render lists, never a list shared with native work. | Existing provider protocols, request retry budget, partial retries, epoch retry, user edit policy and guarded final commit. |
| `engines/rendering/RenderColorEstimator.kt` | Add a pure style-result API in this existing file. Keep `recomputeFor` as a synchronous compatibility adapter for existing batch consumers; migrate reader calls to the result API. | Current color sampling algorithm, parent-region sampling, layout-owned stroke width and active batch render semantics. |
| `pipeline/CleanedPublication.kt` and `persistence/chapter/CleanedImagePublisher.kt` | Minimal publication-contract safeguard: record the authoritative accepted/rejected receipt inside the commit decision; prohibit cancellation cleanup from deleting an accepted/referenced image. This is publication surface repair, not store/journal decomposition. | Candidate/committed separation, previous display image retention, atomic artifacts and existing `PatchPrecondition` validation. |

Resume-only rendering currently lives in `SinglePageOnnxPhase.renderResumedPage` / `resumeInpaintAndRender`. Move the **render tail** to the common HTTP/render stage, with a no-provider branch when translation is reusable. Keep native OCR/inpaint work in native admission; keep disk publication outside it. A page already display-ready returns Completed without a fabricated native admission. Never reinterpret a null result as successful preparation without checking terminal store truth.

# Native operation contract

Proposed signatures, to be implemented together with the invocation tests:

```kotlin
internal class PageNativeOperation(
    val callerJob: Job,
    val fence: ReaderPageFence,
) : NativeInvocationLifecycle {
    fun ensureCallerEligible()
    fun reserveInpaint(source: Bitmap): RegisteredInpaint
    fun abandon(reason: NativeAbandonment)
    suspend fun awaitInpaintInsideInvocation()
    fun claimCleaned(): OwnedCleanedBitmap?
    override fun onAbandoned(reason: NativeAbandonment)
    override suspend fun finalizeAfterNativeExit()
    suspend fun disposeAfterOwnerExit()
}

internal class RegisteredInpaint {
    fun bind(job: Deferred<Unit>)
    fun offer(result: NativeInpaintResult)
    suspend fun finishProducerAndDisposeInput()
}

// Co-located in NativeRunQuarantine.kt; optional for existing generic callers.
internal interface NativeInvocationLifecycle {
    fun onAbandoned(reason: NativeAbandonment)
    suspend fun finalizeAfterNativeExit()
}

// Member of NativeRunQuarantine; existing public run delegates with lifecycle=null.
internal suspend fun <T> runRegistered(
    chapter: String,
    pageKey: String,
    timeoutMs: Long,
    lifecycle: NativeInvocationLifecycle,
    onTimeout: suspend (Long) -> Unit = {},
    block: suspend CoroutineScope.() -> T,
): Outcome<T>
```

`ReaderPageFence` is an operation-local capture of the existing store identity, generation, lease token and `PatchPrecondition`; it is **not** an independently mutable store or replacement fence authority. The holder stores the receipt returned by an accepted guarded write to advance its local expected precondition. It cannot synthesize new write authority by re-reading a newer owner's snapshot.

The source checkpoint uses `callerJob.ensureActive()` plus holder abandonment and applicable lease/generation checks. Timeout explicitly abandons even when the caller Job is still active. A request lost while waiting for admission does no decode/native work. An abandoned request before launching inpaint disposes the decoded input after recognition has actually exited and skips the new stage.

## Registration before launch

```kotlin
operation.ensureCallerEligible()
val engineForThisInvocation = engines.recognitionEngine
val privateInpaintDraft = pageTranslation.detachedCopy()
val slot = operation.reserveInpaint(decoded.bitmap) // registered first
val child = invocationScope.async(nativeDispatcher, start = CoroutineStart.LAZY) {
    try {
        operation.ensureCallerEligible()
        val cleaned = engineForThisInvocation.inpaint(decoded.bitmap, privateInpaintDraft)
        slot.offer(classifyInpaintResult(cleaned, privateInpaintDraft, erasePlanEvidence))
        // synchronous ownership recording, before await delivery
    } finally {
        withContext(NonCancellable) { slot.finishProducerAndDisposeInput() }
    }
}
slot.bind(child)
child.start()
```

`invocationScope` is the scope of the quarantine invocation, not a new independent `CoroutineScope(Dispatchers.Default)`. Reserve/bind/start are non-suspending ownership operations. A child cancelled before its body begins does not execute body `finally`; the registered slot and invocation finalizer must handle that case. The producer catches and records output ownership before any cancellable return/await boundary. `Deferred<Unit>` communicates completion only; the bitmap stays in the slot.

Do not start provider work yet: the invocation awaits this child before exiting. This is now compatible with the user-required HTTP-after-preparation flow. The old local `coroutineScope { async { ... } }` serialization warning referred to preserving same-page manual overlap; the new requested ordering supersedes that objective for reader work.

`awaitInpaintInsideInvocation()` collects the child's outcome, and the invocation's `finally` explicitly executes `withContext(NonCancellable) { registeredChild?.join(); finalizeAfterNativeExit() }` before `exited.complete(Unit)`. Finalization cannot recycle the input before this join. Joining a cancelled-before-start child also permits disposal of its reserved input even though its body never ran. A cancelling job is not equivalent to a completed job; a synchronous JNI call keeps its executing child incomplete until it returns. Do not assert that coroutine cancellation alone completes that child or releases the lane. If an engine delegates to another asynchronous native task, its physical completion acknowledgement must be included in the join contract; coroutine completion alone would then be insufficient.

## Allocation and result states

| State | Owner and permitted action |
| --- | --- |
| Reserved, child not started | Slot owns decoded input; cancellation/failure disposes it if no producer used it. |
| Producing | Invocation owns input and child. Abandonment marks intent; it does not free memory used by native code. |
| Available | Slot owns cleaned output. Live request may claim once; abandoned request waits for producer exit, then disposes. |
| Claimed | Reader operation owns output. Native finalizer cannot recycle it. Encoding/render borrow completion must precede owner disposal. |
| Abandoned awaiting exit | No future claim/publication; actual child/invocation exit still gates input/output cleanup and admission release. |
| Disposed | Terminal; no further access or duplicate recycle. |

Check allocation identity: if an engine returns the same bitmap as its input, the slot transfers that allocation to output ownership rather than recycling it as input. Track distinct allocations by identity, not object equality. Do not introduce general reference counting.

Co-locate `NativeInpaintResult` in the operation file and classify without changing engine algorithms:

| Engine result and evidence | Typed result / allocation decision |
| --- | --- |
| Non-null distinct cleaned allocation | `Cleaned(bitmap)`; dispose input only after producer exit and any source borrower ends; retain output for claim. |
| Non-null cleaned allocation identical to input | `Cleaned(bitmap)` with identity transfer; never recycle it as a separate input. |
| Null with verified empty erase plan and READY stage | `NoErase`; if a render-only path needs the original decoded background, transfer input to the render owner before cleanup. Otherwise use the existing source/durable display path and dispose input after exit. No downstream reference may point at a recycled `decoded.bitmap`. |
| Null with FAILED stage, memory deferral or unknown/nonempty erase evidence | `Failed`/`Deferred` as applicable; dispose input after exit, preserve committed display, and use existing retry/failure rules. Never turn null into a successful fallback merely because no cleaned bitmap exists. |
| Existing textless terminal path, no inpaint launched | Preserve its existing terminal/store decision. Release owned decode allocation after its borrowers exit; do not invent an inpaint failure or require rendering from that allocation. |

`PageInpaintingEngine:38` returns null for an empty erase plan, and its caught-failure path also returns null. The private draft plus existing erase-plan evidence distinguishes these cases. Merge its resulting stage/degradation/attempt metadata under the operation's existing fences. `NoErase` is not proof that all OCR blocks are empty; detector-only masks can also exist. Tests must cover null success, null failure, alias, distinct output and textless terminal separately. The baseline already skips the detached launch for empty OCR blocks; a textless canvas crash is not established by the source.

Claim is a synchronous state transition performed in a narrow non-cancellable ownership section, immediately enclosed by an owner `try/finally`. Recheck caller eligibility before continuation; cancellation after claim disposes through that owner. A late accepted return discarded by quarantine is safe because the slot was registered independently of the return value.

Native finalization disposes abandoned outputs and source buffers before `exited.complete`, occupancy release and admission unlock. It **does not** recycle an available output belonging to a live operation. The outer operation finalizer handles abandonment after native exit, failed publication, HTTP cancellation and render failure. Engine buffer release stays inside the existing engine/quarantine synchronization rules.

Do not release the page lease while an outstanding native stage can still issue an authorized write. Mark the operation abandoned first; suppress subsequent publication; drain native exit; then release its exact lease. UI cancellation can clear transient queue intent immediately, but disposal/replacement cannot treat a 2 s join timeout as native completion.

# Preparation/publication/HTTP ordering

```mermaid
sequenceDiagram
    participant Q as Reader queue
    participant R as Common single-page runner
    participant N as Native quarantine
    participant S as Existing store/publication
    participant H as HTTP/render stage
    Q->>R: same execute request for tap or viewport
    R->>S: acquire reader lease; capture fence
    R->>N: registered invocation
    N->>N: decode, recognize, registered inpaint; await actual exit
    N-->>R: release native admission; image remains operation-owned
    R->>S: drain required writes; guarded OCR/cleaned publication
    R->>R: verify request + lease + generation + publication receipt
    R->>H: provider work outside native admission
    Note over Q,N: next page may now run native preparation
    H->>H: merge stable-key results; render under device gate
    H->>S: guarded candidate/committed publication
    R->>R: dispose operation allocations
    R->>S: release exact reader lease
```

Encoding can borrow an owned cleaned image after native admission ends. If the common path uses a durable `PreparedPage` internally, persist/recycle first and load at render entry. No bitmap is retained in the reader queue or passed through a separate producer/consumer channel. Keep required source/OCR fingerprints and candidate version gates on the internal handoff. Do not delete the existing `PreparedPageBoundary` helpers merely because the external auto API disappears.

The render retry that invokes downscaled inpainting must re-enter registered native admission and capture an engine there. It cannot call a native retry untracked while HTTP/render sits outside the native lane. Canvas composition uses `DevicePagePermitGate`; that is not a reason to reacquire native quarantine for non-native rendering.

# Stable block identity and join

`TranslationBlock.blockId` is nullable (`model/PageTranslation.kt:303`), so an ID-only map is not sufficient for legacy pages. Coordinates/text can also be duplicated. Do not merge by the array index **after** provider sorting.

Define operation-local work keys once, before sorting:

```kotlin
internal data class ReaderBlockKey(
    val operationId: Long,
    val sourceOccurrence: Int, // frozen once; never recalculated after sorting
    val ocrIdentity: String,
)
internal data class ReaderBlockTranslation(
    val key: ReaderBlockKey,
    val translation: String,
    val userEditedAt: Long?,
)
internal data class ReaderTranslationResult(
    val translations: List<ReaderBlockTranslation>,
    val retainedKeysInReadingOrder: List<ReaderBlockKey>,
)
```

Co-locate these private reader join types/adapter in `SinglePageHttpRenderPhase.kt`. The key combines operation identity, frozen occurrence and existing `ocrFingerprint()` (which excludes translation/style/edits). Existing unique block IDs are retained as evidence, but do not change persisted nullable IDs or durable schema simply to create work keys.

1. Freeze source block work records and their keys at the OCR handoff. Deeply detach page/block/mask working data using existing helpers. Preserve existing committed display and user edits in the source evidence.
2. Create a **separate** private provider draft and map each provider block object to its frozen key. Provider sorting/filtering may reorder/remove those objects. Existing contextual `StableBlockIds` may assign private wire IDs; keep a sidecar association to original keys and restore original durable IDs when merging.
3. Keep existing translator interface and provider implementation behavior for this step. An adapter runs the mutating translator on its private draft and returns typed keyed results, including retained/suppressed block keys so `TranslationBlockFilters.removeWatermarkBlocks` semantics are preserved.
4. Reject missing/duplicate/unrecognized work keys or changed OCR identity rather than applying by current list position. Explicitly handle provider filtering; do not confuse an intentionally removed watermark with a lost result. Partial retry drafts inherit the same original key association; filtered/subset copies do not get fresh ordinal keys.
5. Join translation results into the operation-owned render draft. Recheck the source generation/version/lease and user-edit policy before publication; stale or conflicting results cannot update the store.
6. Run **one reader style pass at the joined render boundary**, using the actual cleaned background. The estimator receives immutable geometry plus a `shouldRender` flag determined by the join, not mutable translation blocks being written concurrently.
7. Re-run styles only when a downscaled retry actually changes the background bitmap. The second pass is for a different image, not unconditional duplicate computation.

### Provider protocol provenance

Keep a request-scoped association `(attemptId, envelopeId) -> provider draft block -> ReaderBlockKey`. Envelope IDs are scoped to each provider invocation: Google's `b0` is an index in that invocation's private draft, not durable identity; contextual `idToBlockIndex` similarly locates a block in its own frozen request. IDs may legitimately repeat across retries, so a globally bijective envelope-ID map would be wrong.

Before each `translateOnce`, sort/filter only its private draft, freeze its request order, and establish its object-to-key map. Do not reorder that draft while its provider call/parser is active. Existing provider builders/parsers can continue resolving their envelope IDs into that draft; the reader adapter then joins by object/key, not by the master page's position. If a retry creates copied block objects, construct their key association from the original selection **before** invoking the translator. Preserve a sidecar for any contextual `StableBlockIds.assign` rewrite and restore durable IDs only at merge. A filter or retry helper that replaces objects must return/pass the association explicitly; OCR-text/coordinate equality cannot recover duplicate occurrence identity.

Audit `engines/translator/providers/GoogleTranslator.kt:56–67` and `engines/translator/contextual/ContextualResponseParser.kt:318–320` with the HTTP adapter. Change those files only if the audit finds an order/object replacement that cannot satisfy this private-draft contract; no provider wire-format or translator-wide result migration is required merely to add the reader sidecar. Add end-to-end parser/adapter tests for duplicate geometry/null IDs, sorted draft, watermark removal and partial/epoch retries using differently ordered subsets. These tests must exercise real provider envelope application, not a test-local identity mapper.

Pure API co-located in `RenderColorEstimator.kt`:

```kotlin
data class BlockStyleInput(
    val key: String,
    val region: IntArray,
    val parentRegion: IntArray?,
    val shouldRender: Boolean,
)
data class BlockRenderStyle(val key: String, val textColor: Long, val strokeColor: Long)

fun estimateFor(cleanedBitmap: Bitmap?, inputs: List<BlockStyleInput>): List<BlockRenderStyle>
```

Copy geometry arrays when creating inputs; the estimator does not mutate them. Its public key is an opaque string mapped to `ReaderBlockKey` by the reader adapter, so engines acquire no dependency on pipeline types. Preserve the current skip condition (`translation.isBlank() && text.isBlank()`) when deriving `shouldRender`; changing that condition is not a cleanup. Stroke width remains layout-owned.

Remove the fresh concurrent style pass in `SinglePageOnnxPhase(:1203)`. Migrate its resume render pass (`:666`) when moving render tails. Replace the reader HTTP normal/retry passes (`:756/:875`) with this pure result API. Keep the batch compatibility call (`BatchRenderJoin:235`) synchronous on its existing owned draft; a batch immutability migration is deferred.

# Publication cancellation safety

`CleanedImagePublisher.publish` currently writes a new file, invokes a suspending commit, and deletes the new file on caught cancellation. The target must not delete a name whose guarded commit was accepted but whose caller did not receive its result.

- Execute the narrow commit decision and internal receipt recording under `NonCancellable`; retain every store fence.
- Store the authoritative receipt **inside** that section before returning to a cancellable context. Distinguish accepted live-memory reference from durable artifact acceptance in the memory-first path.
- `CleanedImagePublisher` remains filesystem-only. Add a conservative `mayDeleteNew: (String) -> Boolean = { false }` guard supplied by the store-owning `CleanedPublication`, using the existing `store.mayDeleteCleanedImage(pageKey, name)` reference protection. The publisher never obtains a direct store reference or invents write authority. Check that guard only after definite guarded rejection and record the discard decision within the narrow non-cancellable decision section. The unique newly generated name must not be reused or exposed to another writer before acceptance; if reference/exclusion certainty is unavailable, retain it.
- Accepted receipt always retains the new file, including a live-memory accepted reference awaiting durable completion. Definite rejection permits deletion only with the owner's discard guard. Any thrown exception/unknown outcome retains the file and records reconciliation evidence; it may already be referenced, so do not label it an unreferenced orphan. Keep it until existing owner-aware reconciliation can establish safe retirement.
- Encoding, network calls and the whole page flow remain cancellable; do not wrap them all in `NonCancellable`.
- Keep the previous committed display file until the existing retirement guard permits removal.

Add `CleanedImagePublisher.Result.UnknownCommit(name, failure)` for thrown/indeterminate commit outcomes, and update both `CleanedPublication` exhaustive result branches in C05. They retain the file, return typed persistence non-success, preserve any existing committed display and schedule only existing guarded reconciliation. Do not disguise unknown acceptance as definite Rejected. The operation stores an accepted snapshot/receipt inside the non-cancellable section, before result delivery; its fence advances only from that receipt. A late cancellation does not turn accepted memory state into an authorized delete. Existing tests must distinguish write failure, definite rejection, accepted publication and unknown commit.

This minimal surface safeguard is required by operation ownership. It does not extract state, change journal mechanics or loosen the store mutex/lease/version rules.

# Required regression gates

## Priority is separate from lease origin

The baseline common runner hardcodes `withProviderRequestPriority(INTERACTIVE)` and derives native priority from `PageWriteOrigin.MANUAL`. Those expressions must change when auto shares the MANUAL reader lease origin; otherwise prefetch receives foreground admission everywhere.

Add an optional owner-neutral priority source to the common execution request. Queue CURRENT_PAGE maps to foreground native/device priority and INTERACTIVE provider priority; PREFETCH_NEXT maps to background. A constant foreground source remains the default for existing manual callers during migration. Store provenance/lease ownership never changes on promotion.

For a provider request already waiting, reading priority only once at HTTP entry is insufficient. Co-locate a readonly `ProviderPrioritySource` (`StateFlow<AdmissionPriority>`) in existing `engines/translator/ProviderRequestGovernor.kt`. The existing priority context can wrap either a constant source or this live source. `admit` captures the context source; waiter selection uses its current value and wakes on source changes as well as quota delay/release. Keep the same waiter sequence/request key and do not reserve/charge again. Observe a version/value before evaluation and wait for a different value so a promotion between evaluation and subscription is not lost.

Already admitted HTTP calls continue on the same permit; promotion does not cancel transport or refund/re-charge usage. Old callers without a live source keep the existing metadata priority. Engines depend only on their own priority contract, never a pipeline/reader queue class. Extend the existing governor fairness/headroom tests for promotion of a queued waiter without a second paid call.

Native promotion must use **the same waiter**, rather than cancellation/reacquisition. Extend existing `DevicePagePermitGate.kt` with a live `DevicePermitPrioritySource`/priority context and a lock-protected reprioritization operation. While WAITING, mutate effective priority and select by current priority then original sequence. GRANTED and CANCELLED transitions are terminal for reprioritization; promotion racing grant changes future request priority but never releases an already granted permit or restarts execution. Cancel the source observer after grant/cancellation and drain it outside the gate lock.

The baseline has two ordering layers: `TranslationPipeline.withNativeLane:312` first obtains a device permit, then quarantine obtains its mutex. Updating only one can leave priority inversion in the other. In C08, introduce a **fixed-capacity-one priority admission gate inside the quarantine kernel**, reusing the existing gate implementation and the request priority context for all generic/registered calls. Keep the quarantine mutex as the lifecycle exclusion guard for `tryRunExclusive`; acquire it only after the native priority permit is granted. No arbitrary page request queues directly on that mutex before priority selection. Its lock remains held until actual invocation/finalizer completion, and the native priority permit is released afterward.

Move the common pipeline's device-memory permit acquisition **inside** the selected quarantined invocation, before engine work. This removes speculative jobs holding an upstream device permit while waiting for native selection. Lock/resource order is native priority admission -> quarantine lifecycle exclusion -> device-memory permit -> engine guard. Rendering borrows the device-memory gate separately and must release that permit before a retry re-enters native admission; no reverse edge is permitted. Existing batch callers use constant background sources and the same kernel, preserving exclusivity and quotas. An already selected invocation is non-preemptive even if it is waiting for a device budget; priorities choose among not-yet-selected requests only.

Test promotion racing grant, queued foreground versus older prefetch, batch contention, lifecycle `tryRunExclusive`, render/device pressure and cancellation releasing each permit exactly once. This is an orchestration/admission correction; no engine lock splitting or model execution change. Gate cancellation of a GRANTED waiter already returns its permit in the baseline; that accounting is not itself evidence of a bug.

The extra method/field edits are in existing `TranslationExecutor.kt`, `TranslationPipeline.kt`, `DevicePagePermitGate.kt` and `ProviderRequestGovernor.kt`; they add no standalone policy/adapter files.

## Observable ownership checks

| Gate | Required observable result |
| --- | --- |
| Cancel before lazy start | No inference; source recycled once; reserved slot disposed; no leaked deferred handle. |
| Cancel during recognition | Explicit caller token rejects further stages; input disposed after native exit; no later result publication. |
| Cancel during inpaint | Actual producer exit precedes resource/session/admission release; output recycled once and no stale commit. |
| Cancel at output offer, native return and claim | Every race chooses exactly one owner or disposal path; no dropped raw-bitmap delivery. |
| Native timeout while caller remains active | Holder abandoned independently of Job cancellation; next native invocation starts only after actual exit. |
| Chapter replacement / teardown / engine close | Existing generations and leases fence late output; engine guards protect native use until drain. |
| Live output after native exit | Output remains available until claim or explicit abandonment; finalizer does not prematurely recycle it. |
| HTTP N / native N+1 | Native admission is free while N's HTTP waits; native exclusivity remains one. |
| Bitmap alias result | Input/result alias transfers ownership; no double recycle or use-after-recycle. |
| Null inpaint result | NoErase and failure are distinct; retained original background has an explicit owner; textless terminal never draws a recycled decode allocation. |
| Sorted, duplicate and legacy-null-ID blocks | Translation/style results join to the same frozen source identities regardless of order. |
| Provider filtering / partial retry / epoch retry | Intended retained/suppressed keys and edits survive; missing or stale results fail closed. |
| Accepted commit followed by caller cancellation | Referenced image survives; rejected unreferenced candidate is eligible for cleanup. |
| Forced refresh fails | Existing committed display remains visible; no failed candidate promotion. |

Extend existing `NativeRunQuarantineTest`, `scheduling/PreparedPageBoundaryTest`, `scheduling/TranslationPipelineConcurrencyTest`, `persistence/chapter/CleanedImagePublisherTest` and `engines/rendering/RenderColorEstimatorLayoutSamplingTest`. Add one `pipeline/SinglePageNativeOwnershipTest.kt` for holder/allocation race matrices; keep keyed-join cases in `TranslationPipelineConcurrencyTest` unless fixture isolation makes a separate file materially simpler. Use controlled dispatchers/gates for coroutine ordering; retain a small real-thread native-surrogate gate where the property is physical completion despite requester cancellation.

Emit the existing stage events at actual entry: READING -> CLEANING (when needed) -> TRANSLATING -> RENDERING. During native preparation, current-page UI uses these existing stage projections and keeps committed output visible during refresh. Waiting for abandoned prior native work records a diagnostic wait reason in the existing trace/wait span; it does not fabricate a CLEANING stage before entry or add another durable lifecycle. No device baseline supports a 0.5–2.0 second inpaint delay estimate in this audit. Measure time to HTTP dispatch and end-to-end latency in C00/C11; the ordering tradeoff remains explicit and unmeasured.
