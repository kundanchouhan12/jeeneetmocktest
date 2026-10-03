"""
Regression test for cleanup_corrupted_questions.is_corrupted() protecting Daily
Vault documents.

Independently reported gap: the corruption heuristics scanned/deleted questions
without excluding isDailyVault=true docs, so an already-published 30-question
vault could be silently shrunk by an unrelated cleanup pass. Fixed by making
is_corrupted() return (False, "") immediately for any isDailyVault=true doc,
regardless of how "corrupted" its content would otherwise look.

Run with:
    python scripts/test_cleanup_corrupted_questions.py
"""

import unittest

from cleanup_corrupted_questions import is_corrupted


class DailyVaultProtectionTest(unittest.TestCase):

    def _corrupted_looking_question(self, **overrides) -> dict:
        # Deliberately trips the "short text" AND "placeholder options" rules.
        q = {
            "questionText": "bad",
            "options": ["Option A", "Option B", "Option C", "Option D"],
            "explanation": "",
        }
        q.update(overrides)
        return q

    def test_vault_flagged_question_is_never_corrupted(self):
        q = self._corrupted_looking_question(isDailyVault=True)
        corrupted, reason = is_corrupted(q)
        self.assertFalse(corrupted, f"vault doc must never be flagged, got reason={reason!r}")
        self.assertEqual(reason, "")

    def test_same_content_without_vault_flag_is_still_flagged(self):
        # Sanity check: the protection is specific to isDailyVault, not a change
        # to the underlying heuristics.
        q = self._corrupted_looking_question(isDailyVault=False)
        corrupted, _ = is_corrupted(q)
        self.assertTrue(corrupted, "non-vault doc with the same bad content must still be caught")

    def test_vault_flagged_question_with_missing_diagram_pattern_is_protected(self):
        q = self._corrupted_looking_question(
            isDailyVault=True,
            questionText="Find the current in the given circuit as shown in the figure.",
            options=["1 A", "2 A", "3 A", "4 A"],
        )
        corrupted, _ = is_corrupted(q)
        self.assertFalse(corrupted)


    def test_broken_latex_frac_flagged(self):
        q = {
            "questionText": r"The value is given by \Delta F = 5.35 \ln\left( rac{C}{C_0} \right)",
            "options": ["1.0", "2.0", "3.0", "4.0"],
        }
        corrupted, reason = is_corrupted(q)
        self.assertTrue(corrupted)
        self.assertIn("rac{", reason)

    def test_broken_latex_right_flagged(self):
        q = {
            "questionText": r"The fraction is \left( \frac{a}{b} ight) in magnitude.",
            "options": ["1", "2", "3", "4"],
        }
        corrupted, reason = is_corrupted(q)
        self.assertTrue(corrupted)
        self.assertIn("ight", reason)

    def test_multiple_dollar_signs_flagged(self):
        q = {
            "questionText": r"For the system of equations $$$x + y = 2$$$ find x.",
            "options": ["1", "2", "3", "4"],
        }
        corrupted, reason = is_corrupted(q)
        self.assertTrue(corrupted)
        self.assertIn("Multiple dollar signs", reason)

    def test_missing_equation_circle_flagged(self):
        q = {
            "questionText": "For the four circles, the following four equations are given: Circle Circle Circle Circle If the centre of circle 1 is joined with centre of circle 2...",
            "options": ["Rhombus", "Square", "Rectangle", "Parallelogram"],
        }
        corrupted, reason = is_corrupted(q)
        self.assertTrue(corrupted)

    def test_missing_equation_determinant_flagged(self):
        q = {
            "questionText": "If a, b, c are in AP with common difference d and the determinant of the matrix is zero, then the value of d is",
            "options": ["72", "12", "36", "6"],
        }
        corrupted, reason = is_corrupted(q)
        self.assertTrue(corrupted)

    def test_clean_math_question_not_flagged(self):
        q = {
            "questionText": r"Find the value of $x$ such that $\lim_{x \to 0} \frac{\sin x}{x} = 1$.",
            "options": ["0", "1", "2", "3"],
        }
        corrupted, reason = is_corrupted(q)
        self.assertFalse(corrupted, f"Clean question should not be flagged: {reason}")


if __name__ == "__main__":
    unittest.main()

