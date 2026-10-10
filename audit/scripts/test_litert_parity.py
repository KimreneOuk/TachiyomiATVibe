#!/usr/bin/env python3
"""
LiteRT (Google LiteRT 2.x) Parity Verification and Benchmark Suite for Manga LaMa Fused.

Evaluates `audit/models/manga_lama_fused_fp16.tflite` and `audit/models/manga_lama_fused_fp32.tflite`
against all 8 authoritative real manga reference outputs saved at:
`audit/reference_data/reference_outputs/real_case*_ref_fp32.npy`.

Validates against Acceptance Gate:
- Mean PSNR >= 45.0 dB
- Mean SSIM >= 0.9990
- Max Pixel Error <= 5 / 255
"""

import os
import sys
import json
import time
import numpy as np
from PIL import Image
import ai_edge_litert.interpreter as litert


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


def evaluate_tflite_model(model_path: str, cases_meta: list, precision_label: str = "FP16") -> dict:
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Missing LiteRT model at: {model_path}")

    print(f"\n=======================================================")
    print(f"EVALUATING LITERT MODEL: {os.path.basename(model_path)} ({precision_label})")
    print(f"=======================================================")

    interp = litert.Interpreter(model_path=model_path)
    interp.allocate_tensors()
    input_details = interp.get_input_details()
    output_details = interp.get_output_details()

    in_idx = input_details[0]['index']
    out_idx = output_details[0]['index']
    in_shape = input_details[0]['shape'].tolist()
    out_shape = output_details[0]['shape'].tolist()

    print(f"Input signature:  {input_details[0]['name']} {in_shape} {input_details[0]['dtype']}")
    print(f"Output signature: {output_details[0]['name']} {out_shape} {output_details[0]['dtype']}")

    # Check whether input expects NHWC [1, 512, 512, 4] or NCHW [1, 4, 512, 512]
    is_nhwc = (in_shape == [1, 512, 512, 4])

    results = {}
    all_psnr = []
    all_ssim = []
    all_mae = []
    all_rmse = []
    all_max_ae = []
    all_max_ae_255 = []
    all_latencies = []

    # Warmup
    dummy = np.zeros(in_shape, dtype=np.float32)
    interp.set_tensor(in_idx, dummy)
    for _ in range(2):
        interp.invoke()

    for case in cases_meta:
        cid = case["id"]
        img_p = case["img_path"]
        mask_p = case["mask_path"]
        ref_npy_p = f"audit/reference_data/reference_outputs/{cid}_ref_fp32.npy"

        if not os.path.exists(ref_npy_p):
            raise FileNotFoundError(f"Missing reference output tensor: {ref_npy_p}")

        ref_output = np.load(ref_npy_p)  # (512, 512, 3), float32

        raw_img = Image.open(img_p).convert("RGB")
        raw_mask = Image.open(mask_p).convert("L")

        img_np = np.array(raw_img, dtype=np.float32) / 255.0
        mask_np = (np.array(raw_mask, dtype=np.float32) > 127.0).astype(np.float32)
        masked_img = img_np * (1.0 - mask_np[..., None])

        # 4-channel input: [masked_RGB, mask]
        input_4ch = np.concatenate([masked_img, mask_np[..., None]], axis=-1)  # (512, 512, 4)
        if is_nhwc:
            inp_data = input_4ch[None, ...].astype(np.float32)
        else:
            inp_data = np.transpose(input_4ch, (2, 0, 1))[None, ...].astype(np.float32)

        interp.set_tensor(in_idx, inp_data)

        t0 = time.perf_counter()
        interp.invoke()
        t1 = time.perf_counter()
        lat_ms = (t1 - t0) * 1000.0
        all_latencies.append(lat_ms)

        pred = interp.get_tensor(out_idx)  # shape could be [1, 512, 512, 3] or [1, 3, 512, 512]
        if pred.ndim == 4 and pred.shape[1] == 3 and pred.shape[2] == 512:
            # NCHW -> HWC
            pred_hwc = np.transpose(pred[0], (1, 2, 0))
        elif pred.ndim == 4 and pred.shape[-1] == 3:
            # NHWC -> HWC
            pred_hwc = pred[0]
        else:
            raise ValueError(f"Unexpected prediction shape: {pred.shape}")

        pred_hwc = np.clip(pred_hwc, 0.0, 1.0)

        # Metrics against reference output
        psnr = compute_psnr(pred_hwc, ref_output)
        ssim = compute_ssim(pred_hwc, ref_output)
        abs_diff = np.abs(pred_hwc - ref_output)
        mae = float(np.mean(abs_diff))
        rmse = float(np.sqrt(np.mean(abs_diff ** 2)))
        max_ae = float(np.max(abs_diff))
        max_ae_255 = max_ae * 255.0

        # Mask hole region vs unmasked boundary
        hole_mask = (mask_np > 0.5)
        if np.sum(hole_mask) > 0:
            diff_hole = abs_diff[hole_mask]
            mae_hole = float(np.mean(diff_hole))
            max_ae_hole = float(np.max(diff_hole))
            psnr_hole = compute_psnr(pred_hwc[hole_mask], ref_output[hole_mask])
        else:
            mae_hole, max_ae_hole, psnr_hole = 0.0, 0.0, 100.0

        # Pixel threshold exceedances
        px_gt_1 = float(np.mean(abs_diff > (1.0 / 255.0)) * 100.0)
        px_gt_2 = float(np.mean(abs_diff > (2.0 / 255.0)) * 100.0)
        px_gt_5 = float(np.mean(abs_diff > (5.0 / 255.0)) * 100.0)

        results[cid] = {
            "name": case.get("name", cid),
            "bubble_type": case.get("bubble_type", "unknown"),
            "resolution": [512, 512],
            "mask_ratio_pct": case.get("mask_ratio_pct", 0.0),
            "inference_time_ms": round(lat_ms, 2),
            "metrics": {
                "psnr_db": round(psnr, 2),
                "ssim": round(ssim, 6),
                "mae": mae,
                "rmse": rmse,
                "max_absolute_error": max_ae,
                "max_absolute_error_255": round(max_ae_255, 4)
            },
            "hole_region": {
                "mae": mae_hole,
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

        print(f"[{cid}] PSNR: {psnr:.2f} dB | SSIM: {ssim:.6f} | MAE: {mae:.2e} | MaxAE: {max_ae_255:.4f}/255 | Latency: {lat_ms:.1f}ms")

    summary = {
        "model_file": os.path.basename(model_path),
        "precision": precision_label,
        "input_signature": in_shape,
        "output_signature": out_shape,
        "case_count": len(cases_meta),
        "mean_latency_ms": round(float(np.mean(all_latencies)), 2),
        "mean_psnr_db": round(float(np.mean(all_psnr)), 2),
        "min_psnr_db": round(float(np.min(all_psnr)), 2),
        "mean_ssim": round(float(np.mean(all_ssim)), 6),
        "min_ssim": round(float(np.min(all_ssim)), 6),
        "mean_mae": float(np.mean(all_mae)),
        "mean_rmse": float(np.mean(all_rmse)),
        "max_error_across_all_cases": float(np.max(all_max_ae)),
        "max_error_255_across_all_cases": round(float(np.max(all_max_ae_255)), 4),
        "acceptance_gate": {
            "psnr_passed": bool(np.mean(all_psnr) >= 45.0),
            "ssim_passed": bool(np.mean(all_ssim) >= 0.9990),
            "max_ae_passed": bool(np.max(all_max_ae_255) <= 5.0),
            "overall_passed": bool(
                np.mean(all_psnr) >= 45.0 and
                np.mean(all_ssim) >= 0.9990 and
                np.max(all_max_ae_255) <= 5.0
            )
        },
        "per_case_results": results
    }

    print(f"\n--- Summary for {precision_label} ---")
    print(f"  Mean PSNR:   {summary['mean_psnr_db']:.2f} dB (Gate >= 45.0 dB: {'PASS' if summary['acceptance_gate']['psnr_passed'] else 'FAIL'})")
    print(f"  Mean SSIM:   {summary['mean_ssim']:.6f} (Gate >= 0.9990: {'PASS' if summary['acceptance_gate']['ssim_passed'] else 'FAIL'})")
    print(f"  Max Error:   {summary['max_error_255_across_all_cases']:.4f}/255 (Gate <= 5.0/255: {'PASS' if summary['acceptance_gate']['max_ae_passed'] else 'FAIL'})")
    print(f"  Mean Latency: {summary['mean_latency_ms']:.1f} ms")
    print(f"  Overall Gate: {'ALL PASS' if summary['acceptance_gate']['overall_passed'] else 'FAIL'}")

    return summary


def main():
    print("=== Phase 1B LiteRT Parity Verification Suite ===")

    cases_json_p = "audit/reports/real_manga_test_cases.json"
    with open(cases_json_p, "r", encoding="utf-8") as f:
        cases_meta = json.load(f)

    # 1. Evaluate FP16 Model (Primary Target)
    fp16_model_path = "audit/models/manga_lama_fused_fp16.tflite"
    if not os.path.exists(fp16_model_path):
        fp16_model_path = "app/src/main/assets/models/inpainting/manga_lama_fused_fp16.tflite"

    fp16_results = evaluate_tflite_model(fp16_model_path, cases_meta, precision_label="FP16")

    # 2. Evaluate FP32 Model (Reference, optional)
    fp32_model_path = "audit/models/manga_lama_fused_fp32.tflite"
    fp32_results = evaluate_tflite_model(fp32_model_path, cases_meta, precision_label="FP32") if os.path.exists(fp32_model_path) else {}

    # Save results to json
    report_path = "audit/reports/phase1b_parity_results.json"
    report = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "phase": "Phase 1B — Google LiteRT 2.x Conversion & Parity Verification",
        "fp16_evaluation": fp16_results,
        "fp32_evaluation": fp32_results
    }

    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print(f"\nParity verification results saved to: {report_path}")
    return report


if __name__ == "__main__":
    main()
