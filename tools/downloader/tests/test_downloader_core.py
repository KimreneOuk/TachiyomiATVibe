import http.client
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import downloader_core
from downloader_core import (
    _PoliteFetcher,
    download_chapter_images,
    extract_image_urls,
    filename_for,
    has_ajax_pagination_hint,
)


FIXTURE = Path(__file__).parent / "fixtures" / "chapter.html"


class ExtractImageUrlsTests(unittest.TestCase):
    def test_extracts_reader_images_in_order_and_ignores_page_chrome(self):
        html = FIXTURE.read_text(encoding="utf-8")

        self.assertEqual(
            extract_image_urls(html, "https://rawkuma.net/manga/demo/chapter-1/"),
            [
                "https://cdn.example.test/wp-content/uploads/2025/04/page-1.jpg",
                "https://cdn.example.test/wp-content/uploads/2025/04/page-2.webp",
                "https://rawkuma.net/wp-content/uploads/2025/04/page-3.jpg",
            ],
        )

    def test_extracts_rawkuma_image_section_without_site_chrome(self):
        html = (FIXTURE.parent / "rawkuma_chapter.html").read_text(encoding="utf-8")

        self.assertEqual(
            extract_image_urls(html, "https://rawkuma.net/manga/demo/chapter-1/"),
            [
                "https://images.example.test/chapter/page-1.jpg",
                "https://images.example.test/chapter/page-2.jpg",
            ],
        )


class FilenameTests(unittest.TestCase):
    def test_filenames_are_one_indexed_without_padding(self):
        self.assertEqual(filename_for(1), "1.jpg")
        self.assertEqual(filename_for(12), "12.jpg")
        self.assertEqual(filename_for(105), "105.jpg")

    def test_filename_rejects_non_positive_indexes(self):
        with self.assertRaises(ValueError):
            filename_for(0)
        with self.assertRaises(ValueError):
            filename_for(-1)


class PaginationHintTests(unittest.TestCase):
    def test_detects_madara_ajax_reader_endpoint(self):
        self.assertTrue(
            has_ajax_pagination_hint(
                '<script>url="/wp-admin/admin-ajax.php?action=lazy_load"</script>'
            )
        )

    def test_ordinary_reader_html_is_not_marked_as_ajax_paginated(self):
        self.assertFalse(has_ajax_pagination_hint(FIXTURE.read_text(encoding="utf-8")))


class FetchFailureTests(unittest.TestCase):
    def test_retries_incomplete_http_response(self):
        attempts = []

        class Response:
            headers = {"Content-Type": "image/jpeg"}

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                if len(attempts) == 1:
                    raise http.client.IncompleteRead(b"partial", 20)
                return b"\xff\xd8\xffcomplete"

        def fake_urlopen(request, timeout):
            attempts.append(request.full_url)
            return Response()

        with (
            patch("downloader_core.urlopen", side_effect=fake_urlopen),
            patch("downloader_core.REQUEST_DELAY_SECONDS", 0),
        ):
            body, content_type = _PoliteFetcher(threading.Event()).get(
                "https://images.example.test/page.jpg"
            )

        self.assertEqual(len(attempts), 2)
        self.assertTrue(body.startswith(b"\xff\xd8\xff"))
        self.assertEqual(content_type, "image/jpeg")

    def test_exhausted_page_retries_are_reported_and_the_batch_continues(self):
        chapter_url = "https://rawkuma.net/manga/demo/chapter-1/"
        html = """<div class="reading-content">
          <img src="https://images.example.test/1.jpg">
          <img src="https://images.example.test/2.jpg">
        </div>""".encode("utf-8")
        attempts_for_first_image = 0

        class Response:
            def __init__(self, body, content_type, incomplete=False):
                self.body = body
                self.headers = {"Content-Type": content_type}
                self.incomplete = incomplete

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                if self.incomplete:
                    raise http.client.IncompleteRead(b"partial", 20)
                return self.body

        def fake_urlopen(request, timeout):
            nonlocal attempts_for_first_image
            if request.full_url == chapter_url:
                return Response(html, "text/html")
            if request.full_url.endswith("/1.jpg"):
                attempts_for_first_image += 1
                return Response(b"", "image/jpeg", incomplete=True)
            return Response(b"\xff\xd8\xff\xdbvalid-jpeg", "image/jpeg")

        with tempfile.TemporaryDirectory() as temporary_directory:
            with (
                patch("downloader_core.urlopen", side_effect=fake_urlopen),
                patch("downloader_core.REQUEST_DELAY_SECONDS", 0),
            ):
                result = download_chapter_images(
                    chapter_url, temporary_directory, threading.Event()
                )
            saved_names = sorted(path.name for path in result.output_dir.iterdir())

        self.assertEqual(attempts_for_first_image, downloader_core.RETRY_COUNT + 1)
        self.assertEqual([item[0] for item in result.errors], [1])
        self.assertEqual(result.downloaded_count, 1)
        self.assertEqual(saved_names, ["2.jpg"])

    def test_invalid_image_responses_are_reported_and_later_pages_continue(self):
        chapter_url = "https://rawkuma.net/manga/demo/chapter-1/"
        html = """<div class="reading-content">
          <img src="https://images.example.test/1.jpg">
          <img src="https://images.example.test/2.jpg">
          <img src="https://images.example.test/3.jpg">
          <img src="https://images.example.test/4.jpg">
          <img src="https://images.example.test/5.jpg">
        </div>"""
        responses = {
            chapter_url: (html.encode("utf-8"), "text/html; charset=utf-8"),
            "https://images.example.test/1.jpg": (b"", "image/jpeg"),
            "https://images.example.test/2.jpg": (b'{"error":"blocked"}', "application/json"),
            "https://images.example.test/3.jpg": (b'{"error":"blocked"}', "image/jpeg"),
            "https://images.example.test/4.jpg": (b"\xff\xd8\xff\xdbvalid-jpeg", "application/octet-stream"),
            "https://images.example.test/5.jpg": (b'{"error":"blocked"}', ""),
        }

        def fake_get(_fetcher, url, referer=None):
            return responses[url]

        with tempfile.TemporaryDirectory() as temporary_directory:
            with patch.object(_PoliteFetcher, "get", new=fake_get):
                result = download_chapter_images(
                    chapter_url, temporary_directory, threading.Event()
                )

            image_folder = result.output_dir
            saved_names = sorted(path.name for path in image_folder.iterdir())

        self.assertEqual(result.image_count, 5)
        self.assertEqual(result.downloaded_count, 1)
        self.assertEqual([item[0] for item in result.errors], [1, 2, 3, 5])
        self.assertEqual(saved_names, ["4.jpg"])


if __name__ == "__main__":
    unittest.main()
