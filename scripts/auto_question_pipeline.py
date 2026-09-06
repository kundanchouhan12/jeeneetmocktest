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

GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "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")
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "openai/gpt-oss-20b"

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

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
    correct_opt = q.get('correctOption')
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
                    "You are an expert exam question creator for Indian competitive exams (JEE Main & NEET). "
                    "You output strictly valid JSON without markdown codeblock formatting or extra text. "
                    "All math equations MUST be written in clean KaTeX LaTeX syntax (e.g. \\( x^2 + y^2 = r^2 \\) or \\(\\int_0^1 x dx\\)). "
                    "Do NOT refer to external images, figures, or diagrams. Questions must be 100% self-contained in text and math."
                )
            },
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 3000
    }

    max_retries = 5
    for attempt in range(max_retries):
        try:
            resp = requests.post(GROQ_URL, json=body, headers=headers, timeout=45)
            if resp.status_code == 429:
                wait_time = (attempt + 1) * 8
                print(f"    ⏳ Rate limit (429) hit. Waiting {wait_time}s before retry...")
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
            fixed_json = re.sub(r'\\(?![/"\\bfnrtu])', r'\\\\', cleaned_json)
            items = json.loads(fixed_json)
            if not isinstance(items, list):
                items = [items]
        except Exception as e:
            print(f"  ❌ Error parsing JSON response: {e}")
            return []

    processed = []
    for item in items:
        item["examType"] = exam
        item["subject"] = subject
        item["chapter"] = chapter
        item["packId"] = PACK_IDS.get((exam, subject), "allaccessyearly")
        item["isDailyVault"] = False
        processed.append(item)

    return processed


def run_pipeline(count_per_subject: int = 5, target_exam: str = None, dry_run: bool = False, db=None) -> dict:
    print(f"\n🚀 Running Automated Question Pipeline (Target per subject: {count_per_subject}, Dry Run: {dry_run})...")

    exams = [target_exam] if target_exam else ["JEE", "NEET"]
    stats = {"generated": 0, "passed_filter": 0, "rejected": 0, "imported": 0, "rejections": {}}

    existing_ids = set()
    if db and not dry_run:
        try:
            docs = db.collection('questions').select([]).get()
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
            time.sleep(2)

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
