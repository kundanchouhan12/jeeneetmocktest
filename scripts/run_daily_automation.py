"""
run_daily_automation.py — Master Automation Orchestrator for Daily Vault & Question Bank.

Performs complete daily maintenance:
1. Top up Firestore question bank with high-quality AI generated questions (`auto_question_pipeline.py`).
2. Audit & purge corrupted or noisy questions (`cleanup_corrupted_questions.py`).
3. Schedule Daily Vault questions for JEE and NEET (`vault_scheduler.py`).
4. Bump Firestore metadata version timestamp so all mobile clients auto-sync.

Usage:
    # Full daily execution (live)
    python scripts/run_daily_automation.py

    # Test run (dry-run mode without modifying database)
    python scripts/run_daily_automation.py --dry-run
"""

import argparse
import os
import sys
import time
import datetime
import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# Import sub-modules from scripts folder
from auto_question_pipeline import run_pipeline, init_firebase
from build_power100_live import run_power100_rebuild
from cleanup_corrupted_questions import is_corrupted
from purge_duplicate_questions import purge_duplicates
from vault_scheduler import schedule_vault
from web_question_ingestion import run_web_ingestion

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')


def run_cleanup_audit(db, dry_run: bool = False, all_docs=None):
    print("\n🧹 Running Firestore Corrupted Question Audit...")
    questions_ref = db.collection('questions')
    docs = all_docs if all_docs is not None else questions_ref.get()

    corrupted_docs = []
    for doc in docs:
        q = doc.to_dict()
        corrupted, reason = is_corrupted(q)
        if corrupted:
            corrupted_docs.append((doc.id, reason))

    print(f"  • Scanned {len(docs)} total documents. Found {len(corrupted_docs)} corrupted/noisy entries.")

    if corrupted_docs and not dry_run:
        print(f"  ⚠️ Deleting {len(corrupted_docs)} corrupted questions...")
        batch = db.batch()
        count = 0
        for doc_id, _ in corrupted_docs:
            batch.delete(questions_ref.document(doc_id))
            count += 1
            if count >= 450:
                batch.commit()
                batch = db.batch()
                count = 0
        if count > 0:
            batch.commit()
        print("  ✅ Purge complete.")
    elif corrupted_docs:
        print("  🔎 Dry run: Skipped deletion.")


def main():
    parser = argparse.ArgumentParser(description="Master Daily Automation for MockTestApp")
    parser.add_argument("--dry-run", action="store_true", help="Perform dry run without database writes")
    parser.add_argument("--count-per-subj", type=int, default=3, help="Questions to generate per subject (default: 3)")
    parser.add_argument("--vault-count", type=int, default=30, help="Vault questions per exam (default: 30)")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to serviceAccountKey.json")

    args = parser.parse_args()

    target_date = (datetime.date.today() + datetime.timedelta(days=1)).strftime("%Y-%m-%d")

    print("=================================================================")
    print(f"⏰ Starting Daily Vault & Question Bank Automation [{datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}]")
    print(f"📅 Target Vault Date : {target_date}")
    print(f"🛡️ Mode              : {'DRY RUN' if args.dry_run else 'LIVE PRODUCTION'}")
    print("=================================================================")

    db = None
    if not args.dry_run:
        db = init_firebase(args.creds)

    # Fetch the question bank once and share it across the ingestion and
    # pipeline dedup checks below, instead of each step re-reading the whole
    # collection independently (was 2 full scans on a Firestore free-tier
    # quota that's shared with live app traffic).
    pre_write_docs = None
    if not args.dry_run and db:
        try:
            pre_write_docs = db.collection('questions').get()
        except Exception as e:
            print(f"⚠️ Could not pre-fetch question bank for dedup: {e}")

    # Step 1: Web Question Ingestion & Noise Sanitization
    try:
        run_web_ingestion(count_per_subject=args.count_per_subj, dry_run=args.dry_run, db=db, all_docs=pre_write_docs)
    except Exception as e:
        print(f"❌ Error during Web Question Ingestion: {e}")

    # Web ingestion and the AI pipeline both call the same Groq free-tier
    # quota. Pause between them so step 2 doesn't start inside the same
    # per-minute window step 1 just used up.
    time.sleep(20)

    # Step 2: AI Question Generation Top-up
    try:
        run_pipeline(count_per_subject=args.count_per_subj, dry_run=args.dry_run, db=db, all_docs=pre_write_docs)
    except Exception as e:
        print(f"❌ Error during AI Question Generation Pipeline: {e}")

    if not args.dry_run and db:
        # Re-fetch once now that steps 1-2 have written new docs, and share
        # this single read across both audit steps below.
        post_write_docs = None
        try:
            post_write_docs = db.collection('questions').get()
        except Exception as e:
            print(f"⚠️ Could not re-fetch question bank for audit steps: {e}")

        # Step 2: Strict Deduplication Audit & Purge
        deleted_dup_ids = set()
        try:
            _, deleted_dup_ids = purge_duplicates(db, dry_run=args.dry_run, all_docs=post_write_docs)
        except Exception as e:
            print(f"❌ Error during Deduplication Audit: {e}")

        # Step 3: Clean up any corrupted questions. Drop docs purge_duplicates
        # just deleted from the shared snapshot so the audit doesn't re-scan
        # (and re-issue no-op deletes for) documents that no longer exist.
        audit_docs = post_write_docs
        if audit_docs is not None and deleted_dup_ids:
            audit_docs = [d for d in audit_docs if d.id not in deleted_dup_ids]
        try:
            run_cleanup_audit(db, dry_run=args.dry_run, all_docs=audit_docs)
        except Exception as e:
            print(f"❌ Error during Corrupted Question Audit: {e}")

        # Step 3: Schedule Daily Vault for JEE and NEET
        try:
            print("\n⚡ Scheduling Daily Vault Questions...")
            for exam in ["JEE", "NEET"]:
                schedule_vault(db, target_date, exam, count=args.vault_count)
        except Exception as e:
            print(f"❌ Error during Vault Scheduling: {e}")

        # Step 4: Rebuild Power 100 for JEE and NEET from the now-cleaned live bank.
        # Re-fetch rather than reuse audit_docs — that snapshot predates today's
        # ingestion/pipeline/vault writes and would under-select from the newest content.
        try:
            print("\n🏆 Rebuilding Power 100...")
            power100_docs = db.collection('questions').get()
            for exam in ["JEE", "NEET"]:
                run_power100_rebuild(exam, dry_run=args.dry_run, db=db, all_docs=power100_docs)
        except Exception as e:
            print(f"❌ Error during Power 100 Rebuild: {e}")

        # Step 5: Increment metadata version
        try:
            db.collection('metadata').document('question_bank').set({
                'version': firestore.Increment(1),
                'lastUpdated': firestore.SERVER_TIMESTAMP
            }, merge=True)
            print("\n🔄 Metadata version updated. Clients will automatically download the new vault!")
        except Exception as e:
            print(f"⚠️ Metadata update failed: {e}")

    print("\n🎉 Daily Automation Completed Successfully!")
    print("=================================================================\n")


if __name__ == "__main__":
    main()
