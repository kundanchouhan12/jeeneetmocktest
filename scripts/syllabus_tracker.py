"""
syllabus_tracker.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Persistent Pedagogical Syllabus Engine & Progress Tracker for JEE & NEET.

Behaves like a structured teacher systematically covering the syllabus:
1. Tracks topic-level states across the official 5-level curriculum taxonomy:
     NOT_STARTED -> CONTENT_GENERATION -> NEEDS_REVIEW ->
     READY_TO_TEACH -> IN_PROGRESS -> COMPLETED -> REVISION_DUE
2. Tracks approved question counts, difficulty breakdown, diagram coverage,
   and timestamps per topic.
3. Only advances to the next topic when required questions pass validation.
4. If a topic fails generation, places it in a retry queue with a backoff cap
   (MAX_RETRIES = 3) instead of blocking the entire daily pipeline.
5. Advances sequentially through Class 11 & Class 12 units and topics, then
   transitions into progressive revision cycles.
6. Synchronizes with Firestore (metadata/syllabus_tracker) with local JSON fallback.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

import os
import sys
import json
import re
import datetime
from enum import Enum
from typing import Any, Optional, Dict, List

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import curriculum

STATE_FILE = os.path.join(SCRIPT_DIR, "syllabus_state.json")
MAX_RETRIES = 3
MIN_APPROVED_QUESTIONS_PER_TOPIC = 3


class TopicState(str, Enum):
    NOT_STARTED = "NOT_STARTED"
    CONTENT_GENERATION = "CONTENT_GENERATION"
    NEEDS_REVIEW = "NEEDS_REVIEW"
    READY_TO_TEACH = "READY_TO_TEACH"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"
    REVISION_DUE = "REVISION_DUE"


def slugify(text: str) -> str:
    """Creates a clean, stable slug for IDs."""
    text = text.lower().replace("&", "and").replace("-", "_")
    text = re.sub(r"[^a-z0-9_]+", "_", text).strip("_")
    return text[:40]


_SUBJECT_ALIASES = {
    "maths": "Maths", "math": "Maths", "mathematics": "Maths",
    "physics": "Physics", "chemistry": "Chemistry", "biology": "Biology",
}

_DIFFICULTY_ALIASES = {"easy": "Easy", "medium": "Medium", "hard": "Hard"}


def _norm_exam(value) -> str:
    v = str(value or "").strip().upper()
    if v.startswith("JEE"):
        return "JEE"
    if v.startswith("NEET"):
        return "NEET"
    return v


def _norm_subject(value) -> str:
    v = str(value or "").strip()
    return _SUBJECT_ALIASES.get(v.lower(), v.capitalize())


def _norm_difficulty(value) -> str:
    return _DIFFICULTY_ALIASES.get(str(value or "").strip().lower(), "Medium")


class SyllabusTracker:
    def __init__(self, curriculum_data: Optional[dict] = None):
        self.curriculum = curriculum_data or curriculum.get_curriculum()
        self.topics: Dict[str, dict] = {}
        self.cycle: Dict[str, Dict[str, int]] = {
            "JEE": {"Physics": 1, "Chemistry": 1, "Maths": 1},
            "NEET": {"Physics": 1, "Chemistry": 1, "Biology": 1}
        }
        self.last_updated: str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        self._init_curriculum_nodes()

    def _generate_topic_id(self, exam: str, subject: str, unit_num: int, topic_idx: int, topic_name: str) -> str:
        s_slug = slugify(subject)
        t_slug = slugify(topic_name)
        return f"{exam.lower()}_{s_slug}_u{unit_num}_t{topic_idx}_{t_slug}"

    def _init_curriculum_nodes(self):
        """Initializes all topics from curriculum.json."""
        for exam in ["JEE", "NEET"]:
            subjects = ["Physics", "Chemistry", "Maths"] if exam == "JEE" else ["Physics", "Chemistry", "Biology"]
            for subject in subjects:
                units = curriculum.get_official_units(exam, subject)
                for u in units:
                    unit_num = u.get("unit_number", 1)
                    unit_name = u.get("unit_name", "")
                    topics = u.get("topics", [])
                    for t_idx, t in enumerate(topics):
                        t_name = t.get("topic", "")
                        t_id = self._generate_topic_id(exam, subject, unit_num, t_idx, t_name)
                        allowed_modes = t.get("allowed_modes", ["TEXT"])

                        self.topics[t_id] = {
                            "topic_id": t_id,
                            "exam": exam,
                            "subject": subject,
                            "unit_number": unit_num,
                            "unit_name": unit_name,
                            "topic_name": t_name,
                            "topic_index": t_idx,
                            "allowed_modes": allowed_modes,
                            "state": TopicState.NOT_STARTED.value,
                            "approved_count": 0,
                            "difficulty_breakdown": {"Easy": 0, "Medium": 0, "Hard": 0},
                            "diagram_count": 0,
                            "last_processed_timestamp": None,
                            "last_vault_date": None,
                            "retry_count": 0,
                            "failure_reasons": [],
                            "cycle": 1
                        }

    def get_topic(self, topic_id: str) -> Optional[dict]:
        return self.topics.get(topic_id)

    def _topic_index(self) -> Dict[tuple, str]:
        """(exam, subject, lower(topic_name)) -> topic_id lookup."""
        idx: Dict[tuple, str] = {}
        for t_id, t in self.topics.items():
            key = (t["exam"], t["subject"], str(t.get("topic_name", "")).strip().lower())
            idx.setdefault(key, t_id)
        return idx

    def get_topics_for_subject(self, exam: str, subject: str) -> List[dict]:
        res = [
            t for t in self.topics.values()
            if t["exam"] == exam and t["subject"] == subject
        ]
        res.sort(key=lambda t: (t["unit_number"], t["topic_index"]))
        return res

    def get_next_eligible_topic(self, exam: str, subject: str) -> Optional[dict]:
        """
        Determines the next topic to teach/generate in natural syllabus order.
        1. Checks for topics in the retry queue (failed previous attempt, retry_count < MAX_RETRIES).
        2. Steps sequentially through Class 11 and 12 units for NOT_STARTED or CONTENT_GENERATION.
        3. If full curriculum completed, checks for topics due for revision (oldest last_vault_date).
        """
        subject_topics = self.get_topics_for_subject(exam, subject)
        if not subject_topics:
            return None

        # 1. Retry queue: Prioritize failed topics that haven't hit the MAX_RETRIES cap.
        # A failed-but-retryable topic is left in CONTENT_GENERATION by
        # record_generation_attempt(); NEEDS_REVIEW is reserved for topics that
        # already exhausted MAX_RETRIES (quarantined), so it must not be matched here.
        retries = [
            t for t in subject_topics
            if 0 < t["retry_count"] < MAX_RETRIES and t["state"] == TopicState.CONTENT_GENERATION.value
        ]
        if retries:
            return retries[0]

        # 2. Sequential syllabus walk: Find next uncompleted topic
        for t in subject_topics:
            if t["state"] in (TopicState.NOT_STARTED.value, TopicState.CONTENT_GENERATION.value):
                return t

        # 3. Check for topics needing top-up (fewer than MIN_APPROVED_QUESTIONS_PER_TOPIC)
        thin_topics = [
            t for t in subject_topics
            if t["approved_count"] < MIN_APPROVED_QUESTIONS_PER_TOPIC
            and t["state"] not in (TopicState.COMPLETED.value, TopicState.NEEDS_REVIEW.value)
            and t["retry_count"] < MAX_RETRIES
        ]
        if thin_topics:
            return thin_topics[0]

        # 4. Cycle Progression / Revision pass. Quarantined (NEEDS_REVIEW) topics
        # neither block the cycle nor get served.
        active = [t for t in subject_topics if t["state"] != TopicState.NEEDS_REVIEW.value]
        if not active:
            return None

        # Bump the cycle exactly once: only when EVERY active topic has been
        # vaulted (COMPLETED). After the bump all become REVISION_DUE, so this
        # condition stays false until the whole revision pass is vaulted again.
        if all(t["state"] == TopicState.COMPLETED.value for t in active):
            new_cycle = self.cycle.setdefault(exam, {}).get(subject, 1) + 1
            self.cycle[exam][subject] = new_cycle
            for t in active:
                t["state"] = TopicState.REVISION_DUE.value
                t["cycle"] = new_cycle

        # Sort by oldest last_vault_date (never-vaulted first)
        def sort_key(t):
            d = t.get("last_vault_date")
            return (0, "") if not d else (1, str(d))

        active.sort(key=sort_key)
        return active[0]

    def record_generation_attempt(
        self,
        topic_id: str,
        success: bool,
        new_approved_count: int = 0,
        difficulty_counts: Optional[dict] = None,
        has_diagram: bool = False,
        error_reason: Optional[str] = None
    ) -> dict:
        """
        Updates the tracker following a generation and validation run.
        Advances state only if validation passed; otherwise manages retries.
        """
        topic = self.topics.get(topic_id)
        if not topic:
            raise KeyError(f"Topic ID not found in curriculum tracker: {topic_id}")

        now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
        topic["last_processed_timestamp"] = now_iso

        if success:
            topic["retry_count"] = 0
            topic["approved_count"] += new_approved_count
            if difficulty_counts:
                for diff, count in difficulty_counts.items():
                    topic["difficulty_breakdown"][diff] = topic["difficulty_breakdown"].get(diff, 0) + count
            if has_diagram:
                topic["diagram_count"] += 1

            if topic["approved_count"] >= MIN_APPROVED_QUESTIONS_PER_TOPIC:
                topic["state"] = TopicState.READY_TO_TEACH.value
            else:
                topic["state"] = TopicState.CONTENT_GENERATION.value
        else:
            topic["retry_count"] += 1
            if error_reason:
                topic["failure_reasons"].append(f"{now_iso}: {error_reason}")
                topic["failure_reasons"] = topic["failure_reasons"][-5:]  # Keep last 5

            if topic["retry_count"] >= MAX_RETRIES:
                # Quarantine topic to avoid blocking the pipeline; needs human/system review
                topic["state"] = TopicState.NEEDS_REVIEW.value
            else:
                topic["state"] = TopicState.CONTENT_GENERATION.value

        self.last_updated = now_iso
        return topic

    def mark_topic_vaulted(self, topic_id: str, vault_date: str):
        """Marks that a topic's questions have been published in a Daily Vault."""
        topic = self.topics.get(topic_id)
        if topic:
            topic["last_vault_date"] = vault_date
            topic["state"] = TopicState.COMPLETED.value
            self.last_updated = datetime.datetime.now(datetime.timezone.utc).isoformat()

    def mark_questions_vaulted(self, exam: str, questions: List[dict], vault_date: str) -> int:
        """
        Records that `questions` were published in the `exam` Daily Vault on
        `vault_date`. Every matched topic gets its last_vault_date updated; it is
        only promoted to COMPLETED once it actually has enough approved questions,
        so a single vaulted question never stops generation for a thin topic.
        Returns the number of distinct topics touched.
        """
        idx = self._topic_index()
        ex = _norm_exam(exam)
        touched = set()
        promotable = (
            TopicState.READY_TO_TEACH.value, TopicState.IN_PROGRESS.value,
            TopicState.REVISION_DUE.value, TopicState.COMPLETED.value,
        )
        for q in questions or []:
            top = str(q.get("topic", "")).strip().lower()
            if not top:
                continue
            t_id = idx.get((ex, _norm_subject(q.get("subject")), top))
            if not t_id or t_id in touched:
                continue
            touched.add(t_id)
            topic = self.topics[t_id]
            prev = topic.get("last_vault_date")
            if not prev or str(vault_date) > str(prev):
                topic["last_vault_date"] = vault_date
            if topic["approved_count"] >= MIN_APPROVED_QUESTIONS_PER_TOPIC and topic["state"] in promotable:
                topic["state"] = TopicState.COMPLETED.value
        if touched:
            self.last_updated = datetime.datetime.now(datetime.timezone.utc).isoformat()
        return len(touched)

    def get_coverage_summary(self, exam: Optional[str] = None) -> dict:
        """Calculates global metrics across all topics."""
        exams = [exam] if exam else ["JEE", "NEET"]
        summary = {}

        for ex in exams:
            summary[ex] = {}
            subjects = ["Physics", "Chemistry", "Maths"] if ex == "JEE" else ["Physics", "Chemistry", "Biology"]
            for sub in subjects:
                topics = self.get_topics_for_subject(ex, sub)
                total = len(topics)
                ready = sum(1 for t in topics if t["state"] in (TopicState.READY_TO_TEACH.value, TopicState.COMPLETED.value))
                completed = sum(1 for t in topics if t["state"] == TopicState.COMPLETED.value)
                needs_review = sum(1 for t in topics if t["state"] == TopicState.NEEDS_REVIEW.value)
                total_approved = sum(t["approved_count"] for t in topics)
                diagram_coverage = sum(1 for t in topics if t["diagram_count"] > 0)
                diagram_allowed = sum(1 for t in topics if "DIAGRAM" in t["allowed_modes"])

                summary[ex][sub] = {
                    "total_topics": total,
                    "ready_or_completed": ready,
                    "completed": completed,
                    "needs_review": needs_review,
                    "coverage_percent": round((ready / total * 100), 1) if total else 0.0,
                    "total_approved_questions": total_approved,
                    "diagram_coverage": f"{diagram_coverage}/{diagram_allowed}" if diagram_allowed else "N/A",
                    "current_cycle": self.cycle[ex].get(sub, 1)
                }

        return summary

    def hydrate_from_question_bank(self, all_docs):
        """
        Recomputes per-topic approved counts from the live question bank.

        IDEMPOTENT: counts are REPLACED with the bank's ground truth, never added
        on top of the persisted state (the old additive version re-added the
        entire bank on every daily run, inflating counts ~5x within a week).

        Only questions whose `topic` exactly matches a curriculum topic are
        counted. Legacy questions that only carry a chapter/unit are NOT
        attributed to an arbitrary topic (the old fallback dumped every one of
        them onto the first topic of the unit).
        """
        if not all_docs:
            return

        idx = self._topic_index()
        counts: Dict[str, dict] = {}

        for doc in all_docs:
            doc_id = getattr(doc, "id", "") or ""
            q = doc.to_dict() if hasattr(doc, "to_dict") else doc
            if not q:
                continue
            # Daily Vault copies duplicate bank questions — never count them.
            if q.get("isDailyVault") is True or str(doc_id).startswith("vault_"):
                continue

            top = str(q.get("topic", "")).strip().lower()
            if not top:
                continue
            t_id = idx.get((_norm_exam(q.get("examType")), _norm_subject(q.get("subject")), top))
            if not t_id:
                continue

            c = counts.setdefault(t_id, {"n": 0, "diff": {"Easy": 0, "Medium": 0, "Hard": 0}, "img": 0})
            c["n"] += 1
            d = _norm_difficulty(q.get("difficulty"))
            c["diff"][d] = c["diff"].get(d, 0) + 1
            if q.get("imageUrl"):
                c["img"] += 1

        for t_id, node in self.topics.items():
            c = counts.get(t_id)
            node["approved_count"] = c["n"] if c else 0
            node["difficulty_breakdown"] = c["diff"] if c else {"Easy": 0, "Medium": 0, "Hard": 0}
            node["diagram_count"] = c["img"] if c else 0

            # Reconcile state with the real count. Never touch quarantined,
            # vaulted, or revision topics — those states are owned elsewhere.
            state = node.get("state")
            if node["approved_count"] >= MIN_APPROVED_QUESTIONS_PER_TOPIC:
                if state in (TopicState.NOT_STARTED.value, TopicState.CONTENT_GENERATION.value):
                    node["state"] = TopicState.READY_TO_TEACH.value
            elif state == TopicState.READY_TO_TEACH.value:
                # Was promoted on inflated counts — send it back for generation.
                node["state"] = (
                    TopicState.CONTENT_GENERATION.value if node["approved_count"] > 0
                    else TopicState.NOT_STARTED.value
                )

    def to_dict(self) -> dict:
        return {
            "cycle": self.cycle,
            "last_updated": self.last_updated,
            "topics": self.topics
        }

    # Per-topic progress fields that are safe to restore from persisted state.
    # Curriculum-owned fields (ids, names, unit numbers, allowed_modes) always
    # come from the current curriculum.json, never from stale saved state.
    _PERSISTED_FIELDS = (
        "state", "approved_count", "difficulty_breakdown", "diagram_count",
        "last_processed_timestamp", "last_vault_date", "retry_count",
        "failure_reasons", "cycle",
    )

    def load_dict(self, data: dict):
        if not data:
            return
        if "cycle" in data and isinstance(data["cycle"], dict):
            for ex, subs in data["cycle"].items():
                if isinstance(subs, dict):
                    self.cycle.setdefault(ex, {}).update(subs)
        if "last_updated" in data:
            self.last_updated = data["last_updated"]
        if "topics" in data:
            # Topic IDs embed the topic's list position, so inserting/removing a
            # topic in curriculum.json shifts IDs. Fall back to matching by
            # (exam, subject, topic_name) so progress follows the topic, and drop
            # topics that no longer exist in the syllabus (orphans).
            idx = self._topic_index()
            for t_id, t_val in data["topics"].items():
                if not isinstance(t_val, dict):
                    continue
                target_id = t_id if t_id in self.topics else idx.get((
                    t_val.get("exam"), t_val.get("subject"),
                    str(t_val.get("topic_name", "")).strip().lower(),
                ))
                if not target_id:
                    continue  # orphan: topic removed from the official syllabus
                node = self.topics[target_id]
                for k in self._PERSISTED_FIELDS:
                    if k in t_val:
                        node[k] = t_val[k]

    def save_local(self, filepath: str = STATE_FILE):
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, indent=2)

    def load_local(self, filepath: str = STATE_FILE) -> bool:
        if os.path.exists(filepath):
            try:
                with open(filepath, "r", encoding="utf-8") as f:
                    data = json.load(f)
                self.load_dict(data)
                return True
            except Exception as e:
                print(f"⚠️ Error loading local syllabus tracker state: {e}")
        return False

    def sync_from_firestore(self, db, all_docs=None):
        """Loads state from Firestore metadata/syllabus_tracker, with local fallback."""
        loaded = False
        if db:
            try:
                doc = db.collection("metadata").document("syllabus_tracker").get()
                if doc.exists:
                    self.load_dict(doc.to_dict())
                    loaded = True
            except Exception as e:
                print(f"⚠️ Could not read metadata/syllabus_tracker from Firestore: {e}")

        if not loaded:
            self.load_local()

        if all_docs:
            self.hydrate_from_question_bank(all_docs)

    def sync_to_firestore(self, db):
        """Persists state to Firestore and local JSON."""
        self.save_local()
        if db:
            try:
                # Store full state & summary
                payload = self.to_dict()
                payload["summary"] = self.get_coverage_summary()
                # Full overwrite (no merge): merge=True deep-merges the `topics`
                # map, so topics removed from the curriculum would live forever.
                db.collection("metadata").document("syllabus_tracker").set(payload)
            except Exception as e:
                print(f"⚠️ Could not write metadata/syllabus_tracker to Firestore: {e}")


_GLOBAL_TRACKER: Optional[SyllabusTracker] = None

def get_tracker(db=None, all_docs=None) -> SyllabusTracker:
    """Returns singleton instance of SyllabusTracker."""
    global _GLOBAL_TRACKER
    if _GLOBAL_TRACKER is None:
        _GLOBAL_TRACKER = SyllabusTracker()
        _GLOBAL_TRACKER.sync_from_firestore(db=db, all_docs=all_docs)
    return _GLOBAL_TRACKER
