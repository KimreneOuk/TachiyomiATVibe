#!/usr/bin/env python3
"""
Download and verify models and test assets for Manga LaMa LiteRT audit.
"""
import hashlib
import os
import sys
import time
import urllib.request

DOWNLOADS = [
    {
        "name": "lama-manga.onnx (mayocream FP32 reference)",
        "url": "https://huggingface.co/mayocream/lama-manga-onnx/resolve/main/lama-manga.onnx",
        "dest": "audit/models/lama-manga.onnx",
    },
    {
        "name": "lama-manga_fp16.onnx (Liiesl weight-only FP16)",
        "url": "https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/main/lama-manga_fp16.onnx",
        "dest": "audit/models/lama-manga_fp16.onnx",
    },
    {
        "name": "lama-manga_int8.onnx (Liiesl weight-only INT8)",
        "url": "https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/main/lama-manga_int8.onnx",
        "dest": "audit/models/lama-manga_int8.onnx",
    },
    {
        "name": "lama_512_fp16.onnx (g-ronimo full FP16 reference)",
        "url": "https://huggingface.co/g-ronimo/lama/resolve/main/lama_512_fp16.onnx",
        "dest": "audit/models/lama_512_fp16.onnx",
    },
    {
        "name": "comparison.png (Liiesl)",
        "url": "https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/main/comparison.png",
        "dest": "audit/reference_data/comparison_liiesl.png",
    },
    {
        "name": "example.jpg (g-ronimo)",
        "url": "https://huggingface.co/g-ronimo/lama/resolve/main/examples/example.jpg",
        "dest": "audit/reference_data/example_gronimo.jpg",
    },
    {
        "name": "example_mask.png (g-ronimo)",
        "url": "https://huggingface.co/g-ronimo/lama/resolve/main/examples/example_mask.png",
        "dest": "audit/reference_data/example_mask_gronimo.png",
    },
]

def download_file(url, dest, desc):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"[SKIP] {dest} already exists ({os.path.getsize(dest)} bytes).")
        return get_sha256(dest)

    print(f"[DOWNLOADING] {desc} from {url} -> {dest}...")
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
    req = urllib.request.Request(url, headers=headers)

    start_t = time.time()
    with urllib.request.urlopen(req) as resp, open(dest + ".tmp", "wb") as f:
        total = int(resp.headers.get("content-length", 0))
        downloaded = 0
        chunk_size = 1024 * 1024
        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            mb = downloaded / (1024 * 1024)
            pct = (downloaded / total * 100) if total else 0
            sys.stdout.write(f"\r  {mb:.1f} MB / {total / (1024 * 1024):.1f} MB ({pct:.1f}%)")
            sys.stdout.flush()
    print()
    os.replace(dest + ".tmp", dest)
    elapsed = time.time() - start_t
    size = os.path.getsize(dest)
    sha = get_sha256(dest)
    print(f"[DONE] {dest} ({size} bytes, {size/(1024*1024):.2f} MB, {elapsed:.1f}s, SHA256: {sha})")
    return sha

def get_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()

def main():
    results = {}
    for item in DOWNLOADS:
        try:
            sha = download_file(item["url"], item["dest"], item["name"])
            results[item["dest"]] = {
                "size_bytes": os.path.getsize(item["dest"]),
                "sha256": sha,
                "url": item["url"],
            }
        except Exception as e:
            print(f"[ERROR] Failed downloading {item['name']}: {e}")
            sys.exit(1)

    print("\n=== DOWNLOAD VERIFICATION SUMMARY ===")
    for path, info in results.items():
        print(f"{path}:")
        print(f"  Size: {info['size_bytes']:,} bytes ({info['size_bytes']/(1024*1024):.2f} MB)")
        print(f"  SHA-256: {info['sha256']}")

if __name__ == "__main__":
    main()
