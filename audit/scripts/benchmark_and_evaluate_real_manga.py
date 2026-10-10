#!/usr/bin/env python3
"""
Baseline inference benchmarking and numerical fidelity evaluation on real manga dataset.
Evaluates:
- lama-manga.onnx (FP32 Reference, mayocream)
- lama-manga_fp16.onnx (Weight FP16, Liiesl)
- lama-manga_int8.onnx (Weight UINT8, Liiesl)
- lama_512_fp16.onnx (Full FP16 Reference, g-ronimo)

Measures:
- Cold start & warm execution latencies
- Peak RSS memory footprint
- Numerical fidelity vs FP32 Reference (MAE, MaxAE, RMSE, PSNR, SSIM)
- Masked region metrics & 5px boundary band continuity
- Composited image quality
- Generates side-by-side comparison panels and reference .npy tensors
"""

import glob
import json
import os
import sys
import time
import psutil
import numpy as np
from PIL import Image
import onnxruntime as ort
from scipy.ndimage import binary_dilation, binary_erosion

def get_process_memory_mb():
    try:
        return psutil.Process().memory_info().rss / (1024 * 1024)
    except Exception:
        return 0.0

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
    ssims = [compute_ssim_channel(img1[..., c], img2[..., c]) for c in range(img1.shape[-1])]
    return float(np.mean(ssims))

def get_boundary_mask(mask_binary, radius=4):
    dil = binary_dilation(mask_binary, iterations=radius)
    ero = binary_erosion(mask_binary, iterations=radius)
    boundary = dil & (~ero)
    return boundary

def load_case(img_path, mask_path):
    raw_img = Image.open(img_path).convert("RGB")
    raw_mask = Image.open(mask_path).convert("L")

    img_np = np.array(raw_img, dtype=np.float32) / 255.0
    mask_np = (np.array(raw_mask, dtype=np.float32) > 127.0).astype(np.float32)

    masked_img = img_np * (1.0 - mask_np[..., None])

    img_nchw = np.transpose(img_np, (2, 0, 1))[None, ...].astype(np.float32)
    masked_nchw = np.transpose(masked_img, (2, 0, 1))[None, ...].astype(np.float32)
    mask_nchw = mask_np[None, None, ...].astype(np.float32)
    input_4ch_nchw = np.transpose(np.concatenate([masked_img, mask_np[..., None]], axis=-1), (2, 0, 1))[None, ...].astype(np.float32)

    return {
        "raw_img": raw_img,
        "raw_mask": raw_mask,
        "img_np": img_np,
        "mask_np": mask_np,
        "masked_img": masked_img,
        "img_nchw": img_nchw,
        "masked_nchw": masked_nchw,
        "mask_nchw": mask_nchw,
        "input_4ch_nchw": input_4ch_nchw
    }

def main():
    print("=== Manga LaMa Real Manga Dataset Baseline & Fidelity Benchmark ===")
    os.makedirs("audit/reports", exist_ok=True)
    os.makedirs("audit/reference_data/comparison_images", exist_ok=True)
    os.makedirs("audit/reference_data/reference_outputs", exist_ok=True)

    cases_json_path = "audit/reports/real_manga_test_cases.json"
    with open(cases_json_path, "r", encoding="utf-8") as f:
        cases_meta = json.load(f)

    models_config = {
        "lama-manga_fp32": {
            "path": "audit/models/lama-manga.onnx",
            "type": "dual_input",
            "desc": "FP32 Authoritative Reference (mayocream)"
        },
        "lama-manga_fp16": {
            "path": "audit/models/lama-manga_fp16.onnx",
            "type": "single_input",
            "desc": "Weight FP16 (Liiesl)"
        },
        "lama-manga_int8": {
            "path": "audit/models/lama-manga_int8.onnx",
            "type": "single_input",
            "desc": "Weight UINT8 (Liiesl)"
        },
        "lama_512_fp16": {
            "path": "audit/models/lama_512_fp16.onnx",
            "type": "single_input",
            "desc": "Full FP16 Static Reference (g-ronimo)"
        }
    }

    # Session options
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    opts.intra_op_num_threads = 4
    opts.inter_op_num_threads = 1

    sessions = {}
    init_metrics = {}

    print("\n--- Initializing Model Sessions ---")
    for m_key, m_info in models_config.items():
        mem_before = get_process_memory_mb()
        t0 = time.perf_counter()
        sess = ort.InferenceSession(m_info["path"], opts, providers=["CPUExecutionProvider"])
        t_init = (time.perf_counter() - t0) * 1000.0
        mem_after = get_process_memory_mb()
        sessions[m_key] = sess
        init_metrics[m_key] = {
            "init_time_ms": round(t_init, 2),
            "mem_delta_mb": round(mem_after - mem_before, 2),
            "file_size_mb": round(os.path.getsize(m_info["path"]) / (1024 * 1024), 2)
        }
        print(f"[{m_key}] Init: {t_init:.2f} ms | Mem Delta: +{mem_after - mem_before:.2f} MB | Size: {init_metrics[m_key]['file_size_mb']:.2f} MB")

    # Benchmarking latency across real cases
    case_benchmarks = {}
    quality_evaluations = {}

    for c in cases_meta:
        cid = c["id"]
        print(f"\n=======================================================", flush=True)
        print(f"EVALUATING CASE: {cid} ({c['split'].upper()})", flush=True)
        print(f"Desc: {c['description']}", flush=True)
        print(f"Mask Coverage: {c['mask_coverage_pct']}% ({c['mask_pixels']} px)", flush=True)
        print(f"=======================================================", flush=True)

        case_data = load_case(c["img_path"], c["mask_path"])
        img_np = case_data["img_np"]
        mask_np = case_data["mask_np"]
        boundary_mask = get_boundary_mask(mask_np > 0.5)

        case_outs = {}
        model_latencies = {}

        for m_key, sess in sessions.items():
            inputs = sess.get_inputs()
            if len(inputs) == 2:
                feed = {inputs[0].name: case_data["masked_nchw"], inputs[1].name: case_data["mask_nchw"]}
            else:
                feed = {inputs[0].name: case_data["input_4ch_nchw"]}

            # Cold start inference
            t_cold0 = time.perf_counter()
            cold_out = sess.run(None, feed)[0]
            cold_latency = (time.perf_counter() - t_cold0) * 1000.0

            # 5 warm iterations
            warm_times = []
            last_out = None
            for _ in range(5):
                t_w0 = time.perf_counter()
                last_out = sess.run(None, feed)[0]
                warm_times.append((time.perf_counter() - t_w0) * 1000.0)

            out_rgb = np.clip(np.transpose(last_out[0], (1, 2, 0)), 0.0, 1.0)
            case_outs[m_key] = out_rgb

            model_latencies[m_key] = {
                "cold_latency_ms": round(cold_latency, 2),
                "warm_median_ms": round(float(np.median(warm_times)), 2),
                "warm_mean_ms": round(float(np.mean(warm_times)), 2),
                "warm_std_ms": round(float(np.std(warm_times)), 2),
                "warm_min_ms": round(float(np.min(warm_times)), 2),
                "warm_max_ms": round(float(np.max(warm_times)), 2),
            }
            print(f"  [{m_key}] Cold: {cold_latency:.1f} ms | Warm Median: {np.median(warm_times):.1f} ms", flush=True)

        case_benchmarks[cid] = model_latencies

        # Save reference FP32 tensor (.npy)
        ref_fp32 = case_outs["lama-manga_fp32"]
        ref_npy_path = f"audit/reference_data/reference_outputs/{cid}_ref_fp32.npy"
        np.save(ref_npy_path, ref_fp32)

        # Quality metrics against FP32 Reference
        case_quality = {}
        for cand_key in ["lama-manga_fp16", "lama-manga_int8", "lama_512_fp16"]:
            cand_img = case_outs[cand_key]
            diff = np.abs(cand_img - ref_fp32)

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
                masked_mae, masked_maxae, masked_rmse, masked_psnr = 0.0, 0.0, 0.0, 100.0

            # Boundary band continuity (5px dilation ring)
            if boundary_mask.any():
                b_diff = diff[boundary_mask]
                b_mae = float(np.mean(b_diff))
                b_maxae = float(np.max(b_diff))
                b_rmse = float(np.sqrt(np.mean(b_diff ** 2)))
                b_psnr = compute_psnr(cand_img[boundary_mask], ref_fp32[boundary_mask])
            else:
                b_mae, b_maxae, b_rmse, b_psnr = 0.0, 0.0, 0.0, 100.0

            # Composited image quality
            comp_cand = img_np * (1.0 - mask_np[..., None]) + cand_img * mask_np[..., None]
            comp_ref = img_np * (1.0 - mask_np[..., None]) + ref_fp32 * mask_np[..., None]
            comp_psnr = compute_psnr(comp_cand, comp_ref)
            comp_ssim = compute_ssim(comp_cand, comp_ref)

            case_quality[cand_key] = {
                "global_mae": round(mae, 7),
                "global_maxae": round(maxae, 6),
                "global_maxae_255": round(maxae * 255.0, 2),
                "global_rmse": round(rmse, 7),
                "global_psnr_db": round(psnr, 2),
                "global_ssim": round(ssim, 5),
                "masked_mae": round(masked_mae, 7),
                "masked_maxae": round(masked_maxae, 6),
                "masked_rmse": round(masked_rmse, 7),
                "masked_psnr_db": round(masked_psnr, 2),
                "boundary_mae": round(b_mae, 7),
                "boundary_maxae": round(b_maxae, 6),
                "boundary_rmse": round(b_rmse, 7),
                "boundary_psnr_db": round(b_psnr, 2),
                "composited_psnr_db": round(comp_psnr, 2),
                "composited_ssim": round(comp_ssim, 5),
            }
            print(f"    vs FP32 [{cand_key}]: PSNR={psnr:.2f} dB | SSIM={ssim:.5f} | Masked PSNR={masked_psnr:.2f} dB | Boundary PSNR={b_psnr:.2f} dB", flush=True)

        quality_evaluations[cid] = case_quality

        # Generate side-by-side comparison panel
        # Panels: [Original, Mask, FP32 Ref, FP16, INT8, Diff x10 INT8-FP32]
        h_dim, w_dim = 512, 512
        panel = Image.new("RGB", (w_dim * 6, h_dim))
        panel.paste(case_data["raw_img"], (0, 0))
        panel.paste(case_data["raw_mask"].convert("RGB"), (w_dim, 0))
        panel.paste(Image.fromarray((ref_fp32 * 255.0).astype(np.uint8)), (w_dim * 2, 0))
        panel.paste(Image.fromarray((case_outs["lama-manga_fp16"] * 255.0).astype(np.uint8)), (w_dim * 3, 0))
        panel.paste(Image.fromarray((case_outs["lama-manga_int8"] * 255.0).astype(np.uint8)), (w_dim * 4, 0))

        # Diff map INT8 vs FP32 amplified x10
        diff_vis = np.clip(np.abs(case_outs["lama-manga_int8"] - ref_fp32) * 10.0 * 255.0, 0, 255).astype(np.uint8)
        panel.paste(Image.fromarray(diff_vis), (w_dim * 5, 0))

        panel_out_path = f"audit/reference_data/comparison_images/{cid}_comparison.png"
        panel.save(panel_out_path)
        print(f"  [+] Saved visual comparison panel -> {panel_out_path}", flush=True)

    peak_rss = get_process_memory_mb()
    print(f"\nFinal Process Peak RSS Memory: {peak_rss:.2f} MB")

    # Save benchmark & quality reports
    summary_report = {
        "models": init_metrics,
        "peak_rss_mb": round(peak_rss, 2),
        "case_benchmarks": case_benchmarks,
        "quality_evaluations": quality_evaluations
    }

    report_json_path = "audit/reports/real_manga_baseline_quality.json"
    with open(report_json_path, "w", encoding="utf-8") as f:
        json.dump(summary_report, f, indent=2)
    print(f"\n[+] Baseline benchmark & fidelity data saved to {report_json_path}")

if __name__ == "__main__":
    main()
