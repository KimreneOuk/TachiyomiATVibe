# T927 evidence — ONNX graph facts established 2026-09-12 (read-only inspection)

Source: app/src/main/assets/models/ocr/{encoder,decoder_init,decoder_step}.onnx + vocab.txt
Hashes (SHA-256, match HF ogkalu/manga-ocr-mobile LFS exactly):
  encoder.onnx       d1fb455a07c1508cc56a4f4e15e2ed74aca9a4d78fd220ecc0ff39625d67e1b3
  decoder_init.onnx  612f97e22848620fb36fcac611467689cb4213d91f2d84f6a19042ad57d475f1
  decoder_step.onnx  a244b814a3a669190f9eae737960997633597cfa055fa186347e872bee834bc9

## Shapes (all static, no symbolic dims anywhere)
  encoder:  in [1,3,224,224] FLOAT (tf2onnx 1.17.0, timm RepViT "FlattenedEncoder")
            out [1,196,256]
  init:     in encoder_hidden_states [1,196,256], input_ids [1,1] INT64 (PyTorch 2.12.0+cpu)
            out logits [1,9415], self_k/v [4,1,4,1,64], cross_k/v [4,1,4,196,64]
  step:     in hidden [1,196,256], input_ids [1,1], position_ids [1,1],
            self_k/v_cache [4,1,4,256,64], cross_k/v_cache [4,1,4,196,64]
            out logits [1,9415], self_k/v_slice [4,1,4,1,64]
KV layout [num_layers=4, batch, num_heads=4, seq, head_dim=64]. vocab.txt = 9415 lines.
decoder.pos.weight [128,256] -> hard 128-position ceiling (matches DECODER_POSITION_COUNT).

## Cache semantics (probed by exposing internal tensors)
  init: BOS at position 1 (baked ones_like), BOS KV -> slot 0.
  step: new KV written IN-GRAPH at slot position_ids-1 (eq one-hot verified);
        attention mask = arange(256) <= pos-1, fill -10000 (both broadcast over batch);
        graph returns only the new slice; host maintains the 256-window cache.
  EOS = id 3 ([SEP]); start = id 2 ([CLS]); logits = last-token gather.

## Batch=1 baking
  encoder: 10 Reshape constants (2 token flattens + 8 squeeze-excite) +
           2x ReduceMean(axes=[0,2]) pooling batch axis (batch-1 export artifact).
  init:    val_15 [1,1,4,64], val_37 [1,1,256], val_49 [1,196,4,64],
           val_342 [4,1,4,1,64], val_356 [4,1,4,196,64]; 373 static value_info entries.
  step:    val_26 [1,1,4,64], val_61 [1,1,256]; 387 static value_info entries.
  ORT 1.24.1 rejects batch=2 input on all three originals (INVALID_ARGUMENT).

## In-place patch (no re-export) -> verified bit-exact batch-30
  encoder: 10 reshape consts 1->-1, ReduceMean axes [0,2]->[2], symbolic graph input.
  init:    5 consts above, batch dims 1->-1 (stack consts: index 1), value_info deleted.
  step:    val_26/val_61, value_info deleted.
  Result batch=30: encoder/init/step batched == per-sample, maxdiff 0.00e+00.
  Patched-vs-original at batch=1: maxdiff 1.26e-05 (encoder reduction order only).

## Android host-protocol defect (latent, separate from batching)
  MangaOcrEngine.kt writes the returned KV slice at slot `pos` (line 281-282, 409);
  graph convention is slot `pos-1`. Forced 12-step A/B: logit divergence from step 3,
  max |dlogit| 5.3. Rendered-text A/B: identical output on 4/5 strings; on "1234567890"
  Kotlin protocol emitted "112345kis378900" vs aligned "112345678900".

## Batch position semantics (lockstep constraint — probed 2026-09-12, pre-implementation)
  decoder_step extracts position as a SCALAR: Gather(Clip(position_ids-1,0,255),[0]) x2
  -> at batch N, ROW 0's position broadcasts to all rows (eq cache-write one-hot AND
     the arange<=pos-1 causal mask). Verified on patched graphs, batch=2:
       row1 at pos 9 while row0 at pos 5: batched vs solo maxdiff = 0.99  MISMATCH
       both rows at pos 5 (heterogeneous tokens): maxdiff = 0.00          MATCH
  CONSEQUENCE: microbatch decoding must advance all rows in LOCKSTEP at one shared
  position; finished rows are fed EOS filler and their outputs discarded; the shared
  position ceiling is 128 (position_ids=128 would overflow decoder.pos.weight[128]).
  Strategy A (keep-full-batch) satisfies this exactly; compaction (B) preserves it.
