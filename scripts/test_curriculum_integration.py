"""
test_curriculum_integration.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
End-to-End Integration Test Suite for Curriculum-Driven Architecture.

Validates all 11 required scenarios:
  1. JEE Physics numerical
  2. JEE Physics diagram
  3. JEE Maths graph
  4. JEE Chemistry structure
  5. NEET Biology diagram
  6. NEET Biology text question
  7. NEET numerical
  8. Invalid mode rejection
  9. Incorrect numerical answer rejection
  10. Broken diagram rejection
  11. Duplicate rejection
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import unittest
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import curriculum
import curriculum_validator
import stem_diagram_engine as sde
from vault_scheduler import question_fingerprint


class TestCurriculumIntegration(unittest.TestCase):

    def setUp(self):
        self.cur = curriculum.get_curriculum()

    # 1. JEE Physics Numerical
    def test_01_jee_physics_numerical(self):
        q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Motion In One Dimension",
            "questionText": "A car accelerates uniformly from rest at $a = 2.5\\,\\mathrm{m/s^2}$ for $t = 6\\,\\mathrm{s}$. Calculate its final velocity.",
            "options": ["$15.0\\,\\mathrm{m/s}$", "$12.0\\,\\mathrm{m/s}$", "$18.5\\,\\mathrm{m/s}$", "$20.0\\,\\mathrm{m/s}$"],
            "correctOptionIndex": 0,
            "explanation": "Using $v = u + at = 0 + 2.5 \\times 6 = 15.0\\,\\mathrm{m/s}$.",
            "params": {"a": 2.5, "t": 6.0}
        }
        solver = lambda p: abs(float(p["a"]) * float(p["t"]) - 15.0) < 1e-4
        res = curriculum_validator.validate_question(q, solver_func=solver)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")
        self.assertEqual(res.metadata["questionMode"], "NUMERICAL")
        self.assertEqual(res.metadata["officialUnit"], "Kinematics")

    # 2. JEE Physics Diagram
    def test_02_jee_physics_diagram(self):
        params = {"focal_length_cm": 20.0, "object_dist_cm": 35.0, "object_height_cm": 1.2}
        raw_bytes = sde.render_diagram_for_spec("optics", params=params)
        q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Optics",
            "questionText": "An object of height $1.2\\,\\mathrm{cm}$ is placed at $u = 35\\,\\mathrm{cm}$ in front of a thin convex lens of focal length $f = +20\\,\\mathrm{cm}$ as shown in the ray diagram. Find the image distance.",
            "options": ["$v = +46.7\\,\\mathrm{cm}$", "$v = -46.7\\,\\mathrm{cm}$", "$v = +20.0\\,\\mathrm{cm}$", "$v = -35.0\\,\\mathrm{cm}$"],
            "correctOptionIndex": 0,
            "explanation": "1/v - 1/u = 1/f -> 1/v = 1/20 - 1/35 = 3/140 -> v = +46.67 cm.",
            "params": params,
            "questionMode": "DIAGRAM"
        }
        res = curriculum_validator.validate_question(q, raw_diagram_bytes=raw_bytes)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")
        self.assertEqual(res.metadata["questionMode"], "DIAGRAM")
        self.assertEqual(res.metadata["diagramSource"], "DETERMINISTIC")

    # 3. JEE Maths Graph
    def test_03_jee_maths_graph(self):
        params = {"r": 5.0, "px": 3.0, "py": 4.0}
        raw_bytes = sde.render_diagram_for_spec("math_geometry", params=params)
        q = {
            "examType": "JEE",
            "subject": "Maths",
            "chapter": "Coordinate Geometry",
            "questionText": "A circle is given by $x^2 + y^2 = 25$ as shown in the coordinate diagram. What is the tangent equation at $P(3, 4)$?",
            "options": ["$3x + 4y = 25$", "$4x + 3y = 25$", "$3x - 4y = 25$", "$4x - 3y = 25$"],
            "correctOptionIndex": 0,
            "explanation": "Tangent to circle $x^2 + y^2 = r^2$ at $(x_1, y_1)$ is $x x_1 + y y_1 = r^2 -> 3x + 4y = 25$.",
            "params": params,
            "questionMode": "DIAGRAM"
        }
        res = curriculum_validator.validate_question(q, raw_diagram_bytes=raw_bytes)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")
        self.assertEqual(res.metadata["questionMode"], "DIAGRAM")

    # 4. JEE Chemistry Structure
    def test_04_jee_chemistry_structure(self):
        params = {"compound": "But-2-ene"}
        raw_bytes = sde.render_diagram_for_spec("organic_structure", params=params)
        q = {
            "examType": "JEE",
            "subject": "Chemistry",
            "chapter": "Organic Chemistry Basics",
            "questionText": "Examine the geometrical isomers of but-2-ene shown in the structural diagram. Which isomer is polar with a non-zero dipole moment?",
            "options": ["Cis-but-2-ene (Z)", "Trans-but-2-ene (E)", "Both isomers are non-polar", "Neither isomer has a double bond"],
            "correctOptionIndex": 0,
            "explanation": "Cis-but-2-ene has bond dipoles reinforcing on one side, giving net dipole moment mu > 0.",
            "params": params,
            "questionMode": "DIAGRAM"
        }
        res = curriculum_validator.validate_question(q, raw_diagram_bytes=raw_bytes)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")

    # 5. NEET Biology Diagram
    def test_05_neet_biology_diagram(self):
        params = {"gamete1": "A", "gamete2": "a"}
        raw_bytes = sde.render_diagram_for_spec("punnett_square", params=params)
        q = {
            "examType": "NEET",
            "subject": "Biology",
            "chapter": "Genetics",
            "questionText": "In a monohybrid cross between two heterozygous parents ($Aa \\times Aa$) shown in the Punnett square, what percentage of progeny is heterozygous ($Aa$)?",
            "options": ["$50\\%$ ($2/4$)", "$25\\%$ ($1/4$)", "$75\\%$ ($3/4$)", "$100\\%$ ($4/4$)"],
            "correctOptionIndex": 0,
            "explanation": "The cross yields 1 AA : 2 Aa : 1 aa. The heterozygous proportion is 2/4 = 50%.",
            "params": params,
            "questionMode": "DIAGRAM"
        }
        res = curriculum_validator.validate_question(q, raw_diagram_bytes=raw_bytes)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")
        self.assertEqual(res.metadata["officialUnit"], "Genetics & Evolution")

    # 6. NEET Biology Text Question
    def test_06_neet_biology_text(self):
        q = {
            "examType": "NEET",
            "subject": "Biology",
            "chapter": "Cell Biology",
            "questionText": "Which of the following cellular organelles is not bound by a membrane in eukaryotic cells?",
            "options": ["Ribosome", "Mitochondria", "Lysosome", "Chloroplast"],
            "correctOptionIndex": 0,
            "explanation": "Ribosomes are non-membrane bound organelles found in both prokaryotic and eukaryotic cells.",
            "questionMode": "TEXT"
        }
        res = curriculum_validator.validate_question(q)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")
        self.assertEqual(res.metadata["questionMode"], "TEXT")

    # 7. NEET Numerical
    def test_07_neet_numerical(self):
        q = {
            "examType": "NEET",
            "subject": "Biology",
            "chapter": "Evolution",
            "questionText": "In a population in Hardy-Weinberg equilibrium, the frequency of a recessive allele is $q = 0.4$. What is the frequency of heterozygous individuals ($2pq$)?",
            "options": ["$0.48$", "$0.36$", "$0.16$", "$0.24$"],
            "correctOptionIndex": 0,
            "explanation": "Since p + q = 1, p = 1 - 0.4 = 0.6. The frequency of heterozygotes is 2pq = 2 * 0.6 * 0.4 = 0.48.",
            "params": {"q": 0.4}
        }
        solver = lambda p: abs(2 * (1.0 - float(p["q"])) * float(p["q"]) - 0.48) < 1e-4
        res = curriculum_validator.validate_question(q, solver_func=solver)
        self.assertTrue(res.is_valid, f"Failed: {res.reason}")

    # 8. Invalid Mode Rejection
    def test_08_invalid_mode_rejection(self):
        # STRUCTURE mode is strictly forbidden in Mathematics
        q = {
            "examType": "JEE",
            "subject": "Maths",
            "chapter": "Coordinate Geometry",
            "questionText": "Find the distance between points (0, 0) and (3, 4).",
            "options": ["5", "4", "3", "7"],
            "correctOptionIndex": 0,
            "explanation": "Distance formula gives sqrt(3^2 + 4^2) = 5.",
            "questionMode": "STRUCTURE"  # Invalid!
        }
        res = curriculum_validator.validate_question(q)
        self.assertFalse(res.is_valid)
        self.assertEqual(res.failed_stage, "MODE")
        self.assertIn("forbidden", res.reason)

    # 9. Incorrect Numerical Answer Rejection
    def test_09_incorrect_numerical_answer_rejection(self):
        q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Kinematics",
            "questionText": "A particle drops from height h with initial velocity 0. Find v after 2 seconds (g = 10 m/s^2).",
            "options": ["20 m/s", "10 m/s", "30 m/s", "40 m/s"],
            "correctOptionIndex": 0,
            "explanation": "v = u + gt = 0 + 10*2 = 20 m/s",
            "params": {"g": 10, "t": 2}
        }
        # Intentional bug in solver or wrong claim: solver returns False
        wrong_solver = lambda p: False
        res = curriculum_validator.validate_question(q, solver_func=wrong_solver)
        self.assertFalse(res.is_valid)
        self.assertEqual(res.failed_stage, "ANSWER_NUMERICAL")
        self.assertIn("verification failed", res.reason)

    # 10. Broken Diagram Rejection
    def test_10_broken_diagram_rejection(self):
        # Corrupted / Truncated image bytes (< 1000 bytes)
        corrupted_bytes = b"NOT_A_REAL_IMAGE"
        q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Optics",
            "questionText": "In the ray diagram shown, find the image distance.",
            "options": ["+46.7 cm", "-46.7 cm", "+20 cm", "-35 cm"],
            "correctOptionIndex": 0,
            "explanation": "Valid explanation of convex lens image distance.",
            "questionMode": "DIAGRAM"
        }
        res = curriculum_validator.validate_question(q, raw_diagram_bytes=corrupted_bytes)
        self.assertFalse(res.is_valid)
        self.assertEqual(res.failed_stage, "DIAGRAM_STRUCTURE")
        self.assertIn("too small", res.reason)

    # 11. Duplicate Rejection
    def test_11_duplicate_rejection(self):
        q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Kinematics",
            "questionText": "A particle moves along x-axis with constant acceleration.",
            "options": ["10 m/s", "20 m/s", "30 m/s", "40 m/s"],
            "correctOptionIndex": 0,
            "explanation": "Simple constant acceleration motion problem solution."
        }
        fp = question_fingerprint(q)
        existing = {fp}  # Simulate already-seen in bank
        res = curriculum_validator.validate_question(q, existing_fingerprints=existing)
        self.assertFalse(res.is_valid)
        self.assertEqual(res.failed_stage, "DUPLICATE")
        self.assertIn("Duplicate question detected", res.reason)


if __name__ == "__main__":
    unittest.main()
