"""Microbatch decode state machine — PURE LOGIC, the module that ports to Kotlin.

LOCKSTEP CONSTRAINT (evidence/onnx-graph-findings.md §Batch position semantics):
decoder_step extracts the position as a SCALAR from row 0
(`Gather(Clip(position_ids-1), [0])`, twice), so position is a property of the
whole microbatch, not of a row:

  * every row is fed the SAME position_ids value (a microbatch scalar)
  * all rows advance together, one position per step call
  * finished rows stay in place (strategy A) and are fed EOS as filler;
    their outputs are discarded
  * the whole microbatch stops at position 127 — a row still active there
    takes POSITION_LIMIT, mirroring the B=1 ceiling

KV write rule (graph convention): the step's returned slice goes to cache
slot `position - 1` of that row's own cache.
"""
from __future__ import annotations

import numpy as np

from . import decode_common as dc

OUTCOME_ACTIVE = "ACTIVE"
OUTCOME_EOS = "EOS_AT"
OUTCOME_POSITION_LIMIT = "POSITION_LIMIT"


class RowState:
    __slots__ = ("crop_id", "tokens", "confs", "next_input", "finished",
                 "outcome", "eos_position")

    def __init__(self, crop_id: str):
        self.crop_id = crop_id
        self.tokens: list[int] = []
        self.confs: list[float] = []
        self.next_input = dc.BOS
        self.finished = False
        self.outcome = OUTCOME_ACTIVE
        self.eos_position: int | None = None

    def finish_eos(self, position: int) -> None:
        self.finished = True
        self.outcome = OUTCOME_EOS
        self.eos_position = position

    def finish_position_limit(self) -> None:
        self.finished = True
        self.outcome = OUTCOME_POSITION_LIMIT


class MicrobatchState:
    """Host-side decode state for one microbatch of B rows (no padding)."""

    def __init__(self, crop_ids: list[str]):
        b = len(crop_ids)
        self.position = 1                      # SCALAR — broadcast to all rows
        self.input_ids = np.full((b, 1), dc.BOS, dtype=np.int64)
        self.position_ids = np.full((b, 1), 2, dtype=np.int64)  # first FED position
        self.sk = np.zeros((dc.NUM_LAYERS, b, dc.NUM_HEADS, dc.CACHE_WINDOW,
                            dc.HEAD_DIM), dtype=np.float32)
        self.sv = np.zeros_like(self.sk)
        self.rows = [RowState(c) for c in crop_ids]

    def apply_init(self, logits0: np.ndarray, self_k: np.ndarray,
                   self_v: np.ndarray) -> None:
        """Consume decoder_init outputs: logits0 [B, VOCAB],
        self_k/self_v [4, B, 4, 196, 64] — only the position-0 slice is kept."""
        self.sk[:, :, :, 0, :] = self_k[:, :, :, 0, :]
        self.sv[:, :, :, 0, :] = self_v[:, :, :, 0, :]
        for b, row in enumerate(self.rows):
            t = int(np.argmax(logits0[b]))
            if t == dc.EOS:
                row.finish_eos(1)
            else:
                row.tokens.append(t)
                row.confs.append(dc.softmax_max(logits0[b]))
                row.next_input = t
        self.position = 2

    @property
    def all_finished(self) -> bool:
        return all(r.finished for r in self.rows)

    @property
    def can_step(self) -> bool:
        return not self.all_finished and self.position <= dc.MAX_FED_POSITION

    def prepare_feed(self) -> None:
        """Fill the [B,1] feeds from per-row state + the scalar position."""
        self.input_ids[:, 0] = [r.next_input for r in self.rows]
        self.position_ids[:, 0] = self.position

    def apply_step(self, logits: np.ndarray, k_slice: np.ndarray,
                   v_slice: np.ndarray) -> None:
        """Consume decoder_step outputs: logits [B, VOCAB],
        k_slice/v_slice [4, B, 4, 1, 64]. Slot = position - 1 (graph convention)."""
        p = self.position
        self.sk[:, :, :, p - 1, :] = k_slice[:, :, :, 0, :]
        self.sv[:, :, :, p - 1, :] = v_slice[:, :, :, 0, :]
        for b, row in enumerate(self.rows):
            if row.finished:
                continue                      # filler row: output discarded
            t = int(np.argmax(logits[b]))
            if t == dc.EOS:
                row.finish_eos(p)
            else:
                row.tokens.append(t)
                row.confs.append(dc.softmax_max(logits[b]))
                row.next_input = t
        self.advance()

    def advance(self) -> None:
        """LOCKSTEP: one shared position increment; finished rows freeze at EOS."""
        self.position += 1
        for row in self.rows:
            if row.finished:
                row.next_input = dc.EOS
