import firebase_admin
from firebase_admin import credentials, firestore
import os
import sys
import re

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

def is_corrupted(q):
    # Daily Vault documents are managed exclusively by vault_scheduler.py's own
    # select/validate/write/read-back-verify contract (exactly 30 per exam/date).
    # This heuristic-based cleanup — and anything else that imports is_corrupted,
    # e.g. run_daily_automation.py's run_cleanup_audit() — must never flag or
    # delete a live vault doc, or it can silently shrink an already-published
    # 30-question vault below the contract without the scheduler ever knowing.
    if q.get('isDailyVault') is True:
        return False, ""

    text = q.get('questionText', '').strip()
    options = q.get('options', [])
    
    # Rule 1: Extremely short question text
    if not text or len(text) < 15:
        return True, "Extremely short text"
        
    # Rule 2: Placeholder or empty options
    if len(options) != 4:
        return True, f"Invalid options count: {len(options)}"
    placeholder_options = ["option a", "option b", "option c", "option d"]
    if all(o.lower().strip() == p for o, p in zip(options, placeholder_options)):
        return True, "Placeholder options"
    if any(not str(o).strip() for o in options):
        return True, "Empty option string"

    # Rule 3: Missing diagram / figure dependencies
    fig_patterns = [
        r'in the (given )?(figure|diagram|circuit|graph|table)',
        r'as shown in (the )?(figure|diagram|circuit|graph|table|below)',
        r'refer to (the )?(figure|diagram|image)',
        r'shown below',
        r'see (the )?(figure|diagram)',
    ]
    has_image = bool(q.get('imageUrl')) or 'http' in text or 'data:image' in text
    for pat in fig_patterns:
        if re.search(pat, text, re.IGNORECASE) and not has_image:
            return True, f"Missing required figure/diagram ({pat})"

    # Rule 4: Incomplete equation text patterns
    incomplete_patterns = [
        r'area of the region\s*\\?\[?\s*\\?\{?\s*\(?x?,?\s*y?\)?',
        r'area of the region\s*(is|\$|\=|\:)\s*$',
        r'if the area of the region is \$',
        r'value of\s*is\s*(equal to|\$|\:)',
        r'is equal to\s*$',
        r'given by\s*$',
        r'^\s*the area of the region\s*is\s*$',
    ]
    for pat in incomplete_patterns:
        if re.search(pat, text, re.IGNORECASE):
            return True, f"Incomplete equation pattern ({pat})"

    # Rule 5: Text patterns indicating parsing failure / unsupported LaTeX / web noise
    bad_patterns = [
        r'refer to standard textbooks',
        r'Note:\s*For SHORT ANSWER',
        r'Master Practice Workbook',
        r'Click here to download',
        r'Questions with Answer Keys',
        r'Solutions\s*JEE Main',
        r'^\s*:\s*[A-D]\s*$',
        r'^\s*:\s*[A-D]\s*Note:',
        r'\\begin\{array\}',
        r'\\mbox\{',
        r'Page\s*\d+',
        r'Practice Workbook',
        r'Answer Key\s*—',
    ]
    all_content = text + " " + " ".join(str(o) for o in options) + " " + q.get('explanation', '')
    for pattern in bad_patterns:
        if re.search(pattern, all_content, re.IGNORECASE):
            return True, f"Matched bad pattern in text/options: {pattern}"
            
    return False, ""

def cleanup(dry_run=True):
    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        print(f"ERROR: {SERVICE_ACCOUNT_PATH} not found!")
        exit(1)
    if not firebase_admin._apps:
        cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
        firebase_admin.initialize_app(cred)
    db = firestore.client()
    print(f"🔍 Scanning Firestore for corrupted questions (Dry Run: {dry_run})...")
    questions_ref = db.collection('questions')
    
    docs = questions_ref.get()

    corrupted_docs = []
    vault_skipped = 0

    for doc in docs:
        q = doc.to_dict()
        # Belt-and-suspenders: skip Daily Vault docs by id prefix too, in case a
        # data anomaly ever left isDailyVault unset on an actual vault_* doc.
        if q.get('isDailyVault') is True or doc.id.startswith('vault_'):
            vault_skipped += 1
            continue
        corrupted, reason = is_corrupted(q)
        if corrupted:
            corrupted_docs.append((doc.id, q, reason))

    print(f"\nFound {len(corrupted_docs)} corrupted questions out of {len(docs)} total questions "
          f"({vault_skipped} Daily Vault docs skipped/protected).")
    
    if corrupted_docs:
        print("\nExamples of corrupted questions found:")
        print("-" * 80)
        for doc_id, q, reason in corrupted_docs[:10]:
            print(f"ID: {doc_id}")
            print(f"Reason: {reason}")
            print(f"Text: {q.get('questionText')[:150]}...")
            print(f"Options: {q.get('options')}")
            print("-" * 80)
            
        if not dry_run:
            print(f"\n⚠️ Deleting {len(corrupted_docs)} corrupted documents from Firestore...")
            batch = db.batch()
            count = 0
            for doc_id, _, _ in corrupted_docs:
                batch.delete(questions_ref.document(doc_id))
                count += 1
                if count >= 500:
                    batch.commit()
                    batch = db.batch()
                    count = 0
            if count > 0:
                batch.commit()
            print(f"✅ Successfully deleted {len(corrupted_docs)} corrupted questions.")
            
            try:
                db.collection('metadata').document('question_bank').set({
                    'version': firestore.Increment(1),
                    'lastUpdated': firestore.SERVER_TIMESTAMP
                }, merge=True)
                print("✅ Updated /metadata/question_bank version increment.")
            except Exception as e:
                print(f"⚠️ Warning: Could not update metadata version - {e}")
        else:
            print("\nDry run completed. Run with '--execute' to perform the deletion.")

if __name__ == "__main__":
    execute = len(sys.argv) > 1 and sys.argv[1] == "--execute"
    cleanup(dry_run=not execute)
