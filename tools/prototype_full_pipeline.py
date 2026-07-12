import os
import sys
import glob
import cv2
import numpy as np
from PIL import Image
import time
import shutil

# Local imports from prototype files
from prototype_paddle_aot import load_aot, inpaint_aot, draw_pill
from prototype_paddle_fast import android_fast_inpaint
from prototype_combined import solid_fill_smart_color

from ultralytics import YOLO

sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector
from inference.ocr_paddle_det import PaddleOcrV6DetEngine

def ensure_padding(img, padding=512):
    """Pad image slightly for model inputs if needed."""
    pass # we can do this dynamically

def get_free_text_paddle_mask(img, boxes, paddle_det, PADDLE_CROP_PAD=12, PADDLE_THRESH=0.18, PADDLE_BOX_THRESH=0.34, MASK_PAD=8, DILATE_RADIUS=4):
    h, w = img.shape[:2]
    full_mask = np.zeros((h, w), dtype=np.uint8)
    pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    
    # Create disk kernel for dilation
    kernel_size = DILATE_RADIUS * 2 + 1
    y, x = np.ogrid[-DILATE_RADIUS:DILATE_RADIUS+1, -DILATE_RADIUS:DILATE_RADIUS+1]
    disk_kernel = (x**2 + y**2 <= DILATE_RADIUS**2).astype(np.uint8)
    
    for box in boxes:
        x1, y1, x2, y2 = int(box[0]), int(box[1]), int(box[2]), int(box[3])
        
        # Crop pad
        cx1 = max(0, x1 - PADDLE_CROP_PAD)
        cy1 = max(0, y1 - PADDLE_CROP_PAD)
        cx2 = min(w, x2 + PADDLE_CROP_PAD)
        cy2 = min(h, y2 + PADDLE_CROP_PAD)
        
        if cx2 <= cx1 or cy2 <= cy1:
            continue
            
        crop = pil_img.crop((cx1, cy1, cx2, cy2))
        
        try:
            lines = paddle_det.detect_lines(crop, thresh=PADDLE_THRESH, box_thresh=PADDLE_BOX_THRESH)
            
            for line in lines:
                px1 = max(0, cx1 + int(line["x1"]))
                py1 = max(0, cy1 + int(line["y1"]))
                px2 = min(w, cx1 + int(line["x2"]))
                py2 = min(h, cy1 + int(line["y2"]))
                
                if px2 > px1 and py2 > py1:
                    # Draw pill padded by MASK_PAD
                    draw_pill(full_mask, 
                              max(0, px1 - MASK_PAD), 
                              max(0, py1 - MASK_PAD), 
                              min(w, px2 + MASK_PAD), 
                              min(h, py2 + MASK_PAD))
                    
        except Exception as e:
            print(f"Error on paddle det: {e}")
            
    # Dilate full mask
    if DILATE_RADIUS > 0:
        full_mask = cv2.dilate(full_mask, disk_kernel, iterations=1)
        
    return full_mask

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_full_pipeline.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    
    os.makedirs(output_folder, exist_ok=True)
    
    print("Loading Detector v4 (ONNX)...")
    detector_path = "../app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
    detector = OnnxPageTextDetector(detector_path)
    
    print("Loading YOLO Segmentation Bubble model...")
    yolo_model_path = r"C:\Users\ADMIN\.cache\huggingface\hub\models--huyvux3005--manga109-segmentation-bubble\snapshots\f9a4108c4955136a810e5e92207972f3fb3a65fd\best_int8.onnx"
    yolo_model = YOLO(yolo_model_path, task='segment')
    
    print("Loading PaddleOCR v6 Det (ONNX)...")
    paddle_path = "../app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
    paddle_det = PaddleOcrV6DetEngine(paddle_path)
    
    print("Loading AOT...")
    aot_path = "../app/src/main/assets/models/inpainting/aot.onnx"
    aot_sess = load_aot(aot_path)
    
    extensions = ('*.png', '*.jpg', '*.jpeg', '*.webp')
    image_paths = []
    for ext in extensions:
        image_paths.extend(glob.glob(os.path.join(input_folder, ext)))
        
    for img_path in sorted(image_paths):
        filename = os.path.basename(img_path)
        img = cv2.imread(img_path)
        if img is None: continue
        
        h, w = img.shape[:2]
        pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        
        print(f"\nProcessing {filename}...")
        
        # 1. Run Detectors
        v4_boxes = detector.detect(pil_img)
        v4_bubble_boxes = [[b.x1, b.y1, b.x2, b.y2] for b in v4_boxes if b.label == 1]
        v4_free_boxes = [[b.x1, b.y1, b.x2, b.y2] for b in v4_boxes if b.label == 2]
        
        yolo_results = yolo_model.predict(
            source=img,
            imgsz=640,
            conf=0.85,
            iou=0.7,
            retina_masks=True,
            verbose=False
        )
        
        yolo_mask = np.zeros((h, w), dtype=np.uint8)
        yolo_bubble_boxes = []
        if len(yolo_results) > 0 and yolo_results[0].masks is not None:
            masks_data = yolo_results[0].masks.data.cpu().numpy()
            boxes_data = yolo_results[0].boxes.xyxy.cpu().numpy()
            orig_shape = yolo_results[0].orig_shape
            for i, mask in enumerate(masks_data):
                mask_resized = cv2.resize(mask, (orig_shape[1], orig_shape[0]), interpolation=cv2.INTER_NEAREST)
                yolo_mask = np.maximum(yolo_mask, (mask_resized * 255).astype(np.uint8))
                
                bx1, by1, bx2, by2 = boxes_data[i]
                yolo_bubble_boxes.append((int(bx1), int(by1), int(bx2 - bx1), int(by2 - by1)))
        
        # Filter V4 boxes by YOLO bubbles to create the YOLO pipeline free text
        yolo_pipeline_free_boxes = []
        for b in v4_boxes:
            if b.label not in (1, 2): continue
            tx1, ty1, tx2, ty2 = b.x1, b.y1, b.x2, b.y2
            is_parented = False
            for (bx, by, bw, bh) in yolo_bubble_boxes:
                bx1, by1, bx2, by2 = bx, by, bx+bw, by+bh
                ix1, iy1 = max(tx1, bx1), max(ty1, by1)
                ix2, iy2 = min(tx2, bx2), min(ty2, by2)
                if ix1 < ix2 and iy1 < iy2:
                    is_parented = True
                    break
            if not is_parented:
                yolo_pipeline_free_boxes.append([tx1, ty1, tx2, ty2])
                
        # ==========================================
        # PIPELINE A: CURRENT ANDROID (V4 based)
        # ==========================================
        v4_output = img.copy()
        
        # 1. Bubble text via Android Fast Fill
        if v4_bubble_boxes:
            v4_bubble_mask = np.zeros((h, w), dtype=np.uint8)
            for box in v4_bubble_boxes:
                # Basic pill padding for V4 bubbles (Android uses heuristic, we approximate with heavy pill)
                x1, y1, x2, y2 = map(int, box)
                draw_pill(v4_bubble_mask, max(0, x1 - 16), max(0, y1 - 16), min(w, x2 + 16), min(h, y2 + 16))
            v4_bubble_mask = cv2.dilate(v4_bubble_mask, np.ones((5,5), np.uint8), iterations=1)
            
            # Fill bubbles locally (just like Android does in crop loops)
            contours, _ = cv2.findContours(v4_bubble_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            for cnt in contours:
                x_b, y_b, bw, bh = cv2.boundingRect(cnt)
                pad = 20
                crop_y1 = max(0, y_b - pad)
                crop_y2 = min(h, y_b + bh + pad)
                crop_x1 = max(0, x_b - pad)
                crop_x2 = min(w, x_b + bw + pad)
                
                c_img = v4_output[crop_y1:crop_y2, crop_x1:crop_x2]
                c_mask = v4_bubble_mask[crop_y1:crop_y2, crop_x1:crop_x2]
                
                if np.any(c_mask):
                    filled = android_fast_inpaint(c_img, c_mask)
                    v4_output[crop_y1:crop_y2, crop_x1:crop_x2] = filled
        
        # 2. Free text via PaddleOCR + AOT
        if v4_free_boxes:
            v4_free_mask = get_free_text_paddle_mask(img, v4_free_boxes, paddle_det)
            if np.any(v4_free_mask):
                # We can run AOT on the whole page to fix free text
                # Note: pass v4_output so it contains the already-filled bubbles
                v4_output = inpaint_aot(v4_output, v4_free_mask, aot_sess)
                
        # ==========================================
        # PIPELINE B: YOLO BASED
        # ==========================================
        yolo_output = img.copy()
        
        # 1. Bubble text via Solid Fill Smart Color
        if np.any(yolo_mask):
            yolo_output = solid_fill_smart_color(yolo_output, yolo_mask)
            
        # 2. Free text via PaddleOCR + AOT
        if yolo_pipeline_free_boxes:
            yolo_free_mask = get_free_text_paddle_mask(img, yolo_pipeline_free_boxes, paddle_det)
            if np.any(yolo_free_mask):
                yolo_output = inpaint_aot(yolo_output, yolo_free_mask, aot_sess)
                
        # ==========================================
        # Combine into side-by-side visualization
        # ==========================================
        # Add labels
        font = cv2.FONT_HERSHEY_SIMPLEX
        cv2.putText(img, "Original", (20, 40), font, 1.0, (0,0,255), 3)
        cv2.putText(v4_output, "V4 Pipeline", (20, 40), font, 1.0, (0,0,255), 3)
        cv2.putText(yolo_output, "YOLO Pipeline", (20, 40), font, 1.0, (0,0,255), 3)
        
        full_viz = np.concatenate([img, v4_output, yolo_output], axis=1)
        out_path = os.path.join(output_folder, filename)
        cv2.imwrite(out_path, full_viz)
        print(f"  -> Saved {out_path}")

if __name__ == "__main__":
    main()
