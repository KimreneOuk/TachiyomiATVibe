---
kind: review
title: "Wave-2 batching correctness follow-up"
comments: none
---

# Outcome

**PASS for the repaired desktop lab contract; UNTESTED for `.studio/ocr.json` crop protocol comparison.** No Android/app source or pristine model asset was modified.

## Repair

`tools/mangaocr_lab/lab/graphs.py` now widens only semantic decoder batch axes:

- `encoder_hidden_states`, `input_ids`, `position_ids`, and logits use axis 0 (`N`).
- KV cache and KV-slice tensors use axis 1 (`N`).
- Token and position feeds remain `[N, 1]`; they are no longer advertised as `[N, N]`.
- Derived decoder I/O is asserted after surgery and each saved ONNX graph is checked with `onnx.checker`.

The derivation cache key is versioned as `semantic-batch-axes-v2`, so the old broad-axis artifacts are not reused.

## Gates and evidence

Raw results: [batching_followup.json](../results/batching_followup.json)

- Separate pristine and derived encoder outputs were used for the B=1 gate.
- Corrected lab protocol and current Kotlin position/cache protocol were both decoded through the safe final fed position 127. Both passed token equality and the `1e-4` decoder logit tolerance; encoder max absolute difference was `1.21e-5`.
- Derived token/position metadata is `[N,1]` for decoder-init IDs, decoder-step IDs, and decoder-step positions.
- Negative B=2 `[B,B]` feeds were rejected for decoder-init `input_ids`, decoder-step `input_ids`, and decoder-step `position_ids`.
- ONNX checker passed for all three derived graphs.
- Row-level parity against independent corrected B=1 runs passed for B=2, B=4, and B=8 across eight non-empty lab crops; all outcomes and token sequences matched.

## Protocol-crop boundary

There are no `.studio/ocr.json` fixtures in this checkout. Therefore the requested current-Kotlin-versus-corrected-protocol comparison on those crops is **UNTESTED**, with no substitute claim made from synthetic/lab crops. This remains a required follow-up when the fixture corpus is available.

## Recommendation

Keep batching out of the Android path until the `.studio/ocr.json` protocol comparison is rerun and the current-Kotlin versus corrected-reference behavior is explicitly accepted. The graph repair itself is safe to retain as research harness code.
