# T929 Round 4 — solutions-native: N1 OCR engine throughput/selection + N2 native EP selection

Base `main` @ `9c19ad0`, read-only code verification. Paths relative to `app/src/main/java/eu/kanade/translation/`
unless noted; every current-behavior claim cites file:line read firsthand. Device timings stay labeled [P]; the one
imported measurement (T927 desktop lab) is desktop evidence, never device truth. Arithmetic reuses the verified model
(team/model/report.md §4.1): flagship+cloud marginal 63.75 s/ch of which OCR = 31.5 s (P=15 × t_ocr 2.10 s;
t_ocr = t_dec 0.25 + t_det 0.30 + B·S·t_step = 5×20×15 ms = 1.50 + ckpt 0.05); run 3.6 h, native OCR = 1.75 h (49 %).
Mid-range: t_ocr 5.10 s, 109 s/ch.

## 0. Current structure (verified)

- MangaOcr = 3 CPU-only sessions: encoder (RepViT 1×3×224×224, one run/crop, `ocr/MangaOcrEngine.kt:61-63,125-131`),
  decoder_init, decoder_step (:72-85). "Always CPU" is an explicit policy: autoregressive per-step graphlets, NPU
  dispatch latency would dominate (:31-36); decoder threads capped ≤2 (:38,72-74). Decode loop ≤300 steps, effective
  ceiling 128 via position table (:256,260,433,441); greedy host-Kotlin argmax over 9,415 vocab per step (:268-279);
  per-crop KV pools 2×1 MiB K + 2×1 MiB V, maxPoolSize=2 (:39-40). Batching today = serial map
  (`MangaOcrEngine.kt:155-158`, default `ocr/RoiOcrEngine.kt:18`); vertical crops dispatch at B=1
  (`RoiPageRecognitionEngine.kt:389-401`).
- Engine choice is per-language catalog, fixed at construction: `ocr/OcrModelCatalog.kt:21-37` (Paddle = CH/EN only;
  MangaOcr = JA only; ML Kit = all), default JA→MangaOcr else ML Kit (:53-58); wired at
  `RoiPageRecognitionEngine.kt:187-215`. Paddle rec: accelerator session w/ CPU fallback
  (`ocr/PaddleOcrV6SmallEngine.kt:53-57`), ONE CTC pass/crop (:91-97), width buckets 640/1600 (:177-184). Paddle det:
  accelerator + symbolic dims fixed 1×3×736×736 (`ocr/PaddleOcrV6DetEngine.kt:72-83`).
- Page + panel detectors are ALREADY accelerator-first w/ per-model CPU fallback (`detection/OnnxPageTextDetector.kt:45-49`,
  `detection/OnnxPanelDetector.kt:62-65`). Bubble segmenter is the one forced-CPU native model, cause recorded
  (packaged QNN ORT lacks XNNPACK; accelerated sessions hit QNN error 1100 at first execute)
  (`segmentation/OnnxBubbleSegmenter.kt:132-144`, CPU rebuild :305-312).
- EP ladder: probe latches QNN_HTP→QNN_GPU→CPU_XNNPACK on Qualcomm, NNAPI→CPU_XNNPACK otherwise; emulator/SDK<29/non-arm64
  → CPU (`runtime/onnx/HardwareDiscoveryEngine.kt:98-142,144-177,68-77`). Per-model per-EP gate with one
  TEMPORARY_FAILURE recreation (`runtime/onnx/ModelRoutingEngine.kt:86-93`); SUPPORTED requires an EXECUTED inference
  (:111-117). Session threads cores/2 coerced 2..4 (`runtime/onnx/OnnxRuntimeProvider.kt:437-440,:286-289`); strict
  `disable_cpu_ep_fallback` (:471,487,497); `disableIntraOpSpinning` exists (:454-463); XNNPACK/strict-NNAPI builders
  exist for AOT (:334-348,354-361) but no OCR session uses them. Mixed EP in one page is ALREADY legal: analyze() holds
  one nativeGuard across detect→segment→OCR serially (`RoiPageRecognitionEngine.kt:96,296-578`), one run at a time,
  per-engine providers logged (:630-635) — the single-lane invariant is serialization, not EP homogeneity.
- OCR caching across re-runs ALREADY EXISTS: resume gates SKIP_ALL / INPAINT_ONLY
  (`pipeline/batch/BatchLaneWorkers.kt:383-415`); stage identity `artifact/StageFingerprints.kt:29-66`; preconditioned
  OCR merge with expected-prior-fingerprints (`pipeline/SinglePageOnnxPhase.kt:928-942`); checkpoint adoption ~10-50
  ms/page (model §4.4). NOT a new lever — listed to prevent Round-3 duplication.

## 1. Catalog (scored vs flagship+cloud 63.75 s/ch, 3.6 h; mid 109 s/ch, 6.1 h)

| ID | Solution | Class | 200-ch impact (flagship cloud) | Quality impact | Risk / invariants | Novel |
|---|---|---|---|---|---|---|
| N1-1 | Per-tier default engine (ML Kit for JA batch runs) | selection | t_ocr 2.10→~0.65 ⇒ 31.5→9.8 s/ch ⇒ **3.6→~2.4 h**; mid 6.1→~3.3 h | LARGE: ML Kit stylized-JP < MangaOcr; upstream of whole AI lane | None native (ML Kit stays inline on lane, BatchLaneWorkers.kt:306-313); explicit quality-bar trade | no |
| N1-2 | Cascade OCR: fast engine first, MangaOcr rescue on filter fail / low conf | selection | [P] 70 % easy ⇒ t_block 0.30→~0.11 ⇒ t_ocr→1.16 ⇒ **~2.6 h** | BOUNDED: hard blocks still MangaOcr; corpus records engine per block | Confidently-wrong-but-CJK output passes the gate (recall-limited); fingerprints must carry both engines | **NOVEL** |
| N1-3 | Decoder step micro-batching (T927 derived graphs, lockstep) | structure | B=4: t_block 0.30→0.15-0.20 [P] ⇒ t_ocr→1.35-1.60 ⇒ **~2.9-3.1 h**; mid saves up to 23 s/ch | None if bit-exact (T927: maxdiff 0 at every B) | KV pools ×B: 4→16-32 MiB, bounded, inside 6 GB; 128-pos LOCKSTEP ceiling; desktop evidence only | no (T927) |
| N1-4 | Split-EP MangaOcr: encoder(+init) accelerated/batched, decoder steps stay CPU | structure+EP | Encoder = 1 of S+2 runs/crop ⇒ [P] −10-25 % t_block; stacks w/ N1-3 ⇒ **~2.6-2.9 h** | Low risk (single-shot graph); partition failure possible | QNN-1100 precedent (segmenter); per-model UNSUPPORTED latch self-heals to CPU | **NOVEL** |
| N1-5 | Fix KV write-slot defect (host writes slice at `pos`, graph convention `pos-1`) | correctness | Minor throughput (degenerate runs: 816 vs 749 steps, T927); removes fidelity bug upstream of translation | POSITIVE: digit corruption ("112345kis378900") eliminated | Lab-proven, latent; confirm on device before patch | no (T927) |
| N1-6 | OCR session tuning: decoder threads ≤2→4, intra-op spinning off | tuning | [P] t_step −10-20 % ⇒ **3.3-3.4 h** | None | Thermal/battery over 3.6-12 h; reader-responsiveness contention (ranks above throughput) | no |
| N1-7 | Quantized / distilled OCR model variants (int8 enc) | model assets | [P] t_step 2-3× ⇒ t_ocr→~0.8-1.2 ⇒ **~2.1-2.4 h**; scales mid-range too | LARGE, unbounded until benchmarked; `ocrModelHash` change invalidates all OCR corpora | New bundled assets; catalog has NO variant slots today (OcrModelCatalog.kt:21-37) | **NOVEL** |
| N1-8 | Per-run engine profiles: fast engine draft/standard lane, quality engine AI lane | selection | Standard lane 3.6→~2.4 h; AI lane untouched (reference bar intact) | NONE by construction | Rebuild plumbing exists (SinglePageOnnxPhase.kt:252-254) but init I=[3,15] s forbids per-page switching — profile per RUN only | **NOVEL** |
| N2-1 | Complete accelerator coverage: re-attempt segmenter on QNN; keep detectors on EP | EP | t_det halved [P] ⇒ −2.25 s/ch ⇒ **−7.5 min/run** (mid −25 min); TTFP/tap-wait −0.15-0.45 s/page | None (thresholds untouched) | Segmenter CPU-forced for recorded build-artifact cause (OnnxBubbleSegmenter.kt:132-144); failure costs one session creation only | no |
| N2-2 | Webtoon sliding-window cost control (two-phase detection; defer segmentation out of OCR barrier) | structure | Aspect≥2 pages: ~14 det+seg runs per 800×12k strip (WebtoonSlidingDetector.kt:41-69,152-184) ⇒ webtoon-heavy runs −20-40 % native [P] | Seam-bubble recall risk; mergeDetections heals seams (:119-126) | Webtoon-only; standard manga = 1 window, unaffected | **NOVEL** |
| N1-9 | Split-stage detection caching: persist detections keyed by `detectionFingerprint`; engine swaps re-OCR only | caching | Neutral for one run; makes N1-1/2/7 A/B cheap (skip det+seg on re-run) | None (detection fingerprint already engine-independent, StageFingerprints.kt:29-48) | Artifact schema + merge extension (pattern exists, SinglePageOnnxPhase.kt:928-942) | **NOVEL** |
| N1-10 | Decoder early-exit / per-lane step cap | structure | WEAK: loop is EOS-driven, already position-capped 128 (:256,260); cap only trims S-tail worst cases | Truncation = dropped source text | Draft-lane variant of N1-8 only | no |

Rejected/absorbed: detector EP move (already accelerator-first); OCR-result caching across re-runs (exists, §0);
detect+OCR fusion (no second detector pass to fuse away); ML Kit full-page fallback (deliberately removed).

## 2. Detail — N1

**N1-1.** Batch-profile preference override of `OcrModelCatalog.selectedModel` (:53-58,75-87) for Japanese runs. ML Kit
≈ [P ~0.1 s]/page (model §2) ⇒ t_ocr 0.65 ⇒ chapter 9.8 s ⇒ run ≈ 2.35 h — matches redteam N1's ~2.4 h and the model's
§6 sensitivity note. ML Kit is vertical-native (`RoiOcrEngine.kt:56-57`), no rotation degradation. Quality: furigana/
stylized/low-res JP fidelity materially below MangaOcr; every downstream stage consumes OCR text
(`StageFingerprints.profileInputFingerprint` :281-306), so this trades the REFERENCE bar — a Director decision, not a
default. Memory drops; scenario D improves.

**N1-2.** Run ML Kit per crop; where `OcrTextFilter.isUsable` fails the CJK gate (`ocr/OcrTextFilter.kt:6-15`; call sites
`RoiPageRecognitionEngine.kt:407,501`; conf<0.5 `VerticalLineOcr.kt:44,105-111`) or text is empty, re-recognize that crop
with MangaOcr — reference engine on the hard tail only. [P] 70 % pass at ~0.02 s ⇒ t_block ≈ 0.11 ⇒ ~2.6 h; LAN TTFP ch1
144→~124 s. Risk: wrong-but-CJK text is NOT rescued (recall-limited gate) — mitigate with detection-score-based rescue.
Two engines resident ⇒ +~4 MiB KV pools + 3 sessions — fine in 6 GB. Fingerprints must composite both engines or resume
reuse breaks.

**N1-3.** T927 proved the graphs are batch=1-baked but an in-place constant patch yields bit-exact B≤30 on all three
(onnx-graph-findings.md:27-40); decode must advance all rows in LOCKSTEP at one shared position (position is a broadcast
scalar, :48-57), finished rows fed EOS fillers, shared ceiling 128. On-device: KV buffers [4,B,4,256,64] ⇒ 1 MiB·B each,
pools stay maxPoolSize=2 ⇒ 4B MiB total (16 MiB @B=4, 32 MiB @B=8) — bounded at construction, drained by
`forceReleaseNativeBuffers` (MangaOcrEngine.kt:321-325). Step calls −55 % @B=4, −76 % @B=8 desktop
(implementation-report.md:33-35). Lockstep cost scales with max(S_i) — length-sort crops before grouping or the
S∈[10,40] tail eats the win. [P] t_block 0.30→0.15-0.20 @B=4 ⇒ ~2.9-3.1 h; mid t_step 35 ms ⇒ up to −23 s/ch ⇒ ~4.4 h.
Legality: batching REDUCES lane acquisitions and keeps one run() at a time inside nativeGuard — the single-lane
invariant is strengthened. ALL numbers are desktop dispatch evidence until the T927 Android phase measures.

**N1-4.** The CPU-only policy (:31-36) is justified for decoder-STEP graphlets but the encoder is one 224×224 RepViT pass
and decoder_init one pass per crop — single-shot graphs, the classic accelerator shape. Create encoder/init via
`createSessionWithFallback` (generic per-model fallback, OnnxRuntimeProvider.kt:202-261); decoder_step stays CPU;
optionally batch the encoder (T927 patch covers it). [P] −10-25 % t_block, stacking with N1-3. Risk: QNN 1100-class
partition failure — why the segmenter is CPU today — mitigated by the UNSUPPORTED latch (ModelRoutingEngine.kt:86-93),
self-healing to CPU after one creation failure. NOVEL: no path today separates EP policy per OCR sub-session.

**N1-5.** T927 proved the host writes the returned KV slice at slot `pos` while the graph convention is `pos-1`
(MangaOcrEngine.kt:281-282 call, :409 dstOffset; onnx-graph-findings.md:43-47): logit divergence from step 3, digit
corruption reproduced, degenerate sequences lengthen (816 vs 749 steps). One-line slot fix + lab fixtures; positive
quality upstream of the entire AI lane, minor free throughput. Land first regardless.

**N1-6.** Decoder sessions cap threads at 2 (MangaOcrEngine.kt:38) while all other sessions take cores/2 coerced 2..4
(OnnxRuntimeProvider.kt:437-440); AOT's `session.intra_op.allow_spinning=0` (:388) is never set on OCR sessions.
⇒ [P] 3.3-3.4 h. Risk: sustained multi-hour full-core load = thermal throttle + reader contention; batch profile only.

**N1-7.** No quantized/variant OCR assets exist (catalog is 3 fixed entries, OcrModelCatalog.kt:21-37). Int8 encoder or
distilled decoder = new assets + catalog slots; pairs with N1-4 (XNNPACK q8). [P] 2-3× t_step ⇒ ~2.1-2.4 h — the only
N1 lever helping mid-range proportionally. Quality unbounded until benchmarked; `ocrModelHash` change invalidates every
persisted OCR corpus (StageFingerprints.kt:51-66) — correctness-preserving but resume-expensive. Sequence after N1-3.

**N1-8.** Standard/draft runs select the fast engine; AI-profile runs keep MangaOcr. Selection per RUN — session init
I=[3,15] s makes per-page switching a thrash anti-pattern. Rebuild plumbing exists (SinglePageOnnxPhase.kt:252-254);
batch admits one chapter at a time. The product-shaped N1-1 satisfying "read fast, good quality": AI reference lane
never degraded; standard lane 3.6→~2.4 h.

**N1-9 / N1-10.** N1-9: analyze() couples detect+segment+OCR in one pass (RoiPageRecognitionEngine.kt:296-401), so engine
A/B re-pays det+seg per page; a persisted detection artifact keyed by the already-engine-independent
`StageFingerprints.detection` (:29-48) decouples that — enabling infrastructure for N1-1/2/7, neutral for one run.
N1-10: weak; EOS-driven loop already capped at 128 positions; a lower cap is draft-lane truncation, not structure.

## 3. Detail — N2

**N2-1.** Correction to redteam N2: page + panel detectors are ALREADY accelerator-first (OnnxPageTextDetector.kt:45-49;
OnnxPanelDetector.kt:62-65) — on a probe-passing flagship they land on qnn_htp, and the mixed-EP page (detector qnn_htp +
MangaOcr cpu) is the existing production shape (:630-635). Remaining surface: the bubble segmenter, CPU-forced for a
BUILD artifact reason (packaged QNN ORT lacks XNNPACK; QNN error 1100 at first execute, OnnxBubbleSegmenter.kt:132-144),
plus session-option parity with the AOT baseline (:380-389). Impact: t_det/segment halved [P] ⇒ −7.5 min/run cloud
flagship, −25 min mid; TTFP and manual-tap wait shrink by the same per-page amount. A failing attempt costs one session
creation + automatic UNSUPPORTED latch — never a runtime stall. Real, modest, free-standing; does NOT move the headline.

**N2-2 (NOVEL).** The model's t_det is per detector PASS; tall webtoons multiply passes: aspect≥2 pages are sliced into
width×1.4 windows with 250 px overlap (WebtoonSlidingDetector.kt:22-24,41-69), and BOTH detector and segmenter run per
window (:152-184,190-222) — an 800×12,000 strip ⇒ ~14 detector + ~14 segmenter runs, turning 0.3-0.9 s into 4-12 s on
the format where batch reading is most common. Levers: two-phase detection (coarse low-res full-page pass, targeted
window re-scan where detections cluster); move segmentation out of the OCR barrier — masks feed only render/inpaint
(`segmentationMask` attach, RoiPageRecognitionEngine.kt:456-458) — shrinking scenario-C's OCR barrier without reducing
native total. Seam recall risk is healed by mergeDetections (:119-126). Webtoon-only; standard manga = 1 window.

## 4. Portfolio arithmetic + interactions

Stacking N1-3 + N1-5 + N1-4 (keep MangaOcr, make it fast) ⇒ t_ocr ≈ 1.0-1.2 s/page ⇒ run ~2.6-2.8 h; adding N1-1/N1-2 ⇒
t_ocr ≈ 0.6-1.2 ⇒ **2.1-2.4 h**, at which point the cloud lane flips native-bound → provider-bound (post-OCR 32.25 s/ch
dominates) and scheduling levers (S11 waves, recomputed under the new native term) become binding. LAN stays
provider-bound in every case (184 s/ch masks all native gains; only TTFP ch1 144→~124 s improves). Mid-range benefits
most in absolute terms (t_step 35 ms). Scenario C's post-OCR fraction RISES under N1 — pair with perceptual/scheduling
entries. All N1 throughput numbers carry the desktop-only caveat until the T927 Android lab phase runs.

Recommended for Director: (1) N1-5 now (pure correctness); (2) N1-8 + N1-2 as the quality-safe speed step; (3) N1-3
behind the T927 device measurement; (4) N1-9 to make 2-3 cheap; (5) N2-1/N2-2 as independent small wins. N1-1 (blanket
ML Kit) and N1-7 (new assets) trade the reference quality bar.
