# T929 — Parametric wall-clock throughput model (PROFILE_PIPELINE, 200-chapter baseline)

Base `main` @ `9c19ad0`, code-over-docs. All paths relative to
`app/src/main/java/eu/kanade/translation/`. Device/provider timings are
**labeled parameters [P]** with ranges — no runtime measurement exists. OCR
step-call structure is code; one desktop measurement is imported as a labeled
parameter only. Baseline: 200 chapters, 15 pages/chapter (sensitivity 10–20),
5 blocks/page [P 3–8].

## 1. Constants table (cited)

### Provider pacing (process-wide, all workloads)
| Constant | Value | Cite |
|---|---|---|
| Default quota RPM | 60 | translator/ProviderRequestGovernor.kt:67 |
| Default TPM | 60,000 | :68 |
| `minimumSpacingMs` | 1,000 | :69 |
| `maxInFlight` | 1 | :70 |
| `maxForegroundWaitMs` / `pollIntervalMs` | 15,000 / 50 | :71,:73 |
| Interactive reserve fraction | 0.2 | :85 |
| Batch sub-limit `BATCH_REQUESTS_PER_MINUTE` | 15 per rolling 60 s | :710 |
| Sub-limit `maxInFlight` (one Batch request at a time) | 1, spacing 0 | :730–737 |
| Sub-limit applies to | analysis chunks + AI envelopes ONLY; manual/auto exempt | :691–706, pipeline/batch/ProfileEnvelopeExecutor.kt:69–72 |
| Standard-lane pages ride | shared bucket only (60 RPM, 1 s, in-flight 1) | BatchLaneWorkers.kt:933–956 (`translatePage`), provider ctor `SharedProviderRequestGovernor` (e.g. providers/DeepLTranslator.kt:40) |
| HTTP timeout (connect/read/write) | 60 s ×3 | providers/OpenAiCompatibleTranslator.kt:48–50 |

### Sizing / planner constants
| Constant | Value | Cite |
|---|---|---|
| Envelope: max blocks / pages / in-tok / out-tok | 32 / 8 / 16,384 / 8,192 | translator/contextual/GlobalEnvelopePlanner.kt:45–51 |
| Token estimator (env) | 4 chars/tok in + 8/block; out = 1 tok / 2 chars | :128–131 |
| Analysis chunk: max core pages / overlap / blocks / in-tok | 16 / 1 (max 2) / 512 / 16,384 | translator/contextual/AnalysisChunkPlanner.kt:28–47; artifact/AnalysisChunkResult.kt:153–154 |
| Corpus page tokens | per block `(chars+3)/4` | pipeline/batch/ChapterProfileBatchCoordinator.kt:2664 |
| Context ceiling DEFAULT profile | 8,192 total | translator/contextual/TranslationContextChunkPlanner.kt:21 |
| LM_STUDIO profile context | **16,000** (code; T926's "8k" directive is NOT in code) | :196–202 |
| Prompt overhead / rolling ctx / pairs | 1,400 / 1,500 / 32 | :27,:39,:42 |
| `MAX_ENVELOPES` per plan | 4,096 | artifact/EnvelopePlan.kt:89 |
| Envelope retry policy | 1 whole-envelope + 2 missing-block, root budget | translator/retry/AiTranslationRetryController.kt:42–44 |

### Timeouts / native / storage
| Constant | Value | Cite |
|---|---|---|
| `ONNX_PHASE_TIMEOUT_MS` (engine init) | 90 s | TranslationPipeline.kt:139 |
| `SINGLE_PAGE_TIMEOUT_MS` (batch OCR + inpaint lane) | 120 s | :118; pipeline/batch/BatchLaneWorkers.kt:401,:584 |
| `ATTACH_TIMEOUT_MS` (manual waits on batch page) | 210 s | TranslationPipeline.kt:151 |
| MangaOcr decode loop | ≤300 steps/block; position cap 128 | ocr/MangaOcrEngine.kt:256,:433 |
| MangaOcr sessions | 3 CPU-only (enc/dec-init/dec-step), serial per crop | ocr/MangaOcrEngine.kt:30–95 (T928 §b) |
| OCR order | detect whole page → per-block crops, serial | recognition/RoiPageRecognitionEngine.kt:389–503 |
| Vertical OCR | crops batched through `recognizeBatchWithConf` (B=1 in prod dispatch) | :391–401 |
| Checkpoint write | per page: sidecar + snapshot + ONE manifest TX (sidecar-before-pointer) | artifact/ChapterArtifactStore.kt:445–511 |
| Run-record publish per OCR page | 1 durable record | ChapterProfileBatchCoordinator.kt:480–483 |
| Fingerprint | full chapter SHA-256 read, parallel IO, hard barrier | pipeline/batch/BatchChapterTranslator.kt:394–405; pipeline/PageDecode.kt:119–131 |
| Store persist debounce | 250 ms | store/StorePersistenceScheduler.kt:33 (T928 §A.9) |
| Overlap window | inpaint ONLY inside provider wait / serial drain | pipeline/batch/OverlapScheduler.kt:27–65 |

## 2. Serial phase chain — ONE chapter, PROFILE_PIPELINE (AI lane)

Per chapter (warm process): queue admission+store open+preRegister [P ≈ 1 s]
→ full-chapter fingerprint `0.05·P` s [P IO] → engine init (one-time/process,
charged to ch.1: `I ∈ [3,15]` s [P]) → **OCR_PREFLIGHT** (strictly serial
native, whole-chapter barrier: `ChapterProfileBatchCoordinator.kt:378–525`
precedes `:571–595`) → **ANALYSIS** `C` chunks → reconcile+freeze (pure CPU +
1 TX, ≈0.5 s) → **ENVELOPE PLAN+TRANSLATE** `E` envelopes, serial, one
in-flight (`ProfileEnvelopeExecutor.kt:55–56,181`) with inpaint overlapped in
windows (`OverlapScheduler`) → **FINALIZE** serial inpaint drain + layout
sweep + 1 COMPLETE (`:1611–1717`) → teardown flush+retention [P ≈ 1 s].

STANDARD lane: identical through OCR_PREFLIGHT, then
`runStandardTranslateAndFinalize` (`:1749–1979`): **one provider request PER
PAGE, in order** (`:1843–1850` → `translatorWorker.translateOutcome` →
`textTranslator.translatePage`, BatchLaneWorkers.kt:956), paced only by the
shared bucket (≥1 s spacing, latency-bound), inpaint overlapped in the same
per-page windows.

### Per-page native sub-costs (inside 120 s lane permit)
`t_ocr = t_dec + t_det + B·t_block + t_ckpt`
- decode `t_dec` [P 0.25/0.6 s flagship/mid]; detector `t_det` [P 0.3/0.9 s]
- OCR `t_block = S·t_step`: MangaOcr = S decoder steps × per-step run
  [P: S=20 (10–40; T927 desktop lab measured ~15 steps/block on 50 synthetic
  fixtures, 749 calls @ B=1 — desktop measurement, not device truth);
  t_step 15 ms flagship / 35 ms mid, CPU-only per MangaOcrEngine.kt:30–95].
  Paddle = 1 CTC pass/block [P ~0.1–0.3 s]; ML Kit ~per-page [P ~0.1 s],
  inline on native lane (BatchLaneWorkers.kt:313–317).
- checkpoint `t_ckpt` [P 0.05/0.1 s] = TX-01 sidecar+manifest + run record.
Inpaint `t_inp` [P: FAST 0.3/0.75 s; QUALITY AOT 2/5 s], overlapped only with
provider windows; overflow drains serially at FINALIZE.

## 3. Provider request-count formulas (200 chapters)

- Pages/envelope `ppe = min(8, ⌊32/B⌋)`; B=5 ⇒ **6** ⇒ `E = ⌈P/ppe⌉` ⇒
  P=10:2, P=15:3, P=20:4. Total `200·E` = 400–800 (⇒ 2,000–4,000 pages,
  ≥1 request per ≤32 blocks, envelope cap 4,096 never binds).
- Analysis chunks `C = ⌈P/16⌉` (token/block caps non-binding at B≤8:
  16·5·~50 tok ≪ 16,384) ⇒ P=10/15: 1, P=20: 2.
- AI lane requests/chapter = `C+E` (3–6); standard lane = `P` (10–20).
- Cycle per AI request: `c = max(4 s, L+1 s)` — 4 s floor from 15-per-60 s
  sublimit window (:710,730–737), 1 s shared spacing (:69), one in-flight.
  Standard per page: `c_std = L_std + 1 s` (no sublimit).
- LLM latency [P]: cloud fast `L_e=5 s (2–10)`, analysis `L_a=10 s (2–30)`;
  LM Studio LAN `L_e=35 s (10–60)`, `L_a=75 s (30–120)` — output ≈
  `Σ chars/2` tokens (est. 160–640/env) at LAN decode rates, plus prefill of
  the ≤16 k-token window (LM_STUDIO profile is 16 k in code, not 8 k).

## 4. Computed tables

Parameters: B=5, S=20, t_step 15/35 ms, t_dec 0.25/0.6, t_det 0.3/0.9,
t_ckpt 0.05/0.1 ⇒ `t_ocr = 2.10 / 5.10 s·page`; fix overhead 1.75 s
(admission+fingerprint, P=15); freeze 0.5; finalize 1 s.

### 4.1 Total wall-clock, 200 chapters (AI lane)
| Scenario | P=10 | P=15 | P=20 |
|---|---|---|---|
| Flagship + cloud fast | 0.88 h | **3.6 h** (64 s/ch) | 4.9 h |
| Flagship + cloud, QUALITY inpaint | 0.9 h | 3.7 h | 5.0 h |
| Mid-range + cloud fast | 1.6 h | **6.1 h** (109 s/ch) | 8.2 h |
| Mid-range + cloud, QUALITY (spill 46 s/ch) | 2.2 h | 8.6 h | 11.5 h |
| Flagship + LM Studio LAN (9–17 h band) | 6.5 h | **12.1 h** (219 s/ch) | 16.5 h |
| Standard lane, flagship + fast std API (L_std=1 s) | 0.66 h | 3.6 h (64 s/ch) | 4.7 h |

Key: cloud-batch throughput is **native-bound** (pacing floor 3 req/ch × 4 s =
12 s ≪ 31.5 s OCR); LAN is **provider-bound** (3×36+76 = 184 s ≫ OCR).

### 4.2 Per-chapter marginal time (= 4.1 totals ÷ 200) — see column headers.

### 4.3 Time-to-first-readable-page
| Metric | Flagship cloud | Flagship LAN | Mid-range cloud | Standard flagship |
|---|---|---|---|---|
| (a) Ch.1 TTFP | 49 s (31.5 OCR + 11 chunk + 0.5 freeze + 6 env1) | 144 s | 94 s | 34 s (OCR barrier + 1 page) |
| (b) Jump to ch.100 TTFP | 99×64+49 ≈ **1.77 h** | 99×219+144 ≈ 6.0 h | 99×109+94 ≈ 3.0 h | 1.77 h |
| (c) Fraction of a chapter's time AFTER its OCR (scenario C norm) | **47 %** | **85 %** | 51 % (QUALITY) | 48 % |

(b): the chapter queue is FIFO with one active chapter per source
(ChapterTranslator.kt:399–446; T928 §A.4) — no jump mechanism exists, so
TTFP(100) = 99 marginals + that chapter's own (a).

### 4.4 Crash at chapter 137 (scenario F)
Ch.1–136 hold COMPLETE run records + per-page evidence ⇒ zero-work resume
(`resumeFinalizeOrComplete`, ChapterProfileBatchCoordinator.kt:2019–2069).
Ch.137 mid-run: engine re-init `I` (3–15 s) + store reopen + checkpoint
adoption of its OCR'd pages, ~10–50 ms/page (`adoptCheckpointSnapshot`,
:385–423; ST-06) + re-OCR of ≤1 page (the open candidate is cancelled,
:505–522) + remaining pages. Analysis prefix never re-sent (ST-08, :765–807);
envelopes re-planned, committed pages never re-paid (TX-20). **Resume
overhead ≈ 20 s [P 10–40 s] + normal marginal continuation.** Durable writes
≈ 5–7/page ⇒ 12–18 k for the full 3,000-page baseline.

## 5. Top 5 structural bottlenecks (contribution ⇒ lever)

1. **Whole-chapter serial OCR barrier** (`:378–525→571–595`): `P·t_ocr` is
   45–70 % of every chapter on cloud providers (59 s of 109 s mid-range).
   Lever: per-page translate/inpaint as soon as that page's checkpoint
   commits (T928 F.1); move decode out of the native permit (F.2).
2. **FIFO queue ⇒ scenario B**: jump to ch.100 waits 99 marginals (1.8–6 h).
   Lever: user-driven queue prioritization; per-chapter checkpoints already
   make any reorder safe and free.
3. **LAN provider latency × one-in-flight** (scenario E): envelopes serial at
   `max(4, L+1)` s ⇒ 12.1 h on LAN vs 3.6 h cloud. Lever: larger envelopes
   (raise `maxBlocksPerEnvelope`/`maxContributingPages` — request count ∝
   1/envelope size) and/or faster local model; parallelism beyond quota is a
   no-go (T928 F.4).
4. **Analysis+freeze barrier before envelope 1** (AI TTFP): +17.5 s cloud /
   +112 s LAN before the first readable page. Lever: freeze from a prefix of
   chunks (scene-scoped early freeze), or reuse a prior chapter's profile on
   cold start (ST-05 reuse exists only per-chapter).
5. **QUALITY-inpaint overflow past provider windows** (mid-range spill 46 s/ch
   ⇒ +2.5 h over a run): inpaint may only ride provider waits, so on cheap
   fast providers the windows are shorter than Σt_inp. Lever: default FAST
   mode for background batches, or widen windows via fewer/larger envelopes.

## 6. Sensitivity notes
- Native term scales `∂T/∂t_step = 200·P·B·S`; B (3–8) and S (10–40) swing
  OCR time ±3×; engine choice (ML Kit ≈ 0.1 s/page) collapses bottleneck 1.
- Cloud latency ≤3 s keeps pacing at the 4 s floor ⇒ provider time
  `(C+E)·4 s` = 27–54 s/chapter ceiling-independent; batch of 200 never
  exceeds ~40 min of pure provider pacing on cloud — provider class only
  matters via LAN latency.
- Pages 10→20 scales everything ≈ linearly (×0.67/×1.33); total pages
  2,000–4,000.
- LM_STUDIO context 16 k (code) vs 8 k (T926 directive): halving the window
  would roughly double LAN envelope count per page-set — re-run section 3
  with `ppe≈3` before scoring LAN solutions.
