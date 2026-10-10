#!/usr/bin/env python3
"""
Comprehensive baseline benchmarking and numerical reference generation for Manga LaMa ONNX.
Measures:
- Session initialization / graph optimization time
- Cold start latency
- Warm inference statistics (median, mean, std, p90, p95, min, max over N iterations)
- Peak RSS memory footprint
- Detailed ONNX Runtime node-level profiling
- Generates and saves numerical reference output tensors (.npy)
- Generates visual side-by-side comparison images (.png)
"""
import argparse
import hashlib
import json
import os
import sys
import time
import numpy as np
from PIL import Image
import onnxruntime as ort

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()

def get_process_memory_mb():
    try:
        import psutil
        return psutil.Process().memory_info().rss / (1024 * 1024)
    except Exception:
        return 0.0

def load_input_tensors(image_path, mask_path, size=512):
    img = Image.open(image_path).convert("RGB").resize((size, size), Image.Resampling.LANCZOS)
    img_np = np.array(img, dtype=np.float32) / 255.0

    mask = Image.open(mask_path).convert("L").resize((size, size), Image.Resampling.NEAREST)
    mask_np = (np.array(mask, dtype=np.float32) > 127.0).astype(np.float32)

    masked_img = img_np * (1.0 - mask_np[..., None])

    img_nchw = np.transpose(img_np, (2, 0, 1))[None, ...].astype(np.float32)
    masked_nchw = np.transpose(masked_img, (2, 0, 1))[None, ...].astype(np.float32)
    mask_nchw = mask_np[None, None, ...].astype(np.float32)

    input_4ch = np.concatenate([masked_img, mask_np[..., None]], axis=-1)
    input_4ch_nchw = np.transpose(input_4ch, (2, 0, 1))[None, ...].astype(np.float32)

    return {
        "raw_img": img,
        "raw_mask": mask,
        "img_nchw": img_nchw,
        "masked_nchw": masked_nchw,
        "mask_nchw": mask_nchw,
        "input_4ch_nchw": input_4ch_nchw
    }

def benchmark_model(model_path, inputs_dict, num_iterations=30, enable_profiling=False, profile_dir="audit/reports"):
    print(f"\n=======================================================", flush=True)
    print(f"BENCHMARKING: {os.path.basename(model_path)}", flush=True)
    print(f"=======================================================", flush=True)

    model_sha = sha256_file(model_path)
    file_size_mb = os.path.getsize(model_path) / (1024 * 1024)

    # Session Options
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    opts.intra_op_num_threads = 4
    opts.inter_op_num_threads = 1

    profile_file = None
    if enable_profiling:
        opts.enable_profiling = True
        opts.profile_file_prefix = os.path.join(profile_dir, f"ort_profile_{os.path.splitext(os.path.basename(model_path))[0]}")

    # Measure Init Time
    mem_before = get_process_memory_mb()
    t_init_start = time.perf_counter()
    sess = ort.InferenceSession(model_path, opts, providers=["CPUExecutionProvider"])
    t_init = time.perf_counter() - t_init_start
    mem_after_init = get_process_memory_mb()

    in_meta = sess.get_inputs()
    out_meta = sess.get_outputs()
    print(f"Model Init Time: {t_init*1000:.2f} ms | Model File Size: {file_size_mb:.2f} MB", flush=True)
    print(f"RSS Memory: Before={mem_before:.1f} MB, After Init={mem_after_init:.1f} MB (Delta: +{mem_after_init-mem_before:.1f} MB)", flush=True)
    print(f"Graph Inputs: {[(i.name, i.shape) for i in in_meta]}", flush=True)

    # Prepare Feed
    if len(in_meta) == 2:
        feed = {in_meta[0].name: inputs_dict["masked_nchw"], in_meta[1].name: inputs_dict["mask_nchw"]}
    else:
        feed = {in_meta[0].name: inputs_dict["input_4ch_nchw"]}

    # Cold Inference
    t_cold_start = time.perf_counter()
    cold_out = sess.run(None, feed)[0]
    t_cold = time.perf_counter() - t_cold_start
    mem_after_cold = get_process_memory_mb()
    print(f"Cold Start Inference Latency: {t_cold*1000:.2f} ms", flush=True)

    # Warm-up (3 iterations)
    for _ in range(3):
        sess.run(None, feed)

    # Timed Iterations
    latencies = []
    for i in range(num_iterations):
        t0 = time.perf_counter()
        last_out = sess.run(None, feed)[0]
        dt = time.perf_counter() - t0
        latencies.append(dt * 1000.0) # ms
        if (i + 1) % 10 == 0:
            print(f"  Iteration {i+1}/{num_iterations}: current={dt*1000:.2f} ms, running mean={np.mean(latencies):.2f} ms", flush=True)

    mem_end = get_process_memory_mb()
    latencies = np.array(latencies)

    if enable_profiling:
        profile_file = sess.end_profiling()
        print(f"ORT Profiling trace saved to: {profile_file}", flush=True)

    stats = {
        "model_name": os.path.basename(model_path),
        "model_path": os.path.abspath(model_path),
        "file_size_mb": round(file_size_mb, 2),
        "sha256": model_sha,
        "init_time_ms": round(t_init * 1000.0, 2),
        "cold_latency_ms": round(t_cold * 1000.0, 2),
        "iterations": num_iterations,
        "mean_latency_ms": round(float(np.mean(latencies)), 2),
        "median_latency_ms": round(float(np.median(latencies)), 2),
        "std_latency_ms": round(float(np.std(latencies)), 2),
        "min_latency_ms": round(float(np.min(latencies)), 2),
        "max_latency_ms": round(float(np.max(latencies)), 2),
        "p90_latency_ms": round(float(np.percentile(latencies, 90)), 2),
        "p95_latency_ms": round(float(np.percentile(latencies, 95)), 2),
        "p99_latency_ms": round(float(np.percentile(latencies, 99)), 2),
        "mem_init_delta_mb": round(mem_after_init - mem_before, 2),
        "peak_rss_mb": round(mem_end, 2),
        "profile_file": profile_file
    }

    print(f"\n--- Benchmark Results: {os.path.basename(model_path)} ---", flush=True)
    print(f"Median: {stats['median_latency_ms']:.2f} ms | Mean: {stats['mean_latency_ms']:.2f} ms | P95: {stats['p95_latency_ms']:.2f} ms | Min: {stats['min_latency_ms']:.2f} ms", flush=True)
    return stats, last_out

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--iters", type=int, default=30, help="Number of warm benchmark iterations")
    parser.add_argument("--profile", action="store_true", help="Enable node-level ORT profiling")
    args = parser.parse_args()

    test_img = "audit/reference_data/test_images/case1_bubble_screentone.png"
    test_mask = "audit/reference_data/test_masks/case1_bubble_screentone_mask.png"
    inp = load_input_tensors(test_img, test_mask)

    models = [
        "audit/models/lama-manga.onnx",
        "audit/models/lama-manga_fp16.onnx",
        "audit/models/lama-manga_int8.onnx",
        "audit/models/lama_512_fp16.onnx"
    ]

    all_stats = []
    outputs = {}

    for m in models:
        stats, out = benchmark_model(m, inp, num_iterations=args.iters, enable_profiling=args.profile)
        all_stats.append(stats)
        outputs[stats["model_name"]] = out

    # Save benchmark results
    out_json = "audit/reports/baseline_benchmark_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(all_stats, f, indent=2)
    print(f"\nSaved all benchmark statistics to {out_json}", flush=True)

    # Save reference output tensor for original model
    ref_tensor_path = "audit/reference_data/reference_outputs/case1_mayocream_fp32_output.npy"
    np.save(ref_tensor_path, outputs["lama-manga.onnx"])
    print(f"Saved reference FP32 tensor to {ref_tensor_path} (shape: {outputs['lama-manga.onnx'].shape})", flush=True)

if __name__ == "__main__":
    main()
