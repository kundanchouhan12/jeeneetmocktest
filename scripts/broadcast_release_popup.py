#!/usr/bin/env python3
"""
scripts/broadcast_release_popup.py

Run this script anytime you publish a new version or want to show an In-App Release Popup to users!
It automatically reads the current `versionCode` & `versionName` from `app/build.gradle`,
updates Firestore (`app_config/version_control` & `metadata/app_version`), and triggers
the feature popup dialog on all user devices with a direct link to your Play Store URL:
https://play.google.com/store/apps/details?id=com.jeeneet.mocktest&pli=1

Usage:
  # 1. Automatic mode (uses default v1.2.2 release notes & version from build.gradle):
  python scripts/broadcast_release_popup.py

  # 2. Custom release notes mode:
  python scripts/broadcast_release_popup.py --notes "New organic reaction diagrams, Fast test loading, Bug fixes"

  # 3. Mandatory / Force Update mode:
  python scripts/broadcast_release_popup.py --force
"""

import argparse
import os
import re
import sys

# Configure UTF-8 encoding
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

import firebase_admin
from firebase_admin import credentials, firestore

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')
BUILD_GRADLE_PATH = os.path.join(ROOT, 'app', 'build.gradle')
PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.jeeneet.mocktest&pli=1"


def parse_app_version():
    """Reads versionCode and versionName directly from app/build.gradle."""
    version_code = 20
    version_name = "1.2.2"
    if os.path.exists(BUILD_GRADLE_PATH):
        content = open(BUILD_GRADLE_PATH, encoding='utf-8').read()
        m_code = re.search(r'versionCode\s+(\d+)', content)
        m_name = re.search(r'versionName\s+["\']([^"\']+)["\']', content)
        if m_code:
            version_code = int(m_code.group(1))
        if m_name:
            version_name = m_name.group(1)
    return version_code, version_name


def init_firestore():
    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        print(f"❌ Error: {SERVICE_ACCOUNT_PATH} not found!")
        sys.exit(1)
    if not firebase_admin._apps:
        cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
        firebase_admin.initialize_app(cred)
    return firestore.client()


def broadcast_release(notes: list = None, is_force: bool = False, title: str = "", message: str = ""):
    version_code, version_name = parse_app_version()
    db = init_firestore()

    if not notes:
        notes = [
            "📅 Daily fresh JEE & NEET questions via AI top-up & 7-stage quality verification",
            "🎨 Rich STEM PYQ diagrams with 1-tap pinch-to-zoom viewer",
            "⚡ Prioritized image questions placed right at the top of Chapterwise tests",
            "🏆 Weekly refreshed Power 100 tests aligned with 2026 NTA syllabus",
            "🧮 Fast, clean KaTeX math & chemical formula rendering"
        ]

    if not title:
        title = "🚨 Critical Update Required" if is_force else "New Version Available! 🚀"

    if not message:
        if is_force:
            message = f"A mandatory update (v{version_name}) is required to continue practicing with the latest 2026 test bank and features."
        else:
            message = f"A fresh update (v{version_name}) of JEE & NEET Mock Test is live on Play Store! Upgrade now to enjoy new features."

    payload = {
        "latestVersionCode": version_code,
        "latestVersionName": version_name,
        "minRequiredVersionCode": version_code if is_force else 1,
        "forceUpdateEnabled": is_force,
        "isForceUpdate": is_force,
        "title": title,
        "message": message,
        "releaseNotes": notes,
        "playStoreUrl": PLAY_STORE_URL,
        "updatedAt": firestore.SERVER_TIMESTAMP
    }

    print("=================================================================")
    print(f"🚀 Broadcasting Release Popup for Version {version_name} (Build {version_code})")
    print(f"🔗 Target Play Store Link: {PLAY_STORE_URL}")
    print(f"🛡️ Mandatory / Force Update : {'YES' if is_force else 'NO (Recommended)'}")
    print("=================================================================")
    print("📋 Feature Highlights:")
    for note in notes:
        print(f"   • {note}")

    # Write to Firestore version documents
    db.collection('app_config').document('version_control').set(payload, merge=True)
    print("\n   ✅ Updated Firestore: 'app_config/version_control'")

    db.collection('metadata').document('app_version').set(payload, merge=True)
    print("   ✅ Updated Firestore: 'metadata/app_version'")

    print("=================================================================")
    print(f"🎉 SUCCESS! Popup is now LIVE for all users on Play Store URL:\n   {PLAY_STORE_URL}")
    print("=================================================================")


def main():
    parser = argparse.ArgumentParser(description="Broadcast Release Popup to All App Users")
    parser.add_argument("--notes", type=str, default="", help="Comma-separated list of release feature highlights")
    parser.add_argument("--force", action="store_true", help="Set update as mandatory")
    parser.add_argument("--title", type=str, default="", help="Custom dialog title")
    parser.add_argument("--message", type=str, default="", help="Custom dialog description")

    args = parser.parse_args()

    notes_list = [n.strip() for n in args.notes.split(",") if n.strip()] if args.notes else None

    broadcast_release(
        notes=notes_list,
        is_force=args.force,
        title=args.title,
        message=args.message
    )


if __name__ == "__main__":
    main()
