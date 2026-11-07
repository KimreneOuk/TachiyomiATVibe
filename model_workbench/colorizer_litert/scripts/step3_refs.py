"""Step 3: dump scheduler/merge_lines/Lab reference vectors for the Kotlin port.

Writes (UTF-8): exported/scheduler_reference.json, merge_lines_reference.json,
lab_reference.json.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

import numpy as np
import torch


def scheduler_dump():
    from diffusers import FlowMatchEulerDiscreteScheduler
    sched = FlowMatchEulerDiscreteScheduler(shift=3.0)
    sched.set_timesteps(20, device="cpu")

    timesteps = sched.timesteps.detach().cpu().float().tolist()
    sigmas = sched.sigmas.detach().cpu().float().tolist()
    out = {
        "class": "diffusers.FlowMatchEulerDiscreteScheduler",
        "config": {"shift": 3.0, "num_train_timesteps": int(sched.config.num_train_timesteps)},
        "steps": 20,
        "timesteps_f32": timesteps,
        "sigmas_f32": sigmas,
        "per_step": [
            {"i": i, "t": timesteps[i], "sigma": sigmas[i], "sigma_next": sigmas[i + 1],
             "delta": sigmas[i + 1] - sigmas[i]}
            for i in range(20)
        ],
        "step_math": "z_next = z + (sigma_next - sigma) * model_output, with model_output "
                     "= vu + cfg * (vc - vu) when using CFG; t_i = 1000 * sigma_i",
    }

    # Fully-worked toy examples: (1,32,2,2) latents through all 20 steps.
    examples = []
    for seed in (0, 1):
        g = torch.Generator().manual_seed(seed)
        z = torch.randn(1, 32, 2, 2, generator=g)
        v = torch.randn(1, 32, 2, 2, generator=g)  # fixed velocity field for the example
        sched2 = FlowMatchEulerDiscreteScheduler(shift=3.0)
        sched2.set_timesteps(20, device="cpu")
        z0 = z.clone()
        trace = []
        for t in sched2.timesteps:
            prev = sched2.step(v, t, z).prev_sample
            trace.append({"t": float(t), "z_before_sum": float(z.sum()), "z_after_sum": float(prev.sum())})
            z = prev
        examples.append({
            "seed": seed,
            "z0_first8": z0.flatten()[:8].tolist(),
            "v_first8": v.flatten()[:8].tolist(),
            "z_final_first8": z.flatten()[:8].tolist(),
            "z_final_sum": float(z.sum()),
            "trace": trace,
        })
    out["worked_examples"] = examples
    return out


def gaussian_kernel_dump(sigma=3.0):
    """kornia gaussian_blur2d internals: kernel size + normalized 1D kernel."""
    from kornia.filters.kernels import get_gaussian_kernel1d, get_gaussian_kernel2d
    k = 2 * math.ceil(3 * sigma) + 1
    k1 = get_gaussian_kernel1d((k,), (sigma,))
    k2 = get_gaussian_kernel2d((k, k), (sigma, sigma))
    return {
        "kernel_size": k,
        "formula": "k = 2*ceil(3*sigma)+1 = %d, gaussian, normalized (sum=1), "
                   "separable 2D, padding=k//2 with BORDER_REFLECT (kornia default 'reflect')" % k,
        "sigma": sigma,
        "kernel1d_f32": k1.flatten().tolist(),
        "kernel2d_sum": float(k2.sum()),
    }


def merge_lines_dump():
    pinned_model = common.import_pinned_code()
    g = torch.Generator().manual_seed(7)
    H, W = 8, 12
    out_img = torch.rand(1, 3, H, W, generator=g)
    bw_img = torch.rand(1, 3, H, W, generator=g) * 0.4 + 0.3  # grayish page
    merged_ink10 = pinned_model.merge_lines(out_img, bw_img, 3.0, ink=1.0)
    merged_ink07 = pinned_model.merge_lines(out_img, bw_img, 3.0, ink=0.7)

    blur_ref = pinned_model.blur(bw_img.clamp(0, 1), 3.0)
    lab_out = __import__("kornia").color.rgb_to_lab(out_img.clamp(0, 1))
    ref_l = __import__("kornia").color.rgb_to_lab(bw_img.clamp(0, 1))[:, :1]

    out = {
        "shape_hw": [H, W],
        "sigma": 3.0,
        "input_out_rgb_f32": out_img.flatten().tolist(),
        "input_bw_rgb_f32": bw_img.flatten().tolist(),
        "intermediate_lab_out_first12": lab_out.flatten()[:12].tolist(),
        "intermediate_bw_L_first12": ref_l.flatten()[:12].tolist(),
        "intermediate_blur_bw_L_first12": blur_ref[:, :1].flatten()[:12].tolist(),
        "output_rgb_ink1.0_f32": merged_ink10.flatten().tolist(),
        "output_rgb_ink0.7_f32": merged_ink07.flatten().tolist(),
        "kernel": gaussian_kernel_dump(3.0),
        "merge_math": "L_out' = clamp(L_lab(out) + ink * (L_lab(bw) - blur(L_lab(bw), sigma)), 0, 100); "
                      "a,b stay from out; result = lab_to_rgb(L_out', a, b)",
    }
    return out


def lab_dump():
    import kornia as K
    g = torch.Generator().manual_seed(11)
    patches = {
        "random4x4": torch.rand(1, 3, 4, 4, generator=g),
        "gray_ramp": torch.linspace(0.05, 0.95, 16).reshape(1, 1, 4, 4).repeat(1, 3, 1, 1),
        "flat_colors": torch.tensor([0.85, 0.55, 0.35]).reshape(1, 3, 1, 1).repeat(1, 1, 2, 2),
    }
    out = {"note": "kornia rgb_to_lab expects inputs in [0,1]; returns L in [0,100], a,b ~ [-128,127] (D65)"}
    for name, rgb in patches.items():
        lab = K.color.rgb_to_lab(rgb.clamp(0, 1))
        back = K.color.lab_to_rgb(lab)
        out[name] = {
            "rgb_in": rgb.clamp(0, 1).flatten().tolist(),
            "lab_out": lab.flatten().tolist(),
            "lab_to_rgb_roundtrip": back.flatten().tolist(),
            "roundtrip_max_abs": float((back - rgb.clamp(0, 1)).abs().max()),
        }
    return out


def main():
    os.makedirs(common.EXPORTED, exist_ok=True)
    sched = scheduler_dump()
    common.write_json(os.path.join(common.EXPORTED, "scheduler_reference.json"), sched)
    common.write_json(os.path.join(common.EXPORTED, "merge_lines_reference.json"), merge_lines_dump())
    common.write_json(os.path.join(common.EXPORTED, "lab_reference.json"), lab_dump())
    print("STEP3_DONE t0=%.6f t_last=%.6f final_sigma=%.6f examples=%d" % (
        sched["timesteps_f32"][0], sched["timesteps_f32"][-1],
        sched["sigmas_f32"][-1], len(sched["worked_examples"])))


if __name__ == "__main__":
    main()
