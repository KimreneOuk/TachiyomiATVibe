"""AOT inpainting engine — orchestrates the full tiered pipeline.

Port of AOTInpainting.kt. Routes detection boxes through:
  1. Parented bubbles → SmartBubbleTextCleaner.fill_contained
  2. Unparented text → SmartBubbleTextCleaner.fill_contained (no bubble)
  3. Free text (label 2, no parent) → neural AOT per-box + classical fallback

Each free-text box gets its own neural crop for tight context, with
AotOutputGuard detecting gray-output failures and falling back to classical.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image

try:
    import onnxruntime as ort
except Exception:
    ort = None

from inpaint import bubble_mask as bm
from inpaint import smart_cleaner as cleaner
from inpaint.aot_guard import is_suspicious_gray_fill

MAX_INFERENCE_DIM = 512
FEATHER_RAMP_PX = 12
MASK_PAD = 8
FREE_TEXT_REFINE_PAD = 4


class NeuralOutputRejected(RuntimeError):
    """Raised when the AOT model produces a suspicious (e.g. uniform-gray) output.

    This is a detected model failure, not a recoverable condition. The caller
    (Cleaner) logs it and reports the box as PARTIAL — it must NOT silently
    substitute classical output and pretend neural succeeded.
    """


def _find_parent_bubble_list(text_box: list[int], bubble_boxes: list[list[int]]) -> list[int] | None:
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


class AotInpainter:
    def __init__(self, model_path: str | Path, providers: list[str] | None = None) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(path)
        self.session = ort.InferenceSession(
            str(path), providers=providers or ["CPUExecutionProvider"],
        )

    def inpaint_regions(
        self,
        image: Image.Image,
        bubble_boxes: list[Any],
        blocks: list[dict[str, Any]],
        mask_boxes: list[dict[str, Any]] | None = None,
        mode: str = "QUALITY",
    ) -> Image.Image:
        """Full tiered inpainting pipeline.

        Args:
            image: original page image
            bubble_boxes: DetectionBox list with label=0
            blocks: list of block dicts with bbox, label, parentBbox, etc.

        Returns cleaned (inpainted) image.
        """
        result = image.convert("RGB").copy()
        w, h = result.size

        # Group text boxes by parent bubble. Prefer the durable Android-style
        # inpaint mask when present: label 0 = bubble parent, labels 1/2 = erase
        # text boxes. Falling back to blocks keeps older cached metadata usable.
        grouped: dict[tuple[int, int, int, int], list[list[int]]] = {}
        unparented: list[list[int]] = []
        free_boxes: list[list[int]] = []

        if mask_boxes is not None:
            mask_bubbles: list[list[int]] = []
            mask_text: list[tuple[list[int], int]] = []
            for mask in mask_boxes:
                box_list = [int(mask["x1"]), int(mask["y1"]), int(mask["x2"]), int(mask["y2"])]
                if box_list[2] <= box_list[0] or box_list[3] <= box_list[1]:
                    continue
                label = int(mask.get("label", 1))
                if label == 0:
                    mask_bubbles.append(box_list)
                else:
                    mask_text.append((box_list, label))

            for box_list, label in mask_text:
                parent = _find_parent_bubble_list(box_list, mask_bubbles)
                if parent is not None:
                    grouped.setdefault(tuple(parent), []).append(box_list)
                elif label == 2:
                    free_boxes.append(box_list)
                else:
                    unparented.append(box_list)
        else:
            for block in blocks:
                bbox = block["bbox"]
                box_list = [bbox["x1"], bbox["y1"], bbox["x2"], bbox["y2"]]
                parent = block.get("parentBbox", {})
                pw = block.get("parentW", 0)
                ph = block.get("parentH", 0)

                if pw > 0 and ph > 0:
                    key = (parent["x1"], parent["y1"], parent["x2"], parent["y2"])
                    grouped.setdefault(key, []).append(box_list)
                else:
                    if block.get("label") == 2:
                        free_boxes.append(box_list)
                    else:
                        unparented.append(box_list)

        # 1. Parented bubble groups → fill_contained
        for bubble_key, text_list in grouped.items():
            bx1, by1, bx2, by2 = bubble_key
            try:
                result = cleaner.fill_contained(
                    result,
                    bx1, by1, bx2, by2,
                    list(bubble_key),
                    text_list,
                )
            except Exception:
                # Fallback to clean_regions
                result = cleaner.clean_regions(result, text_list)

        # 2. Unparented text (label 1, no bubble) → fill_contained without bubble
        if unparented:
            ux1 = min(b[0] for b in unparented)
            uy1 = min(b[1] for b in unparented)
            ux2 = max(b[2] for b in unparented)
            uy2 = max(b[3] for b in unparented)
            try:
                result = cleaner.fill_contained(
                    result, ux1, uy1, ux2, uy2, None, unparented,
                )
            except Exception:
                result = cleaner.clean_regions(result, unparented)

        # 3. Free text (label 2) → neural per-box or classical fallback
        # Classify free boxes into flat/small/neural
        page_area = w * h
        small_threshold = page_area // 200
        flat_boxes = []
        neural_boxes = []
        for box in free_boxes:
            if cleaner.is_flat_background_region(result, box):
                flat_boxes.append(box)
            elif (box[2] - box[0]) * (box[3] - box[1]) < small_threshold:
                flat_boxes.append(box)
            else:
                neural_boxes.append(box)

        # Apply classical to flat/small first
        if flat_boxes:
            result = cleaner.fill_solid_boxes(result, flat_boxes, MASK_PAD)

        # Neural for textured free text (per-box). Honest semantics: a neural
        # failure (incl. NeuralOutputRejected) propagates so the caller can log
        # and report the box PARTIAL. No classical substitution here.
        if mode.upper() != "FAST":
            for box in neural_boxes:
                result = self.inpaint_free_text_box(result, box)
        elif neural_boxes:
            result = cleaner.fill_solid_boxes(result, neural_boxes, MASK_PAD)

        return result

    def inpaint_free_text_box(self, image: Image.Image, box: list[int]) -> Image.Image:
        """Neural AOT inpainting for a single free-text box.

        Crops with context, builds mask, runs AOT, feather-blends. Raises
        ``NeuralOutputRejected`` if the model emits a suspicious gray fill —
        the caller logs it and reports the box PARTIAL rather than masking
        the failure with classical output.
        """
        w, h = image.size
        x1, y1, x2, y2 = box

        # Neural crop sizing: text box ~1/3 of tensor
        text_w = x2 - x1
        text_h = y2 - y1
        box_long = max(text_w, text_h)
        target_crop = bm.compute_neural_crop(box_long)
        crop_margin = max(0, (target_crop - box_long) // 2)

        cx1 = max(0, x1 - crop_margin)
        cy1 = max(0, y1 - crop_margin)
        cx2 = min(w, x2 + crop_margin + 1)
        cy2 = min(h, y2 + crop_margin + 1)
        cw = cx2 - cx1
        ch = cy2 - cy1
        if cw <= 0 or ch <= 0:
            return image

        crop = image.crop((cx1, cy1, cx2, cy2)).convert("RGB")

        # Build mask: solid padded box + disk dilation
        local_box = [x1 - cx1, y1 - cy1, x2 - cx1, y2 - cy1]
        mask = bm.build_rect_mask(
            boxes=[np.array(local_box)],
            width=cw, height=ch,
            pad=MASK_PAD,
            dilate_radius=2,
        )

        if not np.any(mask):
            return image

        # Resize for inference (max 512, multiple of 8)
        needs_resize = max(cw, ch) > MAX_INFERENCE_DIM
        if needs_resize:
            scale = MAX_INFERENCE_DIM / max(cw, ch)
            infer_w = max(8, int(cw * scale))
            infer_h = max(8, int(ch * scale))
        else:
            pad_w = (8 - cw % 8) % 8
            pad_h = (8 - ch % 8) % 8
            infer_w = cw + pad_w
            infer_h = ch + pad_h

        # Prepare tensors
        if needs_resize:
            infer_image = crop.resize((infer_w, infer_h), Image.Resampling.BILINEAR)
            infer_mask = Image.fromarray(
                (mask != 0).astype(np.uint8) * 255, "L"
            ).resize((infer_w, infer_h), Image.Resampling.BILINEAR)
        else:
            infer_image = Image.new("RGB", (infer_w, infer_h))
            infer_image.paste(crop, (0, 0))
            mask_img = Image.new("L", (infer_w, infer_h), 0)
            mask_img.paste(Image.fromarray((mask != 0).astype(np.uint8) * 255, "L"), (0, 0))
            infer_mask = mask_img

        image_tensor = self._image_tensor(infer_image)
        mask_tensor = (np.asarray(infer_mask, dtype=np.float32) / 255.0)[None, None, :, :]
        mask_bin = (mask_tensor > 0.5).astype(np.float32)
        image_tensor = image_tensor * (1.0 - mask_bin)

        output = self.session.run(None, {"image": image_tensor, "mask": mask_tensor})[0]
        inpainted = self._tensor_image(output)

        # Resize back if needed
        if needs_resize:
            inpainted = inpainted.resize((cw, ch), Image.Resampling.BILINEAR)
        elif infer_w != cw or infer_h != ch:
            inpainted = inpainted.crop((0, 0, cw, ch))

        # Check for suspicious gray output
        inpainted_arr = np.array(inpainted, dtype=np.uint8)
        if is_suspicious_gray_fill(inpainted_arr, mask):
            # Detected model failure — surface it, do not mask it with classical.
            raise NeuralOutputRejected("AOT produced a suspicious uniform-gray fill")

        # Feather blend
        alpha = bm.feather_alpha_field(mask, FEATHER_RAMP_PX)
        orig_arr = np.array(crop, dtype=np.uint8)
        blended = _blend_arrays(orig_arr, inpainted_arr, alpha)

        result = Image.fromarray(blended, "RGB")
        image.paste(result, (cx1, cy1))
        return image

    def _image_tensor(self, image: Image.Image) -> np.ndarray:
        data = np.asarray(image, dtype=np.float32) / 255.0
        data = data * 2.0 - 1.0
        # Mask: zero out masked pixels in image (AOT convention)
        return np.transpose(data, (2, 0, 1))[None, :, :, :].astype(np.float32)

    def _tensor_image(self, tensor: np.ndarray) -> Image.Image:
        data = tensor[0]
        data = np.transpose(data, (1, 2, 0))
        data = ((data + 1.0) / 2.0 * 255.0).clip(0, 255).astype(np.uint8)
        return Image.fromarray(data, mode="RGB")


def _blend_arrays(original: np.ndarray, inpainted: np.ndarray, alpha: np.ndarray) -> np.ndarray:
    """Feather-blend original and inpainted using alpha field."""
    a = alpha[:, :, np.newaxis]
    orig_f = original.astype(np.float32)
    inp_f = inpainted.astype(np.float32)
    result = orig_f * (1.0 - a) + inp_f * a
    return np.clip(result, 0, 255).astype(np.uint8)
