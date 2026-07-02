from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image

try:
    import onnxruntime as ort
except Exception:
    ort = None


class PaddleOcrV6SmallEngine:
    """PaddleOCR v6 small text recognition engine using ONNX."""

    def __init__(self, model_path: str | Path, dict_path: str | Path, providers: list[str] | None = None) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        
        self.session = ort.InferenceSession(
            str(model_path), providers=providers or ["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name
        
        # Load vocab
        with open(dict_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        self.character = [line.strip("\r\n") for line in lines]
        
        # Pad with space and blank tokens (Standard PaddleOCR dict format)
        self.character.append(" ")
        self.character.insert(0, "<blank>")

    def _preprocess(self, image: Image.Image) -> np.ndarray:
        # PP-OCR v3/v4/v6 recognition expected height is 48
        target_h = 48
        
        img_w, img_h = image.size
        ratio = img_w / float(img_h)
        target_w = int(math.ceil(target_h * ratio))
        target_w = max(1, target_w)
        
        img = image.resize((target_w, target_h), Image.Resampling.BILINEAR)
        img = img.convert("RGB")
        
        img_arr = np.array(img, dtype=np.float32)
        # HWC -> CHW
        img_arr = img_arr.transpose(2, 0, 1)
        
        # Normalize: (x - 127.5) / 127.5 is commonly used for PP-OCR (mean=0.5, std=0.5)
        img_arr = (img_arr / 255.0 - 0.5) / 0.5
        
        # Add batch dimension
        img_arr = np.expand_dims(img_arr, axis=0)
        return img_arr

    def recognize(self, image: Image.Image) -> str:
        text, _ = self.recognize_with_conf(image)
        return text

    def recognize_with_conf(self, image: Image.Image) -> tuple[str, float]:
        img_tensor = self._preprocess(image)
        
        outputs = self.session.run(None, {self.input_name: img_tensor})
        preds = outputs[0]  # Shape: (1, time_steps, num_classes)
        
        preds = preds[0]  # (time_steps, num_classes)
        preds_idx = preds.argmax(axis=1)
        preds_prob = preds.max(axis=1)
        
        text, conf = self._decode(preds_idx, preds_prob)
        return text, conf

    def _decode(self, text_index: np.ndarray, text_prob: np.ndarray) -> tuple[str, float]:
        # CTC decode
        char_list = []
        conf_list = []
        
        pre_c = None
        for idx, p in zip(text_index, text_prob):
            # Skip duplicates and blank (usually index 0)
            if idx == pre_c:
                continue
            pre_c = idx
            
            if idx == 0:
                continue
                
            char_idx = idx - 1
            if 0 <= char_idx < len(self.character):
                char = self.character[char_idx]
                if char != '<blank>':
                    char_list.append(char)
                    conf_list.append(float(p))
                    
        res_text = "".join(char_list)
        res_conf = sum(conf_list) / len(conf_list) if conf_list else 0.0
        
        return res_text, res_conf
