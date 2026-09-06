package com.jeeneet.mocktest.utils

import android.content.Context
import android.content.SharedPreferences

object PrefManager {
    private const val PREF_NAME = "mocktest_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // First launch seeding
    fun isFirstLaunch(ctx: Context): Boolean = prefs(ctx).getBoolean("first_launch", true)
    fun setFirstLaunchDone(ctx: Context) = prefs(ctx).edit().putBoolean("first_launch", false).apply()

    // ─── Per-user IAP state ───────────────────────────────────────────────────
    // All purchase flags are stored under the Firebase UID as a key prefix.
    // Switching accounts instantly restores the correct purchase state for each
    // user — no async Google Play query needed, zero flicker on login.

    fun uid(): String = try {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
    } catch (e: Exception) {
        "guest"
    } catch (e: NoClassDefFoundError) {
        "guest"
    }

    // Debug override: forces free-user mode so ads show during testing (debug builds only)
    fun isDebugFreeMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean("debug_force_free_mode", false)
    fun setDebugFreeMode(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("debug_force_free_mode", enabled).apply()

    // IAP: ads removed — true if user bought Remove Ads OR All Access
    fun isAdsRemoved(ctx: Context): Boolean {
        if (isDebugFreeMode(ctx)) return false
        return prefs(ctx).getBoolean("ads_removed_${uid()}", false) || isAllAccessUnlocked(ctx)
    }
    fun setAdsRemoved(ctx: Context) =
        prefs(ctx).edit().putBoolean("ads_removed_${uid()}", true).apply()

    // IAP: unlocked packs
    fun getUnlockedPacks(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("unlocked_packs_${uid()}", emptySet()) ?: emptySet()

    fun unlockPack(ctx: Context, productId: String) {
        val current = getUnlockedPacks(ctx).toMutableSet()
        current.add(productId)
        prefs(ctx).edit().putStringSet("unlocked_packs_${uid()}", current).apply()
    }

    fun isPackUnlocked(ctx: Context, productId: String): Boolean {
        if (productId.isEmpty()) return false
        return productId in getUnlockedPacks(ctx) || isAllAccessUnlocked(ctx)
    }

    fun isAllAccessUnlocked(ctx: Context): Boolean {
        if (isDebugFreeMode(ctx)) return false
        return prefs(ctx).getBoolean("all_access_${uid()}", false)
    }

    fun setAllAccessUnlocked(ctx: Context) =
        prefs(ctx).edit().putBoolean("all_access_${uid()}", true).apply()

    fun hasRatedApp(ctx: Context): Boolean = prefs(ctx).getBoolean("has_rated_app_${uid()}", false)
    fun setRatedApp(ctx: Context) = prefs(ctx).edit().putBoolean("has_rated_app_${uid()}", true).apply()

    fun openPlayStore(ctx: Context) {
        setRatedApp(ctx) // Mark as rated immediately when they click
        val packageName = ctx.packageName
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, 
                android.net.Uri.parse("market://details?id=$packageName")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, 
                android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun revokeAllAccess(ctx: Context) =
        prefs(ctx).edit().remove("all_access_${uid()}").apply()

    fun revokeAdsRemoved(ctx: Context) =
        prefs(ctx).edit().remove("ads_removed_${uid()}").apply()

    fun setUnlockedPacks(ctx: Context, packs: Set<String>) =
        prefs(ctx).edit().putStringSet("unlocked_packs_${uid()}", packs).apply()

    // Clears IAP state for the current logged-in user only
    fun clearIAPState(ctx: Context) {
        val u = uid()
        prefs(ctx).edit()
            .remove("all_access_$u")
            .remove("ads_removed_$u")
            .remove("unlocked_packs_$u")
            .apply()
    }

    // ─── 2-Day Premium Trial ─────────────────────────────────────────────────────
    // One-time 48-hour full-access trial per account. Requires internet to activate.

    private const val TRIAL_DURATION_MS = 48L * 60 * 60 * 1000 // 48 hours

    fun startPremiumTrial(ctx: Context) {
        if (hasPremiumTrialBeenUsed(ctx)) return
        prefs(ctx).edit().putLong("premium_trial_start_${uid()}", System.currentTimeMillis()).apply()
    }

    fun hasPremiumTrialBeenUsed(ctx: Context): Boolean =
        prefs(ctx).getLong("premium_trial_start_${uid()}", 0L) > 0L

    fun isPremiumTrialActive(ctx: Context): Boolean {
        val start = prefs(ctx).getLong("premium_trial_start_${uid()}", 0L)
        if (start == 0L) return false
        return System.currentTimeMillis() - start < TRIAL_DURATION_MS
    }

    fun getPremiumTrialRemainingMs(ctx: Context): Long {
        val start = prefs(ctx).getLong("premium_trial_start_${uid()}", 0L)
        if (start == 0L) return 0L
        return maxOf(0L, TRIAL_DURATION_MS - (System.currentTimeMillis() - start))
    }

    // True if user has any form of premium access (paid subscription or active trial)
    fun isEffectivelyPremium(ctx: Context): Boolean =
        isAllAccessUnlocked(ctx) || isPremiumTrialActive(ctx)

    // No-ops kept for call-site compatibility
    fun syncIAPUserIfNeeded(ctx: Context, currentUid: String) {}
    fun setIAPOwnerUid(ctx: Context, uid: String) {}

    // Daily streak
    fun getStreak(ctx: Context): Int = prefs(ctx).getInt("streak_${uid()}", 0)

    fun isStreakBroken(ctx: Context): Boolean {
        val lastUpdate = prefs(ctx).getLong("streak_last_update_${uid()}", 0)
        if (lastUpdate == 0L) return false
        val today = System.currentTimeMillis() / 86_400_000
        val last = lastUpdate / 86_400_000
        return (today - last) > 1 // Missed at least one full calendar day
    }

    fun getLastTestDate(ctx: Context): Long = prefs(ctx).getLong("last_test_date_${uid()}", 0L)

    fun updateStreak(ctx: Context) {
        val today = System.currentTimeMillis() / 86_400_000
        val lastDay = getLastTestDate(ctx) / 86_400_000
        val previousStreak = getStreak(ctx)
        val streak = when {
            today == lastDay     -> previousStreak           // same day, no change
            today == lastDay + 1 -> previousStreak + 1      // consecutive day
            else                 -> 1                        // streak broken
        }
        // If streak broke (reset to 1), clear milestone flags so user can earn them again
        if (streak == 1 && previousStreak > 1) {
            resetStreakMilestones(ctx)
        }
        prefs(ctx).edit()
            .putInt("streak_${uid()}", streak)
            .putLong("last_test_date_${uid()}", System.currentTimeMillis())
            .apply()
    }

    // Selected exam tab
    fun getSelectedExam(ctx: Context): String = prefs(ctx).getString("selected_exam_${uid()}", "JEE") ?: "JEE"
    fun setSelectedExam(ctx: Context, exam: String) =
        prefs(ctx).edit().putString("selected_exam_${uid()}", exam).apply()

    // Question bank sync versioning
    fun getLastSyncedVersion(ctx: Context): Int = prefs(ctx).getInt("question_bank_version", 0)
    fun setLastSyncedVersion(ctx: Context, version: Int) =
        prefs(ctx).edit().putInt("question_bank_version", version).apply()

    // App version tracking — used to detect installs vs upgrades
    fun getStoredVersionCode(ctx: Context): Int = prefs(ctx).getInt("app_version_code", 0)
    fun setStoredVersionCode(ctx: Context, versionCode: Int) =
        prefs(ctx).edit().putInt("app_version_code", versionCode).apply()

    fun getLastVaultSyncDate(ctx: Context): String = prefs(ctx).getString("last_vault_sync_date_${uid()}", "") ?: ""
    fun setLastVaultSyncDate(ctx: Context, date: String) =
        prefs(ctx).edit().putString("last_vault_sync_date_${uid()}", date).apply()

    // Daily ad-free unlock (1 free try per day)
    fun hasUsedAdFreeUnlockToday(ctx: Context): Boolean {
        val today = System.currentTimeMillis() / 86_400_000
        return prefs(ctx).getLong("ad_unlock_day", -1L) == today
    }
    fun markAdFreeUnlockUsed(ctx: Context) {
        prefs(ctx).edit().putLong("ad_unlock_day", System.currentTimeMillis() / 86_400_000).apply()
    }

    // Paused test session (resume later)
    fun hasSavedTestSession(ctx: Context): Boolean = prefs(ctx).contains("saved_test_session_${uid()}")
    fun getSavedTestSessionJson(ctx: Context): String? = prefs(ctx).getString("saved_test_session_${uid()}", null)
    fun saveTestSessionJson(ctx: Context, json: String) =
        prefs(ctx).edit().putString("saved_test_session_${uid()}", json).apply()
    fun clearSavedTestSession(ctx: Context) =
        prefs(ctx).edit().remove("saved_test_session_${uid()}").apply()

    // ─── Scan / Doubt Solver limits ───────────────────────────────────────────
    // Free users: 2 scans/day  |  Premium (All Access): unlimited
    const val FREE_DAILY_SCANS = 2

    fun getScanCountToday(ctx: Context): Int {
        val today = System.currentTimeMillis() / 86_400_000
        return if (prefs(ctx).getLong("scan_day_${uid()}", -1L) == today)
            prefs(ctx).getInt("scan_count_today_${uid()}", 0) else 0
    }

    fun incrementScanCount(ctx: Context) {
        val today = System.currentTimeMillis() / 86_400_000
        prefs(ctx).edit()
            .putLong("scan_day_${uid()}", today)
            .putInt("scan_count_today_${uid()}", getScanCountToday(ctx) + 1)
            .apply()
    }

    /** Each refill (interstitial ad) grants +5 scans. */
    fun getScanAdBonusToday(ctx: Context): Int {
        val today = System.currentTimeMillis() / 86_400_000
        return if (prefs(ctx).getLong("scan_ad_day_${uid()}", -1L) == today)
            prefs(ctx).getInt("scan_ad_bonus_${uid()}", 0) else 0
    }

    fun addScanRefill(ctx: Context) {
        val today = System.currentTimeMillis() / 86_400_000
        prefs(ctx).edit()
            .putLong("scan_ad_day_${uid()}", today)
            .putInt("scan_ad_bonus_${uid()}", getScanAdBonusToday(ctx) + 1)
            .apply()
    }

    fun updateScanData(ctx: Context, used: Int, refills: Int, day: Long) {
        prefs(ctx).edit()
            .putLong("scan_day_${uid()}", day)
            .putInt("scan_count_today_${uid()}", used)
            .putLong("scan_ad_day_${uid()}", day)
            .putInt("scan_ad_bonus_${uid()}", refills)
            .apply()
    }

    fun canScanNow(ctx: Context): Boolean {
        if (isAllAccessUnlocked(ctx)) return true
        return getScanCountToday(ctx) < FREE_DAILY_SCANS + (getScanAdBonusToday(ctx) * 5)
    }

    fun getRemainingScans(ctx: Context): Int {
        if (isAllAccessUnlocked(ctx)) return Int.MAX_VALUE
        return maxOf(0, FREE_DAILY_SCANS + (getScanAdBonusToday(ctx) * 5) - getScanCountToday(ctx))
    }

    // ─── Daily Full Mock Tracking (Phase 1) ──────────────────────────────────
    
    fun getFullMocksGeneratedToday(ctx: Context): Int {
        val today = System.currentTimeMillis() / 86_400_000
        return if (prefs(ctx).getLong("full_mock_gen_day_${uid()}", -1L) == today)
            prefs(ctx).getInt("full_mock_gen_count_${uid()}", 0) else 0
    }

    fun incrementFullMocksGeneratedToday(ctx: Context) {
        val today = System.currentTimeMillis() / 86_400_000
        prefs(ctx).edit()
            .putLong("full_mock_gen_day_${uid()}", today)
            .putInt("full_mock_gen_count_${uid()}", getFullMocksGeneratedToday(ctx) + 1)
            .apply()
    }

    fun getLastFullMockQuestionsJson(ctx: Context): String? {
        val today = System.currentTimeMillis() / 86_400_000
        if (prefs(ctx).getLong("full_mock_gen_day_${uid()}", -1L) != today) return null
        return prefs(ctx).getString("last_full_mock_qs_${uid()}", null)
    }

    fun saveLastFullMockQuestionsJson(ctx: Context, json: String) {
        val today = System.currentTimeMillis() / 86_400_000
        prefs(ctx).edit()
            .putLong("full_mock_gen_day_${uid()}", today)
            .putString("last_full_mock_qs_${uid()}", json)
            .apply()
    }

    // ─── Rewarded Ad Frequency Caps (Phase 3) ───────────────────────────────

    private fun getDailyAdKey(ctx: Context, type: String): String {
        val today = System.currentTimeMillis() / 86_400_000
        return "ad_${type}_${today}_${uid()}"
    }

    fun getAdUsageCount(ctx: Context, type: String): Int = prefs(ctx).getInt(getDailyAdKey(ctx, type), 0)

    fun incrementAdUsageCount(ctx: Context, type: String) {
        val key = getDailyAdKey(ctx, type)
        prefs(ctx).edit().putInt(key, getAdUsageCount(ctx, type) + 1).apply()
    }

    fun canWatchAdForExtraMock(ctx: Context): Boolean {
        if (isAllAccessUnlocked(ctx)) return true
        return getAdUsageCount(ctx, "extra_mock") < 2 // Max 2 per day
    }

    fun canWatchAdForExtraQuestions(ctx: Context): Boolean {
        if (isAllAccessUnlocked(ctx)) return true
        return getAdUsageCount(ctx, "extra_questions") < 3 // Max 3 per day
    }

    fun getMaxExtraMocks(): Int = 2
    fun getMaxExtraQuestions(): Int = 3

    fun getTotalQuestionsSolvedToday(ctx: Context): Int {
        val today = System.currentTimeMillis() / 86_400_000
        return prefs(ctx).getInt("solved_count_${today}_${uid()}", 0)
    }

    fun incrementTotalQuestionsSolved(ctx: Context, count: Int) {
        val today = System.currentTimeMillis() / 86_400_000
        val current = getTotalQuestionsSolvedToday(ctx)
        prefs(ctx).edit().putInt("solved_count_${today}_${uid()}", current + count).apply()
    }
    fun getLastCommentTimestamp(ctx: Context): Long = prefs(ctx).getLong("last_comment_at_${uid()}", 0L)
    fun updateLastCommentTimestamp(ctx: Context) = prefs(ctx).edit().putLong("last_comment_at_${uid()}", System.currentTimeMillis()).apply()

    fun canPostComment(ctx: Context): Boolean {
        val last = getLastCommentTimestamp(ctx)
        return (System.currentTimeMillis() - last) > 15_000 // 15 seconds
    }

    // ─── Daily Quiz lock ──────────────────────────────────────────────────────
    // Locked once the user submits (or timer expires). Auto-unlocks next calendar day.

    fun isDailyQuizDoneToday(ctx: Context): Boolean {
        val today = System.currentTimeMillis() / 86_400_000
        return prefs(ctx).getLong("daily_quiz_day_${uid()}", -1L) == today
    }

    fun markDailyQuizDone(ctx: Context) {
        val today = System.currentTimeMillis() / 86_400_000
        prefs(ctx).edit().putLong("daily_quiz_day_${uid()}", today).apply()
    }

    // ─── Coins ────────────────────────────────────────────────────────────────────
    const val COINS_COMPLETE_TEST = 10
    const val COINS_DAILY_QUIZ    = 15

    fun getCoins(ctx: Context): Int = prefs(ctx).getInt("coins_${uid()}", 0)

    fun addCoins(ctx: Context, amount: Int) =
        prefs(ctx).edit().putInt("coins_${uid()}", getCoins(ctx) + amount).apply()

    fun spendCoins(ctx: Context, amount: Int): Boolean {
        val current = getCoins(ctx)
        if (current < amount) return false
        prefs(ctx).edit().putInt("coins_${uid()}", current - amount).apply()
        return true
    }

    fun hasAwardedCoinsForResult(ctx: Context, resultId: Int): Boolean =
        prefs(ctx).getBoolean("coins_result_${uid()}_$resultId", false)

    fun markCoinsAwardedForResult(ctx: Context, resultId: Int) =
        prefs(ctx).edit().putBoolean("coins_result_${uid()}_$resultId", true).apply()

    // ─── Streak Calendar — practiced date history ─────────────────────────────────
    fun getPracticedDates(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("practiced_${uid()}", emptySet()) ?: emptySet()

    fun markPracticedToday(ctx: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val updated = getPracticedDates(ctx).toMutableSet().also { it.add(today) }
        prefs(ctx).edit().putStringSet("practiced_${uid()}", updated).apply()
    }

    fun hasPracticedOn(ctx: Context, dateStr: String): Boolean =
        dateStr in getPracticedDates(ctx)

    fun getTotalPracticedDays(ctx: Context): Int = getPracticedDates(ctx).size

    // ─── FOMO daily attempt counter cache ─────────────────────────────────────────
    private fun todayKey(): String = (System.currentTimeMillis() / 86_400_000).toString()

    fun getCachedDailyAttempts(ctx: Context): Int =
        prefs(ctx).getInt("fomo_${todayKey()}", 0)

    fun setCachedDailyAttempts(ctx: Context, count: Int) =
        prefs(ctx).edit().putInt("fomo_${todayKey()}", count).apply()

    // Onboarding
    fun isOnboardingDone(ctx: Context): Boolean = prefs(ctx).getBoolean("onboarding_done", false)
    fun setOnboardingDone(ctx: Context) = prefs(ctx).edit().putBoolean("onboarding_done", true).apply()
    fun resetOnboarding(ctx: Context) = prefs(ctx).edit().remove("onboarding_done").apply() // dev use only

    fun saveOnboardingPending(ctx: Context, exam: String, goal: Int) =
        prefs(ctx).edit()
            .putString("onboarding_exam_pending", exam)
            .putInt("onboarding_goal_pending", goal)
            .apply()

    fun applyAndClearOnboardingPending(ctx: Context) {
        val exam = prefs(ctx).getString("onboarding_exam_pending", null) ?: return
        val goal = prefs(ctx).getInt("onboarding_goal_pending", -1)
        setSelectedExam(ctx, exam)
        if (goal > 0) setDailyGoal(ctx, goal)
        prefs(ctx).edit()
            .remove("onboarding_exam_pending")
            .remove("onboarding_goal_pending")
            .apply()
    }

    // ─── Dark mode preference ─────────────────────────────────────────────────
    // 0 = follow system, 1 = force light, 2 = force dark

    fun getDarkModePreference(ctx: Context): Int =
        prefs(ctx).getInt("dark_mode_pref", 2) // default: dark (see MockTestApplication.onCreate)

    fun setDarkModePreference(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt("dark_mode_pref", mode).apply()

    fun isDarkMode(ctx: Context): Boolean = getDarkModePreference(ctx) == 2

    // ─── Daily Goals ──────────────────────────────────────────────────────────

    fun getDailyGoal(ctx: Context): Int = prefs(ctx).getInt("daily_goal_questions_${uid()}", 50)
    fun setDailyGoal(ctx: Context, n: Int) = prefs(ctx).edit().putInt("daily_goal_questions_${uid()}", n).apply()

    fun getDailyQuestionsAttempted(ctx: Context): Int {
        val today = System.currentTimeMillis() / 86_400_000
        return if (prefs(ctx).getLong("daily_goal_day_${uid()}", -1L) == today)
            prefs(ctx).getInt("daily_goal_attempted_${uid()}", 0) else 0
    }

    fun incrementDailyQuestionsAttempted(ctx: Context, n: Int) {
        val today   = System.currentTimeMillis() / 86_400_000
        val current = getDailyQuestionsAttempted(ctx)
        prefs(ctx).edit()
            .putLong("daily_goal_day_${uid()}", today)
            .putInt("daily_goal_attempted_${uid()}", current + n)
            .apply()
    }

    // ─── Last Test Score (comeback banner + score-aware notifications) ─────────

    data class LastScoreInfo(
        val scorePercent: Int,   // 0–100
        val examType: String,    // "JEE" | "NEET"
        val subject: String,     // "" = full paper
        val savedAt: Long        // epoch ms
    )

    fun saveLastTestScore(ctx: Context, scorePercent: Int, examType: String, subject: String) {
        val currentLast = prefs(ctx).getInt("last_score_pct_${uid()}", -1)
        prefs(ctx).edit()
            .putInt("prev_score_pct_${uid()}", currentLast) // Preserve old score for improvement delta
            .putInt("last_score_pct_${uid()}", scorePercent)
            .putString("last_score_exam_${uid()}", examType)
            .putString("last_score_subject_${uid()}", subject)
            .putLong("last_score_at_${uid()}", System.currentTimeMillis())
            .apply()
    }

    fun getPreviousScorePct(ctx: Context): Int = prefs(ctx).getInt("prev_score_pct_${uid()}", -1)

    fun getLastTestScoreInfo(ctx: Context): LastScoreInfo? {
        val savedAt = prefs(ctx).getLong("last_score_at_${uid()}", 0L)
        if (savedAt == 0L) return null
        return LastScoreInfo(
            scorePercent = prefs(ctx).getInt("last_score_pct_${uid()}", 0),
            examType     = prefs(ctx).getString("last_score_exam_${uid()}", "JEE") ?: "JEE",
            subject      = prefs(ctx).getString("last_score_subject_${uid()}", "") ?: "",
            savedAt      = savedAt
        )
    }

    /**
     * True if the user practiced yesterday but NOT today yet.
     * Used to decide between "comeback" vs normal greeting.
     */
    fun hasDoneQuizYesterday(ctx: Context): Boolean {
        val today     = System.currentTimeMillis() / 86_400_000
        val yesterday = today - 1
        val lastDay   = getLastTestDate(ctx) / 86_400_000
        return lastDay == yesterday
    }

    /**
     * True if the user has NOT opened the app for 2+ days.
     * Used for more urgent comeback notifications.
     */
    fun daysSinceLastPractice(ctx: Context): Int {
        val lastDate = getLastTestDate(ctx)
        if (lastDate == 0L) return Int.MAX_VALUE
        val today   = System.currentTimeMillis() / 86_400_000
        val lastDay = lastDate / 86_400_000
        return (today - lastDay).toInt().coerceAtLeast(0)
    }

    // ─── Streak Milestone Rewards ─────────────────────────────────────────────
    // Milestones: 3, 7, 30 days. Awarded once per streak cycle.
    // "Per cycle" = reset when streak resets to 1, so re-earnable if they rebuild.

    private fun milestoneKey(milestone: Int): String = "streak_milestone_${uid()}_$milestone"

    /** Returns the streak count at which the milestone was last awarded (0 = never). */
    fun getStreakMilestoneAwardedAt(ctx: Context, milestone: Int): Int =
        prefs(ctx).getInt(milestoneKey(milestone), 0)

    /**
     * Mark milestone as awarded at the current streak value.
     * Calling this again when streak > old value means "new cycle" earned it.
     */
    fun markStreakMilestoneAwarded(ctx: Context, milestone: Int, atStreak: Int) =
        prefs(ctx).edit().putInt(milestoneKey(milestone), atStreak).apply()

    /**
     * Checks whether a milestone should be awarded RIGHT NOW.
     * Returns true only once per streak build-up (not repeatedly once reached).
     */
    fun shouldAwardMilestone(ctx: Context, milestone: Int): Boolean {
        val streak    = getStreak(ctx)
        val awardedAt = getStreakMilestoneAwardedAt(ctx, milestone)
        // Award if streak just reached this milestone and we haven't awarded it in this cycle
        return streak >= milestone && awardedAt < milestone
    }

    /**
     * Award milestone coins and mark it done. Returns coins awarded (0 if already given).
     */
    fun awardMilestoneIfEligible(ctx: Context, milestone: Int): Int {
        if (!shouldAwardMilestone(ctx, milestone)) return 0
        val coins = when (milestone) {
            3    -> 30
            7    -> 50
            30   -> 100
            else -> 0
        }
        if (coins > 0) {
            addCoins(ctx, coins)
            markStreakMilestoneAwarded(ctx, milestone, getStreak(ctx))
        }
        return coins
    }

    /**
     * Called on streak reset (when user misses a day) — clears milestone flags
     * so they can earn them again in the next streak cycle.
     */
    fun resetStreakMilestones(ctx: Context) {
        prefs(ctx).edit()
            .remove(milestoneKey(3))
            .remove(milestoneKey(7))
            .remove(milestoneKey(30))
            .apply()
    }

    // ─── Notification Permission Tracking ──────────────────────────────────────
    fun hasPromptedForNotifications(ctx: Context): Boolean =
        prefs(ctx).getBoolean("prompted_notifications", false)
    fun setPromptedForNotifications(ctx: Context) =
        prefs(ctx).edit().putBoolean("prompted_notifications", true).apply()

    // Debug-only: Force set streak to test milestones
    fun setStreakForDebug(ctx: Context, n: Int) =
        prefs(ctx).edit().putInt("streak_${uid()}", n).apply()

    // ─── Total Tests Completed (for rating prompt) ────────────────────────────
    fun getTotalTestsCompleted(ctx: Context): Int = prefs(ctx).getInt("total_tests_${uid()}", 0)
    fun incrementTotalTestsCompleted(ctx: Context) =
        prefs(ctx).edit().putInt("total_tests_${uid()}", getTotalTestsCompleted(ctx) + 1).apply()

    // ─── Weekly Test Tracking ──────────────────────────────────────────────────
    fun getLastSeenWeeklyTestWeek(ctx: Context): Int = 
        prefs(ctx).getInt("last_weekly_test_week_${uid()}", -1)
    
    fun setLastSeenWeeklyTestWeek(ctx: Context, week: Int) =
        prefs(ctx).edit().putInt("last_weekly_test_week_${uid()}", week).apply()

    // ─── AI Counselor Cache ───────────────────────────────────────────────────
    fun getAiAdviceCache(ctx: Context, key: String): String = 
        prefs(ctx).getString("ai_cache_${key}_${uid()}", "") ?: ""

    fun setAiAdviceCache(ctx: Context, key: String, advice: String, testCount: Int) {
        prefs(ctx).edit()
            .putString("ai_cache_${key}_${uid()}", advice)
            .putInt("ai_cache_count_${key}_${uid()}", testCount)
            .apply()
    }

    fun getAiAdviceCacheTestCount(ctx: Context, key: String): Int =
        prefs(ctx).getInt("ai_cache_count_${key}_${uid()}", 0)

    // ─── Daily Vault Completed tracking ───
    fun isDailyVaultDoneToday(ctx: Context, exam: String): Boolean {
        val today = System.currentTimeMillis() / 86_400_000
        return prefs(ctx).getLong("daily_vault_day_${exam}_${uid()}", -1L) == today
    }

    fun markDailyVaultDone(ctx: Context, exam: String) {
        val today = System.currentTimeMillis() / 86_400_000
        prefs(ctx).edit().putLong("daily_vault_day_${exam}_${uid()}", today).apply()
    }

    fun saveLastDailyVaultQuestionsJson(ctx: Context, exam: String, json: String) {
        prefs(ctx).edit().putString("last_daily_vault_qs_${exam}_${uid()}", json).apply()
    }

    fun getLastDailyVaultQuestionsJson(ctx: Context, exam: String): String? {
        return prefs(ctx).getString("last_daily_vault_qs_${exam}_${uid()}", null)
    }

    // ─── Vault Persistence — frozen questions until next scheduled refresh ──────
    fun getVaultCurrentGroupId(ctx: Context, exam: String): String =
        prefs(ctx).getString("vault_group_id_${exam}_${uid()}", "") ?: ""
    fun setVaultCurrentGroupId(ctx: Context, exam: String, groupId: String) =
        prefs(ctx).edit().putString("vault_group_id_${exam}_${uid()}", groupId).apply()

    fun getVaultNextRefreshDate(ctx: Context, exam: String): String =
        prefs(ctx).getString("vault_next_refresh_${exam}_${uid()}", "") ?: ""
    fun setVaultNextRefreshDate(ctx: Context, exam: String, date: String) =
        prefs(ctx).edit().putString("vault_next_refresh_${exam}_${uid()}", date).apply()

    fun getVaultSnapshotDate(ctx: Context, exam: String): String =
        prefs(ctx).getString("vault_snapshot_${exam}_${uid()}", "") ?: ""
    fun setVaultSnapshotDate(ctx: Context, exam: String, date: String) =
        prefs(ctx).edit().putString("vault_snapshot_${exam}_${uid()}", date).apply()

    // ─── Vault TTL — mirrors Power100's lastChecked pattern ──────────────────
    fun getVaultLastCheckedMs(ctx: Context, exam: String): Long =
        prefs(ctx).getLong("vault_last_checked_ms_${exam}_${uid()}", 0L)
    fun setVaultLastCheckedMs(ctx: Context, exam: String, ms: Long) =
        prefs(ctx).edit().putLong("vault_last_checked_ms_${exam}_${uid()}", ms).apply()
}
