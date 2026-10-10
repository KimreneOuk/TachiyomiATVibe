# 02. Neural Network Architecture & Graph Inventory Forensic Report

**Document ID:** AUDIT-REP-02  
**Project:** Manga LaMa Google LiteRT Mobile GPU Optimization  
**Date:** October 2026  
**Auditor Status:** ML Inference & Optimization Forensic Engineer  

---

## 1. Network Structure Overview

Manga LaMa is based on Samsung Research's **Large Mask Inpainting (LaMa)** architecture (Suvorov et al., WACV 2022), specifically the **Big-LaMa** variant, fine-tuned by dreMaz on 300,000 manga artwork pages.

The network is an asymmetric encoder-decoder architecture with high-capacity residual bottleneck blocks featuring **Fast Fourier Convolutions (FFC)**.

```text
[Input: Image 3x512x512 + Mask 1x512x512]
                     │
         [Input Subgraph: Sub -> Mul -> Concat]
                     │
             [4 x 512 x 512]
                     │
       ┌─────────────▼─────────────┐
       │   ENCODER / DOWNSAMPLING  │  (model.0 to model.4)
       │  3x Downsampling (Stride 2)│
       │  512x512 -> 256x256 ->    │
       │  128x128 -> 64x64         │
       │  Channels: 4 -> 64 ->     │
       │  128 -> 256 -> 512        │
       └─────────────┬─────────────┘
                     │ [512 x 64 x 64]
       ┌─────────────▼─────────────┐
       │   FFC RESNET BOTTLENECK   │  (model.5 to model.22: 18 Blocks)
       │  Each Block: 2x FFC Convs │
       │  ┌──────────────────────┐ │
       │  │ Spatial Branch (25%) │ │  128 Channels (Local 3x3 Convs)
       │  │ Spectral Branch (75%)│ │  384 Channels (2D Fourier Unit)
       │  └──────────────────────┘ │
       └─────────────┬─────────────┘
                     │ [512 x 64 x 64]
       ┌─────────────▼─────────────┐
       │   DECODER / UPSAMPLING    │  (model.23 to model.26)
       │  3x Upsampling (ConvTrans)│
       │  64x64 -> 128x128 ->      │
       │  256x256 -> 512x512       │
       │  Channels: 512 -> 256 ->  │
       │  128 -> 64                │
       └─────────────┬─────────────┘
                     │ [64 x 512 x 512]
       ┌─────────────▼─────────────┐
       │       OUTPUT HEAD         │  (model.27 to model.28)
       │  7x7 Conv -> Sigmoid      │
       └─────────────┬─────────────┘
                     │
          [Output: 3 x 512 x 512] (RGB 0.0 - 1.0)
```

---

## 2. Stage-by-Stage Forensic Breakdown

### Stage 1: Input Preprocessing Subgraph
- **Nodes:** `/Sub`, `/Mul`, `/Concat` (plus associated constant nodes).
- **Function:** Inverts binary mask ($1.0 - \text{mask}$), computes elementwise product $\text{image} \odot (1.0 - \text{mask})$ to zero out masked regions, then concatenates the masked RGB channels with the binary mask along the channel axis.
- **Output:** Tensor shaped `[batch, 4, 512, 512]`.

### Stage 2: Encoder & Downsampling (`model.0` – `model.4`)
- **`model.0`:** Conv2d ($4 \to 64$, kernel $7 \times 7$, stride 1, pad 3) + BatchNorm + ReLU.
- **`model.1`:** Conv2d ($64 \to 128$, kernel $3 \times 3$, stride 2, pad 1) + BatchNorm + ReLU. (Resolution: $512 \to 256$).
- **`model.2`:** Conv2d ($128 \to 256$, kernel $3 \times 3$, stride 2, pad 1) + BatchNorm + ReLU. (Resolution: $256 \to 128$).
- **`model.3`:** Conv2d ($256 \to 512$, kernel $3 \times 3$, stride 2, pad 1) + BatchNorm + ReLU. (Resolution: $128 \to 64$).
- **Feature Resolution at Bottleneck:** Exactly $64 \times 64$ pixels with $512$ feature channels.

### Stage 3: The 18 FFC ResNet Bottleneck Blocks (`model.5` – `model.22`)
- **Block Count:** Exactly 18 identical residual blocks operating at $64 \times 64$ spatial resolution.
- **Parameters:** Each block contains $2,215,936$ parameters ($8.45\text{ MB}$ FP32).
  - Across all 18 blocks: $39,886,848$ parameters (**78.2% of total model weights!**).
- **FFC Internal Architecture:**
  Each block contains two sequential Fast Fourier Convolutions (`conv1` and `conv2`).
  Channels are partitioned into:
  - **Local Spatial Stream ($L$):** $128$ channels ($25\%$).
  - **Global Spectral Stream ($G$):** $384$ channels ($75\%$).
  
  Within each FFC module, four cross-branch paths execute simultaneously:
  1. **$L \to L$ (Spatial Local):** Standard Conv2d ($128 \to 128$, kernel $3 \times 3$, pad 1).
  2. **$L \to G$ (Local to Global):** Conv2d ($128 \to 384$, kernel $3 \times 3$, pad 1).
  3. **$G \to L$ (Global to Local):** Conv2d ($384 \to 128$, kernel $3 \times 3$, pad 1).
  4. **$G \to G$ (Global to Global Spectral Unit):**
     - $1 \times 1$ Conv ($384 \to 192$).
     - **Forward 2D Real-to-Complex FFT:** Maps $64 \times 64$ real spatial features to $64 \times 33$ complex frequency coefficients.
     - **Complex Frequency Convolution:** Two $1 \times 1$ Convs processing real and imaginary components ($192 \text{ complex} \to 192 \text{ complex}$, effectively $384 \times 384$ real matrix operations) + LeakyReLU/ReLU + BatchNorm.
     - **Inverse 2D Complex-to-Real FFT:** Maps $64 \times 33$ complex coefficients back to $64 \times 64$ real spatial features.
     - $1 \times 1$ Conv ($192 \to 384$).

### Stage 4: Decoder & Upsampling (`model.23` – `model.26`)
- **`model.23`:** ConvTranspose2d ($512 \to 256$, kernel $3 \times 3$, stride 2, pad 1, output\_pad 1) + BatchNorm + ReLU. ($64 \to 128$).
- **`model.24`:** ConvTranspose2d ($256 \to 128$, kernel $3 \times 3$, stride 2, pad 1, output\_pad 1) + BatchNorm + ReLU. ($128 \to 256$).
- **`model.25`:** ConvTranspose2d ($128 \to 64$, kernel $3 \times 3$, stride 2, pad 1, output\_pad 1) + BatchNorm + ReLU. ($256 \to 512$).

### Stage 5: Output Head (`model.27` – `model.28`)
- **`model.27`:** Conv2d ($64 \to 3$, kernel $7 \times 7$, stride 1, pad 3).
- **`model.28`:** Sigmoid activation, constraining values strictly to $[0.0, 1.0]$.

---

## 3. Comprehensive Machine-Readable Graph Operator Inventory

Audited directly from `audit/models/lama-manga.onnx` (`ai.onnx: 17`):

| Operator Type | Count | Primary Function in Graph | LiteRT Mobile GPU Compatibility Status |
| :--- | :--- | :--- | :--- |
| **`Constant`** | **7,051** | Trigonometric coefficients, slicing indices, dimensions | Direct (Foldable via offline optimization) |
| **`Concat`** | **1,360** | Feature and complex-number stacking | Directly Compatible (`CONCATENATION`) |
| **`Reshape`** | **1,348** | Tensor rank alterations for frequency matrices | Directly Compatible (`RESHAPE`) |
| **`Slice`** | **1,322** | Indexing real/imaginary parts and frequency axes | Directly Compatible (`STRIDED_SLICE`) |
| **`Shape`** | **1,188** | Dynamic shape queries | Direct (Eliminated with static shapes) |
| **`Cast`** | **782** | Float-Int-Float metadata conversions | Directly Compatible (`CAST`) |
| **`Transpose`** | **638** | Permuting axes for matrix multiplications | Directly Compatible (`TRANSPOSE`) |
| **`Unsqueeze`** | **504** | Dimension expansion for broadcasting | Direct (Foldable to Reshape) |
| **`Div`** | **432** | Fourier normalization scaling ($1/\sqrt{N}$) | Directly Compatible (`DIV` / `MUL`) |
| **`Gather`** | **396** | Dynamic dimension extraction | Direct (Eliminated with static shapes) |
| **`Mul`** | **289** | Mask masking and spectral scaling | Directly Compatible (`MUL`) |
| **`Add`** | **252** | Residual connections and Fourier recombination | Directly Compatible (`ADD`) |
| **`Conv`** | **222** | 2D Spatial & Spectral convolutions | Directly Compatible (`CONV_2D`) |
| **`MatMul`** | **216** | Discrete Fourier Transform matrix multiplication | Directly Compatible (`BATCH_MATMUL` / `FULLY_CONNECTED`) |
| **`Einsum`** | **216** | Complex multidimensional contraction in Fourier Unit | **STRICT BLOCKER: UNSUPPORTED ON LiteRT GPU** |
| **`Range`** | **180** | In-graph dynamic frequency index generation | **STRICT BLOCKER: DYNAMIC; UNSUPPORTED ON GPU** |
| **`Relu`** | **152** | Non-linear activations | Directly Compatible (`RELU`) |
| **`Cos`** | **144** | In-graph cosine basis computation | **BLOCKER: UNSUPPORTED ON LiteRT GPU DELEGATE** |
| **`Sin`** | **144** | In-graph sine basis computation | **BLOCKER: UNSUPPORTED ON LiteRT GPU DELEGATE** |
| **`Sqrt`** | **144** | Normalization factor computation ($\sqrt{64}$) | Foldable (Constant scalar) |
| **`Sub`** | **109** | Mask inversion ($1 - M$) and complex subtraction | Directly Compatible (`SUB`) |
| **`ConstantOfShape`**| **98** | Tensor generation | Foldable |
| **`Pad`** | **98** | Reflection / zero padding | Directly Compatible (`PAD`) |
| **`BatchNormalization`**| **75** | Feature normalization across all layers | Compatible (Must be folded into Conv weights) |
| **`Squeeze`** | **72** | Dimension reduction | Direct (Foldable to Reshape) |
| **`Neg`** | **36** | Complex sign inversion | Directly Compatible (`NEG`) |
| **`ConvTranspose`** | **3** | Decoder feature map upsampling | Directly Compatible (`TRANSPOSE_CONV`) |
| **`Sigmoid`** | **1** | Final dynamic range squashing | Directly Compatible (`LOGISTIC`) |
| **TOTAL NODES** | **17,472** | — | — |

---

## 4. Root Cause Analysis: The 17,472-Node Bloat

Why does `lama-manga.onnx` contain **17,472 nodes**, whereas `lama_512_fp16.onnx` contains only **1,580 nodes** (a 91% difference)?

### Forensic Evidence:
In Carve-Photos' `saicinpainting/training/modules/ffc.py` (commit `5a67a02`), the Fourier transform was reimplemented as follows:

```python
def rfft(x):
    N = x.shape[-1]
    n = torch.arange(N, dtype=torch.float32, device=x.device)
    k = torch.arange(N // 2 + 1, dtype=torch.float32, device=x.device)
    cos_part = torch.cos(-2 * torch.pi * n[:, None] * k / N)
    sin_part = torch.sin(-2 * torch.pi * n[:, None] * k / N)
    real_part = torch.matmul(x, cos_part)
    imag_part = torch.matmul(x, sin_part)
    return (real_part / torch.sqrt(N), imag_part / torch.sqrt(N))
```

When PyTorch exported this function into ONNX with dynamic dimensions:
1. Every single call created runtime `Shape`, `Slice`, `Gather`, `Range`, `Mul`, `Div`, `Cos`, `Sin`, and `Sqrt` nodes.
2. Because this executes in **18 blocks $\times$ 2 FFC convolutions $\times$ forward & inverse 2D transforms**, it generated:
   - $180$ dynamic `Range` nodes
   - $288$ dynamic `Cos` / `Sin` nodes
   - $216$ multidimensional `Einsum` nodes
   - Over **12,000 metadata and slicing nodes** (`Constant`, `Concat`, `Reshape`, `Slice`, `Shape`, `Gather`, `Unsqueeze`)!

### Optimization Conclusion:
Because the input resolution for our Android translation pipeline is fixed at $512 \times 512$, the bottleneck spatial resolution is **guaranteed to be strictly $64 \times 64$**.
Therefore:
1. The Fourier basis matrices are completely deterministic and immutable constants ($64 \times 33$ and $64 \times 64$).
2. All 14,000+ dynamic construction nodes can be eliminated by **precomputing the DFT matrices offline** and baking them into the model as static initializers.
3. This single transformation collapses graph complexity from **17,472 nodes down to ~1,580 nodes** and replaces unsupported `Einsum` and `Cos`/`Sin` operations with standard, GPU-accelerated `BATCH_MATMUL` and `CONV_2D` operations.
