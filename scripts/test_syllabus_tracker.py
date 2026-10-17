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


    def test_hydrate_idempotent(self):
        """Hydrating from question bank must replace counts idempotently and ignore vault docs."""
        phy_topics = self.tracker.get_topics_for_subject("JEE", "Physics")
        target_topic = phy_topics[0]
        t_name = target_topic["topic_name"]

        docs = [
            {"examType": "JEE", "subject": "Physics", "topic": t_name, "difficulty": "Easy", "questionText": "Q1"},
            {"examType": "JEE", "subject": "Physics", "topic": t_name, "difficulty": "Medium", "imageUrl": "https://img", "questionText": "Q2"},
            {"examType": "JEE", "subject": "Physics", "topic": t_name, "difficulty": "Hard", "questionText": "Q3"},
            # Vault doc must be ignored
            {"examType": "JEE", "subject": "Physics", "topic": t_name, "isDailyVault": True, "questionText": "Q_vault"},
        ]

        # First hydration
        self.tracker.hydrate_from_question_bank(docs)
        node = self.tracker.get_topic(target_topic["topic_id"])
        self.assertEqual(node["approved_count"], 3)
        self.assertEqual(node["diagram_count"], 1)
        self.assertEqual(node["difficulty_breakdown"]["Easy"], 1)
        self.assertEqual(node["difficulty_breakdown"]["Medium"], 1)
        self.assertEqual(node["difficulty_breakdown"]["Hard"], 1)
        self.assertEqual(node["state"], TopicState.READY_TO_TEACH.value)

        # Re-hydration with same docs MUST be idempotent (never 6 or 9)
        self.tracker.hydrate_from_question_bank(docs)
        node_again = self.tracker.get_topic(target_topic["topic_id"])
        self.assertEqual(node_again["approved_count"], 3)
        self.assertEqual(node_again["diagram_count"], 1)

    def test_mark_questions_vaulted_batch(self):
        """Batch vault marking should update last_vault_date and transition qualified topics to COMPLETED."""
        chem_topics = self.tracker.get_topics_for_subject("JEE", "Chemistry")
        t0 = chem_topics[0]
        # Approve 3 questions so it qualifies
        self.tracker.record_generation_attempt(t0["topic_id"], success=True, new_approved_count=3)
        self.assertEqual(self.tracker.get_topic(t0["topic_id"])["state"], TopicState.READY_TO_TEACH.value)

        # Mark vaulted via questions batch
        vault_batch = [
            {"examType": "JEE", "subject": "Chemistry", "topic": t0["topic_name"], "questionText": "Chem Q1"}
        ]
        touched = self.tracker.mark_questions_vaulted("JEE", vault_batch, "2026-10-18")
        self.assertEqual(touched, 1)

        node = self.tracker.get_topic(t0["topic_id"])
        self.assertEqual(node["state"], TopicState.COMPLETED.value)
        self.assertEqual(node["last_vault_date"], "2026-10-18")

    def test_cycle_progression_single_bump(self):
        """Completing a curriculum cycle bumps cycle by 1, transitions to REVISION_DUE, and does not repeatedly bump."""
        topics = self.tracker.get_topics_for_subject("JEE", "Physics")
        # Mark all topics COMPLETED with sufficient approved questions
        for t in topics:
            t["approved_count"] = 5
            t["state"] = TopicState.COMPLETED.value
            t["last_vault_date"] = "2026-09-01"

        self.assertEqual(self.tracker.cycle["JEE"]["Physics"], 1)

        # Next eligible topic should trigger cycle bump to 2 and transition topics to REVISION_DUE
        next_t = self.tracker.get_next_eligible_topic("JEE", "Physics")
        self.assertIsNotNone(next_t)
        self.assertEqual(self.tracker.cycle["JEE"]["Physics"], 2)
        self.assertEqual(next_t["state"], TopicState.REVISION_DUE.value)

        # Calling get_next_eligible_topic again should NOT increment the cycle to 3
        again_t = self.tracker.get_next_eligible_topic("JEE", "Physics")
        self.assertEqual(self.tracker.cycle["JEE"]["Physics"], 2)


if __name__ == "__main__":
    unittest.main()
