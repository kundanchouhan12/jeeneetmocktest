"""
test_diagram_pipeline.py — Unit tests for Single Source of Truth STEM Diagram Pipeline.
"""

import unittest
import os
import sys

# Ensure scripts dir in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import stem_diagram_engine as sde
from stem_diagram_pipeline import get_diagram_question_definitions


class TestDiagramPipeline(unittest.TestCase):

    def setUp(self):
        self.definitions = get_diagram_question_definitions()

    def test_definitions_count(self):
        self.assertGreaterEqual(len(self.definitions), 6)

    def test_single_source_solver_validation(self):
        """Every question definition must pass independent mathematical solver validation."""
        for defn in self.definitions:
            with self.subTest(exam=defn.exam_type, subject=defn.subject, spec=defn.spec_type):
                passed = defn.solver_validator(defn.params)
                self.assertTrue(passed, f"Solver validation failed for {defn.exam_type} {defn.subject} ({defn.spec_type})")

    def test_diagram_rendering_with_params(self):
        """Every spec must render valid PNG bytes using single-source params."""
        for defn in self.definitions:
            with self.subTest(spec=defn.spec_type):
                raw_png = sde.render_diagram_for_spec(defn.spec_type, params=defn.params)
                self.assertIsInstance(raw_png, bytes)
                self.assertGreater(len(raw_png), 1000)
                # PNG header magic bytes
                self.assertEqual(raw_png[:8], b'\x89PNG\r\n\x1a\n')

    def test_required_fields_present(self):
        for defn in self.definitions:
            self.assertIn(defn.exam_type, ["JEE", "NEET"])
            self.assertEqual(len(defn.options), 4)
            self.assertIn(defn.correct_option_index, [0, 1, 2, 3])
            self.assertTrue(len(defn.question_text) > 20)
            self.assertTrue(len(defn.explanation) > 20)


if __name__ == "__main__":
    unittest.main()
