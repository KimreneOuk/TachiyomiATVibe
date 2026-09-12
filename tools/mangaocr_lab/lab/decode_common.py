"""Token/text/EOS/position policy — the constants and pure logic the graph dictates.

Evidence: T927 evidence/onnx-graph-findings.md
  KV layout [layers=4, batch, heads=4, seq=256, head_dim=64]
  cache write slot = position_ids - 1 (in-graph blend verified)
  causal mask = arange(256) <= pos-1, fill -10000 (broadcasts over batch)
  BOS = id 2 at position 1; EOS = id 3; position table has 128 rows
"""
from __future__ import annotations

import re

BOS = 2
EOS = 3
VOCAB_SIZE = 9415
POSITION_LIMIT = 128          # decoder.pos.weight rows; position_ids >= 128 is illegal
CACHE_WINDOW = 256            # self KV-cache sequence dim
ENC_TOKENS = 196              # encoder output tokens (14x14)
HIDDEN = 256
NUM_LAYERS = 4
NUM_HEADS = 4
HEAD_DIM = 64

# Highest position_ids value that may be FED to decoder_step.
MAX_FED_POSITION = POSITION_LIMIT - 1   # 127


def token_text(vocab: list[str], token_id: int) -> str:
    return vocab[token_id] if 0 <= token_id < len(vocab) else f"<{token_id}>"


def raw_text(vocab: list[str], tokens: list[int]) -> str:
    return "".join(vocab[t] for t in tokens if 0 <= t < len(vocab))


def android_postprocess(text: str) -> str:
    """MangaOcrEngine.postprocess (kt:420-430), verbatim behavior."""
    result = re.sub(r"\s", "", text)
    result = result.replace("…", "...")
    result = re.sub(r"[・.]{2,}", lambda m: "." * len(m.group(0)), result)
    result = re.sub(r"([A-Za-z])0([A-Za-z])", r"\1o\2", result)
    result = re.sub(r"N[0°º˚⁰]", "", result)
    result = result.replace("№", "")
    return result


def softmax_max(logits) -> float:
    """Max softmax probability for one logits row (confidence proxy)."""
    import numpy as np
    x = np.asarray(logits, dtype=np.float32)
    x = x - x.max()
    e = np.exp(x)
    return float(e.max() / e.sum())


def top_k(logits, vocab: list[str], k: int = 5) -> list[dict]:
    import numpy as np
    x = np.asarray(logits, dtype=np.float32)
    x = x - x.max()
    e = np.exp(x)
    p = e / e.sum()
    idx = np.argsort(p)[::-1][:k]
    return [{"id": int(i), "text": token_text(vocab, int(i)), "p": float(p[i])} for i in idx]
