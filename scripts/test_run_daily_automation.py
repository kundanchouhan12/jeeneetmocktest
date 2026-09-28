"""
Local, Firestore-free regression test for run_daily_automation.verify_firebase_connectivity().

Covers the specific failure mode from the bug report: a bad/expired GitHub secret
producing `invalid_grant: Invalid JWT Signature` must never be silently swallowed —
it must raise and (via main()'s sys.exit(1)) turn the GitHub Actions run red.

Run with:
    python scripts/test_run_daily_automation.py
"""

import os
import unittest

# auto_question_pipeline (imported by run_daily_automation at module load time) raises
# at import time if GROQ_API_KEY is unset. Only the Firebase-connectivity helper is
# under test here, so a dummy key is enough to make the module importable.
os.environ.setdefault("GROQ_API_KEY", "test-dummy-key-for-unit-tests")

from run_daily_automation import FirebaseAuthError, run_cleanup_audit, verify_firebase_connectivity
from fix_bad_formatting_questions import run_formatting_fix


class FakeDocRef:
    def __init__(self, fail_with: Exception = None):
        self._fail_with = fail_with

    def get(self):
        if self._fail_with:
            raise self._fail_with
        return object()


class FakeCollectionRef:
    def __init__(self, fail_with: Exception = None):
        self._fail_with = fail_with

    def document(self, _doc_id):
        return FakeDocRef(self._fail_with)


class FakeFirestoreClient:
    def __init__(self, fail_with: Exception = None):
        self._fail_with = fail_with

    def collection(self, _name):
        return FakeCollectionRef(self._fail_with)


class VerifyFirebaseConnectivityTest(unittest.TestCase):

    def test_none_client_raises(self):
        with self.assertRaises(FirebaseAuthError):
            verify_firebase_connectivity(None)

    def test_working_client_does_not_raise(self):
        verify_firebase_connectivity(FakeFirestoreClient())  # must not raise

    def test_invalid_jwt_failure_is_not_swallowed(self):
        jwt_error = Exception("invalid_grant: Invalid JWT Signature.")
        with self.assertRaises(FirebaseAuthError) as ctx:
            verify_firebase_connectivity(FakeFirestoreClient(fail_with=jwt_error))
        self.assertIn("invalid_grant", str(ctx.exception))

    def test_generic_network_failure_is_not_swallowed(self):
        with self.assertRaises(FirebaseAuthError):
            verify_firebase_connectivity(FakeFirestoreClient(fail_with=TimeoutError("deadline exceeded")))


# ─── run_cleanup_audit() must never delete a Daily Vault doc ─────────────────

class SimpleFakeDoc:
    def __init__(self, doc_id: str, data: dict):
        self.id = doc_id
        self._data = data

    def to_dict(self):
        return dict(self._data)


class SimpleFakeDocRef:
    def __init__(self, store: dict, doc_id: str):
        self._store = store
        self.id = doc_id


class SimpleFakeCollection:
    def __init__(self, store: dict):
        self._store = store

    def document(self, doc_id):
        return SimpleFakeDocRef(self._store, doc_id)


class SimpleFakeBatch:
    def __init__(self, store: dict):
        self._store = store
        self._pending_deletes = []

    def delete(self, doc_ref):
        self._pending_deletes.append(doc_ref.id)

    def commit(self):
        for doc_id in self._pending_deletes:
            self._store.pop(doc_id, None)
        self._pending_deletes = []


class SimpleFakeDb:
    def __init__(self, store: dict):
        self._store = store

    def collection(self, name):
        assert name == "questions"
        return SimpleFakeCollection(self._store)

    def batch(self):
        return SimpleFakeBatch(self._store)


class RunCleanupAuditProtectsVaultTest(unittest.TestCase):

    def test_corrupted_vault_doc_survives_cleanup_audit(self):
        store = {
            # Looks corrupted (too short) but is a live Daily Vault doc — must survive.
            "vault_2026-09-25_src1": {"questionText": "bad", "options": [], "isDailyVault": True},
            # Genuinely corrupted, non-vault bank question — must be deleted.
            "bank1": {"questionText": "bad", "options": [], "isDailyVault": False},
            # Healthy, non-vault bank question — must survive.
            "bank2": {
                "questionText": "A particle starts from rest and accelerates uniformly at 2 m/s^2.",
                "options": ["1", "2", "3", "4"],
                "isDailyVault": False,
            },
        }
        db = SimpleFakeDb(store)
        docs = [SimpleFakeDoc(doc_id, data) for doc_id, data in store.items()]

        deleted_count = run_cleanup_audit(db, dry_run=False, all_docs=docs)

        self.assertEqual(deleted_count, 1, "only the non-vault corrupted doc should be counted as deleted")
        self.assertIn("vault_2026-09-25_src1", store, "Daily Vault doc must never be deleted by cleanup audit")
        self.assertNotIn("bank1", store, "genuinely corrupted non-vault doc should still be deleted")
        self.assertIn("bank2", store)


# ─── run_formatting_fix() heals \ce{} in place without shrinking the vault ───

class FmtFakeDoc:
    def __init__(self, doc_id, data):
        self.id = doc_id
        self._data = data
        self.reference = self

    def to_dict(self):
        return self._data


class FmtFakeBatch:
    def __init__(self):
        self.updated = []

    def update(self, ref, updates):
        ref._data.update(updates)
        self.updated.append(ref.id)

    def commit(self):
        pass


class FmtFakeDb:
    def batch(self):
        return FmtFakeBatch()


class RunFormattingFixTest(unittest.TestCase):

    def test_ce_notation_in_vault_option_is_repaired_in_place(self):
        # Exact broken option from the reported vault screenshot, on a live vault doc.
        vault = FmtFakeDoc("vault_2026-09-27_src1", {
            "examType": "NEET", "subject": "Chemistry", "isDailyVault": True,
            "questionText": "Identify the disproportionation reaction.",
            "options": ["$ \\ce{ClO3^- -> ClO4^- + Cl^-} $", "NaCl", "H2O", "KBr"],
            "explanation": "ok",
        })
        clean = FmtFakeDoc("bank1", {
            "examType": "JEE", "subject": "Maths", "isDailyVault": False,
            "questionText": "What is $2 + 2$?",
            "options": ["1", "2", "3", "4"], "explanation": "four",
        })

        fixed, changed = run_formatting_fix(FmtFakeDb(), dry_run=False, all_docs=[vault, clean])

        self.assertEqual(fixed, 1)
        self.assertEqual(changed, {"vault_2026-09-27_src1"})
        opt0 = vault._data["options"][0]
        self.assertNotIn("\\ce", opt0)
        self.assertEqual(opt0, "$ ClO_{3}^- \\rightarrow ClO_{4}^- + Cl^- $")
        self.assertNotIn("$ $", opt0, "must not produce double-delimited $ $...$ $")
        # Vault doc was UPDATED in place, never removed — vault size is preserved.
        self.assertTrue(vault._data["isDailyVault"])

    def test_clean_bank_is_idempotent_no_writes(self):
        clean = FmtFakeDoc("bank1", {
            "examType": "JEE", "subject": "Physics", "isDailyVault": False,
            "questionText": "A body moves with velocity $v$.",
            "options": ["1 m/s", "2 m/s", "3 m/s", "4 m/s"], "explanation": "kinematics",
        })
        fixed, changed = run_formatting_fix(FmtFakeDb(), dry_run=False, all_docs=[clean])
        self.assertEqual(fixed, 0)
        self.assertEqual(changed, set())

    def test_dry_run_reports_but_does_not_mutate(self):
        vault = FmtFakeDoc("vault_x", {
            "examType": "NEET", "subject": "Chemistry", "isDailyVault": True,
            "questionText": "Q", "options": ["$ \\ce{H2O} $", "b", "c", "d"],
            "explanation": "e",
        })
        before = list(vault._data["options"])
        fixed, changed = run_formatting_fix(FmtFakeDb(), dry_run=True, all_docs=[vault])
        self.assertEqual(fixed, 1)
        self.assertEqual(vault._data["options"], before, "dry-run must not mutate docs")


if __name__ == "__main__":
    unittest.main()
