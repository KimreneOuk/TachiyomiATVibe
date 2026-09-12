"""ONNX Runtime sessions with per-model run counting and timing."""
from __future__ import annotations

import os
import time

import onnxruntime as ort


def _android_session_options() -> ort.SessionOptions:
    """Numerically mirrors the app's session configuration (OnnxRuntimeProvider):
    intra/inter threads = (cores/2).coerceIn(2,4), sequential execution mode,
    intra-op spinning disabled. Timings still do not transfer to ARM."""
    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    threads = max(2, min(os.cpu_count() // 2, 4)) if os.cpu_count() else 2
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = threads
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return opts


def make_session_options(profile: str) -> ort.SessionOptions:
    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    if profile == "android":
        return _android_session_options()
    return opts


class CountingSession:
    """Thin wrapper: counts session.run calls and accumulates wall time."""

    def __init__(self, path, profile: str, name: str):
        self.name = name
        self.runs = 0
        self.time_ms = 0.0
        self._sess = ort.InferenceSession(str(path), make_session_options(profile),
                                          providers=["CPUExecutionProvider"])

    def run(self, feed: dict):
        t0 = time.perf_counter()
        out = self._sess.run(None, feed)
        self.time_ms += (time.perf_counter() - t0) * 1000.0
        self.runs += 1
        return out

    @property
    def input_names(self) -> list[str]:
        return [i.name for i in self._sess.get_inputs()]

    @property
    def output_names(self) -> list[str]:
        return [o.name for o in self._sess.get_outputs()]


class SessionBank:
    """encoder/decoder_init/decoder_step bundle with aggregated counters."""

    def __init__(self, paths: dict, profile: str):
        self.encoder = CountingSession(paths["encoder.onnx"], profile, "encoder")
        self.decoder_init = CountingSession(paths["decoder_init.onnx"], profile, "decoder_init")
        self.decoder_step = CountingSession(paths["decoder_step.onnx"], profile, "decoder_step")

    def counters(self) -> dict:
        return {
            "encoder": self.encoder.runs,
            "decoder_init": self.decoder_init.runs,
            "decoder_step": self.decoder_step.runs,
        }

    def times_ms(self) -> dict:
        return {
            "encoder": round(self.encoder.time_ms, 2),
            "decoder_init": round(self.decoder_init.time_ms, 2),
            "decoder_step": round(self.decoder_step.time_ms, 2),
        }
