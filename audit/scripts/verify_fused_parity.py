#!/usr/bin/env python3
"""
Numerical Equivalence and Parity Verification Script for Manga LaMa Fused PyTorch Model.

Evaluates MangaLaMaFused (FP32 and FP16) against all 8 authoritative real manga reference
output tensors saved at `audit/reference_data/reference_outputs/real_case*_ref_fp32.npy`.

Measures:
- Mean Absolute Error (MAE)
- Root Mean Squared Error (RMSE)
- Peak Signal-to-Noise Ratio (PSNR, dB)
- Structural Similarity Index (SSIM)
- Max Absolute Error (both raw float and normalized to 0-255 pixel scale)
- Pixel error threshold exceedance (% pixels > 1/255, > 2/255, > 5/255)
- Masked hole region vs unmasked boundary regions

Validates against Acceptance Gate:
- Global Mean PSNR >= 45.0 dB
- Global Mean SSIM >= 0.9990
- Max Pixel Error <= 5 / 255
"""

import os
import sys
import json
import time
import numpy as np
from PIL import Image
import torch

# Ensure audit/scripts is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from manga_lama_fused import MangaLaMaFused


def compute_psnr(img1: np.ndarray, img2: np.ndarray, max_val: float = 1.0) -> float:
    mse = np.mean((img1 - img2) ** 2)
    if mse < 1e-15:
        return 100.0
    return float(20.0 * np.log10(max_val / np.sqrt(mse)))


def compute_ssim_channel(img1: np.ndarray, img2: np.ndarray, k1: float = 0.01, k2: float = 0.03, l: float = 1.0) -> float:
    c1 = (k1 * l) ** 2
    c2 = (k2 * l) ** 2
    mu1 = img1.mean()
    mu2 = img2.mean()
    sigma1_sq = ((img1 - mu1) ** 2).mean()
    sigma2_sq = ((img2 - mu2) ** 2).mean()
    sigma12 = ((img1 - mu1) * (img2 - mu2)).mean()
    ssim = ((2.0 * mu1 * mu2 + c1) * (2.0 * sigma12 + c2)) / ((mu1**2 + mu2**2 + c1) * (sigma1_sq + sigma2_sq + c2))
    return float(ssim)


def compute_ssim(img1: np.ndarray, img2: np.ndarray) -> float:
    ssims = [compute_ssim_channel(img1[..., c], img2[..., c]) for c in range(img1.shape[-1])]
    return float(np.mean(ssims))


def evaluate_model(model: torch.nn.Module, cases_meta: list, precision_label: str = "FP32") -> dict:
    results = {}
    all_psnr = []
    all_ssim = []
    all_mae = []
    all_rmse = []
    all_max_ae = []
    all_max_ae_255 = []

    print(f"\n=======================================================")
    print(f"EVALUATING MODEL: MangaLaMaFused ({precision_label})")
    print(f"=======================================================")

    for case in cases_meta:
        cid = case["id"]
        img_p = case["img_path"]
        mask_p = case["mask_path"]
        ref_npy_p = f"audit/reference_data/reference_outputs/{cid}_ref_fp32.npy"

        if not os.path.exists(ref_npy_p):
            raise FileNotFoundError(f"Missing reference output tensor: {ref_npy_p}")

        ref_output = np.load(ref_npy_p)  # (512, 512, 3), float32

        # Load input image and mask
        raw_img = Image.open(img_p).convert("RGB")
        raw_mask = Image.open(mask_p).convert("L")

        img_np = np.array(raw_img, dtype=np.float32) / 255.0
        mask_np = (np.array(raw_mask, dtype=np.float32) > 127.0).astype(np.float32)
        masked_img = img_np * (1.0 - mask_np[..., None])

        # 4-channel input: [1, 4, 512, 512]
        input_4ch = np.concatenate([masked_img, mask_np[..., None]], axis=-1)
        input_tensor = torch.from_numpy(np.transpose(input_4ch, (2, 0, 1))[None, ...])
        if precision_label == "FP16":
            input_tensor = input_tensor.half()
        else:
            input_tensor = input_tensor.float()

        # Inference
        t0 = time.perf_counter()
        with torch.no_grad():
            pred_tensor = model(input_tensor)
        inference_time_ms = (time.perf_counter() - t0) * 1000.0

        pred_np = np.clip(pred_tensor[0].permute(1, 2, 0).float().cpu().numpy(), 0.0, 1.0)

        # Whole image metrics
        diff = np.abs(pred_np - ref_output)
        mae = float(np.mean(diff))
        rmse = float(np.sqrt(np.mean(diff ** 2)))
        max_ae = float(np.max(diff))
        max_ae_255 = max_ae * 255.0
        psnr = compute_psnr(pred_np, ref_output)
        ssim = compute_ssim(pred_np, ref_output)

        # Masked region only metrics (where mask > 0.5)
        mask_bool = mask_np > 0.5
        if np.any(mask_bool):
            diff_mask = diff[mask_bool]
            mae_hole = float(np.mean(diff_mask))
            rmse_hole = float(np.sqrt(np.mean(diff_mask ** 2)))
            max_ae_hole = float(np.max(diff_mask))
            psnr_hole = float(20.0 * np.log10(1.0 / rmse_hole)) if rmse_hole > 1e-15 else 100.0
        else:
            mae_hole, rmse_hole, max_ae_hole, psnr_hole = 0.0, 0.0, 0.0, 100.0

        # Pixel error distribution
        total_pixels = 512 * 512 * 3
        px_gt_1 = float(np.sum(diff > (1.0 / 255.0)) / total_pixels * 100.0)
        px_gt_2 = float(np.sum(diff > (2.0 / 255.0)) / total_pixels * 100.0)
        px_gt_5 = float(np.sum(diff > (5.0 / 255.0)) / total_pixels * 100.0)

        results[cid] = {
            "description": case["description"],
            "mask_coverage_pct": case["mask_coverage_pct"],
            "inference_time_ms": round(inference_time_ms, 2),
            "mae": float(mae),
            "rmse": float(rmse),
            "psnr_db": round(psnr, 2),
            "ssim": round(ssim, 6),
            "max_absolute_error": float(max_ae),
            "max_error_255": round(max_ae_255, 4),
            "masked_hole": {
                "mae": float(mae_hole),
                "rmse": float(rmse_hole),
                "psnr_db": round(psnr_hole, 2),
                "max_error_255": round(max_ae_hole * 255.0, 4)
            },
            "pixel_error_pct": {
                "gt_1_255": round(px_gt_1, 4),
                "gt_2_255": round(px_gt_2, 4),
                "gt_5_255": round(px_gt_5, 4)
            }
        }

        all_psnr.append(psnr)
        all_ssim.append(ssim)
        all_mae.append(mae)
        all_rmse.append(rmse)
        all_max_ae.append(max_ae)
        all_max_ae_255.append(max_ae_255)

        print(f"[{cid}] PSNR: {psnr:.2f} dB | SSIM: {ssim:.6f} | MAE: {mae:.2e} | MaxAE: {max_ae_255:.4f}/255 | Latency: {inference_time_ms:.1f}ms")

    summary = {
        "precision": precision_label,
        "case_count": len(cases_meta),
        "mean_psnr_db": round(float(np.mean(all_psnr)), 2),
        "min_psnr_db": round(float(np.min(all_psnr)), 2),
        "mean_ssim": round(float(np.mean(all_ssim)), 6),
        "min_ssim": round(float(np.min(all_ssim)), 6),
        "mean_mae": float(np.mean(all_mae)),
        "mean_rmse": float(np.mean(all_rmse)),
        "max_error_across_all_cases": float(np.max(all_max_ae)),
        "max_error_255_across_all_cases": round(float(np.max(all_max_ae_255)), 4),
        "acceptance_gate": {
            "target_min_psnr_db": 45.0,
            "target_min_ssim": 0.9990,
            "target_max_error_255": 5.0,
            "psnr_gate_passed": bool(np.mean(all_psnr) >= 45.0),
            "ssim_gate_passed": bool(np.mean(all_ssim) >= 0.9990),
            "max_error_gate_passed": bool(np.max(all_max_ae_255) <= 5.0),
            "overall_passed": bool(
                np.mean(all_psnr) >= 45.0 and
                np.mean(all_ssim) >= 0.9990 and
                np.max(all_max_ae_255) <= 5.0
            )
        },
        "cases": results
    }

    print(f"\n--- {precision_label} Summary ---")
    print(f"  Mean PSNR:    {summary['mean_psnr_db']} dB (Gate >= 45.0 dB): {'PASSED' if summary['acceptance_gate']['psnr_gate_passed'] else 'FAILED'}")
    print(f"  Mean SSIM:    {summary['mean_ssim']} (Gate >= 0.9990): {'PASSED' if summary['acceptance_gate']['ssim_gate_passed'] else 'FAILED'}")
    print(f"  Max Error:    {summary['max_error_255_across_all_cases']}/255 (Gate <= 5/255): {'PASSED' if summary['acceptance_gate']['max_error_gate_passed'] else 'FAILED'}")
    print(f"  Overall Gate: {'ALL PASS' if summary['acceptance_gate']['overall_passed'] else 'FAIL'}")

    return summary


def main():
    print("=== Phase 1A Manga LaMa Fused Parity Verification ===")

    cases_json_p = "audit/reports/real_manga_test_cases.json"
    with open(cases_json_p, "r", encoding="utf-8") as f:
        cases_meta = json.load(f)

    # 1. Evaluate FP32 Model
    fp32_model_path = "audit/models/manga_lama_fused_fp32.pt"
    if not os.path.exists(fp32_model_path):
        raise FileNotFoundError(f"Missing {fp32_model_path}. Run extract_and_fuse_weights.py first.")

    model_fp32 = MangaLaMaFused(num_blocks=18)
    model_fp32.load_state_dict(torch.load(fp32_model_path, map_location="cpu"))
    model_fp32.eval()
    fp32_results = evaluate_model(model_fp32, cases_meta, precision_label="FP32")

    # 2. Evaluate FP16 Model
    fp16_model_path = "audit/models/manga_lama_fused_fp16.pt"
    if not os.path.exists(fp16_model_path):
        raise FileNotFoundError(f"Missing {fp16_model_path}. Run extract_and_fuse_weights.py first.")

    model_fp16 = MangaLaMaFused(num_blocks=18)
    model_fp16.load_state_dict(torch.load(fp16_model_path, map_location="cpu"))
    model_fp16 = model_fp16.half()
    model_fp16.eval()
    fp16_results = evaluate_model(model_fp16, cases_meta, precision_label="FP16")

    # Combine and save results
    report = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "phase": "Phase 1A — PyTorch Graph Surgery & Parity Verification",
        "fp32_evaluation": fp32_results,
        "fp16_evaluation": fp16_results
    }

    out_json_path = "audit/reports/phase1a_parity_results.json"
    os.makedirs(os.path.dirname(out_json_path), exist_ok=True)
    with open(out_json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print(f"\nVerification results saved to {out_json_path}")
    print(f"File verified: exists={os.path.exists(out_json_path)}, size={os.path.getsize(out_json_path)} bytes")


if __name__ == "__main__":
    main()
