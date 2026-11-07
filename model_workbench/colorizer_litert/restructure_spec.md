# sana-manga-coloring-v2 → LiteRT conversion contract (Phase 1)

Model: https://huggingface.co/masterofaudio2077/sana-manga-coloring-v2 ("Painted"). Pinned EMA step 15000 (meta.json), trained at 512px, rectified-flow + Lab color loss (color_w 0.5). No tag system: one learned look token + NULL-look CFG + ink line re-imposition.

Authoritative source files (pinned, downloaded, reviewed clean):
- Repo code: `C:/Users/User/sana_work/download/coloring_repo_v2/model.py` (self-contained: merge_lines/blur included; imports torch/diffusers/kornia only)
- Weights: `coloring_repo_v2/ema_bf16.pt` (bf16, 1.19 GB), `sana_base/transformer/diffusion_pytorch_model.fp16.safetensors`, `sana_base/vae/diffusion_pytorch_model.fp16.safetensors` (same base repo `Efficient-Large-Model/Sana_600M_1024px_diffusers` as v1 — already downloaded)
- Configs: `sana_base/{transformer,vae}/config.json` (DiT hidden 1152, 28 layers; DC-AE f32c32 scaling_factor 0.41407)

## v2 control surface (replaces the v1 tag system)

- `ver` (B,) long: look version, 0 = dusk, 1 = normal (default), NULL = 2 (zero-vector token, the CFG unconditional side).
- CFG guidance 2.0 over [ver, NULL] batch rows (cond row first). CFG 1 = plain conditional.
- `ink`: line re-imposition strength in merge_lines; 1.0 = full black ink (app v1 constant).
- Sampler: Euler flow, 20 steps, shift 3.0, sigma 3.0 (README scales sigma by short side; at our fixed 512 short side it is exactly 3.0).

## Fixed inference shape

ONE static shape for every exported graph: **PORTRAIT 512 wide × 768 tall** (B=2 baked for DiT CFG rows). NCHW height first: pixel tensors are (…,3,768,512), latents (…,32,24,16). This matches the Kotlin engine (INPUT_W=512, INPUT_H=768) and manga ARs; v2 itself is AR-flexible (multiples of 32) but LiteRT graphs stay static, Kotlin letterboxes 2:3.
- Latent grid: 24×16 (384 patches, patch_size 1). bw pixels: (2,3,768,512) in [-1,1].
- Letterboxing to 2:3 and crop-back happen in Kotlin, NOT in the graphs.

## Export 1 — DiT (single Euler step, velocity prediction)

Traceable wrapper module (restructure of `Colorizer.forward`):
```
inputs:  z_t (2,32,24,16) f32 | z_bw (2,32,24,16) f32 | bw_pixels (2,3,768,512) f32 | t (2,) f32 | tokens (2,1,256) f32
outputs: velocity (2,32,24,16) f32
```
Required restructure (traceability + GPU-safety):
1. Compute `f = hint(bw)`; `feat = f.flatten(2).transpose(1,2)` explicitly.
2. **Remove the 28 `register_forward_pre_hook` injections**: clear each block's pre-hooks and call `blocks[i](hidden + proj[i](feat), encoder_hidden_states=tokens, ...)` explicitly in sequence, then `net.norm_out`/`proj_out` as the module does. Preserve exact op order of `SanaTransformer2DModel.forward`.
3. **Look-token lookup leaves the graph**: `tokens (2,1,256)` is an INPUT. The pinned lookup `token(ver.clamp(max=1)) * (ver != NULL)[:,None]` becomes Kotlin-side: the "normal" row of the 2×256 table is dumped to `color_look_normal.f32.bin` (256 f32, 1 KB; dusk row to `color_look_dusk.f32.bin` for future use); the NULL row is a zero vector built in code. DiT graph therefore has ZERO int64 inputs/outputs and a single cross-attention token.
4. Weights: `torch.load(ema_bf16.pt, weights_only=True)` → v2 `Colorizer` (all 28 blocks, no `keep`, no offsets buffer; `token` is `nn.Embedding(2, 256)`) → load_state_dict strict → cast everything to fp32 before tracing.
5. Numerics check in-python before export: wrapper(z_t,z_bw,bw,t,tokens) must match `Colorizer.forward` bit-near (same weights, same inputs, fp32) to max-abs < 1e-4 on velocity.

## Export 2 — VAE encoder

```
inputs: x (2,3,768,512) f32   outputs: z_bw_scaled (2,32,24,16) f32
```
Bake `encode()`: vae.encode(x).latent * 0.41407. (The Kotlin pipeline runs it with B=2 rows = [bw, bw].)

## Export 3 — VAE decoder

```
inputs: z (2,32,24,16) f32    outputs: rgb (2,3,768,512) f32 in [0,1]
```
Bake `decode()`: postprocess(vae.decode(z / 0.41407).sample) → (x/2+0.5).clamp(0,1).

## Precision targets

- fp16 .tflite for all three graphs (GPU delegate primary).
- int8 variants for all three (DiT required; VAE enc/dec if quality holds — else VAE stays fp16-only and NPU/CPU ladder uses fp16 VAE + int8 DiT mix where possible).
- Convert fp32→fp16 via the litert_torch generative fp16 recipe; int8 via PT2E PTQ with calibration batches (static preferred). Record exact converter API + flags used.

## Validation gate (before ANY Kotlin work)

1. Full-pipeline Python reference: v2 `sample(model, vae, bw, ver=1, steps=20, cfg=2.0, shift=3.0, sigma=3.0, ink=1.0, seed=<k>)` on ≥3 grayscale B&W manga pages (512×768 portrait), save PNGs.
2. Same pages through the exported graphs driven by a pure-python Euler loop replicating `sample()` (FlowMatchEulerDiscreteScheduler(shift=3.0), 20 steps, CFG 2.0 via tokens rows [normal, NULL-zero], python look lookup, pinned merge_lines with ink=1.0) — run on CPU interpreter. Save PNGs.
3. Report per-page: max abs pixel diff + PSNR between (1) and (2); visual side-by-side sheets.
4. Dump scheduler JSON for Kotlin tests: the exact 20 timesteps, sigmas, and per-step deltas from `FlowMatchEulerDiscreteScheduler(shift=3.0).set_timesteps(20)` + `step()` math, plus 2 fully-worked numeric examples ((1,32,2,2) toy latents through all 20 steps, seeded generator).
5. Dump `merge_lines` test vectors (v2 model.py merge_lines): one small case (8×12 RGB out + bw, sigma 3.0) at ink=1.0 AND ink=0.7, with exact input/output float arrays + intermediate Lab/blur values, plus the blur kernel definition (k = 2*ceil(3*sigma)+1 = 19, gaussian_blur2d normalized, kornia default reflect padding).
6. RGB↔Lab conversion vectors for the Kotlin merger (kornia rgb_to_lab/lab_to_rgb on 2-3 small patches).

## Deliverables (all under `model_workbench/colorizer_litert/exported/`)

- `dit_fp16.tflite`, `dit_int8.tflite`, `vae_enc_fp16.tflite`, `vae_enc_int8.tflite`, `vae_dec_fp16.tflite`, `vae_dec_int8.tflite` (or recorded skip reason per int8)
- `color_look_normal.f32.bin`, `color_look_dusk.f32.bin` (256 f32 each)
- `scheduler_reference.json`, `merge_lines_reference.json`, `lab_reference.json`
- `validation/` reference PNGs + parity report (UTF-8) + per-graph I/O signature dump (names, shapes, dtypes)
- Report exact latencies on this machine per graph/steps (informational only)

## Output discipline

The Linux converter writes findings to UTF-8 files and prints ASCII-only summaries. Keep conversion scripts under `model_workbench/colorizer_litert/scripts/` and downloaded model inputs under the ignored `download/` directory.
