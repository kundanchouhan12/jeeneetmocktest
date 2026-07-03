r"""
groq_pdf_pipeline.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
End-to-end Groq AI pipeline for JEE PDF → Firestore upload.

Usage:
    # Dry-run first (shows what it will do, no Firestore writes):
    py scripts/groq_pdf_pipeline.py --dry-run

    # Process ALL PDFs from a subject:
    py scripts/groq_pdf_pipeline.py --subject Maths

    # Process a single PDF:
    py scripts/groq_pdf_pipeline.py --file "D:\JEE_PDFs\Maths\January_2024\Matrices.pdf"

    # Process everything (all subjects, all years):
    py scripts/groq_pdf_pipeline.py --all

    # Re-process already-done PDFs (clear progress):
    py scripts/groq_pdf_pipeline.py --reset-progress --subject Maths
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import os
import sys
import re
import json
import time
import hashlib
import argparse
import requests

try:
    import PyPDF2
except ImportError:
    print("❌ PyPDF2 not installed. Run: pip install PyPDF2")
    sys.exit(1)

try:
    from google import genai as _google_genai  # new SDK: pip install google-genai
    GEMINI_AVAILABLE = True
except ImportError:
    _google_genai = None
    GEMINI_AVAILABLE = False

try:
    import firebase_admin
    from firebase_admin import credentials, firestore
    FIREBASE_AVAILABLE = True
except ImportError:
    FIREBASE_AVAILABLE = False

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# ─── Config ───────────────────────────────────────────────────────────────────

SCRIPT_DIR       = os.path.dirname(os.path.abspath(__file__))
PDF_ROOT         = r"D:\JEE_PDFs"
PROGRESS_FILE    = os.path.join(SCRIPT_DIR, "pipeline_progress.json")
SERVICE_KEY_PATH = os.path.join(SCRIPT_DIR, "serviceAccountKey.json")

GEMINI_API_KEY = "AIzaSyDc49x3GT-a8Ivfk0U9YrIT9qJaANXvPpc"   # needs billing enabled on Google Cloud project to work
GEMINI_MODEL   = "gemini-2.0-flash"

# ── Cerebras (primary) ────────────────────────────────────────────────────────
CEREBRAS_API_KEY = "csk-9fnejkxyyfc9mjp54nwxw5x2kwe3tnnnnw5mpwcd9n5mhc2h"
CEREBRAS_URL     = "https://api.cerebras.ai/v1/chat/completions"
CEREBRAS_MODEL   = "gpt-oss-120b"

# ── Groq (fallback when Cerebras is rate-limited) ─────────────────────────────
GROQ_API_KEY = "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"
GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL   = "llama-3.3-70b-versatile"

# ─── Chapter Mapping ──────────────────────────────────────────────────────────
# Maps lowercase keywords found in PDF filenames → official chapter names in app

CHAPTER_MAP = {
    "Maths": {
        # Matrices & Determinants
        "matrices":                          "Matrices & Determinants",
        "determinants":                      "Matrices & Determinants",
        # Complex Numbers & Quadratic
        "complex_number":                    "Complex Numbers & Quadratic Equations",
        "complex number":                    "Complex Numbers & Quadratic Equations",
        "quadratic":                         "Complex Numbers & Quadratic Equations",
        # Permutations
        "permutation":                       "Permutations And Combinations",
        "combination":                       "Permutations And Combinations",
        # Binomial
        "binomial":                          "Binomial Theorem",
        # Sequence & Series
        "sequence":                          "Sequence & Series",
        "series":                            "Sequence & Series",
        "progression":                       "Sequence & Series",
        # Calculus - Limits/Differentiation
        "limit":                             "Limit, Continuity & Differentiability",
        "continuity":                        "Limit, Continuity & Differentiability",
        "differentiability":                 "Limit, Continuity & Differentiability",
        "differentiation":                   "Limit, Continuity & Differentiability",
        "application_of_derivatives":        "Limit, Continuity & Differentiability",
        "application of derivatives":        "Limit, Continuity & Differentiability",
        "application_of_derivative":         "Limit, Continuity & Differentiability",
        # Integral Calculus
        "indefinite_integration":            "Integral Calculus",
        "definite_integration":              "Integral Calculus",
        "definite integration":              "Integral Calculus",
        "integration":                       "Integral Calculus",
        "area_under":                        "Integral Calculus",
        "area under":                        "Integral Calculus",
        "differential_equation":             "Integral Calculus",
        "differential equation":             "Integral Calculus",
        # Coordinate Geometry
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
        # Vector Algebra
        "vector_algebra":                    "Vector Algebra",
        "vector algebra":                    "Vector Algebra",
        # Three Dimensional Geometry
        "three_dimensional":                 "Three Dimensional Geometry",
        "three dimensional":                 "Three Dimensional Geometry",
        "3d_geometry":                       "Three Dimensional Geometry",
        "vector_3d":                         "Three Dimensional Geometry",
        # Trigonometry
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
        # Probability
        "probability":                       "Probability",
        # Statistics
        "statistics":                        "Statistics",
        # Sets, Relations, Functions
        "sets_and_relation":                 "Sets, Relations, And Functions",
        "set_and_relation":                  "Sets, Relations, And Functions",
        "sets":                              "Sets, Relations, And Functions",
        "relation":                          "Sets, Relations, And Functions",
        "function":                          "Sets, Relations, And Functions",
        # Mathematical Reasoning
        "mathematical_reasoning":            "Mathematical Reasoning",
        "mathematical reasoning":            "Mathematical Reasoning",
        "logic":                             "Mathematical Reasoning",
        # Area/Areas under curves → Integral Calculus
        "area":                              "Integral Calculus",
    },
    "Physics": {
        # Units & Dimensions
        "units_and_dimension":               "Units, Dimensions And Measurement",
        "units_and_measurement":             "Units, Dimensions And Measurement",
        "unit_and_dimension":                "Units, Dimensions And Measurement",
        "unit":                              "Units, Dimensions And Measurement",
        "dimension":                         "Units, Dimensions And Measurement",
        "measurement":                       "Units, Dimensions And Measurement",
        # Motion 1D
        "motion_in_one":                     "Motion In One Dimension",
        "motion in one":                     "Motion In One Dimension",
        "kinematics_1d":                     "Motion In One Dimension",
        # Motion 2D
        "motion_in_two":                     "Motion In Two Dimension",
        "motion in two":                     "Motion In Two Dimension",
        "kinematics_2d":                     "Motion In Two Dimension",
        "projectile":                        "Motion In Two Dimension",
        # Newton's Laws
        "laws_of_motion":                    "Newton's Laws Of Motion",
        "laws of motion":                    "Newton's Laws Of Motion",
        "newton":                            "Newton's Laws Of Motion",
        # Friction
        "friction":                          "Friction",
        # Work, Energy, Power
        "work_power_and_energy":             "Work, Energy, Power And Collision",
        "work_power_energy":                 "Work, Energy, Power And Collision",
        "work power energy":                 "Work, Energy, Power And Collision",
        "collision":                         "Work, Energy, Power And Collision",
        "center_of_mass":                    "Work, Energy, Power And Collision",
        "centre_of_mass":                    "Work, Energy, Power And Collision",
        "center of mass":                    "Work, Energy, Power And Collision",
        # Rotational Motion
        "rotational":                        "Rotational Motion",
        # Gravitation
        "gravitation":                       "Gravitation",
        # Simple Harmonic Motion
        "oscillation":                       "Simple Harmonic Motion",
        "oscillations":                      "Simple Harmonic Motion",
        "shm":                               "Simple Harmonic Motion",
        "simple_harmonic":                   "Simple Harmonic Motion",
        "simple harmonic":                   "Simple Harmonic Motion",
        # Elasticity
        "elasticity":                        "Elasticity",
        "mechanical_properties_of_solid":    "Elasticity",
        "properties_of_solid":               "Elasticity",
        # Fluid Mechanics
        "fluid":                             "Fluid Mechanics",
        "mechanical_properties_of_fluid":    "Fluid Mechanics",
        "properties_of_liquid":              "Fluid Mechanics",
        "properties_of_matter":              "Fluid Mechanics",
        "properties of matter":              "Fluid Mechanics",
        "properties_of_solids_and_liquid":   "Fluid Mechanics",
        # Thermal Physics
        "thermal_properties":                "Thermal Physics",
        "thermal_property":                  "Thermal Physics",
        "thermal":                           "Thermal Physics",
        "heat":                              "Thermal Physics",
        "calorimetry":                       "Thermal Physics",
        # Kinetic Theory
        "kinetic_theory":                    "Kinetic Theory Of Gases",
        "kinetic theory":                    "Kinetic Theory Of Gases",
        # Thermodynamics (Physics)
        "thermodynamics":                    "Thermodynamics",
        # Wave Motion
        "waves_and_sound":                   "Wave Motion",
        "wave_and_sound":                    "Wave Motion",
        "waves_and":                         "Wave Motion",
        "wave_mechanic":                     "Wave Motion",
        "wave":                              "Wave Motion",
        "sound":                             "Wave Motion",
        # Electrostatics
        "electrostatics":                    "Electrostatics",
        "electrostatic":                     "Electrostatics",
        "capacitance":                       "Electrostatics",
        "electric_field":                    "Electrostatics",
        # Current Electricity
        "current_electricity":               "Current Electricity",
        "current electricity":               "Current Electricity",
        # Magnetic Effect
        "magnetic_effect":                   "Magnetic Effect Of Current",
        "magnetic effect":                   "Magnetic Effect Of Current",
        "magnetic_properties":               "Magnetic Effect Of Current",
        "magnetism":                         "Magnetic Effect Of Current",
        # Electromagnetic Induction
        "electromagnetic_induction":         "Electromagnetic Induction",
        "electromagnetic induction":         "Electromagnetic Induction",
        "alternating_current":               "Electromagnetic Induction",
        "alternating current":               "Electromagnetic Induction",
        "electromagnetic_wave":              "Electromagnetic Induction",
        # Optics
        "ray_optic":                         "Optics",
        "ray optic":                         "Optics",
        "wave_optic":                        "Optics",
        "wave optic":                        "Optics",
        "optic":                             "Optics",
        # Modern Physics
        "atomic_physic":                     "Modern Physics",
        "atomic_structure":                  "Modern Physics",
        "nuclear_physic":                    "Modern Physics",
        "nuclear physic":                    "Modern Physics",
        "dual_nature":                       "Modern Physics",
        "photoelectric":                     "Modern Physics",
        "semiconductor":                     "Modern Physics",
        "modern_physic":                     "Modern Physics",
        "wave_mechanic":                     "Modern Physics",
        # Circular Motion → Rotational Motion (no separate chapter in app)
        "circular":                          "Rotational Motion",
        # Short-form abbreviations
        "emi":                               "Electromagnetic Induction",
        "em_wave":                           "Electromagnetic Induction",
        # Mathematics In Physics
        "mathematics_in_physics":            "Mathematics In Physics",
    },
    "Chemistry": {
        # Some Basic Concepts
        "some_basic_concept":                "Some Basic Concepts Of Chemistry",
        "some basic concept":                "Some Basic Concepts Of Chemistry",
        "mole_concept":                      "Some Basic Concepts Of Chemistry",
        "mole concept":                      "Some Basic Concepts Of Chemistry",
        "qualitative_analysis":              "Some Basic Concepts Of Chemistry",
        "qualitative analysis":              "Some Basic Concepts Of Chemistry",
        "purification_and_characterization": "Some Basic Concepts Of Chemistry",
        "purification_of_organic":           "Organic Chemistry Basics",
        "practical_chemistry":               "Some Basic Concepts Of Chemistry",
        # Structure of Atom
        "structure_of_atom":                 "Structure Of Atom",
        "structure of atom":                 "Structure Of Atom",
        "atomic_structure":                  "Structure Of Atom",
        # Classification of Elements
        "classification_of_element":         "Classification Of Elements",
        "periodic_table":                    "Classification Of Elements",
        "periodic_propert":                  "Classification Of Elements",
        "periodic table":                    "Classification Of Elements",
        "periodic_and_periodicity":          "Classification Of Elements",
        "periodicity":                       "Classification Of Elements",
        # Chemical Bonding
        "chemical_bonding":                  "Chemical Bonding",
        "chemical bonding":                  "Chemical Bonding",
        "molecular_orbital":                 "Chemical Bonding",
        # States of Matter
        "states_of_matter":                  "States Of Matter",
        "states of matter":                  "States Of Matter",
        "gaseous_state":                     "States Of Matter",
        # Thermodynamics (Chemistry)
        "thermodynamics_(c)":                "Thermodynamics",
        "thermodynamics_c":                  "Thermodynamics",
        "thermodynamics":                    "Thermodynamics",
        "thermochemistry":                   "Thermodynamics",
        # Equilibrium
        "equilibrium":                       "Equilibrium",
        "ionic_equilibrium":                 "Equilibrium",
        # Redox
        "redox":                             "Redox Reactions",
        # Hydrogen
        "hydrogen":                          "Hydrogen",
        # S-Block
        "s-block":                           "S-Block Elements",
        "s_block":                           "S-Block Elements",
        "the_s-block":                       "S-Block Elements",
        "the_s_block":                       "S-Block Elements",
        # P-Block
        "p-block":                           "P-Block Elements",
        "p_block":                           "P-Block Elements",
        "the_p-block":                       "P-Block Elements",
        "the_p_block":                       "P-Block Elements",
        # Organic Chemistry Basics
        "organic_chemistry_basic":           "Organic Chemistry Basics",
        "organic chemistry basic":           "Organic Chemistry Basics",
        "general_organic":                   "Organic Chemistry Basics",
        "nomenclature":                      "Organic Chemistry Basics",
        "organic_compound_containing":       "Organic Chemistry Basics",
        "isomerism":                         "Organic Chemistry Basics",
        # Hydrocarbons
        "hydrocarbon":                       "Hydrocarbons",
        # Environmental Chemistry
        "environmental":                     "Environmental Chemistry",
        # Solid State
        "solid_state":                       "Solid State",
        "solid state":                       "Solid State",
        # Solutions
        "solutions_and_colligative":         "Solutions",
        "solution":                          "Solutions",
        # Electrochemistry
        "electrochemistry":                  "Electrochemistry",
        # Chemical Kinetics
        "chemical_kinetics":                 "Chemical Kinetics",
        "chemical kinetics":                 "Chemical Kinetics",
        # Surface Chemistry
        "surface_chemistry":                 "Surface Chemistry",
        "surface chemistry":                 "Surface Chemistry",
        # Coordination Compounds
        "coordination":                      "Coordination Compounds",
        "d_and_f_block":                     "Coordination Compounds",
        "d and f block":                     "Coordination Compounds",
        "d-block":                           "Coordination Compounds",
        "f-block":                           "Coordination Compounds",
        "the_d_and_f":                       "Coordination Compounds",
        # Aldehydes, Ketones, Carboxylic
        "aldehyde":                          "Aldehydes, Ketones And Carboxylic Acids",
        "ketone":                            "Aldehydes, Ketones And Carboxylic Acids",
        "carboxylic":                        "Aldehydes, Ketones And Carboxylic Acids",
        # Biomolecules
        "biomolecule":                       "Biomolecules",
        "polymer":                           "Biomolecules",
        # Organic compounds (mapped to nearest official chapter)
        "alcohol":                           "Organic Chemistry Basics",
        "phenol":                            "Organic Chemistry Basics",
        "ether":                             "Organic Chemistry Basics",
        "amine":                             "Organic Chemistry Basics",
        "haloalkane":                        "Organic Chemistry Basics",
        "haloarene":                         "Organic Chemistry Basics",
        "aromatic":                          "Hydrocarbons",
    }
}

# Official chapters list (for validation)
OFFICIAL_CHAPTERS = {
    "Maths":     ["Sets, Relations, And Functions","Complex Numbers & Quadratic Equations","Matrices & Determinants","Permutations And Combinations","Binomial Theorem","Sequence & Series","Limit, Continuity & Differentiability","Integral Calculus","Coordinate Geometry","Three Dimensional Geometry","Vector Algebra","Probability","Trigonometry","Mathematical Reasoning","Statistics"],
    "Physics":   ["Mathematics In Physics","Units, Dimensions And Measurement","Motion In One Dimension","Motion In Two Dimension","Newton's Laws Of Motion","Friction","Work, Energy, Power And Collision","Rotational Motion","Gravitation","Simple Harmonic Motion","Elasticity","Fluid Mechanics","Thermal Physics","Kinetic Theory Of Gases","Thermodynamics","Wave Motion","Electrostatics","Current Electricity","Magnetic Effect Of Current","Electromagnetic Induction","Optics","Modern Physics"],
    "Chemistry": ["Some Basic Concepts Of Chemistry","Structure Of Atom","Classification Of Elements","Chemical Bonding","States Of Matter","Thermodynamics","Equilibrium","Redox Reactions","Hydrogen","S-Block Elements","P-Block Elements","Organic Chemistry Basics","Hydrocarbons","Environmental Chemistry","Solid State","Solutions","Electrochemistry","Chemical Kinetics","Surface Chemistry","Coordination Compounds","Aldehydes, Ketones And Carboxylic Acids","Biomolecules"],
}

# ─── Groq Parser Prompt ───────────────────────────────────────────────────────

GROQ_SYSTEM_PROMPT = """\
You are an expert JEE Main exam content extractor.
You receive a raw text block containing ONE question (Q<N>), its answer key value, and optionally a solution.
Your job is to output a SINGLE valid JSON object — nothing else.

=== OUTPUT FORMAT ===
{
  "questionText": "<full clean question text. Use LaTeX for ALL math: $inline$ or $$block$$. Remove any page headers, footers, or watermarks. Keep chemical formulas in LaTeX too.>",
  "options": ["<A>", "<B>", "<C>", "<D>"],
  "correctOptionIndex": <0, 1, 2 or 3>,
  "explanation": "<step-by-step solution in plain text with LaTeX for formulas. Be concise but clear.>",
  "difficulty": "<Easy | Medium | Hard — judge from topic complexity>",
  "isNumerical": <true if original had no A/B/C/D options, false otherwise>
}

=== STRICT RULES ===
1. REAL MCQ (has options labeled (1)/(2)/(3)/(4) or (A)/(B)/(C)/(D)):
   - Extract the 4 options verbatim (clean math to LaTeX).
   - Answer key "1" or "A" → correctOptionIndex 0, "2" or "B" → 1, etc.
   - Set isNumerical = false.

2. NUMERICAL question (no lettered options in text):
   - The answer key gives the correct numeric value.
   - Generate 4 realistic distractor values. Place the correct answer at a random index.
   - Set isNumerical = true.
   - Options should look like numbers e.g. ["8", "12", "16", "20"].

3. MATH FORMATTING:
   - ALL fractions → $\\frac{a}{b}$
   - ALL square roots → $\\sqrt{x}$
   - ALL Greek letters → $\\alpha$, $\\beta$, etc.
   - Vectors → $\\vec{F}$
   - Matrices → $\\begin{bmatrix}...\\end{bmatrix}$
   - Use double backslash \\\\ inside JSON strings.

4. NOISE FILTER — NEVER include any of the following in questionText or options:
   - Answer key residue like ": B" or ": 2" at the start of the block
   - Section headers like "NUMERICAL", "NUMERI CAL", "Integer Type", "Section A"
   - Page headers like "JEE Main January 2024 ..."
   - Footer lines like "Questions with Answer Keys | MathonGo"
   - Lines like "Click here to download solutions"
   - Lines like "Note: For SHORT ANSWER and NUMERICAL questions, refer to standard textbooks"
   - "Page 67 JEE + NEET Master Practice Workbook" or any workbook/page references
   - "Ans:" at the end of question text
   - Any text after the last option before "Answer Key"

5. OUTPUT {"skip": true} (nothing else) in ANY of these cases:
   - The block starts with ": A" / ": B" / ": C" / ": D" / ": 1" / ": 2" / ": 3" / ": 4" with no real question text
   - The block is entirely footer/noise with no actual question
   - The question text is too short (< 15 chars of real content) or completely garbled
   - Options cannot be determined (do NOT invent placeholder "Option A" / "Option B" text)

6. Output ONLY the JSON object. No markdown, no commentary, no ```json wrapper.
"""


# ─── Noise / Bad Line Patterns ────────────────────────────────────────────────

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

# Patterns that mean the entire raw question block is garbage — skip it entirely
QUESTION_BAD_PATTERNS = [
    re.compile(r'refer\s+to\s+standard\s+textbooks', re.IGNORECASE),
    re.compile(r'note:\s*for\s+short\s+answer', re.IGNORECASE),
    re.compile(r'master\s+practice\s+workbook', re.IGNORECASE),
    re.compile(r'click\s+here\s+to\s+download', re.IGNORECASE),
    re.compile(r'questions?\s+with\s+answer\s+key', re.IGNORECASE),
    re.compile(r'solutions?\s+jee\s+main', re.IGNORECASE),
    # Answer key value leaked as the entire question block (": B" or ": 2")
    re.compile(r'^\s*:\s*[A-D1-4]\s*$', re.MULTILINE),
    # Answer key + footer noise as the entire block (": B Note: For SHORT ANSWER...")
    re.compile(r':\s*[A-D1-4]\s+note:\s*for\s+short', re.IGNORECASE),
    re.compile(r':\s*[A-D1-4]\s+.*master\s+practice\s+workbook', re.IGNORECASE),
    re.compile(r':\s*[A-D1-4]\s+.*refer\s+to\s+standard', re.IGNORECASE),
]

# Prefixes to STRIP from the start of a question block (header noise, not the question itself)
# These are stripped rather than causing a skip, because the actual question follows the header.
_Q_PREFIX_STRIP = [
    re.compile(r'^numer\s*i\s*cal\s*:?\s*', re.IGNORECASE),    # "NUMERICAL" / "NUMERI CAL" / "NUMERI\nCAL"
    re.compile(r'^integer\s+type\s*:?\s*', re.IGNORECASE),      # "Integer Type:"
    re.compile(r'^section\s*[-–]\s*\w\s*:?\s*', re.IGNORECASE), # "Section - A:"
    re.compile(r'^part\s*[-–]\s*\w\s*:?\s*', re.IGNORECASE),    # "Part - A:"
    re.compile(r'^ans\s*:\s*\n', re.IGNORECASE),                 # standalone "Ans:" line at top
]

def clean_question_block(raw_q):
    """Strip section header prefixes and trailing noise from a question block."""
    text = raw_q.strip()
    # Replace newlines within "NUMERI\nCAL" split (PDF line-wrap artefact) before stripping
    text = re.sub(r'\bNUMERI\s*\n\s*CAL\b', 'NUMERICAL', text, flags=re.IGNORECASE)
    for pat in _Q_PREFIX_STRIP:
        text = pat.sub('', text, count=1).strip()
    # Strip trailing "Ans :" lines that sometimes appear at block end
    text = re.sub(r'\n\s*Ans\s*:\s*$', '', text, flags=re.IGNORECASE).strip()
    return text


# ─── Progress Tracking ────────────────────────────────────────────────────────

def load_progress():
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    return {}

def save_progress(progress):
    with open(PROGRESS_FILE, 'w', encoding='utf-8') as f:
        json.dump(progress, f, indent=2)

def mark_done(progress, pdf_path, count):
    progress[os.path.abspath(pdf_path)] = {"questions_added": count, "done_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    save_progress(progress)

def is_done(progress, pdf_path):
    return os.path.abspath(pdf_path) in progress


# ─── PDF Extraction ───────────────────────────────────────────────────────────

def is_noise_line(line):
    for pat in NOISE_LINE_PATTERNS:
        if pat.search(line):
            return True
    return False

def clean_extracted_text(raw):
    """Remove noise lines from raw PDF text."""
    lines = raw.splitlines()
    clean = []
    for line in lines:
        if not is_noise_line(line):
            clean.append(line)
    return "\n".join(clean)

def find_section_page(reader, keywords):
    """
    Find the first page index where a line by itself (or near-alone)
    matches one of the keywords. Avoids matching keywords in footers.
    """
    for i, page in enumerate(reader.pages):
        text = page.extract_text() or ""
        for line in text.splitlines():
            if is_noise_line(line):
                continue
            stripped = line.strip()
            # Two forms: single-space-normalized and fully-compact (no spaces).
            # PyPDF2 sometimes injects mid-word spaces: "ANSW ER KEYS" → compact = "ANSWERKEYS"
            normalized = re.sub(r'\s+', ' ', stripped).lower()
            compact   = re.sub(r'\s+', '', stripped).lower()
            if len(stripped) < 60 and any(
                kw.lower() in normalized or kw.replace(' ', '').lower() in compact
                for kw in keywords
            ):
                return i
    return -1

def extract_pdf_sections(pdf_path):
    """
    Returns (questions_text, answer_key_text, solutions_text).
    Detects page boundaries for each section.
    """
    with open(pdf_path, 'rb') as f:
        reader = PyPDF2.PdfReader(f)
        total = len(reader.pages)

        # Find Answer Key section page
        ans_page = find_section_page(reader, ["answer key", "answer  key", "answers"])
        sol_page = find_section_page(reader, ["solutions", "solution"])

        if ans_page == -1:
            ans_page = total  # everything is questions
        if sol_page == -1 or sol_page <= ans_page:
            sol_page = ans_page + 1

        q_text  = ""
        ak_text = ""
        sol_text = ""

        for i, page in enumerate(reader.pages):
            raw = page.extract_text() or ""
            cleaned = clean_extracted_text(raw)
            if i < ans_page:
                q_text   += cleaned + "\n"
            elif i < sol_page:
                ak_text  += cleaned + "\n"
            else:
                sol_text += cleaned + "\n"

    return q_text, ak_text, sol_text


# ─── Parsing ──────────────────────────────────────────────────────────────────

def parse_answer_key(ak_text):
    """
    Parse answer key entries. Supports formats:
      Q1 (2)   Q12 (A)   1. (4)   1. (B)   Q1. A
    Returns dict: {q_num: answer_string}
    """
    answers = {}
    # Pattern 1: Q<n> (<val>)  — e.g. "Q1 (2)", "Q12 (A)"
    for m in re.finditer(r'Q\s*(\d+)\s*[:\.]?\s*\(([^)]{1,10})\)', ak_text):
        answers[int(m.group(1))] = m.group(2).strip()
    # Pattern 2: Q<n> <letter>  — e.g. "Q1 A" (when no parens)
    if not answers:
        for m in re.finditer(r'Q\s*(\d+)\s+([A-D1-4])\b', ak_text):
            answers[int(m.group(1))] = m.group(2).strip()
    # Pattern 3: n. (val)  — e.g. "1. (4)", "8. (27)" (2023 PDF style)
    if not answers:
        for m in re.finditer(r'\b(\d{1,2})\.\s*\(([^)]{1,10})\)', ak_text):
            answers[int(m.group(1))] = m.group(2).strip()
    # Pattern 4: n. letter/number  — e.g. "1. A", "1. 2" (no parens, no Q prefix)
    if not answers:
        for m in re.finditer(r'\b(\d{1,2})\.\s*([A-D1-4])\b', ak_text):
            answers[int(m.group(1))] = m.group(2).strip()
    return answers

def split_into_question_blocks(q_text):
    """
    Split the question section text into a map of q_num → raw_block.
    Handles formats: Q1 -  Q1.  1.  (1)
    """
    # Try Q<n> - or Q<n>. format first
    parts = re.split(r'\n(Q\d+)\s*[-\.:\s]', q_text)
    if len(parts) > 2:
        q_map = {}
        for i in range(1, len(parts), 2):
            qnum = int(re.search(r'\d+', parts[i]).group())
            block = parts[i+1].strip() if i+1 < len(parts) else ""
            if block:
                q_map[qnum] = block
        return q_map

    # Fallback: numbered like  1.  or (1)
    parts = re.split(r'\n\s*\(?\s*(\d{1,2})\s*\)?\s*\.\s*', q_text)
    if len(parts) > 2:
        q_map = {}
        for i in range(1, len(parts), 2):
            qnum = int(parts[i])
            block = parts[i+1].strip() if i+1 < len(parts) else ""
            if block:
                q_map[qnum] = block
        return q_map

    return {}

def split_solutions(sol_text):
    """Returns dict: {q_num: solution_text}"""
    sol_map = {}
    parts = re.split(r'\n(Q\d+)\s*\n', sol_text)
    for i in range(1, len(parts), 2):
        qnum = int(re.search(r'\d+', parts[i]).group())
        block = parts[i+1].strip() if i+1 < len(parts) else ""
        if block:
            sol_map[qnum] = block
    return sol_map


# ─── Chapter Resolution ───────────────────────────────────────────────────────

# Filenames that are NOT in official JEE Main chapters — must never be mapped
# via the fuzzy fallback to an unrelated chapter.
_SKIP_FILENAMES = {
    "mathematical_induction",
    "linear_programming",
    "basic_of_mathematics",
    "chemistry_in_everyday_life",
    "metallurgy",
    "general_principles_and_processes_of_isolation_of_metals",
    "communication_system",
    "communication_systems",
    "experimental_physics",
}

def resolve_chapter(pdf_path, subject):
    """Map PDF filename → official chapter name."""
    base = os.path.splitext(os.path.basename(pdf_path))[0].lower().replace(" ", "_")
    # Strip year suffixes like _click_here_to_download before checking skip list
    clean_base = re.sub(r'_click_here_to_download', '', base)
    if clean_base in _SKIP_FILENAMES:
        return None
    mapping = CHAPTER_MAP.get(subject, {})
    for key, chapter in mapping.items():
        if key.lower().replace(" ", "_") in base:
            return chapter
    # Last resort: try partial match
    for key, chapter in mapping.items():
        for word in key.lower().split("_"):
            if len(word) > 4 and word in base:
                return chapter
    return None  # Unknown — will be validated before push

def extract_year_from_path(pdf_path):
    """Extract year integer from folder name like January_2024, Apr_2019."""
    m = re.search(r'(\d{4})', pdf_path)
    return int(m.group(1)) if m else 2024


# ─── JSON / LaTeX Repair ──────────────────────────────────────────────────────

def fix_ai_json(s):
    r"""
    Groq often emits LaTeX with single backslashes inside JSON strings, e.g.
    \frac, \alpha, \sqrt — these are invalid JSON escape sequences.
    This function doubles them before json.loads() is called.

    Pass 1: fix outright invalid escapes (\alpha, \sqrt, \vec, \cdot, etc.)
            by doubling \ before any char that is NOT a valid JSON escape char.
    Pass 2: fix LaTeX commands that START with a valid JSON escape letter
            (f -> \frac \forall; b -> \begin \bar; n -> \nabla; t -> \text \to)
            which parse without error but produce garbage (e.g. \frac -> <FF>rac).
    """
    # Pass 1 — invalid JSON escapes: \ followed by anything not in " \ / b f n r t u
    s = re.sub(r'(?<!\\)\\(?!["\\/bfnrtu])', r'\\\\', s)
    # Pass 2 — LaTeX commands that start with a valid-but-wrong JSON escape letter
    _latex_f = r'(?:frac|forall|loat|left|loor|loorfrac)'
    _latex_b = r'(?:begin|bar|binom|big(?:g?[lr]?)?|boldsymbol|bf|bra|boxed)'
    _latex_n = r'(?:nabla|not|newline|nu)'
    _latex_t = r'(?:text(?:bf|rm|it)?|tilde|to\b|times|theta|tau)'
    _latex_r = r'(?:right|rangle|rceil|rfloor|rm)'
    _latex_u = r'(?:underbrace|underset|underline|uparrow|upsilon)'
    combined = f'({_latex_f}|{_latex_b}|{_latex_n}|{_latex_t}|{_latex_r}|{_latex_u})'
    s = re.sub(r'(?<!\\)\\' + combined, r'\\\\' + r'\1', s)
    return s


# ─── Gemini API Call ──────────────────────────────────────────────────────────




def _call_openai_compat(user_message, api_key, url, model, provider_name, retries=6):
    """Generic caller for any OpenAI-compatible endpoint (Cerebras, Groq, etc.)"""
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": GROQ_SYSTEM_PROMPT},
            {"role": "user",   "content": user_message}
        ],
        "temperature": 0.05,
        "max_tokens": 2048
    }
    wait = 3
    for attempt in range(retries):
        try:
            r = requests.post(url, headers=headers, json=body, timeout=40)
            if r.status_code == 200:
                msg = r.json()["choices"][0]["message"]
                # gpt-oss-120b is a reasoning model — content can be empty, answer may be in reasoning
                text = (msg.get("content") or msg.get("reasoning") or "").strip()
                if not text:
                    raise Exception("Empty response from model")
                return text
            elif r.status_code == 429:
                retry_after  = r.headers.get("retry-after")
                reset_tokens = r.headers.get("x-ratelimit-reset-tokens")
                reset_reqs   = r.headers.get("x-ratelimit-reset-requests")

                def _parse_dur(s):
                    if not s: return None
                    total = 0.0
                    for val, unit in re.findall(r'([\d\.]+)\s*([hms])', s):
                        v = float(val)
                        total += v * 3600 if unit == 'h' else v * 60 if unit == 'm' else v
                    return total if total > 0 else None

                sleep_s = wait
                if retry_after:
                    sleep_s = float(retry_after) if retry_after.replace('.','').isdigit() else (_parse_dur(retry_after) or wait)
                elif reset_tokens:
                    sleep_s = _parse_dur(reset_tokens) or wait
                elif reset_reqs:
                    sleep_s = _parse_dur(reset_reqs) or wait

                sleep_s = max(sleep_s + 1.5, wait)
                if sleep_s > 10:
                    # Switch to fallback rather than wait
                    raise Exception(f"RATE_LIMIT_LONG:{sleep_s:.0f}")
                print(f"    ⏳ {provider_name} rate limited. Sleeping {sleep_s:.0f}s... (Attempt {attempt+1}/{retries})")
                time.sleep(sleep_s)
                wait = min(wait * 2, 45)
            else:
                print(f"    ❌ {provider_name} error {r.status_code}: {r.text[:200]}")
                time.sleep(2)
        except Exception as e:
            if "RATE_LIMIT_LONG" in str(e):
                raise
            print(f"    ❌ {provider_name} connection error: {e}")
            time.sleep(2)
    return None


def call_gemini(user_message, retries=6):
    cerebras_wait = 0.0
    groq_wait     = 0.0

    # Try Cerebras first
    try:
        result = _call_openai_compat(
            user_message, CEREBRAS_API_KEY, CEREBRAS_URL, CEREBRAS_MODEL, "Cerebras", retries
        )
        if result is not None:
            return result
    except Exception as e:
        err = str(e)
        if "RATE_LIMIT_LONG" in err:
            cerebras_wait = float(err.split(":")[1])
            print(f"    ⚠️  Cerebras limited for {cerebras_wait:.0f}s — trying Groq...")
        else:
            print(f"    ⚠️  Cerebras failed ({e}) — trying Groq...")

    # Try Groq fallback
    try:
        result = _call_openai_compat(
            user_message, GROQ_API_KEY, GROQ_URL, GROQ_MODEL, "Groq", retries
        )
        if result is not None:
            return result
    except Exception as e:
        err = str(e)
        if "RATE_LIMIT_LONG" in err:
            groq_wait = float(err.split(":")[1])
            print(f"    ⚠️  Groq also limited for {groq_wait:.0f}s.")

    # Both limited — sleep the shorter wait then retry once
    if cerebras_wait > 0 or groq_wait > 0:
        # prefer Cerebras wait if shorter, else Groq
        candidates = [w for w in [cerebras_wait, groq_wait] if w > 0]
        sleep_s = min(candidates) + 5
        print(f"    😴 Both providers limited. Sleeping {sleep_s:.0f}s then retrying...")
        time.sleep(sleep_s)
        # One more attempt after sleep
        try:
            return _call_openai_compat(
                user_message, CEREBRAS_API_KEY, CEREBRAS_URL, CEREBRAS_MODEL, "Cerebras", 3
            )
        except Exception:
            pass
        try:
            return _call_openai_compat(
                user_message, GROQ_API_KEY, GROQ_URL, GROQ_MODEL, "Groq", 3
            )
        except Exception:
            pass

    return None


call_groq = call_gemini


# ─── Question Validation ──────────────────────────────────────────────────────

PLACEHOLDER_OPTIONS = {"option a", "option b", "option c", "option d"}

def is_valid_parsed_question(q_obj):
    """
    Strict client-side validation before pushing to Firestore.
    Returns (bool, reason_string).
    """
    text = q_obj.get("questionText", "").strip()
    options = q_obj.get("options", [])
    cidx = q_obj.get("correctOptionIndex", -1)

    if len(text) < 15:
        return False, f"Question text too short ({len(text)} chars)"

    # Check for leaked noise in the parsed questionText (last-resort guard)
    _LEAKED_IN_QTEXT = [
        re.compile(r'^\s*:\s*[A-D1-4]', re.IGNORECASE),             # starts with ": B"
        re.compile(r'numer\s*i\s*cal\s+a\s+\w', re.IGNORECASE),     # "NUMERI CAL A coin..."
        re.compile(r'master\s+practice\s+workbook', re.IGNORECASE),
        re.compile(r'refer\s+to\s+standard\s+textbooks', re.IGNORECASE),
        re.compile(r'note:\s*for\s+short\s+answer', re.IGNORECASE),
        re.compile(r'page\s+\d+\s+jee', re.IGNORECASE),
        re.compile(r'mathongo', re.IGNORECASE),
        re.compile(r'click\s+here\s+to\s+download', re.IGNORECASE),
    ]
    for pat in _LEAKED_IN_QTEXT:
        if pat.search(text):
            return False, f"Leaked noise in questionText"

    for pat in QUESTION_BAD_PATTERNS:
        if pat.search(text):
            return False, f"Bad pattern in question text"

    if len(options) != 4:
        return False, f"Expected 4 options, got {len(options)}"

    opt_lower = {o.lower().strip() for o in options}
    if opt_lower == PLACEHOLDER_OPTIONS or opt_lower.issubset(PLACEHOLDER_OPTIONS):
        return False, "Placeholder options detected"

    # Reject if any option literally says "option a/b/c/d" (placeholder Groq invented)
    for i, opt in enumerate(options):
        if re.match(r'^option\s+[a-d]$', opt.strip(), re.IGNORECASE):
            return False, f"Placeholder option at index {i}"

    # Check for very short options (likely parse failure)
    for i, opt in enumerate(options):
        if len(opt.strip()) < 1:
            return False, f"Option {i} is empty"

    if not isinstance(cidx, int) or cidx < 0 or cidx > 3:
        return False, f"Invalid correctOptionIndex: {cidx}"

    return True, ""


# ─── Firestore Upload ─────────────────────────────────────────────────────────

_db = None

def get_db():
    global _db
    if _db is None:
        if not FIREBASE_AVAILABLE:
            raise RuntimeError("firebase_admin not installed. Run: pip install firebase-admin")
        if not os.path.exists(SERVICE_KEY_PATH):
            raise RuntimeError(f"serviceAccountKey.json not found at {SERVICE_KEY_PATH}")
        if not firebase_admin._apps:
            cred = credentials.Certificate(SERVICE_KEY_PATH)
            firebase_admin.initialize_app(cred)
        _db = firestore.client()
    return _db

def generate_doc_id(q):
    content = f"JEE_{q['subject']}_{q['questionText'][:120]}"
    return "q_" + hashlib.md5(content.encode("utf-8")).hexdigest()

PACK_IDS = {
    "Physics":   "jee_physics_pack",
    "Chemistry": "jee_chemistry_pack",
    "Maths":     "jee_maths_pack",
}

def push_to_firestore(questions, dry_run=False):
    """Upload a batch of question dicts to Firestore. Returns (uploaded, skipped)."""
    if not questions:
        return 0, 0

    if dry_run:
        print(f"    [DRY RUN] Would push {len(questions)} questions to Firestore.")
        return len(questions), 0

    db = get_db()
    col = db.collection("questions")
    batch = db.batch()
    count = 0
    skipped = 0

    for q in questions:
        doc_id = generate_doc_id(q)
        # Check for duplicates by doc_id (Firestore will silently overwrite with set)
        batch.set(col.document(doc_id), q)
        count += 1
        if count % 499 == 0:
            batch.commit()
            batch = db.batch()

    if count % 499 != 0:
        batch.commit()

    # Bump metadata version
    try:
        db.collection("metadata").document("question_bank").set({
            "version": firestore.Increment(1),
            "lastUpdated": firestore.SERVER_TIMESTAMP
        }, merge=True)
    except Exception as e:
        print(f"    ⚠️ Could not update metadata version: {e}")

    return count, skipped


# ─── Per-PDF Processing ───────────────────────────────────────────────────────

def process_single_pdf(pdf_path, subject, dry_run=False):
    """
    Full pipeline for one PDF:
      extract → split → Groq parse → validate → push
    Returns number of successfully pushed questions.
    """
    print(f"\n{'─'*70}")
    print(f"📄  {os.path.basename(pdf_path)}  [{subject}]")

    chapter = resolve_chapter(pdf_path, subject)
    year    = extract_year_from_path(pdf_path)

    if chapter is None:
        print(f"  ⚠️  Could not map filename to official chapter. Skipping.")
        return 0

    print(f"  📚 Chapter: {chapter}  |  Year: {year}")

    # 1. Extract text
    try:
        q_text, ak_text, sol_text = extract_pdf_sections(pdf_path)
    except Exception as e:
        print(f"  ❌ PDF extraction failed: {e}")
        return 0

    # 2. Parse structure
    answers  = parse_answer_key(ak_text)
    q_blocks = split_into_question_blocks(q_text)
    sol_map  = split_solutions(sol_text)

    if not answers:
        print(f"  ⚠️  No answer key entries found. Skipping PDF.")
        return 0

    print(f"  🔢 Questions in PDF: {len(q_blocks)} | Answer keys: {len(answers)} | Solutions: {len(sol_map)}")

    good_questions = []
    parse_errors   = 0

    for q_num in sorted(answers.keys()):
        raw_q   = q_blocks.get(q_num, "").strip()
        raw_sol = sol_map.get(q_num, "").strip()
        ans_val = answers[q_num]

        if not raw_q or len(raw_q) < 10:
            print(f"  ⚠️  Q{q_num}: No usable question text found in extraction. Skip.")
            continue

        # Strip section header prefixes (e.g. "NUMERI CAL", "Integer Type:") from start
        raw_q = clean_question_block(raw_q)

        if not raw_q or len(raw_q) < 10:
            print(f"  ⚠️  Q{q_num}: Empty after cleaning header noise. Skip.")
            continue

        # Pre-filter: reject blocks that are pure noise/answer-key residue
        if any(p.search(raw_q) for p in QUESTION_BAD_PATTERNS):
            print(f"  🚫 Q{q_num}: Pre-filter rejected (noise/header text). Skip.")
            continue

        print(f"  ⌛ Q{q_num} → Gemini...", end=" ", flush=True)

        prompt = (
            f"QUESTION Q{q_num}:\n{raw_q}\n\n"
            f"ANSWER KEY: {ans_val}\n\n"
            f"SOLUTION:\n{raw_sol if raw_sol else 'Not available.'}"
        )

        raw_response = call_groq(prompt)
        if not raw_response:
            print("❌ (API error)")
            parse_errors += 1
            time.sleep(1)
            continue

        # Strip any accidental markdown code fences
        clean_resp = re.sub(r'^```(?:json)?\s*|\s*```$', '', raw_response, flags=re.MULTILINE).strip()
        # Fix LaTeX backslashes that Groq emits as single \ (invalid in JSON)
        clean_resp = fix_ai_json(clean_resp)

        q_obj = None
        try:
            q_obj = json.loads(clean_resp)
        except json.JSONDecodeError:
            # Still invalid after backslash repair — likely genuinely truncated response
            print("↩️  (retry — bad JSON)...", end=" ", flush=True)
            retry_prompt = (
                f"Your previous response was not valid JSON. "
                f"Output ONLY a single valid JSON object for this question. "
                f"Use double backslash \\\\\\\\ for ALL LaTeX (\\\\frac, \\\\alpha, \\\\sqrt etc).\n\n"
                f"ORIGINAL PROMPT:\n{prompt}\n\n"
                f"YOUR PREVIOUS (INVALID) RESPONSE WAS:\n{clean_resp[:300]}"
            )
            raw_response2 = call_groq(retry_prompt)
            if raw_response2:
                clean_resp2 = re.sub(r'^```(?:json)?\s*|\s*```$', '', raw_response2, flags=re.MULTILINE).strip()
                clean_resp2 = fix_ai_json(clean_resp2)
                try:
                    q_obj = json.loads(clean_resp2)
                    print("✅", end=" ", flush=True)
                except json.JSONDecodeError as e2:
                    print(f"❌ (still bad JSON: {e2})")
                    parse_errors += 1
                    time.sleep(1)
                    continue
            else:
                print("❌ (retry API error)")
                parse_errors += 1
                time.sleep(1)
                continue

        if q_obj is None:
            parse_errors += 1
            continue

        # AI flagged it as garbage
        if q_obj.get("skip"):
            print("🚫 (AI skipped — garbled text)")
            continue

        # Attach metadata
        q_obj["examType"]       = "JEE"
        q_obj["subject"]        = subject
        q_obj["chapter"]        = chapter
        q_obj["year"]           = year
        q_obj["isPremium"]      = True
        q_obj["isDailyVault"]   = False
        q_obj["packId"]         = PACK_IDS.get(subject, "allaccessyearly")
        q_obj.pop("isNumerical", None)  # internal flag, not needed in DB

        # Strict validation
        valid, reason = is_valid_parsed_question(q_obj)
        if not valid:
            print(f"🚫 (Validation failed: {reason})")
            parse_errors += 1
            continue

        good_questions.append(q_obj)
        print("✅")
        time.sleep(4)  # Groq rate limit buffer (spaced out for free tiers)

    print(f"\n  📊 Valid: {len(good_questions)} | Errors/Skipped: {parse_errors}")

    if not good_questions:
        return 0

    # 3. Push to Firestore
    uploaded, _ = push_to_firestore(good_questions, dry_run=dry_run)
    if not dry_run:
        print(f"  ☁️  Uploaded {uploaded} questions to Firestore [{chapter}].")
    return uploaded


# ─── PDF Discovery ────────────────────────────────────────────────────────────

def discover_pdfs(subject_filter=None):
    """Walk PDF_ROOT and return [(pdf_path, subject)] list."""
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
                # Skip only if a clean version exists in the same folder.
                # Many year folders (2020-2023) have ONLY suffixed versions — those are
                # NOT duplicates and must not be skipped.
                if "click_here_to_download" in fname.lower():
                    clean_name = re.sub(r'(_click_here_to_download)+', '', fname, flags=re.IGNORECASE)
                    if clean_name.lower() != fname.lower() and os.path.exists(os.path.join(root, clean_name)):
                        continue
                results.append((os.path.join(root, fname), subj))

    return results


# ─── Main ─────────────────────────────────────────────────────────────────────

def process_from_text(json_path, dry_run=False):
    """
    Stage-2 alternative: process a pre-extracted JSON file (from pdf_extractor.py)
    instead of reading the PDF directly. Same AI + validation + Firestore logic.
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    subject = data["subject"]
    chapter = data["chapter"]
    year    = data["year"]
    pdf_name = data["pdf_name"]
    questions_raw = data["questions"]

    print(f"\n{'─'*70}")
    print(f"📄  {pdf_name}  [{subject}]  (from extracted text)")
    print(f"  📚 Chapter: {chapter}  |  Year: {year}")
    print(f"  🔢 Questions in file: {len(questions_raw)}")

    good_questions = []
    parse_errors   = 0

    for q in questions_raw:
        q_num   = q["q_num"]
        raw_q   = q["raw_text"].strip()
        ans_val = q["answer_key"]
        raw_sol = q.get("solution", "").strip()

        print(f"  ⌛ Q{q_num} → Gemini...", end=" ", flush=True)

        prompt = (
            f"QUESTION Q{q_num}:\n{raw_q}\n\n"
            f"ANSWER KEY: {ans_val}\n\n"
            f"SOLUTION:\n{raw_sol if raw_sol else 'Not available.'}"
        )

        raw_response = call_groq(prompt)
        if not raw_response:
            print("❌ (API error)")
            parse_errors += 1
            time.sleep(1)
            continue

        clean_resp = re.sub(r'^```(?:json)?\s*|\s*```$', '', raw_response, flags=re.MULTILINE).strip()
        clean_resp = fix_ai_json(clean_resp)

        q_obj = None
        try:
            q_obj = json.loads(clean_resp)
        except json.JSONDecodeError:
            print("↩️  (retry — bad JSON)...", end=" ", flush=True)
            retry_prompt = (
                f"Your previous response was not valid JSON. "
                f"Output ONLY a single valid JSON object for this question. "
                f"Use double backslash \\\\\\\\ for ALL LaTeX (\\\\frac, \\\\alpha, \\\\sqrt etc).\n\n"
                f"ORIGINAL PROMPT:\n{prompt}\n\n"
                f"YOUR PREVIOUS (INVALID) RESPONSE WAS:\n{clean_resp[:300]}"
            )
            raw_response2 = call_groq(retry_prompt)
            if raw_response2:
                clean_resp2 = re.sub(r'^```(?:json)?\s*|\s*```$', '', raw_response2, flags=re.MULTILINE).strip()
                clean_resp2 = fix_ai_json(clean_resp2)
                try:
                    q_obj = json.loads(clean_resp2)
                    print("✅", end=" ", flush=True)
                except json.JSONDecodeError as e2:
                    print(f"❌ (still bad JSON: {e2})")
                    parse_errors += 1
                    time.sleep(1)
                    continue
            else:
                print("❌ (retry API error)")
                parse_errors += 1
                time.sleep(1)
                continue

        if q_obj is None:
            parse_errors += 1
            continue

        if q_obj.get("skip"):
            print("🚫 (AI skipped — garbled text)")
            continue

        q_obj["examType"]     = "JEE"
        q_obj["subject"]      = subject
        q_obj["chapter"]      = chapter
        q_obj["year"]         = year
        q_obj["isPremium"]    = True
        q_obj["isDailyVault"] = False
        q_obj["packId"]       = PACK_IDS.get(subject, "allaccessyearly")
        q_obj.pop("isNumerical", None)

        valid, reason = is_valid_parsed_question(q_obj)
        if not valid:
            print(f"🚫 (Validation failed: {reason})")
            parse_errors += 1
            continue

        good_questions.append(q_obj)
        print("✅")
        time.sleep(4)

    print(f"\n  📊 Valid: {len(good_questions)} | Errors/Skipped: {parse_errors}")

    if not good_questions:
        return 0

    uploaded, _ = push_to_firestore(good_questions, dry_run=dry_run)
    if not dry_run:
        print(f"  ☁️  Uploaded {uploaded} questions to Firestore [{chapter}].")
    return uploaded


def discover_extracted(subject_filter=None):
    """Return list of (json_path, subject) from the extracted/ directory."""
    extract_dir = os.path.join(SCRIPT_DIR, "extracted")
    results     = []
    subjects    = ["Maths", "Physics", "Chemistry"]
    if subject_filter:
        subjects = [s for s in subjects if s.lower() == subject_filter.lower()]
    for subj in subjects:
        subj_dir = os.path.join(extract_dir, subj)
        if not os.path.isdir(subj_dir):
            print(f"⚠️  No extracted folder: {subj_dir}  (run pdf_extractor.py first)")
            continue
        for fname in sorted(os.listdir(subj_dir)):
            if fname.endswith(".json"):
                results.append((os.path.join(subj_dir, fname), subj))
    return results


def _print_api_limits():
    """Ping Cerebras and Groq with a 1-token request and print quota headers."""
    def _ping(name, api_key, url, model):
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        body = {
            "model": model,
            "messages": [{"role": "user", "content": "hi"}],
            "max_tokens": 1,
        }
        try:
            r = requests.post(url, headers=headers, json=body, timeout=15)
            h = r.headers
            limit_req  = h.get("x-ratelimit-limit-requests",     "?")
            remain_req = h.get("x-ratelimit-remaining-requests",  "?")
            reset_req  = h.get("x-ratelimit-reset-requests",      "?")
            limit_tok  = h.get("x-ratelimit-limit-tokens",        "?")
            remain_tok = h.get("x-ratelimit-remaining-tokens",    "?")
            reset_tok  = h.get("x-ratelimit-reset-tokens",        "?")
            status     = "✅" if r.status_code in (200, 429) else f"⚠️  HTTP {r.status_code}"
            print(f"\n{'─'*50}")
            print(f"  {name}  {status}")
            print(f"  Requests : {remain_req} / {limit_req}  (resets in {reset_req})")
            print(f"  Tokens   : {remain_tok} / {limit_tok}  (resets in {reset_tok})")
            if r.status_code == 429:
                retry = h.get("retry-after", "?")
                print(f"  ⚠️  Currently rate-limited — retry-after: {retry}s")
        except Exception as e:
            print(f"\n  {name} — connection error: {e}")

    print("🔍  Checking API rate limits...\n")
    _ping("Cerebras", CEREBRAS_API_KEY, CEREBRAS_URL, CEREBRAS_MODEL)
    _ping("Groq    ", GROQ_API_KEY,     GROQ_URL,     GROQ_MODEL)
    print(f"\n{'─'*50}\n")


def main():
    parser = argparse.ArgumentParser(description="Groq AI JEE PDF → Firestore pipeline")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all",          action="store_true", help="Process all PDFs in all subjects")
    group.add_argument("--subject",      metavar="SUBJECT",   help="Process all PDFs for one subject (Maths|Physics|Chemistry)")
    group.add_argument("--file",         metavar="PATH",      help="Process a single PDF file")
    group.add_argument("--from-text",    action="store_true", help="Read from pre-extracted JSON files (run pdf_extractor.py first)")
    group.add_argument("--check-limits", action="store_true", help="Ping Cerebras & Groq and show remaining quota headers")

    parser.add_argument("--dry-run",        action="store_true", help="Parse but don't push to Firestore")
    parser.add_argument("--reset-progress", action="store_true", help="Ignore progress file and reprocess everything")
    parser.add_argument("--limit",          type=int, default=0,  help="Max number of PDFs to process (0 = unlimited)")
    parser.add_argument("--text-subject",   metavar="SUBJECT",    help="Subject filter when using --from-text")

    args = parser.parse_args()

    if args.check_limits:
        _print_api_limits()
        return

    progress = {} if args.reset_progress else load_progress()

    if args.dry_run:
        print("🔎  DRY-RUN MODE — no Firestore writes will occur.\n")

    # ── Stage-2 mode: read from pre-extracted JSON files ──────────────────────
    if args.from_text:
        subj_filter = getattr(args, 'text_subject', None) or (args.subject if hasattr(args, 'subject') else None)
        json_files  = discover_extracted(subject_filter=subj_filter)
        total_files = len(json_files)
        print(f"📂 Found {total_files} extracted JSON files.\n")

        grand_total = 0
        processed   = 0

        for i, (json_path, subj) in enumerate(json_files):
            key = os.path.abspath(json_path)
            if args.limit and processed >= args.limit:
                print(f"\n🛑 Reached --limit {args.limit}. Stopping.")
                break
            if key in progress and not args.reset_progress:
                info = progress[key]
                print(f"⏭️  [{i+1}/{total_files}] Already done ({info.get('questions_added',0)} q) — {os.path.basename(json_path)}")
                continue

            count = process_from_text(json_path, dry_run=args.dry_run)
            grand_total += count
            processed   += 1

            if count > 0 and not args.dry_run:
                progress[key] = {"questions_added": count, "done_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
                save_progress(progress)

        print(f"\n{'═'*70}")
        print(f"🎉  ALL DONE!  Total questions pushed this run: {grand_total}")
        print(f"    Files processed: {processed} / {total_files}")
        print(f"{'═'*70}")
        return

    if args.file:
        if not os.path.exists(args.file):
            print(f"❌ File not found: {args.file}")
            sys.exit(1)
        # Detect subject from path
        subj = "Maths"
        for s in ["Physics", "Chemistry", "Maths"]:
            if s.lower() in args.file.lower():
                subj = s
                break
        count = process_single_pdf(args.file, subj, dry_run=args.dry_run)
        if count > 0 and not args.dry_run:
            mark_done(progress, args.file, count)
        print(f"\n✅ Done — {count} questions pushed.")
        return

    # Bulk mode
    pdfs = discover_pdfs(subject_filter=args.subject if not args.all else None)
    total_pdfs = len(pdfs)
    print(f"📁 Found {total_pdfs} PDF files to process.\n")

    grand_total = 0
    processed   = 0

    for i, (pdf_path, subj) in enumerate(pdfs):
        if args.limit and processed >= args.limit:
            print(f"\n🛑 Reached --limit {args.limit}. Stopping.")
            break

        if is_done(progress, pdf_path):
            info = progress[os.path.abspath(pdf_path)]
            print(f"⏭️  [{i+1}/{total_pdfs}] Already done ({info['questions_added']} q) — {os.path.basename(pdf_path)}")
            continue

        count = process_single_pdf(pdf_path, subj, dry_run=args.dry_run)
        grand_total += count
        processed   += 1

        if count > 0 and not args.dry_run:
            mark_done(progress, pdf_path, count)

    print(f"\n{'═'*70}")
    print(f"🎉  ALL DONE!  Total questions pushed this run: {grand_total}")
    print(f"    PDFs newly processed: {processed} / {total_pdfs}")
    if not args.dry_run and grand_total > 0:
        print(f"    Firestore metadata version incremented — app will auto-sync on next launch.")
    print(f"{'═'*70}")


if __name__ == "__main__":
    main()
