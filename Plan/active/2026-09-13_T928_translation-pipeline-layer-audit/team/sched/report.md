# T928 Layer Audit — sched slice: resource allocation & scheduling in the translation pipeline

- Auditor: Technical Lead (sched slice)
- Base: branch `main` @ `9c19ad05bd62cc3c222dfa8527fd4407b037ecc6`, tracked files only.
- Scope: read-only audit. No code was modified.
- Claim markers: **VERIFIED** (read at cited lines at this HEAD), **DERIVED** (inference from verified code, reasoning stated), **SUSPECTED** (plausible, not fully traced).
- Workload model per task contract: MANUAL (reader per-page), AUTO (rolling auto / auto-on-open), BATCH (chapter batch) are three workloads contending for the same resources.

All paths are relative to repo root; `app/src/main/java/eu/kanade/translation/` is abbreviated as `…/translation/`.

---

## (a) Resource map — executors, lanes, queues, backpressure, serialization points

### A.1 The single process-wide native lane (the dominant serialization point)

| Element | Location | Behavior |
|---|---|---|
| `NativeRunQuarantine.admission` | `…/scheduling/NativeRunQuarantine.kt:43` | **VERIFIED**. A single `Mutex` admitting ALL ONNX work process-wide: manual ONNX phase, auto prepare, batch OCR and batch inpaint all funnel through it. |
| Invocation select | `NativeRunQuarantine.kt:84-95` | **VERIFIED**. `select` on `invocation.onAwait` vs `onTimeout`: a timeout does NOT cancel the underlying job; the timed-out caller returns while the native run keeps exclusive ownership until real exit (`awaitExitAndLogLate`, `:109-123`, NonCancellable). |
| `tryRunExclusive` | `NativeRunQuarantine.kt:51-59` | **VERIFIED**. Used by engine teardown to grab the lane even while a "timed-out" invocation nominally still holds it. |
| `nativeRunScope` | `…/TranslationPipeline.kt:195` | **VERIFIED**. `SupervisorJob() + Dispatchers.Default` — native (ONNX) work runs off the IO pool on the Default pool, one invocation at a time. |
| Fairness | kotlinx `Mutex` | **DERIVED**: admission is first-come-first-served (FIFO by suspension). There is no priority — a MANUAL request arriving mid-batch-OCR waits for every queued batch native job ahead of it. |
| Stall watchdog | `…/translator/NativeStallWatchdog.kt:36-91`; gate at `TranslationPipeline.kt:403-405` | **VERIFIED**. One-shot timer (threshold = native stall threshold, ~90s class) publishes `NativeStallState`; it never preempts — the quarantine keeps ownership until real exit. |

Consequence: **native concurrency = 1 for the entire process, across all three workloads.** No preemption exists and none is safely addable (see F.4).

### A.2 Engine set and rebuild lock

| Element | Location | Behavior |
|---|---|---|
| `EngineLane` (in singleton `TranslationPipeline`) | `…/pipeline/EngineLane.kt` | **VERIFIED**. One cached engine set per process: recognition engine + translator registry; epoch counter guards stale closes. Shared by manual, auto and batch. |
| `withNativeLane` | `EngineLane.kt:117-146` | **VERIFIED**. Entry into `NativeRunQuarantine.run` under an engine-epoch guard. |
| `ensureEnginesBuiltFor` | `EngineLane.kt:410-444` | **VERIFIED**. Rebuild triggers: engines-closed, fromLang, OCR model, inpainting mode, reading-order, translator signature. Otherwise no-op. |
| `closeEngines` / `closeEnginesNow` | `EngineLane.kt:301-349 / 352-366` | **VERIFIED**. Epoch bump, snapshot close, ≤5s bounded drain of borrowed translators (`ENGINE_DRAIN_GRACE_MS=5_000`, `:70`). |
| `engineRebuildMutex` | `TranslationPipeline.kt:182`; users: `…/pipeline/SinglePageOnnxPhase.kt:252-254`, `…/pipeline/batch/BatchChapterTranslator.kt:318-320` | **VERIFIED**. Single rebuild-at-a-time; a rebuild happens inside the native permit, so a MANUAL page can queue behind a BATCH-initiated full engine rebuild. |
| `inFlightPageKeys` | `TranslationPipeline.kt:191` | **VERIFIED**. Per-page dedup at pipeline boundary. |

### A.3 Coroutine scopes / worker pools

| Scope | Location | Pool / shape |
|---|---|---|
| `TranslationScheduler.scope` | `…/scheduling/TranslationScheduler.kt:94` | **VERIFIED**. `SupervisorJob() + Dispatchers.IO`; launches one page job per manual/auto page (`translatePage`, `:658-780`). |
| `ChapterTranslator.scope` | `…/ChapterTranslator.kt:270` | **VERIFIED**. `SupervisorJob() + Dispatchers.IO`; one batch job per source at a time (`launchTranslatorJob`, `:399-446`, `take(1)` per source). |
| `RollingAutoCoordinator` scope | `…/scheduling/RollingAutoCoordinator.kt` | **VERIFIED** (lane model doc `:57-83`). Coordinator loop + prepare jobs; native lane work is executed INLINE in the reconcile loop (auto native work holds the quarantine directly). |
| `TranslationManager.applicationScope` | `…/TranslationManager.kt:110` | **VERIFIED**. IO scope for store opens etc. |
| `StorePersistenceScheduler.persistScope` | `…/store/StorePersistenceScheduler.kt` (debounce `PERSIST_DEBOUNCE_MS=250`, `:33`; join timeout 2s `:32`; `schedulePersist` `:141-153`) | **VERIFIED**. Debounced durable writes on IO; storage is a shared background lane, not on the native lane. |

### A.4 Queues and channels (backpressure)

| Queue | Location | Capacity / policy |
|---|---|---|
| Auto prepared-page channel | `RollingAutoCoordinator.kt:427` (capacity = `TranslationMemoryBudget.recommendedPrefetchCapacity()`, `…/util/TranslationMemoryBudget.kt:53-57`) | **VERIFIED**. Bounded 6/4/2 by device tier — the auto pipeline's backpressure against unbounded decode. |
| Auto trigger channel | `RollingAutoCoordinator.kt:192` | **VERIFIED**. `Channel.CONFLATED` — event-driven, at most one pending reconcile. |
| Overlap-scheduler inpaint events | `…/pipeline/batch/OverlapScheduler.kt:117-137` (`events Channel.UNLIMITED`) | **VERIFIED**. Unbounded, but producers are the (bounded) batch page set; consumers are serial. |
| Manual/auto page job registry | `TranslationScheduler.kt:99,131` (`activePageJobs` / `queuedPageKeys`) | **VERIFIED**. Dedup, never cancels a live twin; stuck detection via `markPageJobStuck` `:914-924`; bounded join 2s (`JOIN_TIMEOUT_MS`, `:80`). |
| Batch chapter queue | `TranslationManager.translateChapter` `:779-838` → `ChapterTranslator` queue; `evictStaleQueuedChapters` `:803` | **VERIFIED**. FIFO per chapter; PAUSED/ERROR re-armed to QUEUE. |
| Chapter claim poll | `ChapterTranslator.kt:93` (`IN_FLIGHT_CLAIM_RETRY_MS=100`), used `:461-463` | **VERIFIED**. 100ms busy-poll while the same chapter's previous batch job is still winding down. |

### A.5 Provider (network) admission — the second global serialization point

| Element | Location | Behavior |
|---|---|---|
| `SharedProviderRequestGovernor` | `…/translator/ProviderRequestGovernor.kt:684-688` | **VERIFIED**. One governor instance per backend bucket shared by ALL workloads. |
| Default quota policy | `ProviderRequestGovernor.kt:66-85` | **VERIFIED**. 60 RPM, 60k TPM, `minimumSpacingMs=1000`, **`maxInFlight=1`**, `pollIntervalMs=50`, `maxForegroundWaitMs=15_000`, `interactiveMaxAgeMs=30_000`, quota cooldown 60s, interactive token reserve 0.2. |
| Wait loop | `:329-331` | **VERIFIED**. Blocked waiters poll every 50ms (not event-driven). |
| Priority classes | `AdmissionPriority` `:43-46`; manual = INTERACTIVE (`TranslationPipeline.kt:392-429` wrapper), batch = BACKGROUND (`ProfileEnvelopeExecutor.kt:561`) | **VERIFIED**. |
| Interactive reserve & starvation guard | `:449-461`, `selectWaiter :533-546` | **VERIFIED**. INTERACTIVE prefers reserved tokens; a BACKGROUND waiter older than 30s can win over a fresh INTERACTIVE waiter (anti-starvation inversion window). |
| Batch-only nested sublimit | `BatchProviderSublimit` `:707-738` (15 RPM, maxInFlight 1), `BatchRequestSublimitGate.executeBatch` `:754-788`, shared instance `:797-799` | **VERIFIED**. All batch provider traffic additionally passes a 15-RPM all-or-nothing gate; MANUAL/AUTO do NOT pass it. |
| Serial envelope executor | `…/pipeline/batch/ProfileEnvelopeExecutor.kt:52-55, 541-575` | **VERIFIED**. Hard one-envelope-in-flight in the AI lane. |

### A.6 Page-level leases and write fencing

| Element | Location | Behavior |
|---|---|---|
| `PageStageLeaseTable` | `…/store/PageStageLeaseTable.kt` | **VERIFIED**. One writer origin per page. MANUAL may evict in-flight AUTO only (`:89-97`); same-origin regrant `:98-113`; release is NonCancellable `:138-149`. |
| Manual-vs-batch | lease table + `TranslationPipeline.attachToOwnerTerminal` `:729-786` | **VERIFIED**. MANUAL never preempts BATCH; it attaches (observes) and waits for a terminal page status, bounded by `ATTACH_TIMEOUT_MS=210_000` (`TranslationPipeline.kt:151`). |
| Batch defers manual-owned pages | `…/pipeline/batch/BatchLaneWorkers.kt:329-343` (OCR), `OverlapScheduler.kt:285-368` (`inpaintOne` → `deferredByLeaseOwner`) | **VERIFIED**. |
| `BatchWriteIdentity` + guarded writes | `…/pipeline/batch/BatchWriteGate.kt:36-43, 85-120` | **VERIFIED**. generation/pageVersion/leaseToken/candidateGenerationId/dependencyFingerprint/artifactPageVersion preconditions; one lease-held re-sync retry (`:107-120`) — generation/lease mismatch still rejects, so the manual ownership fence is never preempted. |
| Render join | `…/pipeline/batch/BatchRenderJoin.kt:81, 121-347` | **VERIFIED**. Per-page `renderMutexes`; held-bitmap consume or `.cleaned.jpg` disk reload (`:169-174`). |

### A.7 Batch phase structure (strictly sequential)

**VERIFIED** `…/pipeline/batch/ChapterProfileBatchCoordinator.kt`:

- `runPass1` `:253-596`: artifact-authority gate `:262` → resume/fingerprint gates `:280-296` → (AI-only frozen-profile reuse skip `:326-369`) → RUN_SNAPSHOT → **OCR_PLAN: per-page OCR loop `:378-525` (`yield()` between pages `:383`; per-page checkpoint + run record `:414-483`)** → corpus-gap pause `:556-569` → then branch `:571-595`: STANDARD → `runStandardTranslateAndFinalize` (`:1749-1979`), AI → analysis → chunks → profile freeze → envelope translation (`ProfileEnvelopeExecutor`).
- ST-14 FINALIZE: serial inpaint drain via `overlapScheduler.drainSerial()` at `:1623`.
- Overlap scheduler launched in both lanes: `:1460` (AI), `:1812` (standard); serial native inpaint ONLY inside remote-provider windows (`OverlapScheduler.kt:27-65, 173-188`).

**Key structural fact:** translation of page 1 cannot begin until ALL pages of the chapter are OCR-checkpointed (**VERIFIED** loop order `:378-525` precedes branch `:571-595`). The AI lane additionally waits for the whole-corpus analysis + profile freeze before the first envelope.

### A.8 Auto lane compute gate

- `computeGate = Semaphore(1)` for LOCAL_COMPUTE (ML Kit) workloads so ML Kit never overlaps native ONNX: `RollingAutoCoordinator.kt:426` **VERIFIED**.
- Memory admission gate: `hasHeadroomForPrefetch` (`RollingAutoCoordinator.kt:87`; `TranslationMemoryBudget.kt:388-398`) **VERIFIED** — auto prepares are deferred on memory pressure.
- Provider drain grace on teardown: `PROVIDER_DRAIN_GRACE_MS = ATTACH_TIMEOUT_MS = 210s`, NonCancellable drain (`RollingAutoCoordinator.kt:1359`, `consumeTranslations :522-688`) **VERIFIED**.

### A.9 Persistence lane

- `StorePersistenceScheduler`: 250ms debounce, IO scope (`:32-33, 141-153`) **VERIFIED**.
- `TranslationQueueStore`: SharedPreferences `commit()` (synchronous) per queue mutation (`…/TranslationQueueStore.kt:42-49`) **VERIFIED** — queue mutations are on the caller's thread.

---

## (b) Native model lifecycle table

Shared engine set: one `EngineLane` in the singleton pipeline serves manual, auto and batch (**VERIFIED** `TranslationManager.kt:100-101`; batch rebuilds route through the same `engineRebuildMutex`, `BatchChapterTranslator.kt:318-320`).

| Component | Created when | Sessions created | Reused across | Closed when | Load cost notes |
|---|---|---|---|---|---|
| Model files on disk | First native use of any kind | `OnnxModelStore.ensureModels/ensurePaddle*` `…/runtime/onnx/OnnxModelStore.kt:82-196` deploy assets→files with stamps + ONNX header integrity check (`:198-326`) | All workloads | Never (disk cache) | **VERIFIED**. One-time per install/upgrade. |
| `RoiPageRecognitionEngine` | `EngineLane.createRecognitionEngine` (`EngineLane.kt:287-299`); sessions LAZY on first `analyze()` under `initMutex` (`RoiPageRecognitionEngine.kt:153-265`) | detector; optional panel detector (best-effort); optional bubble segmenter (best-effort); OCR engine per model; paddle det for inpaint masks regardless of OCR model (`:221-242`); AOT inpainting ONLY in QUALITY mode (`:246-253`) — all at once on first use | All workloads (same engine instance) | `EngineLane.closeEngines` `:301-349` (epoch bump + ≤5s borrow drain) | **VERIFIED**. "initialized in Xms" log at `:258`. **No warm-up inference exists** — first real page also pays first-inference costs (DERIVED from absence of any dummy-run in `initialize()` `:153-265`). |
| `MangaOcrEngine` | per OCR-model selection | 3 CPU-only sessions (encoder / decoder-init / decoder-step), `…/ocr/MangaOcrEngine.kt:30-95` | same | same | **VERIFIED** CPU-only by design. |
| Paddle OCR small/det | per OCR-model / inpaint-mask path | accelerator sessions (`PaddleOcrV6SmallEngine.kt:53`; `PaddleOcrV6DetEngine.kt:67-80`, 736×736 symbolic dims) | same | same | **VERIFIED**. |
| `AOTInpainting` | first QUALITY-mode inpaint | fixed+dynamic XNNPACK sessions + optional QNN HTP (staged context cache) + strict NNAPI (`…/inpainting/aot/AOTInpainting.kt:115-341`); independent close helpers `AotSessionLifecycle.kt` | same | `closeEngines` / mode change | **VERIFIED**. QNN context cache: multi-second graph finalization paid once per install (`OnnxRuntimeProvider.kt:269-275`). |
| Session EP selection | every session create | strict accelerator → CPU fallback with honest labels (qnn_htp/qnn_gpu/nnapi/xnnpack/cpu) `OnnxRuntimeProvider.kt:202-261`; intra-op threads = cores/2 coerce 2..4 (`:287-289`) | — | — | **VERIFIED**. |

**Rebuild triggers** (`EngineLane.ensureEnginesBuiltFor`, `:410-444`) **VERIFIED**: engines-closed, fromLang change, OCR model change, inpainting mode change, reading-order change, translator signature change. A rebuild is a full session-set teardown + recreate, performed inside the native permit under `engineRebuildMutex` — i.e., it occupies the global native lane.

**Thrash analysis:**

- Manual→batch and batch→manual transitions do **NOT** reload models: same `EngineLane`. **VERIFIED**.
- Most stops keep engines warm: `ChapterTranslator.stop()` returns early when a reason is given and `closeEngines=false` (`ChapterTranslator.kt:343`) — e.g., reader-backgrounded releases pooled native buffers only (`:349-358` memory-pressure path; buffers, not sessions). **VERIFIED**.
- The one common unload path is `clearQueue()` → `stop()` with null reason → falls through to `closeEngines` (**VERIFIED** call shape in `TranslationManager.kt:779-838` region) → next translate pays a full rebuild **inside the native permit**, delaying every queued workload.
- Memory pressure releases pools/buffers, never sessions (`RoiPageRecognitionEngine.kt:935-961` `reclaimPooledMemory`; `:963-990` `forceReleaseNativeBuffers` tryLock-skip; `MemoryGovernance.kt:62-91`) — **no pressure-driven unload thrash**. **VERIFIED**.
- `nativeGuard` mutex in the recognition engine makes close-vs-run races leak instead of crash (`RoiPageRecognitionEngine.kt:87-103, 911-933`) — deliberate leak-instead-of-SIGSEGV policy. **VERIFIED**.

---

## (c) Admission & arbitration trace

### C.1 MANUAL (reader tap, per page)

1. Reader tap → `TranslationManager.translatePage` → `TranslationScheduler.translatePage` (`TranslationScheduler.kt:658-780`): dedup against `activePageJobs` without cancelling the live twin; launches on IO. **VERIFIED**.
2. `TranslationPipeline.translateSinglePage` (`:392-429`): native-stall gate first (`:403-405` — refuses new interactive promises while a stall is published); resolves/opens the chapter store (first touch may pay a runBlocking store open, `TranslationManager.kt:1402`); acquires page lease at **INTERACTIVE** priority.
3. Lease denied (owner is AUTO) → AUTO evicted, manual regrants (`PageStageLeaseTable.kt:89-113`). Lease denied (owner is BATCH) → `attachToOwnerTerminal` (`:729-786`): passive observation with `ATTACH_TIMEOUT_MS=210_000`; terminal = translation READY/PARTIAL/SKIPPED.
4. Lease granted → `runGrantedSinglePageBoundary` (`:497-716`): enters `withNativeLane` (90s ONNX-phase cap, `ONNX_PHASE_TIMEOUT_MS` `:139`) → `engineRebuildMutex` + `ensureEnginesBuiltFor` (`SinglePageOnnxPhase.kt:252-254`) → decode → analyze → inpaint → deferred publications drained AFTER permit release (`:608-618`) → HTTP translate+render OUTSIDE the permit under a 120s cap (`:669`, `SINGLE_PAGE_TIMEOUT_MS` `:118`), admitted by the governor at INTERACTIVE priority.

### C.2 AUTO (rolling auto / auto-on-open)

1. `TranslationManager.updateAutoWindow` (`:1585-1646`): fully suppressed while a batch is retained (`:1621-1626`) and in `reconcileAutoWindow` guard (`:1645`). **VERIFIED**.
2. `TranslationScheduler.updateAutoWindow` (`:181-250`): manual-job arbitration wraps the page resolver — a visible manual job wins the page. **VERIFIED**.
3. `RollingAutoCoordinator` reconcile pass (`:785-1008`): memory gate (`hasHeadroomForPrefetch`), admission into the bounded prepared channel, native prepare **inline** (holds the quarantine), ML Kit compute gate `Semaphore(1)` (`:426`), re-prepare cap 3 (`:1370`).
4. Consumption drains NonCancellable with the 210s provider grace (`:522-688, 1359`). AUTO pages are evictable by MANUAL at any stage (`PageStageLeaseTable.kt:89-97`).

### C.3 BATCH (chapter run)

1. UI Start → `TranslationManager.translateChapter` (`:779-838`): under the request fence, **shuts the auto coordinator down** (`:802`), evicts stale queued chapters, queues, re-arms PAUSED/ERROR, starts. **VERIFIED**.
2. `ChapterTranslator.launchTranslatorJob` (`:399-446`): one active chapter per source; claims `inFlightChapterIds` with a 100ms retry poll (`:461-463`).
3. `translateChapterInternal` (`:595-808`): store open, shared `ArchiveReader` (`:669-690`), `preRegisterPages`, rebuild-from-store, → `pipeline.translateBatch`.
4. `BatchChapterTranslator.translateBatch`: `beginGeneration` (`:290`), possible engine rebuild under `engineRebuildMutex` (`:318-320`), **full-chapter source-fingerprint hashing in parallel on Dispatchers.IO before planning** (`:389-410`), then `runBatchPass1` (`:635-865`) → coordinator phases (A.7).
5. Provider traffic: BACKGROUND priority + nested 15-RPM `sublimitGate` (`ProfileEnvelopeExecutor.kt:561-575`); one envelope in flight; maxInFlight=1 end-to-end.

### C.4 Arbitration summary (who wins what)

| Contention | Winner | Evidence |
|---|---|---|
| MANUAL vs AUTO same page | MANUAL (evicts in-flight AUTO) | `PageStageLeaseTable.kt:89-97` **VERIFIED** |
| MANUAL vs BATCH same page | BATCH keeps lease; MANUAL waits ≤210s (attach) | `TranslationPipeline.kt:151, 729-786` **VERIFIED** |
| MANUAL vs BATCH native lane | FCFS mutex — whoever holds runs to completion; no preemption | `NativeRunQuarantine.kt:43-95` **VERIFIED** |
| MANUAL vs BATCH provider slot | INTERACTIVE priority + 20% token reserve, but 30s starvation guard can hand the slot to an old BACKGROUND waiter; an already-admitted batch request is never interrupted (`maxInFlight=1`) | `ProviderRequestGovernor.kt:449-461, 533-546, 66-85` **VERIFIED** |
| AUTO during batch | Fully suppressed for the batch's lifetime | `TranslationManager.kt:1621-1626` **VERIFIED** |
| Auto memory admission | Deferred when no prefetch headroom | `RollingAutoCoordinator.kt:87` **VERIFIED** |
| Native stall | Refuse new interactive promises; never cancel the running invocation | `TranslationPipeline.kt:403-405`, `NativeStallWatchdog.kt` **VERIFIED** |

**What MANUAL waits on when batch is live (worst case chain):** current batch native invocation (up to its 120s cap, `BatchLaneWorkers.kt:401`) → any queued batch native jobs (FIFO) → its own lease path. If the tapped page is batch-leased during OCR preflight, attach cannot succeed until the TRANSLATE phase reaches that page — which requires the whole chapter OCR (+ AI analysis) — so the 210s attach times out. **DERIVED** (structural, from A.7 phase order + attach predicate `:729-786`).

---

## (d) Memory governance summary

| Mechanism | Location | Behavior / latency impact |
|---|---|---|
| Device tiers | `TranslationMemoryBudget.kt:46-50` | FLAGSHIP ≥14GiB / HIGH ≥7GiB / BASELINE. **VERIFIED**. |
| Prefetch capacity | `:53-57` (6/4/2) consumed by `RollingAutoCoordinator.kt:427` | Auto backpressure. **VERIFIED**. |
| Held-bitmap ceiling | `HeldBitmapRegistry.kt:28,38`: fixed **48MiB / 4 bitmaps**; tier API `TranslationMemoryBudget.heldBitmapByteCeiling()` (`:59-63`) exists but has **NO callers** (grep at HEAD) | **VERIFIED + gap**: FLAGSHIP devices get the BASELINE ceiling → excess disk reloads at render (`BatchRenderJoin.kt:169-174`). |
| Held-bitmap spill | `HeldBitmapRegistry.kt:45-58` | tryAcquire count/bytes else recycle; render later reloads `.cleaned.jpg` from disk. **VERIFIED**. |
| Preflight gates | `MemoryGovernance.kt:26-60` + `TranslationMemoryBudget.canStartDecode/Analyze/Inpaint` (`:293-371`) | Fail-fast `LowMemory*DeferredException` → page deferred; **no queueing**, retry is workload-driven. Adds latency only as full deferral. **VERIFIED**. |
| Auto admission gate | `hasHeadroomForPrefetch` `:388-398` | Auto prepare deferred under pressure. **VERIFIED**. |
| Pressure classification | `MemoryPressurePolicy.kt:59-73` | Critical = level 10..15 or ≥80%. **VERIFIED**. |
| Reaction to pressure | `ChapterTranslator.onMemoryPressure :349-358`; `TranslationManager.onMemoryPressure :720-723`; `MemoryGovernance.reclaimTranslationMemory :62-91` | Release pools + native buffers + Coil trim + GC; **never cancels jobs, never closes sessions** → no reload latency, bounded relief. **VERIFIED**. |
| AOT reserve accounting | `TranslationMemoryBudget.kt:33-34` | 96MiB/session + 64MiB reserved in budget math. **VERIFIED**. |

Net: memory reactions trade **throughput/bitmap-caching**, not model reloads. The only model-reload latency sources are config changes and explicit engine close (clearQueue) — see (b).

---

## (e) Latency budget / dead-time inventory per scenario

### (e.a) MANUAL page while reader idle

| # | Stage | Dead time | Severity | Evidence |
|---|---|---|---|---|
| 1 | Scheduler hop (launch on IO) | ~ms | LOW | `TranslationScheduler.kt:697` region **VERIFIED** |
| 2 | Store open on first touch of chapter (runBlocking IO variant) | tens–hundreds ms (SAF) | LOW-MED (once per chapter) | `TranslationManager.kt:1402` **VERIFIED** |
| 3 | Native admission (idle) | ~0 (uncontended mutex) | LOW | `NativeRunQuarantine.kt:43` **VERIFIED** |
| 4 | Engine rebuild check | ~0 unless config changed | LOW | `EngineLane.kt:410-444` **VERIFIED** |
| 5 | **First native use of session set: model deploy check + ALL session creations + first-inference costs, all inside the 90s permit** | 100s of ms – multi-seconds (QNN finalization once per install can be seconds+) | **HIGH (one-time per process/mode)** | `RoiPageRecognitionEngine.kt:153-265`; `OnnxRuntimeProvider.kt:269-275`; no warm-up **VERIFIED (absence)** |
| 6 | Decode → analyze → inpaint | compute (not dead time) | — | `SinglePageOnnxPhase` **VERIFIED** |
| 7 | Deferred publication drain (outside permit) | ms (disk) | LOW | `TranslationPipeline.kt:608-618` **VERIFIED** |
| 8 | Governor admission (first request) | ~0 (spacing measured from last admission) | LOW | `ProviderRequestGovernor.kt:66-85` **VERIFIED** |
| 9 | Consecutive manual pages | ≥1s spacing between admissions (`minimumSpacingMs=1000`) | LOW-MED | same **VERIFIED** |
| 10 | HTTP → render → persist | network-bound + 250ms debounce tail | LOW | `StorePersistenceScheduler.kt:33` **VERIFIED** |

Summary (e.a): stage chain is strictly sequential but contiguous; the only structural dead time is the one-time session init (#5), which is charged to the first translated page of a process/mode.

### (e.b) MANUAL page while BATCH is running

| # | Wait | Bound | Severity | Evidence |
|---|---|---|---|---|
| 1 | Batch lease on tapped page → attach observe | ≤210s, then `AttachedUnresolved` | **HIGH (structural)**: during OCR preflight (or AI analysis), the tapped page cannot reach translation-terminal, so a manual tap during the entire preflight phase is effectively dead for 210s | `TranslationPipeline.kt:151, 729-786`; phase order A.7 **VERIFIED/DERIVED** |
| 2 | Native lane behind in-flight batch native job | ≤120s per batch invocation (`BatchLaneWorkers.kt:401`) + queue of pending batch pages ahead (FIFO) | **MED-HIGH**; a whole-chapter preflight is a back-to-back native occupancy — a manual request can wait many page-OCR durations | `NativeRunQuarantine.kt:43` FCFS **VERIFIED** |
| 3 | Decode inside the batch native permit | adds to wait in #2 (decode is heap work but runs inside `withNativeLane`) | MED (removable — see F.2) | `BatchLaneWorkers.kt:401, 584` **VERIFIED** |
| 4 | `engineRebuildMutex` if batch start triggered a rebuild | full session-set rebuild inside permit | MED (only after config change/close) | `BatchChapterTranslator.kt:318-320`; `EngineLane.kt:410-444` **VERIFIED** |
| 5 | Provider slot held by an in-flight batch envelope | remainder of that envelope (LLM latency, unbounded by governor) | MED | `maxInFlight=1` `ProviderRequestGovernor.kt:66-85` **VERIFIED** |
| 6 | Provider starvation-guard inversion | a ≥30s-old BACKGROUND waiter can be selected over the fresh INTERACTIVE waiter | LOW-MED (one-slot inversion, bounded by 30s guard window) | `:533-546` **VERIFIED** |
| 7 | If page not yet leased by batch: manual wins; batch defers and re-runs the page later in-pass | batch does extra work, manual unblocked | (beneficial) | `BatchLaneWorkers.kt:329-343`; `OverlapScheduler.kt:285-368` **VERIFIED** |

Summary (e.b): the reader-visible worst case is #1 — a tap during batch preflight waits the full attach timeout with no result; #2–#4 add seconds-to-tens-of-seconds even when the page is free.

### (e.c) BATCH chapter run start (queue → first translated page)

| # | Stage | Dead time | Severity | Evidence |
|---|---|---|---|---|
| 1 | Queue admission + fence | ~ms | LOW | `TranslationManager.kt:779-838` **VERIFIED** |
| 2 | Chapter claim poll if previous run unwinding | 100ms granularity busy-wait | LOW | `ChapterTranslator.kt:93, 461-463` **VERIFIED** |
| 3 | Store open + preRegister + tracker rebuild | IO | LOW-MED | `:595-808` **VERIFIED** |
| 4 | Engine rebuild if config changed | full session rebuild inside first permit | MED | `BatchChapterTranslator.kt:318-320` **VERIFIED** |
| 5 | **Full-chapter source-fingerprint hashing before planning** | full chapter byte-read (parallel IO but a hard barrier) | MED | `:389-410` **VERIFIED** |
| 6 | **Whole-chapter OCR preflight before ANY translation starts** (strictly sequential native lane; `yield()` is only a courtesy) | N pages × (decode+OCR) — the dominant batch-start dead time for perceived progress | **HIGH (structural)** | `ChapterProfileBatchCoordinator.kt:378-525 → 571-595` **VERIFIED** |
| 7 | Per-page checkpoint + run-record `publishRecord` transactions inside the loop | durable writes interleaved with native occupancy (storage lane, off native permit) | LOW-MED | `:414-483` **VERIFIED** |
| 8 | AI lane: analysis chunks + profile freeze before first envelope | N chunks × provider pacing (nested 15-RPM sublimit ⇒ ≥4s average pacing per request) | HIGH for AI mode | `ProfileEnvelopeExecutor.kt`; sublimit `ProviderRequestGovernor.kt:707-738` **VERIFIED** |
| 9 | Standard lane: first page translate only after #6 | same barrier | HIGH | `:571-583` **VERIFIED** |

Summary (e.c): the batch intentionally front-loads ALL native OCR work; first user-visible translated page lands only after the entire chapter is OCR'd (+ analyzed for AI). This is also what makes (e.b)#1 structurally severe.

---

## (f) Ranked recommendations

Priority honored per contract: correctness/data safety > resume/crash safety > reader responsiveness > memory safety > throughput.

### F.1 (Highest impact · MEDIUM risk · reader responsiveness + throughput) Remove the whole-chapter OCR→translate barrier for the STANDARD lane
Pipeline per-page translation/inpaint to start as soon as that page's OCR checkpoint is committed, instead of gating on chapter-wide preflight completion (`ChapterProfileBatchCoordinator.kt:378-525 → 571-595`). The corpus fingerprint is a run-record input, not a translation input; the STANDARD lane has no corpus dependency. Keep the AI-lane analysis barrier intact (context genuinely needs the full corpus). Benefits: earlier first translated page, shorter attach-starvation window in (e.b)#1, better overlap with provider windows. Risk: touches resume/finalize invariants — needs staged rollout with existing T924 checkpoint/fencing tests; correctness machinery (leases, guarded writes, generation fences) already exists and is untouched.

### F.2 (High impact · LOW-MED risk · reader responsiveness) Move batch page decode OUT of the native permit
`runOcrStage`/`runInpaintStage` run decode inside `withNativeLane` (`BatchLaneWorkers.kt:401, 584`). Decode is heap work (bitmap decode), not ONNX; moving it before permit entry shrinks every manual wait behind a batch native job (e.b)#2–#3 and reduces quarantine occupancy per page. Must keep the OOM `reclaimPooledMemory` interaction (`PageDecode` engine hook) intact — reclaim does not require the permit (`RoiPageRecognitionEngine.kt:935-961`). Risk: low; verify memory gates (`canStartDecode`) still run before decode.

### F.3 (High impact · LOW risk · reader responsiveness) Opportunistic native warm-up
Warm the engine session set (and a dummy recognition pass if cheap) when a reader session opens or a batch is queued, outside any timed region, gated by `hasHeadroomForPrefetch`-style memory checks. Eliminates (e.a)#5 from the first translated page. Risk: low (memory-gated; sessions are reused and never reloaded under pressure per (d)).

### F.4 (Explicit no-go, state for the record) Do NOT add native parallelism or preemption
- Second native lane / parallel OrtSession runs would violate the quarantine's one-invocation ownership invariant and its leak-instead-of-SIGSEGV close-vs-run handling (`NativeRunQuarantine.kt:19-31, 43-95`; `RoiPageRecognitionEngine.kt:87-103`) and break the bounded native-memory envelope (96MiB/session reserves, `TranslationMemoryBudget.kt:33-34`).
- Preempting a running native invocation for MANUAL is impossible safely: timed-out calls already keep ownership until real exit (`NativeRunQuarantine.kt:109-123`); cancellation cannot interrupt `OrtSession.run`. The FCFS mutex + F.2/F.3 are the correct levers.
- Parallelizing provider envelopes beyond `maxInFlight=1`/the 15-RPM sublimit would violate quota-safety (`ProviderRequestGovernor.kt:66-85, 707-738`).
- Letting batch inpaint overlap batch OCR (rather than only remote-wait windows) would violate the one-native-lane and the OCR-barrier invariants (`OverlapScheduler.kt:27-65`).

### F.5 (Medium impact · LOW risk · throughput + memory safety) Wire the tier-scaled held-bitmap ceiling
`HeldBitmapRegistry` uses a fixed 48MiB ceiling (`:38`) while `TranslationMemoryBudget.heldBitmapByteCeiling()` (384/192/48MiB by tier, `:59-63`) has no callers. Using the tier API reduces disk reload renders on FLAGSHIP/HIGH devices at zero new risk (count cap of 4 stays). Memory-safety positive on BASELINE (unchanged) and behavior-preserving elsewhere.

### F.6 (Medium impact · LOW risk · reader responsiveness) Trim polling dead times
- Chapter claim 100ms busy-poll → event-driven completion signal (`ChapterTranslator.kt:93, 461-463`).
- Governor 50ms admission poll → fine while quota-blocked (bounded by design); optionally event-driven wakeup on release (`ProviderRequestGovernor.kt:329-331`).
Both are small but free.

### F.7 (Low impact · LOW risk · throughput, cross-slice with io) Batch the per-page preflight record publications
Per-page `publishRecord` durable transactions inside the OCR loop (`ChapterProfileBatchCoordinator.kt:414-483`) serialize storage work between native pages. Batching to phase boundaries (or coalescing via the existing `StorePersistenceScheduler` debounce pattern) shortens wall-clock of (e.c)#6–#7. Tradeoff is resume granularity — explicitly a data-safety tradeoff, so defer to the io slice's durability findings before acting.

### F.8 (Low impact · LOW risk) Rebuild-free clearQueue path
`clearQueue()` currently falls through to `closeEngines` (null-reason stop, `ChapterTranslator.kt:343`), charging the next translate a full session rebuild inside the native permit. Releasing buffers/pools instead of sessions (the memory-pressure treatment, (d)) would keep queue-clears cheap. Verify no correctness need for session teardown on queue clear.

---

## Cross-cutting conclusions

1. The pipeline is built around three deliberate serializations: **one native lane**, **one provider slot per backend** (with batch nested in a 15-RPM sublimit), and **one bitmap memory envelope**. All three protect bounded memory and crash safety on 6–8GB devices; none should be parallelized away (F.4).
2. The largest dead-time structures are (i) batch's whole-chapter OCR (and AI analysis) before any translation, (ii) the 210s manual attach that cannot succeed during that preflight, and (iii) one-time session init charged to the first page. F.1–F.3 attack exactly these without touching the serialization invariants.
3. Memory governance is well-shaped: fail-fast preflight deferrals, pool-only pressure relief, tier-scaled prefetch — the one concrete gap is the unused tier-scaled held-bitmap ceiling (F.5).
4. No busy-waiting exists on hot paths beyond the 100ms chapter claim and the quota-blocked 50ms governor poll; both are bounded and cheap (F.6).
