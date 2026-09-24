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
import copy
import hashlib
import json
import math
import os
import re
import shutil
import sys
import threading
import time
from contextlib import contextmanager
from collections import deque
from functools import lru_cache, wraps
from pathlib import Path
from pathlib import PurePosixPath

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

from boxgeom import (Box, DET_THRESHOLDS, intersection_area,  # noqa: E402
                     reading_order_rtl, select_parent,
                     overlaps_any_bubble, greedy_dedup_with_suppressions,
                     dedupe_within_parents_with_suppressions,
                     suppress_cross_label_with_suppressions)
from detection_artifacts import (assign_mask_center, box_artifact,  # noqa: E402
                                 iter_rle_row_spans,
                                 mask_artifact, resolve_mask_component)
from panel_detector import (PANEL_MODEL, PanelDetector, assign_panel,  # noqa: E402
                            panel_nms, reading_order_panel_indices)
from sliding_detector import (calculate_windows, is_tall_image,  # noqa: E402
                              merge_window_detections, run_text_detector,
                              window_images)
from translation_providers import (AI_DEFAULT_OUTPUT_TOKENS,  # noqa: E402
                                   cache_valid_translations,
                                   stable_openai_block_indexes,
                                   translate_google_batch,
                                   translate_openai_compat_batch)
import inpaint_provenance  # noqa: E402

DETECTOR_PATH = (REPO_ROOT / "app/src/main/assets/models/detection"
                 / "detector-v4-s_int8.onnx")
PANEL_DETECTOR_PATH = PANEL_MODEL
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
OCR_PAD = 12                # RoiPageRecognitionEngine pad around text boxes
CONF_FLOOR = 0.05           # what we persist (slider re-filters above this)
PAGE_HASH_CHUNK_BYTES = 1024 * 1024
PAGE_FINGERPRINT_VERSION = 1
DETECTION_CAPTURE_ALGORITHM = "studio-detection-capture-v3"
DETECTION_REPLAY_ALGORITHM = "studio-detection-replay-v2"
OCR_ALGORITHM = "studio-ocr-v2"
INPAINT_ALGORITHM = "studio-inpaint-v3"
INPAINT_VARIANT_CACHE_LIMIT = 8
RENDER_ALGORITHM = "studio-render-v2"
N_CLASSES = {0: "bubble", 1: "text_bubble", 2: "text_free"}
CLASS_COLORS = {0: (150, 150, 160), 1: (70, 200, 120), 2: (240, 170, 60)}

# Director-locked test chapter: pre-seeded as a recent chapter in the UI.
DEFAULT_CHAPTER = (r"C:\Users\User\Downloads\manga_test_chapters"
                   r"\ore-ni-trauma-wo-ataeta-joshitachi-ga-chirachira-"
                   r"mitekuru-kedo-zannen-desu-ga-teokure-desu_ch16")
RECENT_FILE = Path(__file__).resolve().parent / ".recent.json"


def _stable_fingerprint(value) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False, default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


_STABLE_BLOCK_ID = re.compile(r"(?:p\d+_)?b(\d+)$")
_TRANSLATION_SCHEMA = "stable-block-v1"
_TRANSLATION_META_KEYS = {"_schema", "_pruned"}


def _stable_block_index(value) -> int | None:
    match = _STABLE_BLOCK_ID.fullmatch(str(value or ""))
    if not match:
        return None
    try:
        return int(match.group(1))
    except (TypeError, ValueError):
        return None


def _region_cache_id(region: dict) -> str:
    """Return the persistent translation key, falling back for legacy callers."""
    for value in (region.get("stable_id"), region.get("block_id"), region.get("id")):
        index = _stable_block_index(value)
        if index is not None:
            return f"b{index}"
    return str(region.get("id") or "")


def _region_box(region: dict) -> list | None:
    raw = region.get("box")
    if not isinstance(raw, (list, tuple)) or len(raw) < 4:
        return None
    try:
        box = [float(value) for value in raw[:4]]
    except (TypeError, ValueError, OverflowError):
        return None
    return box if all(math.isfinite(value) for value in box) else None


def _region_sort_key(region: dict, original_index: int) -> tuple:
    box = _region_box(region)
    if box is None:
        return (math.inf, math.inf, math.inf, math.inf, original_index)
    x1, y1, x2, y2 = box
    return (y1, x1, max(0.0, x2 - x1), max(0.0, y2 - y1), original_index)


def _assign_stable_block_ids(regions: list[dict],
                             previous_regions: list[dict] | None = None) -> None:
    """Apply Android StableBlockIds ordering and reuse valid persisted ids.

    Existing per-region ids win. On an OCR refresh, a detector artifact id
    links the new OCR result to its persisted stable id where that link is
    unique. New blocks receive the next unused spatially ordered ``bN`` id.
    """
    previous_by_artifact: dict[str, list[int]] = {}
    for previous in previous_regions or []:
        artifact_id = str(previous.get("artifact_id") or "")
        index = next((parsed for value in (previous.get("stable_id"),
                                            previous.get("block_id"),
                                            previous.get("id"))
                      if (parsed := _stable_block_index(value)) is not None), None)
        if artifact_id and index is not None:
            previous_by_artifact.setdefault(artifact_id, []).append(index)

    ordered = sorted(enumerate(regions),
                     key=lambda item: _region_sort_key(item[1], item[0]))
    planned = []
    for original_index, region in ordered:
        existing = next((parsed for value in (region.get("stable_id"),
                                               region.get("block_id"),
                                               region.get("id"))
                         if (parsed := _stable_block_index(value)) is not None), None)
        inherited = False
        if existing is None:
            candidates = previous_by_artifact.get(str(region.get("artifact_id") or ""), [])
            if len(candidates) == 1:
                existing = candidates[0]
                inherited = True
        planned.append((original_index, region, existing, inherited))

    # Reserve every valid ID before assigning any new spatial slot. Otherwise
    # an earlier new block can consume b0 before a later persisted b0 appears.
    owner_by_id: dict[int, int] = {}
    for original_index, _region, existing, inherited in sorted(
            planned, key=lambda item: (item[3], _region_sort_key(item[1], item[0]))):
        if existing is not None:
            owner_by_id.setdefault(existing, original_index)
    reserved = set(owner_by_id)
    used: set[int] = set()
    next_index = 0
    for original_index, region, existing, _inherited in planned:
        if existing is not None and owner_by_id.get(existing) == original_index:
            index = existing
        else:
            while next_index in reserved or next_index in used:
                next_index += 1
            index = next_index
            next_index += 1
        used.add(index)
        region["stable_id"] = f"b{index}"


def _region_fingerprint(region: dict) -> str:
    assignment = region.get("segmenter_assignment")
    assignment = assignment if isinstance(assignment, dict) else {}
    parent_box = (region.get("parent_box") or region.get("parent_bubble_box")
                  or region.get("parent"))
    if isinstance(parent_box, dict):
        parent_box = parent_box.get("box")
    return _stable_fingerprint({
        "schema": "studio-ocr-region-v1",
        "text": str(region.get("text") or ""),
        "box": _region_box(region),
        "parent": parent_box,
        "label": region.get("label"),
        "score": region.get("score"),
        "mask_ref": assignment.get("mask_ref") or region.get("mask_ref"),
    })


def _translation_text(value) -> str:
    if isinstance(value, dict):
        value = value.get("text", "")
    return str(value or "")


def _translation_origin(value) -> str:
    if isinstance(value, dict):
        return str(value.get("origin") or "legacy")
    return "legacy"


def _region_snapshot(region: dict | None) -> dict | None:
    if not isinstance(region, dict):
        return None
    keys = ("id", "stable_id", "box", "ocr_box", "parent_box", "label",
            "score", "text", "segmenter_assignment")
    return {key: copy.deepcopy(region[key]) for key in keys if key in region}


def _translation_record(value, *, origin: str = "legacy",
                        fingerprint: str | None = None,
                        region: dict | None = None) -> dict:
    if isinstance(value, dict):
        record = copy.deepcopy(value)
        record["text"] = _translation_text(value)
        record.setdefault("origin", origin)
        record.setdefault("ocr_fingerprint", fingerprint)
        if region is not None:
            record["region"] = _region_snapshot(region)
        return record
    return {"text": _translation_text(value), "origin": origin,
            "ocr_fingerprint": fingerprint,
            "region": _region_snapshot(region)}


def _append_pruned_translation(page_cache: dict, stable_id: str,
                               record: dict, reason: str) -> dict:
    pruned = page_cache.setdefault("_pruned", [])
    if not isinstance(pruned, list):
        pruned = page_cache["_pruned"] = []
    row = {
        "stable_id": stable_id,
        "text": _translation_text(record),
        "origin": str(record.get("origin") or "legacy"),
        "ocr_fingerprint": record.get("ocr_fingerprint"),
        "reason": reason,
        "region": copy.deepcopy(record.get("region")),
        "pruned_at": time.time(),
    }
    pruned.append(row)
    return row


def _carry_prune_translation_page(page_cache: dict,
                                 regions: list[dict]) -> list[dict]:
    """Reconcile active translations against one persisted OCR generation."""
    if not isinstance(page_cache, dict):
        return []
    by_stable_id = {}
    for region in regions:
        stable_id = _region_cache_id(region)
        if stable_id:
            by_stable_id[stable_id] = region
    pruned = []
    for stable_id, value in list(page_cache.items()):
        if stable_id in _TRANSLATION_META_KEYS:
            continue
        record = _translation_record(value)
        region = by_stable_id.get(str(stable_id))
        if region is None:
            pruned.append(_append_pruned_translation(
                page_cache, str(stable_id), record, "unmapped-region"))
            page_cache.pop(stable_id, None)
            continue
        current_fingerprint = (region.get("ocr_fingerprint")
                               or _region_fingerprint(region))
        record["region"] = _region_snapshot(region)
        origin = _translation_origin(record)
        if origin == "auto":
            record["ocr_fingerprint"] = current_fingerprint
            page_cache[stable_id] = record
        elif record.get("ocr_fingerprint") == current_fingerprint:
            page_cache[stable_id] = record
        else:
            pruned.append(_append_pruned_translation(
                page_cache, str(stable_id), record,
                "user-edit-fingerprint-mismatch" if origin == "user"
                else "legacy-fingerprint-mismatch"))
            page_cache.pop(stable_id, None)
    page_cache["_schema"] = _TRANSLATION_SCHEMA
    return pruned


def _migrate_translation_page(page_cache: dict,
                              old_id_to_stable: dict[str, str],
                              regions: list[dict] | None) -> bool:
    """Upgrade ordinal-keyed entries; retain ambiguous values for audit."""
    if not isinstance(page_cache, dict):
        return False
    changed = page_cache.get("_schema") != _TRANSLATION_SCHEMA
    current_by_id = {_region_cache_id(region): region for region in (regions or [])}
    current_by_fingerprint: dict[str, list[dict]] = {}
    for region in regions or []:
        fingerprint = region.get("ocr_fingerprint") or _region_fingerprint(region)
        current_by_fingerprint.setdefault(fingerprint, []).append(region)

    migrated: dict[str, dict] = {}
    untouched: dict[str, object] = {}
    entries = list(page_cache.items())
    entries.sort(key=lambda item: 0 if _stable_block_index(item[0]) is not None else 1)
    for old_key, value in entries:
        if old_key in _TRANSLATION_META_KEYS:
            if old_key == "_pruned" and isinstance(value, list):
                untouched[old_key] = value
            continue
        old_key = str(old_key)
        record = _translation_record(value)
        region = None
        target = None
        # An exact OCR fingerprint is stronger evidence than an old ordinal.
        # Use the position key only when the legacy value has no fingerprint.
        if regions is not None and record.get("ocr_fingerprint"):
            matches = current_by_fingerprint.get(str(record["ocr_fingerprint"]), [])
            if len(matches) == 1:
                region = matches[0]
                target = _region_cache_id(region)
        if target is None:
            target = old_id_to_stable.get(old_key)
        if target is None and _stable_block_index(old_key) is not None:
            target = f"b{_stable_block_index(old_key)}"
        if region is None:
            region = current_by_id.get(target) if target else None
        if target and region is not None:
            record = _translation_record(value, region=region)
            if not record.get("ocr_fingerprint"):
                record["ocr_fingerprint"] = (region.get("ocr_fingerprint")
                                             or _region_fingerprint(region))
            existing = migrated.get(target)
            if existing is not None and _translation_text(existing) != _translation_text(record):
                # When both records have unknown legacy provenance, a mapped
                # ordinal alias can be a late edit made by an older caller.
                # Keep that edit active and retain the prior stable value in
                # the audit list. Explicit auto/user stable records take
                # precedence over ambiguous legacy aliases.
                if (_translation_origin(existing) == "legacy"
                        and _translation_origin(record) == "legacy"
                        and _stable_block_index(old_key) is None):
                    _append_pruned_translation(
                        page_cache, target, existing,
                        "migration-conflict-superseded")
                    migrated[target] = record
                else:
                    _append_pruned_translation(page_cache, old_key, record,
                                               "migration-collision")
            elif existing is None:
                migrated[target] = record
            changed = True
        elif regions is None:
            # OCR was reset or has not run. Keep the old key and text pending;
            # a later OCR run will map it only if the old region identity exists.
            untouched[old_key] = record
            changed = True
        else:
            _append_pruned_translation(page_cache, old_key, record,
                                       "migration-unmapped")
            changed = True

    old_active = {key: value for key, value in page_cache.items()
                  if key not in _TRANSLATION_META_KEYS}
    new_page = {**migrated, **untouched,
                "_schema": _TRANSLATION_SCHEMA}
    if page_cache.get("_pruned") is not None:
        new_page["_pruned"] = page_cache["_pruned"]
    if old_active != {key: value for key, value in new_page.items()
                      if key not in _TRANSLATION_META_KEYS}:
        changed = True
    page_cache.clear()
    page_cache.update(new_page)
    return changed


@lru_cache(maxsize=32)
def _cached_file_sha256(path: str, modified_ns: int, size: int,
                        _sample_sha256: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        while chunk := stream.read(PAGE_HASH_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def _asset_sample_sha256(path: Path, size: int) -> str:
    """Cheaply detect same-stat asset edits using a length/head/tail sample."""
    digest = hashlib.sha256()
    digest.update(size.to_bytes(8, "big", signed=False))
    sample_bytes = 4096
    with path.open("rb") as stream:
        if size <= sample_bytes * 2:
            digest.update(stream.read())
        else:
            digest.update(stream.read(sample_bytes))
            stream.seek(max(0, size - sample_bytes))
            digest.update(stream.read(sample_bytes))
    return digest.hexdigest()


def _asset_identity(path: Path | None) -> dict:
    if path is None or not path.is_file():
        return {"status": "missing"}
    stat = path.stat()
    return {"status": "present", "size": stat.st_size,
            "sha256": _cached_file_sha256(str(path.resolve()),
                                           stat.st_mtime_ns, stat.st_size,
                                           _asset_sample_sha256(path, stat.st_size))}


def _asset_sha12(path: Path | None) -> str | None:
    identity = _asset_identity(path)
    return identity["sha256"][:12] if identity["status"] == "present" else None


def _page_operation(method):
    """Hash and validate one page once, including through nested stage calls."""
    @wraps(method)
    def wrapped(self, page, *args, **kwargs):
        with self.lock:
            with self._page_operation_scope(str(page)):
                return method(self, page, *args, **kwargs)
    return wrapped


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
        self._panel_detector_model = None
        self._panel_detector_unavailable = False
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
        self._render_assignments: dict[str, list[dict]] = {}
        self._cache: dict = {}
        self._render_dirty: dict[str, bool] = {}
        self._crop_cache: dict = {}
        self._dims: dict[str, list[int]] = {}
        self._page_fingerprints: dict[str, dict] = {}
        self._page_operation_local = threading.local()

    @contextmanager
    def _page_operation_scope(self, page: str):
        active = getattr(self._page_operation_local, "active", None)
        if active and active[0] == page:
            yield active[1]
            return
        with self.lock:
            info = self._compute_page_fingerprint(page)
            self._accept_page_fingerprint(page, info)
            previous = active
            self._page_operation_local.active = (page, info)
        try:
            yield info
        finally:
            self._page_operation_local.active = previous

    def _compute_page_fingerprint(self, page: str) -> dict:
        if self.chapter is None:
            raise ValueError("No chapter open")
        path = self.chapter / page
        if not path.is_file():
            raise FileNotFoundError(page)
        with Image.open(path) as source_image:
            width, height = source_image.size
        content_hash = hashlib.sha256()
        byte_size = 0
        with path.open("rb") as source:
            while chunk := source.read(PAGE_HASH_CHUNK_BYTES):
                content_hash.update(chunk)
                byte_size += len(chunk)
        identity = PurePosixPath(page.replace("\\", "/")).as_posix()
        fields = {"algorithm": "sha256-compressed-page-v1",
                  "fingerprint_version": PAGE_FINGERPRINT_VERSION,
                  "identity": identity, "byte_size": byte_size,
                  "width": width, "height": height,
                  "content_sha256": content_hash.hexdigest()}
        return {**fields, "fingerprint": _stable_fingerprint(fields)}

    def _current_page_fingerprint(self, page: str) -> str:
        active = getattr(self._page_operation_local, "active", None)
        if active and active[0] == page:
            return active[1]["fingerprint"]
        return self._compute_page_fingerprint(page)["fingerprint"]

    def _cached_page_fingerprint(self, page: str) -> str | None:
        detection = (self._cache.get("detections", {}).get(page)
                     if isinstance(self._cache, dict) else None)
        if isinstance(detection, dict) and detection.get("page_fingerprint"):
            return detection["page_fingerprint"]
        ocr = (self._cache.get("ocr", {}).get(page)
               if isinstance(self._cache, dict) else None)
        if isinstance(ocr, dict) and ocr.get("page_fingerprint"):
            return ocr["page_fingerprint"]
        if self.studio_dir is not None:
            stem = Path(page).stem
            for name in ("inpaint", "render"):
                metadata = self.studio_dir / name / f"{stem}.json"
                try:
                    cached = json.loads(metadata.read_text(encoding="utf-8"))
                except (OSError, ValueError, TypeError):
                    continue
                page_fingerprint = (cached.get("page_fingerprint")
                                    or cached.get("cache_key", {}).get("page_fingerprint"))
                if page_fingerprint:
                    return page_fingerprint
            thumb_dir = self.studio_dir / "thumbs"
            for metadata in thumb_dir.glob(f"{stem}_*.json") if thumb_dir.is_dir() else ():
                try:
                    cached = json.loads(metadata.read_text(encoding="utf-8"))
                except (OSError, ValueError, TypeError):
                    continue
                if cached.get("page_fingerprint"):
                    return cached["page_fingerprint"]
        return None

    def _has_page_artifacts(self, page: str) -> bool:
        if (page in self._cache.get("detections", {})
                or page in self._cache.get("ocr", {})):
            return True
        if self.studio_dir is None:
            return False
        stem = Path(page).stem
        if any((self.studio_dir / folder / f"{stem}{suffix}").exists()
               for folder, suffix in (("inpaint", ".png"), ("inpaint", ".json"),
                                      ("inpaint_mask", ".png"),
                                      ("render", ".png"), ("render", ".json"),
                                      ("segmentation", ".png"),
                                      ("seg_overlay", ".png"))):
            return True
        thumb_dir = self.studio_dir / "thumbs"
        return thumb_dir.is_dir() and any(thumb_dir.glob(f"{stem}_*.jpg"))

    def _accept_page_fingerprint(self, page: str, info: dict) -> None:
        fingerprint = info["fingerprint"]
        previous = self._page_fingerprints.get(page)
        if previous is not None:
            stale = previous.get("fingerprint") != fingerprint
        else:
            stored = self._cached_page_fingerprint(page)
            # A legacy cache may predate page fingerprints entirely. Adopt
            # the current source identity on first touch so its artifacts
            # remain inspectable; stage cache validation still decides
            # whether those records can be reused. A persisted fingerprint,
            # however, is evidence that a different page was previously
            # accepted and must be invalidated when it no longer matches.
            stale = stored is not None and stored != fingerprint
        if stale:
            self._invalidate_page_artifacts(page)
        self._page_fingerprints[page] = info
        self._dims[page] = [int(info["width"]), int(info["height"])]

    def _invalidate_page_artifacts(self, page: str) -> None:
        detections = self._cache.get("detections", {})
        ocr = self._cache.get("ocr", {})
        detection_changed = detections.pop(page, None) is not None
        ocr_changed = ocr.pop(page, None) is not None
        if detection_changed:
            self._save_json("detections.json", detections)
        if ocr_changed:
            self._save_json("ocr.json", ocr)

        self._dims.pop(page, None)
        self._bubble_mask_cache.pop(page, None)
        self._crop_cache.pop(page, None)
        self._render_assignments.pop(page, None)
        self._render_dirty[page] = True
        self._seg_times.pop(page, None)
        self._inpaint_times.pop(page, None)
        self._render_times.pop(page, None)
        if self.studio_dir is None:
            return
        stem = Path(page).stem
        for folder, suffixes in (
                ("inpaint", (".png", ".json")),
                ("inpaint_mask", (".png",)),
                ("render", (".png", ".json")),
                ("segmentation", (".png",)),
                ("seg_overlay", (".png",))):
            for suffix in suffixes:
                path = self.studio_dir / folder / f"{stem}{suffix}"
                try:
                    path.unlink()
                except FileNotFoundError:
                    pass
        inpaint_provenance.remove_page_crop_artifacts(self.studio_dir, page)
        self._remove_inpaint_variant_cache(page)
        thumb_dir = self.studio_dir / "thumbs"
        if thumb_dir.is_dir():
            for path in list(thumb_dir.glob(f"{stem}_*.jpg")) + list(
                    thumb_dir.glob(f"{stem}_*.json")):
                try:
                    path.unlink()
                except FileNotFoundError:
                    pass

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
            ocr_cache_changed = False
            translation_cache_changed = False
            for page, ocr_entry in self._cache["ocr"].items():
                if not isinstance(ocr_entry, dict) or not isinstance(ocr_entry.get("regions"), list):
                    continue
                regions = ocr_entry["regions"]
                before = copy.deepcopy(regions)
                previous_ocr_fingerprint = ocr_entry.get("ocr_fingerprint")
                self._prepare_region_identity(page, regions)
                old_id_to_stable = {}
                for old_region, region in zip(before, regions):
                    old_id = old_region.get("id")
                    if old_id is not None:
                        old_id_to_stable[str(old_id)] = region["stable_id"]
                    old_stable_id = old_region.get("stable_id")
                    if old_stable_id is not None:
                        old_id_to_stable[str(old_stable_id)] = region["stable_id"]
                if regions != before:
                    ocr_cache_changed = True
                # Do not rewrite a fingerprintless empty legacy cache just by
                # opening a chapter.  It has no region identities to migrate;
                # the next OCR operation will persist the current schema.
                if regions or previous_ocr_fingerprint is not None:
                    self._refresh_page_ocr_fingerprint(ocr_entry)
                    if ocr_entry.get("ocr_fingerprint") != previous_ocr_fingerprint:
                        ocr_cache_changed = True
                page_translations = self._cache["translations"].setdefault(page, {})
                if not isinstance(page_translations, dict):
                    page_translations = self._cache["translations"][page] = {}
                translation_cache_changed |= _migrate_translation_page(
                    page_translations, old_id_to_stable, regions)
                pruned = _carry_prune_translation_page(page_translations, regions)
                if pruned:
                    translation_cache_changed = True
            # An ordinal-keyed file may outlive a reset that removed ocr.json.
            # Keep those values pending; the next OCR refresh will either map
            # them from persisted evidence or move them to the audit list.
            for page, page_translations in self._cache["translations"].items():
                if page in self._cache["ocr"] or not isinstance(page_translations, dict):
                    continue
                translation_cache_changed |= _migrate_translation_page(
                    page_translations, {}, None)
            if ocr_cache_changed:
                self._save_json("ocr.json", self._cache["ocr"])
            if translation_cache_changed:
                self._save_json("translations.json", self._cache["translations"])
            self._dims = {}
            self._page_fingerprints = {}
            self._crop_cache = {}
            self._bubble_mask_cache = {}
            self._render_assignments = {}
            self.settings = dict(DEFAULT_SETTINGS)
            self.settings.update(self._load_json("settings.json", {}))
            self._render_dirty = {p: True for p in self.pages}
            self.log(f"opened chapter: {ch} ({len(self.pages)} pages)"
                     + (f", reference: {self.reference}" if self.reference else ""))
            return self.state()

    def _parent_box_for_region(self, page: str, region: dict) -> list | None:
        """Resolve the OCR region's parent bubble from the cached detection set."""
        raw = self._cache.get("detections", {}).get(page) or {}
        model = (raw.get("models", {}).get("text-detector", {})
                 if isinstance(raw, dict) else {})
        bubbles = []
        for row in model.get("derived_outputs", []) or []:
            attrs = row.get("attrs", {}) if isinstance(row, dict) else {}
            geometry = row.get("geometry", {}) if isinstance(row, dict) else {}
            if attrs.get("label") != 0 or not all(
                    key in geometry for key in ("x1", "y1", "x2", "y2")):
                continue
            try:
                bubbles.append(Box(*(float(geometry[key]) for key in
                                     ("x1", "y1", "x2", "y2"))))
            except (TypeError, ValueError, OverflowError):
                continue
        if not bubbles:
            for row in raw.get("boxes", []) if isinstance(raw, dict) else []:
                if not isinstance(row, (list, tuple)) or len(row) < 6:
                    continue
                try:
                    if int(row[0]) == 0:
                        bubbles.append(Box(*(float(value) for value in row[2:6])))
                except (TypeError, ValueError, OverflowError):
                    continue
        box = _region_box(region)
        if box is None or not bubbles:
            return None
        try:
            parent = select_parent(Box(*box), bubbles)
        except (TypeError, ValueError, OverflowError):
            return None
        return parent.as_list() if parent is not None else None

    def _prepare_region_identity(self, page: str, regions: list[dict],
                                 previous_regions: list[dict] | None = None) -> None:
        for region in regions:
            if "parent_box" not in region:
                region["parent_box"] = self._parent_box_for_region(page, region)
        _assign_stable_block_ids(regions, previous_regions)
        for region in regions:
            region["ocr_fingerprint"] = _region_fingerprint(region)

    @staticmethod
    def _refresh_page_ocr_fingerprint(ocr_entry: dict) -> None:
        ocr_entry["ocr_fingerprint"] = _stable_fingerprint({
            "schema": "studio-ocr-region-set-v1",
            "cache_fingerprint": ocr_entry.get("cache_fingerprint"),
            "engine": ocr_entry.get("engine"),
            "regions": [{"stable_id": region.get("stable_id"),
                         "ocr_fingerprint": region.get("ocr_fingerprint")}
                        for region in ocr_entry.get("regions", [])],
        })

    def save_settings(self, patch: dict) -> dict:
        with self.lock:
            previous_ocr_engine = self.settings.get("ocr_engine", "mangaocr")
            previous_inpaint = {key: self.settings.get(key) for key in (
                "inpaint_mode", "inpaint_engine", "inpaint_bubble_leg",
                "inpaint_free_leg", "inpaint_opencv_method",
                "inpaint_telea_radius",
                "bubble_mask_erosion", "inpaint_feather_px")}
            for k, v in patch.items():
                if k == "conf" and v is not None:
                    self.settings[k] = float(v)
                elif k == "bubble_mask_erosion" and v is not None:
                    self.settings[k] = max(0, min(20, int(v)))
                elif k == "inpaint_telea_radius" and v is not None:
                    self.settings[k] = max(1, min(20, int(v)))
                elif k == "inpaint_opencv_method":
                    self.settings[k] = ("ns" if str(v).lower() == "ns"
                                        else "telea")
                elif k == "inpaint_feather_px":
                    self.settings[k] = (None if v in (None, "") else
                                        max(2, min(48, int(v)))
                                        if str(v).strip() else None)
                elif k == "font_scale" and v is not None:
                    self.settings[k] = float(v)
                elif k == "max_batch" and v is not None:
                    self.settings[k] = int(v)
                else:
                    self.settings[k] = v
            if self.settings.get("ocr_engine", "mangaocr") != previous_ocr_engine:
                self._crop_cache.clear()
            self._save_json("settings.json", self.settings)
            if any(self.settings.get(key) != value
                   for key, value in previous_inpaint.items()):
                for page in self.pages:
                    self._invalidate_active_inpaint_variant(page)
            return self.settings

    def _inpaint_variant_page_dir(self, page: str) -> Path | None:
        """Return a page's variant-cache directory only when confined to .studio."""
        if self.studio_dir is None:
            return None
        studio = self.studio_dir.resolve()
        lexical_root = studio / "inpaint_variants"
        root = lexical_root.resolve()
        if root.parent != studio:
            return None
        target = (lexical_root / Path(page).stem).resolve()
        try:
            target.relative_to(root)
        except ValueError:
            return None
        return target

    def _inpaint_variant_dir(self, page: str, fingerprint: str) -> Path | None:
        if (not isinstance(fingerprint, str) or len(fingerprint) != 64
                or any(ch not in "0123456789abcdef" for ch in fingerprint)):
            return None
        page_dir = self._inpaint_variant_page_dir(page)
        if page_dir is None:
            return None
        return page_dir / fingerprint

    def _remove_inpaint_variant_cache(self, page: str | None = None) -> bool:
        if self.studio_dir is None:
            return False
        studio = self.studio_dir.resolve()
        root = (studio / "inpaint_variants").resolve()
        if root.parent != studio:
            return False
        target = root if page is None else self._inpaint_variant_page_dir(page)
        if target is None or not target.exists():
            return False
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink(missing_ok=True)
        return True

    def _invalidate_active_inpaint_variant(self, page: str) -> None:
        """Preserve then remove canonical output so GETs cannot serve old settings."""
        if self.studio_dir is None:
            return
        stem = Path(page).stem
        out_path = self.studio_dir / "inpaint" / f"{stem}.png"
        mask_path = self.studio_dir / "inpaint_mask" / f"{stem}.png"
        provenance_path = self.studio_dir / "inpaint" / f"{stem}.json"
        if provenance_path.is_file():
            try:
                provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
                fingerprint = (provenance.get("cache_key") or {}).get("fingerprint")
                if fingerprint and out_path.is_file() and mask_path.is_file():
                    self._store_inpaint_variant(
                        page, fingerprint, out_path, mask_path, provenance_path)
            except (OSError, ValueError, TypeError, shutil.Error) as error:
                self.log(f"!! could not preserve active inpaint variant for {page}: {error}")
        for path in (out_path, mask_path, provenance_path,
                     self.studio_dir / "render" / f"{stem}.png",
                     self.studio_dir / "render" / f"{stem}.json"):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        self._inpaint_times.pop(page, None)
        self._render_times.pop(page, None)
        self._render_dirty[page] = True

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
                    for ext in (".png", ".jpg", ".jpeg", ".webp", ".json"):
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
                if self._remove_inpaint_variant_cache(page):
                    deleted.append(f"inpaint_variants/{stem}")
                _rm_tree(self.studio_dir / "segmentation")
                _rm_tree(self.studio_dir / "seg_overlay")
                _rm_tree(self.studio_dir / "thumbs")
                if inpaint_provenance.remove_page_crop_artifacts(
                        self.studio_dir, page):
                    deleted.append(f"inpaint_crops/{inpaint_provenance.page_key(page)}")
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
                if self._remove_inpaint_variant_cache():
                    deleted.append("inpaint_variants")
                _rm_tree(self.studio_dir / "segmentation")
                _rm_tree(self.studio_dir / "seg_overlay")
                _rm_tree(self.studio_dir / "thumbs")
                if inpaint_provenance.remove_all_crop_artifacts(self.studio_dir):
                    deleted.append("inpaint_crops")
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
            "dims": {p: self._page_dims_header(p) for p in self.pages},
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

    def _page_dims_header(self, page: str) -> list[int]:
        """Read current header dimensions without hashing a page for state GETs."""
        with self.lock:
            with Image.open(self.chapter / page) as im:
                dimensions = [im.width, im.height]
            self._dims[page] = dimensions
            return dimensions

    @_page_operation
    def page_dims(self, page: str) -> list[int]:
        """Return dimensions after synchronizing this page's content identity."""
        return self._page_dims_header(page)

    @_page_operation
    def thumbnail_path(self, page: str, size: int = 160) -> Path:
        """Cached thumbnail file path on disk."""
        assert self.studio_dir is not None
        tdir = self.studio_dir / "thumbs"
        out = tdir / f"{Path(page).stem}_{size}.jpg"
        metadata_path = tdir / f"{Path(page).stem}_{size}.json"
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            metadata = {}
        page_fingerprint = self._current_page_fingerprint(page)
        if (not out.exists()
                or metadata.get("page_fingerprint") != page_fingerprint):
            img = self.page_image(page)
            img.thumbnail((size, 10000), Image.BILINEAR)
            tdir.mkdir(parents=True, exist_ok=True)
            tmp = out.with_suffix(".tmp.jpg")
            img.save(tmp, "JPEG", quality=80)
            tmp.replace(out)
            metadata_path.write_text(json.dumps({
                "page_fingerprint": page_fingerprint,
                "size": int(size),
                "cache_fingerprint": _stable_fingerprint(
                    {"page_fingerprint": page_fingerprint,
                     "size": int(size), "algorithm": "studio-thumbnail-v1"}),
            }, indent=2), encoding="utf-8")
        return out

    @_page_operation
    def thumbnail(self, page: str, size: int = 160) -> Image.Image:
        """Small cached JPEG for the navigator rail / overview."""
        return Image.open(self.thumbnail_path(page, size)).convert("RGB")

    @_page_operation
    def ocr_input_image(self, page: str, region_id: str) -> Image.Image:
        """The EXACT 224x224 normalized tensor OCR consumed, un-normalized
        for display — reuses lab.preprocessing.preprocess, nothing new."""
        ocr_entry = self._cache["ocr"].get(page, {})
        regions = ocr_entry.get("regions", [])
        r = next((x for x in regions if x["id"] == region_id), None)
        if r is None:
            raise FileNotFoundError(region_id)
        img = self.page_image(page).crop(tuple(recognition_input_box(
            r, ocr_entry.get("engine", "mangaocr"))))
        px = lab_preprocess(img)
        arr = np.clip((px[0] * np.float32(0.5) + np.float32(0.5)) * 255.0,
                      0, 255).astype("uint8")
        return Image.fromarray(arr, mode="L").convert("RGB")

    @_page_operation
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

        conf = float(self.settings.get("conf", DEFAULT_SETTINGS["conf"]))
        for p in self.pages:
            det = self._cache["detections"].get(p)
            ocr = self._cache["ocr"].get(p)
            if ocr and "regions" in ocr:
                regs = [r for r in ocr["regions"] if r.get("score", 1.0) >= conf]
            elif det:
                regs = self._regions_at_conf(p, conf, persist=False).get("regions", [])
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
    @_page_operation
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

    def _panel_detector(self):
        if self._panel_detector_unavailable:
            return None
        if self._panel_detector_model is None:
            model = PanelDetector(PANEL_DETECTOR_PATH)
            if not model.ready:
                self._panel_detector_unavailable = True
                return None
            self._panel_detector_model = model
        return self._panel_detector_model

    def _reading_order_is_rtl(self) -> bool:
        explicit = self.settings.get("reading_order_rtl")
        if explicit is not None:
            if isinstance(explicit, str):
                return explicit.strip().lower() not in {"false", "0", "ltr", "left-to-right"}
            return bool(explicit)
        language = str(self.settings.get(
            "source_language", self.settings.get("source_lang", "ja"))).strip().lower()
        order = str(self.settings.get("reading_order", "auto")).strip().lower()
        if order in {"ltr", "ltr_comic", "left-to-right"}:
            return False
        if order in {"rtl", "rtl_manga", "right-to-left"}:
            return True
        return language in {"ja", "jpn", "japanese"}

    def _panel_skip_reason(self, width: int, height: int) -> str | None:
        if is_tall_image(width, height):
            return "tall-page"
        language = str(self.settings.get(
            "source_language", self.settings.get("source_lang", "ja"))).strip().lower()
        if language in {"ko", "kor", "korean"} or language.startswith("ko-"):
            return "korean-source"
        if not self._reading_order_is_rtl():
            return "ltr-reading-order"
        return None

    def _detection_capture_inputs(self, page_info: dict) -> dict:
        width, height = int(page_info["width"]), int(page_info["height"])
        try:
            from segmentation import SEGMENTER_MODEL
            segmenter_path = SEGMENTER_MODEL
        except Exception:
            segmenter_path = None
        panel_skip_reason = self._panel_skip_reason(width, height)
        panel_identity = (_asset_identity(PANEL_DETECTOR_PATH)
                          if panel_skip_reason is None
                          else {"status": "skipped", "reason": panel_skip_reason})
        segmenter_enabled = bool(self.settings.get("bubble_segmentation", True))
        segmenter_identity = (_asset_identity(segmenter_path)
                              if segmenter_enabled else {
                                  "status": "skipped",
                                  "reason": "bubble-segmentation-disabled"})
        return {
            "schema": 2,
            "algorithm": DETECTION_CAPTURE_ALGORITHM,
            "page_fingerprint": page_info["fingerprint"],
            "page_wh": [width, height],
            "text_detector": _asset_identity(DETECTOR_PATH),
            "panel_detector": panel_identity,
            "panel_skip_reason": panel_skip_reason,
            "bubble_segmentation_enabled": segmenter_enabled,
            "bubble_segmenter": segmenter_identity,
            "raw_score_floor": CONF_FLOOR,
        }

    @staticmethod
    def _detection_capture_statuses(raw: dict) -> dict:
        models = raw.get("models", {})
        return {name: (models.get(name) or {}).get("status")
                for name in ("text-detector", "panel-detector", "bubble-segmenter")}

    def _stamp_detection_capture(self, raw: dict, page_info: dict) -> dict:
        inputs = self._detection_capture_inputs(page_info)
        statuses = self._detection_capture_statuses(raw)
        raw["capture_algorithm_version"] = DETECTION_CAPTURE_ALGORITHM
        raw["page_fingerprint"] = page_info["fingerprint"]
        raw["page_fingerprint_inputs"] = {
            key: page_info[key] for key in
            ("algorithm", "identity", "byte_size", "width", "height", "content_sha256")}
        raw["capture_inputs"] = inputs
        raw["capture_statuses"] = statuses
        raw["capture_fingerprint"] = _stable_fingerprint({
            "algorithm": DETECTION_CAPTURE_ALGORITHM,
            "capture_version": raw.get("capture_version"),
            "inputs": inputs, "statuses": statuses,
        })
        return raw

    def _detection_cache_is_current(self, raw: dict, page_info: dict) -> bool:
        page_wh = [int(page_info["width"]), int(page_info["height"])]
        if not isinstance(raw, dict) or raw.get("capture_version") != 1:
            return False
        if (raw.get("capture_algorithm_version") != DETECTION_CAPTURE_ALGORITHM
                or raw.get("page_fingerprint") != page_info.get("fingerprint")
                or raw.get("page_wh") != page_wh):
            return False
        expected_inputs = self._detection_capture_inputs(page_info)
        capture_inputs = raw.get("capture_inputs")
        if not isinstance(capture_inputs, dict):
            return False
        relevant_inputs = set(expected_inputs)
        if expected_inputs.get("panel_skip_reason") is not None:
            relevant_inputs.difference_update({"panel_detector", "panel_skip_reason"})
        if not expected_inputs.get("bubble_segmentation_enabled", True):
            relevant_inputs.difference_update({
                "bubble_segmentation_enabled", "bubble_segmenter"})
        if any(capture_inputs.get(key) != expected_inputs.get(key)
               for key in relevant_inputs):
            return False
        statuses = self._detection_capture_statuses(raw)
        if raw.get("capture_statuses") != statuses:
            return False
        expected_fingerprint = _stable_fingerprint({
            "algorithm": DETECTION_CAPTURE_ALGORITHM,
            "capture_version": raw.get("capture_version"),
            "inputs": capture_inputs, "statuses": statuses,
        })
        if raw.get("capture_fingerprint") != expected_fingerprint:
            return False
        models = raw.get("models")
        if not isinstance(models, dict):
            return False
        text_model = models.get("text-detector")
        text_sha = _asset_sha12(DETECTOR_PATH)
        if (not isinstance(text_model, dict)
                or text_model.get("asset_sha") != text_sha
                or not isinstance(text_model.get("outputs"), list)):
            return False

        panel_model = models.get("panel-detector")
        if (not isinstance(panel_model, dict)
                or not isinstance(panel_model.get("outputs"), list)):
            return False
        if expected_inputs["panel_skip_reason"] is None:
            panel_input = expected_inputs["panel_detector"]
            if panel_input.get("status") == "present":
                panel_sha = _asset_sha12(PANEL_DETECTOR_PATH)
                if panel_model.get("asset_sha") != panel_sha:
                    return False
                if panel_model.get("status") not in {"ready", "error", "disabled"}:
                    return False
            elif (panel_model.get("asset_sha") is not None
                    or panel_model.get("status") != "disabled"):
                return False

        try:
            from segmentation import SEGMENTER_MODEL
            seg_path = SEGMENTER_MODEL
        except Exception:
            seg_path = None
        segmenter_model = models.get("bubble-segmenter")
        if (not isinstance(segmenter_model, dict)
                or not isinstance(segmenter_model.get("outputs"), list)):
            return False
        if expected_inputs.get("bubble_segmentation_enabled", True):
            segmenter_input = expected_inputs["bubble_segmenter"]
            if segmenter_input.get("status") == "present":
                segmenter_sha = _asset_sha12(seg_path)
                if segmenter_model.get("asset_sha") != segmenter_sha:
                    return False
                if segmenter_model.get("status") not in {"ready", "error", "disabled"}:
                    return False
            elif (segmenter_model.get("asset_sha") is not None
                    or segmenter_model.get("status") != "disabled"):
                return False
        return True

    def _capture_detection_models(self, page: str, img: Image.Image) -> dict:
        """Run optional detection-phase models and persist raw per-model outputs."""
        detector = self._detector_model()
        detector_start = time.perf_counter()
        text_outputs, windows, tall = run_text_detector(img, detector.detect)
        detector_ms = round((time.perf_counter() - detector_start) * 1000, 1)
        text_sha = _asset_sha12(DETECTOR_PATH)
        text_records = [box_artifact(
            f"td{index:04d}", page, "text-detector", DETECTOR_PATH.name,
            text_sha, output["window"], output["box"], output["raw_score"],
            output["label"],
        ) for index, output in enumerate(text_outputs)]
        for index, output in enumerate(text_outputs):
            output["id"] = text_records[index]["id"]

        panel_sha = _asset_sha12(PANEL_DETECTOR_PATH)
        panel_cache = {"asset": PANEL_DETECTOR_PATH.name, "asset_sha": panel_sha,
                       "outputs": [], "kept_ids": [], "suppression_records": [],
                       "status": "disabled" if panel_sha is None else "skipped"}
        panel_reason = self._panel_skip_reason(img.width, img.height)
        if panel_sha is None:
            panel_cache["reason"] = "asset-missing"
        elif panel_reason is not None:
            panel_cache["reason"] = panel_reason
        else:
            try:
                panel_detector = self._panel_detector()
                if panel_detector is None:
                    panel_cache["status"] = "disabled"
                    panel_cache["reason"] = "asset-missing"
                else:
                    t0 = time.perf_counter()
                    panel_candidates = panel_detector.detect_candidates(img)
                    panel_ids = [f"pd{index:04d}" for index in range(len(panel_candidates))]
                    kept_panel_indices, panel_suppressions = panel_nms(
                        panel_candidates, panel_ids)
                    panel_records = []
                    suppressed_ids = {event["loser_id"] for event in panel_suppressions}
                    for index, candidate in enumerate(panel_candidates):
                        record = box_artifact(
                            panel_ids[index], page, "panel-detector",
                            PANEL_DETECTOR_PATH.name, panel_sha, None,
                            candidate["box"], candidate["score"], "panel")
                        passed = candidate["score"] >= 0.5
                        trace = [{"step": "floor", "kept": passed,
                                  "threshold": 0.5}]
                        if not passed:
                            state = "raw"
                        elif panel_ids[index] in suppressed_ids:
                            state = "suppressed"
                            event = next(e for e in panel_suppressions
                                         if e["loser_id"] == panel_ids[index])
                            trace.append({"step": "panel-nms", "kept": False,
                                          "suppressed_by": event["winner_id"],
                                          "threshold": event["threshold"]})
                        elif not candidate["page_box_valid"]:
                            state = "raw"
                            trace.extend([
                                {"step": "panel-nms", "kept": True,
                                 "threshold": 0.45},
                                {"step": "unletterbox", "kept": False,
                                 "reason": "empty-page-box"},
                            ])
                        else:
                            state = "kept"
                            trace.append({"step": "panel-nms", "kept": True,
                                          "threshold": 0.45})
                        record["lifecycle"] = {"state": state, "trace": trace}
                        panel_records.append(record)
                    panel_cache.update({
                        "status": "ready",
                        "outputs": panel_records,
                        "kept_ids": [panel_ids[i] for i in kept_panel_indices
                                     if panel_candidates[i]["page_box_valid"]],
                        "suppression_records": panel_suppressions,
                        "confidence_threshold": 0.5,
                        "nms_iou_threshold": 0.45,
                        "inference_ms": round((time.perf_counter() - t0) * 1000, 1),
                    })
            except Exception as exc:
                panel_cache.update({"status": "error", "reason": str(exc),
                                    "outputs": [], "kept_ids": [],
                                    "suppression_records": []})
                self.log(f"!! panel detector disabled for {page}: {exc}")

        segmenter_cache = {"asset": "manga109_bubble_int8.onnx",
                           "asset_sha": None, "outputs": [], "status": "disabled"}
        mask_cache: dict[str, dict] = {}
        segmenter_ms = 0.0
        try:
            from segmentation import SEGMENTER_MODEL
            segmenter_cache["asset"] = SEGMENTER_MODEL.name
            segmenter_cache["asset_sha"] = _asset_sha12(SEGMENTER_MODEL)
            if segmenter_cache["asset_sha"] is None:
                segmenter_cache["reason"] = "asset-missing"
            elif not self.settings.get("bubble_segmentation", True):
                segmenter_cache["status"] = "skipped"
                segmenter_cache["reason"] = "bubble-segmentation-disabled"
            else:
                segmenter = self._bubble_segmenter()
                if segmenter is None:
                    segmenter_cache["status"] = "disabled"
                    segmenter_cache["reason"] = "asset-missing-or-invalid"
                else:
                    t0 = time.perf_counter()
                    segmenter_records = []
                    full_page_masks = []
                    segment_windows = calculate_windows(img.width, img.height)
                    for window, crop in window_images(img, segment_windows):
                        full = window.top == 0 and window.height == img.height
                        window_index = None if full else window.index
                        for mask in segmenter.segment(crop):
                            artifact_id = f"bs{len(segmenter_records):04d}"
                            record, encoded = mask_artifact(
                                artifact_id, page, SEGMENTER_MODEL.name,
                                segmenter_cache["asset_sha"], window_index,
                                window.top, img.size, mask)
                            segmenter_records.append(record)
                            mask_cache[record["mask_ref"]] = encoded
                            if not tall and window_index is None:
                                full_page_masks.append(mask)
                    segmenter_ms = round((time.perf_counter() - t0) * 1000, 1)
                    segmenter_cache.update({
                        "status": "ready", "outputs": segmenter_records,
                        "windows": [{"index": None if w.top == 0 and w.height == img.height else w.index,
                                     "top": w.top, "bottom": w.bottom, "height": w.height}
                                    for w in segment_windows],
                        "inference_ms": segmenter_ms,
                    })
                    self._seg_times[page] = segmenter_ms
                    # Reuse the full-page masks for later legacy inpaint/render
                    # consumers. Tall-page callers retain their own window-space
                    # RLE cache and do not materialize page-sized dense masks.
                    if not tall:
                        self._bubble_mask_cache[page] = full_page_masks
        except Exception as exc:
            segmenter_cache.update({"status": "error", "reason": str(exc),
                                    "outputs": []})
            self.log(f"!! bubble segmenter capture degraded for {page}: {exc}")

        legacy_boxes = [[int(output["label"]), float(output["score"]),
                         *[int(v) for v in output["box"]]]
                        for output in text_outputs]
        return {
            "capture_version": 1,
            "boxes": legacy_boxes,
            "page_wh": [img.width, img.height],
            "model_ms": detector_ms,
            "infer_ms": detector_ms,
            "load_ms": self._load_times.get("detector", 0),
            "is_tall": tall,
            "windows": windows,
            "models": {
                "text-detector": {"asset": DETECTOR_PATH.name,
                                  "asset_sha": text_sha, "status": "ready",
                                  "raw_score_floor": CONF_FLOOR,
                                  "outputs": text_records,
                                  "inference_ms": detector_ms},
                "panel-detector": panel_cache,
                "bubble-segmenter": segmenter_cache,
            },
            "mask_cache": {"bubble-segmenter": mask_cache},
            "decisions": {"conf": None, "suppression_records": [],
                          "merge_records": [], "kept_ids": []},
        }

    @_page_operation
    def detect_page(self, page: str, conf: float | None = None,
                    force: bool = False) -> dict:
        """Runs (or reuses) the detector; re-filters at the requested conf."""
        with self.lock:
            conf = float(self.settings["conf"] if conf is None else conf)
            page_info = self._page_fingerprints[page]
            raw = self._cache["detections"].get(page)
            if raw is None or force:
                img = self.page_image(page)
                if force:
                    self._bubble_mask_cache.pop(page, None)
                raw = self._stamp_detection_capture(
                    self._capture_detection_models(page, img), page_info)
                self._cache["detections"][page] = raw
                self.log(f"detect {page}: {len(raw['boxes'])} raw "
                         f"(infer {raw['infer_ms']}ms, load {raw['load_ms']}ms)")
            elif not self._detection_cache_is_current(raw, page_info):
                self.log(f"detect {page}: stale capture; recapturing model outputs")
                img = self.page_image(page)
                self._bubble_mask_cache.pop(page, None)
                raw = self._stamp_detection_capture(
                    self._capture_detection_models(page, img), page_info)
                self._cache["detections"][page] = raw
            regions = self._regions_at_conf(page, conf)
            raw = self._cache["detections"][page]
            return {"page": page, "conf": conf, **regions,
                    "page_fingerprint": raw.get("page_fingerprint"),
                    "model_ms": raw.get("infer_ms", raw.get("model_ms", 0)),
                    "infer_ms": raw.get("infer_ms", raw.get("model_ms", 0)),
                    "load_ms": raw.get("load_ms", self._load_times.get("detector", 0))}

    def _regions_at_conf(self, page: str, conf: float,
                         persist: bool = True) -> dict:
        """Replay decisions under lock; display-only callers use an isolated copy."""
        with self.lock:
            raw = self._cache["detections"].get(page)
            if raw is None:
                raise ValueError(f"page not detected yet: {page}")
            working = raw if persist else copy.deepcopy(raw)
            return self._regions_at_conf_locked(page, conf, raw_override=working,
                                                persist=persist)

    def _regions_at_conf_locked(self, page: str, conf: float, *,
                                raw_override: dict | None = None,
                                persist: bool = True) -> dict:
        """Replay detection decisions while holding the pipeline cache lock."""
        raw = raw_override if raw_override is not None else self._cache["detections"].get(page)
        if raw is None:
            raise ValueError(f"page not detected yet: {page}")
        previous_decisions = raw.get("decisions") or {}
        previous_replay_fingerprint = previous_decisions.get("fingerprint")
        models = raw.setdefault("models", {})
        text_model = models.setdefault("text-detector", {"outputs": []})
        records = text_model.setdefault("outputs", [])
        record_by_id = {record["id"]: record for record in records}
        if not records and raw.get("boxes") and raw.get("capture_version") != 1:
            # Compatibility for callers that load an old cache without first
            # passing through detect_page's schema refresh.
            for index, row in enumerate(raw["boxes"]):
                artifact_id = f"tdlegacy{index:04d}"
                record = box_artifact(
                    artifact_id, page, "text-detector", DETECTOR_PATH.name,
                    _asset_sha12(DETECTOR_PATH), None,
                    [int(v) for v in row[2:6]], float(row[1]), int(row[0]))
                records.append(record)
                record_by_id[artifact_id] = record

        candidates = []
        for record in records:
            geometry = record["geometry"]
            score = float(record["attrs"]["score"])
            label = int(record["attrs"]["label"])
            passed = math.isfinite(score) and score >= conf
            raw_state = "context" if passed and label == 0 else ("kept" if passed else "raw")
            floor_trace = {"step": "floor", "kept": passed,
                           "threshold": conf}
            if passed and label == 0:
                floor_trace["reason"] = "label-0 bubble context"
            record["lifecycle"] = {
                "state": raw_state,
                "trace": [floor_trace],
            }
            if not passed:
                continue
            candidates.append({
                "id": record["id"],
                "label": label,
                "score": math.floor(score * 10000.0 + 0.5) / 10000.0,
                "raw_score": score,
                "box": Box(int(geometry["x1"]), int(geometry["y1"]),
                           int(geometry["x2"]), int(geometry["y2"])),
                "window": record["source"].get("window"),
            })

        def mark_suppressed(event: dict) -> None:
            record = record_by_id.get(event["loser_id"])
            if not record:
                return
            lifecycle = record.setdefault("lifecycle", {"state": "kept", "trace": []})
            lifecycle["state"] = "suppressed"
            lifecycle["trace"].append({"step": event["rule"], "kept": False,
                                       "suppressed_by": event["winner_id"],
                                       "threshold": event["threshold"]})

        def add_trace(artifact_id: str, step: str, **entry) -> None:
            record = derived_by_id.get(artifact_id)
            if record:
                record["lifecycle"]["trace"].append({"step": step, **entry})

        page_wh = raw.get("page_wh") or [1, 1]
        text_candidates = [item for item in candidates if item["label"] in (1, 2)]
        groups = [(item["window"], item["label"]) for item in text_candidates]
        detector_kept_indices, detector_suppressions = greedy_dedup_with_suppressions(
            [item["box"] for item in text_candidates],
            [item["score"] for item in text_candidates],
            [item["id"] for item in text_candidates],
            DET_THRESHOLDS, groups=groups, rule="det-dedup")
        for event in detector_suppressions:
            mark_suppressed(event)
        detector_kept = [text_candidates[i] for i in detector_kept_indices]
        bubbles = [item for item in candidates if item["label"] == 0]
        survivors = detector_kept + bubbles

        tall = bool(raw.get("is_tall"))
        merge_candidates = [dict(item, box=item["box"].as_list())
                            for item in survivors]
        if tall:
            canonical, merge_records = merge_window_detections(merge_candidates)
        else:
            canonical = [dict(item, merged_with=[]) for item in merge_candidates]
            merge_records = []
        for item in canonical:
            item["box"] = Box(*[int(v) for v in item["box"]])
        for event in merge_records:
            raw_loser = record_by_id.get(event["loser_id"])
            raw_winner = record_by_id.get(event["winner_id"])
            if raw_loser:
                raw_loser["lifecycle"] = {
                    "state": "merged",
                    "trace": raw_loser.get("lifecycle", {}).get("trace", []) + [
                        {"step": "merge", "kept": False,
                         "merged_with": [event["winner_id"]],
                         "rule": event["rule"], "threshold": event["threshold"]}],
                }
            if raw_winner:
                raw_winner["lifecycle"]["trace"].append(
                    {"step": "merge", "kept": True,
                     "merged_with": [event["loser_id"]],
                     "rule": event["rule"], "threshold": event["threshold"]})

        derived_records = []
        derived_by_id = {}
        for item in canonical:
            raw_id = item["id"]
            derived_id = f"dt-{raw_id}"
            item["artifact_id"] = derived_id
            label = item["label"]
            record = box_artifact(
                derived_id, page, "text-detector", DETECTOR_PATH.name,
                text_model.get("asset_sha"), item.get("window"),
                item["box"].as_list(), item["raw_score"], label)
            trace = [{"step": "floor", "kept": True, "threshold": conf}]
            if label in (1, 2):
                trace.append({"step": "det-dedup", "kept": True,
                              "threshold": {"iou": 0.75, "containment": 0.88,
                                            "center": 0.12, "size": 0.18}})
            else:
                trace.append({"step": "context", "kept": True,
                              "reason": "label-0 bubble context"})
            if item["merged_with"]:
                trace.append({"step": "merge", "kept": True,
                              "merged_with": list(item["merged_with"]),
                              "rule": "win-merge",
                              "threshold": {"iou": 0.40, "containment": 0.70,
                                            "horizontal_overlap": 0.60,
                                            "vertical_gap_px": 20}})
            record["lifecycle"] = {
                "state": "context" if label == 0 else "kept",
                "trace": trace,
            }
            derived_records.append(record)
            derived_by_id[derived_id] = record

        segmenter_model = models.get("bubble-segmenter", {})
        segmenter_enabled = (segmenter_model.get("status") == "ready"
                             and self.settings.get("bubble_segmentation", True))
        mask_cache = raw.get("mask_cache", {}).get("bubble-segmenter", {})
        segmenter_outputs = segmenter_model.get("outputs", []) if segmenter_enabled else []
        if segmenter_enabled:
            # Bind the complete confidence-projected detector set to A1's
            # captured page-space artifacts before OCR-stage deduplication.
            # Detector-only planner proposals use these same stable references.
            for item in canonical:
                record = derived_by_id[item["artifact_id"]]
                assignment = assign_mask_center(segmenter_outputs, mask_cache,
                                                item["box"].as_list())
                if assignment is not None:
                    record["segmenter_assignment"] = assignment

        canonical_texts = [item for item in canonical if item["label"] in (1, 2)]
        same_label_kept_indices, ocr_suppressions = dedupe_within_parents_with_suppressions(
            [item["box"] for item in canonical_texts],
            [item["label"] for item in canonical_texts],
            [item["score"] for item in canonical_texts],
            [item["artifact_id"] for item in canonical_texts],
            [item["box"] for item in canonical if item["label"] == 0])
        for event in ocr_suppressions:
            record = derived_by_id.get(event["loser_id"])
            if record:
                record["lifecycle"]["state"] = "suppressed"
                record["lifecycle"]["trace"].append({
                    "step": event["rule"], "kept": False,
                    "suppressed_by": event["winner_id"],
                    "threshold": event["threshold"]})
        after_ocr = [canonical_texts[i] for i in same_label_kept_indices]
        cross_kept_indices, cross_suppressions = suppress_cross_label_with_suppressions(
            [item["box"] for item in after_ocr],
            [item["label"] for item in after_ocr],
            [item["score"] for item in after_ocr],
            [item["artifact_id"] for item in after_ocr],
            [item["box"] for item in canonical if item["label"] == 0])
        for event in cross_suppressions:
            record = derived_by_id.get(event["loser_id"])
            if record:
                record["lifecycle"]["state"] = "suppressed"
                record["lifecycle"]["trace"].append({
                    "step": event["rule"], "kept": False,
                    "suppressed_by": event["winner_id"],
                    "threshold": event["threshold"]})
        texts = [after_ocr[i] for i in cross_kept_indices]
        order = reading_order_rtl([item["box"] for item in texts], page_wh[1])
        texts = [texts[i] for i in order]

        panel_model = models.get("panel-detector", {})
        panel_assignments_enabled = (
            panel_model.get("status") == "ready"
            and self._panel_skip_reason(*page_wh) is None)
        panel_records = panel_model.get("outputs", []) if panel_assignments_enabled else []
        panel_by_id = {item["id"]: item for item in panel_records}
        active_panel_records = [panel_by_id[artifact_id]
                                for artifact_id in panel_model.get("kept_ids", [])
                                if artifact_id in panel_by_id]
        panel_boxes = [[float(rec["geometry"][key]) for key in ("x1", "y1", "x2", "y2")]
                       for rec in active_panel_records]
        panel_order = reading_order_panel_indices(panel_boxes, self._reading_order_is_rtl())
        ordered_panels = [
            {"id": active_panel_records[index]["id"],
             "box": panel_boxes[index], "score": active_panel_records[index]["attrs"]["score"]}
            for index in panel_order
        ]
        panel_views = [dict(panel, panel_index=index)
                       for index, panel in enumerate(ordered_panels)]

        kept_regions = []
        panel_assignment_records = []
        parent_bubbles = [item["box"] for item in canonical
                          if item["label"] == 0]
        for index, item in enumerate(texts):
            box = item["box"]
            parent = select_parent(box, parent_bubbles) if parent_bubbles else None
            region = {"id": f"r{index:02d}",
                      "artifact_id": item["artifact_id"],
                      "label": item["label"],
                      "class": N_CLASSES.get(item["label"], str(item["label"])),
                      "score": round(item["score"], 3),
                      "box": box.as_list(),
                      "parent_box": parent.as_list() if parent is not None else None,
                      "ocr_box": clamp_pad(box, page_wh).as_list()}
            if item["merged_with"]:
                region["merged_from"] = list(item["merged_with"])
            record = derived_by_id[item["artifact_id"]]
            add_trace(item["artifact_id"], "ocr-dedup", kept=True,
                      threshold={"iou": 0.62, "containment": 0.86,
                                 "center": 0.12, "size": 0.20})
            add_trace(item["artifact_id"], "xlabel", kept=True, threshold=0.3)
            if panel_assignments_enabled:
                assignment = assign_panel(box.as_list(), ordered_panels)
                region["panel_assignment"] = assignment
                panel_assignment_records.append({"artifact_id": item["artifact_id"],
                                                 **assignment})
                add_trace(item["artifact_id"], "panel", kept=True,
                          assignment=assignment)
            assignment = record.get("segmenter_assignment")
            if assignment is not None:
                region["segmenter_assignment"] = assignment
                add_trace(item["artifact_id"], "segmenter-assignment",
                          kept=True, **assignment)
            kept_regions.append(region)

        panel_suppressions = panel_model.get("suppression_records", [])
        suppressions = (detector_suppressions + ocr_suppressions
                        + cross_suppressions + panel_suppressions)
        text_model["derived_outputs"] = derived_records
        text_model["decisions"] = {
            "conf": conf,
            "suppression_records": detector_suppressions + ocr_suppressions + cross_suppressions,
            "merge_records": merge_records,
            "kept_ids": [item["artifact_id"] for item in texts],
            "context_ids": [item["artifact_id"] for item in canonical if item["label"] == 0],
        }
        raw["decisions"] = {
            "conf": conf,
            "suppression_records": suppressions,
            "merge_records": merge_records,
            "kept_ids": [item["artifact_id"] for item in texts],
            "context_ids": [item["artifact_id"] for item in canonical if item["label"] == 0],
            "panel_assignments": panel_assignment_records,
            "panel_assignments_enabled": panel_assignments_enabled,
            "segmenter_assignments_enabled": segmenter_enabled,
        }
        decision_inputs = {
            "algorithm": DETECTION_REPLAY_ALGORITHM,
            "capture_fingerprint": raw.get("capture_fingerprint"),
            "page_fingerprint": raw.get("page_fingerprint"),
            "confidence": conf,
            "panel_assignments_enabled": panel_assignments_enabled,
            "segmenter_assignments_enabled": segmenter_enabled,
            "reading_order_rtl": self._reading_order_is_rtl(),
            "source_language": str(self.settings.get(
                "source_language", self.settings.get("source_lang", "ja"))).strip().lower(),
            "regions": kept_regions,
            "panels": panel_views,
            "candidate_ids": [record.get("id") for record in derived_records],
        }
        decision_fingerprint = _stable_fingerprint(decision_inputs)
        raw["decisions"]["algorithm_version"] = DETECTION_REPLAY_ALGORITHM
        raw["decisions"]["fingerprint"] = decision_fingerprint
        raw["decisions"]["fingerprint_inputs"] = decision_inputs
        if persist:
            self._cache["detections"][page] = raw
            if previous_replay_fingerprint != decision_fingerprint:
                self._save_json("detections.json", self._cache["detections"])
        return {"bubbles": sum(1 for item in canonical if item["label"] == 0),
                "regions": kept_regions, "panels": panel_views,
                "decision_fingerprint": decision_fingerprint}

    # --------------------------------------------------------------------- OCR
    def _mangaocr_batch_size(self) -> int:
        try:
            requested = int(self.settings.get("max_batch", 8))
        except (TypeError, ValueError):
            requested = 8
        requested = max(1, requested)
        return 1 if self.settings.get("mangaocr_serial_timing", False) else requested

    def _ocr_batch_setting(self) -> int:
        try:
            return max(1, int(self.settings.get("max_batch", 8)))
        except (TypeError, ValueError):
            return 8

    def _ocr_engine(self):
        if self._ocr is None:
            t0 = time.perf_counter()
            log("initializing OCR (derived graphs + B=1 gate)…")
            self._ocr = OcrEngine(max_batch=self._mangaocr_batch_size())
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
            try:
                detector = paddle_ocr.PaddleDet()
            except Exception as error:
                detector = None
                log(f"PaddleOCR detector unavailable — using single-line/ink-gap fallback: {error}")
            self._paddle = (detector, paddle_ocr.PaddleRec())
            self._load_times["ocr_paddle"] = round((time.perf_counter() - t0) * 1000, 1)
            log(f"PaddleOCR ready in {self._load_times['ocr_paddle']}ms — det={'ready' if detector else 'fallback'}, "
                f"dict={len(self._paddle[1].dictionary)} classes")
        return self._paddle

    def _ocr_engine_name(self) -> str:
        return self.settings.get("ocr_engine", "mangaocr")

    def _ocr_model_assets(self, engine_name: str) -> dict:
        if engine_name == "paddle":
            try:
                import paddle_ocr
                paths = {"detector": paddle_ocr.DET_MODEL,
                         "recognizer": paddle_ocr.REC_MODEL,
                         "dictionary": paddle_ocr.REC_DICT}
                return {name: _asset_identity(path) for name, path in paths.items()}
            except Exception as error:
                return {"status": "unavailable", "error": type(error).__name__}
        try:
            return {name: _asset_identity(path)
                    for name, path in lab_assets.model_paths().items()}
        except Exception as error:
            return {"status": "unavailable", "error": type(error).__name__}

    def _ocr_cache_inputs(self, page: str, engine_name: str,
                          decision_fingerprint: str,
                          regions: list[dict]) -> dict:
        page_info = self._page_fingerprints[page]
        if engine_name == "paddle":
            settings = {
                "max_batch": self._ocr_batch_setting(),
                "source_language": str(self.settings.get(
                    "source_language", self.settings.get("source_lang", "ja"))),
                "webtoon_mode": bool(self.settings.get("webtoon_mode", False)),
                "reading_order_rtl": self._reading_order_is_rtl(),
            }
        else:
            settings = {
                "max_batch": self._mangaocr_batch_size(),
                "serial_timing": bool(self.settings.get(
                    "mangaocr_serial_timing", False)),
            }
        return {
            "algorithm": OCR_ALGORITHM,
            "page_fingerprint": page_info["fingerprint"],
            "detection_decision_fingerprint": decision_fingerprint,
            "engine": engine_name,
            "models": self._ocr_model_assets(engine_name),
            "settings": settings,
            "regions": [{key: region.get(key) for key in
                         ("id", "artifact_id", "label", "score", "box", "ocr_box",
                          "segmenter_assignment", "panel_assignment")}
                        for region in regions],
        }

    def _recognize_crops(self, crops: list[Image.Image], regions: list[dict] | None = None,
                         page_image: Image.Image | None = None) -> tuple[list[dict], dict, float]:
        """Dispatch on settings['ocr_engine']: mangaocr (T927 lab stack) or
        paddle (v6 small det+rec). Both return the same region dicts."""
        if self._ocr_engine_name() == "paddle":
            import paddle_ocr
            det, rec = self._paddle_engines()
            t0 = time.perf_counter()
            region_boxes = [r.get("box", (0, 0, crop.width, crop.height))
                            for r, crop in zip(regions or [], crops)]
            if len(region_boxes) < len(crops):
                region_boxes.extend((0, 0, crops[i].width, crops[i].height)
                                    for i in range(len(region_boxes), len(crops)))

            def unpadded_crop(index: int):
                if page_image is None or regions is None:
                    raise ValueError("page image and regions are required for unpadded Paddle reread")
                return page_image.crop(tuple(regions[index]["box"]))

            language = str(self.settings.get(
                "source_language", self.settings.get("source_lang", "ja")))
            normalized_language = language.strip().lower().replace("_", "-")
            webtoon_mode = bool(self.settings.get("webtoon_mode", False))
            webtoon_mode = webtoon_mode or bool(
                page_image is not None
                and is_tall_image(page_image.width, page_image.height))
            webtoon_mode = webtoon_mode or normalized_language in {
                "ko", "kor", "korean"} or normalized_language.startswith("ko-")
            webtoon_mode = webtoon_mode or not self._reading_order_is_rtl()
            recognized = paddle_ocr.recognize_regions(
                det, rec, crops,
                max_batch=self._ocr_batch_setting(),
                region_boxes=region_boxes,
                language=language,
                webtoon_mode=webtoon_mode,
                unpadded_crop_factory=unpadded_crop if page_image is not None and regions is not None else None,
            )
            out = []
            for result in recognized:
                text = result.get("text", "")
                error = result.get("error")
                if error:
                    log(f"!! paddle rec failed: {error}")
                row = {
                    "text": text,
                    "raw_text": text,
                    "confidence": None,
                    "lines": result.get("lines", []),
                    "engine": "paddle",
                }
                if error:
                    row["error"] = error
                out.append(row)
            ms = round((time.perf_counter() - t0) * 1000, 1)
            trace = getattr(rec, "last_batch_trace", [])
            return out, {
                "engine": "paddle", "regions": len(out),
                "batches": len(trace),
                "fallback_batches": sum(bool(row.get("fallback")) for row in trace),
            }, ms
        engine = self._ocr_engine()
        engine.max_batch = self._mangaocr_batch_size()
        results, runs, ms = engine.decode_regions(crops)
        return results, runs, ms

    @_page_operation
    def ocr_page(self, page: str, conf: float | None = None,
                 force: bool = False) -> dict:
        with self.lock:
            conf = float(self.settings["conf"] if conf is None else conf)
            detection = self.detect_page(page, conf)
            regions = detection["regions"]
            engine_name = self._ocr_engine_name()
            prev_entry = self._cache["ocr"].get(page) or {}
            cache_inputs = self._ocr_cache_inputs(
                page, engine_name, detection["decision_fingerprint"], regions)
            cache_fingerprint = _stable_fingerprint(cache_inputs)
            if (not force
                    and prev_entry.get("cache_fingerprint") == cache_fingerprint
                    and isinstance(prev_entry.get("regions"), list)):
                self._prepare_region_identity(
                    page, prev_entry["regions"], prev_entry["regions"])
                self._refresh_page_ocr_fingerprint(prev_entry)
                self._cache["ocr"][page] = prev_entry
                pruned = self.carry_translations(page)
                self._save_json("ocr.json", self._cache["ocr"])
                return {"page": page, "regions": prev_entry["regions"],
                        "runs": prev_entry.get("runs", {}),
                        "ms": prev_entry.get("ms", 0),
                        "infer_ms": prev_entry.get("infer_ms", prev_entry.get("ms", 0)),
                        "load_ms": prev_entry.get("load_ms", 0),
                        "engine": engine_name, "cache_hit": True,
                        "cache_fingerprint": cache_fingerprint,
                        "pruned": pruned}

            # Cached results from a different engine are not reusable.
            previous_regions = copy.deepcopy(
                prev_entry.get("regions", [])
                if isinstance(prev_entry.get("regions"), list) else [])
            if prev_entry.get("engine", "mangaocr") != engine_name:
                prev_entry = {}
            if regions:
                img = self.page_image(page)
                crops = []
                for region in regions:
                    box = Box(*recognition_input_box(
                        region, engine_name, [img.width, img.height]))
                    crops.append(img.crop((box.x1, box.y1, box.x2, box.y2)))
                results, runs, ms = self._recognize_crops(
                    crops, regions=regions, page_image=img)
            else:
                results, runs, ms = [], {}, 0

            for region, result in zip(regions, results):
                region.update(result)
            self._prepare_region_identity(page, regions, previous_regions)
            load_ms = self._load_times.get(f"ocr_{engine_name}", 0)
            ocr_entry = {
                "regions": regions, "runs": runs,
                "ms": ms, "infer_ms": ms, "load_ms": load_ms,
                "engine": engine_name,
                "page_fingerprint": self._current_page_fingerprint(page),
                "detection_fingerprint": detection["decision_fingerprint"],
                "cache_fingerprint": cache_fingerprint,
                "cache_inputs": cache_inputs,
            }
            self._refresh_page_ocr_fingerprint(ocr_entry)
            self._cache["ocr"][page] = ocr_entry
            self._crop_cache.pop(page, None)
            self._save_json("ocr.json", self._cache["ocr"])
            pruned = self.carry_translations(page)
            self._render_dirty[page] = True
            texts = [r.get("text", "") for r in regions]
            self.log(f"ocr[{engine_name}] {page}: {len(regions)} regions, "
                     f"infer {ms}ms (load {load_ms}ms, {sum(1 for t in texts if t)} non-empty)")
            return {"page": page, "regions": regions, "runs": runs,
                    "ms": ms, "infer_ms": ms, "load_ms": load_ms,
                    "engine": engine_name, "cache_hit": False,
                    "cache_fingerprint": cache_fingerprint,
                    "pruned": pruned}

    # -------------------------------------------------------------- translate
    def translations(self, page: str) -> dict:
        """Return display-region ids mapped to the stable-keyed cache text."""
        page_cache = self._cache.get("translations", {}).get(page, {})
        if not isinstance(page_cache, dict):
            return {}
        regions = self._cache.get("ocr", {}).get(page, {}).get("regions", [])
        result = {}
        for region in regions:
            stable_id = _region_cache_id(region)
            display_id = str(region.get("id") or stable_id)
            # Keep an in-memory legacy alias readable until the next OCR
            # reconciliation can map it from persisted region evidence.  New
            # writes always use stable keys; stable data wins if both exist.
            cache_id = stable_id if stable_id in page_cache else display_id
            if cache_id in page_cache:
                result[display_id] = _translation_text(page_cache[cache_id])
        return result

    def set_translation(self, page: str, region_id: str, text: str) -> dict:
        with self.lock:
            regions = self._cache.get("ocr", {}).get(page, {}).get("regions", [])
            region = next((item for item in regions
                           if str(item.get("id")) == str(region_id)
                           or _region_cache_id(item) == str(region_id)), None)
            stable_id = _region_cache_id(region) if region else str(region_id)
            fingerprint = ((region.get("ocr_fingerprint") or _region_fingerprint(region))
                           if region else None)
            page_cache = self._cache["translations"].setdefault(page, {})
            page_cache[stable_id] = _translation_record(
                text, origin="user", fingerprint=fingerprint, region=region)
            page_cache["_schema"] = _TRANSLATION_SCHEMA
            self._save_json("translations.json", self._cache["translations"])
            self._render_dirty[page] = True
            return {"page": page, "region_id": region_id,
                    "stable_id": stable_id, "saved": True}

    def carry_translations(self, page: str) -> list[dict]:
        """Reconcile stable translation keys after OCR, retaining an audit trail."""
        regs = self._cache["ocr"].get(page, {}).get("regions", [])
        page_tr = self._cache["translations"].setdefault(page, {})
        before_pruned = len(page_tr.get("_pruned", [])) if isinstance(
            page_tr.get("_pruned"), list) else 0
        old_id_to_stable = {
            str(region.get("id")): _region_cache_id(region)
            for region in regs if region.get("id")
        }
        migrated = _migrate_translation_page(page_tr, old_id_to_stable, regs)
        _carry_prune_translation_page(page_tr, regs)
        audit = page_tr.get("_pruned", [])
        pruned = audit[before_pruned:] if isinstance(audit, list) else []
        if migrated or page_tr:
            self._save_json("translations.json", self._cache["translations"])
        if pruned:
            self._render_dirty[page] = True
            self.log(f"translation prune {page}: {len(pruned)} unmappable entr"
                     f"{'y' if len(pruned) == 1 else 'ies'} (audit retained)")
        return pruned

    def translate_page(self, page: str) -> dict:
        """Fill missing translations. Backend from settings.translate_backend:
        'google' (free gtx web endpoint) or 'lm-studio' (OpenAI-compatible).
        Only validated provider results are cached; failed/source-equal blocks
        remain untranslated so a later run can retry them."""
        with self.lock:
            ocr = self._cache["ocr"].get(page)
            if not ocr or not ocr["regions"]:
                raise ValueError(f"no OCR regions for {page} — run OCR first")
            page_cache = self._cache["translations"].get(page, {})
            cache_changed = False
            cache_ids = {}
            for region in ocr["regions"]:
                cache_id = _region_cache_id(region)
                cache_ids[region["id"]] = cache_id
                cached = page_cache.get(cache_id)
                source = str(region.get("text") or "").strip()
                cached_text = _translation_text(cached)
                if cached_text and source and cached_text.strip() == source:
                    # Android validation treats a source echo as untranslated.
                    # Clear stale values so they can be retried. Metadata is
                    # removed with the rejected text, as with the old cache.
                    page_cache.pop(cache_id, None)
                    cache_changed = True
            targets = [(index, region) for index, region in enumerate(ocr["regions"])
                       if region.get("text")
                       and not _translation_text(page_cache.get(cache_ids[region["id"]]))]
            if not targets:
                if cache_changed:
                    self._save_json("translations.json", self._cache["translations"])
                    self._render_dirty[page] = True
                return {"page": page, "translated": 0, "message": "nothing to translate"}
            backend = self.settings.get("translate_backend", "lm-studio")
            target_lang = self.settings.get("target_lang", "English")
            source_lang = self.settings.get("source_lang", "Japanese")
            region_targets = [(cache_ids[region["id"]], region["text"])
                              for _, region in targets]
            result_by_region: dict[str, str | None]
            if backend == "google":
                translated = translate_google_batch(
                    [region["text"] for _, region in targets],
                    target_lang,
                    source_lang,
                )
                result_by_region = {
                    cache_ids[region["id"]]: text
                    for (_, region), text in zip(targets, translated)
                }
            else:
                try:
                    page_index = self.pages.index(page)
                except ValueError:
                    page_index = 0
                geometries = []
                for region in ocr["regions"]:
                    box = region.get("box") or [0, 0, 0, 0]
                    x1, y1, x2, y2 = (list(box) + [0, 0, 0, 0])[:4]
                    geometries.append((float(x1), float(y1),
                                       float(x2) - float(x1), float(y2) - float(y1)))
                stable_indexes = stable_openai_block_indexes(geometries)
                for region_index, region in enumerate(ocr["regions"]):
                    persisted_index = _stable_block_index(
                        cache_ids[region["id"]])
                    if persisted_index is not None:
                        stable_indexes[region_index] = persisted_index
                protocol_blocks = [
                    (f"p{page_index}_b{stable_indexes[region_index]}", region["text"])
                    for region_index, region in targets
                ]
                try:
                    requested_output_tokens = int(self.settings.get(
                        "translation_output_tokens", AI_DEFAULT_OUTPUT_TOKENS))
                except (TypeError, ValueError):
                    requested_output_tokens = AI_DEFAULT_OUTPUT_TOKENS
                translated = translate_openai_compat_batch(
                    protocol_blocks,
                    target_lang,
                    self.settings.get("endpoint", "http://127.0.0.1:1234/v1"),
                    self.settings.get("model", "local-model"),
                    source_language=source_lang,
                    temperature=float(self.settings.get("temperature", 0.2)),
                    requested_output_tokens=requested_output_tokens,
                )
                region_by_protocol_id = {
                    f"p{page_index}_b{stable_indexes[region_index]}": region
                    for region_index, region in targets
                }
                result_by_region = {
                    cache_ids[region["id"]]: translated.get(protocol_id)
                    for protocol_id, region in region_by_protocol_id.items()
                }

            provider_cache = {
                key: _translation_text(value)
                for key, value in page_cache.items()
                if key not in _TRANSLATION_META_KEYS
            }
            out = cache_valid_translations(provider_cache, region_targets,
                                           result_by_region)
            cache_changed = cache_changed or bool(out)
            if cache_changed:
                region_by_cache_id = {cache_ids[region["id"]]: region
                                      for region in ocr["regions"]}
                for entry in out:
                    stable_id = entry["id"]
                    region = region_by_cache_id.get(stable_id)
                    page_cache[stable_id] = _translation_record(
                        entry.get("text", ""), origin="auto",
                        fingerprint=((region.get("ocr_fingerprint")
                                      or _region_fingerprint(region))
                                     if region else None),
                        region=region)
                if page_cache:
                    page_cache["_schema"] = _TRANSLATION_SCHEMA
                self._cache["translations"][page] = page_cache
                self._save_json("translations.json", self._cache["translations"])
                self._render_dirty[page] = True
            translated_ids = {entry["id"] for entry in out}
            untranslated = [region["id"] for _, region in targets
                            if cache_ids[region["id"]] not in translated_ids]
            output_regions = []
            for entry in out:
                stable_id = entry["id"]
                region = next((row for row in ocr["regions"]
                               if cache_ids[row["id"]] == stable_id), None)
                output_regions.append({**entry, "stable_id": stable_id,
                                       "id": region.get("id") if region else stable_id})
            self.log(f"translate {page}: {len(out)}/{len(targets)} regions via {backend}")
            return {"page": page, "translated": len(out), "regions": output_regions,
                    "untranslated": untranslated}

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
                try:
                    log("loading PaddleOCR v6 det (free-text line refinement)…")
                    self._paddle_det = paddle_ocr.PaddleDet()
                except Exception as e:
                    log(f"Paddle det unavailable — free-text boxes stay unrefined: {e}")
                    self._paddle_det_unavailable = True
                    return None
        return self._paddle_det

    def _aot_inpainter(self, allow_dynamic: bool = False):
        import aot_inpaint
        fixed_ready = (self._aot.ready if self._aot is not None
                       else aot_inpaint.AotInpainter().ready)
        dynamic_ready = False
        if allow_dynamic:
            import inpaint_android
            dynamic_ready = inpaint_android.AOT_DYNAMIC_MODEL.exists()
        if not fixed_ready and not dynamic_ready:
            return None
        if self._aot is None:
            t0 = time.perf_counter()
            log("loading AOT inpainting sessions (QUALITY leg)…")
            self._aot = aot_inpaint.AotInpainter()
            self._load_times["aot"] = round((time.perf_counter() - t0) * 1000, 1)
            log(f"AOT wrapper ready in {self._load_times['aot']}ms")
        return self._aot

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

    @_page_operation
    def bubble_masks(self, page: str) -> list:
        """Return masks for legacy callers, preferring the captured A1 output.

        Android inpaint and render consume the RLE resolver directly. This
        adapter exists for the legacy engine and the segmentation overlay; a
        current capture is never replaced with a fresh segmenter inference.
        """
        if not self.settings.get("bubble_segmentation", True):
            return []
        with self.lock:
            if page not in self._bubble_mask_cache:
                capture = self._cache.get("detections", {}).get(page) or {}
                if capture.get("capture_version") == 1:
                    # Tall captures intentionally stay as page-space RLEs.
                    # The A4 Android consumer reads selected runs directly.
                    if capture.get("is_tall"):
                        self._bubble_mask_cache[page] = []
                        return []
                    model = (capture.get("models", {})
                             .get("bubble-segmenter", {}))
                    mask_cache = (capture.get("mask_cache", {})
                                  .get("bubble-segmenter", {}))
                    width, height = capture.get("page_wh", self.page_dims(page))
                    masks = []
                    if model.get("status") == "ready":
                        from segmentation import BubbleMask
                        for output in model.get("outputs", []):
                            ref = output.get("mask_ref")
                            entry = mask_cache.get(ref, {})
                            runs = entry.get("runs", [])
                            dense = np.zeros((height, width), dtype=bool)
                            for y, x1, x2 in iter_rle_row_spans(runs, width, height):
                                dense[y, x1:x2] = True
                            if dense.any():
                                masks.append(BubbleMask.build(
                                    dense, float(output.get("attrs", {}).get("score", 0.0))))
                    self._bubble_mask_cache[page] = masks
                    return masks
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

    def _inpaint_execution_projection(self, page: str, conf: float) -> dict:
        """Join cached OCR text to the current A1 execution projection."""
        raw = self._cache.get("detections", {}).get(page) or {}
        ocr_data = self._cache.get("ocr", {}).get(page) or {}
        if raw.get("capture_version") != 1:
            def meets_confidence(score) -> bool:
                try:
                    value = float(score)
                except (TypeError, ValueError, OverflowError):
                    return False
                return math.isfinite(value) and value >= conf

            raw_boxes = [row for row in raw.get("boxes", [])
                         if len(row) >= 6 and meets_confidence(row[1])]
            regions = [region for region in ocr_data.get("regions", [])
                       if meets_confidence(region.get("score", 1.0))]
            return {"current_capture": False, "regions": regions,
                    "detections": raw_boxes,
                    "candidate_ids": [],
                    "decision_fingerprint": _stable_fingerprint({
                        "schema": "legacy", "confidence": conf,
                        "detections": raw_boxes,
                        "regions": [{key: region.get(key) for key in
                                     ("id", "label", "score", "box", "text")}
                                    for region in regions]}),
                    "mask_capture_integrity": {
                        "status": "legacy-schema", "degraded": False,
                        "missing_mask_refs": []},
                    "ocr_join": {"status": "legacy-schema-fallback",
                                 "matched": len(regions), "unmatched": 0}}

        projected = self._regions_at_conf(page, conf)
        raw = self._cache["detections"][page]
        text_model = raw.get("models", {}).get("text-detector", {})
        candidates = text_model.get("derived_outputs", [])
        segmenter_model = raw.get("models", {}).get("bubble-segmenter", {})
        mask_cache = raw.get("mask_cache", {}).get("bubble-segmenter", {})
        if segmenter_model.get("status") == "ready":
            missing_mask_refs = [
                item.get("mask_ref") or item.get("id")
                for item in segmenter_model.get("outputs", [])
                if not mask_cache.get(item.get("mask_ref"))
            ]
            mask_status = ("degraded-missing-references" if missing_mask_refs
                           else "complete")
        else:
            missing_mask_refs = []
            mask_status = segmenter_model.get("status", "unknown")
        region_by_artifact = {region.get("artifact_id"): region
                              for region in projected.get("regions", [])
                              if region.get("artifact_id")}
        joined = []
        unmatched = 0
        for cached_region in ocr_data.get("regions", []):
            artifact_id = cached_region.get("artifact_id")
            current_region = region_by_artifact.get(artifact_id)
            if not artifact_id or current_region is None:
                unmatched += 1
                continue
            region = dict(current_region)
            for key in ("id", "text", "raw_text", "confidence", "lines",
                        "engine", "error", "carried_from"):
                if key in cached_region:
                    region[key] = cached_region[key]
            region["artifact_id"] = artifact_id
            joined.append(region)
        join_status = "joined" if unmatched == 0 else "degraded-unjoined-ocr"
        if not ocr_data.get("regions"):
            join_status = "no-cached-ocr"
        return {
            "current_capture": True,
            "regions": joined,
            "detections": candidates,
            "candidate_ids": [record.get("id") for record in candidates],
            "decision_fingerprint": projected.get("decision_fingerprint"),
            "mask_capture_integrity": {
                "status": mask_status, "degraded": bool(missing_mask_refs),
                "missing_mask_refs": missing_mask_refs},
            "ocr_join": {"status": join_status, "matched": len(joined),
                         "unmatched": unmatched},
        }

    def _normalized_inpaint_variant_params(self, engine: str) -> dict:
        def integer(key: str, default: int, low: int, high: int) -> int:
            try:
                value = int(self.settings.get(key, default))
            except (TypeError, ValueError, OverflowError):
                value = default
            return max(low, min(high, value))

        erosion = integer("bubble_mask_erosion", 5, 0, 20)
        raw_feather = self.settings.get("inpaint_feather_px")
        feather = None
        if raw_feather not in (None, ""):
            try:
                feather = max(2, min(48, int(raw_feather)))
            except (TypeError, ValueError, OverflowError):
                feather = None
        return {
            "opencv_method": ("ns" if str(self.settings.get(
                "inpaint_opencv_method", "telea")).lower() == "ns" else "telea"),
            "telea_radius": integer("inpaint_telea_radius", 3, 1, 20),
            "erosion_radius": erosion,
            # None preserves Android's path-specific 12px bubble / 3px text defaults.
            "feather_px": feather,
        }

    def _inpaint_cache_inputs(self, page: str, *, engine: str, mode: str,
                              bubble_leg: str, free_leg: str, conf: float,
                              variant_params: dict,
                              execution: dict, regions: list[dict],
                              planner_detections: list,
                              planner_items: list[dict],
                              bubble_items: list[dict],
                              free_items: list[dict]) -> dict:
        import inpaint_android

        raw = self._cache.get("detections", {}).get(page) or {}
        mask_cache = (raw.get("mask_cache", {})
                      .get("bubble-segmenter", {}))
        segmenter_outputs = (raw.get("models", {})
                             .get("bubble-segmenter", {}).get("outputs", []))
        refs: dict[tuple[str, str], dict] = {}
        for source in list(regions) + [
                item for item in planner_detections if isinstance(item, dict)]:
            assignment = source.get("segmenter_assignment") or {}
            ref = assignment.get("mask_ref")
            component = assignment.get("mask_component_id")
            if not ref:
                continue
            key = (str(ref), str(component))
            if key in refs:
                continue
            entry = mask_cache.get(ref, {})
            runs = (entry.get("components", {}).get(str(component))
                    if component is not None else None)
            if runs is None:
                runs = entry.get("runs", [])
            output = next((item for item in segmenter_outputs
                           if item.get("mask_ref") == ref), {})
            refs[key] = {
                "mask_ref": str(ref),
                "segmenter_component_id": component,
                "rle_sha256": _stable_fingerprint(runs),
                "source": assignment.get("source") or output.get("source"),
            }

        def detector_candidate(value):
            if isinstance(value, dict):
                attrs = value.get("attrs", {})
                geometry = value.get("geometry", {})
                box = value.get("box")
                if box is None and all(key in geometry for key in
                                       ("x1", "y1", "x2", "y2")):
                    box = [geometry[key] for key in ("x1", "y1", "x2", "y2")]
                return {
                    "id": value.get("id"),
                    "artifact_id": value.get("artifact_id") or value.get("id"),
                    "label": attrs.get("label", value.get("label")),
                    "score": attrs.get("score", value.get("score")),
                    "box": box,
                    "segmenter_assignment": value.get("segmenter_assignment"),
                }
            if isinstance(value, (list, tuple)) and len(value) >= 6:
                return {"label": value[0], "score": value[1],
                        "box": list(value[2:6])}
            return {"value": value}

        def planner_item(value):
            return {key: value.get(key) for key in
                    ("box", "label", "kind", "record_ids", "source")}

        region_inputs = [{key: region.get(key) for key in
                          ("id", "artifact_id", "box", "ocr_box", "label", "score",
                           "text", "segmenter_assignment", "panel_assignment")}
                         for region in regions]
        ocr_entry = self._cache.get("ocr", {}).get(page) or {}
        ocr_fingerprint = ocr_entry.get("ocr_fingerprint")
        if not ocr_fingerprint:
            ocr_fingerprint = _stable_fingerprint({
                "legacy_ocr": [{key: region.get(key) for key in
                                ("id", "artifact_id", "box", "label", "score", "text")}
                               for region in ocr_entry.get("regions", [])]})

        page_info = self._page_fingerprints[page]
        assets: dict[str, dict] = {}
        uses_bubble_aot = bubble_leg == "aot" and bool(bubble_items)
        uses_free_aot = free_leg == "aot" and bool(free_items)
        if engine == "legacy" and mode == "QUALITY" and planner_items:
            uses_bubble_aot = True
        if uses_bubble_aot or uses_free_aot:
            import aot_inpaint
            assets["aot_fixed"] = _asset_identity(aot_inpaint.AOT_MODEL)
            assets["aot_dynamic"] = _asset_identity(
                inpaint_android.AOT_DYNAMIC_MODEL)
        if free_items:
            try:
                import paddle_ocr
                assets["paddle_detector"] = _asset_identity(paddle_ocr.DET_MODEL)
            except Exception as error:
                assets["paddle_detector"] = {
                    "status": "unavailable", "error": type(error).__name__}

        parameters = {
            "engine": engine,
            "leg_matrix": {"bubble": bubble_leg, "free_text": free_leg},
            "variant_params": variant_params,
            "variant_params_hash": _stable_fingerprint(variant_params),
            "legacy_mode": mode if engine == "legacy" else None,
            "execution_confidence": conf,
            "bubble_segmentation": bool(
                self.settings.get("bubble_segmentation", True)),
        }
        if engine == "legacy":
            parameters["bubble_mask_erosion"] = int(
                self.settings.get("bubble_mask_erosion", 5))
        else:
            parameters["planner_constants"] = {
                "bubble_erosion": inpaint_android.BUBBLE_SEG_EROSION,
                "bubble_box_pad": inpaint_android.BUBBLE_BOX_PAD,
                "bubble_smooth_passes": inpaint_android.BUBBLE_SMOOTH_PASSES,
                "bubble_feather": inpaint_android.BUBBLE_FEATHER,
                "free_text_context": inpaint_android.FREE_TEXT_CONTEXT,
                "free_text_pad": inpaint_android.FREE_TEXT_PAD,
                "free_text_dilate": inpaint_android.FREE_TEXT_DILATE,
                "free_text_feather": inpaint_android.FREE_TEXT_FEATHER,
                "paddle_crop_pad": inpaint_android.PADDLE_CROP_PAD,
                "paddle_thresh": inpaint_android.PADDLE_THRESH,
                "paddle_box_thresh": inpaint_android.PADDLE_BOX_THRESH,
            }
        if ((engine == "legacy" and mode == "FAST")
                or (bubble_leg == "opencv" and bubble_items)
                or (free_leg == "opencv" and free_items)):
            try:
                import cv2
                parameters["opencv_version"] = cv2.__version__
            except Exception:
                parameters["opencv_version"] = None

        return {
            "schema": 2,
            "algorithm": INPAINT_ALGORITHM,
            "page_fingerprint": page_info["fingerprint"],
            "detection_decision_fingerprint": execution.get(
                "decision_fingerprint"),
            "ocr_fingerprint": ocr_fingerprint,
            "regions": region_inputs,
            "planner_candidate_ids_scores": [detector_candidate(item)
                                             for item in planner_detections],
            "planner_items": [planner_item(item) for item in planner_items],
            "bubble_items": [planner_item(item) for item in bubble_items],
            "free_text_items": [planner_item(item) for item in free_items],
            "mask_rles": [refs[key] for key in sorted(refs)],
            "parameters": parameters,
            "model_assets": assets,
        }

    @staticmethod
    def _inpaint_cache_base_fingerprint(cache_inputs: dict) -> str:
        """Fingerprint inputs shared by leg/parameter variants of one plan."""
        base = copy.deepcopy(cache_inputs)
        parameters = base.get("parameters", {})
        for key in ("leg_matrix", "variant_params", "variant_params_hash"):
            parameters.pop(key, None)
        return _stable_fingerprint(base)

    def _store_inpaint_variant(self, page: str, fingerprint: str,
                               out_path: Path, mask_path: Path,
                               provenance_path: Path) -> Path | None:
        target = self._inpaint_variant_dir(page, fingerprint)
        if target is None or not all(path.is_file()
                                     for path in (out_path, mask_path, provenance_path)):
            return None
        target.parent.mkdir(parents=True, exist_ok=True)
        pending = target.with_name(target.name + ".pending")
        if pending.exists():
            if pending.is_dir():
                shutil.rmtree(pending)
            else:
                pending.unlink()
        pending.mkdir()
        try:
            shutil.copy2(out_path, pending / "inpaint.png")
            shutil.copy2(mask_path, pending / "mask.png")
            shutil.copy2(provenance_path, pending / "provenance.json")
            if target.exists():
                if target.is_dir():
                    shutil.rmtree(target)
                else:
                    target.unlink()
            pending.replace(target)
        except Exception:
            if pending.exists():
                shutil.rmtree(pending, ignore_errors=True)
            raise
        return target

    def _load_inpaint_variant(self, page: str, fingerprint: str,
                              cache_key: dict) -> tuple[dict, Path] | None:
        folder = self._inpaint_variant_dir(page, fingerprint)
        if folder is None:
            return None
        out_path, mask_path = folder / "inpaint.png", folder / "mask.png"
        provenance_path = folder / "provenance.json"
        if not all(path.is_file() for path in (out_path, mask_path, provenance_path)):
            return None
        try:
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            return None
        if (provenance.get("cache_key") != cache_key
                or not inpaint_provenance.crop_artifacts_are_current(
                    provenance, fingerprint, self.studio_dir)):
            return None
        return provenance, folder

    def _prune_inpaint_variant_cache(self, page: str,
                                     active_fingerprint: str) -> None:
        page_dir = self._inpaint_variant_page_dir(page)
        if page_dir is None or not page_dir.is_dir():
            return
        variants = []
        for path in list(page_dir.iterdir()):
            if path.is_dir() and len(path.name) == 64 and all(
                    ch in "0123456789abcdef" for ch in path.name):
                variants.append(path)
            elif path.name.endswith(".pending"):
                shutil.rmtree(path, ignore_errors=True) if path.is_dir() else path.unlink(missing_ok=True)
        variants.sort(key=lambda path: path.stat().st_mtime_ns, reverse=True)
        keep = {active_fingerprint}
        for path in variants:
            if len(keep) >= INPAINT_VARIANT_CACHE_LIMIT:
                break
            keep.add(path.name)
        for path in variants:
            if path.name not in keep:
                shutil.rmtree(path, ignore_errors=True)
        inpaint_provenance.prune_stale_crop_runs(
            self.studio_dir, page, keep)

    @_page_operation
    def inpaint_page(self, page: str, force: bool = False, mode: str | None = None) -> dict:
        with self.lock:
            mode = (mode or self.settings.get("inpaint_mode", "quality")).upper()
            engine = str(self.settings.get("inpaint_engine", "legacy")).lower()
            bubble_leg = str(self.settings.get("inpaint_bubble_leg", "android-fill")).lower()
            free_leg = str(self.settings.get("inpaint_free_leg", "opencv")).lower()
            if engine not in ("legacy", "android"):
                raise ValueError(f"unsupported inpaint engine: {engine}")
            variant_params = self._normalized_inpaint_variant_params(engine)
            variant_params_hash = _stable_fingerprint(variant_params)
            conf = float(self.settings.get("conf", DEFAULT_SETTINGS["conf"]))
            # Validate captures and, when present, the OCR record before using
            # them to form this stage's cache key. These calls are cheap hits
            # for current inputs and refresh stale upstream stages otherwise.
            self.detect_page(page, conf)
            if page in self._cache.get("ocr", {}):
                self.ocr_page(page, conf)
            det_data = self._cache["detections"].get(page) or {}
            raw_boxes = det_data.get("boxes", [])
            execution = self._inpaint_execution_projection(page, conf)
            regions = execution["regions"]
            planner_detections = execution["detections"]
            current_capture = execution["current_capture"]
            import inpaint_android
            planner_items, _ = inpaint_android.plan_erase_regions(
                regions, planner_detections,
                min_confidence=(conf if current_capture else None))
            bubble_items, free_items = inpaint_android.partition_erase_items(
                planner_items, [item.get("label", 2) for item in planner_items])
            if current_capture:
                legacy_raw_boxes = [
                    [int(row[0]), float(row[1]),
                     *[int(v) for v in row[2:6]]]
                    for row in raw_boxes if len(row) >= 6
                    and math.isfinite(float(row[1])) and float(row[1]) >= conf
                ]
            else:
                legacy_raw_boxes = planner_detections

            out_dir = self.studio_dir / "inpaint"
            mask_dir = self.studio_dir / "inpaint_mask"
            out_dir.mkdir(parents=True, exist_ok=True)
            mask_dir.mkdir(parents=True, exist_ok=True)
            stem = Path(page).stem
            out_path = out_dir / (stem + ".png")
            mask_path = mask_dir / (stem + ".png")
            provenance_path = out_dir / (stem + ".json")
            leg_matrix = {"bubble": bubble_leg, "free_text": free_leg}
            cache_inputs = self._inpaint_cache_inputs(
                page, engine=engine, mode=mode, bubble_leg=bubble_leg,
                free_leg=free_leg, conf=conf, variant_params=variant_params,
                execution=execution,
                regions=regions, planner_detections=planner_detections,
                planner_items=planner_items, bubble_items=bubble_items,
                free_items=free_items)
            cache_key = {**cache_inputs,
                         "fingerprint": _stable_fingerprint(cache_inputs),
                         "base_fingerprint": self._inpaint_cache_base_fingerprint(
                             cache_inputs),
                         "variant_params": variant_params,
                         "variant_params_hash": variant_params_hash,
                         # Keep A4-visible parameters at the key's top level.
                         "engine": engine,
                         "leg_matrix": leg_matrix,
                         "legacy_mode": mode if engine == "legacy" else None,
                         "execution_confidence": conf,
                         "bubble_segmentation": bool(
                             self.settings.get("bubble_segmentation", True))}
            cached = {}
            if provenance_path.exists():
                try:
                    with open(provenance_path, "r", encoding="utf-8") as f:
                        cached = json.load(f)
                except Exception:
                    cached = {}
            cache_matches = (cached.get("cache_key") == cache_key
                             and inpaint_provenance.crop_artifacts_are_current(
                                 cached, cache_key["fingerprint"], self.studio_dir))

            # Preserve the currently active result before replacing its canonical
            # page paths with another leg/parameter variant. This makes a later
            # switch back a real cache hit instead of another reconstruction.
            if (not force and not cache_matches
                    and cached.get("cache_key", {}).get("base_fingerprint")
                    == cache_key["base_fingerprint"]
                    and out_path.is_file() and mask_path.is_file()
                    and inpaint_provenance.crop_artifacts_are_current(
                        cached, cached.get("cache_key", {}).get("fingerprint", ""),
                        self.studio_dir)):
                try:
                    self._store_inpaint_variant(
                        page, cached["cache_key"]["fingerprint"], out_path,
                        mask_path, provenance_path)
                except Exception as error:
                    self.log(f"!! could not preserve previous inpaint variant: {error}")

            if not force and not cache_matches:
                variant_hit = self._load_inpaint_variant(
                    page, cache_key["fingerprint"], cache_key)
                if variant_hit is not None:
                    cached, variant_dir = variant_hit
                    shutil.copy2(variant_dir / "inpaint.png", out_path)
                    shutil.copy2(variant_dir / "mask.png", mask_path)
                    shutil.copy2(variant_dir / "provenance.json", provenance_path)
                    self._inpaint_times[page] = cached.get("infer_ms", 0)
                    self._render_dirty[page] = True
                    try:
                        os.utime(variant_dir, None)
                    except OSError:
                        pass
                    cache_matches = True

            if force or not out_path.exists() or not mask_path.exists() or not cache_matches:
                img = self.page_image(page)
                t0 = time.perf_counter()
                parity = ("android-production"
                          if engine == "android" and current_capture and conf == 0.6
                          else "experimental/non-parity")
                execution_provenance = {
                    "confidence_threshold": conf,
                    "parity": parity,
                    "source": ("cached-detection-artifacts" if current_capture
                               else "legacy-box-cache"),
                    "candidate_artifact_ids": execution["candidate_ids"],
                    "mask_capture_integrity": execution["mask_capture_integrity"],
                    "ocr_region_join": execution["ocr_join"],
                }
                if engine == "android":
                    import inpaint_android
                    if current_capture:
                        seg_masks = None
                        mask_cache = (det_data.get("mask_cache", {})
                                      .get("bubble-segmenter", {}))
                        segmenter_outputs = (det_data.get("models", {})
                                             .get("bubble-segmenter", {})
                                             .get("outputs", []))
                        segmenter_enabled = (
                            det_data.get("models", {}).get("bubble-segmenter", {})
                            .get("status") == "ready"
                            and self.settings.get("bubble_segmentation", True))
                    else:
                        # Legacy caches have no stable RLE references; keep the
                        # comparison path behind its explicit compatibility adapter.
                        seg_masks = self.bubble_masks(page)
                        mask_cache = None
                        segmenter_outputs = None
                        segmenter_enabled = False
                    paddle_det = self._inpaint_paddle_det()
                    needs_aot = bubble_leg == "aot" or free_leg == "aot"
                    aot = self._aot_inpainter(allow_dynamic=True) if needs_aot else None
                    cleaned, mask, region_routes, stats = inpaint_android.inpaint_page_android(
                        img, regions, raw_detections=planner_detections,
                        seg_masks=seg_masks, bubble_leg=bubble_leg,
                        free_leg=free_leg, paddle_det=paddle_det, aot=aot,
                        mask_cache=mask_cache,
                        segmenter_outputs=segmenter_outputs,
                        current_capture=current_capture,
                        segmenter_enabled=segmenter_enabled,
                        execution_confidence=conf,
                        opencv_method=variant_params["opencv_method"],
                        telea_radius=variant_params["telea_radius"],
                        erosion_radius=variant_params["erosion_radius"],
                        feather_px=variant_params["feather_px"],
                    )
                    provenance = {
                        "provenance_schema_version": 3,
                        "engine": "android",
                        "cache_key": cache_key,
                        "page_fingerprint": self._current_page_fingerprint(page),
                        "execution": execution_provenance,
                        "mask_source": ("A1-cached-page-space-rle"
                                        if current_capture
                                        else "legacy-schema-fallback"),
                        "leg_matrix": leg_matrix,
                        "variant": {"engine": engine,
                                    "leg_matrix": leg_matrix,
                                    "params": variant_params,
                                    "params_hash": variant_params_hash},
                        "stats": stats,
                        "regions": region_routes,
                    }
                else:
                    # Legacy engine remains available for comparison.
                    seg_masks = self.bubble_masks(page)
                    paddle_det = self._inpaint_paddle_det()
                    aot = self._aot_inpainter() if mode == "QUALITY" else None
                    import aot_inpaint
                    bubble_erosion = variant_params["erosion_radius"]
                    cleaned, mask, stats = aot_inpaint.inpaint_page_pipeline(
                        img, regions, raw_detections=legacy_raw_boxes,
                        seg_masks=seg_masks, mode=mode,
                        paddle_det=paddle_det, aot=aot,
                        bubble_erosion=bubble_erosion,
                    )
                    provenance = {
                        "provenance_schema_version": 3,
                        "engine": "legacy", "cache_key": cache_key,
                        "page_fingerprint": self._current_page_fingerprint(page),
                        "execution": execution_provenance,
                        "leg_matrix": leg_matrix,
                        "variant": {"engine": engine,
                                    "leg_matrix": leg_matrix,
                                    "params": variant_params,
                                    "params_hash": variant_params_hash},
                        "mode": mode,
                        "stats": stats, "regions": [],
                    }
                if engine == "android":
                    inpaint_provenance.save_region_crop_artifacts(
                        img, cleaned, mask, provenance["regions"],
                        self.studio_dir, page, cache_key["fingerprint"])
                inp_ms = round((time.perf_counter() - t0) * 1000, 1)
                self._inpaint_times[page] = inp_ms

                cleaned.save(out_path)
                mask_img = Image.fromarray((mask.astype(np.uint8) * 255), mode="L")
                mask_img.save(mask_path)
                provenance["infer_ms"] = inp_ms
                try:
                    pending_path = provenance_path.with_name(
                        provenance_path.name + ".pending")
                    pending_path.write_text(
                        json.dumps(provenance, indent=2, ensure_ascii=False),
                        encoding="utf-8")
                    pending_path.replace(provenance_path)
                except Exception as e:
                    try:
                        pending_path.unlink(missing_ok=True)
                    except Exception:
                        pass
                    raise RuntimeError(
                        f"failed to save inpaint provenance for {page}: {e}") from e
                try:
                    self._store_inpaint_variant(
                        page, cache_key["fingerprint"], out_path, mask_path,
                        provenance_path)
                    self._prune_inpaint_variant_cache(
                        page, cache_key["fingerprint"])
                except Exception as error:
                    self.log(f"!! could not save inpaint variant cache: {error}")
                self._render_dirty[page] = True
                if engine == "android":
                    self.log(f"inpaint[android {bubble_leg}+{free_leg}] {page}: "
                             f"{stats.get('mask_pixels', 0)} mask pixels -> "
                             f"{out_path.name} ({inp_ms}ms)")
                else:
                    self.log(f"inpaint[legacy {mode.lower()}] {page}: "
                             f"{stats.get('bubbleBoxes', 0)} bubbles, "
                             f"{stats.get('freeBoxes', 0)} free boxes -> "
                             f"{out_path.name} ({inp_ms}ms)")
            else:
                provenance = cached
                if "infer_ms" in cached:
                    self._inpaint_times[page] = cached["infer_ms"]
            return {"page": page, "path": str(out_path), "mask_path": str(mask_path),
                    "infer_ms": self._inpaint_times.get(page, 0),
                    "engine": engine, "leg_matrix": leg_matrix,
                    "cache_fingerprint": cache_key["fingerprint"],
                    "cache_hit": not (force or not out_path.exists()
                                      or not mask_path.exists()
                                      or not cache_matches),
                    "provenance": provenance.get("regions", []),
                    "provenance_path": str(provenance_path)}

    @_page_operation
    def inpainted_image(self, page: str) -> Image.Image:
        out_path = self.studio_dir / "inpaint" / (Path(page).stem + ".png")
        if not out_path.exists():
            self.inpaint_page(page)
        return Image.open(out_path).convert("RGB")

    @_page_operation
    def inpaint_provenance(self, page: str) -> dict:
        """Return the persisted inpaint provenance record, strictly read-only.

        Gate 2 F1: GET routes must never trigger stage work. A missing or
        stale record is reported as such (FileNotFoundError carries the
        reason); refreshing provenance belongs to the explicit processing
        routes (POST /api/inpaint, /api/process).
        """
        path = self.studio_dir / "inpaint" / (Path(page).stem + ".json")
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError) as exc:
            raise FileNotFoundError(f"inpaint provenance is unavailable for {page}") from exc
        if not inpaint_provenance.crop_artifacts_are_current(
                document, document.get("cache_key", {}).get("fingerprint", ""),
                self.studio_dir):
            raise FileNotFoundError(f"inpaint crop artifacts are stale for {page}")
        return document

    @_page_operation
    def inpaint_crop_path(self, page: str, region_id: str, kind: str,
                          representation: str = "debug") -> Path:
        """Resolve an image route only through the current page provenance map."""
        if kind not in ("input", "output", "mask"):
            raise ValueError(f"unsupported inpaint crop kind: {kind}")
        if representation not in ("debug", "exact"):
            raise ValueError(f"unsupported inpaint crop representation: {representation}")
        provenance = self.inpaint_provenance(page)
        matches = [record for record in provenance.get("regions", [])
                   if str(record.get("id", "")) == str(region_id)]
        if not matches:
            matches = [record for record in provenance.get("regions", [])
                       if str(record.get("artifact_id", "")) == str(region_id)]
        if len(matches) != 1:
            raise FileNotFoundError(region_id)
        entry = (matches[0].get("crops", {}).get(kind, {})
                 .get(representation, {}))
        relative_path = entry.get("path") if isinstance(entry, dict) else None
        if not relative_path:
            raise FileNotFoundError(region_id)
        candidate = (self.chapter / Path(relative_path)).resolve()
        crop_root = (self.studio_dir / "inpaint_crops").resolve()
        try:
            candidate.relative_to(crop_root)
        except ValueError as exc:
            raise FileNotFoundError(region_id) from exc
        if not candidate.is_file():
            raise FileNotFoundError(candidate)
        return candidate

    @_page_operation
    def inpaint_mask_image(self, page: str) -> Image.Image:
        mask_path = self.studio_dir / "inpaint_mask" / (Path(page).stem + ".png")
        if mask_path.exists():
            return Image.open(mask_path).convert("L")
        w, h = self.page_dims(page)
        return Image.new("L", (w, h), 0)


    @_page_operation
    def segmentation_overlay_image(self, page: str) -> Image.Image:
        w, h = self.page_dims(page)
        if not self.settings.get("bubble_segmentation", True):
            return Image.new("RGBA", (w, h), (0, 0, 0, 0))
        capture = self._cache.get("detections", {}).get(page) or {}
        if capture.get("capture_version") == 1:
            overlay_np = np.zeros((h, w, 4), dtype=np.uint8)
            colors = [
                (56, 189, 248, 110), (168, 85, 247, 110),
                (52, 211, 153, 110), (251, 146, 60, 110),
                (244, 114, 182, 110),
            ]
            border_colors = [
                (56, 189, 248, 240), (168, 85, 247, 240),
                (52, 211, 153, 240), (251, 146, 60, 240),
                (244, 114, 182, 240),
            ]
            mask_cache = (capture.get("mask_cache", {})
                          .get("bubble-segmenter", {}))
            outputs = (capture.get("models", {})
                       .get("bubble-segmenter", {}).get("outputs", []))
            for index, output in enumerate(outputs):
                entry = mask_cache.get(output.get("mask_ref"), {})
                for component_runs in entry.get("components", {}).values():
                    spans = list(iter_rle_row_spans(component_runs, w, h))
                    border = border_colors[index % len(border_colors)]
                    fill = colors[index % len(colors)]
                    for y, x1, x2 in spans:
                        left, right = max(0, x1 - 1), min(w, x2 + 1)
                        if y > 0:
                            overlay_np[y - 1, left:right] = border
                        if y + 1 < h:
                            overlay_np[y + 1, left:right] = border
                        if x1 > 0:
                            overlay_np[y, x1 - 1] = border
                        if x2 < w:
                            overlay_np[y, x2] = border
                    for y, x1, x2 in spans:
                        overlay_np[y, x1:x2] = fill
            return Image.fromarray(overlay_np, "RGBA")

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

    @_page_operation
    def segmentation_overlay_path(self, page: str) -> Path | None:
        assert self.studio_dir is not None
        sdir = self.studio_dir / "seg_overlay"
        out = sdir / (Path(page).stem + ".png")
        if out.exists():
            return out
        return None


    @_page_operation
    def segmentation_image(self, page: str) -> Image.Image:
        img = self.page_image(page).convert("RGBA")
        overlay_img = self.segmentation_overlay_image(page)
        result = Image.alpha_composite(img, overlay_img)
        return result.convert("RGB")

    def _render_cache_inputs(self, page: str, ocr: dict,
                             inpaint_fingerprint: str) -> dict:
        page_info = self._page_fingerprints[page]
        raw = self._cache.get("detections", {}).get(page) or {}
        decision_fingerprint = (raw.get("decisions") or {}).get("fingerprint")
        if not decision_fingerprint:
            projection = self._regions_at_conf(
                page, float(self.settings.get("conf", DEFAULT_SETTINGS["conf"])),
                persist=False)
            decision_fingerprint = projection["decision_fingerprint"]
        font = _load_font(14, self.settings)
        font_path = getattr(font, "path", None)
        font_state = (_asset_identity(Path(str(font_path)))
                      if font_path and Path(str(font_path)).is_file()
                      else {"status": "builtin", "name": str(font_path or "default")})
        return {
            "algorithm": RENDER_ALGORITHM,
            "page_fingerprint": page_info["fingerprint"],
            "page_wh": [page_info["width"], page_info["height"]],
            "detection_decision_fingerprint": decision_fingerprint,
            "ocr_fingerprint": ocr.get("ocr_fingerprint") or _stable_fingerprint(
                {"regions": ocr.get("regions", []), "engine": ocr.get("engine")}),
            "inpaint_fingerprint": inpaint_fingerprint,
            "render_regions": [{key: region.get(key) for key in
                                ("id", "artifact_id", "box", "label", "score",
                                 "segmenter_assignment", "panel_assignment")}
                               for region in ocr.get("regions", [])],
            "translations": {key: self.translations(page).get(key, "")
                             for key in sorted(self.translations(page))},
            "settings": {
                "confidence": float(self.settings.get(
                    "conf", DEFAULT_SETTINGS["conf"])),
                "bubble_segmentation": bool(
                    self.settings.get("bubble_segmentation", True)),
                "font_scale": float(self.settings.get("font_scale", 1.0)),
                "font_path": str(self.settings.get("font_path", "")),
                "font_asset": font_state,
                "pillow_version": getattr(Image, "__version__", "unknown"),
            },
        }

    @_page_operation
    def render_page(self, page: str, force: bool = False) -> dict:
        with self.lock:
            ocr = self._cache["ocr"].get(page)
            if not ocr:
                raise ValueError(f"no OCR data for {page} — run OCR first")
            out_dir = self.studio_dir / "render"
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / (Path(page).stem + ".png")
            cache_path = out_dir / (Path(page).stem + ".json")
            inpaint = self.inpaint_page(page)
            # inpaint_page validates upstream detection/OCR fingerprints and
            # may refresh the OCR cache; rendering must use that same lineage.
            ocr = self._cache["ocr"].get(page)
            if not ocr:
                raise ValueError(f"no current OCR data for {page} — run OCR first")
            cache_inputs = self._render_cache_inputs(
                page, ocr, inpaint["cache_fingerprint"])
            cache_key = {**cache_inputs,
                         "fingerprint": _stable_fingerprint(cache_inputs)}
            try:
                cached = json.loads(cache_path.read_text(encoding="utf-8"))
            except (OSError, ValueError, TypeError):
                cached = {}
            if (not force and out_path.exists()
                    and cached.get("cache_key") == cache_key):
                self._render_dirty[page] = False
                self._render_assignments[page] = cached.get("mask_assignments", [])
                self._render_times[page] = cached.get("render_ms", 0)
                render_ms = self._render_times[page]
                assignment_records = self._render_assignments[page]
                cache_hit = True
            else:
                t0 = time.perf_counter()
                # Start from clean inpainted background
                img = self.inpainted_image(page).copy()
                img.studio_page = page      # for bubble lookups in render_regions
                tr = self.translations(page)
                render_assignments = []
                n = render_regions(img, ocr["regions"], tr,
                                   self.settings, self, page=page,
                                   assignment_records=render_assignments)
                img.save(out_path)
                self._render_assignments[page] = render_assignments
                self._render_dirty[page] = False
                render_ms = round((time.perf_counter() - t0) * 1000, 1)
                self._render_times[page] = render_ms
                self.log(f"render {page}: {n}/{len(ocr['regions'])} regions "
                         f"translated -> {out_path.name} ({render_ms}ms)")
                assignment_records = render_assignments
                cached = {"page_fingerprint": self._current_page_fingerprint(page),
                          "cache_key": cache_key,
                          "cache_fingerprint": cache_key["fingerprint"],
                          "render_ms": render_ms,
                          "mask_assignments": render_assignments}
                cache_path.write_text(json.dumps(cached, indent=2, ensure_ascii=False),
                                      encoding="utf-8")
                cache_hit = False
            return {"page": page, "path": str(out_path),
                    "render_ms": render_ms,
                    "cache_fingerprint": cache_key["fingerprint"],
                    "cache_hit": cache_hit,
                    "mask_assignments": assignment_records}

    @_page_operation
    def overlay_image(self, page: str, conf: float | None = None) -> Image.Image:
        conf = self.settings["conf"] if conf is None else conf
        regions = self._regions_at_conf(page, conf, persist=False)["regions"]
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

    @_page_operation
    def region_crop(self, page: str, region_id: str, scale: int = 2) -> Image.Image:
        key = (page, region_id)
        if key in self._crop_cache:
            return self._crop_cache[key]
        regions = self._cache["ocr"].get(page, {}).get("regions", [])
        r = next((x for x in regions if x["id"] == region_id), None)
        if r is None:
            raise FileNotFoundError(region_id)
        engine_name = self._cache["ocr"].get(page, {}).get("engine", "mangaocr")
        img = self.page_image(page).crop(tuple(recognition_input_box(r, engine_name)))
        if scale > 1 and min(img.size) < 300:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        self._crop_cache[key] = img
        return img

    # ---------------------------------------------------------------- process
    def process_page(self, page: str, conf: float | None = None,
                     translate: bool = True, force: bool = False) -> dict:
        with self.lock:
            with self._page_operation_scope(page):
                t0 = time.perf_counter()
                det = self.detect_page(page, conf, force=force)
                ocr = self.ocr_page(page, conf, force=force)
                inpaint = self.inpaint_page(page, force=force)
                if translate:
                    try:
                        self.translate_page(page)
                    except Exception as e:
                        self.log(f"!! translate skipped: {e}")
                rend = self.render_page(page, force=force)
                return {"page": page, "detections": det, "ocr": {
                    "regions": ocr["regions"], "runs": ocr["runs"], "ms": ocr["ms"]},
                    "inpaint": inpaint["path"],
                    "render": rend["path"],
                    "translations": self.translations(page),
                    "pruned": ocr.get("pruned", []),
                    "total_ms": round((time.perf_counter() - t0) * 1000, 1)}

    @_page_operation
    def page_data(self, page: str) -> dict:
        conf = float(self.settings.get("conf", DEFAULT_SETTINGS["conf"]))
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
            regions = self._regions_at_conf(page, conf, persist=False).get("regions", [])
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


def recognition_input_box(region: dict, engine_name: str,
                          page_wh: list[int] | None = None) -> list[int]:
    """Paddle needs 12px context; MangaOCR consumes Android's tight ROI."""
    if engine_name == "paddle":
        if region.get("ocr_box") is not None:
            return region["ocr_box"]
        if page_wh is not None:
            return clamp_pad(Box(*region["box"]), page_wh).as_list()
    return region["box"]


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
            out.append({"label": int(lab),
                        "score": math.floor(s * 10000.0 + 0.5) / 10000.0,
                        "raw_score": s,
                        "box": [int(v) for v in box[:4]]})
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
            test_rect = [rx1, ry1, rx1 + int(block_w) + 1,
                         ry1 + int(total_h) + 1]
            if hasattr(comp_mask, "contains_rect"):
                inside = comp_mask.contains_rect(test_rect)
            else:
                sub = comp_mask[max(0, ry1):ry1 + int(total_h) + 1,
                                max(0, rx1):rx1 + int(block_w) + 1]
                inside = bool(sub.size and sub.all())
            if inside:
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
    wy1, wx1 = max(0, ly1), max(0, lx1)
    if hasattr(mask, "rasterize_crop"):
        h, w = mask.height, mask.width
    else:
        h, w = mask.labels.shape
    wy2, wx2 = min(h, ly1 + lh_), min(w, lx1 + lw_)
    if wy2 > wy1 and wx2 > wx1:
        if hasattr(mask, "rasterize_crop"):
            comp_pixels = mask.rasterize_crop([wx1, wy1, wx2, wy2])
        else:
            comp_pixels = mask.labels[wy1:wy2, wx1:wx2] == comp
        comp_crop = Image.fromarray(comp_pixels.astype(np.uint8) * 255, "L")
        full = Image.new("L", (lw_, lh_), 0)
        full.paste(comp_crop, (wx1 - lx1, wy1 - ly1))
        layer.putalpha(ImageChops.multiply(layer.getchannel("A"), full))
        img.paste(layer, (lx1, ly1), layer)


def render_regions(img: Image.Image, regions: list[dict],
                   translations: dict[str, str], settings: dict,
                   pipe: "Pipeline | None" = None,
                   page: str | None = None,
                   assignment_records: list[dict] | None = None) -> int:
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
    assignment: dict[str, object] = {}
    execution_bubble_records = None
    if pipe is not None:
        p = page or getattr(img, "studio_page", None)
        if p:
            det_data = pipe._cache.get("detections", {}).get(p) or {}
            if det_data.get("capture_version") == 1:
                conf = float(settings.get("conf", DEFAULT_SETTINGS["conf"]))
                projected = pipe._regions_at_conf(p, conf, persist=False).get("regions", [])
                projected_by_id = {r.get("artifact_id"): r for r in projected
                                   if r.get("artifact_id")}
                raw = pipe._cache["detections"][p]
                execution_bubble_records = [
                    record for record in raw.get("models", {})
                    .get("text-detector", {}).get("derived_outputs", [])
                    if int(record.get("attrs", {}).get("label", -1)) == 0
                ]
                mask_cache = (raw.get("mask_cache", {})
                              .get("bubble-segmenter", {}))
                seg_model = raw.get("models", {}).get("bubble-segmenter", {})
                outputs = seg_model.get("outputs", [])
                missing_mask_refs = [
                    item.get("mask_ref") or item.get("id")
                    for item in outputs if not mask_cache.get(item.get("mask_ref"))
                ]
                resolved_views = {}
                eligible_todo = []
                for region in todo:
                    artifact_id = region.get("artifact_id")
                    current_region = projected_by_id.get(artifact_id)
                    source_assignment = ((current_region or {}).get(
                         "segmenter_assignment"))
                    audit = {"region_id": region.get("id"),
                             "artifact_id": artifact_id,
                             "mask_ref": (source_assignment or {}).get("mask_ref"),
                             "segmenter_component_id": (source_assignment or {}).get(
                                 "mask_component_id"),
                             "status": "no-assignment"}
                    if current_region is None:
                        audit["status"] = "not-in-current-execution-projection"
                        if assignment_records is not None:
                            assignment_records.append(audit)
                        continue
                    eligible_todo.append(region)
                    if not settings.get("bubble_segmentation", True):
                        audit["status"] = "segmentation-disabled"
                    elif source_assignment:
                        key = (source_assignment.get("mask_ref"),
                               source_assignment.get("mask_component_id"))
                        if key not in resolved_views:
                            resolved_views[key] = resolve_mask_component(
                                key[0], key[1], mask_cache, outputs)
                        view, error = resolved_views[key]
                        if view is not None and (view.width, view.height) != img.size:
                            view, error = None, "page-dimension-mismatch"
                        if view is not None:
                            assignment[region["id"]] = view
                            audit.update({"status": "resolved",
                                          "source": dict(view.source)})
                        else:
                            audit["status"] = error or "missing-reference"
                            audit["degraded"] = True
                            pipe.log(f"!! render mask reference degraded for {p}/"
                                     f"{region.get('id')}: {audit['status']}")
                    elif missing_mask_refs:
                        audit.update({"status": "capture-missing-references",
                                      "degraded": True,
                                      "missing_mask_refs": missing_mask_refs})
                    if assignment_records is not None:
                        assignment_records.append(audit)
                todo = eligible_todo
            else:
                if settings.get("bubble_segmentation", True):
                    segmenter = pipe._bubble_segmenter()
                    if segmenter is not None:
                        try:
                            seg_masks = segmenter.segment(img)
                        except Exception as e:
                            log(f"!! bubble segmentation lookup error: {e}")
                            seg_masks = None
    if seg_masks:
        for r in todo:
            a = _seg.assign_region_component(seg_masks, Box(*r["box"]).as_list())
            if a is not None:
                assignment[r["id"]] = a

    p = page or getattr(img, "studio_page", None)
    bubbles: list[Box] = []
    if pipe is not None and p:
        det_data = pipe._cache["detections"].get(p) or {}
        if execution_bubble_records is not None:
            for record in execution_bubble_records:
                geometry = record.get("geometry", {})
                try:
                    bubbles.append(Box(*(int(geometry[key]) for key in
                                         ("x1", "y1", "x2", "y2"))))
                except (KeyError, TypeError, ValueError, OverflowError):
                    continue
        else:
            raw_b = det_data.get("boxes", [])
            conf = float(settings.get("conf", DEFAULT_SETTINGS["conf"]))
            bubbles = [Box(int(b[2]), int(b[3]), int(b[4]), int(b[5]))
                       for b in raw_b if int(b[0]) == 0 and len(b) >= 6
                       and float(b[1]) >= conf]

    comp_counts: dict[tuple, int] = {}
    for r in todo:
        a = assignment.get(r["id"])
        if a is not None:
            key = a.key if hasattr(a, "key") else a
            comp_counts[key] = comp_counts.get(key, 0) + 1

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
            if hasattr(a, "component_bounds"):
                mask, comp = a, a.component_id
                cb = mask.component_bounds
            else:
                mask, comp = a
                c_obj = next((c for c in mask.components if c.id == comp), None)
                if c_obj is not None:
                    cb = c_obj.bounds
                else:
                    cys, cxs = np.where(mask.labels == comp)
                    cb = (int(cxs.min()), int(cys.min()),
                          int(cxs.max()) + 1, int(cys.max()) + 1)

            assignment_key = a.key if hasattr(a, "key") else a
            if comp_counts.get(assignment_key, 0) == 1:
                # Expand symmetrically from the anchor center within the bubble mask bounds
                half_w = max(rbox.w / 2.0, min(anchor_cx - cb[0], cb[2] - anchor_cx))
                half_h = max(rbox.h / 2.0, min(anchor_cy - cb[1], cb[3] - anchor_cy))
                fit_box = Box(int(anchor_cx - half_w), int(anchor_cy - half_h),
                              int(anchor_cx + half_w), int(anchor_cy + half_h))
            else:
                gx1, gy1 = max(0, rbox.x1), max(0, rbox.y1)
                if hasattr(mask, "rasterize_crop"):
                    inter = mask.rasterize_crop(
                        [gx1, gy1, min(mask.width, rbox.x2),
                         min(mask.height, rbox.y2)])
                else:
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
            component_mask = (mask if hasattr(mask, "contains_rect")
                              else mask.component_mask(comp))
            font, lines, line_h = _fit_text_in_mask(
                draw, text, fit_box, component_mask, settings)
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


DEFAULT_SETTINGS = {
    "conf": 0.6,
    "max_batch": 8,
    "mangaocr_serial_timing": False,
    "target_lang": "English",
    "endpoint": "http://127.0.0.1:1234/v1",
    "model": "local-model",
    "font_path": "",
    "font_scale": 1.0,
    "erase": "auto",
    "ocr_engine": "mangaocr",        # "mangaocr" | "paddle"
    "inpaint_mode": "quality",       # "quality" (AOT-512) | "fast" (classical)
    "inpaint_engine": "legacy",      # "legacy" | "android"
    "inpaint_bubble_leg": "android-fill",  # android-fill | opencv | aot | pushpull
    "inpaint_free_leg": "opencv",    # opencv | aot | pushpull
    "inpaint_opencv_method": "telea",  # telea | ns (experimental)
    "inpaint_telea_radius": 3,        # Android OpenCV Telea default (px)
    "inpaint_feather_px": None,       # None keeps path-specific Android defaults
    "translate_backend": "google",   # "google" | "lm-studio"
    "bubble_segmentation": True,     # manga109 YOLO11-seg (on when model loads)
    "bubble_mask_erosion": 5,        # px erosion for bubble seg mask edge reduction
}

PIPELINE = Pipeline()
