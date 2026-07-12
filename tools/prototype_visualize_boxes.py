import cv2
import numpy as np
import os
import glob
import argparse
from PIL import Image
from ultralytics import YOLO

# Import detector v4
import sys
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "companion_server"))
from inference.detector import OnnxPageTextDetector

def main():
    parser = argparse.ArgumentParser(description="Visualize bounding boxes from detector-v4 and yolov11n")
    parser.add_argument("input_folder", help="Path to folder containing manga pages")
    parser.add_argument("out_folder", help="Path to output folder for visual results")
    args = parser.parse_args()

    input_folder = args.input_folder
    out_folder = args.out_folder
    os.makedirs(out_folder, exist_ok=True)

    print("Loading Detector v4 (ONNX)...")
    detector_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "detection", "detector-v4-s_int8.onnx")
    detector = OnnxPageTextDetector(detector_path)
    
    print("Loading YOLO Segmentation Bubble model (PT)...")
    model_path = r"C:\Users\ADMIN\.cache\huggingface\hub\models--huyvux3005--manga109-segmentation-bubble\snapshots\f9a4108c4955136a810e5e92207972f3fb3a65fd\best.pt"
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
        print(f"Processing: {img_path}")
        
        p_img = cv2.imread(img_path)
        if p_img is None:
            continue
            
        h, w = p_img.shape[:2]
        
        # 1. Run Detector V4
        text_boxes_raw = detector.detect(Image.fromarray(cv2.cvtColor(p_img, cv2.COLOR_BGR2RGB)))
        
        # 2. Run YOLOv11 Bubble Segmentation
        yolo_results = yolo_model.predict(
            source=p_img,
            imgsz=1600,
            conf=0.25,
            iou=0.7,
            retina_masks=True,
            verbose=False
        )
        
        out_img = p_img.copy()
        
        # 3. Draw YOLO Masks in RED overlay
        yolo_mask = np.zeros((h, w), dtype=np.uint8)
        if len(yolo_results) > 0 and yolo_results[0].masks is not None:
            masks_data = yolo_results[0].masks.data.cpu().numpy()
            orig_shape = yolo_results[0].orig_shape
            for mask in masks_data:
                mask_resized = cv2.resize(mask, (orig_shape[1], orig_shape[0]), interpolation=cv2.INTER_NEAREST)
                yolo_mask = np.maximum(yolo_mask, (mask_resized * 255).astype(np.uint8))
                
        # Overlay the mask with some transparency
        colored_mask = np.zeros_like(out_img)
        colored_mask[:, :] = (0, 0, 255) # Red color
        mask_indices = yolo_mask > 0
        
        # Pure numpy alpha blending
        alpha = 0.5
        out_img[mask_indices] = (out_img[mask_indices] * (1 - alpha) + colored_mask[mask_indices] * alpha).astype(np.uint8)
            
        # Draw ALL Text Boxes from detector v4
        for tbox in text_boxes_raw:
            tx, ty = int(tbox.x1), int(tbox.y1)
            tw, th = int(tbox.x2 - tbox.x1), int(tbox.y2 - tbox.y1)
            
            if tbox.label == 0:
                color = (255, 0, 0) # Blue for bubble
                label_str = "Bubble (v4)"
            elif tbox.label == 1:
                color = (0, 255, 0) # Green for text_bubble
                label_str = "Text_Bubble (v4)"
            else:
                color = (0, 255, 255) # Yellow for text_free
                label_str = "Text_Free (v4)"
                
            cv2.rectangle(out_img, (tx, ty), (tx + tw, ty + th), color, 2)
            cv2.putText(out_img, label_str, (tx, ty - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
                
        out_path = os.path.join(out_folder, base_name)
        cv2.imwrite(out_path, out_img)
        print(f"Saved: {out_path}")

if __name__ == "__main__":
    main()
