package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.ExamConfig
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestSession
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TestSessionTest {

    private lateinit var session: TestSession

    private fun makeQuestion(id: Int, correct: Int = 0) = Question(
        id = id,
        examType = "JEE", subject = "Physics", chapter = "Kinematics",
        difficulty = "Medium", year = 2023,
        questionText = "Question $id",
        options = listOf("A", "B", "C", "D"),
        correctOptionIndex = correct,
        explanation = ""
    )

    @Before
    fun setUp() {
        val config = ExamConfig.jeeMainsFull()
        val questions = (1..5).map { makeQuestion(it) }
        session = TestSession(config, questions)
    }

    // ─── Initial state ───────────────────────────────────────────────────────

    @Test
    fun `answers map is empty initially`() {
        assertTrue(session.answers.isEmpty())
    }

    @Test
    fun `markedForReview set is empty initially`() {
        assertTrue(session.markedForReview.isEmpty())
    }

    @Test
    fun `isAnswered returns false for unvisited question`() {
        assertFalse(session.isAnswered(0))
    }

    @Test
    fun `isMarkedForReview returns false for unmarked question`() {
        assertFalse(session.isMarkedForReview(0))
    }

    // ─── Answering ───────────────────────────────────────────────────────────

    @Test
    fun `answerQuestion stores the selected option`() {
        session.answerQuestion(0, 2)
        assertEquals(2, session.answers[0])
    }

    @Test
    fun `isAnswered returns true after answering`() {
        session.answerQuestion(0, 1)
        assertTrue(session.isAnswered(0))
    }

    @Test
    fun `answerQuestion can overwrite a previous answer`() {
        session.answerQuestion(0, 0)
        session.answerQuestion(0, 3)
        assertEquals(3, session.answers[0])
    }

    @Test
    fun `answering one question does not affect others`() {
        session.answerQuestion(2, 1)
        assertFalse(session.isAnswered(0))
        assertFalse(session.isAnswered(1))
        assertTrue(session.isAnswered(2))
        assertFalse(session.isAnswered(3))
    }

    @Test
    fun `clearing an answer removes it from the map`() {
        session.answerQuestion(0, 1)
        session.answers.remove(0)
        assertFalse(session.isAnswered(0))
        assertNull(session.answers[0])
    }

    // ─── Mark for Review ─────────────────────────────────────────────────────

    @Test
    fun `toggleReview marks an unmarked question`() {
        session.toggleReview(1)
        assertTrue(session.isMarkedForReview(1))
    }

    @Test
    fun `toggleReview unmarks an already-marked question`() {
        session.toggleReview(1)
        session.toggleReview(1)
        assertFalse(session.isMarkedForReview(1))
    }

    @Test
    fun `marking for review does not affect answer state`() {
        session.answerQuestion(0, 2)
        session.toggleReview(0)
        assertTrue(session.isAnswered(0))
        assertTrue(session.isMarkedForReview(0))
    }

    @Test
    fun `can mark multiple questions for review`() {
        session.toggleReview(0)
        session.toggleReview(2)
        session.toggleReview(4)
        assertTrue(session.isMarkedForReview(0))
        assertFalse(session.isMarkedForReview(1))
        assertTrue(session.isMarkedForReview(2))
        assertFalse(session.isMarkedForReview(3))
        assertTrue(session.isMarkedForReview(4))
    }

    // ─── Elapsed time ────────────────────────────────────────────────────────

    @Test
    fun `elapsedSeconds is non-negative`() {
        assertTrue(session.elapsedSeconds() >= 0)
    }
}
