import json
import os
import tempfile

import torch
import torch.nn as nn
from ai_edge_litert.interpreter import Interpreter
import litert_torch


class TinyNamed(nn.Module):
    def forward(self, z_t, z_bw, bw, t, tokens):
        return z_t + z_bw + bw[:, :1, :1, :1] + t[:, None, None, None] + tokens[:, :1, :1].reshape(2, 1, 1, 1)


def main():
    args = {
        "z_t": torch.zeros(2, 32, 24, 16),
        "z_bw": torch.zeros(2, 32, 24, 16),
        "bw": torch.zeros(2, 3, 768, 512),
        "t": torch.zeros(2),
        "tokens": torch.zeros(2, 1, 256),
    }
    edge = litert_torch.convert(TinyNamed().eval(), sample_kwargs=args, lightweight_conversion=True)
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "named.tflite")
        edge.export(path)
        interp = Interpreter(model_path=path)
        interp.allocate_tensors()
        print(json.dumps({
            "inputs": [{"name": d["name"], "shape": [int(x) for x in d["shape"]]} for d in interp.get_input_details()],
            "outputs": [{"name": d["name"], "shape": [int(x) for x in d["shape"]]} for d in interp.get_output_details()],
            "signatures": interp.get_signature_list(),
        }, indent=2))


if __name__ == "__main__":
    main()
