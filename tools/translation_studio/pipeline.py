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
                     reading_order_rtl, select_parent, suppress_cross_label)

DETECTOR_PATH = (REPO_ROOT / "app/src/main/assets/models/detection"
                 / "detector-v4-s_int8.onnx")
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
OCR_PAD = 12                # RoiPageRecognitionEngine pad around text boxes
CONF_FLOOR = 0.05           # what we persist (slider re-filters above this)
N_CLASSES = {0: "bubble", 1: "text_bubble", 2: "text_free"}
CLASS_COLORS = {0: (150, 150, 160), 1: (70, 200, 120), 2: (240, 170, 60)}

# Director-locked test chapter: opened automatically unless --chapter overrides.
DEFAULT_CHAPTER = (r"C:\Users\User\Downloads\manga_test_chapters"
                   r"\ore-ni-trauma-wo-ataeta-joshitachi-ga-chirachira-"
                   r"mitekuru-kedo-zannen-desu-ga-teokure-desu_ch16")


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
    def open_folders(self, chapter: str, reference: str | None) -> dict:
        with self.lock:
            ch = Path(chapter)
            if not ch.is_dir():
                raise ValueError(f"chapter folder not found: {chapter}")
            self.chapter = ch
            self.reference = Path(reference) if reference and Path(reference).is_dir() else None
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
            self.settings = dict(DEFAULT_SETTINGS)
            self.settings.update(self._load_json("settings.json", {}))
            self._render_dirty = {p: True for p in self.pages}
            self.log(f"opened chapter: {ch} ({len(self.pages)} pages)"
                     + (f", reference: {self.reference}" if self.reference else ""))
            return self.state()

    def save_settings(self, patch: dict) -> dict:
        with self.lock:
            self.settings.update(patch)
            self._save_json("settings.json", self.settings)
            return self.settings

    def state(self) -> dict:
        return {
            "chapter": str(self.chapter) if self.chapter else None,
            "reference": str(self.reference) if self.reference else None,
            "pages": self.pages,
            "settings": self.settings,
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

    def thumbnail(self, page: str, size: int = 160) -> Image.Image:
        """Small cached JPEG for the navigator rail / overview."""
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
        return Image.open(out).convert("RGB")

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
        """Chapter-level aggregate computed from the existing caches only."""
        rows = []
        for p in self.pages:
            det = self._cache["detections"].get(p) is not None
            ocr = self._cache["ocr"].get(p)
            regs = (ocr or {}).get("regions", [])
            tr = self.translations(p)
            translated = sum(1 for r in regs if (tr.get(r["id"]) or "").strip())
            rendered = False
            if self.studio_dir is not None:
                out = self.studio_dir / "render" / (Path(p).stem + ".png")
                # Status display: a rendered PNG exists. (Re-opening the chapter
                # marks pages re-render-dirty, but that must not flip the UI
                # status back to "not rendered" — /img/render still regenerates
                # those on demand via _render_dirty.)
                rendered = out.exists()
            rows.append({
                "page": p,
                "detected": det,
                "ocr": ocr is not None,
                "regions": len(regs),
                "translated": translated,
                "rendered": rendered,
                "ocr_ms": (ocr or {}).get("ms", 0) or 0,
                "position_limit": any(r.get("position_limit") for r in regs),
            })
        errors = [{"page": r["page"], "reason": "OCR position limit"}
                  for r in rows if r["position_limit"]]
        slowest = max(rows, key=lambda r: r["ocr_ms"], default=None)
        return {"chapter": str(self.chapter) if self.chapter else None,
                "pages": rows, "errors": errors, "slowest": slowest}

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
            self.log(f"loading detector: {DETECTOR_PATH.name}")
            self._detector = Detector(DETECTOR_PATH)
        return self._detector

    def detect_page(self, page: str, conf: float | None = None,
                    force: bool = False) -> dict:
        """Runs (or reuses) the detector; re-filters at the requested conf."""
        with self.lock:
            conf = self.settings["conf"] if conf is None else conf
            raw = self._cache["detections"].get(page)
            if raw is None or force:
                t0 = time.perf_counter()
                img = self.page_image(page)
                raw_all = self._detector_model().detect(img)
                raw = {"boxes": [[d["label"], round(d["score"], 4), *d["box"]]
                                 for d in raw_all],
                       "page_wh": [img.width, img.height],
                       "model_ms": round((time.perf_counter() - t0) * 1000, 1)}
                self._cache["detections"][page] = raw
                self._save_json("detections.json", self._cache["detections"])
                self.log(f"detect {page}: {len(raw['boxes'])} raw "
                         f"({raw['model_ms']}ms)")
            regions = self._regions_at_conf(page, conf)
            return {"page": page, "conf": conf, **regions,
                    "model_ms": raw["model_ms"]}

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
            log("initializing OCR (derived graphs + B=1 gate)…")
            self._ocr = OcrEngine()
            g = self._ocr.gate
            log(f"OCR ready — gate {'PASS' if g['pass'] else 'FAILED'} "
                f"(enc {g['encoder_max_abs_diff']:.1e}, dec "
                f"{g['decoder_max_abs_diff']:.1e})")
        return self._ocr

    def _paddle_engines(self):
        """PaddleOCR v6 small det+rec (desktop port of the Android engines)."""
        if self._paddle is None:
            import paddle_ocr
            log("initializing PaddleOCR v6 small (det + rec)…")
            self._paddle = (paddle_ocr.PaddleDet(), paddle_ocr.PaddleRec())
            log(f"PaddleOCR ready — dict={len(self._paddle[1].dictionary)} "
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
            self._cache["ocr"][page] = {"regions": regions, "runs": runs,
                                        "ms": ms, "engine": engine_name}
            self._save_json("ocr.json", self._cache["ocr"])
            self._render_dirty[page] = True
            texts = [r.get("text", "") for r in regions]
            self.log(f"ocr[{engine_name}] {page}: {len(regions)} regions, "
                     f"{ms}ms ({sum(1 for t in texts if t)} non-empty)")
            return {"page": page, "regions": regions, "runs": runs, "ms": ms}

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
            for r in targets:
                if backend == "google":
                    text = translate_google(r["text"],
                                            self.settings["target_lang"])
                else:
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
            log("loading AOT-512 inpainting model (QUALITY mode)…")
            self._aot = aot_inpaint.AotInpainter()
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
            log("loading bubble segmenter (manga109 YOLO11-seg, 640 int8)…")
            self._segmenter = segmentation.BubbleSegmenter()
        return self._segmenter

    def render_page(self, page: str, force: bool = False) -> dict:
        with self.lock:
            ocr = self._cache["ocr"].get(page)
            if not ocr:
                raise ValueError(f"no OCR data for {page} — run OCR first")
            out_dir = self.studio_dir / "render"
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / (Path(page).stem + ".png")
            if force or self._render_dirty.get(page, True) or not out_path.exists():
                img = self.page_image(page)
                img.studio_page = page      # for bubble lookups in render_regions
                tr = self.translations(page)
                n = render_regions(img, ocr["regions"], tr,
                                   self.settings, self)
                img.save(out_path)
                self._render_dirty[page] = False
                self.log(f"render {page}: {n}/{len(ocr['regions'])} regions "
                         f"translated -> {out_path.name}")
            return {"page": page, "path": str(out_path)}

    def overlay_image(self, page: str, conf: float | None = None) -> Image.Image:
        conf = self.settings["conf"] if conf is None else conf
        regions = self._regions_at_conf(page, conf)["regions"]
        img = self.page_image(page)
        draw = ImageDraw.Draw(img)
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
                     translate: bool = False) -> dict:
        t0 = time.perf_counter()
        det = self.detect_page(page, conf)
        ocr = self.ocr_page(page, conf)
        self.carry_translations(page)
        if translate:
            try:
                self.translate_page(page)
            except Exception as e:
                self.log(f"!! translate skipped: {e}")
        self.render_page(page)
        return {"page": page, "detections": det, "ocr": {
            "regions": ocr["regions"], "runs": ocr["runs"], "ms": ocr["ms"]},
            "translations": self.translations(page),
            "total_ms": round((time.perf_counter() - t0) * 1000, 1)}

    def page_data(self, page: str) -> dict:
        ocr = self._cache["ocr"].get(page, {})
        det = self._cache["detections"].get(page)
        return {"page": page,
                "regions": ocr.get("regions", []),
                "runs": ocr.get("runs", {}),
                "ocr_ms": ocr.get("ms", 0),
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
def _load_font(size: int, settings: dict):
    candidates = [settings.get("font_path")] if settings.get("font_path") else []
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


def _median_ring_color(img: Image.Image, box: Box, band: int = 4) -> tuple:
    w, h = img.size
    strips = []
    x1, y1 = max(0, box.x1 - band), max(0, box.y1 - band)
    x2, y2 = min(w, box.x2 + band), min(h, box.y2 + band)
    if x2 - x1 < 2 or y2 - y1 < 2:
        return (255, 255, 255)
    ring = np.asarray(img.crop((x1, y1, x2, y2)))
    inner = np.asarray(img.crop(box.as_list()))
    mask = np.ones(ring.shape[:2], bool)
    mask[band:band + inner.shape[0] if inner.shape[0] else band,
         band:band + inner.shape[1] if inner.shape[1] else band] = False
    sel = ring[mask]
    if sel.size == 0:
        return (255, 255, 255)
    med = np.median(sel.reshape(-1, sel.shape[-1]), axis=0)
    return tuple(int(v) for v in med)


def _wrap(draw, text: str, font, max_w: float) -> list[str]:
    words = text.split(" ")
    use_words = len(words) > 1
    tokens = words if use_words else list(text)
    lines, cur = [], ""
    for t in tokens:
        cand = (cur + " " + t).strip() if use_words else cur + t
        if draw.textlength(cand, font=font) <= max_w or not cur:
            cur = cand
        else:
            lines.append(cur)
            cur = t
    if cur:
        lines.append(cur)
    return lines or [""]


def _fit_text(draw, text: str, box: Box, settings: dict):
    margin = 6
    max_w, max_h = box.w - 2 * margin, box.h - 2 * margin
    size = max(9, min(int(max_h / 1.25), 40))
    while size >= 9:
        font = _load_font(int(size * settings.get("font_scale", 1.0))
                          if settings.get("font_scale", 1.0) != 1.0 else size,
                          settings)
        lines = _wrap(draw, text, font, max_w)
        line_h = size * 1.2
        if len(lines) * line_h <= max_h:
            return font, lines, line_h
        size -= 1
    font = _load_font(9, settings)
    return font, _wrap(draw, text, font, max_w), 11.0


def _erase_fill_color(img: Image.Image, box: Box, settings: dict) -> tuple:
    if settings.get("erase", "auto") == "white":
        return (255, 255, 255)
    return _median_ring_color(img, box)


def render_regions(img: Image.Image, regions: list[dict],
                   translations: dict[str, str], settings: dict,
                   pipe: "Pipeline | None" = None) -> int:
    """Draw translated text.

    Erase routing mirrors Android AOTInpainting.inpaintRegions: bubble text
    ALWAYS uses the classical cleaner (inpaintReportBubbles -> the ported
    SmartBubbleTextCleaner in segmentation.py, mask-constrained to the
    bubble component); only free text (label 2 outside bubbles) gets the
    AOT-512 neural model in QUALITY mode; FAST and every neural fallback use
    the PushPullGradient push/pull fill over Paddle line-refined masks
    (aot_inpaint.inpaint_free_text — the app's inpaintRegions free-text leg).

    When bubble segmentation is active (settings.bubble_segmentation and the
    manga109 model loads), each region is assigned to a bubble component
    (MaskGeometry.componentForRectangleDeterministic); erase is limited to
    that component's pixels, text is fitted into the component's bounding
    geometry and clipped to the component mask (Phase A layout fidelity).
    Text color is estimated AFTER erase via RenderColorEstimator (seeded
    2-means, pure black/white fill, luma-inverse stroke of width
    max(2, 0.12 * font)) — the app re-derives post-inpaint.
    """
    todo = [r for r in regions if (translations.get(r["id"]) or "").strip()]
    if not todo:
        return 0

    bubbles = []
    if pipe is not None:
        raw = pipe._cache["detections"].get(_page_of(img)) or {}
        bubbles = [Box(*[int(v) for v in r[2:]])
                   for r in raw.get("boxes", []) if int(r[0]) == 0]

    def is_free_text(r: dict) -> bool:
        if r.get("label") != 2:
            return False
        b = Box(*r["box"])
        return not any(intersection_area(b, bub) > 0 for bub in bubbles)

    quality = settings.get("inpaint_mode", "fast") == "quality"

    # ---- bubble segmentation: once per page, reused for erase and layout.
    _seg = _seg_module()
    seg_masks: list | None = None
    if pipe is not None and settings.get("bubble_segmentation", True):
        segmenter = pipe._bubble_segmenter()
        if segmenter is not None:
            try:
                t0 = time.perf_counter()
                seg_masks = segmenter.segment(img)
                ms = round((time.perf_counter() - t0) * 1000, 1)
                ncomp = sum(len(m.components) for m in seg_masks)
                log(f"segment {_page_of(img)}: {len(seg_masks)} bubble "
                    f"masks, {ncomp} usable components ({ms}ms)")
            except Exception as e:
                log(f"!! bubble segmentation failed ({e}) — rect-fill "
                    f"fallback for bubble regions")
                seg_masks = None

    assignment: dict[str, tuple] = {}
    if seg_masks:
        for r in todo:
            a = _seg.assign_region_component(seg_masks,
                                             Box(*r["box"]).as_list())
            if a is not None:
                assignment[r["id"]] = a
        log(f"segment {_page_of(img)}: {len(assignment)}/{len(todo)} "
            f"regions matched to bubble components")

    # ---- bubble-route text with no segmentation component: Android never
    # rect-fills these — inpaintReportBubbles gives them a dynamic pill mask
    # (pad MASK_PAD=8, radius clamp(shortSide/8, 2, 16)) + AotReportBubbleFill
    # fillAndBlend (smooth 12, feather 12). The solid rect fill remains only
    # when the segmenter is unavailable entirely, or for erase=white.
    import aot_inpaint
    free_regions = [r for r in todo if is_free_text(r)]
    free_ids = {r["id"] for r in free_regions}
    unassigned = [r for r in todo
                  if r["id"] not in free_ids and r["id"] not in assignment]
    if settings.get("erase", "auto") == "white":
        pill_regions, rect_regions = [], unassigned
    elif seg_masks is not None:
        pill_regions, rect_regions = unassigned, []
    else:
        pill_regions, rect_regions = [], unassigned
    if pill_regions:
        try:
            aot_inpaint.inpaint_report_bubbles(
                img, [Box(*r["box"]).as_list() for r in pill_regions])
            log(f"erase: {len(pill_regions)} bubble-route regions -> "
                f"report pill fill (smooth=12, feather=12)")
        except Exception as e:
            log(f"!! report pill fill failed ({e}) — rect fallback")
            rect_regions = pill_regions
    for r in rect_regions:
        _erase_one(img, Box(*r["box"]), settings)

    # ---- free text: Paddle line refinement -> clustered groups -> neural
    # (QUALITY) or push/pull (FAST + all neural fallbacks) per group. Mirrors
    # AOTInpainting.inpaintRegions' free-text leg; the solid rect fill is
    # never used for free text.
    if free_regions:
        aot = pipe._aot_inpainter() if quality and pipe is not None else None
        if quality and aot is None:
            log("inpaint[quality]: AOT-512 model unavailable — push/pull fallback")
        paddle_det = pipe._inpaint_paddle_det() if pipe is not None else None
        stats = aot_inpaint.inpaint_free_text(
            img, [Box(*r["box"]).as_list() for r in free_regions],
            mode="QUALITY" if quality else "FAST",
            paddle_det=paddle_det, aot=aot)
        log(f"inpaint[{'quality' if quality else 'fast'}]: "
            f"{stats['neural']} neural / {stats['push_pull']} push-pull "
            f"({stats['clusteredGroups']} groups, {stats['paddleLines']} "
            f"paddle lines, {stats['fallback']} box fallbacks); "
            f"bubble text classical")

    np_page = np.asarray(img).copy()
    if seg_masks:
        cleaner = _seg.SmartBubbleCleaner()
        cleaned = 0
        for r in todo:
            a = assignment.get(r["id"])
            if a is None or r["id"] in free_ids:
                continue
            mask, comp = a
            msg = cleaner.clean_region(
                np_page, Box(*r["box"]).as_list(),
                component_mask=mask.component_mask(comp))
            cleaned += 1
            log(f"erase[{r['id']}] bubble-clean {msg}")
        if cleaned and settings.get("erase", "auto") == "white":
            log("note: erase=white applies to rect fallback only; bubble "
                "clean derives the fill from the bubble interior")
        img.paste(Image.fromarray(np_page))

    # ---- draw. Color is estimated against the POST-ERASE bitmap
    # (RenderColorEstimator.recomputeFor ordering).
    draw = ImageDraw.Draw(img)
    for r in todo:
        text = (translations.get(r["id"]) or "").strip()
        rbox = Box(*r["box"])
        a = assignment.get(r["id"])
        if a is not None:
            mask, comp = a
            # Phase A usable area = component ∩ region box. Bounding the
            # component alone lets two regions of a merged bubble instance
            # collapse onto one center; intersecting with the detector box
            # keeps each region's text in its own area (the role
            # MaskTextRegionPlanner plays in the app) while the clip below
            # still forbids ink outside the bubble shape.
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
            fit_box = rbox
        font, lines, line_h = _fit_text(draw, text, fit_box, settings)
        if mask is not None:
            font, lines, line_h = _fit_text_in_mask(
                draw, text, fit_box, mask.component_mask(comp), settings)
        stroke_w = max(0, int(round(_seg.compute_stroke_width(font.size))))
        fill, stroke, bg_luma = _seg_estimate_color(
            np_page, rbox.as_list(),
            mask.bounds if mask is not None else None)
        log(f"color[{r['id']}] bgLuma={bg_luma:.0f} -> "
            f"{'white' if fill[0] > 0 else 'black'} fill, "
            f"stroke w={stroke_w}")
        if mask is not None:
            _draw_text_clipped(img, mask, comp, fit_box, font, lines,
                               line_h, fill, stroke, stroke_w)
        else:
            y = fit_box.y1 + 6
            for line in lines:
                lw = draw.textlength(line, font=font)
                draw.text((fit_box.cx - lw / 2, y), line, font=font,
                          fill=fill, stroke_width=stroke_w,
                          stroke_fill=stroke)
                y += line_h
    return len(todo)


def _fit_text_in_mask(draw, text: str, box: Box, comp_mask, settings: dict):
    """Phase A inscribed-rectangle fit (MaskTextRegionPlanner stand-in):
    same descent as _fit_text, but the centered text block (inflated by the
    stroke width) must lie FULLY inside the bubble component mask, so oval
    edges cannot clip glyphs. Before giving up on a size, lines are wrapped
    progressively narrower — an oval fits narrow tall blocks that a wide
    wrap would reject, keeping the font size up."""
    margin = 6
    max_w, max_h = box.w - 2 * margin, box.h - 2 * margin
    size = max(9, min(int(max_h / 1.25), 40))
    while size >= 9:
        font = _load_font(int(size * settings.get("font_scale", 1.0))
                          if settings.get("font_scale", 1.0) != 1.0 else size,
                          settings)
        line_h = size * 1.2
        sw = int(round(_seg_module().compute_stroke_width(font.size)))
        for frac in (1.0, 0.9, 0.8, 0.72, 0.64, 0.56, 0.5):
            lines = _wrap(draw, text, font, max_w * frac)
            total_h = len(lines) * line_h + 2 * sw
            if total_h > max_h:
                break          # narrower wraps are taller: stop this size
            block_w = max(draw.textlength(l, font=font) for l in lines) \
                + 2 * sw
            # comp_mask is PAGE-space; check the block rect in absolute page
            # coordinates (box.cx/cy are already page-absolute).
            rx1 = int(box.cx - block_w / 2)
            ry1 = int(box.cy - total_h / 2)
            sub = comp_mask[max(0, ry1):ry1 + int(total_h) + 1,
                            max(0, rx1):rx1 + int(block_w) + 1]
            if sub.size and bool(sub.all()):
                return font, lines, line_h
        size -= 1
    font = _load_font(9, settings)
    return font, _wrap(draw, text, font, max_w), 11.0


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
    lw_ = int(block_w) + 2
    lh_ = int(total_h) + 2
    lx1 = int(box.cx - lw_ / 2)
    ly1 = int(box.cy - lh_ / 2)
    # keep the window inside the page
    ox = max(0, -lx1); oy = max(0, -ly1)
    lx1, ly1 = lx1 + ox, ly1 + oy
    layer = Image.new("RGBA", (lw_, lh_), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    y = oy + max(0, (lh_ - oy) - total_h) / 2.0 + stroke_w
    for line in lines:
        lw = ld.textlength(line, font=font)
        ld.text((lw_ / 2.0 - lw / 2, y), line, font=font,
                fill=fill + (255,), stroke_width=stroke_w,
                stroke_fill=stroke + (255,))
        y += line_h
    h, w = mask.labels.shape
    wy1, wx1 = max(0, ly1), max(0, lx1)
    wy2, wx2 = min(h, ly1 + lh_), min(w, lx1 + lw_)
    comp_crop = Image.fromarray(
        (mask.labels[wy1:wy2, wx1:wx2] == comp).astype(np.uint8) * 255, "L")
    full = Image.new("L", (lw_, lh_), 0)
    full.paste(comp_crop, (wx1 - lx1, wy1 - ly1))
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), full))
    img.paste(layer, (lx1, ly1), layer)


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
def translate_google(text: str, target_lang: str) -> str:
    """Free Google web endpoint (client=gtx). Best-effort language mapping —
    manga OCR text is Japanese source; target follows the studio setting."""
    lang_map = {"English": "en", "Japanese": "ja", "Spanish": "es",
                "French": "fr", "German": "de", "Portuguese": "pt",
                "Italian": "it", "Russian": "ru", "Korean": "ko",
                "Chinese": "zh-CN"}
    tl = lang_map.get(target_lang, "en")
    quoted = urllib.parse.quote(text)
    url = ("https://translate.googleapis.com/translate_a/single"
           f"?client=gtx&sl=ja&tl={tl}&dt=t&q={quoted}")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return "".join(seg[0] for seg in data[0] if seg and seg[0]).strip()


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
    "inpaint_mode": "fast",          # "fast" (classical) | "quality" (AOT-512)
    "translate_backend": "google",   # "google" | "lm-studio"
    "bubble_segmentation": True,     # manga109 YOLO11-seg (on when model loads)
}

PIPELINE = Pipeline()
