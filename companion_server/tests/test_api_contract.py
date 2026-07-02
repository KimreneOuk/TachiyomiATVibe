from __future__ import annotations

import io

from fastapi.testclient import TestClient
from PIL import Image

import server
from server import PROTOCOL_VERSION, app


def test_translate_contract_returns_expected_schema(monkeypatch) -> None:
    monkeypatch.setattr(server, "detector", None)
    image = Image.new("RGBA", (120, 180), (255, 255, 255, 255))
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")

    response = TestClient(app).post(
        "/v1/translate",
        data={"target_lang": "ENGLISH", "mode": "FAST"},
        files={"image": ("page.png", buffer.getvalue(), "image/png")},
    )

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"protocol_version", "img_width", "img_height", "blocks", "inpaint_mask_boxes"}
    assert body["protocol_version"] == PROTOCOL_VERSION
    assert body["img_width"] == 120
    assert body["img_height"] == 180
    assert len(body["blocks"]) == 1
    assert set(body["blocks"][0]) == {
        "text",
        "translation",
        "x",
        "y",
        "width",
        "height",
        "sym_height",
        "sym_width",
        "angle",
        "label",
        "direction",
    }
    assert len(body["inpaint_mask_boxes"]) == 1
    assert set(body["inpaint_mask_boxes"][0]) == {"x1", "y1", "x2", "y2", "label"}
