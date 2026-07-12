"""Structured logging foundation for the companion server.

Every failure in the pipeline is routed through ``log_failure`` so it is
recorded with full context (chapter, page, stage, box, exception traceback)
and surfaced honestly in page results as a structured failure entry.

No failure is ever swallowed silently. A bare ``except`` without logging is
a bug.

Design:
  - Root logger configured once (idempotent under re-import).
  - Console handler (stderr) + rotating file handler under ``data/logs/``.
  - Structured ``LogRecord`` format includes timestamp, level and message;
    chapter/page/stage context is carried inside the message itself so the
    flat text log stays greppable.
"""
from __future__ import annotations

import logging
import os
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
LOG_DIR = ROOT / "data" / "logs"

_FORMAT = "%(asctime)s | %(levelname)-7s | %(name)s | %(message)s"
_DATEFMT = "%Y-%m-%dT%H:%M:%S"

_configured = False


def configure_logging(level: int | str | None = None) -> logging.Logger:
    """Configure the root logger. Safe to call repeatedly.

    On a fresh environment, defaults to ``INFO`` (or ``MANGA_LOG_LEVEL``).
    The rotating file handler is created lazily and only when the log dir is
    writable; if it is not, that failure is itself logged to the console
    handler (we never silently drop logs).
    """
    global _configured
    root = logging.getLogger()

    if level is None:
        level = os.environ.get("MANGA_LOG_LEVEL", "INFO")
    if isinstance(level, str):
        level = getattr(logging, level.upper(), logging.INFO)
    root.setLevel(level)

    if _configured:
        return root

    formatter = logging.Formatter(_FORMAT, datefmt=_DATEFMT)

    console = logging.StreamHandler(stream=sys.stderr)
    console.setFormatter(formatter)
    console.setLevel(level)
    root.addHandler(console)

    try:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(
            LOG_DIR / "server.log",
            maxBytes=2 * 1024 * 1024,
            backupCount=5,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        file_handler.setLevel(level)
        root.addHandler(file_handler)
    except OSError as exc:
        # The one place we tolerate a failure: file logging is best-effort.
        # Console logging still works, and we record WHY the file handler
        # could not be created rather than hiding it.
        root.error("file-log-handler unavailable: %r", exc)

    _configured = True
    root.info("logging configured (level=%s, file=%s)", logging.getLevelName(level), LOG_DIR)
    return root


def log_failure(
    stage: str,
    reason: str,
    *,
    chapter_id: str | None = None,
    page: int | None = None,
    box: dict[str, int] | tuple[int, int, int, int] | None = None,
    exc: BaseException | None = None,
) -> dict[str, Any]:
    """Record a failure and return a structured entry for the page result.

    ``stage``    — dotted stage id, e.g. ``"inpaint/neural"``, ``"translate"``.
    ``reason``   — short human-readable cause.
    ``box``      — the affected bbox (label 2 free-text box, etc.), if any.
    ``exc``      — the captured exception; its traceback is attached.

    Returns a dict suitable for embedding in ``result["failures"]`` so the
    UI can render a banner per failure.
    """
    logger = logging.getLogger("manga.failure")
    parts: list[str] = [f"stage={stage}", f"reason={reason}"]
    if chapter_id is not None:
        parts.append(f"chapter={chapter_id}")
    if page is not None:
        parts.append(f"page={page}")
    box_serialized = _serialize_box(box)
    if box_serialized is not None:
        parts.append(f"box={box_serialized}")
    message = " | ".join(parts)
    logger.error(message, exc_info=exc)

    entry: dict[str, Any] = {"stage": stage, "reason": reason}
    if box_serialized is not None:
        entry["box"] = box_serialized
    if chapter_id is not None:
        entry["chapter"] = chapter_id
    if page is not None:
        entry["page"] = page
    if exc is not None:
        entry["exception"] = f"{type(exc).__name__}: {exc}"
    return entry


def _serialize_box(box: Any) -> dict[str, int] | None:
    if box is None:
        return None
    if isinstance(box, dict):
        return {k: int(v) for k, v in box.items()}
    if isinstance(box, (tuple, list)) and len(box) >= 4:
        return {"x1": int(box[0]), "y1": int(box[1]), "x2": int(box[2]), "y2": int(box[3])}
    return None
