"""Assembles graph_signatures.json for SanaMangaColoringEngine from the
step2a/2b signature dumps (exported/signatures/{dit,vae_enc,vae_dec}_fp16.json).

The engine binds tensors by contract name and validates exact dims + FLOAT
element type, failing closed on drift — this file is that contract.

Usage: python -I assemble_sana_contract.py --signatures-dir <exported/signatures> --out <graph_signatures.json>
"""

import argparse
import json
from pathlib import Path


def tensor(spec):
    return {"name": spec["name"], "dims": list(spec["shape"])}


def graph(io):
    signature = io.get("signatures", {}).get("serving_default")
    if signature is None:
        raise SystemExit("LiteRT signature dump is missing serving_default")
    expected = {"inputs": signature.get("inputs", []), "outputs": signature.get("outputs", [])}
    graph = {}
    for direction in ("inputs", "outputs"):
        specs = io[direction]
        by_name = {spec["name"]: spec for spec in specs}
        missing = [name for name in expected[direction] if name not in by_name]
        if missing:
            raise SystemExit("semantic %s missing from tensor details: %s" % (direction, missing))
        graph[direction] = [tensor(by_name[name]) for name in expected[direction]]
    return graph


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signatures-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    arguments = parser.parse_args()

    contract = {}
    for key in ("dit", "vae_enc", "vae_dec"):
        path = arguments.signatures_dir / ("%s_fp16.json" % key)
        with open(path, encoding="utf-8") as handle:
            io = json.load(handle)
        contract[key] = graph(io)
        for spec in io["inputs"] + io["outputs"]:
            if spec["dtype"] != "float32":
                raise SystemExit("%s tensor %r dtype %s is not float32" % (key, spec["name"], spec["dtype"]))

    with open(arguments.out, "w", encoding="utf-8") as handle:
        json.dump(contract, handle, indent=2)
    print("CONTRACT %s (dit=%d in, enc=%d in, dec=%d in)" % (
        arguments.out, len(contract["dit"]["inputs"]),
        len(contract["vae_enc"]["inputs"]), len(contract["vae_dec"]["inputs"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
