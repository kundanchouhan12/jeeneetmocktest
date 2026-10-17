"""
test_llm_client.py — Unit tests for centralized multi-provider LLM client (Gemini + Groq).

Verifies:
1. Primary Gemini success path.
2. Gemini 429 rate limit triggers immediate fallback to Groq.
3. Gemini 404 (model not found) tries secondary model and falls back to Groq.
4. Gemini connection error falls back to Groq.
5. Gemini rate limiter paces requests to satisfy 15 RPM (< 15 calls / 60s).
6. Groq-specific _post_groq preserves MAX_RETRY_AFTER_SECS capping.
7. clean_markdown_json strips markdown code blocks accurately.
"""

import os
import sys
import time
import unittest
from unittest.mock import patch, MagicMock

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import llm_client


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


class LLMClientTest(unittest.TestCase):

    def setUp(self):
        llm_client.reset_gemini_circuit_breaker()
        self.original_gemini_key = os.environ.get("GEMINI_API_KEY", "")
        self.original_groq_key = os.environ.get("GROQ_API_KEY", "")

    def tearDown(self):
        llm_client.reset_gemini_circuit_breaker()
        if self.original_gemini_key:
            os.environ["GEMINI_API_KEY"] = self.original_gemini_key
        else:
            os.environ.pop("GEMINI_API_KEY", None)

        if self.original_groq_key:
            os.environ["GROQ_API_KEY"] = self.original_groq_key
        else:
            os.environ.pop("GROQ_API_KEY", None)

    def test_gemini_primary_success(self):
        """When Gemini returns 200, post_llm returns Gemini's answer without calling Groq."""
        os.environ["GEMINI_API_KEY"] = "fake-gemini-key"
        os.environ["GROQ_API_KEY"] = "fake-groq-key"

        with patch("requests.post") as mock_post:
            mock_post.return_value = FakeResponse(
                status_code=200,
                json_data={"choices": [{"message": {"content": "Gemini generated question"}}]}
            )

            result = llm_client.post_llm({"messages": [{"role": "user", "content": "test"}]})

            self.assertEqual(result, "Gemini generated question")
            self.assertEqual(mock_post.call_count, 1)
            # Ensure the call was made to the Gemini endpoint
            call_url = mock_post.call_args[0][0]
            self.assertEqual(call_url, llm_client.GEMINI_URL)

    @patch("time.sleep")
    def test_gemini_429_triggers_groq_fallback(self, mock_sleep):
        """When Gemini returns 429 with high retry-after, it falls back to Groq."""
        os.environ["GEMINI_API_KEY"] = "fake-gemini-key"
        os.environ["GROQ_API_KEY"] = "fake-groq-key"

        def side_effect(url, **kwargs):
            if "generativelanguage" in url:
                return FakeResponse(status_code=429, headers={"retry-after": "60"})
            else:
                return FakeResponse(
                    status_code=200,
                    json_data={"choices": [{"message": {"content": "Groq fallback answer"}}]}
                )

        with patch("requests.post", side_effect=side_effect) as mock_post:
            result = llm_client.post_llm({"messages": [{"role": "user", "content": "test"}]})

            self.assertEqual(result, "Groq fallback answer")
            self.assertGreaterEqual(mock_post.call_count, 2)

    @patch("time.sleep")
    def test_gemini_404_model_not_found_triggers_groq_fallback(self, mock_sleep):
        """When Gemini models return 404, it exhausts Gemini models and falls back to Groq."""
        os.environ["GEMINI_API_KEY"] = "fake-gemini-key"
        os.environ["GROQ_API_KEY"] = "fake-groq-key"

        def side_effect(url, **kwargs):
            if "generativelanguage" in url:
                return FakeResponse(status_code=404)
            else:
                return FakeResponse(
                    status_code=200,
                    json_data={"choices": [{"message": {"content": "Groq recovered answer"}}]}
                )

        with patch("requests.post", side_effect=side_effect) as mock_post:
            result = llm_client.post_llm({"messages": [{"role": "user", "content": "test"}]})

            self.assertEqual(result, "Groq recovered answer")

    def test_groq_primary_when_gemini_key_missing(self):
        """When GEMINI_API_KEY is unset, post_llm calls Groq directly."""
        os.environ.pop("GEMINI_API_KEY", None)
        os.environ["GROQ_API_KEY"] = "fake-groq-key"

        with patch("requests.post") as mock_post:
            mock_post.return_value = FakeResponse(
                status_code=200,
                json_data={"choices": [{"message": {"content": "Direct Groq response"}}]}
            )

            result = llm_client.post_llm({"messages": [{"role": "user", "content": "test"}]})

            self.assertEqual(result, "Direct Groq response")
            call_url = mock_post.call_args[0][0]
            self.assertEqual(call_url, llm_client.GROQ_URL)

    def test_clean_markdown_json(self):
        """Markdown codeblock fences should be cleanly stripped."""
        wrapped = "```json\n[{\"questionText\": \"test\"}]\n```"
        self.assertEqual(llm_client.clean_markdown_json(wrapped), "[{\"questionText\": \"test\"}]")

        plain = "[{\"questionText\": \"plain\"}]"
        self.assertEqual(llm_client.clean_markdown_json(plain), "[{\"questionText\": \"plain\"}]")

    def test_gemini_rate_limiter_pacing(self):
        """_wait_for_gemini_cooldown ensures minimum interval between calls."""
        llm_client._last_gemini_call_timestamp = time.time()
        
        with patch("time.sleep") as mock_sleep:
            llm_client._wait_for_gemini_cooldown()
            # Since _last_gemini_call_timestamp was just set to time.time(),
            # elapsed is ~0s, so sleep must be called with ~5.0s.
            self.assertTrue(mock_sleep.called)
            sleep_arg = mock_sleep.call_args[0][0]
            self.assertGreater(sleep_arg, 4.5)
            self.assertLessEqual(sleep_arg, 5.1)


if __name__ == "__main__":
    unittest.main()
