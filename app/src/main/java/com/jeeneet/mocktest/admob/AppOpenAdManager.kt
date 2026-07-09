package com.jeeneet.mocktest.admob

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.jeeneet.mocktest.data.model.AdUnitIds
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.ui.auth.ForgotPasswordActivity
import com.jeeneet.mocktest.ui.auth.LoginActivity
import com.jeeneet.mocktest.ui.auth.OnboardingActivity
import com.jeeneet.mocktest.ui.auth.SignupActivity
import com.jeeneet.mocktest.ui.auth.SplashActivity
import com.jeeneet.mocktest.ui.doubts.ScanActivity
import com.jeeneet.mocktest.ui.home.ShopActivity
import com.jeeneet.mocktest.ui.power100.Power100Activity
import com.jeeneet.mocktest.ui.simulation.SimulationIntroActivity
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.PrefManager

/**
 * Manages App Open Ads. Shows on cold start and warm start (app brought to foreground).
 * Never shows during an active test session.
 */
class AppOpenAdManager(private val application: Application) :
    Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AppOpenAdManager"
        private const val AD_EXPIRY_MS  = 4 * 3_600_000L   // 4 hours
        private const val SHOW_COOLDOWN = 5 * 3_600_000L   // 5 hours — was 30 SECONDS, way too aggressive for app-open
    }

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var loadTimeMs  = 0L
    private var lastShownMs = 0L
    private var isShowPending = false   // true when onStart fired but ad wasn't ready yet
    var currentActivity: Activity? = null
        private set

    init {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Returns true for activities where open ads must never appear. */
    private fun Activity.isAdExcluded() =
        this is TestActivity || this is Power100Activity || this is SimulationIntroActivity ||
        this is LoginActivity || this is OnboardingActivity || this is SignupActivity ||
        this is ForgotPasswordActivity || this is SplashActivity ||
        this is ShopActivity || this is ScanActivity

    // ─── ProcessLifecycle — fires only on true foreground (not between activities) ──

    override fun onStart(owner: LifecycleOwner) {
        val activity = currentActivity ?: return
        if (activity.isAdExcluded()) return                    // Auth screens & test — skip
        if (PrefManager.isAdsRemoved(activity)) return
        if (System.currentTimeMillis() - lastShownMs < SHOW_COOLDOWN) return
        if (!isAdAvailable()) {
            isShowPending = true   // Ad still loading (cold start) — show as soon as it's ready
            loadAd()
            return
        }
        isShowPending = false
        showAdIfAvailable(activity)
    }

    // ─── Load ─────────────────────────────────────────────────────────────────

    fun loadAd() {
        if (isLoadingAd || isAdAvailable()) return
        isLoadingAd = true
        AppOpenAd.load(
            application, AdUnitIds.APP_OPEN, AdManager.buildRequest(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    ad.onPaidEventListener = OnPaidEventListener { adValue ->
                        Log.d(TAG, "[Revenue] AppOpen: ${adValue.valueMicros}µ ${adValue.currencyCode} prec=${adValue.precisionType}")
                        AnalyticsManager.adImpression(
                            application, "app_open", AdUnitIds.APP_OPEN,
                            adValue.valueMicros, adValue.currencyCode, adValue.precisionType
                        )
                    }
                    appOpenAd  = ad
                    loadTimeMs = System.currentTimeMillis()
                    isLoadingAd = false
                    Log.d(TAG, "Loaded")
                    // Cold start: onStart fired before the ad was ready — show it now
                    if (isShowPending) {
                        isShowPending = false
                        val activity = currentActivity
                        if (activity != null && !activity.isAdExcluded() &&
                            !PrefManager.isAdsRemoved(activity)) {
                            showAdIfAvailable(activity)
                        }
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    isShowPending = false
                    Log.w(TAG, "Failed to load: ${error.message}")
                }
            }
        )
    }

    // ─── Show ─────────────────────────────────────────────────────────────────

    private fun isAdAvailable() =
        appOpenAd != null && System.currentTimeMillis() - loadTimeMs < AD_EXPIRY_MS

    private fun showAdIfAvailable(activity: Activity) {
        if (!isAdAvailable()) { loadAd(); return }
        val ad = appOpenAd ?: return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShownMs = System.currentTimeMillis()
            }
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                loadAd()   // Pre-load for next time
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                Log.w(TAG, "Failed to show: ${error.message}")
            }
        }
        ad.show(activity)
    }

    // ─── ActivityLifecycleCallbacks ───────────────────────────────────────────

    override fun onActivityStarted(activity: Activity)  { currentActivity = activity }
    override fun onActivityResumed(activity: Activity)  { currentActivity = activity }
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) currentActivity = null
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity)   {}
    override fun onActivityStopped(activity: Activity)  {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
