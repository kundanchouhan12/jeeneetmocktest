package com.jeeneet.mocktest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import com.jeeneet.mocktest.data.repository.AiCounselorRepository
import com.jeeneet.mocktest.utils.PrefManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VaultCommunityAndAiTest {

    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAnonymousUsernameGeneration() {
        val uid = "user_abc_1234"
        val anonymousName = "Aspirant_" + uid.takeLast(4).uppercase()
        assertEquals("Aspirant_1234", anonymousName)
    }

    @Test
    fun testCommentCooldownLogic() {
        assertTrue(PrefManager.canPostComment(context))
        PrefManager.updateLastCommentTimestamp(context)
        assertFalse(PrefManager.canPostComment(context))
        
        val prefs = context.getSharedPreferences("mocktest_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_comment_at_guest", System.currentTimeMillis() - 16_000).apply()
        assertTrue(PrefManager.canPostComment(context))
    }

    @Test
    fun testArchiveLogic48Hours() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val fortyNineHoursAgo = System.currentTimeMillis() - (49 * 3600 * 1000L)
        val oldDateStr = sdf.format(java.util.Date(fortyNineHoursAgo))
        
        val date = sdf.parse(oldDateStr)!!
        val diff = System.currentTimeMillis() - date.time
        assertTrue(diff > 48 * 3600 * 1000L)
    }

    @Test
    fun testReadinessScoreCalculation() {
        val repository = AiCounselorRepository(context)
        
        val resultsHigh = listOf(
            TestResult(
                id = 0, userId = "", examType = "JEE", subject = "Physics", 
                totalQuestions = 10, attempted = 10, correct = 9, wrong = 1,
                score = 90f, maxScore = 100f, percentile = 90f, timeTakenSeconds = 600, 
                completedAt = System.currentTimeMillis()
            )
        )
        val scoreHigh = repository.calculateReadinessScore(resultsHigh)
        assertTrue(scoreHigh > 50)
    }

    @Test
    fun testWeakChapterDetection() {
        val q1 = Question(id = 0, examType = "JEE", subject = "Physics", chapter = "Kinematics", difficulty = "Easy", year = 2024, questionText = "Q1", options = listOf("A", "B", "C", "D"), correctOptionIndex = 1, explanation = "")
        val q2 = Question(id = 0, examType = "JEE", subject = "Physics", chapter = "Kinematics", difficulty = "Easy", year = 2024, questionText = "Q2", options = listOf("A", "B", "C", "D"), correctOptionIndex = 1, explanation = "")
        val q3 = Question(id = 0, examType = "JEE", subject = "Physics", chapter = "Kinematics", difficulty = "Easy", year = 2024, questionText = "Q3", options = listOf("A", "B", "C", "D"), correctOptionIndex = 1, explanation = "")
        
        val results = listOf(
            TestResult(
                id = 0, userId = "", examType = "JEE", subject = "Physics", 
                totalQuestions = 3, attempted = 3, correct = 0, wrong = 3,
                score = 0f, maxScore = 12f, percentile = 0f, timeTakenSeconds = 180,
                questionsJson = gson.toJson(listOf(q1, q2, q3)),
                answersJson = gson.toJson(mapOf("0" to 0, "1" to 0, "2" to 0)),
                completedAt = System.currentTimeMillis()
            )
        )
        
        val chapterStats = mutableMapOf<String, Pair<Int, Int>>()
        val typeQ = object : TypeToken<List<Question>>() {}.type
        val typeA = object : TypeToken<Map<String, Int?>>() {}.type

        results.forEach { r ->
            val qs: List<Question> = gson.fromJson(r.questionsJson, typeQ)
            val ans: Map<String, Int?> = gson.fromJson(r.answersJson, typeA)
            qs.forEachIndexed { idx, q ->
                val key = "${q.subject}: ${q.chapter}"
                val current = chapterStats[key] ?: Pair(0, 0)
                val isCorrect = ans[idx.toString()] == q.correctOptionIndex
                chapterStats[key] = Pair(current.first + (if (isCorrect) 1 else 0), current.second + 1)
            }
        }
        
        val weak = chapterStats.entries.filter { it.value.second >= 3 && (it.value.first.toFloat() / it.value.second) < 0.6f }
        assertEquals(1, weak.size)
        assertEquals("Physics: Kinematics", weak[0].key)
    }
}
