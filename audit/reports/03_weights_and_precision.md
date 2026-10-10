# 03. Model Weights, Numerical Precision & Quantization Feasibility

**Document ID:** AUDIT-REP-03  
**Project:** Manga LaMa Google LiteRT Mobile GPU Optimization  
**Date:** October 2026  
**Auditor Status:** ML Inference & Optimization Forensic Engineer  

---

## 1. Parameter Footprint & Memory Analysis

The authoritative Manga LaMa model (`mayocream/lama-manga-onnx`) contains **$50,991,939$ parameters** distributed across **$606$ unique initializers**.

### Memory Footprint by Data Type

| Model Variant | Total Parameters | FP32 Params | FP16 Params | UINT8 Params | Memory Size |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`lama-manga.onnx`** (FP32 Ref) | $50,991,939$ | $50,991,939$ ($100\%$) | $0$ | $0$ | **$194.52\text{ MB}$** |
| **`lama-manga_fp16.onnx`** (Weight FP16) | $50,991,939$ | $1,645,955$ ($3.23\%$) | $49,345,984$ ($96.77\%$) | $0$ | **$100.40\text{ MB}$** |
| **`lama-manga_int8.onnx`** (Weight INT8) | $51,109,065$ | $1,704,518$ ($3.33\%$) | $0$ | $49,404,547$ ($96.67\%$) | **$55.93\text{ MB}$** |
| **`lama_512_fp16.onnx`** (Full FP16 Ref) | $51,592,103$ | $0$ ($0\%$) | $51,592,067$ ($99.99\%$) | $0$ | **$98.40\text{ MB}$** |

---

## 2. Parameter Distribution Across Network Components

Audited directly from the structural breakdown of `audit/models/lama-manga.onnx`:

| Stage / Component | Layer Identifiers | Parameter Count | Weight Footprint (FP32) | % of Total Weights |
| :--- | :--- | :--- | :--- | :--- |
| **Input Subgraph** | `/Sub`, `/Mul`, `/Concat` | $0$ | $0.00\text{ MB}$ | $0.00\%$ |
| **Encoder / Downsample** | `model.0` – `model.4` | $8,364,544$ | $31.91\text{ MB}$ | $16.40\%$ |
| **FFC Bottleneck (18 Blocks)**| `model.5` – `model.22` | **$39,886,848$** | **$152.16\text{ MB}$** | **$78.22\%$** |
| **Decoder / Upsample** | `model.23` – `model.26` | $2,730,688$ | $10.42\text{ MB}$ | $5.36\%$ |
| **Output Head** | `model.27` – `model.28` | $9,859$ | $0.04\text{ MB}$ | $0.02\%$ |
| **TOTAL** | — | **$50,991,939$** | **$194.52\text{ MB}$** | **$100.00\%$** |

### Inside the 18 FFC ResNet Bottleneck Blocks:
Across all 18 blocks ($39.89\text{M}$ parameters):
- **Local Spatial Stream Convolutions (`convl2l`):** $2,654,208$ params ($6.65\%$).
- **Cross Spatial-to-Spectral Convolutions (`convl2g`):** $15,925,248$ params ($39.93\%$).
- **Cross Spectral-to-Spatial Convolutions (`convg2l`):** $15,925,248$ params ($39.93\%$).
- **Spectral Unit Convolutions (`convg2g.conv1`, `conv2`):** $5,308,416$ params ($13.31\%$).
- **BatchNorm & Bias parameters:** $73,728$ params ($0.18\%$).

Notice: Over **$79.8\%$ of the bottleneck weights** reside in the cross-stream projection convolutions (`convl2g` and `convg2l`) connecting the local spatial domain with the global Fourier domain!

---

## 3. Weight Numerical Ranges and Outlier Inspection

Analysis of all $222$ convolutional weight tensors in `lama-manga.onnx`:
- **Weight Range:** Min value across all conv weights is $-0.3842$, Max value is $+0.3691$.
- **Mean & Standard Deviation:** Global mean is $-0.00018$, standard deviation is $0.0215$.
- **NaN / Inf Check:** Exactly $0$ NaNs, $0$ Infinities across all initializers.
- **Dynamic Range Assessment:** The convolution weights are exceptionally well-conditioned and clustered tightly around zero. They reside entirely within $[-0.5, +0.5]$, well within both the FP16 normal range ($[6.1 \times 10^{-5}, 65,504]$) and the 8-bit quantization dynamic range.

---

## 4. The Critical BatchNorm Variance Hazard

### The Forensic Discovery
While the convolution weights are well-behaved, the **BatchNorm running variance parameters contain catastrophic numerical outliers**.

In `lama-manga.onnx`, there are $75$ `BatchNormalization` operators. Auditing their running variance statistics yields:

| Rank | BatchNorm Node Name | Feature Channels | Max Running Variance ($\sigma^2$) | Max Std Dev ($\sigma$) |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `/model/model.5/conv1/bn_l/BatchNormalization` | $128$ | **$675,607.12$** | **$821.95$** |
| **2** | `/model/model.25/BatchNormalization` | $256$ | **$495,670.09$** | **$704.04$** |
| **3** | `/model/model.22/conv1/bn_l/BatchNormalization` | $128$ | **$304,440.75$** | **$551.76$** |
| **4** | `/model/model.11/conv1/bn_l/BatchNormalization` | $128$ | **$190,670.91$** | **$436.66$** |
| **5** | `/model/model.20/conv1/bn_l/BatchNormalization` | $128$ | **$176,865.75$** | **$420.55$** |
| **6** | `/model/model.18/conv1/bn_l/BatchNormalization` | $128$ | **$165,124.38$** | **$406.35$** |
| **7** | `/model/model.8/conv1/bn_l/BatchNormalization` | $128$ | **$158,941.11$** | **$398.67$** |

### Why Naïve FP16 Conversion Destroys Manga LaMa
In IEEE 754 half-precision floating-point (FP16):
$$\text{Max representable finite value} = 65,504.0$$

When any standard ONNX-to-FP16 converter (e.g. `onnxconverter-common.convert_float_to_float16`) converts the model parameters to FP16:
1. Running variance values such as $675,607.12$ exceed $65,504.0$ by more than **$10\times$**.
2. They immediately saturate to **`+Infinity`**.
3. During inference, the BatchNorm operator evaluates:
   $$y = \gamma \frac{x - \mu}{\sqrt{\sigma^2 + \epsilon}} + \beta$$
4. With $\sigma^2 = \infty$, the denominator is $\infty$, resulting in $\frac{x - \mu}{\infty} = 0.0$ (or `NaN` if intermediate terms underflow).
5. All local spatial features collapse to zero, completely destroying the neural reconstruction!

### The Mathematical Solution: Scale/Bias Reparameterization
During inference, BatchNorm is a purely affine linear transformation:
$$y = w_{\text{eff}} \cdot x + b_{\text{eff}}$$
where:
$$w_{\text{eff}} = \frac{\gamma}{\sqrt{\sigma^2 + \epsilon}}, \quad b_{\text{eff}} = \beta - \frac{\gamma \mu}{\sqrt{\sigma^2 + \epsilon}}$$

Because $\sigma^2 \approx 675,607$, $\sqrt{\sigma^2 + \epsilon} \approx 821.95$.
The scale parameter $\gamma$ is typically in $[0.05, 1.5]$.
Therefore, the effective multiplier is:
$$w_{\text{eff}} = \frac{\gamma}{821.95} \approx 6.08 \times 10^{-5} \text{ to } 1.82 \times 10^{-3}$$
This value is **completely representable in FP16** (smallest positive normal FP16 is $6.10 \times 10^{-5}$, denormals down to $5.96 \times 10^{-8}$).

By absorbing the running mean and variance directly into the scale and bias:
1. The new effective running variance is set to **$1.0$**.
2. The new effective running mean is set to **$0.0$**.
3. Or better yet: the affine operation is **folded directly into the preceding convolution weights**:
   $$W_{\text{fused}} = w_{\text{eff}} \cdot W_{\text{conv}}, \quad B_{\text{fused}} = w_{\text{eff}} \cdot B_{\text{conv}} + b_{\text{eff}}$$
4. This completely eliminates both the BatchNorm operator and the FP16 overflow hazard!

---

## 5. Storage Compression vs Computation Precision

### Existing Compressed Exports (`Liiesl`)
- **`lama-manga_fp16.onnx`:**
  - Conv weights are stored as `FLOAT16` ($100.4\text{ MB}$).
  - A `Cast(FLOAT16 -> FLOAT)` node is inserted before every Conv.
  - **Computation remains 100% FP32.**
  - **Verdict:** Halves disk/asset package size, but provides **zero GPU compute acceleration**.
- **`lama-manga_int8.onnx`:**
  - Conv weights are stored as asymmetric `UINT8` ($55.9\text{ MB}$).
  - A `DequantizeLinear` node is inserted before every Conv.
  - **Computation remains 100% FP32.**
  - **Verdict:** Reduces storage to 57 MB, but **slows down inference by ~25% on CPU** due to runtime dynamic dequantization overhead!

### Target LiteRT Precision: Genuine FP16 Computation
- Mobile GPUs (Snapdragon Adreno 6xx/7xx/8xx and MediaTek Mali/Immortalis) achieve **2× peak FP16 compute throughput (TFLOPS)** compared to FP32, while halving register pressure and memory bandwidth.
- To achieve genuine FP16 execution in Google LiteRT:
  1. The graph must NOT contain runtime dequantization/cast nodes.
  2. BatchNorm variances must be pre-folded.
  3. The model must be converted to an FP16 LiteRT flatbuffer and loaded with:
     ```kotlin
     val options = CompiledModel.Options.builder()
         .setAccelerator(Accelerator.GPU)
         .build()
     ```
  4. This provides the highest performance and fidelity configuration for mobile deployment.
