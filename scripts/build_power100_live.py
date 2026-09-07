"""
build_power100_live.py — Rebuilds the Power 100 fixed set for an exam directly
from the live Firestore `questions` bank, and pushes a new version.

Unlike the old build_power100.js (which parsed a one-off local PYQ dump under
scripts/extracted/), this reads whatever the current question bank looks like
right now — including content added by web_question_ingestion.py and
auto_question_pipeline.py — so Power 100 keeps pace with the growing bank
without a manual curation pass.

Called nightly by run_daily_automation.py, after the dedup/corruption audit
steps. Each run fully replaces the Firestore standard_tests/{exam} document
and bumps its version, so clients detect the change and reset any saved
progress (Power100SyncManager.kt) rather than pointing stale progress at
swapped questions.

Usage:
    python scripts/build_power100_live.py --exam JEE
    python scripts/build_power100_live.py --exam NEET --dry-run
"""

import argparse
import os
import random
import sys

import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

from cleanup_corrupted_questions import is_corrupted

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

# Mirrors the real exam's subject weighting, scaled to 100 questions.
SUBJECT_TARGETS = {
    "JEE":  {"Physics": 31, "Chemistry": 36, "Maths": 33},
    "NEET": {"Physics": 25, "Chemistry": 25, "Biology": 50},
}

# No more than this many questions from one chapter, so the fixed set stays broad.
MAX_PER_CHAPTER = 4

# Power 100 is framed in-app as a broad foundational set ("Master the 100 most
# important questions"), not an elite/hardest-only challenge — bias selection
# towards Easy/Medium without fully excluding Hard.
DIFFICULTY_WEIGHT = {"Easy": 3, "Medium": 3, "Hard": 1}


def init_firebase(creds_path: str):
    if not firebase_admin._apps:
        if not os.path.exists(creds_path):
            print(f"ERROR: Credentials file not found: {creds_path}")
            sys.exit(1)
        firebase_admin.initialize_app(credentials.Certificate(creds_path))
    return firestore.client()


def _select_for_subject(pool: list, target: int) -> list:
    random.shuffle(pool)
    pool.sort(key=lambda q: DIFFICULTY_WEIGHT.get(q.get("difficulty", "Medium"), 2), reverse=True)

    chapter_count = {}
    selected = []
    for q in pool:
        if len(selected) >= target:
            break
        ch = q.get("chapter", "")
        if chapter_count.get(ch, 0) >= MAX_PER_CHAPTER:
            continue
        chapter_count[ch] = chapter_count.get(ch, 0) + 1
        selected.append(q)

    # Relax the per-chapter cap if the pool couldn't fill the target otherwise.
    if len(selected) < target:
        for q in pool:
            if len(selected) >= target:
                break
            if q not in selected:
                selected.append(q)

    return selected[:target]


def build_power100(db, exam: str, all_docs=None) -> list:
    """Selects up to 100 clean, balanced questions for `exam` from the live bank."""
    docs = (
        all_docs if all_docs is not None
        else db.collection("questions").where("examType", "==", exam).get()
    )

    by_subject: dict = {}
    for doc in docs:
        q = doc.to_dict()
        if q.get("examType") != exam:
            continue
        if q.get("isDailyVault") or doc.id.startswith("vault_"):
            continue
        corrupted, _ = is_corrupted(q)
        if corrupted:
            continue
        subject = q.get("subject")
        if subject not in SUBJECT_TARGETS[exam]:
            continue
        options = q.get("options")
        if not isinstance(options, list) or len(options) != 4:
            continue
        idx = q.get("correctOptionIndex")
        if not isinstance(idx, int) or isinstance(idx, bool) or not (0 <= idx <= 3):
            continue
        if not q.get("questionText"):
            continue
        by_subject.setdefault(subject, []).append(q)

    selected = []
    for subject, target in SUBJECT_TARGETS[exam].items():
        pool = by_subject.get(subject, [])
        picked = _select_for_subject(pool, target)
        if len(picked) < target:
            print(f"  WARNING: {exam}/{subject} only has {len(picked)}/{target} eligible questions.")
        selected.extend(picked)

    return [
        {
            "subject": q.get("subject"),
            "chapter": q.get("chapter", ""),
            "difficulty": q.get("difficulty", "Medium"),
            "questionText": q.get("questionText"),
            "options": q.get("options"),
            "correctOptionIndex": q.get("correctOptionIndex"),
            "explanation": q.get("explanation", ""),
        }
        for q in selected
    ]


def run_power100_rebuild(exam: str, dry_run: bool = False, db=None, all_docs=None) -> bool:
    print(f"\n🏆 Rebuilding Power 100 for {exam} from live question bank...")
    questions = build_power100(db, exam, all_docs=all_docs)

    if len(questions) != 100:
        print(f"  ERROR: Built {len(questions)}/100 questions for {exam} — bank too thin, skipping push.")
        return False

    # Reuse the existing admin script's validation + upload logic rather than
    # duplicating the schema/version-bump rules.
    from update_power100 import validate, upload
    try:
        validate(questions, exam)
    except ValueError as e:
        print(f"  ERROR: {exam} rebuild failed validation, skipping push: {e}")
        return False

    upload(questions, exam, dry_run=dry_run)
    return True


def main():
    parser = argparse.ArgumentParser(description="Rebuild Power 100 from the live Firestore question bank")
    parser.add_argument("--exam", choices=list(SUBJECT_TARGETS.keys()), required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH)
    args = parser.parse_args()

    db = init_firebase(args.creds)
    run_power100_rebuild(args.exam, dry_run=args.dry_run, db=db)


if __name__ == "__main__":
    main()
