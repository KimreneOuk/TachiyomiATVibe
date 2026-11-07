"""Step 1 (v2): wrapper-vs-Colorizer.forward numerics gate + look-token dumps.

Writes (UTF-8): exported/wrapper_numerics.json, exported/diffusers_source_dump.txt,
exported/aiet_api_probe.json, exported/color_look_normal.f32.bin,
exported/color_look_dusk.f32.bin, wrapper_check_io.npz.
Prints ASCII PASS/FAIL summary; exits nonzero on FAIL.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

import numpy as np
import torch


def dump_diffusers_sources(report):
    import inspect
    from diffusers import SanaTransformer2DModel
    import diffusers
    from diffusers.models.transformers.sana_transformer import SanaTransformerBlock
    report["diffusers_version"] = diffusers.__version__
    report["torch_version"] = torch.__version__
    parts = [
        "diffusers==%s torch==%s python=%s" % (diffusers.__version__, torch.__version__, sys.version.split()[0]),
        "=" * 80,
        inspect.getsource(SanaTransformer2DModel.forward),
        "=" * 80,
        inspect.getsource(SanaTransformerBlock.forward),
    ]
    return "\n".join(parts)


def main():
    os.makedirs(common.EXPORTED, exist_ok=True)
    report = {"gate_max_abs": 1e-4}

    t0 = __import__("time").time()
    colorizer = common.load_colorizer_fp32()
    report["load_seconds"] = round(__import__("time").time() - t0, 1)

    torch.manual_seed(0)
    z_t = torch.randn(common.B, common.LATENT_CH, common.H_LAT, common.W_LAT)
    z_bw = torch.randn(common.B, common.LATENT_CH, common.H_LAT, common.W_LAT)
    bw = torch.randn(common.B, 3, common.H_PX, common.W_PX)
    t_f32 = torch.tensor([500.0, 250.0], dtype=torch.float32)
    ver = torch.tensor([common.LOOK_NORMAL, common.LOOK_NULL], dtype=torch.long)
    tokens = common.look_tokens(colorizer, ver)

    # Reference BEFORE DiTStep construction: constructing the step clears the
    # block pre-hooks that Colorizer.forward relies on for B/W conditioning.
    with torch.no_grad():
        ref = colorizer.forward(z_t, z_bw, bw, t_f32, ver).float()
        ref_int = colorizer.forward(z_t, z_bw, bw, t_f32.long(), ver).float()
    report["int64_t_vs_f32_t_max_abs"] = float((ref - ref_int).abs().max())

    step = common.DiTStep(colorizer)
    report["hooks_cleared"] = step.hooks_cleared

    with torch.no_grad():
        out = step(z_t, z_bw, bw, t_f32, tokens).float()

    max_abs = float((ref - out).abs().max())
    report["velocity_max_abs_diff"] = max_abs
    report["velocity_shape"] = list(out.shape)
    report["pass"] = bool(max_abs < 1e-4)

    np.savez(os.path.join(common.EXPORTED, "wrapper_check_io.npz"),
             z_t=z_t.numpy(), z_bw=z_bw.numpy(), bw=bw.numpy(), t=t_f32.numpy(),
             tokens=tokens.numpy(), ref=ref.numpy(), wrapped=out.numpy())

    src_dump = dump_diffusers_sources(report)
    common.write_text(os.path.join(common.EXPORTED, "diffusers_source_dump.txt"), src_dump)

    # --- Look-token table dump (spec Export 1 step 3): Kotlin reads row 1
    # (normal); the NULL/CFG row is a 256-wide zero vector, no file needed. ---
    table = colorizer.token.weight.detach().float().contiguous()
    assert tuple(table.shape) == (2, common.TEXT_DIM), tuple(table.shape)
    table[common.LOOK_NORMAL].numpy().tofile(os.path.join(common.EXPORTED, "color_look_normal.f32.bin"))
    table[0].numpy().tofile(os.path.join(common.EXPORTED, "color_look_dusk.f32.bin"))
    report["look_rows"] = 2
    report["look_normal_vector_first8"] = table[common.LOOK_NORMAL].flatten()[:8].tolist()
    report["look_dusk_vector_first8"] = table[0].flatten()[:8].tolist()

    # Converter API probe for the export scripts.
    common.probe_aiet_api(os.path.join(common.EXPORTED, "aiet_api_probe.json"))

    common.write_json(os.path.join(common.EXPORTED, "wrapper_numerics.json"), report)
    print("WRAPPER_NUMERICS: max_abs_diff=%.6g gate=1e-4 -> %s" % (max_abs, "PASS" if report["pass"] else "FAIL"))
    print("int64_t_vs_f32_t_max_abs=%.6g" % report["int64_t_vs_f32_t_max_abs"])
    print("diffusers=%s" % report.get("diffusers_version"))
    if not report["pass"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
