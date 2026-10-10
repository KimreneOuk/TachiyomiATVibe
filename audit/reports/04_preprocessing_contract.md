# 04. Preprocessing, Postprocessing & Inference Output Contract

**Document ID:** AUDIT-REP-04  
**Project:** Manga LaMa Google LiteRT Mobile GPU Optimization  
**Date:** October 2026  
**Auditor Status:** ML Inference & Optimization Forensic Engineer  

---

## 1. Inference Input Contracts: Dual-Input vs Single-Input

### A. Original Reference Contract: `mayocream/lama-manga.onnx`
The authoritative model exposes two separate NCHW tensor inputs:

```text
Input 0: 'image'
  Shape:  [batch, 3, 512, 512]  (e.g., [1, 3, 512, 512])
  Dtype:  FLOAT32
  Layout: NCHW (Batch, Channels, Height, Width)
  Color:  RGB (Red = 0, Green = 1, Blue = 2)
  Range:  [0.0, 1.0] float32

Input 1: 'mask'
  Shape:  [batch, 1, 512, 512]  (e.g., [1, 1, 512, 512])
  Dtype:  FLOAT32
  Layout: NCHW (Batch, Channels, Height, Width)
  Values: Binary float: 1.0 = inpaint hole (erase), 0.0 = preserved artwork
```

#### In-Graph Transformation:
The original graph begins with three explicit nodes:
1. `/Sub`: $1.0 - \text{mask}$
2. `/Mul`: $\text{image} \odot (1.0 - \text{mask})$  *(zeros out the hole)*
3. `/Concat`: Concatenates masked image and mask along axis 1 $\to$ `[batch, 4, 512, 512]`.

Because `/Mul` enforces $\text{image} \odot (1.0 - \text{mask})$ internally, passing either the raw image or an already-zeroed masked image produces numerically identical results because $(1 - M)^2 = (1 - M)$ for $M \in \{0, 1\}$.

---

### B. Consolidated Single-Input Contract: `lama-manga_fp16.onnx` & LiteRT Target
The optimized 4-channel contract eliminates the input nodes from the neural graph and accepts a single pre-packed tensor:

```text
Input 0: 'input'
  Shape:  [1, 4, 512, 512]  (Fixed static dimensions)
  Dtype:  FLOAT32 (or FLOAT16 in true FP16 LiteRT)
  Layout: NCHW (in ONNX) / NHWC [1, 512, 512, 4] (in standard LiteRT GPU)
  Channels:
    Channel 0: Red channel (masked: pixel * (1.0 - mask))
    Channel 1: Green channel (masked: pixel * (1.0 - mask))
    Channel 2: Blue channel (masked: pixel * (1.0 - mask))
    Channel 3: Inpainting mask (1.0 = erase, 0.0 = keep)
```

---

## 2. Output Contract & Value Range

```text
Output 0: 'output'
  Shape:  [1, 3, 512, 512]
  Dtype:  FLOAT32 (or FLOAT16)
  Layout: NCHW (ONNX) / NHWC (LiteRT)
  Color:  RGB
  Range:  Strictly bounded to [0.0, 1.0] via graph-terminal Sigmoid operator
```

---

## 3. Postprocessing & Compositing Specification

The neural network outputs a complete $512 \times 512$ RGB prediction. **The network prediction must NEVER simply overwrite the unmasked artwork.**

### The Compositing Rule
Original manga artwork outside the inpainting mask contains high-frequency details (line art, screentones, halftones) that the neural network would slightly blur or resample. Only the masked text region may be replaced.

### Seamless Blending with Distance Field Feathering
To prevent rectangular boundary seams or hard edge cliffs around erased text, the mask must be blended using a feathered alpha ramp:

$$\text{Composited Pixel} = \text{Original}(x, y) \cdot (1.0 - \alpha(x, y)) + \text{Inpainted}(x, y) \cdot \alpha(x, y)$$

Where:
- $\alpha(x, y) = 1.0$ in the core of the text erasure.
- $\alpha(x, y) = 0.0$ outside the erasure.
- $\alpha(x, y) \in (0.0, 1.0)$ across a 8–12 pixel distance-transform feather ramp at the mask perimeter (matching `AOTInpainting.kt:FEATHER_RAMP_PX = 12`).

---

## 4. Windowing, Tiling & Crop Strategy for Full-Page Manga

Manga pages are typically $1080 \times 1920$ to $2048 \times 3072$ pixels. Running a $512 \times 512$ fixed-shape neural model on full pages requires intelligent windowing:

```text
1. Detect Text / Bubbles:
   Bounding boxes: [B_1, B_2, ... B_k] from Text Detector.

2. Cluster Overlapping Regions:
   Group boxes within 64px into unified inpainting ROIs.

3. Calculate Context Window:
   ROI dimensions: W_roi x H_roi.
   Pad ROI by LAMA_CONTEXT_PAD (32 - 64 px) to provide surrounding artwork context.

4. Aspect Ratio & Scale to 512x512:
   If max(W_padded, H_padded) <= 512:
     - Pad with edge-reflection/mirror padding to 512 x 512.
     - (Avoid resizing to preserve original screentone frequency!).
   If max(W_padded, H_padded) > 512:
     - Downsample with bilinear interpolation to 512 x 512, OR
     - Tile into overlapping 512 x 512 windows with 64px overlap blend.

5. Execute Neural Inpainting:
   LiteRT GPU CompiledModel inference at [1, 512, 512, 4].

6. Unpad and Composite:
   Extract ROI, composite with distance-field feathering, and write back to page buffer.
```

---

## 5. Reference Implementation: Python & Android Kotlin

### Python Preprocessing & Packing (`pack_input_4ch`)
```python
import numpy as np
from PIL import Image

def prepare_lama_input(image_pil: Image.Image, mask_pil: Image.Image, size=512):
    # Resize / pad to 512x512
    img = np.array(image_pil.convert("RGB").resize((size, size), Image.Resampling.LANCZOS), dtype=np.float32) / 255.0
    mask = np.array(mask_pil.convert("L").resize((size, size), Image.Resampling.NEAREST), dtype=np.float32)
    
    # Binarize mask: 1.0 = inpaint, 0.0 = keep
    mask_binary = (mask > 127.0).astype(np.float32)
    
    # Zero out masked region
    masked_img = img * (1.0 - mask_binary[..., None])
    
    # Pack into 4-channel tensor: [512, 512, 4]
    packed = np.concatenate([masked_img, mask_binary[..., None]], axis=-1)
    
    # Layout conversion:
    # ONNX expects NCHW: [1, 4, 512, 512]
    onnx_tensor = np.transpose(packed, (2, 0, 1))[None, ...].astype(np.float32)
    
    # LiteRT GPU expects NHWC: [1, 512, 512, 4]
    litert_tensor = packed[None, ...].astype(np.float32)
    
    return onnx_tensor, litert_tensor
```

### Android Kotlin Buffer Packing
```kotlin
fun packInpaintBuffer(
    srcBitmap: Bitmap,
    maskBitmap: Bitmap,
    outFloatBuffer: FloatBuffer, // Capacity: 1 * 512 * 512 * 4
    width: Int = 512,
    height: Int = 512
) {
    outFloatBuffer.rewind()
    val srcPixels = IntArray(width * height)
    val maskPixels = IntArray(width * height)
    srcBitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
    maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

    for (i in 0 until width * height) {
        val src = srcPixels[i]
        val maskVal = (maskPixels[i] and 0xFF) / 255.0f
        val maskBit = if (maskVal > 0.5f) 1.0f else 0.0f
        val keep = 1.0f - maskBit

        val r = ((src shr 16) and 0xFF) / 255.0f * keep
        val g = ((src shr 8) and 0xFF) / 255.0f * keep
        val b = (src and 0xFF) / 255.0f * keep

        // Direct NHWC write for LiteRT GPU buffer
        outFloatBuffer.put(r)
        outFloatBuffer.put(g)
        outFloatBuffer.put(b)
        outFloatBuffer.put(maskBit)
    }
}
```
