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

from run_daily_automation import FirebaseAuthError, verify_firebase_connectivity


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


if __name__ == "__main__":
    unittest.main()
