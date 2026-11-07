"""Shared helpers for the pinned Sana Coloring v2 -> LiteRT conversion."""

import gc
import json
import os
import sys
import time
import traceback

# Module-level: VaeEnc/VaeDec subclass nn.Module at class-definition time.
import torch.nn as nn

WORK = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
EXPORTED = os.path.join(WORK, "exported")
DOWNLOAD = os.path.join(WORK, "download")
COLORING_REPO = os.path.join(DOWNLOAD, "coloring_repo_v2")
SANA_BASE = os.path.join(DOWNLOAD, "sana_base")
LOG_PATH = os.path.join(WORK, "export_progress.log")

# Spec §Fixed v1 inference shape. PORTRAIT (matches the Kotlin engine's
# INPUT_W=512 / INPUT_H=768): NCHW (B,3,768,512), latent (B,32,24,16).
B = 2
H_PX, W_PX = 768, 512
H_LAT, W_LAT = 24, 16
LATENT_CH = 32
TEXT_DIM = 256
N_TOKENS = 1  # v2: a single look token per row
LOOK_NORMAL = 1
LOOK_NULL = 2
VAE_SCALE = 0.41407


def log(msg):
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    # Console output must stay ASCII (cp1252 host console).
    print(line.encode("ascii", "replace").decode("ascii"), flush=True)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def append_errors_md(title, exc):
    path = os.path.join(EXPORTED, "errors.md")
    os.makedirs(EXPORTED, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write("\n\n## %s\n\n%s\n" % (title, exc if isinstance(exc, str) else "".join(traceback.format_exception(exc))))


def import_pinned_code():
    """Import the pinned v2 model.py (its imports: torch/diffusers/kornia only,
    all real in the container)."""
    sys.path.insert(0, COLORING_REPO)
    import model as pinned_model
    return pinned_model


def build_colorizer(repo=SANA_BASE):
    """Build the pinned v2 Colorizer against the local offline copy.

    Mirrors pinned model.Colorizer.__init__ exactly except from_pretrained
    gets variant="fp16" so the local *.fp16.safetensors resolve (the pinned
    default path names only exist on the hub layout).
    """
    import torch
    import torch.nn as nn
    pinned_model = import_pinned_code()
    from diffusers import SanaTransformer2DModel
    from diffusers.models.controlnets.controlnet import ControlNetConditioningEmbedding

    class ColorizerLocal(pinned_model.Colorizer):
        def __init__(self2):
            nn.Module.__init__(self2)
            net = SanaTransformer2DModel.from_pretrained(repo, subfolder="transformer", variant="fp16")
            old = net.patch_embed.proj
            conv = nn.Conv2d(2 * 32 + pinned_model.HINT, old.out_channels, old.kernel_size, old.stride)
            with torch.no_grad():
                conv.weight.zero_()
                conv.weight[:, :32] = old.weight
                conv.bias.copy_(old.bias)
            net.patch_embed.proj = conv
            net.caption_projection = type(net.caption_projection)(pinned_model.TEXT_DIM, old.out_channels)
            self2.net = net
            self2.token = nn.Embedding(2, pinned_model.TEXT_DIM)
            nn.init.normal_(self2.token.weight, std=0.02)
            self2.hint = ControlNetConditioningEmbedding(pinned_model.HINT, 3, (16, 32, 64, 128, 256, 256))
            self2.hint.conv_out.reset_parameters()
            self2.proj = nn.ModuleList(nn.Linear(pinned_model.HINT, old.out_channels) for _ in net.transformer_blocks)
            for p in self2.proj:
                nn.init.zeros_(p.weight)
                nn.init.zeros_(p.bias)
            for i, b in enumerate(net.transformer_blocks):
                b.register_forward_pre_hook(self2.inject(i), with_kwargs=True)

    return ColorizerLocal()


def load_colorizer_fp32(repo=SANA_BASE):
    """Colorizer with ema weights, everything fp32 (spec Export 1 step 4)."""
    import torch
    t0 = time.time()
    colorizer = build_colorizer(repo=repo)
    log("colorizer built in %.1fs" % (time.time() - t0))
    t0 = time.time()
    ema = torch.load(os.path.join(COLORING_REPO, "ema_bf16.pt"), weights_only=True, map_location="cpu", mmap=True)
    res = colorizer.load_state_dict(ema, strict=True)
    log("load_state_dict(strict) missing=%d unexpected=%d" % (len(res.missing_keys), len(res.unexpected_keys)))
    n_loaded = len(ema)
    del ema
    gc.collect()
    colorizer = colorizer.float().eval().requires_grad_(False)
    log("ema loaded (%d tensors) + fp32 cast in %.1fs" % (n_loaded, time.time() - t0))
    return colorizer


def build_vae_fp32(repo=SANA_BASE):
    import torch
    from diffusers import AutoencoderDC
    vae = AutoencoderDC.from_pretrained(repo, subfolder="vae", variant="fp16")
    vae = vae.float().eval().requires_grad_(False)
    return vae


class DiTStep:
    """Factory for the hook-free, token-input DiT single-step graph module.

    Per spec Export 1: hint/feat explicit, embedding lookup removed (tokens is
    an input), inject pre-hooks cleared and per-block proj residuals applied
    by calling blocks explicitly, then net.norm_out/proj_out + unpatchify in
    SanaTransformer2DModel.forward op order.
    """

    def __new__(cls, colorizer):
        import torch
        import torch.nn as nn

        net = colorizer.net
        for b in net.transformer_blocks:
            if not b._forward_pre_hooks:
                raise RuntimeError("expected inject pre-hooks on every block")
        hooks_cleared = sum(len(b._forward_pre_hooks) for b in net.transformer_blocks)
        for b in net.transformer_blocks:
            b._forward_pre_hooks.clear()

        class _DiTStep(nn.Module):
            def __init__(self2):
                super().__init__()
                self2.net = net
                self2.hint = colorizer.hint
                self2.proj = colorizer.proj

            def forward(self2, z_t, z_bw, bw, t, tokens):
                f = self2.hint(bw)
                feat = f.flatten(2).transpose(1, 2)
                net2 = self2.net
                hidden = torch.cat([z_t, z_bw, f], 1)
                n, _, hh, ww = hidden.shape
                temb, embedded_timestep = net2.time_embed(t, batch_size=n, hidden_dtype=hidden.dtype)
                hidden = net2.patch_embed(hidden)
                enc = net2.caption_projection(tokens)
                enc = enc.view(n, -1, hidden.shape[-1])
                enc = net2.caption_norm(enc)
                for i, block in enumerate(net2.transformer_blocks):
                    hidden = block(hidden + self2.proj[i](feat), None, enc, None, temb, hh, ww)
                hidden = net2.norm_out(hidden, embedded_timestep, net2.scale_shift_table)
                hidden = net2.proj_out(hidden)
                hidden = hidden.reshape(n, hh, ww, 1, 1, -1)
                hidden = hidden.permute(0, 5, 1, 3, 2, 4)
                return hidden.reshape(n, -1, hh, ww)

        step = _DiTStep()
        step.eval().requires_grad_(False)
        step.hooks_cleared = hooks_cleared
        return step


class VaeEnc(nn.Module):
    """Bakes vae.encode(x).latent * scaling_factor (spec Export 2)."""

    def __init__(self, vae):
        super().__init__()
        self.vae = vae

    def forward(self, x):
        return self.vae.encode(x).latent * self.vae.config.scaling_factor


class VaeDec(nn.Module):
    """Bakes vae.decode(z / scaling_factor).sample -> (x/2+0.5).clamp(0,1)."""

    def __init__(self, vae):
        super().__init__()
        self.vae = vae

    def forward(self, z):
        img = self.vae.decode(z / self.vae.config.scaling_factor).sample
        return (img / 2 + 0.5).clamp(0, 1)


def look_tokens(colorizer, ver):
    """The Kotlin-side lookup that replaces the in-graph embedding (v2): one
    256-d look vector per row, NULL rows forced to the zero vector."""
    import torch
    with torch.no_grad():
        tok = colorizer.token(ver.clamp(max=LOOK_NORMAL)) * (ver != LOOK_NULL)[:, None]
        return tok[:, None]


def probe_aiet_api(out_json):
    """Dump the installed converter API surface so exports are reproducible."""
    import inspect

    info = {}
    try:
        import litert_torch
        info["litert_torch_version"] = getattr(litert_torch, "__version__", "unknown")
        info["convert_signature"] = str(inspect.signature(litert_torch.convert))
        from litert_torch.quantize import quant_config as qcfg
        info["quant_config_fields"] = list(getattr(qcfg.QuantConfig, "__dataclass_fields__", {}).keys())
        from litert_torch.quantize import translate_recipe
        info["translate_recipe_fns"] = [n for n in dir(translate_recipe) if not n.startswith("_")]
        try:
            info["quantize_model_signature"] = str(inspect.signature(translate_recipe.quantize_model))
            info["translate_to_ai_edge_recipe_signature"] = str(inspect.signature(translate_recipe.translate_to_ai_edge_recipe))
        except Exception as e:
            info["translate_recipe_sig_error"] = repr(e)
        from litert_torch.quantize import pt2e_quantizer as pt2eq
        info["pt2e_config_fns"] = [n for n in dir(pt2eq) if n.startswith("get_")]
        try:
            info["sym_quant_cfg_signature"] = str(inspect.signature(pt2eq.get_symmetric_quantization_config))
        except Exception as e:
            info["pt2e_sig_error"] = repr(e)
    except Exception as e:
        info["litert_torch_error"] = repr(e)
    try:
        import ai_edge_litert
        info["ai_edge_litert_version"] = getattr(ai_edge_litert, "__version__", "unknown")
    except Exception as e:
        info["ai_edge_litert_error"] = repr(e)
    try:
        import torchao
        info["torchao_version"] = getattr(torchao, "__version__", "unknown")
    except Exception as e:
        info["torchao_error"] = repr(e)
    write_json(out_json, info)
    return info


def fp16_recipe():
    """Installed LiteRT Torch recipe: fp32 activations and fp16 weights."""
    from litert_torch.generative.quantize.quant_recipes import full_fp16_recipe
    return full_fp16_recipe()


def convert_and_export(model, sample_inputs, out_path, notes, quant_config=None, lightweight=True):
    """litert_torch.convert + optional quantization + .export, with records.

    sample_inputs may be a tuple (positional signature) or dict (semantic names
    preserved as the serving_default signature required by CompiledModel).
    """
    import litert_torch
    rec = {"out_path": out_path, "notes": notes, "quant_config": quant_config is not None, "lightweight": lightweight}
    t0 = time.time()
    kwargs = {"lightweight_conversion": lightweight}
    if quant_config is not None:
        kwargs["quant_config"] = quant_config
    if isinstance(sample_inputs, dict):
        edge = litert_torch.convert(model, sample_kwargs=sample_inputs, **kwargs)
    else:
        edge = litert_torch.convert(model, tuple(sample_inputs), **kwargs)
    rec["convert_seconds"] = round(time.time() - t0, 1)
    t0 = time.time()
    edge.export(out_path)
    rec["export_seconds"] = round(time.time() - t0, 1)
    rec["size_bytes"] = os.path.getsize(out_path)
    log("exported %s (%d bytes, convert %.1fs, export %.1fs)" % (out_path, rec["size_bytes"], rec["convert_seconds"], rec["export_seconds"]))
    return rec


def interpreter_run(tflite_path, input_arrays):
    """Run one graph through the LiteRT CPU interpreter; returns np outputs.

    input_arrays: list of np.float32 arrays in input index order, or a
    {input_name: array} dict bound by name (order-independent, name match
    ignores a trailing ":N" index on the interpreter's tensor names).
    """
    import numpy as np
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        from tensorflow.lite.python.interpreter import Interpreter
    interp = Interpreter(model_path=tflite_path, num_threads=4)
    interp.allocate_tensors()
    details_in = interp.get_input_details()
    if isinstance(input_arrays, dict):
        pairs = []
        for d in details_in:
            base = d["name"].split(":")[0].removeprefix("serving_default_")
            if base in input_arrays:
                pairs.append((d, input_arrays[base]))
            elif d["name"] in input_arrays:
                pairs.append((d, input_arrays[d["name"]]))
            else:
                raise KeyError("graph input %r missing from dict %r" % (d["name"], sorted(input_arrays)))
    else:
        pairs = list(zip(details_in, input_arrays))
    for d, arr in pairs:
        a = np.ascontiguousarray(arr.astype(np.float32))
        if list(a.shape) != d["shape"]:
            interp.resize_tensor_input(d["index"], list(a.shape))
        interp.set_tensor(d["index"], a)
    interp.invoke()
    outs = [interp.get_tensor(d["index"]).copy() for d in interp.get_output_details()]
    return outs


def interpreter_signature(tflite_path):
    """Per-graph I/O signature dump (names, shapes, dtypes)."""
    import numpy as np

    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        from tensorflow.lite.python.interpreter import Interpreter
    interp = Interpreter(model_path=tflite_path, num_threads=1)
    interp.allocate_tensors()
    signature_list = interp.get_signature_list()
    signature_name = "serving_default" if "serving_default" in signature_list else next(iter(signature_list), None)
    signature = signature_list.get(signature_name, {}) if signature_name else {}
    sig = {"path": tflite_path, "size_bytes": os.path.getsize(tflite_path),
           "inputs": [], "outputs": [], "signatures": signature_list}

    for key, details in (("inputs", interp.get_input_details()), ("outputs", interp.get_output_details())):
        semantic_names = signature.get(key, [])
        for index, detail in enumerate(details):
            raw_name = detail["name"].split(":")[0]
            fallback_name = raw_name.removeprefix(signature_name + "_") if signature_name else raw_name
            if fallback_name in semantic_names:
                name = fallback_name
            elif index < len(semantic_names):
                name = semantic_names[index]
            else:
                name = raw_name
            sig[key].append({
                "name": name,
                "shape": [int(dim) for dim in detail["shape"]],
                "dtype": np.dtype(detail["dtype"]).name,
            })
    return sig
