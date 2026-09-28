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

from web_question_ingestion import wrap_inline_latex as web_wrap_inline, wrap_bare_latex as web_wrap_bare, strip_ce_notation as web_strip_ce
from neet_web_question_ingestion import wrap_inline_latex as neet_wrap_inline, strip_ce_notation as neet_strip_ce
from auto_question_pipeline import wrap_inline_latex as auto_wrap_inline, wrap_bare_latex as auto_wrap_bare, strip_ce_notation as auto_strip_ce


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

    def test_bare_math_outside_existing_delimiter_is_wrapped(self):
        text = r"Given $x^2$, use \theta and \text{Na}."
        expected = r"Given $x^2$, use $\theta$ and $\text{Na}$."
        self.assertEqual(web_wrap_inline(text), expected)
        self.assertEqual(neet_wrap_inline(text), expected)
        self.assertEqual(auto_wrap_inline(text), expected)

    def test_wrap_bare_latex_options(self):
        self.assertEqual(web_wrap_bare("\\frac{3}{2}"), "\\(\\frac{3}{2}\\)")
        self.assertEqual(auto_wrap_bare("\\frac{3}{2}"), "\\(\\frac{3}{2}\\)")
        self.assertEqual(web_wrap_bare("None of these"), "None of these")


from web_question_ingestion import sanitize_web_content as web_sanitize
from neet_web_question_ingestion import sanitize_web_content as neet_sanitize
from fix_bad_formatting_questions import fix_field_prose, fix_field_option


class TestLiteralEscapeStripPreservesLatex(unittest.TestCase):
    """Stripping literal \\n/\\t escape artifacts must NOT eat LaTeX commands that
    start with n or t (\\theta, \\times, \\text, \\tan, \\nu, \\nabla, \\neq, ...).
    Regression for the 2026-09-27 near-miss where a blind .replace('\\n',' ')
    turned \\theta into 'heta' across ~1000 questions."""

    def test_sanitize_preserves_n_t_latex_commands(self):
        for s in (web_sanitize, neet_sanitize):
            self.assertIn('\\theta', s(r'Angle is \theta here.'))
            self.assertIn('\\times', s(r'Area 3 \times 4.'))
            self.assertIn('\\text{Na}', s(r'Ion \text{Na}^+ present.'))
            self.assertIn('\\tan', s(r'Value of \tan x.'))
            self.assertIn('\\nu', s(r'Frequency \nu given.'))
            self.assertIn('\\nabla', s(r'Operator \nabla acts.'))

    def test_sanitize_still_strips_real_escape_artifacts(self):
        for s in (web_sanitize, neet_sanitize):
            self.assertNotIn('\\n', s('Question stem\\nA) first option'))
            self.assertNotIn('\\t', s('col1\\tcol2 values'))

    def test_fix_fields_preserve_n_t_latex_commands(self):
        for command in (r'\to', r'\text{Na}^+', r'\theta', r'\times', r'\tan',
                        r'\nu', r'\nabla', r'\frac{1}{2}', r'\sqrt{x}'):
            output = fix_field_prose(command)
            self.assertIn(command, output, command)
            self.assertNotIn(command[1:], output.replace(command, ''), command)
        self.assertIn('\\text{Na}', fix_field_option(r'\text{Na}^+ ion'))

    def test_implication_expression_is_not_corrupted(self):
        value = r'$(p \land q) \lor (p \to q)$'
        self.assertEqual(fix_field_prose(value), value)
        self.assertEqual(fix_field_option(value), value)

    def test_allowlisted_legacy_whitespace_commands_are_restored(self):
        cases = (
            ('\\\text{m}', r'\text{m}'),
            ('\\\theta', r'\theta'),
            ('\\\nabla f', r'\nabla f'),
        )
        for broken, expected in cases:
            self.assertIn(expected.split()[0], fix_field_prose(broken))

    def test_unknown_legacy_whitespace_artifact_is_not_guessed(self):
        value = '\\\tunknown{m}'
        self.assertIn(value, fix_field_prose(value))

    def test_fix_strips_literal_escape_before_prose(self):
        self.assertNotIn('\\n', fix_field_prose(r'Question\nA) first option'))
        self.assertNotIn('\\t', fix_field_option(r'col1\tcol2 values'))


class TestStripCeNotation(unittest.TestCase):
    """mhchem \\ce{...} must be converted to plain KaTeX at ingestion — shipped
    JLaTeXMath builds render \\ce{} as raw literal text (Play Store screenshot bug)."""

    def _all(self, text):
        return [web_strip_ce(text), neet_strip_ce(text), auto_strip_ce(text)]

    def test_disproportionation_option_from_screenshot(self):
        # Exact broken option from the reported NEET/JEE vault screenshot.
        text = "$ \\ce{ClO3^- -> ClO4^- + Cl^-} $"
        expected = "$ ClO_{3}^- \\rightarrow ClO_{4}^- + Cl^- $"
        for out in self._all(text):
            self.assertEqual(out, expected)
            self.assertNotIn("\\ce", out)

    def test_no_double_dollar_delimiters(self):
        # Must preserve the existing $...$ wrapper, never produce "$ $...$ $".
        for out in self._all("$ \\ce{H2O} $"):
            self.assertEqual(out, "$ H_{2}O $")
            self.assertNotIn("$ $", out)

    def test_reaction_with_parentheses_subscripts(self):
        text = "\\ce{Cu + 2AgNO3 -> Cu(NO3)2 + 2Ag}"
        for out in self._all(text):
            self.assertNotIn("\\ce", out)
            self.assertIn("AgNO_{3}", out)
            self.assertIn("Cu(NO_{3})_{2}", out)
            self.assertIn("\\rightarrow", out)

    def test_double_backslash_ce_also_stripped(self):
        for out in self._all("\\\\ce{K2Cr2O7}"):
            self.assertNotIn("ce{", out)
            self.assertIn("K_{2}Cr_{2}O_{7}", out)

    def test_reversible_equilibrium_arrow(self):
        for out in self._all("\\ce{N2 + 3H2 <=> 2NH3}"):
            self.assertIn("\\leftrightarrow", out)
            self.assertNotIn("\\ce", out)

    def test_plain_text_without_ce_untouched(self):
        text = "Which of the following is a disproportionation reaction?"
        for out in self._all(text):
            self.assertEqual(out, text)


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
