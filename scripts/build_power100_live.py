"""
build_power100_live.py — Rebuilds the Power 100 fixed set for an exam directly
from the live Firestore `questions` bank using the Curriculum-Driven Architecture.

Key Principles:
1. Single Common Pipeline & Quality Gate:
   Consumes the same validated question bank as Daily Vault. Every question
   must satisfy the 7-Stage Curriculum Quality Gate (curriculum_validator.py).
2. Strict Exam Isolation:
   - JEE Power 100: Physics, Chemistry, Maths ONLY (zero Biology).
   - NEET Power 100: Physics, Chemistry, Biology ONLY (zero Maths).
3. Configurable Subject Targets:
   Driven by curriculum.json `power100_config`:
   - JEE: Physics 31, Chemistry 36, Maths 33 (= 100)
   - NEET: Physics 25, Chemistry 25, Biology 50 (= 100)
4. Syllabus Unit & Topic Coverage:
   Distributes questions broadly across official NCERT units (capping each unit
   at max_per_unit).
5. 4 Question Modes Supported:
   Supports TEXT, NUMERICAL, DIAGRAM, STRUCTURE with full image metadata
   (imageUrl, solutionImageUrl, optionImageUrls).
6. Non-Destructive Invariant:
   Rebuilds standard_tests/{exam} on schedule (or when forced), bumping version
   so Android clients auto-sync cleanly without altering individual question docs.
"""

import argparse
import os
import random
import sys
from typing import Any, Optional

import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import curriculum
import curriculum_validator
from cleanup_corrupted_questions import is_corrupted
from vault_scheduler import question_fingerprint

SERVICE_ACCOUNT_PATH = os.path.join(SCRIPT_DIR, 'serviceAccountKey.json')

# Power 100 difficulty weight: broad foundational set (favors Easy/Medium)
DIFFICULTY_WEIGHT = {"Easy": 3, "Medium": 3, "Hard": 1}


def init_firebase(creds_path: str):
    if not firebase_admin._apps:
        if not os.path.exists(creds_path):
            print(f"ERROR: Credentials file not found: {creds_path}")
            sys.exit(1)
        firebase_admin.initialize_app(credentials.Certificate(creds_path))
    return firestore.client()


def get_subject_targets(exam: str) -> dict[str, int]:
    """Retrieves configurable subject targets from curriculum.json."""
    norm_exam = "JEE" if exam.upper() in ("JEE", "JEE_MAIN") else "NEET"
    cfg = curriculum.get_power100_config(norm_exam)
    if cfg and "subject_distribution" in cfg:
        return cfg["subject_distribution"]
    if norm_exam == "JEE":
        return {"Physics": 31, "Chemistry": 36, "Maths": 33}
    return {"Physics": 25, "Chemistry": 25, "Biology": 50}


def _select_for_subject(pool: list[dict[str, Any]], target: int, max_per_unit: int = 4) -> list[dict[str, Any]]:
    """
    Selects balanced questions for a subject:
    - Actively prioritizes authentic STEM visual/diagram/structure questions
    - Balances difficulty across foundational levels
    - Caps each official unit at max_per_unit to ensure syllabus breadth
    - Enforces zero duplicate fingerprints within the selection
    """
    random.shuffle(pool)

    def is_stem_visual(q: dict[str, Any]) -> bool:
        return bool(
            q.get("imageUrl") or
            q.get("diagramRequired") or
            q.get("questionMode") in ("DIAGRAM", "STRUCTURE")
        )

    # Sort so visual/STEM diagram questions are chosen first across units
    pool.sort(
        key=lambda q: (
            1 if is_stem_visual(q) else 0,
            DIFFICULTY_WEIGHT.get(q.get("difficulty", "Medium"), 2)
        ),
        reverse=True
    )

    unit_count: dict[str, int] = {}
    selected: list[dict[str, Any]] = []
    seen_fps: set[str] = set()

    for q in pool:
        if len(selected) >= target:
            break
        fp = question_fingerprint(q)
        if fp in seen_fps:
            continue
        unit = q.get("officialUnit") or q.get("chapter", "")
        if unit_count.get(unit, 0) >= max_per_unit:
            continue
        unit_count[unit] = unit_count.get(unit, 0) + 1
        selected.append(q)
        seen_fps.add(fp)

    # Relax the per-unit cap if the pool couldn't fill the target otherwise.
    if len(selected) < target:
        for q in pool:
            if len(selected) >= target:
                break
            fp = question_fingerprint(q)
            if fp not in seen_fps:
                selected.append(q)
                seen_fps.add(fp)

    # Randomize final selection order so diagram questions are naturally distributed
    random.shuffle(selected)
    return selected[:target]


def build_power100(db, exam: str, all_docs=None) -> list[dict[str, Any]]:
    """
    Selects exactly 100 curriculum-validated questions for `exam` from the live bank.
    Enforces official unit mapping, question mode integrity, and exact subject quotas.
    """
    norm_exam = "JEE" if exam.upper() in ("JEE", "JEE_MAIN") else "NEET"
    cfg = curriculum.get_power100_config(norm_exam)
    subject_targets = get_subject_targets(norm_exam)
    max_per_unit = cfg.get("max_per_unit", 4) if cfg else 4

    docs = (
        all_docs if all_docs is not None
        else db.collection("questions").where("examType", "==", norm_exam).get()
    )

    by_subject: dict[str, list[dict[str, Any]]] = {}
    seen_bank_fps: set[str] = set()

    for doc in docs:
        q = doc.to_dict() or {}
        # 1. Strictly enforce exam isolation: no cross-exam contamination
        doc_exam = str(q.get("examType", "")).strip().upper()
        if doc_exam != norm_exam:
            continue
        if q.get("isDailyVault") or doc.id.startswith("vault_"):
            continue

        # 2. Corrupted question filter
        corrupted, _ = is_corrupted(q)
        if corrupted:
            continue

        subject = str(q.get("subject", "")).strip().capitalize()
        if subject not in subject_targets:
            continue

        # 3. Common 7-Stage Curriculum Quality Gate
        val_res = curriculum_validator.validate_question(q, is_new_content=False)
        if not val_res.is_valid:
            continue
        # Only include questions that confidently map to official 2026 syllabus units
        if val_res.metadata.get("curriculumStatus") == "UNMAPPED":
            continue

        # 4. Options and correct index sanity
        options = q.get("options")
        if not isinstance(options, list) or len(options) != 4:
            continue
        idx = q.get("correctOptionIndex") if q.get("correctOptionIndex") is not None else q.get("correctOption")
        if not isinstance(idx, int) or isinstance(idx, bool) or not (0 <= idx <= 3):
            continue
        if not q.get("questionText"):
            continue

        # 5. Deduplication across bank
        fp = question_fingerprint(q)
        if fp in seen_bank_fps:
            continue
        seen_bank_fps.add(fp)

        # 6. Attach validated curriculum metadata
        q["officialUnit"] = val_res.metadata.get("officialUnit", q.get("chapter", ""))
        q["topic"] = val_res.metadata.get("topic", q["officialUnit"])
        q["questionMode"] = val_res.metadata.get("questionMode", "TEXT")
        q["sourceType"] = val_res.metadata.get("sourceType", "ORIGINAL_PRACTICE")
        q["diagramSource"] = val_res.metadata.get("diagramSource", "NONE")
        q["validationStatus"] = "PASSED"
        q["correctOptionIndex"] = idx
        q["correctOption"] = idx

        by_subject.setdefault(subject, []).append(q)

    selected: list[dict[str, Any]] = []
    for subject, target in subject_targets.items():
        pool = by_subject.get(subject, [])
        picked = _select_for_subject(pool, target, max_per_unit=max_per_unit)
        if len(picked) < target:
            print(f"  WARNING: {norm_exam}/{subject} only has {len(picked)}/{target} eligible questions.")
        selected.extend(picked)

    result: list[dict[str, Any]] = []
    for q in selected:
        item: dict[str, Any] = {
            "subject": q.get("subject"),
            "chapter": q.get("officialUnit") or q.get("chapter", ""),
            "officialUnit": q.get("officialUnit", ""),
            "topic": q.get("topic", ""),
            "difficulty": q.get("difficulty", "Medium"),
            "questionText": q.get("questionText"),
            "options": q.get("options"),
            "correctOptionIndex": q.get("correctOptionIndex"),
            "correctOption": q.get("correctOptionIndex"),
            "explanation": q.get("explanation", ""),
            "questionMode": q.get("questionMode", "TEXT"),
            "sourceType": q.get("sourceType", "ORIGINAL_PRACTICE"),
            "diagramSource": q.get("diagramSource", "NONE"),
            "validationStatus": "PASSED"
        }
        if q.get("imageUrl"):
            item["imageUrl"] = q["imageUrl"]
        if q.get("solutionImageUrl"):
            item["solutionImageUrl"] = q["solutionImageUrl"]
        if q.get("optionImageUrls"):
            item["optionImageUrls"] = q["optionImageUrls"]
        result.append(item)
    return result


def run_power100_rebuild(exam: str, dry_run: bool = False, db=None, all_docs=None) -> bool:
    norm_exam = "JEE" if exam.upper() in ("JEE", "JEE_MAIN") else "NEET"
    print(f"\n🏆 Rebuilding Power 100 for {norm_exam} from curriculum-validated question bank...")
    questions = build_power100(db, norm_exam, all_docs=all_docs)

    if len(questions) != 100:
        print(f"  ERROR: Built {len(questions)}/100 questions for {norm_exam} — bank too thin, skipping push.")
        return False

    # Always keep local JSON cache in sync with the clean rebuild
    local_file = os.path.join(SCRIPT_DIR, f"{norm_exam.lower()}_power100.json")
    try:
        with open(local_file, "w", encoding="utf-8") as f:
            json.dump(questions, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"  ⚠️ Could not save local backup to {local_file}: {e}")

    # Validate against update_power100 requirements
    from update_power100 import validate, upload
    try:
        validate(questions, norm_exam)
    except ValueError as e:
        print(f"  ERROR: {norm_exam} rebuild failed validation, skipping push: {e}")
        return False

    upload(questions, norm_exam, dry_run=dry_run)
    return True


def main():
    parser = argparse.ArgumentParser(description="Rebuild Power 100 using Curriculum-Driven Architecture")
    parser.add_argument("--exam", choices=["JEE", "NEET", "JEE_MAIN", "NEET_UG"], required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH)
    args = parser.parse_args()

    norm_exam = "JEE" if args.exam.upper() in ("JEE", "JEE_MAIN") else "NEET"
    db = None
    if not args.dry_run or os.path.exists(args.creds):
        db = init_firebase(args.creds)

    run_power100_rebuild(norm_exam, dry_run=args.dry_run, db=db)


if __name__ == "__main__":
    main()
