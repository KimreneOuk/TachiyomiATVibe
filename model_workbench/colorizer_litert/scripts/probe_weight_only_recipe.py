import os
import tempfile

import torch
import torch.nn as nn
import litert_torch
from ai_edge_litert.interpreter import Interpreter
from litert_torch.generative.quantize.quant_recipes import full_weight_only_recipe
from litert_torch.quantize import translate_recipe


class Tiny(nn.Module):
    def __init__(self):
        super().__init__()
        self.conv = nn.Conv2d(3, 4, 3, padding=1)

    def forward(self, x):
        return self.conv(x)


def main():
    model = Tiny().eval()
    sample = torch.randn(1, 3, 16, 16)
    edge = litert_torch.convert(model, (sample,), lightweight_conversion=True)
    recipe = full_weight_only_recipe()
    ai_recipe = translate_recipe.translate_to_ai_edge_recipe(recipe.generative_recipe)

    with tempfile.TemporaryDirectory() as tmp:
        fp32_path = os.path.join(tmp, "fp32.tflite")
        int8_path = os.path.join(tmp, "int8.tflite")
        edge.export(fp32_path)
        payload = translate_recipe.quantize_model(open(fp32_path, "rb").read(), ai_recipe)
        with open(int8_path, "wb") as handle:
            handle.write(payload)
        interpreter = Interpreter(model_path=int8_path)
        interpreter.allocate_tensors()
        inputs = interpreter.get_input_details()
        outputs = interpreter.get_output_details()
        print(
            "TINY_WEIGHT_ONLY_OK",
            os.path.getsize(fp32_path),
            os.path.getsize(int8_path),
            str(inputs[0]["dtype"]),
            str(outputs[0]["dtype"]),
        )


if __name__ == "__main__":
    main()
