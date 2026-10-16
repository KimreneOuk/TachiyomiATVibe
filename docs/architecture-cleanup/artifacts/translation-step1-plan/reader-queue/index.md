---
title: "Step 1 reader queue: caller migration, priority and lifecycle contracts"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

# Scope and authority

Plan only, source inspected at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. No production edits or tests executed. The primary completed this caller map after the assigned auditor repeatedly ended without publishing its document. The [master queue API](../index.md#unified-reader-api-contract), [native ownership spec](../single-page) and [commit gates](../commits) remain authoritative; this document supplies integration details rather than a competing API.

## Source-to-target manifest

Paths in this table are repository-relative. Line anchors describe the baseline, not proposed code.

| File | Baseline seam | Exact target disposition |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/scheduling/ReaderTranslationQueue.kt` | New | Own deduplication, two reader priorities, demand changes, bounded pending work, operation registration and request outcomes. All queue-only types stay in this file. |
| `app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt` | `translatePage:312`, job map:87, outcomes:95; auto owners:111–137; window methods:152/235/250/268 | Keep a compatibility facade; delegate reader execution to one queue. Move job/outcome ownership into the queue. Delete owner versions, retiring coordinators, auto snapshot flow, `beginAutoCancellation`, `performAutoCancellation`, `finishAutoCancellation`, `retireLocked`, `shutdownRetiringCoordinator` and their owner/cancellation types after cutover. Chapter cancellation epochs remain until their equivalent session fencing is demonstrated. |
| Same scheduler | `markChapterCancelledAsync:538`, sync bridge:566, `cancelPageTranslation:623`, `cancelPageTranslations:657`, `cancelAllPageTranslations:708`, `awaitReaderStop:744` | Preserve store-fenced cancellation and completion ordering behind suspend facade methods. Remove blocking sync bridges once callers migrate. Never clear a successor attempt using the cancelled predecessor's page key alone. |
| `app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt` | `updateWindow:197`, `cancel:281`, `shutdown:297`, `awaitTermination:318`; `runCoordinationLoop:419`, prepared channel:425, consumer:507, reconcile:766 | Delete whole file after queue parity. Remove the preparation/translation producer-consumer channels, slot registries, admission/completion sets, reprepare/paused maps, predecessor coordinator handoff and separate traces. Keep source/memory/provider deferral semantics as queue policy and existing typed provider outcomes. |
| `app/src/main/java/eu/kanade/translation/scheduling/AutoWindowState.kt` | `AutoChapterIdentity:22`, `AutoSlotState:52`, bounds:103, snapshot:182 | Delete file and its auto-specific state hierarchy. Reader session tokens replace coordination identity only. Durable readiness continues through existing projections; no renamed slot state machine. |
| `app/src/main/java/eu/kanade/translation/workflow/TranslationManager.kt` | Scheduler construction:223; `openTranslationSession:1911`; auto public facade:1932–1994; manual facade:2135 | Keep session/store opening and session coordinator admission. Replace auto window APIs with the queue submission/viewport/toggle facade described below. Manual and viewport submissions receive the same admitted reader token/store. Keep whole-chapter BATCH suppression including paused queue entries; never bypass it by treating prefetch as MANUAL lease origin. |
| Same manager | `onMemoryPressure:754`, rekey cancellation:1681, `switchReaderSession:369`, teardown facade:742–746 | Reconcile only still-demanded queue entries after memory/source/provider events. Rekey and session replacement invalidate the old token before cancellation. Replace auto-only cancellation with demand removal or full session cancellation according to the caller's existing intent. Preserve ordered rekey/store admission. |
| `app/src/main/java/eu/kanade/translation/workflow/ReaderTeardownCoordinator.kt` | `requestReaderStop`, `awaitReaderStop`, `translatePage`, chapter/all cancellation | Keep as workflow lifecycle owner. Delegate request abandonment to the queue, then await actual drain before store unregister/engine teardown. Preserve clear-transient-state-before-defunct ordering and same-chapter BATCH store exclusion. No fire-and-forget replacement for completion acknowledgements. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt` | `handleAutoTranslation:375`, IO handler:416, source-ready binding:473/497, snapshot observer:550, reset:622, auto-current trigger:643 | Replace with shared reader viewport/demand handling. Delete `observeAutoSnapshot` and accepted owner/window version projection logic. Retain source-ready callback generation checks and invalidation before resource recycling. Make the scroll callback update the desired viewport immediately; async store/source preparation must not drop the latest viewport just because an earlier scroll job is active. |
| Same controller | `translateSinglePage:815`, batch-reader confirmation:848, admitted manual path:870, submissions:953/980/992 | Keep admission dialog and force-from-failed-stage behavior. Replace all three manual dispatch branches with `submitCurrent` for the captured session, including cached bytes, cancellable online download, and downloaded-disk fallback. A late online download must revalidate the session/config/page identity before registration or submission. |
| Same controller | `cancelTranslationForChapter:649`, delete/reset paths, `cancelSinglePageTranslation:1032`, `stopAllTranslation:1048` | Use one session/page cancellation path. Invalidate resolver and source callbacks synchronously; await workflow teardown before releasing old chapter resources. Preserve committed display on ordinary cancellation/failed refresh. Explicit delete/reset still clears display according to existing semantics. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationPageResolver.kt` | `bind`, `bindOwnerVersion`, `invalidate`, `Binding.resolve`, issued stream/lifecycle gate; canonical key helper | Rename to `ReaderPageWorkResolver.kt`, net zero files. Return `ReaderPageWork` with reader index, not `RollingAutoCoordinator.PageWorkItem`. Remove auto owner-version tracking; bind to reader session token and existing store identity. Keep captured stream factories, page-list replacement behavior, original/cache/disk source selection, canonical filename preference and invalidate-before-open gate. Rename source-ready predicate to shared reader semantics; allow manual deferred demand to wake even when auto is off. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | Auto UI flow:163; fields:356–389; scroll job:1162; state field:1681 | Remove auto snapshot/UI collectors, auto identity, accepted owner/window version fields and `State.autoTranslation`. Keep one reader session generation, shared resolver/readiness callback lifetime and existing live/batch store observations. Do not remove `observePageView:1811` or its model/holder consumers. |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderAutoTranslationUiState.kt` | DTO:22, slot:60, state:82, projection:98, acceptance predicates | Delete after existing overlay/P5 tests absorb relevant copy and stale-session cases. No replacement auto DTO hierarchy. |
| `app/src/main/java/eu/kanade/translation/presentation/TranslationUiTruth.kt` | `forAutoSlot:125` and auto-state import | Remove auto-slot mapper. Derive current/next readiness from PageView/display projection and typed request outcomes. Keep safe deferral/retry copy in ordinary outcome projection. |
| `app/src/main/java/eu/kanade/translation/workflow/TranslationProgressProjection.kt` | PageView projection/observation | Keep unchanged semantic model. Queue intent does not replace committed display truth. |
| `app/src/main/java/eu/kanade/translation/pipeline/execution/TranslationExecutor.kt` | Single runner:38; `prepareSinglePage:74`, `translatePreparedPage:107` | Add trailing optional reader stream factory/live priority source to the common runner, preserving defaults. Remove the separate public prepared-page reader APIs only after old coordinator and fake callers retire. Keep internal guarded preparation helpers. |
| `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt` | Fixed INTERACTIVE priority:505/519; native scope:587; auto methods:854/1069 | Replace origin-based fixed foreground priority with a live reader priority source. Common runner completes local preparation under registered admission, exits native work, drains required guarded storage writes, then enters HTTP/render. Remove old public auto orchestration; retain native guard, budgets, retry and rendering gates. |
| `app/src/main/java/eu/kanade/translation/engines/translator/ProviderRequestGovernor.kt` | Static priority context:132; waiter metadata selection:549/638/645 | Add owner-neutral live priority source and promotion wake support in this existing file. Preserve bucket quotas, ordering age, reservation identity and already admitted request accounting. |
| `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt` | `autoTranslate:107`, ahead count accessor:113 | Keep auto toggle. Retire ahead-count accessor once callers are removed; retain stored key/data without a migration or storage purge. |
| `app/src/main/java/eu/kanade/presentation/reader/TranslationSettingsSheet.kt` | Toggle section:393, slider:427 | Keep auto toggle; delete prefetch slider and callback/parameter plumbing. Remove corresponding controller setter:1097, ViewModel forwarding:1747, preference combine input:198 and settings field:1844. Exactly N+1 is the requested new policy. |

## Facade signatures and ownership

Expose the following manager entry points by delegation to its sole reader queue; queue API types are defined in the master document. The existing `openTranslationSession` remains the suspending store-opening prerequisite, and workflow session admission stays outside the queue's control lock.

```kotlin
fun bindReaderQueue(
    session: ReaderSessionToken,
    admitted: TranslationSession,
    computeClass: TranslatorComputeClass,
)
fun submitReaderPage(
    session: ReaderSessionToken,
    work: ReaderPageWork,
    trigger: ReaderTrigger,
    force: Boolean = false,
): ReaderSubmission
fun updateReaderViewport(
    session: ReaderSessionToken,
    pageIndex: Int,
    pageCount: Int,
    isAutoEnabled: Boolean,
    resolve: (Int) -> ReaderPageWork?,
)
fun setReaderAutoEnabled(session: ReaderSessionToken, enabled: Boolean)
fun readerOutcomes(session: ReaderSessionToken): StateFlow<Map<String, SinglePageOutcome>>
```

Do not infer that a token is admitted from its string identity. `bindReaderQueue` validates the workflow admission, authoritative active store identity and generation; a later submission must match that binding. Existing external `translatePage` can remain a compatibility facade, resolving the admitted token and delegating once. It must never construct a second worker path.

The control lock owns only queue entries/demands/priority. Resolve page factories outside it: capture a viewport revision under lock, call the resolver without the queue lock, then apply only if that revision/session still matches. This avoids reversing resolver invalidation and queue lock order. No source read, store open, provider wait or lease mutation occurs under the queue lock.

## Capacity, promotion and completion

Automatic desired demand is at most two canonical pages. Abandoned native operations still count toward physical in-flight and memory occupancy until exit; they cannot be subtracted merely to admit another pair on every swipe. Start another worker only when admission capacity and the existing memory budget permit. Resolve unavailable pages on readiness events/cooldown without polling.

Preserve the existing `TranslatorComputeClass.mayOverlapNative` distinction (`TranslatorComputeClass.kt:33`). The old coordinator's semaphore at `:423` serializes local preparation with local translation. Replace it with one queue-owned execution semaphore for LOCAL_COMPUTE, acquired before entering the common runner and released after its completion/drain. REMOTE_IO does not hold that semaphore over HTTP, so its two operations can overlap. Freeze compute class/config epoch at binding; a mode change invalidates compatibility, drains all active reader operations, then rebinds. No second local executor or prepared-page channel is introduced. Test local translator execution versus next-page native work separately from remote overlap.

Promotion changes an entry's live priority and demand flags while preserving its parent job, native operation holder, lease, trace and paid provider work. Preserve compatibility checks for source/config/force. Native wait promotion updates the **same** WAITING gate entry under its lock; grant wins atomically and an already selected invocation is non-preemptive. The priority gate precedes quarantine lifecycle exclusion and device-budget borrowing, as specified in the native document. Never cancel/reacquire a waiter or the whole page job merely to promote it. Provider promotion wakes the existing waiter and retains its sequence/reservation. Already admitted HTTP calls continue.

At common-runner completion, remove the entry only if its operation identity still matches; a cancelled predecessor's completion cannot erase a replacement. Record one typed terminal and one trace terminal, including cancellation before body entry. Cancellation cleanup uses the captured lease/generation/version, never a broad unguarded RUNNING reset.

## Teardown acknowledgement versus physical drain

`cancelSession`/`closeAndDrain` return `ReaderDrainResult.Completed` or `PendingNativeDrain` after the bounded wait. The pending variant carries a completion handle owned by workflow/native quarantine, rather than a detached bitmap or untracked job. It permits reporting/navigation preparation, **not** engine close, input reuse, store disposal or a claim that teardown completed.

Keep existing `ReaderTeardownCoordinator.awaitReaderStop(): Unit` and `requestReaderStop(): Deferred<Unit>` as full completion acknowledgements: if the queue returns PendingNativeDrain, await its handle before clearing/evicting stores and completing these APIs. Never await that handle while holding the queue control lock, native finalizer's required lock, or a store lock. Manager teardown serialization may remain held only where the native finalizer has no reverse dependency; verify this with an explicit lock-order test before cutover. A UI coroutine may suspend for completion; its thread must not block.

Resolver invalidation happens first. Already captured/owned decoded native input remains valid until physical completion. Reader page/loaders and active engines are released only at their corresponding safe completion boundary. Bounded wait expiry never authorizes a second mutable authoritative store or early session close. Bind the next session's request intent without synchronous native waiting; its jobs remain subject to workflow admission and physical lane availability. If blocked by an abandoned previous invocation, record the reason in the existing native-wait trace span. Queued/waiting status must not fabricate a new durable stage or recreate AutoSlotState.

## Required parity cases

1. Auto off tap N runs once with CURRENT_PAGE; repeated taps deduplicate, failed-stage force creates an ordered new attempt.
2. Auto on N creates only N/N+1; last page and empty chapter are safe; inserted/double-page entries sharing a canonical key deduplicate.
3. Promotion while queued, waiting for native, in native inference, waiting for provider and in admitted HTTP preserves identity/counters. Exactly one OCR/inpaint/provider attempt for a compatible request.
4. Rapid swipe prunes stale speculative demand, retains explicit manual demand and counts abandoned physical work until exit. Auto off keeps explicit manual work.
5. N HTTP overlaps N+1 native; native occupancy remains one. Render gate and local translators' compute constraints remain honored; never promise overlap for a local translator that shares constrained compute.
6. Source readiness and lazy online download completions reject stale tokens. Missing source is retryable demand, not a fabricated successful outcome.
7. Memory/provider deferral pauses safely; event reconciliation does not bypass retry budgets or charge another paid request on every viewport update.
8. Same-chapter queued/paused BATCH ownership blocks reader admission. Reader cancellation does not clear BATCH leases or stores.
9. Native cancellation, pending drain, chapter switch, delete/reset and engine teardown preserve input/output ownership, store fences and committed display.
10. PageLifecycle/PageView equality/readiness and existing overlay copy remain covered; auto-slot file retirement requires equivalent safety coverage, not identical old preemption assertions.

The eight legacy test files and destinations are listed in the [test manifest](../test-manifest). This caller map adds no production/test files beyond the master forecast: three queue-specific production files retired, one queue file added, resolver rename net zero. It does not claim runtime parity before C09/C11 tests and device gates execute.
