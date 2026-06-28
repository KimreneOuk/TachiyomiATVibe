from __future__ import annotations

import base64
import io
import math
import time
from dataclasses import dataclass
from pathlib import Path

try:
    import cv2
    OPENCV_AVAILABLE = True
except ImportError:
    OPENCV_AVAILABLE = False

import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageDraw, ImageFilter, ImageOps, ImageChops, ImageFont
import urllib.request
import json

import cci_inpaint
import text_render

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "models"
DETECTOR_MODEL = ASSETS / "detection" / "detector-v4-s_int8.onnx"
PADDLE_DET_MODEL = ASSETS / "ocr" / "paddle-v6-small" / "det" / "inference.onnx"
PADDLE_REC_MODEL = ASSETS / "ocr" / "paddle-v6-small" / "inference.onnx"
PADDLE_REC_DICT = ASSETS / "ocr" / "paddle-v6-small" / "PP-OCRv6_small_rec.txt"
AOT_MODEL = ASSETS / "inpainting" / "aot.onnx"

DETECTOR_THRESHOLD = 0.45
PADDLE_THRESH = 0.2
PADDLE_BOX_THRESH = 0.34
PADDLE_REC_CONFIDENCE = 0.5
PADDLE_TARGET = 736
MIN_DB_AREA = 16

# Free-text (legacy) inpaint tuning. LOCAL color (ring around the text) replaces
# the old global page median that kept free text "stuck on white".
FREE_RING = 8           # annulus half-width (px) sampled for the local bg color
FREE_TEXT_FEATHER = 3   # light bleed-free feather radius for free text

# Vertical-CJK source languages — direction is "TTB" for tall boxes in these
# (mirrors RoiPageRecognitionEngine.kt:308-310,403 isVerticalLanguage rule).
VERTICAL_LANGS = {"japanese", "chinese", "chinese simplified", "chinese traditional", "korean"}

# Inpaint-stage Paddle refine constants — Android AOTInpainting.kt:37-40 re-runs
# Paddle DET on free-text boxes during INPAINT (separate from the detect stage)
# to recover the true line boxes for the erase mask. Detect-stage boxes used the
# detect thresh above; the inpaint stage uses these (AOTInpainting.kt:38-40).
PADDLE_INPAINT_THRESH = 0.18
PADDLE_INPAINT_BOX_THRESH = 0.34
PADDLE_INPAINT_CROP_PAD = 12
FREE_TEXT_REFINE_PAD = 4
# AOTInpainting.kt:47 — distance-field feather ramp (px) for the NEURAL blend.
FEATHER_RAMP_PX = 12

app = FastAPI()
app.mount("/static", StaticFiles(directory=Path(__file__).parent), name="static")

detector_session: ort.InferenceSession | None = None
paddle_session: ort.InferenceSession | None = None
paddle_rec_session: ort.InferenceSession | None = None
paddle_rec_chars: list[str] = []
aot_session: ort.InferenceSession | None = None


@dataclass
class TextLine:
    bbox: list[int]
    mean_score: float


def get_detector() -> ort.InferenceSession:
    global detector_session
    if detector_session is None:
        detector_session = ort.InferenceSession(str(DETECTOR_MODEL), providers=["CPUExecutionProvider"])
    return detector_session


def get_paddle() -> ort.InferenceSession:
    global paddle_session
    if paddle_session is None:
        paddle_session = ort.InferenceSession(str(PADDLE_DET_MODEL), providers=["CPUExecutionProvider"])
    return paddle_session

def get_paddle_rec() -> ort.InferenceSession:
    global paddle_rec_session, paddle_rec_chars
    if paddle_rec_session is None:
        paddle_rec_session = ort.InferenceSession(str(PADDLE_REC_MODEL), providers=["CPUExecutionProvider"])
        with open(PADDLE_REC_DICT, "r", encoding="utf-8") as f:
            paddle_rec_chars = [line.strip("\n") for line in f.readlines()]
    return paddle_rec_session

def paddle_align_width(width: int) -> int:
    MAX_RECOGNITION_WIDTH = 960
    MIN_TARGET_WIDTH = 320
    WIDTH_ALIGNMENT = 16
    floored = max(width, MIN_TARGET_WIDTH)
    aligned = ((floored + WIDTH_ALIGNMENT - 1) // WIDTH_ALIGNMENT) * WIDTH_ALIGNMENT
    return min(aligned, MAX_RECOGNITION_WIDTH)

def ctc_decode_indices(preds_idx, dict_chars: list[str]) -> str:
    """Pure decode: blank at index 0, chars at 1..len(dict), space at len(dict)+1.
    Mirrors PaddleCtcDecoder.kt (BLANK_INDEX=0, spaceIndex=dictionary.size+1)."""
    BLANK_IDX = 0
    decoder_table = ["__BLANK__"] + dict_chars + [" "]
    text = ""
    prev = -1
    for idx in preds_idx:
        if idx != BLANK_IDX and idx != prev:
            if idx < len(decoder_table):
                text += decoder_table[idx]
        prev = idx
    return text

def ctc_decode_with_conf(preds_idx, preds_prob, dict_chars: list[str]) -> tuple[str, float]:
    """Decode CTC with recognition confidence.
    Confidence = mean max-prob of non-blank, non-duplicate, non-space timesteps.
    Mirrors PaddleOCR CTCLabelDecode (confidence excludes blank, dup, and space)."""
    BLANK_IDX = 0
    SPACE_IDX = len(dict_chars) + 1
    decoder_table = ["__BLANK__"] + dict_chars + [" "]
    text = ""
    confs: list[float] = []
    prev = -1
    for i, idx in enumerate(preds_idx):
        if idx != BLANK_IDX and idx != prev:
            if idx < len(decoder_table):
                text += decoder_table[idx]
                if idx != SPACE_IDX:
                    confs.append(float(preds_prob[i]))
        prev = idx
    confidence = float(np.mean(confs)) if confs else 0.0
    return text, confidence

def run_paddle_rec(crop_rgb: np.ndarray) -> tuple[str, float]:
    session = get_paddle_rec()
    h, w = crop_rgb.shape[:2]

    # If it's a vertical text box (typical for manga), rotate it 90 degrees
    # COUNTER-clockwise because PaddleOCR is trained on horizontal text lines.
    if h > w * 1.5:
        crop_rgb = np.rot90(crop_rgb, k=1)
        h, w = crop_rgb.shape[:2]

    RECOGNITION_HEIGHT = 48
    scaled_w = max(1, min(960, math.ceil(w * RECOGNITION_HEIGHT / h)))
    input_w = paddle_align_width(scaled_w)
    # Resize to (scaled_w, 48)
    img = Image.fromarray(crop_rgb).resize((scaled_w, RECOGNITION_HEIGHT), Image.Resampling.BILINEAR)
    # Pad to (input_w, 48) with gray 128 (normalizes to 0.0, matching Android's PAD_GRAY)
    padded = Image.new("RGB", (input_w, RECOGNITION_HEIGHT), (128, 128, 128))
    padded.paste(img, (0, 0))
    img_arr = np.array(padded).astype(np.float32) / 255.0
    img_arr = (img_arr - 0.5) / 0.5
    img_arr = np.transpose(img_arr, (2, 0, 1))
    img_arr = np.expand_dims(img_arr, axis=0)
    ort_inputs = {session.get_inputs()[0].name: img_arr}
    preds = session.run(None, ort_inputs)[0]
    preds_idx = preds.argmax(axis=2)[0]
    preds_prob = preds.max(axis=2)[0]

    return ctc_decode_with_conf(preds_idx, preds_prob, paddle_rec_chars)

import re

# CJK script code point ranges (mirrors NumberedLineResponseParser.kt)
_CJK_RANGES = [
    (0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0x20000, 0x2A6DF),
    (0x2A700, 0x2B73F), (0x2B740, 0x2B81F), (0xF900, 0xFAFF),
    (0x2F800, 0x2FA1F), (0x3000, 0x303F), (0x3040, 0x309F),
    (0x30A0, 0x30FF), (0x31F0, 0x31FF), (0xAC00, 0xD7AF),
    (0xFF00, 0xFFEF), (0xFE30, 0xFE4F),
]

def _is_cjk(cp: int) -> bool:
    return any(lo <= cp <= hi for lo, hi in _CJK_RANGES)

def contains_cjk(text: str) -> bool:
    return any(_is_cjk(ord(ch)) for ch in text)

_NUMBERED_LINE_RE = re.compile(r'^\[(\d+)\][ \t]*(.+)$', re.MULTILINE)

def parse_numbered_lines(raw: str, expected_count: int, allow_cjk: bool = True) -> list[str]:
    result: dict[int, str] = {}
    for match in _NUMBERED_LINE_RE.finditer(raw):
        idx = int(match.group(1))
        text = match.group(2).strip()
        if idx < 0 or idx >= expected_count:
            continue
        if not text:
            continue
        if not allow_cjk and contains_cjk(text):
            continue
        if idx in result:
            continue
        result[idx] = text
    return [result.get(i, "") for i in range(expected_count)]

# OCR artifact patterns from OcrArtifactSanitizer.kt
_OCR_ARTIFACT_RE = re.compile(r'N[0°º]|Ｎ０|№')

def ocr_artifact_sanitize(text: str) -> str:
    return _OCR_ARTIFACT_RE.sub('', text)

def translate_batch(
    texts: list[str],
    lm_url: str,
    lm_model: str,
    max_tokens: int = 8192,
    from_lang: str = "Japanese",
    to_lang: str = "English",
) -> list[str]:
    if not texts:
        return []

    formatted_texts = "\n".join(f"[{i}] {t}" for i, t in enumerate(texts))

    system_prompt = f"""You are an expert manga/comic translator and localization specialist. Translate the following list of sequential text blocks from {from_lang} to {to_lang}.

CRITICAL GUIDELINES:
1. READING ORDER: The sequential blocks are loosely ordered based on physical coordinates (Top-to-Bottom, then Right-to-Left for manga). However, complex comic panel layouts mean this numbering is just a nudge. Use your narrative judgment to connect dialogue logically across adjacent speech bubbles if the numbered sequence seems slightly out of order.
2. HONORIFICS: Honorifics (-san, -kun, -chan, -sama, -senpai, etc.) are highly expressive of character relationships. Preserve them natively (e.g., 'Taro-kun') if the tone is character-driven/anime-style, or translate them to natural relational equivalents if a more conventional western localization is appropriate.
3. BUBBLE SIZE & CONCISENESS: Manga speech bubbles have very limited space. Keep translations concise, natural, and close to the original length.
4. STYLE & TONE: Adapt register, slang, and dialect to fit character personalities. For sound effects (SFX) / onomatopoeia, provide standard comic-styled localized equivalents.
5. OCR ARTIFACTS: The source text comes from OCR and may contain misread glyphs such as "N0", "N°", "Nº", "№", or "Ｎ０". These are NOT meaningful — they are scanner misreads of Japanese characters like の. Do NOT preserve or translate them literally. Simply omit them and translate the intended meaning naturally.
6. NO EXTRA TEXT: Output ONLY the translations in the exact numbered format below, one block per line. Do not include explanations, notes, or preambles.
7. SCRIPT FIDELITY: If the target language uses Latin script, do NOT output Japanese/Chinese/Korean characters. Localize sound-effect parentheses like (笑) to "lol", "(laugh)", or an equivalent in the target language.

Format:
[index] translation"""

    try:
        url = f"{lm_url.rstrip('/')}/chat/completions"
        headers = {"Content-Type": "application/json"}
        data = {
            "model": lm_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": f"Translate these {from_lang} text blocks to {to_lang}:\n\n{formatted_texts}"}
            ],
            "temperature": 0.3,
            "max_tokens": max_tokens,
        }
        req = urllib.request.Request(url, data=json.dumps(data).encode("utf-8"), headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.loads(response.read().decode())
            content = result["choices"][0]["message"]["content"].strip()

            allow_cjk = to_lang.lower() in ("japanese", "japanese", "korean", "chinese", "chinese simplified", "chinese traditional")
            translations = parse_numbered_lines(content, len(texts), allow_cjk=allow_cjk)

            # Apply OCR artifact sanitization to each translation (mirrors OcrArtifactSanitizer)
            for i in range(len(translations)):
                if translations[i]:
                    translations[i] = ocr_artifact_sanitize(translations[i])

            # Remove watermark blocks (mirrors TranslationBlockFilters.removeWatermarkBlocks)
            TRANSLATED_PREFIX = "[Translated] "
            for i in range(len(translations)):
                t = translations[i].strip()
                if not t:
                    continue
                # Strip any "[Translated] " prefix that some models emit
                if t.startswith(TRANSLATED_PREFIX):
                    t = t[len(TRANSLATED_PREFIX):]
                # Check for RTMTH watermark
                if t.upper() == "RTMTH" or t == "RTMTH":
                    translations[i] = ""

            return translations
    except Exception as e:
        print("Translation error:", e)
        if "WinError 10061" in str(e) or "ConnectionRefused" in str(e):
            return ["[LM Studio Connection Refused]"] * len(texts)
        return ["[Translation Failed]"] * len(texts)

def render_translated_text(rgb: np.ndarray, boxes_with_translations: list[dict]) -> np.ndarray:
    # DEPRECATED stub retained only so older callers that imported it keep
    # working. The faithful render now lives in text_render.render_page and is
    # served by POST /api/render (Phase 0 render parity).
    raise NotImplementedError(
        "render_translated_text was replaced by text_render.render_page (/api/render)"
    )


def get_aot() -> ort.InferenceSession:
    global aot_session
    if aot_session is None:
        aot_session = ort.InferenceSession(str(AOT_MODEL), providers=["CPUExecutionProvider"])
    return aot_session


@app.get("/")
def index() -> FileResponse:
    return FileResponse(Path(__file__).parent / "index.html")


@app.get("/{name}")
def static_file(name: str) -> FileResponse:
    safe = Path(name).name
    filepath = Path(__file__).parent / safe
    if not filepath.is_file():
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(filepath)


@app.get("/api/models")
def get_models(baseUrl: str = "http://localhost:1234/v1") -> dict:
    url = f"{baseUrl.rstrip('/')}/models"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as response:
            data = json.loads(response.read().decode())
            models = []
            if "data" in data and isinstance(data["data"], list):
                for item in data["data"]:
                    if "id" in item:
                        models.append(item["id"])
            return {"models": models}
    except Exception as e:
        print("Failed to fetch models from", url, e)
        return {"models": [], "error": str(e)}

@app.post("/api/detect")
async def api_detect(
    image: UploadFile = File(...),
    paddle_crop_pad: int = Form(12),
    paddle_thresh: float = Form(PADDLE_THRESH),
    paddle_box_thresh: float = Form(0.34),
) -> dict:
    t0 = time.perf_counter()
    raw = await image.read()
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    rgb = np.array(pil)
    height, width = rgb.shape[:2]

    detections = detect_page(rgb)
    t1 = time.perf_counter()
    text_dets = [d for d in detections if d["label"] in (1, 2)]
    paddle_boxes = []
    fallback_boxes = []
    for det in text_dets:
        x1, y1, x2, y2 = clamp_box(
            [
                det["bbox"][0] - paddle_crop_pad,
                det["bbox"][1] - paddle_crop_pad,
                det["bbox"][2] + paddle_crop_pad,
                det["bbox"][3] + paddle_crop_pad,
            ],
            width,
            height,
        )
        if x2 <= x1 or y2 <= y1:
            continue
        crop = rgb[y1:y2, x1:x2]
        lines = detect_paddle_lines(crop, thresh=paddle_thresh, box_thresh=paddle_box_thresh)
        
        paddle_area = 0
        if lines:
            for line in lines:
                paddle_area += (line.bbox[2] - line.bbox[0]) * (line.bbox[3] - line.bbox[1])
                
        crop_area = max(1, (det["bbox"][2] - det["bbox"][0]) * (det["bbox"][3] - det["bbox"][1]))
        
        if not lines or paddle_area < 0.10 * crop_area:
            fallback_boxes.append(
                {
                    "bbox": det["bbox"],
                    "score": det["score"],
                    "source": "safety_net_fallback",
                    "parent": det["bbox"],
                    "parent_label": det["label"],
                    "parent_class": det["className"],
                }
            )
            continue
        for line in lines:
            lx1, ly1, lx2, ly2 = line.bbox
            full = clamp_box([x1 + lx1, y1 + ly1, x1 + lx2, y1 + ly2], width, height)
            if full[2] > full[0] and full[3] > full[1]:
                paddle_boxes.append(
                    {
                        "bbox": full,
                        "score": round(line.mean_score, 4),
                        "source": "paddle_v6_det",
                        "parent": det["bbox"],
                        "parent_label": det["label"],
                        "parent_class": det["className"],
                    }
                )
    t2 = time.perf_counter()
    
    mask_items = paddle_boxes + fallback_boxes
    if not mask_items:
        mask_items = [
            {
                "bbox": d["bbox"],
                "score": d["score"],
                "source": "safety_net_fallback",
                "parent": d["bbox"],
                "parent_label": d["label"],
                "parent_class": d["className"],
            }
            for d in text_dets
        ]
    mask_boxes = [item["bbox"] for item in mask_items]
    
    return {
        "width": width,
        "height": height,
        "detections": detections,
        "paddle_boxes": paddle_boxes,
        "fallback_boxes": fallback_boxes,
        "text_dets": text_dets,
        "mask_boxes": [{"bbox": b} for b in mask_boxes],
        "timings_ms": {
            "detector_v4": round((t1 - t0) * 1000, 1),
            "paddle_v6_det": round((t2 - t1) * 1000, 1),
            "total": round((t2 - t0) * 1000, 1),
        }
    }


@app.post("/api/ocr")
async def api_ocr(
    image: UploadFile = File(...),
    text_dets_json: str = Form("[]"),
    paddle_boxes_json: str = Form("[]"),
    rec_confidence: float = Form(PADDLE_REC_CONFIDENCE),
) -> dict:
    t0 = time.perf_counter()
    raw = await image.read()
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    rgb = np.array(pil)
    
    text_dets = json.loads(text_dets_json)
    paddle_boxes = json.loads(paddle_boxes_json)
    
    ocr_texts = []
    det_list = []
    
    for det in text_dets:
        det_lines = [b for b in paddle_boxes if b["parent"] == det["bbox"]]
        if not det_lines:
            continue
        
        # Classify lines as vertical or horizontal, apply size guards
        classified = []
        for lb in det_lines:
            lx1, ly1, lx2, ly2 = lb["bbox"]
            lw = lx2 - lx1
            lh = ly2 - ly1
            if lw < 12 or lh < 12 or lw > 400 or lh > 800:
                continue
            is_vert = lh > lw * 1.5
            # Sort key: vertical by x-center DESC (RTL), horizontal by y ASC (TTB)
            if is_vert:
                sort_key = -((lx1 + lx2) / 2)
            else:
                sort_key = (ly1 + ly2) / 2
            classified.append((lb, is_vert, sort_key))
        
        classified.sort(key=lambda x: x[2])
        
        full_ocr = ""
        for line_box, is_vert, _ in classified:
            lx1, ly1, lx2, ly2 = line_box["bbox"]
            box_rgb = rgb[ly1:ly2, lx1:lx2]
            if box_rgb.size > 0:
                text, conf = run_paddle_rec(box_rgb)
                line_box["rec_conf"] = round(conf, 3)
                if conf >= rec_confidence:
                    line_box["ocr_text"] = text
                    full_ocr += text
                else:
                    line_box["ocr_text"] = ""
        
        if full_ocr:
            ocr_texts.append(full_ocr)
            det_list.append(det)
            
    t1 = time.perf_counter()
    return {
        "ocr_texts": ocr_texts,
        "det_list": det_list,
        "timings_ms": {
            "ocr": round((t1 - t0) * 1000, 1)
        }
    }


@app.post("/api/inpaint")
async def api_inpaint(
    image: UploadFile = File(...),
    text_dets_json: str = Form("[]"),
    paddle_boxes_json: str = Form("[]"),
    fallback_boxes_json: str = Form("[]"),
    mask_pad: int = Form(8),
    feather: int = Form(8),
    lowres_scale: int = Form(10),
    smooth_passes: int = Form(36),
    mode: str = Form("quality"),
    gray_fill_thresh: float = Form(3.0),
    algo: str = Form("coherent"),
    tiny_expand: bool = Form(True),
    poisson_iters: int = Form(300),
) -> dict:
    t0 = time.perf_counter()
    raw = await image.read()
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    rgb = np.array(pil)
    height, width = rgb.shape[:2]
    
    text_dets = json.loads(text_dets_json)
    paddle_boxes = json.loads(paddle_boxes_json)
    fallback_boxes = json.loads(fallback_boxes_json)
    
    mask_items = paddle_boxes + fallback_boxes
    if not mask_items:
        mask_items = [
            {
                "bbox": d["bbox"],
                "score": d["score"],
                "source": "safety_net_fallback",
                "parent": d["bbox"],
                "parent_label": d["label"],
                "parent_class": d["className"],
            }
            for d in text_dets
        ]

    # ── Coherent branch ──────────────────────────────────────────
    if algo == "coherent":
        # Phase 0 inpaint parity: Android re-refines FREE-text (label 2) boxes
        # through Paddle DET at the inpaint thresholds (AOTInpainting.kt:247-254,
        # 392-447), separate from the detect stage. Parented (label 0/1) boxes
        # keep their detect-stage paddle lines (Android fillContained consumes
        # the OCR-stage group boxes). Rebuild the free-text mask items from the
        # refined line boxes so the sandbox's erase mask matches the device.
        parented_items = [it for it in mask_items if int(it.get("parent_label", -1)) != 2]
        free_dets = [d for d in text_dets if int(d.get("label", -1)) == 2]
        paddle_lines_total = 0
        paddle_fallback = 0
        if free_dets:
            refined_free, paddle_lines_total, paddle_fallback = refine_free_text_boxes(
                rgb, [d["bbox"] for d in free_dets],
            )
            free_items = [
                {
                    "bbox": b,
                    "parent": b,
                    "parent_label": 2,
                    "parent_class": "text_free",
                    "source": "inpaint_paddle_refine",
                }
                for b in refined_free
            ]
        else:
            free_items = []
        mask_items = parented_items + free_items

        clusters = build_inpaint_clusters(mask_items, width, height)
        opts = cci_inpaint.CCIOptions()
        if not tiny_expand:
            opts.free_min_side = 0
            opts.free_long_floor = 0
        opts.poisson_iters = poisson_iters
        # Android neural blend feather ramp (AOTInpainting.kt:47 FEATHER_RAMP_PX).
        opts.feather_ramp = FEATHER_RAMP_PX
        quality_path = (mode.lower() == "quality")
        aot_fn = make_coherent_aot_fn(gray_fill_thresh) if quality_path else None

        t_coh0 = time.perf_counter()
        result, diag, union_mask = cci_inpaint.inpaint_coherent(
            rgb, clusters, opts=opts, aot_fn=aot_fn, quality=quality_path,
        )
        t_coh1 = time.perf_counter()

        # Display mask = the ACTUAL union of per-cluster erase masks, so the
        # "Mask Only" layer matches the inpaint exactly (no bbox/mask/inpaint
        # mismatch). mask_to_rgb expands the single-channel mask to 3 channels.
        display_mask = union_mask

        # Compact diagnostics summary
        tier_counts = {}
        method_counts = {}
        uniform_rejected = 0
        blend_counts = {}
        for d in diag:
            # Coerce the Tier enum to its plain string value so the JSON
            # response keys are stable "FLAT"/"TEXTURED"/"COLOR" across
            # Python versions (str-Enum key serialization differs by version).
            t = d.get("tier")
            if t is not None:
                t = t.value if hasattr(t, "value") else str(t)
                tier_counts[t] = tier_counts.get(t, 0) + 1
            m = d.get("method")
            if m:
                method_counts[m] = method_counts.get(m, 0) + 1
            if d.get("uniform_rejected"):
                uniform_rejected += 1
            b = d.get("blend")
            if b:
                blend_counts[b] = blend_counts.get(b, 0) + 1

        return {
            "mask_png": png_data_url(mask_to_rgb(display_mask)),
            "inpaint_png": png_data_url(result),
            "timings_ms": {
                "inpaint": round((t_coh1 - t_coh0) * 1000, 1),
            },
            "algo": "coherent",
            "diagnostics": {
                "total_clusters": len(diag),
                "tier_counts": tier_counts,
                "method_counts": method_counts,
                "uniform_rejected": uniform_rejected,
                "blend_counts": blend_counts,
                "paddle_refine": {
                    "free_dets": len(free_dets),
                    "paddle_lines": paddle_lines_total,
                    "fallback": paddle_fallback,
                    "thresh": PADDLE_INPAINT_THRESH,
                    "box_thresh": PADDLE_INPAINT_BOX_THRESH,
                    "crop_pad": PADDLE_INPAINT_CROP_PAD,
                    "refine_pad": FREE_TEXT_REFINE_PAD,
                },
                "feather_ramp_px": opts.feather_ramp,
            },
        }

    # ── Legacy branch ────────────────────────────────────────────
    # Android FAST/legacy free-text also consumes Paddle-refined line boxes
    # (AOTInpainting.kt:247-254, 325-365; LegacyFreeTextInpainter.kt). The
    # sandbox LEGACY path is the visually closest path to Android in practice,
    # so apply the same inpaint-stage refine here too instead of relying on the
    # earlier detect-stage boxes.
    parented_items = [it for it in mask_items if int(it.get("parent_label", -1)) != 2]
    free_dets = [d for d in text_dets if int(d.get("label", -1)) == 2]
    paddle_lines_total = 0
    paddle_fallback = 0
    if free_dets:
        refined_free, paddle_lines_total, paddle_fallback = refine_free_text_boxes(
            rgb, [d["bbox"] for d in free_dets],
        )
        refined_free_items = [
            {
                "bbox": b,
                "parent": b,
                "parent_label": 2,
                "parent_class": "text_free",
                "source": "inpaint_paddle_refine",
            }
            for b in refined_free
        ]
        mask_items = parented_items + refined_free_items

    bubble_items = [i for i in mask_items if int(i.get("parent_label", -1)) in (0, 1)]
    free_items = [i for i in mask_items if int(i.get("parent_label", -1)) == 2]

    # If no items match, ensure we don't crash and at least return the original image
    if not bubble_items and not free_items:
        print("Warning: No mask items found for inpainting!")
        
    bubble_mask = build_rect_mask([b["bbox"] for b in bubble_items], width, height, mask_pad)
    
    # 1. Cheap/fast inpaint for bubbles using OpenCV Telea FMM or pil_inpaint_bubble
    inpaint, _ = inpaint_image(rgb, bubble_mask, lowres_scale / 100.0, smooth_passes, feather, "bubble_fast", gray_fill_thresh)
    
    # 2. LEGACY free text always uses the validated local-ring + push-pull path.
    # Keep AOT experimentation in the coherent branch; the Android import now
    # routes all label-2 free text through LegacyFreeTextInpainter.
    free_mask_visual = np.zeros((height, width), dtype=np.uint8)

    for item in free_items:
        inpaint, single_mask = inpaint_free_text(
            inpaint, item["bbox"], pad=mask_pad,
            feather_radius=FREE_TEXT_FEATHER, tiny_expand=True,
        )
        free_mask_visual = np.maximum(free_mask_visual, single_mask)
            
    mask = np.maximum(bubble_mask, free_mask_visual)
    t1 = time.perf_counter()
    
    return {
        "mask_png": png_data_url(mask_to_rgb(mask)),
        "inpaint_png": png_data_url(inpaint),
        "timings_ms": {
            "inpaint": round((t1 - t0) * 1000, 1)
        },
        "algo": "legacy",
        "diagnostics": {
            "paddle_refine": {
                "free_dets": len(free_dets),
                "paddle_lines": paddle_lines_total,
                "fallback": paddle_fallback,
                "thresh": PADDLE_INPAINT_THRESH,
                "box_thresh": PADDLE_INPAINT_BOX_THRESH,
                "crop_pad": PADDLE_INPAINT_CROP_PAD,
                "refine_pad": FREE_TEXT_REFINE_PAD,
            },
        },
    }


@app.post("/api/translate")
async def api_translate(
    inpaint_image: UploadFile = File(...),
    ocr_texts_json: str = Form("[]"),
    det_list_json: str = Form("[]"),
    lm_url: str = Form("http://localhost:1234/v1"),
    lm_model: str = Form("local-model"),
    max_tokens: int = Form(8192),
    from_lang: str = Form("Japanese"),
    to_lang: str = Form("English"),
) -> dict:
    t0 = time.perf_counter()

    # inpaint_image is still accepted so the existing pipeline shape is
    # unchanged, but Phase 0 moves the actual rendering to /api/render
    # (faithful parity). The bytes are read + discarded here; translation is
    # text-only.
    await inpaint_image.read()

    ocr_texts = json.loads(ocr_texts_json)
    det_list = json.loads(det_list_json)

    translations_to_render = []

    if ocr_texts:
        translated_texts = translate_batch(
            ocr_texts, lm_url, lm_model,
            max_tokens=max_tokens, from_lang=from_lang, to_lang=to_lang,
        )
        for i, det in enumerate(det_list):
            translations_to_render.append({
                "bbox": det["bbox"],
                "ocr_text": ocr_texts[i],
                "translation": translated_texts[i],
            })

    t1 = time.perf_counter()

    return {
        "translations": translations_to_render,
        "debug": {
            "ocr_count": len(ocr_texts),
            "translated_count": len(translations_to_render),
            "lm_url": lm_url,
        },
        "timings_ms": {
            "translation": round((t1 - t0) * 1000, 1),
        },
    }


@app.post("/api/render")
async def api_render(
    image: UploadFile = File(...),
    detections_json: str = Form("[]"),
    det_list_json: str = Form("[]"),
    translations_json: str = Form("[]"),
    sample_size: int = Form(1),
    from_lang: str = Form("Japanese"),
) -> dict:
    """Faithful render (Phase 0): parent selection -> text_render.render_page.

    Reproduces the Android render (positions, sizes, wrapping, direction) given
    the same detections + translations. Returns the rendered PNG plus per-block
    diagnostics (Task 0.4 contract).
    """
    t0 = time.perf_counter()
    raw = await image.read()
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    rgb = np.array(pil)

    detections = json.loads(detections_json)
    det_list = json.loads(det_list_json)
    translations = json.loads(translations_json)

    bubbles = [d for d in detections if int(d.get("label", -1)) == 0]
    is_vert_lang = from_lang.strip().lower() in VERTICAL_LANGS

    blocks = []
    for det, tr_item in zip(det_list, translations):
        bbox = tr_item.get("bbox") or det.get("bbox")
        if not bbox or len(bbox) < 4:
            continue
        translation = tr_item.get("translation", "")
        if not translation:
            continue
        bw = float(bbox[2] - bbox[0])
        bh = float(bbox[3] - bbox[1])
        cx = (bbox[0] + bbox[2]) / 2.0
        cy = (bbox[1] + bbox[3]) / 2.0
        parent = text_render.select_parent_bubble(bbox, bubbles, cx, cy)
        direction = "TTB" if (is_vert_lang and bh > bw * 1.2) else "LTR"
        pbbox = parent["bbox"] if parent else None
        blocks.append(text_render.Block(
            translation=translation,
            width=bw, height=bh, x=float(bbox[0]), y=float(bbox[1]),
            label=int(det.get("label", 1)),
            score=float(det.get("score", 1.0)),
            direction=direction,
            parent_x=float(pbbox[0]) if pbbox else 0.0,
            parent_y=float(pbbox[1]) if pbbox else 0.0,
            parent_width=float(pbbox[2] - pbbox[0]) if pbbox else 0.0,
            parent_height=float(pbbox[3] - pbbox[1]) if pbbox else 0.0,
        ))

    opts = text_render.RenderOptions(sample_size=sample_size)
    rendered, diagnostics = text_render.render_page(rgb, blocks, sample_size, opts)
    t1 = time.perf_counter()

    return {
        "rendered_png": png_data_url(rendered),
        "diagnostics": diagnostics,
        "timings_ms": {
            "render": round((t1 - t0) * 1000, 1),
        },
    }


def detect_page(rgb: np.ndarray) -> list[dict]:
    session = get_detector()
    height, width = rgb.shape[:2]
    resized_img = Image.fromarray(rgb).resize((640, 640), Image.Resampling.BILINEAR)
    resized = np.array(resized_img)
    tensor = resized.astype(np.float32) / 255.0
    tensor = np.transpose(tensor, (2, 0, 1))[None, :, :, :]
    sizes = np.array([[width, height]], dtype=np.int64)
    labels, boxes, scores = session.run(None, {"images": tensor, "orig_target_sizes": sizes})
    labels = labels[0]
    boxes = boxes[0]
    scores = scores[0]
    out = []
    for label, box, score in zip(labels, boxes, scores):
        score = float(score)
        if math.isnan(score) or score < DETECTOR_THRESHOLD:
            continue
        bbox = clamp_box([int(box[0]), int(box[1]), int(box[2]), int(box[3])], width, height)
        out.append(
            {
                "bbox": bbox,
                "label": int(label),
                "className": {0: "bubble", 1: "text_bubble", 2: "text_free"}.get(int(label), f"class_{int(label)}"),
                "score": round(score, 4),
            }
        )
    return dedupe_detections(out)


def detect_paddle_lines(crop_rgb: np.ndarray, thresh: float = PADDLE_THRESH, box_thresh: float = PADDLE_BOX_THRESH) -> list[TextLine]:
    if crop_rgb.size == 0:
        return []
    session = get_paddle()
    crop_h, crop_w = crop_rgb.shape[:2]
    scale = PADDLE_TARGET / max(crop_w, crop_h)
    resized_w = max(1, min(PADDLE_TARGET, round(crop_w * scale)))
    resized_h = max(1, min(PADDLE_TARGET, round(crop_h * scale)))
    resized_crop_img = Image.fromarray(crop_rgb).resize((resized_w, resized_h), Image.Resampling.BILINEAR)
    resized_crop = np.array(resized_crop_img)
    padded = np.zeros((PADDLE_TARGET, PADDLE_TARGET, 3), dtype=np.uint8)
    padded[:resized_h, :resized_w, :] = resized_crop

    arr = padded.astype(np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    arr = (arr - mean) / std
    tensor = np.transpose(arr, (2, 0, 1))[None, :, :, :]
    output = session.run(None, {"x": tensor})[0][0, 0]
    active = output[:resized_h, :resized_w]
    map_lines = db_postprocess(active, thresh=thresh, box_thresh=box_thresh)
    map_to_crop_x = crop_w / resized_w
    map_to_crop_y = crop_h / resized_h
    lines = []
    for line in map_lines:
        x1, y1, x2, y2 = line.bbox
        bbox = [
            int(math.floor(x1 * map_to_crop_x)),
            int(math.floor(y1 * map_to_crop_y)),
            int(math.ceil(x2 * map_to_crop_x)),
            int(math.ceil(y2 * map_to_crop_y)),
        ]
        bbox = clamp_box(bbox, crop_w, crop_h)
        if bbox[2] > bbox[0] and bbox[3] > bbox[1]:
            lines.append(TextLine(bbox=bbox, mean_score=line.mean_score))
    return lines


def connected_components(binary: np.ndarray) -> tuple[int, np.ndarray, np.ndarray]:
    height, width = binary.shape
    visited = np.zeros_like(binary, dtype=bool)
    labels = np.zeros_like(binary, dtype=int)
    stats = [[0, 0, width, height, 0]]
    label_idx = 1
    
    from collections import deque
    for y in range(height):
        for x in range(width):
            if binary[y, x] and not visited[y, x]:
                q = deque([(x, y)])
                visited[y, x] = True
                labels[y, x] = label_idx
                
                min_x, max_x = x, x
                min_y, max_y = y, y
                area = 0
                
                while q:
                    cx, cy = q.popleft()
                    area += 1
                    if cx < min_x: min_x = cx
                    if cx > max_x: max_x = cx
                    if cy < min_y: min_y = cy
                    if cy > max_y: max_y = cy
                    
                    for dy in (-1, 0, 1):
                        for dx in (-1, 0, 1):
                            if dx == 0 and dy == 0:
                                continue
                            nx, ny = cx + dx, cy + dy
                            if 0 <= nx < width and 0 <= ny < height:
                                if binary[ny, nx] and not visited[ny, nx]:
                                    visited[ny, nx] = True
                                    labels[ny, nx] = label_idx
                                    q.append((nx, ny))
                                    
                stats.append([min_x, min_y, max_x - min_x + 1, max_y - min_y + 1, area])
                label_idx += 1
                
    return label_idx, labels, np.array(stats)


def pil_inpaint_bubble(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    h, w = rgb.shape[:2]
    img = rgb.copy()
    
    binary = (mask > 0).astype(np.uint8)
    num, labels, stats = connected_components(binary)
    
    for label in range(1, num):
        mx = int(stats[label, 0])
        my = int(stats[label, 1])
        mw = int(stats[label, 2])
        mh = int(stats[label, 3])
        
        # Inner bounding box
        ix1, iy1 = mx, my
        ix2, iy2 = mx + mw, my + mh
        
        # 2-pixel outer boundary perimeter
        ox1 = max(0, ix1 - 2)
        oy1 = max(0, iy1 - 2)
        ox2 = min(w, ix2 + 2)
        oy2 = min(h, iy2 + 2)
        
        # Create outer ring mask for sampling
        ring_mask = np.ones((oy2 - oy1, ox2 - ox1), dtype=bool)
        
        in_x1 = ix1 - ox1
        in_y1 = iy1 - oy1
        in_x2 = in_x1 + mw
        in_y2 = in_y1 + mh
        
        if in_x1 < in_x2 and in_y1 < in_y2:
            ring_mask[in_y1:in_y2, in_x1:in_x2] = False
            
        crop_rgb = img[oy1:oy2, ox1:ox2].astype(np.float32)
        ring_pixels = crop_rgb[ring_mask]
        
        if len(ring_pixels) > 0:
            median_color = np.median(ring_pixels, axis=0)
        else:
            median_color = np.array([255, 255, 255], dtype=np.float32)
            
        # Component mask
        comp_mask = (labels[oy1:oy2, ox1:ox2] == label).astype(np.float32)[..., None]
        
        # Paint the patch with clean outer median color
        crop_rgb = crop_rgb * (1.0 - comp_mask) + median_color * comp_mask
        
        # Blend edges slightly
        for _ in range(12):
            smoothed = (
                np.roll(crop_rgb, 1, axis=0) +
                np.roll(crop_rgb, -1, axis=0) +
                np.roll(crop_rgb, 1, axis=1) +
                np.roll(crop_rgb, -1, axis=1)
            ) * 0.25
            crop_rgb = crop_rgb * (1.0 - comp_mask) + smoothed * comp_mask
            
        img[oy1:oy2, ox1:ox2] = np.clip(crop_rgb, 0, 255).astype(np.uint8)
        
    return img


def _local_ring_median(rgb: np.ndarray, mask_bool: np.ndarray, ring: int = FREE_RING) -> np.ndarray:
    """Median RGB of the local annulus around the mask bbox (the free-text
    surroundings). Falls back to the global page median when the annulus is
    empty (degenerate/edge case) — never pure white.

    This is the LOCAL replacement for the old global ``np.median(valid_pixels)``
    that kept free text "stuck on white" (pages are mostly white, so the global
    median was white).
    """
    h, w = rgb.shape[:2]
    ys, xs = np.where(mask_bool)
    if len(ys) == 0:
        # No mask: fall back to global median of the whole image.
        return np.median(rgb.reshape(-1, 3), axis=0).astype(np.float32)
    y1, y2 = int(ys.min()), int(ys.max())
    x1, x2 = int(xs.min()), int(xs.max())
    ry1, ry2 = max(0, y1 - ring), min(h, y2 + ring + 1)
    rx1, rx2 = max(0, x1 - ring), min(w, x2 + ring + 1)
    block = rgb[ry1:ry2, rx1:rx2]
    ring_mask = np.ones(block.shape[:2], dtype=bool)
    # Exclude the mask bbox interior so we sample only the surrounding background.
    in_y1, in_x1 = y1 - ry1, x1 - rx1
    in_y2, in_x2 = in_y1 + (y2 - y1 + 1), in_x1 + (x2 - x1 + 1)
    ring_mask[in_y1:in_y2, in_x1:in_x2] = False
    ring_pixels = block[ring_mask]
    if len(ring_pixels) > 0:
        return np.median(ring_pixels, axis=0).astype(np.float32)
    # Degenerate ring (e.g. mask fills the whole crop): global fallback.
    return np.median(rgb[~mask_bool].reshape(-1, 3), axis=0).astype(np.float32)


def pil_inpaint_stroke(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    h, w = rgb.shape[:2]
    out = rgb.copy().astype(np.float32)
    mask_bool = mask > 0
    
    # CRITICAL: Erase the text ink BEFORE downscaling so it doesn't pollute the
    # gradient. Use the LOCAL surrounding color (ring around the text), NOT the
    # global page median — otherwise free text is "stuck on white".
    if np.any(mask_bool):
        safe_bg_color = _local_ring_median(rgb, mask_bool)
        out[mask_bool] = safe_bg_color
        
    # Now generate the Push-Pull gradient on the clean image
    pil_img = Image.fromarray(out.astype(np.uint8))
    small_w, small_h = max(4, w // 20), max(4, h // 20)
    small = pil_img.resize((small_w, small_h), Image.Resampling.BILINEAR)
    gradient_map = np.array(small.resize((w, h), Image.Resampling.BICUBIC)).astype(np.float32)
    
    # Reset to original image, but fill holes with our clean gradient map
    out = rgb.copy().astype(np.float32)
    out[mask_bool] = gradient_map[mask_bool]
    
    # Vectorized boundary diffusion to stitch the edges
    for _ in range(15):
        up = np.roll(out, -1, axis=0)
        down = np.roll(out, 1, axis=0)
        left = np.roll(out, -1, axis=1)
        right = np.roll(out, 1, axis=1)
        avg = (up + down + left + right) * 0.25
        out[mask_bool] = avg[mask_bool]
        
    return np.clip(out, 0, 255).astype(np.uint8)


def inpaint_free_text(
    rgb: np.ndarray,
    box: list[int],
    pad: int = 2,
    feather_radius: int = FREE_TEXT_FEATHER,
    tiny_expand: bool = True,
) -> tuple[np.ndarray, np.ndarray]:
    """Legacy free-text inpaint with LOCAL color + bleed-free light feather.

    1. (optional) expand tiny free boxes to a size floor (free text only);
    2. build a stroke mask dilated by ``feather_radius`` so the erase region
       covers text + halo + feather margin (this is what makes the feather
       bleed-free — the feather ring lands on pure background, not text);
    3. reconstruct with ``pil_inpaint_stroke`` (now using the LOCAL ring color,
       not the global page median that kept free text "stuck on white");
    4. composite back with a light feather.

    Returns ``(result_rgb, erase_mask)``.
    """
    h, w = rgb.shape[:2]
    box_use = list(box)
    if tiny_expand:
        opts = cci_inpaint.CCIOptions()
        box_use = cci_inpaint.expand_tiny_free_boxes([box_use], [True], w, h, opts)[0]

    dilate_size = 5 + 2 * max(0, int(feather_radius))
    erase = build_stroke_mask_local(rgb, box_use, pad=pad, dilate_size=dilate_size)
    if not np.any(erase):
        return rgb.copy(), erase
    filled = pil_inpaint_stroke(rgb, erase)
    out = feather_composite(rgb, filled, erase, max(0, int(feather_radius)))
    return out, erase


def db_postprocess(prob: np.ndarray, thresh: float = PADDLE_THRESH, box_thresh: float = PADDLE_BOX_THRESH) -> list[TextLine]:
    binary = (prob > thresh).astype(np.uint8)
    num, labels, stats = connected_components(binary)
    lines: list[TextLine] = []
    for label in range(1, num):
        area = int(stats[label, 4])
        if area < MIN_DB_AREA:
            continue
        mask = labels == label
        score = float(prob[mask].mean()) if area else 0.0
        if score < box_thresh:
            continue
        x = int(stats[label, 0])
        y = int(stats[label, 1])
        w = int(stats[label, 2])
        h = int(stats[label, 3])
        lines.append(TextLine([x, y, x + w, y + h], score))
    return merge_line_fragments(lines)


def merge_line_fragments(lines: list[TextLine]) -> list[TextLine]:
    current = lines
    for _ in range(3):
        horiz = [l for l in current if box_w(l.bbox) >= box_h(l.bbox)]
        vert = [l for l in current if box_w(l.bbox) < box_h(l.bbox)]
        merged = merge_axis(horiz, horizontal=True) + merge_axis(vert, horizontal=False)
        if len(merged) == len(current):
            break
        current = merged
    return sorted(current, key=lambda l: (l.bbox[1], l.bbox[0]))


def merge_axis(lines: list[TextLine], horizontal: bool) -> list[TextLine]:
    if not lines:
        return []
    if horizontal:
        ordered = sorted(lines, key=lambda l: ((l.bbox[1] + l.bbox[3]) / 2, l.bbox[0]))
    else:
        ordered = sorted(lines, key=lambda l: ((l.bbox[0] + l.bbox[2]) / 2, l.bbox[1]))
    merged: list[TextLine] = []
    for line in ordered:
        b = line.bbox
        placed = False
        for m in merged:
            mb = m.bbox
            if horizontal:
                same = abs(center_y(b) - center_y(mb)) <= 0.6 * min(box_h(b), box_h(mb))
                gap = max(0, max(b[0], mb[0]) - min(b[2], mb[2]))
                allowed = gap <= 1.0 * max(box_h(b), box_h(mb))
            else:
                same = abs(center_x(b) - center_x(mb)) <= 0.6 * min(box_w(b), box_w(mb))
                gap = max(0, max(b[1], mb[1]) - min(b[3], mb[3]))
                allowed = gap <= 1.0 * max(box_w(b), box_w(mb))
            if same and allowed:
                m.bbox = [min(mb[0], b[0]), min(mb[1], b[1]), max(mb[2], b[2]), max(mb[3], b[3])]
                m.mean_score = max(m.mean_score, line.mean_score)
                placed = True
                break
        if not placed:
            merged.append(TextLine(b.copy(), line.mean_score))
    return merged


def build_rect_mask(boxes: list[list[int]], width: int, height: int, pad: int) -> np.ndarray:
    mask_img = Image.new("L", (width, height), 0)
    draw = ImageDraw.Draw(mask_img)
    for box in boxes:
        x1, y1, x2, y2 = clamp_box([box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad], width, height)
        if x2 > x1 and y2 > y1:
            r = max(4, pad)
            draw.rounded_rectangle([x1, y1, x2, y2], radius=r, fill=255)
    return np.array(mask_img)


def build_stroke_mask_local(rgb_img: np.ndarray, box: list[int], pad: int = 2, dilate_size: int = 5) -> np.ndarray:
    x1, y1, x2, y2 = clamp_box([box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad], rgb_img.shape[1], rgb_img.shape[0])
    crop = rgb_img[y1:y2, x1:x2]
    if crop.size == 0:
        return np.zeros((rgb_img.shape[0], rgb_img.shape[1]), dtype=np.uint8)
    
    # 1. Pure NumPy Grayscale conversion
    gray = 0.299 * crop[:, :, 0] + 0.587 * crop[:, :, 1] + 0.114 * crop[:, :, 2]
    
    # 2. Aggressive threshold (captures black ink and dark gray anti-aliasing)
    stroke_binary = (gray < 160).astype(np.uint8) * 255
    
    # 3. Native Dilation using Pillow to swallow the halo. dilate_size controls
    # the dilation radius (default 5 ≈ 2px halo). Free text passes a larger size
    # so the erase region covers text + halo + feather margin → bleed-free feather.
    if dilate_size < 3:
        dilate_size = 3
    mask_img = Image.fromarray(stroke_binary)
    dilated_img = mask_img.filter(ImageFilter.MaxFilter(dilate_size))
    dilated_stroke = np.array(dilated_img)
    
    # 4. Reconstruct full page mask
    full_mask = np.zeros((rgb_img.shape[0], rgb_img.shape[1]), dtype=np.uint8)
    full_mask[y1:y2, x1:x2] = dilated_stroke
    return full_mask






def inpaint_image(
    rgb: np.ndarray,
    mask: np.ndarray,
    scale: float,
    smooth_passes: int,
    feather: int,
    method: str,
    gray_fill_thresh: float = 3.0,
) -> tuple[np.ndarray, list[int] | None]:
    if not np.any(mask):
        return rgb.copy(), None
        
    if method == "bubble_fast":
        if OPENCV_AVAILABLE:
            filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_TELEA)
        else:
            filled = pil_inpaint_bubble(rgb, mask)
        # Bubbles keep their feathering for soft edges
        return feather_composite(rgb, filled, mask, feather), None
        
    if method == "stroke_fast":
        if OPENCV_AVAILABLE:
            filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_TELEA)
        else:
            filled = pil_inpaint_stroke(rgb, mask)
        # STROKES MUST HAVE 0 FEATHER or the original text bleeds through!
        return feather_composite(rgb, filled, mask, 0), None
        
    if method == "telea":
        if OPENCV_AVAILABLE:
            filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_TELEA)
        else:
            filled = pil_inpaint_stroke(rgb, mask)
        return feather_composite(rgb, filled, mask, feather), None
    if method == "ns":
        if OPENCV_AVAILABLE:
            filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_NS)
        else:
            filled = pil_inpaint_stroke(rgb, mask)
        return feather_composite(rgb, filled, mask, feather), None
    try:
        return aot_inpaint(rgb, mask, feather, gray_fill_thresh)
    except Exception as e:
        print(f"[AOT rejection/failure] {e}, falling back to Telea FMM.")
        if OPENCV_AVAILABLE:
            filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_TELEA)
        else:
            filled = pil_inpaint_bubble(rgb, mask)
        return feather_composite(rgb, filled, mask, feather), None


def aot_inpaint(rgb: np.ndarray, mask: np.ndarray, feather: int, gray_fill_thresh: float) -> tuple[np.ndarray, list[int] | None]:
    if not np.any(mask):
        return rgb.copy(), None
    session = get_aot()
    h, w = rgb.shape[:2]
    ys, xs = np.where(mask > 0)
    x_min, x_max = int(xs.min()), int(xs.max())
    y_min, y_max = int(ys.min()), int(ys.max())
    
    # Goal-driven crop sizing:
    box_w = x_max - x_min
    box_h = y_max - y_min
    box_long_side = max(box_w, box_h)
    
    # Multiply long side by 3, keep it square, clamp to [384, 512]
    target_crop_dim = int(np.clip(box_long_side * 3, 384, 512))
    # Round to a multiple of 8 to prevent scaling past 512 when rounding up
    target_crop_dim = (target_crop_dim // 8) * 8
    
    cx = (x_min + x_max) / 2
    cy = (y_min + y_max) / 2
    
    x1 = int(cx - target_crop_dim / 2)
    y1 = int(cy - target_crop_dim / 2)
    x2 = x1 + target_crop_dim
    y2 = y1 + target_crop_dim
    
    # Shift to keep it square and of target_crop_dim size within boundaries
    if x1 < 0:
        x2 = min(w, x2 - x1)
        x1 = 0
    if x2 > w:
        x1 = max(0, x1 - (x2 - w))
        x2 = w
    if y1 < 0:
        y2 = min(h, y2 - y1)
        y1 = 0
    if y2 > h:
        y1 = max(0, y1 - (y2 - h))
        y2 = h
        
    crop = rgb[y1:y2, x1:x2]
    crop_mask = mask[y1:y2, x1:x2]
    ch, cw = crop.shape[:2]

    # Calculate resized dimensions for neural inference
    max_dim = 512
    scale = min(1.0, max_dim / max(cw, ch))
    infer_w = max(8, int(round(cw * scale)))
    infer_h = max(8, int(round(ch * scale)))
    infer_w += (8 - infer_w % 8) % 8
    infer_h += (8 - infer_h % 8) % 8

    # Absolutely forbid any tensor larger than 512x512 from entering aot_session.run
    if infer_w > 512 or infer_h > 512:
        raise ValueError(f"Calculated tensor size {infer_w}x{infer_h} exceeds 512x512 constraint")

    # Resize using Pillow to prevent cv2.resize mismatch
    crop_img = Image.fromarray(crop)
    resized_crop_img = crop_img.resize((infer_w, infer_h), Image.Resampling.BILINEAR)
    resized = np.array(resized_crop_img)

    crop_mask_img = Image.fromarray(crop_mask)
    resized_mask_img = crop_mask_img.resize((infer_w, infer_h), Image.Resampling.NEAREST)
    resized_mask = np.array(resized_mask_img)

    m = (resized_mask > 127).astype(np.float32)
    img = resized.astype(np.float32) / 127.5 - 1.0
    img = img * (1.0 - m[..., None])
    img_tensor = np.transpose(img, (2, 0, 1))[None, :, :, :].astype(np.float32)
    mask_tensor = m[None, None, :, :].astype(np.float32)

    # Double check assertions to strictly enforce tensor constraint
    assert img_tensor.shape[2] <= 512 and img_tensor.shape[3] <= 512, "Tensor width/height exceeds 512"
    assert mask_tensor.shape[2] <= 512 and mask_tensor.shape[3] <= 512, "Tensor width/height exceeds 512"

    out = session.run(None, {"image": img_tensor, "mask": mask_tensor})[0][0]
    out = np.transpose(out, (1, 2, 0))
    out = np.clip((out + 1.0) * 127.5, 0, 255).astype(np.uint8)

    # Gray-fill safety guard check (matching Android's AotOutputGuard.kt)
    pixels_to_check = out[resized_mask > 127] if np.any(resized_mask > 127) else out.reshape(-1, 3)
    if len(pixels_to_check) >= 16:
        # Calculate luma (0.299*R + 0.587*G + 0.114*B)
        luma = 0.299 * pixels_to_check[:, 0] + 0.587 * pixels_to_check[:, 1] + 0.114 * pixels_to_check[:, 2]
        mean_luma = float(np.mean(luma))
        variance = float(np.var(luma))
        
        # Calculate channel delta (mean of |R-G| + |G-B|)
        rg_delta = np.abs(pixels_to_check[:, 0].astype(np.int32) - pixels_to_check[:, 1].astype(np.int32))
        gb_delta = np.abs(pixels_to_check[:, 1].astype(np.int32) - pixels_to_check[:, 2].astype(np.int32))
        channel_delta = float(np.mean(rg_delta + gb_delta))
        
        variance_limit = gray_fill_thresh * gray_fill_thresh  # Map UI std_val to variance
        uniform = (variance < variance_limit) and (channel_delta < 8.0)
        
        # A genuinely reconstructed region is not a perfectly uniform block.
        # Treat both uniform-gray and uniform-white fills as suspicious.
        uniform_mid_gray = 96.0 <= mean_luma <= 160.0
        uniform_near_white = mean_luma >= 238.0
        
        if uniform and (uniform_mid_gray or uniform_near_white):
            print(f"[gray-fill guard] REJECTED. var = {variance:.2f}, mean = {mean_luma:.1f}")
            raise ValueError(f"Suspicious uniform output (var = {variance:.2f}, mean = {mean_luma:.1f})")
        else:
            print(f"[gray-fill guard] ACCEPTED. var = {variance:.2f}, mean = {mean_luma:.1f}")

    # Resize back using Pillow
    out_img = Image.fromarray(out)
    resized_out_img = out_img.resize((cw, ch), Image.Resampling.BICUBIC)
    out_crop = np.array(resized_out_img)

    blended_crop = feather_composite(crop, out_crop, crop_mask, feather)
    result = rgb.copy()
    result[y1:y2, x1:x2] = blended_crop
    return result, [x1, y1, x2, y2]


def feather_composite(rgb: np.ndarray, filled: np.ndarray, mask: np.ndarray, feather: int) -> np.ndarray:
    rgb_img = Image.fromarray(rgb).convert("RGB")
    filled_img = Image.fromarray(filled).convert("RGB")
    mask_img = Image.fromarray(mask).convert("L")

    if feather > 0:
        # Create an ALPHA_8 style Pillow mask, apply GaussianBlur
        blurred_mask = mask_img.filter(ImageFilter.GaussianBlur(radius=feather))
        # Emulate PorterDuff.Mode.DST_IN: blend by taking maximum of blurred mask and original mask
        final_mask = ImageChops.lighter(blurred_mask, mask_img)
    else:
        final_mask = mask_img

    # Blend using Image.composite
    blended = Image.composite(filled_img, rgb_img, final_mask)
    return np.array(blended)


def draw_overlay(rgb: np.ndarray, detections: list[dict], paddle_boxes: list[dict]) -> np.ndarray:
    pil = Image.fromarray(rgb).convert("RGB")
    draw = ImageDraw.Draw(pil)
    for det in detections:
        # Detector v4 text -> Bright Magenta (255, 0, 255), Bubble -> Blue (47, 112, 219)
        color = (255, 0, 255) if det["label"] in (1, 2) else (47, 112, 219)
        draw.rectangle(det["bbox"], outline=color, width=3)
    for box in paddle_boxes:
        # Paddle OCR -> Bright Green (0, 255, 0)
        draw.rectangle(box["bbox"], outline=(0, 255, 0), width=2)
    return np.array(pil)


def mask_to_rgb(mask: np.ndarray) -> np.ndarray:
    return np.repeat(mask[:, :, None], 3, axis=2)


def png_data_url(rgb: np.ndarray) -> str:
    pil = Image.fromarray(rgb)
    buf = io.BytesIO()
    pil.save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return f"data:image/png;base64,{b64}"


def dedupe_detections(detections: list[dict]) -> list[dict]:
    keep: list[dict] = []
    for det in sorted(detections, key=lambda d: d["score"], reverse=True):
        if det["label"] in (1, 2) and any(k["label"] == det["label"] and iou(det["bbox"], k["bbox"]) > 0.75 for k in keep):
            continue
        keep.append(det)
    return sorted(keep, key=lambda d: (d["bbox"][1], d["bbox"][0]))


def clamp_box(box: list[int], width: int, height: int) -> list[int]:
    x1 = max(0, min(width, int(box[0])))
    y1 = max(0, min(height, int(box[1])))
    x2 = max(0, min(width, int(box[2])))
    y2 = max(0, min(height, int(box[3])))
    return [min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)]


def refine_free_text_boxes(
    rgb: np.ndarray,
    detector_boxes: list[list[int]],
    thresh: float = PADDLE_INPAINT_THRESH,
    box_thresh: float = PADDLE_INPAINT_BOX_THRESH,
    crop_pad: int = PADDLE_INPAINT_CROP_PAD,
) -> tuple[list[list[int]], int, int]:
    """Mirrors ``AOTInpainting.refineFreeTextBoxes`` (AOTInpainting.kt:392-447).

    For every detector-v4 free-text box, crop the page with ``crop_pad`` of
    context, run Paddle DET at the inpaint thresholds, and back-project the
    returned line boxes to page coords (crop origin + line bbox, clamped). A
    small free-text-only guard pad is applied after back-projection so tight
    Paddle boxes erase glyph tails/diacritics. When Paddle returns 0 lines for a
    region, that region falls back to its detector-v4 box (counted in the
    fallback count) so a missed region is still erased. Returns
    ``(refined_boxes, paddle_line_count, fallback_count)``.
    """
    h, w = rgb.shape[:2]
    refined: list[list[int]] = []
    paddle_line_count = 0
    fallback_count = 0
    for det in detector_boxes:
        cx1 = max(0, det[0] - crop_pad)
        cy1 = max(0, det[1] - crop_pad)
        cx2 = min(w, det[2] + crop_pad)
        cy2 = min(h, det[3] + crop_pad)
        if cx2 <= cx1 or cy2 <= cy1:
            refined.append(list(det))
            fallback_count += 1
            continue
        crop = rgb[cy1:cy2, cx1:cx2]
        lines = detect_paddle_lines(crop, thresh=thresh, box_thresh=box_thresh)
        if not lines:
            refined.append(list(det))
            fallback_count += 1
            continue
        for line in lines:
            lx1, ly1, lx2, ly2 = line.bbox
            full = clamp_box([
                cx1 + lx1 - FREE_TEXT_REFINE_PAD,
                cy1 + ly1 - FREE_TEXT_REFINE_PAD,
                cx1 + lx2 + FREE_TEXT_REFINE_PAD,
                cy1 + ly2 + FREE_TEXT_REFINE_PAD,
            ], w, h)
            if full[2] > full[0] and full[3] > full[1]:
                refined.append(full)
                paddle_line_count += 1
    return refined, paddle_line_count, fallback_count


def build_inpaint_clusters(mask_items, page_w, page_h, cluster_distance=100):
    """Group mask_items into clusters for coherent inpaint.

    Bubble items (parent_label in (0,1)) are grouped by their shared `parent` box
    so all text lines in one bubble share one crop + background model.
    Free-text items (parent_label == 2) are clustered by spatial proximity
    (center distance <= cluster_distance px), mirroring the Android
    clusterNearbyBoxes(distance=100) behaviour.

    Returns list of dicts: {"boxes":[...], "is_free":[bool,...], "parent_rect":box or None}.
    Each box is [x1,y1,x2,y2] clamped to the page.
    """
    clusters = []

    # 1. Bubble items grouped by shared parent
    bubble_by_parent = {}
    for item in mask_items:
        pl = int(item.get("parent_label", -1))
        if pl in (0, 1):
            pkey = tuple(item["parent"])
            bubble_by_parent.setdefault(pkey, []).append(item)

    for pkey, items in bubble_by_parent.items():
        boxes = [clamp_box(item["bbox"], page_w, page_h) for item in items]
        clusters.append({
            "boxes": boxes,
            "is_free": [False] * len(boxes),
            "parent_rect": list(pkey),
        })

    # 2. Free-text items clustered by spatial proximity
    free_items = [item for item in mask_items if int(item.get("parent_label", -1)) == 2]
    if not free_items:
        return clusters

    n = len(free_items)
    adj = [[] for _ in range(n)]
    centers = []
    for item in free_items:
        b = item["bbox"]
        cx = (b[0] + b[2]) / 2.0
        cy = (b[1] + b[3]) / 2.0
        centers.append((cx, cy))

    for i in range(n):
        for j in range(i + 1, n):
            dx = centers[i][0] - centers[j][0]
            dy = centers[i][1] - centers[j][1]
            if math.sqrt(dx * dx + dy * dy) <= cluster_distance:
                adj[i].append(j)
                adj[j].append(i)

    visited = [False] * n
    for i in range(n):
        if visited[i]:
            continue
        group = []
        stack = [i]
        visited[i] = True
        while stack:
            cur = stack.pop()
            group.append(cur)
            for nb in adj[cur]:
                if not visited[nb]:
                    visited[nb] = True
                    stack.append(nb)
        boxes = [clamp_box(free_items[idx]["bbox"], page_w, page_h) for idx in group]
        clusters.append({
            "boxes": boxes,
            "is_free": [True] * len(boxes),
            "parent_rect": None,
        })

    return clusters


def make_coherent_aot_fn(gray_fill_thresh=3.0):
    """Build AOT adapter for the coherent pipeline.

    Calls aot_inpaint with feather=0 so the result is unblended
    (original outside mask, raw neural inside mask) — exactly the
    guidance image that the Poisson blend needs.
    """
    def aot_fn(crop_rgb, mask_uint8):
        res, _cb = aot_inpaint(crop_rgb, mask_uint8, feather=0, gray_fill_thresh=gray_fill_thresh)
        return res
    return aot_fn


def box_w(box: list[int]) -> int:
    return box[2] - box[0]


def box_h(box: list[int]) -> int:
    return box[3] - box[1]


def center_x(box: list[int]) -> float:
    return (box[0] + box[2]) / 2


def center_y(box: list[int]) -> float:
    return (box[1] + box[3]) / 2


def iou(a: list[int], b: list[int]) -> float:
    ix1 = max(a[0], b[0])
    iy1 = max(a[1], b[1])
    ix2 = min(a[2], b[2])
    iy2 = min(a[3], b[3])
    iw = max(0, ix2 - ix1)
    ih = max(0, iy2 - iy1)
    inter = iw * ih
    area = box_w(a) * box_h(a) + box_w(b) * box_h(b) - inter
    return inter / area if area > 0 else 0.0
