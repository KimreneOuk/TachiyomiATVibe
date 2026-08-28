# T903 Technical Lead — Batch (Pre-Translate) Pipeline Architecture

Investigation of the committed tree at HEAD 926ae00 (T902 phases A/B/C landed).
All file:line references are from this tree unless stated otherwise. Evidence
classes: VERIFIED (read in source, sometimes test-backed), STRONG INFERENCE,
ASSUMPTION, UNKNOWN, CONTRADICTION.

Prior T901 findings were re-verified where reused; anything that changed after
T902 is marked.

---

## 1. End-to-end flow (life of one batch translation)

Ordered stage list. `L` = TranslationPipeline.kt unless another file is named.

1. **User confirm (manga screen).** Long-press chapter → Translate action. If the
   `translationConfirmPretranslate` pref is set, a read-only settings-review dialog
   shows first (`MangaScreenModel.kt:829-843`, dialog `:926-943`); otherwise
   `confirmChapterTranslation` runs directly (`MangaScreenModel.kt:946-991`).
   It verifies the chapter is downloaded (probe moved off main by T902 A1),
   then either queues or, on a same-source TRANSLATING conflict, shows
   `Dialog.RunningTranslationConflict` (`:986-996`); confirm →
   `confirmReplaceRunningChapter` cancels the running chapter and queues the new
   one (`:1003-1014`). VERIFIED.

2. **Enqueue + queue arbitration.** `TranslationManager.translateChapter(s)`
   (`TranslationManager.kt:319-335`): shuts down the chapter's rolling-auto
   coordinator, evicts stale QUEUE entries of the same source, `queueChapter`
   (persists queue order to disk, `ChapterTranslator.kt:141-164,342-371`),
   `startTranslation()` which also starts `TranslationForegroundService` when any
   entry is QUEUE/TRANSLATING (`TranslationManager.kt:289-296`). The translator
   job arbitrates **one chapter at a time, globally**: first source group's first
   chapter (`ChapterTranslator.kt:250-286`, filter at `:254-258`). VERIFIED.

3. **Chapter setup.** `translateChapterInternal` (`ChapterTranslator.kt:377-509`):
   resolve/open shared `ChapterTranslationStore`, locate the download dir, open ONE
   shared archive reader for cbz/zip (mmap; no per-page decompression, `:443-460`),
   natural-order the pages, `store.preRegisterPages(...)` (sets the trusted
   expected page count in the manifest), create the batch progress tracker, then
   call `pipeline.translateBatch(...)` (`:488-496`). VERIFIED.

4. **Batch entry, engine + planning preflight** (`translateBatch`, L:1150).
   - `store.beginGeneration("batch start…")` (L:1169).
   - Engine build under the native lane (`<engine-setup>` permit, L:1175-1192).
   - **Parallel source-fingerprint hashing of every page stream** on IO
     (L:1259-1265) — pure I/O preflight; a page whose bytes changed cannot reuse
     old artifacts.
   - `PageWorkPlanner.planChapter` builds per-page stage plans
     (OCR/TRANSLATION/INPAINT/LAYOUT → RUN / REUSE / TERMINAL_COMPLETE / FAILED /
     WAIT_FOR_DEPENDENCY) from the durable store + fingerprints (L:1287-1298).
   - `BatchContextFrontier.seed` folds the durable natural-order prefix so resume
     restores rolling context; a non-textless terminal gap in the prefix is
     remembered (L:1303-1324; `BatchContextFrontier.kt:34-48`). VERIFIED.

5. **Chunk discovery loop (OCR admission, serial).**
   `SequentialBatchCoordinator.runPass1` (`SequentialBatchCoordinator.kt:329-386`)
   walks pages in natural order:
   - `runOcrStage` (L:2008-2174): acquire BATCH page-stage lease (reader-owned
     pages are deferred, L:2016-2025); evaluate `resumeGate` (SKIP_ALL →
     metadata-only ref, no decode; INPAINT_ONLY → no OCR re-run; FULL → decode +
     `analyzePage`); OCR blocks are durably persisted BEFORE inpaint via
     `store.mergeOcr` (L:4072-4086; analyze at L:3981-4096).
   - `translatorWorker.admit(ref)` feeds the page into the `StreamingChunkPlanner`
     — **no provider call**; the planner buffers blocks into a token-budget
     envelope and returns `PROBE` for the page that flushed the previous envelope
     (L:2384-2388, L:2504-2519). That probe page is retained OCR-only until the
     current chunk is terminal (`SequentialBatchCoordinator.kt:333-379`). VERIFIED.

6. **Chunk barrier (processChunk).** When a boundary is reached the coordinator
   runs (`SequentialBatchCoordinator.kt:103-327`):
   - starts the per-page **render join** job (awaits nativeGate ∧ translationGate
     per page, then `awaitAndRender` → `tryRender`, `:129-155`);
   - starts the **translation lane** (remote translators only): AI path calls
     `completeChunk(finalChunk)` which drains buffered planner emissions
     (`:157-178` with L:2585-2629); standard per-page path translates inline;
   - the main coroutine runs **inpaint serially on the native lane** for each
     page, reusing the OCR handoff bitmap (`:290-320`; L:2180-2340 — reuse
     shortcuts at L:2202-2226, inpaint + downscaled-OOM recovery, cleaned-bitmap
     JPEG publication via `persistCleanedBitmap` L:2303-2324, then `holdCleaned`
     bounded registry L:2338).
   - next chunk's OCR starts only after translation lane AND render join both
     complete (strict barrier, `:322-323,385`). VERIFIED.

7. **AI envelope request + merge.** `completeChunk` → `processAiEmission`
   (L:2569-2583) → under the process-wide provider mutex
   `SharedProviderRequestAdmission.withRequest` (L:2571) →
   `translateChunkAi` (L:1724-1961):
   - context-gap pre-check fails pages after a terminal gap (L:1732-1757);
   - builds glossary text + rolling context into the chunk
     (`TranslationContextChunkPlanner.withRollingContext`, L:1759-1766);
   - marks pages' translation RUNNING (guarded, lease-fenced, L:1768-1801);
   - ONE provider request via `translateAiChunkWithAdaptiveRetry`
     (L:1804-1811; see §4);
   - commits pages **in natural order**: structural refusal → page fails alone;
     `TranslationBlockValidation.applyTo` → READY/PARTIAL/FAILED per page;
     guarded durable commit per page; `recordContextPage` advances the frontier;
     `tryRender` per committed page (L:1812-1883);
   - folds committed pairs into the chapter glossary and persists it (L:1884-1894);
   - any non-persistence exception fails the envelope's pages durably via
     `markBatchTranslationFailed` + `abortBatchCandidate` (L:1918-1960;
     `markBatchTranslationFailed` at L:2748-2769). VERIFIED.

8. **Render + publication.** `tryRender` (L:1564-1713) under a per-page mutex:
   joins translation READY/PARTIAL with inpaint READY/TEXTLESS, consumes the held
   cleaned bitmap (or reloads `.cleaned.jpg` on spill/SKIP_ALL resume), recomputes
   render colors, commits `RenderStagePatch` via `store.mergeRender`, drains
   retired cleaned files, releases the BATCH lease at terminal success/failure.
   The durable promotion to the committed artifact bundle happens inside the
   store (`ChapterArtifactStore.promoteLiveCandidate`, see §3). VERIFIED.

9. **Chapter finish.** After `runPass1` returns: `BatchProgressReconciler.reconcile`
   over the durable store (L:2706-2710; `BatchProgressReconciler.kt:25-100`) —
   counts done/failed/partial, computes stranded pages; stranded pages are
   durably persisted FAILED (L:2713-2724); `store.flush()`; `tracker.finish`;
   release all BATCH leases (L:2725-2730). The `finally` block (L:2731-2744)
   cancels any remaining stage work, releases leases, flushes on NonCancellable,
   sweeps artifact retention, and triggers the orphaned-cleaned-image sweep
   (`onBatchClosed` → `TranslationManager.kt:138-150, 937-967`). The queue then
   advances to the next chapter (`ChapterTranslator.kt:288-309,504-510`). VERIFIED.

---

## 2. Scheduling model

### Sequential vs concurrent (VERIFIED)

| Work | Concurrency |
|---|---|
| Chapters | strictly 1 at a time, globally (`ChapterTranslator.kt:254-267`) |
| OCR admission / discovery | serial, one page at a time on the native lane |
| Inpaint | serial on the native lane, per chunk, after the chunk's OCR barrier |
| AI translation | ONE ordered lane per chunk; process-wide mutex also excludes standard-path batch requests and (in theory) reader requests — see caveat |
| Render | per-page join; a render job per chunk awaits native+translation gates in natural order |
| OCR(next chunk) ∩ AI/inpaint(current chunk) | NO overlap — strict barrier (`SequentialBatchCoordinator.kt:385`) |
| AI request ∩ inpaint (same chunk) | YES, overlap allowed for remote translators (translationJob async at `:157-178` while the main coroutine inpaints at `:290-320`) |
| Local compute (ML Kit) | everything page-serial, chunk = 1 page (`SequentialBatchCoordinator.kt:42-46`) |
| Standard remote (non-AI) translators | per-page translation lane + native lookahead chunks of 7 pages (`MAX_NATIVE_LOOKAHEAD_PAGES=6`, `:46,412`) |

### Lanes and limits

- **Native lane**: a single `NativeRunQuarantine` mutex admitting all decode/OCR/
  inpaint/engine work for batch AND reader (`TranslationPipeline.kt:216-217,237-268`).
  Batch hold times: `SINGLE_PAGE_TIMEOUT_MS = 120_000` per page OCR/inpaint,
  `ONNX_PHASE_TIMEOUT_MS = 90_000` engine setup (L:144,165). VERIFIED.
- **Provider lane**: `SharedProviderRequestAdmission` — a process-wide `Mutex`
  (`TranslationStageContracts.kt:195-206`). Currently taken ONLY by the batch
  paths (L:2524 standard, L:2571 AI envelope; grep over `app/src/main` confirms
  these two call sites only). The reader manual/auto AI calls do NOT take it
  (L:3216-3250 unwrapped) — still true post-T902 (T902 explicitly left provider-
  lane sharing out of scope). So during a batch, a reader page can issue a
  concurrent Gemini call. VERIFIED (T901 D1 re-confirmed on this tree).
- **Held-bitmap registry**: ≤4 cleaned bitmaps AND ≤48 MB for render reuse;
  overflow spills to disk and render reloads the `.cleaned.jpg`
  (L:175-185,1529-1551). VERIFIED.
- **Stage advancement**: the coordinator is the only driver — a stage advances
  when the previous stage's gates complete; there is no timer. The per-chunk
  gates are `CompletableDeferred`s completed in `finally` blocks on both lanes
  (`SequentialBatchCoordinator.kt:115-127`; L:2636-2660). `BatchResumeGateDecider`
  is not a runtime scheduler — it decides per-page resume depth at OCR entry
  (L:1446-1527; `BatchResumeGateDecider.kt:32-48`, planned path takes precedence
  at L:1447-1485). `BatchProgressReconciler` runs once at chapter end (§1 step 9).
  VERIFIED.
- **Foreground service**: `TranslationForegroundService` (dataSync type) starts
  when any queue entry is QUEUE/TRANSLATING (`TranslationManager.kt:289-296`),
  polls queue state every 1000 ms and stops itself when the policy says idle
  (`TranslationForegroundService.kt:72-87,116`; policy
  `BatchTranslationForegroundPolicy.kt:7-9`). `START_NOT_STICKY` — restart does
  NOT auto-resume OCR/LLM (`:55-61`). VERIFIED.

---

## 3. I/O model (post-T902)

All durable state lives in the per-chapter artifact tree under the manga dir:
`X_artifacts/…` manifest + page snapshots + glossary, plus versioned
`*.cleaned.<version>.jpg` files in the companion images dir
(`ChapterArtifactLayout`; cleaned writer at L:3628-3641).

**Write mechanism (every JSON document):** `AtomicChapterDocuments.publish` =
write `.tmp` → read back + validate → rotate current to `.bak` → rename tmp over
primary (`ChapterDocumentIo.kt:189-213`). Each publish is therefore ≥2 writes +
1 read + 2 renames through SAF. VERIFIED.

**Cadence per boundary (VERIFIED unless noted):**

- **Per accepted store mutation** (`updatePageGuarded` → `publishLocked` →
  `persistArtifactMutationLocked`, `ChapterTranslationStore.kt:544-574,1337-1357,
  1372-1429`): a *durable* update (gate `shouldPersistUpdate` `:1999-2025` — only
  blocks/cleaned/failed/rendered content, not RUNNING/PENDING placeholders) writes
  a full **candidate page-snapshot JSON + full manifest JSON** (page registration
  writes the manifest again when new pages join). Manifest rewrite per mutation
  remains O(chapter size); T902 B2 reduced the *number* of registrations
  (batch pre-registration at chapter start, L:474 / store `:1402-1428`) but the
  manifest is still rewritten on every durable page transition (OCR persist,
  translation commit, inpaint ready, render commit, failure).
- **Per rendered page** (`promoteLiveCandidate`, `ChapterArtifactStore.kt:358-464`):
  candidate snapshot (re-)write + full committed snapshot write + generation-record
  JSON + manifest rewrite — 4 document publishes ≈ 8 SAF writes + reads/renames.
  The T901 "double snapshot write" is still visible (candidate + committed hold
  the same content, `:397-407`). VERIFIED.
- **Per inpaint**: one cleaned JPEG (quality 90, verified non-empty) + commit
  patch (L:3603-3662; `CleanedImagePublisher.kt:24-87`).
- **Per AI envelope (chunk commit)**: glossary sidecar publish (new versioned
  glossary JSON + manifest rewrite for the pointer) at L:1894 →
  `ChapterTranslationStore.updateGlossary` (`:1897-1941`) →
  `ChapterArtifactStore.publishGlossary` (`:167-184`). Plus per-page translation
  commits inside the envelope (each a durable candidate write if content-bearing).
- **Chapter boundaries**: `store.flush()` after stranded-page persistence
  (L:2725), retention sweep once per batch close (`reconcileArtifactRetention`,
  L:2742; `ChapterArtifactStore.kt:826+`), orphaned-cleaned-image sweep (≤64
  files/chapter, `TranslationManager.kt:65,937-967`).
- **Queue**: the ordered chapter-id list is persisted after every queue mutation
  (`ChapterTranslator.kt:141-164,582-612`).
- **Legacy flat translation.json**: frozen after artifact cutover; read/recovery
  and one-way migration only (T902 C1-C4; `persistLocked` `:2027-2038`). VERIFIED.

Net: a 60-page chapter with ~8 envelopes produces on the order of
60×(OCR+inpaint+render commits ≈ 3-5 durable mutations each) + 8 glossary
publishes + per-mutation manifest rewrites — i.e. **hundreds of SAF document
publishes, each ~3 file operations** — concentrated on the batch's critical path
(store writes happen inside the provider mutex for AI envelopes, L:2571).
STRONG INFERENCE for the totals; mechanism VERIFIED.

---

## 4. AI request volume and pacing

### Chunk sizing (VERIFIED)

`StreamingChunkPlanner` greedily packs **whole pages** into an envelope until the
token budget is hit (`StreamingChunkPlanner.kt:65-139`):

- budget = `MAX_CONTEXT_TOKENS(8192) − SAFETY_MARGIN(512) − MIN_OUTPUT_TOKENS(256)
  − responseOverhead(34 + 24·pages + 8·blocks)` (`:214-217`;
  `TranslationContextChunkPlanner.kt:20-41,178-185`).
- accumulated prompt starts at `PROMPT_OVERHEAD_TOKENS = 1400`; each block costs
  `tokens(pageKey)+8+tokens(block.text)` (cl100k via jtokkit,
  `StreamingChunkPlanner.kt:209-212`; `TranslationContextChunkPlanner.kt:142-148`).
- effective block budget ≈ 8192−512−256−1400 ≈ **6024 tokens** minus response
  overhead → typically **~3-15 pages per envelope** depending on dialogue density
  (dense pages flush earlier; a single over-budget page is rejected outright,
  `StreamingChunkPlanner.kt:87-88,107-114`). Pages-per-chunk is token-bound, not
  count-bound. LM Studio profile: 16 000 context (`TranslationContextChunkPlanner.kt:195-201`).
- Rolling context + glossary are capped at 1500 tokens / 32 pairs
  (`TranslationContextChunkPlanner.kt:38-41`).

**Calls per chapter (STRONG INFERENCE):** one `generateContent` POST per envelope
+ final flush (`completeChunk`, L:2585-2607). A 60-page manga chapter ≈
**5-20 envelopes → 5-20 Gemini calls minimum**, plus HTTP-level retries. Free-tier
rate limits (RPM/TPM) are therefore hit within the first envelopes of a chapter.

### Pacing

- **None between requests.** As soon as a chunk barrier releases, the envelope is
  sent; the next chunk's OCR runs, then its envelope fires immediately. The only
  pacing that exists is (a) the strict barrier itself and (b) backoff sleeps
  after failures. There is no RPM/TPM-aware throttle, no min-interval, no
  token-bucket. VERIFIED (absence verified by reading `completeChunk` /
  `translateChunkAi` / `TranslationRetry`).
- Envelopes also serialize behind the process-wide `SharedProviderRequestAdmission`
  mutex (L:2571) — which additionally holds guarded store writes, renders and the
  glossary persist, not just HTTP (T901 D7, re-verified).

### Retries

- **HTTP level** (`withTranslationRetry`, `TranslationRetry.kt:26-103`): up to
  **3 attempts**, transient = IOException or message containing 429/rate-limit/
  5xx/timeout/overloaded (`:112-131`); backoff = `1000ms·2^(n−1)` + 0-500 ms
  jitter, capped at **30 s**, Gemini `Retry-After` header honored
  (`GeminiTranslator.kt:148,155`; `TranslationRetry.kt:83-85`). All four AI
  backends share this (`GeminiTranslator.kt:82-91` etc.). VERIFIED.
- **Envelope level**: **exactly ONE request per envelope — no envelope retry.**
  `translateAiChunkWithAdaptiveRetry` performs a single `translateContextual`
  call; missing/untranslated blocks are logged
  (`stage2_retry_plan reason=page_atomic_partial`) and NOT re-requested
  (`AiTranslationRetryController.kt:25-107`, esp. `:88-106`). Missing output
  commits PARTIAL per page. VERIFIED.
- **Exhaustion**: a thrown HTTP failure (after 3 attempts) or a parse/structural
  failure propagates to `translateChunkAi`'s catch → every page of the envelope
  is durably FAILED via `markBatchTranslationFailed` (L:2748-2769: FAILED status,
  reason, `recordAttemptFailure` charge), aborted (`abortBatchCandidate`,
  L:1553-1562), and recorded into the frontier as a terminal failure → later
  pages of the chapter are failed with "blocked by non-textless terminal context
  gap" (L:1732-1757, L:2482-2500; `BatchContextFrontier.kt:50-79`). VERIFIED
  (T901 D3 gap poisoning still present in this tree).
- Per-page retry budget across runs: `MAX_STAGE_RETRIES = 2`
  (`PageTranslation.kt:274`); `hasExhaustedRetries` keys off in-memory
  `attemptCount` which resets per process (`PageTranslation.kt:106-131`), so a
  restart re-admits previously failed pages. VERIFIED.

---

## 5. Contrast with manual / rolling-auto

### Shared machinery (VERIFIED)

- Same `TranslationPipeline` engines (ONNX OCR/inpaint), same `GeminiTranslator`,
  same `ChapterTranslationStore` instance per chapter, same native-lane
  quarantine, same page-lease system (origins differ: BATCH vs READER_ADHOC,
  `TranslationStageContracts.kt:16-19`), same glossary sidecar, same artifact
  store and cleaned-image publication (`CleanedImagePublisher` used by both).

### Structural divergence (VERIFIED)

| Aspect | Batch (pre-translate) | Reader manual / rolling auto |
|---|---|---|
| Entry | `MangaScreenModel` → queue → `translateBatch` (L:1150) | `ReaderViewModel.kt:2096-2210` → `TranslationScheduler.translatePage` (`TranslationScheduler.kt:568-630`) / `updateAutoWindow` (`:139-208`, `ReaderViewModel.kt:1221`) → `runSinglePageBoundary` (L:701-767) / `prepareSinglePage`+`translatePreparedPage` (L:860+,995+) |
| Schedule | SequentialBatchCoordinator, chunk barriers, token-planner envelopes (BATCH_V1 protocol) | one page at a time; no coordinator; rolling window reconciles around the viewport |
| Request shape | multi-page BATCH_V1 envelope, ≤8 192-token budget | single-page LEGACY-protocol chunk with glossary + recent pairs (L:3216-3250) |
| Provider mutex | taken (L:2524/2571) | NOT taken (L:3218-3250 unwrapped; grep-verified) |
| Partial results | committed PARTIAL, no re-request | up to 2 local re-requests of missing blocks (`SINGLE_PAGE_PARTIAL_MAX_RETRIES`, L:156,3280-3295) |
| Failure blast radius | envelope → its pages; gap → chapter tail | single page only |

### Why manual/auto cope while batch stresses the system (mechanics)

1. **Request rate**: manual is user-paced (seconds between pages); auto is
   viewport-paced (a handful of pages ahead, `RollingAutoCoordinator`). Batch
   sustains one envelope after another with zero idle spacing → free-tier
   Gemini 429s arrive early, and every 429 freezes the *whole chapter's* pipeline
   because the strict barrier makes OCR wait on the translation lane (and the
   Retry-After sleep happens inside that lane, up to 30 s ×2). Manual pages just
   wait out their own backoff. VERIFIED mechanics; rate observation STRONG INFERENCE.
2. **Same failures, different cost**: a single dense/over-budget page or one
   exhausted envelope terminally fails later chapter pages via the context-gap
   rule (§4); in the reader the same page fails alone. VERIFIED.
3. **I/O amplification**: batch writes the manifest + snapshots per stage
   transition for every page in order (§3); manual writes the same documents but
   only for the pages the user actually visits, at human pace. VERIFIED.
4. **Shared lanes**: both contend for the single native lane and (untaken on the
   reader side) the provider lane; when a batch runs, reader pages queue behind
   batch OCR/inpaint (≤120 s hold) and vice versa — manual-only sessions never
   see this contention. VERIFIED.
5. **Memory**: batch holds up to 4 cleaned bitmaps (≤48 MB) + one probe source
   bitmap across a whole chunk (incl. backoff sleeps) + chapter-wide plans;
   manual holds one page's bitmaps transiently. VERIFIED
   (`SequentialBatchCoordinator.kt:333-340`; L:175-185).

---

## 6. Resume / cancel / restart

- **App restart mid-batch**: the queue is persisted after every mutation and
  rehydrated on launch as status QUEUE — the user must press Start; nothing
  auto-runs (`ChapterTranslator.kt:152-175,137-164`). Page leases are in-memory
  and simply gone; stranded RUNNING statuses are treated as FULL work by the
  resume gate (`BatchResumeGateDecider.kt:37-40`). Durable artifacts (OCR blocks,
  masks, cleaned images, translations, committed bundles) are reused via the
  stage plans (`PageWorkPlanner`), including physical revalidation that a
  referenced `.cleaned.jpg` actually exists (L:1454-1477, L:2190-2226).
  Chapter status certification is fail-safe post-T902: missing expected pages
  without a durable failure certify READY_WITH_WARNINGS, not ERROR
  (`ChapterTranslationStore.artifactStatus`, `:1831-1882`, esp. `:1874-1881`).
  VERIFIED.
- **Cancel/dequeue while running**: removing from queue cancels the job; the
  cancellation path skips the end-of-run reconciler, flushes the store, and the
  outer finally releases every BATCH lease and flushes on NonCancellable
  (L:2731-2744; `ChapterTranslator.kt:511-520`). Tracker aborts only if the
  translation was actually removed from the queue (pause keeps it resumable,
  `ChapterTranslator.kt:512-519`). Replace-conflict path:
  `cancelRunningChapterForReplace` joins page cancellations, clears transient
  queue pages (committed artifacts preserved), drops the entry
  (`TranslationManager.kt:393-407`). VERIFIED.
- **Resume gate (summary)**: at each page's OCR entry — planned stage decisions
  (fingerprints incl. source-byte hash, engine, inpaint mode) →
  SKIP_ALL (render/nothing) / INPAINT_ONLY / FULL; translation lane separately
  honors REUSE/TERMINAL_COMPLETE/WAIT_FOR_DEPENDENCY decisions so completed work
  is never re-requested (L:2421-2455). VERIFIED.

---

## Key numbers

| Quantity | Value | Evidence |
|---|---|---|
| Context budget per envelope | 8 192 tokens (DEFAULT), 16 000 (LM Studio) | TranslationContextChunkPlanner.kt:20,195-201 |
| Prompt overhead / safety / min output | 1 400 / 512 / 256 tokens | TranslationContextChunkPlanner.kt:22-26 |
| Response envelope overhead | 34 + 24/page + 8/block tokens | TranslationContextChunkPlanner.kt:30-33,178-185 |
| Effective block budget per envelope | ≈ 6 024 tokens − overhead | StreamingChunkPlanner.kt:214-217 |
| Pages per envelope | token-bound (~3-15 typical; estimate) | StreamingChunkPlanner.kt:65-139 |
| Gemini calls per chapter | ≈ envelopes ≈ 5-20 for 60 pages (+retries; estimate) | L:2585-2607; §4 |
| HTTP retry attempts / backoff | 3 / 1 s·2ⁿ + ≤500 ms jitter, cap 30 s, Retry-After honored | TranslationRetry.kt:26-103 |
| Envelope-level retries | 1 (none; missing → PARTIAL) | AiTranslationRetryController.kt:25-107 |
| Manual PARTIAL retries | 2 (reader only) | TranslationPipeline.kt:156,3280-3295 |
| Concurrency: chapters | 1 (global) | ChapterTranslator.kt:254-267 |
| Concurrency: native lane | 1 (batch+reader shared) | TranslationPipeline.kt:216-217,237-268 |
| Concurrency: provider lane | 1 mutex; batch-only today | TranslationStageContracts.kt:195-206; L:2524,2571 |
| Standard-remote chunk size | 7 pages (lookahead 6) | SequentialBatchCoordinator.kt:46,412 |
| Native hold timeout | 120 s/page (batch OCR/inpaint), 90 s engine setup | TranslationPipeline.kt:144,165 |
| Held cleaned bitmaps | ≤ 4 and ≤ 48 MB | TranslationPipeline.kt:175-185 |
| OkHttp timeouts | 60 s connect/read/write | GeminiTranslator.kt:38-42 |
| Manifest/page-snapshot publishes | per durable stage mutation + 4 docs per rendered page | ChapterTranslationStore.kt:544-574,1337-1429; ChapterArtifactStore.kt:358-464 |
| Glossary publish | per envelope commit (atomic, versioned) | TranslationPipeline.kt:1894; ChapterArtifactStore.kt:167-184 |
| Store persist debounce (legacy/flat path) | 250 ms | ChapterTranslationStore.kt:2101 |
| Foreground service poll | 1 000 ms; START_NOT_STICKY | TranslationForegroundService.kt:55-61,116 |
| Orphan sweep cap | 64 images/chapter | TranslationManager.kt:65 |
| Max stage retries (per-run budget) | 2 (attemptCount in-memory, resets per process) | PageTranslation.kt:274,106-131 |

## Open questions for the failure-mode auditor

1. **Provider-lane asymmetry**: reader AI calls still bypass
   `SharedProviderRequestAdmission` while batch holds it across store I/O +
   backoff sleeps (L:2571, L:3218-3250). With free-tier Gemini, does an open
   reader during batch measurably multiply 429s and freeze chunk barriers
   (Retry-After up to 30 s ×2 inside the strict barrier)?
2. **Gap poisoning end-state**: a deterministically over-budget page rejects
   (StreamingChunkPlanner.kt:87-114) → terminal gap → every later page durably
   FAILED *this run*. `attemptCount` resets per process, so every future run
   re-burns the whole tail. Is the chapter then permanently ERROR-certified
   (durable failures), and does anything ever downgrade the gap?
3. **Reader-deferred pages in-run**: `runOcrStage` defers reader-owned pages
   without a lease identity (L:2016-2025); the end-of-run reconciler counts them
   failed but the guarded stranded-page write is rejected ("batch page lease
   missing", L:1383-1384) — verify whether the chapter queue entry ends ERROR
   while the durable artifact status later disagrees (READY_WITH_WARNINGS).
4. **Manifest write amplification on SAF**: each durable mutation rewrites the
   full manifest + candidate snapshot (§3). On slow SAF providers, does this
   starve the render join / provider mutex (envelope store writes run inside
   `withRequest`)? Any observed ANR/watchdog?
5. **Probe memory pin**: the retained probe page pins its decoded source bitmap
   across the prior chunk incl. all backoff sleeps (SequentialBatchCoordinator.kt:
   333-340). Worst-case long-strip page ~48 MB — how often does this trip the
   low-memory decode-deferral path (L:2094-2109) and strand pages FAILED?
6. **Envelope size vs free-tier TPM**: 8 192-token envelopes with ~6 k prompt +
   up to ~2 k output approach free-tier per-minute token ceilings quickly.
   Should the failure-mode audit quantify actual 429 rates per chapter from
   `TranslationRetry` logs to size a pacing fix?
7. **Standard (non-AI) batch path** wraps each per-page provider call in the
   provider mutex (L:2524) — with 7-page chunks this serializes ~7 requests
   back-to-back per chunk with no pacing; same free-tier concern.
