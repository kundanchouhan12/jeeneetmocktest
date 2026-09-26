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
COOLDOWN_DAYS = 30


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


def select_vault_questions(
    pool: list,
    vault_history=None,
    count: int = EXPECTED_VAULT_COUNT,
    target_date: str = None,
    cooldown_days: int = COOLDOWN_DAYS,
    recently_used_ids=None,
) -> list:
    """
    Picks exactly `count` items from `pool` according to the 3-Tier Freshness Policy:
      - Tier 1 (Never Used): Questions that have never appeared in any historical Daily Vault.
        Highest priority. Selected first.
      - Tier 2 (Cooldown Cleared): Questions whose latest historical Daily Vault date is
        strictly MORE than `cooldown_days` before `target_date`. Selected using LRU
        (oldest last-served date first).
      - Tier 3 (Active Cooldown): Questions whose latest Daily Vault date is within the
        last `cooldown_days` (<= cooldown_days). Strictly excluded.

    Raises VaultContractError if:
      - Total pool has fewer than `count` candidates.
      - Combined Tier 1 + Tier 2 has fewer than `count` candidates (refuses to violate cooldown).
    """
    if vault_history is None and recently_used_ids is not None:
        vault_history = recently_used_ids
    elif vault_history is None:
        vault_history = {}

    if len(pool) < count:
        raise VaultContractError(
            f"select_vault_questions: pool has {len(pool)} candidates, need exactly {count}. "
            "Refusing to silently shrink the Daily Vault selection."
        )

    if target_date is None:
        target_dt = datetime.date.today() + datetime.timedelta(days=1)
    elif isinstance(target_date, datetime.date):
        target_dt = target_date
    else:
        target_dt = datetime.date.fromisoformat(str(target_date))

    history_dict = vault_history if isinstance(vault_history, dict) else {k: None for k in vault_history}

    tier1 = []  # Fresh / never used
    tier2 = []  # (last_served_dt, doc) -> Cooldown cleared
    tier3 = []  # Active cooldown

    for doc in pool:
        last_date_val = history_dict.get(doc.id)
        if last_date_val is None and doc.id in history_dict and not isinstance(vault_history, dict):
            # Legacy test compatibility when a raw set of IDs with no dates is passed
            tier2.append((datetime.date.min, doc))
        elif last_date_val is None:
            tier1.append(doc)
        else:
            try:
                if isinstance(last_date_val, datetime.date):
                    last_dt = last_date_val
                else:
                    last_dt = datetime.date.fromisoformat(str(last_date_val))
                days_ago = (target_dt - last_dt).days
                if days_ago > cooldown_days:
                    tier2.append((last_dt, doc))
                else:
                    tier3.append(doc)
            except Exception:
                tier2.append((datetime.date.min, doc))

    fresh_n = min(count, len(tier1))
    selected = random.sample(tier1, fresh_n) if fresh_n else []

    needed_from_cooldown = count - fresh_n
    if needed_from_cooldown > 0:
        if len(tier2) < needed_from_cooldown:
            raise VaultContractError(
                f"select_vault_questions: need {count} questions, but only {len(tier1)} fresh and "
                f"{len(tier2)} cooldown-cleared questions available ({len(tier3)} in active {cooldown_days}-day cooldown). "
                f"Refusing to violate the {cooldown_days}-day cooldown policy."
            )
        # Sort Tier 2 by oldest last-served date first (LRU)
        tier2.sort(key=lambda item: (item[0], item[1].id))
        selected += [doc for _, doc in tier2[:needed_from_cooldown]]

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
    # Build map of source_id -> latest vaultDate (ignoring target_date itself for idempotency)
    vault_history: dict[str, str] = {}
    for d in previous_vault_docs:
        doc_id = d.id
        q_data = d.to_dict() or {}
        vdate = q_data.get("vaultDate")
        if not vdate or vdate == target_date:
            continue
        if doc_id.startswith("vault_") and doc_id.count("_") >= 2:
            src_id = doc_id.split("_", 2)[2]
            if src_id not in vault_history or vdate > vault_history[src_id]:
                vault_history[src_id] = vdate

    target_dt = datetime.date.fromisoformat(target_date)
    fresh_count = len([d for d in pool if d.id not in vault_history])
    cooldown_cleared_count = len([
        d for d in pool if d.id in vault_history and
        (target_dt - datetime.date.fromisoformat(vault_history[d.id])).days > COOLDOWN_DAYS
    ])
    active_cooldown_count = len([
        d for d in pool if d.id in vault_history and
        (target_dt - datetime.date.fromisoformat(vault_history[d.id])).days <= COOLDOWN_DAYS
    ])
    print(f"  • {exam_type} Pool Breakdown: {fresh_count} fresh (never-vaulted), "
          f"{cooldown_cleared_count} cooldown-cleared (> {COOLDOWN_DAYS}d), "
          f"{active_cooldown_count} active cooldown (<= {COOLDOWN_DAYS}d).")

    selected = select_vault_questions(pool, vault_history, count, target_date=target_date, cooldown_days=COOLDOWN_DAYS)
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
