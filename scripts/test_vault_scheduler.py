"""
Local, Firestore-free regression test for vault_scheduler.select_vault_questions().

Simulates a 100-question Daily Vault pool across several scheduling cycles and
verifies the anti-repeat behavior the "same question repeated again and again"
user feedback was about: no question should be re-vaulted until every question
in the pool has been vaulted at least once.

Run with:
    python scripts/test_vault_scheduler.py
"""

import unittest

from vault_scheduler import select_vault_questions


class FakeDoc:
    """Stand-in for a Firestore DocumentSnapshot — only `.id` is used by select_vault_questions."""
    def __init__(self, doc_id: str):
        self.id = doc_id

    def __repr__(self):
        return f"FakeDoc({self.id!r})"


class SelectVaultQuestionsTest(unittest.TestCase):

    def test_100_question_pool_does_not_repeat_until_exhausted(self):
        pool = [FakeDoc(f"q{i}") for i in range(100)]
        count_per_cycle = 25  # e.g. --count 25, run 4x → exactly covers the 100-question pool
        recently_used_ids: set = set()
        all_seen_across_cycles: set = set()

        for cycle in range(4):
            selected = select_vault_questions(pool, recently_used_ids, count_per_cycle)
            selected_ids = {d.id for d in selected}

            self.assertEqual(
                len(selected), len(selected_ids),
                f"cycle {cycle}: duplicate question selected within a single vault"
            )
            overlap = selected_ids & all_seen_across_cycles
            self.assertEqual(
                overlap, set(),
                f"cycle {cycle}: re-vaulted {overlap} before the 100-question pool was exhausted"
            )

            all_seen_across_cycles |= selected_ids
            recently_used_ids |= selected_ids

        self.assertEqual(
            len(all_seen_across_cycles), 100,
            "all 100 questions should have been vaulted exactly once across 4 cycles of 25"
        )

    def test_recirculates_once_pool_is_fully_exhausted(self):
        pool = [FakeDoc(f"q{i}") for i in range(10)]
        # Every question already vaulted in a prior cycle.
        recently_used_ids = {d.id for d in pool}

        selected = select_vault_questions(pool, recently_used_ids, count=10)

        self.assertEqual(len(selected), 10)
        self.assertEqual({d.id for d in selected}, {d.id for d in pool},
            "once genuinely exhausted, recirculating the full pool is expected, not a bug")

    def test_never_selects_the_same_question_twice_in_one_call(self):
        pool = [FakeDoc(f"q{i}") for i in range(30)]
        # Half already used — forces the top-up path to mix fresh + stale.
        recently_used_ids = {f"q{i}" for i in range(15)}

        selected = select_vault_questions(pool, recently_used_ids, count=30)
        selected_ids = [d.id for d in selected]

        self.assertEqual(len(selected_ids), len(set(selected_ids)),
            "top-up path must not duplicate a question within the same vault")
        self.assertEqual(len(selected), 30)

    def test_shrinks_count_when_pool_smaller_than_requested(self):
        pool = [FakeDoc(f"q{i}") for i in range(5)]
        selected = select_vault_questions(pool, recently_used_ids=set(), count=30)

        self.assertEqual(len(selected), 5, "should return the whole (small) pool, not pad with duplicates")
        self.assertEqual(len({d.id for d in selected}), 5)


class AndroidParseableTest(unittest.TestCase):

    def _valid(self, **overrides):
        q = {
            "examType": "JEE",
            "subject": "Maths",
            "chapter": "Limits",
            "questionText": "What is lim x→0 sin(x)/x ?",
            "options": ["0", "1", "∞", "-1"],
            "correctOptionIndex": 1,
            "correctOption": 1,
        }
        q.update(overrides)
        return q

    def test_string_and_float_indexes_are_coerced(self):
        from vault_scheduler import coerce_correct_index, is_android_parseable
        self.assertEqual(coerce_correct_index("2"), 2)
        self.assertEqual(coerce_correct_index(3.0), 3)
        ok, reason = is_android_parseable(self._valid(correctOptionIndex="1", correctOption="1"))
        self.assertTrue(ok, reason)

    def test_numeric_options_are_coerced(self):
        from vault_scheduler import coerce_options
        self.assertEqual(coerce_options([1, 2, 3, 4]), ["1", "2", "3", "4"])

    def test_rejects_missing_chapter(self):
        from vault_scheduler import is_android_parseable
        q = self._valid()
        del q["chapter"]
        ok, reason = is_android_parseable(q)
        self.assertFalse(ok)
        self.assertEqual(reason, "chapter")


if __name__ == "__main__":
    unittest.main()
