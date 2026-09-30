"""
test_curriculum.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Unit test suite verifying the official JEE Main 2026 & NEET UG 2026 Curriculum.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import unittest
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import curriculum


class TestCurriculum(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.cur = curriculum.get_curriculum()

    def test_curriculum_metadata(self):
        meta = self.cur.get("metadata", {})
        self.assertEqual(meta.get("version"), "2026.1")
        self.assertIn("TEXT", meta.get("modes_legend", {}))
        self.assertIn("NUMERICAL", meta.get("modes_legend", {}))
        self.assertIn("DIAGRAM", meta.get("modes_legend", {}))
        self.assertIn("STRUCTURE", meta.get("modes_legend", {}))

    def test_jee_maths_units_count(self):
        units = curriculum.get_official_units("JEE", "Maths")
        self.assertEqual(len(units), 14, f"JEE Maths must have exactly 14 units, found {len(units)}")
        unit_names = [u["unit_name"] for u in units]
        self.assertIn("Sets, Relations & Functions", unit_names)
        self.assertIn("Complex Numbers & Quadratic Equations", unit_names)
        self.assertIn("Coordinate Geometry", unit_names)
        self.assertIn("Trigonometry", unit_names)

    def test_jee_physics_units_count(self):
        units = curriculum.get_official_units("JEE", "Physics")
        self.assertEqual(len(units), 20, f"JEE Physics must have exactly 20 units, found {len(units)}")

    def test_jee_chemistry_units_count(self):
        units = curriculum.get_official_units("JEE", "Chemistry")
        self.assertEqual(len(units), 20, f"JEE Chemistry must have exactly 20 units, found {len(units)}")

    def test_neet_physics_units_count(self):
        units = curriculum.get_official_units("NEET", "Physics")
        self.assertEqual(len(units), 20, f"NEET Physics must have exactly 20 units, found {len(units)}")

    def test_neet_chemistry_units_count(self):
        units = curriculum.get_official_units("NEET", "Chemistry")
        self.assertEqual(len(units), 20, f"NEET Chemistry must have exactly 20 units, found {len(units)}")

    def test_neet_biology_units_count(self):
        units = curriculum.get_official_units("NEET", "Biology")
        self.assertEqual(len(units), 10, f"NEET Biology must have exactly 10 broad units, found {len(units)}")
        unit_names = [u["unit_name"] for u in units]
        self.assertIn("Diversity in Living World", unit_names)
        self.assertIn("Structural Organisation in Animals & Plants", unit_names)
        self.assertIn("Cell Structure & Function", unit_names)
        self.assertIn("Plant Physiology", unit_names)
        self.assertIn("Human Physiology", unit_names)
        self.assertIn("Reproduction", unit_names)
        self.assertIn("Genetics & Evolution", unit_names)
        self.assertIn("Biology & Human Welfare", unit_names)
        self.assertIn("Biotechnology", unit_names)
        self.assertIn("Ecology & Environment", unit_names)

    def test_no_ai_image_generation_anywhere(self):
        """Rule: Strictly NO free-form AI image generation across any topic."""
        for exam in ["JEE", "NEET"]:
            exam_data = self.cur["curriculum"].get(exam, {})
            for subj, s_val in exam_data.items():
                for u in s_val.get("units", []):
                    for t in u.get("topics", []):
                        self.assertFalse(
                            t.get("ai_image_generation", True),
                            f"ai_image_generation must be False for {exam} {subj} - {t.get('topic')}"
                        )

    def test_maths_no_structure_mode(self):
        """Rule: STRUCTURE is never allowed in Maths."""
        units = curriculum.get_official_units("JEE", "Maths")
        for u in units:
            for t in u.get("topics", []):
                self.assertNotIn(
                    "STRUCTURE", t.get("allowed_modes", []),
                    f"Maths topic '{t.get('topic')}' must not have STRUCTURE mode"
                )

    def test_physics_no_structure_mode(self):
        """Rule: STRUCTURE is never allowed in Physics."""
        units = curriculum.get_official_units("JEE", "Physics")
        for u in units:
            for t in u.get("topics", []):
                self.assertNotIn(
                    "STRUCTURE", t.get("allowed_modes", []),
                    f"Physics topic '{t.get('topic')}' must not have STRUCTURE mode"
                )

    def test_unit_alias_resolution(self):
        """Test that legacy/NCERT chapter names resolve to the right official unit."""
        u1 = curriculum.find_unit("JEE", "Physics", "Motion In One Dimension")
        self.assertIsNotNone(u1)
        self.assertEqual(u1["unit_name"], "Kinematics")

        u2 = curriculum.find_unit("NEET", "Biology", "Genetics")
        self.assertIsNotNone(u2)
        self.assertEqual(u2["unit_name"], "Genetics & Evolution")

        u3 = curriculum.find_unit("JEE", "Chemistry", "Organic Chemistry Basics")
        self.assertIsNotNone(u3)
        self.assertEqual(u3["unit_name"], "Basic Principles of Organic Chemistry")

        u4 = curriculum.find_unit("JEE", "Maths", "Three Dimensional Geometry")
        self.assertIsNotNone(u4)
        self.assertEqual(u4["unit_name"], "Three Dimensional Geometry")

    def test_get_allowed_modes(self):
        # Kinematics allows TEXT, NUMERICAL, DIAGRAM
        modes = curriculum.get_allowed_modes("JEE", "Physics", "Kinematics")
        self.assertIn("TEXT", modes)
        self.assertIn("NUMERICAL", modes)
        self.assertIn("DIAGRAM", modes)
        self.assertNotIn("STRUCTURE", modes)

        # Hydrocarbons allows TEXT, STRUCTURE, DIAGRAM
        chem_modes = curriculum.get_allowed_modes("JEE", "Chemistry", "Hydrocarbons")
        self.assertIn("STRUCTURE", chem_modes)

        # Pedigree Analysis in Biology
        pedigree_modes = curriculum.get_allowed_modes("NEET", "Biology", "Genetics & Evolution", "Pedigree Analysis")
        self.assertIn("DIAGRAM", pedigree_modes)

    def test_prompt_constraints(self):
        prompt_block = curriculum.get_prompt_constraints("NEET", "Biology", "Genetics & Evolution", "Pedigree Analysis")
        self.assertIn("Official Unit: Genetics & Evolution", prompt_block)
        self.assertIn("Allowed Question Modes:", prompt_block)
        self.assertIn("Strict Invariants:", prompt_block)
        self.assertIn("Do NOT generate questions outside these syllabus topics", prompt_block)

    def test_detect_question_mode(self):
        # Diagram question
        q_diag = {
            "imageUrl": "https://firebasestorage.googleapis.com/v0/b/apps-273d9.firebasestorage.app/o/diagrams%2Fq_123.webp?alt=media",
            "questionText": "In the circuit shown, calculate equivalent resistance.",
            "options": ["4 Ω", "6 Ω", "8 Ω", "12 Ω"]
        }
        self.assertEqual(curriculum.detect_question_mode(q_diag), "DIAGRAM")

        # Structure question
        q_struct = {
            "questionText": "Which of the following carbocations is most stable due to hyperconjugation?",
            "options": ["(CH3)3C+", "(CH3)2CH+", "CH3CH2+", "CH3+"]
        }
        self.assertEqual(curriculum.detect_question_mode(q_struct), "STRUCTURE")

        # Numerical question
        q_num = {
            "questionText": "A particle moves with velocity v = 3t^2 + 2t. Find the distance traveled in 4 seconds.",
            "options": ["64 m", "72 m", "80 m", "96 m"]
        }
        self.assertEqual(curriculum.detect_question_mode(q_num), "NUMERICAL")

        # Pure text question
        q_text = {
            "questionText": "Which of the following statements about cell theory is incorrect?",
            "options": [
                "All living organisms are composed of cells",
                "Viruses are an exception to cell theory",
                "New cells arise from pre-existing cells",
                "Cell wall is present in all eukaryotic cells"
            ]
        }
        self.assertEqual(curriculum.detect_question_mode(q_text), "TEXT")

    def test_validate_question_against_curriculum(self):
        valid_q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Kinematics",
            "questionText": "A car accelerates uniformly from rest at 2 m/s^2 for 5 s. What is its final velocity?",
            "options": ["10 m/s", "15 m/s", "20 m/s", "25 m/s"],
            "explanation": "v = u + at = 0 + 2*5 = 10 m/s"
        }
        is_valid, msg = curriculum.validate_question_against_curriculum(valid_q)
        self.assertTrue(is_valid, msg)

        # Invalid chapter
        invalid_chap_q = {
            "examType": "JEE",
            "subject": "Physics",
            "chapter": "Quantum Computing Advanced",
            "questionText": "Compute qubit entanglement.",
            "options": ["1", "0", "-1", "i"]
        }
        is_valid2, msg2 = curriculum.validate_question_against_curriculum(invalid_chap_q)
        self.assertFalse(is_valid2)
        self.assertIn("does not map", msg2)


if __name__ == "__main__":
    unittest.main()
