import firebase_admin
from firebase_admin import credentials, firestore
import json
import os
import sys
import hashlib

# Configure stdout encoding to utf-8
try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# SETUP: Rename your service account key to 'serviceAccountKey.json' in this folder
SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')

if not os.path.exists(SERVICE_ACCOUNT_PATH):
    print(f"ERROR: {SERVICE_ACCOUNT_PATH} not found!")
    exit(1)

if not firebase_admin._apps:
    cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
    firebase_admin.initialize_app(cred)
db = firestore.client()

OFFICIAL_CHAPTERS = {
    "Physics": [
        "Mathematics In Physics", "Units, Dimensions And Measurement",
        "Motion In One Dimension", "Motion In Two Dimension",
        "Newton's Laws Of Motion", "Friction",
        "Work, Energy, Power And Collision", "Rotational Motion",
        "Gravitation", "Simple Harmonic Motion",
        "Elasticity", "Fluid Mechanics",
        "Thermal Physics", "Kinetic Theory Of Gases",
        "Thermodynamics", "Wave Motion",
        "Electrostatics", "Current Electricity",
        "Magnetic Effect Of Current", "Electromagnetic Induction",
        "Optics", "Modern Physics"
    ],
    "Chemistry": [
        "Some Basic Concepts Of Chemistry", "Structure Of Atom",
        "Classification Of Elements", "Chemical Bonding",
        "States Of Matter", "Thermodynamics",
        "Equilibrium", "Redox Reactions",
        "Hydrogen", "S-Block Elements",
        "P-Block Elements", "Organic Chemistry Basics",
        "Hydrocarbons", "Environmental Chemistry",
        "Solid State", "Solutions",
        "Electrochemistry", "Chemical Kinetics",
        "Surface Chemistry", "Coordination Compounds",
        "Aldehydes, Ketones And Carboxylic Acids", "Biomolecules"
    ],
    "Maths": [
        "Sets, Relations, And Functions", "Complex Numbers & Quadratic Equations",
        "Matrices & Determinants", "Permutations And Combinations",
        "Binomial Theorem", "Sequence & Series",
        "Limit, Continuity & Differentiability", "Integral Calculus",
        "Coordinate Geometry", "Three Dimensional Geometry",
        "Vector Algebra", "Probability",
        "Trigonometry", "Mathematical Reasoning",
        "Statistics"
    ],
    "Biology": [
        "Cell Biology", "Genetics",
        "Evolution", "Human Physiology",
        "Plant Physiology", "Reproduction",
        "Ecology", "Biomolecules",
        "Microbes In Human Welfare", "Biotechnology",
        "Animal Kingdom", "Plant Kingdom",
        "Morphology Of Flowering Plants", "Anatomy Of Flowering Plants",
        "Structural Organisation In Animals"
    ]
}

def get_pack_id(exam, subject):
    ex = exam.upper()
    subj = subject.capitalize()
    if ex == "JEE" and subj == "Physics": return "jee_physics_pack"
    if ex == "JEE" and subj == "Chemistry": return "jee_chemistry_pack"
    if ex == "JEE" and (subj == "Maths" or subj == "Mathematics"): return "jee_maths_pack"
    if ex == "NEET" and (subj == "Biology" or subj == "Bio"): return "neet_biology_pack"
    if ex == "NEET" and subj == "Chemistry": return "neet_chemistry_pack"
    if ex == "NEET" and subj == "Physics": return "neetphysicspack"
    return "allaccessyearly"

def generate_id(q):
    content = f"{q['examType']}_{q['subject']}_{q['questionText']}"
    return "q_" + hashlib.md5(content.encode('utf-8')).hexdigest()

def is_valid_question(q):
    import re
    text = q.get('questionText', '').strip()
    options = q.get('options', [])
    
    # 1. Extremely short question text
    if not text or len(text) < 8:
        return False, "Extremely short text"
        
    # 2. Placeholder options
    placeholder_options = ["option a", "option b", "option c", "option d"]
    if len(options) != 4:
        return False, f"Invalid options count: {len(options)}"
    if all(o.lower().strip() == p for o, p in zip(options, placeholder_options)):
        return False, "Placeholder options"
        
    # 3. Text patterns indicating parsing failure
    bad_patterns = [
        r'refer to standard textbooks',
        r'Note:\s*For SHORT ANSWER',
        r'Master Practice Workbook',
        r'Click here to download',
        r'Questions with Answer Keys',
        r'Solutions\s*JEE Main',
        r'^\s*:\s*[A-D]\s*$',
        r'^\s*:\s*[A-D]\s*Note:',
    ]
    for pattern in bad_patterns:
        if re.search(pattern, text, re.IGNORECASE):
            return False, f"Matched bad pattern: {pattern}"
            
    return True, ""

def upload_folder(root_dir):
    print(f"🚀 Starting Bulk Import from: {root_dir}")
    questions_ref = db.collection('questions')
    total_count = 0
    skipped_count = 0
    
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.json'):
                file_path = os.path.join(root, file)
                print(f"📄 Processing: {file_path}")
                
                with open(file_path, 'r', encoding='utf-8') as f:
                    try:
                        questions = json.load(f)
                    except Exception as e:
                        print(f"❌ Skipped {file}: Invalid JSON - {e}")
                        continue
                
                batch = db.batch()
                batch_count = 0
                imported_from_file = 0
                
                for idx, q in enumerate(questions):
                    subj = q.get('subject', 'Physics')
                    exam = q.get('examType', 'JEE')
                    
                    # Validate question before importing
                    valid, reason = is_valid_question(q)
                    if not valid:
                        print(f"  ⚠️ Skipped question #{idx+1} in {file}: {reason} ('{q.get('questionText', '')[:50]}...')")
                        skipped_count += 1
                        continue
                        
                    # Ensure defaults and attach correct packId
                    if 'isPremium' not in q: q['isPremium'] = True
                    if 'isDailyVault' not in q: q['isDailyVault'] = False
                    q['packId'] = get_pack_id(exam, subj)
                    
                    # Map to official chapters or distribute if generic/missing
                    chaps = OFFICIAL_CHAPTERS.get(subj, ["General"])
                    chap = q.get('chapter', '').strip()
                    if chap.lower() in [c.lower() for c in chaps]:
                        matched_chap = [c for c in chaps if c.lower() == chap.lower()][0]
                        q['chapter'] = matched_chap
                    else:
                        h = int(hashlib.md5(q.get('questionText', '').encode('utf-8')).hexdigest(), 16)
                        q['chapter'] = chaps[h % len(chaps)]
                    
                    doc_id = generate_id(q)
                    doc_ref = questions_ref.document(doc_id)
                    
                    batch.set(doc_ref, q)
                    batch_count += 1
                    total_count += 1
                    imported_from_file += 1
                    
                    if batch_count >= 500:
                        batch.commit()
                        batch = db.batch()
                        batch_count = 0
                
                if batch_count > 0:
                    batch.commit()
                
                print(f"✅ Imported {imported_from_file} questions from {file} (skipped {len(questions) - imported_from_file} invalid)")


    # Also update metadata version so app triggers background sync
    try:
        db.collection('metadata').document('question_bank').set({
            'version': firestore.Increment(1),
            'lastUpdated': firestore.SERVER_TIMESTAMP
        }, merge=True)
        print("✅ Updated /metadata/question_bank version increment.")
    except Exception as e:
        print(f"⚠️ Warning: Could not update metadata version - {e}")

    print(f"\n🎉 ALL DONE! Total questions imported: {total_count}")

if __name__ == "__main__":
    content_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'content'))
    upload_folder(content_dir)
