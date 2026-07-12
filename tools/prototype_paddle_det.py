import os
import sys
import glob
import cv2
import numpy as np
from PIL import Image

sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector
from inference.ocr_paddle_det import PaddleOcrV6DetEngine

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_paddle_det.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    
    os.makedirs(output_folder, exist_ok=True)
    
    detector_path = "../app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
    paddle_path = "../app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
    
    print("Loading Detector v4 (ONNX)...")
    detector = OnnxPageTextDetector(detector_path)
    
    print("Loading PaddleOCR v6 Det (ONNX)...")
    paddle_det = PaddleOcrV6DetEngine(paddle_path)
    
    extensions = ('*.png', '*.jpg', '*.jpeg', '*.webp')
    image_paths = []
    for ext in extensions:
        image_paths.extend(glob.glob(os.path.join(input_folder, ext)))
        
    PADDLE_CROP_PAD = 12
    PADDLE_THRESH = 0.18
    PADDLE_BOX_THRESH = 0.34
    
    for img_path in sorted(image_paths):
        img = cv2.imread(img_path)
        if img is None: continue
        
        h, w, _ = img.shape
        pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        
        boxes = detector.detect(pil_img)
        free_text_boxes = [b for b in boxes if b.label == 2]
        
        if not free_text_boxes:
            continue
            
        print(f"Processing {os.path.basename(img_path)} ({len(free_text_boxes)} free texts)")
        result = img.copy()
        
        for box in free_text_boxes:
            x1, y1, x2, y2 = int(box.x1), int(box.y1), int(box.x2), int(box.y2)
            
            # Draw detector box in red
            cv2.rectangle(result, (x1, y1), (x2, y2), (0, 0, 255), 2)
            
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
                        # Draw paddle line in blue
                        cv2.rectangle(result, (px1, py1), (px2, py2), (255, 0, 0), 2)
                        
            except Exception as e:
                print(f"Error on paddle det: {e}")
                
        out_path = os.path.join(output_folder, os.path.basename(img_path))
        cv2.imwrite(out_path, result)
        print(f"  -> Saved {out_path}")

if __name__ == "__main__":
    main()
