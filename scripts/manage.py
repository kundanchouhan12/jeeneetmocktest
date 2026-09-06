import sys
import os
import datetime

# Ensure the scripts directory is in the path
sys.path.append(os.path.dirname(__file__))

# Import logic from other scripts
try:
    import bulk_import
    import vault_scheduler
    import firebase_admin
    from firebase_admin import messaging, credentials, firestore
except ImportError as e:
    print(f"⚠️ Warning: Missing dependencies - {e}")

# Global setup for Firebase if not already initialized
if not firebase_admin._apps:
    cred_path = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')
    if os.path.exists(cred_path):
        cred = credentials.Certificate(cred_path)
        firebase_admin.initialize_app(cred)

def show_help():
    print("\n🚀 MockTestApp Master Controller")
    print("-" * 30)
    print("Commands:")
    print("  import     -> Upload all JSON files from /content to Firebase")
    print("  schedule   -> Pick 30 random questions per exam for Today & Tomorrow's Vault + Send Notification")
    print("  help       -> Show this menu")
    print("-" * 30)
    print("Usage: python manage.py <command>\n")

def send_global_notification(title, body):
    """Triggers a push notification to all users."""
    try:
        message = messaging.Message(
            notification=messaging.Notification(title=title, body=body),
            topic='all_users',
            data={'type': 'DAILY_VAULT'}
        )
        response = messaging.send(message)
        print(f"🔔 Notification sent: {response}")
    except Exception as e:
        print(f"❌ Failed to send notification: {e}")

def main():
    if len(sys.argv) < 2:
        show_help()
        return

    cmd = sys.argv[1].lower()

    if cmd == "import":
        print("📁 Starting master import...")
        content_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'content'))
        bulk_import.upload_folder(content_dir)

    elif cmd == "schedule":
        print("🎲 Scheduling Daily Vault for Today and Tomorrow...")
        db = firestore.client()
        today = datetime.date.today().strftime('%Y-%m-%d')
        tomorrow = (datetime.date.today() + datetime.timedelta(days=1)).strftime('%Y-%m-%d')

        # Schedule for both JEE and NEET for today
        vault_scheduler.schedule_vault(db, today, "JEE", 30)
        vault_scheduler.schedule_vault(db, today, "NEET", 30)

        # Schedule for both JEE and NEET for tomorrow
        vault_scheduler.schedule_vault(db, tomorrow, "JEE", 30)
        vault_scheduler.schedule_vault(db, tomorrow, "NEET", 30)

        print("✨ Vault ready. Sending push notification...")
        send_global_notification(
            "🔥 Daily Vault Refreshed!",
            "Today's 50 Fresh PYQs are now live. Unlock them now for +50 Coins!"
        )

    elif cmd == "help":
        show_help()
    else:
        print(f"❌ Unknown command: {cmd}")
        show_help()

if __name__ == "__main__":
    main()
