# T929 Round 3 — red-team: arithmetic, scenarios, coverage (merged Round-2 catalog)

Base `main` @ `9c19ad0` (confirmed HEAD), read-only for code. Every constant below re-checked
in code firsthand. Paths relative to `app/src/main/java/eu/kanade/translation/` unless noted.
Attack scope: arithmetic re-derivation, scenario A–F completeness, coverage completeness.

## 1. Headline verdict table

Model constants re-verified in code: sublimit 15/60 s, maxInFlight 1, spacing 0
(`ProviderRequestGovernor.kt:710,730-737`); shared bucket 60 RPM / 1,000 ms / in-flight 1 /
reserve 0.2 (`:67-85` region, `interactiveTokenReserveFraction`); envelope 32 blk / 8 pg /
16,384 / 8,192 (`GlobalEnvelopePlanner.kt:44-52`); estimator 4 chars/tok + 8, out 1/2
(`:128-135`); chunk 16 pg / 512 blk / 16,384 (`AnalysisChunkPlanner.kt:28-47`,
`AnalysisChunkResult.kt:153`); timeouts 120 s / 90 s / 210 s (`TranslationPipeline.kt:118,
139,151`); one envelope in flight (`ProfileEnvelopeExecutor.kt` class contract + serial while
loop); checkpoint = sidecar + snapshot + ONE manifest TX (`ChapterArtifactStore.kt:445-511`);
decode + inpaint re-decode inside `withNativeLane` 120 s permit (`BatchLaneWorkers.kt:401-431,
584-599`); per-page standard translate (`:933-956`); 250 ms persist debounce
(`StorePersistenceScheduler.kt:33`); MAX_ENVELOPES 4,096; retry 1+2.
`ppe=min(8,⌊32/5⌋)=6`, E=3, C=1, cloud marginal 31.5+1.75+11+0.5+18+1 = **63.75 s** → 3.54 h ≈
3.6 h ✓; LAN 31.5+1.75+76+0.5+108+1 = **218.75 s** → 12.15 h ≈ 12.1 h ✓; ch1 TTFP 49 / 144 s ✓;
jump-B 1.77 / 6.0 h ✓. **The model's computed tables are arithmetically correct** — one
correction below.

| # | Headline claim | Verdict | Correction / evidence |
|---|---|---|---|
| M1 | Model 4.3(c): post-OCR fraction 47 % flagship cloud | **CORRECTED** | 63.75−31.5 = 32.5 → **51 %** (or 48 % excluding pre-OCR fix 1.75). Model's own standard-lane row (identical 64 s marginal) prints 48 %. Band in io/reader catalogs "47–85 %" → **48–51 %–85 %**. LAN 85 % ✓. Qualitative claim (≈half of chapter time post-OCR) stands. |
| S-sched | S7 reader-position priority: B 1.77 h → minutes | **CONFIRMED** | ≤ in-flight tail 64 s + own 49 s ≈ ≤113 s cloud; ≤219+144 = 363 s ≈ 6 min LAN (catalog's "4–6 min": top end correct). FIFO append + `take(1)` one-chapter claim verified (`ChapterTranslator.kt:419-425,843-855`); no reorder code exists (grep). |
| S-sched | S6 carry-over: 12.1 → 8.0 h LAN | **CONFIRMED** | 218.75−76 = 142.75 s/ch → 7.93 h; cloud 52.75 s/ch → 2.93 h. Both match. |
| S-sched | S8 in-pass gap rescan kills corpus-gap PAUSE | **CONFIRMED** | Code: `corpusFingerprint == null → status=PAUSED`, no rescan; deferred pages recorded (`deferredPages.putIfAbsent`, `ocrDeferred`) and never consumed in-pass (`ChapterProfileBatchCoordinator.kt:529-569`, `BatchLaneWorkers.kt:329-341`). +2–5 s/page ≈ t_ocr+overhead ✓. |
| S-sched | S11 waves: 3.6 → ~2.0 h cloud | **CONFIRMED** | max(31.5 native, 29.5 provider) + ~3.25 overhead ≈ 35 s/ch → 1.99 h; LAN max(31.5,184)+3.25 ≈ 187 → 10.3 h ✓. |
| S-sched | S12 LAN envelope enlargement 8.2–10.2 h | **CONFIRMED arithmetic / UNSCORED under 8k** | E=2: 182.75 → 10.15 h; E=1: 146.75 → 8.15 h ✓. But assumes the 16k window (see P2). |
| I-io | OPT-4 event-driven retention saves ~450–600k io-calls | **CONFIRMED (high-end)** | Per-commit `reconcileRetention` verified (`ChapterArtifactStore.kt:750-756`); recursive sweep lists each dir 2–3× (`ArtifactRetention.kt:39-46`). 150–200 calls/page × 3,000 is the upper end; 1–2 sweeps/page-commit gives ~200–400k central. Order-of-magnitude and the O(P²)→O(P) claim stand. |
| I-io | "OPT-1 removes ~70 % of ops" | **CORRECTED** | Catalog itself claims −40–45 %: 7 MP publishes→1 removes 6×9 = 54 of ~113–122 io-calls/page → 59–68 ≈ **−44–48 %** ✓ as cataloged. −~65 % is S8 (WAL), −80–85 % is S10. The ~70 % figure belongs to no entry. |
| I-io | S8 WAL "−~65 %" vs text "~4-6 io-calls/page vs 55-122" | **CORRECTED (internal inconsistency)** | 5 vs ~88 avg would be −94 %. The table row (~110→~25-35 → −65–73 %) is the defensible number; the "4-6 io-calls/page" line counts journal appends only. |
| I-io | S9 per-page shards −~30 % run io | **CONFIRMED (loose)** | MP 9→6 alone = −21/page ≈ −18 %; −30 % reachable only with shard-packed open reads + avoided chapter-level events. Upper bound, not typical. |
| P-prov | Governor is per-backend keyed ⇒ dual-lane legal | **CONFIRMED** | `ProviderRequestKey(backend, model, credentialScope)` `:21-41`; `buckets` map `:281`; `inFlightReady` per-bucket; sublimit keyed credential-wide (`model=null`) `:716-741`. Two different backends = two sublimit buckets; same-credential analysis+envelopes stay serialized by sublimit maxInFlight=1 — catalog scopes entry 5 correctly. `desktop` fast-path exists (`:670-677`), no production callers (grep). |
| P-prov | LM Studio window 16k in code vs Director's 8k | **CONFIRMED — compliance finding** | `TranslationContextChunkPlanner.kt` `Profile.LM_STUDIO -> maxContextTokens = 16_000` (rolling cap 1,024); no 8k anywhere in code; `GlobalEnvelopePlanner` in-tok 16,384 matches 16k, not 8k. Director decision required. |
| P-prov | 8k ⇒ ppe halves to ~3 ⇒ 14.2 h | **UNSUPPORTED as point estimate** | `splitForTokenFit` (`ProfileEnvelopeExecutor.kt:435-484`) packs by `promptAvailableTokens(constraints,…)`. Fixed overhead ≈ prompt 1,400 + rolling 1,024 + safety 512 + min-output 256 ≈ 3.2 k; CJK bubble blocks ≈ 11–33 tok. For sparse text, 8k fits ~as many pages as 16k; for dense text it can halve. 14.2 h is a worst-case bound; the real 8k penalty is unmeasured. Same caveat applies to model §6 "roughly double envelope count". |
| R-reader | B1 queue steering: jump TTFP → ~49 s cloud | **CORRECTED** | 49 s is own-chapter only. Steering never preempts the live chapter (both catalogs agree), so TTFP = in-flight tail (≤64 s cloud / ≤219 s LAN) + own 49/144 s → **≤~2 min cloud / ~6 min LAN** — exactly sched S7's number. B1's "≈49 s" is the no-tail best case. |
| R-reader | C1 dual-lane: pages readable 47–85 % earlier | **CORRECTED** | Inherits M1: **48–51 %–85 %**. Timing otherwise confirmed: standard TTFP 34 s = 31.5 barrier + page-1 request+spacing (code: standard branch runs after full preflight, `ChapterProfileBatchCoordinator.kt:571-583`). |

Tally: 9 CONFIRMED, 5 CORRECTED, 1 UNSUPPORTED (P-prov 14.2h), of ~15 attacked headlines.
The model's headline tables (3.6 h / 12.1 h / TTFP / jump-B) survive re-derivation exactly.

## 2. Scenario completeness (A–F through the merged catalog)

- **A (chase)** — served: S8+S10 (gap stall), S13 (15 s defer), R1–R3, C1/E2. Adequate.
- **B (jump)** — served with corrected magnitudes: S7/B1 → ≤~2 min cloud / ~6 min LAN;
  B2/B3/C3 make it navigable; provider 14 (prefetch) is the only entry that beats the tail.
  Adequate.
- **C (partial)** — served only perceptually on the AI lane: the post-OCR ~48–85 % wait is
  structural (freeze + serial envelopes); C2/R4/provider-3 (SSE) make it visible/earlier, S1/C1
  eliminate it on the standard lane only (explicit trade). No entry removes the AI-lane barrier
  itself except S11 (next-chapter pre-OCR) and S6/S-provider-8 (skip analysis). Adequate with
  trades stated.
- **D (cold start)** — **THIN.** S3 (engine warm-up), provider 16 (connection pre-warm),
  R1/R2 (visible truth) cover process-internal init. Nobody owns: (a) first-run OCR model
  DEPLOY (download/extract of engine assets); (b) **source-image download for 200 chapters** —
  the batch consumes local streams only (`BatchLaneWorkers.kt:319,324`); no DownloadManager
  integration exists in the batch path (grep). On an undownloaded library, cold start is
  network-bound and unbounded, and no catalog entry addresses it.
- **E (LM Studio 8k)** — **served only under the code's 16k.** The biggest LAN levers
  (sched S12, provider 1: 8.2–10.2 h) score envelope enlargement inside a 16k window; under the
  Director's binding 8k directive they are capped or void (P-prov above), and the LAN baseline
  worsens toward 12.1–14 h. Levers that survive 8k: S6/provider-8 carry-over (−4.2 h LAN),
  provider 6 (routing, 7.9 h @100/100), 5 (bucket concurrency), 3 (SSE), 7 (overlap).
  The catalogs' scenario-E portfolios should be re-scored under 8k before any recommendation.
- **F (crash ch137)** — best-served scenario: zero-work COMPLETE resume verified
  (`resumeFinalizeOrComplete`, `:2019-2069`, incl. work-product evidence check), checkpoint
  adoption, S15 (auto re-arm — `restoreQueue` sets `isPaused=true`, never auto-starts,
  `ChapterTranslator.kt:184-222`), B2 visibility. But see new-lever N4: nothing keeps the
  process alive for a 3.6–12.1 h run, so F may fire repeatedly.

Quiet flagship+cloud assumptions: sched S1/S11 impacts are stated flagship-only (mid-range
σ scales with t_ocr); B3's ETAs use flagship marginals; io's [0.1–2.5 h] stall band is against
the 3.6 h flagship total. These are labeled, not hidden — acceptable — but scenario D/E/F
numbers exist only for flagship+cloud and flagship+LAN; mid-range cold/LAN profiles are unscored.

## 3. Coverage hunt — lever classes nobody catalogued

**N1. OCR engine throughput itself (engine selection + decoder batching) — REAL, largest gap.**
The model proves flagship+cloud is native-OCR-bound (31.5 of 63.75 s/ch), and its own §6 says
"engine choice (ML Kit ≈ 0.1 s/page) collapses bottleneck 1" — yet **no catalog offers OCR
throughput as a solution entry**; it appears only as a model sensitivity note. The codebase
already ships the alternatives: `ocr/MangaOcrEngine.kt:30-37` (autoregressive, "Always CPU",
≤2 decoder threads), `:63` `useAccelerator = false`; serial per-crop loop B=1
(`ocr/RoiOcrEngine.kt:19-21` — `recognizeBatch(crops) = crops.map { recognize(it) }`, call site
`RoiPageRecognitionEngine.kt:391-401`); `PaddleOcrV6SmallEngine` + `PaddleCtcDecoder`
(single CTC pass) and `MlKitRoiOcrEngine` exist (`ocr/OcrModelCatalog.kt:29`); T927 desktop
batching lab exists (`Plan/active/2026-09-12_T927_mangaocr-batching-desktop-lab/`, ~15
steps/block measured). Mechanism: engine profile per run, or batch K decoder steps / crops.
Rough 200-ch impact (P=15): ML Kit ROI path t_ocr 2.10→~0.70 s/page → chapter OCR 31.5→10.5 s
→ cloud 3.6→**~2.4 h**; Paddle ≈ parity per block but removes the S∈[10,40] long tail;
step-batching 2× → t_block 0.30→0.15 → cloud ~2.9 h; LAN gains are masked (provider-bound)
but TTFP ch1 improves 31.5→10.5 s of the 144 s. Quality trade must be stated: engine swap
changes recognition fidelity — the AI-lane bar is defined on OCR quality too.

**N2. Native EP / accelerator selection for detector, segmenter, OCR encoder — REAL, modest.**
`runtime/onnx/HardwareDiscoveryEngine.kt:32-37` already models `QNN_HTP / NNAPI / CPU_XNNPACK`
routes with a circuit breaker; `ModelRoutingEngine.kt:19` mentions CPU/XNNPACK fallback; yet
the segmenter forces CPU (`OnnxBubbleSegmenter.kt:141` `useAccelerator = false`) and MangaOcr
forces CPU for all three sessions (`:63`). The code comment is right that the decoder-STEP
graphlets should stay CPU, but the detector (one pass/page, t_det [P 0.3–0.9 s]), the bubble
segmenter, and the OCR encoder (one pass/crop) are big single-shot graphs — classic
accelerator candidates (`Plan/active/2026-08-17-npu-acceleration-architecture/` exists).
Impact: t_det halved → −0.15–0.45 s/page → ~5–25 min/run cloud + smaller manual-tap wait;
small but free-standing from all 63 cataloged entries. No no-go touched (still one lane,
one invocation).

**N3. Source-image download-ahead for the batch — REAL, scenario D/A gating lever.**
`streamsByKey` is built from local page streams (`BatchLaneWorkers.kt:319`); missing streams
yield null decodes/defers; the only download mention in the coordinator is a resume comment
(`:695`). The 200-chapter baseline silently assumes 3,000 local pages. Mechanism: chapter
download-ahead N≥2 ahead of the OCR frontier (T907 did adjacent work:
`2026-08-28_T907_batch-download-autostart-recovery`). Impact: unbounded if the library is not
local (network-bound cold start); once local, zero effect — so it is a conditional but
first-order lever for D and for fresh libraries.

**N4. Background-execution durability (wake lock / Doze / foreground service) — REAL,
scenario-F amplifier.** No WakeLock/foreground-service usage anywhere in `pipeline/`,
`ChapterTranslator.kt`, `TranslationManager.kt`, `scheduling/` (grep). A 12.1 h LAN run or a
mid-range 8.6 h run will cross Doze/App-Standby windows; each force-stop re-enters scenario F
(~20 s + user re-arm without S15). Mechanism: optional keep-awake while a batch is active, or
WorkManager-owned resumption. Impact: run-completability, not per-chapter throughput; without
it the 12.1 h headline is optimistic on stock Android.

Checked and judged NOT levers (one line each):
- **Font/layout/render cost** — render is explicitly outside the native permit
  (`TranslationPipeline.kt:122-130` comment); sub-second per page; S2-class at best.
- **Parallelism across manga** — already catalogued in passing: sched S11 legalizes
  cross-manga lookahead; provider 5 supplies the second bucket; the process-wide
  `take(1)` claim (`ChapterTranslator.kt:421-425`) is the documented blocker. Covered.
- **Archive vs streaming chapters** — decode goes through `streamFn` either way
  (`BatchLaneWorkers.kt:324`); archive random-access adds IO latency, not native-lane or
  provider time; immaterial to the 200-ch totals.
- **Memory-tier scaling** as a standalone class — its only throughput manifestation
  (decode-defer → corpus gap → PAUSE) is already owned by sched S2/S8/S10; remaining
  effects are reliability, not throughput.
- **Thermal throttling over a 3.6–12.1 h CPU-heavy run** — real physics (cold [P] t_step
  will drift up under sustained load; totals ±20–30 %), but a model-calibration risk, not a
  solution lever; fold into model sensitivity, not the catalog.

## 4. Convergence statement

**NOT converged: these 4 classes remain unmapped** — N1 OCR engine throughput/selection
(largest single cloud-lane lever found anywhere in Round 2–3), N2 native EP selection for
detector/segmenter/encoder, N3 source-image download-ahead (owns scenario D), N4
background-execution durability (owns scenario F completability). Per the loop protocol these
spawn a targeted Round 4; N1 is the only one that materially moves the flagship+cloud headline
(3.6 h → ~2.4–2.9 h) and scenario-C's post-OCR fraction. Additionally, before any Director
recommendation: (a) resolve the 16k-vs-8k compliance conflict (P-prov) and re-score all
LAN entries under 8k; (b) adopt the 48–51 %–85 % post-OCR band; (c) restate reader B1 as
≤~2 min / ~6 min incl. in-flight tail.
