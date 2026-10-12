"""
test_syllabus_tracker.py — Unit tests for the Pedagogical Syllabus Tracker.
"""

import unittest
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from syllabus_tracker import SyllabusTracker, TopicState, MAX_RETRIES, MIN_APPROVED_QUESTIONS_PER_TOPIC


class TestSyllabusTracker(unittest.TestCase):

    def setUp(self):
        self.tracker = SyllabusTracker()

    def test_topic_initialization(self):
        """Tracker must initialize all units and topics from curriculum.json."""
        self.assertGreater(len(self.tracker.topics), 100)
        
        # Verify JEE subjects exist
        jee_phy = self.tracker.get_topics_for_subject("JEE", "Physics")
        jee_chem = self.tracker.get_topics_for_subject("JEE", "Chemistry")
        jee_math = self.tracker.get_topics_for_subject("JEE", "Maths")
        self.assertGreater(len(jee_phy), 20)
        self.assertGreater(len(jee_chem), 20)
        self.assertGreater(len(jee_math), 20)

        # Verify NEET subjects exist
        neet_bio = self.tracker.get_topics_for_subject("NEET", "Biology")
        self.assertGreater(len(neet_bio), 30)

        # Verify initial states
        first_topic = jee_phy[0]
        self.assertEqual(first_topic["state"], TopicState.NOT_STARTED.value)
        self.assertEqual(first_topic["approved_count"], 0)
        self.assertEqual(first_topic["retry_count"], 0)

    def test_topic_id_stability(self):
        """Topic IDs must be deterministic, non-empty, and unique."""
        all_ids = list(self.tracker.topics.keys())
        self.assertEqual(len(all_ids), len(set(all_ids)))
        for tid in all_ids:
            self.assertTrue(tid.startswith("jee_") or tid.startswith("neet_"))
            self.assertIn("_u", tid)
            self.assertIn("_t", tid)

    def test_sequential_syllabus_progression(self):
        """The tracker must serve topics in exact curriculum order (Unit 1 -> Unit 2...)."""
        phy_topics = self.tracker.get_topics_for_subject("JEE", "Physics")
        first = self.tracker.get_next_eligible_topic("JEE", "Physics")
        self.assertIsNotNone(first)
        self.assertEqual(first["topic_id"], phy_topics[0]["topic_id"])

        # Mark first completed with enough questions
        self.tracker.record_generation_attempt(
            first["topic_id"],
            success=True,
            new_approved_count=MIN_APPROVED_QUESTIONS_PER_TOPIC,
            difficulty_counts={"Easy": 1, "Medium": 2}
        )
        self.assertEqual(self.tracker.get_topic(first["topic_id"])["state"], TopicState.READY_TO_TEACH.value)

        # Next topic must be topic #2 in curriculum order
        second = self.tracker.get_next_eligible_topic("JEE", "Physics")
        self.assertIsNotNone(second)
        self.assertEqual(second["topic_id"], phy_topics[1]["topic_id"])

    def test_failure_retry_and_quarantine(self):
        """Failed generations must be retried up to MAX_RETRIES, then quarantined without blocking pipeline."""
        first = self.tracker.get_next_eligible_topic("JEE", "Maths")
        t_id = first["topic_id"]

        # Failure 1: Enters retry queue
        self.tracker.record_generation_attempt(t_id, success=False, error_reason="LLM Rate Limit")
        topic = self.tracker.get_topic(t_id)
        self.assertEqual(topic["retry_count"], 1)
        self.assertEqual(topic["state"], TopicState.CONTENT_GENERATION.value)

        # Still next eligible due to retry priority
        next_t = self.tracker.get_next_eligible_topic("JEE", "Maths")
        self.assertEqual(next_t["topic_id"], t_id)

        # Fail up to MAX_RETRIES
        for _ in range(MAX_RETRIES - 1):
            self.tracker.record_generation_attempt(t_id, success=False, error_reason="Schema error")

        # Now quarantined to NEEDS_REVIEW
        topic = self.tracker.get_topic(t_id)
        self.assertEqual(topic["retry_count"], MAX_RETRIES)
        self.assertEqual(topic["state"], TopicState.NEEDS_REVIEW.value)

        # Pipeline must NOT be blocked! It must advance to the next topic
        next_t = self.tracker.get_next_eligible_topic("JEE", "Maths")
        self.assertNotEqual(next_t["topic_id"], t_id)

    def test_mark_topic_vaulted(self):
        """Marking a topic vaulted sets last_vault_date and transitions to COMPLETED."""
        first = self.tracker.get_next_eligible_topic("NEET", "Biology")
        t_id = first["topic_id"]
        self.tracker.record_generation_attempt(t_id, success=True, new_approved_count=5)
        self.tracker.mark_topic_vaulted(t_id, "2026-10-15")

        topic = self.tracker.get_topic(t_id)
        self.assertEqual(topic["state"], TopicState.COMPLETED.value)
        self.assertEqual(topic["last_vault_date"], "2026-10-15")

    def test_coverage_summary(self):
        """Summary must calculate coverage percentages and approved counts cleanly."""
        summary = self.tracker.get_coverage_summary("JEE")
        self.assertIn("Physics", summary["JEE"])
        self.assertIn("Chemistry", summary["JEE"])
        self.assertIn("Maths", summary["JEE"])
        
        phy_stat = summary["JEE"]["Physics"]
        self.assertIn("total_topics", phy_stat)
        self.assertIn("coverage_percent", phy_stat)
        self.assertIn("current_cycle", phy_stat)

    def test_serialization_roundtrip(self):
        """Tracker must cleanly export to dict and restore without data loss."""
        first = self.tracker.get_next_eligible_topic("JEE", "Physics")
        self.tracker.record_generation_attempt(
            first["topic_id"], success=True, new_approved_count=4, has_diagram=True
        )

        d = self.tracker.to_dict()
        new_tracker = SyllabusTracker()
        new_tracker.load_dict(d)

        restored_node = new_tracker.get_topic(first["topic_id"])
        self.assertEqual(restored_node["approved_count"], 4)
        self.assertEqual(restored_node["diagram_count"], 1)
        self.assertEqual(restored_node["state"], TopicState.READY_TO_TEACH.value)


if __name__ == "__main__":
    unittest.main()
