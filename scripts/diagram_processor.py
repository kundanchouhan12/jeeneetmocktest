"""
diagram_processor.py
====================
Diagram Optimization and Firebase Cloud Storage Engine for STEM Questions.

Handles:
1. Downloading diagram assets from educational web sources.
2. Auto-trimming redundant whitespace borders and normalizing dimensions.
3. Resizing high-resolution diagrams to mobile-optimal width (max 800px).
4. Compressing to modern WebP (q=85) for minimal network payload (20-50 KB).
5. Uploading to Firebase Cloud Storage (apps-273d9.firebasestorage.app)
   with immutable public download tokens for Android Coil caching.
"""

import io
import os
import sys
import uuid
import urllib.parse
try:
    from PIL import Image, ImageChops
    HAS_PIL = True
except ImportError:
    Image = None
    ImageChops = None
    HAS_PIL = False


# Ensure utf-8 stdout on Windows consoles
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

DEFAULT_BUCKET_NAME = "apps-273d9.firebasestorage.app"
MAX_DIAGRAM_WIDTH = 800
WEBP_QUALITY = 85
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"


def download_image(url: str, timeout: int = 15) -> bytes | None:
    """
    Downloads image bytes from a URL with robust browser headers and timeout.
    """
    if not url or not url.startswith(("http://", "https://")):
        return None
    try:
        headers = {"User-Agent": USER_AGENT}
        resp = requests.get(url, headers=headers, timeout=timeout)
        if resp.status_code == 200 and len(resp.content) > 200:
            return resp.content
        return None
    except Exception as e:
        print(f"    ⚠️ Diagram download failed from {url[:60]}...: {e}")
        return None


def compress_diagram_to_webp(image_bytes: bytes, max_width: int = MAX_DIAGRAM_WIDTH, quality: int = WEBP_QUALITY) -> bytes | None:
    """
    Cleans, crops, downscales and compresses diagram bytes into WebP.
    """
    if not HAS_PIL or Image is None:
        print("    ⚠️ Pillow (PIL) is not installed; skipping diagram compression.")
        return None
    try:
        img = Image.open(io.BytesIO(image_bytes))

        # Handle alpha channel
        has_alpha = img.mode in ('RGBA', 'LA') or (img.mode == 'P' and 'transparency' in img.info)
        if has_alpha:
            if img.mode != 'RGBA':
                img = img.convert('RGBA')
        else:
            img = img.convert('RGB')

        # Auto-trim white borders for non-alpha images
        if img.mode == 'RGB':
            bg = Image.new(img.mode, img.size, (255, 255, 255))
            diff = ImageChops.difference(img, bg)
            bbox = diff.getbbox()
            if bbox:
                pad = 12
                w, h = img.size
                crop_box = (
                    max(0, bbox[0] - pad),
                    max(0, bbox[1] - pad),
                    min(w, bbox[2] + pad),
                    min(h, bbox[3] + pad)
                )
                img = img.crop(crop_box)

        # Downscale proportionally if width exceeds max_width
        if img.width > max_width:
            ratio = max_width / float(img.width)
            new_height = max(1, int(float(img.height) * ratio))
            img = img.resize((max_width, new_height), Image.Resampling.LANCZOS)

        out = io.BytesIO()
        img.save(out, format="WEBP", quality=quality, method=6)
        return out.getvalue()
    except Exception as e:
        print(f"    ⚠️ Diagram compression to WebP failed: {e}")
        return None


GITHUB_REPO_RAW_URL = "https://raw.githubusercontent.com/kundanchouhan12/jeeneetmocktest/main"


def save_local_diagram(webp_bytes: bytes, storage_path: str) -> str:
    """Saves diagram to local repo diagrams/ directory and returns permanent GitHub Raw CDN URL."""
    rel_path = storage_path.replace("\\", "/")
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    full_local_path = os.path.join(repo_root, rel_path)
    os.makedirs(os.path.dirname(full_local_path), exist_ok=True)
    with open(full_local_path, "wb") as f:
        f.write(webp_bytes)
    return f"{GITHUB_REPO_RAW_URL}/{rel_path}"


def upload_diagram_to_storage(webp_bytes: bytes, bucket, storage_path: str) -> str | None:
    """
    Uploads WebP bytes to Firebase Storage bucket and returns permanent CDN download URL.
    Falls back to repository CDN if bucket is not initialized or unreachable.
    """
    if bucket:
        try:
            blob = bucket.blob(storage_path)
            token = str(uuid.uuid4())
            blob.metadata = {"firebaseStorageDownloadTokens": token}
            blob.upload_from_string(webp_bytes, content_type="image/webp")

            encoded_path = urllib.parse.quote(blob.name, safe='')
            public_url = f"https://firebasestorage.googleapis.com/v0/b/{bucket.name}/o/{encoded_path}?alt=media&token={token}"
            return public_url
        except Exception as e:
            print(f"    ⚠️ Firebase Storage upload failed for {storage_path}: {e}")
            print("    🔄 Using reliable GitHub CDN fallback...")
    
    return save_local_diagram(webp_bytes, storage_path)


def process_and_upload_diagram(
    source_url_or_bytes: str | bytes,
    exam: str,
    subject: str,
    doc_id: str,
    is_solution: bool = False,
    bucket=None
) -> str | None:
    """
    End-to-end pipeline: takes source URL or raw bytes, optimizes to WebP,
    uploads to Firebase Storage (or repository CDN fallback), and returns the CDN public URL.
    """

    if isinstance(source_url_or_bytes, str):
        if "firebasestorage.googleapis.com" in source_url_or_bytes:
            return source_url_or_bytes
        raw_bytes = download_image(source_url_or_bytes)
    else:
        raw_bytes = source_url_or_bytes

    if not raw_bytes:
        return None

    webp_bytes = compress_diagram_to_webp(raw_bytes)
    if not webp_bytes:
        return None

    suffix = "_sol" if is_solution else ""
    storage_path = f"questions/diagrams/{exam.lower()}/{subject.lower()}/{doc_id}{suffix}.webp"

    return upload_diagram_to_storage(webp_bytes, bucket, storage_path)


if __name__ == "__main__":
    import argparse
    from PIL import ImageDraw

    parser = argparse.ArgumentParser(description="Diagram Optimization & Cloud Storage Test Tool")
    parser.add_argument("--test", action="store_true", help="Run self-test generating a synthetic circuit diagram")
    args = parser.parse_args()

    if args.test:
        print("🧪 Running Diagram Processor self-test...")
        # Create a synthetic circuit diagram
        img = Image.new('RGB', (1000, 600), (255, 255, 255))
        draw = ImageDraw.Draw(img)
        # Draw a bridge diamond
        draw.polygon([(500, 100), (750, 300), (500, 500), (250, 300)], outline=(15, 23, 42), width=5)
        draw.line([(500, 100), (500, 500)], fill=(220, 38, 38), width=3)  # galvanometer branch
        draw.text((490, 290), "G", fill=(220, 38, 38))

        out = io.BytesIO()
        img.save(out, format="PNG")
        png_data = out.getvalue()
        print(f"   Original PNG size : {len(png_data)} bytes ({img.size[0]}x{img.size[1]})")

        webp_data = compress_diagram_to_webp(png_data, max_width=600)
        assert webp_data is not None, "Compression returned None"
        print(f"   Optimized WebP size: {len(webp_data)} bytes")
        print(f"   Compression ratio  : {(1 - len(webp_data)/len(png_data))*100:.1f}% reduction")
        print("✅ Diagram Processor self-test passed successfully!")
