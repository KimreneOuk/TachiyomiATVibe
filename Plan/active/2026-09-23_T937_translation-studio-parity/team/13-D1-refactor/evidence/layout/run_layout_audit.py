"""Run the inherited five-viewport audit against a hermetic cache fixture."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import urlopen


HERE = Path(__file__).resolve().parent
REPO = next(parent for parent in HERE.parents
            if (parent / "tools" / "translation_studio").is_dir())
STUDIO = REPO / "tools" / "translation_studio"
SMOKE = (REPO / "Plan" / "active" / "2026-09-23_T937_translation-studio-parity"
         / "team" / "08-B2-visualization" / "evidence" / "smoke_b2.py")
AUDIT = HERE / "audit_playwright.py"
URL = "http://127.0.0.1:8765"


def load_fixture_builder():
    spec = importlib.util.spec_from_file_location("t937_b2_fixture", SMOKE)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load fixture builder at {SMOKE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module._make_fixture


def wait_for_fixture(chapter: Path, process: subprocess.Popen, log_path: Path) -> None:
    expected = str(chapter.resolve()).casefold()
    for _ in range(80):
        if process.poll() is not None:
            raise RuntimeError(f"Studio server exited; see {log_path}")
        try:
            with urlopen(URL + "/api/state", timeout=1) as response:
                state = json.loads(response.read())
            if str(Path(state.get("chapter", "")).resolve()).casefold() != expected:
                raise RuntimeError("Port 8765 responded with a different chapter; audit stopped safely")
            return
        except RuntimeError:
            raise
        except Exception:
            time.sleep(0.2)
    raise TimeoutError(f"Studio server did not become ready; see {log_path}")


def main() -> int:
    make_fixture = load_fixture_builder()
    log_path = HERE / "server.log"
    with tempfile.TemporaryDirectory(prefix="t937-d1-layout-") as temp_root:
        chapter = Path(temp_root) / "chapter"
        width, height = make_fixture(chapter)
        with log_path.open("w", encoding="utf-8") as log:
            server = subprocess.Popen(
                [sys.executable, "-u", "studio.py", "--chapter", str(chapter), "--no-browser"],
                cwd=STUDIO, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                wait_for_fixture(chapter, server, log_path)
                print(f"Fixture: {chapter} ({width}x{height}, synthetic cached pipeline artifacts)")
                print(f"Server: {URL}")
                return subprocess.run([sys.executable, str(AUDIT)], cwd=REPO, check=False).returncode
            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
