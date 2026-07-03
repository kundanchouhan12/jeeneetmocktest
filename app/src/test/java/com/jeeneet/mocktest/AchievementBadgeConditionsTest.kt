package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.repository.AchievementManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for badge eligibility conditions in AchievementManager.
 *
 * Mirrors the threshold logic from check*() methods (checkStreak,
 * checkDoubtSolved, checkTestResult, checkVaultCompleted) as pure
 * boolean / set functions so no Firebase or Room stack is needed.
 *
 * Also covers the ALL_BADGES catalog for structural correctness.
 */
class AchievementBadgeConditionsTest {

    // ─── ALL_BADGES catalog ───────────────────────────────────────────────────────

    @Test
    fun `ALL_BADGES contains exactly 7 entries`() {
        assertEquals(7, AchievementManager.ALL_BADGES.size)
    }

    @Test
    fun `ALL_BADGES contains all expected badge IDs`() {
        val ids = AchievementManager.ALL_BADGES.map { it.id }.toSet()
        listOf("streak_3", "streak_7", "doubt_buster", "perfect_100",
               "speed_demon", "early_bird", "vault_master")
            .forEach { expectedId ->
                assertTrue("Missing badge: $expectedId", ids.contains(expectedId))
            }
    }

    @Test
    fun `every badge has non-empty title, description, and emoji`() {
        AchievementManager.ALL_BADGES.forEach { badge ->
            assertTrue("Badge '${badge.id}' title is empty",       badge.title.isNotEmpty())
            assertTrue("Badge '${badge.id}' description is empty", badge.description.isNotEmpty())
            assertTrue("Badge '${badge.id}' emoji is empty",       badge.emoji.isNotEmpty())
        }
    }

    @Test
    fun `badge IDs are unique — no duplicates in ALL_BADGES`() {
        val ids = AchievementManager.ALL_BADGES.map { it.id }
        assertEquals("Duplicate badge IDs found", ids.distinct().size, ids.size)
    }

    // ─── Streak badge conditions (mirrors checkStreak) ────────────────────────────

    private fun streakBadges(streak: Int): Set<String> = buildSet {
        if (streak >= 3) add("streak_3")
        if (streak >= 7) add("streak_7")
    }

    @Test
    fun `streak below 3 earns no streak badges`() {
        assertTrue(streakBadges(0).isEmpty())
        assertTrue(streakBadges(1).isEmpty())
        assertTrue(streakBadges(2).isEmpty())
    }

    @Test
    fun `streak of exactly 3 earns streak_3 only`() {
        assertEquals(setOf("streak_3"), streakBadges(3))
    }

    @Test
    fun `streak between 4 and 6 earns streak_3 only`() {
        (4..6).forEach { streak ->
            assertEquals("streak=$streak", setOf("streak_3"), streakBadges(streak))
        }
    }

    @Test
    fun `streak of exactly 7 earns both streak_3 and streak_7`() {
        assertEquals(setOf("streak_3", "streak_7"), streakBadges(7))
    }

    @Test
    fun `streak above 7 always earns both streak badges`() {
        listOf(8, 10, 30, 100).forEach { streak ->
            assertEquals("streak=$streak", setOf("streak_3", "streak_7"), streakBadges(streak))
        }
    }

    // ─── Doubt Buster badge conditions (mirrors checkDoubtSolved) ────────────────

    private fun shouldAwardDoubtBuster(count: Int) = count >= 10

    @Test
    fun `doubt_buster not earned with fewer than 10 doubts solved`() {
        (0..9).forEach { assertFalse("count=$it", shouldAwardDoubtBuster(it)) }
    }

    @Test
    fun `doubt_buster earned at exactly 10 doubts solved`() {
        assertTrue(shouldAwardDoubtBuster(10))
    }

    @Test
    fun `doubt_buster earned with more than 10 doubts solved`() {
        assertTrue(shouldAwardDoubtBuster(11))
        assertTrue(shouldAwardDoubtBuster(100))
    }

    // ─── Test result badge conditions (mirrors checkTestResult) ──────────────────

    private fun testResultBadges(score: Int, timeTakenSeconds: Long): Set<String> = buildSet {
        if (score == 100) add("perfect_100")
        if (timeTakenSeconds < 120 && score >= 80) add("speed_demon")
    }

    @Test
    fun `perfect_100 requires exactly 100 percent score`() {
        assertTrue(testResultBadges(100, 300).contains("perfect_100"))
    }

    @Test
    fun `perfect_100 not earned at 99 percent`() {
        assertFalse(testResultBadges(99, 300).contains("perfect_100"))
    }

    @Test
    fun `speed_demon requires time under 120s and score at least 80`() {
        assertTrue(testResultBadges(80, 119).contains("speed_demon"))
        assertTrue(testResultBadges(100, 1).contains("speed_demon"))
        assertTrue(testResultBadges(95, 60).contains("speed_demon"))
    }

    @Test
    fun `speed_demon not earned when time is exactly 120s`() {
        assertFalse(testResultBadges(100, 120).contains("speed_demon"))
    }

    @Test
    fun `speed_demon not earned when time exceeds 120s`() {
        assertFalse(testResultBadges(100, 121).contains("speed_demon"))
        assertFalse(testResultBadges(100, 300).contains("speed_demon"))
    }

    @Test
    fun `speed_demon not earned when score is below 80 even with fast time`() {
        assertFalse(testResultBadges(79, 10).contains("speed_demon"))
        assertFalse(testResultBadges(0, 10).contains("speed_demon"))
    }

    @Test
    fun `score 100 with fast time earns both perfect_100 and speed_demon`() {
        val badges = testResultBadges(100, 60)
        assertTrue(badges.contains("perfect_100"))
        assertTrue(badges.contains("speed_demon"))
    }

    @Test
    fun `score 99 with fast time earns speed_demon but not perfect_100`() {
        val badges = testResultBadges(99, 60)
        assertFalse(badges.contains("perfect_100"))
        assertTrue(badges.contains("speed_demon"))
    }

    @Test
    fun `score 100 with slow time earns perfect_100 but not speed_demon`() {
        val badges = testResultBadges(100, 200)
        assertTrue(badges.contains("perfect_100"))
        assertFalse(badges.contains("speed_demon"))
    }

    // ─── Vault Master badge conditions (mirrors checkVaultCompleted) ──────────────

    private fun shouldAwardVaultMaster(attempted: Int, total: Int) =
        attempted == total && total >= 50

    @Test
    fun `vault_master earned when all 50 questions are attempted`() {
        assertTrue(shouldAwardVaultMaster(50, 50))
    }

    @Test
    fun `vault_master earned when all questions above 50 are attempted`() {
        assertTrue(shouldAwardVaultMaster(60, 60))
        assertTrue(shouldAwardVaultMaster(100, 100))
    }

    @Test
    fun `vault_master not earned if not all questions attempted`() {
        assertFalse(shouldAwardVaultMaster(49, 50))
        assertFalse(shouldAwardVaultMaster(0, 50))
        assertFalse(shouldAwardVaultMaster(99, 100))
    }

    @Test
    fun `vault_master not earned if total is below 50 even if all attempted`() {
        assertFalse(shouldAwardVaultMaster(30, 30))
        assertFalse(shouldAwardVaultMaster(49, 49))
    }

    @Test
    fun `vault_master boundary — exactly 49 attempted and total is 49`() {
        assertFalse(shouldAwardVaultMaster(49, 49))
    }
}
