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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tests for the Daily Vault 7-day freeze logic.
 *
 * Covers:
 *  1. The `today < nextRefreshDate` string comparison used in
 *     QuestionSyncManager.syncDailyVault() to decide whether to skip
 *     the Firestore call.
 *  2. PrefManager vault TTL state (groupId, nextRefreshDate) persistence
 *     and per-exam isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VaultSyncLogicTest {

    private lateinit var ctx: android.content.Context
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ─── Vault freeze logic — mirrors `today < nextRefreshDate` in syncDailyVault ─

    private fun isVaultFrozen(
        today: String,
        nextRefreshDate: String,
        groupId: String,
        localCount: Int
    ) = groupId.isNotEmpty() && nextRefreshDate.isNotEmpty() &&
        today < nextRefreshDate && localCount > 0

    @Test
    fun `vault is frozen when today is before next refresh date`() {
        assertTrue(isVaultFrozen("2026-06-07", "2026-06-14", "group_1", 30))
    }

    @Test
    fun `vault is NOT frozen on the refresh date itself — triggers re-sync`() {
        // today < nextRefreshDate is false when both are equal
        assertFalse(isVaultFrozen("2026-06-14", "2026-06-14", "group_1", 30))
    }

    @Test
    fun `vault is NOT frozen after the refresh date has passed`() {
        assertFalse(isVaultFrozen("2026-06-15", "2026-06-14", "group_1", 30))
    }

    @Test
    fun `vault is NOT frozen on first launch when group id is empty`() {
        assertFalse(isVaultFrozen("2026-06-07", "2026-06-14", "", 0))
    }

    @Test
    fun `vault is NOT frozen when refresh date is empty even with valid group`() {
        assertFalse(isVaultFrozen("2026-06-07", "", "group_1", 30))
    }

    @Test
    fun `vault is NOT frozen when local question count is zero despite valid dates`() {
        assertFalse(isVaultFrozen("2026-06-07", "2026-06-14", "group_1", 0))
    }

    // ─── yyyy-MM-dd string comparison is safe as a date proxy ───────────────────

    @Test
    fun `date strings compare correctly within the same month`() {
        assertTrue("2026-06-07" < "2026-06-14")
        assertFalse("2026-06-14" < "2026-06-07")
    }

    @Test
    fun `date strings compare correctly across months`() {
        assertTrue("2026-06-30" < "2026-07-01")
    }

    @Test
    fun `date strings compare correctly across years`() {
        assertTrue("2026-12-31" < "2027-01-01")
    }

    @Test
    fun `next refresh date is 7 days after today in string order`() {
        val today = Calendar.getInstance()
        val todayStr = sdf.format(today.time)
        val next = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, 7) }
        val nextStr = sdf.format(next.time)
        assertTrue("nextRefresh '$nextStr' must be > today '$todayStr'", nextStr > todayStr)
    }

    // ─── PrefManager vault state round-trips ─────────────────────────────────────

    @Test
    fun `getVaultNextRefreshDate returns empty string by default`() {
        assertEquals("", PrefManager.getVaultNextRefreshDate(ctx, "JEE"))
    }

    @Test
    fun `setVaultNextRefreshDate persists and is readable`() {
        PrefManager.setVaultNextRefreshDate(ctx, "JEE", "2026-06-14")
        assertEquals("2026-06-14", PrefManager.getVaultNextRefreshDate(ctx, "JEE"))
    }

    @Test
    fun `getVaultCurrentGroupId returns empty string by default`() {
        assertEquals("", PrefManager.getVaultCurrentGroupId(ctx, "JEE"))
    }

    @Test
    fun `setVaultCurrentGroupId persists and is readable`() {
        PrefManager.setVaultCurrentGroupId(ctx, "JEE", "group_abc_123")
        assertEquals("group_abc_123", PrefManager.getVaultCurrentGroupId(ctx, "JEE"))
    }

    @Test
    fun `vault state is per-exam — JEE and NEET do not share group or refresh date`() {
        PrefManager.setVaultCurrentGroupId(ctx, "JEE", "jee_group")
        PrefManager.setVaultNextRefreshDate(ctx, "JEE", "2026-06-14")
        PrefManager.setVaultCurrentGroupId(ctx, "NEET", "neet_group")
        PrefManager.setVaultNextRefreshDate(ctx, "NEET", "2026-06-21")

        assertEquals("jee_group",  PrefManager.getVaultCurrentGroupId(ctx, "JEE"))
        assertEquals("neet_group", PrefManager.getVaultCurrentGroupId(ctx, "NEET"))
        assertEquals("2026-06-14", PrefManager.getVaultNextRefreshDate(ctx, "JEE"))
        assertEquals("2026-06-21", PrefManager.getVaultNextRefreshDate(ctx, "NEET"))
    }

    @Test
    fun `overwriting vault group id replaces previous value`() {
        PrefManager.setVaultCurrentGroupId(ctx, "JEE", "group_v1")
        PrefManager.setVaultCurrentGroupId(ctx, "JEE", "group_v2")
        assertEquals("group_v2", PrefManager.getVaultCurrentGroupId(ctx, "JEE"))
    }

    @Test
    fun `overwriting refresh date replaces previous value`() {
        PrefManager.setVaultNextRefreshDate(ctx, "JEE", "2026-06-14")
        PrefManager.setVaultNextRefreshDate(ctx, "JEE", "2026-06-21")
        assertEquals("2026-06-21", PrefManager.getVaultNextRefreshDate(ctx, "JEE"))
    }
}
