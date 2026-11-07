"""Step 2a: export the DiT single-step graph (fp16 required, int8 best-effort).

Writes: exported/dit_fp16.tflite, exported/dit_int8.tflite (or skip record),
exported/signatures/dit_*.json, exported/errors.md entries for int8 attempts.
"""

import gc
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

import numpy as np
import torch


def sample_inputs():
    torch.manual_seed(123)
    return {
        "z_t": torch.randn(common.B, common.LATENT_CH, common.H_LAT, common.W_LAT),
        "z_bw": torch.randn(common.B, common.LATENT_CH, common.H_LAT, common.W_LAT),
        "bw": torch.randn(common.B, 3, common.H_PX, common.W_PX),
        "t": torch.tensor([750.0, 250.0], dtype=torch.float32),
        "tokens": torch.randn(common.B, common.N_TOKENS, common.TEXT_DIM),
    }


def quantize_flatbuffer(source_path, output_path, recipe, label):
    from litert_torch.quantize import translate_recipe

    ai_recipe = translate_recipe.translate_to_ai_edge_recipe(recipe.generative_recipe)
    with open(source_path, "rb") as handle:
        quantized = translate_recipe.quantize_model(handle.read(), ai_recipe)
    with open(output_path, "wb") as handle:
        handle.write(quantized)
    return output_path, [("post_quantize_%s" % label, "ok", {"size_bytes": os.path.getsize(output_path)})]


def export_fp16(step, args):
    """Export an FP32 baseline then post-quantize its weights to FP16."""
    fp32_path = os.path.join(common.EXPORTED, "tmp_dit_fp32_named.tflite")
    if not os.path.isfile(fp32_path):
        common.convert_and_export(step, args, fp32_path, notes="named fp32 baseline for post-quantize")
    return quantize_flatbuffer(fp32_path, os.path.join(common.EXPORTED, "dit_fp16.tflite"), common.fp16_recipe(), "fp16")


def export_int8_weight_only(fp32_path, int8_path):
    from litert_torch.generative.quantize.quant_recipes import full_weight_only_recipe
    return quantize_flatbuffer(fp32_path, int8_path, full_weight_only_recipe(), "int8_weight_only")


def assert_float_signature(signature):
    tensors = signature["inputs"] + signature["outputs"]
    invalid = [tensor for tensor in tensors if tensor["dtype"] != "float32"]
    if invalid:
        raise TypeError("LiteRT graph boundary must remain float32: %r" % invalid)


def main():
    os.makedirs(common.EXPORTED, exist_ok=True)
    os.makedirs(os.path.join(common.EXPORTED, "signatures"), exist_ok=True)
    common.probe_aiet_api(os.path.join(common.EXPORTED, "aiet_api_probe.json"))

    t0 = time.time()
    colorizer = common.load_colorizer_fp32()
    step = common.DiTStep(colorizer)
    args = sample_inputs()
    torch_out = None
    with torch.no_grad():
        torch_out = step(**args).numpy().copy()
    common.log("DiT reference forward done (%.1fs elapsed)" % (time.time() - t0))

    status = {"graphs": {}}

    fp16_path, fp16_attempts = export_fp16(step, args)
    sig = common.interpreter_signature(fp16_path)
    assert_float_signature(sig)
    common.write_json(os.path.join(common.EXPORTED, "signatures", "dit_fp16.json"), sig)
    fp16_out = common.interpreter_run(fp16_path, {name: value.numpy() for name, value in args.items()})[0]
    status["graphs"]["dit_fp16"] = {
        "size_bytes": sig["size_bytes"], "attempts": [a[0] + ":" + a[1] for a in fp16_attempts],
        "io": sig,
        "smoke_max_abs_vs_fp32_torch": float(np.abs(fp16_out - torch_out).max()),
    }
    print("DIT_FP16_OK size=%d smoke_max_abs=%.4g" % (sig["size_bytes"], status["graphs"]["dit_fp16"]["smoke_max_abs_vs_fp32_torch"]))
    gc.collect()

    try:
        int8_path, int8_attempts = export_int8_weight_only(
            os.path.join(common.EXPORTED, "tmp_dit_fp32_named.tflite"),
            os.path.join(common.EXPORTED, "dit_int8.tflite"))
        sig8 = common.interpreter_signature(int8_path)
        assert_float_signature(sig8)
        common.write_json(os.path.join(common.EXPORTED, "signatures", "dit_int8.json"), sig8)
        int8_out = common.interpreter_run(int8_path, {name: value.numpy() for name, value in args.items()})[0]
        status["graphs"]["dit_int8"] = {
            "size_bytes": sig8["size_bytes"], "attempts": [a[0] + ":" + a[1] for a in int8_attempts],
            "io": sig8,
            "smoke_max_abs_vs_fp32_torch": float(np.abs(int8_out - torch_out).max()),
        }
        print("DIT_INT8_OK size=%d smoke_max_abs=%.4g" % (sig8["size_bytes"], status["graphs"]["dit_int8"]["smoke_max_abs_vs_fp32_torch"]))
    except Exception as e:
        status["graphs"]["dit_int8"] = {"skipped": True, "reason": str(e)[-1500:]}
        print("DIT_INT8_SKIPPED reason=%s" % str(e)[-200:])

    common.write_json(os.path.join(common.EXPORTED, "dit_export_status.json"), status)
    print("STEP2A_DONE")


if __name__ == "__main__":
    main()
