#!/usr/bin/env python3
"""
Weight Extraction, Conv-BatchNorm Folding, and State-Dict Builder for Manga LaMa.

Extracts all parameters from `audit/models/lama-manga.onnx`, folds all 75 BatchNorm points
into preceding Conv2d and ConvTranspose2d layers, and populates MangaLaMaFused.
Saves:
- audit/models/manga_lama_fused_fp32.pt
- audit/models/manga_lama_fused_fp16.pt
"""

import os
import sys
import time
import numpy as np
import onnx
from onnx import numpy_helper
import torch

from manga_lama_fused import MangaLaMaFused


def fold_conv2d_bn(w_conv, b_conv, bn_gamma, bn_beta, bn_mean, bn_var, bn_eps=1e-5):
    """
    Fold BatchNorm into Conv2d:
    W_fused = (gamma / sqrt(var + eps)) * W_conv
    B_fused = (gamma / sqrt(var + eps)) * (B_conv - mean) + beta
    """
    scale = bn_gamma / np.sqrt(bn_var + bn_eps)
    w_fused = w_conv * scale[:, None, None, None]
    if b_conv is not None:
        b_fused = scale * (b_conv - bn_mean) + bn_beta
    else:
        b_fused = bn_beta - scale * bn_mean
    return w_fused, b_fused


def fold_convtrans2d_bn(w_convt, b_convt, bn_gamma, bn_beta, bn_mean, bn_var, bn_eps=1e-5):
    """
    Fold BatchNorm into ConvTranspose2d:
    Weight shape in PyTorch: [in_channels, out_channels, kH, kW]
    W_fused = W_convt * (gamma / sqrt(var + eps))[None, :, None, None]
    B_fused = (gamma / sqrt(var + eps)) * (B_convt - mean) + beta
    """
    scale = bn_gamma / np.sqrt(bn_var + bn_eps)
    w_fused = w_convt * scale[None, :, None, None]
    b_fused = scale * (b_convt - bn_mean) + bn_beta
    return w_fused, b_fused


def extract_and_fuse(onnx_path: str = "audit/models/lama-manga.onnx"):
    print(f"Loading ONNX model from {onnx_path}...")
    t0 = time.time()
    model_proto = onnx.load(onnx_path, load_external_data=False)
    graph = model_proto.graph
    print(f"ONNX graph loaded in {time.time() - t0:.2f}s ({len(graph.initializer)} initializers)")

    # Build dictionary of all initializers
    inits = {init.name: numpy_helper.to_array(init).astype(np.float32) for init in graph.initializer}

    # Map node name -> node
    nodes = {node.name: node for node in graph.node}

    # Helper to look up Conv weight/bias by node name
    def get_conv_wb(node_name):
        node = nodes[node_name]
        w = inits[node.input[1]]
        b = inits[node.input[2]] if len(node.input) > 2 else None
        return w, b

    # Helper to look up BN params
    def get_bn_params(prefix):
        gamma = inits[f"{prefix}.weight"]
        beta = inits[f"{prefix}.bias"]
        mean = inits[f"{prefix}.running_mean"]
        var = inits[f"{prefix}.running_var"]
        return gamma, beta, mean, var

    print("Instantiating MangaLaMaFused model...")
    fused_model = MangaLaMaFused(num_blocks=18)
    fused_model.eval()

    fused_count = 0

    # 1. Load Encoder Convs (No BN folding needed; already bias-only or pre-fused in ONNX)
    print("Loading Encoder weights...")
    w0, b0 = get_conv_wb("/model/model.1/ffc/convl2l/Conv")
    fused_model.enc_conv0.weight.data.copy_(torch.from_numpy(w0))
    fused_model.enc_conv0.bias.data.copy_(torch.from_numpy(b0))

    w1, b1 = get_conv_wb("/model/model.2/ffc/convl2l/Conv")
    fused_model.enc_conv1.weight.data.copy_(torch.from_numpy(w1))
    fused_model.enc_conv1.bias.data.copy_(torch.from_numpy(b1))

    w2, b2 = get_conv_wb("/model/model.3/ffc/convl2l/Conv")
    fused_model.enc_conv2.weight.data.copy_(torch.from_numpy(w2))
    fused_model.enc_conv2.bias.data.copy_(torch.from_numpy(b2))

    w3_l, b3_l = get_conv_wb("/model/model.4/ffc/convl2l/Conv")
    fused_model.enc_conv3_l.weight.data.copy_(torch.from_numpy(w3_l))
    fused_model.enc_conv3_l.bias.data.copy_(torch.from_numpy(b3_l))

    w3_g, b3_g = get_conv_wb("/model/model.4/ffc/convl2g/Conv")
    fused_model.enc_conv3_g.weight.data.copy_(torch.from_numpy(w3_g))
    fused_model.enc_conv3_g.bias.data.copy_(torch.from_numpy(b3_g))

    # 2. Load 18 Bottleneck Blocks (72 BN folding points)
    print("Loading and folding 18 FFC Bottleneck Blocks...")
    for b_idx in range(18):
        block_num = b_idx + 5  # model.5 to model.22
        block = fused_model.blocks[b_idx]

        for conv_name, ffc in [("conv1", block.ffc1), ("conv2", block.ffc2)]:
            prefix = f"model.{block_num}.{conv_name}"

            # --- Local Branch: conv_l2l and conv_g2l fused with bn_l ---
            w_l2l = inits[f"{prefix}.ffc.convl2l.weight"]
            w_g2l = inits[f"{prefix}.ffc.convg2l.weight"]
            gamma_l, beta_l, mean_l, var_l = get_bn_params(f"{prefix}.bn_l")

            w_l2l_fused, b_l_fused = fold_conv2d_bn(w_l2l, None, gamma_l, beta_l, mean_l, var_l)
            scale_l = gamma_l / np.sqrt(var_l + 1e-5)
            w_g2l_fused = w_g2l * scale_l[:, None, None, None]

            ffc.conv_l2l.weight.data.copy_(torch.from_numpy(w_l2l_fused))
            ffc.conv_l2l.bias.data.copy_(torch.from_numpy(b_l_fused))
            ffc.conv_g2l.weight.data.copy_(torch.from_numpy(w_g2l_fused))
            fused_count += 1

            # --- Global Branch: conv_l2g and conv_g2g.conv2 fused with bn_g ---
            w_l2g = inits[f"{prefix}.ffc.convl2g.weight"]
            w_g2g_conv2 = inits[f"{prefix}.ffc.convg2g.conv2.weight"]
            gamma_g, beta_g, mean_g, var_g = get_bn_params(f"{prefix}.bn_g")

            w_l2g_fused, b_g_fused = fold_conv2d_bn(w_l2g, None, gamma_g, beta_g, mean_g, var_g)
            scale_g = gamma_g / np.sqrt(var_g + 1e-5)
            w_g2g_conv2_fused = w_g2g_conv2 * scale_g[:, None, None, None]

            ffc.conv_l2g.weight.data.copy_(torch.from_numpy(w_l2g_fused))
            ffc.conv_l2g.bias.data.copy_(torch.from_numpy(b_g_fused))
            ffc.conv_g2g.conv2.weight.data.copy_(torch.from_numpy(w_g2g_conv2_fused))
            fused_count += 1

            # --- Spectral Unit internal convs: conv1 and fu.conv_layer ---
            conv_prefix = f"/model/model.{block_num}/{conv_name}"
            w_g2g_conv1, b_g2g_conv1 = get_conv_wb(f"{conv_prefix}/ffc/convg2g/conv1/conv1.0/Conv")
            ffc.conv_g2g.conv1.weight.data.copy_(torch.from_numpy(w_g2g_conv1))
            ffc.conv_g2g.conv1.bias.data.copy_(torch.from_numpy(b_g2g_conv1))

            w_fu_conv, b_fu_conv = get_conv_wb(f"{conv_prefix}/ffc/convg2g/fu/conv_layer/Conv")
            # Permute from interleaved [r0, i0, r1, i1, ...] to concatenated [r0...r191, i0...i191]
            perm = [2 * c for c in range(192)] + [2 * c + 1 for c in range(192)]
            w_fu_conv_perm = w_fu_conv[perm, :, :, :][:, perm, :, :]
            b_fu_conv_perm = b_fu_conv[perm]
            ffc.conv_g2g.fu.conv_layer.weight.data.copy_(torch.from_numpy(w_fu_conv_perm))
            ffc.conv_g2g.fu.conv_layer.bias.data.copy_(torch.from_numpy(b_fu_conv_perm))

    print(f"Fused {fused_count} bottleneck BN points (72 expected).")

    # 3. Load and fold Decoder ConvTranspose layers (3 BN folding points)
    print("Loading and folding Decoder ConvTranspose layers...")
    w_ct0 = inits["model.24.weight"]
    b_ct0 = inits["model.24.bias"]
    gamma_ct0, beta_ct0, mean_ct0, var_ct0 = get_bn_params("model.25")
    w_ct0_fused, b_ct0_fused = fold_convtrans2d_bn(w_ct0, b_ct0, gamma_ct0, beta_ct0, mean_ct0, var_ct0)
    fused_model.dec_convtrans0.weight.data.copy_(torch.from_numpy(w_ct0_fused))
    fused_model.dec_convtrans0.bias.data.copy_(torch.from_numpy(b_ct0_fused))
    fused_count += 1

    w_ct1 = inits["model.27.weight"]
    b_ct1 = inits["model.27.bias"]
    gamma_ct1, beta_ct1, mean_ct1, var_ct1 = get_bn_params("model.28")
    w_ct1_fused, b_ct1_fused = fold_convtrans2d_bn(w_ct1, b_ct1, gamma_ct1, beta_ct1, mean_ct1, var_ct1)
    fused_model.dec_convtrans1.weight.data.copy_(torch.from_numpy(w_ct1_fused))
    fused_model.dec_convtrans1.bias.data.copy_(torch.from_numpy(b_ct1_fused))
    fused_count += 1

    w_ct2 = inits["model.30.weight"]
    b_ct2 = inits["model.30.bias"]
    gamma_ct2, beta_ct2, mean_ct2, var_ct2 = get_bn_params("model.31")
    w_ct2_fused, b_ct2_fused = fold_convtrans2d_bn(w_ct2, b_ct2, gamma_ct2, beta_ct2, mean_ct2, var_ct2)
    fused_model.dec_convtrans2.weight.data.copy_(torch.from_numpy(w_ct2_fused))
    fused_model.dec_convtrans2.bias.data.copy_(torch.from_numpy(b_ct2_fused))
    fused_count += 1

    print(f"Total BatchNorm points folded: {fused_count} (75 expected).")
    assert fused_count == 75, f"Expected exactly 75 BN points folded, got {fused_count}"

    # 4. Load Output Head Conv
    print("Loading Output Head weights...")
    w_out = inits["model.34.weight"]
    b_out = inits["model.34.bias"]
    fused_model.out_conv.weight.data.copy_(torch.from_numpy(w_out))
    fused_model.out_conv.bias.data.copy_(torch.from_numpy(b_out))

    # Save FP32 model checkpoint
    os.makedirs("audit/models", exist_ok=True)
    fp32_path = "audit/models/manga_lama_fused_fp32.pt"
    torch.save(fused_model.state_dict(), fp32_path)
    fp32_size_mb = os.path.getsize(fp32_path) / (1024 * 1024)
    print(f"Saved fused FP32 state-dict to {fp32_path} ({fp32_size_mb:.2f} MB)")

    # Save FP16 model checkpoint
    fused_model_fp16 = MangaLaMaFused(num_blocks=18)
    fused_model_fp16.load_state_dict(fused_model.state_dict())
    fused_model_fp16 = fused_model_fp16.half()
    fp16_path = "audit/models/manga_lama_fused_fp16.pt"
    torch.save(fused_model_fp16.state_dict(), fp16_path)
    fp16_size_mb = os.path.getsize(fp16_path) / (1024 * 1024)
    print(f"Saved fused FP16 state-dict to {fp16_path} ({fp16_size_mb:.2f} MB)")

    return fused_model


if __name__ == "__main__":
    extract_and_fuse()
