#!/usr/bin/env python3
"""
Generate reproducible synthetic manga test patterns for LaMa inpainting baseline audit.
These test patterns specifically evaluate:
1. Dense screentones / halftone dot matrices
2. Fine line art and boundary edge preservation
3. Manga tone gradients (dithered halftones)
4. Radial action lines
5. Speech bubbles intersecting line art
6. Large SFX overlay text masks
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

DATA_DIR = "audit/reference_data"

def make_screentone_pattern(size, dot_spacing=8, dot_radius=2):
    """Generate a 2D halftone screentone dot grid."""
    img = np.ones((size, size), dtype=np.uint8) * 255
    y, x = np.ogrid[:size, :size]
    mask = ((x % dot_spacing - dot_spacing // 2) ** 2 +
            (y % dot_spacing - dot_spacing // 2) ** 2) <= dot_radius ** 2
    img[mask] = 0
    return img

def make_gradient_screentone(size):
    """Generate a variable-density manga screentone gradient."""
    img = np.ones((size, size), dtype=np.uint8) * 255
    spacing = 8
    for y in range(0, size, spacing):
        for x in range(0, size, spacing):
            # Density increases from left to right (radius 0 to 4)
            density = x / size
            r = int(round(density * 3.8))
            if r > 0:
                y_idx, x_idx = np.ogrid[max(0, y - r):min(size, y + r + 1),
                                        max(0, x - r):min(size, x + r + 1)]
                mask = ((x_idx - x) ** 2 + (y_idx - y) ** 2) <= r ** 2
                img[max(0, y - r):min(size, y + r + 1),
                    max(0, x - r):min(size, x + r + 1)][mask] = 0
    return img

def generate_test_cases():
    os.makedirs(f"{DATA_DIR}/test_images", exist_ok=True)
    os.makedirs(f"{DATA_DIR}/test_masks", exist_ok=True)
    cases = []

    # Case 1: Speech bubble on dense screentone
    img1 = make_screentone_pattern(512, dot_spacing=6, dot_radius=2)
    pil1 = Image.fromarray(img1).convert("RGB")
    draw1 = ImageDraw.Draw(pil1)
    # Speech bubble (white oval with black stroke)
    draw1.ellipse([160, 160, 360, 340], fill=(255, 255, 255), outline=(0, 0, 0), width=4)
    # Simulated Japanese text vertical strokes inside bubble
    for x_pos in [240, 260, 280]:
        for y_pos in range(190, 300, 16):
            draw1.rectangle([x_pos - 3, y_pos - 4, x_pos + 3, y_pos + 4], fill=(0, 0, 0))
    # Mask: covers text inside bubble
    mask1 = Image.new("L", (512, 512), 0)
    dmask1 = ImageDraw.Draw(mask1)
    dmask1.rectangle([220, 180, 300, 315], fill=255)
    pil1.save(f"{DATA_DIR}/test_images/case1_bubble_screentone.png")
    mask1.save(f"{DATA_DIR}/test_masks/case1_bubble_screentone_mask.png")
    cases.append("case1_bubble_screentone")

    # Case 2: Fine line art intersecting mask (character boundary)
    pil2 = Image.new("RGB", (512, 512), (255, 255, 255))
    draw2 = ImageDraw.Draw(pil2)
    # Draw hair/face outlines
    for offset in range(-50, 60, 15):
        draw2.arc([100 + offset, 50, 400 + offset, 450], 45, 220, fill=(0, 0, 0), width=2)
    # Cross-hatching shadow
    for i in range(150, 320, 8):
        draw2.line([(i, 200), (i + 60, 280)], fill=(0, 0, 0), width=1)
    # Mask cutting across cross-hatching and character boundary
    mask2 = Image.new("L", (512, 512), 0)
    dmask2 = ImageDraw.Draw(mask2)
    dmask2.rectangle([180, 210, 270, 270], fill=255)
    pil2.save(f"{DATA_DIR}/test_images/case2_lineart_crosshatch.png")
    mask2.save(f"{DATA_DIR}/test_masks/case2_lineart_crosshatch_mask.png")
    cases.append("case2_lineart_crosshatch")

    # Case 3: Manga screentone gradient with text mask
    img3 = make_gradient_screentone(512)
    pil3 = Image.fromarray(img3).convert("RGB")
    draw3 = ImageDraw.Draw(pil3)
    # Text strokes across gradient
    for x in range(180, 340, 20):
        draw3.rectangle([x, 220, x + 8, 290], fill=(0, 0, 0))
    mask3 = Image.new("L", (512, 512), 0)
    dmask3 = ImageDraw.Draw(mask3)
    dmask3.rectangle([170, 210, 350, 300], fill=255)
    pil3.save(f"{DATA_DIR}/test_images/case3_gradient_tone.png")
    mask3.save(f"{DATA_DIR}/test_masks/case3_gradient_tone_mask.png")
    cases.append("case3_gradient_tone")

    # Case 4: Radial action lines with solid black and white regions
    pil4 = Image.new("RGB", (512, 512), (255, 255, 255))
    draw4 = ImageDraw.Draw(pil4)
    cx, cy = 256, 256
    for angle in np.linspace(0, 2 * np.pi, 72, endpoint=False):
        ex = cx + int(300 * np.cos(angle))
        ey = cy + int(300 * np.sin(angle))
        draw4.line([(cx + int(40 * np.cos(angle)), cy + int(40 * np.sin(angle))), (ex, ey)], fill=(0, 0, 0), width=3)
    mask4 = Image.new("L", (512, 512), 0)
    dmask4 = ImageDraw.Draw(mask4)
    dmask4.ellipse([200, 200, 312, 312], fill=255)
    pil4.save(f"{DATA_DIR}/test_images/case4_radial_action_lines.png")
    mask4.save(f"{DATA_DIR}/test_masks/case4_radial_action_lines_mask.png")
    cases.append("case4_radial_action_lines")

    # Case 5: Large SFX text overlay over solid black and white
    pil5 = Image.new("RGB", (512, 512), (255, 255, 255))
    draw5 = ImageDraw.Draw(pil5)
    draw5.rectangle([0, 0, 256, 512], fill=(0, 0, 0)) # half black, half white
    # SFX polygon covering border
    sfx_poly = [(200, 100), (320, 120), (350, 380), (210, 420), (180, 260)]
    draw5.polygon(sfx_poly, fill=(200, 200, 200), outline=(0, 0, 0))
    mask5 = Image.new("L", (512, 512), 0)
    dmask5 = ImageDraw.Draw(mask5)
    dmask5.polygon(sfx_poly, fill=255)
    pil5.save(f"{DATA_DIR}/test_images/case5_sfx_split_boundary.png")
    mask5.save(f"{DATA_DIR}/test_masks/case5_sfx_split_boundary_mask.png")
    cases.append("case5_sfx_split_boundary")

    print(f"Generated {len(cases)} synthetic manga benchmark cases in {DATA_DIR}.")

if __name__ == "__main__":
    generate_test_cases()
