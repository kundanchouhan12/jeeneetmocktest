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
 * for the Daily Quiz path (ExamConfig.dailyQuiz()) — mirrors ChapterRepeatRegressionTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DailyQuizRepeatRegressionTest {

    private lateinit var ctx: Context
    private lateinit var repo: MockTestRepository
    private val examType = "JEE_DQ_${System.currentTimeMillis()}" // unique per run — isolates from other tests/data

    @Before
    fun setUp() {
        MockTestDatabase.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        PrefManager.revokeAllAccess(ctx)
        repo = MockTestRepository(ctx)
    }

    private fun freeQuestion(subject: String, chapter: String) = Question(
        examType = examType, subject = subject, chapter = chapter,
        difficulty = "Medium", year = 0,
        questionText = "DQ question #${(0..999999).random()}",
        options = listOf("A", "B", "C", "D"), correctOptionIndex = 0,
        explanation = "because", isPremium = false
    )

    private fun clearTodaysQuizCache() {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        ctx.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE).edit()
            .remove("daily_quiz_${examType}_$today")
            .apply()
    }

    @Test
    fun `daily quiz does not repeat questions across days until the pool is exhausted`() = runBlocking {
        // 40 free questions available, 10 per quiz — exactly 4 clean sessions before recirculation is expected.
        val seeded = (1..40).map { freeQuestion(if (it % 2 == 0) "Physics" else "Chemistry", "General") }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig.dailyQuiz(examType)
        val seenAcrossDays = mutableSetOf<Int>()

        repeat(4) { day ->
            clearTodaysQuizCache()
            val result = repo.getQuestionsForConfig(ctx, config)
            val ids = result.questions.map { it.id }

            assertEquals("day ${day + 1}: no duplicate within a single quiz", ids.size, ids.toSet().size)
            val overlap = ids.filter { it in seenAcrossDays }
            assertTrue(
                "day ${day + 1}: repeated ${overlap.size} question(s) from an earlier day " +
                    "even though the 40-question pool wasn't exhausted yet",
                overlap.isEmpty()
            )
            assertTrue("pool isn't exhausted yet, so this day shouldn't be flagged as recycled", !result.recycled)
            seenAcrossDays.addAll(ids)
        }

        assertEquals("after 4 days of 10, all 40 distinct questions should have been shown once",
            40, seenAcrossDays.size)

        // 5th day: pool is now exhausted — recirculation (and the recycled flag) is expected.
        clearTodaysQuizCache()
        val fifth = repo.getQuestionsForConfig(ctx, config)
        assertTrue("once genuinely exhausted, recirculation is expected and should be flagged", fifth.recycled)
    }

    @Test
    fun `thin daily quiz pool returns fewer questions instead of duplicating`() = runBlocking {
        val seeded = (1..4).map { freeQuestion("Maths", "General") }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig.dailyQuiz(examType) // requests 10
        val result = repo.getQuestionsForConfig(ctx, config)
        val ids = result.questions.map { it.id }

        assertEquals("no question should appear twice", ids.size, ids.toSet().size)
        assertTrue("thin pool should return fewer than requested, not pad with dupes",
            result.questions.size <= 4)
        assertTrue("thin pool should be flagged so the UI can tell the user more is coming", result.recycled)
    }
}
