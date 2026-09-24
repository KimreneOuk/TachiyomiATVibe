"""Run the seven requested suites and py_compile, retaining per-command logs."""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = next(parent for parent in HERE.parents
            if (parent / "tools" / "translation_studio").is_dir())
COMMANDS = [
    ("cache_fingerprints", ["python", "tools/translation_studio/selftest_cache_fingerprints.py"]),
    ("mask_planner", ["python", "tools/translation_studio/selftest_mask_planner.py"]),
    ("ocr", ["python", "tools/translation_studio/selftest_ocr.py"]),
    ("translation_providers", ["python", "tools/translation_studio/test_translation_providers.py"]),
    ("inpaint", ["python", "tools/translation_studio/selftest_inpaint.py"]),
    ("translation_mapping", ["python", "tools/translation_studio/selftest_translation_mapping.py"]),
    ("inpaint_variants", ["python", "tools/translation_studio/selftest_inpaint_variants.py"]),
    ("py_compile", ["python", "-m", "py_compile",
     "tools/translation_studio/inpaint_android.py",
     "tools/translation_studio/inpaint_provenance.py",
     "tools/translation_studio/pipeline.py",
     "tools/translation_studio/server.py",
     "tools/translation_studio/selftest_inpaint_variants.py"]),
]


def main() -> int:
    results = []
    for name, command in COMMANDS:
        print(f"RUN {' '.join(command)}", flush=True)
        started = time.perf_counter()
        process = subprocess.run(command, cwd=REPO, capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", check=False)
        elapsed = round(time.perf_counter() - started, 3)
        log_path = HERE / f"{name}.log"
        log_path.write_text(
            f"$ {' '.join(command)}\nexit_code={process.returncode}\nelapsed_seconds={elapsed}\n\n"
            f"--- stdout ---\n{process.stdout}\n--- stderr ---\n{process.stderr}",
            encoding="utf-8",
        )
        result = {"name": name, "command": " ".join(command),
                  "exit_code": process.returncode, "elapsed_seconds": elapsed,
                  "log": log_path.relative_to(REPO).as_posix()}
        results.append(result)
        print(f"{'PASS' if process.returncode == 0 else 'FAIL'} {name} ({elapsed}s)", flush=True)
        if process.returncode:
            print((process.stdout + "\n" + process.stderr)[-6000:], flush=True)
            break

    summary = {"results": results,
               "passed": sum(item["exit_code"] == 0 for item in results),
               "failed": sum(item["exit_code"] != 0 for item in results)}
    (HERE / "required_tests.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2), flush=True)
    return int(summary["failed"] > 0 or len(results) != len(COMMANDS))


if __name__ == "__main__":
    raise SystemExit(main())
