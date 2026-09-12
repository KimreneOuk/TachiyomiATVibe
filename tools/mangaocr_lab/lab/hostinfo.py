"""Host environment description and peak-RSS sampling (desktop-only, req 6)."""
from __future__ import annotations

import platform
import sys
import threading
import time


def host_block() -> dict:
    import onnxruntime as ort
    cpu = platform.processor() or platform.machine()
    return {
        "python": sys.version.split()[0],
        "platform": f"{platform.system()} {platform.release()}",
        "cpu": cpu,
        "onnx": _module_version("onnx"),
        "onnxruntime": ort.__version__,
        "note": "desktop host — timings here must NOT be read as Android performance",
    }


def _module_version(name: str) -> str:
    import importlib.metadata
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return "unknown"


class RssPeak:
    """Context manager sampling this process's RSS in a 20 ms poller thread.

    Absolute process peak (not delta) — good enough to spot per-B cache
    growth order-of-magnitude style; psutil is optional at import time.
    """

    def __init__(self):
        self.peak_bytes = 0
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.available = True

    def __enter__(self) -> "RssPeak":
        try:
            import psutil  # noqa: F401
        except ImportError:
            self.available = False
            return self
        self._thread = threading.Thread(target=self._poll, daemon=True)
        self._thread.start()
        return self

    def _poll(self) -> None:
        import psutil
        proc = psutil.Process()
        while not self._stop.is_set():
            self.peak_bytes = max(self.peak_bytes, proc.memory_info().rss)
            time.sleep(0.02)

    def __exit__(self, *exc) -> None:
        if self._thread is not None:
            self._stop.set()
            self._thread.join(timeout=1.0)

    @property
    def peak_mb(self) -> float | None:
        if not self.available:
            return None
        return round(self.peak_bytes / (1 << 20), 1)
