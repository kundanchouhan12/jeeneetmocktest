import firebase_admin
from firebase_admin import credentials, firestore
import os
import sys

# Configure stdout encoding to utf-8
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

if not os.path.exists(SERVICE_ACCOUNT_PATH):
    print(f"ERROR: {SERVICE_ACCOUNT_PATH} not found!")
    exit(1)

if not firebase_admin._apps:
    cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
    firebase_admin.initialize_app(cred)
db = firestore.client()

def setup_version_control():
    print("⏳ Creating/updating 'version_control' in Firestore...")
    doc_ref = db.collection('app_config').document('version_control')
    
    data = {
        'minRequiredVersionCode': 17,
        'forceUpdateEnabled': True
    }

    doc_ref.set(data, merge=True)
    print("✅ Successfully updated 'app_config/version_control' with minRequiredVersionCode=17 and forceUpdateEnabled=True!")

if __name__ == '__main__':
    setup_version_control()
