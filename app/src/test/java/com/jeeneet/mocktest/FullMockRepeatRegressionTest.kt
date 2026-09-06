package com.jeeneet.mocktest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.data.model.ExamConfig
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.data.repository.MockTestRepository
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the "same question repeated again and again" user complaint,
 * for full-length mock tests (subject == null && chapter == null) — both the free-tier
 * weekly-seed branch and the premium weekly-seed branch of getQuestionsForConfig().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FullMockRepeatRegressionTest {

    private lateinit var ctx: Context
    private lateinit var repo: MockTestRepository

    @Before
    fun setUp() {
        MockTestDatabase.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        PrefManager.revokeAllAccess(ctx)
        repo = MockTestRepository(ctx)
    }

    private fun question(exam: String, isPremium: Boolean) = Question(
        examType = exam, subject = listOf("Physics", "Chemistry", "Maths").random(), chapter = "General",
        difficulty = "Medium", year = 0,
        questionText = "Full mock question #${(0..999999).random()}",
        options = listOf("A", "B", "C", "D"), correctOptionIndex = 0,
        explanation = "because", isPremium = isPremium
    )

    private fun weekKey(): String =
        java.text.SimpleDateFormat("YYYY-'W'ww", java.util.Locale.UK).format(java.util.Date())

    private fun clearWeeklyCache(exam: String, tier: String) {
        ctx.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE).edit()
            .remove("weekly_${tier}_mock_${exam}_${weekKey()}")
            .apply()
    }

    @Test
    fun `free full mock does not repeat questions across weeks until pool exhausted`() = runBlocking {
        val exam = "JEE_FM_FREE_${System.currentTimeMillis()}"
        // 80 free questions, 20 per mock — exactly 4 clean weeks before recirculation is expected.
        val seeded = (1..80).map { question(exam, isPremium = false) }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig(
            examType = exam, subject = null, chapter = null,
            totalQuestions = 20, durationMinutes = 60,
            correctMarks = 4f, negativeMarks = -1f
        )
        val seenAcrossWeeks = mutableSetOf<Int>()

        repeat(4) { week ->
            clearWeeklyCache(exam, "free")
            val result = repo.getQuestionsForConfig(ctx, config, adUnlocked = false)
            val ids = result.questions.map { it.id }

            assertEquals("week ${week + 1}: no duplicate within a single mock", ids.size, ids.toSet().size)
            val overlap = ids.filter { it in seenAcrossWeeks }
            assertTrue(
                "week ${week + 1}: repeated ${overlap.size} question(s) from an earlier week " +
                    "even though the 80-question pool wasn't exhausted yet",
                overlap.isEmpty()
            )
            assertTrue("pool isn't exhausted yet, so this week shouldn't be flagged as recycled", !result.recycled)
            seenAcrossWeeks.addAll(ids)
        }

        assertEquals("after 4 weeks of 20, all 80 distinct questions should have been shown once",
            80, seenAcrossWeeks.size)

        clearWeeklyCache(exam, "free")
        val fifth = repo.getQuestionsForConfig(ctx, config, adUnlocked = false)
        assertTrue("once genuinely exhausted, recirculation is expected and should be flagged", fifth.recycled)
    }

    @Test
    fun `premium full mock does not repeat questions across weeks until pool exhausted`() = runBlocking {
        val exam = "JEE_FM_PREM_${System.currentTimeMillis()}"
        val seeded = (1..80).map { question(exam, isPremium = true) }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig(
            examType = exam, subject = null, chapter = null,
            totalQuestions = 20, durationMinutes = 60,
            correctMarks = 4f, negativeMarks = -1f
        )
        val seenAcrossWeeks = mutableSetOf<Int>()

        repeat(4) { week ->
            clearWeeklyCache(exam, "premium")
            val result = repo.getQuestionsForConfig(ctx, config, adUnlocked = true)
            val ids = result.questions.map { it.id }

            assertEquals("week ${week + 1}: no duplicate within a single mock", ids.size, ids.toSet().size)
            val overlap = ids.filter { it in seenAcrossWeeks }
            assertTrue(
                "week ${week + 1}: repeated ${overlap.size} question(s) from an earlier week " +
                    "even though the 80-question pool wasn't exhausted yet",
                overlap.isEmpty()
            )
            assertTrue("pool isn't exhausted yet, so this week shouldn't be flagged as recycled", !result.recycled)
            seenAcrossWeeks.addAll(ids)
        }

        assertEquals(80, seenAcrossWeeks.size)

        clearWeeklyCache(exam, "premium")
        val fifth = repo.getQuestionsForConfig(ctx, config, adUnlocked = true)
        assertTrue("once genuinely exhausted, recirculation is expected and should be flagged", fifth.recycled)
    }

    @Test
    fun `thin full mock pool returns fewer questions instead of duplicating`() = runBlocking {
        val exam = "JEE_FM_THIN_${System.currentTimeMillis()}"
        val seeded = (1..6).map { question(exam, isPremium = false) }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig(
            examType = exam, subject = null, chapter = null,
            totalQuestions = 20, durationMinutes = 60,
            correctMarks = 4f, negativeMarks = -1f
        )
        val result = repo.getQuestionsForConfig(ctx, config, adUnlocked = false)
        val ids = result.questions.map { it.id }

        assertEquals("no question should appear twice", ids.size, ids.toSet().size)
        assertTrue("thin pool should return fewer than requested, not pad with dupes",
            result.questions.size <= 6)
        assertTrue("thin pool should be flagged so the UI can tell the user more is coming", result.recycled)
    }
}
