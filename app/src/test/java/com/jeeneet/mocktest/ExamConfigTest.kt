package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.ExamConfig
import org.junit.Assert.*
import org.junit.Test

class ExamConfigTest {

    // ─── JEE Mains Full ──────────────────────────────────────────────────────

    @Test
    fun `jeeMainsFull has correct question count`() {
        assertEquals(90, ExamConfig.jeeMainsFull().totalQuestions)
    }

    @Test
    fun `jeeMainsFull has 180 minute duration`() {
        assertEquals(180, ExamConfig.jeeMainsFull().durationMinutes)
    }

    @Test
    fun `jeeMainsFull has +4 correct marks`() {
        assertEquals(4f, ExamConfig.jeeMainsFull().correctMarks)
    }

    @Test
    fun `jeeMainsFull has -1 negative marks`() {
        assertEquals(-1f, ExamConfig.jeeMainsFull().negativeMarks)
    }

    @Test
    fun `jeeMainsFull has no subject or chapter filter`() {
        val config = ExamConfig.jeeMainsFull()
        assertNull(config.subject)
        assertNull(config.chapter)
    }

    @Test
    fun `jeeMainsFull has JEE exam type`() {
        assertEquals("JEE", ExamConfig.jeeMainsFull().examType)
    }

    // ─── NEET Full ────────────────────────────────────────────────────────────

    @Test
    fun `neetFull has correct question count`() {
        assertEquals(200, ExamConfig.neetFull().totalQuestions)
    }

    @Test
    fun `neetFull has 200 minute duration`() {
        assertEquals(200, ExamConfig.neetFull().durationMinutes)
    }

    @Test
    fun `neetFull has +4 correct marks`() {
        assertEquals(4f, ExamConfig.neetFull().correctMarks)
    }

    @Test
    fun `neetFull has -1 negative marks`() {
        assertEquals(-1f, ExamConfig.neetFull().negativeMarks)
    }

    @Test
    fun `neetFull has NEET exam type`() {
        assertEquals("NEET", ExamConfig.neetFull().examType)
    }

    // ─── Chapter Wise ─────────────────────────────────────────────────────────

    @Test
    fun `chapterWise has 30 questions`() {
        val config = ExamConfig.chapterWise("JEE", "Physics", "Kinematics")
        assertEquals(30, config.totalQuestions)
    }

    @Test
    fun `chapterWise has 40 minute duration`() {
        val config = ExamConfig.chapterWise("JEE", "Physics", "Kinematics")
        assertEquals(40, config.durationMinutes)
    }

    @Test
    fun `chapterWise stores subject and chapter correctly`() {
        val config = ExamConfig.chapterWise("NEET", "Biology", "Genetics")
        assertEquals("NEET", config.examType)
        assertEquals("Biology", config.subject)
        assertEquals("Genetics", config.chapter)
    }

    @Test
    fun `chapterWise has +4 correct marks`() {
        assertEquals(4f, ExamConfig.chapterWise("JEE", "Maths", "Calculus").correctMarks)
    }

    @Test
    fun `chapterWise has -1 negative marks`() {
        assertEquals(-1f, ExamConfig.chapterWise("JEE", "Maths", "Calculus").negativeMarks)
    }

    // ─── Daily Quiz ───────────────────────────────────────────────────────────

    @Test
    fun `dailyQuiz has 10 questions`() {
        assertEquals(10, ExamConfig.dailyQuiz("JEE").totalQuestions)
    }

    @Test
    fun `dailyQuiz has 5 minute duration`() {
        assertEquals(5, ExamConfig.dailyQuiz("JEE").durationMinutes)
    }

    @Test
    fun `dailyQuiz for JEE sets exam type correctly`() {
        assertEquals("JEE", ExamConfig.dailyQuiz("JEE").examType)
    }

    @Test
    fun `dailyQuiz for NEET sets exam type correctly`() {
        assertEquals("NEET", ExamConfig.dailyQuiz("NEET").examType)
    }

    @Test
    fun `dailyQuiz has no subject or chapter filter`() {
        val config = ExamConfig.dailyQuiz("JEE")
        assertNull(config.subject)
        assertNull(config.chapter)
    }
}
