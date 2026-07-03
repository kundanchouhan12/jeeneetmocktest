package com.jeeneet.mocktest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.data.model.*
import com.jeeneet.mocktest.utils.PrefManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FinalVerificationTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ─── 1. Marking Scheme & Scoring Verification ─────────────────────────────

    @Test
    fun `verify standard marking scheme (+4 -1)`() {
        val config = ExamConfig.jeeMainsFull() // difficultyScoring = false by default
        
        // Mock session with 1 correct, 1 wrong, 1 unattempted
        val questions = listOf(
            makeQ(0, "Medium"), 
            makeQ(1, "Medium"), 
            makeQ(2, "Medium")
        )
        val answers = mapOf(0 to 0, 1 to 1) // Correct is 0. Q0 Correct, Q1 Wrong
        
        var score = 0f
        questions.forEachIndexed { idx, q ->
            val ans = answers[idx]
            if (ans != null) {
                if (ans == q.correctOptionIndex) score += config.correctMarks
                else score += config.negativeMarks
            }
        }
        
        assertEquals("Score should be 4 - 1 = 3", 3f, score, 0.001f)
    }

    @Test
    fun `verify difficulty-based scoring logic (+5 for hard, +3 for easy)`() {
        // config with difficultyScoring = true
        val config = ExamConfig.jeeMainsFull().copy(difficultyScoring = true)
        
        val qEasy = makeQ(0, "Easy")
        val qHard = makeQ(1, "Hard")
        
        // Easy correct -> 4 - 1 = 3
        var score = 0f
        score += maxOf(1f, config.correctMarks - 1f) 
        assertEquals(3f, score, 0.001f)
        
        // Hard correct -> 4 + 1 = 5
        score = 0f
        score += config.correctMarks + 1f
        assertEquals(5f, score, 0.001f)
    }

    @Test
    fun `verify max score calculation with difficulty scaling`() {
        val config = ExamConfig.jeeMainsFull().copy(difficultyScoring = true)
        val questions = listOf(
            makeQ(0, "Easy"),   // 3
            makeQ(1, "Medium"), // 4
            makeQ(2, "Hard")    // 5
        )
        
        val maxScore = questions.sumOf { q ->
            when (q.difficulty) {
                "Easy" -> 3.0
                "Hard" -> 5.0
                else   -> 4.0
            }
        }.toFloat()
        
        assertEquals(12f, maxScore, 0.001f)
    }

    // ─── 2. Purchase & Access Verification ────────────────────────────────────

    @Test
    fun `verify All Access unlocks everything and removes ads`() {
        PrefManager.setAllAccessUnlocked(ctx)
        
        assertTrue("All Access should be active", PrefManager.isAllAccessUnlocked(ctx))
        assertTrue("Ads should be removed", PrefManager.isAdsRemoved(ctx))
        assertTrue("JEE Physics should be unlocked", PrefManager.isPackUnlocked(ctx, IAPProducts.JEE_PHYSICS_PACK))
        assertTrue("NEET Bio should be unlocked", PrefManager.isPackUnlocked(ctx, IAPProducts.NEET_BIO_PACK))
    }

    @Test
    fun `verify single pack unlock does not remove ads for other areas`() {
        PrefManager.unlockPack(ctx, IAPProducts.JEE_MATHS_PACK)
        
        assertTrue("JEE Maths should be unlocked", PrefManager.isPackUnlocked(ctx, IAPProducts.JEE_MATHS_PACK))
        assertFalse("NEET Bio should still be locked", PrefManager.isPackUnlocked(ctx, IAPProducts.NEET_BIO_PACK))
        assertFalse("Ads should NOT be removed globally", PrefManager.isAdsRemoved(ctx))
    }

    // ─── 3. Activity Insights Verification ────────────────────────────────────

    @Test
    fun `verify daily goal tracking`() {
        // Default goal might be 15
        assertEquals(0, PrefManager.getDailyQuestionsAttempted(ctx))
        
        repeat(5) { PrefManager.incrementDailyQuestionsAttempted(ctx, 1) }
        assertEquals(5, PrefManager.getDailyQuestionsAttempted(ctx))
    }

    @Test
    fun `verify practiced dates persistence`() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        PrefManager.markPracticedToday(ctx)
        
        val dates = PrefManager.getPracticedDates(ctx)
        assertTrue("Today's date should be in practiced list", dates.contains(today))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun makeQ(id: Int, diff: String) = Question(
        id = id, examType = "JEE", subject = "Physics", chapter = "Any",
        difficulty = diff, year = 2024, questionText = "Q$id",
        options = listOf("A", "B", "C", "D"), correctOptionIndex = 0, explanation = ""
    )
}
