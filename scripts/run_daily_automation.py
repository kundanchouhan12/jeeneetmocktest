"""
run_daily_automation.py — Master Automation Orchestrator for Daily Vault & Question Bank.

Performs complete daily maintenance:
1. Fetch/sanitize web-sourced questions, independently answer-verified (`web_question_ingestion.py`).
2. Top up Firestore question bank with high-quality AI generated questions, independently answer-verified (`auto_question_pipeline.py`).
3. Purge duplicate questions (`purge_duplicate_questions.py`) and audit/purge corrupted or noisy questions (`cleanup_corrupted_questions.py`).
4. Schedule Daily Vault questions for JEE and NEET (`vault_scheduler.py`).
5. Rebuild Power 100 for JEE and NEET from the live, cleaned bank (`build_power100_live.py`).
6. Bump Firestore metadata version timestamp so all mobile clients auto-sync.

Any step that raises is caught, logged, and recorded — the run still attempts every
remaining step, but if any step failed the script exits non-zero and prints a
"FAILED STEP(S)" summary instead of claiming success, so a GitHub Actions run
shows red instead of silently reporting green on a partial failure.

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
from neet_web_question_ingestion import run_neet_web_ingestion

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')


class FirebaseAuthError(Exception):
    """Raised when the Firestore client can't actually be reached — e.g. a bad/expired
    GitHub secret producing `invalid_grant: Invalid JWT Signature`. Kept as a distinct,
    narrow exception (instead of a bare `except Exception` in main()) so a real auth/
    connectivity failure is never silently swallowed into a generic caught-and-continue
    step and always aborts the run with a non-zero exit."""


def verify_firebase_connectivity(db) -> None:
    """Confirms `db` is a working Firestore client by actually reading a document.
    `init_firebase()` can return a client object even when the credentials are bad —
    the failure only surfaces on first real network call — so this forces that call
    up front instead of deferring it into whichever pipeline step happens to run first."""
    if db is None:
        raise FirebaseAuthError("Firebase init returned no client.")
    try:
        db.collection("metadata").document("question_bank").get()
    except Exception as e:
        raise FirebaseAuthError(f"Firebase authentication/connectivity failed: {e}") from e


def run_cleanup_audit(db, dry_run: bool = False, all_docs=None) -> int:
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
    return len(corrupted_docs)


def main():
    parser = argparse.ArgumentParser(description="Master Daily Automation for MockTestApp")
    parser.add_argument("--dry-run", action="store_true", help="Perform dry run without database writes")
    parser.add_argument("--count-per-subj", type=int, default=10, help="Questions to generate per subject (default: 10)")
    parser.add_argument("--vault-count", type=int, default=30, help="Vault questions per exam (default: 30)")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to serviceAccountKey.json")
    parser.add_argument("--force-power100", action="store_true", help="Force Power 100 rebuild regardless of bi-weekly schedule")

    args = parser.parse_args()

    target_date = (datetime.date.today() + datetime.timedelta(days=1)).strftime("%Y-%m-%d")

    print("=================================================================")
    print(f"⏰ Starting Daily Vault & Question Bank Automation [{datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}]")
    print(f"📅 Target Vault Date : {target_date}")
    print(f"🛡️ Mode              : {'DRY RUN' if args.dry_run else 'LIVE PRODUCTION'}")
    print("=================================================================")

    failed_steps: list[str] = []

    db = None
    if not args.dry_run:
        if not os.path.exists(args.creds):
            print(f"ERROR: Firebase credentials file not found: {args.creds}")
            sys.exit(1)
        try:
            db = init_firebase(args.creds)
            verify_firebase_connectivity(db)
            print("✅ Firebase authentication succeeded (metadata/question_bank readable).")
        except FirebaseAuthError as e:
            print(f"ERROR: {e}")
            print("GitHub secret FIREBASE_SERVICE_ACCOUNT may be invalid (invalid_grant / JWT).")
            sys.exit(1)

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

    # Step 1: Web Question Ingestion & Noise Sanitization (JEE & NEET)
    try:
        run_web_ingestion(count_per_subject=args.count_per_subj, target_exam="JEE", dry_run=args.dry_run, db=db, all_docs=pre_write_docs)
    except Exception as e:
        print(f"❌ Error during JEE Web Question Ingestion: {e}")
        failed_steps.append(f"JEE Web Question Ingestion: {e}")
    try:
        time.sleep(20)
        run_neet_web_ingestion(count_per_subject=args.count_per_subj, dry_run=args.dry_run, db=db, all_docs=pre_write_docs)
    except Exception as e:
        print(f"❌ Error during NEET Web Question Ingestion: {e}")
        failed_steps.append(f"NEET Web Question Ingestion: {e}")

    # Web ingestion and the AI pipeline both call the same Groq free-tier
    # quota. Pause between them so step 2 doesn't start inside the same
    # per-minute window step 1 just used up.
    time.sleep(20)

    # Step 2: AI Question Generation Top-up
    try:
        run_pipeline(count_per_subject=args.count_per_subj, dry_run=args.dry_run, db=db, all_docs=pre_write_docs)
    except Exception as e:
        print(f"❌ Error during AI Question Generation Pipeline: {e}")
        failed_steps.append(f"AI Question Generation Pipeline: {e}")

    if not args.dry_run and db:
        # Re-fetch once now that steps 1-2 have written new docs, and share
        # this single read across both audit steps below.
        vault_ok = {"JEE": False, "NEET": False}
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
            failed_steps.append(f"Deduplication Audit: {e}")

        # Step 3: Clean up any corrupted questions. Drop docs purge_duplicates
        # just deleted from the shared snapshot so the audit doesn't re-scan
        # (and re-issue no-op deletes for) documents that no longer exist.
        audit_docs = post_write_docs
        if audit_docs is not None and deleted_dup_ids:
            audit_docs = [d for d in audit_docs if d.id not in deleted_dup_ids]
        deleted_corrupt_count = 0
        try:
            deleted_corrupt_count = run_cleanup_audit(db, dry_run=args.dry_run, all_docs=audit_docs)
        except Exception as e:
            print(f"❌ Error during Corrupted Question Audit: {e}")
            failed_steps.append(f"Corrupted Question Audit: {e}")

        # Step 3: Schedule Daily Vault for JEE and NEET
        try:
            print("\n⚡ Scheduling Daily Vault Questions...")
            for exam in ["JEE", "NEET"]:
                try:
                    schedule_vault(db, target_date, exam, count=args.vault_count)
                    vault_ok[exam] = True
                except Exception as e:
                    print(f"❌ Error during {exam} Daily Vault: {e}")
                    failed_steps.append(f"{exam} Daily Vault: {e}")
        except Exception as e:
            print(f"❌ Error during Vault Scheduling: {e}")
            failed_steps.append(f"Vault Scheduling: {e}")

        # Step 4: Rebuild Power 100 for JEE and NEET bi-weekly (1st & 15th of each month, or when forced).
        # Gives users 2 weeks to complete Power 100 standard tests without daily resets.
        # Step 4: Weekly Power 100 Rebuild (Runs every 7 days / Monday)
        today = datetime.date.today()
        # Weekly schedule: Every Monday (weekday == 0) runs every 7 days
        is_power100_day = args.force_power100 or (today.weekday() == 0)
        if is_power100_day:
            try:
                print(f"\n🏆 Rebuilding Power 100 (Weekly Schedule: Day {today.strftime('%A')}, {today.isoformat()})...")
                power100_docs = db.collection('questions').get()
                for exam in ["JEE", "NEET"]:
                    run_power100_rebuild(exam, dry_run=args.dry_run, db=db, all_docs=power100_docs)
            except Exception as e:
                print(f"❌ Error during Power 100 Rebuild: {e}")
                failed_steps.append(f"Power 100 Rebuild: {e}")
        else:
            print(f"\nℹ️ Skipping Power 100 Rebuild today (Runs weekly every Monday; today is {today.strftime('%A')}).")

        # Step 5: Increment metadata version
        try:
            db.collection('metadata').document('question_bank').set({
                'version': firestore.Increment(1),
                'lastUpdated': firestore.SERVER_TIMESTAMP
            }, merge=True)
            print("\n🔄 Metadata version updated. Clients will automatically download the new vault!")
        except Exception as e:
            print(f"⚠️ Metadata update failed: {e}")
            failed_steps.append(f"Metadata Version Update: {e}")

        # Step 6: Record Daily Health & Growth Summary Telemetry
        try:
            final_bank_docs = db.collection('questions').get()
            total_jee = len([d for d in final_bank_docs if d.to_dict().get('examType') == 'JEE' and not d.id.startswith('vault_')])
            total_neet = len([d for d in final_bank_docs if d.to_dict().get('examType') == 'NEET' and not d.id.startswith('vault_')])
            db.collection('metadata').document('daily_health_summary').set({
                'last_run_timestamp': firestore.SERVER_TIMESTAMP,
                'target_vault_date': target_date,
                'duplicates_purged_count': len(deleted_dup_ids),
                'corrupted_purged_count': deleted_corrupt_count,
                'live_total_jee': total_jee,
                'live_total_neet': total_neet,
                'vault_scheduled_jee': 30 if vault_ok.get("JEE") else 0,
                'vault_scheduled_neet': 30 if vault_ok.get("NEET") else 0,
                'failed_steps_count': len(failed_steps)
            }, merge=True)
            print(f"📊 Telemetry saved: {total_jee} JEE / {total_neet} NEET live questions in bank.")
        except Exception as e:
            print(f"⚠️ Telemetry summary update failed: {e}")

    print("\n=================================================================")
    if failed_steps:
        print(f"⚠️ Daily Automation Completed WITH {len(failed_steps)} FAILED STEP(S):")
        for step in failed_steps:
            print(f"   ❌ {step}")
        print("=================================================================\n")
        sys.exit(1)
    else:
        print("🎉 Daily Automation Completed Successfully!")
        print("=================================================================\n")


if __name__ == "__main__":
    main()
