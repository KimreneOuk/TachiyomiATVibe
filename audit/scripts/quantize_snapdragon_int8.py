#!/usr/bin/env python3
"""
Quantize Manga LaMa Snapdragon Model to INT8 for Adreno GPU and Hexagon NPU.

Features:
1. Calibrated Representative Dataset from real manga pages and masks.
2. Dual INT8 Export:
   - Dynamic Range INT8: INT8 weights, FP32/FP16 activations (Adreno GPU OpenCL accelerated).
   - Full Integer INT8 with Float I/O: Calibrated INT8 weights + INT8 activations (Hexagon NPU QNN + Adreno GPU).
3. LiteRT GPU Delegate Compatibility Verification via tf.lite.experimental.Analyzer.
"""

import os
import sys
import glob
import numpy as np
from PIL import Image
import tensorflow as tf

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(SCRIPT_DIR)
MODELS_DIR = os.path.join(ROOT_DIR, "models")
SAVED_MODEL_DIR = os.path.join(MODELS_DIR, "onnx2tf_out")
REF_DATA_DIR = os.path.join(ROOT_DIR, "reference_data")


def load_calibration_samples(num_samples: int = 25):
    """
    Load real manga crops and masks to create representative calibration inputs.
    Shape: [1, 512, 512, 4] float32 in [0.0, 1.0] (NHWC)
    Channels 0-2: Masked RGB (original pixel * (1 - mask))
    Channel 3: Binary Mask (1.0 = inpaint hole, 0.0 = keep original)
    """
    test_imgs = sorted(glob.glob(os.path.join(REF_DATA_DIR, "test_images", "*.png")))
    test_masks = sorted(glob.glob(os.path.join(REF_DATA_DIR, "test_masks", "*.png")))

    samples = []
    for img_p, mask_p in zip(test_imgs, test_masks):
        try:
            img = Image.open(img_p).convert("RGB").resize((512, 512), Image.Resampling.BILINEAR)
            mask = Image.open(mask_p).convert("L").resize((512, 512), Image.Resampling.NEAREST)

            img_np = np.array(img, dtype=np.float32) / 255.0  # [512, 512, 3]
            mask_np = (np.array(mask, dtype=np.float32) / 255.0) > 0.5
            mask_np = mask_np.astype(np.float32)[:, :, None]  # [512, 512, 1]

            # Masked image
            masked_img = img_np * (1.0 - mask_np)
            tensor_4d = np.concatenate([masked_img, mask_np], axis=-1)  # [512, 512, 4]
            samples.append(np.expand_dims(tensor_4d, axis=0))  # [1, 512, 512, 4]
        except Exception as e:
            print(f"Warning: Failed to load {img_p}: {e}")

    # If fewer samples, supplement with synthetically masked crops
    while len(samples) < num_samples and len(samples) > 0:
        base = samples[len(samples) % len(test_imgs)].copy()
        # Add random noise or shift
        noise = np.random.uniform(-0.05, 0.05, base.shape).astype(np.float32)
        base = np.clip(base + noise, 0.0, 1.0)
        samples.append(base)

    print(f"Loaded {len(samples)} representative calibration samples.")
    return samples


def quantize_dynamic_range(saved_model_dir: str, output_path: str):
    print(f"\n--- Quantizing to Dynamic Range INT8: {output_path} ---")
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Dynamic INT8 model saved. Size: {size_mb:.2f} MB")
    return output_path


def quantize_full_integer(saved_model_dir: str, output_path: str, samples: list):
    print(f"\n--- Quantizing to Full Integer INT8 with Float I/O: {output_path} ---")
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    def representative_dataset():
        for s in samples:
            yield [s]

    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
        tf.lite.OpsSet.TFLITE_BUILTINS,
    ]
    # Float32 I/O for direct ByteBuffer compatibility on Android
    converter.inference_input_type = tf.float32
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Full Integer INT8 model saved. Size: {size_mb:.2f} MB")
    return output_path


def main():
    if not os.path.exists(SAVED_MODEL_DIR):
        print(f"SavedModel not found at {SAVED_MODEL_DIR}")
        sys.exit(1)

    samples = load_calibration_samples(num_samples=20)

    # 1. Full Integer INT8 (Hexagon NPU + Adreno GPU)
    full_int8_path = os.path.join(MODELS_DIR, "manga_lama_fused_snapdragon_int8.tflite")
    quantize_full_integer(SAVED_MODEL_DIR, full_int8_path, samples)

    # 2. Dynamic Range INT8 (Adreno GPU)
    dyn_int8_path = os.path.join(MODELS_DIR, "manga_lama_fused_snapdragon_dyn_int8.tflite")
    quantize_dynamic_range(SAVED_MODEL_DIR, dyn_int8_path)

    print("\n--- Verifying GPU Compatibility with TFLite Analyzer ---")
    for path, name in [(full_int8_path, "Full INT8"), (dyn_int8_path, "Dynamic INT8")]:
        print(f"\nAnalyzing {name} ({os.path.basename(path)}):")
        tf.lite.experimental.Analyzer.analyze(model_path=path, gpu_compatibility=True)

    print("\nQuantization complete.")


if __name__ == "__main__":
    main()
