"""TachiyomiAT Vision & Inpainting Diagnostic Studio
Standalone desktop testing interface for:
- PP-OCRv6 Manga v0.2 OCR pipeline (detection, CTC recognition, reading order)
- YOLO11 Manga Bubble Segmentation with stroke border erosion
- Inpainting Quality Laboratory: Fast (OpenCV), Balance, Full (AOT GAN), Legacy Median Fill
- Real-time before/after quality comparisons, difference heatmaps, oversized box tiling, and speed telemetry.
"""

import os
import sys
import json
import base64
import time
import io
import webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper

# ---------------------------------------------------------
# Configuration & Model Paths
# ---------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

OCR_ASSET_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "models", "ocr", "paddle-v6-small")
DET_MODEL_PATH = os.path.join(OCR_ASSET_DIR, "det", "inference.onnx")
REC_MODEL_PATH = os.path.join(OCR_ASSET_DIR, "inference.onnx")
DICT_PATH = os.path.join(OCR_ASSET_DIR, "PP-OCRv6_small_rec.txt")

SEG_MODEL_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "models", "segmentation", "manga109_bubble_int8.onnx")
AOT_512_MODEL_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "models", "inpainting", "aot-512.onnx")
AOT_DYN_MODEL_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "models", "inpainting", "aot.onnx")

PORT = 8765

# ---------------------------------------------------------
# OCR Engine Implementation
# ---------------------------------------------------------
class MangaOcrEngine:
    def __init__(self, det_path, rec_path, dict_path):
        print(f"[*] Loading Detector: {det_path}")
        opts = ort.SessionOptions()
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC

        self.det_session = ort.InferenceSession(det_path, opts, providers=["CPUExecutionProvider"])
        print(f"[*] Loading Recognizer: {rec_path}")
        self.rec_session = ort.InferenceSession(rec_path, opts, providers=["CPUExecutionProvider"])

        print(f"[*] Loading Dictionary: {dict_path}")
        with open(dict_path, "r", encoding="utf-8") as f:
            lines = [line.strip("\r\n") for line in f]
        self.vocab = ["blank"] + lines + [" "]
        print(f"[+] Loaded vocabulary with {len(self.vocab)} symbols.")

        self.det_input_name = self.det_session.get_inputs()[0].name
        self.rec_input_name = self.rec_session.get_inputs()[0].name

    def box_score_fast(self, pred, box):
        h, w = pred.shape[:2]
        b = box.copy().astype(np.int32)
        xmin = int(np.clip(np.floor(b[:, 0].min()), 0, w - 1))
        xmax = int(np.clip(np.ceil(b[:, 0].max()), 0, w - 1))
        ymin = int(np.clip(np.floor(b[:, 1].min()), 0, h - 1))
        ymax = int(np.clip(np.ceil(b[:, 1].max()), 0, h - 1))
        score_mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
        b[:, 0] -= xmin
        b[:, 1] -= ymin
        cv2.fillPoly(score_mask, [b.reshape(-1, 2)], 1)
        return float(cv2.mean(pred[ymin : ymax + 1, xmin : xmax + 1], score_mask)[0])

    def unclip(self, box, ratio):
        x, y = box[:, 0], box[:, 1]
        area = 0.5 * abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))
        perimeter = np.linalg.norm(np.roll(box, -1, axis=0) - box, axis=1).sum()
        if area <= 0 or perimeter <= 0:
            return None
        offset = pyclipper.PyclipperOffset()
        offset.AddPath(box.tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
        paths = offset.Execute(area * ratio / perimeter)
        return np.asarray(paths[0], dtype=np.float32) if len(paths) == 1 else None

    def process(self, img_bgr, thresh=0.15, box_thresh=0.25, unclip_ratio=1.4):
        h, w = img_bgr.shape[:2]
        t0 = time.perf_counter()

        max_dim = max(h, w)
        min_dim = min(h, w)
        if min_dim > 0 and max_dim / min_dim > 2:
            ratio = min(1.0, (0.75 * 960.0 * 960.0 / (h * w)) ** 0.5)
        else:
            ratio = 960.0 / max(1.0, max_dim)

        rh = max(32, int(round(h * ratio / 32.0) * 32))
        rw = max(32, int(round(w * ratio / 32.0) * 32))

        det_inp = cv2.resize(img_bgr, (rw, rh)).astype(np.float32) / 255.0
        det_inp = (det_inp - np.array([0.485, 0.456, 0.406], dtype=np.float32)) / np.array([0.229, 0.224, 0.225], dtype=np.float32)
        det_tensor = det_inp.transpose((2, 0, 1))[np.newaxis, ...].astype(np.float32)

        pred_map = self.det_session.run(None, {self.det_input_name: det_tensor})[0][0, 0]
        t_det = time.perf_counter()

        mask = pred_map > thresh
        contours, _ = cv2.findContours((mask * 255).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)

        boxes = []
        scores = []
        for cnt in contours:
            pts = cnt.squeeze(1)
            if pts.ndim != 2 or pts.shape[0] < 4:
                continue
            score = self.box_score_fast(pred_map, pts)
            if score < box_thresh:
                continue
            pts = self.unclip(pts, unclip_ratio)
            if pts is None or len(pts) == 0:
                continue
            rect = cv2.minAreaRect(pts.astype(np.float32))
            _, (bw, bh), _ = rect
            if min(bw, bh) < 3:
                continue
            box_pts = cv2.boxPoints(rect).astype(np.float32)
            box_pts[:, 0] = np.clip(np.round(box_pts[:, 0] / rw * w), 0, w)
            box_pts[:, 1] = np.clip(np.round(box_pts[:, 1] / rh * h), 0, h)
            boxes.append(box_pts)
            scores.append(score)

        sorted_indices = sorted(range(len(boxes)), key=lambda i: (boxes[i][:, 1].min() // 40, -boxes[i][:, 0].mean()))
        boxes = [boxes[i] for i in sorted_indices]
        scores = [scores[i] for i in sorted_indices]

        lines = []
        t_rec_start = time.perf_counter()
        for idx, (box, score) in enumerate(zip(boxes, scores)):
            xs = box[:, 0]
            ys = box[:, 1]
            x1, y1 = max(0, int(np.floor(xs.min()))), max(0, int(np.floor(ys.min())))
            x2, y2 = min(w, int(np.ceil(xs.max()))), min(h, int(np.ceil(ys.max())))
            crop = img_bgr[y1:y2, x1:x2]
            if crop.size == 0 or crop.shape[0] < 2 or crop.shape[1] < 2:
                continue

            is_vertical = crop.shape[0] > crop.shape[1]
            rec_crop = crop
            if is_vertical:
                rec_crop = cv2.rotate(crop, cv2.ROTATE_90_COUNTERCLOCKWISE)

            ch, cw = rec_crop.shape[:2]
            natural_w = int(round(48.0 * cw / max(1, ch)))
            target_w = max(16, min(2400 if natural_w > 2000 else 640, natural_w))

            rec_inp = cv2.resize(rec_crop, (target_w, 48)).astype(np.float32) / 255.0
            rec_inp = ((rec_inp - 0.5) / 0.5).transpose((2, 0, 1))[np.newaxis, ...].astype(np.float32)

            logits = self.rec_session.run(None, {self.rec_input_name: rec_inp})[0][0]
            indices = np.argmax(logits, axis=-1)

            text_chars = []
            char_confs = []
            probs = np.max(logits, axis=-1)

            for step_idx, char_idx in enumerate(indices):
                if char_idx != 0 and (step_idx == 0 or char_idx != indices[step_idx - 1]):
                    text_chars.append(self.vocab[char_idx])
                    char_confs.append(float(probs[step_idx]))

            text = "".join(text_chars).strip()
            line_conf = float(np.mean(char_confs)) if char_confs else float(score)

            _, thumb_buf = cv2.imencode(".png", rec_crop)
            crop_b64 = "data:image/png;base64," + base64.b64encode(thumb_buf).decode("ascii")

            lines.append({
                "id": idx + 1,
                "box": box.tolist(),
                "rect": [x1, y1, x2, y2],
                "width": x2 - x1,
                "height": y2 - y1,
                "vertical": bool(is_vertical),
                "det_score": round(score, 3),
                "rec_conf": round(line_conf, 3),
                "text": text,
                "crop": crop_b64
            })

        t_end = time.perf_counter()

        return {
            "image": {"width": w, "height": h},
            "timing": {
                "det_ms": round((t_det - t0) * 1000, 1),
                "rec_ms": round((t_end - t_rec_start) * 1000, 1),
                "total_ms": round((t_end - t0) * 1000, 1)
            },
            "parameters": {
                "thresh": thresh,
                "box_thresh": box_thresh,
                "unclip_ratio": unclip_ratio
            },
            "lines": lines
        }

# ---------------------------------------------------------
# Bubble Segmentation Engine (YOLO11-seg)
# ---------------------------------------------------------
class MangaSegmentationEngine:
    def __init__(self, model_path):
        print(f"[*] Loading Bubble Segmenter: {model_path}")
        opts = ort.SessionOptions()
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        self.session = ort.InferenceSession(model_path, opts, providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name

    def segment(self, img_bgr, conf_thresh=0.35, nms_thresh=0.5, erosion_radius=2):
        h, w = img_bgr.shape[:2]
        t0 = time.perf_counter()

        scale = min(640.0 / w, 640.0 / h)
        nw, nh = int(round(w * scale)), int(round(h * scale))
        pad_x = (640 - nw) // 2
        pad_y = (640 - nh) // 2

        resized = cv2.resize(img_bgr, (nw, nh))
        letterboxed = np.full((640, 640, 3), 114, dtype=np.uint8)
        letterboxed[pad_y:pad_y + nh, pad_x:pad_x + nw] = resized

        inp = (letterboxed.astype(np.float32) / 255.0).transpose((2, 0, 1))[np.newaxis, ...]
        out0, out1 = self.session.run(None, {self.input_name: inp})
        preds = out0[0].T  # (8400, 37)
        protos = out1[0]   # (32, 160, 160)

        boxes = []
        scores = []
        coefs_list = []
        for i in range(preds.shape[0]):
            score = float(preds[i, 4])
            if score >= conf_thresh:
                cx, cy, bw, bh = preds[i, :4]
                x1 = cx - bw / 2.0
                y1 = cy - bh / 2.0
                boxes.append([int(round(x1)), int(round(y1)), int(round(bw)), int(round(bh))])
                scores.append(score)
                coefs_list.append(preds[i, 5:37])

        if len(boxes) == 0:
            return {
                "bubbles": [],
                "page_raw_mask": np.zeros((h, w), dtype=np.uint8),
                "page_eroded_mask": np.zeros((h, w), dtype=np.uint8),
                "seg_ms": round((time.perf_counter() - t0) * 1000, 1)
            }

        indices = cv2.dnn.NMSBoxes(boxes, scores, conf_thresh, nms_thresh)
        if len(indices) == 0:
            return {
                "bubbles": [],
                "page_raw_mask": np.zeros((h, w), dtype=np.uint8),
                "page_eroded_mask": np.zeros((h, w), dtype=np.uint8),
                "seg_ms": round((time.perf_counter() - t0) * 1000, 1)
            }

        page_raw_mask = np.zeros((h, w), dtype=np.uint8)
        bubbles = []

        for idx_entry in indices:
            idx = idx_entry if isinstance(idx_entry, (int, np.integer)) else idx_entry[0]
            coefs = coefs_list[idx]
            score = scores[idx]

            mask_160 = (coefs @ protos.reshape(32, -1)).reshape(160, 160)
            mask_sig = 1.0 / (1.0 + np.exp(-mask_160))
            mask_640 = cv2.resize(mask_sig, (640, 640), interpolation=cv2.INTER_LINEAR)
            mask_crop = mask_640[pad_y:pad_y + nh, pad_x:pad_x + nw]
            mask_full = cv2.resize(mask_crop, (w, h), interpolation=cv2.INTER_LINEAR)
            single_raw_mask = (mask_full > 0.5).astype(np.uint8) * 255

            contours, _ = cv2.findContours(single_raw_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if not contours:
                continue
            largest_cnt = max(contours, key=cv2.contourArea)
            bx, by, bw, bh = cv2.boundingRect(largest_cnt)
            if bw < 8 or bh < 8:
                continue

            page_raw_mask = cv2.bitwise_or(page_raw_mask, single_raw_mask)
            bubbles.append({
                "id": len(bubbles) + 1,
                "rect": [bx, by, bx + bw, by + bh],
                "score": round(score, 3),
                "area": int(np.count_nonzero(single_raw_mask)),
                "raw_mask": single_raw_mask
            })

        # Apply morphological border erosion to protect bubble ink strokes
        if erosion_radius > 0:
            ksize = erosion_radius * 2 + 1
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ksize, ksize))
            page_eroded_mask = cv2.erode(page_raw_mask, kernel)
        else:
            page_eroded_mask = page_raw_mask.copy()

        t_end = time.perf_counter()
        return {
            "bubbles": bubbles,
            "page_raw_mask": page_raw_mask,
            "page_eroded_mask": page_eroded_mask,
            "seg_ms": round((t_end - t0) * 1000, 1)
        }

# ---------------------------------------------------------
# Inpainting Engine Suite
# ---------------------------------------------------------
class MangaInpaintEngine:
    def __init__(self, aot_512_path, aot_dyn_path=None):
        print(f"[*] Loading AOT GAN 512: {aot_512_path}")
        opts = ort.SessionOptions()
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        self.aot_512_sess = ort.InferenceSession(aot_512_path, opts, providers=["CPUExecutionProvider"])
        self.aot_dyn_sess = None
        if aot_dyn_path and os.path.exists(aot_dyn_path):
            print(f"[*] Loading Dynamic AOT GAN: {aot_dyn_path}")
            self.aot_dyn_sess = ort.InferenceSession(aot_dyn_path, opts, providers=["CPUExecutionProvider"])

    @staticmethod
    def inpaint_opencv(img_bgr, mask_u8, method="telea", radius=3):
        cv_method = cv2.INPAINT_TELEA if method.lower() == "telea" else cv2.INPAINT_NS
        return cv2.inpaint(img_bgr, mask_u8, radius, cv_method)

    @staticmethod
    def inpaint_legacy_median(img_bgr, mask_u8, feather_px=4):
        out = img_bgr.copy()
        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(mask_u8)
        for lbl in range(1, num_labels):
            comp = (labels == lbl).astype(np.uint8) * 255
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
            dilated = cv2.dilate(comp, kernel)
            ring = cv2.bitwise_and(dilated, cv2.bitwise_not(mask_u8))
            if np.count_nonzero(ring) > 0:
                ring_pixels = img_bgr[ring > 0]
                med_bgr = np.median(ring_pixels, axis=0).astype(np.uint8)
            else:
                med_bgr = np.array([255, 255, 255], dtype=np.uint8)
            out[comp > 0] = med_bgr

        if feather_px > 0:
            dist = cv2.distanceTransform(mask_u8, cv2.DIST_L2, 3)
            alpha = np.clip(dist / float(feather_px), 0.0, 1.0)[:, :, np.newaxis]
            out = (out.astype(np.float32) * alpha + img_bgr.astype(np.float32) * (1.0 - alpha)).astype(np.uint8)
        return out

    def inpaint_aot_512_tile(self, img_bgr, mask_u8, tile_rect):
        tx, ty, tw, th = tile_rect
        crop_img = img_bgr[ty:ty + th, tx:tx + tw]
        crop_mask = mask_u8[ty:ty + th, tx:tx + tw]

        pad_h = max(0, 512 - th)
        pad_w = max(0, 512 - tw)
        top = pad_h // 2
        bottom = pad_h - top
        left = pad_w // 2
        right = pad_w - left

        padded_img = cv2.copyMakeBorder(crop_img, top, bottom, left, right, cv2.BORDER_REPLICATE)
        padded_mask = cv2.copyMakeBorder(crop_mask, top, bottom, left, right, cv2.BORDER_CONSTANT, value=0)

        rgb = cv2.cvtColor(padded_img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        img_t = rgb.transpose((2, 0, 1))[np.newaxis, ...]
        mask_t = (padded_mask.astype(np.float32) / 255.0)[np.newaxis, np.newaxis, ...]

        out = self.aot_512_sess.run(None, {"image": img_t, "mask": mask_t})[0][0]
        out_rgb = np.clip(out * 255.0, 0, 255).astype(np.uint8).transpose((1, 2, 0))
        out_bgr = cv2.cvtColor(out_rgb, cv2.COLOR_RGB2BGR)

        return out_bgr[top:top + th, left:left + tw]

    def inpaint_aot_region(self, img_bgr, mask_u8, rect, context_pad=32, tile_oversized=True):
        h, w = img_bgr.shape[:2]
        rx1, ry1, rx2, ry2 = rect
        cx1 = max(0, rx1 - context_pad)
        cy1 = max(0, ry1 - context_pad)
        cx2 = min(w, rx2 + context_pad)
        cy2 = min(h, ry2 + context_pad)
        cw = cx2 - cx1
        ch = cy2 - cy1

        if cw <= 0 or ch <= 0:
            return img_bgr, 0

        crop_img = img_bgr[cy1:cy2, cx1:cx2].copy()
        crop_mask = mask_u8[cy1:cy2, cx1:cx2].copy()

        if np.count_nonzero(crop_mask) == 0:
            return img_bgr, 0

        tiles_run = 0
        if not tile_oversized or (cw <= 512 and ch <= 512):
            # Single AOT crop
            inpainted_crop = self.inpaint_aot_512_tile(crop_img, crop_mask, (0, 0, cw, ch))
            tiles_run += 1
            mask_3c = (crop_mask > 0)[:, :, np.newaxis]
            np.copyto(crop_img, inpainted_crop, where=mask_3c)
        else:
            # Oversized group tiling (Resolving Finding F2)
            stride = 448
            xs = [0] if cw <= 512 else list(range(0, cw - 512, stride)) + [cw - 512]
            ys = [0] if ch <= 512 else list(range(0, ch - 512, stride)) + [ch - 512]
            xs = sorted(list(set(xs)))
            ys = sorted(list(set(ys)))

            accum_img = np.zeros_like(crop_img, dtype=np.float32)
            accum_weight = np.zeros((ch, cw, 1), dtype=np.float32)

            for ty in ys:
                for tx in xs:
                    tw = min(512, cw - tx)
                    th = min(512, ch - ty)
                    tile_res = self.inpaint_aot_512_tile(crop_img, crop_mask, (tx, ty, tw, th))
                    tiles_run += 1

                    wx = np.sin(np.linspace(0.1, np.pi - 0.1, tw))
                    wy = np.sin(np.linspace(0.1, np.pi - 0.1, th))
                    w_tile = (wy[:, np.newaxis] * wx[np.newaxis, :])[:, :, np.newaxis]

                    accum_img[ty:ty + th, tx:tx + tw] += tile_res.astype(np.float32) * w_tile
                    accum_weight[ty:ty + th, tx:tx + tw] += w_tile

            mask_nonzero = accum_weight > 1e-4
            blended = np.zeros_like(crop_img)
            blended[mask_nonzero[:, :, 0]] = np.clip(accum_img[mask_nonzero[:, :, 0]] / accum_weight[mask_nonzero[:, :, 0]], 0, 255).astype(np.uint8)

            mask_3c = (crop_mask > 0)[:, :, np.newaxis]
            np.copyto(crop_img, blended, where=mask_3c)

        result = img_bgr.copy()
        result[cy1:cy2, cx1:cx2] = crop_img
        return result, tiles_run

# Global Engine Instances
OCR_ENGINE = None
SEG_ENGINE = None
INPAINT_ENGINE = None

# ---------------------------------------------------------
# Dynamic Pill Mask Construction
# ---------------------------------------------------------
def build_dynamic_pill_mask(rects, w, h, pad=6):
    mask = np.zeros((h, w), dtype=np.uint8)
    for r in rects:
        x1 = max(0, r[0] - pad)
        y1 = max(0, r[1] - pad)
        x2 = min(w, r[2] + pad)
        y2 = min(h, r[3] + pad)
        rw = x2 - x1
        rh = y2 - y1
        if rw <= 0 or rh <= 0:
            continue
        radius = min(rw, rh) // 2
        sub = np.zeros((rh, rw), dtype=np.uint8)
        cv2.rectangle(sub, (radius, 0), (rw - radius, rh), 255, -1)
        cv2.rectangle(sub, (0, radius), (rw, rh - radius), 255, -1)
        cv2.circle(sub, (radius, radius), radius, 255, -1)
        cv2.circle(sub, (rw - radius, radius), radius, 255, -1)
        cv2.circle(sub, (radius, rh - radius), radius, 255, -1)
        cv2.circle(sub, (rw - radius, rh - radius), radius, 255, -1)
        mask[y1:y2, x1:x2] = cv2.bitwise_or(mask[y1:y2, x1:x2], sub)
    return mask

# ---------------------------------------------------------
# Unified Web Studio HTML Template
# ---------------------------------------------------------
HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>TachiyomiAT Vision Diagnostic & Inpainting Quality Studio</title>
<style>
  :root {
    --bg-base: #080C14;
    --bg-surface: #0F172A;
    --bg-card: #1E293B;
    --border: #334155;
    --text-primary: #F8FAFC;
    --text-secondary: #94A3B8;
    --accent: #6366F1;
    --accent-hover: #4F46E5;
    --accent-glow: rgba(99, 102, 241, 0.25);
    --green: #10B981;
    --amber: #F59E0B;
    --red: #EF4444;
    --cyan: #06B6D4;
    --purple: #A855F7;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background-color: var(--bg-base);
    color: var(--text-primary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", sans-serif;
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow: hidden;
  }
  header {
    background-color: var(--bg-surface);
    border-bottom: 1px solid var(--border);
    padding: 10px 24px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }
  .brand { display: flex; align-items: center; gap: 12px; }
  .logo {
    width: 28px; height: 28px; background: linear-gradient(135deg, var(--accent), var(--cyan));
    border-radius: 6px; display: flex; align-items: center; justify-content: center;
    font-weight: 900; font-size: 14px; color: #fff;
  }
  .title-group h1 { font-size: 15px; font-weight: 700; letter-spacing: 0.5px; }
  .title-group p { font-size: 11px; color: var(--text-secondary); }

  /* Navigation Tabs */
  .nav-tabs {
    display: flex;
    gap: 4px;
    background: var(--bg-base);
    padding: 4px;
    border-radius: 8px;
    border: 1px solid var(--border);
  }
  .nav-tab {
    background: transparent;
    border: none;
    color: var(--text-secondary);
    padding: 6px 14px;
    font-size: 12px;
    font-weight: 600;
    border-radius: 6px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .nav-tab.active {
    background: var(--accent);
    color: #fff;
    box-shadow: 0 0 12px var(--accent-glow);
  }

  .header-actions { display: flex; align-items: center; gap: 10px; }
  .btn-upload {
    background-color: var(--accent); color: white; border: none; padding: 7px 14px;
    border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer;
    display: flex; align-items: center; gap: 6px;
  }
  .btn-upload:hover { background-color: var(--accent-hover); }

  /* Main Workspace */
  main {
    display: flex;
    flex: 1;
    overflow: hidden;
  }
  .tab-content {
    display: none;
    width: 100%;
    height: 100%;
  }
  .tab-content.active {
    display: flex;
  }

  /* 3-Column Studio Layout */
  .panel-left {
    width: 320px;
    background-color: var(--bg-surface);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    padding: 16px;
    gap: 14px;
    flex-shrink: 0;
  }
  .panel-center {
    flex: 1;
    position: relative;
    background-color: #05070B;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .panel-right {
    width: 340px;
    background-color: var(--bg-surface);
    border-left: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    padding: 16px;
    gap: 14px;
    flex-shrink: 0;
  }

  /* Section Styling */
  .section-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .section-title {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: var(--text-secondary);
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  /* Step Action Buttons */
  .action-deck {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .btn-step {
    padding: 10px;
    border-radius: 6px;
    font-weight: 700;
    font-size: 12px;
    cursor: pointer;
    border: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    transition: all 0.2s;
  }
  .btn-step-1 {
    background: #1E293B;
    color: var(--cyan);
    border-color: var(--cyan);
  }
  .btn-step-1:hover { background: rgba(6, 182, 212, 0.15); box-shadow: 0 0 10px rgba(6, 182, 212, 0.3); }

  .btn-step-2 {
    background: linear-gradient(135deg, var(--accent), #4F46E5);
    color: #fff;
    border: none;
  }
  .btn-step-2:hover { opacity: 0.95; box-shadow: 0 0 12px var(--accent-glow); }
  .btn-step:disabled { opacity: 0.4; cursor: not-allowed; }

  /* Segmented Mode Control */
  .mode-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 4px;
  }
  .mode-btn {
    background: var(--bg-base);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    padding: 7px 4px;
    font-size: 11px;
    font-weight: 600;
    border-radius: 6px;
    cursor: pointer;
    text-align: center;
  }
  .mode-btn.active {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
  }
  .mode-desc {
    font-size: 11px;
    color: var(--text-secondary);
    background: rgba(15, 23, 42, 0.6);
    padding: 6px 8px;
    border-radius: 4px;
    border-left: 2px solid var(--accent);
  }

  /* Slider Controls */
  .control-group {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .control-label {
    display: flex;
    justify-content: space-between;
    font-size: 11px;
    color: var(--text-secondary);
  }
  .control-label span:last-child {
    font-weight: 700;
    color: var(--text-primary);
  }
  input[type="range"] {
    -webkit-appearance: none;
    width: 100%;
    height: 4px;
    background: var(--border);
    border-radius: 2px;
    outline: none;
  }
  input[type="range"]::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 14px;
    height: 14px;
    background: var(--accent);
    border-radius: 50%;
    cursor: pointer;
  }

  /* Viewport Controls Bar */
  .viewport-toolbar {
    height: 42px;
    background: var(--bg-surface);
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 16px;
    z-index: 10;
  }
  .toolbar-group {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .btn-tool {
    background: var(--bg-card);
    border: 1px solid var(--border);
    color: var(--text-primary);
    padding: 5px 10px;
    font-size: 11px;
    font-weight: 600;
    border-radius: 5px;
    cursor: pointer;
  }
  .btn-tool.active {
    background: var(--accent);
    border-color: var(--accent);
    color: #fff;
  }

  /* Canvas Stage */
  .canvas-stage {
    flex: 1;
    position: relative;
    overflow: hidden;
    cursor: grab;
  }
  .canvas-stage:active { cursor: grabbing; }
  .canvas-container {
    position: absolute;
    transform-origin: 0 0;
  }
  canvas {
    position: absolute;
    top: 0;
    left: 0;
  }

  /* Split Curtain Slider */
  .curtain-divider {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 3px;
    background: #FFFFFF;
    box-shadow: 0 0 10px rgba(0, 0, 0, 0.9);
    cursor: ew-resize;
    z-index: 50;
  }
  .curtain-handle {
    position: absolute;
    top: 50%;
    left: -15px;
    transform: translateY(-50%);
    width: 32px;
    height: 32px;
    background: #FFFFFF;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #000;
    font-weight: bold;
    font-size: 14px;
    box-shadow: 0 2px 10px rgba(0,0,0,0.6);
    user-select: none;
  }

  /* Telemetry Cards */
  .metric-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .metric-card {
    background: var(--bg-base);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .metric-num {
    font-size: 18px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }
  .metric-label {
    font-size: 10px;
    color: var(--text-secondary);
    text-transform: uppercase;
  }

  /* Region List */
  .region-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-height: 420px;
    overflow-y: auto;
  }
  .region-item {
    background: var(--bg-base);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px;
    font-size: 11px;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .region-item:hover { border-color: var(--accent); }
  .region-item.selected { border-color: var(--accent); background: rgba(99, 102, 241, 0.1); }
  .region-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .badge {
    padding: 2px 6px;
    border-radius: 4px;
    font-size: 10px;
    font-weight: 700;
  }
  .badge-bubble { background: rgba(16, 185, 129, 0.2); color: var(--green); }
  .badge-free { background: rgba(99, 102, 241, 0.2); color: var(--accent); }
  .badge-route { background: rgba(245, 158, 11, 0.2); color: var(--amber); }

  /* Layer Switchers */
  .layer-checkboxes {
    display: flex;
    flex-direction: column;
    gap: 6px;
    font-size: 11px;
  }
  .layer-item {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
  }
  .layer-item input { accent-color: var(--accent); }

  /* Status Bar */
  footer {
    height: 24px;
    background: var(--bg-surface);
    border-top: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 16px;
    font-size: 11px;
    color: var(--text-secondary);
  }
</style>
</head>
<body>

<header>
  <div class="brand">
    <div class="logo">AT</div>
    <div class="title-group">
      <h1>Manga Vision Diagnostic &amp; Inpainting Quality Studio</h1>
      <p>PP-OCRv6 Manga v0.2 FP16 · YOLO11-seg Manga109 · AOT GAN · OpenCV SIMD</p>
    </div>
  </div>

  <div class="nav-tabs">
    <button class="nav-tab active" id="tabBtnInpaint" onclick="switchTab('inpaint')">01 · Inpainting Lab</button>
    <button class="nav-tab" id="tabBtnOcr" onclick="switchTab('ocr')">02 · OCR Diagnostic</button>
  </div>

  <div class="header-actions">
    <input type="file" id="fileInput" accept="image/*" style="display:none;" onchange="onFileSelected(event)">
    <button class="btn-upload" onclick="document.getElementById('fileInput').click()">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
      Load Page Image
    </button>
  </div>
</header>

<main>
  <!-- TAB 1: INPAINTING QUALITY LAB -->
  <div class="tab-content active" id="tabInpaint">
    <!-- LEFT PANEL: PIPELINE CONTROLS -->
    <div class="panel-left">

      <!-- Step Actions -->
      <div class="section-card">
        <div class="section-title">Workflow Actions</div>
        <div class="action-deck">
          <button class="btn-step btn-step-1" id="btnDetect" onclick="runDetectionStep()">
            <span>1 · Detect &amp; Segment Page</span>
          </button>
          <button class="btn-step btn-step-2" id="btnInpaint" onclick="runInpaintStep()">
            <span>2 · Inpaint Detected Regions</span>
          </button>
          <button class="btn-tool" style="padding:7px; font-weight:700;" onclick="runAllPipeline()">
            ⚡ Auto: Run Full Pipeline
          </button>
        </div>
      </div>

      <div class="section-card">
        <div class="section-title">Quality Routing Preset</div>
        <div class="mode-grid">
          <button class="mode-btn" onclick="setQualityMode('fast', this)">Fast</button>
          <button class="mode-btn active" onclick="setQualityMode('balance', this)">Balance</button>
          <button class="mode-btn" onclick="setQualityMode('full', this)">Full</button>
          <button class="mode-btn" onclick="setQualityMode('legacy', this)">Legacy</button>
          <button class="mode-btn" onclick="setQualityMode('manual', this)">Manual</button>
        </div>
        <div class="mode-desc" id="modeDesc">
          Balance: OpenCV on speech bubbles, AOT GAN on free text.
        </div>
      </div>

      <div class="section-card">
        <div class="section-title">OpenCV Classical Engine</div>
        <div class="control-group">
          <div class="control-label">Method</div>
          <div style="display:flex; gap:8px;">
            <label style="font-size:11px; display:flex; align-items:center; gap:4px;">
              <input type="radio" name="cvMethod" value="telea" checked onchange="updateCvMethod('telea')"> Telea (FMM)
            </label>
            <label style="font-size:11px; display:flex; align-items:center; gap:4px;">
              <input type="radio" name="cvMethod" value="ns" onchange="updateCvMethod('ns')"> Navier-Stokes
            </label>
          </div>
        </div>
        <div class="control-group">
          <div class="control-label"><span>Neighborhood Radius</span><span id="lblRadius">3 px</span></div>
          <input type="range" id="sliderRadius" min="1" max="15" value="3" oninput="document.getElementById('lblRadius').innerText = this.value + ' px'">
        </div>
      </div>

      <div class="section-card">
        <div class="section-title">
          <span>Stroke Border Protection</span>
          <span style="color:var(--green);" id="lblErosion">2 px</span>
        </div>
        <div class="control-group">
          <div class="control-label"><span>Mask Erosion Radius</span><span id="lblErosionVal">2 px</span></div>
          <input type="range" id="sliderErosion" min="0" max="15" value="2" oninput="document.getElementById('lblErosion').innerText = this.value + ' px'; document.getElementById('lblErosionVal').innerText = this.value + ' px';">
        </div>
        <div style="font-size:10px; color:var(--text-secondary);">
          Protects hand-drawn bubble strokes from being erased. Higher values preserve border ink; lower values clean closer to edge.
        </div>
      </div>

      <div class="section-card">
        <div class="section-title">AOT GAN &amp; Tiling (F2 Fix)</div>
        <div class="control-group">
          <div class="control-label"><span>Context Crop Padding</span><span id="lblPad">32 px</span></div>
          <input type="range" id="sliderPad" min="16" max="64" value="32" oninput="document.getElementById('lblPad').innerText = this.value + ' px'">
        </div>
        <label style="font-size:11px; display:flex; align-items:center; gap:6px; cursor:pointer;">
          <input type="checkbox" id="chkTile" checked> Tile Oversized Boxes (&gt;512px)
        </label>
      </div>

      <div class="section-card">
        <div class="section-title">Manual Mask Brush</div>
        <div class="control-group">
          <div class="control-label"><span>Brush Size</span><span id="lblBrushSize">24 px</span></div>
          <input type="range" id="sliderBrushSize" min="6" max="80" value="24" oninput="document.getElementById('lblBrushSize').innerText = this.value + ' px'">
        </div>
        <div style="display:flex; gap:6px;">
          <button class="btn-tool" id="btnBrushToggle" onclick="toggleBrushMode()" style="flex:1;">Enable Brush</button>
          <button class="btn-tool" onclick="clearBrushMask()" style="flex:1;">Clear Mask</button>
        </div>
      </div>
    </div>

    <!-- CENTER PANEL: INTERACTIVE CANVAS VIEWPORT -->
    <div class="panel-center">
      <div class="viewport-toolbar">
        <div class="toolbar-group">
          <span style="font-size:11px; color:var(--text-secondary);">View:</span>
          <button class="btn-tool active" id="btnModeClean" onclick="setViewMode('clean')">Clean View</button>
          <button class="btn-tool" id="btnModeOriginal" onclick="setViewMode('original')">Original</button>
          <button class="btn-tool" id="btnModeCurtain" onclick="setViewMode('curtain')">Curtain Split</button>
          <button class="btn-tool" id="btnModeDiff" onclick="setViewMode('diff')">Diff Heatmap</button>
          <span style="font-size:10px; color:var(--text-secondary); margin-left:8px;">[Space = Peek Orig]</span>
        </div>

        <div class="toolbar-group">
          <button class="btn-tool" onclick="zoomCanvas(0.8)">-</button>
          <span style="font-size:11px; font-weight:700;" id="lblZoom">100%</span>
          <button class="btn-tool" onclick="zoomCanvas(1.25)">+</button>
          <button class="btn-tool" onclick="resetZoom()">Fit</button>
        </div>
      </div>

      <div class="canvas-stage" id="stageContainer" onmousedown="onStageMouseDown(event)" onmousemove="onStageMouseMove(event)" onmouseup="onStageMouseUp(event)" onwheel="onStageWheel(event)">
        <div class="canvas-container" id="canvasContainer">
          <canvas id="canvasBase"></canvas>
          <canvas id="canvasClean"></canvas>
          <canvas id="canvasOverlay"></canvas>
          <canvas id="canvasBrush"></canvas>
        </div>
        <div class="curtain-divider" id="curtainDivider" style="display:none;" onmousedown="onCurtainMouseDown(event)">
          <div class="curtain-handle">&#x2194;</div>
        </div>
      </div>
    </div>

    <!-- RIGHT PANEL: TELEMETRY & REGION INSPECTION -->
    <div class="panel-right">
      <div class="section-card">
        <div class="section-title">Speed &amp; Latency Telemetry</div>
        <div class="metric-grid">
          <div class="metric-card">
            <span class="metric-num" style="color:var(--accent);" id="valTotalMs">0.0</span>
            <span class="metric-label">Total Time (ms)</span>
          </div>
          <div class="metric-card">
            <span class="metric-num" style="color:var(--green);" id="valMpix">0.0</span>
            <span class="metric-label">Throughput (MP/s)</span>
          </div>
          <div class="metric-card">
            <span class="metric-num" id="valSegMs">0.0</span>
            <span class="metric-label">Segmentation (ms)</span>
          </div>
          <div class="metric-card">
            <span class="metric-num" id="valAotMs">0.0</span>
            <span class="metric-label">AOT Neural (ms)</span>
          </div>
        </div>
        <div style="font-size:10px; color:var(--text-secondary); display:flex; justify-content:space-between; margin-top:4px;">
          <span>Det: <b id="subDetMs">0</b>ms</span>
          <span>CV: <b id="subCvMs">0</b>ms</span>
          <span>Tiles: <b id="subTiles">0</b></span>
          <span>Coverage: <b id="subCov">0%</b></span>
        </div>
      </div>

      <div class="section-card">
        <div class="section-title">Visual Layer Stack</div>
        <div class="layer-checkboxes">
          <label class="layer-item"><input type="checkbox" id="chkLyrOriginal" checked onchange="renderLayers()"> Original Manga Artwork</label>
          <label class="layer-item"><input type="checkbox" id="chkLyrCleaned" checked onchange="renderLayers()"> Cleaned Inpainted Artwork</label>
          <label class="layer-item"><input type="checkbox" id="chkLyrBoxes" checked onchange="renderLayers()"> Bounding Boxes &amp; Labels</label>
          <label class="layer-item"><input type="checkbox" id="chkLyrSeg" onchange="renderLayers()"> Bubble Segmentation Mask</label>
          <label class="layer-item"><input type="checkbox" id="chkLyrPill" onchange="renderLayers()"> Dynamic Pill Mask</label>
          <label class="layer-item"><input type="checkbox" id="chkLyrRoute" onchange="renderLayers()"> Pipeline Routing Map</label>
          <label class="layer-item"><input type="checkbox" id="chkLyrHeatmap" onchange="renderLayers()"> Difference Heatmap</label>
        </div>
      </div>

      <div class="section-card" style="flex:1;">
        <div class="section-title">
          <span>Admitted Regions</span>
          <span style="color:var(--accent);" id="lblRegionCount">0 Regions</span>
        </div>
        <div class="region-list" id="regionListContainer">
          <div style="font-size:11px; color:var(--text-secondary); text-align:center; padding:16px;">
            Load an image and click <b>1 · Detect &amp; Segment</b> to inspect admitted regions.
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- TAB 2: OCR DIAGNOSTIC WORKBENCH (PP-OCRv6) -->
  <div class="tab-content" id="tabOcr">
    <div class="panel-left">
      <div class="section-card">
        <div class="section-title">PP-OCRv6 Manga Parameters</div>
        <div class="control-group">
          <div class="control-label"><span>Detection Thresh</span><span id="lblOcrThresh">0.15</span></div>
          <input type="range" id="sliderOcrThresh" min="0.05" max="0.5" step="0.01" value="0.15" oninput="document.getElementById('lblOcrThresh').innerText = this.value">
        </div>
        <div class="control-group">
          <div class="control-label"><span>Box Score Thresh</span><span id="lblOcrBox">0.25</span></div>
          <input type="range" id="sliderOcrBox" min="0.1" max="0.6" step="0.01" value="0.25" oninput="document.getElementById('lblOcrBox').innerText = this.value">
        </div>
        <div class="control-group">
          <div class="control-label"><span>Unclip Ratio</span><span id="lblOcrUnclip">1.4</span></div>
          <input type="range" id="sliderOcrUnclip" min="1.0" max="2.2" step="0.05" value="1.4" oninput="document.getElementById('lblOcrUnclip').innerText = this.value">
        </div>
      </div>
      <button class="btn-step btn-step-1" onclick="runOcrOnly()">Run PP-OCRv6 Recognition</button>
    </div>

    <div class="panel-center" style="display:flex; flex-direction:row;">
      <div class="canvas-stage" id="ocrCanvasStage" style="flex:1;">
        <div class="canvas-container" id="ocrCanvasContainer">
          <canvas id="ocrCanvas"></canvas>
        </div>
      </div>
    </div>

    <div class="panel-right">
      <div class="section-card">
        <div class="section-title">Recognized Text Lines</div>
        <div class="region-list" id="ocrTextList">
          <div style="font-size:11px; color:var(--text-secondary); text-align:center; padding:16px;">
            No OCR text recognized yet.
          </div>
        </div>
      </div>
    </div>
  </div>
</main>

<footer>
  <span id="statusMessage">Ready. Load an image to begin.</span>
  <span id="engineStatus">Detector: Active · Recognizer: Active · Segmenter: Active · AOT: Active</span>
</footer>

<script>
// State Management
let currentImageUri = null;
let imgWidth = 0;
let imgHeight = 0;
let baseImageObj = null;
let cleanImageObj = null;
let heatmapImageObj = null;

let detectedRegions = null;
let inpaintResults = null;
let ocrResults = null;

let zoomLevel = 1.0;
let panX = 0;
let panY = 0;
let isPanning = false;
let startPanX = 0;
let startPanY = 0;

let curtainPos = 0.5; // 0..1
let isDraggingCurtain = false;
let currentViewMode = "clean"; // clean, original, curtain, diff

let currentQualityMode = "balance";
let currentCvMethod = "telea";
let brushActive = false;
let brushMaskCanvas = null;
let selectedRegionId = null;

const modeDescriptions = {
  fast: "Fast: OpenCV Telea / Navier-Stokes on all regions (instant turnaround).",
  balance: "Balance: OpenCV on speech bubbles, AOT GAN on free text (recommended).",
  full: "Full Quality: Neural AOT GAN on speech bubbles and free text.",
  legacy: "Legacy: Kotlin median fill on bubbles, AOT GAN on free text.",
  manual: "Manual: Per-box customized backend overrides."
};

function switchTab(tab) {
  document.getElementById("tabBtnInpaint").classList.toggle("active", tab === "inpaint");
  document.getElementById("tabBtnOcr").classList.toggle("active", tab === "ocr");
  document.getElementById("tabInpaint").classList.toggle("active", tab === "inpaint");
  document.getElementById("tabOcr").classList.toggle("active", tab === "ocr");
}

function setQualityMode(mode, btn) {
  currentQualityMode = mode;
  document.querySelectorAll(".mode-btn").forEach(b => b.classList.remove("active"));
  btn.classList.add("active");
  document.getElementById("modeDesc").innerText = modeDescriptions[mode];
}

function updateCvMethod(method) {
  currentCvMethod = method;
}

function onFileSelected(e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function(evt) {
    currentImageUri = evt.target.result;
    loadImage(currentImageUri);
  };
  reader.readAsDataURL(file);
}

function loadImage(uri) {
  baseImageObj = new Image();
  baseImageObj.onload = function() {
    imgWidth = baseImageObj.width;
    imgHeight = baseImageObj.height;

    setupCanvases(imgWidth, imgHeight);
    resetZoom();

    document.getElementById("statusMessage").innerText = `Loaded image: ${imgWidth}x${imgHeight}px. Click '1 · Detect & Segment' or 'Auto: Run Full Pipeline'.`;
    // Auto run full pipeline
    runAllPipeline();
  };
  baseImageObj.src = uri;
}

function setupCanvases(w, h) {
  ["canvasBase", "canvasClean", "canvasOverlay", "canvasBrush", "ocrCanvas"].forEach(id => {
    const c = document.getElementById(id);
    if (c) {
      c.width = w;
      c.height = h;
    }
  });

  const ctxBase = document.getElementById("canvasBase").getContext("2d");
  ctxBase.drawImage(baseImageObj, 0, 0);

  const ocrCtx = document.getElementById("ocrCanvas").getContext("2d");
  ocrCtx.drawImage(baseImageObj, 0, 0);

  brushMaskCanvas = document.createElement("canvas");
  brushMaskCanvas.width = w;
  brushMaskCanvas.height = h;
}

function resetZoom() {
  const stage = document.getElementById("stageContainer");
  const scaleX = (stage.clientWidth - 40) / imgWidth;
  const scaleY = (stage.clientHeight - 40) / imgHeight;
  zoomLevel = Math.min(1.0, Math.min(scaleX, scaleY));
  panX = (stage.clientWidth - imgWidth * zoomLevel) / 2;
  panY = (stage.clientHeight - imgHeight * zoomLevel) / 2;
  applyTransform();
}

function zoomCanvas(factor) {
  zoomLevel = Math.max(0.1, Math.min(10.0, zoomLevel * factor));
  applyTransform();
}

function applyTransform() {
  const cont = document.getElementById("canvasContainer");
  cont.style.transform = `translate(${panX}px, ${panY}px) scale(${zoomLevel})`;
  document.getElementById("lblZoom").innerText = Math.round(zoomLevel * 100) + "%";
  updateCurtainPosition();
}

function setViewMode(mode) {
  currentViewMode = mode;
  document.querySelectorAll("#btnModeClean, #btnModeOriginal, #btnModeCurtain, #btnModeDiff").forEach(b => b.classList.remove("active"));
  if (mode === "clean") document.getElementById("btnModeClean").classList.add("active");
  if (mode === "original") document.getElementById("btnModeOriginal").classList.add("active");
  if (mode === "curtain") document.getElementById("btnModeCurtain").classList.add("active");
  if (mode === "diff") document.getElementById("btnModeDiff").classList.add("active");

  const curtain = document.getElementById("curtainDivider");
  curtain.style.display = (mode === "curtain") ? "block" : "none";
  updateCurtainPosition();
  renderLayers();
}

function updateCurtainPosition() {
  if (currentViewMode !== "curtain") return;
  const stage = document.getElementById("stageContainer");
  const divider = document.getElementById("curtainDivider");
  const dividerX = stage.clientWidth * curtainPos;
  divider.style.left = dividerX + "px";
  renderLayers();
}

function onCurtainMouseDown(e) {
  isDraggingCurtain = true;
  e.stopPropagation();
}

function onStageMouseDown(e) {
  if (brushActive) {
    drawBrushStroke(e);
    return;
  }
  isPanning = true;
  startPanX = e.clientX - panX;
  startPanY = e.clientY - panY;
}

function onStageMouseMove(e) {
  if (isDraggingCurtain) {
    const stage = document.getElementById("stageContainer");
    const rect = stage.getBoundingClientRect();
    curtainPos = Math.max(0.01, Math.min(0.99, (e.clientX - rect.left) / stage.clientWidth));
    updateCurtainPosition();
    return;
  }
  if (isPanning) {
    panX = e.clientX - startPanX;
    panY = e.clientY - startPanY;
    applyTransform();
    return;
  }
  if (brushActive && (e.buttons === 1)) {
    drawBrushStroke(e);
  }
}

function onStageMouseUp(e) {
  isDraggingCurtain = false;
  isPanning = false;
}

function onStageWheel(e) {
  e.preventDefault();
  const zoomFactor = e.deltaY < 0 ? 1.15 : 0.85;
  zoomCanvas(zoomFactor);
}

// Spacebar to peek original
window.addEventListener("keydown", function(e) {
  if (e.code === "Space" && e.target === document.body) {
    e.preventDefault();
    document.getElementById("canvasClean").style.display = "none";
  }
});
window.addEventListener("keyup", function(e) {
  if (e.code === "Space") {
    document.getElementById("canvasClean").style.display = "block";
  }
});

function toggleBrushMode() {
  brushActive = !brushActive;
  const btn = document.getElementById("btnBrushToggle");
  btn.classList.toggle("active", brushActive);
  btn.innerText = brushActive ? "Brush Active" : "Enable Brush";
}

function clearBrushMask() {
  if (brushMaskCanvas) {
    const ctx = brushMaskCanvas.getContext("2d");
    ctx.clearRect(0, 0, imgWidth, imgHeight);
    const brushCtx = document.getElementById("canvasBrush").getContext("2d");
    brushCtx.clearRect(0, 0, imgWidth, imgHeight);
  }
}

function drawBrushStroke(e) {
  if (!brushMaskCanvas) return;
  const rect = document.getElementById("canvasBase").getBoundingClientRect();
  const x = (e.clientX - rect.left) / zoomLevel;
  const y = (e.clientY - rect.top) / zoomLevel;
  const radius = parseInt(document.getElementById("sliderBrushSize").value);

  const ctx = brushMaskCanvas.getContext("2d");
  ctx.fillStyle = "#FF0000";
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.fill();

  const visCtx = document.getElementById("canvasBrush").getContext("2d");
  visCtx.fillStyle = "rgba(239, 68, 68, 0.4)";
  visCtx.beginPath();
  visCtx.arc(x, y, radius, 0, Math.PI * 2);
  visCtx.fill();
}

// ---------------------------------------------------------
// Pipeline Step 1: Detect & Segment
// ---------------------------------------------------------
async function runDetectionStep() {
  if (!currentImageUri) {
    alert("Please load a manga page image first.");
    return;
  }
  const btn = document.getElementById("btnDetect");
  btn.disabled = true;
  document.getElementById("statusMessage").innerText = "Step 1: Running OCR detection & YOLO11 bubble segmentation...";

  const payload = {
    image: currentImageUri,
    mode: currentQualityMode,
    erosion_radius: parseInt(document.getElementById("sliderErosion").value)
  };

  try {
    const resp = await fetch("/api/detect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await resp.json();
    if (data.error) throw new Error(data.error);

    detectedRegions = data.regions;
    populateRegions(detectedRegions);

    document.getElementById("valSegMs").innerText = data.timing.seg_ms;
    document.getElementById("subDetMs").innerText = data.timing.det_ms;

    // Draw detected boxes & bubble outlines on canvas
    drawBoxesOnOverlay(detectedRegions);

    document.getElementById("statusMessage").innerText = `Step 1 Complete: ${detectedRegions.length} regions identified (${data.stats.bubble_count} bubbles, ${data.stats.text_count} text lines). Ready to inpaint.`;
    btn.disabled = false;
  } catch (err) {
    alert("Detection error: " + err.message);
    btn.disabled = false;
  }
}

// ---------------------------------------------------------
// Pipeline Step 2: Inpaint Detected Regions
// ---------------------------------------------------------
async function runInpaintStep() {
  if (!currentImageUri) {
    alert("Please load an image first.");
    return;
  }

  const btn = document.getElementById("btnInpaint");
  btn.disabled = true;
  document.getElementById("statusMessage").innerText = "Step 2: Inpainting detected regions...";

  let brushMaskUri = null;
  if (brushMaskCanvas) {
    brushMaskUri = brushMaskCanvas.toDataURL("image/png");
  }

  const payload = {
    image: currentImageUri,
    mode: currentQualityMode,
    opencv_method: currentCvMethod,
    opencv_radius: parseInt(document.getElementById("sliderRadius").value),
    erosion_radius: parseInt(document.getElementById("sliderErosion").value),
    context_pad: parseInt(document.getElementById("sliderPad").value),
    tile_oversized: document.getElementById("chkTile").checked,
    brush_mask: brushMaskUri
  };

  try {
    const resp = await fetch("/api/inpaint", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await resp.json();
    if (data.error) throw new Error(data.error);

    inpaintResults = data;
    detectedRegions = data.regions;

    cleanImageObj = new Image();
    cleanImageObj.onload = function() {
      heatmapImageObj = new Image();
      heatmapImageObj.onload = function() {
        updateTelemetry(data);
        setViewMode("clean");
        populateRegions(data.regions);
        document.getElementById("statusMessage").innerText = `✓ Inpainting Complete: Cleaned ${data.regions.length} regions in ${data.timing.total_ms} ms.`;
        btn.disabled = false;
      };
      heatmapImageObj.src = data.heatmap_uri;
    };
    cleanImageObj.src = data.cleaned_uri;

  } catch (err) {
    console.error(err);
    alert("Inpainting error: " + err.message);
    btn.disabled = false;
  }
}

async function runAllPipeline() {
  await runInpaintStep();
}

function updateTelemetry(data) {
  document.getElementById("valTotalMs").innerText = data.timing.total_ms;
  document.getElementById("valSegMs").innerText = data.timing.seg_ms;
  document.getElementById("valAotMs").innerText = data.timing.aot_ms;
  document.getElementById("subDetMs").innerText = data.timing.det_ms;
  document.getElementById("subCvMs").innerText = data.timing.cv_ms;
  document.getElementById("subTiles").innerText = data.stats.tile_count || 0;

  const mpix = (imgWidth * imgHeight / 1000000) / (data.timing.total_ms / 1000);
  document.getElementById("valMpix").innerText = mpix.toFixed(1);
  document.getElementById("subCov").innerText = data.stats.coverage_pct + "%";
}

function drawBoxesOnOverlay(regions) {
  const canvasOverlay = document.getElementById("canvasOverlay");
  const ctx = canvasOverlay.getContext("2d");
  ctx.clearRect(0, 0, imgWidth, imgHeight);

  if (!regions) return;

  regions.forEach(reg => {
    const [x1, y1, x2, y2] = reg.rect;
    const isBubble = reg.class === "bubble_text" || reg.class === "bubble";
    const isSelected = reg.id === selectedRegionId;

    ctx.lineWidth = isSelected ? 3 : 1.5;
    ctx.strokeStyle = isSelected ? "#F43F5E" : (isBubble ? "#10B981" : "#6366F1");
    ctx.strokeRect(x1, y1, x2 - x1, y2 - y1);

    ctx.fillStyle = isBubble ? "#10B981" : "#6366F1";
    ctx.fillRect(x1, Math.max(0, y1 - 16), 56, 16);
    ctx.fillStyle = "#FFFFFF";
    ctx.font = "bold 9px sans-serif";
    ctx.fillText(reg.route.toUpperCase(), x1 + 4, Math.max(12, y1 - 4));
  });
}

function renderLayers() {
  const canvasClean = document.getElementById("canvasClean");
  const canvasOverlay = document.getElementById("canvasOverlay");
  const ctxClean = canvasClean.getContext("2d");

  ctxClean.clearRect(0, 0, imgWidth, imgHeight);

  if (!cleanImageObj) return;

  const showClean = document.getElementById("chkLyrCleaned").checked;
  const showBoxes = document.getElementById("chkLyrBoxes").checked;
  const showHeatmap = document.getElementById("chkLyrHeatmap").checked;

  if (currentViewMode === "original") {
    // Canvas clean is clear, showing canvasBase
  } else if (currentViewMode === "diff" || showHeatmap) {
    if (heatmapImageObj) ctxClean.drawImage(heatmapImageObj, 0, 0);
  } else if (currentViewMode === "curtain") {
    const stage = document.getElementById("stageContainer");
    const dividerStageX = stage.clientWidth * curtainPos;
    const dividerCanvasX = (dividerStageX - panX) / zoomLevel;

    ctxClean.save();
    ctxClean.beginPath();
    ctxClean.rect(dividerCanvasX, 0, imgWidth - dividerCanvasX, imgHeight);
    ctxClean.clip();
    ctxClean.drawImage(cleanImageObj, 0, 0);
    ctxClean.restore();
  } else if (showClean) {
    ctxClean.drawImage(cleanImageObj, 0, 0);
  }

  if (showBoxes && (detectedRegions || (inpaintResults && inpaintResults.regions))) {
    drawBoxesOnOverlay(detectedRegions || inpaintResults.regions);
  } else {
    canvasOverlay.getContext("2d").clearRect(0, 0, imgWidth, imgHeight);
  }
}

function populateRegions(regions) {
  const container = document.getElementById("regionListContainer");
  container.innerHTML = "";
  if (!regions) return;

  document.getElementById("lblRegionCount").innerText = `${regions.length} Regions`;

  regions.forEach(reg => {
    const item = document.createElement("div");
    item.className = "region-item" + (reg.id === selectedRegionId ? " selected" : "");
    const [x1, y1, x2, y2] = reg.rect;
    item.innerHTML = `
      <div class="region-header">
        <span style="font-weight:700;">#${reg.id} · ${x2 - x1}x${y2 - y1}px</span>
        <span class="badge ${reg.class === 'bubble_text' || reg.class === 'bubble' ? 'badge-bubble' : 'badge-free'}">${reg.class.replace('_', ' ')}</span>
      </div>
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <span style="color:var(--text-secondary); font-size:10px;">[${x1},${y1}] &rarr; [${x2},${y2}]</span>
        <span class="badge badge-route">${reg.route}</span>
      </div>
    `;
    item.onclick = () => selectRegion(reg.id, reg.rect);
    container.appendChild(item);
  });
}

function selectRegion(id, rect) {
  selectedRegionId = id;
  const [x1, y1, x2, y2] = rect;
  const stage = document.getElementById("stageContainer");
  const rw = x2 - x1;
  const rh = y2 - y1;
  zoomLevel = Math.min(2.5, Math.min(stage.clientWidth / (rw * 1.5), stage.clientHeight / (rh * 1.5)));
  panX = stage.clientWidth / 2 - (x1 + rw / 2) * zoomLevel;
  panY = stage.clientHeight / 2 - (y1 + rh / 2) * zoomLevel;
  applyTransform();
  renderLayers();
  populateRegions(detectedRegions || (inpaintResults ? inpaintResults.regions : []));
}

async function runOcrOnly() {
  if (!currentImageUri) {
    alert("Please load an image first.");
    return;
  }
  document.getElementById("statusMessage").innerText = "Running PP-OCRv6 Manga recognition...";

  const payload = {
    image: currentImageUri,
    thresh: parseFloat(document.getElementById("sliderOcrThresh").value),
    box_thresh: parseFloat(document.getElementById("sliderOcrBox").value),
    unclip_ratio: parseFloat(document.getElementById("sliderOcrUnclip").value)
  };

  try {
    const resp = await fetch("/api/ocr", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await resp.json();
    ocrResults = data;

    const canvas = document.getElementById("ocrCanvas");
    const ctx = canvas.getContext("2d");
    ctx.drawImage(baseImageObj, 0, 0);

    const list = document.getElementById("ocrTextList");
    list.innerHTML = "";

    data.lines.forEach(l => {
      ctx.lineWidth = 2;
      ctx.strokeStyle = "#10B981";
      const [x1, y1, x2, y2] = l.rect;
      ctx.strokeRect(x1, y1, x2 - x1, y2 - y1);

      const div = document.createElement("div");
      div.className = "region-item";
      div.innerHTML = `
        <div class="region-header">
          <span style="font-weight:700;">#${l.id} · Conf: ${l.rec_conf}</span>
          <span class="badge badge-bubble">${l.vertical ? 'Vertical' : 'Horizontal'}</span>
        </div>
        <div style="font-size:12px; font-weight:600; color:#fff; margin-top:2px;">${l.text || '(blank)'}</div>
      `;
      list.appendChild(div);
    });

    document.getElementById("statusMessage").innerText = `OCR complete: ${data.lines.length} lines detected.`;
  } catch (err) {
    alert("OCR Error: " + err.message);
  }
}
</script>

</body>
</html>
"""

# ---------------------------------------------------------
# HTTP Request Handler
# ---------------------------------------------------------
class StudioRequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode("utf-8"))
        elif self.path == "/api/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            resp = {
                "detector": DET_MODEL_PATH,
                "recognizer": REC_MODEL_PATH,
                "segmenter": SEG_MODEL_PATH,
                "aot_512": AOT_512_MODEL_PATH
            }
            self.wfile.write(json.dumps(resp).encode("utf-8"))
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        post_data = self.rfile.read(content_length)
        data = json.loads(post_data.decode("utf-8"))
        img_data_uri = data.get("image", "")

        if "," in img_data_uri:
            img_data_uri = img_data_uri.split(",", 1)[1]
        img_bytes = base64.b64decode(img_data_uri)
        nparr = np.frombuffer(img_bytes, np.uint8)
        img_bgr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if img_bgr is None:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(json.dumps({"error": "Failed to decode image"}).encode("utf-8"))
            return

        h, w = img_bgr.shape[:2]

        if self.path == "/api/ocr":
            thresh = float(data.get("thresh", 0.15))
            box_thresh = float(data.get("box_thresh", 0.25))
            unclip_ratio = float(data.get("unclip_ratio", 1.4))
            res = OCR_ENGINE.process(img_bgr, thresh=thresh, box_thresh=box_thresh, unclip_ratio=unclip_ratio)
            resp_bytes = json.dumps(res).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(resp_bytes)

        elif self.path == "/api/detect":
            mode = data.get("mode", "balance").lower()
            erosion_radius = int(data.get("erosion_radius", 2))

            t0 = time.perf_counter()
            ocr_res = OCR_ENGINE.process(img_bgr)
            det_ms = round((time.perf_counter() - t0) * 1000, 1)

            seg_res = SEG_ENGINE.segment(img_bgr, erosion_radius=erosion_radius)
            seg_ms = seg_res["seg_ms"]

            bubble_rects = [b["rect"] for b in seg_res["bubbles"]]

            regions = []
            # Speech Bubbles from Segmenter
            for b in seg_res["bubbles"]:
                route = "telea"
                if mode == "fast": route = "telea"
                elif mode == "balance": route = "telea"
                elif mode == "full": route = "aot"
                elif mode == "legacy": route = "median"

                regions.append({
                    "id": len(regions) + 1,
                    "rect": b["rect"],
                    "class": "bubble",
                    "route": route
                })

            # Text Lines from OCR
            for line in ocr_res["lines"]:
                rect = line["rect"]
                rx1, ry1, rx2, ry2 = rect
                text_area = max(1, (rx2 - rx1) * (ry2 - ry1))

                is_bubble_text = False
                for bx1, by1, bx2, by2 in bubble_rects:
                    iw = max(0, min(rx2, bx2) - max(rx1, bx1))
                    ih = max(0, min(ry2, by2) - max(ry1, by1))
                    if (iw * ih) / float(text_area) >= 0.12:
                        is_bubble_text = True
                        break

                if not is_bubble_text:
                    route = "telea" if mode == "fast" else "aot"
                    regions.append({
                        "id": len(regions) + 1,
                        "rect": rect,
                        "class": "free_text",
                        "route": route
                    })

            resp_payload = {
                "regions": regions,
                "timing": {
                    "det_ms": det_ms,
                    "seg_ms": seg_ms
                },
                "stats": {
                    "bubble_count": len(seg_res["bubbles"]),
                    "text_count": len(ocr_res["lines"])
                }
            }
            resp_bytes = json.dumps(resp_payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(resp_bytes)

        elif self.path == "/api/inpaint":
            t_start = time.perf_counter()
            mode = data.get("mode", "balance").lower()
            cv_method = data.get("opencv_method", "telea").lower()
            cv_radius = int(data.get("opencv_radius", 3))
            erosion_radius = int(data.get("erosion_radius", 2))
            context_pad = int(data.get("context_pad", 32))
            tile_oversized = bool(data.get("tile_oversized", True))
            brush_mask_uri = data.get("brush_mask", None)

            # 1. OCR Detection
            t_det_0 = time.perf_counter()
            ocr_res = OCR_ENGINE.process(img_bgr)
            det_ms = round((time.perf_counter() - t_det_0) * 1000, 1)

            # 2. Bubble Segmentation
            seg_res = SEG_ENGINE.segment(img_bgr, erosion_radius=erosion_radius)
            seg_ms = seg_res["seg_ms"]

            bubble_rects = [b["rect"] for b in seg_res["bubbles"]]
            eroded_bubble_mask = seg_res["page_eroded_mask"]

            # 3. Geometric Classification & Region Assembly
            regions = []
            text_pill_rects = []
            for b in seg_res["bubbles"]:
                route = cv_method
                if mode == "fast": route = cv_method
                elif mode == "balance": route = cv_method
                elif mode == "full": route = "aot"
                elif mode == "legacy": route = "median"

                regions.append({
                    "id": len(regions) + 1,
                    "rect": b["rect"],
                    "class": "bubble",
                    "route": route
                })

            for line in ocr_res["lines"]:
                rect = line["rect"]
                rx1, ry1, rx2, ry2 = rect
                text_area = max(1, (rx2 - rx1) * (ry2 - ry1))

                is_bubble_text = False
                for bx1, by1, bx2, by2 in bubble_rects:
                    iw = max(0, min(rx2, bx2) - max(rx1, bx1))
                    ih = max(0, min(ry2, by2) - max(ry1, by1))
                    if (iw * ih) / float(text_area) >= 0.12:
                        is_bubble_text = True
                        break

                if not is_bubble_text:
                    route = cv_method if mode == "fast" else "aot"
                    regions.append({
                        "id": len(regions) + 1,
                        "rect": rect,
                        "class": "free_text",
                        "route": route
                    })
                    text_pill_rects.append(rect)
                else:
                    text_pill_rects.append(rect)

            # 4. Mask Assembly
            pill_mask = build_dynamic_pill_mask(text_pill_rects, w, h, pad=6)
            combined_mask = cv2.bitwise_or(eroded_bubble_mask, pill_mask)

            # Add manual brush strokes if present
            if brush_mask_uri and "," in brush_mask_uri:
                b_bytes = base64.b64decode(brush_mask_uri.split(",", 1)[1])
                b_arr = np.frombuffer(b_bytes, np.uint8)
                b_img = cv2.imdecode(b_arr, cv2.IMREAD_UNCHANGED)
                if b_img is not None:
                    if b_img.shape[-1] == 4:
                        brush_alpha = b_img[:, :, 3]
                        combined_mask = cv2.bitwise_or(combined_mask, (brush_alpha > 10).astype(np.uint8) * 255)

            # 5. Inpainting Execution
            cleaned_bgr = img_bgr.copy()
            cv_ms = 0
            aot_ms = 0
            tile_count = 0

            # Execute OpenCV / Median regions
            if mode in ["fast", "balance", "legacy"]:
                # Fast: all mask goes to OpenCV
                # Balance: bubble mask goes to OpenCV
                # Legacy: bubble mask goes to Median
                if mode == "fast":
                    target_classical_mask = combined_mask
                else:
                    target_classical_mask = eroded_bubble_mask

                if np.count_nonzero(target_classical_mask) > 0:
                    t_cv_0 = time.perf_counter()
                    if mode == "legacy":
                        cleaned_bgr = MangaInpaintEngine.inpaint_legacy_median(cleaned_bgr, target_classical_mask)
                    else:
                        cleaned_bgr = MangaInpaintEngine.inpaint_opencv(cleaned_bgr, target_classical_mask, method=cv_method, radius=cv_radius)
                    cv_ms = round((time.perf_counter() - t_cv_0) * 1000, 1)

            # Execute Neural AOT GAN regions
            aot_regions = [r for r in regions if r["route"] == "aot"]
            if aot_regions:
                t_aot_0 = time.perf_counter()
                for reg in aot_regions:
                    cleaned_bgr, tiles = INPAINT_ENGINE.inpaint_aot_region(
                        cleaned_bgr, combined_mask, reg["rect"],
                        context_pad=context_pad, tile_oversized=tile_oversized
                    )
                    tile_count += tiles
                aot_ms = round((time.perf_counter() - t_aot_0) * 1000, 1)

            total_ms = round((time.perf_counter() - t_start) * 1000, 1)

            # 6. Generate Difference Heatmap
            diff = cv2.absdiff(img_bgr, cleaned_bgr)
            diff_gray = cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY)
            diff_norm = cv2.normalize(diff_gray, None, 0, 255, cv2.NORM_MINMAX)
            heatmap = cv2.applyColorMap(diff_norm, cv2.COLORMAP_JET)
            heatmap_masked = np.zeros_like(heatmap)
            heatmap_masked[diff_gray > 2] = heatmap[diff_gray > 2]

            # Encode Outputs
            _, clean_buf = cv2.imencode(".jpg", cleaned_bgr, [cv2.IMWRITE_JPEG_QUALITY, 92])
            clean_b64 = "data:image/jpeg;base64," + base64.b64encode(clean_buf).decode("ascii")

            _, heat_buf = cv2.imencode(".png", heatmap_masked)
            heat_b64 = "data:image/png;base64," + base64.b64encode(heat_buf).decode("ascii")

            masked_pixels = int(np.count_nonzero(combined_mask))
            coverage_pct = round((masked_pixels / float(w * h)) * 100, 2)

            resp_payload = {
                "cleaned_uri": clean_b64,
                "heatmap_uri": heat_b64,
                "timing": {
                    "det_ms": det_ms,
                    "seg_ms": seg_ms,
                    "cv_ms": cv_ms,
                    "aot_ms": aot_ms,
                    "total_ms": total_ms
                },
                "stats": {
                    "tile_count": tile_count,
                    "masked_pixels": masked_pixels,
                    "coverage_pct": coverage_pct
                },
                "regions": regions
            }

            resp_bytes = json.dumps(resp_payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp_bytes)))
            self.end_headers()
            self.wfile.write(resp_bytes)
        else:
            self.send_error(404, "Not Found")

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

def main():
    global OCR_ENGINE, SEG_ENGINE, INPAINT_ENGINE
    print("=" * 70)
    print("  TachiyomiAT Vision Diagnostic & Inpainting Quality Studio")
    print("=" * 70)

    for p, name in [
        (DET_MODEL_PATH, "Detector"),
        (REC_MODEL_PATH, "Recognizer"),
        (DICT_PATH, "Vocabulary"),
        (SEG_MODEL_PATH, "Bubble Segmenter"),
        (AOT_512_MODEL_PATH, "AOT GAN 512")
    ]:
        if not os.path.exists(p):
            print(f"[!] Error: {name} not found at {p}")
            sys.exit(1)

    OCR_ENGINE = MangaOcrEngine(DET_MODEL_PATH, REC_MODEL_PATH, DICT_PATH)
    SEG_ENGINE = MangaSegmentationEngine(SEG_MODEL_PATH)
    INPAINT_ENGINE = MangaInpaintEngine(AOT_512_MODEL_PATH, AOT_DYN_MODEL_PATH)

    server = ThreadedHTTPServer(("127.0.0.1", PORT), StudioRequestHandler)
    url = f"http://127.0.0.1:{PORT}"
    print(f"\n[+] Studio UI running at: {url}")
    print("[+] Opening browser automatically...")
    webbrowser.open(url)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Shutting down studio server.")
        server.server_close()

if __name__ == "__main__":
    main()
