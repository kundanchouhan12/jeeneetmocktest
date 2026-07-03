package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.QuestionExposure
import org.junit.Assert.*
import org.junit.Test

/**
 * Recreates the adaptive logic from MockTestRepository to verify the math
 * and fallback behavior of the 40/40/20 algorithm.
 */
class AdaptiveEngineTest {

    private fun simulateAdaptiveSelection(
        allQuestions: List<Question>,
        exposureMap: Map<String, QuestionExposure>,
        totalDesired: Int
    ): List<Question> {
        val wrongIds = mutableListOf<Question>()
        val seenIds = mutableListOf<Question>()
        val unseenIds = mutableListOf<Question>()

        allQuestions.forEach { q ->
            val exp = exposureMap[q.id.toString()]
            when {
                exp == null -> unseenIds.add(q)
                exp.correctCount < exp.seenCount -> wrongIds.add(q)
                else -> seenIds.add(q)
            }
        }

        val wrongCount = (totalDesired * 0.4).toInt()
        val revisionCount = (totalDesired * 0.2).toInt()
        val unseenCount = totalDesired - wrongCount - revisionCount

        val result = mutableListOf<Question>()
        result.addAll(unseenIds.take(unseenCount))
        result.addAll(wrongIds.take(wrongCount))
        result.addAll(seenIds.take(revisionCount))

        // Fill gaps if any category is empty
        if (result.size < totalDesired) {
            val remaining = allQuestions.filter { it !in result }
            result.addAll(remaining.take(totalDesired - result.size))
        }

        return result
    }

    private fun mockQ(id: Int) = Question(
        id = id, 
        examType = "JEE", 
        subject = "Physics", 
        chapter = "Electrostatics", 
        difficulty = "Medium", 
        year = 2024, 
        questionText = "Q$id", 
        options = listOf("A", "B", "C", "D"), 
        correctOptionIndex = 0, 
        explanation = ""
    )

    @Test
    fun `40-40-20 math is correct for 10 questions`() {
        // desired: 4 unseen, 4 wrong, 2 revision
        val all = (1..20).map { mockQ(it) }
        val exposure = mapOf(
            "1" to QuestionExposure(1, 2, 0, 0), // wrong
            "2" to QuestionExposure(2, 2, 0, 0), // wrong
            "3" to QuestionExposure(3, 2, 0, 0), // wrong
            "4" to QuestionExposure(4, 2, 0, 0), // wrong
            "5" to QuestionExposure(5, 1, 1, 0), // revision
            "6" to QuestionExposure(6, 1, 1, 0)  // revision
        )
        // 7-20 are unseen

        val result = simulateAdaptiveSelection(all, exposure, 10)
        
        assertEquals(10, result.size)
        
        val wrongCount = result.count { exposure[it.id.toString()]?.let { e -> e.correctCount < e.seenCount } ?: false }
        val revisionCount = result.count { exposure[it.id.toString()]?.let { e -> e.correctCount == e.seenCount } ?: false }
        val unseenCount = result.count { exposure[it.id.toString()] == null }

        assertEquals(4, wrongCount)
        assertEquals(2, revisionCount)
        assertEquals(4, unseenCount)
    }

    @Test
    fun `fallback logic fills gaps when categories are empty`() {
        // User has only 2 wrong questions, but we want 4 (40% of 10)
        val all = (1..20).map { mockQ(it) }
        val exposure = mapOf(
            "1" to QuestionExposure(1, 2, 0, 0), // wrong
            "2" to QuestionExposure(2, 2, 0, 0)  // wrong
        )
        
        val result = simulateAdaptiveSelection(all, exposure, 10)
        
        assertEquals(10, result.size)
        // Should contain both wrong ones + 8 others
        assertTrue(result.any { it.id == 1 })
        assertTrue(result.any { it.id == 2 })
    }
}
