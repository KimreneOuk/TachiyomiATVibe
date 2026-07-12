import os
import glob
import argparse
import numpy as np
from PIL import Image
import scipy.ndimage as ndi

import sys
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector

def get_gradients(gray):
    # Simple Sobel-like
    dx = ndi.sobel(gray, axis=1)
    dy = ndi.sobel(gray, axis=0)
    mag = np.hypot(dx, dy)
    return mag
def generate_pseudo_mask(crop, padding):
    rawRGB = crop[...,:3]
    rawGray = np.dot(rawRGB, [0.2989, 0.5870, 0.1140]).astype(np.uint8)
    h, w = rawGray.shape
    
    # Define writable inner bounding box
    inner_mask = np.zeros_like(rawGray, dtype=bool)
    if h > padding * 2 and w > padding * 2:
        inner_mask[padding:h-padding, padding:w-padding] = True
    else:
        inner_mask = np.ones_like(rawGray, dtype=bool)
        
    # 1. Three versions
    lowPassGray = ndi.median_filter(rawGray, size=5)
    highFreqGray = np.abs(rawGray.astype(np.int16) - lowPassGray.astype(np.int16)).astype(np.uint8)
    
    # Extract ring pixels from lowPassGray
    ring_pixels = lowPassGray[~inner_mask]
    if len(ring_pixels) == 0:
        ring_pixels = lowPassGray.flatten()
        
    median = np.median(ring_pixels)
    mad = np.median(np.abs(ring_pixels - median))
    if mad == 0: mad = 1.0
    
    # Mode C: Color text fallback detection
    # Convert to HSV-like saturation purely using numpy
    r, g, b = rawRGB[:,:,0].astype(float), rawRGB[:,:,1].astype(float), rawRGB[:,:,2].astype(float)
    max_c = np.maximum(np.maximum(r, g), b)
    min_c = np.minimum(np.minimum(r, g), b)
    saturation = np.where(max_c == 0, 0, (max_c - min_c) / max_c * 255)
    sat_mean = np.mean(saturation[inner_mask])
    
    # Mode Classification
    mode = "A" # Normal
    if sat_mean > 50:
        mode = "C" # Color text
    else:
        # Check for Mode B: White Outline
        # 1. Background context has high dark/screentone ratio
        # Let's say if the median is dark, or if there is a lot of dark pixels
        bg_is_dark = median < 100
        
        # 2. Central bright connected components
        k_bright = 2.0
        brightCandidate = rawGray > (median + k_bright * mad)
        labeled_b, num_b = ndi.label(brightCandidate & inner_mask)
        objects_b = ndi.find_objects(labeled_b)
        
        thick_bright_components = 0
        for i, slice_obj in enumerate(objects_b):
            if slice_obj is None: continue
            comp = (labeled_b == (i + 1))
            area = np.sum(comp)
            height = slice_obj[0].stop - slice_obj[0].start
            width = slice_obj[1].stop - slice_obj[1].start
            aspect = max(width, height) / max(1, min(width, height))
            
            # Thick/blob-like, not thin speedline
            if area > 30 and aspect < 5.0:
                thick_bright_components += 1
                
        if bg_is_dark and thick_bright_components > 0:
            mode = "B"
            
    # Process by Mode
    if mode == "C":
        # Fallback to whole inner bbox for color text right now
        return inner_mask, True, inner_mask

    elif mode == "B":
        # Mode B: White Outline Mode
        k_bright = 2.0
        brightCandidate = rawGray > (median + k_bright * mad)
        labeled_bright, num_features = ndi.label(brightCandidate & inner_mask)
        objects = ndi.find_objects(labeled_bright)
        
        center_y, center_x = h / 2, w / 2
        valid_components = []
        
        for i, slice_obj in enumerate(objects):
            if slice_obj is None: continue
            comp = (labeled_bright == (i + 1))
            area = np.sum(comp)
            
            y_slice, x_slice = slice_obj
            y1, y2 = y_slice.start, y_slice.stop
            x1, x2 = x_slice.start, x_slice.stop
            height = y2 - y1
            width = x2 - x1
            aspect = max(width, height) / max(1, min(width, height))
            cx = x1 + width/2
            cy = y1 + height/2
            dist_to_center = np.hypot(cx - center_x, cy - center_y)
            
            # Reject long/thin, touching edges, isolated
            if aspect > 5.0: continue
            if area < 15: continue
            
            touches_left = x1 <= padding + 2
            touches_right = x2 >= w - padding - 2
            touches_top = y1 <= padding + 2
            touches_bottom = y2 >= h - padding - 2
            if touches_left or touches_right or touches_top or touches_bottom:
                continue
                
            if dist_to_center > (w/2.0) and area < 40:
                continue
                
            valid_components.append({
                'mask': comp, 'cx': cx, 'cy': cy, 'area': area
            })
            
        whiteOutlineSeed = np.zeros_like(rawGray, dtype=bool)
        if len(valid_components) > 0:
            clusters = []
            for c in valid_components:
                placed = False
                for cl in clusters:
                    if abs(cl['mean_x'] - c['cx']) < 30:
                        cl['components'].append(c)
                        cl['mean_x'] = sum(x['cx'] for x in cl['components']) / len(cl['components'])
                        placed = True
                        break
                if not placed:
                    clusters.append({'mean_x': c['cx'], 'components': [c]})
                    
            for cl in clusters:
                comps = cl['components']
                for c in comps: whiteOutlineSeed |= c['mask']
                
        # Dilate whiteOutlineSeed slightly
        whiteOutlineNear = ndi.binary_dilation(whiteOutlineSeed, structure=np.ones((3,3)), iterations=2)
        
        # Add nearby dark text
        k_dark = 1.0
        darkCandidate = rawGray < (median - k_dark * mad)
        darkInnerText = darkCandidate & whiteOutlineNear
        
        # Add nearby gray/edge pixels
        grad = get_gradients(rawGray)
        if grad.size > 0:
            edgeCandidate = grad > np.percentile(grad, 80)
        else:
            edgeCandidate = np.zeros_like(grad, dtype=bool)
            
        haloMask = edgeCandidate & whiteOutlineNear
        
        # Final Mask
        finalMask = whiteOutlineSeed | darkInnerText | haloMask
        finalMask = ndi.binary_dilation(finalMask, structure=np.ones((3,3)), iterations=2)
        finalMask = finalMask & inner_mask
        
        # Confidence check
        total_area = h * w
        mask_area = np.sum(finalMask)
        seed_area = np.sum(whiteOutlineSeed)
        confidence_good = True
        if mask_area < 0.01 * total_area or mask_area > 0.85 * total_area: confidence_good = False
        if seed_area == 0: confidence_good = False
        
        return finalMask, confidence_good, inner_mask
        
    else:
        # Mode A: Normal Dark Text Mode
        k_dark = 2.0
        lowPassDarkSeed = lowPassGray < (median - k_dark * mad)
        
        k_bg = 1.0
        screentone_protect = (highFreqGray > 15) & (np.abs(lowPassGray - median) < k_bg * mad)
        lowPassDarkSeed = lowPassDarkSeed & (~screentone_protect)
        
        rawStrongDarkSeed = rawGray < 120
        labeled_raw, num_features_raw = ndi.label(rawStrongDarkSeed)
        objects_raw = ndi.find_objects(labeled_raw)
        cleanedRawStrongDarkSeed = np.zeros_like(rawStrongDarkSeed)
        for i, slice_obj in enumerate(objects_raw):
            if slice_obj is None: continue
            comp = (labeled_raw == (i + 1))
            if np.sum(comp) >= 10:
                cleanedRawStrongDarkSeed |= comp
                
        combinedDarkSeed = (lowPassDarkSeed | cleanedRawStrongDarkSeed) & inner_mask
        
        labeled, num_features = ndi.label(combinedDarkSeed)
        objects = ndi.find_objects(labeled)
        
        center_y, center_x = h / 2, w / 2
        
        valid_components = []
        for i, slice_obj in enumerate(objects):
            if slice_obj is None: continue
            comp = (labeled == (i + 1))
            area = np.sum(comp)
            
            y_slice, x_slice = slice_obj
            y1, y2 = y_slice.start, y_slice.stop
            x1, x2 = x_slice.start, x_slice.stop
            height = y2 - y1
            width = x2 - x1
            aspect = max(width, height) / max(1, min(width, height))
            cx = x1 + width/2
            cy = y1 + height/2
            dist_to_center = np.hypot(cx - center_x, cy - center_y)
            
            if area < 10: continue
            if aspect > 8 and area < 150: continue
                
            touches_left = x1 <= padding + 2
            touches_right = x2 >= w - padding - 2
            if touches_left and touches_right and width > (w - 2*padding)*0.8: continue
                
            touches_top = y1 <= padding + 2
            touches_bottom = y2 >= h - padding - 2
            if touches_top and touches_bottom and height > (h - 2*padding)*0.8: continue
                
            if dist_to_center > (w/2.5) and area < 25 and aspect < 3: continue
                
            valid_components.append({
                'mask': comp, 'cx': cx, 'cy': cy,
                'area': area, 'aspect': aspect, 'width': width, 'height': height
            })
            
        filtered_text_seed = np.zeros_like(combinedDarkSeed)
        if len(valid_components) > 0:
            clusters = []
            for c in valid_components:
                placed = False
                for cl in clusters:
                    if abs(cl['mean_x'] - c['cx']) < 30:
                        cl['components'].append(c)
                        cl['mean_x'] = sum(x['cx'] for x in cl['components']) / len(cl['components'])
                        placed = True
                        break
                if not placed:
                    clusters.append({'mean_x': c['cx'], 'components': [c]})
                    
            for cl in clusters:
                comps = cl['components']
                if len(comps) >= 2:
                    for c in comps: filtered_text_seed |= c['mask']
                elif len(comps) == 1:
                    c = comps[0]
                    if c['height'] > c['width'] or c['area'] > 60:
                        filtered_text_seed |= c['mask']
                        
        darkTextNear = ndi.binary_dilation(filtered_text_seed, structure=np.ones((3,3)), iterations=3)
        
        k_bright = 2.0
        grayWhiteText = rawGray < (median + k_bright * mad)
        grayWhiteText = grayWhiteText & darkTextNear
        
        grad = get_gradients(rawGray)
        if grad.size > 0:
            edgeCandidate = grad > np.percentile(grad, 80)
        else:
            edgeCandidate = np.zeros_like(grad, dtype=bool)
            
        edgeText = edgeCandidate & darkTextNear
        
        finalMask = filtered_text_seed | grayWhiteText | edgeText
        finalMask = ndi.binary_closing(finalMask, structure=np.ones((3,3)), iterations=2)
        finalMask = ndi.binary_dilation(finalMask, structure=np.ones((3,3)), iterations=1)
        
        max_expansion = ndi.binary_dilation(darkTextNear, iterations=2)
        finalMask = finalMask & max_expansion & inner_mask
        
        total_area = h * w
        mask_area = np.sum(finalMask)
        seed_area = np.sum(filtered_text_seed)
        
        confidence_good = True
        if mask_area < 0.01 * total_area or mask_area > 0.85 * total_area: confidence_good = False
        if seed_area == 0: confidence_good = False
        if seed_area > 0 and (mask_area / seed_area) > 10.0: confidence_good = False
            
        return finalMask, confidence_good, inner_mask

def classify_background(image, mask, inner_mask):
    """Classifies background into FLAT, SCREENTONE, SPEEDLINES, MIXED"""
    rawGray = np.dot(image[...,:3], [0.2989, 0.5870, 0.1140]).astype(np.float32)
    bg_mask = (~mask) & inner_mask
    if np.sum(bg_mask) < 100:
        bg_mask = ~mask
        
    bg_pixels = rawGray[bg_mask]
    if len(bg_pixels) == 0:
        return "FLAT"
        
    variance = np.var(bg_pixels)
    
    # 1. Flat check
    if variance < 30: # Very low variance
        return "FLAT"
        
    # High frequency check for screentone
    lowPassGray = ndi.median_filter(rawGray, size=5)
    highFreqGray = np.abs(rawGray - lowPassGray)
    hf_energy = np.mean(highFreqGray[bg_mask])
    
    # Directional check for speedlines
    grad_y = ndi.sobel(rawGray, axis=0)
    grad_x = ndi.sobel(rawGray, axis=1)
    
    g_y_bg = grad_y[bg_mask]
    g_x_bg = grad_x[bg_mask]
    magnitudes = np.hypot(g_x_bg, g_y_bg)
    
    strong_edges = magnitudes > np.percentile(magnitudes, 75) if len(magnitudes)>0 else []
    if np.sum(strong_edges) > 50:
        angles = np.arctan2(g_y_bg[strong_edges], g_x_bg[strong_edges]) * 180 / np.pi
        angles = angles % 180 # 0 to 180
        
        hist, bins = np.histogram(angles, bins=18, range=(0,180))
        max_bin = np.max(hist)
        total_strong = np.sum(hist)
        
        if total_strong > 0 and (max_bin / total_strong) > 0.3:
            return "SPEEDLINES"
            
    if hf_energy > 12:
        return "SCREENTONE"
        
    return "MIXED"

def inpaint_hybrid(image, mask, inner_mask):
    h, w, c = image.shape
    
    # 0. Force Expand Mask Slightly to kill anti-aliased edge ghosts
    dilated_mask = ndi.binary_dilation(mask, iterations=2)
    unknown = dilated_mask & inner_mask
    
    bg_type = classify_background(image, dilated_mask, inner_mask)
    
    # 1. Base Reconstruction: INTENTIONALLY DESTROY SILHOUETTES
    # Initialize the filled image by completely wiping the unknown pixels to the median background color
    filled = image.copy().astype(np.float32)
    safe_bg = (~dilated_mask) & inner_mask
    if np.sum(safe_bg) < 100:
        safe_bg = ~dilated_mask
        
    bg_median = np.median(image[safe_bg], axis=0) if np.sum(safe_bg) > 0 else np.array([255, 255, 255])
    unknown_3d = np.repeat(unknown[:,:,np.newaxis], c, axis=2)
    filled = np.where(unknown_3d, bg_median, filled)
    
    # Telea-style boundary propagation over the wiped area
    temp_unknown = unknown.copy()
    for i in range(40):
        if not np.any(temp_unknown):
            break
        boundary = ndi.binary_dilation(temp_unknown, iterations=1) ^ temp_unknown
        
        neighbors = np.zeros((h, w, c), dtype=np.float32)
        counts = np.zeros((h, w, c), dtype=np.float32)
        
        for dy, dx in [(-1,0), (1,0), (0,-1), (0,1), (-1,-1), (-1,1), (1,-1), (1,1)]:
            shifted_img = np.roll(np.roll(filled, dy, axis=0), dx, axis=1)
            shifted_unk = np.roll(np.roll(temp_unknown, dy, axis=0), dx, axis=1)
            valid = (~shifted_unk)
            valid_3d = np.repeat(valid[:, :, np.newaxis], c, axis=2)
            
            neighbors += np.where(valid_3d, shifted_img, 0)
            counts += valid_3d
            
        counts[counts == 0] = 1
        avg = neighbors / counts
        
        boundary_3d = np.repeat(boundary[:, :, np.newaxis], c, axis=2)
        filled = np.where(boundary_3d, avg, filled)
        temp_unknown = temp_unknown & (~boundary)
        
    # Heavily blur the base layer inside the mask to guarantee silhouette destruction
    base_blurred = np.zeros_like(filled)
    for c_idx in range(c):
        base_blurred[:,:,c_idx] = ndi.gaussian_filter(filled[:,:,c_idx], sigma=3.0)
    
    filled = np.where(unknown_3d, base_blurred, filled)
    
    # Stage 2 & 3: Texture Transfer
    texture = filled.copy()
    bg_y, bg_x = np.nonzero(safe_bg)
    
    if len(bg_y) > 0 and bg_type != "FLAT":
        mask_y, mask_x = np.nonzero(unknown)
        
        if bg_type in ["SCREENTONE", "MIXED"]:
            patch_size = 13
            patch_found = False
            for _ in range(30): # try harder to find a safe patch
                idx = np.random.randint(0, len(bg_y))
                sy, sx = bg_y[idx], bg_x[idx]
                if sy-patch_size > 0 and sy+patch_size < h and sx-patch_size > 0 and sx+patch_size < w:
                    if np.all(safe_bg[sy-patch_size:sy+patch_size, sx-patch_size:sx+patch_size]):
                        patch_found = True
                        for i in range(len(mask_y)):
                            my, mx = mask_y[i], mask_x[i]
                            ty = sy - patch_size//2 + (my % patch_size)
                            tx = sx - patch_size//2 + (mx % patch_size)
                            
                            # V7: Hard Texture Transfer with Tone Matching (NO 50/50 BLENDING)
                            # Contrast from source, brightness from local base fill
                            for c_idx in range(c):
                                src_val = image[ty, tx, c_idx]
                                src_patch = image[sy-patch_size//2:sy+patch_size//2, sx-patch_size//2:sx+patch_size//2, c_idx]
                                src_mean = np.mean(src_patch) if src_patch.size > 0 else src_val
                                
                                base_val = filled[my, mx, c_idx]
                                # Add the source high-frequency texture to the local base low-frequency tone
                                texture[my, mx, c_idx] = np.clip(base_val + (src_val - src_mean), 0, 255)
                        break
            
            if not patch_found:
                # Fallback tiling
                for i in range(len(mask_y)):
                    my, mx = mask_y[i], mask_x[i]
                    idx = np.random.randint(0, len(bg_y))
                    for c_idx in range(c):
                        src_val = image[bg_y[idx], bg_x[idx], c_idx]
                        base_val = filled[my, mx, c_idx]
                        texture[my, mx, c_idx] = np.clip(base_val + (src_val - bg_median[c_idx]), 0, 255)
                        
        elif bg_type == "SPEEDLINES":
            patch_size = 7
            for i in range(len(mask_y)):
                my, mx = mask_y[i], mask_x[i]
                idx = np.random.randint(0, len(bg_y))
                sy, sx = bg_y[idx], bg_x[idx]
                for dy in range(-patch_size//2 + 1, patch_size//2 + 1):
                    for dx in range(-patch_size//2 + 1, patch_size//2 + 1):
                        t_y, t_x = my + dy, mx + dx
                        s_y, s_x = sy + dy, sx + dx
                        if 0 <= t_y < h and 0 <= t_x < w and 0 <= s_y < h and 0 <= s_x < w:
                            if unknown[t_y, t_x]:
                                for c_idx in range(c):
                                    texture[t_y, t_x, c_idx] = image[s_y, s_x, c_idx]

    # Stage 4: Seam Cleanup
    # V7 Overwrite rule: 100% replacement inside eroded mask, 1-2px feather on the edge
    mask_eroded = ndi.binary_erosion(unknown, iterations=1)
    mask_boundary = unknown ^ mask_eroded
    
    result = image.astype(np.float32)
    mask_eroded_3d = np.repeat(mask_eroded[:,:,np.newaxis], c, axis=2)
    result = np.where(mask_eroded_3d, texture, result)
    
    mask_boundary_3d = np.repeat(mask_boundary[:,:,np.newaxis], c, axis=2)
    alpha = ndi.gaussian_filter(unknown.astype(np.float32), sigma=1.0)
    alpha_3d = np.repeat(alpha[:,:,np.newaxis], c, axis=2)
    
    boundary_blend = texture * alpha_3d + image * (1.0 - alpha_3d)
    result = np.where(mask_boundary_3d, boundary_blend, result)
    
    inner_mask_3d = np.repeat(inner_mask[:,:,np.newaxis], c, axis=2)
    result = np.where(inner_mask_3d, result, image)
    
    return np.clip(result, 0, 255).astype(np.uint8)

def process_free_text_box(img_arr, box):
    padding = 15
    h, w, _ = img_arr.shape
    x1, y1, x2, y2 = box
    x1 = max(0, x1 - padding)
    y1 = max(0, y1 - padding)
    x2 = min(w, x2 + padding)
    y2 = min(h, y2 + padding)
    
    crop = img_arr[y1:y2, x1:x2]
    if crop.size == 0:
        return img_arr, None
        
    mask, is_good, inner_mask = generate_pseudo_mask(crop, padding)
    
    if is_good:
        inpainted_crop = inpaint_hybrid(crop, mask, inner_mask)
    else:
        # Fallback: full inner box
        inpainted_crop = inpaint_hybrid(crop, inner_mask, inner_mask)
        
    result = img_arr.copy()
    result[y1:y2, x1:x2] = inpainted_crop
    
    debug_mask = (mask * 255).astype(np.uint8)
    debug_mask_rgb = np.stack([debug_mask, debug_mask, debug_mask], axis=-1)
    
    # Outline the bbox in crop for visualization
    crop_viz = crop.copy()
    cvh, cvw, _ = crop_viz.shape
    if cvh > padding*2 and cvw > padding*2:
        # draw a thin red rectangle representing the original box
        crop_viz[padding:cvh-padding, padding:padding+2] = [255, 0, 0]
        crop_viz[padding:cvh-padding, cvw-padding-2:cvw-padding] = [255, 0, 0]
        crop_viz[padding:padding+2, padding:cvw-padding] = [255, 0, 0]
        crop_viz[cvh-padding-2:cvh-padding, padding:cvw-padding] = [255, 0, 0]

    debug_viz = np.concatenate([crop_viz, debug_mask_rgb, inpainted_crop], axis=1)
    
    return result, debug_viz
    
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input_folder")
    parser.add_argument("out_folder")
    args = parser.parse_args()

    os.makedirs(args.out_folder, exist_ok=True)
    os.makedirs(os.path.join(args.out_folder, "debug"), exist_ok=True)

    print("Loading Detector v4 (ONNX)...")
    detector_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "detection", "detector-v4-s_int8.onnx")
    detector = OnnxPageTextDetector(detector_path)
    
    if os.path.isfile(args.input_folder):
        image_paths = [args.input_folder]
    else:
        image_paths = glob.glob(os.path.join(args.input_folder, "*.jpg"))
        image_paths.extend(glob.glob(os.path.join(args.input_folder, "*.png")))
        
    for img_path in sorted(image_paths):
        base = os.path.basename(img_path)
        img_pil = Image.open(img_path).convert("RGB")
        
        boxes = detector.detect(img_pil)
        free_texts = [b for b in boxes if b.label == 2] # text_free only
        
        if not free_texts:
            continue
            
        print(f"Processing {base} ({len(free_texts)} free texts)")
        img_arr = np.array(img_pil)
        
        debug_list = []
        for i, box in enumerate(free_texts):
            bbox = [int(box.x1), int(box.y1), int(box.x2), int(box.y2)]
            img_arr, debug_viz = process_free_text_box(img_arr, bbox)
            if debug_viz is not None:
                debug_list.append(debug_viz)
                
        out_path = os.path.join(args.out_folder, base)
        Image.fromarray(img_arr).save(out_path)
        
        if debug_list:
            max_w = max(d.shape[1] for d in debug_list)
            padded_debugs = []
            for d in debug_list:
                dh, dw, dc = d.shape
                if dw < max_w:
                    pad = np.zeros((dh, max_w - dw, dc), dtype=np.uint8)
                    d = np.concatenate([d, pad], axis=1)
                padded_debugs.append(d)
                
            stacked = np.concatenate(padded_debugs, axis=0)
            Image.fromarray(stacked).save(os.path.join(args.out_folder, "debug", base))
            print(f"  -> Debug saved to debug/{base}")

if __name__ == "__main__":
    main()
