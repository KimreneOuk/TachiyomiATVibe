# LaMa Manga Neural Inpainting Integration Design Spec

**Date:** 2026-10-10  
**Status:** Approved  
**Epic:** `3532f3ad-be1b-4eae-835d-eac4d3b16478`  
**Topic:** LaMa Manga (`Liiesl/lama-manga-onnx-quant`) integration as selectable Quality/Balance neural inpainting engine alongside AOT-GAN.

---

## 1. Context & Motivation

TachiyomiAT's neural inpainting currently relies on AOT-GAN (`aot-512.onnx`). While lightweight (~22.8 MB), AOT-GAN models in the manga space frequently suffer from severe artifact generation when confronted with varied artistic styles or larger bubble geometries, occasionally hallucinating facial features or distorted textures. Furthermore, fixed-shape adaptations historically conflicted with Qualcomm AI Hub tensor scaling assumptions.

**LaMa (Large Mask Inpainting)** uses fast Fourier convolutions (FFCs) with expansive receptive fields, vastly improving background texture completion, screen-tone preservation, and complex stroke continuation without facial hallucinations. The newly available quantized export [`Liiesl/lama-manga-onnx-quant`](https://huggingface.co/Liiesl/lama-manga-onnx-quant) (`lama-manga_int8.onnx`, 59.8 MB) provides single-input FP32 compute with INT8 weights, making high-fidelity on-device manga inpainting viable on Android.

This specification details how LaMa Manga is introduced as the default/recommended neural engine while preserving AOT-GAN as an alternate option under the three-mode (`Fast` / `Balance` / `Quality`) redesign.

---

## 2. Architecture & Settings

### 2.1 Mode & Preference Hierarchy

The top-level operational modes established in the three-mode redesign remain unchanged:
* **FAST:** Pure classical OpenCV NS (bubbles) + Telea (free text). Zero neural inference.
* **BALANCE:** OpenCV NS (bubbles) + Active Neural Engine (free text).
* **QUALITY:** Active Neural Engine (bubbles + free text, with 448px overlapping tiling on bubbles).

A new sub-preference governs the active neural engine:

```kotlin
// Preference key in TranslationPreferences
fun translationInpaintingNeuralModel(): Preference<NeuralInpaintModel>
```

* **Enum Values:**
  * `LAMA_MANGA` ("LaMa Manga (Recommended)") — Default
  * `AOT_GAN` ("AOT-GAN (Legacy)")
* **UI Location:**
  * **Settings → Translation → Inpainting mode group:** Displayed as a dropdown/list preference when mode is `BALANCE` or `QUALITY`.
  * **Reader Translation Settings Sheet:** Displayed when mode is `BALANCE` or `QUALITY`.

### 2.2 Model Distribution & Manifest

The model asset is tracked and verified via `scripts/models.manifest`:
* **Path:** `app/src/main/assets/models/inpainting/lama-manga.onnx`
* **Source URL:** `https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/51d07e18caf9b1258585d3706fec8986ccbb8cfb/lama-manga_int8.onnx`
* **Size:** `59,809,275` bytes (~59.8 MB)
* **SHA-256:** `502ce98fbd8d030501040d4505daea0616003266f4eb82b1d27fd4fca5b55f4a`
* **License:** Apache-2.0
* **Storage Deployment:** Handled by `OnnxModelStore.ensureModels()`, copying to `<noBackupFilesDir>/tachiyomiat-models/lama-manga.onnx`.

---

## 3. Tensor Contracts & Abstraction

### 3.1 Common Abstraction: `NeuralInpaintModelContract`

Both models operate on fixed `512x512` crops, but their input tensor layouts, channel counts, and value ranges differ. We introduce an explicit tensor contract strategy:

```kotlin
internal sealed interface NeuralInpaintModelContract {
    val modelId: String
    val isSingleTensor: Boolean

    fun writeInputs(
        paddedImage: IntArray,
        paddedMask: IntArray,
        sourceSize: Int,
        imageBuffer: FloatBuffer,
        maskBuffer: FloatBuffer,
    ): Int

    fun decodeOutput(
        output: FloatBuffer,
        outputShape: LongArray,
        sourceSize: Int,
        offset: Int,
        grayscale: Boolean,
    ): IntArray
}
```

### 3.2 LaMa Manga Contract (`LamaMangaTensorContract`)
* **Input Tensor:** 1 tensor named `input` shaped `[1, 4, 512, 512]` FLOAT32.
  * Channels 0–2: RGB normalized to `[0.0, 1.0]`. Masked pixels are zeroed: `img * (1.0 - mask)`.
  * Channel 3: Binary mask (`1.0` for inpaint region, `0.0` for context).
* **Buffer Layout:** Planar NCHW in a single buffer of `4 * 512 * 512` floats.
* **Output Tensor:** 1 tensor named `output` shaped `[1, 3, 512, 512]` FLOAT32.
  * Channels 0–2: Inpainted RGB in `[0.0, 1.0]`.
* **Output Decoding:** Pixel values decoded as `round(val * 255.0f).coerceIn(0, 255)`.

### 3.3 AOT-GAN Contract (`AotFixedTensorContract`)
* **Input Tensors:** 2 tensors: `image` `[1, 3, 512, 512]` and `mask` `[1, 1, 512, 512]`.
* Retains current fixed-512 input/output routines, unified behind `NeuralInpaintModelContract`.

---

## 4. Execution Pipeline & Engine Integration

### 4.1 Session Management in `NeuralInpaintingEngine`

`AOTInpainting` is refactored to serve as the unified `NeuralInpaintingEngine`:
1. **Model Session Resolution:**
   * Reads `translationInpaintingNeuralModel` preference.
   * If `LAMA_MANGA`: Loads `lama-manga.onnx`. Uses CPU/XNNPACK (and probes NNAPI/QNN if compatible).
   * If `AOT_GAN`: Loads `aot-512.onnx`. Uses existing EP ladder (QNN HTP -> QNN GPU -> NNAPI -> CPU -> dynamic).
2. **Engine Lane Cache Invalidation:**
   * `EngineLane.shouldRebuildRecognition` incorporates changes to `translationInpaintingNeuralModel` alongside mode changes so switching the neural model reloads sessions cleanly.

### 4.2 Tiling & Compositing Integration
* **Crop Bounds & Tiling:** Oversized bubbles (>512px) continue using `AotBoxGeometry.tileBounds` (448px tiles with 64px overlap).
* **Padding:** `AotPadPath.padSquareReplicateInto` centers crops within 512x512 with edge-replication for context.
* **Blending:** Consecutive tiles blend into the page bitmap with `REPORT_FREE_TEXT_FEATHER` (3px ramp) for seamless overlap joints.

---

## 5. Persistence, Degradation & Fingerprints

### 5.1 Route Stamping
Stored stamps reflect both the mode and the neural engine used:
* `QUALITY:LAMA`, `QUALITY:AOT`
* `BALANCE:LAMA`, `BALANCE:AOT`
* `QUALITY:LAMA_DEGRADED`, etc.
* `FAST` (classical only)

### 5.2 Resume Rule (`InpaintStampDecision`)
* `InpaintStampDecision` includes both `desiredMode` and `desiredNeuralModel`.
* If a page was stamped `*_DEGRADED`, it upgrades on resume only when neural sessions are positively healthy.
* If a user switches from `AOT` to `LAMA` (or vice versa), `stampNeedsReinpaint` evaluates to `true`, re-rendering the page with the newly selected neural engine.

---

## 6. Verification & Test Plan

1. **Unit Tests:**
   * `LamaMangaTensorContractTest`: Packing verification (channels 0-2 zeroed under mask; channel 3 contains mask; correct offset and decode).
   * `NeuralModelSwitchingTest`: Verifying `EngineLane` rebuilds recognition engine when switching between LaMa and AOT.
   * `InpaintStampDecisionTest`: Verifying stamp mismatch triggers re-inpaint when changing neural model preference.
2. **Asset Integrity Test:**
   * Manifest validation script verifying `scripts/models.manifest` hashes and size for `lama-manga.onnx`.
3. **Coexistence & Regression Pass:**
   * Run existing inpainting test suite ensuring zero regressions for classical FAST and standard coexistence lanes.
