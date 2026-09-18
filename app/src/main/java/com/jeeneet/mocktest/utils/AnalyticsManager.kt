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

    // ─── User Properties ─────────────────────────────────────────────────────

    /**
     * Sets global user properties to segment and filter all events in Firebase Console.
     */
    fun setUserProperties(
        ctx: Context,
        targetExam: String? = null,
        isPremium: Boolean? = null,
        streakDays: Int? = null,
        bankVersion: Int? = null
    ) {
        val fa = fa(ctx)
        targetExam?.let { fa.setUserProperty("target_exam", it) }
        isPremium?.let { fa.setUserProperty("user_tier", if (it) "premium" else "free") }
        streakDays?.let {
            val cohort = when {
                it == 0 -> "0_days"
                it in 1..3 -> "1_3_days"
                it in 4..7 -> "4_7_days"
                it in 8..30 -> "8_30_days"
                else -> "30_plus_days"
            }
            fa.setUserProperty("streak_cohort", cohort)
        }
        bankVersion?.let { fa.setUserProperty("bank_version", it.toString()) }
    }

    fun setTargetExam(ctx: Context, exam: String) {
        fa(ctx).setUserProperty("target_exam", exam)
    }

    fun setUserTier(ctx: Context, isPremium: Boolean) {
        fa(ctx).setUserProperty("user_tier", if (isPremium) "premium" else "free")
    }

    // ─── Screen Tracking ──────────────────────────────────────────────────────

    fun screenView(ctx: Context, screenName: String) {
        log(ctx, FirebaseAnalytics.Event.SCREEN_VIEW, bundle(
            FirebaseAnalytics.Param.SCREEN_NAME to screenName,
            FirebaseAnalytics.Param.SCREEN_CLASS to screenName
        ))
    }

    // ─── Sync & Bank Telemetry ────────────────────────────────────────────────

    fun syncStarted(ctx: Context, syncType: String) =
        log(ctx, "bank_sync_started", bundle("sync_type" to syncType))

    fun syncCompleted(
        ctx: Context,
        syncType: String,
        version: Int,
        freshCount: Int,
        localTotal: Int,
        durationMs: Long
    ) {
        val params = Bundle().apply {
            putString("sync_type", syncType)
            putInt("bank_version", version)
            putInt("fresh_count", freshCount)
            putInt("local_total", localTotal)
            putLong("duration_ms", durationMs)
        }
        log(ctx, "bank_sync_completed", params)
    }

    fun syncFailed(ctx: Context, syncType: String, reason: String) =
        log(ctx, "bank_sync_failed", bundle("sync_type" to syncType, "reason" to reason.take(100)))

    // ─── Scan / Doubt Solver ──────────────────────────────────────────────────

    /** User tapped Analyze — API call is about to be made. */
    fun scanStarted(ctx: Context) = log(ctx, "scan_started")

    /** User snapped or picked an image for doubt solving. */
    fun doubtImageCaptured(ctx: Context, source: String) =
        log(ctx, "doubt_image_captured", bundle("source" to source))

    /** User confirmed the cropped doubt region. */
    fun doubtCropConfirmed(ctx: Context) = log(ctx, "doubt_crop_confirmed")

    /** Gemini / AI API duration and result status. */
    fun doubtApiLatency(ctx: Context, durationMs: Long, isSuccess: Boolean) {
        val params = Bundle().apply {
            putLong("latency_ms", durationMs)
            putString("status", if (isSuccess) "success" else "failure")
        }
        log(ctx, "doubt_api_latency", params)
    }

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
    fun testCompleted(ctx: Context, examType: String, subject: String, score: Int = 0, accuracy: Float = 0f) {
        val params = Bundle().apply {
            putString("exam_type", examType)
            putString("subject", subject)
            putInt("score", score)
            putFloat("accuracy", accuracy)
        }
        log(ctx, "test_completed", params)
    }

    /** User started a test (first question displayed). */
    fun testStarted(ctx: Context, examType: String, testType: String = "practice") =
        log(ctx, "test_started", bundle("exam_type" to examType, "test_type" to testType))

    // ─── Daily Vault ──────────────────────────────────────────────────────────

    /** User tapped on the Daily Vault card. */
    fun vaultOpened(ctx: Context, examType: String = "JEE") =
        log(ctx, "vault_opened", bundle("exam_type" to examType))

    /** User answered a question within the vault. */
    fun vaultQuestionAnswered(ctx: Context, questionNum: Int, isCorrect: Boolean, subject: String) {
        val params = Bundle().apply {
            putInt("question_number", questionNum)
            putBoolean("is_correct", isCorrect)
            putString("subject", subject)
        }
        log(ctx, "vault_q_answered", params)
    }

    /** User completed all questions in the Daily Vault. */
    fun vaultCompleted(ctx: Context, accuracy: Float, score: Int = 0, examType: String = "JEE") {
        val params = Bundle().apply {
            putFloat("accuracy", accuracy)
            putInt("score", score)
            putString("exam_type", examType)
        }
        log(ctx, "vault_completed", params)
    }

    // ─── Power 100 Live ───────────────────────────────────────────────────────

    /** User viewed Power 100 entry. */
    fun power100Opened(ctx: Context, examType: String) =
        log(ctx, "power100_opened", bundle("exam_type" to examType))

    /** User started or resumed Power 100. */
    fun power100Started(ctx: Context, examType: String, isResumed: Boolean) =
        log(ctx, "power100_started", bundle("exam_type" to examType, "is_resumed" to isResumed.toString()))

    /** User submitted Power 100. */
    fun power100Submitted(ctx: Context, examType: String, score: Int, total: Int, accuracy: Float, durationSec: Long) {
        val params = Bundle().apply {
            putString("exam_type", examType)
            putInt("score", score)
            putInt("total", total)
            putFloat("accuracy", accuracy)
            putLong("duration_sec", durationSec)
        }
        log(ctx, "power100_submitted", params)
    }

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

    // ─── Telemetry & Content Quality ──────────────────────────────────────────

    /** User reported an issue with a question. */
    fun questionReported(ctx: Context, questionId: String, exam: String, subject: String, reason: String) {
        val params = Bundle().apply {
            putString("question_id", questionId.take(40))
            putString("exam_type", exam)
            putString("subject", subject)
            putString("reason", reason.take(40))
        }
        log(ctx, "question_reported", params)
    }

    /** User reached a study streak milestone. */
    fun streakMilestoneReached(ctx: Context, days: Int) =
        log(ctx, "streak_milestone", Bundle().apply { putInt("days", days) })

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
