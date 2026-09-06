import firebase_admin
from firebase_admin import credentials, firestore
import os, sys, random

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

creds_path = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')
firebase_admin.initialize_app(credentials.Certificate(creds_path))
db = firestore.client()

print("🔍 Inspecting 3 random live questions from Firestore...\n")
docs = [d for d in db.collection('questions').limit(150).get() if not d.id.startswith('vault_')]
samples = random.sample(docs, 3)

for i, doc in enumerate(samples, 1):
    q = doc.to_dict()
    print("=" * 70)
    print(f"📌 QUESTION #{i} (ID: {doc.id})")
    print("=" * 70)
    print(f"🎯 Exam & Subject : {q.get('examType')} | {q.get('subject')} ({q.get('chapter')})")
    print(f"📝 Question Text  : {q.get('questionText')}")
    print("\n💡 Options        :")
    for idx, opt in enumerate(q.get('options', [])):
        marker = " ✅ (Correct)" if idx == q.get('correctOption') else ""
        print(f"   [{chr(65+idx)}] {opt}{marker}")
    print(f"\n📖 Explanation    :\n{q.get('explanation')}")
    print("=" * 70 + "\n")
