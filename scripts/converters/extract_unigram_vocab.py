#!/usr/bin/env python3
"""Extract a HuggingFace Unigram tokenizer vocab into the TSV the app loads.

Reads a `tokenizer.json` whose model type is Unigram and writes one line per
token id: `piece<TAB>score`. The line order IS the token id order, so the app
rebuilds id->piece/score tables by line index. Byte-fallback tokens (<0xNN>)
are ordinary entries; the app applies the unk penalty to them at encode time.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="path to tokenizer.json")
    parser.add_argument("--output", type=Path, required=True, help="path of the TSV to write")
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    data = json.loads(arguments.source.read_text(encoding="utf-8"))
    model = data.get("model", {})
    if model.get("type") != "Unigram":
        print(f"ERROR: expected Unigram model, got {model.get('type')!r}", file=sys.stderr)
        return 1
    vocab = model.get("vocab")
    if not isinstance(vocab, list) or not vocab:
        print("ERROR: model.vocab must be a non-empty [[piece, score], ...] list", file=sys.stderr)
        return 1

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("w", encoding="utf-8", newline="\n") as output:
        for token_id, entry in enumerate(vocab):
            piece, score = entry[0], float(entry[1])
            if "\t" in piece or "\n" in piece or "\r" in piece:
                print(f"ERROR: token {token_id} contains a TSV-control character", file=sys.stderr)
                return 1
            output.write(f"{piece}\t{score!r}\n")

    print(f"Wrote {len(vocab)} tokens to {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
