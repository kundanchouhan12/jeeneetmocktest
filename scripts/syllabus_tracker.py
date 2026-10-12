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

        # 1. Retry queue: Prioritize failed topics that haven't hit the MAX_RETRIES cap
        retries = [
            t for t in subject_topics
            if 0 < t["retry_count"] < MAX_RETRIES and t["state"] == TopicState.NEEDS_REVIEW.value
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
            if t["approved_count"] < MIN_APPROVED_QUESTIONS_PER_TOPIC and t["retry_count"] < MAX_RETRIES
        ]
        if thin_topics:
            return thin_topics[0]

        # 4. Cycle Progression / Revision pass
        # Sort by oldest last_vault_date (or None)
        def sort_key(t):
            d = t.get("last_vault_date")
            return (0, "") if not d else (1, str(d))

        subject_topics.sort(key=sort_key)
        candidate = subject_topics[0]

        # Mark cycle bump if advancing
        current_cycle = self.cycle[exam].get(subject, 1)
        if all(t["state"] in (TopicState.COMPLETED.value, TopicState.REVISION_DUE.value) for t in subject_topics):
            self.cycle[exam][subject] = current_cycle + 1
            for t in subject_topics:
                t["state"] = TopicState.REVISION_DUE.value

        return candidate

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
        Scans existing Firestore question documents and populates baseline topic approved counts.
        """
        if not all_docs:
            return

        for doc in all_docs:
            q = doc.to_dict() if hasattr(doc, "to_dict") else doc
            if not q:
                continue

            exam = str(q.get("examType", "")).upper()
            sub = str(q.get("subject", "")).capitalize()
            ch = str(q.get("chapter", ""))
            top = str(q.get("topic", ""))
            diff = q.get("difficulty", "Medium")
            has_img = bool(q.get("imageUrl"))

            # Match to topic node
            matched_id = None
            if top:
                for t_id, t in self.topics.items():
                    if t["exam"] == exam and t["subject"] == sub and t["topic_name"].lower() == top.lower():
                        matched_id = t_id
                        break

            if not matched_id and ch:
                # Match by unit/chapter
                for t_id, t in self.topics.items():
                    if t["exam"] == exam and t["subject"] == sub:
                        if ch.lower() in t["unit_name"].lower() or t["unit_name"].lower() in ch.lower():
                            matched_id = t_id
                            break

            if matched_id:
                node = self.topics[matched_id]
                node["approved_count"] += 1
                node["difficulty_breakdown"][diff] = node["difficulty_breakdown"].get(diff, 0) + 1
                if has_img:
                    node["diagram_count"] += 1
                if node["approved_count"] >= MIN_APPROVED_QUESTIONS_PER_TOPIC:
                    node["state"] = TopicState.READY_TO_TEACH.value

    def to_dict(self) -> dict:
        return {
            "cycle": self.cycle,
            "last_updated": self.last_updated,
            "topics": self.topics
        }

    def load_dict(self, data: dict):
        if not data:
            return
        if "cycle" in data:
            self.cycle = data["cycle"]
        if "last_updated" in data:
            self.last_updated = data["last_updated"]
        if "topics" in data:
            for t_id, t_val in data["topics"].items():
                if t_id in self.topics:
                    self.topics[t_id].update(t_val)
                else:
                    self.topics[t_id] = t_val

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
                db.collection("metadata").document("syllabus_tracker").set(payload, merge=True)
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
