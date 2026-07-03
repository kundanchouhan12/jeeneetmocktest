r"""
pdf_extractor.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Stage 1 of the two-stage pipeline: PDF → JSON text files.

No API calls. No tokens consumed. Runs completely offline.
Applies all noise filtering, section detection, duplicate
detection, and question splitting — same logic as the main
pipeline. Output JSON files are inspectable before AI runs.

Usage:
    py scripts/pdf_extractor.py --all               # extract all subjects
    py scripts/pdf_extractor.py --subject Maths      # one subject
    py scripts/pdf_extractor.py --subject Physics
    py scripts/pdf_extractor.py --subject Chemistry
    py scripts/pdf_extractor.py --reset              # clear progress, re-extract
    py scripts/pdf_extractor.py --stats              # show extraction summary

Output:
    scripts/extracted/{Subject}/{pdf_stem}.json  — one file per PDF

Each JSON file contains:
    {
      "pdf_path":  "...",
      "pdf_name":  "Probability.pdf",
      "subject":   "Maths",
      "chapter":   "Probability",
      "year":      2024,
      "extracted_at": "...",
      "questions": [
        {
          "q_num":      1,
          "raw_text":   "A bag contains 8 balls ...",
          "answer_key": "3",
          "solution":   "Given that ..."
        }, ...
      ],
      "stats": { "q_blocks": 11, "answers": 11, "solutions": 10, "exported": 10 }
    }
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import os
import sys
import re
import json
import time
import argparse

try:
    import PyPDF2
except ImportError:
    print("❌ PyPDF2 not installed. Run: pip install PyPDF2")
    sys.exit(1)

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# ─── Config ───────────────────────────────────────────────────────────────────

SCRIPT_DIR    = os.path.dirname(os.path.abspath(__file__))
PDF_ROOT      = r"D:\JEE_PDFs"
EXTRACT_DIR   = os.path.join(SCRIPT_DIR, "extracted")
PROGRESS_FILE = os.path.join(SCRIPT_DIR, "extract_progress.json")

# ─── Chapter Mapping (same as pipeline) ───────────────────────────────────────

CHAPTER_MAP = {
    "Maths": {
        "matrices":                          "Matrices & Determinants",
        "determinants":                      "Matrices & Determinants",
        "complex_number":                    "Complex Numbers & Quadratic Equations",
        "complex number":                    "Complex Numbers & Quadratic Equations",
        "quadratic":                         "Complex Numbers & Quadratic Equations",
        "permutation":                       "Permutations And Combinations",
        "combination":                       "Permutations And Combinations",
        "binomial":                          "Binomial Theorem",
        "sequence":                          "Sequence & Series",
        "series":                            "Sequence & Series",
        "progression":                       "Sequence & Series",
        "limit":                             "Limit, Continuity & Differentiability",
        "continuity":                        "Limit, Continuity & Differentiability",
        "differentiability":                 "Limit, Continuity & Differentiability",
        "differentiation":                   "Limit, Continuity & Differentiability",
        "application_of_derivatives":        "Limit, Continuity & Differentiability",
        "application of derivatives":        "Limit, Continuity & Differentiability",
        "application_of_derivative":         "Limit, Continuity & Differentiability",
        "indefinite_integration":            "Integral Calculus",
        "definite_integration":              "Integral Calculus",
        "definite integration":              "Integral Calculus",
        "integration":                       "Integral Calculus",
        "area_under":                        "Integral Calculus",
        "area under":                        "Integral Calculus",
        "area":                              "Integral Calculus",
        "differential_equation":             "Integral Calculus",
        "differential equation":             "Integral Calculus",
        "straight_line":                     "Coordinate Geometry",
        "straight line":                     "Coordinate Geometry",
        "straight_lines":                    "Coordinate Geometry",
        "point_and_straight":                "Coordinate Geometry",
        "pair_of_line":                      "Coordinate Geometry",
        "pair of line":                      "Coordinate Geometry",
        "circle":                            "Coordinate Geometry",
        "parabola":                          "Coordinate Geometry",
        "ellipse":                           "Coordinate Geometry",
        "hyperbola":                         "Coordinate Geometry",
        "conic":                             "Coordinate Geometry",
        "coordinate":                        "Coordinate Geometry",
        "vector_algebra":                    "Vector Algebra",
        "vector algebra":                    "Vector Algebra",
        "three_dimensional":                 "Three Dimensional Geometry",
        "three dimensional":                 "Three Dimensional Geometry",
        "3d_geometry":                       "Three Dimensional Geometry",
        "vector_3d":                         "Three Dimensional Geometry",
        "trigonometric_equation":            "Trigonometry",
        "trigonometrical_equation":          "Trigonometry",
        "trigonometrical_ratio":             "Trigonometry",
        "trigonometric_ratio":               "Trigonometry",
        "trigonometric ratios":              "Trigonometry",
        "inverse_trigonometric":             "Trigonometry",
        "trigonometry":                      "Trigonometry",
        "properties_of_triangle":            "Trigonometry",
        "properties of triangle":            "Trigonometry",
        "height_and_distance":               "Trigonometry",
        "probability":                       "Probability",
        "statistics":                        "Statistics",
        "sets_and_relation":                 "Sets, Relations, And Functions",
        "set_and_relation":                  "Sets, Relations, And Functions",
        "sets":                              "Sets, Relations, And Functions",
        "relation":                          "Sets, Relations, And Functions",
        "function":                          "Sets, Relations, And Functions",
        "mathematical_reasoning":            "Mathematical Reasoning",
        "mathematical reasoning":            "Mathematical Reasoning",
        "logic":                             "Mathematical Reasoning",
    },
    "Physics": {
        "units_and_dimension":               "Units, Dimensions And Measurement",
        "units_and_measurement":             "Units, Dimensions And Measurement",
        "unit_and_dimension":                "Units, Dimensions And Measurement",
        "unit":                              "Units, Dimensions And Measurement",
        "dimension":                         "Units, Dimensions And Measurement",
        "measurement":                       "Units, Dimensions And Measurement",
        "motion_in_one":                     "Motion In One Dimension",
        "motion in one":                     "Motion In One Dimension",
        "kinematics_1d":                     "Motion In One Dimension",
        "motion_in_two":                     "Motion In Two Dimension",
        "motion in two":                     "Motion In Two Dimension",
        "kinematics_2d":                     "Motion In Two Dimension",
        "projectile":                        "Motion In Two Dimension",
        "laws_of_motion":                    "Newton's Laws Of Motion",
        "laws of motion":                    "Newton's Laws Of Motion",
        "newton":                            "Newton's Laws Of Motion",
        "friction":                          "Friction",
        "work_power_and_energy":             "Work, Energy, Power And Collision",
        "work_power_energy":                 "Work, Energy, Power And Collision",
        "work power energy":                 "Work, Energy, Power And Collision",
        "collision":                         "Work, Energy, Power And Collision",
        "center_of_mass":                    "Work, Energy, Power And Collision",
        "centre_of_mass":                    "Work, Energy, Power And Collision",
        "center of mass":                    "Work, Energy, Power And Collision",
        "rotational":                        "Rotational Motion",
        "gravitation":                       "Gravitation",
        "oscillation":                       "Simple Harmonic Motion",
        "oscillations":                      "Simple Harmonic Motion",
        "shm":                               "Simple Harmonic Motion",
        "simple_harmonic":                   "Simple Harmonic Motion",
        "simple harmonic":                   "Simple Harmonic Motion",
        "elasticity":                        "Elasticity",
        "mechanical_properties_of_solid":    "Elasticity",
        "properties_of_solid":               "Elasticity",
        "fluid":                             "Fluid Mechanics",
        "mechanical_properties_of_fluid":    "Fluid Mechanics",
        "properties_of_liquid":              "Fluid Mechanics",
        "properties_of_matter":              "Fluid Mechanics",
        "properties of matter":              "Fluid Mechanics",
        "properties_of_solids_and_liquid":   "Fluid Mechanics",
        "thermal_properties":                "Thermal Physics",
        "thermal_property":                  "Thermal Physics",
        "thermal":                           "Thermal Physics",
        "heat":                              "Thermal Physics",
        "calorimetry":                       "Thermal Physics",
        "kinetic_theory":                    "Kinetic Theory Of Gases",
        "kinetic theory":                    "Kinetic Theory Of Gases",
        "thermodynamics":                    "Thermodynamics",
        "waves_and_sound":                   "Wave Motion",
        "wave_and_sound":                    "Wave Motion",
        "waves_and":                         "Wave Motion",
        "wave_mechanic":                     "Wave Motion",
        "wave":                              "Wave Motion",
        "sound":                             "Wave Motion",
        "electrostatics":                    "Electrostatics",
        "electrostatic":                     "Electrostatics",
        "capacitance":                       "Electrostatics",
        "electric_field":                    "Electrostatics",
        "current_electricity":               "Current Electricity",
        "current electricity":               "Current Electricity",
        "magnetic_effect":                   "Magnetic Effect Of Current",
        "magnetic effect":                   "Magnetic Effect Of Current",
        "magnetic_properties":               "Magnetic Effect Of Current",
        "magnetism":                         "Magnetic Effect Of Current",
        "electromagnetic_induction":         "Electromagnetic Induction",
        "electromagnetic induction":         "Electromagnetic Induction",
        "alternating_current":               "Electromagnetic Induction",
        "alternating current":               "Electromagnetic Induction",
        "electromagnetic_wave":              "Electromagnetic Induction",
        "ray_optic":                         "Optics",
        "ray optic":                         "Optics",
        "wave_optic":                        "Optics",
        "wave optic":                        "Optics",
        "optic":                             "Optics",
        "atomic_physic":                     "Modern Physics",
        "atomic_structure":                  "Modern Physics",
        "nuclear_physic":                    "Modern Physics",
        "nuclear physic":                    "Modern Physics",
        "dual_nature":                       "Modern Physics",
        "photoelectric":                     "Modern Physics",
        "semiconductor":                     "Modern Physics",
        "modern_physic":                     "Modern Physics",
        "circular":                          "Rotational Motion",
        "emi":                               "Electromagnetic Induction",
        "em_wave":                           "Electromagnetic Induction",
        "mathematics_in_physics":            "Mathematics In Physics",
    },
    "Chemistry": {
        "some_basic_concept":                "Some Basic Concepts Of Chemistry",
        "some basic concept":                "Some Basic Concepts Of Chemistry",
        "mole_concept":                      "Some Basic Concepts Of Chemistry",
        "mole concept":                      "Some Basic Concepts Of Chemistry",
        "qualitative_analysis":              "Some Basic Concepts Of Chemistry",
        "qualitative analysis":              "Some Basic Concepts Of Chemistry",
        "purification_and_characterization": "Some Basic Concepts Of Chemistry",
        "purification_of_organic":           "Organic Chemistry Basics",
        "practical_chemistry":               "Some Basic Concepts Of Chemistry",
        "structure_of_atom":                 "Structure Of Atom",
        "structure of atom":                 "Structure Of Atom",
        "atomic_structure":                  "Structure Of Atom",
        "classification_of_element":         "Classification Of Elements",
        "periodic_table":                    "Classification Of Elements",
        "periodic_propert":                  "Classification Of Elements",
        "periodic table":                    "Classification Of Elements",
        "periodic_and_periodicity":          "Classification Of Elements",
        "periodicity":                       "Classification Of Elements",
        "chemical_bonding":                  "Chemical Bonding",
        "chemical bonding":                  "Chemical Bonding",
        "molecular_orbital":                 "Chemical Bonding",
        "states_of_matter":                  "States Of Matter",
        "states of matter":                  "States Of Matter",
        "gaseous_state":                     "States Of Matter",
        "thermodynamics_(c)":                "Thermodynamics",
        "thermodynamics_c":                  "Thermodynamics",
        "thermodynamics":                    "Thermodynamics",
        "thermochemistry":                   "Thermodynamics",
        "equilibrium":                       "Equilibrium",
        "ionic_equilibrium":                 "Equilibrium",
        "redox":                             "Redox Reactions",
        "hydrogen":                          "Hydrogen",
        "s-block":                           "S-Block Elements",
        "s_block":                           "S-Block Elements",
        "the_s-block":                       "S-Block Elements",
        "the_s_block":                       "S-Block Elements",
        "p-block":                           "P-Block Elements",
        "p_block":                           "P-Block Elements",
        "the_p-block":                       "P-Block Elements",
        "the_p_block":                       "P-Block Elements",
        "organic_chemistry_basic":           "Organic Chemistry Basics",
        "organic chemistry basic":           "Organic Chemistry Basics",
        "general_organic":                   "Organic Chemistry Basics",
        "nomenclature":                      "Organic Chemistry Basics",
        "organic_compound_containing":       "Organic Chemistry Basics",
        "isomerism":                         "Organic Chemistry Basics",
        "hydrocarbon":                       "Hydrocarbons",
        "environmental":                     "Environmental Chemistry",
        "solid_state":                       "Solid State",
        "solid state":                       "Solid State",
        "solutions_and_colligative":         "Solutions",
        "solution":                          "Solutions",
        "electrochemistry":                  "Electrochemistry",
        "chemical_kinetics":                 "Chemical Kinetics",
        "chemical kinetics":                 "Chemical Kinetics",
        "surface_chemistry":                 "Surface Chemistry",
        "surface chemistry":                 "Surface Chemistry",
        "coordination":                      "Coordination Compounds",
        "d_and_f_block":                     "Coordination Compounds",
        "d and f block":                     "Coordination Compounds",
        "d-block":                           "Coordination Compounds",
        "f-block":                           "Coordination Compounds",
        "the_d_and_f":                       "Coordination Compounds",
        "aldehyde":                          "Aldehydes, Ketones And Carboxylic Acids",
        "ketone":                            "Aldehydes, Ketones And Carboxylic Acids",
        "carboxylic":                        "Aldehydes, Ketones And Carboxylic Acids",
        "biomolecule":                       "Biomolecules",
        "polymer":                           "Biomolecules",
        "alcohol":                           "Organic Chemistry Basics",
        "phenol":                            "Organic Chemistry Basics",
        "ether":                             "Organic Chemistry Basics",
        "amine":                             "Organic Chemistry Basics",
        "haloalkane":                        "Organic Chemistry Basics",
        "haloarene":                         "Organic Chemistry Basics",
        "aromatic":                          "Hydrocarbons",
    }
}

_SKIP_FILENAMES = {
    "mathematical_induction", "linear_programming", "basic_of_mathematics",
    "chemistry_in_everyday_life", "metallurgy",
    "general_principles_and_processes_of_isolation_of_metals",
    "communication_system", "communication_systems", "experimental_physics",
}

# ─── Noise Patterns (same as pipeline) ────────────────────────────────────────

NOISE_LINE_PATTERNS = [
    re.compile(r'questions?\s+with\s+answer\s+key', re.IGNORECASE),
    re.compile(r'questions?\s+with\s+solutions?', re.IGNORECASE),
    re.compile(r'mathongo', re.IGNORECASE),
    re.compile(r'click\s+here\s+to\s+download', re.IGNORECASE),
    re.compile(r'jee\s+main\s+\w+\s+\d{4}.*chapter', re.IGNORECASE),
    re.compile(r'^\s*page\s+\d+\s*$', re.IGNORECASE),
    re.compile(r'master\s+practice\s+workbook', re.IGNORECASE),
    re.compile(r'for\s+more\s+information', re.IGNORECASE),
    re.compile(r'^\s*www\.', re.IGNORECASE),
    re.compile(r'refer\s+to\s+standard\s+textbooks', re.IGNORECASE),
    re.compile(r'note:\s*for\s+short\s+answer', re.IGNORECASE),
]

QUESTION_BAD_PATTERNS = [
    re.compile(r'refer\s+to\s+standard\s+textbooks', re.IGNORECASE),
    re.compile(r'note:\s*for\s+short\s+answer', re.IGNORECASE),
    re.compile(r'master\s+practice\s+workbook', re.IGNORECASE),
    re.compile(r'click\s+here\s+to\s+download', re.IGNORECASE),
    re.compile(r'questions?\s+with\s+answer\s+key', re.IGNORECASE),
    re.compile(r'solutions?\s+jee\s+main', re.IGNORECASE),
    re.compile(r'^\s*:\s*[A-D1-4]\s*$', re.MULTILINE),
    re.compile(r':\s*[A-D1-4]\s+note:\s*for\s+short', re.IGNORECASE),
    re.compile(r':\s*[A-D1-4]\s+.*master\s+practice\s+workbook', re.IGNORECASE),
    re.compile(r':\s*[A-D1-4]\s+.*refer\s+to\s+standard', re.IGNORECASE),
]

_Q_PREFIX_STRIP = [
    re.compile(r'^numer\s*i\s*cal\s*:?\s*', re.IGNORECASE),
    re.compile(r'^integer\s+type\s*:?\s*', re.IGNORECASE),
    re.compile(r'^section\s*[-–]\s*\w\s*:?\s*', re.IGNORECASE),
    re.compile(r'^part\s*[-–]\s*\w\s*:?\s*', re.IGNORECASE),
    re.compile(r'^ans\s*:\s*\n', re.IGNORECASE),
]

# ─── Helper Functions ─────────────────────────────────────────────────────────

def is_noise_line(line):
    return any(p.search(line) for p in NOISE_LINE_PATTERNS)

def clean_extracted_text(raw):
    return "\n".join(line for line in raw.splitlines() if not is_noise_line(line))

def clean_question_block(raw_q):
    text = raw_q.strip()
    text = re.sub(r'\bNUMERI\s*\n\s*CAL\b', 'NUMERICAL', text, flags=re.IGNORECASE)
    for pat in _Q_PREFIX_STRIP:
        text = pat.sub('', text, count=1).strip()
    text = re.sub(r'\n\s*Ans\s*:\s*$', '', text, flags=re.IGNORECASE).strip()
    return text

def find_section_page(reader, keywords):
    for i, page in enumerate(reader.pages):
        text = page.extract_text() or ""
        for line in text.splitlines():
            if is_noise_line(line):
                continue
            stripped  = line.strip()
            normalized = re.sub(r'\s+', ' ', stripped).lower()
            compact    = re.sub(r'\s+', '', stripped).lower()
            if len(stripped) < 60 and any(
                kw.lower() in normalized or kw.replace(' ', '').lower() in compact
                for kw in keywords
            ):
                return i
    return -1

def extract_pdf_sections(pdf_path):
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        total  = len(reader.pages)
        ans_page = find_section_page(reader, ["answer key", "answer  key", "answers"])
        sol_page = find_section_page(reader, ["solutions", "solution"])
        if ans_page == -1:
            ans_page = total
        if sol_page == -1 or sol_page <= ans_page:
            sol_page = ans_page + 1
        q_text = ak_text = sol_text = ""
        for i, page in enumerate(reader.pages):
            cleaned = clean_extracted_text(page.extract_text() or "")
            if i < ans_page:
                q_text   += cleaned + "\n"
            elif i < sol_page:
                ak_text  += cleaned + "\n"
            else:
                sol_text += cleaned + "\n"
    return q_text, ak_text, sol_text

def parse_answer_key(ak_text):
    answers = {}
    for m in re.finditer(r'Q\s*(\d+)\s*[:\.]?\s*\(([^)]{1,10})\)', ak_text):
        answers[int(m.group(1))] = m.group(2).strip()
    if not answers:
        for m in re.finditer(r'Q\s*(\d+)\s+([A-D1-4])\b', ak_text):
            answers[int(m.group(1))] = m.group(2).strip()
    if not answers:
        for m in re.finditer(r'\b(\d{1,2})\.\s*\(([^)]{1,10})\)', ak_text):
            answers[int(m.group(1))] = m.group(2).strip()
    if not answers:
        for m in re.finditer(r'\b(\d{1,2})\.\s*([A-D1-4])\b', ak_text):
            answers[int(m.group(1))] = m.group(2).strip()
    return answers

def split_into_question_blocks(q_text):
    parts = re.split(r'\n(Q\d+)\s*[-\.:\s]', q_text)
    if len(parts) > 2:
        q_map = {}
        for i in range(1, len(parts), 2):
            qnum  = int(re.search(r'\d+', parts[i]).group())
            block = parts[i+1].strip() if i+1 < len(parts) else ""
            if block:
                q_map[qnum] = block
        return q_map
    parts = re.split(r'\n\s*\(?\s*(\d{1,2})\s*\)?\s*\.\s*', q_text)
    if len(parts) > 2:
        q_map = {}
        for i in range(1, len(parts), 2):
            qnum  = int(parts[i])
            block = parts[i+1].strip() if i+1 < len(parts) else ""
            if block:
                q_map[qnum] = block
        return q_map
    return {}

def split_solutions(sol_text):
    sol_map = {}
    parts = re.split(r'\n(Q\d+)\s*\n', sol_text)
    for i in range(1, len(parts), 2):
        qnum  = int(re.search(r'\d+', parts[i]).group())
        block = parts[i+1].strip() if i+1 < len(parts) else ""
        if block:
            sol_map[qnum] = block
    return sol_map

def resolve_chapter(pdf_path, subject):
    base       = os.path.splitext(os.path.basename(pdf_path))[0].lower().replace(" ", "_")
    clean_base = re.sub(r'_click_here_to_download', '', base)
    if clean_base in _SKIP_FILENAMES:
        return None
    mapping = CHAPTER_MAP.get(subject, {})
    for key, chapter in mapping.items():
        if key.lower().replace(" ", "_") in base:
            return chapter
    for key, chapter in mapping.items():
        for word in key.lower().split("_"):
            if len(word) > 4 and word in base:
                return chapter
    return None

def extract_year_from_path(pdf_path):
    m = re.search(r'(\d{4})', pdf_path)
    return int(m.group(1)) if m else 2024

# ─── Progress ─────────────────────────────────────────────────────────────────

def load_progress():
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    return {}

def save_progress(progress):
    with open(PROGRESS_FILE, 'w', encoding='utf-8') as f:
        json.dump(progress, f, indent=2)

# ─── PDF Discovery ────────────────────────────────────────────────────────────

def discover_pdfs(subject_filter=None):
    results = []
    subjects = ["Maths", "Physics", "Chemistry"]
    if subject_filter:
        subjects = [s for s in subjects if s.lower() == subject_filter.lower()]
    for subj in subjects:
        folder = os.path.join(PDF_ROOT, subj)
        if not os.path.isdir(folder):
            print(f"⚠️  Folder not found: {folder}")
            continue
        for root, _, files in os.walk(folder):
            for fname in sorted(files):
                if not fname.lower().endswith(".pdf"):
                    continue
                if "click_here_to_download" in fname.lower():
                    clean_name = re.sub(r'(_click_here_to_download)+', '', fname, flags=re.IGNORECASE)
                    if clean_name.lower() != fname.lower() and os.path.exists(os.path.join(root, clean_name)):
                        continue
                results.append((os.path.join(root, fname), subj))
    return results

# ─── Core Extraction ──────────────────────────────────────────────────────────

def extract_single_pdf(pdf_path, subject):
    """
    Extract all question data from one PDF.
    Returns a dict ready to save as JSON, or None if the PDF should be skipped.
    """
    chapter = resolve_chapter(pdf_path, subject)
    if chapter is None:
        return None, "unmapped chapter"

    year = extract_year_from_path(pdf_path)

    try:
        q_text, ak_text, sol_text = extract_pdf_sections(pdf_path)
    except Exception as e:
        return None, f"PDF read error: {e}"

    answers  = parse_answer_key(ak_text)
    q_blocks = split_into_question_blocks(q_text)
    sol_map  = split_solutions(sol_text)

    if not answers:
        return None, "no answer key found (likely image-based PDF)"

    questions = []
    skipped   = 0

    for q_num in sorted(answers.keys()):
        raw_q   = q_blocks.get(q_num, "").strip()
        raw_sol = sol_map.get(q_num, "").strip()
        ans_val = answers[q_num]

        if not raw_q or len(raw_q) < 10:
            skipped += 1
            continue

        raw_q = clean_question_block(raw_q)

        if not raw_q or len(raw_q) < 10:
            skipped += 1
            continue

        if any(p.search(raw_q) for p in QUESTION_BAD_PATTERNS):
            skipped += 1
            continue

        questions.append({
            "q_num":      q_num,
            "raw_text":   raw_q,
            "answer_key": ans_val,
            "solution":   raw_sol if raw_sol else "",
        })

    if not questions:
        return None, "no usable questions after filtering"

    result = {
        "pdf_path":     pdf_path,
        "pdf_name":     os.path.basename(pdf_path),
        "subject":      subject,
        "chapter":      chapter,
        "year":         year,
        "extracted_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "questions":    questions,
        "stats": {
            "q_blocks":  len(q_blocks),
            "answers":   len(answers),
            "solutions": len(sol_map),
            "exported":  len(questions),
            "skipped":   skipped,
        }
    }
    return result, None

def output_path_for(pdf_path, subject):
    """Returns the JSON output path for a given PDF."""
    stem    = os.path.splitext(os.path.basename(pdf_path))[0]
    stem    = re.sub(r'_click_here_to_download', '', stem, flags=re.IGNORECASE)
    out_dir = os.path.join(EXTRACT_DIR, subject)
    os.makedirs(out_dir, exist_ok=True)
    return os.path.join(out_dir, stem + ".json")

# ─── Stats ────────────────────────────────────────────────────────────────────

def show_stats():
    if not os.path.isdir(EXTRACT_DIR):
        print("No extracted files found. Run --all first.")
        return
    total_files = total_questions = 0
    for subj in ["Maths", "Physics", "Chemistry"]:
        subj_dir = os.path.join(EXTRACT_DIR, subj)
        if not os.path.isdir(subj_dir):
            continue
        files = [f for f in os.listdir(subj_dir) if f.endswith('.json')]
        q_count = 0
        for fname in files:
            with open(os.path.join(subj_dir, fname), 'r', encoding='utf-8') as f:
                data = json.load(f)
                q_count += data.get("stats", {}).get("exported", 0)
        print(f"  {subj:12s}: {len(files):4d} JSON files | {q_count:6d} questions")
        total_files     += len(files)
        total_questions += q_count
    print(f"  {'TOTAL':12s}: {total_files:4d} JSON files | {total_questions:6d} questions")

# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Stage 1: Extract JEE PDFs → JSON text files (no API needed)")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all",     action="store_true",  help="Extract all subjects")
    group.add_argument("--subject", metavar="SUBJECT",    help="Extract one subject (Maths|Physics|Chemistry)")
    group.add_argument("--stats",   action="store_true",  help="Show extraction summary")
    parser.add_argument("--reset",  action="store_true",  help="Re-extract already-done PDFs")
    args = parser.parse_args()

    if args.stats:
        print("\n📊 Extraction Summary:")
        show_stats()
        return

    progress = {} if args.reset else load_progress()
    pdfs     = discover_pdfs(subject_filter=args.subject if not args.all else None)
    total    = len(pdfs)

    print(f"📁 Found {total} PDF files.\n")

    done_count  = 0
    skip_count  = 0
    error_count = 0
    q_total     = 0

    for i, (pdf_path, subj) in enumerate(pdfs):
        pdf_key = os.path.abspath(pdf_path)

        if pdf_key in progress and not args.reset:
            print(f"⏭️  [{i+1}/{total}] Already extracted — {os.path.basename(pdf_path)}")
            continue

        print(f"[{i+1}/{total}] 📄 {os.path.basename(pdf_path)}", end=" ... ", flush=True)

        data, reason = extract_single_pdf(pdf_path, subj)

        if data is None:
            print(f"⏭️  Skipped ({reason})")
            skip_count += 1
            progress[pdf_key] = {"status": "skipped", "reason": reason}
            save_progress(progress)
            continue

        out_path = output_path_for(pdf_path, subj)
        with open(out_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

        q_count = data["stats"]["exported"]
        q_total += q_count
        print(f"✅  {q_count} questions → {os.path.relpath(out_path, SCRIPT_DIR)}")
        done_count += 1
        progress[pdf_key] = {"status": "done", "questions": q_count, "out": out_path}
        save_progress(progress)

    print(f"\n{'═'*70}")
    print(f"🎉  Extraction complete!")
    print(f"    ✅ Extracted : {done_count} PDFs | {q_total} questions saved")
    print(f"    ⏭️  Skipped   : {skip_count} PDFs (image-based or unmapped chapter)")
    print(f"    📂 Output dir: {EXTRACT_DIR}")
    print(f"\n    Next step → run the upload pipeline:")
    print(f"    py scripts/groq_pdf_pipeline.py --from-text --subject Maths")
    print(f"{'═'*70}")


if __name__ == "__main__":
    main()
