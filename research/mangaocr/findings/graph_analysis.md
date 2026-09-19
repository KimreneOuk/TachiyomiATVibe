# Manga OCR mobile ONNX graph analysis

Date: 2026-09-18  
Source: local checkout of `ogkalu/manga-ocr-mobile` under
`research/models/manga-ocr-mobile`  
Reproduction: `python research/inspect_onnx.py`

## Executive readout

- **CONFIRMED** — the bundle is a three-graph, single-item encoder/decoder
  pipeline: fixed image encoder, one-token decoder initialization, and
  one-token decoder step with explicit KV-cache inputs/outputs.
- **CONFIRMED** — all model boundary shapes are static and batch 1. The image
  input is `[1, 3, 224, 224]`; encoder output and decoder memory are
  `[1, 196, 256]` (196 is a 14 x 14 visual-token grid); logits are
  `[1, 9415]`.
- **CONFIRMED** — weights and activations exposed by the graphs are FP32;
  token and position inputs are INT64. The ONNX payload is 64,721,852 bytes
  before runtime/provider memory, with 16,002,727 initializer elements and
  64,012,304 embedded parameter bytes.
- **CONFIRMED** — the decoder exports four repeated layers and explicit
  self/cross K/V boundaries. The step graph reserves a 256-position self
  cache, while the learned position table is only `[128, 256]`.
- **CONFIRMED** — CPU ONNX Runtime checker validation and a zero-input
  encoder → init → step smoke run completed for all three graphs.
- **LIKELY** — this export is easiest to deploy as a CPU/XNNPACK baseline.
  Static shapes help provider compilation, but the unfused decoder graph has
  many generic `MatMul`, `Transpose`, `Gather`, `LayerNormalization`, and
  masking nodes that may partition or incur launch overhead on mobile GPU/NPU
  providers.
- **UNTESTED** — no Android NNAPI, QNN/HTP, GPU, or device benchmark was run;
  provider support and latency must be established on the target SoCs.

## Graph inventory

| graph | producer / opset | nodes | initializers | parameter elements / embedded bytes |
| --- | --- | ---: | ---: | ---: |
| `encoder.onnx` | tf2onnx 1.17.0; ONNX 17 + ai.onnx.ml 2 | 474 | 350 | 4,111,596 / 16,446,608 |
| `decoder_init.onnx` | PyTorch 2.12.0+cpu; ONNX 17 | 253 | 125 | 6,207,970 / 24,831,972 |
| `decoder_step.onnx` | PyTorch 2.12.0+cpu; ONNX 17 | 276 | 114 | 5,683,161 / 22,733,724 |

All three graphs report IR/opset metadata in
[`onnx_inspection.json`](../results/onnx_inspection.json). `encoder.onnx` uses
IR 8; both decoder graphs use IR 10. The encoder is dominated by 98 `Conv`,
98 `Add`, 97 `Mul`, and 59 `Transpose` nodes. The decoder init is dominated by
61 `Add`, 58 `MatMul`, 41 `Transpose`, 36 `Reshape`, 14
`LayerNormalization`, and 8 `Softmax`. The decoder step adds cache/mask
plumbing (`Gather` 21, `Where` 4, `LessOrEqual` 1, `Equal` 1) around 50
`MatMul`, 14 `LayerNormalization`, and 8 `Softmax` nodes.

The graphs do not contain fused `Attention` or `MultiHeadAttention` operators;
attention is represented by ordinary ONNX arithmetic and matrix operators.
That is **CONFIRMED** from the node op-type inventory, not a claim about the
training architecture.

## Inputs, outputs, and batch contract

### Encoder

`serving_default_args_0:0`: FLOAT `[1, 3, 224, 224]` →
`StatefulPartitionedCall:0`: FLOAT `[1, 196, 256]`.

**CONFIRMED:** there are no symbolic/dynamic boundary dimensions. The caller
must perform any crop/resize/channel ordering/normalization required by the
model; those preprocessing semantics are not described in the local model
files and are **UNTESTED** here.

### Decoder initialization

Inputs:

- `encoder_hidden_states`: FLOAT `[1, 196, 256]`
- `input_ids`: INT64 `[1, 1]`

Outputs:

- `logits`: FLOAT `[1, 9415]`
- `self_k`, `self_v`: FLOAT `[4, 1, 4, 1, 64]`
- `cross_k`, `cross_v`: FLOAT `[4, 1, 4, 196, 64]`

**CONFIRMED:** this is a one-token initialization graph, not a variable-length
prefill graph. The first cache axis is four because the graph has named layers
`.0` through `.3`; the third cache axis is four and the final axis is 64,
which is consistent with four attention heads of width 64 and hidden width
256. The semantic assignment of each cache axis is **LIKELY** from the tensor
layout and layer names, although the export has no schema comment.

The graph contains an INT64 initializer `ones_like=[[1]]` feeding the initial
position embedding lookup. **CONFIRMED:** the init graph does not accept a
caller-supplied `position_ids`; position handling is partly baked into this
export.

### Decoder step

Inputs:

- `encoder_hidden_states`: FLOAT `[1, 196, 256]`
- `input_ids`, `position_ids`: INT64 `[1, 1]`
- `self_k_cache`, `self_v_cache`: FLOAT `[4, 1, 4, 256, 64]`
- `cross_k_cache`, `cross_v_cache`: FLOAT `[4, 1, 4, 196, 64]`

Outputs:

- `logits`: FLOAT `[1, 9415]`
- `self_k_slice`, `self_v_slice`: FLOAT `[4, 1, 4, 1, 64]`

**CONFIRMED:** the decoder step is designed for token-by-token decoding. A
full FP32 cache at the declared dimensions is 3,702,784 bytes (about 3.53
MiB): 2 x 1,048,576 bytes for self K/V at 256 slots plus 2 x 802,816 bytes
for cross K/V at 196 visual slots. This is a persistent per-session memory
requirement before temporary tensors and provider workspace.

The model has no dynamic batch axis. **CONFIRMED:** every boundary batch is 1,
and the cache's second dimension is also fixed at 1. Batched crops or multiple
OCR regions would require separate sessions/loops or a new export.

## Vocabulary and generation metadata

`vocab.txt` contains 9,415 unique non-empty lines, matching both the decoder
logit width and `decoder.word.weight` shape `[9415, 256]`. Special token
indices are `[PAD]=0`, `[UNK]=1`, `[CLS]=2`, `[SEP]=3`, `[MASK]=4`.

**CONFIRMED:** no model-side `config.json`, generation config, tokenizer config,
or special-token map was shipped. The 28-byte README only declares
Apache-2.0. Therefore beam/sampling policy, EOS stopping policy, preprocessing
normalization, and authoritative BOS/EOS IDs are not available in this bundle.

**LIKELY:** `[CLS]`/`[SEP]` are intended as start/stop tokens because the
vocabulary uses the standard names and IDs, but this must not be treated as a
generation contract without the exporter/inference code. Likewise, the
decoder's 128-row `decoder.pos.weight` strongly suggests a 128-position
learned positional limit. This limit is directly exercised below.

**CONFIRMED:** on CPU ONNX Runtime, `decoder_step.onnx` accepts a valid
`position_ids` value 127 but rejects 128 with a Gather out-of-bounds error
(`idx=128`, valid range `[-128,127]`). The 256-slot self cache is therefore
not evidence that 256 output positions are supported. Generation beyond 128
positions is **FAILED** for the checked graph and must be bounded or handled
by a different export.

## Runtime and acceleration implications

### CPU / XNNPACK

**LIKELY:** CPU is the most portable baseline. All required operators are
ordinary ONNX ops, shapes are fixed, and the graphs contain no custom domain
operator in their nodes. The decoder's work is still repeated once per output
token; cache reuse avoids recomputing prior self-attention keys/values but does
not remove the four-layer decoder step.

The exported weights are all FP32. **LIKELY:** FP32 CPU execution will be
memory/compute heavier than an INT8/FP16 export, but an accuracy-preserving
quantization decision needs model-level validation and is outside this graph
inventory.

### Mobile GPU

**LIKELY:** the fixed shapes are favorable to ahead-of-time compilation, but
the decoder step has many small-ish matrix/transposition/normalization and
indexing operations. A GPU provider may have higher dispatch overhead than
CPU for one-token steps and may partition unsupported `Gather`, `Where`,
`LessOrEqual`, or INT64 indexing back to CPU. **UNTESTED:** no mobile GPU
provider compiled or executed these exact graphs.

### NPU / NNAPI / QNN

**LIKELY:** the encoder's convolution-heavy graph is the best NPU candidate.
The decoder may be less portable because it mixes FP32 arithmetic with INT64
token/position inputs and cache/mask indexing. `Gather`, `Where`, `Equal`,
`LessOrEqual`, `Clip`, and unfused `LayerNormalization`/attention patterns are
the main provider-partition risks visible in the step graph. Static shapes and
batch 1 reduce shape-compile complexity, but do not establish support.

**UNTESTED:** this worktree has no target-device provider compile log for the
new graphs. CPU fallback must remain available; a provider failure in the
decoder should not invalidate the encoder or the normal reader path.

## Validation performed

1. `onnx.load(..., load_external_data=False)` and `onnx.checker.check_model`
   passed for all three files.
2. `onnxruntime` 1.24.1 CPU sessions loaded all three files.
3. A zero-input smoke run passed: encoder → decoder init with token `2` →
   decoder step with token `3`, position `1`, and zero-padded self cache.
   Observed shapes matched the graph declarations.
4. A separate CPU run at `position_ids=128` failed with the expected Gather
   bounds error, documenting the positional limit.

The smoke run is a structural/runtime check only. It is **UNTESTED** for OCR
accuracy, preprocessing parity, EOS behavior, Android memory pressure, and
provider-specific numerical parity.

## Recommended next action

Treat this bundle as a fixed-shape, batch-1 CPU baseline first. Implement the
caller around the explicit init/step cache contract and a hard generation cap
below 128 positions, then run an Android matrix comparing CPU/XNNPACK,
available GPU, and QNN/NNAPI providers. Only after that matrix should the team
consider FP16/INT8 or a provider-specific graph rewrite.
