#!/usr/bin/env python3
"""
Deep architectural and weight inspector for ONNX models.
Extracts operator distributions, layer hierarchy, Fourier components,
BatchNormalization statistics, and parameter memory footprints.
"""
import argparse
import json
import os
import sys
import numpy as np
import onnx
from onnx import numpy_helper

DTYPE_MAP = {
    1: "FLOAT",
    2: "UINT8",
    3: "INT8",
    4: "UINT16",
    5: "INT16",
    6: "INT32",
    7: "INT64",
    8: "STRING",
    9: "BOOL",
    10: "FLOAT16",
    11: "DOUBLE",
    12: "UINT32",
    13: "UINT64",
    14: "COMPLEX64",
    15: "COMPLEX128",
    16: "BFLOAT16"
}

def get_tensor_shape(proto):
    return [d.dim_value if d.HasField("dim_value") else d.dim_param for d in proto.type.tensor_type.shape.dim]

def get_tensor_dtype(proto):
    elem_type = proto.type.tensor_type.elem_type
    return DTYPE_MAP.get(elem_type, f"UNKNOWN({elem_type})")

def inspect_model(model_path):
    print(f"Loading {model_path}...")
    model = onnx.load(model_path, load_external_data=False)
    graph = model.graph

    # Model metadata
    opset_imports = {op.domain or "ai.onnx": op.version for op in model.opset_import}
    meta = {
        "ir_version": model.ir_version,
        "producer_name": model.producer_name,
        "producer_version": model.producer_version,
        "opset_imports": opset_imports,
        "doc_string": model.doc_string,
        "metadata_props": {p.key: p.value for p in model.metadata_props}
    }

    # Inputs and Outputs
    inputs = []
    for inp in graph.input:
        inputs.append({
            "name": inp.name,
            "shape": get_tensor_shape(inp),
            "dtype": get_tensor_dtype(inp)
        })

    outputs = []
    for out in graph.output:
        outputs.append({
            "name": out.name,
            "shape": get_tensor_shape(out),
            "dtype": get_tensor_dtype(out)
        })

    # Initializers and Parameters
    initializers = {}
    total_params = 0
    param_bytes = 0
    dtype_params = {}
    dtype_bytes = {}

    for init in graph.initializer:
        dtype_name = DTYPE_MAP.get(init.data_type, f"UNKNOWN({init.data_type})")
        dims = list(init.dims)
        count = int(np.prod(dims)) if dims else 1
        # Calculate byte size
        arr = numpy_helper.to_array(init)
        nbytes = arr.nbytes

        total_params += count
        param_bytes += nbytes
        dtype_params[dtype_name] = dtype_params.get(dtype_name, 0) + count
        dtype_bytes[dtype_name] = dtype_bytes.get(dtype_name, 0) + nbytes

        initializers[init.name] = {
            "dims": dims,
            "count": count,
            "dtype": dtype_name,
            "bytes": nbytes,
            "min": float(arr.min()) if arr.size > 0 and np.issubdtype(arr.dtype, np.number) else None,
            "max": float(arr.max()) if arr.size > 0 and np.issubdtype(arr.dtype, np.number) else None,
            "mean": float(arr.mean()) if arr.size > 0 and np.issubdtype(arr.dtype, np.number) else None,
            "std": float(arr.std()) if arr.size > 0 and np.issubdtype(arr.dtype, np.number) else None,
            "has_nan": bool(np.isnan(arr).any()) if arr.size > 0 and np.issubdtype(arr.dtype, np.floating) else False,
            "has_inf": bool(np.isinf(arr).any()) if arr.size > 0 and np.issubdtype(arr.dtype, np.floating) else False,
        }

    # Nodes and Op distribution
    op_counts = {}
    nodes_info = []
    bn_stats = []
    conv_info = []
    dft_info = []
    matmul_info = []
    cast_dequant_info = []

    for i, node in enumerate(graph.node):
        op_counts[node.op_type] = op_counts.get(node.op_type, 0) + 1

        attrs = {}
        for attr in node.attribute:
            if attr.HasField("f"):
                attrs[attr.name] = attr.f
            elif attr.HasField("i"):
                attrs[attr.name] = attr.i
            elif attr.HasField("s"):
                attrs[attr.name] = attr.s.decode("utf-8", errors="replace")
            elif attr.ints:
                attrs[attr.name] = list(attr.ints)
            elif attr.floats:
                attrs[attr.name] = list(attr.floats)

        node_data = {
            "idx": i,
            "name": node.name,
            "op_type": node.op_type,
            "inputs": list(node.input),
            "outputs": list(node.output),
            "attrs": attrs
        }
        nodes_info.append(node_data)

        # Inspect BatchNorm
        if node.op_type == "BatchNormalization":
            # Inputs: X, scale, B, input_mean, input_var
            scale_name = node.input[1] if len(node.input) > 1 else None
            bias_name = node.input[2] if len(node.input) > 2 else None
            mean_name = node.input[3] if len(node.input) > 3 else None
            var_name = node.input[4] if len(node.input) > 4 else None

            var_init = initializers.get(var_name)
            scale_init = initializers.get(scale_name)
            mean_init = initializers.get(mean_name)
            bias_init = initializers.get(bias_name)

            bn_stats.append({
                "node_name": node.name,
                "scale": scale_init,
                "bias": bias_init,
                "mean": mean_init,
                "var": var_init,
                "epsilon": attrs.get("epsilon", 1e-5),
            })

        # Inspect Conv
        elif node.op_type == "Conv":
            w_name = node.input[1] if len(node.input) > 1 else None
            w_init = initializers.get(w_name)
            conv_info.append({
                "node_name": node.name,
                "weight_name": w_name,
                "weight_shape": w_init["dims"] if w_init else None,
                "weight_dtype": w_init["dtype"] if w_init else None,
                "kernel_shape": attrs.get("kernel_shape"),
                "strides": attrs.get("strides"),
                "pads": attrs.get("pads"),
                "group": attrs.get("group", 1),
                "dilations": attrs.get("dilations"),
                "has_bias": len(node.input) > 2
            })

        # Inspect DFT
        elif "DFT" in node.op_type or "FFT" in node.op_type:
            dft_info.append({
                "node_name": node.name,
                "op_type": node.op_type,
                "inputs": list(node.input),
                "outputs": list(node.output),
                "attrs": attrs
            })

        # Inspect MatMul / Gemm
        elif node.op_type in ("MatMul", "Gemm"):
            matmul_info.append({
                "node_name": node.name,
                "op_type": node.op_type,
                "inputs": list(node.input),
                "outputs": list(node.output),
                "attrs": attrs
            })

        # Inspect Cast / DequantizeLinear
        elif node.op_type in ("Cast", "DequantizeLinear", "QuantizeLinear"):
            cast_dequant_info.append({
                "node_name": node.name,
                "op_type": node.op_type,
                "inputs": list(node.input),
                "outputs": list(node.output),
                "attrs": attrs
            })

    # Summary
    summary = {
        "model_path": os.path.abspath(model_path),
        "file_size_bytes": os.path.getsize(model_path),
        "file_size_mb": round(os.path.getsize(model_path) / (1024 * 1024), 2),
        "metadata": meta,
        "inputs": inputs,
        "outputs": outputs,
        "total_nodes": len(graph.node),
        "op_type_counts": dict(sorted(op_counts.items(), key=lambda x: -x[1])),
        "total_initializers": len(graph.initializer),
        "total_params": total_params,
        "total_param_bytes": param_bytes,
        "param_bytes_mb": round(param_bytes / (1024 * 1024), 2),
        "dtype_param_counts": dtype_params,
        "dtype_byte_counts": dtype_bytes,
        "bn_count": len(bn_stats),
        "conv_count": len(conv_info),
        "dft_count": len(dft_info),
        "matmul_count": len(matmul_info),
        "cast_dequant_count": len(cast_dequant_info),
    }

    return {
        "summary": summary,
        "bn_stats": bn_stats,
        "conv_info": conv_info,
        "dft_info": dft_info,
        "matmul_info": matmul_info,
        "cast_dequant_info": cast_dequant_info,
        "initializers": initializers,
        "nodes": nodes_info
    }

def main():
    parser = argparse.ArgumentParser(description="Inspect ONNX model architecture and weights.")
    parser.add_argument("model", help="Path to ONNX model")
    parser.add_argument("--out", "-o", help="Path to output JSON summary", default=None)
    parser.add_argument("--full", action="store_true", help="Dump full node and initializer list into JSON")
    args = parser.parse_args()

    data = inspect_model(args.model)
    summary = data["summary"]

    print("\n=================== MODEL SUMMARY ===================")
    print(f"File: {summary['model_path']}")
    print(f"Size: {summary['file_size_mb']} MB ({summary['file_size_bytes']:,} bytes)")
    print(f"Opset: {summary['metadata']['opset_imports']}")
    print(f"Inputs: {summary['inputs']}")
    print(f"Outputs: {summary['outputs']}")
    print(f"Nodes: {summary['total_nodes']}")
    print(f"Initializers: {summary['total_initializers']}")
    print(f"Parameters: {summary['total_params']:,} ({summary['param_bytes_mb']} MB)")
    print(f"Dtype breakdown: {summary['dtype_param_counts']}")
    print(f"Operator Distribution: {summary['op_type_counts']}")
    print(f"BatchNorms: {summary['bn_count']}, Convs: {summary['conv_count']}, DFTs: {summary['dft_count']}, MatMuls: {summary['matmul_count']}")

    # Print BN variance statistics
    if data["bn_stats"]:
        max_vars = []
        for bn in data["bn_stats"]:
            v = bn["var"]
            if v and v["max"] is not None:
                max_vars.append((v["max"], bn["node_name"], v["dims"]))
        if max_vars:
            max_vars.sort(key=lambda x: -x[0])
            print("\n--- TOP 5 LARGEST BATCHNORM RUNNING VARIANCES ---")
            for mvar, name, dims in max_vars[:5]:
                print(f"  {mvar:,.2f} -> {name} (shape: {dims})")

    if args.out:
        out_data = data if args.full else {
            "summary": summary,
            "bn_stats": data["bn_stats"],
            "conv_info": data["conv_info"],
            "dft_info": data["dft_info"],
            "matmul_info": data["matmul_info"],
            "cast_dequant_info": data["cast_dequant_info"],
        }
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(out_data, f, indent=2)
        print(f"\nSaved analysis to {args.out}")

if __name__ == "__main__":
    main()
