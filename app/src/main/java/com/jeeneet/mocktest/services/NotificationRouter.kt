package com.jeeneet.mocktest.services

import android.app.Activity
import android.content.Intent
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.ui.insights.InsightsActivity
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager

/**
 * Centralized navigation engine for all push notifications.
 * Decouples MainActivity from feature-specific navigation logic.
 */
object NotificationRouter {

    const val EXTRA_TYPE = "notif_type"
    
    // Standardized Notification Types
    const val TYPE_DAILY_TEST  = "DAILY_TEST"
    const val TYPE_STREAK_ALERT = "STREAK_ALERT"
    const val TYPE_SCORE_DROP   = "SCORE_DROP"
    const val TYPE_DORMANT      = "DORMANT"
    const val TYPE_DAILY_VAULT  = "DAILY_VAULT"
    const val TYPE_GENERAL      = "GENERAL"

    /**
     * Parses the incoming intent and routes the user to the correct screen.
     * Also logs analytics for the click event.
     */
    fun handle(activity: Activity, intent: Intent?) {
        if (intent == null) return
        
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return

        // Clear the extra first — prevents duplicate handling on activity re-entry/rotation
        intent.removeExtra(EXTRA_TYPE)

        // 1. Log the click
        AnalyticsManager.notificationClicked(activity, type)

        // 2. Route based on type
        when (type) {
            TYPE_DAILY_TEST, TYPE_STREAK_ALERT -> {
                val exam = PrefManager.getSelectedExam(activity)
                AdManager.showInterstitial(activity) {
                    TestActivity.startDailyQuiz(activity, exam)
                }
            }
            TYPE_SCORE_DROP -> {
                activity.startActivity(Intent(activity, InsightsActivity::class.java))
            }
            TYPE_DAILY_VAULT -> {
                // Opens MainActivity — vault card is visible on the home screen
            }
            TYPE_DORMANT -> {
                // Just open the app (already here)
            }
        }
    }
}
