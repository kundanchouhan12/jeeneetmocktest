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
import time

import firebase_admin
from firebase_admin import credentials, firestore

VALID_EXAMS = ["JEE", "NEET"]
EXPECTED_VAULT_COUNT = 30
COOLDOWN_DAYS = 30

DEFAULT_EXAM_QUOTAS = {
    "JEE": {"Physics": 10, "Chemistry": 10, "Maths": 10},
    "NEET": {"Physics": 8, "Chemistry": 8, "Biology": 14},
}


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


from purge_duplicate_questions import normalize_text


def question_fingerprint(q: dict) -> str:
    r"""
    Normalized fingerprint for deduplication — uses purge_duplicate_questions.normalize_text()
    to safely normalize LaTeX formatting, delimiters, and minor trailing punctuation without
    losing numbers, math operators (+, -, *, /, ^, =, <, >), or scientific commands (\sin, \cos, etc.).
    """
    return normalize_text(str(q.get("questionText") or ""))


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
    try:
        import curriculum
        if curriculum.is_deleted_chapter(q.get("chapter", "")):
            return False, f"deleted_syllabus_chapter:{q.get('chapter')}"
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


def assert_selected_vault(selected: list, exam_type: str, target_date: str, count: int = EXPECTED_VAULT_COUNT, force: bool = False):
    if len(selected) != count:
        raise VaultContractError(
            f"{exam_type} {target_date}: selected {len(selected)} questions, need exactly {count}. "
            "Incomplete vaults are not written."
        )
    ids = [d.id for d in selected]
    if len(set(ids)) != count:
        raise VaultContractError(f"{exam_type} {target_date}: duplicate source document IDs in selection")
    group_id = f"{exam_type.lower()}_vault_{target_date}_{int(time.time())}"
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


def _get_doc_data(doc) -> dict:
    if hasattr(doc, "to_dict") and callable(doc.to_dict):
        return doc.to_dict() or {}
    if isinstance(doc, dict):
        return doc
    return getattr(doc, "__dict__", {})


def _has_diagram(doc) -> bool:
    data = _get_doc_data(doc)
    return bool(data.get("imageUrl"))


def _classify_pool(pool: list, history_dict: dict, target_dt: datetime.date, cooldown_days: int, is_raw_set: bool = False):
    tier1 = []  # Fresh / never used
    tier2 = []  # (last_served_dt, doc) -> Cooldown cleared
    tier3 = []  # Active cooldown

    for doc in pool:
        last_date_val = history_dict.get(doc.id)
        if last_date_val is None and doc.id in history_dict and is_raw_set:
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
    return tier1, tier2, tier3


def _count_available(pool: list, history_dict: dict, target_dt: datetime.date, cooldown_days: int, is_raw_set: bool = False) -> int:
    t1, t2, _ = _classify_pool(pool, history_dict, target_dt, cooldown_days, is_raw_set)
    return len(t1) + len(t2)


def _pick_with_topic_diversity(candidates: list, needed: int, max_per_topic: int = 2, shuffle: bool = True) -> list:
    """Picks up to `needed` questions from `candidates`, enforcing topic diversity while preserving candidate order if shuffle=False."""
    if not candidates or needed <= 0:
        return []
    items = list(candidates)
    if shuffle:
        random.shuffle(items)

    # Check if distinct topics actually exist in candidates
    topics_in_candidates = {(_get_doc_data(d).get("topic") or _get_doc_data(d).get("chapter")) for d in items}
    topics_in_candidates.discard(None)

    # If no topics are defined (e.g. FakeDoc in unit tests) or only 1 topic, take in original order
    if len(topics_in_candidates) <= 1:
        return items[:needed]

    topic_counts = {}
    picked = []

    # Pass 1: Enforce max_per_topic
    for d in items:
        if len(picked) >= needed:
            break
        data = _get_doc_data(d)
        topic = data.get("topic") or data.get("chapter") or "general"
        if topic_counts.get(topic, 0) < max_per_topic:
            picked.append(d)
            topic_counts[topic] = topic_counts.get(topic, 0) + 1

    # Pass 2: Relax if still needed
    if len(picked) < needed:
        picked_ids = {d.id for d in picked}
        for d in items:
            if len(picked) >= needed:
                break
            if d.id not in picked_ids:
                picked.append(d)
    return picked[:needed]


def _select_from_sub_pool(
    pool: list,
    needed_count: int,
    history_dict: dict,
    target_dt: datetime.date,
    cooldown_days: int,
    max_per_topic: int = 2,
    max_diagrams: int = 2,
    is_raw_set: bool = False,
) -> list:
    tier1, tier2, tier3 = _classify_pool(pool, history_dict, target_dt, cooldown_days, is_raw_set)
    total_avail = len(tier1) + len(tier2)
    if total_avail < needed_count:
        raise VaultContractError(
            f"select_vault_questions: need {needed_count} questions, but only {len(tier1)} fresh and "
            f"{len(tier2)} cooldown-cleared questions available ({len(tier3)} in active {cooldown_days}-day cooldown). "
            f"Refusing to violate the {cooldown_days}-day cooldown policy."
        )

    selected = []
    fresh_n = min(needed_count, len(tier1))
    if fresh_n > 0:
        t1_diags = [d for d in tier1 if _has_diagram(d)]
        t1_text = [d for d in tier1 if not _has_diagram(d)]
        target_diags = min(max_diagrams, len(t1_diags))
        selected_diags = random.sample(t1_diags, target_diags) if target_diags else []
        needed_text = fresh_n - len(selected_diags)
        selected_text = _pick_with_topic_diversity(t1_text, needed_text, max_per_topic=max_per_topic, shuffle=True)
        selected = selected_diags + selected_text

    needed_from_cooldown = needed_count - len(selected)
    if needed_from_cooldown > 0:
        current_diags = len([d for d in selected if _has_diagram(d)])
        t2_diags = [(dt, d) for dt, d in tier2 if _has_diagram(d)]
        t2_text = [(dt, d) for dt, d in tier2 if not _has_diagram(d)]
        t2_diags.sort(key=lambda item: (item[0], item[1].id))
        t2_text.sort(key=lambda item: (item[0], item[1].id))

        picked_t2_diags = []
        if current_diags < max_diagrams and t2_diags:
            take_diags = min(max_diagrams - current_diags, len(t2_diags), needed_from_cooldown)
            picked_t2_diags = [d for _, d in t2_diags[:take_diags]]
            t2_diags = t2_diags[take_diags:]

        needed_text_t2 = needed_from_cooldown - len(picked_t2_diags)
        pool_rem = t2_diags + t2_text
        pool_rem.sort(key=lambda item: (item[0], item[1].id))
        picked_t2_text = _pick_with_topic_diversity([d for _, d in pool_rem], needed_text_t2, max_per_topic=max_per_topic, shuffle=False)
        selected += picked_t2_diags + picked_t2_text

    return selected[:needed_count]


def select_vault_questions(
    pool: list,
    vault_history=None,
    count: int = EXPECTED_VAULT_COUNT,
    target_date: str = None,
    cooldown_days: int = COOLDOWN_DAYS,
    recently_used_ids=None,
    subject_quotas: dict = None,
    max_per_topic: int = 2,
) -> list:
    """
    Picks exactly `count` items from `pool` according to the 3-Tier Freshness Policy & Subject Quotas:
      - Subject Quotas: 10:10:10 PCM for JEE, 8:8:14 PCB for NEET daily practice set.
      - Topic Diversity: limits max questions per topic within a daily set (max_per_topic=2).
      - Diagram Integration: up to 2 STEM diagrams per subject (up to 5 total) in daily vaults.
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
    is_raw_set = False
    if vault_history is None and recently_used_ids is not None:
        vault_history = recently_used_ids
        is_raw_set = not isinstance(recently_used_ids, dict)
    elif vault_history is not None:
        is_raw_set = not isinstance(vault_history, dict)
    else:
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

    if not subject_quotas:
        return _select_from_sub_pool(
            pool, count, history_dict, target_dt, cooldown_days,
            max_per_topic=max_per_topic, max_diagrams=5, is_raw_set=is_raw_set
        )

    # Subject-partitioned selection with safeguards & topic diversity
    pool_by_subj = {s: [] for s in subject_quotas}
    unmatched = []
    for doc in pool:
        data = _get_doc_data(doc)
        s = data.get("subject")
        if s in pool_by_subj:
            pool_by_subj[s].append(doc)
        else:
            unmatched.append(doc)

    avail_counts = {s: _count_available(pool_by_subj[s], history_dict, target_dt, cooldown_days, is_raw_set) for s in subject_quotas}
    unmatched_avail = _count_available(unmatched, history_dict, target_dt, cooldown_days, is_raw_set)
    total_avail = sum(avail_counts.values()) + unmatched_avail

    if total_avail < count:
        shortages = [f"{s} (avail {avail_counts[s]} < quota {req})" for s, req in subject_quotas.items() if avail_counts[s] < req]
        raise VaultContractError(
            f"select_vault_questions: total available pool is {total_avail} < {count}. "
            f"Shortages detected: {', '.join(shortages) if shortages else 'overall shortage'}."
        )

    # Safeguard: check shortages and reallocate gracefully without compromising quality
    allocated_quotas = {}
    deficit = 0
    for s, req in subject_quotas.items():
        avail = avail_counts[s]
        take = min(req, avail)
        allocated_quotas[s] = take
        if take < req:
            deficit += (req - take)
            print(f"  ⚠️ [Vault Safeguard] Shortage in subject '{s}': available={avail} < quota={req}")

    if deficit > 0:
        # Reallocate deficit to other subjects with surplus
        for s in subject_quotas:
            if deficit <= 0:
                break
            surplus = avail_counts[s] - allocated_quotas[s]
            if surplus > 0:
                extra = min(deficit, surplus)
                allocated_quotas[s] += extra
                deficit -= extra

    selected = []
    for s, alloc_n in allocated_quotas.items():
        if alloc_n > 0:
            subj_selected = _select_from_sub_pool(
                pool_by_subj[s], alloc_n, history_dict, target_dt, cooldown_days,
                max_per_topic=max_per_topic, max_diagrams=2, is_raw_set=is_raw_set
            )
            selected.extend(subj_selected)

    if len(selected) < count and unmatched:
        needed = count - len(selected)
        extra = _select_from_sub_pool(
            unmatched, needed, history_dict, target_dt, cooldown_days,
            max_per_topic=max_per_topic, max_diagrams=1, is_raw_set=is_raw_set
        )
        selected.extend(extra)

    return selected[:count]


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


def is_vault_complete_in_firestore(db, target_date: str, exam_type: str, count: int = EXPECTED_VAULT_COUNT) -> bool:
    try:
        docs = (
            db.collection("questions")
            .where("vaultDate", "==", target_date)
            .where("examType", "==", exam_type)
            .where("isDailyVault", "==", True)
            .get()
        )
        if len(docs) != count:
            return False
        valid = 0
        for doc in docs:
            d = doc.to_dict() or {}
            if d.get("vaultGroupId") and len(d.get("options", [])) == 4 and d.get("correctOptionIndex") is not None:
                valid += 1
        return valid == count
    except Exception:
        return False


def schedule_vault(db, target_date: str, exam_type: str, count: int = EXPECTED_VAULT_COUNT, force: bool = False, all_docs=None) -> None:
    print(f"\n[vault] Scheduling {exam_type} vault for {target_date} ({count} questions)...")
    if count != EXPECTED_VAULT_COUNT:
        raise VaultContractError(f"Daily Vault count must be {EXPECTED_VAULT_COUNT}, got {count}")

    questions_ref = db.collection("questions")

    if all_docs is not None:
        all_exam_docs = [d for d in all_docs if (d.to_dict() or {}).get("examType") == exam_type]
    else:
        all_exam_docs = questions_ref.where("examType", "==", exam_type).get()

    pool = [d for d in all_exam_docs if not d.id.startswith("vault_")]

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

    if all_docs is not None:
        previous_vault_docs = [
            d for d in all_docs
            if (d.to_dict() or {}).get("isDailyVault") is True
            and (d.to_dict() or {}).get("examType") == exam_type
        ]
    else:
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

    quotas = DEFAULT_EXAM_QUOTAS.get(exam_type)
    selected = select_vault_questions(
        pool,
        vault_history,
        count,
        target_date=target_date,
        cooldown_days=COOLDOWN_DAYS,
        subject_quotas=quotas,
        max_per_topic=2,
    )
    payloads, group_id = assert_selected_vault(selected, exam_type, target_date, count, force=force)

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

    try:
        db.collection("metadata").document("question_bank").set({
            "version": int(time.time()),
            "lastUpdated": firestore.SERVER_TIMESTAMP,
            "vault_date": target,
        }, merge=True)
        print("  [metadata] Bumped question_bank version timestamp.")
    except Exception as e:
        print(f"  [metadata] Warning: Could not update question_bank metadata: {e}")

    print("\n[vault] Done. App will sync on next launch.")


if __name__ == "__main__":
    main()
