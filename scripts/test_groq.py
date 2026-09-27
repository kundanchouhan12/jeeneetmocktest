import os
import requests
import json

GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")
GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL   = "groq/compound"

headers = {
    "Authorization": f"Bearer {GROQ_API_KEY}",
    "Content-Type": "application/json"
}
body = {
    "model": GROQ_MODEL,
    "messages": [
        {"role": "user", "content": "Hello! Reply with 'OK'."}
    ],
    "temperature": 0.05,
    "max_tokens": 10
}

r = requests.post(GROQ_URL, json=body, headers=headers)
print("Status Code:", r.status_code)
print("Response:", r.text)
