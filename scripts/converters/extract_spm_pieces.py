#!/usr/bin/env python3
"""Extract the piece table of a SentencePiece model into the TSV the app loads.

Parses the sentencepiece ModelProto wire format with a minimal reader (no
sentencepiece dependency) and writes one line per piece id: `piece<TAB>type`.
Type values follow the SentencePieceProto enum (1 NORMAL, 2 UNKNOWN, 3 CONTROL,
4 USER_DEFINED, 5 UNUSED, 6 BYTE); the app decodes by concatenating
NORMAL/USER_DEFINED surfaces, raw bytes for BYTE pieces, and nothing for
CONTROL pieces.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


PIECE_TYPE_NORMAL = 1
PIECE_TYPE_UNKNOWN = 2
PIECE_TYPE_CONTROL = 3
PIECE_TYPE_USER_DEFINED = 4
PIECE_TYPE_UNUSED = 5
PIECE_TYPE_BYTE = 6


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise ValueError("truncated varint")
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


def parse_pieces(data: bytes) -> list[tuple[str, int]]:
    """Return [(piece, type)] in file order; field 1 of ModelProto is `pieces`."""
    pieces: list[tuple[str, int]] = []
    offset = 0
    while offset < len(data):
        tag, offset = read_varint(data, offset)
        field_number, wire_type = tag >> 3, tag & 0x7
        if field_number == 1 and wire_type == 2:
            length, offset = read_varint(data, offset)
            pieces.append(parse_sentence_piece(data[offset : offset + length]))
            offset += length
        elif wire_type == 2:
            # trainer_spec / normalizer_spec and friends: length-delimited, skipped.
            length, offset = read_varint(data, offset)
            offset += length
        elif wire_type == 0:
            _, offset = read_varint(data, offset)
        elif wire_type == 5:
            offset += 4
        elif wire_type == 1:
            offset += 8
        else:
            raise ValueError(f"unsupported wire type {wire_type} at field {field_number}")
    return pieces


def parse_sentence_piece(data: bytes) -> tuple[str, int]:
    piece = ""
    piece_type = PIECE_TYPE_NORMAL
    offset = 0
    while offset < len(data):
        tag, offset = read_varint(data, offset)
        field_number, wire_type = tag >> 3, tag & 0x7
        if field_number == 1 and wire_type == 2:
            length, offset = read_varint(data, offset)
            piece = data[offset : offset + length].decode("utf-8")
            offset += length
        elif field_number == 2 and wire_type == 5:
            offset += 4
        elif field_number == 3 and wire_type == 0:
            piece_type, offset = read_varint(data, offset)
        elif wire_type == 0:
            _, offset = read_varint(data, offset)
        elif wire_type == 5:
            offset += 4
        elif wire_type == 1:
            offset += 8
        else:
            raise ValueError(f"unsupported wire type {wire_type} at field {field_number}")
    return piece, piece_type


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="path to a .model SentencePiece proto")
    parser.add_argument("--output", type=Path, required=True, help="path of the TSV to write")
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    pieces = parse_pieces(arguments.source.read_bytes())

    for piece_id, (piece, piece_type) in enumerate(pieces):
        if "\t" in piece or "\n" in piece or "\r" in piece:
            print(f"ERROR: piece {piece_id} contains a TSV-control character", file=sys.stderr)
            return 1
        if piece_type not in (
            PIECE_TYPE_NORMAL,
            PIECE_TYPE_UNKNOWN,
            PIECE_TYPE_CONTROL,
            PIECE_TYPE_USER_DEFINED,
            PIECE_TYPE_BYTE,
        ):
            print(f"ERROR: piece {piece_id} has UNUSED type {piece_type}", file=sys.stderr)
            return 1

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("w", encoding="utf-8", newline="\n") as output:
        for piece, piece_type in pieces:
            output.write(f"{piece}\t{piece_type}\n")

    print(f"Wrote {len(pieces)} pieces to {arguments.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
