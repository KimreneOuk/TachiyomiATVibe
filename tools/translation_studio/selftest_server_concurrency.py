"""Exercise browser-boot artifact reads against page fingerprint invalidation."""
from __future__ import annotations

import json
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from PIL import Image

import pipeline
import server


REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_PATH = (REPO_ROOT / "Plan/active/2026-09-23_T937_translation-studio-parity"
                 / "team/10-B1-provenance/evidence/server_concurrency_selftest.json")
ROUNDS = 12


def _request(url: str, method: str = "GET", payload: dict | None = None) -> int:
    data = (json.dumps(payload).encode("utf-8") if payload is not None else None)
    request = Request(url, data=data, method=method,
                      headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=10) as response:
            response.read()
            return response.status
    except HTTPError as exc:
        exc.read()
        return exc.code


def _write_fixture(chapter: Path) -> Path:
    page = "p001.png"
    Image.new("RGB", (32, 24), (120, 80, 150)).save(chapter / page)
    studio = chapter / ".studio"
    (studio / "inpaint").mkdir(parents=True)
    (studio / "detections.json").write_text(json.dumps({
        page: {"page_fingerprint": "stale-page-fingerprint", "boxes": []},
    }), encoding="utf-8")
    (studio / "ocr.json").write_text(json.dumps({}), encoding="utf-8")
    (studio / "translations.json").write_text(json.dumps({}), encoding="utf-8")
    (studio / "inpaint" / "p001.json").write_text(json.dumps({
        "page_fingerprint": "stale-page-fingerprint",
        "cache_key": {"page_fingerprint": "stale-page-fingerprint"},
    }), encoding="utf-8")
    (studio / "inpaint" / "p001.png").write_bytes(b"inpaint-cache")
    return chapter


def _run_round(index: int) -> dict:
    with tempfile.TemporaryDirectory(prefix=f"t937-boot-race-{index}-") as temp:
        chapter = _write_fixture(Path(temp))
        pipe = pipeline.Pipeline()
        pipe.record_recent = lambda *_args, **_kwargs: []
        pipe.open_folders(str(chapter), None)

        old_pipeline = server.PIPELINE
        old_reader = server._read_artifact_source
        old_get = server.Handler.do_GET
        old_post = server.Handler.do_POST
        inpaint_reader_open = threading.Event()
        release_reader = threading.Event()
        page_handler_started = threading.Event()
        settings_handler_started = threading.Event()
        reader_path = chapter / ".studio" / "inpaint" / "p001.json"

        def held_reader(path, display_path, page=None):
            if path == reader_path:
                # Keep a real Windows file handle open while invalidation would
                # otherwise attempt to unlink this cache entry.
                with path.open("rb"):
                    inpaint_reader_open.set()
                    if not release_reader.wait(timeout=5):
                        raise TimeoutError("test did not release the held reader")
            return old_reader(path, display_path, page)

        def observed_get(handler):
            if handler.path.startswith("/api/page?"):
                page_handler_started.set()
            return old_get(handler)

        def observed_post(handler):
            if handler.path == "/api/settings":
                settings_handler_started.set()
            return old_post(handler)

        server.PIPELINE = pipe
        server._read_artifact_source = held_reader
        server.Handler.do_GET = observed_get
        server.Handler.do_POST = observed_post
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        httpd.daemon_threads = True
        worker = threading.Thread(target=httpd.serve_forever, daemon=True)
        worker.start()
        base = f"http://127.0.0.1:{httpd.server_port}"
        page_url = base + "/api/page?" + urlencode({"p": "p001.png"})
        try:
            with ThreadPoolExecutor(max_workers=3) as pool:
                artifact = pool.submit(_request, base + "/api/artifacts?" +
                                       urlencode({"p": "p001.png"}))
                assert inpaint_reader_open.wait(timeout=5), "artifact reader did not reach inpaint JSON"
                page = pool.submit(_request, page_url)
                settings = pool.submit(_request, base + "/api/settings", "POST",
                                       {"filters": {"confidence": 0.6}})
                assert page_handler_started.wait(timeout=5), "page GET did not start"
                assert settings_handler_started.wait(timeout=5), "settings POST did not start"
                time.sleep(0.04)
                assert not page.done(), "page operation did not wait for the held artifact read"
                assert not settings.done(), "settings POST did not wait for the held artifact read"
                release_reader.set()
                statuses = {"artifacts": artifact.result(timeout=10),
                            "page": page.result(timeout=10),
                            "settings": settings.result(timeout=10)}
            assert all(status == 200 for status in statuses.values()), statuses
            assert not reader_path.exists(), "changed-page invalidation should remove stale provenance"
            return {"round": index, "statuses": statuses,
                    "reader_and_page_overlap": True,
                    "reader_and_settings_overlap": True,
                    "invalidation_removed_stale_json": True}
        finally:
            release_reader.set()
            httpd.shutdown()
            httpd.server_close()
            worker.join(timeout=5)
            server.PIPELINE = old_pipeline
            server._read_artifact_source = old_reader
            server.Handler.do_GET = old_get
            server.Handler.do_POST = old_post


def main() -> None:
    rounds = [_run_round(i + 1) for i in range(ROUNDS)]
    evidence = {
        "status": "ok",
        "pattern": "browser boot: GET /api/artifacts overlaps POST /api/settings and GET /api/page",
        "race_seed": "held .studio/inpaint/p001.json handle until both concurrent handlers start",
        "rounds": ROUNDS,
        "all_statuses_200": all(
            status == 200 for row in rounds for status in row["statuses"].values()),
        "round_results": rounds,
    }
    EVIDENCE_PATH.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE_PATH.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps(evidence, indent=2), flush=True)


if __name__ == "__main__":
    main()
