"""
test_groq_rate_limit.py — Unit tests for Groq 429 rate limit backoff and capping.

Verifies:
1. Excessive Retry-After headers (> 45s) do not cause multi-minute/hour sleeps and fail fast.
2. Short Retry-After headers (<= 45s) retry and succeed if subsequent attempt returns 200.
3. Persistent 429s exhaust after max 3 attempts without uncaught exceptions.
4. Consistent behavior across web_question_ingestion, neet_web_question_ingestion, and auto_question_pipeline.
"""

import os
import unittest
from unittest.mock import patch

# Dummy API key to satisfy module imports
os.environ.setdefault("GROQ_API_KEY", "test-dummy-groq-key")

import web_question_ingestion
import neet_web_question_ingestion
import auto_question_pipeline


class FakeResponse:
    def __init__(self, status_code: int, headers: dict = None, json_data: dict = None):
        self.status_code = status_code
        self.headers = headers or {}
        self._json_data = json_data or {}

    def json(self):
        return self._json_data

    def raise_for_status(self):
        if self.status_code >= 400:
            import requests
            raise requests.HTTPError(f"HTTP {self.status_code}")


class GroqRateLimitTest(unittest.TestCase):

    def setUp(self):
        self.modules = [
            web_question_ingestion,
            neet_web_question_ingestion,
            auto_question_pipeline
        ]

    def test_excessive_retry_after_skips_immediately_without_sleeping(self):
        """When Groq returns 429 with Retry-After: 1488 (25 mins), it must skip fast without sleeping."""
        for mod in self.modules:
            with patch("requests.post") as mock_post, patch("time.sleep") as mock_sleep:
                mock_post.return_value = FakeResponse(
                    status_code=429,
                    headers={"retry-after": "1488"}
                )

                result = mod._post_groq({"test": "body"}, max_retries=3)

                self.assertEqual(result, "", f"Expected empty result on excessive 429 for {mod.__name__}")
                # Mock sleep must NOT have been called with 1488s or anything > 45s
                mock_sleep.assert_not_called()
                self.assertEqual(mock_post.call_count, 1, f"Should abort on first excessive 429 for {mod.__name__}")

    def test_reasonable_retry_after_retries_and_succeeds(self):
        """When Groq returns 429 with Retry-After: 2, it sleeps 3s and retries."""
        for mod in self.modules:
            with patch("requests.post") as mock_post, patch("time.sleep") as mock_sleep:
                mock_post.side_effect = [
                    FakeResponse(status_code=429, headers={"retry-after": "2"}),
                    FakeResponse(
                        status_code=200,
                        json_data={"choices": [{"message": {"content": "ok_response"}}]}
                    )
                ]

                result = mod._post_groq({"test": "body"}, max_retries=3)

                self.assertEqual(result, "ok_response")
                self.assertEqual(mock_post.call_count, 2)
                mock_sleep.assert_called_once_with(3.0)

    def test_persistent_429_exhausts_bounded_retries(self):
        """When 429 persists across all attempts with short retry-after, caps at max_retries."""
        for mod in self.modules:
            with patch("requests.post") as mock_post, patch("time.sleep") as mock_sleep:
                mock_post.return_value = FakeResponse(
                    status_code=429,
                    headers={"retry-after": "5"}
                )

                result = mod._post_groq({"test": "body"}, max_retries=3)

                self.assertEqual(result, "")
                self.assertEqual(mock_post.call_count, 3)
                self.assertEqual(mock_sleep.call_count, 3)

    def test_network_exception_fails_gracefully(self):
        """Network exception on final attempt returns empty string without raising uncaught exception."""
        for mod in self.modules:
            with patch("requests.post") as mock_post, patch("time.sleep") as mock_sleep:
                import requests
                mock_post.side_effect = requests.ConnectionError("Connection refused")

                result = mod._post_groq({"test": "body"}, max_retries=2)

                self.assertEqual(result, "")
                self.assertEqual(mock_post.call_count, 2)


if __name__ == "__main__":
    unittest.main()
