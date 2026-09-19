---
kind: review
title: "Wave-1 MangaOCR acceleration evidence review"
comments: none
---

# Review outcome

**Do not promote batching, OpenVINO routing, FP16, or INT8 to the Android path from wave 1.** The work is useful exploratory evidence, but the headline `32/32` is desktop agreement with a lab reference, not proof of shipped-decoder parity, OCR accuracy, Android memory/latency, or accelerator execution.

Each finding below has an explicit severity, line-anchored evidence label, and a minimum test whose outcome changes the claim.

## P0 — Batching is validated against the corrected lab protocol, not the production decoder

**Evidence: OBSERVED.** The lab README says its ground truth is a “corrected B=1 reference” and warns it is not production approval (`tools/mangaocr_lab/README.md:5-7`). The reference consumes init logits, starts decoder positions at 2, and stores each returned KV slice at `pos - 1` (`tools/mangaocr_lab/lab/reference.py:43-53`, `:73-92`). The shipped Kotlin path discards init logits, starts `pos = 1`, and writes returned slices at `pos` (`app/src/main/java/eu/kanade/translation/ocr/MangaOcrEngine.kt:241-254`, `:262-291`, `:400-409`).

The run therefore proves `derived B=1 == corrected reference`, not `derived B=1 == current app`. A batching integration could silently change normal manga OCR even when the lab comparison stays green.

**Minimum follow-up test (upgrade/downgrade):** Run both decoders over the same real-crop golden corpus, including EOS and the position ceiling: current Kotlin semantics, corrected reference, and derived B=1. Capture full logits, token IDs, decoded text, EOS position, and cache-slot trace. Upgrade to “production-compatible” only if the derived path matches the explicitly chosen production contract at every sample and the protocol decision is recorded; downgrade this concern only if current-Kotlin and corrected outputs are demonstrated equivalent or a deliberate migration gate is accepted.

## P0 — Decoder graph surgery declares token and position axes as `[N,N]`

**Evidence: OBSERVED.** `_patch_decoder()` applies `_symbolize(vi, (0, 1))` to every decoder graph input and output (`tools/mangaocr_lab/lab/graphs.py:41-46`). The saved graph metadata reports pristine `input_ids` and `position_ids` as `[1,1]` (`research/results/batching-probe.json:126-138`) but derived `decoder_init.input_ids` as `[N,N]` (`research/results/batching-probe.json:218-227`) and derived `decoder_step.input_ids`/`position_ids` as `[N,N]` (`research/results/batching-probe.json:269-281`). The actual probe feeds `[B,1]` (`research/test_batching.py:120-128`), so ORT acceptance does not repair the exported contract.

This is a provider/compiler risk: token and position width is semantically one, while only batch and cache batch axes should vary. A permissive desktop CPU run is not evidence that QNN/OpenVINO shape specialization will preserve the intended contract.

**Minimum follow-up test (upgrade/downgrade):** Patch only semantic batch axes, save, run ONNX checker, and assert every input/output shape: tokens and positions must be `[N,1]`; encoder hidden and cache tensors must have batch `N`; decoder KV output slices must retain their one-position axis. Add a negative feed test showing `[B,B]` is rejected, then run full B=1 parity plus B=2/4/8 row comparisons. Upgrade only when all shape assertions and provider compiles pass; downgrade the concern if an independent compiler proves `[N,N]` is intentional and semantically equivalent (currently there is no such evidence).

## P1 — The B=1 gate is incomplete, and `32/32` is not OCR accuracy

**Evidence: OBSERVED.** The graph gate uses the derived encoder output for both pristine and derived decoder sessions (`tools/mangaocr_lab/lab/graphs.py:109-123`), then compares only a six-step synthetic decode (`tools/mangaocr_lab/lab/graphs.py:117-163`). The lab run records `expected_matches: 18` of `expected_total: 32` (`tools/mangaocr_lab/results/20260918-032700-b1_2_4_8/run.json:1575-1585`), while the batch rows report 32/32 token/text matches only against their reference decoder (`tools/mangaocr_lab/results/20260918-032700-b1_2_4_8/run.json:1603-1607`, `:1625-1629`, `:1647-1651`, `:1669-1673`). The independent probe samples only the first eight synthetic non-empty crops (`research/test_batching.py:197-214`, with fixtures assembled at `research/test_batching.py:249-266`).

Thus the 32/32 result is implementation consistency, not human/independent OCR correctness. It also does not exercise the full 127-fed-position boundary or a complete real chapter.

**Minimum follow-up test (upgrade/downgrade):** Make the gate run separate pristine and derived encoder sessions, then compare complete greedy sequences through position 127 or EOS, with numeric logit tolerances, token equality, text equality, EOS position, and final cache state. Run B=1/2/4/8 on all available real detector crops plus vertical text, SFX, mixed scripts, empty/near-empty, and long crops; add a full chapter with human or independent transcription CER/exact-match. Upgrade the accuracy claim only from the independent ground-truth metrics; downgrade the current claim to “batch consistency” if only implementation parity remains.

## P1 — OpenVINO hybrid timing is disconnected from OCR state and shows CPU fallback

**Evidence: OBSERVED.** The hybrid probe constructs independent random stage inputs (`research/test_openvino.py:63-76`) and in `child_hybrid` calls encoder, decoder-init, and decoder-step with those unchanged inputs; it never wires encoder output into init, init KV into step, or updates the cache (`research/test_openvino.py:178-205`). Those rows are therefore stage-call timing, not a connected autoregressive OCR pipeline or numerical-equivalence result.

The host inventory contains CPU/GPU only (`research/results/openvino_probe.json:10-15`); NPU compilation fails with `Unrecognized device ID` (`research/results/openvino_probe.json:297-299`, `:1011-1013`), and AUTO reports a query error while its compiled execution device is `(CPU)` (`research/results/openvino_probe.json:335-358`, `:1049-1085`). The script records finite outputs but does not compare decoded tokens/logits across devices (`research/test_openvino.py:95-128`).

**Minimum follow-up test (upgrade/downgrade):** Replace random stage calls with one real crop and a connected encoder → init → per-position step loop that carries hidden states, KV caches, EOS, and positions. Compare full token/logit traces against CPU, record compiled execution devices per stage, and fail the run on any unintended CPU fallback. Repeat on the target Android provider/device. Upgrade an accelerator claim only after device execution and parity both pass; downgrade current hybrid numbers to host plugin smoke timings.

## P1 — Precision results are exploratory; current INT8 result is a negative accuracy signal, not a conversion failure

**Evidence: OBSERVED.** FP16 metadata shows eight fixtures, `decode_length: 8`, and one repeat (`research/results/fp16_results.json:4-17`); the comparison loop is FP32-versus-FP16 tensor/pipeline comparison (`research/test_fp16.py:106-124`), not ground-truth accuracy. This supports numerical encouragement on a small CPU sample only.

The current INT8 result contains both dynamic and static converted models and an empty `conversion_errors` object (`research/results/int8_results.json:48-115`), so the earlier “static conversion failed” interpretation is stale. However, the run still uses only eight fixtures, two real crops, decode length 8, and one repeat (`:4-18`); calibration counts are recorded in the script (`research/test_int8.py:84-98`). Dynamic encoder cosine is roughly 0.57–0.68 (`research/results/int8_results.json:118-330`), static encoder cosine roughly 0.38–0.44 (`:334-546`), and sequence token-agreement rows include zero (`:552-773`)—strong negative evidence for shipping these variants as-is. The quantization script explicitly disables per-channel static quantization after a noted encoder failure (`research/test_int8.py:66-74`), so this is not evidence that a production-quality static recipe exists.

Preprocessing is closer to the app than the baseline benchmark: quantization uses grayscale, aspect-preserving padding and PIL bilinear (`research/quantization_common.py:64-74`), while the lab documents that PIL resize is not bit-identical and uses desktop nearest as a known deviation (`tools/mangaocr_lab/lab/preprocessing.py:5-9`, `:37-45`). The INT8 pipeline also follows Kotlin-equivalent position-1 decoding (`research/quantization_common.py:122-135`), reinforcing the need to settle the protocol gate above.

**Minimum follow-up test (upgrade/downgrade):** Do not ship either INT8 variant. Calibrate on a representative real-crop corpus with exact app preprocessing, run complete 127-position decoded text and logits, and report CER/exact-match against independent transcription plus delegate/provider results on target hardware. Upgrade only if accuracy and memory/latency gates pass relative to FP32; downgrade the negative claim only after zero/low token agreement is shown to be a measurement/protocol artifact and a corrected full-corpus run passes.

## P2 — CPU baseline sizes host model calls, not Android end-to-end OCR

**Evidence: OBSERVED.** `research/benchmark_onnx.py` resizes inputs to RGB 224×224 with BICUBIC (`:135-139`) and times already-prepared tensors; the app instead grayscales, aspect-preserves, pads white, and creates 224×224 input (`app/src/main/java/eu/kanade/translation/ocr/MangaOcrEngine.kt:339-374`). The baseline metadata explicitly says neither synthetic nor real fixtures establish OCR accuracy (`research/results/baseline_benchmarks.json:3-5`), and the real sample is 32 pages with selected page/crop fixtures rather than a full chapter distribution (`:81-107`, `:212-247`).

The host timing rows remain useful for rough sizing, but should not be presented as app latency, device performance, or quality evidence.

**Minimum follow-up test (upgrade/downgrade):** Rerun candidate and baseline with exact app preprocessing, cold/warm session costs, complete detector outputs from a full chapter, peak Java/native RSS, thermal state, and per-stage timings on representative Android devices. Upgrade the performance claim only from those device runs; otherwise retain “desktop CPU sizing signal.”

## What remains valid

- **OBSERVED:** The batch artifact records exact pristine/derived hashes, batch sizes 1/2/4/8, and cache scaling; see `research/results/batching-probe.json:5-45`, `:4418-4521`. This establishes reproducible desktop shape experiments, not production compatibility.
- **OBSERVED:** The pristine graphs are fixed batch-1 inputs while derived graphs execute true-size desktop batches; the probe’s own note limits the result to desktop shape/correctness, not Android timing (`research/results/batching-probe.json:4531-4534`).
- **OBSERVED:** The ONNX Runtime CPU benchmark has a connected hidden-state/KV-cache loop (`research/benchmark_onnx.py:180-217`), so its full-generation rows are a valid host pipeline timing baseline after the input-contract caveat; this is distinct from the disconnected OpenVINO hybrid probe.
- **OBSERVED:** The lab records position lockstep, EOS filler behavior, and a 128-position ceiling in its README (`tools/mangaocr_lab/README.md:60-69`). These are constraints to preserve in a device experiment, not sign-off.
- **OBSERVED:** QNN evidence is correctly marked offline/`REQUIRES_DEVICE_TEST` in `research/results/qnn_compatibility.json:3-13`, `:23-23`; without QNN SDK, adb, or strict HTP execution in this worktree, operator classifications remain hypotheses.
- **INFERRED:** B=2 is a reasonable first device experiment only after the two P0 gates pass. The reported host RSS is a sizing signal, not an Android memory budget.

## Recommended next experiment sequence

1. Repair the derived shape contract and run the full pristine-vs-derived gate.
2. Establish current-Kotlin versus corrected-reference golden outputs on real crops.
3. Run Android B=1 baseline, then feature-gated B=2, with memory, thermal, reader-latency, and OCR-quality gates.
4. Only after CPU correctness is green, retest FP16/INT8 and provider routes with strict no-fallback telemetry.
