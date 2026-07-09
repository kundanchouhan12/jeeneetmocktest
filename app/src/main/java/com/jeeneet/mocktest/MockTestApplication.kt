package com.jeeneet.mocktest

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.admob.AppOpenAdManager
import com.jeeneet.mocktest.data.repository.QuestionSyncWorker
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MockTestApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ── Synchronous (must happen before first Activity) ──────────────
        // Apply dark-mode preference (0=system, 1=light, 2=dark — default is dark)
        val nightMode = when (PrefManager.getDarkModePreference(this)) {
            1    -> AppCompatDelegate.MODE_NIGHT_NO
            2    -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // Detect app upgrades and reset version-sensitive cached state so the new version
        // doesn't inherit stale data that can cause crashes or stuck force-update loops.
        val storedVersion = PrefManager.getStoredVersionCode(this)
        val currentVersion = BuildConfig.VERSION_CODE
        if (storedVersion != 0 && storedVersion != currentVersion) {
            // Upgraded: force a fresh question-bank sync so the new version pulls latest data
            PrefManager.setLastSyncedVersion(this, 0)
            // Clear any paused test session — the serialized format may not match the new model
            PrefManager.clearSavedTestSession(this)
        }
        if (storedVersion != currentVersion) {
            PrefManager.setStoredVersionCode(this, currentVersion)
        }

        try {
            FirebaseApp.initializeApp(this)
            val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            com.google.firebase.firestore.FirebaseFirestore.getInstance().firestoreSettings = settings
        } catch (e: Exception) {
            android.util.Log.e("MockTestApp", "Firebase init failed: ${e.message}")
        }

        // Notification channels must exist before any notif is posted — fast call
        NotificationHelper.createChannels(this)

        // Must register here (synchronously, before onCreate() returns) so its
        // ActivityLifecycleCallbacks/ProcessLifecycleObserver catch every Activity from the
        // very first one — registering later (e.g. inside an async callback below) would miss
        // early lifecycle events and break currentActivity tracking.
        val appOpenAdManager = try {
            AppOpenAdManager(this)
        } catch (e: Exception) {
            android.util.Log.e("MockTestApp", "AppOpenAdManager init failed: ${e.message}")
            null
        }

        // AdManager must init before any Activity calls loadInterstitial/loadRewarded
        try {
            AdManager.init(this) {
                // Preload native ad early so the home screen renders it instantly
                AdManager.preloadNativeAd(this)
                appOpenAdManager?.loadAd()
            }
        } catch (e: Exception) {
            android.util.Log.e("MockTestApp", "AdManager init failed: ${e.message}")
        }

        // WorkManager scheduling is heavy on first call (SQLite init) — move off critical path
        appScope.launch {
            try {
                NotificationHelper.scheduleDailyReminder(this@MockTestApplication)
                NotificationHelper.scheduleEveningReminder(this@MockTestApplication)
                NotificationHelper.scheduleDailyVaultReminder(this@MockTestApplication)
            } catch (e: Exception) {
                android.util.Log.e("MockTestApp", "Notification scheduling failed: ${e.message}")
            }

            try {
                QuestionSyncWorker.schedule(this@MockTestApplication)
            } catch (e: Exception) {
                android.util.Log.e("MockTestApp", "SyncWorker schedule failed: ${e.message}")
            }
        }
    }
}
