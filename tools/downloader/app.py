"""Tkinter desktop UI for downloading chapter page images."""

from __future__ import annotations

import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from downloader_core import DownloadError, DownloadResult, chapter_output_dir, download_chapter_images


class DownloaderApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("Rawkuma Chapter Image Downloader")
        self.root.minsize(680, 440)
        self.events: queue.Queue[tuple] = queue.Queue()
        self.cancel_event: threading.Event | None = None
        self.worker: threading.Thread | None = None

        self.url_var = tk.StringVar()
        self.output_var = tk.StringVar(
            value=str(Path(__file__).resolve().parent / "output")
        )
        self.status_var = tk.StringVar(value="Paste a Rawkuma chapter URL to begin.")

        self._build_widgets()
        self.root.protocol("WM_DELETE_WINDOW", self._close)
        self.root.after(100, self._poll_events)

    def _build_widgets(self) -> None:
        frame = ttk.Frame(self.root, padding=16)
        frame.pack(fill="both", expand=True)
        frame.columnconfigure(1, weight=1)
        frame.rowconfigure(5, weight=1)

        ttk.Label(frame, text="Chapter URL").grid(row=0, column=0, sticky="w", pady=(0, 6))
        self.url_entry = ttk.Entry(frame, textvariable=self.url_var)
        self.url_entry.grid(row=0, column=1, columnspan=2, sticky="ew", pady=(0, 6))

        ttk.Label(frame, text="Output root").grid(row=1, column=0, sticky="w", pady=(0, 12))
        self.output_entry = ttk.Entry(frame, textvariable=self.output_var)
        self.output_entry.grid(row=1, column=1, sticky="ew", pady=(0, 12))
        self.browse_button = ttk.Button(frame, text="Browse…", command=self._browse)
        self.browse_button.grid(row=1, column=2, sticky="ew", padx=(8, 0), pady=(0, 12))

        controls = ttk.Frame(frame)
        controls.grid(row=2, column=0, columnspan=3, sticky="ew")
        controls.columnconfigure(2, weight=1)
        self.download_button = ttk.Button(controls, text="Download chapter", command=self._start)
        self.download_button.grid(row=0, column=0, sticky="w")
        self.cancel_button = ttk.Button(controls, text="Cancel", command=self._cancel, state="disabled")
        self.cancel_button.grid(row=0, column=1, sticky="w", padx=(8, 0))

        self.progress = ttk.Progressbar(frame, mode="determinate", maximum=1)
        self.progress.grid(row=3, column=0, columnspan=3, sticky="ew", pady=(14, 6))
        ttk.Label(frame, textvariable=self.status_var).grid(
            row=4, column=0, columnspan=3, sticky="w", pady=(0, 8)
        )

        log_frame = ttk.LabelFrame(frame, text="Image errors", padding=8)
        log_frame.grid(row=5, column=0, columnspan=3, sticky="nsew")
        log_frame.rowconfigure(0, weight=1)
        log_frame.columnconfigure(0, weight=1)
        self.error_log = tk.Text(log_frame, height=8, wrap="word", state="disabled")
        self.error_log.grid(row=0, column=0, sticky="nsew")
        scrollbar = ttk.Scrollbar(log_frame, orient="vertical", command=self.error_log.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.error_log.configure(yscrollcommand=scrollbar.set)

    def _browse(self) -> None:
        selected = filedialog.askdirectory(title="Choose output root")
        if selected:
            self.output_var.set(selected)

    def _start(self) -> None:
        if self.worker and self.worker.is_alive():
            return
        chapter_url = self.url_var.get().strip()
        output_root = self.output_var.get().strip()
        if not output_root:
            messagebox.showerror("Output root required", "Choose or enter an output folder.")
            return
        try:
            target_dir = chapter_output_dir(chapter_url, output_root)
        except ValueError as error:
            messagebox.showerror("Invalid chapter URL", str(error))
            return

        self._clear_errors()
        self.progress.configure(value=0, maximum=1)
        self.status_var.set(f"Ready to save images to {target_dir}")
        self.cancel_event = threading.Event()
        self.download_button.configure(state="disabled")
        self.cancel_button.configure(state="normal")
        self.url_entry.configure(state="disabled")
        self.output_entry.configure(state="disabled")
        self.browse_button.configure(state="disabled")
        self.worker = threading.Thread(
            target=self._run_download,
            args=(chapter_url, output_root, self.cancel_event),
            daemon=True,
        )
        self.worker.start()

    def _run_download(
        self, chapter_url: str, output_root: str, cancel_event: threading.Event
    ) -> None:
        try:
            result = download_chapter_images(
                chapter_url,
                output_root,
                cancel_event,
                on_status=lambda text: self.events.put(("status", text)),
                on_progress=lambda index, total, url: self.events.put(
                    ("progress", index, total, url)
                ),
                on_image_error=lambda index, url, error: self.events.put(
                    ("image_error", index, url, error)
                ),
            )
            self.events.put(("done", result))
        except DownloadError as error:
            self.events.put(("fatal", str(error)))
        except Exception as error:  # Keep unexpected worker failures visible in the GUI.
            self.events.put(("fatal", f"Unexpected error: {error}"))

    def _poll_events(self) -> None:
        try:
            while True:
                event = self.events.get_nowait()
                kind = event[0]
                if kind == "status":
                    self.status_var.set(event[1])
                elif kind == "progress":
                    _, index, total, _url = event
                    self.progress.configure(maximum=max(total, 1), value=index - 1)
                    self.status_var.set(f"Downloading image {index} of {total}…")
                elif kind == "image_error":
                    _, index, url, error = event
                    self._append_error(f"Image {index}: {error}\n{url}\n")
                elif kind == "fatal":
                    self._finish_controls()
                    self.status_var.set("Download stopped.")
                    messagebox.showerror("Download failed", event[1])
                elif kind == "done":
                    self._show_result(event[1])
        except queue.Empty:
            pass
        if self.root.winfo_exists():
            self.root.after(100, self._poll_events)

    def _show_result(self, result: DownloadResult) -> None:
        self._finish_controls()
        self.progress.configure(maximum=max(result.image_count, 1), value=result.image_count)
        if result.cancelled:
            self.status_var.set(
                f"Cancelled after saving {result.downloaded_count} of {result.image_count} images. "
                f"Output: {result.output_dir}"
            )
        elif result.errors:
            self.status_var.set(
                f"Finished with errors: saved {result.downloaded_count} of "
                f"{result.image_count} images. Output: {result.output_dir}"
            )
        else:
            self.status_var.set(
                f"Finished: saved {result.downloaded_count} images to {result.output_dir}"
            )

    def _finish_controls(self) -> None:
        self.download_button.configure(state="normal")
        self.cancel_button.configure(state="disabled")
        self.url_entry.configure(state="normal")
        self.output_entry.configure(state="normal")
        self.browse_button.configure(state="normal")

    def _cancel(self) -> None:
        if self.cancel_event and self.worker and self.worker.is_alive():
            self.cancel_event.set()
            self.cancel_button.configure(state="disabled")
            self.status_var.set("Cancelling after the current request…")

    def _clear_errors(self) -> None:
        self.error_log.configure(state="normal")
        self.error_log.delete("1.0", "end")
        self.error_log.configure(state="disabled")

    def _append_error(self, text: str) -> None:
        self.error_log.configure(state="normal")
        self.error_log.insert("end", text + "\n")
        self.error_log.see("end")
        self.error_log.configure(state="disabled")

    def _close(self) -> None:
        if self.cancel_event:
            self.cancel_event.set()
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    DownloaderApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
