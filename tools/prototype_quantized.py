import os
import sys
import glob
import cv2
import numpy as np
import argparse
import time
from PIL import Image
from ultralytics import YOLO
from huggingface_hub import hf_hub_download

# Add companion_server to path to import OnnxPageTextDetector
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector

def solid_fill_smart_color(image, mask):
    # Find individual connected components (bubbles)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    result = image.copy()
    
    h, w = image.shape[:2]
    
    for contour in contours:
        comp_mask = np.zeros_like(mask)
        cv2.drawContours(comp_mask, [contour], -1, 255, -1)
        
        # Get bounding box of the component
        x, y, bw, bh = cv2.boundingRect(contour)
        
        # Create a rectangular ring around the bounding box
        ox1 = max(0, x - 2)
        oy1 = max(0, y - 2)
        ox2 = min(w, x + bw + 2)
        oy2 = min(h, y + bh + 2)
        
        ring_mask = np.zeros_like(mask)
        cv2.rectangle(ring_mask, (ox1, oy1), (ox2, oy2), 255, -1)
        cv2.rectangle(ring_mask, (x, y), (x + bw, y + bh), 0, -1)
        
        ring_pixels = image[ring_mask == 255]
        
        if len(ring_pixels) > 0:
            median_color = np.median(ring_pixels, axis=0)
            result[comp_mask == 255] = median_color
            
    return result

def crop_margins(image):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    _, thresh = cv2.threshold(gray, 240, 255, cv2.THRESH_BINARY_INV)
    coords = cv2.findNonZero(thresh)
    if coords is None:
        return image, 0, 0
    x, y, w, h = cv2.boundingRect(coords)
    pad = 10
    x = max(0, x - pad)
    y = max(0, y - pad)
    w = min(image.shape[1] - x, w + 2 * pad)
    h = min(image.shape[0] - y, h + 2 * pad)
    cropped = image[y:y+h, x:x+w]
    return cropped, x, y

def split_and_crop(img):
    h, w = img.shape[:2]
    images_to_process = []
    if w > h:
        mid = w // 2
        right_half = img[:, mid:]
        left_half = img[:, :mid]
        
        r_crop, rx, ry = crop_margins(right_half)
        l_crop, lx, ly = crop_margins(left_half)
        images_to_process.append((r_crop, mid + rx, ry))
        images_to_process.append((l_crop, lx, ly))
    else:
        crop, cx, cy = crop_margins(img)
        images_to_process.append((crop, cx, cy))
    return images_to_process

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_combined.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    os.makedirs(output_folder, exist_ok=True)
    
    print("Loading Detector v4 (ONNX)...")
    detector_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "detection", "detector-v4-s_int8.onnx")
    detector = OnnxPageTextDetector(detector_path)
    
    print("Loading YOLO Segmentation Bubble model...")
    model_path = r"C:\Users\ADMIN\.cache\huggingface\hub\models--huyvux3005--manga109-segmentation-bubble\snapshots\f9a4108c4955136a810e5e92207972f3fb3a65fd\best_int8.onnx"
    yolo_model = YOLO(model_path, task='segment')
    
    if os.path.isfile(input_folder):
        image_paths = [input_folder]
    else:
        extensions = ('*.png', '*.jpg', '*.jpeg', '*.webp')
        image_paths = []
        for ext in extensions:
            image_paths.extend(glob.glob(os.path.join(input_folder, ext)))
        
    for img_path in sorted(image_paths):
        base_name = os.path.basename(img_path)
        name, ext = os.path.splitext(base_name)
        print(f"Processing: {img_path}")
        
        original_img = cv2.imread(img_path)
        if original_img is None:
            continue
            
        final_img = original_img.copy()
        preprocessed = split_and_crop(original_img)
        
        for i, (p_img, offset_x, offset_y) in enumerate(preprocessed):
            h, w = p_img.shape[:2]
            pil_img = Image.fromarray(cv2.cvtColor(p_img, cv2.COLOR_BGR2RGB))
            
            # 1. Detect text with detector v4
            
            # 1. Run Detector V4
            t_det_start = time.time()
            text_boxes = detector.detect(Image.fromarray(cv2.cvtColor(p_img, cv2.COLOR_BGR2RGB)))
            t_det_end = time.time()
            text_boxes = [d for d in text_boxes if d.label in (1, 2)]
            
            # 2. Run YOLOv8 Bubble Segmentation
            t_yolo_start = time.time()
            yolo_results = yolo_model.predict(
                source=p_img,
                imgsz=640,
                conf=0.85,
                iou=0.7,
                retina_masks=True,
                verbose=False
            )
            t_yolo_end = time.time()
            
            # --- Timing Results ---
            det_time = (t_det_end - t_det_start) * 1000
            yolo_time = (t_yolo_end - t_yolo_start) * 1000
            total_inf_time = det_time + yolo_time
            print(f"  -> Detector v4 time: {det_time:.1f}ms")
            print(f"  -> YOLOv8 time: {yolo_time:.1f}ms")
            print(f"  -> Total Inference: {total_inf_time:.1f}ms")
            
            yolo_mask = np.zeros((h, w), dtype=np.uint8)
            yolo_boxes = []
            
            if len(yolo_results) > 0 and yolo_results[0].masks is not None:
                masks_data = yolo_results[0].masks.data.cpu().numpy()
                boxes_data = yolo_results[0].boxes.xyxy.cpu().numpy()
                orig_shape = yolo_results[0].orig_shape
                
                for i, mask in enumerate(masks_data):
                    mask_resized = cv2.resize(mask, (orig_shape[1], orig_shape[0]), interpolation=cv2.INTER_NEAREST)
                    yolo_mask = np.maximum(yolo_mask, (mask_resized * 255).astype(np.uint8))
                    
                    bx1, by1, bx2, by2 = boxes_data[i]
                    bx, by, bw, bh = int(bx1), int(by1), int(bx2 - bx1), int(by2 - by1)
                    if bw > 0 and bh > 0:
                        yolo_boxes.append((bx, by, bw, bh))
            
            # 3. Categorize text boxes into Parented (in bubble) or Free Text
            free_text_boxes = []
            
            for tbox in text_boxes:
                tx1, ty1 = int(tbox.x1), int(tbox.y1)
                tx2, ty2 = int(tbox.x2), int(tbox.y2)
                
                # Check intersection with any YOLO bubble box
                is_parented = False
                for (bx, by, bw, bh) in yolo_boxes:
                    bx1, by1, bx2, by2 = bx, by, bx+bw, by+bh
                    ix1, iy1 = max(tx1, bx1), max(ty1, by1)
                    ix2, iy2 = min(tx2, bx2), min(ty2, by2)
                    if ix1 < ix2 and iy1 < iy2:
                        is_parented = True
                        break
                        
                if not is_parented:
                    free_text_boxes.append([tx1, ty1, tx2, ty2])
            
            # 4. Solid Fill YOLO Bubbles
            filled_p_img = solid_fill_smart_color(p_img, yolo_mask)
            
            # 5. Inpaint Free Text with Android's Push-Pull (legacy_free_text)
            from inpaint.legacy_free_text import inpaint_legacy_free_text
            
            pil_filled = Image.fromarray(cv2.cvtColor(filled_p_img, cv2.COLOR_BGR2RGB))
            for box in free_text_boxes:
                pil_filled = inpaint_legacy_free_text(pil_filled, box)
            
            filled_p_img = cv2.cvtColor(np.array(pil_filled), cv2.COLOR_RGB2BGR)
            
            # 6. Paste back into original image
            final_img[offset_y:offset_y+h, offset_x:offset_x+w] = filled_p_img
            
        out_path = os.path.join(output_folder, f"{name}{ext}")
        cv2.imwrite(out_path, final_img)
        print(f"Saved combined output to: {out_path}")

if __name__ == "__main__":
    main()
