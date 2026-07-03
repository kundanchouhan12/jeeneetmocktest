import os
import sys
import re
import json
import PyPDF2

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

def sanitize_filename(name):
    # Remove unwanted characters for clean JSON filenames
    s = re.sub(r'[^\w\s-]', '', name).strip().lower()
    return re.sub(r'[-\s]+', '_', s) if s else "general"

def main():
    pdf_path = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'content', 'JEE_NEET_Practice_500_Questions.pdf'))
    if not os.path.exists(pdf_path):
        print(f"❌ Error: Could not find {pdf_path}")
        return

    print("📖 Reading PDF pages...")
    full_text = ""
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        for page in reader.pages:
            full_text += page.extract_text() + "\n"

    print("🔍 Parsing Answer Keys from back of workbook...")
    answer_keys = {}
    # Match patterns like Q.1: B or Q.123: A
    matches = re.findall(r'Q\.(\d+):\s*([A-D])', full_text)
    for q_num_str, opt_char in matches:
        q_num = int(q_num_str)
        answer_keys[q_num] = ord(opt_char.upper()) - ord('A')
    print(f"✅ Found {len(answer_keys)} answer keys.")

    print("🔍 Extracting questions and categorising...")
    lines = full_text.split('\n')
    
    current_subject = "Physics"
    current_chapter = "General"
    questions_list = []
    
    # State tracking
    q_state = None  # None, "Q_TEXT", "OPT_A", "OPT_B", "OPT_C", "OPT_D"
    current_q = None
    
    # Regex helpers
    subject_regex = re.compile(r'(\u25a0|\u25aa|\u2022|\?|#)\s*(PHYSICS|CHEMISTRY|MATHEMATICS|BIOLOGY)', re.IGNORECASE)
    header_regex  = re.compile(r'(\u25a0|\u25aa|\u2022|\?|#)\s*([A-Za-z0-9 ]+)')
    q_num_regex   = re.compile(r'^Q\.(\d+)')
    opt_a_regex   = re.compile(r'^\(A\)\s*(.*)')
    opt_b_regex   = re.compile(r'^\(B\)\s*(.*)')
    opt_c_regex   = re.compile(r'^\(C\)\s*(.*)')
    opt_d_regex   = re.compile(r'^\(D\)\s*(.*)')
    
    for line in lines:
        line_str = line.strip()
        if not line_str:
            continue
            
        # Check for Subject Header
        subj_match = subject_regex.search(line_str)
        if subj_match:
            current_subject = subj_match.group(2).capitalize()
            if current_subject == "Mathematics": current_subject = "Maths"
            current_chapter = "General"
            continue
            
        # Check for Chapter Header (Bullet followed by text but not Q.)
        if ('\u25a0' in line_str or '\u25aa' in line_str or '■' in line_str) and not line_str.startswith("Q."):
            clean_chap = re.sub(r'[\u25a0\u25aa■]', '', line_str).strip()
            if clean_chap and clean_chap.lower() not in ["physics", "chemistry", "mathematics", "biology", "maths"]:
                current_chapter = clean_chap
                continue
                
        # Check for Question Start: Q.123
        q_match = q_num_regex.match(line_str)
        if q_match:
            # Save previous question if finished
            if current_q:
                questions_list.append(current_q)
                
            q_num = int(q_match.group(1))
            current_q = {
                "q_num": q_num,
                "subject": current_subject,
                "chapter": current_chapter,
                "questionText": "",
                "options": ["Option A", "Option B", "Option C", "Option D"],
                "correctOptionIndex": answer_keys.get(q_num, 0),
                "explanation": f"Official answer key option {chr(65 + answer_keys.get(q_num, 0))}."
            }
            q_state = "Q_TEXT"
            # If there's text on the same line after Q.123
            rem = line_str[q_match.end():].strip()
            if rem.upper() in ["MCQ", "SHORT", "NUMERICAL"]:
                rem = ""
            if rem:
                current_q["questionText"] += rem + " "
            continue
            
        if current_q:
            # If line is just "MCQ" or "SHORT", skip
            if line_str.upper() in ["MCQ", "SHORT", "NUMERICAL"]:
                continue
                
            # Check Options
            m_a = opt_a_regex.match(line_str)
            if m_a: current_q["options"][0] = m_a.group(1); q_state = "OPT_A"; continue
            m_b = opt_b_regex.match(line_str)
            if m_b: current_q["options"][1] = m_b.group(1); q_state = "OPT_B"; continue
            m_c = opt_c_regex.match(line_str)
            if m_c: current_q["options"][2] = m_c.group(1); q_state = "OPT_C"; continue
            m_d = opt_d_regex.match(line_str)
            if m_d: current_q["options"][3] = m_d.group(1); q_state = "OPT_D"; continue
            
            # If in Q_TEXT state, append to question
            if q_state == "Q_TEXT":
                current_q["questionText"] += line_str + " "
            elif q_state == "OPT_A":
                current_q["options"][0] += " " + line_str
            elif q_state == "OPT_B":
                current_q["options"][1] += " " + line_str
            elif q_state == "OPT_C":
                current_q["options"][2] += " " + line_str
            elif q_state == "OPT_D":
                current_q["options"][3] += " " + line_str

    if current_q:
        questions_list.append(current_q)
        
    print(f"✅ Extracted {len(questions_list)} questions total.")
    
    # Save into content folders
    # Exam rules: Maths -> JEE, Biology -> NEET, Physics/Chem -> Both
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'content'))
    
    files_created = 0
    total_written = 0
    
    # Group by (exam, subject, chapter)
    grouped = {}
    for q in questions_list:
        subj = q["subject"]
        chap = q["chapter"]
        exams = []
        if subj == "Maths": exams = ["JEE"]
        elif subj == "Biology": exams = ["NEET"]
        else: exams = ["JEE", "NEET"]
        
        for ex in exams:
            key = (ex, subj, chap)
            if key not in grouped:
                grouped[key] = []
            
            # Build clean Question object
            grouped[key].append({
                "examType": ex,
                "subject": subj,
                "chapter": chap,
                "difficulty": "Medium",
                "year": 2024,
                "questionText": q["questionText"].strip(),
                "options": [o.strip() for o in q["options"]],
                "correctOptionIndex": q["correctOptionIndex"],
                "explanation": q["explanation"],
                "isPremium": True,
                "isDailyVault": False
            })

    for (ex, subj, chap), q_list in grouped.items():
        fname = sanitize_filename(chap)
        target_dir = os.path.join(base_dir, ex.lower(), subj.lower())
        os.makedirs(target_dir, exist_ok=True)
        file_path = os.path.join(target_dir, f"{fname}.json")
        
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(q_list, f, indent=2)
            
        files_created += 1
        total_written += len(q_list)
        print(f"📁 Created {ex}/{subj}/{fname}.json ({len(q_list)} Qs)")

    print(f"\n🎉 SUCCESS! Created {files_created} JSON files containing {total_written} question entries ready for import.")

if __name__ == "__main__":
    main()
