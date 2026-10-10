#!/usr/bin/env python3
"""
Evaluate numerical quality and reconstruction fidelity across all test cases.
Computes:
- MAE, MaxAE, RMSE, PSNR, SSIM
- Masked region metrics
- Boundary band metrics (5px dilation band around mask edge)
- Final composited image metrics
Saves side-by-side PNG comparisons and JSON quality evaluation report.
"""
import glob
import json
import os
import sys
import numpy as np
from PIL import Image
import onnxruntime as ort

def compute_psnr(img1, img2, max_val=1.0):
    mse = np.mean((img1 - img2) ** 2)
    if mse < 1e-12:
        return 100.0
    return float(20.0 * np.log10(max_val / np.sqrt(mse)))

def compute_ssim_channel(img1, img2, k1=0.01, k2=0.03, l=1.0):
    c1 = (k1 * l) ** 2
    c2 = (k2 * l) ** 2
    mu1 = img1.mean()
    mu2 = img2.mean()
    sigma1_sq = ((img1 - mu1) ** 2).mean()
    sigma2_sq = ((img2 - mu2) ** 2).mean()
    sigma12 = ((img1 - mu1) * (img2 - mu2)).mean()
    ssim = ((2 * mu1 * mu2 + c1) * (2 * sigma12 + c2)) / ((mu1**2 + mu2**2 + c1) * (sigma1_sq + sigma2_sq + c2))
    return float(ssim)

def compute_ssim(img1, img2):
    # img: (H, W, C) float 0..1
    ssims = [compute_ssim_channel(img1[..., c], img2[..., c]) for c in range(img1.shape[-1])]
    return float(np.mean(ssims))

def get_boundary_mask(mask_binary, radius=4):
    """5-pixel boundary band around mask perimeter."""
    from scipy.ndimage import binary_dilation, binary_erosion
    dil = binary_dilation(mask_binary, iterations=radius)
    ero = binary_erosion(mask_binary, iterations=radius)
    boundary = dil & (~ero)
    return boundary

def run_evaluation():
    os.makedirs("audit/reports", exist_ok=True)
    os.makedirs("audit/reference_data/comparison_images", exist_ok=True)
    os.makedirs("audit/reference_data/reference_outputs", exist_ok=True)

    img_paths = sorted(glob.glob("audit/reference_data/test_images/*.png"))
    models = {
        "mayocream_fp32": "audit/models/lama-manga.onnx",
        "liiesl_fp16": "audit/models/lama-manga_fp16.onnx",
        "liiesl_int8": "audit/models/lama-manga_int8.onnx",
    }

    sessions = {tag: ort.InferenceSession(path, providers=["CPUExecutionProvider"]) for tag, path in models.items()}

    all_results = {}

    for ipath in img_paths:
        case_name = os.path.splitext(os.path.basename(ipath))[0]
        mpath = f"audit/reference_data/test_masks/{case_name}_mask.png"
        if not os.path.exists(mpath):
            continue

        print(f"\nEvaluating test case: {case_name}...", flush=True)

        raw_img = Image.open(ipath).convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)
        img_np = np.array(raw_img, dtype=np.float32) / 255.0

        raw_mask = Image.open(mpath).convert("L").resize((512, 512), Image.Resampling.NEAREST)
        mask_np = (np.array(raw_mask, dtype=np.float32) > 127.0).astype(np.float32)

        masked_img = img_np * (1.0 - mask_np[..., None])

        img_nchw = np.transpose(img_np, (2, 0, 1))[None, ...].astype(np.float32)
        masked_nchw = np.transpose(masked_img, (2, 0, 1))[None, ...].astype(np.float32)
        mask_nchw = mask_np[None, None, ...].astype(np.float32)
        input_4ch_nchw = np.transpose(np.concatenate([masked_img, mask_np[..., None]], axis=-1), (2, 0, 1))[None, ...].astype(np.float32)

        case_outputs = {}
        for tag, sess in sessions.items():
            inputs = sess.get_inputs()
            if len(inputs) == 2:
                feed = {inputs[0].name: masked_nchw, inputs[1].name: mask_nchw}
            else:
                feed = {inputs[0].name: input_4ch_nchw}
            out = sess.run(None, feed)[0]  # (1, 3, 512, 512)
            out_rgb = np.clip(np.transpose(out[0], (1, 2, 0)), 0.0, 1.0)
            case_outputs[tag] = out_rgb

        # Save reference tensor
        ref_fp32 = case_outputs["mayocream_fp32"]
        np.save(f"audit/reference_data/reference_outputs/{case_name}_ref_fp32.npy", ref_fp32)

        # Composited output: where mask is 0, keep original image; where mask is 1, take inpaint
        boundary_mask = get_boundary_mask(mask_np > 0.5)

        case_metrics = {}
        for candidate in ["liiesl_fp16", "liiesl_int8"]:
            cand_img = case_outputs[candidate]
            diff = np.abs(cand_img - ref_fp32)

            # Global metrics vs FP32 reference
            mae = float(np.mean(diff))
            maxae = float(np.max(diff))
            rmse = float(np.sqrt(np.mean(diff ** 2)))
            psnr = compute_psnr(cand_img, ref_fp32)
            ssim = compute_ssim(cand_img, ref_fp32)

            # Masked region metrics
            mask_bool = mask_np > 0.5
            if mask_bool.any():
                m_diff = diff[mask_bool]
                masked_mae = float(np.mean(m_diff))
                masked_maxae = float(np.max(m_diff))
                masked_rmse = float(np.sqrt(np.mean(m_diff ** 2)))
                masked_psnr = compute_psnr(cand_img[mask_bool], ref_fp32[mask_bool])
            else:
                masked_mae, masked_maxae, masked_rmse, masked_psnr = 0, 0, 0, 100

            # Boundary band metrics
            if boundary_mask.any():
                b_diff = diff[boundary_mask]
                b_mae = float(np.mean(b_diff))
                b_maxae = float(np.max(b_diff))
            else:
                b_mae, b_maxae = 0, 0

            # Composited image
            comp_cand = img_np * (1.0 - mask_np[..., None]) + cand_img * mask_np[..., None]
            comp_ref = img_np * (1.0 - mask_np[..., None]) + ref_fp32 * mask_np[..., None]
            comp_psnr = compute_psnr(comp_cand, comp_ref)
            comp_ssim = compute_ssim(comp_cand, comp_ref)

            case_metrics[candidate] = {
                "global_mae": round(mae, 7),
                "global_maxae": round(maxae, 6),
                "global_maxae_255": round(maxae * 255.0, 2),
                "global_rmse": round(rmse, 7),
                "global_psnr": round(psnr, 2),
                "global_ssim": round(ssim, 5),
                "masked_mae": round(masked_mae, 7),
                "masked_maxae": round(masked_maxae, 6),
                "masked_psnr": round(masked_psnr, 2),
                "boundary_mae": round(b_mae, 7),
                "boundary_maxae": round(b_maxae, 6),
                "composited_psnr": round(comp_psnr, 2),
                "composited_ssim": round(comp_ssim, 5),
            }

            print(f"  [{candidate}] Global PSNR: {psnr:.2f} dB, SSIM: {ssim:.5f}, MaxAE: {maxae*255.0:.2f}/255", flush=True)

        all_results[case_name] = case_metrics

        # Create Visual Side-by-Side Comparison Panel
        # Columns: [Original, Mask, FP32 Inpainted, FP16 Inpainted, INT8 Inpainted, Absolute Diff x10 INT8-FP32]
        h, w = 512, 512
        panel = Image.new("RGB", (w * 6, h))
        panel.paste(raw_img, (0, 0))
        panel.paste(raw_mask.convert("RGB"), (w, 0))
        panel.paste(Image.fromarray((ref_fp32 * 255).astype(np.uint8)), (w * 2, 0))
        panel.paste(Image.fromarray((case_outputs["liiesl_fp16"] * 255).astype(np.uint8)), (w * 3, 0))
        panel.paste(Image.fromarray((case_outputs["liiesl_int8"] * 255).astype(np.uint8)), (w * 4, 0))

        # Diff visualizer (amplified x10)
        diff_vis = np.clip(np.abs(case_outputs["liiesl_int8"] - ref_fp32) * 10.0 * 255.0, 0, 255).astype(np.uint8)
        panel.paste(Image.fromarray(diff_vis), (w * 5, 0))

        panel_path = f"audit/reference_data/comparison_images/{case_name}_comparison.png"
        panel.save(panel_path)
        print(f"  Saved comparison image to {panel_path}", flush=True)

    with open("audit/reports/baseline_quality_evaluation.json", "w", encoding="utf-8") as f:
        json.dump(all_results, f, indent=2)
    print("\nSaved baseline quality evaluation report to audit/reports/baseline_quality_evaluation.json", flush=True)

if __name__ == "__main__":
    run_evaluation()
