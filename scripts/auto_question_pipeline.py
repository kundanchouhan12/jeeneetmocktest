"""
auto_question_pipeline.py — High-Quality AI Question Generator & Quality Validator for Firestore.

Generates official syllabus questions for JEE and NEET using Groq LLM API,
applies strict noise/quality filters, converts/skips image-dependent questions,
prevents duplicates, and imports valid questions to Firestore.

Usage:
    python scripts/auto_question_pipeline.py --count 10 --exam JEE --dry-run
    python scripts/auto_question_pipeline.py --count 20
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

# Groq free tier is limited on requests/tokens per minute. Space consecutive
# calls out so a single script run doesn't burn the whole per-minute quota.
INTER_REQUEST_DELAY = 20

# Verification calls are much smaller (short answer, no full question/options/
# explanation to write) than generation calls, so they can be spaced closer
# together without risking the same per-minute token budget.
VERIFY_REQUEST_DELAY = 5

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


# Matches individual bare LaTeX tokens inside mixed prose:
# - Backslash-command fragments: \frac{3}{2}, \theta, \Delta, \sqrt{3}
# - Chemical/physics subscript/superscript tokens: CH_3COO^-, H_2O, SO_4^{2-}, Ca(OH)_2, [Cr(H_2O)_6]^{3+}, 10^{-5}
_BARE_LATEX_TOKEN = re.compile(
    r'(?<!\$)'
    r'(?:'
    r'\\[a-zA-Z]+(?:\{[^}]*\})*(?:\^(?:\{[^}]+\}|[^\s{}$\\,;)]+)|_(?:\{[^}]+\}|[^\s{}$\\,;)]+))?'
    r'|[A-Za-z0-9\(\)\[\]]+(?:_(?:\{[^}]+\}|[a-zA-Z0-9+\-]+)|\^(?:\{[^}]+\}|[a-zA-Z0-9+\-]+))+'
    r'(?:[A-Za-z0-9\(\)\[\]]*(?:_(?:\{[^}]+\}|[a-zA-Z0-9+\-]+)|\^(?:\{[^}]+\}|[a-zA-Z0-9+\-]+))*)*'
    r')'
    r'(?!\$)'
)


def wrap_inline_latex(text: str) -> str:
    """
    Selectively wraps bare LaTeX tokens within mixed-prose questionText/explanation.
    Scans text and wraps individual LaTeX fragments (chemical formulas, backslash
    commands, scientific notation exponents) with $...$ so MathRenderer.kt renders them correctly.

    Unlike wrap_bare_latex() (which wraps the WHOLE string), this is safe to call
    on long sentences because it only wraps the specific tokens, not the surrounding text.
    """
    if not text:
        return text
    if _HAS_MATH_DELIMITER.search(text):
        return text

    def replacer(m: re.Match) -> str:
        return f"${m.group(0)}$"

    return _BARE_LATEX_TOKEN.sub(replacer, text)


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


from cleanup_corrupted_questions import is_corrupted


def generate_id(q: dict) -> str:
    content = f"{q.get('examType','')}_{q.get('subject','')}_{q.get('questionText','').strip()}"
    return "q_" + hashlib.md5(content.encode('utf-8')).hexdigest()


def validate_and_clean_question(q: dict) -> tuple[bool, str]:
    """
    Strict quality & noise filter module.
    Returns (is_valid, rejection_reason).
    """
    corrupted, reason = is_corrupted(q)
    if corrupted:
        return False, f"Corrupted question check failed: {reason}"

    text = q.get('questionText', '').strip()
    options = q.get('options', [])
    correct_opt = q.get('correctOptionIndex') if q.get('correctOptionIndex') is not None else q.get('correctOption')
    explanation = q.get('explanation', '').strip()
    exam = q.get('examType', '').strip()
    subject = q.get('subject', '').strip()

    # 1. Basic text checks
    if not text or len(text) < 25:
        return False, f"Question text too short ({len(text)} chars)"

    if len(text) > 2500:
        return False, f"Question text unusually long ({len(text)} chars)"

    # 2. Image / Diagram Dependency Check (Reject if question relies on unseen image)
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

    # 3. Noise / Garbage text check
    bad_patterns = [
        r'refer to standard textbook',
        r'master practice workbook',
        r'click here to download',
        r'option a', r'option b', r'option c', r'option d',
        r'\[image\]', r'\[figure\]',
        r'^\s*:\s*[A-D]\s*$'
    ]
    for pattern in bad_patterns:
        if re.search(pattern, text, re.IGNORECASE):
            return False, f"Matched noise pattern: {pattern}"

    # 4. Options validation
    if not isinstance(options, list) or len(options) != 4:
        return False, f"Options count must be exactly 4 (got {len(options) if isinstance(options, list) else 'invalid'})"

    cleaned_options = [str(o).strip() for o in options]
    if any(len(o) == 0 for o in cleaned_options):
        return False, "Contains empty option string"

    if len(set(o.lower() for o in cleaned_options)) < 4:
        return False, "Duplicate option choices detected"

    placeholder_opts = {"option a", "option b", "option c", "option d", "a", "b", "c", "d"}
    if all(o.lower() in placeholder_opts for o in cleaned_options):
        return False, "Placeholder options found"

    # 5. Correct option index check
    if not isinstance(correct_opt, int) or correct_opt not in (0, 1, 2, 3):
        return False, f"Invalid correctOption index: {correct_opt}"

    # 6. Explanation check
    if not explanation or len(explanation) < 15:
        return False, "Explanation missing or too brief (< 15 chars)"

    # 7. Metadata validation
    if exam not in ("JEE", "NEET"):
        return False, f"Invalid examType: {exam}"

    if subject not in ("Physics", "Chemistry", "Maths", "Biology"):
        return False, f"Invalid subject: {subject}"

    return True, ""


MAX_RETRY_AFTER_SECS = 45  # Cap on Retry-After sleep to prevent workflow stalling


def _post_groq(body: dict, max_retries: int = 3) -> str:
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
                        wait_time = (attempt + 1) * 15
                else:
                    wait_time = (attempt + 1) * 15

                if wait_time > MAX_RETRY_AFTER_SECS:
                    print(f"    ⚠️ Rate limit (429) requested excessive wait ({wait_time:.0f}s > {MAX_RETRY_AFTER_SECS}s). Skipping further retries for this request.")
                    return ""

                print(f"    ⏳ Rate limit (429) hit. Waiting {wait_time:.0f}s before retry (attempt {attempt + 1}/{max_retries})...")
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


def call_groq_api(prompt: str) -> str:
    body = {
        "model": GROQ_MODEL,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are an expert exam question creator for Indian competitive exams (JEE Main & NEET). "
                    "You output strictly valid JSON without markdown codeblock formatting or extra text. "
                    "All math equations MUST be written in clean KaTeX LaTeX syntax (e.g. \\( x^2 + y^2 = r^2 \\) or \\(\\int_0^1 x dx\\)). "
                    "NEVER use \\ce{} mhchem notation — instead write chemical formulas as plain text or simple KaTeX (e.g. H_2O, SO_4^{2-}, MnO_4^-). "
                    "NEVER embed literal \\n or \\t escape sequences inside string values; use actual whitespace or spaces. "
                    "Do NOT refer to external images, figures, or diagrams. Questions must be 100% self-contained in text and math."
                )
            },
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 4000,
        # openai/gpt-oss-20b is a reasoning model that spends completion
        # tokens on a hidden chain-of-thought before writing the actual
        # answer. Without this, a sufficiently detailed prompt could burn
        # the entire max_tokens budget on reasoning and return empty
        # content — which this script would then misreport as "rate limit
        # or API error" even on a clean 200 response.
        "reasoning_effort": "low"
    }
    return _post_groq(body)


def verify_answer(exam: str, subject: str, chapter: str, question_text: str, options: list) -> int:
    """
    Independently re-derives the answer to an already-generated question and
    returns the option index (0-3) it believes is correct, or -1 if it can't
    confidently match any option, or -2 on an API/parse failure (caller
    should treat -2 as "couldn't verify" rather than "verification failed").

    Deliberately does NOT set reasoning_effort here (unlike call_groq_api) —
    generation optimizes for not burning its token budget on hidden
    reasoning, but verification's entire purpose is accuracy, and its
    response is short (no full question/options/explanation to write), so
    full reasoning stays cheap.
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
            # Same failure mode as generation: unrestricted reasoning can
            # burn the whole budget on a hard problem before writing the
            # answer. Retry once with reasoning capped so it's forced to
            # actually answer instead of reasoning until it runs out.
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


def generate_questions(exam: str, subject: str, chapter: str, count: int = 5) -> list[dict]:
    prompt = f"""
Generate exactly {count} high-caliber multiple-choice questions for {exam} exam in the subject '{subject}', chapter '{chapter}'.

Return a raw JSON array of objects. Each object MUST have these exact fields:
- "questionText": string (clear problem statement with LaTeX math if needed)
- "options": array of 4 strings (e.g. ["A", "B", "C", "D"])
- "correctOption": integer (0, 1, 2, or 3 corresponding to index in options)
- "explanation": string (detailed step-by-step solution)
- "difficulty": string ("Easy", "Medium", or "Hard")

Constraints:
1. NO image references or diagram dependencies. Describe all numerical/physical parameters explicitly in text.
2. Make options distinct and realistic.
3. Ensure LaTeX equations use double backslashes for JSON escaping (e.g., \\\\frac{{a}}{{b}}).
4. NEVER use \\ce{{}} mhchem notation. Write chemical formulas as plain text or simple KaTeX (e.g. H_2O, CuSO_4, MnO_4^-).
5. NEVER embed \\n or \\t escape sequences inside string values. Use actual spaces.
"""
    raw_response = call_groq_api(prompt)
    if not raw_response:
        print("  ⚠️ Skipped generation due to rate limit or API error.")
        return []

    # Clean markdown formatting and extract JSON array
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
        item["questionText"] = wrap_inline_latex(str(item.get("questionText", "")))
        item["explanation"] = wrap_inline_latex(str(item.get("explanation", "")))
        item["options"] = [wrap_bare_latex(str(o)) for o in item.get("options", [])]
        corr = item.get("correctOptionIndex") if item.get("correctOptionIndex") is not None else item.get("correctOption")
        item["correctOption"] = corr
        item["correctOptionIndex"] = corr
        item["examType"] = exam
        item["subject"] = subject
        item["chapter"] = chapter
        item["packId"] = PACK_IDS.get((exam, subject), "allaccessyearly")
        item["isDailyVault"] = False
        processed.append(item)

    return processed


def run_pipeline(count_per_subject: int = 5, target_exam: str = None, dry_run: bool = False, db=None, all_docs=None) -> dict:
    print(f"\n🚀 Running Automated Question Pipeline (Target per subject: {count_per_subject}, Dry Run: {dry_run})...")

    exams = [target_exam] if target_exam else ["JEE", "NEET"]
    stats = {"generated": 0, "passed_filter": 0, "rejected": 0, "imported": 0, "rejections": {}}

    existing_ids = set()
    if db and not dry_run:
        try:
            docs = all_docs if all_docs is not None else db.collection('questions').select([]).get()
            existing_ids = {d.id for d in docs}
            print(f"ℹ️ Found {len(existing_ids)} existing questions in Firestore for deduplication.")
        except Exception as e:
            print(f"⚠️ Could not fetch existing IDs for deduplication: {e}")

    batch_to_import = []

    for exam in exams:
        subjects = ["Physics", "Chemistry", "Maths"] if exam == "JEE" else ["Physics", "Chemistry", "Biology"]
        for subj in subjects:
            chapters = OFFICIAL_CHAPTERS.get(subj, [])
            if not chapters:
                continue

            selected_chapter = random.choice(chapters)
            print(f"  📚 Generating {count_per_subject} questions for [{exam} - {subj}] -> Chapter: '{selected_chapter}'...")

            questions = generate_questions(exam, subj, selected_chapter, count=count_per_subject)
            stats["generated"] += len(questions)
            time.sleep(INTER_REQUEST_DELAY)

            for q in questions:
                q_id = generate_id(q)
                if q_id in existing_ids:
                    stats["rejected"] += 1
                    stats["rejections"]["Duplicate Question"] = stats["rejections"].get("Duplicate Question", 0) + 1
                    continue

                is_valid, reason = validate_and_clean_question(q)
                if not is_valid:
                    stats["rejected"] += 1
                    stats["rejections"][reason] = stats["rejections"].get(reason, 0) + 1
                    print(f"    ⚠️ Rejected question: {reason}")
                    continue

                verified_idx = verify_answer(exam, subj, selected_chapter, q.get("questionText", ""), q.get("options", []))
                time.sleep(VERIFY_REQUEST_DELAY)
                claimed_idx = q.get("correctOption")
                if verified_idx == -2:
                    print("    ⚠️ Could not verify answer (API/parse failure) — keeping question, unverified.")
                elif verified_idx != claimed_idx:
                    stats["rejected"] += 1
                    reason = f"Answer verification mismatch (claimed {claimed_idx}, verifier got {verified_idx})"
                    stats["rejections"]["Answer Verification Mismatch"] = stats["rejections"].get("Answer Verification Mismatch", 0) + 1
                    print(f"    ⚠️ Rejected question: {reason}")
                    continue

                q["id"] = q_id
                stats["passed_filter"] += 1
                batch_to_import.append(q)

    print(f"\n📊 Quality Pipeline Summary:")
    print(f"   • Total Generated    : {stats['generated']}")
    print(f"   • Passed Filter      : {stats['passed_filter']}")
    print(f"   • Rejected           : {stats['rejected']}")
    if stats["rejections"]:
        print(f"   • Rejection Reasons  : {stats['rejections']}")

    if dry_run:
        print(f"\n🔎 Dry run complete. Sample passed question:\n")
        if batch_to_import:
            sample = batch_to_import[0]
            print(f"ID         : {sample['id']}")
            print(f"Exam/Subj  : {sample['examType']} / {sample['subject']} ({sample['chapter']})")
            print(f"Question   : {sample['questionText'][:120]}...")
            print(f"Options    : {sample['options']}")
            print(f"CorrectOpt : {sample['correctOption']} -> {sample['options'][sample['correctOption']]}")
        return stats

    if db and batch_to_import:
        print(f"\n📥 Uploading {len(batch_to_import)} verified questions to Firestore...")
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
        print(f"✅ Successfully imported {len(batch_to_import)} questions to Firestore.")

        # Update metadata timestamp
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
    parser = argparse.ArgumentParser(description="Automated High-Quality Question Pipeline for Firestore")
    parser.add_argument("--count", type=int, default=5, help="Number of questions to generate per subject (default: 5)")
    parser.add_argument("--exam", choices=["JEE", "NEET"], default=None, help="Restrict to single exam")
    parser.add_argument("--dry-run", action="store_true", help="Run validation without writing to Firestore")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to service account key")

    args = parser.parse_args()

    db = None
    if not args.dry_run:
        db = init_firebase(args.creds)

    run_pipeline(count_per_subject=args.count, target_exam=args.exam, dry_run=args.dry_run, db=db)


if __name__ == "__main__":
    main()
