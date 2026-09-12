"""PaddleOCR v6 small (det + rec) — desktop port of the Android engines.

Mirrors, constant-for-constant, the app's own implementation so studio results
are meaningful for the on-device pipeline:
  * PaddleOcrV6DetEngine  (det preprocess 736 + ImageNet normalize + DB map)
  * DbPostProcess         (8-connectivity CC + line-fragment merge)
  * PaddleOcrV6SmallEngine (rec 48px height, width bucketing, mid-gray pad)
  * PaddleCtcDecoder      (blank=0, dict 1..N, space=N+1, mean-prob confidence)
  * VerticalLineOcr.recognizeDetColumns (manga reading order, CCW rotation,
    per-glyph vertical CJK reads)
Pure numpy + onnxruntime; no Android dependencies.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
PADDLE_DIR = (REPO_ROOT / "app/src/main/assets/models/ocr/paddle-v6-small")
DET_MODEL = PADDLE_DIR / "det" / "inference.onnx"
REC_MODEL = PADDLE_DIR / "inference.onnx"
REC_DICT = PADDLE_DIR / "PP-OCRv6_small_rec.txt"

# ---- DbPostProcess defaults (inference.yml of PP-OCRv6_small_det_onnx) ----
DB_THRESH = 0.2
DB_BOX_THRESH = 0.45
DB_MAX_CANDIDATES = 3000
DB_MIN_AREA_PX = 16
DB_MAX_COMPONENT_AREA_FRAC = 0.5
SAME_LINE_FRAC = 0.6
MERGE_GAP_FACTOR = 1.0
MERGE_MAX_PASSES = 3

# ---- det preprocess (PaddleOcrV6DetEngine) ----
DET_TARGET = 736
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], np.float32)

# ---- rec (PaddleOcrV6SmallEngine) ----
REC_HEIGHT = 48
BUCKET_WIDTH_SMALL = 640
MAX_RECOGNITION_WIDTH = 1600

# ---- VerticalLineOcr line assembly ----
MIN_DET_LINE_PX = 4
MAX_COLUMN_WIDTH_PX = 400
MAX_COLUMN_HEIGHT_PX = 800
OCR_MIN_CONFIDENCE = 0.5
INK_LUMINANCE_THRESHOLD = 110
COLUMN_GAP_INK_FRACTION = 0.02
MIN_COLUMN_GAP_PX = 10
MIN_COLUMN_WIDTH_PX = 12


# ================================================================== DB postprocess
def _merge_along_axis(lines, axis: str):
    """One pass of DbPostProcess.mergeAlongAxis (same thresholds)."""
    if not lines:
        return []
    if axis == "h":
        ordered = sorted(lines, key=lambda l: ((l[1] + l[3]) / 2, l[0]))
    else:
        ordered = sorted(lines, key=lambda l: ((l[0] + l[2]) / 2, l[1]))
    merged = []  # [x1, y1, x2, y2, score]
    for b in ordered:
        x1, y1, x2, y2, score = b
        if axis == "h":
            center, cross, gap_size = (y1 + y2) / 2, y2 - y1, y2 - y1
        else:
            center, cross, gap_size = (x1 + x2) / 2, x2 - x1, y2 - y1
        placed = False
        for m in merged:
            if axis == "h":
                m_center, m_cross, m_gap = (m[1] + m[3]) / 2, m[3] - m[1], m[3] - m[1]
            else:
                m_center, m_cross, m_gap = (m[0] + m[2]) / 2, m[2] - m[0], m[3] - m[1]
            if abs(center - m_center) > SAME_LINE_FRAC * min(cross, m_cross):
                continue
            if axis == "h":
                gap = max(0, max(x1, m[0]) - min(x2, m[2]))
            else:
                gap = max(0, max(y1, m[1]) - min(y2, m[3]))
            if gap <= MERGE_GAP_FACTOR * max(gap_size, m_gap):
                m[0] = min(m[0], x1); m[1] = min(m[1], y1)
                m[2] = max(m[2], x2); m[3] = max(m[3], y2)
                m[4] = max(m[4], score)
                placed = True
                break
        if not placed:
            merged.append([x1, y1, x2, y2, score])
    return merged


def merge_line_fragments(lines):
    """DbPostProcess.mergeLineFragments: wide boxes merge along rows, tall
    boxes along columns, to a fixed point (max 3 passes)."""
    if len(lines) < 2:
        return lines
    current = lines
    for _ in range(MERGE_MAX_PASSES):
        horiz = [b for b in current if (b[2] - b[0]) >= (b[3] - b[1])]
        vert = [b for b in current if (b[2] - b[0]) < (b[3] - b[1])]
        nxt = _merge_along_axis(horiz, "h") + _merge_along_axis(vert, "v")
        if len(nxt) == len(current):
            break
        current = nxt
    return current


def db_post_process(prob_map: np.ndarray, thresh: float = DB_THRESH,
                    box_thresh: float = DB_BOX_THRESH) -> list[list]:
    """Prob map [H,W] -> merged text-line boxes [[x1,y1,x2,y2,score],...] in
    MAP space (caller back-projects). Mirrors DbPostProcess.detectLines;
    thresholds are parameters so the AOT inpaint path can pass its own
    (PADDLE_THRESH=0.18 / PADDLE_BOX_THRESH=0.34, AOTInpainting.kt)."""
    h, w = prob_map.shape
    binary = prob_map > thresh
    labels = np.zeros((h, w), np.int32)
    comps = []  # (minx, miny, maxx, maxy, count, prob_sum)
    for sy in range(h):
        row = binary[sy]
        for sx in range(w):
            if not row[sx] or labels[sy, sx] != 0:
                continue
            # iterative 8-connectivity flood fill
            stack = [(sx, sy)]
            labels[sy, sx] = 1
            minx, miny, maxx, maxy = sx, sy, sx, sy
            count, prob_sum = 0, 0.0
            while stack:
                x, y = stack.pop()
                count += 1
                prob_sum += float(prob_map[y, x])
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
                x0, x1c = max(0, x - 1), min(w - 1, x + 1)
                y0, y1c = max(0, y - 1), min(h - 1, y + 1)
                for ny in range(y0, y1c + 1):
                    for nx in range(x0, x1c + 1):
                        if binary[ny, nx] and labels[ny, nx] == 0:
                            labels[ny, nx] = 1
                            stack.append((nx, ny))
            if count >= DB_MIN_AREA_PX:
                comps.append((minx, miny, maxx, maxy, count, prob_sum))
            if len(comps) >= DB_MAX_CANDIDATES:
                break
        if len(comps) >= DB_MAX_CANDIDATES:
            break

    out = []
    for minx, miny, maxx, maxy, count, prob_sum in comps:
        mean_score = prob_sum / count
        if mean_score < box_thresh:
            continue
        area = (maxx - minx + 1) * (maxy - miny + 1)
        if area > DB_MAX_COMPONENT_AREA_FRAC * w * h:
            continue
        out.append([minx, miny, maxx, maxy, mean_score])
    out = merge_line_fragments(out)
    for b in out:
        b[0] = int(np.clip(b[0], 0, w - 1)); b[1] = int(np.clip(b[1], 0, h - 1))
        b[2] = int(np.clip(b[2], 0, w - 1)); b[3] = int(np.clip(b[3], 0, h - 1))
    out.sort(key=lambda b: (b[1], b[0]))
    return out


# ===================================================================== engines
class PaddleDet:
    """PP-OCRv6 small det: longer-side 736 resize + black pad, ImageNet
    normalize, [1,1,736,736] prob map, active-region crop, back-projection."""

    def __init__(self, path: Path = DET_MODEL):
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.log_severity_level = 3
        self.sess = ort.InferenceSession(str(path), opts,
                                         providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name

    def detect_lines(self, crop: Image.Image, thresh: float = DB_THRESH,
                     box_thresh: float = DB_BOX_THRESH) -> list[list]:
        """-> [[x1,y1,x2,y2,score], ...] in CROP pixel coords (manga reading
        order comes later, in assemble_region_text). Mirrors the Android
        detectLines(crop, thresh, boxThresh) signature: the AOT inpaint path
        calls it with thresh=0.18 / boxThresh=0.34 (AOTInpainting.kt)."""
        w, h = crop.size
        scale = DET_TARGET / float(max(w, h))
        rw = max(1, min(DET_TARGET, int(round(w * scale))))
        rh = max(1, min(DET_TARGET, int(round(h * scale))))
        resized = np.asarray(crop.resize((rw, rh), Image.BILINEAR), np.float32)
        padded = np.zeros((DET_TARGET, DET_TARGET, 3), np.float32)  # black pad
        padded[:rh, :rw] = resized
        x = (padded / 255.0 - IMAGENET_MEAN) / IMAGENET_STD
        x = np.ascontiguousarray(x.transpose(2, 0, 1))[None]
        prob = self.sess.run(None, {self.input_name: x})[0][0, 0]  # [736,736]
        active = prob[:rh, :rw]                     # only the pre-pad region
        sx, sy = w / float(rw), h / float(rh)       # map -> crop scale
        lines = []
        for bx1, by1, bx2, by2, score in db_post_process(active, thresh, box_thresh):
            x1 = int(np.clip(bx1 * sx, 0, w - 1)); y1 = int(np.clip(by1 * sy, 0, h - 1))
            x2 = int(np.clip(bx2 * sx, 0, w - 1)); y2 = int(np.clip(by2 * sy, 0, h - 1))
            if x2 < x1: x1, x2 = x2, x1
            if y2 < y1: y1, y2 = y2, y1
            lines.append([x1, y1, x2, y2, float(score)])
        return lines


class PaddleRec:
    """PP-OCRv6 small rec: 48px height, width bucket 640/1600, mid-gray pad,
    (v/255-.5)/.5 normalize, CTC decode (blank=0, dict 1..N, space=N+1)."""

    def __init__(self, model: Path = REC_MODEL, dictionary: Path = REC_DICT):
        import onnxruntime as ort
        self.dictionary = dictionary.read_text(encoding="utf-8").splitlines()
        opts = ort.SessionOptions()
        opts.log_severity_level = 3
        self.sess = ort.InferenceSession(str(model), opts,
                                         providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name

    def _align_width(self, width: int) -> int:
        return BUCKET_WIDTH_SMALL if width <= BUCKET_WIDTH_SMALL \
            else MAX_RECOGNITION_WIDTH

    def recognize(self, crop: Image.Image) -> tuple[str, float]:
        w = max(1, crop.width)
        h = max(1, crop.height)
        scaled_w = int(math.ceil(w * (REC_HEIGHT / float(h))))
        scaled_w = max(1, min(MAX_RECOGNITION_WIDTH, scaled_w))
        input_w = self._align_width(scaled_w)
        canvas = Image.new("RGB", (input_w, REC_HEIGHT), (128, 128, 128))
        canvas.paste(crop.resize((scaled_w, REC_HEIGHT), Image.BILINEAR),
                     (0, 0))
        arr = np.asarray(canvas, np.float32)
        x = (arr / 255.0 - 0.5) / 0.5
        x = np.ascontiguousarray(x.transpose(2, 0, 1))[None]
        out = self.sess.run(None, {self.input_name: x})[0]  # [1, T, C]
        return self._ctc_decode(out[0])

    def _ctc_decode(self, logits: np.ndarray) -> tuple[str, float]:
        """PaddleCtcDecoder.decodeWithConf: greedy argmax, ignore blank=0 and
        repeated tokens, dict index = token-1, dict size+1 = space.
        The ONNX graph already ends in softmax (verified: max 0.985), so the
        values ARE probabilities — no second softmax (that would flatten the
        confidence and the 0.5 filter would reject every line)."""
        n_dict = len(self.dictionary)
        space_index = n_dict + 1
        indices = np.argmax(logits, axis=1)
        probs = np.max(logits, axis=1)
        chars: list[str] = []
        confs: list[float] = []
        prev = 0
        for idx, p in zip(indices, probs):
            i = int(idx)
            if i != prev and i != 0:
                if i in range(1, n_dict + 1):
                    chars.append(self.dictionary[i - 1])
                elif i == space_index:
                    chars.append(" ")
                confs.append(float(p))
            prev = i
        text = "".join(chars)
        conf = sum(confs) / len(confs) if confs else 0.0
        return text, conf


# ============================================================== line assembly
def _detect_vertical_glyph_rows(crop: Image.Image) -> list[tuple[int, int]]:
    """VerticalLineOcr.detectVerticalGlyphRows: row ink-gap analysis."""
    w, h = crop.size
    if w < 2 or h < 2:
        return [(0, h)]
    lum = np.asarray(crop.convert("L"))
    ink = (lum < INK_LUMINANCE_THRESHOLD).mean(axis=1)
    rows, in_run, run_start, gap = [], False, 0, 0
    for y in range(h):
        if ink[y] >= COLUMN_GAP_INK_FRACTION:
            if not in_run:
                run_start, in_run = y, True
            gap = 0
        elif in_run:
            gap += 1
            if gap >= MIN_COLUMN_GAP_PX:
                end = y - gap
                if end - run_start >= MIN_COLUMN_WIDTH_PX:
                    rows.append((run_start, end))
                in_run, gap = False, 0
    if in_run:
        end = h - gap if gap > 0 else h
        if end - run_start >= MIN_COLUMN_WIDTH_PX:
            rows.append((run_start, end))
    return rows


def assemble_region_text(det: PaddleDet, rec: PaddleRec,
                         crop: Image.Image) -> tuple[str, list[dict]]:
    """VerticalLineOcr.recognizeDetColumns for Japanese:
    det lines -> manga reading order (vertical cols right-to-left first, then
    horizontal top-to-bottom) -> per-glyph CCW-rotated rec -> join('')."""
    lines = det.detect_lines(crop)
    items = []
    for b in lines:
        x1, y1, x2, y2, score = b
        bw, bh = x2 - x1, y2 - y1
        if bw < MIN_DET_LINE_PX or bh < MIN_DET_LINE_PX:
            continue
        if bw > MAX_COLUMN_WIDTH_PX or bh > MAX_COLUMN_HEIGHT_PX:
            continue
        vertical = bh > bw * 1.5
        key = -((x1 + x2) / 2) if vertical else (y1 + y2) / 2
        items.append((b, vertical, key))
    items.sort(key=lambda it: it[2])

    def read(img: Image.Image) -> str:
        text, conf = rec.recognize(img)
        if conf < OCR_MIN_CONFIDENCE and conf < 1.0:
            return ""
        return text

    parts, line_info = [], []
    for b, vertical, _key in items:
        x1, y1, x2, y2, score = b
        cell = crop.crop((x1, y1, x2, y2))
        if vertical:
            rows = _detect_vertical_glyph_rows(cell)
            if len(rows) <= 1:
                text = read(_rotate_ccw(cell))
            else:
                glyphs = []
                for (gy0, gy1) in rows:
                    if gy1 - gy0 < MIN_COLUMN_WIDTH_PX:
                        continue
                    g = cell.crop((0, gy0, cell.width, gy1))
                    t = read(_rotate_ccw(g))
                    if t:
                        glyphs.append(t)
                text = "".join(glyphs)
        else:
            text = read(cell)
        if text:
            parts.append(text)
            line_info.append({"bbox": [x1, y1, x2, y2], "vertical": vertical,
                              "score": round(score, 3), "text": text})
    return "".join(parts), line_info


def _rotate_ccw(img: Image.Image) -> Image.Image:
    """Android postRotate(-90): 90 degrees counterclockwise."""
    return img.transpose(Image.ROTATE_90)
