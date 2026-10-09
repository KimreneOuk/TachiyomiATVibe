# Inpainting audit & three-mode redesign — OpenCV NS bubbles + manga-tuned AOT

Date: 2026-10-09 · Scope: `eu.kanade.translation.engines.inpainting.**`, model
pipeline, mode wiring, settings UI · Method: read-only code audit (all anchors
verified against the working tree), HuggingFace/web verification. Builds on
`Plan/inpainting-quality-audit/engine-audit.md` (findings F1–F12 referenced
throughout).

---

## 0. Executive summary

| Requested change | Audit verdict | Effort class |
|---|---|---|
| "Replace manual inpainting with OpenCV; bubbles get OpenCV **NS**" | **Dispatch change, not a new dependency.** Native OpenCV 4.9.0 is already linked (`org.opencv:opencv`, `gradle/libs.versions.toml:18,45`) and already runs free-text FAST via Telea (`OpenCvInpaintEngine`). Bubbles today use a hand-written median+smooth fill (`AotReportBubbleFill`) — they need a new dispatch branch, crop-scoped NS execution, and memory gating. | Medium |
| "AOT GAN receives its own algorithm" (no push-pull coupling) | Today `PushPullGradient.localRingMedian` fabricates the 512-tensor's out-of-crop padding (`AOTInpainting.kt:992-998,1302`). Replace with an AOT-local padding strategy (edge-replicate recommended) and confine push-pull to the emergency-only fallback. | Small–medium |
| "Replace current AOT GAN with the manga tune from `ogkalu/aot-inpainting`" | **The app already ships exactly that model.** `scripts/models.manifest:23-39` pins `ogkalu/aot-inpainting` @ `42ffc84f`; the HF repo is unchanged since 2025-10-16 (same commit, same `aot.onnx` sha256 `ffd39ed8…`). The card reads *"A jit-traced version of the model trained by zyddnys for manga-image-translator"* — i.e. the manga-tuned AOT. Replacement reduces to **provenance attestation + optional re-derivation of the fixed-512 variant**; NPU/GPU compatibility is structurally unaffected. | Small (verify) / optional |
| "Three modes: Fast / Balance / Quality" | Extends the existing two-value enum (`InpaintingMode.kt`). Requires: `BALANCE` value, pref/UI entries, engine init gate fix (F3), per-class dispatch table (F1), effective-mode persistence (F4), unknown-pref policy (F11). Quality-bubbles-on-AOT is **net-new capability** (no neural bubble path exists) and must solve the >512px bubble problem (F2). | Medium–large |

**Recommended mode matrix (user spec, verbatim):**

| Mode | Bubble regions | Free-text regions | AOT sessions loaded | Expected cost profile |
|---|---|---|---|---|
| **FAST** (default) | OpenCV **NS** (crop-scoped) | OpenCV Telea (existing FAST path) | None — init gate skips AOT entirely | Lowest latency + lowest RAM; classical only |
| **BALANCE** | OpenCV **NS** (crop-scoped) | AOT neural (full EP ladder, Telea fallback per group) | Fixed-512 + accelerator + dynamic (as QUALITY today) | ~today's QUALITY cost on free-text only; bubbles cheap |
| **QUALITY** | AOT neural (new path; NS fallback) | AOT neural (existing path) | Fixed-512 + accelerator + dynamic | Highest: neural passes for every region class |

---

## 1. Current-state architecture (verified)

### 1.1 The five inpainting algorithms in the tree

| # | Algorithm | Where | Role today | Classification |
|---|---|---|---|---|
| 1 | `AotReportBubbleFill` — connected components → BFS distance field → histogram-median fill (with paper-white/ink snap) → 12 box-smoothing passes → distance-field feather | `aot/AotReportBubbleFill.kt:11-227` | **All bubbles, in every mode** | Manual (Kotlin) |
| 2 | `PushPullGradient` — ring-median erase → box-downsample/bilinear-upsample gradient → 15 diffusion passes | `aot/PushPullGradient.kt:47-171` | (a) fabricates AOT-512 padding background; (b) emergency fill when OpenCV native missing | Manual (Kotlin) |
| 3 | `OpenCvInpaintEngine` — native `cv::photo::inpaint` (`Photo.inpaint`, Telea & NS flags, radius 3) | `opencv/OpenCvInpaintEngine.kt:25-200` | Free-text FAST path + neural-exhausted fallback ("telea" slot) | OpenCV C++ |
| 4 | AOT fixed-512 — static-shape graph, planar RGB **[0,1]**, mask as separate tensor | `aot/AotFixedTensorContract.kt`, `AotPadPath.kt` | Free-text QUALITY primary | Neural (ONNX) |
| 5 | AOT dynamic — variable shape ≤768, RGB **[−1,1]** with masked pixels zeroed | `AOTInpainting.kt:1216-1488` | Free-text QUALITY fallback after fixed-CPU failure | Neural (ONNX) |

> **Audit flag (new, not in F1–F12):** the two AOT tensor paths use *different*
> normalization conventions — fixed path writes `[0,1]` planar
> (`AotPixelOps.kt:45-47`, `AotFixedTensorContract.kt:31-38`), dynamic path
> writes `[−1,1]` and zeroes masked pixels (`AOTInpainting.kt:1363-1380`).
> The upstream manga-image-translator convention is `[−1,1] × (1−mask)`. The
> fixed path's `[0,1]` is **documented as deliberate** — `AotPixelOps.kt:39-44`:
> *"the Qualcomm AI Hub AOT-GAN export takes [0,1] RGB pixels and applies the
> mask internally"* — so the two graphs genuinely expect different contracts
> rather than one being miscalibrated. Still verify both routes against the
> workbench corpus in Phase 0 (the comment could itself be wrong, and this
> convention feeds every accelerator).

### 1.2 Dispatch flow today

```
PageInpaintingEngine.inpaint (PageInpaintingEngine.kt:34)
 └─ PageInpaintingPlanner.build → boxes + labels          (0=bubble, block-label=bubble-text, 2=free-text)
 └─ QUALITY gate: !inpainter.isInitialized() → throw unless fallback pref (:61-77)
 └─ effectiveMode = QUALITY if initialized else FAST (:73-77)
 └─ AOTInpainting.inpaintRegions (AOTInpainting.kt:386)
     ├─ partition boxes: label 0 → bubble; text w/ bubble parent or overlap → bubble-text; else free-text (:398-433)
     ├─ free-text: optional PaddleOCR-v6 DET refinement into line boxes (:437-444, tunables :43-49)
     ├─ cluster groups to ≤512 context (:446)
     ├─ BUBBLES (all modes): inpaintReportBubbles (:545)
     │    ├─ rasterize block segmentation masks + circular erosion r=5 (:552-567, :605-695)
     │    ├─ dynamic pill mask for remaining boxes (pad 8) (:581-588)
     │    └─ AotReportBubbleFill.fillAndBlend — median fill + 12 smooth passes + 12px feather (:593)
     └─ FREE-TEXT per group:
          ├─ mode==QUALITY && session available → inpaintReportFreeTextNeural (:722)
          │    ├─ 512² centered crop (REPORT_AOT_CONTEXT) (:728)
          │    ├─ EngineMemoryBudget.neuralInpaintDecision gate (:732-751)
          │    ├─ pill mask pad=1 dilate=2 (:761)
          │    ├─ prepareFixedInput: ring-median pad background → AotPadPath center → tensors (:977-1057)
          │    ├─ AotExecutionCoordinator.run: QNN_HTP→QNN_GPU→NNAPI → XNNPACK/CPU → dynamic → TELEA (:813-895)
          │    └─ feather composite ramp 3px (:896-909)
          └─ else → inpaintReportFreeTextFast (:697)
               ├─ paddedUnionBounds crop, 64px context (:702)
               ├─ pill mask pad=1 dilate=2 (:707)
               └─ OpenCvInpaintEngine.inpaintPixelsWithBackend — TELEA r=3 + 3px feather (:712-715)
```

### 1.3 Backend ladder (unchanged by this redesign)

`AotExecutionCoordinator.Backend = {QNN_HTP, QNN_GPU, NNAPI, XNNPACK, CPU, DYNAMIC}`
(`AotExecutionCoordinator.kt:11`). Order: preferred accelerator (Qualcomm HTP
NPU → QNN GPU → NNAPI, resolved by `HardwareDiscoveryEngine`) → fixed CPU
(XNNPACK or CPU) → dynamic → classical Telea. QNN HTP uses a precompiled
context-binary cache for ~300ms warm loads (`AOTInpainting.kt:166-195`),
circuit breakers on session/execution failure, strict no-CPU-fallback NNAPI
with a health monitor. Runtime: `onnxruntime-android-qnn` 1.28.0
(`libs.versions.toml:16,43`).

### 1.4 Mode wiring today

- Pref: `translation_inpainting_mode` ∈ {`"FAST"`, `"QUALITY"`}, default `"FAST"`
  (`TranslationPreferences.kt:116`); `translation_inpaint_quality_fallback`
  default false (`:124`), latched once per engine instance
  (`PageInpaintingEngine.kt:21-32` — defect F5).
- Parse: `"FAST" → FAST, else → QUALITY` (`EngineLane.kt:341-346`) — unknown
  values silently select the throwing mode (F11).
- Init gate: AOT sessions load **only** `if (inpaintingMode == QUALITY)`
  (`RoiPageRecognitionEngine.kt:376-383`) — any third value would silently
  degrade to FAST (F3).
- Stamp: only the *selected* mode is persisted (`SinglePageOnnxPhase.kt:866`);
  silent degradations are logcat-only and reused forever on resume (F4).
- UI: two-entry list + fallback switch gated on `QUALITY`
  (`SettingsTranslationScreen.kt:124-148`).

### 1.5 Model pipeline

`scripts/models.manifest` (v2) → `scripts/fetch_models.py` →
`app/src/main/assets/models/inpainting/{aot.onnx, aot-512.onnx}` →
`OnnxModelStore.ensureModels()` copies to
`<noBackupFilesDir>/tachiyomiat-models/` (`OnnxModelStore.kt:97-109`) →
`AOTInpainting.initialize(fixedModelFile, dynamicModelFile)`. The fixed-512
variant is produced by `scripts/converters/convert_aot_512.py` (static shape
fold `[1,3,512,512]`/`[1,1,512,512]`/`[1,3,512,512]` on tensors
`image`/`mask`/`inpainted` + `onnxslim`), then validated at load by
`AotModelContract` (input names `{image,mask}`/`{input_image,input_mask}`/`{input,mask}`,
1 output, NCHW) and benchmarked on QNN HTP by `QnnDiagnostics.kt:225,405`.

---

## 2. Model verification — "replace AOT with the manga tune"

### 2.1 Finding: the linked repo *is* the incumbent model

Verified against the HF API and repo page on 2026-10-09:

- `ogkalu/aot-inpainting` main SHA: **`42ffc84ff1bd46dd95f1c5a41e83ee7e98f39189`**, last
  modified 2025-10-16 — byte-identical to the pin in `scripts/models.manifest:26,38`.
- Files: `README.md`, `aot.onnx`, `aot_traced.pt`, `inpainting.ckpt`. The
  manifest's `aot.onnx` sha256 (`ffd39ed8e2a2…`) matches the deployed asset.
- Model card: *"A jit-traced version of the model trained by zyddnys for
  manga-image-translator."* License MIT. The upstream training lineage is the
  `zyddnys/manga-image-translator` project (original `inpainting.ckpt`, 21.7 MB,
  April 2022 GitHub release), architecture AOT-GAN
  (Aggregated Contextual Transformations, researchmm/AOT-GAN-for-Inpainting).
- **No newer manga-tuned checkpoint exists in this lineage.** The upstream
  project still distributes the same 2022 AOT checkpoint; the HF repo has had
  no commits since 2025-10-16.

So: the app's AOT **already is** the manga-tuned model the user linked. There
is nothing newer to swap in from that URL.

### 2.2 What "replacement" should therefore mean (recommendation)

1. **Attest (do now, zero risk):** add a `provenance` note to
   `scripts/models.manifest` entries and a comment in `AOTInpainting`
   identifying the model as the zyddnys manga-tuned AOT via
   `ogkalu/aot-inpainting@42ffc84f`. This converts an accidental pin into a
   documented, intentional one.
2. **Verify tensor conventions (before any re-derivation):** resolve the
   `[0,1]`-vs-`[−1,1]` fixed-path discrepancy (§1.1 flag) using the inpainting
   workbench (`run_inpainting_workbench.bat`, `Plan/inpainting-workbench-design.md`)
   on a fixed page corpus. If the fixed route is miscalibrated, correcting it
   is worth more quality than any model swap.
3. **Optionally re-derive `aot-512.onnx`** from the current upstream `aot.onnx`
   (the converter is deterministic; expect a byte-identical or near-identical
   artifact). Only worth doing if (2) changes the tensor contract. On swap:
   update manifest sha256s, re-fetch assets, confirm
   `QnnContextCacheManager` invalidates stale context binaries for the new
   file (cold compile once per device), and re-run `QnnDiagnostics` HTP parity.
4. **NPU/GPU compatibility: structurally unchanged.** Same graph family, same
   `AotModelContract`, same EP ladder, same session options. The manga-tuned
   AOT is a conv-GAN without exotic ops; it already validates and runs on
   QNN HTP in this app today (`QnnDiagnostics.kt:405` — ≥30-run benchmark +
   numerical parity). A re-derived graph only needs the standard
   `ModelRoutingEngine` re-validation path.

**If the actual desire is a *better* inpainting model than this 2022 AOT:**
that is a different work package (candidates: LaMa — much heavier; MI-GAN /
ZITS++ — mobile-friendlier; or MangaNinjia-style manga-specific models). Out
of scope for this redesign; flag for a future decision. The three-mode
architecture below is model-agnostic — the neural slot is behind
`AotModelContract`, so a successor model slots in without mode-logic changes.

---

## 3. Algorithm changes

### 3.1 Bubbles → OpenCV NS (FAST + BALANCE)

**Method choice.** `Photo.inpaint(src, mask, dst, radius, INPAINT_NS)` —
Navier–Stokes fluid-dynamics diffusion (Bertalmío et al.), already exposed as
`OpenCvInpaintEngine.INPAINT_NS` (`OpenCvInpaintEngine.kt:28`). Web research
consensus: both NS and Telea are diffusion methods; NS measured marginally
better quality *and* marginally faster than Telea in LearnOpenCV's tests; both
blur on large holes — but manga bubble interiors are near-uniform paper white,
so the diffusion target is trivially smooth. The real quality risk is the
bubble border halo, which the existing pipeline already mitigates:
segmentation-mask erosion (r=5, `BUBBLE_SEG_MASK_EROSION`) protects
hand-drawn stroke borders, and the 12px distance-field feather
(`FEATHER_RAMP_PX`) blends the fill into the page. Both are retained.

**Design (per F7 — never page-scale):**

1. Keep `inpaintReportBubbles`' mask construction verbatim (seg-mask
   rasterization + erosion + dynamic pill mask) — only the *fill* changes.
2. Replace the single full-page `fillAndBlend` with **per-component
   crop-scoped NS**: for each connected mask component (or clustered
   component bbox union), crop with context pad (start at 24px, tune via
   workbench), run NS with radius 3 (bubbles ≤256px) / 5 (larger), composite
   back through the existing feather-alpha field.
3. Consult `EngineMemoryBudget` before each crop (mirror the neural gate's
   shape, log `skip_bubble_ns`/`run_bubble_ns` like `run_report_aot`) — the
   OpenCV path currently has no memory gate.
4. Degradation ladder: NS native → Telea (same engine, method flag) →
   `PUSH_PULL_EMERGENCY` (existing `isAvailable` fallback) — and stamp the
   effective backend (F8: a mode whose contract is "OpenCV" must not silently
   be push-pull).
5. Delete `AotReportBubbleFill`'s median-fill role after the visual A/B
   baseline is captured (workbench); keep the file only if the emergency
   ladder needs it.

**Expected behavior change:** bubble interiors become local diffusion of
surrounding art rather than a snapped median — on gradient/halftone/screentone
backgrounds this is an improvement; on pure-white bubbles both are
near-identical. Requires visual confirmation on the corpus (workbench),
including edge cases: tiny bubbles (erosion-collapse fallback at
`AOTInpainting.kt:650-692`), bubbles clipped by page edges, overlapping
bubbles merged into one component.

### 3.2 AOT decoupled from push-pull ("AOT receives its own algorithm")

Today `prepareFixedInput` fills the entire 512² tensor's out-of-crop area with
one flat ring-median color (`AOTInpainting.kt:992-1004`), and the legacy
`inpaint()` does the same for the dynamic fixed-shape branch (`:1295-1306`).
Push-pull also remains the OpenCV-missing emergency.

**Change:** replace the flat ring-median background with **edge replication**
(clamp-to-edge) in `AotPadPath`: pad each row/column by repeating the crop's
border pixels. Rationale: the manga-tuned AOT was trained on real 512² crops
whose surroundings are artwork, not flat color — replicate-padding presents
plausibly continuous context instead of an invented uniform field, at zero
sampling cost (no median pass — removes ~O(crop) histogram work from the hot
path). The mask still marks only the true hole, so the model is free to
repaint the padded band; only the centered source square is decoded back
(`AotFixedTensorContract.decodeOutput` already crops exactly that region).

- Alternatives considered: zero-pad (risks black-frame artifacts bleeding
  into reconstruction); keep ring-median (status quo — retained as a
  workbench A/B arm); page-context crop (prefer *real* surrounding page
  pixels when they fit — attractive but changes crop geometry semantics;
  revisit later).
- `PushPullGradient` shrinks to: (a) emergency fill in
  `OpenCvInpaintEngine.inpaintPixelsWithBackend:120-125`, (b) nothing else.
  Its `localRingMedian` export can be deleted once both call sites are gone.

### 3.3 Free-text (unchanged methods, one decision)

- FAST/BALANCE-classical free-text keeps OpenCV **Telea r=3** (existing
  `inpaintReportFreeTextFast`). Rationale: free-text strokes are thin lines;
  Telea's fast-marching order is well suited to thin masks, and the path is
  battle-tested here. NS for free-text becomes a workbench A/B arm, not a
  code change (the method flag already threads through
  `inpaintPixelsWithBackend`).
- QUALITY free-text neural path stays as built — including the per-group EP
  ladder, memory gate, and Telea fallback.

---

## 4. The three modes — detailed plan

### 4.1 Dispatch table (single source of truth)

Introduce an explicit region-class × mode matrix (fixes F1 — modes must never
alias onto the old QUALITY/FAST pair):

| Region class | FAST | BALANCE | QUALITY |
|---|---|---|---|
| Bubble (label 0) + seg masks | OpenCV NS (crop) | OpenCV NS (crop) | **AOT neural (new)** → NS fallback |
| Bubble-text (overlaps bubble) | treated as bubble content → NS | same | treated as bubble content → AOT |
| Free-text (label 2, no bubble) | OpenCV Telea | **AOT neural** → Telea fallback | AOT neural → Telea fallback |
| Degradation stamp | effective backend | effective backend + degraded flag | same |

Concretely: `InpaintingMode` gains `BALANCE`; `AOTInpainting.inpaintRegions`
takes a per-class decision struct (e.g. `RegionDispatch(bubble: Backend, freeText: Backend)`)
derived from the mode *once*, instead of the mode bool sprinkled through
(`:466-477`). The bubble branch gains its NS and neural variants; the
free-text branch gains its BALANCE case (= today's QUALITY case).

### 4.2 QUALITY bubble-neural path (net-new — the hard part)

Bubble masks routinely exceed 512² (F1/F2). Design:

1. **Per-bubble-group processing:** connected components of the merged
   (seg-mask + pill) bubble mask, clustered when within 64px of each other.
2. **Fit-to-512 tiling:** for each group, take a context crop
   (`centeredReportCrop`-style, pad 32). If the crop fits ≤512² → single AOT
   pass with the existing fixed path. If larger → **tile into overlapping
   448px windows (64px overlap)**, run AOT per tile, blend in overlap bands
   with the feather field. This closes F2's partial-erase class of bugs for
   bubbles by construction, and the same tiler should be retrofitted to the
   free-text path's >512 groups (F2 proper).
3. **Mask semantics:** holes = eroded seg mask + pill mask, exactly as the NS
   path sees them (shared mask builder) — one mask vocabulary across all
   backends.
4. **Fallback:** neural unavailable/OOM/exhausted per group → the §3.1 NS
   path, stamped degraded (F4). Engine-level: QUALITY with no session throws
   unless the fallback pref is on (existing semantics, now covering bubbles
   too).
5. **Verification hooks:** `AotOutputGuard` uniform-output classification and
   `lastAcceptedRoute` reporting apply unchanged.

### 4.3 Mode wiring changes (fixes F3/F4/F5/F11)

| Touchpoint | File | Change |
|---|---|---|
| Enum | `InpaintingMode.kt` | add `BALANCE` |
| Pref default | `TranslationPreferences.kt:116` | keep `"FAST"` default; document values |
| Pref parse | `EngineLane.kt:341-346` | explicit three-way `when` + **unknown → FAST** (never the throwing mode; fixes F11) |
| Fallback pref | `PageInpaintingEngine.kt:23-32` | read live per inpaint, or add to `ensureEnginesBuiltFor` rebuild key (fixes F5); switch applies when mode uses neural (BALANCE + QUALITY) |
| Init gate | `RoiPageRecognitionEngine.kt:376-383` | `mode != FAST` initializes AOT (BALANCE needs free-text neural; fixes F3) |
| QUALITY gate | `PageInpaintingEngine.kt:61-77` | throw-condition = `(BALANCE or QUALITY) && !initialized && !fallback`; downgrade target = FAST semantics per class |
| Stamp | `SinglePageOnnxPhase.kt:866`, `CleanedPublication` | persist *effective* route per page: e.g. `BALANCE_DEGRADED_FREE_TEXT`, `QUALITY_DEGRADED_BUBBLES` — and make resume-reuse + PageDecode fingerprint consume it (fixes F4) |
| Trace span | `RoiPageRecognitionEngine.kt:885-889` | model label from actual session availability/backend mix, not unconditional `AOT_GAN` (F3b) |
| UI | `SettingsTranslationScreen.kt:124-148` | three entries + descriptions; fallback switch enabled for BALANCE/QUALITY |
| Strings | `i18n-at/.../strings.xml` | `pref_inpainting_mode_balance` (+ revise quality/fast descriptions) |

### 4.4 Fallback & failure matrix

| Failure | FAST | BALANCE | QUALITY |
|---|---|---|---|
| OpenCV native missing | bubble+free-text → push-pull emergency, stamped (F8) | bubbles → emergency (stamped); free-text unaffected (neural) | bubbles → emergency only if neural also failed |
| AOT session init failed | n/a (not loaded) | **engine-level:** throw unless fallback pref → free-text → Telea per group, page stamped degraded (same rule as QUALITY) | bubble+free-text → throw unless fallback pref → FAST semantics |
| Per-group neural OOM/exhausted | n/a | Telea for that group (existing ladder terminal) | NS (bubbles) / Telea (free-text), stamped |
| Memory gate trip (neural) | n/a | classical path for that group (existing) | same |
| Cancellation | rethrow `CancellationException` before generic catch (F6 — required once per-group `ensureActive()` lands) | same | same |

### 4.5 Resource & performance expectations

- **FAST** improves vs today's FAST: bubbles drop the 12-pass smoothing loop
  (pure-Kotlin per-pixel) in favor of SIMD NS on small crops; free-text
  unchanged. AOT sessions never initialize → same minimal footprint.
- **BALANCE** ≈ today's QUALITY for free-text-carrying pages (neural passes
  scale with free-text groups), but bubbles cost classical-only — pages that
  are mostly bubbles get *cheaper* than today's QUALITY and *better* than
  today's FAST.
- **QUALITY** is the most expensive: neural per bubble group (tiled when
  >512) + per free-text group. Watch `EngineMemoryBudget.neuralInpaintDecision`
  interplay: session count unchanged (fixed + accelerator + dynamic), but
  sequential inference count per page rises; keep per-group gating to bound
  peak memory, and keep the serialized `nativeGuard` discipline (F12 — the
  shared scratch arrays in `AOTInpainting` remain single-lane by contract).
- **Battery/thermal:** QUALITY on QNN HTP devices amortizes well (burst
  performance mode is already parameterized, `AOTInpainting.kt:33`); on
  CPU-only devices QUALITY will be slow — the UI descriptions should say so.

### 4.6 Test plan (extends the F-list gaps noted in the prior audit)

- Unit: mode parse (`FAST`/`BALANCE`/`QUALITY`/unknown/garbage → FAST),
  init-gate truth table (FAST skips; BALANCE/QUALITY load), dispatch matrix
  per region class (labels 0/1/2, null-label trap F9), per-mode backend
  assertions using the `OpenCvInpaintEngineTest` route-label template.
- Neural-bubble: >512px bubble group → tiled, full erase (regression for F2);
  seg-mask erosion collapse fallback; NS fallback on neural failure; degraded
  stamp content.
- Persistence: resume does not reuse `*_DEGRADED` outputs after model
  recovery; fingerprint sees effective route (F4).
- Cancellation: `CancellationException` rethrow path (F6).
- Visual regression: workbench corpus A/B — {median vs NS} bubbles,
  {ring-median vs replicate} AOT padding, {Telea vs NS} free-text, three-mode
  matrix, on ≥3 device classes (QNN HTP phone, NNAPI phone, CPU/emulator).

---

## 5. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| >512 bubble groups partially erased in QUALITY (F2 generalized) | High | Tiler by construction; regression test with oversized group; fast-path catch-up for any out-of-crop remainder |
| Bubble visual regression (median → NS) | Medium | Workbench A/B before deleting `AotReportBubbleFill`; feather + erosion retained; per-component radius tuning |
| AOT padding change shifts neural output character | Medium | A/B arm in workbench; ship behind the same change so BALANCE/QUALITY both get measured |
| `[0,1]` vs `[−1,1]` fixed-path miscalibration (new flag §1.1) | Medium–High if real | Verify first (§2.2 step 2); correcting it may be the single biggest quality win available |
| Stale QNN context binary after any model re-derivation | Low | Cache invalidation check on sha change; `QnnDiagnostics` parity re-run |
| Page-scale OpenCV memory blowup (F7) | Medium | Per-component crop + memory gate + backend stamping |
| Mode/pref drift across the codebase (`inpaintingModeUsed` consumers) | Medium | Grep sweep of `InpaintingMode.` + stamp consumers (`CleanedPublication`, `BatchResumePlanner`, `PageDecode`) in the same PR |
| Push-pull removal breaks host JVM tests | Low | Emergency path keeps `pushPullFill`; only `localRingMedian` callers change |

---

## 6. Implementation roadmap

**Phase 0 — Baseline & verification (no behavior change).**
Workbench corpus snapshots for today's FAST/QUALITY; verify fixed-path tensor
convention (§1.1); manifest provenance note (§2.2-1).

**Phase 1 — Mode skeleton.** `BALANCE` enum; pref parse + unknown→FAST;
init gate `!= FAST`; settings UI three entries; fallback switch live-read;
trace-span fix. Ships inert (BALANCE ≈ today's QUALITY dispatch is still
behind the flag) but wire-complete. Tests: §4.6 unit block 1.

**Phase 2 — Bubble OpenCV NS path** (FAST + BALANCE). Per-component crop NS
with memory gate + backend stamp; delete median-fill role after A/B signoff.
Tests: route labels, erosion-collapse, edge-clipped bubbles.

**Phase 3 — AOT decoupling.** Replicate-padding in `AotPadPath` /
`prepareFixedInput` / legacy `inpaint()`; retire `localRingMedian` callers.
Workbench A/B. (Optionally: re-derive `aot-512.onnx` if Phase 0 found contract
deviation — §2.2-3.)

**Phase 4 — QUALITY bubble-neural path.** Component clustering + ≤512 tiler
(shared with free-text F2 fix) + neural bubble dispatch + NS fallback +
degraded stamping. Tests: §4.6 neural-bubble block.

**Phase 5 — Persistence honesty.** Effective-route stamping end-to-end
(`SinglePageOnnxPhase`, `CleanedPublication`, resume planner, PageDecode
fingerprint); F4/F5/F8/F11 closures verified by tests.

Each phase is independently shippable and revertible; phases 2–4 each carry a
workbench A/B gate.

---

## 7. Open decisions (need product sign-off)

1. **Model action:** attest-only (recommended) vs re-derive `aot-512.onnx` vs
   pursue a successor model (LaMa-class — separate work package).
2. **Free-text classical method:** keep Telea (recommended) vs switch to NS
   for method uniformity.
3. **Unknown-pref policy:** map to FAST (recommended — never throws) vs keep
   today's else→QUALITY.
4. **QUALITY bubble tiling:** overlapping-tile blend (recommended) vs
   downscale-infer-upscale (cheaper, softer output) — decide on workbench
   evidence; Phase 4 is gated on this sign-off (the §4.2 text currently
   embeds the tiling recommendation).

---

## 8. Sources

- [ogkalu/aot-inpainting — model card](https://huggingface.co/ogkalu/aot-inpainting) (jit-traced zyddnys model, MIT, files incl. `aot.onnx`/`aot_traced.pt`/`inpainting.ckpt`) · [HF API record](https://huggingface.co/api/models/ogkalu/aot-inpainting) (SHA `42ffc84f…`, last modified 2025-10-16)
- [zyddnys/manga-image-translator](https://github.com/zyddnys/manga-image-translator) · [releases — original `inpainting.ckpt` (Apr 2022)](https://github.com/zyddnys/manga-image-translator/releases)
- [researchmm/AOT-GAN-for-Inpainting](https://github.com/researchmm/AOT-GAN-for-Inpainting) — AOT-GAN architecture
- [OpenCV docs — Image Inpainting tutorial](https://docs.opencv.org/4.13.0/df/d3d/tutorial_py_inpainting.html) · [LearnOpenCV — inpainting (NS slightly better + marginally faster)](https://learnopencv.com/image-inpainting-with-opencv-cpp-python) · [Comparative study of OpenCV inpainting algorithms (PDF)](https://computerresearch.org/index.php/computer/article/download/2048/2032/25990) · [OpenCV issue #25404 — Telea rotational symmetry](https://github.com/opencv/opencv/issues/25404)
- Internal: `Plan/inpainting-quality-audit/engine-audit.md` (F1–F12),
  `Plan/inpainting-workbench-design.md`, `ARCHITECTURE_LAYERS.md` (L3/L5).
