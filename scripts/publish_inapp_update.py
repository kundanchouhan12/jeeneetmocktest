"""
publish_inapp_update.py — Script to trigger live in-app update notification in Firestore.

Usage:
    python scripts/publish_inapp_update.py                # Triggers Version 20 Recommended Update
    python scripts/publish_inapp_update.py --force        # Triggers Version 20 Mandatory Force Update
    python scripts/publish_inapp_update.py --reset        # Resets version control document
"""

import argparse
import sys
import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SERVICE_ACCOUNT_PATH = 'scripts/serviceAccountKey.json'
cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

parser = argparse.ArgumentParser(description="Manage In-App Update Config in Firestore")
parser.add_argument("--force", action="store_true", help="Enable mandatory force update mode")
parser.add_argument("--reset", action="store_true", help="Reset version control document to default")
args = parser.parse_args()

doc_ref = db.collection('app_config').document('version_control')

if args.reset:
    reset_config = {
        'latestVersionCode': 20,
        'latestVersionName': '1.2.2',
        'minRequiredVersionCode': 1,
        'isForceUpdate': False,
        'title': '',
        'message': '',
        'releaseNotes': [],
        'playStoreUrl': ''
    }
    doc_ref.set(reset_config, merge=True)
    print("✅ Firestore 'app_config/version_control' reset back to Version 20 base state.")
else:
    update_config = {
        'latestVersionCode': 20,
        'latestVersionName': '1.2.2',
        'minRequiredVersionCode': 20 if args.force else 15,
        'isForceUpdate': args.force,
        'title': '🚀 New JEE/NEET 2026 Update Available!',
        'message': 'Version 1.2.2 is live on Play Store with fresh practice questions, live chapter sync, and performance improvements.',
        'releaseNotes': [
            '• Live 2026 chapterwise question sync',
            '• Instant diagram zoom & dark-mode cards',
            '• Revise My Mistakes notebook enhancements',
            '• Zero repetition anti-cooldown question bank'
        ],
        'playStoreUrl': 'https://play.google.com/store/apps/details?id=com.jeeneet.mocktest'
    }
    doc_ref.set(update_config, merge=True)
    mode = "MANDATORY FORCE UPDATE" if args.force else "RECOMMENDED UPDATE"
    print(f"🎉 Successfully published live [{mode}] for Version 20 (v1.2.2) to Firestore!")
    print("All active app users will now see the update notification on next launch.")
