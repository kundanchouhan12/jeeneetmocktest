package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.SubjectPerformance
import org.junit.Assert.*
import org.junit.Test

class SubjectPerformanceTest {

    // ─── accuracy ────────────────────────────────────────────────────────────

    @Test
    fun `accuracy is 1_0 when all answers are correct`() {
        val p = SubjectPerformance("Physics", "Kinematics", correct = 10, wrong = 0, unattempted = 0)
        assertEquals(1.0f, p.accuracy, 0.001f)
    }

    @Test
    fun `accuracy is 0_0 when all answers are wrong`() {
        val p = SubjectPerformance("Physics", "Kinematics", correct = 0, wrong = 10, unattempted = 0)
        assertEquals(0.0f, p.accuracy, 0.001f)
    }

    @Test
    fun `accuracy is 0_0 when no attempts at all`() {
        val p = SubjectPerformance("Maths", "Calculus", correct = 0, wrong = 0, unattempted = 5)
        assertEquals(0.0f, p.accuracy, 0.001f)
    }

    @Test
    fun `accuracy is 0_5 for equal correct and wrong`() {
        val p = SubjectPerformance("Chemistry", "Organic", correct = 5, wrong = 5, unattempted = 0)
        assertEquals(0.5f, p.accuracy, 0.001f)
    }

    @Test
    fun `accuracy is 0_75 for 3 correct and 1 wrong`() {
        val p = SubjectPerformance("Biology", "Genetics", correct = 3, wrong = 1, unattempted = 2)
        assertEquals(0.75f, p.accuracy, 0.001f)
    }

    @Test
    fun `accuracy is not affected by unattempted count`() {
        val p1 = SubjectPerformance("Physics", "Optics", correct = 4, wrong = 1, unattempted = 0)
        val p2 = SubjectPerformance("Physics", "Optics", correct = 4, wrong = 1, unattempted = 10)
        assertEquals(p1.accuracy, p2.accuracy, 0.001f)
    }

    // ─── totalAttempted ──────────────────────────────────────────────────────

    @Test
    fun `totalAttempted is correct + wrong`() {
        val p = SubjectPerformance("Maths", "Algebra", correct = 7, wrong = 3, unattempted = 10)
        assertEquals(10, p.totalAttempted)
    }

    @Test
    fun `totalAttempted is 0 when nothing answered`() {
        val p = SubjectPerformance("Physics", "Waves", correct = 0, wrong = 0, unattempted = 15)
        assertEquals(0, p.totalAttempted)
    }

    @Test
    fun `totalAttempted does not include unattempted`() {
        val p = SubjectPerformance("Chemistry", "Inorganic", correct = 5, wrong = 2, unattempted = 8)
        assertEquals(7, p.totalAttempted)
    }
}
