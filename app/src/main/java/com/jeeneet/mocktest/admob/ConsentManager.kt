package com.jeeneet.mocktest.admob

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.jeeneet.mocktest.BuildConfig

/**
 * Google UMP consent flow (GDPR/EEA/UK). Ads must only be requested once [canRequestAds] is true.
 * The consent message itself is configured in AdMob console → Privacy & messaging.
 */
object ConsentManager {

    private const val TAG = "ConsentManager"

    private fun info(context: Context): ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    /** Uses consent stored from a previous session — safe to call from Application.onCreate(). */
    fun canRequestAds(context: Context): Boolean = info(context).canRequestAds()

    /** True when the user is allowed (and required) to reopen the consent form from settings. */
    fun isPrivacyOptionsRequired(activity: Activity): Boolean =
        info(activity).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Refreshes consent state and shows the form if required. [onComplete] is always invoked
     * exactly once (also on errors/offline) with whether ads may be requested.
     */
    fun gatherConsent(
        activity: Activity,
        onFormRequired: () -> Unit = {},
        onComplete: (canRequestAds: Boolean) -> Unit
    ) {
        var done = false
        fun finish() {
            if (done) return
            done = true
            onComplete(info(activity).canRequestAds())
        }

        val params = ConsentRequestParameters.Builder().apply {
            if (BuildConfig.DEBUG) {
                // Add your device hash from logcat ("Use new ConsentDebugSettings.Builder().addTestDeviceHashedId") to test EEA.
                setConsentDebugSettings(ConsentDebugSettings.Builder(activity).build())
            }
        }.build()

        info(activity).requestConsentInfoUpdate(
            activity, params,
            {
                if (activity.isFinishing || activity.isDestroyed) { finish(); return@requestConsentInfoUpdate }
                // Stored consent expired or the message changed: a form is about to show, so the
                // caller must keep its screen alive until it closes.
                if (info(activity).consentStatus == ConsentInformation.ConsentStatus.REQUIRED) onFormRequired()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) Log.w(TAG, "Consent form: ${formError.errorCode} ${formError.message}")
                    finish()
                }
            },
            { error ->
                Log.w(TAG, "Consent info update failed: ${error.errorCode} ${error.message}")
                finish()  // may still be true from cached consent state
            }
        )
    }

    /** Reopens the privacy options form (call from a settings entry when [isPrivacyOptionsRequired]). */
    fun showPrivacyOptions(activity: Activity, onDismissed: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "Privacy options: ${error.message}")
            onDismissed()
        }
    }
}
