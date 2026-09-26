"""
test_latex_rendering.py — Unit tests for inline and bare LaTeX wrapping logic.
Verifies that chemical formulas, KaTeX tokens, and powers are wrapped with $...$
while plain text prose and already-delimited equations are not corrupted.
"""

import unittest
import sys
import os

# Insert scripts folder to path
sys.path.insert(0, os.path.dirname(__file__))

# Mock firebase_admin if needed to allow imports
import types
if 'firebase_admin' not in sys.modules:
    sys.modules['firebase_admin'] = types.ModuleType('firebase_admin')
    sys.modules['firebase_admin'].credentials = types.SimpleNamespace(Certificate=lambda x: x)
    sys.modules['firebase_admin'].firestore = types.SimpleNamespace()
    sys.modules['firebase_admin']._apps = {}

# Set dummy GROQ_API_KEY so scripts can import without sys.exit
os.environ["GROQ_API_KEY"] = os.environ.get("GROQ_API_KEY", "dummy_test_key")

from web_question_ingestion import wrap_inline_latex as web_wrap_inline, wrap_bare_latex as web_wrap_bare
from neet_web_question_ingestion import wrap_inline_latex as neet_wrap_inline
from auto_question_pipeline import wrap_inline_latex as auto_wrap_inline, wrap_bare_latex as auto_wrap_bare


class TestLatexRendering(unittest.TestCase):

    def test_acetate_ion_chemical_formula(self):
        text = "The pH of CH_3COO^- solution is 8.9."
        expected = "The pH of $CH_3COO^-$ solution is 8.9."
        self.assertEqual(web_wrap_inline(text), expected)
        self.assertEqual(neet_wrap_inline(text), expected)
        self.assertEqual(auto_wrap_inline(text), expected)

    def test_multiple_chemical_formulas(self):
        text = "H_2O reacts with SO_4^{2-} ions in aqueous solution."
        expected = "$H_2O$ reacts with $SO_4^{2-}$ ions in aqueous solution."
        self.assertEqual(web_wrap_inline(text), expected)
        self.assertEqual(neet_wrap_inline(text), expected)
        self.assertEqual(auto_wrap_inline(text), expected)

    def test_complex_coordination_and_exponents(self):
        text = "Calculate dissociation of [Cr(H_2O)_6]^{3+} when K_a = 1.8 \\times 10^{-5}."
        self.assertIn("$[Cr(H_2O)_6]^{3+}$", web_wrap_inline(text))
        self.assertIn("$K_a$", web_wrap_inline(text))
        self.assertIn("$\\times$", web_wrap_inline(text))
        self.assertIn("$10^{-5}$", web_wrap_inline(text))

    def test_plain_text_untouched(self):
        text = "Which of the following is an example of an extensive property?"
        self.assertEqual(web_wrap_inline(text), text)
        self.assertEqual(neet_wrap_inline(text), text)
        self.assertEqual(auto_wrap_inline(text), text)

    def test_already_delimited_math_untouched(self):
        text = "If $x^2 + y^2 = 1$, find $\\frac{dy}{dx}$."
        self.assertEqual(web_wrap_inline(text), text)
        self.assertEqual(neet_wrap_inline(text), text)
        self.assertEqual(auto_wrap_inline(text), text)

    def test_wrap_bare_latex_options(self):
        self.assertEqual(web_wrap_bare("\\frac{3}{2}"), "\\(\\frac{3}{2}\\)")
        self.assertEqual(auto_wrap_bare("\\frac{3}{2}"), "\\(\\frac{3}{2}\\)")
        self.assertEqual(web_wrap_bare("None of these"), "None of these")


from purge_duplicate_questions import normalize_text
from vault_scheduler import question_fingerprint


class TestSafeDeduplicationNormalization(unittest.TestCase):

    def test_whitespace_and_trailing_punctuation_matches(self):
        q1 = "Which of the following is the function of mitochondria?"
        q2 = "Which of the following is the function of mitochondria ?"
        self.assertEqual(normalize_text(q1), normalize_text(q2))
        self.assertEqual(question_fingerprint({"questionText": q1}), question_fingerprint({"questionText": q2}))

    def test_latex_delimiter_difference_matches(self):
        q1 = "Calculate the value of $\\sin(x) + \\cos(x)$."
        q2 = "Calculate the value of \\(\\sin(x) + \\cos(x)\\)"
        self.assertEqual(normalize_text(q1), normalize_text(q2))

    def test_chemical_subscript_bracing_matches(self):
        q1 = "The pH of CH_3COO^- solution is 8.9."
        q2 = "The pH of CH_{3}COO^{-} solution is 8.9"
        self.assertEqual(normalize_text(q1), normalize_text(q2))

    def test_numbers_must_not_collide(self):
        q1 = "What is the value of 2 + 2?"
        q2 = "What is the value of 2 + 3?"
        self.assertNotEqual(normalize_text(q1), normalize_text(q2))
        self.assertNotEqual(question_fingerprint({"questionText": q1}), question_fingerprint({"questionText": q2}))

    def test_math_operators_must_not_collide(self):
        q1 = "What is the value of 2 + 3?"
        q2 = "What is the value of 2 - 3?"
        self.assertNotEqual(normalize_text(q1), normalize_text(q2))

    def test_trig_functions_must_not_collide(self):
        q1 = "Find the value of \\sin(x)."
        q2 = "Find the value of \\cos(x)."
        self.assertNotEqual(normalize_text(q1), normalize_text(q2))

    def test_inequalities_must_not_collide(self):
        q1 = "For what values is f(x) > 0?"
        q2 = "For what values is f(x) < 0?"
        self.assertNotEqual(normalize_text(q1), normalize_text(q2))


if __name__ == "__main__":
    unittest.main()
