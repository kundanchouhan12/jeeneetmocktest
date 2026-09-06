"""
purge_duplicate_questions.py — Strict Deduplication Audit & Purge for Firestore.

Scans all questions in Firestore, identifies duplicate or near-duplicate questions
using normalized text signatures and similarity matching, and purges duplicate documents.

Usage:
    # Dry run audit (check for duplicates without deleting)
    python scripts/purge_duplicate_questions.py --dry-run

    # Live purge (delete duplicate documents from Firestore)
    python scripts/purge_duplicate_questions.py
"""

import argparse
import os
import re
import sys
import difflib
import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')


def init_firebase(creds_path: str = SERVICE_ACCOUNT_PATH):
    if firebase_admin._apps:
        return firestore.client()
    if not os.path.exists(creds_path):
        print(f"ERROR: Credentials file not found at {creds_path}")
        sys.exit(1)
    firebase_admin.initialize_app(credentials.Certificate(creds_path))
    return firestore.client()


def normalize_text(text: str) -> str:
    """
    Normalizes question text by stripping LaTeX formatting,
    punctuation, numbers, and extra spaces for accurate deduplication.
    """
    if not text:
        return ""
    # Strip LaTeX delimiters and commands
    t = re.sub(r'\\\(|\\\)|\\\[|\\\]|\$', '', text)
    t = re.sub(r'\\text\{([^}]*)\}', r'\1', t)
    t = re.sub(r'\\[a-zA-Z]+', '', t)
    # Strip non-alphanumeric chars
    t = re.sub(r'[^a-zA-Z0-9\s]', '', t)
    # Lowercase & collapse spaces
    return re.sub(r'\s+', ' ', t).strip().lower()


def find_duplicates(docs: list) -> list:
    """
    Scans a list of Firestore DocumentSnapshots and returns a list of
    (duplicate_doc_id, original_doc_id, reason) to be deleted.
    Fast O(N) hash-based deduplication with prefix indexing.
    """
    seen_signatures = {}  # norm_signature -> doc_id
    seen_prefixes = {}    # prefix_50 -> list of (doc_id, norm_text)
    duplicates_to_delete = []

    for doc in docs:
        d_id = doc.id
        # Skip vault copies (they start with vault_)
        if d_id.startswith("vault_"):
            continue

        q = doc.to_dict()
        q_text = q.get('questionText', '').strip()
        norm = normalize_text(q_text)

        if not norm or len(norm) < 15:
            continue

        # Check 1: Exact normalized signature match
        if norm in seen_signatures:
            orig_id = seen_signatures[norm]
            duplicates_to_delete.append((d_id, orig_id, "Exact normalized text match"))
            continue

        # Check 2: Prefix hash match for fuzzy duplicates
        prefix = norm[:50]
        found_fuzzy = False
        if prefix in seen_prefixes:
            for seen_id, seen_norm in seen_prefixes[prefix]:
                ratio = difflib.SequenceMatcher(None, norm, seen_norm).ratio()
                if ratio > 0.88:
                    duplicates_to_delete.append((d_id, seen_id, f"Fuzzy text match ({ratio:.1%} similarity)"))
                    found_fuzzy = True
                    break

        if not found_fuzzy:
            seen_signatures[norm] = d_id
            if prefix not in seen_prefixes:
                seen_prefixes[prefix] = []
            seen_prefixes[prefix].append((d_id, norm))

    return duplicates_to_delete


def purge_duplicates(db, dry_run: bool = False, all_docs=None):
    print(f"\n🔍 Scanning Firestore for Duplicate Questions (Dry Run: {dry_run})...")
    questions_ref = db.collection('questions')
    all_docs = all_docs if all_docs is not None else questions_ref.get()
    print(f"ℹ️ Total documents in Firestore: {len(all_docs)}")

    duplicates = find_duplicates(all_docs)

    print(f"\n📊 Deduplication Summary:")
    print(f"   • Total Questions Scanned : {len(all_docs)}")
    print(f"   • Duplicates Identified    : {len(duplicates)}")

    if duplicates:
        print("\n📋 Sample Duplicate Questions Found:")
        print("-" * 80)
        for dup_id, orig_id, reason in duplicates[:10]:
            print(f"   ❌ Duplicate Doc ID : {dup_id}")
            print(f"      Kept Original ID : {orig_id}")
            print(f"      Reason           : {reason}")
            print("-" * 80)

        if not dry_run:
            print(f"\n⚠️ Deleting {len(duplicates)} duplicate documents from Firestore...")
            batch = db.batch()
            count = 0
            for dup_id, _, _ in duplicates:
                batch.delete(questions_ref.document(dup_id))
                count += 1
                if count >= 450:
                    batch.commit()
                    batch = db.batch()
                    count = 0
            if count > 0:
                batch.commit()

            print(f"✅ Successfully deleted {len(duplicates)} duplicate questions!")

            # Update question_bank metadata timestamp
            try:
                db.collection('metadata').document('question_bank').set({
                    'version': firestore.Increment(1),
                    'lastUpdated': firestore.SERVER_TIMESTAMP
                }, merge=True)
                print("🔄 Updated question_bank metadata version so apps download clean data.")
            except Exception as e:
                print(f"⚠️ Metadata version update failed: {e}")

    else:
        print("🎉 No duplicates found! The question bank is 100% unique.")

    deleted_ids = {dup_id for dup_id, _, _ in duplicates} if not dry_run else set()
    return len(duplicates), deleted_ids


def main():
    parser = argparse.ArgumentParser(description="Purge Duplicate Questions from Firestore")
    parser.add_argument("--dry-run", action="store_true", help="Perform audit without deleting")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to service account key")

    args = parser.parse_args()
    db = init_firebase(args.creds)
    purge_duplicates(db, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
