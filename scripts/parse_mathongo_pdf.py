import os
import sys
import re
import json
import time
import requests
import PyPDF2

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

API_KEY = "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"
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

# Mapping from MathonGo PDF filename prefixes to official app chapters
CHAPTER_MAPPING = {
    "Maths": {
        "matrices": "Matrices & Determinants",
        "determinants": "Matrices & Determinants",
        "application_of_derivatives": "Limit, Continuity & Differentiability",
        "definite_integration": "Integral Calculus",
        "indefinite_integration": "Integral Calculus",
        "differential_equations": "Integral Calculus",
        "area_under_curves": "Integral Calculus",
        "sequences_and_series": "Sequence & Series",
        "sequence_and_series": "Sequence & Series",
        "straight_lines": "Coordinate Geometry",
        "circle": "Coordinate Geometry",
        "parabola": "Coordinate Geometry",
        "ellipse": "Coordinate Geometry",
        "hyperbola": "Coordinate Geometry",
        "vector_algebra": "Vector Algebra",
        "three_dimensional_geometry": "Three Dimensional Geometry",
        "trigonometric_equations": "Trigonometry",
        "trigonometric_ratios": "Trigonometry",
        "trigonometry": "Trigonometry",
        "inverse_trigonometric": "Trigonometry",
        "complex_number": "Complex Numbers & Quadratic Equations",
        "quadratic_equation": "Complex Numbers & Quadratic Equations",
        "permutation_combination": "Permutations And Combinations",
        "binomial_theorem": "Binomial Theorem",
        "probability": "Probability",
        "statistics": "Statistics",
        "limits": "Limit, Continuity & Differentiability",
        "continuity_and_differentiability": "Limit, Continuity & Differentiability",
        "differentiation": "Limit, Continuity & Differentiability",
        "functions": "Sets, Relations, And Functions"
    },
    "Physics": {
        "current_electricity": "Current Electricity",
        "electrostatics": "Electrostatics",
        "capacitance": "Electrostatics",
        "alternating_current": "Electromagnetic Induction",
        "electromagnetic_induction": "Electromagnetic Induction",
        "electromagnetic_waves": "Electromagnetic Induction",
        "magnetic_effects_of_current": "Magnetic Effect Of Current",
        "magnetic_properties_of_matter": "Magnetic Effect Of Current",
        "ray_optics": "Optics",
        "wave_optics": "Optics",
        "atomic_physics": "Modern Physics",
        "nuclear_physics": "Modern Physics",
        "dual_nature_of_matter": "Modern Physics",
        "semiconductors": "Modern Physics",
        "motion_in_one_dimension": "Motion In One Dimension",
        "motion_in_two_dimensions": "Motion In Two Dimension",
        "laws_of_motion": "Newton's Laws Of Motion",
        "work_power_energy": "Work, Energy, Power And Collision",
        "center_of_mass_momentum_and_collision": "Work, Energy, Power And Collision",
        "rotational_motion": "Rotational Motion",
        "gravitation": "Gravitation",
        "oscillations": "Simple Harmonic Motion",
        "mechanical_properties_of_solids": "Elasticity",
        "mechanical_properties_of_fluids": "Fluid Mechanics",
        "thermal_properties_of_matter": "Thermal Physics",
        "kinetic_theory_of_gases": "Kinetic Theory Of Gases",
        "thermodynamics": "Thermodynamics",
        "waves_and_sound": "Wave Motion",
        "units_and_dimensions": "Units, Dimensions And Measurement",
        "mathematics_in_physics": "Mathematics In Physics"
    },
    "Chemistry": {
        "some_basic_concepts": "Some Basic Concepts Of Chemistry",
        "structure_of_atom": "Structure Of Atom",
        "classification_of_elements": "Classification Of Elements",
        "chemical_bonding": "Chemical Bonding",
        "states_of_matter": "States Of Matter",
        "thermodynamics": "Thermodynamics",
        "equilibrium": "Equilibrium",
        "redox_reactions": "Redox Reactions",
        "hydrogen": "Hydrogen",
        "s-block": "S-Block Elements",
        "p-block": "P-Block Elements",
        "organic_chemistry_basics": "Organic Chemistry Basics",
        "hydrocarbons": "Hydrocarbons",
        "environmental_chemistry": "Environmental Chemistry",
        "solid_state": "Solid State",
        "solutions": "Solutions",
        "electrochemistry": "Electrochemistry",
        "chemical_kinetics": "Chemical Kinetics",
        "surface_chemistry": "Surface Chemistry",
        "coordination_compounds": "Coordination Compounds",
        "aldehydes_ketones": "Aldehydes, Ketones And Carboxylic Acids",
        "biomolecules": "Biomolecules"
    }
}

PARSER_SYSTEM_PROMPT = """
You are an expert JEE/NEET exam content parser.
Your task is to take a raw question text, its corresponding answer value from the Answer Key, and its step-by-step solution from the Solutions section, and format it into a single, valid JSON object.

Output JSON format:
{
  "examType": "JEE",
  "subject": "[Subject name]",
  "chapter": "[Chapter name]",
  "difficulty": "Medium",
  "year": [Year, e.g. 2024],
  "questionText": "[Cleaned question text. Convert all math symbols, matrices, equations, and chemical formulas into clean, readable standard LaTeX wrapped in $...$ for inline or $$...$$ for block math. Example: A=[1 0; 0 1] should be $A=\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}$]",
  "options": [
     "Option A text (or correct numerical answer)",
     "Option B text",
     "Option C text",
     "Option D text"
  ],
  "correctOptionIndex": [0-3 integer corresponding to the correct option],
  "explanation": "[Clean step-by-step explanation. Format all formulas in LaTeX. Keep the explanation clear and structured]"
}

Strictest Rules:
1. Multiple Choice Questions (MCQ):
   - Extract the 4 options from the question text (e.g. labeled (1)-(4) or (A)-(D)).
   - Clean the options text (converting math to LaTeX).
   - Find which option is correct by looking at the Answer Key value (which will be 1, 2, 3, or 4).
   - Map it to a 0-indexed integer (0 for 1/A, 1 for 2/B, 2 for 3/C, 3 for 4/D) in "correctOptionIndex".
2. Numerical / Short Answer Questions:
   - If the question does NOT have options (1)-(4) in its text, it is a numerical question.
   - You MUST generate 4 options. One option must be the correct numerical value from the Answer Key. The other 3 options must be realistic mathematical distractor values (e.g., if the answer is 10, generate [ "10", "5", "15", "20" ]).
   - Set "correctOptionIndex" to the index of the correct value in your generated options.
3. Keep all LaTeX syntax clean. Use double backslashes in JSON strings (e.g., \\sqrt{x} or \\begin{matrix}).
4. Do not output any commentary, markdown wrappers, or extra text. Output ONLY the JSON object.
"""

def get_official_chapter(filename, subject):
    base = os.path.splitext(os.path.basename(filename))[0].lower()
    mapping = CHAPTER_MAPPING.get(subject, {})
    for key, val in mapping.items():
        if key in base:
            return val
    return "General"

def extract_pdf_sections(pdf_path):
    print(f"📖 Extracting text from PDF: {pdf_path}")
    questions_text = ""
    answer_key_text = ""
    solutions_text = ""
    
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        total_pages = len(reader.pages)
        
        # Step 1: Find the Answer Key page
        answer_key_page_idx = -1
        for i in range(total_pages):
            page_text = reader.pages[i].extract_text()
            if "answer key" in page_text.lower() or "answer  key" in page_text.lower():
                answer_key_page_idx = i
                break
                
        if answer_key_page_idx == -1:
            print("⚠️ Warning: Could not find Answer Key page! Assuming no solutions.")
            answer_key_page_idx = total_pages  # Assume everything is questions
            
        # Step 2: Extract text based on sections
        for i in range(total_pages):
            page_text = reader.pages[i].extract_text() + "\n"
            if i < answer_key_page_idx:
                questions_text += page_text
            elif i == answer_key_page_idx:
                answer_key_text += page_text
            else:
                solutions_text += page_text
                
    return questions_text, answer_key_text, solutions_text

def parse_answer_key(answer_key_text):
    # Match patterns like Q1 (2) or Q12 (10)
    # The format is Q<num> (<val>)
    answers = {}
    matches = re.findall(r'Q(\d+)\s*\(([^)]+)\)', answer_key_text)
    for q_num_str, val in matches:
        answers[int(q_num_str)] = val.strip()
    return answers

def split_questions(questions_text):
    # Splits the questions text by "Q<num> -"
    blocks = re.split(r'\n(Q\d+)\s*-', questions_text)
    q_map = {}
    
    current_q_num = None
    for b in blocks:
        if not b.strip():
            continue
        # If it matches Q\d+, it's a question number header
        if re.match(r'^Q\d+$', b.strip()):
            current_q_num = int(b.strip()[1:])
        elif current_q_num is not None:
            q_map[current_q_num] = b.strip()
            
    return q_map

def split_solutions(solutions_text):
    # Splits the solutions text by "Q<num>" (on a line by itself or beginning of paragraph)
    blocks = re.split(r'\n(Q\d+)\s*\n', solutions_text)
    sol_map = {}
    
    current_q_num = None
    for b in blocks:
        if not b.strip():
            continue
        if re.match(r'^Q\d+$', b.strip()):
            current_q_num = int(b.strip()[1:])
        elif current_q_num is not None:
            sol_map[current_q_num] = b.strip()
            
    return sol_map

def call_groq_api(prompt_content):
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    
    data = {
        "model": "llama-3.3-70b-versatile",
        "messages": [
            {"role": "system", "content": PARSER_SYSTEM_PROMPT},
            {"role": "user", "content": prompt_content}
        ],
        "temperature": 0.1,
        "max_tokens": 1024
    }
    
    for attempt in range(3):
        try:
            r = requests.post(GROQ_URL, headers=headers, json=data, timeout=30)
            if r.status_code == 200:
                response_json = r.json()
                return response_json['choices'][0]['message']['content'].strip()
            elif r.status_code == 429:
                print("  ⚠️ Rate limit hit. Waiting 5 seconds...")
                time.sleep(5)
            else:
                print(f"  ❌ Groq Error {r.status_code}: {r.text}")
                time.sleep(2)
        except Exception as e:
            print(f"  ❌ Connection error: {e}")
            time.sleep(2)
            
    return None

def process_pdf(pdf_path, subject, year=2024):
    chapter_name = get_official_chapter(pdf_path, subject)
    questions_text, answer_key_text, solutions_text = extract_pdf_sections(pdf_path)
    
    answers = parse_answer_key(answer_key_text)
    q_map = split_questions(questions_text)
    sol_map = split_solutions(solutions_text)
    
    print(f"📊 Found {len(q_map)} questions in text, {len(answers)} answer keys, {len(sol_map)} solutions.")
    
    parsed_questions = []
    
    # Iterate through each question we found in the answer key
    for q_num in sorted(answers.keys()):
        raw_q = q_map.get(q_num)
        raw_sol = sol_map.get(q_num, "Not available.")
        ans_val = answers[q_num]
        
        if not raw_q:
            print(f"  ⚠️ Skipping Q{q_num}: Question text not found in PDF extraction.")
            continue
            
        print(f"⌛ Parsing Q{q_num} of {len(answers)} via Groq...")
        
        # Build prompt
        prompt = f"""--- QUESTION ---
Q{q_num} - {raw_q}

--- ANSWER KEY ENTRY ---
Answer for Q{q_num} is: ({ans_val})

--- SOLUTION ---
{raw_sol}
"""
        
        response_text = call_groq_api(prompt)
        if response_text:
            try:
                # Clean up any potential markdown wraps
                clean_json_str = re.sub(r'^```json\s*|\s*```$', '', response_text).strip()
                q_obj = json.loads(clean_json_str)
                
                # Fill in metadata
                q_obj["subject"] = subject
                q_obj["chapter"] = chapter_name
                q_obj["year"] = year
                q_obj["examType"] = "JEE"
                q_obj["isPremium"] = True
                q_obj["isDailyVault"] = False
                
                parsed_questions.append(q_obj)
                print(f"  ✅ Q{q_num} parsed successfully.")
            except Exception as e:
                print(f"  ❌ Error parsing JSON response for Q{q_num}: {e}")
                print(f"  Raw response was: {response_text[:300]}...")
        else:
            print(f"  ❌ Failed to parse Q{q_num} (Groq API error).")
            
        time.sleep(1.5) # Avoid rate limits
        
    return parsed_questions

def main():
    if len(sys.argv) < 3:
        print("Usage: py parse_mathongo_pdf.py <pdf_path> <subject> [year]")
        print("Example: py parse_mathongo_pdf.py D:\\JEE_PDFs\\Maths\\January_2024\\Matrices.pdf Maths 2024")
        return
        
    pdf_path = sys.argv[1]
    subject = sys.argv[2]
    year = int(sys.argv[3]) if len(sys.argv) > 3 else 2024
    
    if not os.path.exists(pdf_path):
        print(f"❌ Error: File not found: {pdf_path}")
        return
        
    questions = process_pdf(pdf_path, subject, year)
    
    if not questions:
        print("❌ No questions were successfully parsed.")
        return
        
    # Generate output file path
    base_name = os.path.splitext(os.path.basename(pdf_path))[0].lower()
    output_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'content', 'jee', subject.lower()))
    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, f"{base_name}.json")
    
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(questions, f, indent=2)
        
    print(f"\n🎉 SUCCESS! Created JSON question bank: {output_path} ({len(questions)} questions)")

if __name__ == "__main__":
    main()
