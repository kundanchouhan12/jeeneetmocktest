"""
vault_scheduler.py — Admin script to schedule Daily Vault questions in Firestore.

Hard contract: for each (target_date, exam) write exactly 30 Android-parseable
vault documents. Incomplete vaults are never written. Read-back must confirm 30
or the step raises VaultContractError (GitHub Actions red).
"""

import argparse
import datetime
import os
import random
import re
import sys

import firebase_admin
from firebase_admin import credentials, firestore

VALID_EXAMS = ["JEE", "NEET"]
EXPECTED_VAULT_COUNT = 30


class VaultContractError(Exception):
    """Raised when a Daily Vault cannot be generated to the 30-valid-doc contract."""


def init_firebase(creds_path: str) -> None:
    if firebase_admin._apps:
        return
    if not os.path.exists(creds_path):
        print(f"ERROR: Credentials file not found: {creds_path}")
        sys.exit(1)
    firebase_admin.initialize_app(credentials.Certificate(creds_path))


def coerce_correct_index(value):
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int) and value in (0, 1, 2, 3):
        return value
    if isinstance(value, float) and value in (0.0, 1.0, 2.0, 3.0):
        return int(value)
    if isinstance(value, str):
        text = value.strip()
        if text.isdigit() and int(text) in (0, 1, 2, 3):
            return int(text)
    return None


def coerce_options(options):
    if not isinstance(options, list) or len(options) != 4:
        return None
    out = []
    for item in options:
        if item is None:
            return None
        text = str(item).strip()
        if not text:
            return None
        out.append(item if isinstance(item, str) else str(item))
    return out


def question_fingerprint(q: dict) -> str:
    return re.sub(r"\s+", " ", str(q.get("questionText") or "").strip().lower())


def validate_source_question(q: dict, exam_type: str, doc_id: str = "") -> tuple:
    """Strict source validation aligned with Android QuestionFirestoreParser."""
    if doc_id.startswith("vault_"):
        return False, "source_is_vault_copy"
    if q.get("isDailyVault") is True:
        return False, "source_is_daily_vault"
    if q.get("examType") != exam_type:
        return False, f"examType (got {q.get('examType')!r})"
    if not isinstance(q.get("subject"), str) or not q["subject"].strip():
        return False, "subject"
    if not isinstance(q.get("chapter"), str) or not q["chapter"].strip():
        return False, "chapter"
    if not isinstance(q.get("questionText"), str) or not q["questionText"].strip():
        return False, "questionText"
    if coerce_options(q.get("options")) is None:
        return False, "options"
    corr = q.get("correctOptionIndex") if q.get("correctOptionIndex") is not None else q.get("correctOption")
    if coerce_correct_index(corr) is None:
        return False, "correctOptionIndex"
    try:
        from cleanup_corrupted_questions import is_corrupted
        bad, reason = is_corrupted(q)
        if bad:
            return False, f"corrupted:{reason}"
    except Exception:
        pass
    return True, ""


def is_android_parseable(q: dict) -> tuple:
    exam = q.get("examType")
    if exam not in VALID_EXAMS:
        return False, "examType"
    return validate_source_question(
        {**q, "isDailyVault": False},
        exam,
        doc_id="",
    )


def normalize_vault_payload(source: dict, exam_type: str, target_date: str, group_id: str) -> dict:
    q = dict(source)
    options = coerce_options(q.get("options"))
    corr = coerce_correct_index(
        q.get("correctOptionIndex") if q.get("correctOptionIndex") is not None else q.get("correctOption")
    )
    if options is None or corr is None:
        raise VaultContractError("normalize_vault_payload received an unparseable source")
    q["examType"] = exam_type
    q["options"] = options
    q["correctOptionIndex"] = corr
    q["correctOption"] = corr
    q["explanation"] = q.get("explanation") if isinstance(q.get("explanation"), str) else ""
    q["isDailyVault"] = True
    q["isPremium"] = False
    q["vaultDate"] = target_date
    q["vaultGroupId"] = group_id
    return q


def assert_selected_vault(selected: list, exam_type: str, target_date: str, count: int = EXPECTED_VAULT_COUNT):
    if len(selected) != count:
        raise VaultContractError(
            f"{exam_type} {target_date}: selected {len(selected)} questions, need exactly {count}. "
            "Incomplete vaults are not written."
        )
    ids = [d.id for d in selected]
    if len(set(ids)) != count:
        raise VaultContractError(f"{exam_type} {target_date}: duplicate source document IDs in selection")
    group_id = f"{exam_type.lower()}_vault_{target_date}"
    payloads = []
    fingerprints = []
    for doc in selected:
        q = doc.to_dict() or {}
        ok, reason = validate_source_question(q, exam_type, doc.id)
        if not ok:
            raise VaultContractError(f"{exam_type} {target_date}: {doc.id} failed validation ({reason})")
        payload = normalize_vault_payload(q, exam_type, target_date, group_id)
        payloads.append((doc.id, payload))
        fingerprints.append(question_fingerprint(payload))
    if len(set(fingerprints)) != count:
        raise VaultContractError(f"{exam_type} {target_date}: duplicate questionText in selection")
    return payloads, group_id


def verify_written_vault(docs: list, exam_type: str, target_date: str, group_id: str, count: int = EXPECTED_VAULT_COUNT):
    if len(docs) != count:
        raise VaultContractError(
            f"{exam_type} {target_date}: read-back count {len(docs)} != {count}"
        )
    source_ids = []
    fps = []
    for doc in docs:
        q = doc.to_dict() or {}
        if q.get("examType") != exam_type:
            raise VaultContractError(f"{doc.id}: examType mismatch")
        if q.get("vaultDate") != target_date:
            raise VaultContractError(f"{doc.id}: vaultDate mismatch")
        if q.get("isDailyVault") is not True:
            raise VaultContractError(f"{doc.id}: isDailyVault is not true")
        if q.get("vaultGroupId") != group_id:
            raise VaultContractError(f"{doc.id}: vaultGroupId mismatch")
        if coerce_options(q.get("options")) is None:
            raise VaultContractError(f"{doc.id}: options invalid on read-back")
        corr = coerce_correct_index(
            q.get("correctOptionIndex") if q.get("correctOptionIndex") is not None else q.get("correctOption")
        )
        if corr is None:
            raise VaultContractError(f"{doc.id}: answer index invalid on read-back")
        if isinstance(q.get("questionText"), str) and q["questionText"].strip():
            fps.append(question_fingerprint(q))
        else:
            raise VaultContractError(f"{doc.id}: questionText missing on read-back")
        if doc.id.startswith("vault_") and "_" in doc.id:
            source_ids.append(doc.id.split("_", 2)[-1] if doc.id.count("_") >= 2 else doc.id)
    if len(set(fps)) != count:
        raise VaultContractError(f"{exam_type} {target_date}: duplicate questionText on read-back")
    if len(set(source_ids)) != count:
        raise VaultContractError(f"{exam_type} {target_date}: duplicate source ids on read-back")


def select_vault_questions(pool: list, recently_used_ids: set, count: int) -> list:
    """
    Picks up to `count` items from `pool`, preferring ones not in
    `recently_used_ids`. Pure / I/O-free for unit tests.
    """
    fresh_pool = [d for d in pool if d.id not in recently_used_ids]
    stale_pool = [d for d in pool if d.id in recently_used_ids]

    count = min(count, len(pool))
    fresh_n = min(count, len(fresh_pool))
    selected = random.sample(fresh_pool, fresh_n) if fresh_n else []
    if fresh_n < count:
        selected += random.sample(stale_pool, count - fresh_n)
    return selected


def _dedupe_pool(pool: list) -> list:
    seen = set()
    unique = []
    for doc in pool:
        fp = question_fingerprint(doc.to_dict() or {})
        if not fp or fp in seen:
            continue
        seen.add(fp)
        unique.append(doc)
    return unique


def schedule_vault(db, target_date: str, exam_type: str, count: int = EXPECTED_VAULT_COUNT) -> None:
    print(f"\n[vault] Scheduling {exam_type} vault for {target_date} ({count} questions)...")
    if count != EXPECTED_VAULT_COUNT:
        raise VaultContractError(f"Daily Vault count must be {EXPECTED_VAULT_COUNT}, got {count}")
    questions_ref = db.collection("questions")

    all_docs = questions_ref.where("examType", "==", exam_type).get()
    pool = [d for d in all_docs if not d.id.startswith("vault_")]

    skipped = []
    parseable = []
    for d in pool:
        ok, reason = validate_source_question(d.to_dict() or {}, exam_type, d.id)
        if ok:
            parseable.append(d)
        else:
            skipped.append((d.id, reason))
    if skipped:
        print(f"  Skipping {len(skipped)} invalid bank docs (e.g. {skipped[0][0]}: {skipped[0][1]}).")
    pool = _dedupe_pool(parseable)

    if len(pool) < count:
        raise VaultContractError(
            f"{exam_type}: only {len(pool)} unique valid source questions (need {count}). "
            "Refusing to write an incomplete Daily Vault."
        )

    previous_vault_docs = (
        questions_ref
        .where("isDailyVault", "==", True)
        .where("examType", "==", exam_type)
        .get()
    )
    recently_used_ids = {
        d.id.split("_", 2)[2] for d in previous_vault_docs
        if d.id.startswith("vault_") and d.id.count("_") >= 2
        and d.to_dict().get("vaultDate") != target_date
    }

    fresh_pool_size = len([d for d in pool if d.id not in recently_used_ids])
    if fresh_pool_size < count:
        if fresh_pool_size == 0:
            print(f"  NOTE: Every question has been vaulted before; re-circulating full pool for '{exam_type}'.")
        else:
            print(f"  NOTE: Only {fresh_pool_size} never-vaulted questions left; topping up with previously-vaulted ones.")

    selected = select_vault_questions(pool, recently_used_ids, count)
    payloads, group_id = assert_selected_vault(selected, exam_type, target_date, count)

    new_ids = []
    batch = db.batch()
    for source_id, payload in payloads:
        doc_id = f"vault_{target_date}_{source_id}"
        new_ids.append(doc_id)
        batch.set(questions_ref.document(doc_id), payload)
    batch.commit()

    written = (
        questions_ref
        .where("vaultDate", "==", target_date)
        .where("examType", "==", exam_type)
        .where("isDailyVault", "==", True)
        .get()
    )
    written_new = [d for d in written if d.id in set(new_ids)]
    verify_written_vault(written_new, exam_type, target_date, group_id, count)

    stale = [d for d in written if d.id not in set(new_ids)]
    if stale:
        print(f"  Removing {len(stale)} leftover vault docs for {target_date}/{exam_type}...")
        del_batch = db.batch()
        for doc in stale:
            del_batch.delete(doc.reference)
        del_batch.commit()
        written = (
            questions_ref
            .where("vaultDate", "==", target_date)
            .where("examType", "==", exam_type)
            .where("isDailyVault", "==", True)
            .get()
        )
        verify_written_vault(list(written), exam_type, target_date, group_id, count)

    print(f"  OK: Wrote and verified {count} vault questions for {exam_type} (groupId: {group_id})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Schedule Daily Vault questions in Firestore")
    parser.add_argument("--creds", default=None, help="Path to Firebase service account JSON")
    parser.add_argument("--exam", choices=VALID_EXAMS, default=None)
    parser.add_argument("--today", action="store_true")
    parser.add_argument("--date", type=str, default=None)
    parser.add_argument("--count", type=int, default=EXPECTED_VAULT_COUNT)
    args = parser.parse_args()

    if args.creds:
        creds_path = args.creds
    else:
        creds_path = os.environ.get(
            "GOOGLE_APPLICATION_CREDENTIALS",
            os.path.join(os.path.dirname(__file__), "serviceAccountKey.json")
        )

    init_firebase(creds_path)
    db = firestore.client()

    if args.date:
        target = args.date
    elif args.today:
        target = datetime.date.today().strftime("%Y-%m-%d")
    else:
        target = (datetime.date.today() + datetime.timedelta(days=1)).strftime("%Y-%m-%d")

    print(f"[vault] Target date : {target}")
    exams = [args.exam] if args.exam else VALID_EXAMS
    print(f"[vault] Exams       : {', '.join(exams)}")
    print(f"[vault] Count each  : {args.count}")

    for exam in exams:
        schedule_vault(db, target, exam, args.count)

    print("\n[vault] Done. App will sync on next launch.")


if __name__ == "__main__":
    main()
