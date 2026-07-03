package com.jeeneet.mocktest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.utils.PrefManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RetentionLogicTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // Clear prefs for isolation
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `saveLastTestScore persists and retrieves correctly and shifts history`() {
        // First save
        PrefManager.saveLastTestScore(ctx, 70, "JEE", "Physics")
        val info1 = PrefManager.getLastTestScoreInfo(ctx)
        assertEquals(70, info1?.scorePercent)
        assertEquals(-1, PrefManager.getPreviousScorePct(ctx))

        // Second save
        PrefManager.saveLastTestScore(ctx, 85, "JEE", "Physics")
        val info2 = PrefManager.getLastTestScoreInfo(ctx)
        assertEquals(85, info2?.scorePercent)
        assertEquals(70, PrefManager.getPreviousScorePct(ctx))
    }

    @Test
    fun `daysSinceLastPractice returns 0 if practiced today`() {
        // daysSinceLastPractice uses getLastTestDate which reads "last_test_date"
        val today = System.currentTimeMillis()
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("last_test_date_guest", today).commit()

        assertEquals(0, PrefManager.daysSinceLastPractice(ctx))
    }

    @Test
    fun `daysSinceLastPractice returns 1 if practiced yesterday`() {
        // daysSinceLastPractice uses getLastTestDate which reads "last_test_date"
        val yesterday = System.currentTimeMillis() - 86_400_000L
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("last_test_date_guest", yesterday).commit()

        assertEquals(1, PrefManager.daysSinceLastPractice(ctx))
    }

    @Test
    fun `shouldAwardMilestone returns true if streak reached and not awarded`() {
        // Mock streak = 7
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("streak_guest", 7).commit()

        assertTrue(PrefManager.shouldAwardMilestone(ctx, 7))
        assertTrue(PrefManager.shouldAwardMilestone(ctx, 3)) // Also true for lower milestones
    }

    @Test
    fun `awardMilestoneIfEligible grants coins exactly once per streak cycle`() {
        // Set streak to 7
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("streak_guest", 7).commit()

        val coins = PrefManager.awardMilestoneIfEligible(ctx, 7)
        assertEquals(50, coins)
        assertEquals(50, PrefManager.getCoins(ctx))

        // Second attempt should grant 0
        val secondTry = PrefManager.awardMilestoneIfEligible(ctx, 7)
        assertEquals(0, secondTry)
        assertEquals(50, PrefManager.getCoins(ctx))
    }

    @Test
    fun `streak milestones reset when streak is broken`() {
        // 1. Set streak to 3 and award milestone
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("streak_guest", 3).commit()
        PrefManager.awardMilestoneIfEligible(ctx, 3)
        assertFalse(PrefManager.shouldAwardMilestone(ctx, 3))

        // 2. Break streak (simulate missed day)
        // updateStreak resets milestones if break detected
        val twoDaysAgo = System.currentTimeMillis() - 2 * 86_400_000L
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("last_test_date_guest", twoDaysAgo) // streak break check uses last_test_date
            .commit()

        PrefManager.updateStreak(ctx) // This should call resetStreakMilestones internally
        assertEquals(1, PrefManager.getStreak(ctx))

        // 3. Reach streak 3 again — should be eligible for milestone again
        val yesterday = System.currentTimeMillis() - 86_400_000L
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("streak_guest", 2)
            .putLong("last_test_date_guest", yesterday)
            .commit()

        PrefManager.updateStreak(ctx) // Now streak = 3
        assertEquals(3, PrefManager.getStreak(ctx))
        assertTrue("Milestone should be re-awardable after streak reset", PrefManager.shouldAwardMilestone(ctx, 3))
    }
}
