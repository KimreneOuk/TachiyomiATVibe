import os
import sys
import glob
import cv2
import numpy as np
from ultralytics import YOLO
from huggingface_hub import hf_hub_download

def crop_margins(image):
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Invert image to make content white and background black
    # Threshold at 240 to catch near-white pixels as background
    _, thresh = cv2.threshold(gray, 240, 255, cv2.THRESH_BINARY_INV)
    
    # Find all non-zero points (content)
    coords = cv2.findNonZero(thresh)
    if coords is None:
        return image # fallback if the page is completely blank
        
    x, y, w, h = cv2.boundingRect(coords)
    
    # Add a small padding (e.g., 10 pixels) so we don't cut off exact edges
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
    
    # Auto-detect spreads (width > height usually indicates a spread)
    if w > h:
        mid = w // 2
        # Manga reads right-to-left, so part1 is right, part2 is left
        right_half = img[:, mid:]
        left_half = img[:, :mid]
        
        images_to_process.append(crop_margins(right_half))
        images_to_process.append(crop_margins(left_half))
    else:
        images_to_process.append(crop_margins(img))
        
    return images_to_process

def main():
    if len(sys.argv) < 3:
        print("Usage: python prototype_yolo.py <input_folder> <output_folder>")
        sys.exit(1)
        
    input_folder = sys.argv[1]
    output_folder = sys.argv[2]
    
    os.makedirs(output_folder, exist_ok=True)
    
    print("Downloading model from Hugging Face...")
    model_path = hf_hub_download(repo_id="ShadowB/Manga109-panel-balloon-text-yolov26-segmentation", filename="best.pt")
    
    model = YOLO(model_path)
    
    extensions = ('*.png', '*.jpg', '*.jpeg', '*.webp')
    image_paths = []
    for ext in extensions:
        image_paths.extend(glob.glob(os.path.join(input_folder, ext)))
        
    if not image_paths:
        print(f"No images found in {input_folder}")
        return
        
    print(f"Found {len(image_paths)} images. Running preprocessing and inference...")
    
    for img_path in image_paths:
        base_name = os.path.basename(img_path)
        name, ext = os.path.splitext(base_name)
        print(f"Processing: {img_path}")
        
        preprocessed_images = process_image(img_path)
        
        for i, p_img in enumerate(preprocessed_images):
            # Run inference directly on the preprocessed numpy array
            results = model.predict(
                source=p_img,
                imgsz=1280,
                conf=0.85,
                iou=0.7,
                retina_masks=True,
            )
            
            # Form output filename
            suffix = f"-part{i+1}" if len(preprocessed_images) > 1 else ""
            out_path = os.path.join(output_folder, f"{name}{suffix}{ext}")
            
            for result in results:
                # Save the annotated image
                result.save(filename=out_path)
                print(f"Saved to: {out_path}")

if __name__ == "__main__":
    main()
