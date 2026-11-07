"""Apply LiteRT Torch's INT8 weight-only recipe to an existing .tflite.

Preserves FP32 graph inputs/outputs and activation tensors. Intended for
post-conversion quantization of Sana flatbuffers; does not load PyTorch weights.
"""

import argparse
import hashlib
import json
from pathlib import Path

from litert_torch.generative.quantize.quant_recipes import full_weight_only_recipe
from litert_torch.quantize import translate_recipe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    source = args.source.read_bytes()
    recipe = full_weight_only_recipe()
    translated = translate_recipe.translate_to_ai_edge_recipe(recipe.generative_recipe)
    output = translate_recipe.quantize_model(source, translated)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(output)

    report = {
        "source": str(args.source),
        "source_size_bytes": len(source),
        "source_sha256": hashlib.sha256(source).hexdigest(),
        "output": str(args.output),
        "output_size_bytes": len(output),
        "output_sha256": hashlib.sha256(output).hexdigest(),
        "recipe": "LiteRT generative weight-only INT8 channelwise; FP32 activations",
        "quantization_rules": translated,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("WEIGHT_ONLY_INT8_DONE", len(source), len(output), report["output_sha256"])


if __name__ == "__main__":
    main()
