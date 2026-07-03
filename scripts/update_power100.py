"""
update_power100.py — Admin script to push Power 100 questions to Firestore.

Usage:
    pip install firebase-admin
    python update_power100.py --exam JEE --file jee_power100.json
    python update_power100.py --exam NEET --file neet_power100.json

The script reads a JSON file with exactly 100 questions, increments the
Firestore version field, and uploads. The app detects the new version on
next launch and syncs automatically.

Required env var:
    GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json

JSON format (array of 100 objects):
[
  {
    "subject": "Physics",
    "chapter": "Kinematics",
    "difficulty": "Medium",
    "questionText": "A block of mass m...",
    "options": ["2π√(m/k)", "2π√(k/m)", "2π√(m)", "2π√(k)"],
    "correctOptionIndex": 0,
    "explanation": "Using T = 2π√(m/k) for SHM..."
  },
  ...
]
"""

import argparse
import json
import sys
import firebase_admin
from firebase_admin import credentials, firestore


REQUIRED_KEYS = {"subject", "chapter", "difficulty", "questionText", "options", "correctOptionIndex", "explanation"}
VALID_EXAMS = {"JEE", "NEET"}
VALID_DIFFICULTIES = {"Easy", "Medium", "Hard"}


def validate(questions: list, exam: str) -> None:
    subjects_jee = {"Physics", "Chemistry", "Maths"}
    subjects_neet = {"Physics", "Chemistry", "Biology"}
    valid_subjects = subjects_jee if exam == "JEE" else subjects_neet

    if len(questions) != 100:
        raise ValueError(f"Expected exactly 100 questions, got {len(questions)}")

    for i, q in enumerate(questions, 1):
        missing = REQUIRED_KEYS - q.keys()
        if missing:
            raise ValueError(f"Q{i}: missing fields {missing}")
        if q["subject"] not in valid_subjects:
            raise ValueError(f"Q{i}: invalid subject '{q['subject']}' for {exam}")
        if q["difficulty"] not in VALID_DIFFICULTIES:
            raise ValueError(f"Q{i}: invalid difficulty '{q['difficulty']}'")
        if not isinstance(q["options"], list) or len(q["options"]) != 4:
            raise ValueError(f"Q{i}: options must be a list of exactly 4 strings")
        if not isinstance(q["correctOptionIndex"], int) or not (0 <= q["correctOptionIndex"] <= 3):
            raise ValueError(f"Q{i}: correctOptionIndex must be 0–3")

    print(f"✓ Validation passed: {len(questions)} questions for {exam}")


def upload(questions: list, exam: str, dry_run: bool) -> None:
    db = firestore.client()
    doc_ref = db.collection("standard_tests").document(exam)

    existing = doc_ref.get()
    current_version = existing.get("version") if existing.exists else 0
    new_version = (current_version or 0) + 1

    payload = {
        "version": new_version,
        "updatedAt": firestore.SERVER_TIMESTAMP,
        "questions": questions,
    }

    if dry_run:
        print(f"[DRY RUN] Would write {len(questions)} questions for {exam} as version {new_version}")
        return

    doc_ref.set(payload)
    print(f"✓ Uploaded {len(questions)} questions for {exam} → version {new_version}")
    print("  App will sync on next launch.")


def main():
    parser = argparse.ArgumentParser(description="Upload Power 100 questions to Firestore")
    parser.add_argument("--exam", required=True, choices=list(VALID_EXAMS), help="JEE or NEET")
    parser.add_argument("--file", required=True, help="Path to JSON file with 100 questions")
    parser.add_argument("--dry-run", action="store_true", help="Validate and print without uploading")
    parser.add_argument("--creds", default=None, help="Path to Firebase service account JSON (or set GOOGLE_APPLICATION_CREDENTIALS)")
    args = parser.parse_args()

    # Init Firebase
    if args.creds:
        cred = credentials.Certificate(args.creds)
    elif "GOOGLE_APPLICATION_CREDENTIALS" in __import__("os").environ:
        cred = credentials.ApplicationDefault()
    else:
        print(
            "\nERROR: No Firebase credentials found.\n"
            "  Option A (recommended): pass --creds path/to/serviceAccountKey.json\n"
            "    Download from: Firebase Console → Project Settings → Service accounts\n"
            "                   → Generate new private key\n"
            "  Option B: set GOOGLE_APPLICATION_CREDENTIALS env var to the key file path\n"
        )
        raise SystemExit(1)

    if not firebase_admin._apps:
        firebase_admin.initialize_app(credential=cred)

    # Load questions
    with open(args.file, encoding="utf-8") as f:
        questions = json.load(f)

    validate(questions, args.exam)
    upload(questions, args.exam, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
