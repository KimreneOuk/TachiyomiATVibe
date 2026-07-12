import cv2
import numpy as np
import scipy.ndimage as ndi
import os
import sys

import sys
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector

def load_detector_v4(model_path):
    print("Loading Detector v4 (ONNX)...")
    return OnnxPageTextDetector(model_path)

def detect_text_v4(image, detector):
    return detector.detect(image)

def get_gradients(gray):
    dy = ndi.sobel(gray, axis=0)
    dx = ndi.sobel(gray, axis=1)
    mag = np.hypot(dx, dy)
    return mag

def generate_pseudo_mask(crop, padding):
    rawRGB = crop[...,:3]
    rawGray = np.dot(rawRGB, [0.2989, 0.5870, 0.1140]).astype(np.uint8)
    h, w = rawGray.shape
    
    inner_mask = np.zeros_like(rawGray, dtype=bool)
    if h > padding * 2 and w > padding * 2:
        inner_mask[padding:h-padding, padding:w-padding] = True
    else:
        inner_mask = np.ones_like(rawGray, dtype=bool)
        
    lowPassGray = ndi.median_filter(rawGray, size=5)
    highFreqGray = np.abs(rawGray.astype(np.int16) - lowPassGray.astype(np.int16)).astype(np.uint8)
    
    ring_pixels = lowPassGray[~inner_mask]
    if len(ring_pixels) == 0:
        ring_pixels = lowPassGray.flatten()
        
    median = np.median(ring_pixels)
    mad = np.median(np.abs(ring_pixels - median))
    if mad == 0: mad = 1.0
    
    # Convert to HSV-like saturation purely using numpy
    r, g, b = rawRGB[:,:,0].astype(float), rawRGB[:,:,1].astype(float), rawRGB[:,:,2].astype(float)
    max_c = np.maximum(np.maximum(r, g), b)
    min_c = np.minimum(np.minimum(r, g), b)
    saturation = np.where(max_c == 0, 0, (max_c - min_c) / max_c * 255)
    sat_mean = np.mean(saturation[inner_mask])
    
    mode = "A" # Normal
    if sat_mean > 50:
        mode = "C" # Color text
    else:
        bg_is_dark = median < 100
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
            
            if area > 30 and aspect < 5.0:
                thick_bright_components += 1
                
        if bg_is_dark and thick_bright_components > 0:
            mode = "B"
            
    if mode == "C":
        return inner_mask, True, inner_mask

    elif mode == "B":
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
                
        whiteOutlineNear = ndi.binary_dilation(whiteOutlineSeed, structure=np.ones((3,3)), iterations=2)
        
        k_dark = 1.0
        darkCandidate = rawGray < (median - k_dark * mad)
        darkInnerText = darkCandidate & whiteOutlineNear
        
        grad = get_gradients(rawGray)
        if grad.size > 0:
            edgeCandidate = grad > np.percentile(grad, 80)
        else:
            edgeCandidate = np.zeros_like(grad, dtype=bool)
            
        haloMask = edgeCandidate & whiteOutlineNear
        
        finalMask = whiteOutlineSeed | darkInnerText | haloMask
        finalMask = ndi.binary_dilation(finalMask, structure=np.ones((3,3)), iterations=2)
        finalMask = finalMask & inner_mask
        
        total_area = h * w
        mask_area = np.sum(finalMask)
        seed_area = np.sum(whiteOutlineSeed)
        confidence_good = True
        if mask_area < 0.01 * total_area or mask_area > 0.85 * total_area: confidence_good = False
        if seed_area == 0: confidence_good = False
        
        return finalMask, confidence_good, inner_mask
        
    else:
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


def load_aot(model_path):
    print(f"Loading AOT model from {model_path}...")
    import onnxruntime as ort
    return ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])

def inpaint_aot(image_bgr, mask, sess):
    h, w, c = image_bgr.shape
    
    pad_h = (8 - h % 8) % 8
    pad_w = (8 - w % 8) % 8
    
    if pad_h > 0 or pad_w > 0:
        image_padded = np.pad(image_bgr, ((0, pad_h), (0, pad_w), (0,0)), mode='reflect')
        mask_padded = np.pad(mask, ((0, pad_h), (0, pad_w)), mode='constant')
    else:
        image_padded = image_bgr
        mask_padded = mask
        
    image_rgb = cv2.cvtColor(image_padded, cv2.COLOR_BGR2RGB)
    
    img_tensor = (image_rgb.astype(np.float32) / 127.5) - 1.0
    img_tensor = np.transpose(img_tensor, (2, 0, 1))
    img_tensor = np.expand_dims(img_tensor, axis=0)
    
    mask_tensor = mask_padded.astype(np.float32)
    if mask_tensor.max() > 1.0: mask_tensor /= 255.0
    mask_tensor = np.expand_dims(mask_tensor, axis=0)
    mask_tensor = np.expand_dims(mask_tensor, axis=0)
    
    # Zero out masked pixels
    img_tensor *= (1.0 - mask_tensor)
    
    out = sess.run(None, {'image': img_tensor, 'mask': mask_tensor})[0]
    
    out_img = out[0]
    out_img = np.transpose(out_img, (1, 2, 0))
    out_img = (out_img + 1.0) * 127.5
    out_img = np.clip(out_img, 0, 255).astype(np.uint8)
    
    out_img_bgr = cv2.cvtColor(out_img, cv2.COLOR_RGB2BGR)
    
    if pad_h > 0 or pad_w > 0:
        out_img_bgr = out_img_bgr[:h, :w, :]
        
    mask_3d = np.repeat(mask[:, :, np.newaxis], c, axis=2)
    result = np.where(mask_3d, out_img_bgr, image_bgr)
    return result

def process_free_text_box(image, box, detector_sess, aot_sess):
    padding = 20
    h, w, _ = image.shape
    x1, y1, x2, y2 = int(box.x1), int(box.y1), int(box.x2), int(box.y2)
    
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    bw, bh = x2 - x1, y2 - y1
    side = max(bw, bh)
    crop_x1 = max(0, cx - side//2 - padding)
    crop_y1 = max(0, cy - side//2 - padding)
    crop_x2 = min(w, cx + side//2 + padding)
    crop_y2 = min(h, cy + side//2 + padding)
    
    crop = image[crop_y1:crop_y2, crop_x1:crop_x2].copy()
    
    mask, confidence_good, inner_mask = generate_pseudo_mask(crop, padding)
    
    # Optional debug visualization for the crop
    crop_viz = crop.copy()
    cv2.rectangle(crop_viz, (padding, padding), (crop_x2-crop_x1-padding, crop_y2-crop_y1-padding), (0,0,255), 1)
    
    debug_mask_rgb = np.zeros_like(crop)
    debug_mask_rgb[mask] = [0, 255, 0]
    
    # Expand the mask slightly for AOT so it completely devours the halo
    dilated_mask = ndi.binary_dilation(mask, iterations=2) & inner_mask
    
    # INPAINT WITH AOT
    inpainted_crop = inpaint_aot(crop, dilated_mask, aot_sess)
    
    debug_viz = np.concatenate([crop_viz, debug_mask_rgb, inpainted_crop], axis=1)
    
    global_mask = np.zeros((h, w), dtype=bool)
    global_mask[crop_y1:crop_y2, crop_x1:crop_x2] = dilated_mask
    
    return inpainted_crop, debug_viz, crop_x1, crop_y1, crop_x2, crop_y2, global_mask

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_free_text_aot.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    
    os.makedirs(output_folder, exist_ok=True)
    os.makedirs(os.path.join(output_folder, "debug"), exist_ok=True)
    os.makedirs(os.path.join(output_folder, "full"), exist_ok=True)
    
    detector_path = "../app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
    aot_path = "../app/src/main/assets/models/inpainting/aot.onnx"
    detector_sess = load_detector_v4(detector_path)
    aot_sess = load_aot(aot_path)
    
    image_paths = [os.path.join(input_folder, f) for f in os.listdir(input_folder) 
                   if f.lower().endswith(('.png', '.jpg', '.jpeg'))]
                   
    for img_path in image_paths:
        filename = os.path.basename(img_path)
        img = cv2.imread(img_path)
        if img is None: continue
        
        from PIL import Image
        pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        
        # Load bounding boxes using Detector V4
        boxes = detect_text_v4(pil_img, detector_sess)
        free_text_boxes = [b for b in boxes if b.label == 2]
        
        if len(free_text_boxes) == 0:
            continue
            
        print(f"Processing {filename} ({len(free_text_boxes)} free texts)")
        
        result = img.copy()
        full_mask = np.zeros(img.shape[:2], dtype=bool)
        
        debug_vizes = []
        for box in free_text_boxes:
            inpainted_crop, debug_viz, cx1, cy1, cx2, cy2, g_mask = process_free_text_box(result, box, detector_sess, aot_sess)
            result[cy1:cy2, cx1:cx2] = inpainted_crop
            full_mask |= g_mask
            debug_vizes.append(debug_viz)
            
        # Draw full mask overlay
        overlay = img.copy()
        overlay[full_mask] = [0, 0, 255] # Red overlay for mask
        alpha = 0.5
        overlay = cv2.addWeighted(img, 1 - alpha, overlay, alpha, 0)
        
        # Concat original, overlay, and full inpaint
        full_viz = np.concatenate([img, overlay, result], axis=1)
        cv2.imwrite(os.path.join(output_folder, "full", filename), full_viz)
        
        if len(debug_vizes) > 0:
            max_w = max(v.shape[1] for v in debug_vizes)
            padded_vizes = []
            for v in debug_vizes:
                if v.shape[1] < max_w:
                    pad = np.zeros((v.shape[0], max_w - v.shape[1], 3), dtype=np.uint8)
                    v = np.concatenate([v, pad], axis=1)
                padded_vizes.append(v)
            stacked = np.concatenate(padded_vizes, axis=0)
            cv2.imwrite(os.path.join(output_folder, "debug", filename), stacked)
            print(f"  -> Debug saved to debug/{filename}")

if __name__ == "__main__":
    main()
