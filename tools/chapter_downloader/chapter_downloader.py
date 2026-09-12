#!/usr/bin/env python3
"""Chapter Downloader — standalone desktop GUI for saving a manga chapter.

Built for assembling TachiyomiAT test material: paste a chapter URL (any
site with an adapter in sites.py), pick or create a folder, and get a
folder of zero-padded images (001.jpg, 002.jpg, …) that translation_studio
opens directly as a chapter.

Run:
    python chapter_downloader.py                      # GUI
    python chapter_downloader.py <chapter-url>        # GUI, URL prefilled
    python chapter_downloader.py <chapter-url> <dir>  # headless (scripts)

Site-specific behavior lives entirely in sites.py; the GUI is generic.
"""
from __future__ import annotations

import argparse
import json
import queue
import sys
import threading
import time
import tkinter as tk
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from tkinter import filedialog, ttk
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))

import sites
from sites import normalize_url

RETRY_BACKOFF_S = (1.0, 2.0, 4.0)
DEFAULT_DEST = Path.home() / "Downloads" / "manga_test_chapters"
SETTINGS_PATH = Path(__file__).resolve().parent / ".settings.json"


# --------------------------------------------------------------------------
# Download engine (GUI-free; emit() receives ("kind", payload) events)
# --------------------------------------------------------------------------


def _looks_like_html(data: bytes) -> bool:
    head = data[:256].lstrip().lower()
    return head.startswith(b"<!doctype") or head.startswith(b"<html")


def download_one(idx: int, url: str, dest: Path, referer: str,
                 skip_existing: bool, cancel: threading.Event):
    """Fetch one page image. Returns (idx, name, status, detail)."""
    name = dest.name
    if skip_existing and dest.exists() and dest.stat().st_size > 0:
        return idx, name, "skip", "kept existing"
    part = dest.with_suffix(dest.suffix + ".part")
    for attempt in range(len(RETRY_BACKOFF_S) + 1):
        if cancel.is_set():
            return idx, name, "cancel", ""
        try:
            data = sites.fetch_bytes(url, referer=referer)
            if not data or _looks_like_html(data):
                raise ValueError("response is not an image (error page?)")
            part.write_bytes(data)
            part.replace(dest)
            return idx, name, "ok", f"{len(data) / 1024:.0f} KB"
        except Exception as e:  # noqa: BLE001 — any network/format failure retries
            if cancel.is_set():
                return idx, name, "cancel", ""
            if attempt >= len(RETRY_BACKOFF_S):
                return idx, name, "fail", str(e)[:140]
            time.sleep(RETRY_BACKOFF_S[attempt])
    return idx, name, "fail", "unreachable"


def run_download(page_url: str, base_dest: str, subfolder: bool,
                 skip_existing: bool, workers: int, cancel: threading.Event,
                 emit) -> None:
    """Resolve a chapter URL and download all its pages. Terminal event is
    always exactly one of ("error", …) or ("done", …)."""
    emit("status", "Resolving chapter page…")
    try:
        info = sites.resolve(normalize_url(page_url))
    except Exception as e:  # noqa: BLE001
        emit("error", f"Could not open chapter page: {e}")
        return

    urls = info.image_urls
    if not urls:
        emit("error", f"No chapter images found (adapter: {info.adapter}).")
        return

    dest = Path(base_dest)
    if subfolder:
        dest = dest / info.suggested_name
    try:
        dest.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        emit("error", f"Cannot create destination folder: {e}")
        return

    emit("dest", str(dest))
    emit("log", f"Adapter: {info.adapter} — {len(urls)} pages")
    emit("log", f"  first: {urls[0]}")
    if len(urls) > 1:
        emit("log", f"  last:  {urls[-1]}")
    emit("log", f"  into:  {dest}")

    width = max(2, len(str(len(urls))))
    jobs = []
    for i, u in enumerate(urls, 1):
        ext = Path(urlparse(u).path).suffix.lower() or ".jpg"
        jobs.append((i, u, dest / f"{i:0{width}d}{ext}"))

    emit("progress", (0, len(jobs)))
    done = 0
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = [
            pool.submit(download_one, i, u, out, info.referer, skip_existing, cancel)
            for i, u, out in jobs
        ]
        for fut in as_completed(futures):
            idx, name, status, detail = fut.result()
            done += 1
            if status == "ok":
                emit("log", f"[{done}/{len(jobs)}] {name}  ({detail})")
            elif status == "skip":
                emit("log", f"[{done}/{len(jobs)}] {name}  (kept existing)")
            elif status == "fail":
                failures.append(name)
                emit("log-err", f"[{done}/{len(jobs)}] {name}  FAILED: {detail}")
            emit("progress", (done, len(jobs)))
            if cancel.is_set():
                break

    for p in dest.glob("*.part"):
        try:
            p.unlink()
        except OSError:
            pass

    if cancel.is_set():
        emit("error", f"Cancelled at {done}/{len(jobs)}. Re-run to resume.")
    elif failures:
        emit("error", f"Finished with {len(failures)} failed page(s): "
                      f"{', '.join(sorted(failures)[:8])} — re-run (kept existing) to retry.")
    else:
        emit("done", f"Done — {len(jobs)} pages in {dest}")


# --------------------------------------------------------------------------
# GUI
# --------------------------------------------------------------------------


class App(tk.Tk):
    def __init__(self, prefill_url: str = "", prefill_dest: str = ""):
        super().__init__()
        self.title("Chapter Downloader — TachiyomiAT test material")
        self.geometry("800x580")
        self.minsize(660, 460)
        self.q: queue.Queue = queue.Queue()
        self.cancel_evt = threading.Event()
        self.running = False
        self.last_dest: str | None = None

        self._build()
        self._load_settings()
        if prefill_url:
            self.url_var.set(prefill_url)
        if prefill_dest:
            self.dest_var.set(prefill_dest)
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(100, self._poll)

    # -- layout ------------------------------------------------------------

    def _build(self):
        pad = {"padx": 10, "pady": 4}

        top = ttk.Frame(self)
        top.pack(fill="x", **pad)
        ttk.Label(top, text="Chapter URL").grid(row=0, column=0, sticky="w")
        self.url_var = tk.StringVar()
        self.url_entry = ttk.Entry(top, textvariable=self.url_var)
        self.url_entry.grid(row=1, column=0, sticky="ew", pady=(2, 6))
        self.url_entry.bind("<Return>", lambda _e: self.start())

        ttk.Label(top, text="Save to").grid(row=2, column=0, sticky="w")
        row3 = ttk.Frame(top)
        row3.grid(row=3, column=0, sticky="ew", pady=(2, 6))
        self.dest_var = tk.StringVar(value=str(DEFAULT_DEST))
        ttk.Entry(row3, textvariable=self.dest_var).pack(side="left", fill="x", expand=True)
        ttk.Button(row3, text="Browse…", command=self.browse).pack(side="left", padx=(6, 0))
        ttk.Button(row3, text="Open", command=self.open_dest).pack(side="left", padx=(6, 0))
        top.columnconfigure(0, weight=1)

        opts = ttk.Frame(self)
        opts.pack(fill="x", **pad)
        self.subfolder_var = tk.BooleanVar(value=True)
        self.skip_var = tk.BooleanVar(value=True)
        self.workers_var = tk.IntVar(value=4)
        ttk.Checkbutton(opts, text="Create subfolder per chapter",
                        variable=self.subfolder_var).pack(side="left")
        ttk.Checkbutton(opts, text="Skip existing files (resume)",
                        variable=self.skip_var).pack(side="left", padx=(12, 12))
        ttk.Label(opts, text="Parallel").pack(side="left")
        ttk.Spinbox(opts, from_=1, to=8, width=3, textvariable=self.workers_var)\
            .pack(side="left", padx=(4, 0))

        bar = ttk.Frame(self)
        bar.pack(fill="x", **pad)
        self.start_btn = ttk.Button(bar, text="Download", command=self.start)
        self.start_btn.pack(side="left")
        self.cancel_btn = ttk.Button(bar, text="Cancel", command=self.cancel,
                                     state="disabled")
        self.cancel_btn.pack(side="left", padx=(6, 12))
        self.progress = ttk.Progressbar(bar, mode="determinate")
        self.progress.pack(side="left", fill="x", expand=True)
        self.status_var = tk.StringVar(value="Paste a chapter URL, choose a folder, Download.")
        self.status_lbl = ttk.Label(self, textvariable=self.status_var,
                                    foreground="#8a93a3")
        self.status_lbl.pack(fill="x", **pad)

        logbox = ttk.Frame(self)
        logbox.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        self.log_text = tk.Text(logbox, height=12, state="disabled", wrap="word",
                                font=("Consolas", 9), background="#12151a",
                                foreground="#d7dde6", relief="flat")
        scroll = ttk.Scrollbar(logbox, command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=scroll.set)
        self.log_text.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        for tag, color in (("err", "#ff6b6b"), ("ok", "#7ad48a"), ("dim", "#8a93a3")):
            self.log_text.tag_configure(tag, foreground=color)

    # -- actions -----------------------------------------------------------

    def browse(self):
        # The native Windows folder dialog includes "Make New Folder".
        try:
            chosen = filedialog.askdirectory(
                parent=self, title="Choose (or create) a folder", mustexist=False)
        except tk.TclError as e:
            self._status(f"Folder dialog failed ({e}) — type the path in the box instead.", err=True)
            return
        if chosen:
            self.dest_var.set(chosen)

    def open_dest(self):
        dest = self.last_dest or self.dest_var.get()
        if dest and Path(dest).exists():
            try:
                os.startfile(dest)  # noqa: S606 — Windows Explorer
            except OSError as e:
                self._status(f"Could not open folder: {e}", err=True)

    def start(self):
        if self.running:
            return
        url = normalize_url(self.url_var.get())
        dest = self.dest_var.get().strip()
        if not url:
            self._status("Enter a chapter URL.", err=True)
            return
        if not dest:
            self._status("Choose a destination folder.", err=True)
            return
        self._save_settings()
        self.url_var.set(url)
        self.cancel_evt = threading.Event()
        self.running = True
        self.start_btn.configure(state="disabled")
        self.cancel_btn.configure(state="normal")
        self._log(f"—— {url}", "dim")
        threading.Thread(
            target=run_download,
            args=(url, dest, self.subfolder_var.get(), self.skip_var.get(),
                  self.workers_var.get(), self.cancel_evt, self._emit),
            daemon=True,
        ).start()

    def _emit(self, kind, payload):
        """Thread-safe worker event -> GUI pump (q.put would eat the payload
        as its `block` argument, so wrap the tuple explicitly)."""
        self.q.put((kind, payload))

    def cancel(self):
        self.cancel_evt.set()
        self._status("Cancelling…")

    # -- event pump ----------------------------------------------------------

    def _poll(self):
        try:
            while True:
                kind, payload = self.q.get_nowait()
                if kind == "log":
                    self._log(payload)
                elif kind == "log-err":
                    self._log(payload, "err")
                elif kind == "status":
                    self._status(payload)
                elif kind == "progress":
                    done, total = payload
                    self.progress.configure(maximum=total, value=done)
                elif kind == "dest":
                    self.last_dest = payload
                elif kind == "error":
                    self._finish(ok=False, message=payload)
                elif kind == "done":
                    self._finish(ok=True, message=payload)
        except queue.Empty:
            pass
        except Exception as e:  # noqa: BLE001 — one bad event must never kill the pump
            self._log(f"internal event error: {e}", "err")
        finally:
            self.after(100, self._poll)

    def _finish(self, ok: bool, message: str):
        self.running = False
        self.start_btn.configure(state="normal")
        self.cancel_btn.configure(state="disabled")
        self._status(message, ok=ok, err=not ok)
        self._log(message, "ok" if ok else "err")

    def _status(self, text: str, ok: bool = False, err: bool = False):
        self.status_var.set(text)
        self.status_lbl.configure(
            foreground="#7ad48a" if ok else "#ff6b6b" if err else "#8a93a3")

    def _log(self, text: str, tag: str | None = None):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", text + "\n", tag or ())
        self.log_text.see("end")
        self.log_text.configure(state="disabled")


    def _save_settings(self):
        try:
            SETTINGS_PATH.write_text(json.dumps({
                "dest": self.dest_var.get(),
                "subfolder": self.subfolder_var.get(),
                "skip_existing": self.skip_var.get(),
                "workers": self.workers_var.get(),
            }), encoding="utf-8")
        except OSError:
            pass

    def _load_settings(self):
        try:
            data = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        if data.get("dest"):
            self.dest_var.set(data["dest"])
        self.subfolder_var.set(bool(data.get("subfolder", True)))
        self.skip_var.set(bool(data.get("skip_existing", True)))
        try:
            self.workers_var.set(max(1, min(8, int(data.get("workers", 4)))))
        except (TypeError, ValueError):
            pass

    def _on_close(self):
        self.cancel_evt.set()
        self._save_settings()
        self.destroy()


# --------------------------------------------------------------------------
# entry point
# --------------------------------------------------------------------------


def _print_emit(kind, payload):
    if kind == "log":
        print(payload)
    elif kind == "status":
        print(f"… {payload}")
    elif kind in ("error", "done"):
        print(("[FAIL] " if kind == "error" else "[OK] ") + str(payload))
        if kind == "error":
            sys.exit(1)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Download one manga chapter into a folder of numbered images.")
    ap.add_argument("url", nargs="?", help="chapter page URL (or a direct image URL)")
    ap.add_argument("dest", nargs="?", help="destination folder (created if missing)")
    ap.add_argument("--no-subfolder", action="store_true",
                    help="save straight into DEST instead of DEST/<chapter>")
    ap.add_argument("--overwrite", action="store_true",
                    help="re-download even if files already exist")
    ap.add_argument("--workers", type=int, default=4, help="parallel downloads (1-8)")
    args = ap.parse_args(argv)

    if args.url and args.dest:
        run_download(
            args.url, args.dest, subfolder=not args.no_subfolder,
            skip_existing=not args.overwrite, workers=args.workers,
            cancel=threading.Event(), emit=_print_emit,
        )
        return

    app = App(prefill_url=args.url or "", prefill_dest=args.dest or "")
    app.mainloop()


if __name__ == "__main__":
    main()
