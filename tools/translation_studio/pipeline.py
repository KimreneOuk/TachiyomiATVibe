"""Studio pipeline: Detect -> OCR -> Translate(cache/manual) -> Render.

Reuses, unmodified:
  * the app's own detection model  (app/src/main/assets/models/detection/
    detector-v4-s_int8.onnx) with the exact OnnxPageTextDetector protocol,
  * the T927 lab OCR stack (derived batch-capable graphs + the corrected
    decoder / lockstep state machine),
and adds desktop-side rendering (background erase + auto-fit text) and a
translation cache (fill by hand in the UI, or pull from an OpenAI-compatible
endpoint such as LM Studio).

Caches live under <chapter>/.studio/ so re-testing never repeats work:
  detections.json   raw detector outputs (kept at a low score floor, so the
                    confidence slider re-filters without re-running the model)
  ocr.json          per-page regions + OCR text/timings
  translations.json page -> region id -> translated text (hand-filled or API)
  settings.json     studio settings
  render/<page>.png rendered output
"""
from __future__ import annotations

import io
import json
import sys
import threading
import time
import urllib.parse
import urllib.request
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

STUDIO_DIR = Path(__file__).resolve().parent
TOOLS_DIR = STUDIO_DIR.parent
REPO_ROOT = TOOLS_DIR.parent
LAB_DIR = TOOLS_DIR / "mangaocr_lab"
sys.path.insert(0, str(LAB_DIR))

import lab.assets as lab_assets                      # noqa: E402
import lab.batched as lab_batched                    # noqa: E402
import lab.corpus as lab_corpus                      # noqa: E402
import lab.decode_common as lab_dc                   # noqa: E402
import lab.graphs as lab_graphs                      # noqa: E402
import lab.sessions as lab_sessions                  # noqa: E402
from lab.preprocessing import preprocess as lab_preprocess  # noqa: E402

from boxgeom import (Box, DET_THRESHOLDS, greedy_dedup,  # noqa: E402
                     intersection_area, dedupe_within_parents,
                     reading_order_rtl, select_parent, suppress_cross_label,
                     overlaps_any_bubble)

DETECTOR_PATH = (REPO_ROOT / "app/src/main/assets/models/detection"
                 / "detector-v4-s_int8.onnx")
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
OCR_PAD = 12                # RoiPageRecognitionEngine pad around text boxes
CONF_FLOOR = 0.05           # what we persist (slider re-filters above this)
N_CLASSES = {0: "bubble", 1: "text_bubble", 2: "text_free"}
CLASS_COLORS = {0: (150, 150, 160), 1: (70, 200, 120), 2: (240, 170, 60)}

# Director-locked test chapter: pre-seeded as a recent chapter in the UI.
DEFAULT_CHAPTER = (r"C:\Users\User\Downloads\manga_test_chapters"
                   r"\ore-ni-trauma-wo-ataeta-joshitachi-ga-chirachira-"
                   r"mitekuru-kedo-zannen-desu-ga-teokure-desu_ch16")
RECENT_FILE = Path(__file__).resolve().parent / ".recent.json"


def log(msg: str) -> None:
    PIPELINE.log(msg)


class Pipeline:
    def __init__(self):
        self.lock = threading.RLock()
        self.logs: deque = deque(maxlen=500)
        self.chapter: Path | None = None
        self.reference: Path | None = None
        self.pages: list[str] = []
        self.settings: dict = {}
        self._detector = None
        self._ocr = None
        self._paddle = None      # (PaddleDet, PaddleRec), lazy
        self._paddle_det = None  # det-only singleton for free-text refinement
        self._paddle_det_unavailable = False
        self._aot = None         # AotInpainter, lazy
        self._segmenter = None   # BubbleSegmenter, lazy (manga109 YOLO11-seg)
        self._bubble_mask_cache: dict[str, list] = {}
        self._load_times: dict[str, float] = {}
        self._seg_times: dict[str, float] = {}
        self._inpaint_times: dict[str, float] = {}
        self._render_times: dict[str, float] = {}
        self._cache: dict = {}
        self._render_dirty: dict[str, bool] = {}
        self._crop_cache: dict = {}
        self._dims: dict[str, list[int]] = {}

    # ------------------------------------------------------------------ util
    def log(self, msg: str) -> None:
        line = f"[{time.strftime('%H:%M:%S')}] {msg}"
        self.logs.append(line)
        print(line, flush=True)

    @property
    def studio_dir(self) -> Path | None:
        return self.chapter / ".studio" if self.chapter else None

    def _cache_path(self, name: str) -> Path | None:
        return self.studio_dir / name if self.studio_dir else None

    def _load_json(self, name: str, default):
        p = self._cache_path(name)
        if p and p.exists():
            try:
                return json.loads(p.read_text(encoding="utf-8"))
            except Exception as e:
                self.log(f"!! corrupt cache {name}: {e}")
        return default

    def _save_json(self, name: str, data) -> None:
        p = self._cache_path(name)
        if not p:
            return
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(data, indent=1, ensure_ascii=False),
                     encoding="utf-8")

    # ---------------------------------------------------------------- folders
    def get_recent(self) -> list[dict]:
        recents: list[dict] = []
        if RECENT_FILE.exists():
            try:
                with open(RECENT_FILE, "r", encoding="utf-8") as f:
                    recents = json.load(f)
            except Exception:
                recents = []
        # Pre-seed with DEFAULT_CHAPTER if not already present
        existing = {r.get("chapter") for r in recents if isinstance(r, dict)}
        if DEFAULT_CHAPTER not in existing and Path(DEFAULT_CHAPTER).is_dir():
            recents.append({"chapter": DEFAULT_CHAPTER, "reference": None})
        return recents

    def record_recent(self, chapter: str | Path, reference: str | Path | None = None) -> list[dict]:
        ch_str = str(Path(chapter).resolve())
        ref_str = str(Path(reference).resolve()) if reference else None
        recents = [r for r in self.get_recent() if r.get("chapter") != ch_str]
        recents.insert(0, {"chapter": ch_str, "reference": ref_str})
        recents = recents[:15]
        try:
            with open(RECENT_FILE, "w", encoding="utf-8") as f:
                json.dump(recents, f, indent=2, ensure_ascii=False)
        except Exception as e:
            self.log(f"warning: failed to save recent chapters: {e}")
        return recents

    def open_folders(self, chapter: str, reference: str | None) -> dict:
        with self.lock:
            ch = Path(chapter)
            if not ch.is_dir():
                raise ValueError(f"chapter folder not found: {chapter}")
            self.chapter = ch
            self.reference = Path(reference) if reference and Path(reference).is_dir() else None
            self.record_recent(ch, self.reference)
            self.pages = sorted(
                p.relative_to(ch).as_posix() for p in ch.rglob("*")
                if p.suffix.lower() in IMAGE_EXTS and p.is_file()
                and ".studio" not in p.parts)
            if self.reference is not None:
                ref_pages = {p.relative_to(self.reference).as_posix()
                             for p in self.reference.rglob("*")
                             if p.suffix.lower() in IMAGE_EXTS and p.is_file()}
                missing = [p for p in self.pages if p not in ref_pages]
                if missing:
                    self.log(f"reference folder missing {len(missing)} pages "
                             f"(first: {missing[0]})")
            self._cache = {
                "detections": self._load_json("detections.json", {}),
                "ocr": self._load_json("ocr.json", {}),
                "translations": self._load_json("translations.json", {}),
            }
            self._dims = {}
            self._crop_cache = {}
            self._bubble_mask_cache = {}
            self.settings = dict(DEFAULT_SETTINGS)
            self.settings.update(self._load_json("settings.json", {}))
            self._render_dirty = {p: True for p in self.pages}
            self.log(f"opened chapter: {ch} ({len(self.pages)} pages)"
                     + (f", reference: {self.reference}" if self.reference else ""))
            return self.state()

    def save_settings(self, patch: dict) -> dict:
        with self.lock:
            for k, v in patch.items():
                if k == "conf" and v is not None:
                    self.settings[k] = float(v)
                elif k == "bubble_mask_erosion" and v is not None:
                    self.settings[k] = int(v)
                elif k == "font_scale" and v is not None:
                    self.settings[k] = float(v)
                elif k == "max_batch" and v is not None:
                    self.settings[k] = int(v)
                else:
                    self.settings[k] = v
            self._save_json("settings.json", self.settings)
            return self.settings

    def reset_artifacts(self, page: str | None = None,
                        keep_translations: bool = False) -> dict:
        """Delete cached artifacts so the pipeline re-runs from scratch.

        Args:
            page: If given, reset only that page's data. Otherwise reset all.
            keep_translations: When True, preserve translations.json so manual
                               or API translations are not lost.

        Returns a summary of what was deleted.
        """
        with self.lock:
            if not self.studio_dir:
                raise ValueError("No chapter open")
            deleted: list[str] = []
            stem = Path(page).stem if page else None

            def _rm(p: Path) -> None:
                if p.exists():
                    p.unlink()
                    deleted.append(p.name)

            def _rm_tree(d: Path) -> None:
                """Delete every file in a subdirectory (or only the page's file)."""
                if not d.is_dir():
                    return
                if stem:
                    for ext in (".png", ".jpg", ".jpeg", ".webp"):
                        _rm(d / (stem + ext))
                else:
                    for f in list(d.iterdir()):
                        if f.is_file():
                            f.unlink()
                            deleted.append(f.name)

            if stem:
                # Per-page reset: remove this page's entries from JSON caches
                # and its rendered/inpainted/segmentation images.
                for key in ("detections", "ocr"):
                    if page in self._cache.get(key, {}):
                        del self._cache[key][page]
                        self._save_json(f"{key}.json", self._cache[key])
                        deleted.append(f"{key}.json[{page}]")
                if not keep_translations and page in self._cache.get("translations", {}):
                    del self._cache["translations"][page]
                    self._save_json("translations.json", self._cache["translations"])
                    deleted.append(f"translations.json[{page}]")
                _rm_tree(self.studio_dir / "render")
                _rm_tree(self.studio_dir / "inpaint")
                _rm_tree(self.studio_dir / "inpaint_mask")
                _rm_tree(self.studio_dir / "segmentation")
                _rm_tree(self.studio_dir / "seg_overlay")
                _rm_tree(self.studio_dir / "thumbs")
                self._render_dirty[page] = True
                self._bubble_mask_cache.pop(page, None)
                self._crop_cache.pop(page, None)
                self._seg_times.pop(page, None)
                self._inpaint_times.pop(page, None)
                self._render_times.pop(page, None)
            else:
                # Full chapter reset
                _rm(self.studio_dir / "detections.json")
                _rm(self.studio_dir / "ocr.json")
                if not keep_translations:
                    _rm(self.studio_dir / "translations.json")
                _rm_tree(self.studio_dir / "render")
                _rm_tree(self.studio_dir / "inpaint")
                _rm_tree(self.studio_dir / "inpaint_mask")
                _rm_tree(self.studio_dir / "segmentation")
                _rm_tree(self.studio_dir / "seg_overlay")
                _rm_tree(self.studio_dir / "thumbs")
                self._cache["detections"] = {}
                self._cache["ocr"] = {}
                if not keep_translations:
                    self._cache["translations"] = {}
                self._crop_cache = {}
                self._bubble_mask_cache = {}
                self._seg_times = {}
                self._inpaint_times = {}
                self._render_times = {}
                self._render_dirty = {p: True for p in self.pages}

            self.log(f"reset_artifacts(page={page!r}, keep_translations={keep_translations}): "
                     f"removed {len(deleted)} items")
            return {"deleted": deleted, "count": len(deleted)}



    def state(self) -> dict:
        return {
            "chapter": str(self.chapter) if self.chapter else None,
            "reference": str(self.reference) if self.reference else None,
            "pages": self.pages,
            "settings": self.settings,
            "recent": self.get_recent(),
            "dims": {p: self.page_dims(p) for p in self.pages},
            "models": {
                "detector_ready": self._detector is not None,
                "ocr_ready": self._ocr is not None,
                "ocr_gate": self._ocr.gate_summary() if self._ocr else None,
                "paddle_ready": self._paddle is not None,
                "aot_ready": self._aot is not None,
                "segmenter_ready": self._segmenter is not None,
            },
        }

    # ------------------------------------------------------- ui support
    # Additive helpers that feed the viewer UI. They reuse the existing
    # caches/outputs above; none of them change pipeline semantics.

    def page_dims(self, page: str) -> list[int]:
        """[w, h] from the image header, so the viewer can reserve layout
        space before decoding anything."""
        d = self._dims.get(page)
        if d is None:
            with Image.open(self.chapter / page) as im:
                d = [im.width, im.height]
            self._dims[page] = d
        return d

    def thumbnail_path(self, page: str, size: int = 160) -> Path:
        """Cached thumbnail file path on disk."""
        assert self.studio_dir is not None
        tdir = self.studio_dir / "thumbs"
        out = tdir / f"{Path(page).stem}_{size}.jpg"
        if not out.exists():
            img = self.page_image(page)
            img.thumbnail((size, 10000), Image.BILINEAR)
            tdir.mkdir(parents=True, exist_ok=True)
            tmp = out.with_suffix(".tmp.jpg")
            img.save(tmp, "JPEG", quality=80)
            tmp.replace(out)
        return out

    def thumbnail(self, page: str, size: int = 160) -> Image.Image:
        """Small cached JPEG for the navigator rail / overview."""
        return Image.open(self.thumbnail_path(page, size)).convert("RGB")

    def ocr_input_image(self, page: str, region_id: str) -> Image.Image:
        """The EXACT 224x224 normalized tensor OCR consumed, un-normalized
        for display — reuses lab.preprocessing.preprocess, nothing new."""
        regions = self._cache["ocr"].get(page, {}).get("regions", [])
        r = next((x for x in regions if x["id"] == region_id), None)
        if r is None:
            raise FileNotFoundError(region_id)
        img = self.page_image(page).crop(tuple(r["ocr_box"]))
        px = lab_preprocess(img)
        arr = np.clip((px[0] * np.float32(0.5) + np.float32(0.5)) * 255.0,
                      0, 255).astype("uint8")
        return Image.fromarray(arr, mode="L").convert("RGB")

    def render_crop(self, page: str, region_id: str, scale: int = 2) -> Image.Image:
        """Region box cropped from the rendered output (final on-page text)."""
        self.render_page(page)
        regions = self._cache["ocr"].get(page, {}).get("regions", [])
        r = next((x for x in regions if x["id"] == region_id), None)
        if r is None:
            raise FileNotFoundError(region_id)
        out = self.studio_dir / "render" / (Path(page).stem + ".png")
        img = Image.open(out).convert("RGB").crop(tuple(r["box"]))
        if scale > 1 and min(img.size) < 300:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        return img

    def overview(self) -> dict:
        """Chapter-level aggregate computed from the existing caches & timings."""
        rows = []
        total_regions_count = 0
        total_chapter_infer_ms = 0.0
        pages_with_data = 0

        conf = float(self.settings.get("conf", 0.45))
        for p in self.pages:
            det = self._cache["detections"].get(p)
            ocr = self._cache["ocr"].get(p)
            if ocr and "regions" in ocr:
                regs = [r for r in ocr["regions"] if r.get("score", 1.0) >= conf]
            elif det:
                regs = self._regions_at_conf(p, conf).get("regions", [])
            else:
                regs = []
            tr = self.translations(p)
            translated = sum(1 for r in regs if (tr.get(r["id"]) or "").strip())
            rendered = False
            inpainted = False
            if self.studio_dir is not None:
                out = self.studio_dir / "render" / (Path(p).stem + ".png")
                rendered = out.exists()
                inp_path = self.studio_dir / "inpaint" / (Path(p).stem + ".png")
                inpainted = inp_path.exists()

            det_ms = (det or {}).get("infer_ms", (det or {}).get("model_ms", 0)) or 0
            ocr_ms = (ocr or {}).get("infer_ms", (ocr or {}).get("ms", 0)) or 0
            seg_ms = self._seg_times.get(p, 0)
            inp_ms = self._inpaint_times.get(p, 0)
            ren_ms = self._render_times.get(p, 0)
            page_infer_total = det_ms + ocr_ms + seg_ms + inp_ms + ren_ms
            if page_infer_total > 0 or len(regs) > 0:
                pages_with_data += 1
                total_chapter_infer_ms += page_infer_total
                total_regions_count += len(regs)

            rows.append({
                "page": p,
                "detected": det is not None,
                "ocr": ocr is not None,
                "regions": len(regs),
                "translated": translated,
                "inpainted": inpainted,
                "rendered": rendered,
                "det_ms": det_ms,
                "ocr_ms": ocr_ms,
                "seg_ms": seg_ms,
                "inp_ms": inp_ms,
                "ren_ms": ren_ms,
                "total_infer_ms": round(page_infer_total, 1),
                "position_limit": any(r.get("position_limit") for r in regs),
            })

        errors = [{"page": r["page"], "reason": "OCR position limit"}
                  for r in rows if r["position_limit"]]
        slowest = max(rows, key=lambda r: r["ocr_ms"], default=None)

        avg_page_infer_ms = round(total_chapter_infer_ms / max(1, pages_with_data), 1)
        avg_regions = round(total_regions_count / max(1, pages_with_data), 2)
        total_model_load_ms = round(sum(self._load_times.values()), 1)

        summary = {
            "total_pages": len(self.pages),
            "pages_with_data": pages_with_data,
            "total_regions": total_regions_count,
            "avg_regions_per_page": avg_regions,
            "total_chapter_infer_ms": round(total_chapter_infer_ms, 1),
            "avg_page_infer_ms": avg_page_infer_ms,
            "total_model_load_ms": total_model_load_ms,
            "model_load_times": dict(self._load_times),
        }

        return {
            "chapter": str(self.chapter) if self.chapter else None,
            "summary": summary,
            "pages": rows,
            "errors": errors,
            "slowest": slowest,
        }

    # ------------------------------------------------------------------ image
    def page_image(self, page: str) -> Image.Image:
        p = self.chapter / page
        if not p.exists():
            raise FileNotFoundError(page)
        return Image.open(p).convert("RGB")

    def reference_image(self, page: str) -> Image.Image:
        p = self.reference / page
        if not p.exists():
            raise FileNotFoundError(page)
        return Image.open(p).convert("RGB")

    # --------------------------------------------------------------- detection
    def _detector_model(self):
        if self._detector is None:
            t0 = time.perf_counter()
            self.log(f"loading detector: {DETECTOR_PATH.name}")
            self._detector = Detector(DETECTOR_PATH)
            self._load_times["detector"] = round((time.perf_counter() - t0) * 1000, 1)
            self.log(f"detector loaded in {self._load_times['detector']}ms")
        return self._detector

    def detect_page(self, page: str, conf: float | None = None,
                    force: bool = False) -> dict:
        """Runs (or reuses) the detector; re-filters at the requested conf."""
        with self.lock:
            conf = self.settings["conf"] if conf is None else conf
            raw = self._cache["detections"].get(page)
            if raw is None or force:
                det = self._detector_model()
                t0 = time.perf_counter()
                img = self.page_image(page)
                raw_all = det.detect(img)
                infer_ms = round((time.perf_counter() - t0) * 1000, 1)
                raw = {"boxes": [[d["label"], round(d["score"], 4), *d["box"]]
                                 for d in raw_all],
                       "page_wh": [img.width, img.height],
                       "model_ms": infer_ms,
                       "infer_ms": infer_ms,
                       "load_ms": self._load_times.get("detector", 0)}
                self._cache["detections"][page] = raw
                self._save_json("detections.json", self._cache["detections"])
                self.log(f"detect {page}: {len(raw['boxes'])} raw "
                         f"(infer {raw['infer_ms']}ms, load {raw['load_ms']}ms)")
            regions = self._regions_at_conf(page, conf)
            return {"page": page, "conf": conf, **regions,
                    "model_ms": raw.get("infer_ms", raw.get("model_ms", 0)),
                    "infer_ms": raw.get("infer_ms", raw.get("model_ms", 0)),
                    "load_ms": raw.get("load_ms", self._load_times.get("detector", 0))}

    def _regions_at_conf(self, page: str, conf: float) -> dict:
        """Filter + dedupe raw detections exactly like the app's stages."""
        raw = self._cache["detections"].get(page)
        if raw is None:
            raise ValueError(f"page not detected yet: {page}")
        items = [(Box(*[int(v) for v in r[2:]]), int(r[0]), float(r[1]))
                 for r in raw["boxes"]]
        page_wh = raw.get("page_wh") or [1, 1]
        kept = []
        bubbles = [it for it in items if it[1] == 0 and it[2] >= conf]
        texts = [it for it in items if it[1] in (1, 2) and it[2] >= conf]
        if texts:
            boxes = [t[0] for t in texts]
            scores = [t[2] for t in texts]
            labels = [t[1] for t in texts]
            kept_idx = greedy_dedup(boxes, scores, DET_THRESHOLDS)
            texts = [texts[i] for i in kept_idx]
            # re-sync boxes/labels/scores after dedup
            boxes = [texts[i][0] for i in range(len(texts))]
            kept_idx = suppress_cross_label(boxes, [t[1] for t in texts],
                                            [t[2] for t in texts], [b[0] for b in bubbles])
            texts = [texts[i] for i in kept_idx]
            boxes = [t[0] for t in texts]
            kept_idx = dedupe_within_parents(boxes, [t[1] for t in texts],
                                             [t[2] for t in texts],
                                             [b[0] for b in bubbles])
            texts = [texts[i] for i in kept_idx]
            order = reading_order_rtl([t[0] for t in texts], page_wh[1])
            texts = [texts[i] for i in order]
        for n, (box, label, score) in enumerate(texts):
            kept.append({"id": f"r{n:02d}", "label": label,
                         "class": N_CLASSES.get(label, str(label)),
                         "score": round(score, 3),
                         "box": box.as_list(),
                         "ocr_box": clamp_pad(box, page_wh).as_list()})
        return {"bubbles": len(bubbles), "regions": kept}

    # --------------------------------------------------------------------- OCR
    def _ocr_engine(self):
        if self._ocr is None:
            t0 = time.perf_counter()
            log("initializing OCR (derived graphs + B=1 gate)…")
            self._ocr = OcrEngine()
            self._load_times["ocr_mangaocr"] = round((time.perf_counter() - t0) * 1000, 1)
            g = self._ocr.gate
            log(f"OCR ready in {self._load_times['ocr_mangaocr']}ms — gate {'PASS' if g['pass'] else 'FAILED'} "
                f"(enc {g['encoder_max_abs_diff']:.1e}, dec "
                f"{g['decoder_max_abs_diff']:.1e})")
        return self._ocr

    def _paddle_engines(self):
        """PaddleOCR v6 small det+rec (desktop port of the Android engines)."""
        if self._paddle is None:
            t0 = time.perf_counter()
            import paddle_ocr
            log("initializing PaddleOCR v6 small (det + rec)…")
            self._paddle = (paddle_ocr.PaddleDet(), paddle_ocr.PaddleRec())
            self._load_times["ocr_paddle"] = round((time.perf_counter() - t0) * 1000, 1)
            log(f"PaddleOCR ready in {self._load_times['ocr_paddle']}ms — dict={len(self._paddle[1].dictionary)} "
                f"classes")
        return self._paddle

    def _ocr_engine_name(self) -> str:
        return self.settings.get("ocr_engine", "mangaocr")

    def _recognize_crops(self, crops: list[Image.Image]) -> tuple[list[dict], dict, float]:
        """Dispatch on settings['ocr_engine']: mangaocr (T927 lab stack) or
        paddle (v6 small det+rec). Both return the same region dicts."""
        if self._ocr_engine_name() == "paddle":
            import paddle_ocr
            det, rec = self._paddle_engines()
            t0 = time.perf_counter()
            out = []
            for c in crops:
                try:
                    text, line_info = paddle_ocr.assemble_region_text(det, rec, c)
                    out.append({
                        "text": text,
                        "raw_text": text,
                        "confidence": None,
                        "lines": line_info,
                        "engine": "paddle",
                    })
                except Exception as e:
                    log(f"!! paddle rec failed: {e}")
                    out.append({"text": "", "raw_text": "", "confidence": None,
                                "lines": [], "engine": "paddle", "error": str(e)})
            ms = round((time.perf_counter() - t0) * 1000, 1)
            return out, {"engine": "paddle", "regions": len(out)}, ms
        engine = self._ocr_engine()
        results, runs, ms = engine.decode_regions(crops)
        return results, runs, ms

    def ocr_page(self, page: str, conf: float | None = None) -> dict:
        with self.lock:
            conf = self.settings["conf"] if conf is None else conf
            self.detect_page(page, conf)
            regions = self._regions_at_conf(page, conf)["regions"]
            engine_name = self._ocr_engine_name()
            prev_entry = self._cache["ocr"].get(page) or {}
            if not regions:
                self._cache["ocr"][page] = {"regions": [], "runs": {},
                                            "ms": 0, "engine": engine_name}
                self._save_json("ocr.json", self._cache["ocr"])
                return {"page": page, "regions": [], "runs": {}, "ms": 0}

            # cached results from a DIFFERENT engine are not reusable
            if prev_entry.get("engine", "mangaocr") != engine_name:
                prev_entry = {}
            img = self.page_image(page)
            crops, err = [], []
            for r in regions:
                b = Box(*r["ocr_box"])
                crops.append(img.crop((b.x1, b.y1, b.x2, b.y2)))

            results, runs, ms = self._recognize_crops(crops)

            prev = {r0["id"]: r0 for r0 in prev_entry.get("regions", [])}
            for r, res in zip(regions, results):
                r.update(res)
                # preserve hand/API translations across re-OCR by box overlap
                for pid, p in prev.items():
                    if iou_at_least(r["box"], p["box"], 0.7):
                        r["carried_from"] = pid
                        break
            load_ms = self._load_times.get(f"ocr_{engine_name}", 0)
            self._cache["ocr"][page] = {"regions": regions, "runs": runs,
                                        "ms": ms, "infer_ms": ms, "load_ms": load_ms,
                                        "engine": engine_name}
            self._save_json("ocr.json", self._cache["ocr"])
            self._render_dirty[page] = True
            texts = [r.get("text", "") for r in regions]
            self.log(f"ocr[{engine_name}] {page}: {len(regions)} regions, "
                     f"infer {ms}ms (load {load_ms}ms, {sum(1 for t in texts if t)} non-empty)")
            return {"page": page, "regions": regions, "runs": runs, "ms": ms, "infer_ms": ms, "load_ms": load_ms}

    # -------------------------------------------------------------- translate
    def translations(self, page: str) -> dict:
        return self._cache["translations"].get(page, {})

    def set_translation(self, page: str, region_id: str, text: str) -> dict:
        with self.lock:
            self._cache["translations"].setdefault(page, {})[region_id] = text
            self._save_json("translations.json", self._cache["translations"])
            self._render_dirty[page] = True
            return {"page": page, "region_id": region_id, "saved": True}

    def carry_translations(self, page: str) -> None:
        """After re-OCR: copy translations onto regions whose box survived."""
        regs = self._cache["ocr"].get(page, {}).get("regions", [])
        page_tr = self._cache["translations"].get(page, {})
        if not page_tr or not regs:
            return
        by_old_id = {}
        for r in regs:
            src = r.get("carried_from")
            if src and src in page_tr and not page_tr.get(r["id"]):
                by_old_id[r["id"]] = page_tr[src]
        if by_old_id:
            page_tr.update(by_old_id)
            self._save_json("translations.json", self._cache["translations"])
            self._render_dirty[page] = True

    def translate_page(self, page: str) -> dict:
        """Fill missing translations. Backend from settings.translate_backend:
        'google' (free gtx web endpoint) or 'lm-studio' (OpenAI-compatible).
        Results are cached in translations.json either way — only regions with
        no cached translation hit the network."""
        with self.lock:
            ocr = self._cache["ocr"].get(page)
            if not ocr or not ocr["regions"]:
                raise ValueError(f"no OCR regions for {page} — run OCR first")
            tr = self._cache["translations"].setdefault(page, {})
            targets = [r for r in ocr["regions"]
                       if r.get("text") and not tr.get(r["id"])]
            if not targets:
                return {"page": page, "translated": 0, "message": "nothing to translate"}
            backend = self.settings.get("translate_backend", "lm-studio")
            out = []
            if backend == "google":
                texts = [r["text"] for r in targets]
                translated = translate_google_batch(texts, self.settings.get("target_lang", "English"))
                for r, text in zip(targets, translated):
                    tr[r["id"]] = text
                    out.append({"id": r["id"], "text": text})
            else:
                for r in targets:
                    text = translate_one(
                        r["text"], self.settings["target_lang"],
                        self.settings["endpoint"], self.settings["model"])
                    tr[r["id"]] = text
                    out.append({"id": r["id"], "text": text})
            self._save_json("translations.json", self._cache["translations"])
            self._render_dirty[page] = True
            self.log(f"translate {page}: {len(out)} regions via {backend}")
            return {"page": page, "translated": len(out), "regions": out}

    # ----------------------------------------------------------------- render
    def _inpaint_paddle_det(self):
        """AOTInpainting.paddleDetector — the Paddle det engine wired for
        free-text line refinement whenever the det asset exists, independent
        of the chosen OCR engine (the app shares one det engine; here we
        reuse the OCR det singleton when it exists, else det-only)."""
        if self._paddle_det_unavailable:
            return None
        if self._paddle_det is None:
            import paddle_ocr
            if not paddle_ocr.DET_MODEL.exists():
                log("Paddle det asset missing — free-text boxes stay unrefined")
                self._paddle_det_unavailable = True
                return None
            if self._paddle is not None:
                self._paddle_det = self._paddle[0]
            else:
                log("loading PaddleOCR v6 det (free-text line refinement)…")
                self._paddle_det = paddle_ocr.PaddleDet()
        return self._paddle_det

    def _aot_inpainter(self):
        if self._aot is None:
            import aot_inpaint
            if not aot_inpaint.AotInpainter().ready:
                return None
            t0 = time.perf_counter()
            log("loading AOT-512 inpainting model (QUALITY mode)…")
            self._aot = aot_inpaint.AotInpainter()
            self._load_times["aot"] = round((time.perf_counter() - t0) * 1000, 1)
            log(f"AOT loaded in {self._load_times['aot']}ms")
        return self._aot if self._aot.ready else None

    def _bubble_segmenter(self):
        """Lazy manga109 bubble segmenter (Android OnnxBubbleSegmenter).
        Missing/invalid model -> None and the render path falls back to the
        rect fill; a failed page is never raised to the caller."""
        if self._segmenter is None:
            import segmentation
            if not segmentation.BubbleSegmenter().ready:
                if not getattr(self, "_segmenter_unavailable", False):
                    self._segmenter_unavailable = True
                    log(f"bubble segmentation OFF: model missing "
                        f"({segmentation.SEGMENTER_MODEL.name}) — rect-fill "
                        f"fallback")
                return None
            t0 = time.perf_counter()
            log("loading bubble segmenter (manga109 YOLO11-seg, 640 int8)…")
            self._segmenter = segmentation.BubbleSegmenter()
            self._load_times["segmenter"] = round((time.perf_counter() - t0) * 1000, 1)
            log(f"bubble segmenter loaded in {self._load_times['segmenter']}ms")
        return self._segmenter

    def bubble_masks(self, page: str) -> list:
        """Cached bubble masks for a page, run on the PRISTINE page_image.
        Sharing the mask across inpaint, segmentation viewer, and render
        avoids redundant ONNX passes and guarantees render sees the same
        bubble boundaries as inpaint (running segmentation on an already-
        inpainted image fails to detect smoothed bubbles)."""
        if not self.settings.get("bubble_segmentation", True):
            return []
        with self.lock:
            if page not in self._bubble_mask_cache:
                segmenter = self._bubble_segmenter()
                if segmenter is None:
                    return []
                try:
                    img = self.page_image(page)
                    t0 = time.perf_counter()
                    masks = segmenter.segment(img)
                    ms = round((time.perf_counter() - t0) * 1000, 1)
                    self._seg_times[page] = ms
                    self.log(f"segment {page}: {len(masks)} bubble masks ({ms}ms)")
                    self._bubble_mask_cache[page] = masks
                except Exception as e:
                    self.log(f"!! bubble segmentation error {page}: {e}")
                    self._bubble_mask_cache[page] = []
                    self._seg_times[page] = 0
            return self._bubble_mask_cache[page]

    def inpaint_page(self, page: str, force: bool = False, mode: str | None = None) -> dict:
        with self.lock:
            mode = (mode or self.settings.get("inpaint_mode", "quality")).upper()
            det_data = self._cache["detections"].get(page) or {}
            raw_boxes = det_data.get("boxes", [])
            ocr_data = self._cache["ocr"].get(page) or {}
            regions = ocr_data.get("regions", [])
            if not regions and not raw_boxes:
                self.detect_page(page)
                det_data = self._cache["detections"].get(page) or {}
                raw_boxes = det_data.get("boxes", [])
                conf = self.settings.get("conf", 0.45)
                regions = self._regions_at_conf(page, conf).get("regions", [])

            out_dir = self.studio_dir / "inpaint"
            mask_dir = self.studio_dir / "inpaint_mask"
            out_dir.mkdir(parents=True, exist_ok=True)
            mask_dir.mkdir(parents=True, exist_ok=True)
            stem = Path(page).stem
            out_path = out_dir / (stem + ".png")
            mask_path = mask_dir / (stem + ".png")

            if force or not out_path.exists() or not mask_path.exists():
                img = self.page_image(page)
                # 1. Bubble segmentation model (YOLO11-seg manga109)
                seg_masks = self.bubble_masks(page)

                # 2. Paddle Det line refinement
                paddle_det = self._inpaint_paddle_det()

                # 3. AOT neural inpainter (if QUALITY)
                aot = self._aot_inpainter() if mode == "QUALITY" else None

                # 4. Inpaint pipeline (AotReportBubbleFill + PushPull/AOT)
                import aot_inpaint
                bubble_erosion = int(self.settings.get("bubble_mask_erosion", 5))
                t0 = time.perf_counter()
                cleaned, mask, stats = aot_inpaint.inpaint_page_pipeline(
                    img, regions, raw_detections=raw_boxes,
                    seg_masks=seg_masks, mode=mode,
                    paddle_det=paddle_det, aot=aot,
                    bubble_erosion=bubble_erosion,
                )
                inp_ms = round((time.perf_counter() - t0) * 1000, 1)
                self._inpaint_times[page] = inp_ms

                cleaned.save(out_path)
                mask_img = Image.fromarray((mask.astype(np.uint8) * 255), mode="L")
                mask_img.save(mask_path)
                self._render_dirty[page] = True
                self.log(f"inpaint[{mode.lower()}] {page}: {stats.get('bubbleBoxes', 0)} bubbles, "
                         f"{stats.get('freeBoxes', 0)} free boxes -> {out_path.name} ({inp_ms}ms)")
            return {"page": page, "path": str(out_path), "mask_path": str(mask_path),
                    "infer_ms": self._inpaint_times.get(page, 0)}

    def inpainted_image(self, page: str) -> Image.Image:
        out_path = self.studio_dir / "inpaint" / (Path(page).stem + ".png")
        if not out_path.exists():
            self.inpaint_page(page)
        return Image.open(out_path).convert("RGB")

    def inpaint_mask_image(self, page: str) -> Image.Image:
        mask_path = self.studio_dir / "inpaint_mask" / (Path(page).stem + ".png")
        if mask_path.exists():
            return Image.open(mask_path).convert("L")
        w, h = self.page_dims(page)
        return Image.new("L", (w, h), 0)


    def segmentation_overlay_image(self, page: str) -> Image.Image:
        w, h = self.page_dims(page)
        masks = self.bubble_masks(page)
        overlay_np = np.zeros((h, w, 4), dtype=np.uint8)
        colors = [
            (56, 189, 248, 110),   # cyan
            (168, 85, 247, 110),   # purple
            (52, 211, 153, 110),   # emerald
            (251, 146, 60, 110),   # orange
            (244, 114, 182, 110),  # pink
        ]
        border_colors = [
            (56, 189, 248, 240),
            (168, 85, 247, 240),
            (52, 211, 153, 240),
            (251, 146, 60, 240),
            (244, 114, 182, 240),
        ]
        for idx, m in enumerate(masks):
            c_fill = colors[idx % len(colors)]
            c_border = border_colors[idx % len(border_colors)]
            for comp in getattr(m, "components", [1]):
                cid = getattr(comp, "id", comp)
                c_mask = m.component_mask(cid)
                overlay_np[c_mask] = c_fill
                dilated = ndimage.binary_dilation(c_mask, structure=np.ones((3, 3), bool))
                border = dilated & (~c_mask)
                overlay_np[border] = c_border
        return Image.fromarray(overlay_np, "RGBA")

    def segmentation_overlay_path(self, page: str) -> Path | None:
        assert self.studio_dir is not None
        sdir = self.studio_dir / "seg_overlay"
        out = sdir / (Path(page).stem + ".png")
        if out.exists():
            return out
        return None


    def segmentation_image(self, page: str) -> Image.Image:
        img = self.page_image(page).convert("RGBA")
        overlay_img = self.segmentation_overlay_image(page)
        result = Image.alpha_composite(img, overlay_img)
        return result.convert("RGB")

    def render_page(self, page: str, force: bool = False) -> dict:
        with self.lock:
            ocr = self._cache["ocr"].get(page)
            if not ocr:
                raise ValueError(f"no OCR data for {page} — run OCR first")
            out_dir = self.studio_dir / "render"
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / (Path(page).stem + ".png")
            if force or self._render_dirty.get(page, True) or not out_path.exists():
                t0 = time.perf_counter()
                # Start from clean inpainted background
                img = self.inpainted_image(page).copy()
                img.studio_page = page      # for bubble lookups in render_regions
                tr = self.translations(page)
                n = render_regions(img, ocr["regions"], tr,
                                   self.settings, self, page=page)
                img.save(out_path)
                self._render_dirty[page] = False
                render_ms = round((time.perf_counter() - t0) * 1000, 1)
                self._render_times[page] = render_ms
                self.log(f"render {page}: {n}/{len(ocr['regions'])} regions "
                         f"translated -> {out_path.name} ({render_ms}ms)")
            return {"page": page, "path": str(out_path),
                    "render_ms": self._render_times.get(page, 0)}

    def overlay_image(self, page: str, conf: float | None = None) -> Image.Image:
        conf = self.settings["conf"] if conf is None else conf
        regions = self._regions_at_conf(page, conf)["regions"]
        raw = self._cache["detections"].get(page) or {}
        raw_boxes = raw.get("boxes", [])
        img = self.page_image(page)
        draw = ImageDraw.Draw(img)

        # Draw raw bubble boxes first in subtle cyan/gray
        for r in raw_boxes:
            lbl = int(r[0])
            score = float(r[1])
            if lbl == 0 and len(r) >= 6 and score >= conf:
                b = Box(int(r[2]), int(r[3]), int(r[4]), int(r[5]))
                draw.rectangle(b.as_list(), outline=(120, 140, 160), width=2)

        for r in regions:
            b = Box(*r["box"])
            color = CLASS_COLORS.get(r["label"], (255, 255, 255))
            draw.rectangle(b.as_list(), outline=color, width=3)
            tag = f"{r['id']} {r['score']:.2f}"
            tw = draw.textlength(tag, font=_ui_font())
            draw.rectangle([b.x1, max(0, b.y1 - 18), b.x1 + tw + 6, b.y1], fill=color)
            draw.text((b.x1 + 3, max(0, b.y1 - 17)), tag, fill=(0, 0, 0),
                      font=_ui_font())
        return img

    def region_crop(self, page: str, region_id: str, scale: int = 2) -> Image.Image:
        key = (page, region_id)
        if key in self._crop_cache:
            return self._crop_cache[key]
        regions = self._cache["ocr"].get(page, {}).get("regions", [])
        r = next((x for x in regions if x["id"] == region_id), None)
        if r is None:
            raise FileNotFoundError(region_id)
        img = self.page_image(page).crop(tuple(r["ocr_box"]))
        if scale > 1 and min(img.size) < 300:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        self._crop_cache[key] = img
        return img

    # ---------------------------------------------------------------- process
    def process_page(self, page: str, conf: float | None = None,
                     translate: bool = True) -> dict:
        t0 = time.perf_counter()
        det = self.detect_page(page, conf)
        ocr = self.ocr_page(page, conf)
        inpaint = self.inpaint_page(page)
        self.carry_translations(page)
        if translate:
            try:
                self.translate_page(page)
            except Exception as e:
                self.log(f"!! translate skipped: {e}")
        rend = self.render_page(page)
        return {"page": page, "detections": det, "ocr": {
            "regions": ocr["regions"], "runs": ocr["runs"], "ms": ocr["ms"]},
            "inpaint": inpaint["path"],
            "render": rend["path"],
            "translations": self.translations(page),
            "total_ms": round((time.perf_counter() - t0) * 1000, 1)}

    def page_data(self, page: str) -> dict:
        conf = float(self.settings.get("conf", 0.45))
        ocr = self._cache["ocr"].get(page, {})
        det = self._cache["detections"].get(page)
        raw_boxes = []
        if det and "boxes" in det:
            for b in det["boxes"]:
                score = float(b[1])
                if score >= conf:
                    raw_boxes.append({
                        "label": int(b[0]),
                        "score": round(score, 3),
                        "box": [int(v) for v in b[2:6]],
                        "class": N_CLASSES.get(int(b[0]), "unknown"),
                    })

        # Regions: if OCR data exists, filter it by conf.
        # If OCR hasn't run yet, but detection has run, provide filtered detector regions!
        if ocr and "regions" in ocr:
            regions = [r for r in ocr["regions"] if r.get("score", 1.0) >= conf]
        elif det:
            regions = self._regions_at_conf(page, conf).get("regions", [])
        else:
            regions = []

        det_infer = det.get("infer_ms", det.get("model_ms", 0)) if det else 0
        ocr_infer = ocr.get("infer_ms", ocr.get("ms", 0)) if ocr else 0
        seg_infer = self._seg_times.get(page, 0)
        inp_infer = self._inpaint_times.get(page, 0)
        ren_infer = self._render_times.get(page, 0)

        ocr_engine_name = ocr.get("engine", self._ocr_engine_name())
        metrics = {
            "load_times": dict(self._load_times),
            "detector": {
                "load_ms": self._load_times.get("detector", 0),
                "infer_ms": det_infer,
            },
            "ocr": {
                "load_ms": self._load_times.get(f"ocr_{ocr_engine_name}", 0),
                "infer_ms": ocr_infer,
                "regions_count": len(regions),
                "engine": ocr_engine_name,
            },
            "segmenter": {
                "load_ms": self._load_times.get("segmenter", 0),
                "infer_ms": seg_infer,
            },
            "inpaint": {
                "load_ms": self._load_times.get("aot", 0),
                "infer_ms": inp_infer,
            },
            "render": {
                "render_ms": ren_infer,
            },
            "total_infer_ms": round(det_infer + ocr_infer + seg_infer + inp_infer + ren_infer, 1)
        }

        return {"page": page,
                "regions": regions,
                "raw_boxes": raw_boxes,
                "runs": ocr.get("runs", {}),
                "ocr_ms": ocr.get("ms", 0),
                "metrics": metrics,
                "detected": det is not None,
                "translations": self.translations(page),
                "has_reference": (self.reference is not None
                                  and (self.reference / page).exists())}


def iou_at_least(a: list, b: list, thresh: float) -> bool:
    from boxgeom import iou
    return iou(Box(*a), Box(*b)) >= thresh


def clamp_pad(box: Box, page_wh: list) -> Box:
    return Box(max(0, box.x1 - OCR_PAD), max(0, box.y1 - OCR_PAD),
               min(page_wh[0], box.x2 + OCR_PAD),
               min(page_wh[1], box.y2 + OCR_PAD))


# ================================================================== detector
class Detector:
    """detector-v4-s ONNX via the app's exact protocol (RT-DETR-style)."""

    def __init__(self, path: Path):
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.log_severity_level = 3
        self.sess = ort.InferenceSession(str(path), opts,
                                         providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name
        self.sizes_name = self.sess.get_inputs()[1].name

    def detect(self, img: Image.Image) -> list[dict]:
        resized = img.resize((640, 640), Image.BILINEAR)
        arr = np.asarray(resized, np.float32) / np.float32(255.0)
        x = np.ascontiguousarray(arr.transpose(2, 0, 1))[None]
        sizes = np.array([[img.width, img.height]], dtype=np.int64)
        labels, boxes, scores = self.sess.run(
            None, {self.input_name: x, self.sizes_name: sizes})
        out = []
        for lab, score, box in zip(labels[0], scores[0], boxes[0]):
            s = float(score)
            if np.isnan(s) or s < CONF_FLOOR:
                continue
            out.append({"label": int(lab), "score": s,
                        "box": [int(round(v)) for v in box[:4]]})
        return out


# ======================================================================= OCR
class OcrEngine:
    """Batch-capable MangaOCR via the T927 lab (corrected semantics)."""

    def __init__(self, profile: str = "android", max_batch: int = 8):
        self.vocab = lab_assets.load_vocab()
        derived = lab_graphs.ensure_derived(lab_assets.model_paths(),
                                            LAB_DIR / "work")
        self.bank = lab_sessions.SessionBank(derived["paths"], profile)
        self.gate = derived["gate"]
        self.max_batch = max_batch

    def gate_summary(self) -> dict:
        return {"pass": self.gate["pass"],
                "encoder_max_abs_diff": self.gate["encoder_max_abs_diff"],
                "decoder_max_abs_diff": self.gate["decoder_max_abs_diff"]}

    def decode_regions(self, crops: list[Image.Image]) -> tuple[list[dict], dict, float]:
        from lab.state import OUTCOME_EOS, OUTCOME_POSITION_LIMIT
        pixels = [(i, lab_preprocess(c)) for i, c in enumerate(crops)]
        out: list[dict | None] = [None] * len(crops)
        cb = self.bank.counters()
        t0 = time.perf_counter()
        for s in range(0, len(pixels), self.max_batch):
            window = pixels[s:s + self.max_batch]
            fakes = [lab_corpus.Crop(crop_id=str(i), path=Path("."),
                                     category="studio", expected=None)
                     for i, _ in window]
            rows, _, _ = lab_batched.run_microbatch(
                self.bank, fakes, [p for _, p in window], self.vocab)
            for (fake, row) in rows:
                i = int(fake.crop_id)
                tokens = row.tokens
                confs = row.confs
                out[i] = {
                    "text": lab_dc.android_postprocess(
                        lab_dc.raw_text(self.vocab, tokens)),
                    "raw_text": lab_dc.raw_text(self.vocab, tokens),
                    "tokens": len(tokens),
                    "token_ids": tokens,
                    "eos_position": row.eos_position,
                    "eos": row.outcome == OUTCOME_EOS,
                    "position_limit": row.outcome == OUTCOME_POSITION_LIMIT,
                    "confidence": round(sum(confs) / len(confs), 4) if confs else None,
                }
        runs = {k: v - cb[k] for k, v in self.bank.counters().items()}
        ms = round((time.perf_counter() - t0) * 1000, 1)
        filled = [o or {"text": "", "raw_text": "", "tokens": 0, "token_ids": [],
                        "eos_position": None, "eos": False,
                        "position_limit": False, "confidence": None}
                  for o in out]
        return filled, runs, ms


# =================================================================== render
APP_DIR = Path(__file__).resolve().parents[2]
ANIMEACE_PATH = APP_DIR / "app" / "src" / "main" / "res" / "font" / "animeace.ttf"
MANGA_MASTER_PATH = APP_DIR / "app" / "src" / "main" / "res" / "font" / "manga_master_bb.ttf"


def _load_font(size: int, settings: dict):
    candidates = []
    if settings.get("font_path"):
        candidates.append(settings["font_path"])
    if ANIMEACE_PATH.exists():
        candidates.append(str(ANIMEACE_PATH))
    if MANGA_MASTER_PATH.exists():
        candidates.append(str(MANGA_MASTER_PATH))
    candidates += ["C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/msyh.ttc",
                   "C:/Windows/Fonts/msgothic.ttc",
                   "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
    for c in candidates:
        try:
            return ImageFont.truetype(c, size)
        except Exception:
            continue
    return ImageFont.load_default()


def _ui_font():
    return _load_font(14, {})


def _is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return ((0x4E00 <= cp <= 0x9FFF) or
            (0x3400 <= cp <= 0x4DBF) or
            (0x20000 <= cp <= 0x2A6DF) or
            (0x2A700 <= cp <= 0x2B73F) or
            (0x2B740 <= cp <= 0x2B81F) or
            (0xF900 <= cp <= 0xFAFF) or
            (0x2F800 <= cp <= 0x2FA1F) or
            (0x3000 <= cp <= 0x303F) or
            (0x3040 <= cp <= 0x309F) or
            (0x30A0 <= cp <= 0x30FF) or
            (0x31F0 <= cp <= 0x31FF) or
            (0xAC00 <= cp <= 0xD7AF) or
            (0xFF00 <= cp <= 0xFFEF) or
            (0xFE30 <= cp <= 0xFE4F))


def _tokenize(text: str) -> list[tuple[str, str]]:
    """Tokenize matching Android TextLineBreaker:
    contract:
      1. forced newline stays forced
      2. whitespace separates
      3. CJK graphemes are breakable individually
      4. Latin runs end after a '-' (source hyphen stays with the run)
    """
    tokens = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '\n':
            tokens.append(('NL', '\n'))
            i += 1
        elif ch.isspace():
            tokens.append(('WS', ' '))
            i += 1
        elif _is_cjk(ch):
            tokens.append(('WORD', ch))
            i += 1
        else:
            start = i
            while i < n and not _is_cjk(text[i]) and not text[i].isspace() and text[i] != '\n':
                if text[i] == '-' and i > start:
                    i += 1
                    break
                i += 1
            tokens.append(('WORD', text[start:i]))
    return tokens


def _prewrap(draw: ImageDraw.ImageDraw, text: str, font, max_w: float) -> list[str]:
    """Greedy line wrap matching Android TextLineBreaker.prewrap."""
    tokens = _tokenize(text)
    lines = []
    current = ""
    for kind, val in tokens:
        if kind == 'NL':
            lines.append(current.rstrip())
            current = ""
        elif kind == 'WS':
            cand = current + " "
            if draw.textlength(cand, font=font) > max_w and current:
                lines.append(current.rstrip())
                current = ""
            else:
                current = cand
        elif kind == 'WORD':
            cand = current + val
            if draw.textlength(cand, font=font) > max_w:
                if current:
                    lines.append(current.rstrip())
                    current = ""
                # Only break long words (>= 8 chars) that cannot fit within max_w
                if len(val) >= 8 and draw.textlength(val, font=font) > max_w:
                    sub = ""
                    for ch in val:
                        hyphen_cand = sub + ch + "-"
                        if len(sub) >= 3 and draw.textlength(hyphen_cand, font=font) > max_w:
                            lines.append(sub + "-")
                            sub = ch
                        else:
                            sub += ch
                    current = sub
                else:
                    current = val
            else:
                current = cand
    if current:
        lines.append(current.rstrip())
    return [l for l in lines if l] or [""]


def _fit_text(draw, text: str, box: Box, settings: dict):
    margin = 6
    max_w, max_h = max(10, box.w - 2 * margin), max(10, box.h - 2 * margin)
    font_scale = float(settings.get("font_scale", 1.0))
    size = max(8, min(int(max_h / 1.15), 48))
    while size >= 8:
        scaled_size = max(8, int(round(size * font_scale)))
        font = _load_font(scaled_size, settings)
        lines = _prewrap(draw, text, font, max_w)
        try:
            ascent, descent = font.getmetrics()
            line_h = max(scaled_size, ascent + descent)
        except Exception:
            line_h = scaled_size * 1.25
        total_h = len(lines) * line_h
        max_lw = max(draw.textlength(l, font=font) for l in lines) if lines else 0
        if total_h <= max_h and max_lw <= max_w:
            return font, lines, line_h
        size -= 1
    scaled_size = max(8, int(round(8 * font_scale)))
    font = _load_font(scaled_size, settings)
    try:
        ascent, descent = font.getmetrics()
        line_h = max(scaled_size, ascent + descent)
    except Exception:
        line_h = scaled_size * 1.25
    return font, _prewrap(draw, text, font, max_w), line_h


def _fit_text_in_mask(draw, text: str, box: Box, comp_mask, settings: dict):
    """Phase A inscribed-rectangle fit (MaskTextRegionPlanner stand-in):
    same descent as _fit_text, but the centered text block (inflated by the
    stroke width) must lie FULLY inside the bubble component mask, so oval
    edges cannot clip glyphs. Before giving up on a size, lines are wrapped
    progressively narrower — an oval fits narrow tall blocks that a wide
    wrap would reject, keeping the font size up."""
    margin = 6
    max_w, max_h = max(10, box.w - 2 * margin), max(10, box.h - 2 * margin)
    font_scale = float(settings.get("font_scale", 1.0))
    size = max(8, min(int(max_h / 1.15), 48))
    while size >= 8:
        scaled_size = max(8, int(round(size * font_scale)))
        font = _load_font(scaled_size, settings)
        try:
            ascent, descent = font.getmetrics()
            line_h = max(scaled_size, ascent + descent)
        except Exception:
            line_h = scaled_size * 1.25
        sw = max(2, int(round(font.size * 0.12)))
        for frac in (1.0, 0.92, 0.85, 0.78, 0.70, 0.62, 0.55):
            lines = _prewrap(draw, text, font, max_w * frac)
            total_h = len(lines) * line_h + 2 * sw
            if total_h > max_h:
                break
            block_w = max(draw.textlength(l, font=font) for l in lines) + 2 * sw
            rx1 = int(box.cx - block_w / 2)
            ry1 = int(box.cy - total_h / 2)
            sub = comp_mask[max(0, ry1):ry1 + int(total_h) + 1,
                            max(0, rx1):rx1 + int(block_w) + 1]
            if sub.size and bool(sub.all()):
                return font, lines, line_h
        size -= 1
    scaled_size = max(8, int(round(8 * font_scale)))
    font = _load_font(scaled_size, settings)
    try:
        ascent, descent = font.getmetrics()
        line_h = max(scaled_size, ascent + descent)
    except Exception:
        line_h = scaled_size * 1.25
    return font, _prewrap(draw, text, font, max_w), line_h


def _draw_text_clipped(img: Image.Image, mask, comp: int, box: Box,
                       font, lines: list[str], line_h: float,
                       fill: tuple, stroke: tuple, stroke_w: int) -> None:
    """Draw the text block centered in the fit box, clipped to the bubble
    component mask (no ink outside the bubble). The layer covers the WHOLE
    block — where the bubble bulges beyond the fit box, the inscribed fit
    legally places ink outside the fit box, so a fit-box-sized layer would
    cut glyphs at its own border."""
    from PIL import ImageChops
    ld0 = ImageDraw.Draw(img)
    block_w = max(ld0.textlength(l, font=font) for l in lines) + 2 * stroke_w
    total_h = len(lines) * line_h + 2 * stroke_w
    lw_ = int(block_w) + 4
    lh_ = int(total_h) + 4
    lx1 = int(box.cx - lw_ / 2)
    ly1 = int(box.cy - lh_ / 2)
    # keep inside image boundaries
    ox = max(0, -lx1); oy = max(0, -ly1)
    lx1, ly1 = lx1 + ox, ly1 + oy
    layer = Image.new("RGBA", (lw_, lh_), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    y = oy + max(0.0, (lh_ - oy - total_h) / 2.0) + stroke_w
    for line in lines:
        lw = ld.textlength(line, font=font)
        x = lw_ / 2.0 - lw / 2.0
        ld.text((x, y), line, font=font,
                fill=fill + (255,), stroke_width=stroke_w,
                stroke_fill=stroke + (255,))
        y += line_h
    h, w = mask.labels.shape
    wy1, wx1 = max(0, ly1), max(0, lx1)
    wy2, wx2 = min(h, ly1 + lh_), min(w, lx1 + lw_)
    if wy2 > wy1 and wx2 > wx1:
        comp_crop = Image.fromarray(
            (mask.labels[wy1:wy2, wx1:wx2] == comp).astype(np.uint8) * 255, "L")
        full = Image.new("L", (lw_, lh_), 0)
        full.paste(comp_crop, (wx1 - lx1, wy1 - ly1))
        layer.putalpha(ImageChops.multiply(layer.getchannel("A"), full))
        img.paste(layer, (lx1, ly1), layer)


def render_regions(img: Image.Image, regions: list[dict],
                   translations: dict[str, str], settings: dict,
                   pipe: "Pipeline | None" = None,
                   page: str | None = None) -> int:
    """Draw translated text onto the pre-inpainted image.

    Layout and rendering mirror Android's TextLineBreaker and TextLayoutPlanner:
    - Font: Anime Ace (app/src/main/res/font/animeace.ttf)
    - Line wrapping: deterministic prewrap with CJK grapheme breakability & Latin token atoms
    - Inscribed mask fitting: centered text blocks constrained to bubble contours
    - Color: RenderColorEstimator 2-means post-erase luma polarity + font-proportional stroke
    - Drawing: direct stroke-then-fill clipped to bubble component mask
    """
    todo = [r for r in regions if (translations.get(r["id"]) or "").strip()]
    if not todo:
        return 0

    _seg = _seg_module()
    seg_masks: list | None = None
    if pipe is not None and settings.get("bubble_segmentation", True):
        p = page or getattr(img, "studio_page", None)
        if p:
            seg_masks = pipe.bubble_masks(p)
        else:
            segmenter = pipe._bubble_segmenter()
            if segmenter is not None:
                try:
                    seg_masks = segmenter.segment(img)
                except Exception as e:
                    log(f"!! bubble segmentation lookup error: {e}")
                    seg_masks = None

    assignment: dict[str, tuple] = {}
    if seg_masks:
        for r in todo:
            a = _seg.assign_region_component(seg_masks, Box(*r["box"]).as_list())
            if a is not None:
                assignment[r["id"]] = a

    p = page or getattr(img, "studio_page", None)
    bubbles: list[Box] = []
    if pipe is not None and p:
        det_data = pipe._cache["detections"].get(p) or {}
        raw_b = det_data.get("boxes", [])
        bubbles = [Box(int(b[2]), int(b[3]), int(b[4]), int(b[5]))
                   for b in raw_b if int(b[0]) == 0 and len(b) >= 6]

    comp_counts: dict[tuple, int] = {}
    for r in todo:
        a = assignment.get(r["id"])
        if a is not None:
            comp_counts[a] = comp_counts.get(a, 0) + 1

    np_page = np.asarray(img)
    draw = ImageDraw.Draw(img)

    for r in todo:
        text = (translations.get(r["id"]) or "").strip()
        rbox = Box(*r["box"])
        pbox = select_parent(rbox, bubbles) if bubbles else None
        anchor_cx = pbox.cx if pbox is not None else rbox.cx
        anchor_cy = pbox.cy if pbox is not None else rbox.cy
        a = assignment.get(r["id"])
        if a is not None:
            mask, comp = a
            c_obj = next((c for c in mask.components if c.id == comp), None)
            if c_obj is not None:
                cb = c_obj.bounds
            else:
                cys, cxs = np.where(mask.labels == comp)
                cb = (int(cxs.min()), int(cys.min()), int(cxs.max()) + 1, int(cys.max()) + 1)

            if comp_counts.get(a, 0) == 1:
                # Expand symmetrically from the anchor center within the bubble mask bounds
                half_w = max(rbox.w / 2.0, min(anchor_cx - cb[0], cb[2] - anchor_cx))
                half_h = max(rbox.h / 2.0, min(anchor_cy - cb[1], cb[3] - anchor_cy))
                fit_box = Box(int(anchor_cx - half_w), int(anchor_cy - half_h),
                              int(anchor_cx + half_w), int(anchor_cy + half_h))
            else:
                gx1, gy1 = max(0, rbox.x1), max(0, rbox.y1)
                inter = mask.labels[gy1:rbox.y2, gx1:rbox.x2] == comp
                if inter.any():
                    ys, xs = np.where(inter)
                    fit_box = Box(gx1 + int(xs.min()), gy1 + int(ys.min()),
                                  gx1 + int(xs.max()) + 1, gy1 + int(ys.max()) + 1)
                else:
                    fit_box = rbox
        else:
            mask, comp = None, 0
            fit_box = pbox if pbox is not None else rbox

        if mask is not None:
            font, lines, line_h = _fit_text_in_mask(
                draw, text, fit_box, mask.component_mask(comp), settings)
        else:
            font, lines, line_h = _fit_text(draw, text, fit_box, settings)

        stroke_w = max(2, int(round(font.size * 0.12)))
        fill, stroke, bg_luma = _seg_estimate_color(
            np_page, rbox.as_list(),
            mask.bounds if mask is not None else None)

        if mask is not None:
            _draw_text_clipped(img, mask, comp, fit_box, font, lines,
                               line_h, fill, stroke, stroke_w)
        else:
            total_h = len(lines) * line_h
            y = fit_box.cy - total_h / 2.0
            for line in lines:
                lw = draw.textlength(line, font=font)
                draw.text((fit_box.cx - lw / 2.0, y), line, font=font,
                          fill=fill, stroke_width=stroke_w,
                          stroke_fill=stroke)
                y += line_h

    return len(todo)


def _seg_module():
    """Lazy import of the bubble segmentation/cleaner/color port."""
    import segmentation
    return segmentation


def _seg_estimate_color(np_page, box, parent_bounds):
    """RenderColorEstimator.estimate against the post-erase page array."""
    return _seg_module().estimate_text_color(np_page, box, parent_bounds)


def _page_of(img: Image.Image) -> str:
    """Reverse-lookup the page name for the image the pipeline is rendering
    (set by render_page before calling render_regions)."""
    return getattr(img, "studio_page", "")


def _erase_one(img: Image.Image, box: Box, settings: dict) -> None:
    fill = _erase_fill_color(img, box, settings)
    ImageDraw.Draw(img).rectangle(box.as_list(), fill=fill)


# ================================================================ translate
def translate_google_batch(texts: list[str], target_lang: str = "English") -> list[str]:
    """Robust multi-endpoint Google Translate client with page-level batching.
    Batches texts using delimiters to reduce HTTP requests by 90-95%, avoiding 429s,
    and falls back across multiple endpoints (clients5 Chrome dict proxy, gtx, and MyMemory)."""
    if not texts:
        return []
    lang_map = {"English": "en", "Japanese": "ja", "Spanish": "es",
                "French": "fr", "German": "de", "Portuguese": "pt",
                "Italian": "it", "Russian": "ru", "Korean": "ko",
                "Chinese": "zh-CN"}
    tl = lang_map.get(target_lang, "en")
    cleaned_texts = [t.replace("\n", " ").strip() for t in texts]

    # Strategy 1: Batch translation via clients5 (Chrome dict endpoint) with ||| delimiter
    if len(cleaned_texts) > 1:
        delim = " ||| "
        combined = delim.join(cleaned_texts)
        try:
            q = urllib.parse.quote(combined)
            url = f"https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=ja&tl={tl}&q={q}"
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                res_str = data[0] if isinstance(data, list) and data else str(data)
                parts = [p.strip() for p in res_str.split("|||")]
                if len(parts) == len(texts):
                    return parts
        except Exception:
            pass

    # Strategy 2: Per-item fallback with multi-endpoint failover
    results = []
    for t in cleaned_texts:
        if not t:
            results.append("")
            continue
        res = None
        q = urllib.parse.quote(t)
        # Attempt A: clients5
        try:
            url = f"https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=ja&tl={tl}&q={q}"
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                res = data[0] if isinstance(data, list) and data else str(data)
        except Exception:
            pass

        # Attempt B: gtx endpoint
        if not res:
            try:
                url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl=ja&tl={tl}&dt=t&q={q}"
                req = urllib.request.Request(url, headers={
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
                with urllib.request.urlopen(req, timeout=10) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    res = "".join(seg[0] for seg in data[0] if seg and seg[0]).strip()
            except Exception:
                pass

        # Attempt C: MyMemory free API fallback
        if not res:
            try:
                url = f"https://api.mymemory.translated.net/get?q={q}&langpair=ja|{tl}"
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=10) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    res = data.get("responseData", {}).get("translatedText", "")
            except Exception:
                pass

        results.append(res or t)
        time.sleep(0.04)

    return results


def translate_google(text: str, target_lang: str) -> str:
    res = translate_google_batch([text], target_lang)
    return res[0] if res else text


def translate_one(text: str, target_lang: str, endpoint: str, model: str) -> str:
    url = endpoint.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "temperature": 0.2,
        "messages": [
            {"role": "system",
             "content": f"You translate manga dialogue to {target_lang}. "
                        f"Reply with ONLY the translation, no notes, no quotes."},
            {"role": "user", "content": text},
        ],
    }
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"].strip()


DEFAULT_SETTINGS = {
    "conf": 0.45,
    "max_batch": 8,
    "target_lang": "English",
    "endpoint": "http://127.0.0.1:1234/v1",
    "model": "local-model",
    "font_path": "",
    "font_scale": 1.0,
    "erase": "auto",
    "ocr_engine": "mangaocr",        # "mangaocr" | "paddle"
    "inpaint_mode": "quality",       # "quality" (AOT-512) | "fast" (classical)
    "translate_backend": "google",   # "google" | "lm-studio"
    "bubble_segmentation": True,     # manga109 YOLO11-seg (on when model loads)
    "bubble_mask_erosion": 5,        # px erosion for bubble seg mask edge reduction
}

PIPELINE = Pipeline()
