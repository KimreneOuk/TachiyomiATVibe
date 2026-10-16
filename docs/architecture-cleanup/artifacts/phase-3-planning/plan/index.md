---
title: "Phase 3 implementation plan — bitmap ownership (memory-first, structured same-page overlap)"
kind: spec
comments: none
---

# 1. Status and decision record

**Revised — pending Gemini recheck of this bounded revision. DOCS ONLY; no coding authorization.** User steering (settled): same-page inpaint may run beside external translation after OCR — the existing unsafely-detached overlap is restructured, not removed. Planning baseline `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec` was content-clean when audited; implementation progresses through the reviewed slice commits. Protected report hash `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` untouched. This revision resolves the five findings of the [final independent review](../plan-review): (1) explicit HttpRender split at the await seam, (2) early estimator retired outright, (3) enrollment/publication outside the native segment, (4) corrected await/cancellation facts + explicit sibling-coordination case table preserving the paid-provider policy, (5) branch-private working instances + defensive copies + field-specific authority merges + executable first-terminal-wins. The historical serial approval's correctness primitives (authority, owner, receipt, publisher policy) remain valid and are preserved. Type references: `PreconditionT` = internal `PageSnapshot.toPrecondition()` (PageStoreWriter.kt:286-295); `PatchResult` = `ChapterTranslationStore.PatchResult` (:457-479).

# 2. Contract overview

One operation-scoped `AttemptAuthority` + one `CleanedOwner` created at the actual top-level caller (`pipeline.translateSinglePage`) before any branch forks. The boundary forks a STRUCTURED scope (both branches are children of the operation's Job — structured parentage is what makes caller cancellation reach both; the quarantine's internal Job strip (NativeRunQuarantine.kt:72) detaches only the native invocation from cancellation and is not an ownership or admission boundary; dispatcher choice is irrelevant to ownership):

- **Native branch**: inpaint under lane segment 2 (its own `withNativeLane` = own quarantine run + `nativeTimeoutMs`) — JNI inference + `CleanedOwner.produce` + local reference release INSIDE; nothing else.
- **External branch**: `translateSinglePageHttpOnly` (provider call + classification + translation publication) — NO native permit, timer-bounded.
- **Join at the boundary**: both branches awaited (each bounded by its own timer; native real-exit join is NonCancellable and potentially unbounded in wall time — accepted, D3).
- **Post-join**: retry segment if cleaned==null (admitted), then `renderSinglePageTranslated` (render permit, authoritative estimator, draw, commit, publication), boundary drain, owner release.

Prepared boundary (`prepareSinglePage`) has no external branch: OCR + inpaint inline in ONE lane segment. Batch untouched.

```mermaid
stateDiagram-v2
    [*] --> OCR : segment 1 (nativeTimeoutMs)
    OCR --> FORKED : OCR terminal published (attempt.write)
    FORKED --> INPAINT : segment 2 (nativeTimeoutMs) — inpaint + produce only
    FORKED --> HTTPONLY : translateSinglePageHttpOnly (singlePageTimeoutMs) — no permit
    INPAINT --> JOINED : cleaned in owner; segment exits; enrollment at boundary
    HTTPONLY --> JOINED : TranslationCompletionOutcome + translation published
    JOINED --> RETRY : cleaned == null (segment 3, admitted)
    RETRY --> RENDER : owner.replace(scaledCleaned); estimator re-run (background changed)
    JOINED --> RENDER : borrow + estimator (single authoritative run) + render permit
    RENDER --> [*] : publication (attempt.write) + drain + owner release
    INPAINT --> NATIVE_TERMINAL : TimedOut / failure — marker written POST-JOIN (§14 table)
    NATIVE_TERMINAL --> [*] : classification by store truth; sibling never cancelled mid-provider-call
```

# 3. Verified overlap mechanics (facts F — source-grounded, corrected per review finding 4)

- Today the fresh reader path forks inpaint onto a **detached, unquarantined scope**: `CoroutineScope(Dispatchers.Default).async` (SinglePageOnnxPhase:1183-1232) — no lane admission, no occupancy tracking, leaks native compute beyond caller cancellation (the invocation itself is not intrinsically uncancellable; JNI native calls simply cannot observe cancellation — that is the separate, real limitation the quarantine exists for). The job's `finally` recycles the source bitmap (1226-1231).
- **Corrected await facts (F, review finding 4)**: the join `ctx.inpaintJob.await()` (SinglePageHttpRenderPhase:663-669) sits INSIDE `withTimeoutOrNull(singlePageTimeoutMs)` (TranslationPipeline:698-699) which wraps the ENTIRE HTTP+render monolith — so today's join IS timer-bounded and `Deferred.await()` is intrinsically cancellable. The genuine defects of today: (a) the detached inpaint is unquarantined (native compute escapes occupancy, epoch guards, stage accounting, and reader-stop observability); (b) a timer fire cancels the AWAIT but orphans the job — native work continues past the page's terminal state with no real-exit join and no segment marker; (c) timeout classification cannot distinguish "inpaint still running" from "inpaint hung".
- **Execution order inside the monolith (F, load-bearing for provider policy)**: `translateSinglePageHttpRender` = context prep + provider translate + classification + translation publication (:211-660), THEN the inpaint await + cleaned persist (:662-695), THEN the render gate (permit, estimator :757, draw, :704-795). The provider call therefore ALWAYS completes before the join today — an inpaint failure can never cancel a mid-flight provider call. The revision preserves this: **the sibling is never cancelled because of a native-branch outcome** (case table §14); only genuine caller cancellation (existing reader-stop policy) cancels in-flight provider work.
- HTTP has no native needs (EngineLane.kt:511-519); the external branch needs no permit. Quarantine is segment-reusable (each `withNativeLane` = own run + own timer; real-exit join built in, NonCancellable, **potentially unbounded in wall time** — accepted design, no artificial timeout invented, device gate D3).
- `RenderColorEstimator.recomputeFor` mutates block color fields on the instances given (RenderColorEstimator.kt:295-307); `PageInpaintingEngine` mutates the passed `PageTranslation` directly: status RUNNING + updatedAt (:44-45), FAILED + recordAttemptFailure + errorMessage + updatedAt (:137-142), READY + errorMessage=null + inpaintingModeUsed stamp + updatedAt (:153-161), and passes `pageTranslation.blocks` into the inpainter paths (:107/:118/:130). `inpaint` on the engine adapter may also write status/error (RoiPageRecognitionEngine:963-1007). This is why branch isolation needs whole-instance separation, not just block-list copies (finding 5).
- Retry native work is unadmitted today (OnnxPhase:814-887, runs inside the HTTP phase). Prepared path: OCR + inpaint inline today (resume :556-563), detached job awaited at Pipeline:952-959 — single-segment under the revision.
- `CoexistenceBarrier` has NO native-inpaint barrier point today; the fake inpaint returns immediately (`NATIVE_RELEASE` sits on its return, TranslationCoexistenceHarness:561-562). True-overlap determinism tests require a NARROWLY ADDED inpaint gate in the test harness — not an invented existing seam (recorded in §13/§15).

**Revised shape (P)**:

| Concern | Today (F) | Revised (P) |
| --- | --- | --- |
| Fork structure | detached `CoroutineScope(Dispatchers.Default).async` | structured `coroutineScope` in the boundary; both branches are operation children |
| Inpaint admission | none | segment 2: own `withNativeLane`; inside = JNI inference + `owner.produce` + local release ONLY |
| Inpaint timer | none for the job itself | `nativeTimeoutMs` on segment 2 → adapter → `attempt.markTimeout` |
| Join | cancellable await under the HTTP timer; timeout orphans the job | explicit boundary join of two bounded branches; native outcome does NOT cancel the sibling (§14); no orphan (quarantine owns real exit) |
| HTTP scope | one monolith covers translate+join+persist+render under one timer | split at the await seam: `translateSinglePageHttpOnly` (external branch) vs `renderSinglePageTranslated` (post-join); `singlePageTimeoutMs` meaning preserved (§14 T3) |
| Cleaned handoff | `pageTranslation.cleanedBitmap` + job-owned recycle + await-side recycles (1069-1079) | `CleanedOwner.produce` in segment 2; render borrows; owner release at boundary finally |
| Persist | in-phase after the await (:674-690) | lazy receipt enrollment + tail enqueue at BOUNDARY scope after segment 2 exits (review finding 3) |
| Early estimator | annotates shared originals (:1204) | RETIRED outright; single authoritative run at render (:757); the retry path's re-run on a genuinely new bitmap is retained (background changed — existing behavior) |
| Retry admission | unadmitted inside HTTP phase | segment 3, reader-only wiring (§8 unchanged) |
| Working state | one shared `PageTranslation` mutated by both concerns | branch-private working instances + defensive copies; store via field-specific authority writes (finding 5) |

# 4. AttemptAuthority

Unchanged invariants; two concrete corrections:

```kotlin
class AttemptAuthority(store, pageKey, initial: PreconditionT, val callerJob: Job?) {
    private val mutex = Mutex()
    @Volatile var precondition: PreconditionT = initial  // mutated ONLY under mutex
    @Volatile var closed = false                         // set ONLY under mutex, by markTimeout

    suspend fun write(desc: String, update: (PageTranslation) -> PageTranslation): PatchResult =
        mutex.withLock {
            if (closed || callerJob?.isCancelled == true) return Rejected("attempt closed")
            when (val r = store.patchPage(pageKey, precondition, desc, update)) {
                is Accepted -> { precondition = r.snapshot.toPrecondition(); r } // receipt-advance INSIDE the CS
                is Rejected -> r
            }
        }

    suspend fun markTimeout(update: (PageTranslation) -> PageTranslation): PatchResult = mutex.withLock {
        if (closed) return@withLock Rejected("attempt already closed")   // first-terminal-wins is EXECUTABLE: second marker is an idempotent no-op
        closed = true
        withContext(NonCancellable) { store.patchPage(pageKey, precondition, "mark timed out", update) }
    }
}
```

- **Field-specific merges (finding 5)**: `update` functions compose per-field against the store's current page — the translation branch writes only translation fields (translationStatus/translationError/translated block fields), the native branch only inpaint fields (inpaintStatus/inpaintingModeUsed/attempt counters). No `update` ever replaces the page with a stale whole-object copy of the other branch's working instance; block-level merges take the store's current blocks as merge base.
- **Terminal policy (executable)**: the terminal marker is written POST-JOIN, after both branches have settled, by the boundary classification — so branch publications race neither the marker nor each other (the authority mutex serializes concurrent branch writes; R3). `markTimeout` is idempotent (already-closed ⇒ typed no-op rejection). `callerJob` cancellation gate unchanged. Ordering invariants unchanged: `attemptMutex → store APIs` only; never held across native/HTTP waits; admission outermost on marker paths only.
- **Whole-chain adapter (unchanged)**: marker, `onPageStuck` getter, and diagnostic invoke inside one try/catch suppressing only `CancellationException`, logging `markerCompleted` truthfully; branch always returns `TimedOut` so `awaitExitAndLogLate` is always reached; **no fallback claimed**. Serves segments 1, 2, and 3. Distinct markers: native segment timeout → attempt-scoped `markTimeout` (written at the barrier/post-join point, NOT inside the onTimeout callback — the callback moment and the `withNativeLane` return after the real-exit barrier are distinct events and are not conflated); HTTP timer → existing `markPageTimedOut(nativeTimer = false)` classification; cancellation → existing CANCELLED fast-cancel paths (store 2285-2314, lease-aware). Foreign-rejected ⇒ terminal-and-ignore. Caller intent captured before the quarantine Job strip.

# 5. CleanedOwner

Unchanged: `bitmap/provenance/receipt` fields; `produce(b)/replace(b)` (identity transfer EXECUTED), `borrow()` (+borrow-close counted; release never recycles under an open borrow), `registerReceipt(d)`, `release()` (sole recycler, idempotent, boundary finally, gate = native-real-exit AND receipt-join). Recycler rows as previously approved; the fresh-path source decode recycles in segment 2's own block-scoped finally (transfer-aware) instead of the detached job's finally. H3 unchanged.

# 6. Writer receipt + drain (enrollment location corrected — finding 3)

Mechanism unchanged (plain non-suspending `owner.registerReceipt` before NC entry; `withContext(NonCancellable) { store.enqueueLazyPersistence(...) }`; callee-only false; guards defunct/stale/no-target(H5)/closed-scope; plain `join()`; NC-actual-join potentially unbounded — D3). **Location corrected**: the receipt registration + NC enrollment + deferredPublications tail enqueue execute at BOUNDARY scope AFTER segment 2 exits — the native segment holds the lane for JNI inference, owner production, and local release ONLY. Rationale (F): `admission.withLock` is the global native mutex (NativeRunQuarantine.kt:46); holding it across store synchronization delays the next page's native admission. The enrollment transaction itself remains NC and in-memory-fast; it simply does not run under the native permit.

# 7. Tails — topology corrected (finding 3)

ONE `DeferredPagePublications` queue per operation at the boundary; the single fail-closed drain stays post-join (Pipeline:645-652 shape); orphan discipline unchanged (`deferredPublications.orphaned` on segment timeout; inline orphan runs, DeferredPagePublications.kt:40-46); semantic/physical split unchanged in mechanism. **Queue-site changes stated plainly (no "unchanged" over-claim)**: the persist tail (receipt-based) now enqueues at the boundary after segment 2 exits (moved from in-phase-after-await); the flush/stream-clear tail keeps its existing site (segment 1's OnnxPhase:623-633 defer); render/publication tails enqueue post-join. Resume/deferred-cleanup paths traced: the resume path's persist rides `persistPageWithOomRecovery` inside `renderResumedPage`'s finally (OnnxPhase:684) — unchanged and inline; the prepared boundary's flush barrier (PreparedPageBoundary:71-77) is retained with its recycle becoming the owner-gated release. **PHYSICAL** (flush, stream release, JPEG completion via receipt, borrow cleanup) always finishes; **SEMANTIC** (stage publication, render commit/READY, status/UI) routes through `attempt.write` and is suppressed once closed/cancelled — a suppressed tail returns normally; accepted borrow work always runs.

# 8. Retry — segment 3

Unchanged: reader-only admission in the wiring lambda (Pipeline:801-802) wraps the retry in its own `withNativeLane`; shared delegate (1343-1352) + batch wiring (1242) untouched; gated on `cleanedBitmap == null` (OnnxPhase:823); capture = `scaledCleaned` at :868, `owner.replace` in-block before the retry finally recycles the retry source (880-885); degrade paths unchanged; no new paid provider calls. Timing: the retry runs post-join under the boundary timer (§14 T3) AND its own native segment timer. **Estimator repeat on retry is retained**: a successful retry produces a genuinely new background bitmap, so the single authoritative estimator re-runs on the retried bitmap + settled translated blocks (existing behavior at HttpRenderPhase:876).

# 9. Working state — branch isolation (finding 5, corrected)

- **Branch-private working instances**: the native branch and the external branch each operate on their OWN `PageTranslation` working instance (built via existing copy facilities from the OCR result). `PageInpaintingEngine`'s direct mutations (status/error/mode-stamp/updatedAt/attempt counters — F above) therefore land on the native branch's private instance and are published to the store only through `attempt.write` field-specific merges. The external branch's provider/classification mutations land on its own instance likewise. No concurrent unsynchronized mutation of any shared `PageTranslation` exists; the shared objects are the store's published pages (behind `store.mutex`) and the attempt authority.
- **Defensive nested copies**: branch working instances copy blocks with `it.copy(segmentationMask = it.segmentationMask?.immutableCopy())` (existing facility, PageTranslationView.kt:233 idiom; `BubbleMaskRle`'s `List` vals are NOT intrinsically immutable — PageTranslationView.kt:425-436 defensive-copy helpers exist for exactly this reason). Lists copied via `toList()`/`map{}` as today.
- **No new type**: no `TranslationDraft` class and no new DTO — isolation is by existing `copy()`/`immutableCopy()` facilities plus the branch structure; "draft-only reads" is enforced by construction and verified by test, not by the type system (the provider API takes a full `PageTranslation`; pixel independence is observed behavior, not a type restriction).
- Store receives state only via preconditioned `attempt.write` (copies at CleanedPublication.kt:323-325 shape). User edits/re-translations touch store snapshots, never branch working instances; source/user-edit fences ride the attempt precondition.

# 10. File policy

Unchanged: commit throw/cancellation ⇒ RETAIN `newName` unconditionally; `Rejected` ⇒ delete only if neverAcceptedLiveName AND store-authorized (decision under `store.mutex`, delete outside); H8 closed by source (CleanedPublication.kt:72, CleanedImageLifecycleController.kt:71); uncertain-file registration rejected; **UNBOUNDED orphan debt**, logcat-ERROR ledger, no sweep, no release-safety compromise.

# 11. Call graph (concrete, with the explicit seam — finding 1)

Reader: TranslationScheduler:376 → TranslationExecutor:41 → Pipeline.translateSinglePage:471 → lease admission :489-506 (INTERACTIVE governor context wraps the whole boundary :507-518 — a coroutine context element inherited by both branches) → boundary :522:

1. **Segment 1**: `withNativeLane` (nativeTimeoutMs) → decode + `analyze` (OnnxPhase:1111) → OCR terminal publication (attempt.write).
2. **Fork** (structured, in boundary): **native branch** = `withNativeLane` #2 (nativeTimeoutMs): JNI `inpaint` (PageInpaintingEngine) + `owner.produce` + local release → segment exits → **at boundary scope**: `owner.registerReceipt` + NC `enqueueLazyPersistence` + persist tail enqueue (finding 3). **External branch** = `translateSinglePageHttpOnly(...)` inside the ONE shared T3 boundary budget (not a separately reset timeout) — the extracted pre-seam content of the current monolith (SinglePageHttpRenderPhase:211-660): context prep, provider governor/priority, provider call, failure classification, translation publication, trace/ledger spans for the translate stage. Returns `TranslationCompletionOutcome`.
3. **Join**: boundary awaits both. Native branch outcome: cleaned-in-owner | TimedOut | failure — real exit already joined inside the quarantine. Sibling coordination per §14 (never cancel-on-native-failure).
4. **Post-join**: retry segment if cleaned==null (§8) → `renderSinglePageTranslated(...)`: the extracted post-seam content (SinglePageHttpRenderPhase:704-795 + the :662-695 block minus the dead await): cleaned-availability gate (bitmap-or-on-disk), `withRenderPermit`, SINGLE authoritative estimator (:757), canvas render, render commit + publication via attempt.write, persist/cleaned-publication spans (STORAGE lane) at the enrollment/drain boundary.
5. **Close**: boundary drain (fail-closed) → finally: lease release :723 + owner release (receipt-join).

Prepared: RollingAutoCoordinator 902/917 → Executor:74 → Pipeline.prepareSinglePage:850 → ONE `withNativeLane` (OCR + inline inpaint) → persist :970 → prepared publish :996. Batch: BatchChapterTranslator 150-157/1242 → BatchLaneWorkers 536/683(retry 732) — untouched.

The split is a SMALL EXTRACTION at the existing `:662` await seam (the await dies; the seam becomes the join) — no new framework, no DTO; the two functions are named so the call graph is physically implementable and the boundary is equivalently explicit. No code path invokes the whole legacy monolith while claiming a join-first boundary.

# 12. Manifests

INTERFACES: NEW one file — `AttemptAuthority` (mutex, precondition, closed, callerJob; write, idempotent markTimeout), `CleanedOwner` (bitmap, provenance, receipt; produce, replace, borrow(+close), registerReceipt, release). CHANGED: `TranslationPipeline.runGrantedSinglePageBoundary` (structured fork/join; entry; coordination table §14), `SinglePageHttpRenderPhase` — SPLIT at the :662 seam into `translateSinglePageHttpOnly` (pre-join) + `renderSinglePageTranslated` (post-join); `inpaintJob` ctx/await/cancel branches removed (:662-695, 1069-1079); borrows; owner-gated recycles; gated semantic tails; retry call unchanged, `SinglePageOnnxPhase` (processSinglePage returns OCR-only result — `Pair`/`inpaintJob`/detached scope removed; inpaint segment-entry point reused by reader fork + prepare inline; branch-private working instances; retry body), `OnnxPhaseResult` (drop `inpaintJob`), `EngineLane.withNativeLane` (whole-chain adapter, per-segment), `CleanedPublication`/`CleanedImagePublisher` (receipt wiring; enrollment at boundary; retain policy), `ChapterTranslationStore.enqueueLazyPersistence` (+receipt param, +guards incl. scope-active), `PreparedPageBoundary` (owner-gated release). **Writer-receipt HANDLE API changed (listed); store RECEIPT SEMANTICS unchanged.** RETIRED: detached scope (:1183), `Pair` return (:1285), `inpaintJob` (:614/:1323, Pipeline:669, HttpRenderPhase ctx), naked await + cancel branches, unadmitted reader retry, EARLY estimator pass (:1204) — retired outright (finding 2). RETAINED: `NativeRunQuarantine` (no API change — segment reuse), `EngineLane` guards, batch paths/wiring (1242/1343 untouched), `DeferredPagePublications` mechanics (persist-tail ENQUEUE SITE moved to boundary — stated, §7), `PreparedPageBoundary` flush barrier, `PostOcrStageSemantics`, fences/`patchPage` atomicity/`mayDeleteCleanedImage`, retry algorithm + its estimator re-run, OCR/LiteRT, provider call chain (no new paid calls; provider drain policy preserved per §14).

# 13. Ordered slices (compile-safe AND resource-safe at every published commit)

| # | Slice | Notes |
| --- | --- | --- |
| S1 | Types (`AttemptAuthority`, `CleanedOwner`) — no wiring | unit: mutex cases (M-first/W-first/concurrent/foreign, TWO concurrent writers), suppression, idempotent markTimeout, owner idempotence, borrow-before-recycle, alias transfer |
| S2 | Receipt handshake — store signature + guards, NC enrollment, LOCAL receipt | resource-safe pre-cutover; unit: cancel-before-lock, queued→drain, defunct, no-target, closed-scope reject, double-complete |
| S3 | EngineLane whole-chain adapter (all segments) | unit: throwing marker / getter+invoke / cancelled caller ⇒ TimedOut AND real-exit awaited (fake native latch); `markerCompleted` truthfulness |
| S4 | Publisher retain policy + mutex-held authorization | unit: retain-on-throw/cancel; Rejected-delete conditions; interleave guard |
| S5 | ATOMIC fork/join cutover — HttpRender split at the :662 seam + boundary fork (segment 2 ∥ translate-only) + detached-scope/inpaintJob/early-estimator removal + prepare single-segment + branch-private working instances + field-specific merges + retry adapter + tail/enrollment topology + owner handoff — ALL changed consumers one tree | unit: true overlap (harness inpaint gate added), BOTH completion orders (HTTP-first, native-first), cancel mid-join (provider-call cancellation only via genuine caller cancellation — paid-call test), native-timeout-with-completed-translation (store-truth outcome), both-terminal race, two concurrent writers, field-merge isolation (no stale whole-object replacement), draft defensive copies (mask immutability by test), retry serialization, tail suppression drain+orphan, existing batch suite green |
| S6 | Consolidation | full suites BOTH flavors; lint/format/build; consolidated variant/boundary runs (host only) |

Test-harness note (F): `CoexistenceBarrier` has no native-inpaint point; S5 adds a NARROW inpaint barrier gate to the coexistence test harness (test-only seam, mirroring the existing NATIVE_RELEASE pattern at TranslationCoexistenceHarness:561-562) — no production seam is invented or claimed to exist.

# 14. Coordination, timing, and terminal policy (finding 4 — explicit case table)

**Timers (T)**:

- T1 OCR segment: `nativeTimeoutMs` (quarantine run 1) — unchanged.
- T2 inpaint segment: `nativeTimeoutMs` (quarantine run 2) — bounds the select; the real-exit join after TimedOut is NonCancellable and potentially UNBOUNDED in wall time (accepted; D3).
- T3 `singlePageTimeoutMs`: bounds external translate + join wait + post-join render as a boundary-level budget — preserves today's wall-clock meaning of the "HTTP+render timer" (today it covers translate+await+persist+render; the persist moves to receipt-joined drain, its completion observable via the receipt, D3).
- T4 retry segment: `nativeTimeoutMs` (run 3), inside T3's scope.
- T5 real-exit + receipt joins: NonCancellable, no artificial timeout (accepted design).

**Sibling coordination (C) — provider drain policy preserved**:

| # | Case | Outcome |
| --- | --- | --- |
| C1 | inpaint segment TimedOut, HTTP already completed (translation published pre-join) | translation stays durable (store truth outranks timers — Pipeline:705-708 precedent); marker written POST-JOIN via `attempt.markTimeout` (nativeTimer truth); render suppressed (closed); outcome Failed("native phase timed out") with translationStatus durable — exactly today's coexistence shape (markPageTimedOut only fails non-READY stages, PageStoreWriter:157-175) |
| C2 | inpaint TimedOut, HTTP still in flight | sibling NOT cancelled (a mid-flight provider call is never lost to a native outcome — preserves today's order, where translate always completes before the join); boundary waits the sibling (bounded by T3); then C1 classification. The native timeout CALLBACK may record/notify (trace/log) at the callback moment but does not cancel the sibling and does not write the marker — callback moment ≠ withNativeLane return ≠ marker time |
| C3 | HTTP completes first, inpaint still running | boundary waits the native branch (T2 + real-exit join); classify per store truth (translated + no cleaned → retry path or render-FAILED, mirroring today's cleaned==null flow) |
| C4 | genuine parent cancellation mid-join | structured scope cancels both branches; provider call cancelled — this is the EXISTING reader-stop cancellation policy, unchanged; CANCELLED fast-cancel paths run; native real-exit joined; owner release receipt-joins |
| C5 | native branch fails (non-timeout exception) | sibling NOT cancelled; branch failure recorded; sibling completes; classification Failed(original native cause) with translation durable (today's analog: inpaintStatus FAILED + translation READY coexist); reviewer's "unconditional sibling cancellation" is REJECTED as a provider-policy change |
| C6 | cleaned == null at join (inpaint soft-failed) | retry segment 3 (T4 inside T3); success ⇒ `owner.replace` + estimator re-run (background changed); fail ⇒ render FAILED path — existing behavior |
| C7 | both branches terminal-failed | post-join classification picks by existing precedence (store truth first, then native vs provider failure ordering as today); ONE terminal marker via idempotent `markTimeout`/`markPageFailed` equivalent; loser terminal-and-ignore |

Race matrix: prior R1-R17 stand (R19 rephrased: the join is bounded by T2/T3 timers plus T5 unbounded real-exit joins — today's join was T3-bounded but orphaned the job on timeout; the revision adds quarantine real-exit ownership and segment markers). R18-R22 from the previous revision are superseded by C1-C7 (same coverage, executable ordering).

# 15. Phase 3 physical gates (observed values only; no invented thresholds)

D1 abandonment disposal (both native segments; deterministic release evidenced by logs/traces). D2 retry admission serialization, no nested lock. D3 SAF join stall magnitude (T5 unbounded joins — receipt + real-exit). D4 marker policy end-to-end incl. branch-terminal interleavings (C1/C7) observable in traces. Host tests verify recycle/exit via injected hooks + fake native latches + the added harness inpaint gate; real Bitmap/JNI behavior is device-gated (stated, not claimed). Deferred to Phase 4: throughput/memory, overlap benefit measurement, soak. No performance claims.

# 16. Revision disposition (five findings)

| # | Finding | Resolution |
| --- | --- | --- |
| 1 | HttpRender monolith vs claimed join-first boundary | §11: SMALL EXTRACTION at the existing :662 await seam — `translateSinglePageHttpOnly` (211-660 content; returns TranslationCompletionOutcome) vs `renderSinglePageTranslated` (704-795 + persist-block minus dead await); governor/context lifetime stated (INTERACTIVE context element inherited by both branches); trace/ledger spans scoped per stage; no new DTO/framework; nothing invokes the legacy monolith |
| 2 | contradictory early estimator | RETIRED outright (§3 table, §12); single authoritative run at render :757; retry's estimator re-run retained (genuine background change, existing behavior) |
| 3 | enrollment/publication inside admitted segment | §6/§7: segment 2 = JNI + produce + local release ONLY; receipt registration + NC enrollment + persist-tail enqueue at boundary scope; queue-site moves STATED (no unchanged over-claim); resume/deferred paths traced; admission-mutex-efficiency rationale pinned (NativeRunQuarantine:46) |
| 4 | await/cancellation facts + sibling policy | §3 corrected (T3-bounded, cancellable await; defect = unquarantined detachment + orphaned-on-timeout job; detached ≠ uncancellable, JNI cancellation separate); §14 T-table + C1-C7 case table; sibling never cancelled on native outcome (C2/C5 — reviewer's unconditional-cancellation remedy REJECTED as a paid-provider policy change); callback vs barrier vs marker moments distinguished; T5 joins honestly unbounded |
| 5 | shared PageTranslation/mask races + executable terminal policy | §9: branch-private working instances; defensive `immutableCopy()` masks + list copies via existing facilities; NO new TranslationDraft class; store writes via field-specific authority merges only; §4 `markTimeout` already-closed guard makes first-terminal-wins executable and idempotent; marker written POST-JOIN so branch publications never race the terminal transition |

**Implementation proof obligations remain** — H1/H3/H4/H5/H7 carried as host tests (H7 now requires defensive mask copies, not type immutability); H8 closed; device gates D1-D4. Independent Gemini recheck approved this revision. Execution proceeds under the user's standing implementation instruction.

# 17. Execution alignment after final overlap approval

The parent records these explicit consequences of the approved T/C policy to avoid ambiguity during wiring:

- T3 is ONE `singlePageTimeoutMs` boundary budget covering HTTP translation, sibling join, admitted retry and render. Do not give HTTP its own fresh budget and restart the same duration for render. Native real-exit and accepted-borrow cleanup can outlast that deadline; this does not reset it.
- Ordinary native non-cancellation failures that should preserve a charged provider call must be captured as a native-branch outcome INSIDE that child before they can escape `async` and cancel the structured sibling. Genuine caller cancellation and the shared T3 deadline retain their cancellation paths. A blanket uncaught child failure would violate C2/C5. Fatal errors and actual timeout outcomes must keep their truthful classification; do not turn every failure into cleaned-null retry.
- `BubbleMaskRle.immutableCopy()` is currently a private helper in PageTranslationView. Reuse suitable existing published-to-draft copying or minimally expose/reuse the copy facility if needed; the plan does not assume a presently public extension or require a new draft framework.

These clarify the already-approved contract. They do not authorize changes to paid-call accounting, cancellation policy, algorithm internals or the physical validation gates. Implementation tests and independent actual-code review must establish the behavior.
