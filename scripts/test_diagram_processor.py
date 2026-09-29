"""
test_diagram_processor.py
=========================
Unit tests for STEM Diagram Processor & Cloud Storage Engine.
"""

import io
import sys
import unittest
from unittest.mock import MagicMock
from PIL import Image, ImageDraw

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

from diagram_processor import (
    compress_diagram_to_webp,
    download_image,
    upload_diagram_to_storage,
    process_and_upload_diagram,
    MAX_DIAGRAM_WIDTH
)


class TestDiagramProcessor(unittest.TestCase):

    def test_compress_diagram_rgb_downscale(self):
        """Verify image wider than MAX_DIAGRAM_WIDTH is resized proportionally."""
        img = Image.new('RGB', (1600, 1200), (255, 255, 255))
        draw = ImageDraw.Draw(img)
        draw.rectangle([200, 200, 1400, 1000], fill=(30, 41, 59))
        
        buf = io.BytesIO()
        img.save(buf, format='PNG')
        raw_bytes = buf.getvalue()

        webp_bytes = compress_diagram_to_webp(raw_bytes, max_width=800)
        self.assertIsNotNone(webp_bytes)

        # Inspect resulting image
        out_img = Image.open(io.BytesIO(webp_bytes))
        self.assertLessEqual(out_img.width, 800)
        self.assertEqual(out_img.format, "WEBP")

    def test_compress_diagram_preserves_alpha(self):
        """Verify RGBA images retain transparency mode in WebP output."""
        img = Image.new('RGBA', (400, 400), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        draw.ellipse([50, 50, 350, 350], fill=(16, 185, 129, 200))

        buf = io.BytesIO()
        img.save(buf, format='PNG')
        raw_bytes = buf.getvalue()

        webp_bytes = compress_diagram_to_webp(raw_bytes)
        self.assertIsNotNone(webp_bytes)

        out_img = Image.open(io.BytesIO(webp_bytes))
        self.assertEqual(out_img.mode, "RGBA")
        self.assertEqual(out_img.format, "WEBP")

    def test_compress_invalid_data_returns_none(self):
        """Corrupt or garbage bytes should return None instead of crashing."""
        garbage = b"not_an_image_data_stream_corrupt_test"
        result = compress_diagram_to_webp(garbage)
        self.assertIsNone(result)

    def test_download_image_invalid_url(self):
        """Invalid URLs or empty strings should return None safely."""
        self.assertIsNone(download_image(""))
        self.assertIsNone(download_image("ftp://invalid.com/image.png"))

    def test_upload_diagram_to_storage_mock(self):
        """Verify upload sets metadata token and generates valid Firebase CDN URL."""
        mock_bucket = MagicMock()
        mock_bucket.name = "apps-273d9.firebasestorage.app"
        mock_blob = MagicMock()
        mock_blob.name = "questions/diagrams/jee/physics/q_test123.webp"
        mock_bucket.blob.return_value = mock_blob

        sample_bytes = b"RIFFtestWEBPVP8"
        url = upload_diagram_to_storage(sample_bytes, mock_bucket, mock_blob.name)

        self.assertIsNotNone(url)
        self.assertTrue(url.startswith("https://firebasestorage.googleapis.com/v0/b/apps-273d9.firebasestorage.app/o/"))
        self.assertIn("alt=media&token=", url)
        mock_blob.upload_from_string.assert_called_once()
        self.assertIn("firebaseStorageDownloadTokens", mock_blob.metadata)

    def test_process_and_upload_without_bucket_returns_none(self):
        """If bucket is None, returns None gracefully."""
        res = process_and_upload_diagram("https://example.com/test.png", "JEE", "Physics", "q_123", bucket=None)
        self.assertIsNone(res)


if __name__ == "__main__":
    unittest.main()
