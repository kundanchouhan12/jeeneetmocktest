"""
refresh_vault_today.py — Force refresh today's Daily Vault for JEE and NEET.

Use this utility script after running manual web ingestion or when you want to
immediately test new questions/diagrams in the mobile app without waiting for midnight.

Usage:
    python scripts/refresh_vault_today.py
    python scripts/refresh_vault_today.py --exam JEE
    python scripts/refresh_vault_today.py --exam NEET
"""

import argparse
import datetime
import os
import sys
import time

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

sys.path.insert(0, os.path.dirname(__file__))

from auto_question_pipeline import init_firebase
from vault_scheduler import schedule_vault, EXPECTED_VAULT_COUNT
from firebase_admin import firestore

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), "serviceAccountKey.json")


def main():
    parser = argparse.ArgumentParser(description="Force refresh today's Daily Vault in Firestore")
    parser.add_argument("--exam", choices=["JEE", "NEET"], default=None, help="Limit to specific exam")
    parser.add_argument("--creds", default=SERVICE_ACCOUNT_PATH, help="Path to service account JSON")
    args = parser.parse_args()

    today_str = datetime.date.today().strftime("%Y-%m-%d")
    print("=================================================================")
    print(f"🔄 Force Refreshing Daily Vault for TODAY: {today_str}")
    print("=================================================================")

    db = init_firebase(args.creds)
    exams = [args.exam] if args.exam else ["JEE", "NEET"]

    for exam in exams:
        try:
            schedule_vault(db, today_str, exam, count=EXPECTED_VAULT_COUNT, force=True)
            print(f"✅ {exam} Daily Vault scheduled with 30 fresh questions (including STEM diagrams)!")
        except Exception as e:
            print(f"❌ Failed to schedule {exam} vault: {e}")

    try:
        new_version = int(time.time())
        db.collection("metadata").document("question_bank").set({
            "version": new_version,
            "lastUpdated": firestore.SERVER_TIMESTAMP,
            "vault_date": today_str,
            "diagram_support_enabled": True
        }, merge=True)
        print(f"\n🔄 question_bank metadata bumped to v{new_version}")
    except Exception as e:
        print(f"⚠️ Warning: Could not update question_bank metadata: {e}")

    print("\n=================================================================")
    print("🎉 Done! To see the new questions on your phone:")
    print("   1. Open the MockTest app.")
    print("   2. Tap the '🔄 Sync' badge on the Daily Vault card (or Drawer menu -> '🔄 Sync Fresh Questions').")
    print("   3. The app will immediately load the fresh 30 questions into your test!")
    print("=================================================================\n")


if __name__ == "__main__":
    main()
