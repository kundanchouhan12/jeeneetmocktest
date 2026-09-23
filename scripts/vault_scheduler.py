"""
vault_scheduler.py — Admin script to schedule Daily Vault questions in Firestore.

Usage:
    pip install firebase-admin

    # Schedule for tomorrow (default — both JEE and NEET)
    python vault_scheduler.py --creds scripts/serviceAccountKey.json

    # Schedule for today (testing)
    python vault_scheduler.py --today --creds scripts/serviceAccountKey.json

    # Specific date
    python vault_scheduler.py --date 2026-06-10 --creds scripts/serviceAccountKey.json

    # Single exam only
    python vault_scheduler.py --exam NEET --creds scripts/serviceAccountKey.json

    # Custom question count
    python vault_scheduler.py --count 20 --creds scripts/serviceAccountKey.json

The app detects new vault questions on next launch and syncs automatically.
Vault documents are copies of regular questions with isDailyVault=True,
vaultDate, and vaultGroupId set. The originals are never modified.
"""

import argparse
import datetime
import os
import random
import sys

import firebase_admin
from firebase_admin import credentials, firestore

VALID_EXAMS = ["JEE", "NEET"]


def init_firebase(creds_path: str) -> None:
    if firebase_admin._apps:
        return
    if not os.path.exists(creds_path):
        print(f"ERROR: Credentials file not found: {creds_path}")
        sys.exit(1)
    firebase_admin.initialize_app(credentials.Certificate(creds_path))


def select_vault_questions(pool: list, recently_used_ids: set, count: int) -> list:
    """
    Picks up to `count` items from `pool` (anything with a `.id` attribute),
    preferring ones not in `recently_used_ids`. Only dips into previously-used
    ones to top up once the fresh pool can't fill `count` on its own.

    Pure and I/O-free (no Firestore calls) so it can be unit-tested directly —
    see test_vault_scheduler.py.
    """
    fresh_pool = [d for d in pool if d.id not in recently_used_ids]
    stale_pool = [d for d in pool if d.id in recently_used_ids]

    count = min(count, len(pool))
    fresh_n = min(count, len(fresh_pool))
    selected = random.sample(fresh_pool, fresh_n)
    if fresh_n < count:
        selected += random.sample(stale_pool, count - fresh_n)
    return selected


def schedule_vault(db, target_date: str, exam_type: str, count: int) -> None:
    print(f"\n[vault] Scheduling {exam_type} vault for {target_date} ({count} questions)...")
    questions_ref = db.collection("questions")

    # 1. Clean up any existing vault documents for this date + exam
    all_vault_docs = questions_ref.where("vaultDate", "==", target_date).get()
    existing = [d for d in all_vault_docs if d.to_dict().get("isDailyVault") == True and d.to_dict().get("examType") == exam_type]
    if existing:
        print(f"  Removing {len(existing)} stale vault docs for {target_date}/{exam_type}...")
        batch = db.batch()
        for doc in existing:
            batch.delete(doc.reference)
        batch.commit()

    # 2. Fetch candidate pool — fetch all for this exam, then filter in Python.
    #    We do NOT filter by isDailyVault==False in Firestore because new question
    #    documents that lack the field entirely would be silently excluded.
    #    No .limit() here — the pool must cover every question for the exam, not
    #    just whatever Firestore's default ordering happens to put first.
    all_docs = (
        questions_ref
        .where("examType", "==", exam_type)
        .get()
    )

    # Exclude vault copies (their IDs start with "vault_")
    pool = [d for d in all_docs if not d.id.startswith("vault_")]

    if len(pool) == 0:
        print(f"  ERROR: No questions found in Firestore for exam '{exam_type}'. Skipping.")
        return

    # 2b. Exclude questions used in previous vault cycles so the same set doesn't
    #     get resampled every time this script runs. Each vault doc's id is
    #     "vault_<date>_<originalDocId>"; recover the original id from it rather
    #     than trusting in-document fields (which may not be stable across imports).
    #     (Step 1 already deleted target_date's own vault docs, so every doc seen
    #     here is genuine prior-cycle history.)
    previous_vault_docs = (
        questions_ref
        .where("isDailyVault", "==", True)
        .where("examType", "==", exam_type)
        .get()
    )
    recently_used_ids = {
        d.id.split("_", 2)[2] for d in previous_vault_docs if d.id.startswith("vault_")
    }

    fresh_pool_size = len([d for d in pool if d.id not in recently_used_ids])
    if fresh_pool_size < count:
        if fresh_pool_size == 0:
            print(f"  NOTE: Every question has been vaulted before; re-circulating full pool for '{exam_type}'.")
        else:
            print(f"  NOTE: Only {fresh_pool_size} never-vaulted questions left; topping up with previously-vaulted ones.")

    if len(pool) < count:
        print(f"  WARNING: Pool has only {len(pool)} questions (wanted {count}). Using all.")
        count = len(pool)

    selected = select_vault_questions(pool, recently_used_ids, count)
    group_id = f"{exam_type.lower()}_vault_{target_date}"

    # 3. Write new vault documents (originals are never modified)
    batch = db.batch()
    for doc in selected:
        q = doc.to_dict()
        q["isDailyVault"] = True
        q["vaultDate"] = target_date
        q["vaultGroupId"] = group_id
        corr = q.get("correctOptionIndex") if q.get("correctOptionIndex") is not None else q.get("correctOption")
        if corr is not None:
            q["correctOptionIndex"] = corr
            q["correctOption"] = corr
        new_ref = questions_ref.document(f"vault_{target_date}_{doc.id}")
        batch.set(new_ref, q)
    batch.commit()

    print(f"  OK: Wrote {count} vault questions for {exam_type} (groupId: {group_id})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Schedule Daily Vault questions in Firestore")
    parser.add_argument(
        "--creds", default=None,
        help="Path to Firebase service account JSON (or set GOOGLE_APPLICATION_CREDENTIALS)"
    )
    parser.add_argument(
        "--exam", choices=VALID_EXAMS, default=None,
        help="Restrict to a single exam. Omit to schedule for both JEE and NEET (default)."
    )
    parser.add_argument(
        "--today", action="store_true",
        help="Schedule for today instead of tomorrow (useful for testing)"
    )
    parser.add_argument(
        "--date", type=str, default=None,
        help="Target date in yyyy-MM-dd format (overrides --today)"
    )
    parser.add_argument(
        "--count", type=int, default=30,
        help="Number of questions per vault (default: 30)"
    )
    args = parser.parse_args()

    # Resolve credentials
    if args.creds:
        creds_path = args.creds
    else:
        creds_path = os.environ.get(
            "GOOGLE_APPLICATION_CREDENTIALS",
            os.path.join(os.path.dirname(__file__), "serviceAccountKey.json")
        )

    init_firebase(creds_path)
    db = firestore.client()

    # Resolve target date
    if args.date:
        target = args.date
    elif args.today:
        target = datetime.date.today().strftime("%Y-%m-%d")
    else:
        target = (datetime.date.today() + datetime.timedelta(days=1)).strftime("%Y-%m-%d")

    print(f"[vault] Target date : {target}")

    exams = [args.exam] if args.exam else VALID_EXAMS
    print(f"[vault] Exams       : {', '.join(exams)}")
    print(f"[vault] Count each  : {args.count}")

    for exam in exams:
        schedule_vault(db, target, exam, args.count)

    print("\n[vault] Done. App will sync on next launch.")


if __name__ == "__main__":
    main()
