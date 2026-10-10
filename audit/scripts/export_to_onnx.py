#!/usr/bin/env python3
"""
Export Manga LaMa Fused PyTorch model to ONNX.
Produces static input/output shapes [1, 4, 512, 512] -> [1, 3, 512, 512].
"""

import os
import sys
import torch
import onnx
import onnxruntime as ort
import numpy as np

# Ensure audit/scripts is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from manga_lama_fused import MangaLaMaFused


def export_model():
    models_dir = os.path.join(os.path.dirname(SCRIPT_DIR), "models")
    pt_path = os.path.join(models_dir, "manga_lama_fused_fp32.pt")
    onnx_path = os.path.join(models_dir, "manga_lama_fused_fp32.onnx")

    print(f"Loading PyTorch model from {pt_path}...")
    model = MangaLaMaFused(num_blocks=18)
    model.load_state_dict(torch.load(pt_path, map_location="cpu"))
    model.eval()

    dummy_input = torch.zeros(1, 4, 512, 512, dtype=torch.float32)

    print(f"Exporting to ONNX at {onnx_path}...")
    torch.onnx.export(
        model,
        dummy_input,
        onnx_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes=None,  # strictly static
        dynamo=False
    )

    print(f"ONNX export completed. File size: {os.path.getsize(onnx_path) / (1024*1024):.2f} MB")

    # Validate ONNX model with onnx checker
    print("Validating ONNX model integrity...")
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    print("ONNX model structure is valid.")

    # Validate with ONNX Runtime
    print("Validating inference with ONNX Runtime...")
    ort_session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    dummy_np = np.zeros((1, 4, 512, 512), dtype=np.float32)
    ort_inputs = {ort_session.get_inputs()[0].name: dummy_np}
    ort_outs = ort_session.run(None, ort_inputs)
    print(f"ONNX Runtime output shape: {ort_outs[0].shape}, dtype: {ort_outs[0].dtype}")
    assert ort_outs[0].shape == (1, 3, 512, 512), f"Unexpected shape {ort_outs[0].shape}"
    print("ONNX verification successful!")


if __name__ == "__main__":
    export_model()
