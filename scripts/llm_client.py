"""
llm_client.py — Centralized Multi-Provider LLM Client (Gemini Primary + Groq Fallback).

Architecture & Design Rationale:
1. Primary Provider: Google Gemini (gemini-2.5-flash / gemini-flash-latest)
   - Extremely generous free tier: 1,000,000 TPM (Tokens/Minute), 1,500 RPD (Requests/Day).
   - Eliminates Groq's 6,000 TPM ceiling that caused frequent 429 hangs.
   - Built-in rate limiter enforces a 4.2-second minimum interval to strictly respect the 15 RPM free tier ceiling.
2. Fallback Provider: Groq (openai/gpt-oss-20b or llama-3.3-70b-versatile)
   - Used when GEMINI_API_KEY is not set, or when Gemini returns 429, 404, or network errors.
   - Implements MAX_RETRY_AFTER_SECS = 45 capping so workflow runs never stall.
3. OpenAI Compatibility:
   - Uses Google's official OpenAI-compatible endpoint:
     https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
   - Requires zero external dependencies beyond `requests`.
"""

import os
import re
import sys
import time
import requests

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# Environment variables
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "").strip()

# Gemini config
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
GEMINI_MODELS = [
    os.environ.get("GEMINI_MODEL", "gemini-flash-lite-latest"),
    "gemini-flash-latest",
    "gemini-3.1-flash-lite",
    "gemini-3.5-flash-lite"
]
# Enforces <= 12 requests per 60s (safely below the 15 RPM free tier limit)
GEMINI_MIN_INTERVAL = 5.0
_last_gemini_call_timestamp = 0.0
_gemini_exhausted = False


def reset_gemini_circuit_breaker():
    """Resets the circuit breaker state (useful for unit testing)."""
    global _gemini_exhausted, _last_gemini_call_timestamp
    _gemini_exhausted = False
    _last_gemini_call_timestamp = 0.0


def _wait_for_gemini_cooldown():
    """Enforces minimum interval between consecutive Gemini requests to stay under 15 RPM."""
    global _last_gemini_call_timestamp
    now = time.time()
    elapsed = now - _last_gemini_call_timestamp
    if elapsed < GEMINI_MIN_INTERVAL:
        sleep_needed = GEMINI_MIN_INTERVAL - elapsed
        time.sleep(sleep_needed)
    _last_gemini_call_timestamp = time.time()


def _post_gemini(body: dict, max_retries: int = 2) -> str:
    """
    Sends request to Gemini's OpenAI-compatible endpoint.
    Tries models in GEMINI_MODELS sequentially. If a model's daily quota is exhausted,
    fails over to alternative Gemini models before tripping the circuit breaker to Groq.
    """
    global _gemini_exhausted
    if _gemini_exhausted:
        return ""

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        return ""

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }

    gemini_body = dict(body)
    
    # Try models in order of preference
    for model_name in GEMINI_MODELS:
        gemini_body["model"] = model_name
        
        for attempt in range(max_retries):
            _wait_for_gemini_cooldown()
            try:
                resp = requests.post(GEMINI_URL, json=gemini_body, headers=headers, timeout=45)
                
                # Check for rate limit / quota exhaustion
                if resp.status_code == 429:
                    is_daily_quota = False
                    try:
                        err_text = resp.text
                        if "GenerateRequestsPerDayPerProjectPerModel" in err_text or "Quota exceeded" in err_text:
                            is_daily_quota = True
                    except Exception:
                        pass
                    
                    if is_daily_quota:
                        print(f"    ⚠️ Gemini model '{model_name}' daily quota reached. Trying next model in pool...")
                        break  # Break retry loop to try next model in GEMINI_MODELS
                    
                    retry_after = resp.headers.get("retry-after")
                    wait_time = 0
                    if retry_after:
                        try:
                            wait_time = float(retry_after) + 1
                        except ValueError:
                            wait_time = 5
                    else:
                        wait_time = (attempt + 1) * 5
                    
                    if wait_time > 10 or attempt == max_retries - 1:
                        print(f"    ⚠️ Gemini rate limit reached for '{model_name}'. Trying next model in pool...")
                        break  # Break retry loop to try next model
                    print(f"    ⏳ Gemini rate limit hit (429). Waiting {wait_time:.0f}s before retry (attempt {attempt + 1}/{max_retries})...")
                    time.sleep(wait_time)
                    continue
                
                # Check for model not found (404) -> try next model in GEMINI_MODELS
                if resp.status_code == 404:
                    print(f"    ⚠️ Gemini model '{model_name}' not available (404). Trying alternative model...")
                    break  # Break inner retry loop to try next model
                
                resp.raise_for_status()
                data = resp.json()
                content = data['choices'][0]['message']['content'].strip()
                return content
            except Exception as e:
                if attempt == max_retries - 1:
                    print(f"    ⚠️ Gemini request failed for '{model_name}': {e}")
                time.sleep(2)

    # If all Gemini models failed/exhausted, trip circuit breaker for this session
    _gemini_exhausted = True
    print("    ⚠️ All Gemini models in pool exhausted. Circuit breaker tripped to fallback provider.")
    return ""


# Groq config
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = os.environ.get("GROQ_MODEL", "openai/gpt-oss-20b")
MAX_GROQ_RETRY_AFTER_SECS = 45


def _post_groq(body: dict, max_retries: int = 3) -> str:
    """
    Sends request to Groq endpoint with Retry-After capping.
    """
    api_key = os.environ.get("GROQ_API_KEY", "").strip()
    if not api_key:
        return ""

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }

    groq_body = dict(body)
    # Ensure Groq-compatible model name
    if "model" not in groq_body or "gemini" in groq_body.get("model", "").lower():
        groq_body["model"] = GROQ_MODEL

    for attempt in range(max_retries):
        try:
            resp = requests.post(GROQ_URL, json=groq_body, headers=headers, timeout=45)
            if resp.status_code == 429:
                retry_after = resp.headers.get("retry-after")
                if retry_after:
                    try:
                        wait_time = float(retry_after) + 1
                    except ValueError:
                        wait_time = (attempt + 1) * 15
                else:
                    wait_time = (attempt + 1) * 15

                if wait_time > MAX_GROQ_RETRY_AFTER_SECS:
                    print(f"    ⚠️ Groq rate limit (429) requested excessive wait ({wait_time:.0f}s > {MAX_GROQ_RETRY_AFTER_SECS}s). Skipping.")
                    return ""

                print(f"    ⏳ Groq rate limit (429) hit. Waiting {wait_time:.0f}s before retry (attempt {attempt + 1}/{max_retries})...")
                time.sleep(wait_time)
                continue
            resp.raise_for_status()
            data = resp.json()
            return data['choices'][0]['message']['content'].strip()
        except Exception as e:
            if attempt == max_retries - 1:
                print(f"    ❌ Groq API error on final attempt: {e}")
                return ""
            time.sleep(5)
    return ""


def post_llm(body: dict, max_retries: int = 3, allow_fallback: bool = True) -> str:
    """
    Multi-provider LLM dispatcher:
    1. Attempts Gemini primary if GEMINI_API_KEY is available.
    2. Seamlessly falls back to Groq if Gemini is rate limited or unavailable.
    3. Uses Groq directly if GEMINI_API_KEY is not set.
    """
    has_gemini = bool(os.environ.get("GEMINI_API_KEY", "").strip())
    has_groq = bool(os.environ.get("GROQ_API_KEY", "").strip())

    # 1. Try Gemini primary
    if has_gemini:
        content = _post_gemini(body, max_retries=2)
        if content:
            return content
        if allow_fallback and has_groq:
            print("    🔄 Gemini unavailable or rate-limited. Seamlessly falling back to Groq...")

    # 2. Try Groq fallback / primary
    if has_groq:
        return _post_groq(body, max_retries=max_retries)

    if not has_gemini and not has_groq:
        print("    ❌ Neither GEMINI_API_KEY nor GROQ_API_KEY is set in environment.")
    return ""


def clean_markdown_json(raw: str) -> str:
    """Strips markdown codeblock fences from JSON responses."""
    cleaned = raw.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    return cleaned.strip()


def get_active_provider_summary() -> str:
    """Returns human-readable summary of active and fallback LLM providers."""
    has_gemini = bool(os.environ.get("GEMINI_API_KEY", GEMINI_API_KEY).strip())
    has_groq = bool(os.environ.get("GROQ_API_KEY", GROQ_API_KEY).strip())
    providers = []
    if has_gemini:
        providers.append(f"Primary: Gemini ({GEMINI_MODELS[0]}) [1,000,000 TPM]")
    if has_groq:
        role = "Fallback" if has_gemini else "Primary"
        providers.append(f"{role}: Groq ({GROQ_MODEL}) [6,000 TPM]")
    if not providers:
        return "None (No API keys found)"
    return " -> ".join(providers)
