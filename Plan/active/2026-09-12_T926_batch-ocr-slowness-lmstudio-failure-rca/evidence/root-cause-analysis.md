# T926 Root-Cause Analysis — batch Detect+OCR slowness & LM Studio translation failure

Audit base: branch `t924/batch-profile-pipeline`, HEAD `d3464d8`.
All paths relative to `app/src/main/java/eu/kanade/translation/` unless
prefixed `app/...`. Verification marks: **[V]** = Main Leader verified the
cited lines directly; **[R]** = investigator-reported, not independently
re-read.

Executive summary: the Detect+OCR phase is slow because the resource
lock is a process-wide concurrency-1 gate wrapped around a strictly
page-at-a-time loop, inside which each bubble costs ~130 sequential
CPU-bound ONNX calls plus a per-page durable-I/O storm — not because of
model reloading or redundant detection (both refuted). The translation
phase fails with LM Studio because of a hard 60-second read timeout on a
blocking non-streaming request that a local model can never meet,
aggravated by a token-budget spec violation (LM Studio allowed 16k
combined instead of the directed 8k) and by cloud-style quota governance
misapplied to an unmetered LAN endpoint. The visible "trying again at
<time>" message is the pause notification's retry-at timestamp, not a
server rate limit.

---

## 1. Detect+OCR phase slowness

### 1.1 The "resource allocation / lock-out" is concurrency-1 with zero pipelining

- The native lane is a process-wide `Mutex()` — every native job (reader
  single-page OCR/inpaint, batch OCR, batch inpaint) queues behind it:
  `scheduling/NativeRunQuarantine.kt:43` (declaration), `:67`
  (`admission.withLock` in `run()`). **[V]**
- The batch preflight walks pages in a single serial `for` loop, no
  launch/async: `pipeline/batch/ChapterProfileBatchCoordinator.kt:378-525`;
  the file's own invariant states "the preflight loop is strictly serial"
  (`:108-113`). **[R]** (loop structure consistent with mutex evidence)
- `pipeline/batch/OverlapScheduler.kt:29-35` — its contract states
  detector/OCR never run there and the native lane stays strictly
  one-native-job-at-a-time process-wide; it is constructed/started only
  for the TRANSLATE phase (`pipeline/batch/BatchChapterTranslator.kt:766-812`
  **[R]**). During Detect+OCR the translation lane is idle and vice
  versa: the two lanes never overlap in this phase.
- Translation cannot begin until every page's OCR is durable
  (corpus-completeness gate): `ChapterProfileBatchCoordinator.kt:529-569`. **[R]**

Net effect: decode of page N+1, detection of page N+1, and all
translation sit idle while page N occupies the single native slot,
including its disk bookkeeping.

Note: the serial native lane is a deliberate Ten Never rule (see
T925 README), so this is a *design consequence*, not an accident. Fixes
must pipeline around the lane (see §5.2), not parallelize native
inference across pages.

### 1.2 Dominant compute cost: per-ROI autoregressive OCR on CPU

- Per bubble: 1 encoder run + 1 `decoder_init` run, then a token loop of
  up to 127 `decoder_step` session runs (position cap
  `DECODER_POSITION_COUNT = 128`, `MAX_GENERATION_LENGTH = 300`):
  `ocr/MangaOcrEngine.kt:256-295`, `:433`, `:441`. **[V]** (loop read
  directly; each step is a full `decStep.run` + vocab argmax + 2 KV-cache
  writes)
- ROIs processed strictly one at a time:
  `ocr/RoiOcrEngine.kt:24-27` (`crops.map { recognize(it) }`), called from
  the per-page loop at `recognition/RoiPageRecognitionEngine.kt:389-401`. **[R]**
- Pinned to CPU by design, ≤2 decode threads:
  `MangaOcrEngine.kt:36-38` (`executionProviderLabel = "cpu"`,
  `decoderThreadCount = min(cores/2, 2)`). **[V]**
- Arithmetic: a 20–40 bubble page ≈ 2,600–5,200 sequential ONNX
  invocations per page. **[R]** (derived from the above)

### 1.3 Tall/webtoon pages multiply inferences N×

- Aspect ≥ 2.0 → 1:1.4 sliding windows, 250 px overlap:
  `webtoon/WebtoonSlidingDetector.kt:22-24, 41-69`. **[R]**
- Detector runs once per window in a plain loop (`:152-184`; call site
  `RoiPageRecognitionEngine.kt:306`), then the segmenter runs over the
  same windows again (`:190-222`; call site `:330`), each window paying a
  full-width `Bitmap.createBitmap` crop (`:168, :206`). **[R]**
- Example: 800×8000 strip → windowH=1120, step=870 → 9 windows →
  9 detector + 9 segmenter = 18 accelerator inferences, all serial. **[R]**
- Non-webtoon pages additionally pay a third full-page 640×640 inference
  (panel detector) inside analyze: `RoiPageRecognitionEngine.kt:618,
  741-742`. **[R]**

### 1.4 Per-page durable-I/O storm on the serial critical path

Per page, strictly between its OCR and the next page's start: **[R]**

- `store.mergeOcr` (blocks + mask persist):
  `pipeline/SinglePageOnnxPhase.kt:928-951`.
- Checkpoint transaction: `@Synchronized`, publishes 3 sidecar JSONs +
  one crash-safe manifest publication (temp→validate→rotate→rename):
  `artifact/ChapterArtifactStore.kt:558-565`, `:234-237`; stale-manifest
  retry can publish twice (`:487-511`).
- `readCheckpointFingerprint` re-reads the sidecar just written:
  `ChapterProfileBatchCoordinator.kt:447` → `:2886-2894`.
- `publishRecord` per page: JSON-encode + SHA-256 + run-record sidecar +
  a second full manifest publication (`:480` → `:2924-2959`;
  `ChapterArtifactStore.kt:336-392`).
- Plus reusable-checkpoint sidecar read at loop top (`:385` →
  `:2873-2884`) and per-page lease release write (`:522`).

The manifest holds a record per page → each publication is O(chapter
size) → **O(N²) total manifest bytes for an N-page chapter**, all inside
the lock. **[R]** (arithmetic derived)

### 1.5 First-page engine-init stall ("allocating resources")

- `analyze()` lazily calls `initialize()` which creates every ONNX
  session — page detector, optional panel detector, bubble segmenter,
  MangaOCR encoder + 2 decoders, optional Paddle det
  (`RoiPageRecognitionEngine.kt:163-243, 268`). **[R]**
- QUALITY inpaint mode adds 4 AOT sessions (XNNPACK fixed/dynamic, QNN
  HTP, strict NNAPI): `inpainting/aot/AOTInpainting.kt:114-128`; QNN HTP
  cold compile is multi-second (`runtime/onnx/OnnxRuntimeProvider.kt:266-271`),
  ~300 ms warm (`AOTInpainting.kt:145`). All before page 1's first
  detection, under the admission mutex. **[R]**

### 1.6 Refuted hypotheses (Detect+OCR)

- **Per-page model loading — REFUTED.** Sessions are created once per
  engine build and reused across pages/chapters
  (`RoiPageRecognitionEngine.kt:150-156` guard; model bytes copy-cached in
  `runtime/onnx/OnnxModelStore.kt:82-131, 198-214`; engine rebuilt only on
  config change or explicit stop, `pipeline/EngineLane.kt:410-444`). No
  per-call warmup. Re-init re-pays only after user stop
  (`EngineLane.kt:301-349`). **[R]**
- **Redundant detection — REFUTED** (with two minor exceptions). Detection
  runs once per page; boxes are stashed and reused by the inpaint planner
  (`RoiPageRecognitionEngine.kt:298-318, 359-362`;
  `inpainting/PageInpaintingPlanner.kt:108-131`). Exceptions: Paddle det
  re-run per free-text crop at inpaint time
  (`inpainting/aot/AOTInpainting.kt:399-406, 452-471`); the extra
  panel-detector pass (§1.3). **[R]**
- **Hidden sleeps/timers — REFUTED.** No sleep/delay/polling on the
  detect/OCR path; all `delay` sites live elsewhere (provider governor,
  retry clock, persistence debounce). `SINGLE_PAGE_TIMEOUT_MS = 120_000`
  (`TranslationPipeline.kt:118`) is a ceiling via `select`/`onTimeout`
  (`NativeRunQuarantine.kt:84-95`), not a fixed wait. **[R]**
- Minor churn (contributing, not dominant): per-page pool wipes discard
  ~10 MB of reusable direct buffers + pooled bitmaps
  (`ChapterProfileBatchCoordinator.kt:509` →
  `pipeline/batch/BatchLaneWorkers.kt:732-747`). **[R]**
- Decode itself: full file read + SHA-256 + `inJustDecodeBounds` pass +
  real ARGB_8888 decode, all inside `withNativeLane`
  (`BatchLaneWorkers.kt:429-465`; `pipeline/PageDecode.kt:52-99`). **[R]**

### 1.7 One page's journey (Detect+OCR)

1. `BatchChapterTranslator.kt:780-812` builds the coordinator →
   `runPass1` (`ChapterProfileBatchCoordinator.kt:253`). **[R]**
2. Serial loop (`:378`): yield → checkpoint-sidecar read (`:385`) →
   BATCH OCR lease (`BatchLaneWorkers.kt:329`).
3. `withNativeLane` → process-wide mutex (`NativeRunQuarantine.kt:67`);
   page 1 additionally pays full engine init (§1.5).
4. Decode + SHA-256 + bounds + bitmap inside the lane (§1.6).
5. Detect (×W windows if tall) → segment (×W) → per-ROI autoregressive
   OCR (§1.2) → panel detect + dedupe + color estimation.
6. Persist blocks → recycle bitmap + wipe pools → checkpoint transaction
   (3 sidecars + manifest) → re-read checkpoint → run-record + second
   manifest publication → lease release → next page.
7. Only after ALL pages: analysis/translation begins (`:529-595`).

---

## 2. Translation phase failure with LM Studio

### 2.1 Request shape — NOT one giant prompt (Director question refuted)

The chunk→summarize→synthesize architecture the Director asked for
already exists:

- **Analysis stage**: OCR corpus chunked into ≤16 core pages + ≤2 overlap
  pages (`translator/contextual/AnalysisChunkPlanner.kt:26-32`); one
  `chat/completions` per chunk returning a JSON facts document
  (`translator/analysis/AnalysisEngineTransport.kt:38-44, 80-99`;
  `ANALYSIS_MAX_OUTPUT_TOKENS = 8192` at `:72`). Synthesis is local
  (no LLM): `ProfileReconciler.reconcile(chunks)` freezes the chapter
  profile/glossary (`ChapterProfileBatchCoordinator.kt:1042-1044`). **[R]**
- **Envelope stage**: `translator/contextual/GlobalEnvelopePlanner.kt:43-51`
  — structural caps ≤32 blocks / ≤8 pages per envelope; estimator caps
  16,384 input / 8,192 output (actual per-envelope totals are clamped by
  the profile context ceiling, §3). One request per envelope via
  `pipeline/batch/ProfileEnvelopeExecutor.kt:541-681`, hard-serialized
  one-in-flight (`:53-56`). Page atomicity: an over-budget single page is
  rejected whole, never split (`translator/contextual/StreamingChunkPlanner.kt:113-120`). **[R]**
- Wire format: system prompt demands `ID|Translated Text`, one line per
  block (`translator/contextual/TranslationPrompts.kt:90-107`); payload at
  `translator/providers/LmStudioTranslator.kt:65-81`. **[R]**

### 2.2 Primary root cause: 60-second read timeout vs. multi-minute local generation

`translator/providers/OpenAiCompatibleTranslator.kt:47-51` — **[V]**

```kotlin
okHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
```

No `callTimeout`, **no SSE streaming** — a blocking completion call that
reads the full body (`postChatCompletion`, `:108-196`). **[V]** A local
model with a ~16k-token prompt routinely needs minutes; every attempt
deterministically dies as `SocketTimeoutException` at 60 s. Field
diagnostic: LM Studio's server log will show requests arriving and
connections cut at almost exactly 60 s.

### 2.3 The visible "trying again at <time>" — two producers

**Loop A — analysis-chunk timeout → instant PAUSE with a near-now
retryAt (this is the first message the Director sees after OCR):**

1. Analysis chunk request times out (`IOException`).
2. `translator/analysis/AnalysisEngineTransport.kt:49-64` converts any
   `IOException` to `ProviderFailure(NETWORK, PAUSE)` **before** the
   generic retry wrapper sees it — no transport retry happens. **[V]**
3. `translator/retry/TranslationRetry.kt:233-253, 277-283`: PAUSE →
   `ProviderRequestPausedException(nextEligibleRetryAtEpochMs = now +
   retryDelay)`, `retryDelay = exponentialDelay(base 1000 ms, attempt 1)`
   = 1 s. **[R]** (call site `translator/analysis/AnalysisChunkExecutor.kt:177-194`)
4. Coordinator returns `BatchPass1Status.PAUSED` carrying that epoch
   (`ChapterProfileBatchCoordinator.kt:874-903`) → tracker pause →
   snapshot with `retryAtEpochMs`. **[R]**
5. UI: notification copy "Paused — <reason> · automatic retry when
   eligible" (`ui/TranslationNotificationCopy.kt:62-84`) and the
   foreground service appends `" after " + DateFormat.getTimeInstance(SHORT)`
   when a retryAt exists — `app/src/main/java/eu/kanade/tachiyomi/data/translation/TranslationForegroundService.kt:262-263`.
   **[V]** (this literal concatenation is the Director's "trying again at
   <time>"); also `presentation/manga/components/TranslationProgressSheet.kt:955-962`
   ("Paused — $reason · retry after $next"). **[R]**
6. Resume re-sends the same oversized chunk → identical timeout →
   re-pause: the observed infinite timed-retry cycle. **[R]**

**Loop B — self-imposed quota governance ("exhausted API" wording):**

- Every request passes `translator/ProviderRequestGovernor.kt`: provider
  bucket defaults 60 req/min, 60,000 tokens/min, maxInFlight 1,
  maxForegroundWaitMs 15,000 (`:66-75`; only backend `"desktop"` is
  exempt, `:670-679`) plus a Batch sub-limit of 15 requests / rolling
  60 s (`:707-738`). **[R]**
- One LM Studio envelope reserves ~16k input + up to 8k output ≈ 20k+
  tokens (`:442-464, :48-62`), so ~2–3 envelopes exhaust the 60k/min
  window; when the computed wait exceeds 15 s the request is deferred
  with `nextEligibleRetryAtEpochMs` = window roll-off (up to +60 s) and
  thrown as `ProviderRequestPausedException` of kind `QUOTA_EXHAUSTED`
  (`:193-204, :358-362, :484-503, :771-776`). **[R]** LM Studio has no
  real rate limit — this is self-inflicted, and 60 s timeouts hold
  reservations for their full window, compounding the deferrals.

**Loop C — envelope-stage timeout (untimed variant):** raw
`SocketTimeoutException` on envelopes classifies as NETWORK/RETRY_NOW
(`translator/retry/ProviderFailureClassification.kt:145-148`); 3 transport
attempts × 1s/2s backoff re-send the same giant prompt and re-time-out;
semantic controller adds up to 1 whole-envelope reissue, bounded by
`RequestRetryBudget.DEFAULT_MAX_ATTEMPTS = 8`
(`translator/retry/AiTranslationRetryController.kt:347-382, 41-51`;
`TranslationRetry.kt:71-74`); on exhaustion → pause with
`nextEligibleRetryAtEpochMs = null` ("Semantic envelope request budget
exhausted", `AiTranslationRetryController.kt:902-907`) → pause WITHOUT a
time. **[R]**

Terminal state: chapter → `Translation.State.PAUSED`
(`pipeline/batch/BatchProgressReconciler.kt:246-272`); auto-resume only
via cooldown-gated requeue (`ChapterTranslator.kt:373-392`) or manual
Retry. Unverified: any component that auto-requeues a paused *batch*
solely on retryAt maturity (the notification copy promises it; only the
reader/auto lane matures per-page pauses in-run,
`scheduling/RollingAutoCoordinator.kt:741-773`). **[R]**

### 2.4 Parser verdict — robust enough; not the cause of the timed message

`translator/contextual/ContextualResponseParser.kt:104-189` — line-based
`id|text`: skips markdown fences (`:116`), ignores envelope-marker/preamble
lines (`:121`), normalizes ids (`:24-37`). Strictness that a weak local
model can fail: prose preamble without `|`, wrapped continuation lines,
unknown/duplicate ids, blank text, truncated output at max_tokens →
"Missing translation for" (`:173-174`); echoed-source rejection
(`:691-695`). Parser (`protocolIssue`) failures feed the same retry
machinery but terminate in an **untimed** pause
(`AiTranslationRetryController.kt:436-509, 881-900`). The strict
`[index]`-only `translator/providers/NumberedLineResponseParser.kt:26-51`
is the legacy/reader path, not the batch envelope path. **[R]**

---

## 3. Token-budget spec violation (Director follow-up: "8k total, input+output")

Director directive: each model gets an **8,192-token total context
allowance, input + output combined**.

- The directive IS implemented for the general case:
  `translator/contextual/TranslationContextChunkPlanner.kt:21-23` —
  `MAX_CONTEXT_TOKENS = 8_192`, `SAFETY_MARGIN = 512`,
  `MIN_OUTPUT_TOKENS = 256`, with the comment "provider-safety ceiling …
  prompt and response reserve reaches this ceiling". **[V]**
- The math enforces it as a combined budget:
  `StreamingChunkPlanner.effectiveOutputCap` (`:236-254`) computes
  `available = maxContextTokens − safetyMargin − promptTokens −
  protocolReserve` and clamps output into it, so prompt+output ≤
  maxContextTokens. **[V]**
- **But** `Profile.LM_STUDIO` overrides the ceiling to
  `maxContextTokens = 16_000` (`TranslationContextChunkPlanner.kt:196-202`)
  — double the directive — and the batch path selects that profile
  whenever the translator is `LmStudioTranslator`
  (`pipeline/batch/BatchChapterTranslator.kt:361-365`). **[V]**
- Envelope planner caps (`maxEstimatedInputTokens = 16_384`,
  `maxEstimatedOutputTokens = 8_192`, `GlobalEnvelopePlanner.kt:49-51`;
  analysis chunk input cap `AnalysisChunkPlanner.kt:47`) are estimator
  caps; under DEFAULT they never push a request past 8k combined, but
  under LM_STUDIO they can approach 16k combined. **[V]** (caps read;
  interaction via §2.1/§3 math)
- User-facing output preference exists (`translationAiOutputTokens()`,
  default 8192, `translator/AiTranslatorKind.kt:31`) — clamped by the
  same combined-budget math. **[R]**

Impact: ~2× prompt → ~2× generation time on an endpoint that already
cannot finish within the 60 s timeout, and ~2× tokens charged against
the 60k/min governor → more QUOTA_EXHAUSTED deferrals.

---

## 4. Causal chain (both symptoms, one narrative)

Detect+OCR: concurrency-1 native mutex + serial page loop (by design,
Ten Never rules) × CPU autoregressive per-ROI OCR × windowed re-inference
on tall pages × per-page O(chapter) manifest publications = hours-scale
wall time. No pipelining is possible inside the current loop shape, and
translation is corpus-gated until the last page finishes.

Translation (LM Studio): first LLM call after OCR is an analysis chunk
built under the 16k LM Studio ceiling (spec says 8k) → local generation
exceeds the 60 s blocking read timeout → `SocketTimeoutException` →
converted to NETWORK/PAUSE before retry logic → chapter PAUSED with
retryAt ≈ now+1 s → notification renders "…automatic retry when eligible
after <time>" → resume re-sends the same chunk → identical failure →
loop. Subsequent envelope requests additionally trip the self-imposed
60k-tokens/min governor → QUOTA_EXHAUSTED deferrals with retryAt up to
+60 s. The endpoint is never actually rate-limited; the client times out
and blames quotas.

## 5. Recommended fixes (ranked; none implemented)

### LLM lane (small, contained in translator layer)

1. **Honor the 8k directive for LM Studio**: set
   `Profile.LM_STUDIO.maxContextTokens = 8_192` (or remove the profile
   special case) — `TranslationContextChunkPlanner.kt:196-202`.
2. **Stream or extend the timeout for local backends**: SSE streaming
   (`stream: true`) makes the read timeout apply per-chunk instead of to
   the whole generation; at minimum raise read/call timeouts for
   LAN/local endpoints — `OpenAiCompatibleTranslator.kt:47-51, 108-196`,
   `LmStudioTranslator.kt`.
3. **Stop governing the LAN endpoint like a metered cloud API**: give
   LM Studio the `"desktop"`-style exemption (or per-backend policy) in
   `ProviderRequestGovernor.kt:66-75, 670-679, 707-738`.
4. **Let analysis-stage network failures use transport retry** instead of
   instant PAUSE — `AnalysisEngineTransport.kt:49-64`.
5. (Optional, robustness) Relax parser terminality for local models
   (preamble tolerance, truncation-aware re-ask) — secondary; parser is
   not the current blocker.

### Native lane (larger effort; must respect Ten Never "serial detector/OCR")

6. Pipeline page N+1's decode (+checkpoint sidecar reads) outside the
   admitted native slot; move manifest/run-record publications off the
   serial critical path (batch them per K pages) — addresses §1.4/§1.6.
7. Batch/parallelize per-ROI OCR preprocessing within the admitted page
   (crops + normalize ahead of the serial autoregressive steps), and
   reuse pooled buffers across pages where the memory budget allows
   (§1.2, §1.6 churn).
8. Revisit windowed detect+segment reuse on tall pages (single-pass
   fusion or shared crops) — §1.3.

## 6. Evidence verification log (Main Leader)

Directly re-read and confirmed: `NativeRunQuarantine.kt:43,67`;
`MangaOcrEngine.kt:33-38, 256-295, 425-445`;
`OpenAiCompatibleTranslator.kt:47-51, 108-165`;
`AnalysisEngineTransport.kt:38-70`; `TranslationForegroundService.kt:255-268`;
`TranslationContextChunkPlanner.kt:15-25, 150-240`;
`GlobalEnvelopePlanner.kt:29-70`; `StreamingChunkPlanner.kt:200-255`;
`BatchChapterTranslator.kt:350-370`. All other citations are
investigator-reported [R] and consistent with the verified anchor points.
