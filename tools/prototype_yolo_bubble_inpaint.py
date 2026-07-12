import os
import sys
import glob
import cv2
import numpy as np
from ultralytics import YOLO
from huggingface_hub import hf_hub_download

def crop_margins(image):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    _, thresh = cv2.threshold(gray, 240, 255, cv2.THRESH_BINARY_INV)
    coords = cv2.findNonZero(thresh)
    if coords is None:
        return image
    x, y, w, h = cv2.boundingRect(coords)
    pad = 10
    x = max(0, x - pad)
    y = max(0, y - pad)
    w = min(image.shape[1] - x, w + 2 * pad)
    h = min(image.shape[0] - y, h + 2 * pad)
    cropped = image[y:y+h, x:x+w]
    return cropped

def process_image(img_path):
    img = cv2.imread(img_path)
    if img is None:
        return []
    h, w = img.shape[:2]
    images_to_process = []
    if w > h:
        mid = w // 2
        right_half = img[:, mid:]
        left_half = img[:, :mid]
        images_to_process.append(crop_margins(right_half))
        images_to_process.append(crop_margins(left_half))
    else:
        images_to_process.append(crop_margins(img))
    return images_to_process

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_yolo_bubble_inpaint.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    
    os.makedirs(output_folder, exist_ok=True)
    
    print("Downloading model from Hugging Face...")
    model_path = hf_hub_download(repo_id="huyvux3005/manga109-segmentation-bubble", filename="best.pt")
    
    model = YOLO(model_path)
    
    extensions = ('*.png', '*.jpg', '*.jpeg', '*.webp')
    image_paths = []
    for ext in extensions:
        image_paths.extend(glob.glob(os.path.join(input_folder, ext)))
        
    if not image_paths:
        print(f"No images found in {input_folder}")
        return
        
    print(f"Found {len(image_paths)} images. Running inference and inpainting...")
    
    for img_path in image_paths:
        base_name = os.path.basename(img_path)
        name, ext = os.path.splitext(base_name)
        print(f"Processing: {img_path}")
        
        preprocessed_images = process_image(img_path)
        
        for i, p_img in enumerate(preprocessed_images):
            results = model.predict(
                source=p_img,
                imgsz=1600,
                conf=0.85,
                iou=0.7,
                retina_masks=True,
            )
            
            result = results[0]
            
            h, w = p_img.shape[:2]
            combined_mask = np.zeros((h, w), dtype=np.uint8)
            
            if result.masks is not None:
                masks = result.masks.data.cpu().numpy()
                for mask in masks:
                    mask_resized = cv2.resize(mask, (w, h), interpolation=cv2.INTER_NEAREST)
                    combined_mask = cv2.bitwise_or(combined_mask, (mask_resized * 255).astype(np.uint8))
            elif result.boxes is not None and len(result.boxes) > 0:
                for box in result.boxes.xyxy.cpu().numpy():
                    x1, y1, x2, y2 = map(int, box[:4])
                    cv2.rectangle(combined_mask, (x1, y1), (x2, y2), 255, -1)
            
            if np.any(combined_mask):
                # Dilate mask slightly to cover edges of the bubbles
                kernel = np.ones((5,5), np.uint8)
                dilated_mask = cv2.dilate(combined_mask, kernel, iterations=2)
                
                # Apply Telea inpainting
                inpainted_img = cv2.inpaint(p_img, dilated_mask, 3, cv2.INPAINT_TELEA)
            else:
                inpainted_img = p_img.copy()
            
            suffix = f"-part{i+1}" if len(preprocessed_images) > 1 else ""
            out_path = os.path.join(output_folder, f"{name}{suffix}{ext}")
            
            cv2.imwrite(out_path, inpainted_img)
            print(f"Saved to: {out_path}")

if __name__ == "__main__":
    main()
