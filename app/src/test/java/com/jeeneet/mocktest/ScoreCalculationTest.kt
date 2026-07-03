package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.ExamConfig
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestSession
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the score + percentile calculation logic implemented in
 * TestViewModel.calculateResult(). The algorithm is:
 *   - Correct answer  → score += correctMarks (4.0)
 *   - Wrong answer    → score += negativeMarks (-1.0)
 *   - Unattempted     → no change
 *   - maxScore        = totalQuestions * correctMarks
 *   - scorePercent    = score / maxScore * 100
 *   - percentile curve (JEE/NEET distribution approximation)
 */
class ScoreCalculationTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun makeQuestion(id: Int, correctIndex: Int = 0) = Question(
        id = id,
        examType = "JEE", subject = "Physics", chapter = "Kinematics",
        difficulty = "Medium", year = 2023,
        questionText = "Q$id",
        options = listOf("A", "B", "C", "D"),
        correctOptionIndex = correctIndex,
        explanation = ""
    )

    /**
     * Mirrors the calculateResult logic from TestViewModel exactly.
     * Returns Triple(score, correct, wrong).
     */
    private fun calcScore(
        session: TestSession,
        config: ExamConfig = ExamConfig.jeeMainsFull()
    ): Triple<Float, Int, Int> {
        var correct = 0; var wrong = 0; var score = 0f
        session.questions.forEachIndexed { idx, q ->
            when (val answer = session.answers[idx]) {
                null -> { /* unattempted */ }
                q.correctOptionIndex -> { correct++; score += config.correctMarks }
                else -> { wrong++; score += config.negativeMarks }
            }
        }
        return Triple(score, correct, wrong)
    }

    /**
     * Mirrors the percentile estimate curve from TestViewModel.
     */
    private fun calcPercentile(score: Float, maxScore: Float): Float {
        val scorePercent = if (maxScore > 0) score / maxScore * 100 else 0f
        return when {
            scorePercent >= 90 -> (95 + (scorePercent - 90) * 0.4f).coerceAtMost(99.9f)
            scorePercent >= 75 -> (85 + (scorePercent - 75) * 0.67f)
            scorePercent >= 50 -> (60 + (scorePercent - 50) * 1.0f)
            scorePercent >= 25 -> (30 + (scorePercent - 25) * 1.2f)
            else               -> (scorePercent * 1.2f).coerceAtLeast(0f)
        }
    }

    private fun sessionOf(vararg pairs: Pair<Int, Int?>): TestSession {
        val questions = (0 until maxOf(pairs.maxOf { it.first } + 1, 5))
            .map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        pairs.forEach { (idx, answer) ->
            if (answer != null) session.answerQuestion(idx, answer)
        }
        return session
    }

    // ─── Score Calculation ────────────────────────────────────────────────────

    @Test
    fun `all correct answers yield maximum score`() {
        val n = 10
        val questions = (0 until n).map { makeQuestion(it, correctIndex = 1) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        (0 until n).forEach { session.answerQuestion(it, 1) }

        val (score, correct, wrong) = calcScore(session)
        assertEquals(n * 4f, score, 0.001f)
        assertEquals(n, correct)
        assertEquals(0, wrong)
    }

    @Test
    fun `all wrong answers yield negative score`() {
        val n = 10
        val questions = (0 until n).map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        // Answer all with option 1 (wrong, correct is 0)
        (0 until n).forEach { session.answerQuestion(it, 1) }

        val (score, correct, wrong) = calcScore(session)
        assertEquals(n * -1f, score, 0.001f)
        assertEquals(0, correct)
        assertEquals(n, wrong)
    }

    @Test
    fun `unattempted questions contribute nothing to score`() {
        val n = 10
        val questions = (0 until n).map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        // Leave all unattempted

        val (score, correct, wrong) = calcScore(session)
        assertEquals(0f, score, 0.001f)
        assertEquals(0, correct)
        assertEquals(0, wrong)
    }

    @Test
    fun `mixed answers 7 correct and 3 wrong yields 25 score`() {
        val questions = (0 until 10).map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        // 7 correct (option 0), 3 wrong (option 1), 0 unattempted
        (0 until 7).forEach { session.answerQuestion(it, 0) }   // correct
        (7 until 10).forEach { session.answerQuestion(it, 1) }  // wrong

        val (score, correct, wrong) = calcScore(session)
        // 7*4 + 3*(-1) = 28 - 3 = 25
        assertEquals(25f, score, 0.001f)
        assertEquals(7, correct)
        assertEquals(3, wrong)
    }

    @Test
    fun `partial attempt some answered and some skipped`() {
        val questions = (0 until 10).map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        // Answer only 5 correctly
        (0 until 5).forEach { session.answerQuestion(it, 0) }

        val (score, correct, wrong) = calcScore(session)
        assertEquals(20f, score, 0.001f)  // 5 * 4
        assertEquals(5, correct)
        assertEquals(0, wrong)
    }

    @Test
    fun `attempted count is correct + wrong only`() {
        val questions = (0 until 10).map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        // 3 correct, 2 wrong, 5 unattempted
        (0 until 3).forEach { session.answerQuestion(it, 0) }
        (3 until 5).forEach { session.answerQuestion(it, 1) }

        val (_, correct, wrong) = calcScore(session)
        val attempted = correct + wrong
        assertEquals(5, attempted)
    }

    @Test
    fun `maxScore equals totalQuestions times correctMarks`() {
        val config = ExamConfig.jeeMainsFull()
        val maxScore = config.totalQuestions * config.correctMarks
        assertEquals(360f, maxScore, 0.001f)  // 90 * 4
    }

    @Test
    fun `NEET maxScore equals 200 times 4`() {
        val config = ExamConfig.neetFull()
        val maxScore = config.totalQuestions * config.correctMarks
        assertEquals(800f, maxScore, 0.001f)
    }

    @Test
    fun `score cannot go below negative total (extreme case)`() {
        val questions = (0 until 90).map { makeQuestion(it, correctIndex = 0) }
        val session = TestSession(ExamConfig.jeeMainsFull(), questions)
        // All wrong
        (0 until 90).forEach { session.answerQuestion(it, 1) }

        val (score, _, _) = calcScore(session)
        assertEquals(-90f, score, 0.001f)
    }

    // ─── Percentile Calculation ───────────────────────────────────────────────

    @Test
    fun `100 percent score yields 99th percentile`() {
        // Formula: 95 + (100 - 90) * 0.4 = 99.0; coerceAtMost(99.9) doesn't fire
        val p = calcPercentile(360f, 360f)
        assertEquals(99.0f, p, 0.001f)
    }

    @Test
    fun `90 percent score yields 95th percentile`() {
        // scorePercent = 90 → 95 + (90-90)*0.4 = 95
        val p = calcPercentile(324f, 360f)
        assertEquals(95f, p, 0.1f)
    }

    @Test
    fun `75 percent score yields 85th percentile`() {
        // scorePercent = 75 → 85 + (75-75)*0.67 = 85
        val p = calcPercentile(270f, 360f)
        assertEquals(85f, p, 0.1f)
    }

    @Test
    fun `50 percent score yields 60th percentile`() {
        // scorePercent = 50 → 60 + (50-50)*1.0 = 60
        val p = calcPercentile(180f, 360f)
        assertEquals(60f, p, 0.1f)
    }

    @Test
    fun `25 percent score yields 30th percentile`() {
        // scorePercent = 25 → 30 + (25-25)*1.2 = 30
        val p = calcPercentile(90f, 360f)
        assertEquals(30f, p, 0.1f)
    }

    @Test
    fun `zero score yields zero percentile`() {
        val p = calcPercentile(0f, 360f)
        assertEquals(0f, p, 0.001f)
    }

    @Test
    fun `negative score yields zero percentile (not negative)`() {
        val p = calcPercentile(-10f, 360f)
        assertTrue("Percentile should be >= 0", p >= 0f)
    }

    @Test
    fun `percentile is monotonically increasing with score`() {
        val maxScore = 360f
        val percentiles = listOf(0f, 50f, 90f, 150f, 200f, 270f, 320f, 360f)
            .map { calcPercentile(it, maxScore) }
        for (i in 1 until percentiles.size) {
            assertTrue(
                "Percentile should increase: ${percentiles[i-1]} < ${percentiles[i]}",
                percentiles[i] >= percentiles[i - 1]
            )
        }
    }

    @Test
    fun `percentile never exceeds 99_9`() {
        val p = calcPercentile(360f, 360f)
        assertTrue("Percentile must not exceed 99.9", p <= 99.9f)
    }

    // ─── IAPProducts constants ────────────────────────────────────────────────

    @Test
    fun `ALL_PACKS contains exactly the 6 subject packs`() {
        val packs = com.jeeneet.mocktest.data.model.IAPProducts.ALL_PACKS
        assertEquals(6, packs.size)
        assertTrue(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.JEE_PHYSICS_PACK))
        assertTrue(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.JEE_CHEM_PACK))
        assertTrue(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.JEE_MATHS_PACK))
        assertTrue(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.NEET_BIO_PACK))
        assertTrue(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.NEET_CHEM_PACK))
        assertTrue(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.NEET_PHYSICS_PACK))
    }

    @Test
    fun `ALL_PACKS does not include ALL_ACCESS_YEARLY or REMOVE_ADS`() {
        val packs = com.jeeneet.mocktest.data.model.IAPProducts.ALL_PACKS
        assertFalse(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.ALL_ACCESS_YEARLY))
        assertFalse(packs.contains(com.jeeneet.mocktest.data.model.IAPProducts.REMOVE_ADS))
    }
}
