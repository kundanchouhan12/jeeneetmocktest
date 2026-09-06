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

def count_questions():
    print("🔍 Querying Firestore for question statistics... (Using optimized aggregations)")
    
    questions_ref = db.collection('questions')
    
    # 1. Get Total Questions Count (Uses cheap .count() aggregation)
    total_count = questions_ref.count().get()[0][0].value
    
    # 2. Get Breakdown by Exam Type
    jee_count = questions_ref.where('examType', '==', 'JEE').count().get()[0][0].value
    neet_count = questions_ref.where('examType', '==', 'NEET').count().get()[0][0].value
    
    # 3. Get Breakdown by Subject
    physics_count = questions_ref.where('subject', '==', 'Physics').count().get()[0][0].value
    chemistry_count = questions_ref.where('subject', '==', 'Chemistry').count().get()[0][0].value
    maths_count = questions_ref.where('subject', '==', 'Maths').count().get()[0][0].value
    biology_count = questions_ref.where('subject', '==', 'Biology').count().get()[0][0].value
    
    # 4. Get Breakdown by Vault vs Premium
    vault_count = questions_ref.where('isDailyVault', '==', True).count().get()[0][0].value
    premium_count = questions_ref.where('isDailyVault', '==', False).count().get()[0][0].value
    
    print("\n=============================================")
    print("📊 FIRESTORE QUESTION BANK STATISTICS")
    print("=============================================")
    print(f"📈 Total Documents in DB   : {total_count}")
    print("---------------------------------------------")
    print("⚡ BY EXAM TYPE:")
    print(f"   • JEE Questions         : {jee_count}")
    print(f"   • NEET Questions        : {neet_count}")
    print("---------------------------------------------")
    print("📚 BY SUBJECT:")
    print(f"   • Physics               : {physics_count}")
    print(f"   • Chemistry             : {chemistry_count}")
    print(f"   • Mathematics           : {maths_count}")
    print(f"   • Biology               : {biology_count}")
    print("---------------------------------------------")
    print("🛡️ BY TYPE:")
    print(f"   • Daily Vault Entries   : {vault_count}")
    print(f"   • Premium Chapter PYQs  : {premium_count}")
    print("=============================================\n")

if __name__ == "__main__":
    try:
        count_questions()
    except Exception as e:
        if "Quota exceeded" in str(e) or "429" in str(e):
            print("\n⚠️ Firestore Daily Free Tier Quota Exceeded for Today.")
            print("   (This occurs after heavy cloud imports/scans. Quota resets automatically at Midnight UTC.)")
        else:
            print(f"\n⚠️ Error fetching stats: {e}")
