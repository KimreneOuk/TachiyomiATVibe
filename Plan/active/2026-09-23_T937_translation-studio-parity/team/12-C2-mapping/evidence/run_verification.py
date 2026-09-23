"""Run T937 C2 + six baseline suites and B2 smoke without touching their evidence."""
from __future__ import annotations

import contextlib
import copy
import importlib
import importlib.util
import io
import json
import shutil
import sys
import tempfile
import time
import traceback
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO_ROOT = next(parent for parent in HERE.parents
                 if (parent / "tools" / "translation_studio").is_dir())
STUDIO = REPO_ROOT / "tools" / "translation_studio"
BASELINE = HERE / "baseline"
sys.path.insert(0, str(STUDIO))


def _capture(name: str, command: str, operation) -> dict:
    output = io.StringIO()
    started = time.monotonic()
    try:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            operation()
        result = {"name": name, "command": command, "status": "passed",
                  "elapsed_seconds": round(time.monotonic() - started, 3)}
    except Exception as error:
        output.write(traceback.format_exc())
        result = {"name": name, "command": command, "status": "failed",
                  "elapsed_seconds": round(time.monotonic() - started, 3),
                  "error": f"{type(error).__name__}: {error}"}
    with (HERE / "verification.log").open("a", encoding="utf-8") as stream:
        stream.write(f"\n=== {name}: {result['status']} ===\n")
        stream.write(output.getvalue())
    print(f"{name}: {result['status']} ({result['elapsed_seconds']}s)", flush=True)
    return result


def _run_unittest(module_name: str) -> None:
    module = importlib.import_module(module_name)
    output = io.StringIO()
    result = unittest.TextTestRunner(stream=output, verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromModule(module))
    print(output.getvalue(), end="")
    if not result.wasSuccessful():
        raise AssertionError(f"{module_name}: {len(result.failures)} failures, "
                             f"{len(result.errors)} errors")


def _run_inpaint() -> None:
    module = importlib.import_module("selftest_inpaint")
    with tempfile.TemporaryDirectory(prefix="t937-c2-baseline-inpaint-") as temp:
        root = Path(temp)
        chapter = root / "demo_chapter"
        chapter.mkdir()
        for source in (STUDIO / "demo_chapter").glob("*.jpg"):
            shutil.copy2(source, chapter / source.name)
        module.DEMO_CHAPTER = chapter
        module.EVIDENCE = root / "evidence"
        module.B1_EVIDENCE = root / "b1-evidence"
        module.main()
        summary = module.EVIDENCE / "selftest_summary.json"
        (BASELINE / "inpaint_summary.json").write_text(
            summary.read_text(encoding="utf-8"), encoding="utf-8")


def _run_b2_smoke() -> None:
    source = (REPO_ROOT / "Plan/active/2026-09-23_T937_translation-studio-parity"
              / "team/08-B2-visualization/evidence/smoke_b2.py")
    spec = importlib.util.spec_from_file_location("t937_b2_smoke", source)
    if spec is None or spec.loader is None:
        raise RuntimeError("could not load B2 smoke script")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.EVIDENCE = HERE / "b2_smoke"
    module.EVIDENCE.mkdir(parents=True, exist_ok=True)
    module.main()


def main() -> int:
    BASELINE.mkdir(parents=True, exist_ok=True)
    log = HERE / "verification.log"
    if log.exists():
        log.unlink()
    results = []
    os_cwd = Path.cwd()
    try:
        import os
        os.chdir(STUDIO)
        cache = importlib.import_module("selftest_cache_fingerprints")
        cache.EVIDENCE_PATH = BASELINE / "cache_fingerprints.json"
        results.append(_capture("selftest_cache_fingerprints.py",
                                "python selftest_cache_fingerprints.py (evidence redirected)",
                                cache.main))

        mask = importlib.import_module("selftest_mask_planner")
        mask.EVIDENCE_PATH = BASELINE / "mask_planner.json"
        results.append(_capture("selftest_mask_planner.py",
                                "python selftest_mask_planner.py (evidence redirected)",
                                mask.main))

        for module_name in ("selftest_ocr", "selftest_translation_mapping",
                            "test_translation_providers"):
            results.append(_capture(module_name, f"python -m unittest {module_name} -v",
                                    lambda module_name=module_name: _run_unittest(module_name)))

        results.append(_capture("selftest_inpaint.py",
                                "python selftest_inpaint.py (temporary demo/evidence)",
                                _run_inpaint))

        provenance = importlib.import_module("selftest_provenance")
        provenance.EVIDENCE_PATH = BASELINE / "provenance_selftest.json"
        results.append(_capture("selftest_provenance.py",
                                "python selftest_provenance.py (evidence redirected)",
                                provenance.main))

        concurrency = importlib.import_module("selftest_server_concurrency")
        concurrency.EVIDENCE_PATH = BASELINE / "server_concurrency_selftest.json"
        results.append(_capture("selftest_server_concurrency.py",
                                "python selftest_server_concurrency.py (evidence redirected)",
                                concurrency.main))

        results.append(_capture("B2 smoke",
                                "python Plan/active/2026-09-23_T937_translation-studio-parity/team/08-B2-visualization/evidence/smoke_b2.py (output redirected)",
                                _run_b2_smoke))
    finally:
        import os
        os.chdir(os_cwd)
    report = {"branch": "t937-c2-translation-mapping",
              "results": results,
              "all_passed": all(row["status"] == "passed" for row in results)}
    (HERE / "verification_results.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8")
    print(f"verification: {'passed' if report['all_passed'] else 'failed'}")
    return 0 if report["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
