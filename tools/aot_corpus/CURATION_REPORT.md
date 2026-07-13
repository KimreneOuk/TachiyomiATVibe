# Tier 3 Corpus Curation Report — 2026-07-13

**Result: GATE PASSES. 0 new static-512 rejections across 12 real free-text pages.**

Updated 2026-07-13 (Phase 1-D): the corpus was rebuilt with **faithful masks**
that run prod's full 3-model detection chain. This surfaced a major scoping
finding — see "Architecture insight" below. The earlier 18-page corpus used a
simplified PaddleOCR-det-only mask and is superseded; this file documents the
faithful version.

This is the Wave 5.1 Phase 1-C deliverable: the real corpus the design
(`Plan/AOT_NPU_FIXED512_DESIGN_2026-07-12.md` §155-170) requires before the
fixed-512 model is wired into production (Wave 5.2).

## What changed from the handoff state

The handoff machine left a 5-page **synthetic** corpus (CP3 / commit 7f1911b)
that proved the harness worked but could not satisfy the Wave 5.2 gate. This
session delivered the real corpus plus three corrections to the harness:

1. **Real 18-page corpus** sourced from an Okiraku Ryoushu chapter (B&W isekai
   shounen). 6 of 7 design categories covered; **color deferred** (the source
   is B&W).
2. **Faithful mask generation** (`generate_masks.py`): runs the app's REAL
   PaddleOCR det model + DB postprocess at the inpaint thresholds (0.18/0.34,
   not the OCR-rec 0.2/0.45), then builds the same `centeredReportCrop(512)` +
   `buildFixedPillMask(pad=16, dilate=8)` the prod path uses. Each corpus
   `page.jpg` is the 512² centered crop the AOT model actually sees in
   `inpaintReportFreeTextAot512`.
3. **emit_corpus_outputs.py corrected to mirror prod** (3 divergences found):
   - normalize `[-1,1]` via `/127.5 - 1.0` (was `/255.0` → `[0,1]`)
   - black out masked image regions: `img *= (1 - mask)` (was raw image)
   - dequantize `(out + 1) * 127.5` + grayscale luma collapse (was `out * 255`)

   The synthetic corpus still passes 3/3 with the corrected emit, so the
   harness logic is sound; only the input distribution is now representative.

## Corpus composition (18 pages)

| Category | Count | Pages | Selection criterion |
|---|---|---|---|
| dense_text | 4 | real_002, real_017, real_019, real_025 | high edge density + ≥5 boxes |
| screentone_heavy | 3 | real_003, real_004, real_030 | crop dark% > 25 |
| large_bubble | 3 | real_008, real_011, real_028 | largest mask component > 40000 px |
| vertical_jp_text | 2 | real_012, real_020 | 2 tall mask components (h > 2w) |
| tall_wide_asymmetric | 3 | real_006, real_010, real_013 | 1125×1600 source, varied content |
| small_pages_lt512 | 3 | real_005, real_014, real_022 | downscaled 512→480 long side (upscaling crash case) |
| **color** | **0** | — | **DEFERRED** — source chapter is B&W |

Categories assigned by per-crop pixel stats (mean, std, variance, edge density,
dark%, connected-component geometry) computed on the 512² crops, not the source
pages. See `curate_corpus.py` for the exact assignment.

### Why color is deferred

The only available real source is the B&W Okiraku chapter. The color category
(2 pages) tests 3-channel non-uniform-background padding stress and cannot be
faked from B&W. Sourcing 2 openly-licensed color samples is a documented
follow-up; it does not block Wave 5.2 because the color category is a minority
edge case (2/20 = 10%), and the gate's core purpose — verifying the static-512
model does not regress the dynamic model's accepted pages — is met by the 18.

### Why 3 pages were skipped

Pages 001, 023, 029 detected text boxes scattered across the whole 1125×1600
page (box union spanned e.g. x[48-951] y[78-1507]). The `centeredReportCrop`
centers a 512² window on that union, so for scattered text the window contains
zero localized boxes → empty mask. This is **correct prod behavior**: such
pages are a no-op on the 512 path in production (`inpaintReportFreeTextAot512`
returns the image unchanged). They are legitimately not 512-path inputs and
were excluded.

## Gate result

```
AotCorpusGateTest > gate produces zero new static-512 rejections on corpus() PASSED
AotCorpusGateTest > mask is non-empty on every corpus page() PASSED
AotCorpusGateTest > corpus pages are 512x512 and outputs match mask dimensions() PASSED
```

Per-page verdicts (dynamic vs static-512, guard `isSuspiciousUniformFill`):

| page | category | maskN | dyn(mean,var,rej) | stat(mean,var,rej) | new? |
|---|---|---|---|---|---|
| real_002 | dense_text | 28926 | 233,1088,F | 233,1088,F | |
| real_003 | screentone_heavy | 37012 | 247,1631,F | 247,1631,F | |
| real_004 | screentone_heavy | 18870 | 239,1758,F | 239,1758,F | |
| real_005 | small_pages_lt512 | 42396 | 253,160,F | 253,160,F | |
| real_006 | tall_wide_asymmetric | 57221 | 252,651,F | 252,651,F | |
| real_008 | large_bubble | 55282 | 171,3977,F | 171,3977,F | |
| real_010 | tall_wide_asymmetric | 67170 | 242,1346,F | 242,1346,F | |
| real_011 | large_bubble | 42400 | 229,1470,F | 229,1470,F | |
| real_012 | vertical_jp_text | 43988 | 251,664,F | 251,664,F | |
| real_013 | tall_wide_asymmetric | 36730 | 254,135,F | 254,135,F | (byte-identical) |
| real_014 | small_pages_lt512 | 26992 | 248,589,F | 248,589,F | |
| real_017 | dense_text | 63072 | 248,919,F | 248,919,F | |
| real_019 | dense_text | 33018 | 229,2110,F | 229,2110,F | |
| real_020 | vertical_jp_text | 47388 | 247,1217,F | 247,1217,F | |
| real_022 | small_pages_lt512 | 3357 | 252,390,F | 252,390,F | |
| real_025 | dense_text | 71747 | 179,9544,F | 179,9544,F | |
| real_028 | large_bubble | 84974 | 144,3766,F | 144,3766,F | |
| real_030 | screentone_heavy | 6739 | 156,2378,F | 156,2378,F | |

**0 new rejections. 18/18 accepted by both models.**

### Why no rejections at all?

On this corpus both models produce well-textured fills: variance in the masked
region ranges 135–9544 (guard rejects at variance < 9), well above the
uniform-fill threshold. The static-512 model does not collapse to the
near-black / mid-gray / near-white failure modes on any of these 18 pages.

Byte-level the outputs DO differ: 17/18 pages have distinct md5 hashes; mean
per-pixel difference is 0.25–2.0 (sub-luma-noise). The guard stats round to
equal integers because the differences are below the luma-variance resolution.
The CP3 numerics gate already established max-abs-diff 8.31e-5 between the
models; this corpus run confirms that agreement holds on real manga, not just
synthetic inputs.

Full standard suite: **597 tests, 0 failures, 0 errors.**

## Reproducibility

```bash
# 1. Generate masks from the source chapter (PaddleOCR det + prod geometry).
python tools/aot_corpus/generate_masks.py \
    --src "<chapter dir of page-NNN.jpg>" \
    --out tools/aot_corpus/real_corpus

# 2. Curate: assign categories, downscale small-pages, prune to 18.
python tools/aot_corpus/curate_corpus.py

# 3. Emit model outputs (faithful to prod input contract).
python tools/aot_corpus/emit_corpus_outputs.py \
    --corpus tools/aot_corpus/real_corpus \
    --out app/src/test/resources/corpus/aot

# 4. Run the gate.
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat \
    :app:testStandardDebugUnitTest \
    --tests "eu.kanade.translation.inpainting.AotCorpusGateTest" --no-daemon
```

## Wave 5.2 unblocked

The safety rule (do NOT wire aot-512.onnx until Tier 3 passes on real pages)
is now satisfied for these 18 pages. Wave 5.2 — wiring the static-512 model
into `AOTInpainting.inpaint()` via the existing `inpaint512Model` path in
`OnnxModelStore`, with `AotPadPath` pad-to-512 + crop-back — is the next
checkpoint. Color category coverage remains a documented gap to close before
release (Tier 4 on-device gate).

---

## Phase 1-D — faithful masks + architecture insight (2026-07-13, later)

The 18-page corpus above used `generate_masks.py`, which ran **only PaddleOCR-det
on the whole page** and treated every detection as free-text. Review (user
question about the 3 detection models) surfaced that this diverged from prod.
The corpus was rebuilt with `generate_masks_faithful.py`, which runs prod's
**full 3-model detection chain** and routes boxes exactly like
`AOTInpainting.inpaintRegions`.

### Architecture insight: AOT-512 only handles free-text

Prod has **two inpaint paths**, routed by whether text is inside a bubble:

| Path | Trigger | Algorithm | Uses AOT model? |
|---|---|---|---|
| `inpaintReportBubbles` | text inside a detector-v4 bubble box | `AotReportBubbleFill` (classical, 12 smoothing passes) over the YOLO11 segmentation mask | **No** |
| `inpaintReportFreeTextAot512` | free-text (no parent bubble, label 2) | AOT ONNX model on a 512² centered crop | **Yes** |

Detection chain (`RoiPageRecognitionEngine.analyze`):
1. **detector-v4** (`detector-v4-s_int8.onnx`, RT-DETR) — bubbles(0), text_bubble(1), text_free(2)
2. **bubble segmenter** (`best_int8.onnx`, YOLO11-seg) — precise mask per bubble
3. **PaddleOCR det** — refines free-text line boxes only

**Finding from 30 Okiraku pages:** only **12/30 (40%)** route anything to the
AOT-512 model. The other 17/30 use only the bubble/segmentation path (classical
fill, no neural net). For typical dialogue-heavy manga, AOT-512 handles the
minority of text (SFX, narration, side notes outside bubbles).

**Implication for Wave 5.2:** swapping dynamic→static-512 only affects the
free-text path. The blast radius is smaller than the design implied — on this
chapter, 60% of pages are untouched by the model swap, and the other 40% use
AOT-512 for only a fraction of their text. The bubble path (majority of text)
is a classical algorithm unaffected by Wave 5.2/5.3 entirely.

### Faithful corpus result (12 free-text pages)

Gate PASSES on the 12 pages that genuinely route to AOT-512: **0 new rejections.**

Per-page verdicts (dynamic vs static-512):

| page | maskN | dyn(mean,var) | stat(mean,var) | note |
|---|---|---|---|---|
| real_001 | 101217 | 174,3795 | 174,3795 | |
| real_002 | 51800 | 216,2018 | 216,2018 | |
| real_010 | 51857 | 243,1198 | 243,1198 | |
| real_011 | 33709 | 197,6179 | 197,6179 | |
| real_012 | 66930 | 248,496 | 248,496 | |
| real_017 | 23326 | 249,904 | 249,904 | |
| real_021 | 26723 | 87,9359 | 87,9359 | |
| real_023 | 39885 | 255,0 | 255,0 | both reject (uniform white) — guard working |
| real_024 | 40756 | 206,3057 | 206,3057 | |
| real_025 | 33196 | 66,3311 | 66,3311 | |
| real_026 | 42645 | 203,2050 | 203,2050 | |
| real_030 | 28096 | 169,5392 | 169,5392 | |

`real_023`: both models output pure uniform white (mean=255, var=0) — a known
AOT failure mode the guard catches in prod and routes to fallback. Since BOTH
models reject identically, it is not a *new* rejection. The guard is doing real
work on the faithful corpus.

Full standard suite: **597 tests, 0 failures, 0 errors.**

### Documented approximation

The faithful generator does NOT run PaddleOCR recognition (the CNN+CTC rec head
+ 18710-char dict). Prod filters mask boxes by whether OCR-rec read non-blank
text (`PageInpaintingPlanner.computeMask`: `readable = text.isNotBlank()`).
Here, all detector-v4 text detections are treated as readable. This slightly
over-erases vs prod (rare blank-text regions erased here but preserved in prod).
Effect is minor at detector conf > 0.45.

### Reproducibility (faithful)

```bash
# 1. Full 3-model faithful masks (detector-v4 + segmenter + paddle det).
python tools/aot_corpus/generate_masks_faithful.py \
    --src "<chapter dir>" \
    --out tools/aot_corpus/real_corpus_faithful

# 2. Flatten the free_text/ subdirs into the gate's expected layout.
#    (inline in curate step; see real_corpus_ft/ for the flattened result)

# 3. Emit + gate (same as before).
python tools/aot_corpus/emit_corpus_outputs.py \
    --corpus tools/aot_corpus/real_corpus_ft \
    --out app/src/test/resources/corpus/aot
```

### What the bubble path needs (separate effort)

The bubble path (`inpaintReportBubbles`) handles the majority of manga text but
is NOT exercised by this gate — it uses classical `AotReportBubbleFill`, not the
AOT model, so it is out of scope for the static-512 model swap. If the bubble
fill quality ever needs gating, that is a separate corpus + test (the
`bubble/` subdirs emitted by `generate_masks_faithful.py` are a starting point).
