"""
curriculum.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Curriculum & Question-Mode Engine for JEE Main 2026 & NEET UG 2026.

Implements the 5-level syllabus taxonomy:
  exam -> subject -> official_unit -> topic -> question_modes

4 Question Modes:
  - TEXT (T)      : Concept, theory, definitions, statement analysis
  - NUMERICAL (N) : Calculations, formula evaluation, dual-pass math check
  - DIAGRAM (D)   : Deterministic vector plots, ray optics, circuits, graphs
  - STRUCTURE (S) : Chemical/biological structures, reaction mechanisms

Key Principles:
1. No free-form AI image generation (strictly deterministic or authentic PYQ).
2. Curricular gate: AI generation prompts are constrained to official topics.
3. Safe chapter matching: maps legacy NCERT chapter strings to official units.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import json
import os
import re
from typing import Any, Optional

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CURRICULUM_PATH = os.path.join(SCRIPT_DIR, "curriculum.json")

_CACHED_CURRICULUM: Optional[dict[str, Any]] = None


def get_curriculum() -> dict[str, Any]:
    """Loads and caches curriculum.json."""
    global _CACHED_CURRICULUM
    if _CACHED_CURRICULUM is None:
        if not os.path.exists(CURRICULUM_PATH):
            raise FileNotFoundError(f"Curriculum file not found: {CURRICULUM_PATH}")
        with open(CURRICULUM_PATH, "r", encoding="utf-8") as f:
            _CACHED_CURRICULUM = json.load(f)
    return _CACHED_CURRICULUM


def _normalize(s: str) -> str:
    """Normalize string for fuzzy comparison: lowercased, stripped, no punctuation."""
    if not s:
        return ""
    s = s.lower().replace("&", "and").replace("-", " ")
    return re.sub(r"[^a-z0-9 ]", "", s).strip()


def get_official_units(exam: str, subject: str) -> list[dict[str, Any]]:
    """Returns list of official unit objects for an exam and subject."""
    cur = get_curriculum()
    exam_data = cur.get("curriculum", {}).get(exam.upper(), {})
    subject_data = exam_data.get(subject.capitalize(), {})
    if not subject_data:
        # Fallback case-insensitive search
        for s_key, s_val in exam_data.items():
            if _normalize(s_key) == _normalize(subject):
                subject_data = s_val
                break
    return subject_data.get("units", [])


def _norm_tokens(s: str) -> set[str]:
    """Extracts significant stemmed word tokens (stripping trailing 's' for plural normalization)."""
    s = s.lower().replace("&", "and").replace("-", " ")
    s = re.sub(r"[^a-z0-9 ]", "", s)
    return set(re.sub(r"s$", "", w) for w in s.split() if len(w) > 2)


def find_unit(exam: str, subject: str, unit_or_chapter: str) -> Optional[dict[str, Any]]:
    """
    Finds an official unit given a unit name, NCERT chapter alias, or legacy chapter.
    Returns unit dict or None.
    """
    units = get_official_units(exam, subject)
    norm_query = _normalize(unit_or_chapter)
    if not norm_query:
        return None

    # 1. Exact or normalized match on unit_name
    for u in units:
        if _normalize(u.get("unit_name", "")) == norm_query:
            return u

    # 2. Match against ncert_chapters aliases
    for u in units:
        for alias in u.get("ncert_chapters", []):
            if _normalize(alias) == norm_query:
                return u

    # 3. Substring / token containment match
    for u in units:
        norm_unit = _normalize(u.get("unit_name", ""))
        if norm_query in norm_unit or norm_unit in norm_query:
            return u
        for alias in u.get("ncert_chapters", []):
            norm_alias = _normalize(alias)
            if norm_query in norm_alias or norm_alias in norm_query:
                return u

    # 4. Token overlap match (handles plurals, word reordering, e.g. "Cell : Structure and Functions")
    query_tokens = _norm_tokens(unit_or_chapter)
    if query_tokens:
        best_unit = None
        best_score = 0.0
        for u in units:
            unit_tokens = _norm_tokens(u.get("unit_name", ""))
            if unit_tokens:
                overlap = len(query_tokens & unit_tokens) / max(len(query_tokens), len(unit_tokens))
                if overlap > best_score:
                    best_score = overlap
                    best_unit = u
            for alias in u.get("ncert_chapters", []):
                alias_tokens = _norm_tokens(alias)
                if alias_tokens:
                    overlap = len(query_tokens & alias_tokens) / max(len(query_tokens), len(alias_tokens))
                    if overlap > best_score:
                        best_score = overlap
                        best_unit = u
        if best_score >= 0.6:
            return best_unit

    return None


def get_unit_topics(exam: str, subject: str, unit_or_chapter: str) -> list[dict[str, Any]]:
    """Returns list of topic objects for a given unit/chapter."""
    unit = find_unit(exam, subject, unit_or_chapter)
    if not unit:
        return []
    return unit.get("topics", [])


def find_topic(exam: str, subject: str, unit_or_chapter: str, topic_query: str) -> Optional[dict[str, Any]]:
    """Finds a specific topic within a unit."""
    topics = get_unit_topics(exam, subject, unit_or_chapter)
    norm_query = _normalize(topic_query)
    for t in topics:
        norm_t = _normalize(t.get("topic", ""))
        if norm_t == norm_query or norm_query in norm_t or norm_t in norm_query:
            return t
    return None


def get_allowed_modes(exam: str, subject: str, unit_or_chapter: str, topic_query: Optional[str] = None) -> list[str]:
    """
    Returns allowed modes (subset of ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"]).
    If topic_query is provided, returns that topic's allowed modes.
    Otherwise, returns the union of allowed modes for the entire unit.
    """
    if topic_query:
        topic = find_topic(exam, subject, unit_or_chapter, topic_query)
        if topic:
            return topic.get("allowed_modes", ["TEXT"])

    unit = find_unit(exam, subject, unit_or_chapter)
    if not unit:
        # Defaults based on subject
        subj_norm = _normalize(subject)
        if "math" in subj_norm:
            return ["TEXT", "NUMERICAL"]
        elif "physic" in subj_norm:
            return ["TEXT", "NUMERICAL", "DIAGRAM"]
        elif "chem" in subj_norm:
            return ["TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"]
        elif "bio" in subj_norm:
            return ["TEXT", "DIAGRAM", "STRUCTURE"]
        return ["TEXT", "NUMERICAL"]

    modes_union = set()
    for t in unit.get("topics", []):
        for m in t.get("allowed_modes", []):
            modes_union.add(m)
    return sorted(list(modes_union))


def get_preferred_mode(exam: str, subject: str, unit_or_chapter: str, topic_query: Optional[str] = None) -> str:
    """Returns preferred question mode."""
    if topic_query:
        topic = find_topic(exam, subject, unit_or_chapter, topic_query)
        if topic and topic.get("preferred_mode"):
            return topic["preferred_mode"]

    unit = find_unit(exam, subject, unit_or_chapter)
    if unit and unit.get("topics"):
        # Frequency of preferred modes
        counts: dict[str, int] = {}
        for t in unit["topics"]:
            p = t.get("preferred_mode", "TEXT")
            counts[p] = counts.get(p, 0) + 1
        return max(counts, key=counts.get)

    return "TEXT"


def get_prompt_constraints(exam: str, subject: str, unit_or_chapter: str, topic_query: Optional[str] = None) -> str:
    """
    Generates strict prompt injection text enforcing syllabus adherence.
    """
    unit = find_unit(exam, subject, unit_or_chapter)
    unit_name = unit.get("unit_name", unit_or_chapter) if unit else unit_or_chapter
    topic = find_topic(exam, subject, unit_or_chapter, topic_query) if topic_query else None
    topic_name = topic.get("topic", "") if topic else ""

    allowed_modes = get_allowed_modes(exam, subject, unit_or_chapter, topic_query)
    pref_mode = get_preferred_mode(exam, subject, unit_or_chapter, topic_query)

    topics_summary = ""
    if topic_name:
        topics_summary = f"Specific Topic: {topic_name}\n"
    elif unit and unit.get("topics"):
        top_names = [t.get("topic") for t in unit["topics"][:5]]
        topics_summary = f"Key Unit Topics: {', '.join(top_names)}\n"

    lines = [
        f"Curriculum Constraint:",
        f"Exam: {exam.upper()}",
        f"Subject: {subject.capitalize()}",
        f"Official Unit: {unit_name}",
        topics_summary.strip(),
        f"Allowed Question Modes: {', '.join(allowed_modes)}",
        f"Preferred Mode: {pref_mode}",
        f"Strict Invariants:",
        f"- Do NOT generate questions outside these syllabus topics.",
        f"- Do NOT introduce unsupported concepts or out-of-syllabus terms.",
        f"- Strictly NO decorative or free-form AI image generation.",
        f"- All math equations MUST use clean KaTeX LaTeX syntax (e.g. \\( x^2 + y^2 = r^2 \\)).",
        f"- NEVER use \\ce{{}} mhchem notation. Write chemical formulas as plain text/KaTeX (e.g. H_2O, CuSO_4)."
    ]
    return "\n".join([line for line in lines if line])


def detect_question_mode(q: dict[str, Any]) -> str:
    """
    Detects whether a question is DIAGRAM, STRUCTURE, NUMERICAL, or TEXT.
    """
    image_url = q.get("imageUrl") or ""
    q_text = str(q.get("questionText", ""))
    expl = str(q.get("explanation", ""))
    options = [str(o) for o in q.get("options", [])]
    combined = f"{q_text} {' '.join(options)} {expl}"

    # 0. Explicit questionMode if valid
    explicit_mode = q.get("questionMode") or q.get("question_mode")
    if explicit_mode in {"TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"}:
        return str(explicit_mode)

    # 1. Image present
    if image_url:
        # Check if chemical structure or visual diagram
        if any(chem_term in combined.lower() for chem_term in ["benzene", "iupac", "reaction", "mechanism", "isomer", "hybridisation", "conformation", "enantiomer"]):
            return "STRUCTURE"
        return "DIAGRAM"

    # 2. Chemical/Biological structure in text/formula
    chem_structure_patterns = [
        r"\b(sp[23]?|sp3d[2]?)\b",
        r"\b(cis|trans|meso|dextro|laevo)\b",
        r"\b(carbocation|carbanion|free radical)s?\b",
        r"\b(electrophile|nucleophile)s?\b",
        r"\b(hyperconjugation|aromaticity|hybridisation|isomerism|conformation)s?\b",
        r"->|-->|\\rightarrow|\\rightleftharpoons",
        r"\b(haworth|chair form|boat form|newman|sawhorse)\b",
        r"\b(dna|rna|peptide bond|zwitterion)s?\b"
    ]
    for pat in chem_structure_patterns:
        if re.search(pat, combined, re.IGNORECASE):
            return "STRUCTURE"

    # 3. Numerical calculation
    # Checks for numeric options or calculation keywords
    numeric_opt_count = 0
    for opt in options:
        # Strip KaTeX math spacing like \,, \;, \!, \quad
        clean_opt = re.sub(r"\\[,;!]", "", opt)
        # Strip macros like \mathrm{...}, \text{...}, \mathbf{...}
        clean_opt = re.sub(r"\\(mathrm|text|mathbf|bf)\{[^}]*\}", "", clean_opt)
        # Strip any remaining LaTeX commands like \times, \pm, etc.
        clean_opt = re.sub(r"\\[a-zA-Z]+", "", clean_opt)
        # Strip formatting delimiters $, (, ), {, }, \, whitespace, commas
        clean_opt = re.sub(r"[\$\(\)\{\}\\\s,;]", "", clean_opt)
        # Strip trailing unit symbols
        clean_opt = re.sub(r"[a-zA-Z/%Ωμ°^~]+$", "", clean_opt).strip()
        if re.match(r"^[-+]?[0-9]*\.?[0-9]+([eE][-+]?[0-9]+)?$", clean_opt) and clean_opt:
            numeric_opt_count += 1

    math_calc_keywords = [
        r"\bcalculate\b", r"\bfind the value\b", r"\bmagnitude\b",
        r"\bequal to\b", r"\bratio\b", r"\bpercentage\b", r"\bwork done\b",
        r"\bvelocity\b", r"\bacceleration\b", r"\bresistance\b", r"\bcapacitance\b"
    ]
    has_calc_keyword = any(re.search(kw, q_text, re.IGNORECASE) for kw in math_calc_keywords)

    if numeric_opt_count >= 3 or (has_calc_keyword and numeric_opt_count >= 2):
        return "NUMERICAL"

    # 4. Fallback to TEXT
    return "TEXT"


def validate_question_against_curriculum(q: dict[str, Any]) -> tuple[bool, str]:
    """
    Validates a question against curriculum taxonomy:
    - Subject must exist
    - Chapter must resolve to an official unit
    - Detected mode must be in allowed_modes for that unit
    - No fake AI image urls allowed
    """
    exam = q.get("examType", "").upper()
    subject = q.get("subject", "").capitalize()
    chapter = q.get("chapter", "").strip()

    if exam not in ["JEE", "NEET"]:
        return False, f"Invalid examType: {exam}"

    units = get_official_units(exam, subject)
    if not units:
        return False, f"Unknown subject '{subject}' for exam '{exam}'"

    unit = find_unit(exam, subject, chapter)
    if not unit:
        return False, f"Chapter '{chapter}' does not map to any official unit in {exam} {subject}"

    mode = detect_question_mode(q)
    allowed_modes = get_allowed_modes(exam, subject, chapter)

    # Free-form AI image check
    img_url = q.get("imageUrl", "")
    if img_url:
        # Must be hosted on our Firebase Storage or validated CDN
        if not ("firebasestorage.googleapis.com" in img_url or "apps-273d9.firebasestorage.app" in img_url):
            return False, f"Disallowed external image URL: {img_url}"

    # If mode is not strictly in allowed modes, allow TEXT fallback if question has no image
    if mode not in allowed_modes:
        if mode == "NUMERICAL" and "TEXT" in allowed_modes:
            pass # Tolerant of text questions containing numbers
        else:
            return False, f"Mode '{mode}' not permitted for unit '{unit.get('unit_name')}' (Allowed: {allowed_modes})"

    return True, "Valid"


def get_power100_config(exam: str) -> dict[str, Any]:
    """
    Returns the Power 100 curriculum configuration for an exam.
    Includes total_questions, subject_distribution, max_per_unit, difficulty_weights, allowed_modes.
    """
    cur = get_curriculum()
    configs = cur.get("power100_config", {})
    norm_exam = exam.strip().upper()
    if norm_exam in ("JEE", "JEE_MAIN"):
        return configs.get("JEE", {})
    elif norm_exam in ("NEET", "NEET_UG"):
        return configs.get("NEET", {})
    return configs.get(norm_exam, {})

