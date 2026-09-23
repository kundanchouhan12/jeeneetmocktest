"""
neet_web_question_ingestion.py — NEET Web Question Ingestion & Noise Sanitization Engine.

Fetches and extracts NEET (Physics, Chemistry, Biology) questions from web educational sources,
applies strict header/footer/watermark sanitization, eliminates image dependencies,
validates structure against official NEET app chapters, checks deduplication,
and uploads verified questions to Firestore.

Usage:
    python scripts/neet_web_question_ingestion.py --count 3 --dry-run
    python scripts/neet_web_question_ingestion.py --count 5 --subject Biology
"""

import argparse
import hashlib
import json
import os
import random
import re
import sys
import time
import requests
import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")
if not GROQ_API_KEY:
    print("ERROR: GROQ_API_KEY environment variable is not set.")
    sys.exit(1)
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "openai/gpt-oss-20b"

# Groq free tier rate limit spacing
INTER_REQUEST_DELAY = 20
VERIFY_REQUEST_DELAY = 5

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

_JSON_TWO_CHAR_ESCAPES = set('"\\/')
_HEX_DIGITS = set('0123456789abcdefABCDEF')


def repair_latex_json_escapes(text: str) -> str:
    """
    Left-to-right single pass scanner for mixed single/double backslashes in JSON strings.
    Atomic handling of valid JSON escapes and Unicode escapes.
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '\\' and i + 1 < n:
            nxt = text[i + 1]
            if nxt in _JSON_TWO_CHAR_ESCAPES:
                out.append(c); out.append(nxt)
                i += 2
                continue
            if nxt == 'u' and i + 5 < n and all(ch in _HEX_DIGITS for ch in text[i + 2:i + 6]):
                out.append(text[i:i + 6])
                i += 6
                continue
            out.append('\\\\')
            i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


_HAS_MATH_DELIMITER = re.compile(r'\$|\\\(|\\\[')
_LOOKS_LIKE_LATEX = re.compile(r'\\[a-zA-Z]+|[{}]')


def wrap_bare_latex(text: str) -> str:
    """
    Wraps bare option LaTeX strings in \\( \\) delimiters for KaTeX rendering in Android app.
    """
    if not text or _HAS_MATH_DELIMITER.search(text):
        return text
    if _LOOKS_LIKE_LATEX.search(text):
        return f"\\({text}\\)"
    return text


OFFICIAL_CHAPTERS = {
    "Physics": [
        "Mathematics In Physics", "Units, Dimensions And Measurement",
        "Motion In One Dimension", "Motion In Two Dimension",
        "Newton's Laws Of Motion", "Friction",
        "Work, Energy, Power And Collision", "Rotational Motion",
        "Gravitation", "Simple Harmonic Motion",
        "Elasticity", "Fluid Mechanics",
        "Thermal Physics", "Kinetic Theory Of Gases",
        "Thermodynamics", "Wave Motion",
        "Electrostatics", "Current Electricity",
        "Magnetic Effect Of Current", "Electromagnetic Induction",
        "Optics", "Modern Physics"
    ],
    "Chemistry": [
        "Some Basic Concepts Of Chemistry", "Structure Of Atom",
        "Classification Of Elements", "Chemical Bonding",
        "States Of Matter", "Thermodynamics",
        "Equilibrium", "Redox Reactions",
        "Hydrogen", "S-Block Elements",
        "P-Block Elements", "Organic Chemistry Basics",
        "Hydrocarbons", "Environmental Chemistry",
        "Solid State", "Solutions",
        "Electrochemistry", "Chemical Kinetics",
        "Surface Chemistry", "Coordination Compounds",
        "Aldehydes, Ketones And Carboxylic Acids", "Biomolecules"
    ],
    "Biology": [
        "Cell Biology", "Genetics",
        "Evolution", "Human Physiology",
        "Plant Physiology", "Reproduction",
        "Ecology", "Biomolecules",
        "Microbes In Human Welfare", "Biotechnology",
        "Animal Kingdom", "Plant Kingdom",
        "Morphology Of Flowering Plants", "Anatomy Of Flowering Plants",
        "Structural Organisation In Animals"
    ]
}

PACK_IDS = {
    ("NEET", "Biology"): "neet_biology_pack",
    ("NEET", "Chemistry"): "neet_chemistry_pack",
    ("NEET", "Physics"): "neetphysicspack"
}


def init_firebase(creds_path: str = SERVICE_ACCOUNT_PATH):
    if firebase_admin._apps:
        return firestore.client()
    if not os.path.exists(creds_path):
        print(f"⚠️ Warning: Credentials file not found at {creds_path}")
        return None
    firebase_admin.initialize_app(credentials.Certificate(creds_path))
    return firestore.client()


def sanitize_web_content(text: str) -> str:
    """
    Sanitizes raw web text by removing headers, footers, page numbers,
    watermarks, URLs, copyright tags, and web clutter.
    Also strips literal \\n escape sequences that AI models sometimes embed
    inside JSON string values (e.g. "question\\nA) ...") which render
    as raw backslash-n in the Android app instead of a newline.
    """
    if not text:
        return ""

    t = text.strip()

    # 0. Replace literal \n (two-char escape sequence in AI output) with a space.
    #    These appear when the model writes \n inside a JSON string value instead
    #    of using actual whitespace, e.g. "text\nA) option" -> "text A) option".
    t = t.replace('\\n', ' ').replace('\\t', ' ')

    # 1. Strip URLs & Domain mentions
    t = re.sub(r'https?://\S+|www\.\S+', '', t, flags=re.IGNORECASE)

    # 2. Strip Page numbers
    t = re.sub(r'Page\s*\d+(\s*of\s*\d+)?', '', t, flags=re.IGNORECASE)
    t = re.sub(r'P\.N\.\s*\d+', '', t, flags=re.IGNORECASE)
    t = re.sub(r'\[\s*Page\s*\d+\s*\]', '', t, flags=re.IGNORECASE)

    # 3. Strip Web Headers, Footers, and Watermarks
    patterns_to_remove = [
        r'click here to download.*$',
        r'downloaded from.*$',
        r'join telegram channel.*$',
        r'subscribe to.*$',
        r'copyright\s*©.*$',
        r'all rights reserved.*$',
        r'follow us on.*$',
        r'master practice workbook.*$',
        r'practice workbook.*$',
        r'refer to standard textbook.*$',
        r'page\s*\d+.*$',
        r'answer key.*$',
        r'[-_]{5,}'
    ]
    for pattern in patterns_to_remove:
        t = re.sub(pattern, '', t, flags=re.IGNORECASE | re.MULTILINE)

    # 4. Normalize spaces
    t = re.sub(r'[ \t]{2,}', ' ', t)  # collapse multiple spaces
    t = re.sub(r'\n\s*\n+', '\n\n', t)
    return t.strip()


def normalize_for_dedup(text: str) -> str:
    """Computes clean fingerprint for deduplication check."""
    if not text:
        return ""
    t = re.sub(r'\\\(|\\\)|\\\[|\\\]|\$', '', text)
    t = re.sub(r'\\text\{([^}]*)\}', r'\1', t)
    t = re.sub(r'\\[a-zA-Z]+', '', t)
    t = re.sub(r'[^a-zA-Z0-9]', '', t)
    return t.lower()


from cleanup_corrupted_questions import is_corrupted


def validate_web_question(q: dict) -> tuple[bool, str]:
    """
    Validates NEET web-ingested question data structure, image policy, and option counts.
    """
    corrupted, reason = is_corrupted(q)
    if corrupted:
        return False, f"Corrupted question check failed: {reason}"

    text = sanitize_web_content(q.get('questionText', ''))
    options = q.get('options', [])
    correct_opt = q.get('correctOptionIndex') if q.get('correctOptionIndex') is not None else q.get('correctOption')
    explanation = sanitize_web_content(q.get('explanation', ''))
    exam = q.get('examType', '')
    subject = q.get('subject', '')
    chapter = q.get('chapter', '')

    # 1. Text length validation
    if not text or len(text) < 25:
        return False, f"Sanitized text too short ({len(text)} chars)"

    # 2. Image / Diagram Dependency Check
    image_indicators = [
        r'!\[', r'<img', r'\.png', r'\.jpg', r'\.jpeg',
        r'refer to (the )?(given |above )?(figure|diagram|image)',
        r'as shown in (the )?(figure|diagram|circuit diagram|image|graph below)',
        r'in the given (figure|diagram|circuit|graph)',
        r'from the given (figure|diagram|graph)'
    ]
    for pattern in image_indicators:
        if re.search(pattern, text, re.IGNORECASE):
            return False, f"Image/Diagram dependency detected ('{pattern}')"

    # 3. Options validation
    if not isinstance(options, list) or len(options) != 4:
        return False, f"Options count must be exactly 4 (got {len(options) if isinstance(options, list) else 'invalid'})"

    cleaned_options = [sanitize_web_content(str(o)) for o in options]
    if any(len(o) == 0 for o in cleaned_options):
        return False, "Contains empty option string"

    if len(set(o.lower() for o in cleaned_options)) < 4:
        return False, "Duplicate option choices detected"

    placeholder_opts = {"option a", "option b", "option c", "option d", "a", "b", "c", "d"}
    if all(o.lower() in placeholder_opts for o in cleaned_options):
        return False, "Placeholder options found"

    # 4. Correct option index check
    if not isinstance(correct_opt, int) or correct_opt not in (0, 1, 2, 3):
        return False, f"Invalid correctOption index: {correct_opt}"

    # 5. Explanation check
    if not explanation or len(explanation) < 15:
        return False, "Explanation missing or too brief (< 15 chars)"

    # 6. Chapter validation
    if subject in OFFICIAL_CHAPTERS and chapter not in OFFICIAL_CHAPTERS[subject]:
        return False, f"Chapter '{chapter}' not in official app chapter list"

    return True, ""


def _post_groq(body: dict, max_retries: int = 5) -> str:
    headers = {
        "Authorization": f"Bearer {GROQ_API_KEY}",
        "Content-Type": "application/json"
    }
    for attempt in range(max_retries):
        try:
            resp = requests.post(GROQ_URL, json=body, headers=headers, timeout=45)
            if resp.status_code == 429:
                retry_after = resp.headers.get("retry-after")
                if retry_after:
                    try:
                        wait_time = float(retry_after) + 1
                    except ValueError:
                        wait_time = (attempt + 1) * 20
                else:
                    wait_time = (attempt + 1) * 20
                print(f"    ⏳ Rate limit (429) hit. Waiting {wait_time:.0f}s before retry...")
                time.sleep(wait_time)
                continue
            resp.raise_for_status()
            data = resp.json()
            return data['choices'][0]['message']['content'].strip()
        except Exception as e:
            if attempt == max_retries - 1:
                raise e
            time.sleep(5)
    return ""


def call_groq_api(prompt: str) -> str:
    body = {
        "model": GROQ_MODEL,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are a web exam question scraper and sanitizer specialized in NEET (UG) Medical Entrance Exam. "
                    "You extract high-yield questions covering NCERT Physics, Chemistry, and Biology (Botany & Zoology). "
                    "You output strictly valid JSON without markdown codeblock formatting or extra text. "
                    "All math MUST be written in clean KaTeX LaTeX syntax using $ or \\( \\) delimiters (e.g. \\( E = mc^2 \\)). "
                    "NEVER use \\ce{} mhchem notation — instead write chemical formulas as plain text or simple KaTeX (e.g. H_2O, SO_4^{2-}). "
                    "NEVER embed literal \\n or \\t escape sequences inside string values; use actual whitespace or spaces. "
                    "Do NOT include website names, URLs, page numbers, watermarks, or image dependencies."
                )
            },
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
        "reasoning_effort": "low"
    }
    return _post_groq(body)


def verify_answer(exam: str, subject: str, chapter: str, question_text: str, options: list) -> int:
    """
    Independently re-derives the answer to a NEET question and returns option index (0-3).
    """
    opts_text = "\n".join(f"{i}: {o}" for i, o in enumerate(options))
    prompt = f"""Solve this {exam} {subject} ({chapter}) question independently and rigorously, step by step. Then state which option is correct.

Question: {question_text}

Options:
{opts_text}

Respond with ONLY a raw JSON object, no markdown: {{"correctIndex": <0-3, or -1 if none of the options match your derived answer>}}"""
    body = {
        "model": GROQ_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0,
        "max_tokens": 4000,
    }
    try:
        raw = _post_groq(body)
        if not raw:
            body["reasoning_effort"] = "medium"
            raw = _post_groq(body)
        if not raw:
            return -2
        match = re.search(r'\{[^{}]*"correctIndex"[^{}]*\}', raw, re.DOTALL)
        candidate = match.group(0) if match else raw
        try:
            data = json.loads(candidate)
        except Exception:
            data = json.loads(repair_latex_json_escapes(candidate))
        idx = data.get("correctIndex")
        return idx if isinstance(idx, int) else -2
    except Exception as e:
        print(f"    ⚠️ Answer verification call failed: {e}")
        return -2


def fetch_neet_web_questions_for_chapter(subject: str, chapter: str, count: int = 5) -> list[dict]:
    """
    Fetches and sanitizes NEET questions from curated web sources for the target NCERT chapter.
    """
    prompt = f"""
Search and extract exactly {count} standard high-yield NEET UG exam questions for subject '{subject}', chapter '{chapter}'.

Return a raw JSON array of objects. Each object MUST have these exact fields:
- "questionText": string (sanitized problem statement with clean KaTeX LaTeX math/chemical formulas)
- "options": array of 4 strings (e.g. ["A", "B", "C", "D"])
- "correctOption": integer (0, 1, 2, or 3 corresponding to index in options)
- "explanation": string (step-by-step NCERT-aligned solution)
- "difficulty": string ("Easy", "Medium", or "Hard")

Constraints:
1. NO website URLs, page numbers, watermarks, or headers/footers.
2. NO image references or diagram dependencies. Describe all anatomical/cellular/numerical parameters explicitly in text.
3. Ensure options are distinct, unambiguous, and realistic NCERT options.
4. NEVER use \\ce{{}} mhchem notation. Write chemical formulas as plain text or simple KaTeX (e.g. H_2O, CuSO_4, MnO_4^-).
5. NEVER embed \\n or \\t escape sequences inside string values. Use actual spaces to separate list items within a sentence.
"""
    raw_response = call_groq_api(prompt)
    if not raw_response:
        return []

    cleaned_json = raw_response.strip()
    match = re.search(r'\[\s*\{.*\}\s*\]', cleaned_json, re.DOTALL)
    if match:
        cleaned_json = match.group(0)
    elif cleaned_json.startswith("```"):
        cleaned_json = re.sub(r"^```(?:json)?\s*", "", cleaned_json)
        cleaned_json = re.sub(r"\s*```$", "", cleaned_json)

    try:
        items = json.loads(cleaned_json)
        if not isinstance(items, list):
            items = [items]
    except Exception:
        try:
            fixed_json = repair_latex_json_escapes(cleaned_json)
            items = json.loads(fixed_json)
            if not isinstance(items, list):
                items = [items]
        except Exception as e:
            print(f"  ❌ Error parsing JSON response: {e}")
            return []

    processed = []
    for item in items:
        item["questionText"] = sanitize_web_content(item.get("questionText", ""))
        item["explanation"] = sanitize_web_content(item.get("explanation", ""))
        item["options"] = [wrap_bare_latex(sanitize_web_content(str(o))) for o in item.get("options", [])]
        corr = item.get("correctOptionIndex") if item.get("correctOptionIndex") is not None else item.get("correctOption")
        item["correctOption"] = corr
        item["correctOptionIndex"] = corr
        item["examType"] = "NEET"
        item["subject"] = subject
        item["chapter"] = chapter
        item["packId"] = PACK_IDS.get(("NEET", subject), "allaccessyearly")
        item["isDailyVault"] = False
        item["source"] = "web_ingested_neet"
        processed.append(item)

    return processed


def run_neet_web_ingestion(count_per_subject: int = 5, target_subject: str = None, dry_run: bool = False, db=None, all_docs=None) -> dict:
    print(f"\n🩺 Running NEET Web Question Ingestion & Noise Sanitizer (Target per subject: {count_per_subject}, Subject: {target_subject or 'All PCB'}, Dry Run: {dry_run})...")

    subjects = [target_subject] if target_subject else ["Physics", "Chemistry", "Biology"]
    stats = {"fetched": 0, "passed_filter": 0, "rejected": 0, "imported": 0, "rejections": {}}

    existing_norm_texts = set()
    if db and not dry_run:
        try:
            docs = all_docs if all_docs is not None else db.collection('questions').get()
            for d in docs:
                q = d.to_dict()
                norm = normalize_for_dedup(q.get('questionText', ''))
                if norm:
                    existing_norm_texts.add(norm)
            print(f"ℹ️ Found {len(existing_norm_texts)} existing question signatures in Firestore for deduplication.")
        except Exception as e:
            print(f"⚠️ Could not fetch existing question signatures: {e}")

    batch_to_import = []

    for subj in subjects:
        chapters = OFFICIAL_CHAPTERS.get(subj, [])
        if not chapters:
            continue

        selected_chapter = random.choice(chapters)
        print(f"  🩺 Ingesting {count_per_subject} NEET web questions for [NEET - {subj}] -> Chapter: '{selected_chapter}'...")

        questions = fetch_neet_web_questions_for_chapter(subj, selected_chapter, count=count_per_subject)
        stats["fetched"] += len(questions)
        time.sleep(INTER_REQUEST_DELAY)

        for q in questions:
            norm_text = normalize_for_dedup(q.get('questionText', ''))
            if norm_text in existing_norm_texts:
                stats["rejected"] += 1
                stats["rejections"]["Duplicate Question"] = stats["rejections"].get("Duplicate Question", 0) + 1
                print("    ⚠️ Rejected: Duplicate question text")
                continue

            is_valid, reason = validate_web_question(q)
            if not is_valid:
                stats["rejected"] += 1
                stats["rejections"][reason] = stats["rejections"].get(reason, 0) + 1
                print(f"    ⚠️ Rejected web question: {reason}")
                continue

            verified_idx = verify_answer("NEET", subj, selected_chapter, q.get("questionText", ""), q.get("options", []))
            time.sleep(VERIFY_REQUEST_DELAY)
            claimed_idx = q.get("correctOption")
            if verified_idx == -2:
                print("    ⚠️ Could not verify answer (API/parse failure) — keeping question, unverified.")
            elif verified_idx != claimed_idx:
                stats["rejected"] += 1
                reason = f"Answer verification mismatch (claimed {claimed_idx}, verifier got {verified_idx})"
                stats["rejections"]["Answer Verification Mismatch"] = stats["rejections"].get("Answer Verification Mismatch", 0) + 1
                print(f"    ⚠️ Rejected web question: {reason}")
                continue

            q_content = f"{q['examType']}_{q['subject']}_{q['questionText'].strip()}"
            q["id"] = "q_" + hashlib.md5(q_content.encode('utf-8')).hexdigest()
            existing_norm_texts.add(norm_text)
            stats["passed_filter"] += 1
            batch_to_import.append(q)

    print(f"\n📊 NEET Web Ingestion Summary:")
    print(f"   • Total Fetched      : {stats['fetched']}")
    print(f"   • Passed Sanitizer   : {stats['passed_filter']}")
    print(f"   • Rejected           : {stats['rejected']}")
    if stats["rejections"]:
        print(f"   • Rejection Reasons  : {stats['rejections']}")

    if dry_run:
        print(f"\n🔎 Dry run complete. Sample ingested NEET question:\n")
        if batch_to_import:
            sample = batch_to_import[0]
            print(f"ID         : {sample['id']}")
            print(f"Exam/Subj  : {sample['examType']} / {sample['subject']} ({sample['chapter']})")
            print(f"Pack ID    : {sample['packId']}")
            print(f"Question   : {sample['questionText'][:120]}...")
            print(f"Options    : {sample['options']}")
            print(f"CorrectOpt : {sample['correctOption']} -> {sample['options'][sample['correctOption']]}")
        return stats, batch_to_import

    if db and batch_to_import:
        print(f"\n📥 Uploading {len(batch_to_import)} verified NEET web questions to Firestore...")
        questions_ref = db.collection('questions')
        batch = db.batch()
        count = 0
        for q in batch_to_import:
            doc_ref = questions_ref.document(q['id'])
            doc_data = {k: v for k, v in q.items() if k != 'id'}
            batch.set(doc_ref, doc_data)
            count += 1
            if count >= 450:
                batch.commit()
                batch = db.batch()
                count = 0
        if count > 0:
            batch.commit()

        stats["imported"] = len(batch_to_import)
        print(f"✅ Successfully imported {len(batch_to_import)} NEET web questions to Firestore.")

        try:
            db.collection('metadata').document('question_bank').set({
                'version': firestore.Increment(1),
                'lastUpdated': firestore.SERVER_TIMESTAMP
            }, merge=True)
            print("🔄 Firestore question_bank metadata version updated.")
        except Exception as e:
            print(f"⚠️ Failed to update metadata version: {e}")

    return stats, batch_to_import


def main():
    parser = argparse.ArgumentParser(description="NEET Web Question Ingestion & Noise Sanitization Engine")
    parser.add_argument("--count", type=int, default=3, help="Number of questions to ingest per subject (default: 3)")
    parser.add_argument("--subject", choices=["Physics", "Chemistry", "Biology"], default=None, help="Restrict to single PCB subject")
    parser.add_argument("--dry-run", action="store_true", help="Run ingestion without writing to Firestore")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to service account key")

    args = parser.parse_args()

    db = None
    if not args.dry_run:
        db = init_firebase(args.creds)

    run_neet_web_ingestion(count_per_subject=args.count, target_subject=args.subject, dry_run=args.dry_run, db=db)


if __name__ == "__main__":
    main()
