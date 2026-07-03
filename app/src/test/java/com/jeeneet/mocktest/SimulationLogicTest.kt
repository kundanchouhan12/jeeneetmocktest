package com.jeeneet.mocktest

import com.google.gson.Gson
import com.jeeneet.mocktest.data.model.*
import org.junit.Assert.*
import org.junit.Test

class SimulationLogicTest {

    private val gson = Gson()

    @Test
    fun `test result correctly marks simulation flag`() {
        val result = TestResult(
            examType = "JEE",
            subject = "Full Paper",
            totalQuestions = 90,
            attempted = 50,
            correct = 40,
            wrong = 10,
            score = 150f,
            maxScore = 360f,
            percentile = 92f,
            timeTakenSeconds = 3600,
            isSimulation = true
        )
        
        assertTrue("Result should be marked as simulation", result.isSimulation)
    }

    @Test
    fun `test silly mistake detection logic`() {
        // Mocking questions
        val questions = listOf(
            Question(id = 1, examType = "JEE", subject = "Physics", chapter = "Kinematics", questionText = "Q1", options = listOf("A", "B", "C", "D"), correctOptionIndex = 0, difficulty = "Easy", year = 2024, explanation = ""),
            Question(id = 2, examType = "JEE", subject = "Physics", chapter = "Kinematics", questionText = "Q2", options = listOf("A", "B", "C", "D"), correctOptionIndex = 0, difficulty = "Easy", year = 2024, explanation = "")
        )
        
        // Mocking user answers: Q1 wrong, Q2 correct
        val answers = mapOf(0 to 1, 1 to 0) // Index 0 (Q1) answered with option 1 (Wrong)
        
        // Mocking time spent: Q1 = 5s (Fast), Q2 = 60s (Normal)
        val times = mapOf("0" to 5000L, "1" to 60000L)
        
        // Emulating ResultActivity logic
        var careless = 0
        questions.forEachIndexed { idx, q ->
            val ans = answers[idx]
            val ms = times[idx.toString()] ?: 0L
            val fast = ms < 10000L
            if (ans != null && ans != q.correctOptionIndex && fast) {
                careless++
            }
        }
        
        assertEquals("Should detect 1 careless/silly mistake", 1, careless)
    }

    @Test
    fun `test weak chapter detection logic`() {
        val questions = listOf(
            Question(id = 1, chapter = "Optics", options = emptyList(), correctOptionIndex = 0, examType = "JEE", questionText = "Q1", subject = "Physics", difficulty = "Easy", year = 2024, explanation = ""),
            Question(id = 2, chapter = "Optics", options = emptyList(), correctOptionIndex = 0, examType = "JEE", questionText = "Q2", subject = "Physics", difficulty = "Easy", year = 2024, explanation = ""),
            Question(id = 3, chapter = "Thermodynamics", options = emptyList(), correctOptionIndex = 0, examType = "JEE", questionText = "Q3", subject = "Physics", difficulty = "Easy", year = 2024, explanation = ""),
            Question(id = 4, chapter = "Thermodynamics", options = emptyList(), correctOptionIndex = 0, examType = "JEE", questionText = "Q4", subject = "Physics", difficulty = "Easy", year = 2024, explanation = "")
        )
        
        // 2 mistakes in Optics, 1 in Thermodynamics
        val answers = mapOf(0 to 1, 1 to 2, 2 to 1, 3 to 0) 
        
        val weakMap = mutableMapOf<String, Int>()
        questions.forEachIndexed { idx, q ->
            if (answers[idx] != null && answers[idx] != q.correctOptionIndex) {
                weakMap[q.chapter] = (weakMap[q.chapter] ?: 0) + 1
            }
        }
        
        val sorted = weakMap.entries.sortedByDescending { it.value }
        
        assertEquals("Top weak chapter should be Optics", "Optics", sorted[0].key)
        assertEquals("Optics should have 2 mistakes", 2, sorted[0].value)
        assertEquals("Second weak chapter should be Thermodynamics", "Thermodynamics", sorted[1].key)
    }

    @Test
    fun `test simulation exam config values`() {
        val jeeMains = ExamConfig.jeeMainsFull()
        assertEquals("JEE Simulation should be 180 mins", 180, jeeMains.durationMinutes)
        assertEquals("JEE Simulation should have 90 questions", 90, jeeMains.totalQuestions)
        assertTrue("JEE Mains Full should be marked as simulation", jeeMains.isSimulation)
        
        val neet = ExamConfig.neetFull()
        assertEquals("NEET Simulation should be 200 mins", 200, neet.durationMinutes)
        assertEquals("NEET Simulation should have 200 questions", 200, neet.totalQuestions)
        assertTrue("NEET Full should be marked as simulation", neet.isSimulation)
        
        val subjectMock = ExamConfig.subjectMock("JEE", "Physics")
        assertFalse("Subject mock should NOT be marked as simulation", subjectMock.isSimulation)
    }
}
