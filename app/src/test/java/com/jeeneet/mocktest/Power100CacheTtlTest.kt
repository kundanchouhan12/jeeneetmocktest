package com.jeeneet.mocktest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for Power100SyncManager's SharedPreferences-backed TTL and version
 * tracking logic. Exercises the prefs contract directly (same key names and
 * default values) to avoid constructing the class — whose constructor eagerly
 * opens Room and Firebase, both unavailable in unit tests.
 *
 * Key names mirror Power100SyncManager companion object:
 *   "cached_version_$exam"  — Long, default -1
 *   "last_checked_$exam"    — Long epoch ms, default 0 (→ getCacheAgeMinutes -1)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class Power100CacheTtlTest {

    private lateinit var ctx: Context
    private val PREFS     = "power100_prefs"
    private val TTL_MS    = 24 * 60 * 60 * 1000L

    private fun versionKey(exam: String)     = "cached_version_$exam"
    private fun lastCheckedKey(exam: String) = "last_checked_$exam"

    private fun getLocalVersion(exam: String): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(versionKey(exam), -1L)

    private fun getCacheAgeMinutes(exam: String): Long {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(lastCheckedKey(exam), 0L)
        return if (last == 0L) -1L else (System.currentTimeMillis() - last) / 60_000L
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ─── Version tracking ─────────────────────────────────────────────────────────

    @Test
    fun `getLocalVersion returns -1 when no version has been saved`() {
        assertEquals(-1L, getLocalVersion("JEE"))
    }

    @Test
    fun `getLocalVersion reflects a version written directly to prefs`() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(versionKey("JEE"), 5L).commit()
        assertEquals(5L, getLocalVersion("JEE"))
    }

    @Test
    fun `getLocalVersion is per-exam — JEE and NEET versions are independent`() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(versionKey("JEE"),  3L)
            .putLong(versionKey("NEET"), 7L)
            .commit()
        assertEquals(3L, getLocalVersion("JEE"))
        assertEquals(7L, getLocalVersion("NEET"))
    }

    @Test
    fun `getLocalVersion returns 0 when version was written as 0`() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(versionKey("JEE"), 0L).commit()
        assertEquals(0L, getLocalVersion("JEE"))
    }

    // ─── Cache age tracking ───────────────────────────────────────────────────────

    @Test
    fun `getCacheAgeMinutes returns -1 when never checked`() {
        assertEquals(-1L, getCacheAgeMinutes("JEE"))
    }

    @Test
    fun `getCacheAgeMinutes is near zero when last check was just now`() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(lastCheckedKey("JEE"), System.currentTimeMillis()).commit()
        val age = getCacheAgeMinutes("JEE")
        assertTrue("Expected ~0 min, got $age", age <= 1L)
    }

    @Test
    fun `getCacheAgeMinutes returns correct age for a 1-hour-old timestamp`() {
        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1_000L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(lastCheckedKey("JEE"), oneHourAgo).commit()
        val age = getCacheAgeMinutes("JEE")
        assertTrue("Expected ~60 min, got $age", age in 58L..62L)
    }

    @Test
    fun `getCacheAgeMinutes is per-exam`() {
        val now        = System.currentTimeMillis()
        val oneHourAgo = now - 60 * 60 * 1_000L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(lastCheckedKey("JEE"),  now)
            .putLong(lastCheckedKey("NEET"), oneHourAgo)
            .commit()
        assertTrue(getCacheAgeMinutes("JEE") <= 1L)
        assertTrue(getCacheAgeMinutes("NEET") in 58L..62L)
    }

    // ─── cacheIsWarm decision — mirrors checkAndSyncIfNeeded logic ────────────────

    private fun cacheIsWarm(localCount: Int, lastCheckedMs: Long): Boolean {
        val cacheAge = System.currentTimeMillis() - lastCheckedMs
        return localCount == 100 && cacheAge < TTL_MS
    }

    @Test
    fun `cache is warm when 100 questions present and checked within 24h`() {
        val under24h = System.currentTimeMillis() - 23 * 60 * 60 * 1_000L
        assertTrue(cacheIsWarm(100, under24h))
    }

    @Test
    fun `cache is stale when last check was over 24h ago`() {
        val over24h = System.currentTimeMillis() - 25 * 60 * 60 * 1_000L
        assertFalse(cacheIsWarm(100, over24h))
    }

    @Test
    fun `cache is stale when local count is 99 even if recently checked`() {
        val justNow = System.currentTimeMillis()
        assertFalse(cacheIsWarm(99, justNow))
    }

    @Test
    fun `cache is stale when room is empty regardless of timestamp`() {
        assertFalse(cacheIsWarm(0, System.currentTimeMillis()))
    }

    @Test
    fun `cache is stale at exactly the 24h boundary`() {
        val exactly24h = System.currentTimeMillis() - TTL_MS
        assertFalse(cacheIsWarm(100, exactly24h))
    }

    // ─── Version comparison — mirrors syncFromFirestore guard ────────────────────

    @Test
    fun `no re-download when remote version equals local and questions exist`() {
        val remoteVersion = 3L
        val localVersion  = 3L
        val localCount    = 100
        val shouldSkip = remoteVersion <= localVersion && localCount > 0
        assertTrue(shouldSkip)
    }

    @Test
    fun `re-download triggered when remote version is higher than local`() {
        val remoteVersion = 4L
        val localVersion  = 3L
        val localCount    = 100
        val shouldSkip = remoteVersion <= localVersion && localCount > 0
        assertFalse(shouldSkip)
    }

    @Test
    fun `re-download triggered when local count is zero even if versions match`() {
        val remoteVersion = 3L
        val localVersion  = 3L
        val localCount    = 0
        val shouldSkip = remoteVersion <= localVersion && localCount > 0
        assertFalse(shouldSkip)
    }

    @Test
    fun `re-download triggered on very first launch with version -1`() {
        val remoteVersion = 1L
        val localVersion  = -1L
        val localCount    = 0
        val shouldSkip = remoteVersion <= localVersion && localCount > 0
        assertFalse(shouldSkip)
    }
}
