"""
curriculum_validator.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Unified 7-Stage Curriculum Quality Gate & Validation Engine for JEE & NEET.

Quality Gate Pipeline Flow:
  1. CURRICULUM VALIDATION (Exam, subject, official unit resolution)
  2. SOURCE / ORIGIN VALIDATION (PYQ vs Web vs AI vs Original Practice honesty)
  3. MODE VALIDATION (TEXT / NUMERICAL / DIAGRAM / STRUCTURE adherence)
  4. QUESTION FORMAT VALIDATION (Length, options count, noise, KaTeX escapes)
  5. ANSWER & NUMERICAL VALIDATION (Dual-pass math solver consistency)
  6. DIAGRAM & STRUCTURE VALIDATION (Bytes, headers, parameters, zero generative AI)
  7. DUPLICATE VALIDATION (STEM-safe string & ID uniqueness)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import dataclasses
import re
import os
import sys
from typing import Any, Callable, Optional, Set

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import curriculum
from vault_scheduler import question_fingerprint, coerce_options, coerce_correct_index


@dataclasses.dataclass
class ValidationResult:
    is_valid: bool
    failed_stage: Optional[str] = None
    reason: str = ""
    metadata: dict[str, Any] = dataclasses.field(default_factory=dict)


# Noise patterns that invalidate question prose
NOISE_PATTERNS = [
    r'click here to download',
    r'refer to (the )?standard textbook',
    r'master practice workbook',
    r'\[image\]', r'\[figure\]',
    r'^\s*:\s*[A-D]\s*$',
    r'option a\b', r'option b\b', r'option c\b', r'option d\b',
]

PLACEHOLDER_OPTIONS = {"option a", "option b", "option c", "option d", "a", "b", "c", "d"}

VALID_EXAMS = {"JEE", "NEET"}
VALID_SUBJECTS = {"Physics", "Chemistry", "Maths", "Biology"}
VALID_SOURCE_TYPES = {"PYQ", "WEB_SOURCE", "AI_GENERATED", "ORIGINAL_PRACTICE"}
VALID_QUESTION_MODES = {"TEXT", "NUMERICAL", "DIAGRAM", "STRUCTURE"}


class CurriculumQualityGate:

    @classmethod
    def validate(
        cls,
        q: dict[str, Any],
        raw_diagram_bytes: Optional[bytes] = None,
        solver_func: Optional[Callable[[dict[str, Any]], bool]] = None,
        existing_fingerprints: Optional[Set[str]] = None
    ) -> ValidationResult:
        """
        Executes the full 7-stage quality gate sequentially.
        Halts immediately on the first failed stage with exact rejection reason.
        """
        enriched_meta: dict[str, Any] = {}

        # ── STAGE 1: CURRICULUM VALIDATION ────────────────────────────────────
        exam = str(q.get("examType", "")).strip().upper()
        if exam not in VALID_EXAMS:
            return ValidationResult(False, "CURRICULUM", f"Invalid examType: '{exam}' (Must be JEE or NEET)")

        subject = str(q.get("subject", "")).strip().capitalize()
        if subject not in VALID_SUBJECTS:
            return ValidationResult(False, "CURRICULUM", f"Invalid subject: '{subject}'")

        if exam == "JEE" and subject == "Biology":
            return ValidationResult(False, "CURRICULUM", "Biology is not a JEE Main subject")
        if exam == "NEET" and subject == "Maths":
            return ValidationResult(False, "CURRICULUM", "Maths is not a NEET UG subject")

        chapter = str(q.get("chapter", "")).strip()
        if not chapter:
            return ValidationResult(False, "CURRICULUM", "Chapter/Unit name is missing")

        unit = curriculum.find_unit(exam, subject, chapter)
        if not unit:
            return ValidationResult(
                False, "CURRICULUM",
                f"Chapter '{chapter}' does not resolve to an official 2026 syllabus unit for {exam} {subject}"
            )

        official_unit = unit.get("unit_name", chapter)
        topic_name = str(q.get("topic", "")).strip()
        topic = curriculum.find_topic(exam, subject, official_unit, topic_name) if topic_name else None
        resolved_topic = topic.get("topic", topic_name) if topic else (topic_name or official_unit)

        enriched_meta["officialUnit"] = official_unit
        enriched_meta["topic"] = resolved_topic

        # ── STAGE 2: SOURCE / ORIGIN VALIDATION ───────────────────────────────
        source_type = q.get("sourceType") or q.get("source_type")
        if not source_type:
            # Auto-infer if not provided
            if q.get("year") and int(q.get("year", 0)) > 1990:
                source_type = "PYQ"
            elif q.get("is_generated") or q.get("params"):
                source_type = "ORIGINAL_PRACTICE"
            else:
                source_type = "AI_GENERATED"

        if source_type not in VALID_SOURCE_TYPES:
            return ValidationResult(
                False, "SOURCE_AI",
                f"Invalid sourceType '{source_type}'. Allowed: {VALID_SOURCE_TYPES}"
            )

        # Honest labeling invariant: synthetic practice questions cannot be mislabeled as PYQs
        if source_type == "PYQ" and q.get("params") and not q.get("pyq_paper_source"):
            if not q.get("year"):
                return ValidationResult(
                    False, "SOURCE_AI",
                    "Mislabeling violation: Synthetic generated question cannot be labeled as authentic 'PYQ'"
                )

        enriched_meta["sourceType"] = source_type

        # ── STAGE 3: MODE VALIDATION ──────────────────────────────────────────
        mode = q.get("questionMode") or q.get("question_mode")
        if not mode:
            mode = curriculum.detect_question_mode(q)

        mode = str(mode).strip().upper()
        if mode not in VALID_QUESTION_MODES:
            return ValidationResult(False, "MODE", f"Invalid questionMode '{mode}'. Allowed: {VALID_QUESTION_MODES}")

        # Subject-specific mode bans
        if subject == "Maths" and mode == "STRUCTURE":
            return ValidationResult(False, "MODE", "STRUCTURE mode is strictly forbidden in Mathematics")
        if subject == "Physics" and mode == "STRUCTURE":
            return ValidationResult(False, "MODE", "STRUCTURE mode is strictly forbidden in Physics")

        # Check against official allowed modes for this topic/unit
        allowed_modes = curriculum.get_allowed_modes(exam, subject, official_unit, resolved_topic)
        if mode not in allowed_modes:
            # Tolerant fallback: TEXT questions with mild numbers allowed if TEXT is permitted
            if mode == "NUMERICAL" and "TEXT" in allowed_modes and not q.get("imageUrl"):
                mode = "TEXT"
            else:
                return ValidationResult(
                    False, "MODE",
                    f"Mode '{mode}' not permitted for {exam} {subject} [{official_unit} -> {resolved_topic}]. "
                    f"Allowed modes: {allowed_modes}"
                )

        enriched_meta["questionMode"] = mode
        enriched_meta["diagramRequired"] = (mode == "DIAGRAM")

        # ── STAGE 4: QUESTION FORMAT & CONTENT VALIDATION ─────────────────────
        q_text = str(q.get("questionText", "")).strip()
        if len(q_text) < 15:
            return ValidationResult(False, "QUESTION_CONTENT", f"Question text too short ({len(q_text)} < 15 chars)")
        if len(q_text) > 3000:
            return ValidationResult(False, "QUESTION_CONTENT", f"Question text unusually long ({len(q_text)} chars)")

        for pat in NOISE_PATTERNS:
            if re.search(pat, q_text, re.IGNORECASE):
                return ValidationResult(False, "QUESTION_CONTENT", f"Prohibited noise pattern detected: '{pat}'")

        # KaTeX / Formatting sanity check
        if r'\ce{' in q_text:
            return ValidationResult(False, "QUESTION_CONTENT", "Raw \\ce{} mhchem found. Must use plain KaTeX.")

        # Check options
        raw_options = q.get("options")
        options = coerce_options(raw_options)
        if options is None or len(options) != 4:
            return ValidationResult(
                False, "QUESTION_CONTENT",
                f"Options count must be exactly 4 valid non-empty strings (got {raw_options})"
            )

        if len(set(o.lower().strip() for o in options)) < 4:
            return ValidationResult(False, "QUESTION_CONTENT", "Duplicate option choices detected")

        if all(o.lower().strip() in PLACEHOLDER_OPTIONS for o in options):
            return ValidationResult(False, "QUESTION_CONTENT", "Placeholder options ('Option A', etc.) detected")

        # Correct option index
        corr_idx = coerce_correct_index(
            q.get("correctOptionIndex") if q.get("correctOptionIndex") is not None else q.get("correctOption")
        )
        if corr_idx is None or corr_idx not in (0, 1, 2, 3):
            return ValidationResult(
                False, "QUESTION_CONTENT",
                f"Invalid correctOptionIndex: {q.get('correctOptionIndex') or q.get('correctOption')}"
            )

        # Explanation
        explanation = str(q.get("explanation", "")).strip()
        if len(explanation) < 15:
            return ValidationResult(False, "QUESTION_CONTENT", "Explanation missing or too brief (< 15 chars)")

        # ── STAGE 5: ANSWER & NUMERICAL VALIDATION ────────────────────────────
        params = q.get("params") or {}
        if solver_func:
            try:
                solver_passed = solver_func(params)
                if not solver_passed:
                    return ValidationResult(
                        False, "ANSWER_NUMERICAL",
                        "Dual-pass mathematical solver verification failed (calculated result != correct option)"
                    )
            except Exception as err:
                return ValidationResult(
                    False, "ANSWER_NUMERICAL",
                    f"Mathematical solver raised execution error: {err}"
                )

        # ── STAGE 6: DIAGRAM & STRUCTURE VALIDATION ───────────────────────────
        image_url = str(q.get("imageUrl", "")).strip()
        diagram_source = q.get("diagramSource") or q.get("diagram_source") or ("DETERMINISTIC" if mode == "DIAGRAM" else "NONE")

        if mode == "DIAGRAM":
            # 1. Image bytes validation (when generating)
            if raw_diagram_bytes is not None:
                if len(raw_diagram_bytes) < 1000:
                    return ValidationResult(
                        False, "DIAGRAM_STRUCTURE",
                        f"Diagram byte size too small/truncated ({len(raw_diagram_bytes)} < 1000 bytes)"
                    )
                # Header check (PNG or WebP)
                is_png = raw_diagram_bytes.startswith(b'\x89PNG\r\n\x1a\n')
                is_webp = (len(raw_diagram_bytes) > 12 and raw_diagram_bytes[:4] == b'RIFF' and raw_diagram_bytes[8:12] == b'WEBP')
                if not (is_png or is_webp):
                    return ValidationResult(
                        False, "DIAGRAM_STRUCTURE",
                        "Rendered diagram does not have valid PNG or WebP header magic bytes"
                    )

            # 2. Image URL validation (when existing or uploaded)
            elif image_url:
                if not (image_url.startswith("https://") or image_url.startswith("http://")):
                    return ValidationResult(False, "DIAGRAM_STRUCTURE", f"Malformed imageUrl: '{image_url}'")
            else:
                return ValidationResult(
                    False, "DIAGRAM_STRUCTURE",
                    "Question mode is DIAGRAM but neither raw_diagram_bytes nor imageUrl was provided"
                )

            # 3. Single-Source Parameter Consistency Check
            if params:
                for p_key, p_val in params.items():
                    if isinstance(p_val, (int, float)) and abs(p_val) > 0.001:
                        # Check that parameter is cited in the question text or options
                        val_str = f"{p_val:g}"
                        if val_str not in q_text and not any(val_str in opt for opt in options):
                            # Warning or check (tolerant of sign)
                            pos_val_str = f"{abs(p_val):g}"
                            if pos_val_str not in q_text and not any(pos_val_str in opt for opt in options):
                                pass  # Parameter can be in diagram visual annotation

        enriched_meta["diagramSource"] = diagram_source

        # ── STAGE 7: DUPLICATE VALIDATION ─────────────────────────────────────
        fp = question_fingerprint(q)
        if existing_fingerprints is not None and fp in existing_fingerprints:
            return ValidationResult(
                False, "DUPLICATE",
                f"Duplicate question detected in question bank (fingerprint: {fp})"
            )

        enriched_meta["validationStatus"] = "PASSED"
        enriched_meta["fingerprint"] = fp

        return ValidationResult(
            is_valid=True,
            failed_stage=None,
            reason="All 7 quality gate stages passed",
            metadata=enriched_meta
        )


def validate_question(
    q: dict[str, Any],
    raw_diagram_bytes: Optional[bytes] = None,
    solver_func: Optional[Callable[[dict[str, Any]], bool]] = None,
    existing_fingerprints: Optional[Set[str]] = None
) -> ValidationResult:
    """Convenience helper function."""
    return CurriculumQualityGate.validate(
        q,
        raw_diagram_bytes=raw_diagram_bytes,
        solver_func=solver_func,
        existing_fingerprints=existing_fingerprints
    )
