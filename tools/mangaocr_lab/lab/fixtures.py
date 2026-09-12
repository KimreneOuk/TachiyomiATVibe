"""Deterministic synthetic corpus generator (`make-fixtures`, req 10).

Renders the committed fixtures so the lab is never empty on a fresh clone:
categories digits/kana/kanji/punctuation/latin/mixed/long/noisy/empty/
previous_failures get PIL-rendered PNGs + `.expected.txt` sidecars; `real/`
stays empty (user-supplied device crops land there and via export-failure).

Deterministic: fixed string lists, seeded jitter/noise. A JP-capable font is
discovered from the usual system locations.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msgothic.ttc",
    r"C:\Windows\Fonts\msmincho.ttc",
    r"C:\Windows\Fonts\meiryo.ttc",
    r"C:\Windows\Fonts\YuGothR.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/System/Library/Fonts/Hitomi.ttf",
    "/System/Library/Fonts/Apple SD Gothic Neo.ttc",
]

STRINGS = {
    "digits": ["1234567890", "007", "1998", "3.14", "100", "2026"],
    "kana": ["ありがとう", "こんにちは", "すごいね", "だめだよ",
             "おねがいします", "やってみよう"],
    "kanji": ["東京タワー", "漢字試験", "一撃必殺", "西洋魔法陣",
              "禁書目録", "新世界へようこそ"],
    "punctuation": ["……！？", "――!!", "…。", "！！！？", "……。", "、。！？"],
    "latin": ["HELLO", "OK!", "GAME OVER", "NO.5", "STOP", "WINNER"],
    "mixed": ["係数3.14だ", "LV99達成", "2026年9月12日", "50km先へ", "第2話", "Ｗ杯開催"],
    "long": [
        "これはとても長いセリフなのでデコーダが高い位置まで進むことになります"
        "実際にどこまで進むのか確認するための文章です",
        "禁断の書物を開いた瞬間部屋全体が光に包まれ床が抜け落ち地下の大図書館へ"
        "と落ちてしまったのだ彼らはそこで古の知識と対面することになる",
        "お前は本当に俺の仲間なのか疑っていたあの夜全てを捨てて旅に出た理由を"
        "今ここではっきりと言おうそれは家族を守るためでも友を取り戻すためでも"
        "なく自分自身の過ちを正すためだったのだと",
        "北の砦に残っていた兵士たちの記録によればその戦いは三日三晩続いたとされ"
        "ており最後の一人が倒れるまで誰一人として退こうとはしなかったというこ"
        "とが書かれているまさに伝説の名にふさわしい守りであったと後世の書物は"
        "記しているのである",
    ],
    "noisy": ["まって、うそでしょ？", "背後からの気配", "Danger...", "第7章"],
    "empty": ["", "", ""],
    "previous_failures": ["1234567890", "0123456789", "9035782401"],
}

EMPTY_SIZES = [(32, 32), (96, 48), (224, 64)]
SIZES = [28, 32, 36]


def _find_font() -> str:
    from PIL import features
    for cand in FONT_CANDIDATES:
        if not Path(cand).exists():
            continue
        try:
            f = ImageFont.truetype(cand, 32)
            f.getbbox("あ")
            return cand
        except Exception:
            continue
    raise SystemExit(
        "no Japanese-capable font found; install one or add its path to "
        "lab/fixtures.py FONT_CANDIDATES")


def _render_line(text: str, font_path: str, font_size: int,
                 out_path: Path, noise: bool, seed: int) -> None:
    font = ImageFont.truetype(font_path, font_size)
    if text:
        box = font.getbbox(text)
        w = max(48, box[2] - box[0] + 24)
        h = font_size + 24
    else:
        w, h = 48, 32
    img = Image.new("L", (w, h), 255)
    draw = ImageDraw.Draw(img)
    if text:
        draw.text((12, 10), text, font=font, fill=0)

    if noise:
        rng = np.random.default_rng(seed)
        arr = np.asarray(img, np.float32)
        arr += rng.normal(0, 26, arr.shape)
        arr = np.clip(np.rint(arr), 0, 255).astype(np.uint8)
        img = Image.fromarray(arr, "L").rotate(3.0, expand=True,
                                               fillcolor=255, resample=Image.BILINEAR)

    img.save(out_path)


def generate(target: Path) -> dict:
    """Writes fixtures; returns {category: count} (real/ = 0, kept empty)."""
    target = Path(target)
    font_path = _find_font()
    print(f"  font: {font_path}")
    counts: dict[str, int] = {}

    for cat, strings in STRINGS.items():
        (target / cat).mkdir(parents=True, exist_ok=True)
        n = 0
        for i, text in enumerate(strings):
            name = f"sample_{i + 1:03d}"
            png = target / cat / f"{name}.png"
            if cat == "empty":
                Image.new("L", EMPTY_SIZES[i % len(EMPTY_SIZES)], 255).save(png)
                expected = ""
            elif cat == "noisy":
                _render_line(text, font_path, SIZES[i % len(SIZES)], png,
                             noise=True, seed=1234 + i)
                expected = text
            else:
                _render_line(text, font_path, SIZES[i % len(SIZES)], png,
                             noise=False, seed=0)
                expected = text
            (target / cat / f"{name}.expected.txt").write_text(
                expected + "\n", encoding="utf-8")
            n += 1
        counts[cat] = n

    (target / "real").mkdir(parents=True, exist_ok=True)
    counts["real"] = 0
    return counts
