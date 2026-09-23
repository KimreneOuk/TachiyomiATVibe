"""Run the requested Gate 1 suite while keeping outputs in this evidence bundle."""
from __future__ import annotations

import contextlib
import importlib
import io
import json
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = Path(__file__).resolve().parents[6]
STUDIO = REPO / "tools" / "translation_studio"
sys.path.insert(0, str(STUDIO))

import pipeline  # noqa: E402

pipeline.RECENT_FILE = HERE / "verification_recent.json"


def run_call(name: str, call) -> dict:
    output = io.StringIO()
    started = time.perf_counter()
    try:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            call()
        result = {"name": name, "status": "passed"}
    except Exception as error:
        result = {"name": name, "status": "failed",
                  "error": f"{type(error).__name__}: {error}"}
        raise
    finally:
        result["elapsed_seconds"] = round(time.perf_counter() - started, 3)
        result["output"] = output.getvalue()
    return result


def run_unittest_module(name: str) -> None:
    module = importlib.import_module(name)
    suite = unittest.defaultTestLoader.loadTestsFromModule(module)
    stream = io.StringIO()
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise AssertionError(stream.getvalue())


def main() -> None:
    HERE.mkdir(parents=True, exist_ok=True)
    results = []

    pycompile = [
        "tools/translation_studio/pipeline.py",
        "tools/translation_studio/server.py",
        "tools/translation_studio/detection_artifacts.py",
        "tools/translation_studio/inpaint_android.py",
        "tools/translation_studio/translation_providers.py",
    ]

    def compile_modules() -> None:
        completed = subprocess.run(
            [sys.executable, "-m", "py_compile", *pycompile], cwd=REPO,
            text=True, capture_output=True, check=False)
        if completed.returncode:
            raise RuntimeError(completed.stdout + completed.stderr)

    results.append(run_call("py_compile", compile_modules))

    cache_test = importlib.import_module("selftest_cache_fingerprints")
    cache_test.EVIDENCE_PATH = HERE / "cache_fingerprints.json"
    results.append(run_call("selftest_cache_fingerprints.py", cache_test.main))

    mask_test = importlib.import_module("selftest_mask_planner")
    mask_test.EVIDENCE_PATH = HERE / "mask_planner.json"
    results.append(run_call("selftest_mask_planner.py", mask_test.main))

    results.append(run_call(
        "selftest_ocr.py",
        lambda: run_unittest_module("selftest_ocr")))
    results.append(run_call(
        "test_translation_providers.py",
        lambda: run_unittest_module("test_translation_providers")))

    inpaint_test = importlib.import_module("selftest_inpaint")
    inpaint_test.EVIDENCE = HERE / "inpaint_selftest"
    inpaint_test.EVIDENCE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="t937-gate1-inpaint-",
                                     dir=HERE) as temp:
        demo_copy = Path(temp) / "demo_chapter"
        demo_copy.mkdir()
        for source in (STUDIO / "demo_chapter").glob("*.jpg"):
            shutil.copy2(source, demo_copy / source.name)
        inpaint_test.DEMO_CHAPTER = demo_copy
        results.append(run_call("selftest_inpaint.py", inpaint_test.main))

    summary = {"commit": subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPO, text=True,
        capture_output=True, check=True).stdout.strip(),
        "results": results}
    (HERE / "verification_suite.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    lines = [f"Gate 1 verification suite on {summary['commit']}"]
    for result in results:
        lines.append(f"{result['name']}: {result['status']} "
                     f"({result['elapsed_seconds']} s)")
    (HERE / "verification_suite.txt").write_text(
        "\n".join(lines) + "\n", encoding="utf-8")
    print((HERE / "verification_suite.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
