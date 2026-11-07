"""Step 2b: export VAE encoder/decoder graphs (fp16 required, int8 best-effort).

Usage: python step2b_export_vae.py [enc|dec|both]
Writes: exported/vae_enc_fp16.tflite, exported/vae_dec_fp16.tflite (+ *_int8
attempts), exported/signatures/vae_*.json.
"""

import gc
import json
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

import numpy as np
import torch


def enc_args():
    torch.manual_seed(321)
    return {"x": torch.randn(common.B, 3, common.H_PX, common.W_PX)}


def dec_args():
    torch.manual_seed(654)
    return {"z": torch.randn(common.B, common.LATENT_CH, common.H_LAT, common.W_LAT)}


def quantize_flatbuffer(source_path, output_path, recipe, label):
    from litert_torch.quantize import translate_recipe

    ai_recipe = translate_recipe.translate_to_ai_edge_recipe(recipe.generative_recipe)
    with open(source_path, "rb") as handle:
        quantized = translate_recipe.quantize_model(handle.read(), ai_recipe)
    with open(output_path, "wb") as handle:
        handle.write(quantized)
    return output_path, [("post_quantize_%s" % label, "ok", {"size_bytes": os.path.getsize(output_path)})]


def export_fp16(module, args, out_name):
    fp32_path = out_name.replace("_fp16.tflite", "_fp32.tflite")
    if not os.path.isfile(fp32_path):
        common.convert_and_export(module, args, fp32_path, notes="fp32 baseline for post-quantize")
    return quantize_flatbuffer(fp32_path, out_name, common.fp16_recipe(), "fp16")


def export_int8(args, which, out_name):
    fp32_path = out_name.replace("_int8.tflite", "_fp32.tflite")
    if not os.path.isfile(fp32_path):
        raise FileNotFoundError("FP32 VAE graph missing before int8 quantize: %s" % fp32_path)
    from litert_torch.generative.quantize.quant_recipes import full_weight_only_recipe
    return quantize_flatbuffer(fp32_path, out_name, full_weight_only_recipe(), "int8_weight_only")


def assert_float_signature(signature):
    tensors = signature["inputs"] + signature["outputs"]
    invalid = [tensor for tensor in tensors if tensor["dtype"] != "float32"]
    if invalid:
        raise TypeError("LiteRT graph boundary must remain float32: %r" % invalid)


def run_one(which, vae, status):
    if which == "enc":
        module = common.VaeEnc(vae).eval()
        args = enc_args()
        out_name = "vae_enc_fp16.tflite"
        int8_name = "vae_enc_int8.tflite"
    else:
        module = common.VaeDec(vae).eval()
        args = dec_args()
        out_name = "vae_dec_fp16.tflite"
        int8_name = "vae_dec_int8.tflite"

    with torch.no_grad():
        torch_out = module(**args).numpy().copy()

    fp16_path, attempts = export_fp16(module, args, out_name)
    sig = common.interpreter_signature(fp16_path)
    assert_float_signature(sig)
    tag = "vae_%s" % which
    common.write_json(os.path.join(common.EXPORTED, "signatures", "%s_fp16.json" % tag), sig)
    out = common.interpreter_run(fp16_path, {name: value.numpy() for name, value in args.items()})[0]
    status["graphs"]["%s_fp16" % tag] = {
        "size_bytes": sig["size_bytes"], "attempts": [a[0] + ":" + a[1] for a in attempts],
        "io": sig, "smoke_max_abs_vs_fp32_torch": float(np.abs(out - torch_out).max()),
        "smoke_psnr_vs_fp32_torch": float(10 * np.log10(1.0 / max(np.mean((out - torch_out) ** 2), 1e-12))),
    }
    print("%s_FP16_OK size=%d smoke_max_abs=%.4g" % (tag.upper(), sig["size_bytes"], status["graphs"]["%s_fp16" % tag]["smoke_max_abs_vs_fp32_torch"]))
    gc.collect()

    try:
        int8_path, iattempts = export_int8(args, which, int8_name)
        sig8 = common.interpreter_signature(int8_path)
        assert_float_signature(sig8)
        common.write_json(os.path.join(common.EXPORTED, "signatures", "%s_int8.json" % tag), sig8)
        out8 = common.interpreter_run(int8_path, {name: value.numpy() for name, value in args.items()})[0]
        status["graphs"]["%s_int8" % tag] = {
            "size_bytes": sig8["size_bytes"], "attempts": [a[0] + ":" + a[1] for a in iattempts],
            "io": sig8, "smoke_max_abs_vs_fp32_torch": float(np.abs(out8 - torch_out).max()),
            "smoke_psnr_vs_fp32_torch": float(10 * np.log10(1.0 / max(np.mean((out8 - torch_out) ** 2), 1e-12))),
        }
        print("%s_INT8_OK size=%d smoke_max_abs=%.4g" % (tag.upper(), sig8["size_bytes"], status["graphs"]["%s_int8" % tag]["smoke_max_abs_vs_fp32_torch"]))
    except Exception as e:
        status["graphs"]["%s_int8" % tag] = {"skipped": True, "reason": str(e)[-1500:]}
        print("%s_INT8_SKIPPED reason=%s" % (tag.upper(), str(e)[-200:]))
    del module
    gc.collect()


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "both"
    os.makedirs(common.EXPORTED, exist_ok=True)
    os.makedirs(os.path.join(common.EXPORTED, "signatures"), exist_ok=True)
    common.probe_aiet_api(os.path.join(common.EXPORTED, "aiet_api_probe.json"))

    status_path = os.path.join(common.EXPORTED, "vae_export_status.json")
    status = {"graphs": {}}
    if os.path.exists(status_path):
        with open(status_path, encoding="utf-8") as f:
            status = json.load(f)

    t0 = time.time()
    vae = common.build_vae_fp32()
    common.log("vae built in %.1fs" % (time.time() - t0))

    if which in ("enc", "both"):
        run_one("enc", vae, status)
    if which in ("dec", "both"):
        run_one("dec", vae, status)

    common.write_json(status_path, status)
    print("STEP2B_DONE (%s)" % which)


if __name__ == "__main__":
    main()
