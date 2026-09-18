package com.jeeneet.mocktest.utils

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.model.ReviewErrorCode

object InAppReviewManager {

    private const val TAG = "InAppReview"

    /**
     * Attempts to request the Google Play In-App Review flow if criteria are met:
     * 1. User has not already seen the prompt.
     * 2. User has completed at least 2 tests (high intent & good sentiment moment).
     * 3. User is not currently in a test.
     */
    fun maybeRequestReview(activity: Activity) {
        val ctx = activity.applicationContext

        // Check if already prompted
        if (PrefManager.hasShownReviewPrompt(ctx)) {
            return
        }

        // Only prompt after user has completed at least 2 tests or practiced 2+ days
        val totalTests = PrefManager.getTotalTestsCompleted(ctx)
        val practicedDays = PrefManager.getTotalPracticedDays(ctx)
        if (totalTests < 2 && practicedDays < 2) {
            return
        }

        try {
            val manager = ReviewManagerFactory.create(activity)
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(activity, reviewInfo)
                    flow.addOnCompleteListener { _ ->
                        // Regardless of whether review was submitted or dismissed, mark as shown
                        PrefManager.setReviewPromptShown(ctx)
                        Log.d(TAG, "In-app review flow completed successfully")
                    }
                } else {
                    val reviewErrorCode = (task.exception as? ReviewException)?.errorCode
                    Log.w(TAG, "In-app review request failed with code: ")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch in-app review", e)
        }
    }
}
