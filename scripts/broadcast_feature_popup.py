#!/usr/bin/env python3
"""
scripts/broadcast_feature_popup.py

Instant Feature Announcement Broadcast Script.
Run this script ANYTIME you want to pop up a new feature notification / release announcement
to ALL users on ANY app version — zero versionCode or version string dependency required!

When you run this script:
1. It generates a new unique timestamped Broadcast ID.
2. It writes to Firestore (`metadata/app_announcement`).
3. Every app user opening the app gets an instant dark-mode Feature Popup with an "Update on Play Store" button!

Usage Examples:
  # 1. Broadcast default new features (Instant popup for all users on any version):
  python scripts/broadcast_feature_popup.py

  # 2. Broadcast custom feature highlights:
  python scripts/broadcast_feature_popup.py --title "New Features Released! 🚀" --notes "Visual PYQ STEM Diagrams with Zoom, Prioritized Chapterwise Diagrams, Auto-Healing Formula Engine"

  # 3. Test script execution without modifying Firestore:
  python scripts/broadcast_feature_popup.py --dry-run
"""

import argparse
import os
import sys
import time

# Configure UTF-8 encoding
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

import firebase_admin
from firebase_admin import credentials, firestore

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')
DEFAULT_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.jeeneet.mocktest&pli=1"


def init_firestore():
    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        print(f"❌ Error: {SERVICE_ACCOUNT_PATH} not found!")
        sys.exit(1)
    if not firebase_admin._apps:
        cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
        firebase_admin.initialize_app(cred)
    return firestore.client()


def broadcast_feature_popup(
    title: str = "",
    message: str = "",
    notes: list = None,
    button_text: str = "",
    action_url: str = "",
    dry_run: bool = False
):
    broadcast_id = f"broadcast_{int(time.time())}"

    if not notes:
        notes = [
            "📅 Daily fresh JEE & NEET questions via AI top-up & 7-stage quality verification",
            "🎨 Rich STEM PYQ diagrams with 1-tap pinch-to-zoom viewer",
            "⚡ Prioritized image questions placed right at the top of Chapterwise tests",
            "🏆 Weekly refreshed Power 100 tests aligned with 2026 NTA syllabus",
            "🧮 Fast, clean KaTeX math & chemical formula rendering"
        ]

    if not title:
        title = "New Features Released! 🚀"

    if not message:
        message = "A fresh update of JEE & NEET Mock Test is available on Play Store! Upgrade now to enjoy new features."

    if not button_text:
        button_text = "Update on Play Store"

    if not action_url:
        action_url = DEFAULT_PLAY_STORE_URL

    payload = {
        "broadcastId": broadcast_id,
        "title": title,
        "message": message,
        "releaseNotes": notes,
        "buttonText": button_text,
        "actionUrl": action_url,
        "active": True,
        "updatedAt": firestore.SERVER_TIMESTAMP
    }

    print("=================================================================")
    print(f"📣 Instant Feature Broadcast (ID: {broadcast_id})")
    print(f"🔗 Action URL: {action_url}")
    print(f"🧪 Execution Mode: {'DRY RUN (No Firestore writes)' if dry_run else 'LIVE BROADCAST'}")
    print("=================================================================")
    print(f"📌 Title  : {title}")
    print(f"💬 Message: {message}")
    print("📋 Feature Highlights:")
    for note in notes:
        print(f"   • {note}")

    if dry_run:
        print("\n🔎 DRY RUN PREVIEW (Firestore document metadata/app_announcement):")
        import json
        preview_payload = {k: v for k, v in payload.items() if k != "updatedAt"}
        print(json.dumps(preview_payload, indent=2, ensure_ascii=False))
        print("=================================================================")
        print("✅ Dry run successful. Zero changes made to Firestore.")
        print("=================================================================")
        return

    db = init_firestore()
    db.collection('metadata').document('app_announcement').set(payload, merge=True)

    print("=================================================================")
    print(f"🎉 SUCCESS! Feature announcement popup is now LIVE for ALL users on ANY app version!")
    print(f"📲 Play Store Link: {action_url}")
    print("=================================================================")


def main():
    parser = argparse.ArgumentParser(description="Broadcast Instant Feature Popup to ALL App Users")
    parser.add_argument("--title", type=str, default="", help="Popup title header")
    parser.add_argument("--message", type=str, default="", help="Popup description message")
    parser.add_argument("--notes", type=str, default="", help="Comma-separated list of feature bullet points")
    parser.add_argument("--button", type=str, default="Update on Play Store", help="Action button text")
    parser.add_argument("--url", type=str, default=DEFAULT_PLAY_STORE_URL, help="Target update URL")
    parser.add_argument("--dry-run", action="store_true", help="Test script execution without modifying Firestore")

    args = parser.parse_args()

    notes_list = [n.strip() for n in args.notes.split(",") if n.strip()] if args.notes else None

    broadcast_feature_popup(
        title=args.title,
        message=args.message,
        notes=notes_list,
        button_text=args.button,
        action_url=args.url,
        dry_run=args.dry_run
    )


if __name__ == "__main__":
    main()
