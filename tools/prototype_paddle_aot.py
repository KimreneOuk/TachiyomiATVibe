import os
import sys
import glob
import cv2
import numpy as np
from PIL import Image

sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector
from inference.ocr_paddle_det import PaddleOcrV6DetEngine

def load_aot(model_path):
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

def draw_pill(mask, x1, y1, x2, y2):
    # Android fillPill draws a rounded rect with radius = min(w, h) / 2
    w = x2 - x1
    h = y2 - y1
    if w <= 0 or h <= 0: return
    radius = min(w, h) // 2
    
    # Draw center rect
    cv2.rectangle(mask, (x1 + radius, y1), (x2 - radius, y2), 255, -1)
    cv2.rectangle(mask, (x1, y1 + radius), (x2, y2 - radius), 255, -1)
    
    # Draw 4 circles at corners
    cv2.circle(mask, (x1 + radius, y1 + radius), radius, 255, -1)
    cv2.circle(mask, (x2 - radius, y1 + radius), radius, 255, -1)
    cv2.circle(mask, (x1 + radius, y2 - radius), radius, 255, -1)
    cv2.circle(mask, (x2 - radius, y2 - radius), radius, 255, -1)

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_paddle_aot.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    
    os.makedirs(output_folder, exist_ok=True)
    os.makedirs(os.path.join(output_folder, "full"), exist_ok=True)
    
    detector_path = "../app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
    paddle_path = "../app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
    aot_path = "../app/src/main/assets/models/inpainting/aot.onnx"
    
    print("Loading Detector v4 (ONNX)...")
    detector = OnnxPageTextDetector(detector_path)
    
    print("Loading PaddleOCR v6 Det (ONNX)...")
    paddle_det = PaddleOcrV6DetEngine(paddle_path)
    
    print("Loading AOT...")
    aot_sess = load_aot(aot_path)
    
    extensions = ('*.png', '*.jpg', '*.jpeg', '*.webp')
    image_paths = []
    for ext in extensions:
        image_paths.extend(glob.glob(os.path.join(input_folder, ext)))
        
    PADDLE_CROP_PAD = 12
    PADDLE_THRESH = 0.18
    PADDLE_BOX_THRESH = 0.34
    
    MASK_PAD = 8
    DILATE_RADIUS = 4
    
    # Create disk kernel for dilation
    kernel_size = DILATE_RADIUS * 2 + 1
    y, x = np.ogrid[-DILATE_RADIUS:DILATE_RADIUS+1, -DILATE_RADIUS:DILATE_RADIUS+1]
    disk_kernel = (x**2 + y**2 <= DILATE_RADIUS**2).astype(np.uint8)
    
    for img_path in sorted(image_paths):
        filename = os.path.basename(img_path)
        img = cv2.imread(img_path)
        if img is None: continue
        
        h, w, _ = img.shape
        pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        
        boxes = detector.detect(pil_img)
        free_text_boxes = [b for b in boxes if b.label == 2]
        
        if not free_text_boxes:
            continue
            
        print(f"Processing {filename} ({len(free_text_boxes)} free texts)")
        result = img.copy()
        
        full_mask = np.zeros((h, w), dtype=np.uint8)
        
        for box in free_text_boxes:
            x1, y1, x2, y2 = int(box.x1), int(box.y1), int(box.x2), int(box.y2)
            
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
            
        bool_mask = full_mask > 0
        
        if np.any(bool_mask):
            # Inpaint the whole image based on mask
            inpainted = inpaint_aot(img, bool_mask, aot_sess)
            
            # Draw overlay for viz
            overlay = img.copy()
            overlay[bool_mask] = [0, 0, 255] # Red overlay
            alpha = 0.5
            overlay = cv2.addWeighted(img, 1 - alpha, overlay, alpha, 0)
            
            full_viz = np.concatenate([img, overlay, inpainted], axis=1)
            out_path = os.path.join(output_folder, "full", filename)
            cv2.imwrite(out_path, full_viz)
            print(f"  -> Saved {out_path}")

if __name__ == "__main__":
    main()
