import cv2
import os
import glob
import argparse
from ultralytics import YOLO
from huggingface_hub import hf_hub_download

def main():
    parser = argparse.ArgumentParser(description="Test YOLO manga text detector")
    parser.add_argument("input_folder", help="Path to folder containing manga pages")
    parser.add_argument("out_folder", help="Path to output folder for results")
    args = parser.parse_args()

    input_folder = args.input_folder
    out_folder = args.out_folder
    os.makedirs(out_folder, exist_ok=True)

    print("Downloading YOLO Text Detector model (ogkalu/manga-text-detector-yolov8s)...")
    model_path = hf_hub_download(repo_id="ogkalu/manga-text-detector-yolov8s", filename="manga-text-detector.pt")
        
    print(f"Loading model from {model_path}...")
    model = YOLO(model_path)

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
        
        img = cv2.imread(img_path)
        if img is None:
            continue
            
        results = model.predict(source=img, conf=0.25, verbose=False)
        out_img = img.copy()
        
        if len(results) > 0 and results[0].boxes is not None:
            boxes = results[0].boxes.xyxy.cpu().numpy()
            for box in boxes:
                x1, y1, x2, y2 = map(int, box[:4])
                cv2.rectangle(out_img, (x1, y1), (x2, y2), (0, 255, 0), 2)
                
        out_path = os.path.join(out_folder, base_name)
        cv2.imwrite(out_path, out_img)
        print(f"Saved: {out_path}")

if __name__ == "__main__":
    main()
