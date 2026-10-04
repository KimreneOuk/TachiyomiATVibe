"""PP-OCRv6 Manga v0.2 Visual Diagnostic Workbench
Standalone desktop testing interface for PP-OCRv6 Manga v0.2.
Detects text regions, displays bounding boxes, visualizes crops, and displays extracted text.
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
ASSET_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "models", "ocr", "paddle-v6-small")

DET_MODEL_PATH = os.path.join(ASSET_DIR, "det", "inference.onnx")
REC_MODEL_PATH = os.path.join(ASSET_DIR, "inference.onnx")
DICT_PATH = os.path.join(ASSET_DIR, "PP-OCRv6_small_rec.txt")

PORT = 8765

# ---------------------------------------------------------
# OCR Engine Implementation
# ---------------------------------------------------------
class MangaOcrEngine:
    def __init__(self, det_path, rec_path, dict_path):
        print(f"[*] Loading Detector: {det_path}")
        opts = ort.SessionOptions()
        # Use ORT_ENABLE_BASIC to avoid SimplifiedLayerNormFusion issue with FP16
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

        # 1. Detection Preprocessing
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

        # Detector Inference
        pred_map = self.det_session.run(None, {self.det_input_name: det_tensor})[0][0, 0]
        t_det = time.perf_counter()

        # 2. DB Post-Processing
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

        # Reading order sorting: Manga top-to-bottom, right-to-left
        sorted_indices = sorted(range(len(boxes)), key=lambda i: (boxes[i][:, 1].min() // 40, -boxes[i][:, 0].mean()))
        boxes = [boxes[i] for i in sorted_indices]
        scores = [scores[i] for i in sorted_indices]

        # 3. Recognition on Crops
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

            # CTC greedy decode + confidence
            text_chars = []
            char_confs = []
            probs = np.max(logits, axis=-1)

            for step_idx, char_idx in enumerate(indices):
                if char_idx != 0 and (step_idx == 0 or char_idx != indices[step_idx - 1]):
                    text_chars.append(self.vocab[char_idx])
                    char_confs.append(float(probs[step_idx]))

            text = "".join(text_chars).strip()
            line_conf = float(np.mean(char_confs)) if char_confs else float(score)

            # Encode small thumbnail of the crop for UI inspection
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

# Global OCR Engine
OCR_ENGINE = None

# ---------------------------------------------------------
# Web UI HTML Template
# ---------------------------------------------------------
HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>PP-OCRv6 Manga v0.2 Visual Diagnostic Workbench</title>
<style>
  :root {
    --bg-base: #0B0E14;
    --bg-surface: #151922;
    --bg-card: #1D2330;
    --border: #2D3748;
    --text-primary: #F7FAFC;
    --text-secondary: #A0AEC0;
    --accent: #6366F1;
    --accent-glow: rgba(99, 102, 241, 0.3);
    --green: #10B981;
    --amber: #F59E0B;
    --red: #EF4444;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background-color: var(--bg-base);
    color: var(--text-primary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow: hidden;
  }
  header {
    background-color: var(--bg-surface);
    border-bottom: 1px solid var(--border);
    padding: 12px 24px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
  }
  .title-group { display: flex; align-items: center; gap: 12px; }
  .logo-badge {
    background: linear-gradient(135deg, #6366F1, #8B5CF6);
    color: #fff;
    font-weight: 800;
    font-size: 11px;
    padding: 4px 8px;
    border-radius: 6px;
    letter-spacing: 0.5px;
  }
  h1 { font-size: 16px; font-weight: 700; letter-spacing: -0.2px; }
  .stats-bar { display: flex; gap: 18px; font-size: 13px; font-family: monospace; }
  .stat-chip {
    background: var(--bg-card);
    padding: 4px 10px;
    border-radius: 6px;
    border: 1px solid var(--border);
    display: flex;
    gap: 6px;
  }
  .stat-label { color: var(--text-secondary); }
  .stat-val { color: var(--green); font-weight: 700; }

  /* Toolbar */
  .toolbar {
    background-color: var(--bg-surface);
    border-bottom: 1px solid var(--border);
    padding: 8px 24px;
    display: flex;
    align-items: center;
    gap: 20px;
    flex-shrink: 0;
  }
  .btn-primary {
    background: var(--accent);
    color: #fff;
    border: none;
    padding: 8px 16px;
    border-radius: 6px;
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 8px;
    transition: 0.15s;
  }
  .btn-primary:hover { background: #4F46E5; }
  .control-group {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--text-secondary);
  }
  .control-group input[type="range"] {
    width: 90px;
    accent-color: var(--accent);
    cursor: pointer;
  }
  .control-group span.val {
    font-family: monospace;
    font-weight: 600;
    color: var(--text-primary);
    width: 32px;
  }
  .toggle-label {
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
    font-size: 12px;
    user-select: none;
  }

  /* Main Workspace */
  .workspace {
    flex: 1;
    display: flex;
    overflow: hidden;
  }
  .viewport-pane {
    flex: 1;
    background: #06080C;
    position: relative;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: grab;
  }
  .viewport-pane:active { cursor: grabbing; }

  .empty-state {
    position: absolute;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: var(--text-secondary);
    border: 2px dashed var(--border);
    padding: 48px;
    border-radius: 12px;
    background: rgba(21, 25, 34, 0.4);
    cursor: pointer;
    transition: 0.2s;
  }
  .empty-state:hover {
    border-color: var(--accent);
    color: var(--text-primary);
  }

  #canvas-container {
    position: absolute;
    transform-origin: 0 0;
    transition: transform 0.05s ease-out;
  }
  #main-image { display: block; max-width: none; pointer-events: none; }
  #overlay-canvas {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    pointer-events: auto;
  }

  /* Inspector Sidebar */
  .sidebar-pane {
    width: 440px;
    background-color: var(--bg-surface);
    border-left: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .sidebar-header {
    padding: 12px 16px;
    border-bottom: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .sidebar-header h2 { font-size: 14px; font-weight: 700; }
  .btn-subtle {
    background: var(--bg-card);
    border: 1px solid var(--border);
    color: var(--text-primary);
    padding: 4px 10px;
    border-radius: 4px;
    font-size: 11px;
    cursor: pointer;
  }
  .btn-subtle:hover { border-color: var(--accent); }

  .lines-scroll-list {
    flex: 1;
    overflow-y: auto;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .line-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    transition: 0.15s;
    cursor: pointer;
  }
  .line-card:hover, .line-card.active {
    border-color: var(--accent);
    box-shadow: 0 0 12px var(--accent-glow);
  }
  .card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
  }
  .badge-id {
    background: #374151;
    color: #F3F4F6;
    font-weight: 700;
    padding: 2px 6px;
    border-radius: 4px;
  }
  .badge-orient {
    padding: 2px 6px;
    border-radius: 4px;
    font-weight: 600;
  }
  .badge-orient.vert { background: rgba(245, 158, 11, 0.15); color: #FBBF24; }
  .badge-orient.horiz { background: rgba(99, 102, 241, 0.15); color: #A5B4FC; }

  .card-body {
    display: flex;
    gap: 12px;
    align-items: center;
  }
  .crop-preview {
    height: 40px;
    max-width: 120px;
    background: #000;
    border: 1px solid #374151;
    border-radius: 4px;
    object-fit: contain;
  }
  .card-text {
    flex: 1;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0.5px;
    color: #FFFFFF;
    word-break: break-all;
    line-height: 1.4;
  }
  .copy-btn {
    opacity: 0.6;
    cursor: pointer;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    padding: 4px;
    transition: 0.1s;
  }
  .copy-btn:hover { opacity: 1; color: var(--text-primary); }

  /* Zoom controls */
  .zoom-dock {
    position: absolute;
    bottom: 20px;
    left: 20px;
    background: rgba(21, 25, 34, 0.85);
    backdrop-filter: blur(8px);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 4px;
    display: flex;
    gap: 4px;
  }
  .zoom-dock button {
    background: transparent;
    border: none;
    color: var(--text-primary);
    width: 28px;
    height: 28px;
    border-radius: 4px;
    cursor: pointer;
    font-weight: 700;
  }
  .zoom-dock button:hover { background: var(--bg-card); }
</style>
</head>
<body>

<header>
  <div class="title-group">
    <span class="logo-badge">VIBE ARCH</span>
    <h1>PP-OCRv6 Manga v0.2 Diagnostic Workbench</h1>
  </div>
  <div class="stats-bar">
    <div class="stat-chip"><span class="stat-label">Lines:</span><span id="stat-lines" class="stat-val">0</span></div>
    <div class="stat-chip"><span class="stat-label">Det:</span><span id="stat-det" class="stat-val">0 ms</span></div>
    <div class="stat-chip"><span class="stat-label">Rec:</span><span id="stat-rec" class="stat-val">0 ms</span></div>
    <div class="stat-chip"><span class="stat-label">Total:</span><span id="stat-total" class="stat-val">0 ms</span></div>
  </div>
</header>

<div class="toolbar">
  <input type="file" id="file-input" accept="image/*" style="display:none">
  <button class="btn-primary" onclick="document.getElementById('file-input').click()">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
    Select Image
  </button>

  <div class="control-group">
    <label>Binarize Thresh:</label>
    <input type="range" id="param-thresh" min="0.05" max="0.50" step="0.01" value="0.15" oninput="updateParamLabel('thresh')">
    <span class="val" id="val-thresh">0.15</span>
  </div>

  <div class="control-group">
    <label>Box Thresh:</label>
    <input type="range" id="param-box" min="0.10" max="0.60" step="0.01" value="0.25" oninput="updateParamLabel('box')">
    <span class="val" id="val-box">0.25</span>
  </div>

  <div class="control-group">
    <label>Unclip Ratio:</label>
    <input type="range" id="param-unclip" min="1.0" max="2.5" step="0.05" value="1.4" oninput="updateParamLabel('unclip')">
    <span class="val" id="val-unclip">1.4</span>
  </div>

  <button class="btn-subtle" onclick="rerunOcr()">Re-run OCR</button>

  <div style="flex:1"></div>

  <label class="toggle-label">
    <input type="checkbox" id="chk-boxes" checked onchange="renderOverlay()">
    <span>Boxes</span>
  </label>
  <label class="toggle-label">
    <input type="checkbox" id="chk-badges" checked onchange="renderOverlay()">
    <span>Badges</span>
  </label>
</div>

<div class="workspace">
  <div class="viewport-pane" id="viewport">
    <div class="empty-state" id="empty-state" onclick="document.getElementById('file-input').click()">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
      <div style="font-weight:600">Drop an image here or click to select</div>
      <div style="font-size:12px;opacity:0.7">Supports Manga, Manhua, Webtoon strips, Raw speech bubbles</div>
    </div>

    <div id="canvas-container">
      <img id="main-image">
      <canvas id="overlay-canvas"></canvas>
    </div>

    <div class="zoom-dock">
      <button onclick="zoomBy(1.2)" title="Zoom In">+</button>
      <button onclick="zoomBy(0.8)" title="Zoom Out">-</button>
      <button onclick="resetZoom()" title="Reset Zoom">Fit</button>
    </div>
  </div>

  <div class="sidebar-pane">
    <div class="sidebar-header">
      <h2>Extracted Text Lines (<span id="line-count">0</span>)</h2>
      <button class="btn-subtle" onclick="copyAllText()">Copy All Text</button>
    </div>
    <div class="lines-scroll-list" id="lines-list">
      <div style="color:var(--text-secondary);font-size:13px;text-align:center;margin-top:40px;">
        No image loaded yet. Pick an image to run PP-OCRv6 manga recognition.
      </div>
    </div>
  </div>
</div>

<script>
  let currentFile = null;
  let ocrData = null;
  let scale = 1.0;
  let panX = 0, panY = 0;
  let isPanning = false;
  let startX = 0, startY = 0;
  let activeIndex = -1;

  const viewport = document.getElementById('viewport');
  const canvasContainer = document.getElementById('canvas-container');
  const mainImage = document.getElementById('main-image');
  const overlayCanvas = document.getElementById('overlay-canvas');
  const emptyState = document.getElementById('empty-state');
  const linesList = document.getElementById('lines-list');

  function updateParamLabel(key) {
    document.getElementById('val-' + key).textContent = document.getElementById('param-' + key).value;
  }

  // File picker handler
  document.getElementById('file-input').addEventListener('change', e => {
    if (e.target.files.length > 0) {
      loadFile(e.target.files[0]);
    }
  });

  // Drag & drop
  viewport.addEventListener('dragover', e => { e.preventDefault(); viewport.style.borderColor = '#6366F1'; });
  viewport.addEventListener('dragleave', e => { e.preventDefault(); viewport.style.borderColor = 'transparent'; });
  viewport.addEventListener('drop', e => {
    e.preventDefault();
    if (e.dataTransfer.files.length > 0) {
      loadFile(e.dataTransfer.files[0]);
    }
  });

  function loadFile(file) {
    currentFile = file;
    const reader = new FileReader();
    reader.onload = e => {
      mainImage.src = e.target.result;
      mainImage.onload = () => {
        emptyState.style.display = 'none';
        resetZoom();
        runOcr(e.target.result);
      };
    };
    reader.readAsDataURL(file);
  }

  function rerunOcr() {
    if (mainImage.src) {
      runOcr(mainImage.src);
    }
  }

  async function runOcr(dataUrl) {
    document.getElementById('lines-list').innerHTML = `
      <div style="color:var(--text-secondary);font-size:13px;text-align:center;margin-top:40px;">
        Running PP-OCRv6 Manga v0.2 inference...
      </div>`;

    const payload = {
      image: dataUrl,
      thresh: parseFloat(document.getElementById('param-thresh').value),
      box_thresh: parseFloat(document.getElementById('param-box').value),
      unclip_ratio: parseFloat(document.getElementById('param-unclip').value),
    };

    try {
      const res = await fetch('/api/ocr', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      ocrData = data;

      document.getElementById('stat-lines').textContent = data.lines.length;
      document.getElementById('stat-det').textContent = data.timing.det_ms + ' ms';
      document.getElementById('stat-rec').textContent = data.timing.rec_ms + ' ms';
      document.getElementById('stat-total').textContent = data.timing.total_ms + ' ms';
      document.getElementById('line-count').textContent = data.lines.length;

      renderOverlay();
      renderSidebar();
    } catch (err) {
      alert('OCR Failed: ' + err);
    }
  }

  function renderOverlay() {
    if (!ocrData || !mainImage.naturalWidth) return;
    overlayCanvas.width = mainImage.naturalWidth;
    overlayCanvas.height = mainImage.naturalHeight;
    const ctx = overlayCanvas.getContext('2d');
    ctx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);

    const showBoxes = document.getElementById('chk-boxes').checked;
    const showBadges = document.getElementById('chk-badges').checked;

    ocrData.lines.forEach((line, idx) => {
      const isSelected = (idx === activeIndex);
      const pts = line.box;

      if (showBoxes) {
        ctx.beginPath();
        ctx.moveTo(pts[0][0], pts[0][1]);
        for (let i = 1; i < pts.length; i++) {
          ctx.lineTo(pts[i][0], pts[i][1]);
        }
        ctx.closePath();

        ctx.lineWidth = isSelected ? 4 : 2;
        ctx.strokeStyle = isSelected ? '#10B981' : (line.vertical ? '#F59E0B' : '#6366F1');
        ctx.fillStyle = isSelected ? 'rgba(16, 185, 129, 0.25)' : (line.vertical ? 'rgba(245, 158, 11, 0.12)' : 'rgba(99, 102, 241, 0.12)');
        ctx.fill();
        ctx.stroke();
      }

      if (showBadges) {
        const topX = Math.min(...pts.map(p => p[0]));
        const topY = Math.min(...pts.map(p => p[1]));
        ctx.fillStyle = isSelected ? '#10B981' : '#1F2937';
        ctx.fillRect(topX - 2, Math.max(0, topY - 18), 24, 18);
        ctx.fillStyle = '#FFFFFF';
        ctx.font = 'bold 11px sans-serif';
        ctx.fillText('#' + line.id, topX + 2, Math.max(12, topY - 5));
      }
    });
  }

  function renderSidebar() {
    if (!ocrData) return;
    linesList.innerHTML = '';

    if (ocrData.lines.length === 0) {
      linesList.innerHTML = '<div style="color:var(--text-secondary);text-align:center;margin-top:40px;">No text detected with current thresholds. Try reducing Binarize Thresh.</div>';
      return;
    }

    ocrData.lines.forEach((line, idx) => {
      const card = document.createElement('div');
      card.className = 'line-card' + (idx === activeIndex ? ' active' : '');
      card.id = 'card-' + idx;
      card.onclick = () => {
        activeIndex = idx;
        renderOverlay();
        document.querySelectorAll('.line-card').forEach(c => c.classList.remove('active'));
        card.classList.add('active');
      };

      card.innerHTML = `
        <div class="card-top">
          <span class="badge-id">#${line.id}</span>
          <span class="badge-orient ${line.vertical ? 'vert' : 'horiz'}">${line.vertical ? 'Vertical (90° CCW)' : 'Horizontal'}</span>
          <span style="color:var(--green);font-weight:600">${Math.round(line.rec_conf * 100)}%</span>
          <span style="color:var(--text-secondary)">${line.width}×${line.height}</span>
        </div>
        <div class="card-body">
          <img class="crop-preview" src="${line.crop}" title="Rec Input Crop">
          <div class="card-text">${escapeHtml(line.text || '(empty)')}</div>
          <button class="copy-btn" onclick="copyText('${escapeHtml(line.text)}', event)" title="Copy line">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
        </div>
      `;
      linesList.appendChild(card);
    });
  }

  function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function copyText(str, e) {
    if (e) e.stopPropagation();
    navigator.clipboard.writeText(str);
  }

  function copyAllText() {
    if (!ocrData) return;
    const all = ocrData.lines.map(l => l.text).filter(t => t.length > 0).join('\\n');
    navigator.clipboard.writeText(all);
    alert('Copied all ' + ocrData.lines.length + ' lines to clipboard!');
  }

  // Pan & Zoom
  function updateTransform() {
    canvasContainer.style.transform = `translate(${panX}px, ${panY}px) scale(${scale})`;
  }

  function resetZoom() {
    if (!mainImage.naturalWidth) return;
    const pw = viewport.clientWidth;
    const ph = viewport.clientHeight;
    const iw = mainImage.naturalWidth;
    const ih = mainImage.naturalHeight;
    scale = Math.min((pw - 40) / iw, (ph - 40) / ih, 1.0);
    panX = (pw - iw * scale) / 2;
    panY = (ph - ih * scale) / 2;
    updateTransform();
  }

  function zoomBy(factor) {
    scale = Math.max(0.1, Math.min(10.0, scale * factor));
    updateTransform();
  }

  viewport.addEventListener('wheel', e => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.15 : 0.85;
    zoomBy(factor);
  });

  viewport.addEventListener('mousedown', e => {
    isPanning = true;
    startX = e.clientX - panX;
    startY = e.clientY - panY;
  });

  window.addEventListener('mousemove', e => {
    if (!isPanning) return;
    panX = e.clientX - startX;
    panY = e.clientY - startY;
    updateTransform();
  });

  window.addEventListener('mouseup', () => { isPanning = false; });
</script>

</body>
</html>
"""

# ---------------------------------------------------------
# HTTP Server
# ---------------------------------------------------------
class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class OcrRequestHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # Silence verbose console request logging

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/index"):
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
                "vocab_size": len(OCR_ENGINE.vocab) if OCR_ENGINE else 0
            }
            self.wfile.write(json.dumps(resp).encode("utf-8"))
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        if self.path == "/api/ocr":
            content_length = int(self.headers.get("Content-Length", 0))
            post_data = self.rfile.read(content_length)
            try:
                data = json.loads(post_data.decode("utf-8"))
                img_data_uri = data.get("image", "")
                thresh = float(data.get("thresh", 0.15))
                box_thresh = float(data.get("box_thresh", 0.25))
                unclip_ratio = float(data.get("unclip_ratio", 1.4))

                if "," in img_data_uri:
                    img_data_uri = img_data_uri.split(",", 1)[1]
                img_bytes = base64.b64decode(img_data_uri)
                nparr = np.frombuffer(img_bytes, np.uint8)
                img_bgr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

                if img_bgr is None:
                    raise ValueError("Failed to decode image")

                result = OCR_ENGINE.process(img_bgr, thresh=thresh, box_thresh=box_thresh, unclip_ratio=unclip_ratio)
                
                resp_bytes = json.dumps(result).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp_bytes)))
                self.end_headers()
                self.wfile.write(resp_bytes)
            except Exception as e:
                import traceback
                traceback.print_exc()
                err_msg = json.dumps({"error": str(e)}).encode("utf-8")
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(err_msg)
        else:
            self.send_error(404, "Not Found")

# ---------------------------------------------------------
# Main Entry Point
# ---------------------------------------------------------
def main():
    global OCR_ENGINE
    print("=" * 65)
    print("  PP-OCRv6 Manga v0.2 Visual Diagnostic Workbench")
    print("=" * 65)
    
    if not os.path.exists(DET_MODEL_PATH):
        print(f"[!] Error: Detector model not found at {DET_MODEL_PATH}")
        sys.exit(1)
    if not os.path.exists(REC_MODEL_PATH):
        print(f"[!] Error: Recognizer model not found at {REC_MODEL_PATH}")
        sys.exit(1)
    if not os.path.exists(DICT_PATH):
        print(f"[!] Error: Dictionary file not found at {DICT_PATH}")
        sys.exit(1)

    OCR_ENGINE = MangaOcrEngine(DET_MODEL_PATH, REC_MODEL_PATH, DICT_PATH)

    server = ThreadedHTTPServer(("127.0.0.1", PORT), OcrRequestHandler)
    url = f"http://127.0.0.1:{PORT}"
    print(f"\n[+] Desktop Diagnostic UI running at: {url}")
    print("[+] Opening browser automatically...")
    webbrowser.open(url)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Shutting down workbench server.")
        server.server_close()

if __name__ == "__main__":
    main()
