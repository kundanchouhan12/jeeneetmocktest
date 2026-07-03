package com.jeeneet.mocktest.utils

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Centralised wrapper around FirebaseAnalytics.
 * All event names use snake_case and are kept ≤40 chars (Firebase limit).
 */
object AnalyticsManager {

    private fun fa(context: Context): FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context)

    private fun log(context: Context, event: String, params: Bundle? = null) {
        fa(context).logEvent(event, params)
    }

    private fun bundle(vararg pairs: Pair<String, String>) = Bundle().apply {
        pairs.forEach { (k, v) -> putString(k, v) }
    }

    // ─── Scan / Doubt Solver ──────────────────────────────────────────────────

    /** User tapped Analyze — API call is about to be made. */
    fun scanStarted(ctx: Context) = log(ctx, "scan_started")

    /** Returned instantly from Room cache (same image scanned before). */
    fun scanCacheHit(ctx: Context) = log(ctx, "scan_cache_hit")

    /** Gemini API returned successfully. */
    fun scanCompleted(ctx: Context, fromCache: Boolean) =
        log(ctx, "scan_completed", bundle("source" to if (fromCache) "cache" else "api"))

    /** User hit the daily free scan limit. */
    fun scanLimitReached(ctx: Context) = log(ctx, "scan_limit_reached")

    /** User watched a rewarded ad to unlock +1 scan. */
    fun scanAdWatched(ctx: Context) = log(ctx, "scan_ad_unlocked")

    /** User tapped "Go Premium" from the scan limit dialog. */
    fun scanUpgradeClicked(ctx: Context) = log(ctx, "scan_upgrade_clicked")

    // ─── Notifications ────────────────────────────────────────────────────────

    /** A remote push notification was received by the device. */
    fun notificationReceived(ctx: Context, type: String) =
        log(ctx, "notification_received", bundle("type" to type))

    /** User tapped a notification to open the app. */
    fun notificationClicked(ctx: Context, type: String) =
        log(ctx, "notification_clicked", bundle("type" to type))

    // ─── Solution screen ──────────────────────────────────────────────────────

    /** User sent a follow-up doubt question. */
    fun followUpSent(ctx: Context) = log(ctx, "follow_up_sent")

    /** User tapped Save on the solution screen. */
    fun solutionSaved(ctx: Context) = log(ctx, "solution_saved")

    /** User reopened a doubt from the history carousel. */
    fun doubtReopened(ctx: Context) = log(ctx, "doubt_reopened")

    /** User clicked a dot on the performance heatmap. */
    fun heatmapClicked(ctx: Context, status: String) =
        log(ctx, "heatmap_clicked", bundle("status" to status))

    /** New user tapped the 'Try scanning' CTA. */
    fun firstTimeDoubtHintClicked(ctx: Context) = log(ctx, "first_doubt_hint_clicked")

    // ─── Test flow ────────────────────────────────────────────────────────────

    /** User submitted a test. */
    fun testCompleted(ctx: Context, examType: String, subject: String) =
        log(ctx, "test_completed", bundle("exam_type" to examType, "subject" to subject))

    /** User started a test (first question displayed). */
    fun testStarted(ctx: Context, examType: String) =
        log(ctx, "test_started", bundle("exam_type" to examType))

    // ─── Daily Vault ──────────────────────────────────────────────────────────

    /** User tapped on the Daily Vault card. */
    fun vaultOpened(ctx: Context) = log(ctx, "vault_opened")

    /** User completed all 50 questions in the Daily Vault. */
    fun vaultCompleted(ctx: Context, accuracy: Float) =
        log(ctx, "vault_completed", Bundle().apply { putFloat("accuracy", accuracy) })

    // ─── Chapter flow ─────────────────────────────────────────────────────────

    /** User tapped on a chapter card in the list. */
    fun chapterClicked(ctx: Context, subject: String, chapter: String) =
        log(ctx, "chapter_clicked", bundle("subject" to subject, "chapter" to chapter.take(36)))

    /** Pre-screen dialog was shown before starting a chapter test. */
    fun chapterPreScreenShown(ctx: Context, subject: String, chapter: String) =
        log(ctx, "chapter_prescreen_shown", bundle("subject" to subject, "chapter" to chapter.take(36)))

    /** User confirmed start on the pre-screen dialog. */
    fun chapterTestStarted(ctx: Context, subject: String, chapter: String) =
        log(ctx, "chapter_test_started", bundle("subject" to subject, "chapter" to chapter.take(36)))

    /** User attempted to unlock a chapter via an ad. */
    fun chapterAdUnlockAttempted(ctx: Context, subject: String, chapter: String) =
        log(ctx, "chapter_ad_unlock", bundle("subject" to subject, "chapter" to chapter.take(36)))

    // ─── IAP / Revenue ────────────────────────────────────────────────────────

    /** User tapped a "Buy" button in ShopActivity. */
    fun purchaseClicked(ctx: Context, productId: String) =
        log(ctx, FirebaseAnalytics.Event.BEGIN_CHECKOUT,
            bundle(FirebaseAnalytics.Param.ITEM_ID to productId))

    /** IAP purchase completed successfully. */
    fun purchaseSuccess(ctx: Context, productId: String) =
        log(ctx, FirebaseAnalytics.Event.PURCHASE,
            bundle(FirebaseAnalytics.Param.ITEM_ID to productId))

    // ─── Ads ─────────────────────────────────────────────────────────────────

    /** Rewarded ad shown to user (any context). */
    fun rewardedAdShown(ctx: Context, placement: String) =
        log(ctx, "rewarded_ad_shown", bundle("placement" to placement))

    /** Rewarded ad finished completely. */
    fun rewardedAdCompleted(ctx: Context, placement: String) =
        log(ctx, "rewarded_ad_completed", bundle("placement" to placement))

    /** User closed the rewarded ad before completion. */
    fun rewardedAdDismissed(ctx: Context, placement: String) =
        log(ctx, "rewarded_ad_dismissed", bundle("placement" to placement))

    // ─── Phase 3: Telemetry ───────────────────────────────────────────────────

    /** User used a hint in a test. */
    fun hintUsed(ctx: Context, questionId: Int) =
        log(ctx, "hint_used", bundle("question_id" to questionId.toString()))

    /** User abandoned a test without submitting or saving. */
    fun testAbandoned(ctx: Context, questionsAnswered: Int, total: Int) =
        log(ctx, "test_abandoned", Bundle().apply {
            putInt("answered", questionsAnswered)
            putInt("total", total)
        })

    /** User watched an ad to freeze their streak. */
    fun streakFreezeWatched(ctx: Context) = log(ctx, "streak_freeze_ad")

    /**
     * Logs an ad impression with its paid value.
     * Firebase uses this event to power the ad revenue dashboard + user-level LTV.
     * Must be called from OnPaidEventListener with the AdValue provided by AdMob.
     */
    fun adImpression(
        ctx: Context,
        format: String,            // "banner" | "interstitial" | "rewarded" | "native" | "app_open"
        adUnitId: String,
        valueMicros: Long,
        currency: String,
        precisionType: Int         // AdValue.PrecisionType.* (0=unknown, 1=estimated, 2=publisher, 3=precise)
    ) {
        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob")
            putString(FirebaseAnalytics.Param.AD_SOURCE, "AdMob")
            putString(FirebaseAnalytics.Param.AD_FORMAT, format)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, adUnitId)
            putDouble(FirebaseAnalytics.Param.VALUE, valueMicros / 1_000_000.0)
            putString(FirebaseAnalytics.Param.CURRENCY, currency)
            putLong("precision_type", precisionType.toLong())
        }
        log(ctx, FirebaseAnalytics.Event.AD_IMPRESSION, params)
    }
}
