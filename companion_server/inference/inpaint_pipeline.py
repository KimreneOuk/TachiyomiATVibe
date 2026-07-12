"""Honest inpainting façade.

This module replaces the old ``inpainter is not None`` gate that silently
copied the original image when AOT neural was unavailable. The new contract:

  - **Classical cleaning is the intended tier-1 algorithm** for bubbles
    (label 0), parented text (label 1), and flat/small free-text (label 2).
    It mirrors Android FAST mode and needs no ONNX. It always runs.

  - **Neural AOT is an explicit QUALITY tier** for *textured* free-text
    (label 2) regions only. When QUALITY is requested but neural cannot run
    (onnxruntime missing, model missing, per-box runtime error), the failure
    is **logged and reported** — the affected boxes keep their original
    pixels and the page is marked PARTIAL. There is no classical substitution
    masquerading as success.

Mask boxes follow the Android ``InpaintMaskBox`` convention:
  label 0 → parent bubble; labels 1/2 → text erase boxes.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np
from PIL import Image

from inpaint import bubble_mask as bm
from inpaint import smart_cleaner as cleaner
from logging_config import log_failure

MASK_PAD = 8
SMALL_BOX_AREA_DIVISOR = 200

# Paddle det refinement of free-text boxes (port of AOTInpainting.refineFreeTextBoxes).
# Each detector free-text box is cropped with context, Paddle DB finds tight
# text-line boxes at the lowered inpaint thresholds, and they are back-projected
# to page coords with a small guard pad. When Paddle finds nothing the detector
# box is kept (conservative fallback so the region is still erased).
PADDLE_CROP_PAD = 12
PADDLE_THRESH = 0.18
PADDLE_BOX_THRESH = 0.34
FREE_TEXT_REFINE_PAD = 4


@dataclass
class CleanResult:
    """Outcome of cleaning one page."""

    image: Image.Image
    engine: str  # classical-fast | classical | classical+neural | partial:neural-failed | skipped
    neural_available: bool
    failures: list[dict[str, Any]] = field(default_factory=list)

    @property
    def status(self) -> str:
        if self.engine == "skipped":
            return "SKIPPED"
        if self.engine.startswith("partial"):
            return "PARTIAL"
        return "READY"


class Cleaner:
    """Classical tier-1 cleaner with an optional neural QUALITY tier."""

    def __init__(self, neural: Any | None = None, paddle_det: Any | None = None) -> None:
        # ``neural`` is an AotInpainter or None. None means QUALITY neural is
        # unavailable — reported honestly, never substituted.
        self.neural = neural
        # ``paddle_det`` refines free-text erase boxes into tight text-line boxes
        # (Android AOTInpainting.refineFreeTextBoxes). When None, free-text
        # boxes use the coarse detector box (the prior behavior).
        self.paddle_det = paddle_det

    @property
    def neural_available(self) -> bool:
        return self.neural is not None

    def clean(
        self,
        image: Image.Image,
        mask_boxes: list[dict[str, Any]] | None,
        blocks: list[dict[str, Any]],
        mode: str = "QUALITY",
        *,
        chapter_id: str | None = None,
        page: int | None = None,
    ) -> CleanResult:
        result = image.convert("RGB").copy()
        failures: list[dict[str, Any]] = []

        bubbles, parented_groups, unparented_text, free_boxes = _split_mask(mask_boxes, blocks)
        if not (bubbles or parented_groups or unparented_text or free_boxes):
            return CleanResult(image=result, engine="skipped", neural_available=self.neural_available)

        # Tier 1: parented bubble groups → boundary-aware containment fill
        # (Android SmartBubbleTextCleaner.fillContained). One fill_contained
        # call per bubble, passed all its text boxes as the erase set. The
        # result is verified to have actually changed the erase region — a
        # no-op clean is surfaced as PARTIAL, never reported as success.
        for bubble_key, text_list in parented_groups.items():
            result = self._clean_bubble_group(
                result, list(bubble_key[:4]), text_list,
                "inpaint/classical/bubble",
                chapter_id=chapter_id, page=page, failures=failures,
            )

        # Standalone bubble boxes (label 0 with no assigned text) → erase the
        # whole bubble region.
        for bubble in bubbles:
            result = self._clean_bubble_group(
                result, list(bubble), [list(bubble)],
                "inpaint/classical/bubble",
                chapter_id=chapter_id, page=page, failures=failures,
            )

        # Tier 2: unparented text (label 1, no bubble) → Telea erase.
        for text_box in unparented_text:
            result = self._clean_one_box(
                result, text_box, "inpaint/classical/text",
                chapter_id=chapter_id, page=page, failures=failures,
            )

        # Tier 3: free-text (label 2). Refine each detector box into tight
        # Paddle DB text-line boxes (Android AOTInpainting.refineFreeTextBoxes)
        # before classifying flat/small/neural — erasing the coarse RT-DETR box
        # would wipe surrounding art; the refined line boxes hug the text.
        refined_free = self._refine_free_text_boxes(
            result, free_boxes, chapter_id=chapter_id, page=page, failures=failures,
        )

        # Split refined boxes into flat/small (classical) vs textured (neural).
        w, h = result.size
        page_area = w * h
        small_threshold = page_area // SMALL_BOX_AREA_DIVISOR
        flat_boxes: list[list[int]] = []
        neural_boxes: list[list[int]] = []
        for box in refined_free:
            if cleaner.is_flat_background_region(result, box):
                flat_boxes.append(box)
            elif (box[2] - box[0]) * (box[3] - box[1]) < small_threshold:
                flat_boxes.append(box)
            else:
                neural_boxes.append(box)

        if flat_boxes:
            for box in flat_boxes:
                result = self._clean_one_box(
                    result, box, "inpaint/classical/free-text",
                    chapter_id=chapter_id, page=page, failures=failures,
                )

        neural_ran = False
        if neural_boxes:
            if mode.upper() == "FAST":
                # FAST = classical-only by explicit user choice (Android FAST).
                for box in neural_boxes:
                    result = self._clean_one_box(
                        result, box, "inpaint/classical/free-text",
                        chapter_id=chapter_id, page=page, failures=failures,
                    )
            elif not self.neural_available:
                # QUALITY requested but neural cannot run. Report each textured
                # free-text box as FAILED — do NOT silently fall back to classical.
                reason = "neural inpaint unavailable (onnxruntime/model not loaded)"
                for box in neural_boxes:
                    failures.append(log_failure(
                        "inpaint/neural", reason,
                        chapter_id=chapter_id, page=page,
                        box=dict(zip(("x1", "y1", "x2", "y2"), box)),
                    ))
            else:
                for box in neural_boxes:
                    try:
                        result = self.neural.inpaint_free_text_box(result, box)
                        neural_ran = True
                    except Exception as exc:
                        failures.append(log_failure(
                            "inpaint/neural", f"{type(exc).__name__}: {exc}",
                            chapter_id=chapter_id, page=page,
                            box=dict(zip(("x1", "y1", "x2", "y2"), box)), exc=exc,
                        ))

        engine = _derive_engine(mode, self.neural_available, neural_ran, neural_boxes, failures)
        return CleanResult(image=result, engine=engine, neural_available=self.neural_available, failures=failures)

    def _clean_bubble_group(
        self, image: Image.Image, bubble: list[int], text_boxes: list[list[int]],
        stage: str, *, chapter_id: str | None, page: int | None, failures: list[dict[str, Any]],
    ) -> Image.Image:
        """Boundary-aware containment fill for one bubble + its text boxes.

        Verifies the erase region actually changed; a no-op is reported as a
        failure rather than success.
        """
        # Snapshot the union of erase regions before cleaning.
        erase_union = _union_box(bubble, text_boxes)
        before = np.array(image.crop(_clamped(image, erase_union)))
        try:
            after_img = cleaner.fill_contained(
                image.copy(), *bubble, list(bubble), [list(t) for t in text_boxes],
            )
        except Exception as exc:
            failures.append(log_failure(stage, f"{type(exc).__name__}: {exc}",
                                        chapter_id=chapter_id, page=page,
                                        box=dict(zip(("x1", "y1", "x2", "y2"), bubble)), exc=exc))
            return image
        after = np.array(after_img.crop(_clamped(image, erase_union)))
        if _region_unchanged(before, after):
            failures.append(log_failure(
                stage, "classical clean produced no pixel change in the erase region",
                chapter_id=chapter_id, page=page,
                box=dict(zip(("x1", "y1", "x2", "y2"), bubble)),
            ))
            return image
        return after_img

    def _clean_one_box(
        self, image: Image.Image, box: list[int], stage: str, *,
        chapter_id: str | None, page: int | None, failures: list[dict[str, Any]],
    ) -> Image.Image:
        """Erase one box via Telea reconstruction (fill_solid_boxes) and verify.

        A clean that throws is logged. A clean that completes but leaves the
        erased region byte-identical to the input is ALSO logged — that is a
        no-op clean (defect or degenerate input), reported as PARTIAL rather
        than reported as success.
        """
        before = np.array(image.crop(_clamped(image, box)))
        try:
            after_img = cleaner.fill_solid_boxes(image.copy(), [box], MASK_PAD)
        except Exception as exc:
            failures.append(log_failure(stage, f"{type(exc).__name__}: {exc}",
                                        chapter_id=chapter_id, page=page,
                                        box=dict(zip(("x1", "y1", "x2", "y2"), box)), exc=exc))
            return image
        after = np.array(after_img.crop(_clamped(image, box)))
        if _region_unchanged(before, after):
            failures.append(log_failure(
                stage, "classical clean produced no pixel change in the erase region",
                chapter_id=chapter_id, page=page,
                box=dict(zip(("x1", "y1", "x2", "y2"), box)),
            ))
            return image
        return after_img

    def _refine_free_text_boxes(
        self, image: Image.Image, detector_boxes: list[list[int]], *,
        chapter_id: str | None, page: int | None, failures: list[dict[str, Any]],
    ) -> list[list[int]]:
        """Refine free-text detector boxes into tight Paddle DB text-line boxes.

        Port of AOTInpainting.refineFreeTextBoxes: crop each detector box with
        ``PADDLE_CROP_PAD`` context, run Paddle det at the lowered inpaint
        thresholds, back-project line boxes to page coords with a
        ``FREE_TEXT_REFINE_PAD`` guard pad. A detector box for which Paddle finds
        nothing is kept verbatim (conservative fallback so the region is still
        erased). When paddle_det is unavailable the detector boxes are returned
        unchanged (the prior coarse behavior) — no failure is logged for that,
        it is the documented degraded mode.
        """
        if not detector_boxes:
            return []
        if self.paddle_det is None:
            return [list(b) for b in detector_boxes]

        w, h = image.size
        refined: list[list[int]] = []
        for det in detector_boxes:
            cx1 = max(0, det[0] - PADDLE_CROP_PAD)
            cy1 = max(0, det[1] - PADDLE_CROP_PAD)
            cx2 = min(w, det[2] + PADDLE_CROP_PAD)
            cy2 = min(h, det[3] + PADDLE_CROP_PAD)
            if cx2 <= cx1 or cy2 <= cy1:
                refined.append(list(det))
                continue

            crop = image.crop((cx1, cy1, cx2, cy2))
            try:
                lines = self.paddle_det.detect_lines(
                    crop, thresh=PADDLE_THRESH, box_thresh=PADDLE_BOX_THRESH,
                )
            except Exception as exc:
                # Paddle det failed on this crop — log and fall back to the
                # detector box (never abort the whole refine).
                failures.append(log_failure(
                    "inpaint/paddle-det", f"{type(exc).__name__}: {exc}",
                    chapter_id=chapter_id, page=page,
                    box=dict(zip(("x1", "y1", "x2", "y2"), det)), exc=exc,
                ))
                refined.append(list(det))
                continue

            if not lines:
                refined.append(list(det))
                continue

            added = 0
            for line in lines:
                px1 = max(0, int(round(cx1 + line["x1"])) - FREE_TEXT_REFINE_PAD)
                py1 = max(0, int(round(cy1 + line["y1"])) - FREE_TEXT_REFINE_PAD)
                px2 = min(w, int(round(cx1 + line["x2"])) + FREE_TEXT_REFINE_PAD)
                py2 = min(h, int(round(cy1 + line["y2"])) + FREE_TEXT_REFINE_PAD)
                if px2 > px1 and py2 > py1:
                    refined.append([px1, py1, px2, py2])
                    added += 1
            if added == 0:
                # Lines returned but none survived back-projection clamping.
                refined.append(list(det))
        return refined


def _union_box(bubble: list[int], text_boxes: list[list[int]]) -> list[int]:
    """Bounding box over a bubble and its text boxes (for before/after compare)."""
    boxes = [bubble] + list(text_boxes)
    return [
        min(int(b[0]) for b in boxes),
        min(int(b[1]) for b in boxes),
        max(int(b[2]) for b in boxes),
        max(int(b[3]) for b in boxes),
    ]


def _clamped(image: Image.Image, box: list[int]) -> tuple[int, int, int, int]:
    w, h = image.size
    x1 = max(0, min(int(box[0]), w))
    y1 = max(0, min(int(box[1]), h))
    x2 = max(0, min(int(box[2]), w))
    y2 = max(0, min(int(box[3]), h))
    if x2 <= x1:
        x2 = x1 + 1
    if y2 <= y1:
        y2 = y1 + 1
    return (x1, y1, x2, y2)


def _region_unchanged(before: np.ndarray, after: np.ndarray, tol: int = 1) -> bool:
    if before.shape != after.shape:
        return False
    return bool(np.abs(before.astype(np.int16) - after.astype(np.int16)).max() <= tol)


def _split_mask(mask_boxes, blocks):
    """Split the durable Android mask into classical/neural buckets.

    Returns (bubbles, parented_groups, unparented_text, free_boxes):
      - bubbles: standalone label-0 boxes (whole-bubble fill)
      - parented_groups: {bubble_key: [text_boxes]}
      - unparented_text: label-1 boxes with no parent bubble
      - free_boxes: label-2 boxes (candidate for neural)
    """
    bubbles: list[list[int]] = []
    parented_groups: dict[tuple[int, int, int, int], list[list[int]]] = {}
    unparented_text: list[list[int]] = []
    free_boxes: list[list[int]] = []

    if mask_boxes is not None:
        mask_bubbles: list[list[int]] = []
        mask_text: list[tuple[list[int], int]] = []
        for mask in mask_boxes:
            box = [int(mask["x1"]), int(mask["y1"]), int(mask["x2"]), int(mask["y2"])]
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            label = int(mask.get("label", 1))
            if label == 0:
                mask_bubbles.append(box)
            else:
                mask_text.append((box, label))

        assigned_text: set[int] = set()
        for box, label in mask_text:
            parent = _find_parent_bubble(box, mask_bubbles)
            if parent is not None:
                key = tuple(parent)
                parented_groups.setdefault(key, []).append(box)
                assigned_text.add(id(box))
            elif label == 2:
                free_boxes.append(box)
            else:
                unparented_text.append(box)

        # Bubbles that had no text assigned still get whole-bubble classical fill.
        parented_keys = set(parented_groups.keys())
        for bubble in mask_bubbles:
            if tuple(bubble) not in parented_keys:
                bubbles.append(bubble)
    else:
        # Fallback shape when only blocks are available (older callers).
        for block in blocks:
            bbox = block.get("bbox", {})
            box = [int(bbox.get("x1", 0)), int(bbox.get("y1", 0)), int(bbox.get("x2", 0)), int(bbox.get("y2", 0))]
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            pw = block.get("parentW", 0)
            ph = block.get("parentH", 0)
            if pw > 0 and ph > 0:
                parent = block.get("parentBbox", {})
                key = (int(parent.get("x1", 0)), int(parent.get("y1", 0)), int(parent.get("x2", 0)), int(parent.get("y2", 0)))
                parented_groups.setdefault(key, []).append(box)
            elif block.get("label") == 2:
                free_boxes.append(box)
            else:
                unparented_text.append(box)

    return bubbles, parented_groups, unparented_text, free_boxes


def _find_parent_bubble(text_box: list[int], bubble_boxes: list[list[int]]) -> list[int] | None:
    cx = (text_box[0] + text_box[2]) / 2.0
    cy = (text_box[1] + text_box[3]) / 2.0
    best: list[int] | None = None
    best_area = float("inf")
    for bubble in bubble_boxes:
        if bubble[0] <= cx <= bubble[2] and bubble[1] <= cy <= bubble[3]:
            area = (bubble[2] - bubble[0]) * (bubble[3] - bubble[1])
            if area < best_area:
                best = bubble
                best_area = area
    return best


def _derive_engine(mode: str, neural_available: bool, neural_ran: bool,
                   neural_boxes: list[list[int]], failures: list[dict[str, Any]]) -> str:
    if mode.upper() == "FAST":
        return "classical-fast"
    if neural_boxes and not neural_available:
        return "partial:neural-unavailable"
    if failures:
        return "partial:neural-failed"
    if neural_ran:
        return "classical+neural"
    return "classical"
