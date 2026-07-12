from __future__ import annotations

import concurrent.futures
import io
import json
import os
import re
import shutil
import threading
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw

from inference.block_sort import dedupe_post_ocr, sort_blocks_reading_order
from inference.detector import DetectionBox, OnnxPageTextDetector, deterministic_detect
from inference.inpaint_aot import AotInpainter
from inference.inpaint_pipeline import Cleaner
from inference.ocr_manga import MangaOcrEngine
from logging_config import log_failure
from render.text_renderer import TextRenderer
from translate.translator import PlaceholderTranslator, build_translator

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".tiff"}

# Bumped from 3 → 4: earlier revisions lied about inpaint success (the cleaned
# image was a copy of the original when onnxruntime was absent). rev 4 forces a
# reprocess under the honest, classical-always pipeline.
CURRENT_INPAINT_REVISION = 4


def _find_parent_bubble(text_box: DetectionBox, bubble_boxes: list[DetectionBox]) -> DetectionBox | None:
    """Find the smallest bubble box whose center contains the text box center."""
    cx = (text_box.x1 + text_box.x2) / 2.0
    cy = (text_box.y1 + text_box.y2) / 2.0
    best: DetectionBox | None = None
    best_area = float("inf")
    for bubble in bubble_boxes:
        if bubble.x1 <= cx <= bubble.x2 and bubble.y1 <= cy <= bubble.y2:
            area = (bubble.x2 - bubble.x1) * (bubble.y2 - bubble.y1)
            if area < best_area:
                best = bubble
                best_area = area
    return best


def _mask_box(x1: int, y1: int, x2: int, y2: int, label: int) -> dict[str, int]:
    return {"x1": int(x1), "y1": int(y1), "x2": int(x2), "y2": int(y2), "label": int(label)}


def _block_box(block: dict[str, Any]) -> tuple[int, int, int, int] | None:
    bbox = block.get("bbox", {})
    x1 = int(bbox.get("x1", 0))
    y1 = int(bbox.get("y1", 0))
    x2 = int(bbox.get("x2", 0))
    y2 = int(bbox.get("y2", 0))
    return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None


def _box_iou(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1 = max(ax1, bx1)
    iy1 = max(ay1, by1)
    ix2 = min(ax2, bx2)
    iy2 = min(ay2, by2)
    inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
    area_a = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    area_b = max(0, bx2 - bx1) * max(0, by2 - by1)
    union = area_a + area_b - inter
    return 0.0 if union <= 0 else inter / union


def _compute_inpaint_mask_boxes(
    blocks: list[dict[str, Any]],
    all_text_detections: list[DetectionBox],
    width: int,
    height: int,
) -> list[dict[str, int]]:
    """Android PageInpaintingPlanner.computeMask equivalent.

    Persist the erase mask at OCR time so inpainting does not depend on the final
    translated/rendered block list. Label 0 boxes are parent bubbles; labels 1/2
    are text erase boxes.
    """
    readable = [block for block in blocks if str(block.get("text", "")).strip()]

    mask: list[dict[str, int]] = []
    seen: set[tuple[int, int, int, int, int]] = set()

    for block in readable:
        parent = block.get("parentBbox", {})
        px1 = max(0, int(parent.get("x1", 0)))
        py1 = max(0, int(parent.get("y1", 0)))
        px2 = min(width, int(parent.get("x2", 0)))
        py2 = min(height, int(parent.get("y2", 0)))
        if px2 > px1 and py2 > py1:
            key = (px1, py1, px2, py2, 0)
            if key not in seen:
                mask.append(_mask_box(px1, py1, px2, py2, 0))
                seen.add(key)

    for block in readable:
        box = _block_box(block)
        if box is None:
            continue
        x1, y1, x2, y2 = box
        label = int(block.get("label", 1))
        key = (x1, y1, x2, y2, label)
        if key not in seen:
            mask.append(_mask_box(x1, y1, x2, y2, label))
            seen.add(key)

    all_ocr_boxes = [box for block in blocks if (box := _block_box(block)) is not None]
    for det in all_text_detections:
        dx1 = max(0, int(det.x1))
        dy1 = max(0, int(det.y1))
        dx2 = min(width, int(det.x2))
        dy2 = min(height, int(det.y2))
        if dx2 <= dx1 or dy2 <= dy1:
            continue
        expanded = (max(0, dx1 - 3), max(0, dy1 - 3), min(width, dx2 + 3), min(height, dy2 + 3))
        if any(_box_iou(expanded, ocr_box) > 0.40 for ocr_box in all_ocr_boxes):
            continue
        key = (dx1, dy1, dx2, dy2, int(det.label))
        if key not in seen:
            mask.append(_mask_box(dx1, dy1, dx2, dy2, int(det.label)))
            seen.add(key)

    return mask

DATA_ROOT = Path(__file__).resolve().parent.parent / "data"
CHAPTERS_ROOT = DATA_ROOT / "chapters"
REGISTRY_PATH = DATA_ROOT / "registry.json"


def _natural_sort_key(name: str) -> list[Any]:
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", name)]


@dataclass
class _Chapter:
    chapter_id: str
    directory: Path
    pages: list[str] = field(default_factory=list)
    target_lang: str = "ENGLISH"


class ChapterProcessor:
    def __init__(
        self,
        detector: Any | None = None,
        ocr: Any | None = None,
        inpainter: Any | None = None,
        translator: Any | None = None,
        max_workers: int = 4,
        ocr_det: Any | None = None,
        paddle_det: Any | None = None,
        model_load_errors: dict[str, str] | None = None,
    ) -> None:
        self.detector = detector
        self.ocr = ocr
        self.ocr_det = ocr_det
        # ``paddle_det`` refines free-text erase boxes into tight text-line boxes
        # before inpainting (Android AOTInpainting.refineFreeTextBoxes). Falls
        # back to the coarse detector box per-region when Paddle finds nothing.
        self.paddle_det = paddle_det
        # ``inpainter`` is the neural AOT handle (may be None when onnxruntime
        # or the model is unavailable). The Cleaner wraps it; classical tier-1
        # always runs regardless, neural is the explicit QUALITY enhancement.
        self.neural_cleaner = inpainter
        self.cleaner = Cleaner(neural=inpainter, paddle_det=paddle_det)
        self.model_load_errors = dict(model_load_errors or {})
        self.translator = translator
        self.renderer = TextRenderer()
        self._chapters: dict[str, _Chapter] = {}

        DATA_ROOT.mkdir(parents=True, exist_ok=True)
        CHAPTERS_ROOT.mkdir(parents=True, exist_ok=True)
        self._registry = self._load_registry()
        self._registry_lock = threading.Lock()

        self._active_models: dict[str, str | None] = {"detector": None, "ocr": None, "inpaint": None}
        if detector is not None:
            self._active_models["detector"] = "detector-v4-s_int8"
        if ocr is not None:
            self._active_models["ocr"] = "manga_ocr"
        if inpainter is not None:
            self._active_models["inpaint"] = "aot"

        workers = max(1, min(max_workers, os.cpu_count() or 1))
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="manga-worker")
        self._batch_status: dict[str, dict[str, Any]] = {}
        self._page_locks: dict[str, threading.Lock] = {}
        self._page_locks_guard = threading.Lock()

        for entry in self._registry.get("chapters", []):
            cid = entry.get("chapter_id", "")
            cdir = CHAPTERS_ROOT / cid
            if cdir.exists():
                self._chapters[cid] = _Chapter(
                    chapter_id=cid,
                    directory=cdir,
                    pages=entry.get("pages", []),
                    target_lang=entry.get("target_lang", "ENGLISH"),
                )

    # ── Registry ───────────────────────────────────────────────────────────

    def _load_registry(self) -> dict[str, Any]:
        if REGISTRY_PATH.exists():
            try:
                return json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
            except Exception:
                pass
        return {"chapters": [], "settings": {"target_lang": "ENGLISH", "mode": "FAST"}}

    def _save_registry_locked(self) -> None:
        try:
            REGISTRY_PATH.write_text(json.dumps(self._registry, indent=2), encoding="utf-8")
        except Exception:
            pass

    def _save_registry(self) -> None:
        with self._registry_lock:
            self._save_registry_locked()

    def _now_iso(self) -> str:
        return datetime.now().isoformat(timespec="seconds")

    def _touch_registry(self, chapter_id: str, page_index: int) -> None:
        with self._registry_lock:
            for entry in self._registry.get("chapters", []):
                if entry.get("chapter_id") == chapter_id:
                    entry["last_viewed"] = self._now_iso()
                    entry["last_page"] = page_index
                    break
            self._save_registry_locked()

    # ── Page locking ───────────────────────────────────────────────────────

    def _get_page_lock(self, chapter_id: str, page_index: int) -> threading.Lock:
        key = f"{chapter_id}:{page_index}"
        with self._page_locks_guard:
            if key not in self._page_locks:
                self._page_locks[key] = threading.Lock()
            return self._page_locks[key]

    # ── Upload ──────────────────────────────────────────────────────────────

    def extract_zip(
        self,
        zip_bytes: bytes,
        target_lang: str,
        display_name: str = "",
    ) -> tuple[str, list[str]]:
        chapter_id = uuid.uuid4().hex[:12]
        chapter_dir = CHAPTERS_ROOT / chapter_id
        original_dir = chapter_dir / "original"
        cache_dir = chapter_dir / "cache"
        original_dir.mkdir(parents=True, exist_ok=True)
        cache_dir.mkdir(parents=True, exist_ok=True)

        image_names: list[str] = []
        with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                ext = Path(info.filename).suffix.lower()
                if ext not in IMAGE_EXTENSIONS:
                    continue
                dest_name = Path(info.filename).name
                if not dest_name:
                    continue
                with zf.open(info) as src, (original_dir / dest_name).open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                image_names.append(dest_name)

        if not image_names:
            shutil.rmtree(chapter_dir, ignore_errors=True)
            raise ValueError("ZIP archive contains no images")

        image_names.sort(key=_natural_sort_key)

        clean_name = display_name or "Chapter"
        clean_name = Path(clean_name).stem if clean_name.endswith(".zip") else clean_name

        self._chapters[chapter_id] = _Chapter(
            chapter_id=chapter_id,
            directory=chapter_dir,
            pages=image_names,
            target_lang=target_lang,
        )

        with self._registry_lock:
            self._registry["chapters"].insert(0, {
                "chapter_id": chapter_id,
                "name": clean_name,
                "page_count": len(image_names),
                "pages": image_names,
                "target_lang": target_lang,
                "created_at": self._now_iso(),
                "last_viewed": self._now_iso(),
                "last_page": 0,
            })
            self._registry["settings"]["target_lang"] = target_lang
            self._save_registry_locked()

        return chapter_id, image_names

    # ── Chapter management ─────────────────────────────────────────────────

    def list_chapters(self) -> list[dict[str, Any]]:
        with self._registry_lock:
            result = []
            for entry in self._registry.get("chapters", []):
                cid = entry.get("chapter_id", "")
                if (CHAPTERS_ROOT / cid).exists():
                    result.append(dict(entry))
            return result

    def get_chapter_meta(self, chapter_id: str) -> dict[str, Any] | None:
        with self._registry_lock:
            for entry in self._registry.get("chapters", []):
                if entry.get("chapter_id") == chapter_id:
                    return dict(entry)
        return None

    def delete_chapter(self, chapter_id: str) -> bool:
        with self._registry_lock:
            before = len(self._registry.get("chapters", []))
            self._registry["chapters"] = [
                c for c in self._registry.get("chapters", []) if c.get("chapter_id") != chapter_id
            ]
            if len(self._registry["chapters"]) == before:
                return False
            self._save_registry_locked()
        self._chapters.pop(chapter_id, None)
        self._batch_status.pop(chapter_id, None)
        cdir = CHAPTERS_ROOT / chapter_id
        if cdir.exists():
            shutil.rmtree(cdir, ignore_errors=True)
        return True

    def get_settings(self) -> dict[str, Any]:
        with self._registry_lock:
            return dict(self._registry.get("settings", {"target_lang": "ENGLISH"}))

    def update_settings(self, target_lang: str | None = None, mode: str | None = None) -> None:
        with self._registry_lock:
            self._registry.setdefault("settings", {})
            if target_lang:
                self._registry["settings"]["target_lang"] = target_lang
            if mode:
                self._registry["settings"]["mode"] = mode
            self._save_registry_locked()

    def get_chapter(self, chapter_id: str) -> _Chapter | None:
        return self._chapters.get(chapter_id)

    def get_page_result(self, chapter_id: str, page_index: int) -> dict[str, Any]:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            raise KeyError(chapter_id)
        if page_index < 0 or page_index >= len(chapter.pages):
            raise IndexError(page_index)

        cache_dir = chapter.directory / "cache"
        result_path = cache_dir / f"{page_index}_result.json"
        if not result_path.exists():
            return {"processed": False, "page_index": page_index}

        result = json.loads(result_path.read_text(encoding="utf-8"))
        cleaned_path = cache_dir / f"{page_index}_cleaned.png"
        if not cleaned_path.exists() or int(result.get("inpaintRevision", 0)) < CURRENT_INPAINT_REVISION:
            result.update({
                "processed": False,
                "inpaintStatus": "FAILED",
                "renderStatus": "PENDING",
                "errorMessage": "Cleaned image is missing or stale; reprocess this page.",
            })
            return result
        return self._with_artifact_status(result, cache_dir, page_index)

    def _with_artifact_status(self, result: dict[str, Any], cache_dir: Path, page_index: int) -> dict[str, Any]:
        cleaned_path = cache_dir / f"{page_index}_cleaned.png"
        rendered_path = cache_dir / f"{page_index}_rendered.png"
        out = dict(result)
        current = cleaned_path.exists() and int(result.get("inpaintRevision", 0)) >= CURRENT_INPAINT_REVISION
        out["processed"] = current
        out.setdefault("ocrStatus", "READY")
        out["cleanedImageName"] = cleaned_path.name if cleaned_path.exists() else None
        out["renderedImageName"] = rendered_path.name if rendered_path.exists() else None
        out["inpaintRevision"] = int(result.get("inpaintRevision", 0)) if cleaned_path.exists() else 0
        # Preserve the honest status written by the pipeline (READY/PARTIAL/SKIPPED).
        # Only downgrade to FAILED if the cleaned artifact is actually gone/stale.
        if current:
            out.setdefault("inpaintStatus", "READY")
            out["renderStatus"] = "READY" if rendered_path.exists() else "PENDING"
        else:
            out["inpaintStatus"] = "FAILED"
            out["renderStatus"] = "PENDING"
        if current and out.get("errorMessage") in (
            "Cleaned image is missing; reprocess this page.",
            "Cleaned image is missing or stale; reprocess this page.",
        ):
            out["errorMessage"] = None
        return out

    def _save_png(self, image: Image.Image, path: Path) -> None:
        tmp = path.with_suffix(path.suffix + ".tmp")
        image.save(tmp, format="PNG")
        tmp.replace(path)

    # ── Single-page processing ─────────────────────────────────────────────

    def process_page(self, chapter_id: str, page_index: int, mode: str = "QUALITY") -> dict[str, Any]:
        lock = self._get_page_lock(chapter_id, page_index)
        with lock:
            return self._process_page_unlocked(chapter_id, page_index, mode=mode)

    def detect_page(self, chapter_id: str, page_index: int) -> dict[str, Any]:
        """Detect + OCR only (no cleaning). Writes blocks + mask to cache."""
        lock = self._get_page_lock(chapter_id, page_index)
        with lock:
            chapter = self._chapters.get(chapter_id)
            if chapter is None:
                raise KeyError(chapter_id)
            cache_dir = chapter.directory / "cache"
            page_name, image, width, height = self._load_page_image(chapter, page_index)
            blocks, inpaint_mask_boxes, failures = self._detect_and_ocr(
                image, width, height, chapter_id=chapter_id, page_index=page_index,
            )
            result = {
                "page_index": page_index,
                "page_name": page_name,
                "width": width,
                "height": height,
                "blocks": blocks,
                "inpaintMaskBoxes": inpaint_mask_boxes,
                "translated": False,
                "processed": False,
                "ocrStatus": "READY",
                "inpaintStatus": "PENDING",
                "renderStatus": "PENDING",
                "translationStatus": "PENDING",
                "cleanedImageName": None,
                "renderedImageName": None,
                "inpaintRevision": 0,
                "inpaint_engine": None,
                "failures": failures,
                "errorMessage": None,
                "models": self._models_snapshot(),
            }
            (cache_dir / f"{page_index}_result.json").write_text(json.dumps(result), encoding="utf-8")
            self._touch_registry(chapter_id, page_index)
            return result

    def inpaint_page(self, chapter_id: str, page_index: int, mode: str = "QUALITY") -> dict[str, Any]:
        """Clean an already-detected page. Refuses to render over the original."""
        lock = self._get_page_lock(chapter_id, page_index)
        with lock:
            chapter = self._chapters.get(chapter_id)
            if chapter is None:
                raise KeyError(chapter_id)
            cache_dir = chapter.directory / "cache"
            result_path = cache_dir / f"{page_index}_result.json"
            if not result_path.exists():
                # Nothing detected yet — run detect first.
                self.detect_page(chapter_id, page_index)
            result = json.loads(result_path.read_text(encoding="utf-8"))

            _, image, width, height = self._load_page_image(chapter, page_index)
            outcome = self.cleaner.clean(
                image, result.get("inpaintMaskBoxes"), result.get("blocks", []), mode=mode,
                chapter_id=chapter_id, page=page_index,
            )
            cleaned_path = cache_dir / f"{page_index}_cleaned.png"
            self._save_png(outcome.image, cleaned_path)

            result.update({
                "inpaintStatus": outcome.status,
                "inpaint_engine": outcome.engine,
                "cleanedImageName": cleaned_path.name,
                "inpaintRevision": CURRENT_INPAINT_REVISION,
                "processed": outcome.status in ("READY", "SKIPPED"),
                "renderStatus": "PENDING",
                "failures": (result.get("failures") or []) + outcome.failures,
                "models": self._models_snapshot(neural_available=outcome.neural_available),
            })
            result_path.write_text(json.dumps(result), encoding="utf-8")
            return result

    def _process_page_unlocked(self, chapter_id: str, page_index: int, mode: str = "QUALITY") -> dict[str, Any]:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            raise KeyError(chapter_id)
        if page_index < 0 or page_index >= len(chapter.pages):
            raise IndexError(page_index)

        cache_dir = chapter.directory / "cache"
        result_path = cache_dir / f"{page_index}_result.json"
        if result_path.exists():
            cached = json.loads(result_path.read_text(encoding="utf-8"))
            cleaned_path = cache_dir / f"{page_index}_cleaned.png"
            rendered_path = cache_dir / f"{page_index}_rendered.png"
            current_inpaint = int(cached.get("inpaintRevision", 0)) >= CURRENT_INPAINT_REVISION
            if cleaned_path.exists() and current_inpaint:
                if not rendered_path.exists():
                    cleaned = Image.open(cleaned_path).convert("RGB")
                    blocks = cached.get("blocks", [])
                    self.renderer.estimate_block_colors(cleaned, blocks)
                    rendered = self.renderer.render(cleaned, blocks)
                    self._save_png(rendered, rendered_path)
                    cached["renderedImageName"] = rendered_path.name
                    cached["renderStatus"] = "READY"
                    result_path.write_text(json.dumps(cached), encoding="utf-8")
                self._touch_registry(chapter_id, page_index)
                return self._with_artifact_status(cached, cache_dir, page_index)

        page_name, image, width, height = self._load_page_image(chapter, page_index)

        # Stage 1: detect + OCR (with post-OCR dedupe + reading-order sort).
        blocks, inpaint_mask_boxes, detect_failures = self._detect_and_ocr(
            image, width, height, chapter_id=chapter_id, page_index=page_index,
        )

        # Stage 2: clean via the Cleaner (classical tier-1 always; neural QUALITY tier,
        # failures logged and reported — never silently swapped).
        outcome = self.cleaner.clean(
            image, inpaint_mask_boxes, blocks, mode=mode,
            chapter_id=chapter_id, page=page_index,
        )
        cleaned = outcome.image

        # Stage 3: estimate colors on the CLEANED image, then render.
        self.renderer.estimate_block_colors(cleaned, blocks)
        rendered = self.renderer.render(cleaned, blocks)

        cleaned_path = cache_dir / f"{page_index}_cleaned.png"
        rendered_path = cache_dir / f"{page_index}_rendered.png"
        self._save_png(cleaned, cleaned_path)
        self._save_png(rendered, rendered_path)

        all_failures = detect_failures + outcome.failures
        inpaint_ok = outcome.status in ("READY", "SKIPPED")
        result = {
            "page_index": page_index,
            "page_name": page_name,
            "width": width,
            "height": height,
            "blocks": blocks,
            "inpaintMaskBoxes": inpaint_mask_boxes,
            "translated": False,
            "processed": inpaint_ok,
            "ocrStatus": "READY",
            "inpaintStatus": outcome.status,
            "inpaint_engine": outcome.engine,
            "renderStatus": "READY" if inpaint_ok else "PARTIAL",
            "translationStatus": "PENDING",
            "cleanedImageName": cleaned_path.name,
            "renderedImageName": rendered_path.name,
            "inpaintRevision": CURRENT_INPAINT_REVISION,
            "failures": all_failures,
            "errorMessage": None if inpaint_ok else f"inpaint {outcome.status.lower()}: {len(outcome.failures)} failure(s)",
            "models": self._models_snapshot(neural_available=outcome.neural_available),
        }
        result_path.write_text(json.dumps(result), encoding="utf-8")
        self._touch_registry(chapter_id, page_index)
        return self._with_artifact_status(result, cache_dir, page_index)

    def _load_page_image(self, chapter: "_Chapter", page_index: int):
        if page_index < 0 or page_index >= len(chapter.pages):
            raise IndexError(page_index)
        page_name = chapter.pages[page_index]
        original_path = chapter.directory / "original" / page_name
        image = Image.open(original_path).convert("RGB")
        return page_name, image, image.size[0], image.size[1]

    def _detect_and_ocr(
        self, image: Image.Image, width: int, height: int, *,
        chapter_id: str, page_index: int,
    ) -> tuple[list[dict[str, Any]], list[dict[str, int]], list[dict[str, Any]]]:
        """Detect text boxes, OCR them, dedupe, sort into reading order, build mask.

        Returns (blocks, inpaint_mask_boxes, failures). Each failure is a
        structured entry from ``log_failure``.
        """
        failures: list[dict[str, Any]] = []
        try:
            detections = (
                self.detector.detect(image) if self.detector is not None else deterministic_detect(width, height)
            )
        except Exception as exc:
            failures.append(log_failure("detect", f"{type(exc).__name__}: {exc}",
                                        chapter_id=chapter_id, page=page_index, exc=exc))
            detections = deterministic_detect(width, height)

        bubble_boxes = [d for d in detections if d.label == 0]
        text_boxes = [d for d in detections if d.label in (1, 2)]

        blocks: list[dict[str, Any]] = []
        for box in text_boxes:
            x1 = max(0, int(box.x1))
            y1 = max(0, int(box.y1))
            x2 = min(width, int(box.x2))
            y2 = min(height, int(box.y2))
            if x2 <= x1 or y2 <= y1:
                continue

            parent = _find_parent_bubble(box, bubble_boxes)
            if parent is not None:
                px1 = max(0, int(parent.x1))
                py1 = max(0, int(parent.y1))
                px2 = min(width, int(parent.x2))
                py2 = min(height, int(parent.y2))
                parent_w = px2 - px1
                parent_h = py2 - py1
            else:
                px1 = py1 = px2 = py2 = 0
                parent_w = parent_h = 0

            # ocr_det (Paddle DB) tightens the crop when available.
            if self.ocr_det is not None:
                try:
                    det_crop = image.crop((x1, y1, x2, y2))
                    lines = self.ocr_det.detect_lines(det_crop)
                    if lines:
                        min_x = min(l["x1"] for l in lines)
                        min_y = min(l["y1"] for l in lines)
                        max_x = max(l["x2"] for l in lines)
                        max_y = max(l["y2"] for l in lines)
                        x1 = max(0, x1 + min_x - 2)
                        y1 = max(0, y1 + min_y - 2)
                        x2 = min(width, x1 + max_x + 2)
                        y2 = min(height, y1 + max_y + 2)
                except Exception as exc:
                    failures.append(log_failure("ocr/det", f"{type(exc).__name__}: {exc}",
                                                chapter_id=chapter_id, page=page_index, exc=exc))

            crop = image.crop((x1, y1, x2, y2))
            try:
                text = self.ocr.recognize(crop) if self.ocr is not None else ""
            except Exception as exc:
                failures.append(log_failure("ocr", f"{type(exc).__name__}: {exc}",
                                            chapter_id=chapter_id, page=page_index, exc=exc))
                text = ""

            box_w = x2 - x1
            box_h = y2 - y1
            direction = "TTB" if (box_h > 0 and box_w > 0 and box_h / box_w > 1.2) else "LTR"

            blocks.append({
                "index": 0,  # renumbered after sort
                "text": text,
                "translation": "",
                "bbox": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
                "label": box.label,
                "score": box.score,
                "direction": direction,
                "parentBbox": {"x1": px1, "y1": py1, "x2": px2, "y2": py2},
                "parentW": parent_w,
                "parentH": parent_h,
            })

        # Post-OCR dedupe (collapses near-identical fragments) then reading order.
        blocks = dedupe_post_ocr(blocks)
        blocks = sort_blocks_reading_order(blocks, from_lang="JAPANESE")
        for i, block in enumerate(blocks):
            block["index"] = i

        inpaint_mask_boxes = _compute_inpaint_mask_boxes(blocks, text_boxes, width, height)
        return blocks, inpaint_mask_boxes, failures

    def _models_snapshot(self, neural_available: bool | None = None) -> dict[str, str]:
        neural_ok = self.cleaner.neural_available if neural_available is None else neural_available
        if self.detector is not None:
            det = "detector-v4-s_int8"
        else:
            det = f"placeholder ({self.model_load_errors.get('detector', 'not configured')})"
        if self.ocr is not None:
            ocr = "manga-ocr"
        else:
            ocr = f"placeholder ({self.model_load_errors.get('ocr', 'not configured')})"
        inpaint = "classical+aot" if neural_ok else f"classical (neural unavailable: {self.model_load_errors.get('inpaint', 'not configured')})"
        return {"detector": det, "ocr": ocr, "inpaint": inpaint}
        self._touch_registry(chapter_id, page_index)
        return self._with_artifact_status(result, cache_dir, page_index)

    def translate_page(self, chapter_id: str, page_index: int) -> dict[str, Any]:
        """Translate an already-processed page and re-render (batch — all blocks in one LLM call)."""
        lock = self._get_page_lock(chapter_id, page_index)
        with lock:
            chapter = self._chapters.get(chapter_id)
            if chapter is None:
                raise KeyError(chapter_id)

            cache_dir = chapter.directory / "cache"
            result_path = cache_dir / f"{page_index}_result.json"
            cleaned_path = cache_dir / f"{page_index}_cleaned.png"

            # Ensure page is processed first and has a durable cleaned image.
            if not result_path.exists() or not cleaned_path.exists():
                self._process_page_unlocked(chapter_id, page_index)
            if not cleaned_path.exists():
                raise RuntimeError(
                    "Inpainting did not produce a cleaned image; refusing to render over the original image."
                )

            result = json.loads(result_path.read_text(encoding="utf-8"))
            blocks = result.get("blocks", [])

            # Batch translation — send ALL blocks in one call (mirrors Android app).
            # A translator failure is logged and reported honestly (PARTIAL), not
            # swallowed — the page keeps its OCR text and the user sees why.
            texts = [b.get("text", "") for b in blocks]
            source_lang = "JAPANESE"
            translate_failures: list[dict[str, Any]] = []
            try:
                translations = self.translator.translate_blocks(texts, source_lang, chapter.target_lang)
            except Exception as exc:
                translate_failures.append(log_failure(
                    "translate", f"{type(exc).__name__}: {exc}",
                    chapter_id=chapter_id, page=page_index, exc=exc,
                ))
                translations = [""] * len(blocks)
            for block, trans in zip(blocks, translations):
                if trans:
                    block["translation"] = trans

            translated_count = sum(1 for b in blocks if str(b.get("translation", "")).strip())
            if translate_failures:
                translation_status = "FAILED"
            elif translated_count == 0 and blocks:
                translation_status = "FAILED"
            elif translated_count < len(blocks):
                translation_status = "PARTIAL"
            else:
                translation_status = "READY"
            result["translationStatus"] = translation_status
            result["translated"] = translation_status in ("READY", "PARTIAL")

            # Re-render using cached cleaned image
            cleaned = Image.open(cleaned_path).convert("RGB")
            if any(b.get("textColor") is None for b in blocks if b.get("translation", "").strip()):
                self.renderer.estimate_block_colors(cleaned, blocks)
            rendered = self.renderer.render(cleaned, result["blocks"])
            rendered_path = cache_dir / f"{page_index}_rendered.png"
            self._save_png(rendered, rendered_path)
            result["processed"] = True
            # Preserve the honest inpaint status from cleaning — do NOT force READY.
            result.setdefault("inpaintStatus", "READY")
            result["renderStatus"] = "READY"
            result["cleanedImageName"] = cleaned_path.name
            result["renderedImageName"] = rendered_path.name
            result["inpaintRevision"] = max(int(result.get("inpaintRevision", 0)), CURRENT_INPAINT_REVISION)
            result["failures"] = (result.get("failures") or []) + translate_failures
            result["errorMessage"] = None if not translate_failures else f"translate failed: {translate_failures[0]['reason']}"

            result_path.write_text(json.dumps(result), encoding="utf-8")
            return self._with_artifact_status(result, cache_dir, page_index)

    def get_image_path(self, chapter_id: str, page_index: int, image_type: str) -> Path | None:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            return None
        pages = chapter.pages
        if page_index < 0 or page_index >= len(pages):
            return None
        if image_type == "original":
            return chapter.directory / "original" / pages[page_index]
        if image_type in ("cleaned", "rendered"):
            path = chapter.directory / "cache" / f"{page_index}_{image_type}.png"
            return path if path.exists() else None
        return None

    def update_block(self, chapter_id: str, page_index: int, block_index: int,
                     text: str | None = None, translation: str | None = None) -> dict[str, Any]:
        """Update a single block's OCR text or translation, then re-render."""
        lock = self._get_page_lock(chapter_id, page_index)
        with lock:
            chapter = self._chapters.get(chapter_id)
            if chapter is None:
                raise KeyError(chapter_id)
            cache_dir = chapter.directory / "cache"
            result_path = cache_dir / f"{page_index}_result.json"
            if not result_path.exists():
                raise IndexError("Page not processed yet")
            result = json.loads(result_path.read_text(encoding="utf-8"))
            blocks = result.get("blocks", [])
            block = next((b for b in blocks if b.get("index") == block_index), None)
            if block is None:
                raise IndexError(f"Block {block_index} not found")
            if text is not None:
                block["text"] = text
            if translation is not None:
                block["translation"] = translation
            result_path.write_text(json.dumps(result), encoding="utf-8")
            cleaned_path = cache_dir / f"{page_index}_cleaned.png"
            if not cleaned_path.exists():
                raise RuntimeError(
                    "Cleaned image is missing; refusing to re-render over the original image."
                )
            cleaned = Image.open(cleaned_path).convert("RGB")
            self.renderer.estimate_block_colors(cleaned, blocks)
            rendered = self.renderer.render(cleaned, blocks)
            rendered_path = cache_dir / f"{page_index}_rendered.png"
            self._save_png(rendered, rendered_path)
            result["renderStatus"] = "READY"
            result["renderedImageName"] = rendered_path.name
            result["errorMessage"] = None
            result_path.write_text(json.dumps(result), encoding="utf-8")
            return self._with_artifact_status(result, cache_dir, page_index)

    # ── Batch processing ───────────────────────────────────────────────────

    def process_all(self, chapter_id: str, translate: bool = False) -> dict[str, Any]:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            raise KeyError(chapter_id)

        # Don't start if already running
        existing = self._batch_status.get(chapter_id, {})
        if existing.get("running") and not existing.get("done"):
            return existing

        total = len(chapter.pages)
        status = {
            "total": total,
            "processed": 0,
            "failed": [],
            "in_progress": [],
            "cancelled": False,
            "done": False,
            "running": True,
            "translate": translate,
            "started_at": self._now_iso(),
        }
        self._batch_status[chapter_id] = status

        def _worker() -> None:
            futures = {
                self._executor.submit(self._batch_safe_process, chapter_id, i, translate): i
                for i in range(total)
            }
            for future in concurrent.futures.as_completed(futures):
                idx = futures[future]
                if status["cancelled"]:
                    future.cancel()
                    continue
                try:
                    future.result()
                    status["processed"] += 1
                except Exception:
                    status["failed"].append(idx)
                if idx in status["in_progress"]:
                    status["in_progress"].remove(idx)
            status["done"] = True
            status["running"] = False

        thread = threading.Thread(target=_worker, daemon=True, name=f"batch-{chapter_id}")
        thread.start()
        return status

    def _batch_safe_process(self, chapter_id: str, page_index: int, translate: bool = False) -> None:
        status = self._batch_status.get(chapter_id, {})
        if status.get("cancelled"):
            return
        status["in_progress"].append(page_index)
        try:
            self.process_page(chapter_id, page_index)
            if translate:
                self.translate_page(chapter_id, page_index)
        finally:
            pass

    def get_batch_status(self, chapter_id: str) -> dict[str, Any]:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            return {"total": 0, "processed": 0, "done": True, "running": False, "failed": [], "in_progress": []}

        status = self._batch_status.get(chapter_id)
        cache_dir = chapter.directory / "cache"
        cached = sum(
            1 for idx in range(len(chapter.pages))
            if (cache_dir / f"{idx}_result.json").exists()
        )

        if status is None:
            return {
                "total": len(chapter.pages),
                "processed": cached,
                "cached": cached,
                "done": cached >= len(chapter.pages),
                "running": False,
                "failed": [],
                "in_progress": [],
            }

        return {
            "total": status["total"],
            "processed": status["processed"],
            "cached": cached,
            "done": status["done"],
            "running": status["running"],
            "failed": list(status["failed"]),
            "in_progress": list(status["in_progress"]),
            "cancelled": status["cancelled"],
            "started_at": status.get("started_at", ""),
        }

    def cancel_batch(self, chapter_id: str) -> bool:
        status = self._batch_status.get(chapter_id)
        if status is None or not status.get("running"):
            return False
        status["cancelled"] = True
        return True

    # ── Export ──────────────────────────────────────────────────────────────

    def export_chapter(self, chapter_id: str, image_type: str = "rendered") -> bytes:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            raise KeyError(chapter_id)

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for idx in range(len(chapter.pages)):
                path = self.get_image_path(chapter_id, idx, image_type)
                if path and path.exists():
                    ext = path.suffix if image_type == "original" else ".png"
                    zf.write(str(path), f"{idx + 1:04d}{ext}")
        return buf.getvalue()

    def get_chapter_stats(self, chapter_id: str) -> dict[str, Any]:
        chapter = self._chapters.get(chapter_id)
        if chapter is None:
            raise KeyError(chapter_id)

        cache_dir = chapter.directory / "cache"
        total_blocks = 0
        translated_blocks = 0
        processed_pages = 0

        for idx in range(len(chapter.pages)):
            result_path = cache_dir / f"{idx}_result.json"
            if result_path.exists():
                processed_pages += 1
                try:
                    data = json.loads(result_path.read_text(encoding="utf-8"))
                    blocks = data.get("blocks", [])
                    total_blocks += len(blocks)
                    translated_blocks += sum(1 for b in blocks if b.get("translation", "").strip())
                except Exception:
                    pass

        return {
            "total_pages": len(chapter.pages),
            "processed_pages": processed_pages,
            "total_blocks": total_blocks,
            "translated_blocks": translated_blocks,
        }

    # ── Translator config ──────────────────────────────────────────────────

    def get_translator_config(self) -> dict[str, Any]:
        with self._registry_lock:
            return dict(self._registry.get("translator", {"engine": "none"}))

    def update_translator_config(self, config: dict[str, Any]) -> None:
        with self._registry_lock:
            self._registry["translator"] = config
            self._save_registry_locked()
        self.translator = build_translator(config)

    # ── Model scanning & dynamic swapping ───────────────────────────────────

    def scan_available_models(self) -> dict[str, list[dict[str, Any]]]:
        """Scan the model directory for available models per stage."""
        from model_config import ROOT
        base = ROOT.parent / "app" / "src" / "main" / "assets" / "models"
        if not base.exists():
            base = ROOT / "models"
        result: dict[str, list[dict[str, Any]]] = {"detector": [], "ocr": [], "inpaint": []}

        # Detector models
        det_dir = base / "detection"
        if det_dir.is_dir():
            for f in sorted(det_dir.glob("*.onnx")):
                result["detector"].append({"id": f.stem, "name": f.stem, "path": str(f)})

        # OCR engines - Manga OCR and Paddle OCR v6 Small
        ocr_dir = base / "ocr"
        if ocr_dir.is_dir():
            if (ocr_dir / "encoder.onnx").exists() and (ocr_dir / "decoder_init.onnx").exists():
                result["ocr"].append({
                    "id": "manga_ocr", "name": "Manga OCR",
                    "encoder": str(ocr_dir / "encoder.onnx"),
                    "decoder_init": str(ocr_dir / "decoder_init.onnx"),
                    "decoder_step": str(ocr_dir / "decoder_step.onnx"),
                    "vocab": str(ocr_dir / "vocab.txt"),
                })
            paddle_dir = ocr_dir / "paddle-v6-small"
            if (paddle_dir / "inference.onnx").exists() and (paddle_dir / "PP-OCRv6_small_rec.txt").exists():
                result["ocr"].append({
                    "id": "paddle_ocr_v6_small", "name": "Paddle OCR v6 Small",
                    "path": str(paddle_dir / "inference.onnx"),
                    "dict": str(paddle_dir / "PP-OCRv6_small_rec.txt"),
                    "det_path": str(paddle_dir / "det" / "inference.onnx") if (paddle_dir / "det" / "inference.onnx").exists() else None
                })

        # Inpaint models
        inpaint_dir = base / "inpainting"
        if inpaint_dir.is_dir():
            for f in sorted(inpaint_dir.glob("*.onnx")):
                if f.stat().st_size == 0:
                    continue
                result["inpaint"].append({"id": f.stem, "name": f.stem, "path": str(f)})

        # Mark the currently active model per stage
        for stage, models in result.items():
            active_id = self._active_models.get(stage)
            for m in models:
                m["loaded"] = (m["id"] == active_id)

        return result

    def get_model_config(self) -> dict[str, Any]:
        with self._registry_lock:
            return dict(self._registry.get("models", {}))

    def set_model(self, stage: str, model_id: str) -> bool:
        """Dynamically swap a model for a given stage. Returns True on success."""
        available = self.scan_available_models()
        models = available.get(stage, [])
        match = next((m for m in models if m["id"] == model_id), None)
        if match is None:
            return False
        try:
            if stage == "detector":
                self.detector = OnnxPageTextDetector(match["path"])
            elif stage == "ocr":
                if match["id"] == "manga_ocr":
                    self.ocr = MangaOcrEngine(
                        match["encoder"], match["decoder_init"],
                        match["decoder_step"], match["vocab"],
                    )
                elif match["id"] == "paddle_ocr_v6_small":
                    from inference.ocr_paddle_small import PaddleOcrV6SmallEngine
                    self.ocr = PaddleOcrV6SmallEngine(match["path"], match["dict"])
                    if match.get("det_path"):
                        from inference.ocr_paddle_det import PaddleOcrV6DetEngine
                        self.ocr_det = PaddleOcrV6DetEngine(match["det_path"])
                    else:
                        self.ocr_det = None
                else:
                    return False
            elif stage == "inpaint":
                self.neural_cleaner = AotInpainter(match["path"])
                self.cleaner = Cleaner(neural=self.neural_cleaner, paddle_det=self.paddle_det)
                self.model_load_errors.pop("inpaint", None)
            else:
                return False
            self._active_models[stage] = model_id
            with self._registry_lock:
                self._registry.setdefault("models", {})[stage] = model_id
                self._save_registry_locked()
            return True
        except Exception:
            return False
