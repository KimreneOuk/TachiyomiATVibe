#!/usr/bin/env python3
"""
Test and compare the preprocessing and inference contracts of:
1. mayocream/lama-manga.onnx (dual-input: image [1,3,512,512], mask [1,1,512,512])
2. Liiesl/lama-manga_fp16.onnx (single-input: input [1,4,512,512])
3. Liiesl/lama-manga_int8.onnx (single-input: input [1,4,512,512])
4. g-ronimo/lama_512_fp16.onnx (single-input: input [1,4,512,512])
"""
import time
import numpy as np
from PIL import Image
import onnxruntime as ort

def load_and_preprocess(img_path, mask_path, size=512):
    # 1. Load image (RGB)
    img = Image.open(img_path).convert("RGB").resize((size, size), Image.Resampling.LANCZOS)
    img_np = np.array(img, dtype=np.float32) / 255.0  # (512, 512, 3)

    # 2. Load mask (grayscale)
    mask = Image.open(mask_path).convert("L").resize((size, size), Image.Resampling.NEAREST)
    mask_np = np.array(mask, dtype=np.float32)
    # Binary mask: 1.0 = area to inpaint / erase, 0.0 = keep background
    mask_binary = (mask_np > 127.0).astype(np.float32)  # (512, 512)

    # Dual input format (mayocream)
    # image: [1, 3, 512, 512] - masked image where hole is zeroed out: img * (1 - mask)
    # Note: Does mayocream expect img * (1 - mask) or raw img?
    # Let's check both or verify graph!
    masked_img = img_np * (1.0 - mask_binary[..., None])
    img_nchw = np.transpose(img_np, (2, 0, 1))[None, ...] # (1, 3, 512, 512)
    masked_nchw = np.transpose(masked_img, (2, 0, 1))[None, ...] # (1, 3, 512, 512)
    mask_nchw = mask_binary[None, None, ...] # (1, 1, 512, 512)

    # Single 4-channel input format (Liiesl, g-ronimo)
    # Channels 0-2: masked RGB (hole zeroed out)
    # Channel 3: binary mask
    input_4ch = np.concatenate([masked_img, mask_binary[..., None]], axis=-1) # (512, 512, 4)
    input_4ch_nchw = np.transpose(input_4ch, (2, 0, 1))[None, ...] # (1, 4, 512, 512)

    return {
        "raw_img": img,
        "raw_mask": mask,
        "img_nchw": img_nchw,
        "masked_nchw": masked_nchw,
        "mask_nchw": mask_nchw,
        "input_4ch_nchw": input_4ch_nchw
    }

def inspect_graph_inputs(model_path):
    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    inputs = sess.get_inputs()
    outputs = sess.get_outputs()
    return [(i.name, i.shape, i.type) for i in inputs], [(o.name, o.shape, o.type) for o in outputs]

def main():
    img_p = "audit/reference_data/test_images/case1_bubble_screentone.png"
    mask_p = "audit/reference_data/test_masks/case1_bubble_screentone_mask.png"
    data = load_and_preprocess(img_p, mask_p)

    models = [
        ("mayocream_fp32", "audit/models/lama-manga.onnx"),
        ("liiesl_fp16", "audit/models/lama-manga_fp16.onnx"),
        ("liiesl_int8", "audit/models/lama-manga_int8.onnx"),
        ("gronimo_fp16", "audit/models/lama_512_fp16.onnx"),
    ]

    outputs = {}
    for tag, mpath in models:
        in_info, out_info = inspect_graph_inputs(mpath)
        print(f"\n[{tag}] Model: {mpath}")
        print(f"  Inputs: {in_info}")
        print(f"  Outputs: {out_info}")

        sess = ort.InferenceSession(mpath, providers=["CPUExecutionProvider"])
        t0 = time.time()
        if len(in_info) == 2:
            # mayocream: input names: 'image' and 'mask'
            # Let's test passing raw img vs masked img
            feed = {in_info[0][0]: data["masked_nchw"], in_info[1][0]: data["mask_nchw"]}
        else:
            feed = {in_info[0][0]: data["input_4ch_nchw"]}

        res = sess.run(None, feed)[0]
        dt = time.time() - t0
        print(f"  Output shape: {res.shape}, dtype: {res.dtype}, min: {res.min():.4f}, max: {res.max():.4f}, elapsed: {dt:.3f}s")
        outputs[tag] = res

    # Numerical Comparison against mayocream_fp32
    ref = outputs["mayocream_fp32"]
    print("\n=== NUMERICAL COMPARISON VS ORIGINAL MAYOCREAM FP32 ===")
    for tag in ["liiesl_fp16", "liiesl_int8", "gronimo_fp16"]:
        out = outputs[tag]
        diff = np.abs(out - ref)
        mae = float(np.mean(diff))
        max_diff = float(np.max(diff))
        max_diff_255 = max_diff * 255.0
        rmse = float(np.sqrt(np.mean((out - ref) ** 2)))
        print(f"\n{tag} vs mayocream_fp32:")
        print(f"  MAE: {mae:.6f}")
        print(f"  RMSE: {rmse:.6f}")
        print(f"  Max Absolute Diff: {max_diff:.6f} ({max_diff_255:.2f} / 255)")
        print(f"  Pixels differing > 5/255: {(diff > 5/255).sum()} / {diff.size} ({(diff > 5/255).mean()*100:.3f}%)")
        print(f"  Pixels differing > 50/255: {(diff > 50/255).sum()} / {diff.size} ({(diff > 50/255).mean()*100:.3f}%)")

if __name__ == "__main__":
    main()
