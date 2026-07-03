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
class PrefManagerTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // Clear all prefs before each test for isolation
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ─── First Launch ────────────────────────────────────────────────────────

    @Test
    fun `isFirstLaunch returns true by default`() {
        assertTrue(PrefManager.isFirstLaunch(ctx))
    }

    @Test
    fun `setFirstLaunchDone makes isFirstLaunch return false`() {
        PrefManager.setFirstLaunchDone(ctx)
        assertFalse(PrefManager.isFirstLaunch(ctx))
    }

    // ─── Ads Removed ─────────────────────────────────────────────────────────

    @Test
    fun `isAdsRemoved returns false by default`() {
        assertFalse(PrefManager.isAdsRemoved(ctx))
    }

    @Test
    fun `setAdsRemoved makes isAdsRemoved return true`() {
        PrefManager.setAdsRemoved(ctx)
        assertTrue(PrefManager.isAdsRemoved(ctx))
    }

    // ─── Pack Unlocking ───────────────────────────────────────────────────────

    @Test
    fun `getUnlockedPacks is empty by default`() {
        assertTrue(PrefManager.getUnlockedPacks(ctx).isEmpty())
    }

    @Test
    fun `unlockPack adds product to unlocked set`() {
        PrefManager.unlockPack(ctx, "jee_physics_pack")
        assertTrue(PrefManager.getUnlockedPacks(ctx).contains("jee_physics_pack"))
    }

    @Test
    fun `isPackUnlocked returns true for an unlocked pack`() {
        PrefManager.unlockPack(ctx, "jee_physics_pack")
        assertTrue(PrefManager.isPackUnlocked(ctx, "jee_physics_pack"))
    }

    @Test
    fun `isPackUnlocked returns false for a pack not unlocked`() {
        assertFalse(PrefManager.isPackUnlocked(ctx, "jee_physics_pack"))
    }

    @Test
    fun `isPackUnlocked returns true for any pack when all_access is unlocked`() {
        PrefManager.setAllAccessUnlocked(ctx)
        // Even without explicitly unlocking the pack
        assertTrue(PrefManager.isPackUnlocked(ctx, "jee_physics_pack"))
        assertTrue(PrefManager.isPackUnlocked(ctx, "jee_chemistry_pack"))
        assertTrue(PrefManager.isPackUnlocked(ctx, "neet_biology_pack"))
    }

    @Test
    fun `unlocking multiple packs keeps all of them`() {
        PrefManager.unlockPack(ctx, "jee_physics_pack")
        PrefManager.unlockPack(ctx, "jee_chemistry_pack")
        PrefManager.unlockPack(ctx, "jee_maths_pack")
        val packs = PrefManager.getUnlockedPacks(ctx)
        assertTrue(packs.contains("jee_physics_pack"))
        assertTrue(packs.contains("jee_chemistry_pack"))
        assertTrue(packs.contains("jee_maths_pack"))
        assertEquals(3, packs.size)
    }

    // ─── All Access ───────────────────────────────────────────────────────────

    @Test
    fun `isAllAccessUnlocked returns false by default`() {
        assertFalse(PrefManager.isAllAccessUnlocked(ctx))
    }

    @Test
    fun `setAllAccessUnlocked makes isAllAccessUnlocked return true`() {
        PrefManager.setAllAccessUnlocked(ctx)
        assertTrue(PrefManager.isAllAccessUnlocked(ctx))
    }

    // ─── Streak Logic ─────────────────────────────────────────────────────────

    @Test
    fun `getStreak returns 0 by default`() {
        assertEquals(0, PrefManager.getStreak(ctx))
    }

    @Test
    fun `updateStreak on first call sets streak to 1`() {
        PrefManager.updateStreak(ctx)
        assertEquals(1, PrefManager.getStreak(ctx))
    }

    @Test
    fun `updateStreak on same day does not increase streak`() {
        PrefManager.updateStreak(ctx)
        assertEquals(1, PrefManager.getStreak(ctx))
        PrefManager.updateStreak(ctx)
        assertEquals(1, PrefManager.getStreak(ctx))
    }

    @Test
    fun `updateStreak on consecutive day increases streak`() {
        // Simulate yesterday's test by writing last_test_date as yesterday
        val yesterday = System.currentTimeMillis() - 86_400_000L
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("streak_guest", 3)
            .putLong("last_test_date_guest", yesterday)
            .commit()

        PrefManager.updateStreak(ctx)
        assertEquals(4, PrefManager.getStreak(ctx))
    }

    @Test
    fun `updateStreak after missing a day resets streak to 1`() {
        // Simulate 2 days ago
        val twoDaysAgo = System.currentTimeMillis() - 2 * 86_400_000L
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("streak", 5)
            .putLong("last_test_date", twoDaysAgo)
            .commit()

        PrefManager.updateStreak(ctx)
        assertEquals(1, PrefManager.getStreak(ctx))
    }

    // ─── Ad-free Unlock (daily) ───────────────────────────────────────────────

    @Test
    fun `hasUsedAdFreeUnlockToday returns false by default`() {
        assertFalse(PrefManager.hasUsedAdFreeUnlockToday(ctx))
    }

    @Test
    fun `markAdFreeUnlockUsed makes hasUsedAdFreeUnlockToday return true`() {
        PrefManager.markAdFreeUnlockUsed(ctx)
        assertTrue(PrefManager.hasUsedAdFreeUnlockToday(ctx))
    }

    // ─── Scan / Doubt Solver Limits ───────────────────────────────────────────

    @Test
    fun `getScanCountToday returns 0 by default`() {
        assertEquals(0, PrefManager.getScanCountToday(ctx))
    }

    @Test
    fun `incrementScanCount increases count by 1 each call`() {
        PrefManager.incrementScanCount(ctx)
        assertEquals(1, PrefManager.getScanCountToday(ctx))
        PrefManager.incrementScanCount(ctx)
        assertEquals(2, PrefManager.getScanCountToday(ctx))
    }

    @Test
    fun `canScanNow returns true when under free daily limit`() {
        repeat(4) { PrefManager.incrementScanCount(ctx) }
        assertTrue(PrefManager.canScanNow(ctx))
    }

    @Test
    fun `canScanNow returns false after reaching FREE_DAILY_SCANS limit`() {
        repeat(PrefManager.FREE_DAILY_SCANS) { PrefManager.incrementScanCount(ctx) }
        assertFalse(PrefManager.canScanNow(ctx))
    }

    @Test
    fun `canScanNow returns true for all_access user regardless of count`() {
        PrefManager.setAllAccessUnlocked(ctx)
        repeat(100) { PrefManager.incrementScanCount(ctx) }
        assertTrue(PrefManager.canScanNow(ctx))
    }

    @Test
    fun `addScanRefill extends daily scan limit by 5`() {
        // Use up all 5 free scans
        repeat(PrefManager.FREE_DAILY_SCANS) { PrefManager.incrementScanCount(ctx) }
        assertFalse(PrefManager.canScanNow(ctx))

        // Watch an interstitial ad — grants +5 refill
        PrefManager.addScanRefill(ctx)
        assertTrue(PrefManager.canScanNow(ctx))
        assertEquals(5, PrefManager.getRemainingScans(ctx))

        // Use those 5 bonus scans
        repeat(5) { PrefManager.incrementScanCount(ctx) }
        assertFalse(PrefManager.canScanNow(ctx))
        assertEquals(0, PrefManager.getRemainingScans(ctx))
    }

    @Test
    fun `updateScanData correctly restores state from cloud`() {
        val today = System.currentTimeMillis() / 86_400_000
        PrefManager.updateScanData(ctx, used = 3, refills = 1, day = today)
        
        // Total = 5 (free) + 5 (1 refill) = 10
        // Used = 3
        // Remaining = 7
        assertEquals(7, PrefManager.getRemainingScans(ctx))
        assertTrue(PrefManager.canScanNow(ctx))
    }

    @Test
    fun `getRemainingScans returns FREE_DAILY_SCANS when no scans used`() {
        assertEquals(PrefManager.FREE_DAILY_SCANS, PrefManager.getRemainingScans(ctx))
    }

    @Test
    fun `getRemainingScans decrements with each scan`() {
        PrefManager.incrementScanCount(ctx)
        PrefManager.incrementScanCount(ctx)
        assertEquals(PrefManager.FREE_DAILY_SCANS - 2, PrefManager.getRemainingScans(ctx))
    }

    @Test
    fun `getRemainingScans never goes below 0`() {
        repeat(PrefManager.FREE_DAILY_SCANS + 5) { PrefManager.incrementScanCount(ctx) }
        assertEquals(0, PrefManager.getRemainingScans(ctx))
    }

    @Test
    fun `getRemainingScans returns MAX_VALUE for all_access user`() {
        PrefManager.setAllAccessUnlocked(ctx)
        assertEquals(Int.MAX_VALUE, PrefManager.getRemainingScans(ctx))
    }

    // ─── Dark Mode ────────────────────────────────────────────────────────────

    @Test
    fun `getDarkModePreference returns 2 (dark) by default`() {
        assertEquals(2, PrefManager.getDarkModePreference(ctx))
    }

    @Test
    fun `setDarkModePreference to 2 makes isDarkMode return true`() {
        PrefManager.setDarkModePreference(ctx, 2)
        assertTrue(PrefManager.isDarkMode(ctx))
    }

    @Test
    fun `setDarkModePreference to 1 makes isDarkMode return false`() {
        PrefManager.setDarkModePreference(ctx, 1)
        assertFalse(PrefManager.isDarkMode(ctx))
    }

    // ─── Saved Session ────────────────────────────────────────────────────────

    @Test
    fun `hasSavedTestSession returns false by default`() {
        assertFalse(PrefManager.hasSavedTestSession(ctx))
    }

    @Test
    fun `saveTestSessionJson makes hasSavedTestSession return true`() {
        PrefManager.saveTestSessionJson(ctx, """{"test":"data"}""")
        assertTrue(PrefManager.hasSavedTestSession(ctx))
    }

    @Test
    fun `clearSavedTestSession removes saved session`() {
        PrefManager.saveTestSessionJson(ctx, """{"test":"data"}""")
        PrefManager.clearSavedTestSession(ctx)
        assertFalse(PrefManager.hasSavedTestSession(ctx))
        assertNull(PrefManager.getSavedTestSessionJson(ctx))
    }

    // ─── Exam Selection ───────────────────────────────────────────────────────

    @Test
    fun `getSelectedExam returns JEE by default`() {
        assertEquals("JEE", PrefManager.getSelectedExam(ctx))
    }

    @Test
    fun `setSelectedExam persists the exam choice`() {
        PrefManager.setSelectedExam(ctx, "NEET")
        assertEquals("NEET", PrefManager.getSelectedExam(ctx))
    }

    // ─── Weekly Test Tracking ──────────────────────────────────────────────────

    @Test
    fun `getLastSeenWeeklyTestWeek returns -1 by default`() {
        assertEquals(-1, PrefManager.getLastSeenWeeklyTestWeek(ctx))
    }

    @Test
    fun `setLastSeenWeeklyTestWeek persists the week number`() {
        PrefManager.setLastSeenWeeklyTestWeek(ctx, 19)
        assertEquals(19, PrefManager.getLastSeenWeeklyTestWeek(ctx))
    }

    // ─── Phase 3: Monetization & Quotas ───────────────────────────────────────

    @Test
    fun `canWatchAdForExtraMock respects daily limit of 2`() {
        assertTrue(PrefManager.canWatchAdForExtraMock(ctx))
        PrefManager.incrementAdUsageCount(ctx, "extra_mock")
        assertTrue(PrefManager.canWatchAdForExtraMock(ctx))
        PrefManager.incrementAdUsageCount(ctx, "extra_mock")
        assertFalse(PrefManager.canWatchAdForExtraMock(ctx))
    }

    @Test
    fun `canWatchAdForExtraQuestions respects daily limit of 3`() {
        assertTrue(PrefManager.canWatchAdForExtraQuestions(ctx))
        PrefManager.incrementAdUsageCount(ctx, "extra_questions")
        PrefManager.incrementAdUsageCount(ctx, "extra_questions")
        assertTrue(PrefManager.canWatchAdForExtraQuestions(ctx))
        PrefManager.incrementAdUsageCount(ctx, "extra_questions")
        assertFalse(PrefManager.canWatchAdForExtraQuestions(ctx))
    }

    @Test
    fun `getTotalQuestionsSolvedToday tracks count correctly`() {
        assertEquals(0, PrefManager.getTotalQuestionsSolvedToday(ctx))
        PrefManager.incrementTotalQuestionsSolved(ctx, 15)
        assertEquals(15, PrefManager.getTotalQuestionsSolvedToday(ctx))
        PrefManager.incrementTotalQuestionsSolved(ctx, 10)
        assertEquals(25, PrefManager.getTotalQuestionsSolvedToday(ctx))
    }

    @Test
    fun `isStreakBroken returns true if user missed a full day`() {
        // Last update today -> not broken
        PrefManager.updateStreak(ctx)
        assertFalse(PrefManager.isStreakBroken(ctx))

        // Last update 2 days ago -> broken
        val twoDaysAgo = System.currentTimeMillis() - 2 * 86_400_000L
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("streak_last_update_guest", twoDaysAgo)
            .commit()
        
        assertTrue(PrefManager.isStreakBroken(ctx))
    }
}
