# T901 Specialist A — Batch scheduling vs design intent (symptom 1)

Scope: `MangaScreenModel`/`TranslationManager.startTranslation()` → queue arbitration →
`SequentialBatchCoordinator` → `StreamingChunkPlanner` → translation lane → commit,
contrasted with the reader manual and rolling-auto paths. Working tree as-is
(dirty: `MangaScreenModel.kt`, `DownloadCache.kt` — neither alters core scheduling).

Evidence classes: VERIFIED (code+line, sometimes test), STRONG INFERENCE, ASSUMPTION, UNKNOWN.

---

## 1. Executive summary

- The sequential chunk pipeline IS implemented and its barriers work exactly as the
  2026-08-21/08-24 plans describe (page-atomic envelopes, one probe lookahead, AI request
  overlapping only the current chunk's inpaint, render join per page). Tests prove it.
  VERIFIED.
- The dominant reason batch feels "not optimized" is structural, not a broken barrier:
  next-chunk OCR cannot start until the current chunk's render join completes, so the AI
  provider sits idle during every chunk's OCR phase and the native lane sits idle during
  every AI stall (429 Retry-After up to 30 s ×2 retries freezes the whole chapter pipeline).
  VERIFIED — the design's own strict barrier is the throughput limiter.
- The "process-wide provider lane" (`SharedProviderRequestAdmission`) is only wired into
  the batch path; reader manual/auto AI requests bypass it, so batch and reader issue
  CONCURRENT provider calls — increasing 429 pressure on free-tier Gemini. VERIFIED.
- Batch and all reader paths share ONE native lane mutex (`NativeRunQuarantine`); a batch
  run occupies it nearly continuously, so reader pages queue behind batch OCR/inpaint
  (and vice versa); a timed-out native call keeps the lane until real exit (unbounded).
  One terminally-failed page (e.g. over-budget dense page — deterministic) poisons every
  later page of the chapter via the context-gap rule. All VERIFIED.
- No coordinator deadlock/livelock found; cancellation and resume paths release leases and
  reuse durable artifacts correctly. The real defects are cross-path contention, gap
  poisoning, reader/batch lease interop stranding pages as chapter ERROR, and the
  throughput ceiling of the strict barrier itself.

## 2. Actual control flow (as built)

### 2.1 Batch entry and queue arbitration

1. `MangaScreenModel.confirmChapterTranslation` (MangaScreenModel.kt:946-991) → download
   guard (`skipCache = true`, the dirty edit) → `TranslationManager.translateChapter`
   (TranslationManager.kt:272-278): shuts down the chapter's rolling-auto coordinator,
   evicts stale QUEUE chapters of the same source, `translator.queueChapter`, then
   `startTranslation()` (TranslationManager.kt:242-249) which starts
   `TranslationForegroundService` if any entry is QUEUE/TRANSLATING.
2. `ChapterTranslator.start` → `launchTranslatorJob` (ChapterTranslator.kt:250-286).
   Arbitration: `queueState.transformLatest { groupBy { source }.take(1).first() }` —
   **exactly one chapter translates at a time, globally, even across different sources**
   (ChapterTranslator.kt:254-267). The flow waits for that chapter's ERROR or removal
   before emitting the next.
3. `translateChapterInternal` (ChapterTranslator.kt:377-527): resolves the shared
   `ChapterTranslationStore` via `pipeline.activeStoreResolver`, opens one shared
   `ArchiveReader` for archive chapters, orders pages by natural order
   (`ResumeOrdering.naturalOrder` — reader position is intentionally NOT a batch input),
   pre-registers pages, creates the progress tracker, then calls
   `pipeline.translateBatch(...)` (ChapterTranslator.kt:480).
   On queue rehydration: `restoreQueue()` rehydrates persisted ids as QUEUE — user must
   press Start (ChapterTranslator.kt:152-175). OOM policy deliberately never cancels the
   job (ChapterTranslator.kt:227-236).

### 2.2 translateBatch — the chunk pipeline (TranslationPipeline.kt:1145-2748)

1. `store.beginGeneration("batch start")` (1164); engine rebuild under the native lane
   with `pageKey="<engine-setup>"` (1170-1187).
2. Preflight: parallel source-fingerprint hashing of every page stream (1254-1260);
   `PageWorkPlanner.planChapter` builds per-page stage plans (1282-1293);
   `BatchContextFrontier.seed` folds the durable natural-order prefix (1298-1319).
3. Three lane workers are constructed and handed to `SequentialBatchCoordinator`
   (2657-2661); `coordinator.runPass1(orderedPages, computeClass)` (2669) is the entire
   schedule. There is no other batch driver (InactivityFlusher / DynamicPageChunker /
   PageAtomicChunkPlanner no longer exist — grep VERIFIED).

### 2.3 SequentialBatchCoordinator.runPass1 (SequentialBatchCoordinator.kt:34-399)

```
while (cursor < pages || retainedProbe != null):
    chunk = [] (+ retainedProbe from previous boundary)
    while cursor < pages && !boundaryReached:          # OCR DISCOVERY LOOP (serial)
        entry = runOcr(page)                            # native lane, per page
        admission = translatorWorker.admit(entry.ref)   # AI: planner.accept (buffer only)
        if PROBE && chunk.nonEmpty: retainedProbe = entry; boundary = true
        elif PROBE && chunk.empty:   chunk += entry; boundary = true   # 1-page envelope
        elif !adaptive && chunk.size >= 7: boundary = true             # standard remote
    processChunk(chunk, finalChunk):                    # BARRIER
        renderJob    = async { per page: await nativeGate; await translationGate; awaitAndRender }
        translationJob = async (remote only) { completeChunk(finalChunk) }   # AI request HERE
        main coroutine: for each page: runInpaintStage(page, nativeHandoff)  # native lane
        await translationJob; await renderJob           # next OCR only after this
```

- `completeChunk` (TranslationPipeline.kt:2580-2624) drains buffered planner emissions →
  `processAiEmission` → `translateChunkAi` under
  `SharedProviderRequestAdmission.withRequest` (2564-2572) → one Gemini/DeepSeek/…
  envelope (`translateAiChunkWithAdaptiveRetry`), then commits pages in natural order,
  folds glossary/context, calls `tryRender` per page; final chunk flushes the planner tail.
- `translateChunkAi` (1719-1956): context-gap pre-check fails pages after a terminal gap;
  marks pages RUNNING (guarded, lease-fenced); sends the envelope; validates
  (`TranslationBlockValidation.applyTo`); commits READY/PARTIAL/FAILED per page;
  `recordContextPage` advances `BatchContextFrontier`.
- Render join: pipeline-level `nativeRenderSignals`/`translationRenderSignals` completed
  in `finally` blocks on both lanes (2631-2655); coordinator `renderJob` awaits per page
  in natural order and calls `tryRender` (1559-1708), which holds per-page render mutexes,
  merges render patches, releases the BATCH page lease at terminal publish/failure.
- Teardown: `finally` releases all remaining handoffs/bitmats/leases (2671-2745);
  `BatchProgressReconciler.reconcile` computes chapter status, persists stranded pages,
  publishes the summary sidecar, finishes the tracker (2701-2737).

### 2.4 Reader paths (manual + rolling auto) — shared nothing with the batch schedule

- Manual per-page: `ReaderViewModel` → `TranslationManager.translatePage` →
  `TranslationScheduler.translatePage` (TranslationScheduler.kt:568-630) →
  `TranslationPipeline.translateSinglePage/FromStream` → `runSinglePageBoundary`
  (TranslationPipeline.kt:646-762): one `withNativeLane` holding decode→OCR→inpaint→
  persist-cleaned (fused), then HTTP translate + render OUTSIDE the lane with a 120 s
  timeout. **Bypasses SequentialBatchCoordinator by design** (comment at 639-644).
- Rolling auto: `TranslationManager.updateAutoWindow` → `TranslationScheduler` →
  `RollingAutoCoordinator` (RollingAutoCoordinator.kt:293-560): one inline native lane
  (`prepareSinglePage`) + one translate/render consumer of a bounded prepared-page
  channel (`translatePreparedPage`), overlapping native B with translation A for
  REMOTE_IO; a Semaphore(1) compute gate serializes both for LOCAL_COMPUTE.
- Reader AI requests build a **single-page LEGACY-protocol chunk**
  (TranslationPipeline.kt:3218-3252) with rolling pairs from the store + chapter glossary;
  PARTIAL pages get up to 2 local retries (3283-3297) — a retry behavior the batch path
  does not have.

### 2.5 What batch and reader share vs. diverge

| Resource | Batch | Reader manual | Rolling auto |
|---|---|---|---|
| Native lane (`NativeRunQuarantine` mutex) | YES (OCR, inpaint, engine setup) | YES (fused page) | YES (prepare) |
| Provider lane (`SharedProviderRequestAdmission`) | YES (2519, 2566) | **NO** (3218-3252) | **NO** |
| `ChapterTranslationStore` instance | YES (via `activeStoreResolver`) | YES (same instance) | YES (same instance) |
| Page lease (`tryAcquirePageStageLease`) | BATCH origin | READER_ADHOC | READER_ADHOC |
| Chunking | token-budget page-atomic envelopes (BATCH_V1) | single page (LEGACY) | single page (LEGACY) |
| Schedule | chunk barrier coordinator | one-shot | rolling window reconcile |

VERIFIED. Cross-origin lease: `ChapterTranslationStore.tryAcquirePageStageLease` denies
the other origin (ChapterTranslationStore.kt:305-356); batch defers reader-owned pages
(TranslationPipeline.kt:2011-2018), reader "attaches" to batch-owned pages
(TranslationPipeline.kt:770-786).

## 3. Deviation table (intended vs actual)

| # | Intended (design/revision) | Actual (working tree) | Evidence | Class |
|---|---|---|---|---|
| V1 | Page-atomic envelopes packed by StreamingChunkPlanner under token budget; dense page alone; only source-over-budget fails | Implemented; static 4-page/28-block limits gone; reject reasons per page | StreamingChunkPlanner.kt:65-139; TranslationContextChunkPlanner.kt:16-76; BatchEnvelopeLimitsTest | VERIFIED conformance |
| V2 | 429 retries the SAME envelope, preserving status/delay | Implemented: ≤3 attempts, Retry-After honored, 30 s cap, envelope unchanged | TranslationRetry.kt:26-103; GeminiTranslator.kt:138-158 | VERIFIED conformance |
| V3 | One ordered translation lane; native lookahead bounded | Implemented: one translationJob per chunk; AI lookahead = 1 probe page; standard remote = 7-page chunks; local = page-serial | SequentialBatchCoordinator.kt:42-46, 412; tests 101-157 | VERIFIED conformance |
| V4 | Strict barrier: next-chunk OCR after current chunk render join | Implemented exactly (processChunk awaited before loop continues) | SequentialBatchCoordinator.kt:329-386; test 127-157 | VERIFIED conformance — but see D5 |
| V5 | OCR→inpaint native handoff with guaranteed release | Implemented (finally releases; probe released in runPass1 finally); probe pins its decoded bitmap across the prior chunk | TranslationPipeline.kt:2167, 2337-2352; SequentialBatchCoordinator.kt:224-326, 395-398 | VERIFIED; D6 memory note |
| V6 | "Process-wide provider lane shared by manual, auto, batch, and revision callers" | Only the batch path takes the mutex; reader manual/auto AI calls bypass it | TranslationStageContracts.kt:195-206 vs. grep: withRequest only at TranslationPipeline.kt:2519, 2566; reader path 3218-3252 unwrapped | **VERIFIED deviation** (D1) |
| V7 | Reader and batch must not starve each other | Both contend on ONE native-lane mutex; each hold ≤120 s, timeout keeps lane until real exit | NativeRunQuarantine.kt:41-96; withNativeLane call sites 708, 867, 1170, 2059, 2223 | **VERIFIED deviation** (D2) |
| V8 | A failed page "fails alone" (revision: only the over-budget page fails) | A terminal non-textless page failure creates a context gap; every LATER page of the chapter fails in that run ("blocked by non-textless terminal context gap") | BatchContextFrontier.kt:50-79; TranslationPipeline.kt:1727-1752, 2477-2495 | **VERIFIED deviation** (D3) |
| V9 | 2026-08-21 design: "Partial response → retry missing output within the current chunk" | One provider call only; missing output commits PARTIAL; no in-chunk retry (superseded by revision's fail-closed policy — doc contradiction, code follows newer doc) | AiTranslationRetryController.kt:88-106; test AiTranslationRetryControllerTest (calls==1) | VERIFIED deviation vs old design (D8) |
| V10 | Batch must not lose work when reader interacts with the same chapter | Batch skips reader-owned pages without retry → reconciler strands them → chapter ERROR; reverse: rolling-auto slots permanently marked Failed while batch owns pages | TranslationPipeline.kt:2011-2018; BatchProgressReconciler.kt:53-84; RollingAutoCoordinator.kt:515-518, 567-571 | **VERIFIED deviation** (D4) |
| V11 | Memory: no bitmap retained beyond probe; cleaned-bitmap registry bounded | Held-cleaned registry bounded (4 count / 48 MB); probe's decoded SOURCE bitmap pinned for whole prior chunk incl. 429 backoff | TranslationPipeline.kt:174-184, 1524-1546; SequentialBatchCoordinator.kt:333-340 | VERIFIED; D6 |

## 4. Defects / design gaps

Severity: blocker / major / minor. Confidence: High/Med.

### D1 — Provider lane not actually shared (major, High, VERIFIED)
`SharedProviderRequestAdmission` ("Process-wide provider lane shared by manual, auto,
batch, and revision callers", TranslationStageContracts.kt:201-206) is taken only by the
batch paths (TranslationPipeline.kt:2519 standard, 2566 AI envelope). The reader manual
path (`translateSinglePageHttpRender` → `runTranslate`, TranslationPipeline.kt:3218-3252)
and the rolling-auto path (`translatePreparedPage` → same function, 1080-1083) call
`translateContextual`/`translatePage` with NO admission. Consequences: (a) batch envelope
and reader page requests hit the provider concurrently — on free-tier Gemini this
multiplies 429s; (b) each 429 freezes the batch's whole chunk barrier for Retry-After
(≤30 s, up to 2 retries) while the reader keeps firing more requests — a self-reinforcing
rate-limit loop. Explains symptom 1 (scheduling degrades exactly when the provider is
constrained). Related: the batch holds the lane for the ENTIRE `translateChunkAi`
(store writes, renders, glossary persist — not just HTTP) and sleeps backoff inside it
(TranslationPipeline.kt:2564-2572 + TranslationRetry.kt:99).

### D2 — Single native lane shared by batch and all reader work (major, High, VERIFIED)
Every native phase funnels through `withNativeLane` → `NativeRunQuarantine.run`, one
`Mutex` (NativeRunQuarantine.kt:41-47). A batch run occupies it for nearly every instant
(per-page OCR in the discovery loop, per-page inpaint in processChunk, engine setup).
A reader manual/auto page therefore queues behind up to one full batch native op
(≤120 s `SINGLE_PAGE_TIMEOUT_MS`, TranslationPipeline.kt:143); conversely one reader page
stalls the batch's lane equally. On timeout, the quarantine keeps the mutex until the
native call REALLY exits (`awaitExitAndLogLate` inside `withLock`,
NativeRunQuarantine.kt:71-96) — unbounded; a genuinely hung ONNX inference freezes ALL
translation app-wide. Fair mutex means interleaving (no strict starvation), but latency
under contention is user-visible. Explains symptom 1 (batch appears to stall while the
reader is open; reader pages appear stuck while a batch runs).

### D3 — Context-gap poisoning fails the whole chapter tail (major, High, VERIFIED)
`BatchContextFrontier.record(terminalFailure=true)` sets `gapIndex`; `blocksLaterAi`
then returns true for every later page; both the admit path
(TranslationPipeline.kt:2477-2495) and `translateChunkAi` (1727-1752) mark those pages
durable FAILED ("blocked by non-textless terminal context gap"). Triggers include
provider refusal, exhausted envelope retries, and the deterministic
"page text exceeds the … AI context budget" reject (StreamingChunkPlanner.kt:87-88, 114).
A deterministic reject (one pathologically dense page) re-fails on every run, so the
chapter can NEVER reach TRANSLATED and each run burns attempt charges on every later
page. Revision intent was "only a page whose source itself cannot fit fails" — the
implementation fails that page AND everything after it in the run. Explains symptom 1
(chapters end ERROR / huge failure lists from a single bad page).

### D4 — Batch↔reader lease interop strands pages and kills rolling-auto slots (major, Med-High, VERIFIED)
Direction A: reader owns a page lease → batch `runOcrStage` defers the page
(TranslationPipeline.kt:2011-2018, ref=null) and never retries within the run →
`BatchProgressReconciler` strands it (BatchProgressReconciler.kt:53-84) → chapter ERROR.
The compensating durable write is rejected because the batch holds no lease identity for
that page (`guardedBatchUpdate` → "batch page lease missing",
TranslationPipeline.kt:1372-1379, 2708-2719). Direction B: batch owns the page →
`prepareSinglePage` returns null (TranslationPipeline.kt:864-865) →
`RollingAutoCoordinator.reconcilePass` marks the slot `Failed(retryable=true)`
(RollingAutoCoordinator.kt:515-518), but `isAdmissible` excludes Failed slots and nothing
clears them within the same window/session (567-571) — reader auto-translate silently
stops for the open chapter until pages leave and re-enter the window (scroll) or the
chapter changes. Explains symptom 1 (concurrent reader activity breaks/chapters error);
contributes to symptom 3's "does not resume" feel.

### D5 — Strict chunk barrier serializes provider and native lanes (major as design gap, High, VERIFIED)
Per design the next chunk's OCR waits for the current chunk's full render join
(SequentialBatchCoordinator.kt:385; design.md flow). Combined with:
- envelope budget `MAX_CONTEXT_TOKENS = 8_192` (TranslationContextChunkPlanner.kt:20)
  → many small envelopes per chapter,
- one AI request per chunk, sent only AFTER the whole chunk's OCR,
- 429 Retry-After sleeping inside the chunk's translation lane,
the effective schedule alternates "provider idle while OCR runs K pages" with "native
idle while the AI request (possibly + backoff) completes". Only one overlap exists
(AI request ∥ current chunk's inpaint). For a 60-page chapter at ~3 s/page OCR and
multi-second envelopes, wall time is far from any pipelined optimum. This matches the
documented design, so it is a DESIGN GAP rather than an implementation bug — but it is
the most direct root cause of the Director's "chunk with optimized scheduling does not
work as intended" perception. Explains symptom 1.

### D6 — Probe pins a full-page decoded bitmap across the prior chunk (minor, Med, VERIFIED)
The retained OCR-only probe keeps `nativeHandoff` (decoded source bitmap) from its OCR
until it enters the next chunk's inpaint (SequentialBatchCoordinator.kt:333-340, 367-379;
finally release 395-398; ref construction TranslationPipeline.kt:2158-2168). During the
prior chunk's whole processing — including 429 backoff — one extra full-resolution
bitmap (tens of MB on long-strip pages) is pinned. Bounded to one page, but raises
memory pressure and decode-deferral probability. Contributes to symptom 1 indirectly.

### D7 — Batch wraps non-HTTP work and backoff sleeps inside the provider mutex (minor, High, VERIFIED)
`withRequest { translateChunkAi(...) }` (TranslationPipeline.kt:2564-2572) covers guarded
store writes, `tryRender`, glossary persist, and `delay` backoff — not "the provider
call". Harmless today only because nothing else takes the mutex (see D1); any correct
future wiring of the reader path would then block reader translation for the whole
chunk lifecycle including disk I/O and sleeps.

### D8 — No in-chunk retry of missing output; PARTIAL commits (minor vs revision, High, VERIFIED)
`translateAiChunkWithAdaptiveRetry` makes exactly one provider call; missing pages are
logged ("stage2_retry_plan reason=page_atomic_partial") and returned without a retry
(AiTranslationRetryController.kt:88-106; test asserts calls==1). Pages commit PARTIAL →
chapter outcome READY_WITH_WARNINGS, never TRANSLATED. This CONTRADICTS design.md's
failure table ("retry missing output within the current chunk") and follows revision.md's
fail-closed policy — the two plans disagree; the code implements the newer one. The
reader single-page path still retries PARTIAL twice (TranslationPipeline.kt:3283-3297) —
an asymmetry users can feel (reader page completes, batch leaves partials).

### D9 — Silent stale-lease drop in the translation lane (minor, Med, STRONG INFERENCE)
`translate()` returns without any tracker failure or durable state when the OCR ref's
lease token/candidate ids mismatch the current identity (TranslationPipeline.kt:2387-2397)
— the page stays non-terminal and is only surfaced later as a reconciler stranding.
Rare (identity refreshed at 2156-2157), but silent queue stalling of this kind is what
"does not work as intended" reports are made of.

### D10 — Queue runs one chapter at a time, globally (info, High, VERIFIED)
Arbitration takes the first source group's first chapter only
(ChapterTranslator.kt:254-267). Multi-chapter "select all → translate" is strictly
serial; a second source's chapters wait behind the first source's entire chapter. Intended
(same-source conflict model) but central to expectations about "optimized scheduling".

Positive conformance findings (no action): textless terminal semantics
(PostOcrStageSemantics.kt:6-23), cancellation/resume lease hygiene
(TranslationPipeline.kt:2738-2746, all NonCancellable), 429 same-envelope retry (V2),
natural-order commit + per-page publication (translateChunkAi 1807-1878), physically-
present cleaned-image revalidation on resume (1452-1471), strict BATCH_V1 parser kept,
legacy planners deleted.

## 5. Root cause per Director symptom (this specialist's scope)

- **Symptom 1 — "Batch translation does not work as intended as chunk with optimized
  scheduling":** Root causes, in order of impact: D5 (strict barrier + small 8k envelopes
  = alternately idle provider/native lanes; the design itself caps throughput),
  D1 (unserialized reader provider calls → 429 storms → chunk pipeline frozen on
  Retry-After), D3 (one bad page fails the chapter tail), D4 (reader/batch lease interop
  strands pages / kills auto slots), D2 (single native lane contention). The pipeline's
  own barriers, packing, and handoffs are functioning as designed — CONFIDENCE HIGH
  (code + SequentialBatchCoordinatorTest).
- Symptoms 2/3/4: outside this report's scope (persistence / UI gating / ownership),
  though D3/D4 supply ERROR states that feed symptom 3's non-resuming behavior.

## 6. Plain-language explanation (non-technical)

- **D5:** The translator works like an assembly line that refuses to start step 1 of the
  next batch until the last step of the current batch is finished, and it carries small
  boxes. So the AI is always waiting for the scanner, and the scanner is always waiting
  for the AI. Nothing is broken; the rules themselves make it slow.
- **D1:** The app has a "one request at a time" rule for the translation service, but
  only the background batch follows it. Pages you open in the reader jump the queue and
  fire requests at the same time — so the free AI service gets twice the traffic and
  answers "slow down" more often, which then freezes the whole background chapter for
  up to half a minute at a time.
- **D2:** Reading and background translation share one single door for all heavy
  image work. Whoever is inside keeps it for up to two minutes; everyone else waits.
- **D3:** If one page is impossible to translate, every page after it in that chapter is
  declared failed for that run — one bad page ruins the report card for the rest.
- **D4:** If you are reading the chapter that the background translator is working on,
  each side thinks the other "owns" pages and gives up on them; the chapter then ends
  marked as errored even though almost everything succeeded, and the reader's
  auto-translate quietly stops until you scroll away and back.
- **D6:** While waiting, the pipeline keeps one full-size page image in memory longer
  than it needs to.
- **D8:** If the AI returns most of a page but not all of it, the background path
  accepts the partial result and moves on; the reader path asks again. Background
  chapters therefore finish "with warnings" instead of "complete".

## 7. Open questions

1. Does the Director's intended "optimized scheduling" now require cross-chunk overlap
   (next chunk's OCR during the current AI request)? The 2026-08-21 design explicitly
   forbids it; changing it is a design decision (would also re-open native lookahead
   bounds and the probe mechanism).
2. Is `MAX_CONTEXT_TOKENS = 8_192` still right for the direct Gemini API (128k+ context
   models)? Raising it shrinks envelope count and barrier overhead proportionally.
3. Should the reader join `SharedProviderRequestAdmission` (making the lane real), or
   should the batch stop holding it during backoff/disk work? Both directions change
   429 behavior and reader latency; needs a product call (D1/D7).
4. What is the observed worst-case real-exit time of a timed-out ONNX call on target
   devices? It determines whether D2's quarantine can freeze the app's translation
   indefinitely in practice.
5. Should a deterministically over-budget page (D3) downgrade to per-page failure
   without gap semantics, or trigger a smaller-model/manual-review path?
6. Device telemetry for chunk OCR time vs envelope time (and 429 frequency with the
   reader open) would quantify D5 vs D1 contributions to the perceived slowness.

---

### Appendix: key file:line index

- Entry/arbitration: TranslationManager.kt:242-278; ChapterTranslator.kt:152-175, 250-310, 377-527
- Batch core: TranslationPipeline.kt:1145-2748 (workers 2002-2655, coordinator run 2657-2670, teardown 2738-2746)
- Coordinator: SequentialBatchCoordinator.kt:34-413 (OCR loop 329-386, processChunk 103-327)
- Planner: StreamingChunkPlanner.kt:20-249; TranslationContextChunkPlanner.kt:16-235
- Retry: TranslationRetry.kt:26-131; AiTranslationRetryController.kt:25-107
- Lanes: TranslationStageContracts.kt:195-206; NativeRunQuarantine.kt:31-101
- Reader: TranslationPipeline.kt:646-1111, 3166-3307; TranslationScheduler.kt:568-630, 758-850
- Rolling auto: RollingAutoCoordinator.kt:293-560 (Failed-slot policy 407-419, 515-518, 567-571)
- Leases/reconcile: ChapterTranslationStore.kt:291-395; BatchProgressReconciler.kt:23-110; BatchContextFrontier.kt:18-94
- Tests confirming intended schedule: SequentialBatchCoordinatorTest.kt:27-205; AiTranslationRetryControllerTest.kt:12-70
