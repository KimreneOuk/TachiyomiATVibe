# T927 — MangaOCR Desktop Laboratory: Design Specification

Status: **design, awaiting Director approval to implement.**
No lab code exists yet; no Android production code is touched by this task.

---

## 1. Purpose and the two questions

The laboratory exists to answer, with per-ROI observability:

**DESKTOP QUESTION**
> Does batching preserve correctness and reduce runtime/dispatch overhead?

This is a *logic + dispatch* question. The lab answers it with per-ROI token
equality against a corrected B=1 reference, session.run call counts, and
desktop wall-time deltas.

**ANDROID QUESTION** (explicitly out of scope for the lab's conclusions)
> What microbatch size provides the best real-world performance/memory/thermal
> behavior on ARM?

The lab deliberately does **not** answer this. Desktop timings come from
x86-64 Windows, ORT 1.24.1 CPU, no `NativeRunQuarantine` lane, no decode/I/O
pressure, no thermal state. Desktop `session.run` **counts** transfer to
Android exactly (they are properties of the algorithm); desktop
**milliseconds do not**. Every report the lab prints carries this caveat
banner when timing columns are shown.

Correctness ground truth is the **corrected B=1 reference decoder**
(§5), never the Android decoder (which has the proven KV write-slot defect,
`evidence/onnx-graph-findings.md` §Android host-protocol defect).

---

## 2. Platform recommendation

**Recommendation: Python 3.11 + onnxruntime (CPUExecutionProvider) + numpy +
Pillow for the laboratory. Kotlin/JVM parity later, as a port target.**

Rationale against the four criteria:

| Criterion | Python + ORT | Kotlin/JVM + ORT |
|---|---|---|
| ONNX tensor inspection | `onnx` 1.20.1 protobuf access, `numpy_helper`, shape inference — used extensively in T927 evidence work | possible but all tooling must be hand-written |
| Intermediate tensor dumping | append `value_info` to `graph.output`, one line | possible; more ceremony |
| Graph debugging/surgery | proven: the entire batch patch (17 constants, 2 ReduceMean axes, value_info clear) was developed and verified in hours on this machine | no equivalent workflow exists in-repo |
| Speed of iteration | seconds, no Gradle | build + instrumented-test cycle |
| Equivalence with Android | algorithmic parity is enforced by identical constants + a later numeric parity probe (§13) | native parity, but only valuable *after* the algorithm is settled |

The environment is already proven on this exact machine: every experiment in
`evidence/onnx-graph-findings.md` ran with Python 3.11.0, onnx 1.20.1,
onnxruntime 1.24.1, Pillow.

Kotlin/JVM parity (§13.3) becomes worthwhile only once the batching state
machine is frozen. The lab's pure-logic modules are written to port
mechanically (§13).

---

## 3. Folder structure

Lives under the repo's existing `tools/` tree (sibling of
`tools/gpu_isolation/`). Entirely outside Android sources.

```
tools/mangaocr_lab/
  README.md                     # usage, caveats (DESKTOP vs ANDROID question)
  models.lock.json              # expected SHA-256 of pristine bundled assets
  manga_ocr_lab.py              # single-entry CLI (thin dispatcher)
  lab/
    __init__.py
    assets.py                   # locate + hash + verify assets; cache paths
    graphs.py                   # deterministic batch-capable graph derivation
    preprocessing.py            # crop -> fp32 CHW [1,3,224,224] (Android-identical)
    sessions.py                 # CountingSession wrapper (per-model run counters)
    reference.py                # corrected B=1 reference decoder
    android_legacy.py           # exact production-protocol emulation (defect included)
    batched.py                  # microbatched decoder (B in {1,2,4,8,...})
    state.py                    # RowState / MicrobatchState pure-logic module
    decode_common.py            # token<->text, EOS/limit policy, softmax confidence
    numeric.py                  # batch-vs-B1 tensor comparisons
    tracing.py                  # per-step JSONL dumps (--trace)
    records.py                  # dataclasses = the only schema definitions
    report.py                   # terminal rendering + results/ writers
    corpus.py                   # corpus discovery, categories, expected_text sidecars
  work/                         # gitignored, regenerable
    derived/                    # patched graphs, keyed by pristine-content hash
      <sha8>/encoder.b0c0.onnx, decoder_init.b0c0.onnx, decoder_step.b0c0.onnx
      gate.json                 # B=1 patched-vs-original gate result
  test_crops/                   # regression corpus (images gitignored, layout in VCS)
    README.md
    real/ digits/ kana/ kanji/ punctuation/ latin/ mixed/ long/ noisy/ empty/
    previous_failures/
  results/                      # gitignored run artifacts
    <UTC timestamp>-b<batchsizes>-<tag>/
      run.json
      per_crop.csv
      mismatches.json
      trace/<crop_id>.reference.jsonl
      trace/<crop_id>.b<B>.jsonl
```

A crop is `image file + optional sidecars`:
`<id>.png` (or .jpg/.webp), `<id>.expected.txt` (ground-truth text if known),
`<id>.meta.json` (provenance; required in `previous_failures/`).

---

## 4. Model assets and derived batch-capable graphs

### 4.1 Pristine assets (never modified)

`assets.py` resolves the four files from
`app/src/main/assets/models/ocr/`, computes SHA-256, and prints/records them
on every run:

```
encoder.onnx       d1fb455a07c1508cc56a4f4e15e2ed74aca9a4d78fd220ecc0ff39625d67e1b3
decoder_init.onnx  612f97e22848620fb36fcac611467689cb4213d91f2d84f6a19042ad57d475f1
decoder_step.onnx  a244b814a3a669190f9eae737960997633597cfa055fa186347e872bee834bc9
vocab.txt          <recorded at lock time>
```

`models.lock.json` pins these. Any mismatch → hard error, unless
`--allow-unlocked-models` (which stamps the run.json as `unlocked: true`).
The header of every terminal report repeats the hashes (req 1: "make it
obvious which model hashes are being tested").

### 4.2 Derived batch-capable graphs (`graphs.py`)

The exact patch proven in T927 evidence (bit-exact at batch 30), applied to
temp copies, cached under `work/derived/<sha8>/`:

- **encoder**: symbolic batch dim on graph input; 10 Reshape-target
  constants leading `1 → -1` (`const_fold_opt__1285/1050`,
  `new_shape__776/793/797/798/799/810/822`, `const_fold_opt__937`);
  both `ReduceMean` `axes [0,2] → [2]` (removes batch-axis pooling — a
  batch-1 export artifact, identical at B=1). No value_info present.
- **decoder_init**: symbolic dims 0 and 1 on graph I/O; 5 constants:
  `val_15 [1,1,4,64]→[-1,1,4,64]`, `val_37 [1,1,256]→[-1,1,256]`,
  `val_49 [1,196,4,64]→[-1,196,4,64]`, KV stacks `val_342 [4,1,4,1,64]→[4,-1,4,1,64]`,
  `val_356 [4,1,4,196,64]→[4,-1,4,196,64]`; delete all 373 `value_info`
  entries.
- **decoder_step**: symbolic dims 0 and 1; `val_26`/`val_61` as above;
  delete all 387 `value_info` entries.
- Constant discovery is **generic** (scan Reshape shape-sources for leading-1
  int64 arrays; KV-stack form `[4,1,4,S,64]` patch index 1), not a hardcoded
  name list, so re-derived graphs survive upstream model updates.

**Gate (mandatory before any batched run):** run original vs patched at B=1
on 3 fixture crops → require identical token sequences AND logit maxdiff
≤ 5e-5 (encoder) / ≤ 1e-4 (decoders). Result cached in `gate.json` keyed by
all input hashes. `--skip-gate` exists but stamps the run.json. Observed
actuals: encoder 1.26e-05, decoders 0.00e+00.

`work/` is gitignored and fully regenerable from pristine assets + this file.

---

## 5. Corrected B=1 reference decoder (`reference.py`)

Implements the graph's own semantics (`evidence/onnx-graph-findings.md`):

```
constants: BOS=2  EOS=3  VOCAB=9415  POS_LIMIT=128  CACHE_WINDOW=256
           LAYERS=4 HEADS=4 HEAD_DIM=64 ENC_TOKENS=196 HIDDEN=256

init:   input_ids=[[BOS]]                                  # BOS is position 1
        -> logits0 [1,9415], self_k/v [4,1,4,1,64], cross_k/v [4,1,4,196,64]
caches: sk,sv = zeros[4,4,256,64]; sk[:,:,:,0,:]=self_k; sv likewise
t = argmax(logits0);  if t == EOS: return empty(eos_position=1)

pos = 2; cur = t
while pos <= POS_LIMIT - 1:                # positions 2..127 fed; 128 never fed
    logits, k_slice, v_slice = step(cur, pos, sk, sv, cross_k, cross_v)
    sk[:,:,:,[pos-1],:] = k_slice          # GRAPH CONVENTION: slot = pos - 1
    sv[:,:,:,[pos-1],:] = v_slice
    t = argmax(logits)
    if t == EOS: eos_position = pos; break
    tokens.append(t); cur = t; pos += 1
else:
    position_limit_reached = true          # silent truncation, as the model dictates
```

Notes:
- The reference consumes init's logits as token 1 (the Android loop instead
  discards them and re-predicts with a redundant step at position 1 — the
  reference does not copy that).
- The maximum generated-token count is 127, identical to production's
  effective ceiling (`MangaOcrEngine.kt:256-260` breaks before feeding
  position 128).
- Greedy argmax only (matches production). Per-token confidence =
  softmax max-probability over the 9415 logits; records carry mean and min.

### 5.1 `android_legacy.py` — production-protocol emulation

`--decoder android-legacy` reproduces the **current MangaOcrEngine protocol
exactly**: discard init logits, first step at position 1 re-predicts token 1,
host KV slice written at slot `pos` (the off-by-one), `MAX_GENERATION_LENGTH`
outer bound of 300. Purpose: export/reproduce device-observed failures and
quantify the misalignment's corpus impact. It is *never* a comparison
baseline; reports label it `legacy-defective`.

---

## 6. Microbatched decoder (`batched.py` + `state.py`)

`state.py` is a pure-logic module (no I/O, no ORT imports) — this is the
module that ports mechanically to Kotlin later.

```
RowState:   crop_id, tokens[], next_input, finished, outcome
            (EOS_AT, POSITION_LIMIT, ACTIVE), eos_position, conf[]
MicrobatchState:
    position      (SCALAR — see lockstep constraint below)
    input_ids [B,1], position_ids [B,1]      # int64, every row = `position`
    sk, sv    [4,B,4,256,64]                 # fp32 host caches, zero-init
    rows: list[RowState]
    step(logits, k_slice, v_slice):          # slice shape [4,B,4,1,64]
        for b in all rows:
            sk[:,b,:,:,position-1,:] = k_slice[:,b,:,:,0,:]   # slot = pos-1
            sv[:,b,:,:,position-1,:] = v_slice[:,b,:,:,0,:]
        for b in active rows:
            t = argmax(logits[b])
            EOS -> outcome=EOS_AT (eos_position=position), next_input := EOS
            else append token, next_input := t
    advance(): position += 1  (all rows, including finished filler rows)
```

**LOCKSTEP CONSTRAINT (probed, see evidence §Batch position semantics):**
the graph extracts position as a scalar (`Gather(Clip(position_ids-1),[0])` ×2),
so at batch N **row 0's position broadcasts to every row** — both the cache-write
one-hot and the causal mask. Verified: heterogeneous per-row positions produce
mismatches vs solo execution (maxdiff 0.99); uniform positions with heterogeneous
tokens are exact (0.00). Therefore `position` is a **microbatch scalar**: all rows
advance together; finished rows are fed EOS as filler and their outputs discarded;
the whole microbatch stops at position 127 (any row still active then takes
`POSITION_LIMIT`, mirroring the B=1 ceiling). Strategy A satisfies this exactly;
compaction (strategy B) preserves it.

Driver:
1. Corpus order is frozen; ROIs are chunked into `ceil(N/B)` microbatches;
   the last microbatch runs at its true size (graphs are fully dynamic in N
   after patching — **no padding, no mask rows**).
2. Per microbatch: 1 batched encoder run `[B,3,224,224]` → `[B,196,256]`;
   1 init run → per-row token 1; step loop until all rows finished.
3. Finished rows keep their slot in the arrays (strategy A, mask-by-ignore);
   their `next_input` freezes at EOS. Compaction (strategy B: Gather on dim
   1) is a designed extension point in `state.py`, not implemented in S1–S3.
4. Results are collected per `crop_id` and reassembled in input order —
   ordering bugs therefore show up as mismatches, not as reordering.

`--batch-sizes 1` runs the batched state machine with B=1; by construction it
must equal the reference token-for-token, which makes it a permanent
self-test of the state machine independent of graph patching.

---

## 7. Preprocessing (`preprocessing.py`) — Android-identical

Bit-for-bit algorithm of `MangaOcrEngine.preprocess` + `writeNormalizedChw`
(`MangaOcrEngine.kt:339-380, 443-463`):

1. Grayscale.
2. `ratio = 224 / max(w,h)`; resize to `(max(1, round-ish w*ratio), ...)`
   using the same rounding as Kotlin `toInt()` (truncation toward zero) —
   implemented with explicit floor to avoid Pillow's rounding differences.
3. White 224×224 canvas; paste centered at `(224-nw)//2, (224-nh)//2`.
4. Pixels → `[v/255]` → `(x-0.5)/0.5`, replicated ×3 channels, CHW float32.

Empty crop (max dimension 0) → `skipped_empty` record, excluded from
microbatches (matches production early-return).

A fixture-based unit check (one committed tiny PNG + its expected fp32
tensor digest) guards against silent preprocessing drift.

---

## 8. Records and result schemas (`records.py`)

All schemas are defined once as Python dataclasses with
`to_json/to_csv_row`; nothing else formats output.

### 8.1 Per-crop record (req 5)

```json
{
  "crop_id": "real_sample_004",
  "category": "real",
  "path": "test_crops/real/sample_004.png",
  "decoder": "reference | android-legacy | batched",
  "batch_size": 1,
  "text": "まんがおーく",
  "token_ids": [412, 88, 2603, ...],
  "token_count": 8,
  "eos_emitted": true,
  "eos_position": 9,
  "position_limit_reached": false,
  "skipped_empty": false,
  "confidence_mean": 0.987,
  "confidence_min": 0.71,
  "timings_ms": {
    "preprocess": 1.2,
    "encoder": 8.4,
    "decoder_init": 3.1,
    "decoder_step_total": 121.7,
    "total": 134.4
  },
  "decoder_step_calls": 9
}
```

### 8.2 Per-batch-run record (req 6)

```json
{
  "batch_size": 4,
  "decoder": "batched",
  "roi_count": 31,
  "microbatch_count": 8,
  "session_runs": { "encoder": 8, "decoder_init": 8, "decoder_step": 214 },
  "wall_time_ms": { "total": 2103.1, "encoder": 120.0, "decoder_init": 90.2, "decoder_step": 1800.4 },
  "peak_process_rss_mb": 412.7,
  "token_stats": { "avg_len": 38.2, "max_len": 87, "eos_count": 29, "position_limit_count": 2 },
  "vs_reference": { "roi_count": 31, "text_match": 31, "token_match": 31, "mismatched": [] }
}
```

### 8.3 Artifacts (req 11)

- `run.json` — everything: schema_version, UTC timestamp, CLI args,
  host block (python/platform/ORT/CPU), model block (pristine + patched
  hashes, gate status/maxdiffs), corpus counts, reference run record,
  one batch-run record per requested B, numeric-check results,
  `desktop_caveat: true`.
- `per_crop.csv` — **long format**, one row per (crop, decoder mode):
  `crop_id,category,batch_size,decoder,text,token_count,eos_emitted,eos_position,position_limit_reached,confidence_mean,confidence_min,enc_ms,init_ms,step_ms,step_calls,text_match,token_match,first_mismatch_idx`
- `mismatches.json` — every non-match, never aggregated away:

```json
[{
  "crop_id": "digits_003", "batch_size": 8,
  "text_match": false, "token_match": false,
  "first_mismatch": { "index": 5, "reference": 178, "batched": 45 },
  "reference_tokens": [31, 178, ...], "batched_tokens": [31, 178, ...],
  "reference_text": "1234567890", "batched_text": "12345kis378900"
}]
```

---

## 9. Correctness comparison (req 7)

Per ROI, per batch size, against the reference:

- `token_match` — exact token-ID sequence equality (the primary signal).
- `text_match` — decoded-string equality.
- `first_mismatch` — index + both token IDs + both token texts.
- Full sequences in `mismatches.json`; never only percentages.
- Terminal output lists every mismatching crop id; `--verbose` adds inline
  ref/batched token rows for each.

Because T927 demonstrated batched execution can be bit-exact
(maxdiff 0.00e+00), any token mismatch indicates a **state-machine bug**
(slot math, EOS bookkeeping, ordering, row indexing) — the comparison is
designed so such bugs are loud and localizable, not statistical.

---

## 10. Deep diagnostics — `--trace` (req 8)

```
python manga_ocr_lab.py trace --crop test_crops/real/sample_004.png --batch-size 4
```

Writes `trace/<id>.reference.jsonl` and `trace/<id>.b<B>.jsonl`. One JSON
object per generation step:

```json
{"sample":"real_sample_004","mode":"batch","batch_size":4,"row":2,
 "step":7,"position_id":8,"kv_slot":7,
 "input_token":2603,"input_text":"が",
 "argmax_token":88,"argmax_text":"の","is_eos":false,
 "top":[{"id":88,"text":"の","p":0.83},{"id":412,"text":"く","p":0.07}, ...],
 "logits_sum": -1234.56}
```

The init step emits a record too (`step:0`, `position_id:1`,
`input_token:2`). `logits_sum` (float checksum) makes it trivial to spot the
first diverging step between reference and batched runs of the same crop.
`row` is absent in reference traces and identifies the batch row otherwise.
Top-N depth default 5 (`--trace-top N`).

---

## 11. Numerical checking — `--numeric-check` (req 9)

For selected samples (default: 4 deterministic picks, or
`--numeric-check-crops a.png,b.png`), run each stage both batched and as
independent B=1 executions, and report per stage (encoder output,
decoder_init logits, decoder_step logits per step):

```
crop=digits_003 B=8
  encoder        max|Δ|=0.00e+00  mean|Δ|=0.00e+00
  init logits    max|Δ|=0.00e+00  mean|Δ|=0.00e+00
  step logits    steps=9 max|Δ|=0.00e+00 mean|Δ|=0.00e+00   (steps compared: min(len))
```

Steps are compared only up to the shorter of the two sequences; divergent
lengths are themselves reported. Results are embedded in `run.json`
(`numeric_checks[]`). Tolerance is reported, never enforced — the numbers
are the deliverable.

---

## 12. Regression corpus (req 10)

Fixed category layout under `test_crops/` (directories committed; images
gitignored except tiny synthetic fixtures):

```
test_crops/
  real/                # crops cut from actual manga pages (user-supplied)
  digits/ kana/ kanji/ punctuation/ latin/ mixed/
  long/                # expect near-ceiling sequences (pos-limit coverage)
  noisy/               # scans, screentone bleed, skew
  empty/               # blank/white crops -> skipped_empty path
  previous_failures/   # every future Android OCR failure lands here
```

`<id>.expected.txt` sidecars enable true accuracy measurement, not just
batch-vs-reference equality. `long/` and `empty/` must always contain
committed synthetic fixtures (PIL-rendered with a JP font, as used in the
T927 investigation) so the lab is never empty on a fresh clone.

`export-failure` subcommand: given a source page image, crop box, the app's
OCR output and optional expected text, writes
`previous_failures/<id>/{crop.png, meta.json}` — the standing bridge from
device incidents to desktop reproduction.

---

## 13. Reuse when the Android implementation begins (req 14, 15)

### 13.1 Shared by design (mechanical port)

| Lab module | Future Android counterpart |
|---|---|
| `state.py` RowState/MicrobatchState | core of a `MangaOcrBatchDecoder` |
| `decode_common.py` token/EOS/limit/confidence | same-named pure Kotlin object |
| KV indexing rule (`slot = pos−1`, window 256, limit 128) | constants + write loop |
| `preprocessing.py` algorithm | replaces the defective write path & reuses the pixel pipeline |
| run-count/wall-time instrumentation model | `[translation_perf]` counter extension |
| patched graphs (the exact `.onnx` bytes the lab validated) | candidate future bundled assets (separate Director decision) |
| numeric-check methodology | instrumented Android test |

### 13.2 Platform-specific (never shared)

- `Bitmap` extraction, `BitmapPool`, `DirectBufferPool`, `OnnxTensor`
  creation over direct buffers, ORT Android session options
  (`(cores/2).coerceIn(2,4)`, sequential mode, spin settings — the lab
  replicates these numerically via `--session-profile android` but the
  plumbing is desktop-side),
- `NativeRunQuarantine` lane integration, memory governance, checkpointing.

### 13.3 Kotlin/JVM parity probe (later, optional)

A single JVM ORT harness reusing the same patched graphs + corpus, checking:
preprocessed-tensor digest equality, reference token sequence equality. This
validates the *port*, not the algorithm — the algorithm is already pinned by
the lab.

---

## 14. CLI specification (req 3)

```
python manga_ocr_lab.py run --input test_crops/ --batch-sizes 1,2,4,8
      --compare-reference --verbose
python manga_ocr_lab.py run --input test_crops/digits --batch-sizes 8
      --compare-reference --numeric-check
python manga_ocr_lab.py trace --crop test_crops/real/sample_004.png --batch-size 4
python manga_ocr_lab.py verify-models
python manga_ocr_lab.py export-failure --image page.png --box x,y,w,h
      --app-output "..." [--expected "..."] --id 2026-09-12-box17
```

| Flag | Meaning |
|---|---|
| `--input PATH` | corpus root or single category/file |
| `--batch-sizes LIST` | microbatch sizes; `1` runs the batched SM sanity path |
| `--compare-reference` | per-ROI comparison vs reference (default on for `run`) |
| `--decoder reference\|android-legacy` | reference decoder selector |
| `--trace CROP [--trace-top N]` | per-step JSONL dumps |
| `--numeric-check [--numeric-check-crops LIST]` | stage-level tensor diffs |
| `--session-profile android\|lab` | replicate Android ORT session options or ORT defaults |
| `--limit N`, `--category NAME` | corpus slicing |
| `--output DIR` | results dir override (default timestamped) |
| `--allow-unlocked-models`, `--skip-gate` | escape hatches; always stamped into run.json |
| `--verbose` | per-crop lines, inline mismatch token rows |

No GUI, no daemon; every invocation is a fresh, self-describing run.

---

## 15. Terminal output format (req 12)

```
MODEL  encoder d1fb455a…  init 612f97e2…  step a244b814…  vocab 9415 tok
DERIVED patches 3f2a91c0…  gate=B1-EXACT (enc 1.3e-05 / dec 0.0e+00)
NOTE   desktop timings ≠ Android performance (dispatch counts do transfer)

ROIs: 31  corpus: test_crops/  session-profile: android

B=1 reference
  runs: enc=31 init=31 step=1188
  time: enc 260ms init 96ms step 3837ms total 4193ms
  tokens: avg 38.2 max 87   eos 29  pos-limit 2

B=4
  runs: enc=8 init=8 step=214   (-82% step dispatches)
  time: total 1610ms (-62%)
  matches reference: 31/31

B=8
  runs: enc=4 init=4 step=131   (-89% step dispatches)
  time: total 1477ms (-65%)
  matches reference: 30/31
  MISMATCH digits_003: first diff @5 ref=178('8') got=45('k')

peak RSS: 302/391/587 MB   (B=1/B=4/B=8)
```

(Run counts shown are illustrative shapes, not measurements.)

---

## 16. Execution flow (`run`)

```
1 assets.verify()          hash print + models.lock check
2 graphs.ensure_derived()  cached patched graphs + gate (unless --skip-gate)
3 corpus.discover()        crop list, categories, sidecars, skipped_empty
4 reference pass           per-crop CropRecord (req 5) + golden token seqs
5 optional legacy pass     --decoder android-legacy (defect emulation)
6 for B in batch-sizes:    microbatch pass (req 6 counters), comparison (req 7)
7 numeric checks           req 9, if requested
8 trace                    req 8, if requested
9 artifacts                results/<run>/{run.json, per_crop.csv, mismatches.json}
10 terminal report         §15
```

Fail-fast rules: unlocked hashes abort; failed gate aborts batched runs;
a batched run that cannot reproduce the reference is a **correct finding**,
never an error — it lands in mismatches.json with full evidence.

---

## 17. Staged implementation order

| Stage | Contents | Exit criterion |
|---|---|---|
| S0 skeleton | CLI, assets.py, models.lock.json, corpus discovery, `verify-models` | hashes print; corpus enumerated |
| S1 reference | preprocessing + reference decoder + records + run.json/per_crop.csv + terminal report | expected.txt fixtures decode correctly; empty/long fixtures behave per spec |
| S2 observability | --trace, android-legacy emulation, export-failure, numeric scaffolding | misalignment corpus impact measurable; a known failure reproducible from previous_failures/ |
| S3 batching | graphs.py patch + gate, batched.py/state.py, comparisons, --numeric-check | B∈{2,4,8} token-match 100% on corpus; dispatch counts match analytic model (§8.2) |
| S4 hardening | peak-RSS sampling, session-profile android, benchmark baselines committed to results/archive, README, optional JVM parity probe | repeatable runs, comparable over time |

S1–S2 are useful immediately for the (independent) KV write-slot defect
investigation; S3 is where batching conclusions come from.

---

## 18. Requirement map

| Req | Where |
|---|---|
| 1 exact assets + visible hashes | §4.1, §15 |
| 2 harness recommendation | §2 |
| 3 CLI, no GUI | §14 |
| 4 corrected B=1 reference first | §5 |
| 5 per-crop record | §8.1 |
| 6 per-batch record | §8.2 |
| 7 per-ROI comparison, no aggregates-only | §9, §8.3 |
| 8 --trace per-step dumps | §10 |
| 9 numerical checking | §11 |
| 10 corpus layout + previous_failures | §12 |
| 11 machine-readable results | §8.3 |
| 12 concise terminal output | §15 |
| 13 two questions separated | §1 (+ report caveat banner) |
| 14 reusable for Android | §6, §13 |
| 15 shared vs platform-specific | §13 |
| 16 structure/flow/CLI/schemas/order | §3, §8, §14, §16, §17 |
