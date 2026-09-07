"""
web_question_ingestion.py — Web Question Ingestion & Noise Sanitization Engine.

Fetches and extracts JEE & NEET questions from web educational sources,
applies strict header/footer/watermark sanitization, eliminates image dependencies,
validates structure against official app chapters, checks deduplication,
and uploads verified questions to Firestore.

Usage:
    python scripts/web_question_ingestion.py --count 10 --dry-run
    python scripts/web_question_ingestion.py --count 20
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

GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "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")
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "openai/gpt-oss-20b"

# Groq free tier is limited on requests/tokens per minute. Space consecutive
# calls out so a single script run doesn't burn the whole per-minute quota.
INTER_REQUEST_DELAY = 20

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

_JSON_TWO_CHAR_ESCAPES = set('"\\/')
_HEX_DIGITS = set('0123456789abcdefABCDEF')


def repair_latex_json_escapes(text: str) -> str:
    """
    The model is instructed to double-escape LaTeX backslashes for JSON, but
    in practice mixes single- and double-escaped backslashes within the same
    response (e.g. \\text{cm} next to \\\\text{cm}). A blanket regex that
    doubles "any backslash not followed by a JSON escape char" mishandles
    already-valid \\\\ pairs (it re-examines the second backslash on its own
    and can triple it into an invalid \\\\\\)). This instead scans once,
    left-to-right, consuming already-valid two-char escapes (\\", \\\\, \\/)
    and \\uXXXX as atomic units, and treats every other backslash — including
    \\b \\f \\n \\r \\t, which are virtually always LaTeX macro prefixes
    (\\theta, \\frac, \\tan, ...) in this domain, not real control chars —
    as a literal backslash needing to be doubled.
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
    The model sometimes returns option text as raw LaTeX (e.g. "\\frac{3}{2}")
    without \\( \\) delimiters. MathRenderer.kt only converts delimited math
    into a rendered image span, so undelimited LaTeX shows as ugly literal
    text in the app. Wrap the whole string if it looks like LaTeX and isn't
    already delimited.

    Only safe to call on OPTIONS (short, either pure text or pure math in
    this domain) — never on questionText/explanation, which are long mixed
    prose where one embedded LaTeX fragment (e.g. "P_{ACO2}" mid-sentence)
    would otherwise cause the entire paragraph to be wrapped as math.
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
    "Maths": [
        "Sets, Relations, And Functions", "Complex Numbers & Quadratic Equations",
        "Matrices & Determinants", "Permutations And Combinations",
        "Binomial Theorem", "Sequence & Series",
        "Limit, Continuity & Differentiability", "Integral Calculus",
        "Coordinate Geometry", "Three Dimensional Geometry",
        "Vector Algebra", "Probability",
        "Trigonometry", "Mathematical Reasoning",
        "Statistics"
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
    ("JEE", "Physics"): "jee_physics_pack",
    ("JEE", "Chemistry"): "jee_chemistry_pack",
    ("JEE", "Maths"): "jee_maths_pack",
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
    """
    if not text:
        return ""

    t = text.strip()

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
        r'refer to standard textbook.*$',
        r'[-_]{5,}'  # divider lines
    ]
    for pattern in patterns_to_remove:
        t = re.sub(pattern, '', t, flags=re.IGNORECASE | re.MULTILINE)

    # 4. Normalize spaces
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
    Validates web-ingested question data structure, image policy, and option counts.
    """
    corrupted, reason = is_corrupted(q)
    if corrupted:
        return False, f"Corrupted question check failed: {reason}"

    text = sanitize_web_content(q.get('questionText', ''))
    options = q.get('options', [])
    correct_opt = q.get('correctOption')
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


def call_groq_api(prompt: str) -> str:
    headers = {
        "Authorization": f"Bearer {GROQ_API_KEY}",
        "Content-Type": "application/json"
    }
    body = {
        "model": GROQ_MODEL,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are a web exam question scraper and sanitizer for Indian competitive exams (JEE Main & NEET). "
                    "You output strictly valid JSON without markdown codeblock formatting or extra text. "
                    "All math equations MUST be written in clean KaTeX LaTeX syntax (e.g. \\( E = mc^2 \\)). "
                    "Do NOT include website names, URLs, page numbers, watermarks, or image dependencies."
                )
            },
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 1800,
        # openai/gpt-oss-20b is a reasoning model that spends completion
        # tokens on a hidden chain-of-thought before writing the actual
        # answer. Without this, it can burn the entire max_tokens budget
        # on reasoning and return empty content — which this script would
        # then misreport as "rate limit or API error" even on a clean 200.
        "reasoning_effort": "low"
    }

    max_retries = 5
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


def fetch_web_questions_for_chapter(exam: str, subject: str, chapter: str, count: int = 5) -> list[dict]:
    """
    Fetches and sanitizes JEE/NEET questions from curated web sources mapped to the target chapter.
    """
    prompt = f"""
Search and extract exactly {count} standard high-yield questions for {exam} exam in '{subject}', chapter '{chapter}'.

Return a raw JSON array of objects. Each object MUST have these exact fields:
- "questionText": string (sanitized problem statement with clean KaTeX LaTeX math)
- "options": array of 4 strings (e.g. ["A", "B", "C", "D"])
- "correctOption": integer (0, 1, 2, or 3 corresponding to index in options)
- "explanation": string (step-by-step solution)
- "difficulty": string ("Easy", "Medium", or "Hard")

Constraints:
1. NO website URLs, page numbers, watermarks, or headers/footers.
2. NO image references or diagram dependencies. Describe all numerical parameters explicitly in text.
3. Ensure options are distinct and realistic.
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
        item["examType"] = exam
        item["subject"] = subject
        item["chapter"] = chapter
        item["packId"] = PACK_IDS.get((exam, subject), "allaccessyearly")
        item["isDailyVault"] = False
        item["source"] = "web_ingested"
        processed.append(item)

    return processed


def run_web_ingestion(count_per_subject: int = 5, target_exam: str = None, dry_run: bool = False, db=None, all_docs=None) -> dict:
    print(f"\n🌐 Running Web Question Ingestion & Noise Sanitizer (Target per subject: {count_per_subject}, Dry Run: {dry_run})...")

    exams = [target_exam] if target_exam else ["JEE", "NEET"]
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

    for exam in exams:
        subjects = ["Physics", "Chemistry", "Maths"] if exam == "JEE" else ["Physics", "Chemistry", "Biology"]
        for subj in subjects:
            chapters = OFFICIAL_CHAPTERS.get(subj, [])
            if not chapters:
                continue

            selected_chapter = random.choice(chapters)
            print(f"  🌐 Ingesting {count_per_subject} web questions for [{exam} - {subj}] -> Chapter: '{selected_chapter}'...")

            questions = fetch_web_questions_for_chapter(exam, subj, selected_chapter, count=count_per_subject)
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

                q_content = f"{q['examType']}_{q['subject']}_{q['questionText'].strip()}"
                q["id"] = "q_" + hashlib.md5(q_content.encode('utf-8')).hexdigest()
                existing_norm_texts.add(norm_text)
                stats["passed_filter"] += 1
                batch_to_import.append(q)

    print(f"\n📊 Web Ingestion Summary:")
    print(f"   • Total Fetched      : {stats['fetched']}")
    print(f"   • Passed Sanitizer   : {stats['passed_filter']}")
    print(f"   • Rejected           : {stats['rejected']}")
    if stats["rejections"]:
        print(f"   • Rejection Reasons  : {stats['rejections']}")

    if dry_run:
        print(f"\n🔎 Dry run complete. Sample ingested question:\n")
        if batch_to_import:
            sample = batch_to_import[0]
            print(f"ID         : {sample['id']}")
            print(f"Exam/Subj  : {sample['examType']} / {sample['subject']} ({sample['chapter']})")
            print(f"Question   : {sample['questionText'][:120]}...")
            print(f"Options    : {sample['options']}")
            print(f"CorrectOpt : {sample['correctOption']} -> {sample['options'][sample['correctOption']]}")
        return stats

    if db and batch_to_import:
        print(f"\n📥 Uploading {len(batch_to_import)} verified web questions to Firestore...")
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
        print(f"✅ Successfully imported {len(batch_to_import)} web questions to Firestore.")

        try:
            db.collection('metadata').document('question_bank').set({
                'version': firestore.Increment(1),
                'lastUpdated': firestore.SERVER_TIMESTAMP
            }, merge=True)
            print("🔄 Firestore question_bank metadata version updated.")
        except Exception as e:
            print(f"⚠️ Failed to update metadata version: {e}")

    return stats


def main():
    parser = argparse.ArgumentParser(description="Web Question Ingestion & Noise Sanitization Engine")
    parser.add_argument("--count", type=int, default=3, help="Number of questions to ingest per subject (default: 3)")
    parser.add_argument("--exam", choices=["JEE", "NEET"], default=None, help="Restrict to single exam")
    parser.add_argument("--dry-run", action="store_true", help="Run ingestion without writing to Firestore")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to service account key")

    args = parser.parse_args()

    db = None
    if not args.dry_run:
        db = init_firebase(args.creds)

    run_web_ingestion(count_per_subject=args.count, target_exam=args.exam, dry_run=args.dry_run, db=db)


if __name__ == "__main__":
    main()
