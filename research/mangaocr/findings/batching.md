# MangaOCR batching findings

Date: 2026-09-18

Raw evidence: [batching-probe.json](../results/batching-probe.json). Re-run
with `python research/test_batching.py`; the script uses the exact model bytes
under `research/models/manga-ocr-mobile/` (the same SHA-256 values as the app
assets) and derives candidate dynamic graphs through the existing lab patch in
`tools/mangaocr_lab/lab/graphs.py`.

## Executive result

**CONFIRMED** — the shipped encoder, `decoder_init`, and `decoder_step` are
batch-1 only. ORT rejects B=2, B=4, and B=8 for all three pristine graphs.

**CONFIRMED** — the reviewed dynamic graph derivation accepts B=1, B=2, B=4,
and B=8 for all three stages on CPU ONNX Runtime. It is a derived research
artifact; no app code or packaged asset was changed.

**CONFIRMED** — batched encoder, init, and first decoder-step outputs match
independent B=1 row runs exactly in this probe (max and mean absolute error
0.0 for B=2/4/8). The existing patched-graph B=1 gate also passed before this
probe: token sequences equal, encoder max error 1.21e-5, decoder max error
1.91e-5 against pristine graphs (tolerances 5e-5 and 1e-4).

**CONFIRMED** — true batched decoder scheduling works with independent EOS.
Eight fixture crops decoded in one B=8 microbatch produced EOS positions
4, 5, 6, and 12. Rows that finished early were fed EOS filler while active
rows continued to the shared stop. Every row’s tokens and EOS position matched
its independent B=1 run.

## Static and dynamic graph shapes

| stage | pristine batch shape | derived batch shape | B=8 observed output |
|---|---|---|---|
| encoder input/output | `[1,3,224,224]` → `[1,196,256]` | `[N,3,224,224]` → `[N,196,256]` | `[8,196,256]` |
| decoder_init hidden/logits | `[1,196,256]` → `[1,9415]` | `[N,196,256]` → `[N,9415]` | `[8,9415]` |
| decoder_init KV | `[4,1,4,1,64]`, `[4,1,4,196,64]` | batch axis `N` | `[4,8,4,1,64]`, `[4,8,4,196,64]` |
| decoder_step hidden/logits | `[1,196,256]` → `[1,9415]` | `[N,196,256]` → `[N,9415]` | `[8,9415]` |
| decoder_step cache/slices | cache `[4,1,4,256,64]`, slice `[4,1,4,1,64]` | batch axis `N` | cache batch axis 8; slice `[4,8,4,1,64]` |

The derived decoder metadata labels both dimensions of `[B,1]` scalar feeds
as `N` (`[N,N]`) because the patch symbolises dimensions 0 and 1. ORT accepts
the actual `[B,1]` feeds used by the app/lab, but this metadata convention is
a **LIKELY** portability risk for stricter mobile/NPU compilers and must be
checked when compiling the graphs for the target execution provider.

## KV-cache scaling

**CONFIRMED** — cache memory scales linearly with B. For FP32, the two self
caches (`self_k` + `self_v`) are 2,097,152 bytes per row and the two cross
caches (`cross_k` + `cross_v`) are 1,605,632 bytes per row:

| B | self K/V | cross K/V |
|---:|---:|---:|
| 1 | 2 MiB | 1.53 MiB |
| 2 | 4 MiB | 3.06 MiB |
| 4 | 8 MiB | 6.12 MiB |
| 8 | 16 MiB | 12.25 MiB |

These are tensor payload sizes only; ORT/backend workspace and model weights
are not included.

## Decoder scheduling semantics

**CONFIRMED** — a B=8 true batch needs 11 shared `decoder_step` calls for the
selected fixture chunk, while individual rows required 3, 4, 5, or 11 calls.
The shared call count is the longest active row, so early-EOS rows continue as
EOS filler. This is independent-EOS scheduling without per-row graph calls.

**CONFIRMED** — the tested implementation uses true-size chunks, not padded
input rows. It keeps finished rows in place and discards their logits; no
cache compaction or row reindexing occurs.

**UNTESTED** — cache compaction/padding strategies. They are not needed for
correctness of the tested strategy, but could reduce wasted decoder work when
length variance is high. Any compaction design must preserve each row’s KV
cache and restore input order.

**UNTESTED** — Android NNAPI/QNN/NPU execution of the derived graphs. The
results establish CPU ORT shape and numerical behavior only; desktop timings
must not be interpreted as mobile performance.

**UNTESTED** — the supplied real chapter pages. They are full-page images, not
already extracted OCR crops, so using them directly would not test this model
contract. The schedule check instead used eight deterministic OCR-crop
fixtures covering two categories and deliberately different EOS lengths.

## Recommendation

**LIKELY** — use dynamic encoder + dynamic init + dynamic step with host-side
lockstep scheduling as the first implementation candidate, gated on target
backend compilation and an Android numerical/token regression pass. Start with
B=2 or B=4 because B=8 requires 28 MiB of FP32 KV payload before backend
workspace and increases early-EOS filler work; select B empirically after
target-device measurements.
