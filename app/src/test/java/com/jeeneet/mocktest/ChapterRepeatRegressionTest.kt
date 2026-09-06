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
 * specifically for chapter-wise (PYQ-inclusive) tests — the ExamConfig.chapterWise() path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ChapterRepeatRegressionTest {

    private lateinit var ctx: Context
    private lateinit var repo: MockTestRepository
    private val uniqueSuffix = System.currentTimeMillis()

    @Before
    fun setUp() {
        MockTestDatabase.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        PrefManager.revokeAllAccess(ctx)
        repo = MockTestRepository(ctx)
    }

    private fun pyqQuestion(subject: String, chapter: String, year: Int) = Question(
        examType = "JEE", subject = subject, chapter = chapter,
        difficulty = "Medium", year = year,
        questionText = "PYQ $year Q for $chapter #${(0..999999).random()}",
        options = listOf("A", "B", "C", "D"), correctOptionIndex = 0,
        explanation = "because"
    )

    private fun clearDailyCache(subject: String, chapter: String) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        ctx.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE).edit()
            .remove("chapter_daily_JEE_${subject}_${chapter}_$today")
            .apply()
    }

    @Test
    fun `thin PYQ chapter returns each question at most once instead of duplicating`() = runBlocking {
        val chapter = "ThinChapter_$uniqueSuffix"
        val subject = "Physics"
        // Only 12 real PYQs exist for this chapter — fewer than chapterWise()'s target of 30.
        val seeded = (1..12).map { pyqQuestion(subject, chapter, year = 2015 + it % 8) }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig.chapterWise("JEE", subject, chapter)
        val result = repo.getQuestionsForConfig(ctx, config)

        val ids = result.questions.map { it.id }
        assertEquals("no question should appear twice in one test", ids.size, ids.toSet().size)
        assertTrue("thin chapter should return fewer than requested rather than padding with dupes",
            result.questions.size <= 12)
        assertTrue("thin pool should be flagged so the UI can tell the user more is coming",
            result.recycled)
    }

    @Test
    fun `chapter with a healthy pool does not repeat questions across sessions until exhausted`() = runBlocking {
        val chapter = "HealthyChapter_$uniqueSuffix"
        val subject = "Chemistry"
        // 40 distinct PYQs/originals — enough for 4 sessions of 10 with zero overlap.
        val seeded = (1..40).map { pyqQuestion(subject, chapter, year = if (it % 3 == 0) 0 else 2018 + it % 6) }
        MockTestDatabase.getInstance(ctx).questionDao().insertQuestions(seeded)

        val config = ExamConfig(
            examType = "JEE", subject = subject, chapter = chapter,
            totalQuestions = 10, durationMinutes = 15,
            correctMarks = 4f, negativeMarks = -1f
        )

        val seenAcrossSessions = mutableSetOf<Int>()
        repeat(4) { sessionIndex ->
            clearDailyCache(subject, chapter) // simulate opening the chapter test on a new day
            val result = repo.getQuestionsForConfig(ctx, config)

            val ids = result.questions.map { it.id }
            assertEquals("session ${sessionIndex + 1} should have no internal duplicates",
                ids.size, ids.toSet().size)
            val overlapWithPastSessions = ids.filter { it in seenAcrossSessions }
            assertTrue(
                "session ${sessionIndex + 1} repeated ${overlapWithPastSessions.size} question(s) " +
                    "seen in an earlier session even though the 40-question pool wasn't exhausted yet",
                overlapWithPastSessions.isEmpty()
            )
            assertTrue("pool isn't exhausted yet, so this session shouldn't be flagged as recycled",
                !result.recycled)
            seenAcrossSessions.addAll(ids)
        }

        assertEquals("after 4 sessions of 10, all 40 distinct questions should have been shown once",
            40, seenAcrossSessions.size)

        // 5th session: the pool is now fully exhausted, so recirculation (and the "recycled" flag) is expected.
        clearDailyCache(subject, chapter)
        val fifth = repo.getQuestionsForConfig(ctx, config)
        assertTrue("once genuinely exhausted, recirculation is expected and should be flagged to the user",
            fifth.recycled)
    }
}
