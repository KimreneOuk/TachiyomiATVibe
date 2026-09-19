# PP-OCRv6 precision adjudication

Status: research-only. No production files or existing reports were changed.

## Executive decision

Do not promote any candidate yet. FP16 is a viable storage experiment and is
the only precision path with clean REC smoke parity, but FP16 DET has a
repeatable detector counterexample and neither FP16 model has full-corpus or
Android evidence. INT8/QDQ is rejected by the completed bounded evidence:
every completed quantized variant changes the REC decoded text and removes the
only FP32 DET box on the real fixture. The INT8/QDQ measurements are host CPU
measurements only and do not establish Android/QNN compatibility.

## Final status table

| Area | Candidate(s) and model size | Conversion/load | Real parity and drift | Host timing caveat | Adjudication |
|---|---|---|---|---|---|
| **FP16 DET** | FP32 9,880,512 B; FP16 I/O 4,973,761 B (49.7% smaller); keep-I/O 4,974,139 B (49.7% smaller) | Both forms converted and loaded/inferred in ORT BASIC and OpenVINO. ORT `ORT_ENABLE_ALL` is a failed path for converted REC, so the reported ORT run uses BASIC. (`results.json` has an empty `conversion` array; the checked model files plus `fp16.md` document the conversion.) | Four real pages / eight comparisons: equal box counts; worst mean IoU 0.99956 and minimum matched IoU 0.98684. However, deterministic probe is 40 FP32 boxes vs 44 FP16-I/O / 43 keep-I/O, mean best-match IoU 0.9382 and minimum 0.000164. Full 76-page/486-region parity is untested. | One warm Windows ORT run: FP32 7,890.6 ms vs FP16 I/O 6,035.7 ms vs keep-I/O 5,899.1 ms. RSS did not fall (about 493 MB FP32 vs 501/510 MB FP16); this is not Android performance or memory evidence. | **HOLD / not production-approved.** Storage reduction is confirmed; detector parity and device behavior still need gating. |
| **FP16 REC** | FP32 21,159,378 B; FP16 I/O 10,624,002 B (49.8% smaller); keep-I/O 10,624,324 B (49.8% smaller) | Both forms converted and loaded/inferred in ORT BASIC and OpenVINO. | Four real crops / eight comparisons and fixed probes: exact CTC sequence parity (8/8 real-crop comparisons; fixed probes also exact). This is parity to FP32, not ground-truth accuracy. Full 486-crop parity and Android execution are untested. | One warm Windows ORT run is slower: FP32 779.8 ms vs FP16 I/O 1,200.1 ms vs keep-I/O 1,347.2 ms. OpenVINO reverses the keep-I/O direction (396.8 vs 319.8 ms), showing backend dependence; RSS also did not decrease. | **CONDITIONAL / keep FP32 by default.** Reconsider only after target-device measurements show a real end-to-end benefit with no reader regression. |
| **INT8/QDQ DET** | Dynamic per-channel 2,649,472 B (26.8% of FP32); static QOperator per-tensor 2,628,660 B (26.6%); static QDQ per-tensor 2,687,029 B (27.2%). Per-channel QOperator/QDQ conversions were not part of the completed raw run. | Completed dynamic, QOperator, and QDQ forms have zero recorded conversion/load errors on ORT 1.24.1 CPU. No Android, NNAPI, QNN, or HTP load was attempted. | On the one real calibration/fixture image, FP32 produces one DB box; dynamic, QOperator, and QDQ each produce zero (`count_delta=-1`, mean/min IoU 0.0). Probability-map max abs drift is 0.9793 / 0.9749 / 0.9767 respectively. Mixed DET×REC was not completed. | One host warm sample each: dynamic 22,993.6 ms, QOperator 3,819.5 ms, QDQ 4,421.8 ms. Candidate load times were 651–2,242 ms. These are concurrent-load desktop results and cannot rank Android implementations. | **REJECT current candidates.** Do not ship; representative calibration or QAT/layer exclusions are required before another review. |
| **INT8/QDQ REC** | Dynamic per-channel 5,617,574 B (26.5%); static QOperator per-tensor 5,477,891 B (25.9%); static QDQ per-tensor 5,566,223 B (26.3%). Per-channel static variants were not completed in the bounded run. | Same ORT CPU conversion/load result as DET: no recorded errors for completed variants; no device/QNN evidence. | On the same single real fixture, FP32 decodes `ı`; dynamic decodes `.`, while QOperator and QDQ decode empty. Exact sequence/text parity is false for all three; output-map max abs drift is 0.9018 / 0.5270 / 0.8432. | One host warm sample each: dynamic 6,250.1 ms, QOperator 586.6 ms, QDQ 490.2 ms. This is not a clean FP32-vs-candidate comparison and does not predict Android latency. | **REJECT current candidates.** Quantization is not accuracy-safe on the available evidence. |

## Evidence boundaries

- FP16 real-image coverage is a four-page/four-crop smoke subset. The planned
  76-page follow-up stopped under resource pressure and emitted no valid result;
  it must be treated as **untested**, not as a pass.
- INT8/QDQ used `research/fixed_manifest.json` with 118 resolved files (36
  pages, 41 crops, 41 masks), but only one real mask was used as fixture and
  calibration input. This is not the missing 76-page chapter corpus and is not
  representative calibration proof.
- The INT8/QDQ raw files contain no mixed DET/REC measurements. A quantized
  DET result and a quantized REC result therefore must not be treated as an
  end-to-end pipeline result.
- `qnn_htp.md` and `qnn_compatibility_raw.json` are offline inventories only:
  no QAIRT/QNN SDK, Android device, `adb`, QNN tools, or QNN-enabled ORT
  provider was available. Current PP-OCRv6 FP32 graphs are dynamic and have
  unsupported/partition-risk ops; no HTP conclusion is established by the
  desktop runs.

## Exact remaining gates

1. **Representative calibration and parity corpus.** Restore/provide the
   complete 76-page manifest (486 annotated regions), run FP32 baselines and
   each candidate over every page/crop, and compare production DB-postprocessed
   box count, matched IoU, missed/new boxes, crop ordering, and decoded REC
   text. Record both per-page and aggregate worst-case results.
2. **FP16 target-device gate.** On the actual Android ORT/backend used by the
   reader, load FP16 DET and REC with the same preprocessing/postprocessing,
   run cold and warm repetitions over the full corpus, and record p50/p95
   latency, peak RSS, failures, thermal/battery behavior, and end-to-end OCR
   output. Test both FP16 I/O and keep-I/O forms; retain FP32 when the backend
   does not accelerate them or DET parity fails.
3. **INT8/QDQ recalibration gate.** Calibrate DET on representative full-page
   images and REC on representative text crops, not one mask. Re-run static
   per-tensor and per-channel QOperator/QDQ variants, then repeat full-corpus
   DB/CTC parity. If drift remains, test sensitive-layer exclusions or QAT;
   do not infer safety from output-map cosine or model size.
4. **End-to-end mixed gate.** Execute FP32/candidate DET×REC combinations and
   the complete reader pipeline on the same corpus; quantify whether detector
   drift changes the crop set before judging REC text.
5. **Android/QNN HTP gate (only if HTP is a target).** Produce fixed-shape,
   QNN-compatible QDQ DET/REC graphs; run every intended DET/REC shape on a
   Qualcomm device with `session.disable_cpu_ep_fallback=1`, detailed profiling,
   and output parity against FP32. Separate model incompatibility from missing
   ABI/QAIRT/RPC/provider configuration. Generate context binaries only after
   no-fallback execution succeeds, and repeat after any SoC/ORT/QAIRT change.

## Sources audited

- `research/findings/fp16.md`
- `research/fp16-results/results.json` and `research/fp16-results/models/*`
- `research/findings/int8_qdq.md`
- `research/results/int8_results.json`
- `research/results/qdq_results.json`
- `research/findings/qnn_htp.md`

