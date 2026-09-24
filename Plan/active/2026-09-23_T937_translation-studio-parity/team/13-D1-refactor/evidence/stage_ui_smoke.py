"""Exercise the Stage Pipeline UI without dispatching a real translation."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import urlopen

from playwright.sync_api import sync_playwright


HERE = Path(__file__).resolve().parent
REPO = next(parent for parent in HERE.parents
            if (parent / "tools" / "translation_studio").is_dir())
STUDIO = REPO / "tools" / "translation_studio"
SMOKE = (REPO / "Plan" / "active" / "2026-09-23_T937_translation-studio-parity"
         / "team" / "08-B2-visualization" / "evidence" / "smoke_b2.py")
URL = "http://127.0.0.1:8765"


def make_fixture_builder():
    spec = importlib.util.spec_from_file_location("t937_b2_fixture", SMOKE)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load fixture builder at {SMOKE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module._make_fixture


def main() -> int:
    make_fixture = make_fixture_builder()
    result = {"checks": [], "browser_errors": []}
    log_path = HERE / "stage_ui_server.log"
    screenshot_path = HERE / "stage_pipeline_translation_smoke.png"
    with tempfile.TemporaryDirectory(prefix="t937-d1-stage-ui-") as temp_root:
        chapter = Path(temp_root) / "chapter"
        make_fixture(chapter)
        with log_path.open("w", encoding="utf-8") as log:
            server = subprocess.Popen(
                [sys.executable, "-u", "studio.py", "--chapter", str(chapter), "--no-browser"],
                cwd=STUDIO, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                expected = str(chapter.resolve()).casefold()
                for _ in range(80):
                    if server.poll() is not None:
                        raise RuntimeError(f"Studio server exited; see {log_path}")
                    try:
                        with urlopen(URL + "/api/state", timeout=1) as response:
                            state = json.loads(response.read())
                        if str(Path(state.get("chapter", "")).resolve()).casefold() != expected:
                            raise RuntimeError("Port 8765 responded with a different chapter; stopped safely")
                        break
                    except RuntimeError:
                        raise
                    except Exception:
                        time.sleep(0.2)
                else:
                    raise TimeoutError(f"Studio server did not become ready; see {log_path}")

                with sync_playwright() as playwright:
                    browser = playwright.chromium.launch(headless=True)
                    page = browser.new_page(viewport={"width": 1366, "height": 768}, device_scale_factor=1)
                    page.set_default_timeout(10000)
                    page.on("pageerror", lambda error: result["browser_errors"].append(str(error)))
                    page.goto(URL, wait_until="networkidle")
                    page.wait_for_selector("#stagePipeline [data-stage=translate]")
                    page.route("**/api/translate", lambda route: route.fulfill(
                        status=200, content_type="application/json", body=json.dumps({"ok": True})))

                    button = page.locator('#stagePipeline [data-stage="translate"]')
                    button.click()
                    page.wait_for_function("document.querySelector('[data-stage-dispatches=translate]')?.textContent === '1 dispatch'")
                    page.wait_for_function("document.querySelector('[data-stage-time=translate]')?.textContent !== '—'")
                    stage_time = page.locator('[data-stage-time="translate"]').inner_text()
                    stage_status = page.locator('[data-stage-status="translate"]').inner_text()
                    assert "ms" in stage_time, stage_time
                    assert stage_status == "Complete", stage_status
                    result["checks"].append("Translate stage request updates its session dispatch count, status, and elapsed time")

                    page.locator("#btnOverview").click()
                    page.wait_for_selector("#overviewDlg[open] .ov-stage-table")
                    translate_row = page.locator(".ov-stage-table tbody tr").filter(has_text="Translate")
                    table_values = translate_row.locator("td").all_inner_texts()
                    assert table_values and "ms" in table_values[0], table_values
                    result["checks"].append("Chapter benchmark table includes the measured direct Translate request time")
                    result["stage_time"] = stage_time
                    result["stage_status"] = stage_status
                    result["stage_dispatches"] = page.locator('[data-stage-dispatches="translate"]').inner_text()
                    result["benchmark_translate_time"] = table_values[0]
                    page.screenshot(path=str(screenshot_path), full_page=True)
                    browser.close()

            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)

    result["screenshot"] = screenshot_path.relative_to(REPO).as_posix()
    (HERE / "stage_ui_smoke.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if result["browser_errors"] or len(result["checks"]) != 2:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
