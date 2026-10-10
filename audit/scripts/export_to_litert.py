#!/usr/bin/env python3
"""
Manga LaMa Fused -> Google LiteRT 2.x (.tflite) Conversion Pipeline.

End-to-end export script:
1. Loads PyTorch MangaLaMaFused model (folded weights, 0 BatchNorm, static FFT).
2. Exports static ONNX graph [1, 4, 512, 512] -> [1, 3, 512, 512].
3. Optimizes graph with onnxslim (constant folding, dead op pruning).
4. Converts to LiteRT flatbuffer via onnx2tf with GPU delegate optimizations.
5. Produces:
   - audit/models/manga_lama_fused_fp16.tflite (~97.6 MB)
   - audit/models/manga_lama_fused_fp32.tflite (~194.7 MB)
6. Validates I/O signatures with LiteRT runtime.
"""

import os
import sys
import shutil
import subprocess
import torch
import onnx
import numpy as np

# Ensure audit/scripts is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from manga_lama_fused import MangaLaMaFused


def export_pytorch_to_onnx(pt_path: str, onnx_raw_path: str, onnx_slim_path: str):
    print(f"\n--- Step 1: Export PyTorch to ONNX ---")
    print(f"Loading PyTorch checkpoint from: {pt_path}")
    model = MangaLaMaFused(num_blocks=18)
    model.load_state_dict(torch.load(pt_path, map_location="cpu"))
    model.eval()

    dummy_input = torch.zeros(1, 4, 512, 512, dtype=torch.float32)

    print(f"Exporting TorchScript ONNX graph to: {onnx_raw_path}")
    torch.onnx.export(
        model,
        dummy_input,
        onnx_raw_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes=None,  # strictly static
        dynamo=False
    )
    print(f"Raw ONNX size: {os.path.getsize(onnx_raw_path) / (1024*1024):.2f} MB")

    print(f"\n--- Step 2: Optimizing ONNX with onnxslim ---")
    ret = subprocess.run([
        sys.executable, "-m", "onnxslim",
        onnx_raw_path, onnx_slim_path
    ], capture_output=True, text=True)
    if ret.returncode != 0:
        print(f"onnxslim warning: {ret.stderr}")
        # fallback to raw if onnxslim fails
        shutil.copyfile(onnx_raw_path, onnx_slim_path)
    else:
        print(f"Optimized ONNX saved to: {onnx_slim_path} ({os.path.getsize(onnx_slim_path) / (1024*1024):.2f} MB)")


def convert_onnx_to_tflite(onnx_slim_path: str, output_dir: str):
    print(f"\n--- Step 3: Converting ONNX to LiteRT (.tflite) ---")
    os.makedirs(output_dir, exist_ok=True)

    cmd = [
        sys.executable, "-m", "onnx2tf",
        "-i", onnx_slim_path,
        "-o", output_dir,
        "-coion",  # copy onnx input output names
        "-cgdc"    # check gpu delegate compatibility
    ]
    print(f"Running onnx2tf command: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)

    # Locate generated float16 and float32 models
    base_name = os.path.splitext(os.path.basename(onnx_slim_path))[0]
    fp16_gen = os.path.join(output_dir, f"{base_name}_float16.tflite")
    fp32_gen = os.path.join(output_dir, f"{base_name}_float32.tflite")

    if not os.path.exists(fp16_gen):
        raise FileNotFoundError(f"Generated FP16 model not found at {fp16_gen}")
    if not os.path.exists(fp32_gen):
        raise FileNotFoundError(f"Generated FP32 model not found at {fp32_gen}")

    return fp16_gen, fp32_gen


def validate_litert_models(fp16_path: str, fp32_path: str):
    print(f"\n--- Step 4: Validating LiteRT Models with ai_edge_litert ---")
    import ai_edge_litert.interpreter as litert

    for path, prec in [(fp16_path, "FP16"), (fp32_path, "FP32")]:
        size_mb = os.path.getsize(path) / (1024 * 1024)
        interp = litert.Interpreter(model_path=path)
        interp.allocate_tensors()
        in_det = interp.get_input_details()[0]
        out_det = interp.get_output_details()[0]

        print(f"[{prec}] {os.path.basename(path)}:")
        print(f"  File size: {size_mb:.2f} MB")
        print(f"  Input:     {in_det['name']} shape={in_det['shape'].tolist()} dtype={in_det['dtype']}")
        print(f"  Output:    {out_det['name']} shape={out_det['shape'].tolist()} dtype={out_det['dtype']}")

        # Dry-run test inference
        dummy_inp = np.zeros(in_det['shape'], dtype=np.float32)
        interp.set_tensor(in_det['index'], dummy_inp)
        interp.invoke()
        out_tensor = interp.get_tensor(out_det['index'])
        print(f"  Dry-run invoke passed. Output shape: {out_tensor.shape}, min={out_tensor.min():.4f}, max={out_tensor.max():.4f}")


def main():
    root_dir = os.path.dirname(SCRIPT_DIR)
    models_dir = os.path.join(root_dir, "models")
    pt_path = os.path.join(models_dir, "manga_lama_fused_fp32.pt")
    onnx_raw_path = os.path.join(models_dir, "manga_lama_fused_fp32.onnx")
    onnx_slim_path = os.path.join(models_dir, "manga_lama_fused_fp32_slim.onnx")
    onnx2tf_out_dir = os.path.join(models_dir, "onnx2tf_out")

    final_fp16_path = os.path.join(models_dir, "manga_lama_fused_fp16.tflite")
    final_fp32_path = os.path.join(models_dir, "manga_lama_fused_fp32.tflite")

    # Step 1 & 2: Export PyTorch to ONNX + Slim
    if not os.path.exists(onnx_slim_path):
        export_pytorch_to_onnx(pt_path, onnx_raw_path, onnx_slim_path)
    else:
        print(f"Optimized ONNX already exists at: {onnx_slim_path}")

    # Step 3: Convert ONNX to TFLite
    base_name = os.path.splitext(os.path.basename(onnx_slim_path))[0]
    fp16_gen = os.path.join(onnx2tf_out_dir, f"{base_name}_float16.tflite")
    fp32_gen = os.path.join(onnx2tf_out_dir, f"{base_name}_float32.tflite")

    if not os.path.exists(fp16_gen) or not os.path.exists(fp32_gen):
        fp16_gen, fp32_gen = convert_onnx_to_tflite(onnx_slim_path, onnx2tf_out_dir)

    # Copy to target canonical paths
    shutil.copyfile(fp16_gen, final_fp16_path)
    shutil.copyfile(fp32_gen, final_fp32_path)
    print(f"\nFinal models deployed to:")
    print(f"  {final_fp16_path}")
    print(f"  {final_fp32_path}")

    # Step 4: Validate
    validate_litert_models(final_fp16_path, final_fp32_path)
    print("\nLiteRT conversion pipeline completed successfully.")


if __name__ == "__main__":
    main()
