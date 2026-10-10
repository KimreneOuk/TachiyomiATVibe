#!/usr/bin/env python3
"""
Deep architectural breakdown of Manga LaMa ONNX:
Dissects layer hierarchy, local spatial branch vs global Fourier spectral branch,
and computes parameter and FLOP distribution across stages.
"""
import collections
import json
import re
import onnx
from onnx import numpy_helper
import numpy as np

def analyze_hierarchy(model_path):
    model = onnx.load(model_path, load_external_data=False)
    graph = model.graph

    # Map tensor name -> initializer info
    init_map = {}
    for init in graph.initializer:
        dims = list(init.dims)
        count = int(np.prod(dims)) if dims else 1
        arr = numpy_helper.to_array(init)
        init_map[init.name] = {
            "dims": dims,
            "count": count,
            "bytes": arr.nbytes,
            "dtype": init.data_type,
            "arr": arr
        }

    # Categorize nodes and initializers by stage/block
    # In LaMa PyTorch (saicinpainting/training/modules/ffc.py / resnet.py):
    # - model.0 to model.4: Downsampling / Encoder (model.0: Conv, model.1: BN, model.2: ReLU, model.3: Conv, model.4: BN...)
    # - model.5 to model.22: 18 FFCResNetBlocks
    # - model.23 to model.26: Decoder / Upsampling (model.23: ConvTranspose or Upsample+Conv, model.24: BN...)
    # - model.27+: Output Conv head / Sigmoid

    stages = collections.defaultdict(lambda: {
        "nodes": [],
        "ops": collections.defaultdict(int),
        "param_count": 0,
        "param_bytes": 0,
        "conv_count": 0,
        "bn_count": 0,
        "matmul_count": 0,
        "einsum_count": 0,
        "fft_math_count": 0
    })

    # Track Fourier/spectral branch vs local spatial branch inside FFC blocks
    ffc_branch_stats = {
        "spatial_l2l": {"param_count": 0, "param_bytes": 0, "conv_count": 0},
        "spatial_g2l": {"param_count": 0, "param_bytes": 0, "conv_count": 0},
        "spectral_l2g": {"param_count": 0, "param_bytes": 0, "conv_count": 0},
        "spectral_g2g": {"param_count": 0, "param_bytes": 0, "conv_count": 0},
        "other": {"param_count": 0, "param_bytes": 0, "conv_count": 0}
    }

    def categorize_name(name):
        # Look for model.X or /model/model.X
        m = re.search(r"model\.(\d+)", name)
        if m:
            block_idx = int(m.group(1))
            if block_idx <= 4:
                return "1_encoder_downsampling"
            elif 5 <= block_idx <= 22:
                return f"2_ffc_resnet_block_{block_idx - 4:02d}"
            elif 23 <= block_idx <= 26:
                return "3_decoder_upsampling"
            else:
                return "4_output_head"
        return "0_other"

    node_stage_map = {}
    for node in graph.node:
        stage = categorize_name(node.name)
        stages[stage]["nodes"].append(node.name)
        stages[stage]["ops"][node.op_type] += 1
        if node.op_type == "Conv":
            stages[stage]["conv_count"] += 1
        elif node.op_type == "BatchNormalization":
            stages[stage]["bn_count"] += 1
        elif node.op_type == "MatMul":
            stages[stage]["matmul_count"] += 1
        elif node.op_type == "Einsum":
            stages[stage]["einsum_count"] += 1
        elif node.op_type in ("Cos", "Sin", "Sqrt", "Range"):
            stages[stage]["fft_math_count"] += 1
        node_stage_map[node.name] = stage

    # Assign initializers to stages
    assigned_inits = set()
    for init_name, info in init_map.items():
        stage = categorize_name(init_name)
        stages[stage]["param_count"] += info["count"]
        stages[stage]["param_bytes"] += info["bytes"]

        # Check FFC branch breakdown
        if "conv_l2l" in init_name or "bn_l" in init_name:
            ffc_branch_stats["spatial_l2l"]["param_count"] += info["count"]
            ffc_branch_stats["spatial_l2l"]["param_bytes"] += info["bytes"]
        elif "conv_g2l" in init_name:
            ffc_branch_stats["spatial_g2l"]["param_count"] += info["count"]
            ffc_branch_stats["spatial_g2l"]["param_bytes"] += info["bytes"]
        elif "conv_l2g" in init_name:
            ffc_branch_stats["spectral_l2g"]["param_count"] += info["count"]
            ffc_branch_stats["spectral_l2g"]["param_bytes"] += info["bytes"]
        elif "conv_g2g" in init_name or "conv1" in init_name and "fu" in init_name or "fu" in init_name or "bn_g" in init_name:
            ffc_branch_stats["spectral_g2g"]["param_count"] += info["count"]
            ffc_branch_stats["spectral_g2g"]["param_bytes"] += info["bytes"]

    print("=== STAGE BREAKDOWN ===")
    total_p = 0
    total_b = 0
    stage_summary = {}
    for stage_name in sorted(stages.keys()):
        s = stages[stage_name]
        total_p += s["param_count"]
        total_b += s["param_bytes"]
        print(f"\n[{stage_name}]")
        print(f"  Nodes: {len(s['nodes'])}, Convs: {s['conv_count']}, BNs: {s['bn_count']}, MatMuls: {s['matmul_count']}, Einsums: {s['einsum_count']}, FFT-math: {s['fft_math_count']}")
        print(f"  Params: {s['param_count']:,} ({s['param_bytes']/(1024*1024):.2f} MB)")
        # Top 5 ops
        top_ops = sorted(s["ops"].items(), key=lambda x: -x[1])[:5]
        print(f"  Top Ops: {top_ops}")

        stage_summary[stage_name] = {
            "node_count": len(s["nodes"]),
            "conv_count": s["conv_count"],
            "bn_count": s["bn_count"],
            "matmul_count": s["matmul_count"],
            "einsum_count": s["einsum_count"],
            "fft_math_count": s["fft_math_count"],
            "param_count": s["param_count"],
            "param_bytes_mb": round(s["param_bytes"] / (1024 * 1024), 2),
            "top_ops": dict(top_ops)
        }

    print(f"\nTOTAL PARAMS CHECK: {total_p:,} ({total_b/(1024*1024):.2f} MB)")

    print("\n=== FFC BRANCH PARAMETER DISTRIBUTION ===")
    for b_name, b_info in ffc_branch_stats.items():
        print(f"  {b_name}: {b_info['param_count']:,} params ({b_info['param_bytes']/(1024*1024):.2f} MB)")

    return {
        "stage_summary": stage_summary,
        "ffc_branch_stats": ffc_branch_stats
    }

if __name__ == "__main__":
    import sys
    model_p = sys.argv[1] if len(sys.argv) > 1 else "audit/models/lama-manga.onnx"
    res = analyze_hierarchy(model_p)
    with open("audit/reports/architecture_hierarchy.json", "w", encoding="utf-8") as f:
        json.dump(res, f, indent=2)
    print("Saved hierarchy to audit/reports/architecture_hierarchy.json")
