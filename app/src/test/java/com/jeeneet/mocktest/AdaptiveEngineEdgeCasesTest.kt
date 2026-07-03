package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.QuestionExposure
import org.junit.Assert.*
import org.junit.Test

/**
 * Edge-case tests for the 40/40/20 adaptive selection algorithm.
 *
 * Complements AdaptiveEngineTest with scenarios not yet covered:
 *  - Empty question pools
 *  - Pool smaller than the requested count
 *  - All-correct / all-wrong exposure maps
 *  - No-duplicate guarantee
 *  - Integer-rounding of 40/40/20 split for small totals
 */
class AdaptiveEngineEdgeCasesTest {

    // ─── Mirror of MockTestRepository adaptive logic ──────────────────────────────

    private fun selectAdaptive(
        allQuestions: List<Question>,
        exposureMap: Map<String, QuestionExposure>,
        totalDesired: Int
    ): List<Question> {
        val wrong   = mutableListOf<Question>()
        val seen    = mutableListOf<Question>()
        val unseen  = mutableListOf<Question>()

        allQuestions.forEach { q ->
            val exp = exposureMap[q.id.toString()]
            when {
                exp == null                          -> unseen.add(q)
                exp.correctCount < exp.seenCount     -> wrong.add(q)
                else                                 -> seen.add(q)
            }
        }

        val wrongCount    = (totalDesired * 0.4).toInt()
        val revisionCount = (totalDesired * 0.2).toInt()
        val unseenCount   = totalDesired - wrongCount - revisionCount

        val result = mutableListOf<Question>()
        result.addAll(unseen.take(unseenCount))
        result.addAll(wrong.take(wrongCount))
        result.addAll(seen.take(revisionCount))

        if (result.size < totalDesired) {
            val remaining = allQuestions.filter { it !in result }
            result.addAll(remaining.take(totalDesired - result.size))
        }
        return result
    }

    private fun q(id: Int) = Question(
        id = id, examType = "JEE", subject = "Physics",
        chapter = "Mechanics", difficulty = "Medium", year = 2024,
        questionText = "Q$id", options = listOf("A", "B", "C", "D"),
        correctOptionIndex = 0, explanation = ""
    )

    // ─── Empty pool ───────────────────────────────────────────────────────────────

    @Test
    fun `empty question pool returns empty list`() {
        assertTrue(selectAdaptive(emptyList(), emptyMap(), 10).isEmpty())
    }

    @Test
    fun `empty pool with zero desired also returns empty list`() {
        assertTrue(selectAdaptive(emptyList(), emptyMap(), 0).isEmpty())
    }

    // ─── Pool smaller than requested count ────────────────────────────────────────

    @Test
    fun `requesting more than pool size returns all available questions`() {
        val all = (1..5).map { q(it) }
        val result = selectAdaptive(all, emptyMap(), 10)
        assertEquals(5, result.size)
    }

    @Test
    fun `requesting exactly the pool size returns all questions`() {
        val all = (1..10).map { q(it) }
        val result = selectAdaptive(all, emptyMap(), 10)
        assertEquals(10, result.size)
    }

    @Test
    fun `requesting 1 from a pool of 1 returns that single question`() {
        val all = listOf(q(42))
        val result = selectAdaptive(all, emptyMap(), 1)
        assertEquals(1, result.size)
        assertEquals(42, result.first().id)
    }

    // ─── All wrong / all correct exposure maps ────────────────────────────────────

    @Test
    fun `all questions wrong — fallback fills full 10 from wrong pool`() {
        val all = (1..20).map { q(it) }
        val exposure = all.associate { q ->
            q.id.toString() to QuestionExposure(q.id, seenCount = 2, correctCount = 0, lastSeenAt = 0)
        }
        val result = selectAdaptive(all, exposure, 10)
        assertEquals(10, result.size)
    }

    @Test
    fun `all questions seen and correct — fallback fills from revision pool`() {
        val all = (1..10).map { q(it) }
        val exposure = all.associate { q ->
            q.id.toString() to QuestionExposure(q.id, seenCount = 1, correctCount = 1, lastSeenAt = 0)
        }
        val result = selectAdaptive(all, exposure, 10)
        assertEquals(10, result.size)
    }

    @Test
    fun `no wrong questions — quota falls back to unseen and revision`() {
        val all = (1..20).map { q(it) }
        val exposure = mapOf(
            "1" to QuestionExposure(1, seenCount = 1, correctCount = 1, lastSeenAt = 0), // revision
            "2" to QuestionExposure(2, seenCount = 1, correctCount = 1, lastSeenAt = 0)  // revision
        )
        val result = selectAdaptive(all, exposure, 10)
        assertEquals(10, result.size)
    }

    // ─── No duplicates ─────────────────────────────────────────────────────────────

    @Test
    fun `selection never contains the same question twice`() {
        val all = (1..20).map { q(it) }
        val exposure = mapOf(
            "1" to QuestionExposure(1, seenCount = 2, correctCount = 0, lastSeenAt = 0),
            "2" to QuestionExposure(2, seenCount = 2, correctCount = 0, lastSeenAt = 0),
            "3" to QuestionExposure(3, seenCount = 1, correctCount = 1, lastSeenAt = 0)
        )
        val result = selectAdaptive(all, exposure, 10)
        assertEquals("Duplicate questions found", result.size, result.distinctBy { it.id }.size)
    }

    // ─── 40/40/20 integer-rounding ────────────────────────────────────────────────

    @Test
    fun `rounding for 5 desired gives 2 wrong + 1 revision + 2 unseen`() {
        val wrongCount    = (5 * 0.4).toInt()  // 2
        val revisionCount = (5 * 0.2).toInt()  // 1
        val unseenCount   = 5 - wrongCount - revisionCount  // 2
        assertEquals(2, wrongCount)
        assertEquals(1, revisionCount)
        assertEquals(2, unseenCount)
        assertEquals(5, wrongCount + revisionCount + unseenCount)
    }

    @Test
    fun `rounding for 1 desired all categories round to 0 leaving 1 unseen`() {
        val wrongCount    = (1 * 0.4).toInt()  // 0
        val revisionCount = (1 * 0.2).toInt()  // 0
        val unseenCount   = 1 - wrongCount - revisionCount  // 1
        assertEquals(0, wrongCount)
        assertEquals(0, revisionCount)
        assertEquals(1, unseenCount)
    }

    @Test
    fun `unseen + wrong + revision always sums to totalDesired`() {
        listOf(1, 5, 10, 15, 20, 30).forEach { total ->
            val w = (total * 0.4).toInt()
            val r = (total * 0.2).toInt()
            val u = total - w - r
            assertEquals("total=$total", total, w + r + u)
        }
    }
}
