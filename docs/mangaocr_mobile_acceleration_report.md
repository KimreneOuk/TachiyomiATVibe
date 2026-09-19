# MangaOCR mobile acceleration integration report

Date: 2026-09-18. Repository revision: `9c19ad05bd62cc3c222dfa8527fd4407b037ecc6`.

## 1. Executive decision

Do not change the production OCR path yet. Keep the pristine FP32, batch-1 CPU route as the compatibility baseline. The only candidates worth controlled follow-up are: (a) a repaired, feature-gated B=2 derived graph experiment; (b) FP16 as an opt-in Android compatibility experiment; and (c) encoder-only accelerator trials. INT8 is rejected by the current OCR quality gate. No laptop result is Android sign-off.

## 2. Scope and evidence language

This report reconciles the findings, raw JSON/CSV results, research harnesses, lab state machine, repository health record, and review findings. `OBSERVED` means directly measured or inspected; `INFERRED` means a design conclusion; `UNTESTED` needs Android or corpus execution; `FAILED` means a tested gate did not pass; `BLOCKED` means a required environment/tool was unavailable.

## 3. Repository and change boundary

`REPO_HEALTH.md` is GREEN. The worktree was based on `origin/main`, has no merge/rebase state, and application files were not changed. The model checkout is local and ignored. This integration pass adds only this report and `research/results/integration_checks.json`; it does not commit, package model binaries, or modify app code.

## 4. Model inventory and hashes

The `ogkalu/manga-ocr-mobile` bundle is 64,721,852 bytes: `encoder.onnx` 17,070,003; `decoder_init.onnx` 24,875,052; `decoder_step.onnx` 22,776,797; and `vocab.txt` 37,193. The final hash check passed for all four files; exact values are recorded in `research/results/model-inventory.md` and `integration_checks.json`. No model or tokenizer config files were shipped.

## 5. Graph topology

The bundle is three split FP32 ONNX graphs: a RepViT-style image encoder, one-token decoder initialization, and one-token autoregressive decoder step with explicit self/cross KV caches. The encoder has 474 nodes; decoder init 253; decoder step 276. There are no fused attention operators. All pristine boundary shapes are fixed batch 1.

## 6. Tokenizer and generation contract

`vocab.txt` has 9,415 unique non-empty entries with `[PAD]=0`, `[UNK]=1`, `[CLS]=2`, `[SEP]=3`, `[MASK]=4`. The export contains no authoritative generation config. `[CLS]`/`[SEP]` usage is inferred from the vocabulary and app code, not model metadata. The decoder's learned position table has 128 rows even though the self-cache has 256 slots.

## 7. Pristine CPU smoke result

Final safe integration checks passed ONNX validation and a chained CPU smoke: encoder -> decoder init -> decoder step, with valid output shapes at positions 1 and 127. The same graph intentionally fails at position 128 with a Gather bounds error. This confirms structural execution only; it does not establish OCR accuracy, Android memory behavior, or delegate compatibility.

## 8. Current Android decoder protocol

The shipped `MangaOcrEngine.kt` calls init with `[CLS]`, discards init logits, starts `decoder_step` at position 1, writes returned KV slices at `pos`, and uses CPU explicitly. The lab reference instead consumes init logits, starts fed positions at 2, and writes slices at `position - 1`. This is a material compatibility fork. The batching result must not be integrated until a golden comparison decides whether the lab protocol is a bug fix or a migration.

## 9. Preprocessing parity

The app-compatible path is grayscale, aspect-preserving resize, white centered 224x224 padding, replicated CHW channels, and normalization to [-1, 1]. The generic baseline benchmark uses a different RGB/BICUBIC path. Quantization harnesses are closer to app preprocessing, but their protocol and fixture limits still apply. Any quality decision must use the exact production preprocessing.

## 10. CPU baseline and what it measures

The host CPU baseline is useful for sizing and regression detection, not phone latency. Warm full-generation medians were approximately 690 ms synthetic and 703 ms on sampled real inputs; real rows were selected pages/crops, not a full chapter. Results include deterministic fixed-step measurements and host RSS samples, but no Android ARM, thermal, energy, or reader-latency measurement.

## 11. Real chapter fixture coverage

The supplied chapter has 32 pages and 231 detector/OCR regions. Existing baseline results sample eight evenly spaced pages plus one region each (16 real fixtures). FP16 uses only two real crops; INT8 calibrates on two real crops. No batch result uses real crops, a complete chapter, or a 100–200-page soak. These are sizing fixtures, not a corpus accuracy gate.

## 12. Pristine batch behavior

The pristine encoder, decoder init, and decoder step reject B=2, B=4, and B=8 with fixed-dimension errors. This is confirmed both by the stored probe and the final rerun. The current Android `recognizeBatch()` is also sequential `map { recognize(it) }`; it is not graph batching.

## 13. Derived graph batch behavior

The repaired lab graph surgery derives dynamic graphs that execute B=1/2/4/8 on desktop CPU. The rerun preserves semantic `[N,1]` token/position shapes, checks the saved graph with ONNX, and passes separate pristine-versus-derived B=1 gates for both corrected and current Kotlin protocol semantics through the 127-position ceiling. Row-level stage outputs remain exactly equal for B=2/4/8 in the desktop probe. This is research artifact evidence, not evidence that shipped assets or Android providers accept batching.

## 14. Derived shape-contract repair

The original patch symbolised dimensions 0 and 1 for every decoder input/output, advertising `[N,N]` token/position feeds. The follow-up repair now symbolises only semantic batch axes: hidden/logits use axis 0, cache tensors use axis 1, and token/position feeds remain `[N,1]`; it asserts the saved shapes, runs ONNX checker, and **confirmed rejection of negative `[B,B]` feeds**. Target-provider compilation remains untested.

## 15. Decoder microbatch semantics

The decoder step extracts position as a scalar, so rows advance in lockstep. Finished rows must remain in place and receive EOS filler while active rows continue; cache rows must not be compacted implicitly. The B=8 synthetic schedule produced distinct EOS positions 4, 5, 6, and 12 and matched its independent lab B=1 reference. The repaired full gate also compares separate pristine and derived graphs under both corrected and current Kotlin protocol semantics through 127 positions; both token gates pass within the 1e-4 decoder tolerance. This is implementation parity, not OCR correctness.

## 16. Batch memory and throughput interpretation

KV payload grows linearly: approximately 3.5 MiB per FP32 row for self plus cross cache, before workspaces. Stored host results showed B=2/4/8 dispatch reductions and roughly +30.6/+89.5/+207.7 MB peak RSS versus B=1, but these are desktop signals from derived graphs. B=2 is a reasonable first device hypothesis; B=8 is not a safe Android default.

## 17. OpenVINO laptop result

On a 13th-generation Intel laptop, the connected follow-up chained encoder -> init -> step and mutated KV caches. CPU matched the pristine ORT reference on 2/2 synthetic fixtures with max logit error below 8.4e-5. GPU matched tokens but failed the strict logit gate (max error about 0.13–0.15) and was slower (connected medians 189–361 ms versus 71 ms CPU). AUTO selected CPU. Intel NPU compilation failed with an unrecognized device ID. None of this is Qualcomm HTP evidence.

## 18. OpenVINO pipeline caveat and chained follow-up

The first “hybrid” function timed independent random encoder, decoder-init, and decoder-step calls. It discarded encoder output, did not carry init KV into the step, did not update cache between steps, and checked only finite outputs; those rows remain disconnected stage-call smoke evidence.

The Wave-2 chained harness (`research/test_openvino_chained.py`) corrected that flaw: encoder output feeds init, init K/V feeds step, and returned self-K/V is written back for each greedy step. On the Intel host, CPU matched the ORT reference tokens and strict logit gate on 2/2 fixtures. GPU executed all stages on `GPU.0` and matched tokens, but failed the strict logit gate (max absolute error about 0.13–0.15) and was slower than CPU on the connected fixtures. AUTO was explicitly CPU fallback; Intel NPU remained blocked by `Unrecognized device ID`. The follow-up is still synthetic, host-only evidence and does not establish Android or Qualcomm behavior.

## 19. QNN/HTP compatibility

Offline graph inspection classifies the encoder and decoder-init as `PARTIAL` candidates and decoder-step as `UNLIKELY`; all require device tests. The host has no QAIRT/QNN SDK, QNN tools, or adb. Existing repository evidence records a Snapdragon 8 Gen 3 QNN setup failure (`QNN_DEVICE_ERROR_INVALID_CONFIG`), which is a runtime/toolchain blocker rather than proof that the graphs are invalid.

## 20. FP16 precision result

FP16 halves model bytes to 32,913,087 (49.1% smaller). It preserved greedy token sequences on 8/8 bounded fixtures, including 2 real crops, with high encoder cosine similarity. It was not faster on this CPU-only host (median full pipeline about 232.2 ms vs 222.6 ms FP32). Status: promising for an opt-in compatibility trial, but Android speed, numerical parity over long sequences, memory, and delegate support are untested.

## 21. INT8 and QDQ result

Dynamic and static QOperator INT8 reduced size by about 70.8% and 71.6%, but both produced 0/8 exact token sequences and large encoder drift; the two real crops also failed exact parity. Static per-channel conversion hit a reproducible ORT broadcast error. QDQ has a harness but no result artifact. Status: INT8 failed the current OCR gate; QDQ is untested and cannot inherit INT8 or FP16 claims.

## 22. Alternatives assessment

`ogkalu/manga-ocr-mobile` remains the best immediate low-footprint experiment, not an accuracy winner. The 48px CTC model is a useful throughput reference with downstream Snapdragon evidence but has GPL-3.0 and a known accuracy tradeoff. Baberu is a newer multilingual crop-quality candidate; Hayai is a research candidate without a mobile export. `dhleong` is a packaging reference. None replaces a same-corpus Android gate.

## 23. Safety architecture

Use one durable process-wide page queue and one bounded native admission gate. Manual, reader-auto, and chapter-batch work share ownership but differ in priority and cancellation epoch. Persist OCR and inpaint checkpoints, release page/crop memory at boundaries, and overlap translation/network work with the next native page. Preserve existing leases, generations, fingerprints, and artifact authority rather than creating a second queue state machine.

## 24. Scheduling, cancellation, and memory policy

Start manual at B=1, reader-auto at B=2 experimentally, and chapter batch at B=2 then B=4 only after gates. Manual work preempts at the next safe native boundary; a running native call is quarantined until it exits. Bound decoded pages and held bitmaps, keep disk artifacts durable, downgrade batch size under memory/thermal pressure, and retain CPU fallback. Normal manga behavior must remain unchanged with the feature gate off.

## 25. Final architecture and decision matrix

The target architecture is: durable page queue -> bounded decode slot -> native gate -> detector/ROI -> optional compatible microbatch -> OCR checkpoint -> inpaint checkpoint -> translation lane -> render/commit, with generation/fingerprint checks at every publication boundary. The matrix is deliberately conservative:

| Option | Status | Evidence | Benefit | Difficulty | Blocker |
|---|---|---|---|---|---|
| Pristine FP32 CPU B=1 | **PASS baseline** | ONNX checker, chained CPU smoke, current app route | Compatibility and known behavior | Low | Android performance still unmeasured |
| Derived B=2/4/8 CPU | **PASS desktop gate / UNTESTED Android** | Repaired `[N,1]` contract, separate full corrected/Kotlin B=1 gates, synthetic lockstep row equality | Fewer encoder/dispatch calls | High | Real corpus, Android provider compile, RSS/thermal |
| FP16 graphs | **PROMISING, not approved** | 8/8 bounded greedy parity; 49.1% smaller | Packaging/RAM opportunity | Medium | Android delegate and long/corpus parity |
| INT8 QOperator | **FAILED quality gate** | 0/8 exact sequences, large drift | 70% smaller | Medium | Recognition loss |
| QDQ INT8 | **UNTESTED** | Harness only, no result file | Potential delegate fit | Medium | Missing accuracy/device run |
| Intel OpenVINO | **HOST CHAINED SMOKE ONLY** | Connected CPU chain passed token/logit gate; GPU token parity failed strict logit gate and was slower; NPU unavailable | Laptop experiment and provider diagnostics | Low | Synthetic host fixtures; not Android |
| QNN HTP | **REQUIRES DEVICE TEST** | Offline operator hypothesis; no SDK/adb | Possible encoder acceleration | High | Strict Snapdragon session/inference |
| 48px CTC | **REFERENCE ONLY** | Downstream ARM claim; GPL upstream | Parallel crop throughput | High | License and quality tradeoff |
| Alternative mobile OCR models | **BAKE-OFF ONLY** | Published/local size evidence | Quality/size options | High | Same detector/corpus/device gate |

### Required engineering decision rows

| Option | Status | Evidence | Expected benefit | Engineering difficulty | Main blocker |
|---|---|---|---|---|---|
| CPU optimization | **LIKELY** | Pristine graphs run on ORT CPU; cache/session reuse are structurally confirmed; Android timing is untested | Portable latency and predictable fallback | Low–Medium | ARM performance, preprocessing parity, sustained thermal data |
| GPU acceleration | **HOST-ONLY / UNTESTED Android** | Connected Intel GPU chain matched tokens but failed strict logit gate and was slower than CPU | Possible encoder latency reduction on a different provider | Medium | Host numerical drift, no speedup here, Android GPU kernels/fallback |
| Qualcomm NPU | **REQUIRES DEVICE TEST** | Offline QNN operator/shape analysis only; no strict HTP run | Potential encoder throughput/energy improvement | High | QAIRT/ORT/device-specific support and strict no-fallback execution |
| FP16 | **LIKELY candidate / UNTESTED Android** | 49.1% smaller bundle and 8/8 bounded greedy token parity, including 2 real crops | Lower model memory and possible delegate speedup | Medium | Long/corpus parity and Android delegate behavior |
| INT8 | **FAILED current quality gate** | Dynamic and static QOperator variants were 0/8 exact sequences with large drift | ~70% model-size reduction if quality were recovered | Medium–High | Recognition loss; calibration/graph tooling |
| Encoder batching | **PASS desktop derivation / UNTESTED deployment** | Repaired graphs accept B=2/4/8; pristine graphs reject B>1 | Amortize encoder/session dispatch across ROIs | High | Real-crop parity, Android memory/provider compile |
| Decoder batching | **PASS lab protocol gate / UNTESTED deployment** | Full corrected/Kotlin B=1 gates and synthetic lockstep EOS filler schedule | Fewer host decoder calls for compatible ROIs | High | Production integration, shared-position waste, provider support |
| KV-cache improvements | **LIKELY low-risk** | Explicit self/cross caches already avoid full-history recomputation; cache payload scales linearly with B | Avoid allocations/copies and preserve decoder throughput | Medium | Kotlin/native buffer ownership and position-boundary correctness |
| Hybrid NPU/CPU | **UNTESTED** | QNN report recommends encoder-first HTP and CPU decoder, but no Snapdragon execution | Move large encoder while retaining CPU decoder safety | High | Strict HTP compile/run, transfer cost, thermal behavior |
| Hybrid GPU/CPU | **FAILED host speed/logit gate; UNTESTED Android** | Chained host GPU path matched tokens but failed strict logit gate and was slower than CPU; target Android untested | Possible encoder acceleration without decoder dispatch penalty | Medium | Cross-runtime tolerance, real crops, and target-provider validation |

## 26. Implementation roadmap and exact Snapdragon tests remaining

Roadmap:

1. **Completed in the lab:** retain the repaired graph contract, semantic `[N,1]` token/position shapes, full B=1 gates through position 127, and negative `[B,B]` rejection tests.
2. Produce current-Kotlin versus corrected-lab golden outputs on real vertical, SFX, mixed-script, empty, short, and long crops; decide whether protocol migration is permitted.
3. Run Android FP32 B=1 baseline on a representative 6 GB+ device, recording stage latency, Java/native RSS, bitmap bytes, thermal state, reader input latency, position-limit rate, and OCR CER/exact-match against the 231-region chapter.
4. Enable only a debug/internal B=2 trial. Compare B=1/B=2 on the same pages, then test B=4 only if memory and quality gates remain green. Do not start with B=8.
5. Run FP16 A/B under the same corpus and provider matrix. Keep FP32 default until parity and stability pass.

Exact Snapdragon tests remaining:

- Inventory device model, Android/SDK, Snapdragon SoC, QAIRT/QNN and ORT versions, ABI, thermals, memory class, and available CPU/GPU/HTP providers.
- For each pristine graph, create a strict QNN HTP session with CPU fallback disabled; record session-create result, first real `run()` result, provider/partition metadata, and any setup error.
- Run the encoder, decoder-init, and decoder-step as a real chained decode using production preprocessing, cache updates, positions 1..127, EOS handling, and position-128 negative test.
- Repeat with the repaired derived B=2 graph (then B=4 only if justified), recording compile time, context-cache generation/reload, per-stage latency, peak native RSS, cache/workspace bytes, energy/thermal throttling, and CPU fallback.
- Compare CPU versus HTP outputs numerically and by token/text equality on the complete 231-region corpus; reject any silent fallback or mismatch.
- Execute a 100–200-page cancel/resume/process-death/chapter-switch soak with visible-page takeover, translation overlap, storage pressure, and normal manga/manhwa regression checks.

The final recommendation is therefore “experiment behind gates, no production promotion.” Full check details are in [`research/results/integration_checks.json`](../research/results/integration_checks.json), and the evidence ledger remains in `research/findings/`.
