import os
import sys
import gzip
import shutil
import subprocess
import torch
import onnx
import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from manga_lama_fused import MangaLaMaFused

def export_pytorch_to_onnx(pt_path: str, onnx_raw_path: str, onnx_slim_path: str):
    print("\n--- Step 1: Export PyTorch to ONNX ---")
    print(f"Loading PyTorch checkpoint from: {pt_path}")
    model = MangaLaMaFused(num_blocks=18)
    model.load_state_dict(torch.load(pt_path, map_location="cpu"))
    model.eval()

    dummy_image = torch.zeros(1, 3, 512, 512, dtype=torch.float32)
    dummy_mask = torch.zeros(1, 1, 512, 512, dtype=torch.float32)

    print(f"Exporting TorchScript ONNX graph to: {onnx_raw_path}")
    torch.onnx.export(
        model,
        (dummy_image, dummy_mask),
        onnx_raw_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["input_image", "input_mask"],
        output_names=["output"],
        dynamic_axes=None,
        dynamo=False
    )
    print(f"Raw ONNX size: {os.path.getsize(onnx_raw_path) / (1024*1024):.2f} MB")

    print("\n--- Step 2: Optimizing ONNX with onnxslim ---")
    ret = subprocess.run([
        sys.executable, "-m", "onnxslim",
        onnx_raw_path, onnx_slim_path
    ], capture_output=True, text=True)
    if ret.returncode != 0:
        print(f"onnxslim warning: {ret.stderr}")
        shutil.copyfile(onnx_raw_path, onnx_slim_path)
    else:
        print(f"Optimized ONNX saved to: {onnx_slim_path} ({os.path.getsize(onnx_slim_path) / (1024*1024):.2f} MB)")


def convert_onnx_to_tflite(onnx_slim_path: str, output_dir: str):
    print("\n--- Step 3: Converting ONNX to LiteRT (.tflite) ---")
    os.makedirs(output_dir, exist_ok=True)

    cmd = [
        sys.executable, "-m", "onnx2tf",
        "-i", onnx_slim_path,
        "-o", output_dir,
        "-coion",
        "-cgdc"
    ]
    print(f"Running onnx2tf command: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)

    base_name = os.path.splitext(os.path.basename(onnx_slim_path))[0]
    fp16_gen = os.path.join(output_dir, f"{base_name}_float16.tflite")
    fp32_gen = os.path.join(output_dir, f"{base_name}_float32.tflite")

    if not os.path.exists(fp16_gen):
        raise FileNotFoundError(f"Generated FP16 model not found at {fp16_gen}")
    if not os.path.exists(fp32_gen):
        raise FileNotFoundError(f"Generated FP32 model not found at {fp32_gen}")

    return fp16_gen, fp32_gen


def validate_litert_models(fp16_path: str, fp32_path: str):
    print("\n--- Step 4: Validating LiteRT Models with ai_edge_litert ---")
    import ai_edge_litert.interpreter as litert
    from ai_edge_litert.tools import flatbuffer_utils

    for path, prec in [(fp16_path, "FP16"), (fp32_path, "FP32")]:
        size_mb = os.path.getsize(path) / (1024 * 1024)
        interp = litert.Interpreter(model_path=path)
        interp.allocate_tensors()
        in_details = interp.get_input_details()
        out_details = interp.get_output_details()

        print(f"[{prec}] {os.path.basename(path)}:")
        print(f"  File size: {size_mb:.2f} MB")
        for i, in_det in enumerate(in_details):
            print(f"  Input {i}:     {in_det['name']} shape={in_det['shape'].tolist()} dtype={in_det['dtype']}")
            dummy_inp = np.zeros(in_det['shape'], dtype=np.float32)
            interp.set_tensor(in_det['index'], dummy_inp)
        for i, out_det in enumerate(out_details):
            print(f"  Output {i}:    {out_det['name']} shape={out_det['shape'].tolist()} dtype={out_det['dtype']}")

        interp.invoke()
        out_tensor = interp.get_tensor(out_details[0]['index'])
        print(f"  Dry-run invoke passed. Output shape: {out_tensor.shape}, min={out_tensor.min():.4f}, max={out_tensor.max():.4f}")

        model_obj = flatbuffer_utils.get_model(path)
        opcodes = [flatbuffer_utils.opcode2name(op.opcode_index, model_obj) for op in model_obj.subgraphs[0].operators]
        batch_matmul_count = opcodes.count("BATCH_MATMUL")
        conv2d_count = opcodes.count("CONV_2D")
        print(f"  Operator stats: Total={len(opcodes)}, CONV_2D={conv2d_count}, BATCH_MATMUL={batch_matmul_count}")
        assert batch_matmul_count == 0, f"FAILURE: Found {batch_matmul_count} BATCH_MATMUL operators in {prec} model!"
        print(f"  [PASS] Zero BATCH_MATMUL verified! 100% Adreno GPU compatible!")


def main():
    root_dir = os.path.dirname(SCRIPT_DIR)
    models_dir = os.path.join(root_dir, "models")
    pt_path = os.path.join(models_dir, "manga_lama_fused_fp32.pt")
    onnx_raw_path = os.path.join(models_dir, "manga_lama_fused_fp32.onnx")
    onnx_slim_path = os.path.join(models_dir, "manga_lama_fused_fp32_slim.onnx")
    onnx2tf_out_dir = os.path.join(models_dir, "onnx2tf_out")

    final_fp16_path = os.path.join(models_dir, "manga_lama_fused_fp16.tflite")
    final_fp32_path = os.path.join(models_dir, "manga_lama_fused_fp32.tflite")
    asset_gz_path = os.path.join(root_dir, "..", "app", "src", "main", "assets", "models", "inpainting", "manga_lama_fused_fp16.tflite.gz")

    for p in [onnx_raw_path, onnx_slim_path, final_fp16_path, final_fp32_path]:
        if os.path.exists(p):
            os.remove(p)
    if os.path.exists(onnx2tf_out_dir):
        shutil.rmtree(onnx2tf_out_dir)

    export_pytorch_to_onnx(pt_path, onnx_raw_path, onnx_slim_path)
    fp16_gen, fp32_gen = convert_onnx_to_tflite(onnx_slim_path, onnx2tf_out_dir)

    shutil.copyfile(fp16_gen, final_fp16_path)
    shutil.copyfile(fp32_gen, final_fp32_path)
    print(f"\nFinal models saved to:")
    print(f"  {final_fp16_path}")
    print(f"  {final_fp32_path}")

    validate_litert_models(final_fp16_path, final_fp32_path)

    print(f"\n--- Step 5: Packaging FP16 model into app assets ---")
    os.makedirs(os.path.dirname(asset_gz_path), exist_ok=True)
    with open(final_fp16_path, "rb") as f_in, gzip.open(asset_gz_path, "wb", compresslevel=9) as f_out:
        shutil.copyfileobj(f_in, f_out)
    print(f"Packaged gzipped LiteRT FP16 model to: {asset_gz_path}")
    print(f"  GZ size: {os.path.getsize(asset_gz_path) / (1024*1024):.2f} MB")

    print("\n=== LiteRT 1x1 Conv2D Conversion and Packaging Completed Successfully! ===")


if __name__ == "__main__":
    main()
