#!/usr/bin/env python3
"""
Inspect and extract representative real manga pages/crops and realistic masks.
Source Dataset:
"Drawing: Saikyou Mangaka wa Oekaki Skill de Isekai Musou Suru"
Chapters: chapter-201.410030 (16 pages) and chapter-202.412574 (18 pages)
Separation:
- Calibration Candidates: Chapter 201
- Held-out Evaluation: Chapter 202
"""

import os
import json
import numpy as np
import cv2
from PIL import Image

DATA_DIR = "audit/reference_data"
REPORTS_DIR = "audit/reports"
BASE_DIR = r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE-inpainting-modes-redesign\tools\downloader\output\drawing-saikyou-mangaka-wa-oekaki-skill-de-isekai-musou-suru"

def profile_dataset():
    chapters = ["chapter-201.410030", "chapter-202.412574"]
    manifest = {
        "dataset_name": "Drawing: Saikyou Mangaka wa Oekaki Skill de Isekai Musou Suru",
        "provenance_path": BASE_DIR,
        "chapters": {},
        "summary": {}
    }

    total_pages = 0
    total_bytes = 0
    color_pages = []
    monochrome_pages = []
    all_lap_vars = []
    all_midtones = []

    for ch in chapters:
        ch_path = os.path.join(BASE_DIR, ch)
        fnames = sorted(os.listdir(ch_path), key=lambda x: int(os.path.splitext(x)[0]) if os.path.splitext(x)[0].isdigit() else x)
        pages_info = []

        for fn in fnames:
            fpath = os.path.join(ch_path, fn)
            sz = os.path.getsize(fpath)
            total_bytes += sz
            total_pages += 1

            im = cv2.imread(fpath, cv2.IMREAD_COLOR)
            h, w, c = im.shape

            # Grayscale vs Color check
            diff_rg = int(np.max(np.abs(im[:, :, 0].astype(int) - im[:, :, 1].astype(int))))
            diff_rb = int(np.max(np.abs(im[:, :, 0].astype(int) - im[:, :, 2].astype(int))))
            is_color = (diff_rg > 15) or (diff_rb > 15)
            page_id = f"{ch}/{fn}"
            if is_color:
                color_pages.append(page_id)
            else:
                monochrome_pages.append(page_id)

            gray = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)

            # Texture & Screentone Metrics
            lap = cv2.Laplacian(gray, cv2.CV_64F)
            lap_var = float(lap.var())
            all_lap_vars.append(lap_var)

            midtones_pct = float(np.sum((gray >= 80) & (gray <= 220)) / (h * w) * 100.0)
            all_midtones.append(midtones_pct)

            # Connected component analysis for speech bubble detection
            thresh = cv2.threshold(gray, 240, 255, cv2.THRESH_BINARY)[1]
            cnts, _ = cv2.findContours(thresh, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
            detected_bubbles = 0
            for cnt in cnts:
                bx, by, bw, bh = cv2.boundingRect(cnt)
                area = cv2.contourArea(cnt)
                if 12000 < area < 250000 and 0.4 < (bw / bh) < 2.5:
                    if bx > 30 and by > 30 and bx + bw < w - 30 and by + bh < h - 30:
                        roi = gray[by:by+bh, bx:bx+bw]
                        if np.sum(roi < 80) > 400:
                            detected_bubbles += 1

            pages_info.append({
                "filename": fn,
                "width": w,
                "height": h,
                "aspect_ratio": round(w / h, 4),
                "file_size_bytes": sz,
                "file_size_kb": round(sz / 1024, 1),
                "is_color": is_color,
                "max_channel_diff": max(diff_rg, diff_rb),
                "mean_brightness": round(float(np.mean(gray)), 2),
                "laplacian_variance": round(lap_var, 2),
                "midtone_percentage": round(midtones_pct, 2),
                "detected_speech_bubbles": detected_bubbles
            })

        manifest["chapters"][ch] = {
            "page_count": len(pages_info),
            "total_bytes": sum(p["file_size_bytes"] for p in pages_info),
            "pages": pages_info
        }

    manifest["summary"] = {
        "total_chapters": len(chapters),
        "total_pages": total_pages,
        "total_size_mb": round(total_bytes / (1024 * 1024), 2),
        "uniform_dimensions": "1350 x 1920",
        "file_format": "JPEG",
        "color_space": "RGB 24-bit",
        "monochrome_pages_count": len(monochrome_pages),
        "color_pages_count": len(color_pages),
        "color_pages": color_pages,
        "mean_laplacian_variance": round(float(np.mean(all_lap_vars)), 2),
        "max_laplacian_variance": round(float(np.max(all_lap_vars)), 2),
        "min_laplacian_variance": round(float(np.min(all_lap_vars)), 2),
        "mean_midtone_percentage": round(float(np.mean(all_midtones)), 2),
        "split_provenance": {
            "calibration_pool": "chapter-201.410030 (16 pages)",
            "held_out_evaluation_pool": "chapter-202.412574 (18 pages)"
        }
    }

    os.makedirs(REPORTS_DIR, exist_ok=True)
    manifest_path = os.path.join(REPORTS_DIR, "real_manga_dataset_manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"[+] Dataset manifest saved to {manifest_path}")
    return manifest

def extract_representative_test_cases():
    os.makedirs(os.path.join(DATA_DIR, "test_images"), exist_ok=True)
    os.makedirs(os.path.join(DATA_DIR, "test_masks"), exist_ok=True)

    # 8 Representative Test Cases covering both chapters and Section 6 split
    test_cases_defs = [
        # Calibration Candidates (Chapter 201)
        {
            "id": "real_case1_ch201_p1_screentone_bubble",
            "ch": "chapter-201.410030",
            "page": 1,
            "center": (651, 502),
            "bubble": (519, 375, 265, 254),
            "split": "calibration",
            "category": "speech_bubble_dense_screentone",
            "description": "Dialogue speech bubble situated against dense background screentone"
        },
        {
            "id": "real_case2_ch201_p2_lineart_character",
            "ch": "chapter-201.410030",
            "page": 2,
            "center": (1029, 836),
            "bubble": (845, 662, 369, 348),
            "split": "calibration",
            "category": "line_art_character_intersection",
            "description": "Speech bubble boundary intersecting fine line art of character hair and facial contours"
        },
        {
            "id": "real_case3_ch201_p8_heavy_tone_dialogue",
            "ch": "chapter-201.410030",
            "page": 8,
            "center": (811, 564),
            "bubble": (659, 335, 304, 459),
            "split": "calibration",
            "category": "heavy_screentone_shadow",
            "description": "Heavy screentone shadow background with dark hatching and dialogue bubble"
        },
        {
            "id": "real_case4_ch201_p10_color_speech_bubble",
            "ch": "chapter-201.410030",
            "page": 10,
            "center": (278, 712),
            "bubble": (113, 498, 425, 429),
            "split": "calibration",
            "category": "color_illustration_bubble",
            "description": "Full-color painted manga illustration with integrated dialogue bubble"
        },

        # Held-Out Evaluation Cases (Chapter 202)
        {
            "id": "real_case5_ch202_p1_splash_screentone",
            "ch": "chapter-202.412574",
            "page": 1,
            "center": (675, 960),
            "bubble": (500, 800, 350, 320),
            "split": "held_out",
            "category": "splash_art_screentone_overlay",
            "description": "Cover/splash illustration with extreme screentone halftone frequency and text overlay"
        },
        {
            "id": "real_case6_ch202_p3_gradient_shading_bubble",
            "ch": "chapter-202.412574",
            "page": 3,
            "center": (797, 1677),
            "bubble": (632, 1470, 330, 415),
            "split": "held_out",
            "category": "gradient_screentone_closeup",
            "description": "Character close-up with soft gradient screentones and speech bubble"
        },
        {
            "id": "real_case7_ch202_p6_dense_bubble_cluster",
            "ch": "chapter-202.412574",
            "page": 6,
            "center": (970, 706),
            "bubble": (695, 573, 550, 267),
            "split": "held_out",
            "category": "dense_bubble_panel_cluster",
            "description": "High-density multi-panel layout with speech bubble clusters and vertical dialogue"
        },
        {
            "id": "real_case8_ch202_p11_color_action_bubble",
            "ch": "chapter-202.412574",
            "page": 11,
            "center": (820, 1153),
            "bubble": (663, 912, 314, 483),
            "split": "held_out",
            "category": "color_action_scene",
            "description": "Full-color action scene with saturated color artwork and dialogue bubble"
        }
    ]

    extracted_cases = []

    for c in test_cases_defs:
        fpath = os.path.join(BASE_DIR, c["ch"], f"{c['page']}.jpg")
        img_bgr = cv2.imread(fpath, cv2.IMREAD_COLOR)
        h, w, _ = img_bgr.shape

        cx, cy = c["center"]
        x0 = max(0, min(w - 512, cx - 256))
        y0 = max(0, min(h - 512, cy - 256))

        crop_bgr = img_bgr[y0:y0+512, x0:x0+512].copy()
        crop_rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
        gray_crop = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2GRAY)

        bx, by, bw, bh = c["bubble"]
        # Coordinate translation into crop local space
        lbx = max(0, bx - x0)
        lby = max(0, by - y0)
        lbw = min(512 - lbx, bw)
        lbh = min(512 - lby, bh)

        mask = np.zeros((512, 512), dtype=np.uint8)

        # Derive realistic text inpainting mask inside bubble
        roi = gray_crop[lby:lby+lbh, lbx:lbx+lbw]

        # For color pages vs grayscale pages, detect text strokes
        if c["category"].startswith("color"):
            # in color pages, dialogue bubble interior is white/near-white
            text_mask = ((roi < 120) & (roi > 5)).astype(np.uint8) * 255
        elif c["id"] == "real_case5_ch202_p1_splash_screentone":
            # Splash art overlay: synthesize realistic text overlay mask across screentone
            cv2.rectangle(mask, (150, 180, 360, 320), 255, -1)
            text_mask = None
        else:
            # Grayscale manga speech bubble text
            text_mask = ((roi < 110) & (roi > 0)).astype(np.uint8) * 255

        if text_mask is not None:
            # Structuring element: 5x5 elliptical dilation (standard OCR text dilation in Tachiyomi AOT)
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
            dilated = cv2.dilate(text_mask, kernel, iterations=2)
            # Remove any outer border touching edges
            mask[lby:lby+lbh, lbx:lbx+lbw] = dilated

        # If mask is sparse (< 500 px), expand slightly to form realistic OCR bounding regions
        mask_px_count = int(np.sum(mask > 127))
        if mask_px_count < 1000:
            # Add text bounding block mask
            cv2.rectangle(mask, (lbx + 20, lby + 20, lbw - 40, lbh - 40), 255, -1)
            mask_px_count = int(np.sum(mask > 127))

        # Save test image (RGB PNG) and mask (Grayscale PNG)
        img_out_path = os.path.join(DATA_DIR, "test_images", f"{c['id']}.png")
        mask_out_path = os.path.join(DATA_DIR, "test_masks", f"{c['id']}_mask.png")

        Image.fromarray(crop_rgb).save(img_out_path)
        Image.fromarray(mask).save(mask_out_path)

        c_info = dict(c)
        c_info["crop_origin"] = (x0, y0)
        c_info["crop_size"] = (512, 512)
        c_info["mask_pixels"] = mask_px_count
        c_info["mask_coverage_pct"] = round(mask_px_count / (512 * 512) * 100.0, 2)
        c_info["img_path"] = img_out_path
        c_info["mask_path"] = mask_out_path
        extracted_cases.append(c_info)

        print(f"Extracted {c['id']}: origin=({x0},{y0}), mask_px={mask_px_count} ({c_info['mask_coverage_pct']}%) -> {img_out_path}")

    cases_json_path = os.path.join(REPORTS_DIR, "real_manga_test_cases.json")
    with open(cases_json_path, "w", encoding="utf-8") as f:
        json.dump(extracted_cases, f, indent=2)
    print(f"[+] Saved test case registry to {cases_json_path}")
    return extracted_cases

if __name__ == "__main__":
    print("=== Step 1: Profiling Dataset Provenance & Page Statistics ===")
    profile_dataset()
    print("\n=== Step 2: Extracting Representative 512x512 Manga Test Cases ===")
    extract_representative_test_cases()
