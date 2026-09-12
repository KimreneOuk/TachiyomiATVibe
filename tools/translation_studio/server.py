"""Local studio server — stdlib only, no dependencies beyond the pipeline's.

Endpoints:
  GET  /                          studio UI
  GET  /api/state                 folders, pages, settings, model status
  GET  /api/page?p=<page>         regions + translations + status for one page
  GET  /api/log                   recent log lines
  POST /api/open                  {chapter, reference?}      -> state
  POST /api/settings              {conf, max_batch, ...}     -> settings
  POST /api/detect                {page, conf?}
  POST /api/ocr                   {page}
  POST /api/render                {page}
  POST /api/process               {page, translate?}  full detect->ocr->render
  POST /api/translate             {page}              fill missing via endpoint
  POST /api/translation           {page, region_id, text}
  GET  /img/original?p= /img/goal?p= /img/render?p= /img/overlay?p=
  GET  /img/crop?p=&id=<region>
  GET  /img/thumb?p=&s=<px>        cached navigator thumbnail (UI support)
  GET  /img/ocr_input?p=&id=       exact 224x224 tensor OCR consumed
  GET  /img/render_crop?p=&id=     region box cropped from the render
  GET  /api/overview               chapter-level aggregate of existing caches
"""
from __future__ import annotations

import io
import json
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from pipeline import PIPELINE

STATIC = Path(__file__).resolve().parent / "static"


def list_dir(raw: str) -> dict:
    """Directory listing for the UI folder picker (dirs only, robust)."""
    import string
    home = Path.home()
    roots = []
    if raw.strip():
        p = Path(raw.strip()).resolve()
    else:
        p = home
        if hasattr(string, "ascii_uppercase"):
            for letter in string.ascii_uppercase:
                drive = Path(f"{letter}:/")
                if drive.exists():
                    roots.append({"name": f"{letter}:", "path": str(drive)})
    entries = []
    try:
        for child in sorted(p.iterdir(), key=lambda c: c.name.lower()):
            if child.is_dir():
                entries.append({"name": child.name, "path": str(child)})
    except (PermissionError, OSError) as e:
        return {"path": str(p), "parent": None, "entries": [],
                "roots": roots, "home": str(home), "error": str(e)}
    parent = str(p.parent) if p.parent != p else None
    return {"path": str(p), "parent": parent, "entries": entries,
            "roots": roots, "home": str(home)}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------ plumbing
    def log_message(self, fmt, *args):        # silence per-request noise
        pass

    def _send_json(self, obj, status: int = 200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_image(self, img, fmt: str = "PNG"):
        buf = io.BytesIO()
        if fmt == "JPEG":
            img.save(buf, "JPEG", quality=90)
        else:
            img.save(buf, "PNG")
        body = buf.getvalue()
        self.send_response(200)
        self.send_header("Content-Type", "image/png" if fmt == "PNG"
                         else "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: Path, ctype: str):
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n == 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    # -------------------------------------------------------------- routes
    def do_GET(self):
        try:
            url = urlparse(self.path)
            q = parse_qs(url.query)
            get = lambda k: (q.get(k) or [""])[0]
            if url.path == "/":
                return self._send_file(STATIC / "index.html", "text/html; charset=utf-8")
            if url.path.startswith("/static/"):
                name = url.path[len("/static/"):]
                ctype = {"style.css": "text/css; charset=utf-8",
                         "app.js": "text/javascript; charset=utf-8"}.get(name,
                                                                          "application/octet-stream")
                return self._send_file(STATIC / name, ctype)
            if url.path == "/api/state":
                return self._send_json(PIPELINE.state())
            if url.path == "/api/fs":
                return self._send_json(list_dir(get("path")))
            if url.path == "/api/page":
                return self._send_json(PIPELINE.page_data(get("p")))
            if url.path == "/api/log":
                return self._send_json({"lines": list(PIPELINE.logs)[-80:]})
            if url.path == "/api/overview":
                return self._send_json(PIPELINE.overview())
            if url.path == "/img/original":
                return self._send_image(PIPELINE.page_image(get("p")), "JPEG")
            if url.path == "/img/goal":
                try:
                    return self._send_image(PIPELINE.reference_image(get("p")), "JPEG")
                except FileNotFoundError:
                    return self._send_json({"error": "page not in reference folder"}, 404)
            if url.path == "/img/render":
                PIPELINE.render_page(get("p"))
                page = get("p")
                out = PIPELINE.studio_dir / "render" / (Path(page).stem + ".png")
                return self._send_file(out, "image/png")
            if url.path == "/img/overlay":
                return self._send_image(PIPELINE.overlay_image(get("p")), "JPEG")
            if url.path == "/img/crop":
                return self._send_image(PIPELINE.region_crop(get("p"), get("id")))
            if url.path == "/img/thumb":
                size = max(48, min(480, int(get("s") or 160)))
                return self._send_image(PIPELINE.thumbnail(get("p"), size), "JPEG")
            if url.path == "/img/ocr_input":
                return self._send_image(PIPELINE.ocr_input_image(get("p"), get("id")))
            if url.path == "/img/render_crop":
                return self._send_image(PIPELINE.render_crop(get("p"), get("id")))
            return self._send_json({"error": f"no route {url.path}"}, 404)
        except FileNotFoundError as e:
            return self._send_json({"error": str(e)}, 404)
        except Exception as e:
            PIPELINE.log(f"!! GET {self.path}: {type(e).__name__}: {e}")
            return self._send_json({"error": f"{type(e).__name__}: {e}"}, 500)

    def do_POST(self):
        try:
            body = self._body()
            url = urlparse(self.path)
            if url.path == "/api/open":
                return self._send_json(PIPELINE.open_folders(
                    body.get("chapter", ""), body.get("reference")))
            if url.path == "/api/settings":
                return self._send_json({"settings": PIPELINE.save_settings(body)})
            if url.path == "/api/detect":
                return self._send_json(PIPELINE.detect_page(body["page"],
                                                            body.get("conf")))
            if url.path == "/api/ocr":
                return self._send_json(PIPELINE.ocr_page(body["page"]))
            if url.path == "/api/render":
                return self._send_json(PIPELINE.render_page(body["page"]))
            if url.path == "/api/process":
                return self._send_json(PIPELINE.process_page(
                    body["page"], body.get("conf"),
                    translate=bool(body.get("translate"))))
            if url.path == "/api/translate":
                return self._send_json(PIPELINE.translate_page(body["page"]))
            if url.path == "/api/translation":
                return self._send_json(PIPELINE.set_translation(
                    body["page"], body["region_id"], body.get("text", "")))
            return self._send_json({"error": f"no route {url.path}"}, 404)
        except ValueError as e:
            return self._send_json({"error": str(e)}, 400)
        except FileNotFoundError as e:
            return self._send_json({"error": str(e)}, 404)
        except Exception as e:
            PIPELINE.log(f"!! POST {self.path}: {type(e).__name__}: {e}")
            return self._send_json({"error": f"{type(e).__name__}: {e}"}, 500)


def serve(port: int = 8765, open_browser: bool = True):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    url = f"http://127.0.0.1:{port}"
    print(f"Translation Studio -> {url}  (Ctrl+C to stop)", flush=True)
    if open_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
