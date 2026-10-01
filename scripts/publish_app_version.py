#!/usr/bin/env python3
"""
scripts/publish_app_version.py

Utility to publish app update releases to Firestore (app_config/version_control & metadata/app_version).
Old app clients will automatically detect this version metadata and show the In-App Update popup!

Usage Examples:
  # Publish a recommended (optional) update:
  python scripts/publish_app_version.py --code 20 --name "1.2.2" --notes "Visual STEM Diagrams, KaTeX Formula Upgrades, Bug Fixes"

  # Publish a mandatory / force update:
  python scripts/publish_app_version.py --code 20 --name "1.2.2" --force --min-code 20 --title "🚨 Critical Update Required"
"""

import argparse
import os
import sys

# Configure UTF-8 encoding
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

import firebase_admin
from firebase_admin import credentials, firestore

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

def init_firestore():
    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        print(f"❌ Error: {SERVICE_ACCOUNT_PATH} not found!")
        sys.exit(1)
    if not firebase_admin._apps:
        cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
        firebase_admin.initialize_app(cred)
    return firestore.client()

def publish_version(
    latest_code: int,
    latest_name: str,
    min_required_code: int,
    is_force: bool,
    title: str = "",
    message: str = "",
    release_notes: list = None,
    play_store_url: str = ""
):
    db = init_firestore()
    
    if not release_notes:
        release_notes = [
            "✨ Visual STEM & Diagram questions with Pinch-to-Zoom",
            "⚡ High-speed CDN & offline diagram rendering",
            "🧪 Enhanced KaTeX chemical formula support",
            "🐞 Performance improvements & bug fixes"
        ]

    if not title:
        title = "🚨 Critical Update Required" if is_force else "New Version Available! 🚀"

    if not message:
        if is_force:
            message = "A critical update is required to continue preparing for JEE & NEET with the latest question bank and test engine."
        else:
            message = "A fresh update of JEE & NEET Mock Test is available! Upgrade now for diagram questions, faster tests, and formula improvements."

    payload = {
        "latestVersionCode": latest_code,
        "latestVersionName": latest_name,
        "minRequiredVersionCode": min_required_code,
        "forceUpdateEnabled": is_force,
        "isForceUpdate": is_force,
        "title": title,
        "message": message,
        "releaseNotes": release_notes,
        "playStoreUrl": play_store_url or "https://play.google.com/store/apps/details?id=com.jeeneet.mocktest",
        "updatedAt": firestore.SERVER_TIMESTAMP
    }

    print(f"📦 Publishing App Version Info:")
    print(f"   • Latest Version:     {latest_name} (code: {latest_code})")
    print(f"   • Min Required Code:  {min_required_code}")
    print(f"   • Force Update:       {is_force}")
    print(f"   • Title:              {title}")
    print(f"   • Release Notes:      {len(release_notes)} items")

    # Update app_config/version_control (primary legacy & new path)
    doc1 = db.collection('app_config').document('version_control')
    doc1.set(payload, merge=True)
    print("   ✅ Updated 'app_config/version_control'")

    # Update metadata/app_version (standard metadata collection)
    doc2 = db.collection('metadata').document('app_version')
    doc2.set(payload, merge=True)
    print("   ✅ Updated 'metadata/app_version'")

    print("\n🎉 Success! All active apps will now detect this update.")

def main():
    parser = argparse.ArgumentParser(description="Publish App Version Control to Firestore")
    parser.add_argument("--code", type=int, default=19, help="Latest version code (e.g. 19, 20)")
    parser.add_argument("--name", type=str, default="1.2.1", help="Latest version name (e.g. '1.2.1')")
    parser.add_argument("--min-code", type=int, default=17, help="Minimum required version code below which users cannot proceed")
    parser.add_argument("--force", action="store_true", help="Mark update as mandatory (force update dialog)")
    parser.add_argument("--title", type=str, default="", help="Custom dialog title")
    parser.add_argument("--message", type=str, default="", help="Custom dialog description message")
    parser.add_argument("--notes", type=str, default="", help="Comma-separated or semicolon-separated release notes")
    parser.add_argument("--url", type=str, default="", help="Custom store download URL")

    args = parser.parse_args()

    notes_list = [n.strip() for n in args.notes.split(",") if n.strip()] if args.notes else None

    publish_version(
        latest_code=args.code,
        latest_name=args.name,
        min_required_code=args.min_code,
        is_force=args.force,
        title=args.title,
        message=args.message,
        release_notes=notes_list,
        play_store_url=args.url
    )

if __name__ == '__main__':
    main()
