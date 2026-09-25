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


class PayloadDoc:
    def __init__(self, doc_id: str, data: dict):
        self.id = doc_id
        self._data = data

    def to_dict(self):
        return dict(self._data)


class VaultContractTest(unittest.TestCase):

    def _q(self, i, exam="JEE"):
        return {
            "examType": exam,
            "subject": "Physics",
            "chapter": "Kinematics",
            "questionText": f"Unique stem number {i} for {exam}",
            "options": ["A", "B", "C", "D"],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "because conservation of energy",
            "isDailyVault": False,
        }

    def test_assert_selected_fails_when_only_18_valid(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        selected = [PayloadDoc(f"q{i}", self._q(i)) for i in range(18)]
        with self.assertRaises(VaultContractError) as ctx:
            assert_selected_vault(selected, "JEE", "2026-09-26", 30)
        self.assertIn("need exactly 30", str(ctx.exception))

    def test_assert_selected_fails_when_only_27_valid(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        selected = [PayloadDoc(f"q{i}", self._q(i)) for i in range(27)]
        with self.assertRaises(VaultContractError) as ctx:
            assert_selected_vault(selected, "NEET", "2026-09-26", 30)
        self.assertIn("need exactly 30", str(ctx.exception))

    def test_assert_selected_fails_when_29_valid(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        selected = [PayloadDoc(f"q{i}", self._q(i)) for i in range(29)]
        with self.assertRaises(VaultContractError) as ctx:
            assert_selected_vault(selected, "JEE", "2026-09-26", 30)
        self.assertIn("need exactly 30", str(ctx.exception))

    def test_assert_selected_fails_when_31_provided(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        selected = [PayloadDoc(f"q{i}", self._q(i)) for i in range(31)]
        with self.assertRaises(VaultContractError) as ctx:
            assert_selected_vault(selected, "JEE", "2026-09-26", 30)
        self.assertIn("need exactly 30", str(ctx.exception))

    def test_assert_selected_and_readback_30(self):
        from vault_scheduler import (
            assert_selected_vault, normalize_vault_payload, verify_written_vault,
        )
        selected = [PayloadDoc(f"src{i}", self._q(i)) for i in range(30)]
        payloads, group_id = assert_selected_vault(selected, "JEE", "2026-09-26", 30)
        self.assertEqual(len(payloads), 30)
        self.assertEqual(group_id, "jee_vault_2026-09-26")
        written = []
        for src_id, payload in payloads:
            written.append(PayloadDoc(f"vault_2026-09-26_{src_id}", payload))
        verify_written_vault(written, "JEE", "2026-09-26", group_id, 30)

    def test_wrong_exam_type_rejected(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        selected = [PayloadDoc(f"q{i}", self._q(i, exam="JEE")) for i in range(30)]
        # Change one to NEET when target is JEE
        selected[5]._data["examType"] = "NEET"
        with self.assertRaises(VaultContractError):
            assert_selected_vault(selected, "JEE", "2026-09-26", 30)

    def test_malformed_questions_rejected(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        # Missing chapter
        selected1 = [PayloadDoc(f"q{i}", self._q(i)) for i in range(30)]
        del selected1[0]._data["chapter"]
        with self.assertRaises(VaultContractError):
            assert_selected_vault(selected1, "JEE", "2026-09-26", 30)

        # Invalid answer index (4 out of range)
        selected2 = [PayloadDoc(f"q{i}", self._q(i)) for i in range(30)]
        selected2[0]._data["correctOptionIndex"] = 4
        selected2[0]._data["correctOption"] = 4
        with self.assertRaises(VaultContractError):
            assert_selected_vault(selected2, "JEE", "2026-09-26", 30)

        # Options not 4
        selected3 = [PayloadDoc(f"q{i}", self._q(i)) for i in range(30)]
        selected3[0]._data["options"] = ["A", "B", "C"]
        with self.assertRaises(VaultContractError):
            assert_selected_vault(selected3, "JEE", "2026-09-26", 30)

    def test_readback_count_mismatch_fails(self):
        from vault_scheduler import VaultContractError, verify_written_vault
        # Only 29 written instead of 30
        written = [PayloadDoc(f"vault_2026-09-26_src{i}", {
            **self._q(i), "isDailyVault": True, "vaultDate": "2026-09-26", "vaultGroupId": "jee_vault_2026-09-26"
        }) for i in range(29)]
        with self.assertRaises(VaultContractError) as ctx:
            verify_written_vault(written, "JEE", "2026-09-26", "jee_vault_2026-09-26", 30)
        self.assertIn("read-back count 29 != 30", str(ctx.exception))

    def test_duplicate_question_text_rejected(self):
        from vault_scheduler import VaultContractError, assert_selected_vault
        selected = [PayloadDoc(f"q{i}", self._q(i)) for i in range(30)]
        selected[7]._data["questionText"] = selected[3]._data["questionText"]
        with self.assertRaises(VaultContractError):
            assert_selected_vault(selected, "JEE", "2026-09-26", 30)

    def test_30_unique_over_three_days_from_100_pool(self):
        pool = [FakeDoc(f"q{i}") for i in range(100)]
        used = set()
        seen = set()
        for day in range(3):
            selected = select_vault_questions(pool, used, 30)
            ids = {d.id for d in selected}
            self.assertEqual(len(ids), 30)
            self.assertEqual(ids & seen, set(), f"day {day} repeated {ids & seen}")
            seen |= ids
            used |= ids


# ─── In-memory Firestore fake for end-to-end schedule_vault() tests ───────────
# Only the equality-where / batch.set / batch.delete / batch.commit surface
# schedule_vault() actually calls is implemented.

class FakeFirestoreDoc:
    def __init__(self, doc_id: str, data: dict):
        self.id = doc_id
        self._data = dict(data)
        self.reference = self  # batch.delete(doc.reference) just needs .id back

    def to_dict(self):
        return dict(self._data)


class FakeQuery:
    def __init__(self, store: dict, filters=None):
        self._store = store
        self._filters = filters or []

    def where(self, field, op, value):
        assert op == "==", "FakeQuery only supports equality filters"
        return FakeQuery(self._store, self._filters + [(field, value)])

    def get(self):
        return [
            doc for doc in self._store.values()
            if all(doc.to_dict().get(f) == v for f, v in self._filters)
        ]


class FakeDocRef:
    def __init__(self, store: dict, doc_id: str):
        self._store = store
        self.id = doc_id


class FakeCollection:
    def __init__(self, store: dict):
        self._store = store

    def where(self, field, op, value):
        return FakeQuery(self._store).where(field, op, value)

    def get(self):
        return list(self._store.values())

    def document(self, doc_id):
        return FakeDocRef(self._store, doc_id)


class FakeBatch:
    def __init__(self, store: dict, fail: bool):
        self._store = store
        self._fail = fail
        self._pending = []

    def set(self, doc_ref, data):
        self._pending.append(("set", doc_ref.id, data))

    def delete(self, doc_ref):
        self._pending.append(("delete", doc_ref.id, None))

    def commit(self):
        if self._fail:
            raise RuntimeError("Simulated Firestore write failure (deadline exceeded)")
        # Real Firestore batches are atomic — apply everything only on success.
        for op, doc_id, data in self._pending:
            if op == "set":
                self._store[doc_id] = FakeFirestoreDoc(doc_id, data)
            elif op == "delete":
                self._store.pop(doc_id, None)


class FakeDb:
    """fail_batch_call selects which db.batch() call (1-indexed) raises on commit."""

    def __init__(self, fail_batch_call: int = 0):
        self._store: dict = {}
        self._fail_batch_call = fail_batch_call
        self._batch_calls = 0

    def collection(self, name):
        assert name == "questions"
        return FakeCollection(self._store)

    def batch(self):
        self._batch_calls += 1
        return FakeBatch(self._store, fail=(self._batch_calls == self._fail_batch_call))

    def seed(self, doc_id: str, data: dict):
        self._store[doc_id] = FakeFirestoreDoc(doc_id, data)


class FirestoreWriteFailureTest(unittest.TestCase):

    def _source(self, i: int, exam: str = "JEE") -> dict:
        return {
            "examType": exam,
            "subject": "Physics",
            "chapter": "Kinematics",
            "questionText": f"Unique write-fail stem {i} for {exam}",
            "options": ["A", "B", "C", "D"],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "because conservation of energy",
            "isDailyVault": False,
        }

    def test_failed_batch_commit_propagates_as_non_zero_failure(self):
        from vault_scheduler import schedule_vault
        db = FakeDb(fail_batch_call=1)
        for i in range(30):
            db.seed(f"src{i}", self._source(i))

        with self.assertRaises(Exception) as ctx:
            schedule_vault(db, "2026-09-27", "JEE", count=30)
        self.assertIn("Simulated Firestore write failure", str(ctx.exception))

    def test_failed_write_leaves_no_partial_vault_docs(self):
        from vault_scheduler import schedule_vault
        db = FakeDb(fail_batch_call=1)
        for i in range(30):
            db.seed(f"src{i}", self._source(i))

        with self.assertRaises(Exception):
            schedule_vault(db, "2026-09-27", "JEE", count=30)

        vault_docs = [d for d in db._store.values() if d.id.startswith("vault_")]
        self.assertEqual(len(vault_docs), 0,
            "an atomic batch failure must not leave any half-written vault docs")

    def test_old_valid_vault_preserved_when_todays_write_fails(self):
        from vault_scheduler import schedule_vault
        db = FakeDb(fail_batch_call=1)
        # Yesterday's already-published, valid 30-question vault.
        for i in range(30):
            payload = self._source(i)
            payload.update({
                "isDailyVault": True,
                "vaultDate": "2026-09-26",
                "vaultGroupId": "jee_vault_2026-09-26",
            })
            db.seed(f"vault_2026-09-26_src{i}", payload)
        # Fresh source pool available for today's target date.
        for i in range(30):
            db.seed(f"src{i}", self._source(i))

        with self.assertRaises(Exception):
            schedule_vault(db, "2026-09-27", "JEE", count=30)

        kept = [d for d in db._store.values() if d.to_dict().get("vaultDate") == "2026-09-26"]
        self.assertEqual(len(kept), 30,
            "yesterday's valid vault must survive untouched when today's write fails")


class SchedulerRerunTest(unittest.TestCase):
    """schedule_vault() must be idempotent: re-running it for a date that's already
    been scheduled must never leave more (or fewer) than exactly 30 vault docs, even
    when a larger pool means the two runs can pick a different 30-question subset."""

    def _source(self, i: int, exam: str = "JEE") -> dict:
        return {
            "examType": exam,
            "subject": "Physics",
            "chapter": "Kinematics",
            "questionText": f"Unique rerun stem {i} for {exam}",
            "options": ["A", "B", "C", "D"],
            "correctOptionIndex": 0,
            "correctOption": 0,
            "explanation": "e",
            "isDailyVault": False,
        }

    def test_rerun_same_date_leaves_exactly_30_not_60(self):
        from vault_scheduler import schedule_vault
        db = FakeDb()
        for i in range(40):  # larger-than-count pool so the two runs can select differently
            db.seed(f"src{i}", self._source(i))

        schedule_vault(db, "2026-09-28", "JEE", count=30)
        first = {d.id for d in db._store.values() if d.id.startswith("vault_")}
        self.assertEqual(len(first), 30)

        schedule_vault(db, "2026-09-28", "JEE", count=30)
        second = {d.id for d in db._store.values() if d.id.startswith("vault_")}
        self.assertEqual(len(second), 30,
            "rerunning the scheduler for an already-scheduled date must never leave >30 vault docs")

    def test_rerun_three_times_still_exactly_30(self):
        from vault_scheduler import schedule_vault
        db = FakeDb()
        for i in range(50):
            db.seed(f"src{i}", self._source(i))

        for _ in range(3):
            schedule_vault(db, "2026-09-29", "JEE", count=30)

        final = [d for d in db._store.values() if d.id.startswith("vault_")]
        self.assertEqual(len(final), 30)
        dates = {d.to_dict().get("vaultDate") for d in final}
        exams = {d.to_dict().get("examType") for d in final}
        self.assertEqual(dates, {"2026-09-29"})
        self.assertEqual(exams, {"JEE"})


if __name__ == "__main__":
    unittest.main()
