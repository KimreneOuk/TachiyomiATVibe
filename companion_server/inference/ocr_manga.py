from __future__ import annotations

import re
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

try:  # Optional so tests run without ONNX Runtime installed.
    import onnxruntime as ort
except Exception:  # pragma: no cover - environment dependent
    ort = None


MAX_GENERATION_LENGTH = 300
START_TOKEN = 2
END_TOKEN = 3
MAX_LEN = 256
DECODER_POSITION_COUNT = 128


class MangaOcrEngine:
    def __init__(
        self,
        encoder_path: str | Path,
        decoder_init_path: str | Path,
        decoder_step_path: str | Path,
        vocab_path: str | Path,
        providers: list[str] | None = None,
    ) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        paths = [Path(encoder_path), Path(decoder_init_path), Path(decoder_step_path), Path(vocab_path)]
        for path in paths:
            if not path.exists():
                raise FileNotFoundError(path)
        session_providers = providers or ["CPUExecutionProvider"]
        self.encoder = ort.InferenceSession(str(paths[0]), providers=session_providers)
        self.decoder_init = ort.InferenceSession(str(paths[1]), providers=session_providers)
        self.decoder_step = ort.InferenceSession(str(paths[2]), providers=session_providers)
        self.vocab = paths[3].read_text(encoding="utf-8").splitlines()

    def recognize(self, image: Image.Image) -> str:
        pixels = self._preprocess(image)
        if not np.any(pixels):
            return ""
        encoder_input = self.encoder.get_inputs()[0].name
        enc_hidden = self.encoder.run(None, {encoder_input: pixels})[0]
        init_outputs = self.decoder_init.run(
            None,
            {
                "encoder_hidden_states": enc_hidden,
                "input_ids": np.array([[START_TOKEN]], dtype=np.int64),
            },
        )
        self_k_cache = np.zeros((4, 1, 4, MAX_LEN, 64), dtype=np.float32)
        self_v_cache = np.zeros((4, 1, 4, MAX_LEN, 64), dtype=np.float32)
        self_k_cache[:, :, :, 0:1, :] = init_outputs[1]
        self_v_cache[:, :, :, 0:1, :] = init_outputs[2]
        cross_k = init_outputs[3]
        cross_v = init_outputs[4]

        token_ids: list[int] = []
        current_input_id = START_TOKEN
        pos = 1
        for _ in range(MAX_GENERATION_LENGTH):
            if pos >= DECODER_POSITION_COUNT:
                break
            outputs = self.decoder_step.run(
                None,
                {
                    "encoder_hidden_states": enc_hidden,
                    "input_ids": np.array([[current_input_id]], dtype=np.int64),
                    "position_ids": np.array([[pos]], dtype=np.int64),
                    "self_k_cache": self_k_cache,
                    "self_v_cache": self_v_cache,
                    "cross_k_cache": cross_k,
                    "cross_v_cache": cross_v,
                },
            )
            logits = outputs[0].reshape(-1)
            next_id = int(np.argmax(logits))
            self_k_cache[:, :, :, pos : pos + 1, :] = outputs[1]
            self_v_cache[:, :, :, pos : pos + 1, :] = outputs[2]
            if next_id == END_TOKEN:
                break
            token_ids.append(next_id)
            current_input_id = next_id
            pos += 1

        text = "".join(self.vocab[token] for token in token_ids if token < len(self.vocab))
        return postprocess(text)

    def _preprocess(self, image: Image.Image) -> np.ndarray:
        gray = ImageOps.grayscale(image.convert("RGB"))
        width, height = gray.size
        if max(width, height) == 0:
            return np.zeros((1, 3, 224, 224), dtype=np.float32)
        scale = 224.0 / max(width, height)
        new_size = (max(1, int(width * scale)), max(1, int(height * scale)))
        resized = gray.resize(new_size, Image.Resampling.BILINEAR)
        padded = Image.new("L", (224, 224), 255)
        padded.paste(resized, ((224 - new_size[0]) // 2, (224 - new_size[1]) // 2))
        data = np.asarray(padded, dtype=np.float32) / 255.0
        data = (data - 0.5) / 0.5
        data = np.stack([data, data, data], axis=0)
        return data[None, :, :, :].astype(np.float32)


def postprocess(text: str) -> str:
    result = re.sub(r"\s", "", text)
    result = result.replace("…", "...")
    result = re.sub(r"[・.]{2,}", lambda match: "." * len(match.group(0)), result)
    result = re.sub(r"([A-Za-z])0([A-Za-z])", r"\1o\2", result)
    result = re.sub(r"N[0°º˚⁰]", "", result)
    return result.replace("№", "")
