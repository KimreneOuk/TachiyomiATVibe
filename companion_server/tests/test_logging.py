"""Tests for the logging foundation: failures produce structured records and
the helper returns an embeddable failure entry."""
from __future__ import annotations

import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from logging_config import log_failure


def test_log_failure_returns_structured_entry(caplog):
    with caplog.at_level(logging.ERROR, logger="manga.failure"):
        entry = log_failure(
            "inpaint/neural", "onnxruntime not installed",
            chapter_id="abc123", page=2, box=(10, 20, 30, 40),
        )
    assert entry["stage"] == "inpaint/neural"
    assert entry["reason"] == "onnxruntime not installed"
    assert entry["chapter"] == "abc123"
    assert entry["page"] == 2
    assert entry["box"] == {"x1": 10, "y1": 20, "x2": 30, "y2": 40}
    # The structured message is in the log record.
    record = caplog.records[-1]
    assert record.levelno == logging.ERROR
    assert "stage=inpaint/neural" in record.getMessage()
    assert "reason=onnxruntime not installed" in record.getMessage()
    assert "chapter=abc123" in record.getMessage()
    assert "page=2" in record.getMessage()


def test_log_failure_attaches_exception_traceback(caplog):
    exc = RuntimeError("boom")
    with caplog.at_level(logging.ERROR, logger="manga.failure"):
        entry = log_failure("detect", "detector crashed", exc=exc)
    assert entry["exception"] == "RuntimeError: boom"
    # exc_info is set so the traceback is logged.
    assert caplog.records[-1].exc_info is not None


def test_log_failure_box_optional():
    entry = log_failure("translate", "no blocks")
    assert "box" not in entry
