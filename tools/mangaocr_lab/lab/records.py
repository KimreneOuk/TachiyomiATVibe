"""Record dataclasses — the single source of schema truth."""
from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field


@dataclass
class CropRecord:
    crop_id: str
    category: str
    path: str
    decoder: str                    # reference | android-legacy | batched
    batch_size: int = 1
    text: str = ""
    text_raw: str = ""
    token_ids: list[int] = field(default_factory=list)
    token_count: int = 0
    eos_emitted: bool = False
    eos_position: int | None = None
    position_limit_reached: bool = False
    skipped_empty: bool = False
    confidence_mean: float | None = None
    confidence_min: float | None = None
    timings_ms: dict = field(default_factory=lambda: {
        "preprocess": 0.0, "encoder": 0.0, "decoder_init": 0.0,
        "decoder_step_total": 0.0, "total": 0.0,
    })
    decoder_step_calls: int = 0
    expected: str | None = None

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)


@dataclass
class MismatchRecord:
    crop_id: str
    category: str
    batch_size: int
    text_match: bool
    token_match: bool
    first_mismatch: dict | None      # {index, reference, batched, ref_text, batched_text}
    reference_tokens: list[int]
    batched_tokens: list[int]
    reference_text: str
    batched_text: str

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)


@dataclass
class BatchRunRecord:
    batch_size: int
    decoder: str = "batched"
    roi_count: int = 0
    microbatch_count: int = 0
    session_runs: dict = field(default_factory=dict)
    wall_time_ms: dict = field(default_factory=dict)
    peak_process_rss_mb: float | None = None
    token_stats: dict = field(default_factory=dict)
    vs_reference: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)


@dataclass
class NumericCheck:
    crop_id: str
    batch_size: int
    encoder: dict                    # {max_abs, mean_abs}
    init_logits: dict
    step_logits: dict                # includes steps_compared
    note: str = ""

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)
