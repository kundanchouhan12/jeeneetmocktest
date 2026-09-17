package com.jeeneet.mocktest

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.admob.IAPManager
import com.jeeneet.mocktest.data.model.SavedTestSession
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.data.repository.QuestionSyncManager
import com.jeeneet.mocktest.ui.power100.Power100Activity
import com.jeeneet.mocktest.ui.doubts.AISolutionActivity
import com.jeeneet.mocktest.ui.doubts.ScanActivity
import com.jeeneet.mocktest.ui.home.ChapterwiseListActivity
import com.jeeneet.mocktest.ui.home.MockTestListActivity
import com.jeeneet.mocktest.ui.leaderboard.LeaderboardActivity
import com.jeeneet.mocktest.ui.streak.StreakCalendarActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager
import com.jeeneet.mocktest.services.NotificationRouter
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        var requiresHomeRefresh = true
    }

    private lateinit var iapManager: IAPManager
    private lateinit var contentLayout: LinearLayout
    private lateinit var mainScrollView: ScrollView
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private var syncOverlay: View? = null
    private lateinit var navAvatarTv: TextView
    private lateinit var navNameTv: TextView
    private lateinit var tvCoinsBalance: TextView
    private lateinit var examTabJEE: TextView
    private lateinit var examTabNEET: TextView
    private lateinit var tvExamSubtitle: TextView
    private var headerLevelTitle = ""
    private var selectedExam = "JEE"
    private var fomoCount = 0
    private var hasSimulationCompleted = false

    private var lastContentBuildTime = 0L
    private var cachedTestResults: List<com.jeeneet.mocktest.data.model.TestResult>? = null
    private val CONTENT_CACHE_TTL = 60_000L
    private var selectedPracticeTab = 0
    private var hasCompletedInitialSync = false

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) showUpdateReadyBanner()
    }
    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* result ignored — flexible: user can dismiss; immediate: system handles retry */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                NotificationHelper.scheduleDailyReminder(this)
                NotificationHelper.scheduleEveningReminder(this)
                NotificationHelper.scheduleDailyVaultReminder(this)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to schedule reminders: ${e.message}")
            }
        }
    }

    // Persistent native ad container — created once, ad loaded/refreshed via loadNativeAd()
    private val nativeAdContainer by lazy {
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requiresHomeRefresh = true

        // Status bar restored per user request

        val user = try {
            val auth = FirebaseAuth.getInstance()
            val u = auth.currentUser
            if (u == null || !u.isEmailVerified) {
                if (u != null && !u.isEmailVerified) {
                    Toast.makeText(this, "Please verify your email", Toast.LENGTH_SHORT).show()
                    auth.signOut()
                }
                startActivity(Intent(this, com.jeeneet.mocktest.ui.auth.LoginActivity::class.java))
                finish()
                return
            }
            u
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "FirebaseAuth failed: ${e.message}")
            startActivity(Intent(this, com.jeeneet.mocktest.ui.auth.LoginActivity::class.java))
            finish()
            return
        }

        PrefManager.syncIAPUserIfNeeded(this, user.uid)
        fomoCount = PrefManager.getCachedDailyAttempts(this)

        // Initial permission check logic moved to onResume for better lifecycle handling

        val repo = com.jeeneet.mocktest.data.repository.MockTestRepository(this)
        PrefManager.applyAndClearOnboardingPending(this)
        selectedExam = PrefManager.getSelectedExam(this)

        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    MockTestDatabase.getInstance(this@MainActivity).questionDao().getTotalCount()
                }
                if (PrefManager.isFirstLaunch(this@MainActivity) || count == 0) {
                    repo.seedSampleQuestions()
                    PrefManager.setFirstLaunchDone(this@MainActivity)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to seed questions: ${e.message}")
            }
        }

        val hasAnyAccess = PrefManager.isAllAccessUnlocked(this) ||
                com.jeeneet.mocktest.data.model.IAPProducts.ALL_PACKS.any { PrefManager.isPackUnlocked(this, it) }
        if (hasAnyAccess) {
            lifecycleScope.launch {
                try {
                    QuestionSyncManager(this@MainActivity).checkAndSyncIfNeeded()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Question sync failed: ${e.message}")
                }
            }
        }

        try { AdManager.loadInterstitial(this) } catch (e: Exception) {
            android.util.Log.e("MainActivity", "loadInterstitial failed: ${e.message}")
        }
        try { AdManager.loadRewarded(this) } catch (e: Exception) {
            android.util.Log.e("MainActivity", "loadRewarded failed: ${e.message}")
        }
        try {
            checkFirestoreForForceUpdate()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to check for updates: ${e.message}")
        }

        try {
            iapManager = IAPManager(this)
            iapManager.onPurchaseSuccess = {
                if (::contentLayout.isInitialized) runOnUiThread { buildContent() }
            }
            // Run Firestore sync only after Play restore completes so revocations from Play
            // (e.g. refunded or expired test purchases) take precedence over stale Firestore docs.
            iapManager.onRestoreComplete = {
                if (!hasCompletedInitialSync) {
                hasCompletedInitialSync = true
                runOnUiThread {
                    try {
                        if (::iapManager.isInitialized) {
                            iapManager.syncPurchasesFromFirestore {
                                com.jeeneet.mocktest.data.repository.ScanRepository(this@MainActivity).syncWithCloud {
                                    runOnUiThread {
                                        try {
                                            if (requiresHomeRefresh && ::contentLayout.isInitialized) buildContent()
                                            val streak = PrefManager.getStreak(this@MainActivity)
                                            com.jeeneet.mocktest.data.repository.AchievementManager.checkStreak(this@MainActivity, streak)
                                            syncFcmToken()
                                            lifecycleScope.launch {
                                                QuestionSyncManager(this@MainActivity).syncDailyVault()
                                                // Rebuild the vault card so "Syncing…" becomes "Questions Ready"
                                                runOnUiThread {
                                                    if (::contentLayout.isInitialized) buildContent()
                                                }
                                            }
                                            NotificationRouter.handle(this@MainActivity, intent)
                                        } catch (e: Exception) {
                                            android.util.Log.e("MainActivity", "Post-sync UI update failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Firestore sync failed: ${e.message}")
                    }
                }
                } // end if (!hasCompletedInitialSync)
            }
            iapManager.connect()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "IAPManager init failed: ${e.message}")
        }

        setContentView(buildLayoutWithDrawer())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationRouter.handle(this, intent)
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }


    private fun syncFcmToken() {
        // FCM tokens change rarely — skip if already synced today
        val prefs = getSharedPreferences("fcm_prefs", MODE_PRIVATE)
        val today = System.currentTimeMillis() / 86_400_000
        if (prefs.getLong("last_fcm_sync_day", -1L) == today) return
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            FirebaseMessaging.getInstance().subscribeToTopic("all_users")
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token != null) {
                    FirebaseFirestore.getInstance().collection("users")
                        .document(uid)
                        .update("fcmTokens", FieldValue.arrayUnion(token))
                        .addOnSuccessListener { prefs.edit().putLong("last_fcm_sync_day", today).apply() }
                        .addOnFailureListener {
                            // Fallback if document doesn't exist yet
                            FirebaseFirestore.getInstance().collection("users")
                                .document(uid)
                                .set(mapOf("fcmTokens" to listOf(token)), com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener { prefs.edit().putLong("last_fcm_sync_day", today).apply() }
                        }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "FCM token sync failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()

        // Free users require internet; premium users can use offline cache
        val isOnline = com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)
        if (!isOnline && !PrefManager.isEffectivelyPremium(this)) {
            showOfflineGate()
            return
        }
        hideOfflineGate()

        fomoCount = PrefManager.getCachedDailyAttempts(this)
        if (requiresHomeRefresh) {
            requiresHomeRefresh = false
            if (::contentLayout.isInitialized) buildContent()
        }
        refreshNavHeader()
        fetchFomoCount()
        checkNotificationPermissionFlow()
        checkStreakFreeze()
        try {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                when {
                    // Immediate update was interrupted (user switched apps) — force it to resume
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                        appUpdateManager.startUpdateFlowForResult(
                            info, updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                        )
                    }
                    // Flexible update finished downloading while app was in background
                    info.installStatus() == InstallStatus.DOWNLOADED -> showUpdateReadyBanner()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Update check failed in onResume: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { AdManager.destroyNativeAd(nativeAdContainer) } catch (_: Exception) {}
        try { AdManager.cleanup() } catch (_: Exception) {}
        try { if (::iapManager.isInitialized) iapManager.disconnect() } catch (_: Exception) {}
        try { appUpdateManager.unregisterListener(installStateListener) } catch (_: Exception) {}
    }

    private fun refreshNavHeader() {
        if (!::navNameTv.isInitialized) return
        val displayName = try {
            FirebaseAuth.getInstance().currentUser?.displayName?.ifEmpty { "Aspirant" } ?: "Aspirant"
        } catch (e: Exception) {
            "Aspirant"
        }
        navNameTv.text = displayName
        navAvatarTv.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    }

    // ─── In-App Update ────────────────────────────────────────────────────────

    private fun checkFirestoreForForceUpdate() {
        try {
            val db = FirebaseFirestore.getInstance()
            // Source.SERVER bypasses the offline cache so a freshly-updated app doesn't
            // keep seeing a stale forceUpdateEnabled=true document and loop forever.
            db.collection("app_config").document("version_control")
                .get(Source.SERVER)
                .addOnSuccessListener { document ->
                    if (isDestroyed || isFinishing) return@addOnSuccessListener
                    if (document != null && document.exists()) {
                        val minRequiredVersion = document.getLong("minRequiredVersionCode") ?: 0L
                        val forceUpdate = document.getBoolean("forceUpdateEnabled") ?: false
                        val currentVersion = com.jeeneet.mocktest.BuildConfig.VERSION_CODE.toLong()

                        if (currentVersion < minRequiredVersion && forceUpdate) {
                            showForceUpdateDialog()
                        } else {
                            checkForUpdate()
                        }
                    } else {
                        checkForUpdate()
                    }
                }
                .addOnFailureListener {
                    // Network unavailable — skip force-update check and try Play update normally
                    if (!isDestroyed && !isFinishing) checkForUpdate()
                }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Firestore force update check failed: ${e.message}")
            checkForUpdate()
        }
    }

    private fun showForceUpdateDialog() {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🚨 Critical Update Required")
                .setMessage("A new, improved version of the app is available with critical fixes and exciting new features. Please update to continue using the app.")
                .setCancelable(false)
                .setPositiveButton("Update Now") { _, _ ->
                    PrefManager.openPlayStore(this)
                    // Kill the process immediately so no pending Firebase callbacks can
                    // fire on a destroyed Activity context after finishAffinity().
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .setNegativeButton("Exit") { _, _ ->
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .show()
        }
    }

    private fun checkForUpdate() {
        appUpdateManager.registerListener(installStateListener)
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                android.util.Log.d("UpdateCheck", "Availability: ${info.updateAvailability()}, Priority: ${info.updatePriority()}")
                when {
                    // Priority 4-5: force an immediate (blocking) update — can't be skipped
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                            && info.updatePriority() >= 4
                            && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> {
                        android.util.Log.d("UpdateCheck", "Starting IMMEDIATE update")
                        appUpdateManager.startUpdateFlowForResult(
                            info, updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                        )
                    }
                    // Normal update: background download, user prompted to restart when ready
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                            && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                        android.util.Log.d("UpdateCheck", "Starting FLEXIBLE update")
                        appUpdateManager.startUpdateFlowForResult(
                            info, updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                        )
                    }
                    else -> {
                        android.util.Log.d("UpdateCheck", "No update needed or conditions not met")
                    }
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.w("MainActivity", "Update check failed: ${e.message}")
            }
    }

    private fun showUpdateReadyBanner() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val banner = LinearLayout(this).apply {
            id = android.R.id.background  // reuse stable ID to avoid duplicate banners
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(goldPrimary)
            setPadding(Space.XL.dp, 14.dp, Space.M.dp, 14.dp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        banner.addView(uiTextView(UiText.BODY, "Update ready to install", Color.parseColor("#1A1A1A")).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        banner.addView(uiTextView(UiText.LABEL, "RESTART", Color.parseColor("#1A1A1A")).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(Space.L.dp, Space.S.dp, Space.S.dp, Space.S.dp)
            setOnClickListener { appUpdateManager.completeUpdate() }
        })

        root.findViewById<View>(android.R.id.background)?.let { root.removeView(it) }
        (root as? FrameLayout)?.addView(banner)
    }

    // ─── Purchase sync overlay ────────────────────────────────────────────────

    private fun showSyncOverlay() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(bgPrimary)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        inner.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).also {
                it.bottomMargin = Space.L.dp
            }
            indeterminateTintList = ColorStateList.valueOf(colorPrimary)
        })
        inner.addView(uiTextView(UiText.BODY, "Restoring your account…", textSecondary, Gravity.CENTER))
        overlay.addView(inner)
        root.addView(overlay)
        syncOverlay = overlay
    }

    private fun hideSyncOverlay() {
        (syncOverlay?.parent as? ViewGroup)?.removeView(syncOverlay)
        syncOverlay = null
    }

    // ─── Offline gate (free users) ────────────────────────────────────────────

    private var offlineGateView: View? = null

    private fun showOfflineGate() {
        if (offlineGateView != null) return
        val root = findViewById<FrameLayout>(android.R.id.content)
        val gate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgPrimary)
            setPadding(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        gate.addView(android.widget.TextView(this).apply {
            text = "📡"; textSize = 64f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Space.L.dp }
        })
        gate.addView(uiTextView(UiText.H2, "No Internet Connection", textPrimary, Gravity.CENTER))
        gate.addView(uiTextView(UiText.BODY,
            "Connect to the internet to continue.",
            textTertiary, Gravity.CENTER).apply {
            setPadding(0, Space.S.dp, 0, Space.XL.dp)
        })
        gate.addView(uiPrimaryButton("Retry", heightDp = 48) {
            if (com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this@MainActivity)) {
                hideOfflineGate()
                requiresHomeRefresh = true
                buildContent()
            } else {
                Toast.makeText(this@MainActivity, "Still no internet. Check your connection.", Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp) })
        root.addView(gate)
        offlineGateView = gate
    }

    private fun hideOfflineGate() {
        offlineGateView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        offlineGateView = null
    }

    // ─── Drawer ───────────────────────────────────────────────────────────────

    private fun buildLayoutWithDrawer(): View {
        drawerLayout = androidx.drawerlayout.widget.DrawerLayout(this).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        drawerLayout.addView(buildMainLayout())
        drawerLayout.addView(buildNavigationView())
        return drawerLayout
    }

    private fun buildNavigationView(): View {
        val user   = FirebaseAuth.getInstance().currentUser
        val name   = user?.displayName?.ifEmpty { "Aspirant" } ?: "Aspirant"
        val email  = user?.email ?: ""
        val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        val coins  = PrefManager.getCoins(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
            layoutParams = androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                300.dp, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.START }
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
        }
        scroll.addView(container)

        // ── Profile header ────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.XL.dp, 56.dp, Space.XL.dp, Space.L.dp)
        }

        // Avatar + close row
        val avatarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }
        navAvatarTv = TextView(this).apply {
            text = initial; textSize = 26f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorPrimary) }
            layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp)
        }
        avatarRow.addView(navAvatarTv)
        avatarRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        avatarRow.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(textMuted)
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
            setOnClickListener { drawerLayout.closeDrawers() }
        })
        header.addView(avatarRow)

        navNameTv = uiTextView(UiText.H2, name, textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        header.addView(navNameTv)
        header.addView(uiTextView(UiText.CAPTION, email, textMuted).apply {
            setPadding(0, Space.XS.dp, 0, Space.M.dp)
        })

        // Coins + dark mode row
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Space.S.dp }
        }
        bottomRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedFill(Color.argb(15, Color.red(goldPrimary), Color.green(goldPrimary), Color.blue(goldPrimary)), Corner.PILL)
            setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            addView(uiTextView(UiText.H3, "🪙 $coins", goldDark).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = 14f
            })
        })
        bottomRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        // Dark mode toggle
        val isDark = PrefManager.getDarkModePreference(this) == 2
        bottomRow.addView(uiTextView(UiText.CAPTION, "🌙", textSecondary).apply {
            textSize = 16f
            setPadding(0, 0, Space.XS.dp, 0)
        })
        bottomRow.addView(android.widget.Switch(this).apply {
            isChecked = isDark
            thumbTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
            trackTintList = android.content.res.ColorStateList.valueOf(
                Color.argb(80, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary))
            )
            setOnCheckedChangeListener { _, checked ->
                PrefManager.setDarkModePreference(this@MainActivity, if (checked) 2 else 1)
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (checked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        })
        header.addView(bottomRow)

        container.addView(header)
        container.addView(uiDivider())

        // ── Nav items ─────────────────────────────────────────────────────────
        data class NavEntry(val icon: String, val label: String, val color: Int, val action: () -> Unit)

        val close = { drawerLayout.closeDrawers() }
        val navItems = listOf(
            NavEntry("🏠", "Home",              Color.parseColor("#6366F1")) { close() },
            NavEntry("🔖", "Bookmarks",         Color.parseColor("#8B5CF6")) { com.jeeneet.mocktest.ui.bookmarks.BookmarksActivity.start(this); close() },
            NavEntry("📓", "My Notes",          Color.parseColor("#3B82F6")) { com.jeeneet.mocktest.ui.notes.NotesActivity.start(this); close() },
            NavEntry("📋", "Prev Tests",        Color.parseColor("#6366F1")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "mock_test", selectedExam); close() },
            NavEntry("🔬", "Scan Doubts",       Color.parseColor("#10B981")) { requiresHomeRefresh = true; com.jeeneet.mocktest.ui.doubts.ScanActivity.start(this); close() },
            NavEntry("🏆", "Leaderboard",       Color.parseColor("#F59E0B")) { LeaderboardActivity.start(this); close() },
            NavEntry("🏅", "Achievements",      Color.parseColor("#F59E0B")) { requiresHomeRefresh = true; com.jeeneet.mocktest.ui.achievements.AchievementsActivity.start(this); close() },
            NavEntry("👤", "Profile",           Color.parseColor("#64748B")) { requiresHomeRefresh = true; startActivity(Intent(this, com.jeeneet.mocktest.ui.profile.ProfileActivity::class.java)); close() },
            NavEntry("👑", "Upgrade Premium",   Color.parseColor("#F59E0B")) { requiresHomeRefresh = true; com.jeeneet.mocktest.ui.home.ShopActivity.start(this); close() },
            NavEntry("📅", "AI Study Plan",     Color.parseColor("#10B981")) { startActivity(Intent(this, com.jeeneet.mocktest.ui.insights.InsightsActivity::class.java)); close() }
        )

        navItems.forEach { entry -> container.addView(buildNavItem(entry.icon, entry.label, entry.color, entry.action)) }

        container.addView(uiDivider(leftDp = Space.XL, rightDp = Space.XL).apply {
            (layoutParams as LinearLayout.LayoutParams).also { it.topMargin = Space.S.dp }
        })

        // Logout
        container.addView(buildNavItem("🚪", "Logout", Color.parseColor("#EF4444")) {
            drawerLayout.closeDrawers()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Logout") { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(this, com.jeeneet.mocktest.ui.auth.LoginActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null).show()
        })

        root.addView(scroll)

        // App version footer
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgTertiary)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            addView(uiTextView(UiText.CAPTION, "v${com.jeeneet.mocktest.BuildConfig.VERSION_NAME}", textMuted).apply {
                textSize = 11f
            })
        })
        return root
    }

    private fun buildNavItem(icon: String, label: String, color: Int, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.XL.dp, Space.M.dp, Space.XL.dp, Space.M.dp)
            isClickable = true; isFocusable = true
            val tv = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0)
                foreground = ContextCompat.getDrawable(context, tv.resourceId)
            setOnClickListener { action() }

            // Colored icon box
            val iconBg = Color.argb(55, Color.red(color), Color.green(color), Color.blue(color))
            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp).also { it.marginEnd = Space.M.dp }
                background = GradientDrawable().apply { cornerRadius = Corner.S.dpF; setColor(iconBg) }
                addView(TextView(this@MainActivity).apply {
                    text = icon; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                })
            })

            addView(uiTextView(UiText.BODY, label, textPrimary).apply {
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
        }

    // ─── Main layout ──────────────────────────────────────────────────────────

    private fun buildMainLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(buildGreetingHeader())

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }
        mainScrollView = scroll
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, 64.dp) // extra bottom for nav bar
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(contentLayout)
        frame.addView(scroll)

        root.addView(frame)
        root.addView(buildBottomNavBar())
        return root
    }

    private fun buildGreetingHeader(): View {
        // White header — use dark status bar icons
        window.statusBarColor = ContextCompat.getColor(this, R.color.bg_secondary)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val user           = FirebaseAuth.getInstance().currentUser
        val firstName      = user?.displayName?.split(" ")?.firstOrNull()?.ifEmpty { "Aspirant" } ?: "Aspirant"
        val initial        = (user?.displayName?.firstOrNull() ?: 'A').uppercaseChar().toString()
        val streak         = PrefManager.getStreak(this)
        val dailyAttempted = PrefManager.getDailyQuestionsAttempted(this)
        val dailyGoal      = PrefManager.getDailyGoal(this)
        val coins          = PrefManager.getCoins(this)
        val practicedDays  = PrefManager.getTotalPracticedDays(this)
        val levelTitle     = when {
            practicedDays >= 100 -> "Master"
            practicedDays >= 60  -> "Expert"
            practicedDays >= 30  -> "Pro"
            practicedDays >= 15  -> "Challenger"
            practicedDays >= 5   -> "Explorer"
            else                 -> "Beginner"
        }
        headerLevelTitle = levelTitle
        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            elevation = Elev.M.dpF
        }

        // ── Row 1: Menu | Greeting | Coins | Bell | Avatar ───────────────────
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }

        topRow.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            imageTintList = ColorStateList.valueOf(textPrimary)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = Space.S.dp }
            setOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        })

        val greetingCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        greetingCol.addView(uiTextView(UiText.H2, "Hi, $firstName! 👋", textPrimary).apply {
            textSize = 18f
            setOnLongClickListener { showDeveloperAccessDialog(); true }
        })
        tvExamSubtitle = uiTextView(UiText.CAPTION, "$levelTitle · Let's crack $selectedExam!", textTertiary).apply {
            textSize = 11f
            setPadding(0, 2.dp, 0, 0)
        }
        greetingCol.addView(tvExamSubtitle)
        topRow.addView(greetingCol)

        tvCoinsBalance = TextView(this).apply {
            text = "🪙 $coins"
            textSize = 11f; setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(bgTertiary, Corner.PILL)
            setPadding(Space.S.dp, 4.dp, Space.S.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = Space.S.dp }
        }
        topRow.addView(tvCoinsBalance)

        val bellFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp).also { it.marginEnd = Space.S.dp }
            setOnClickListener { if (!hasPermission) showStreakProtectionDialog() }
        }
        bellFrame.addView(TextView(this).apply {
            text = "🔔"; textSize = 16f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })
        if (!hasPermission) {
            bellFrame.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
                layoutParams = FrameLayout.LayoutParams(8.dp, 8.dp).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
            })
        }
        topRow.addView(bellFrame)

        // Avatar circle — tap to open drawer
        topRow.addView(TextView(this).apply {
            text = initial; textSize = 14f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorPrimary) }
            layoutParams = LinearLayout.LayoutParams(38.dp, 38.dp)
            setOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        })
        root.addView(topRow)

        // ── Row 2: Exam toggle pills + stat chips ─────────────────────────────
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // JEE / NEET toggle
        val examBg = Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary))
        val examToggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedFill(bgTertiary, Corner.PILL)
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        fun makeExamTab(exam: String) = TextView(this).apply {
            text = exam; textSize = 11f
            val active = exam == selectedExam
            setTextColor(if (active) colorPrimary else textMuted)
            typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
            background = if (active) roundedFill(examBg, Corner.PILL) else null
            setPadding(Space.M.dp, 4.dp, Space.M.dp, 4.dp)
            setOnClickListener { switchExam(exam) }
        }
        examTabJEE = makeExamTab("JEE")
        examTabNEET = makeExamTab("NEET")
        examToggle.addView(examTabJEE)
        examToggle.addView(examTabNEET)
        statsRow.addView(examToggle)

        statsRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        // Streak chip
        statsRow.addView(buildStatChip("🔥", "$streak day streak",
            Color.parseColor("#FFF3E0"), Color.parseColor("#E65100")) {
            StreakCalendarActivity.start(this)
        })

        // Daily goal chip
        val goalDone = dailyAttempted >= dailyGoal
        statsRow.addView(buildStatChip(
            if (goalDone) "✓" else "🎯",
            if (goalDone) "Goal done!" else "$dailyAttempted/$dailyGoal today",
            if (goalDone) Color.parseColor("#E8F5E9") else Color.parseColor("#EDE7F6"),
            if (goalDone) Color.parseColor("#2E7D32") else Color.parseColor("#4527A0")
        ) { })

        root.addView(statsRow)
        return root
    }

    private fun buildStatChip(icon: String, label: String, bgColor: Int, fgColor: Int, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedFill(bgColor, Corner.PILL)
            setPadding(Space.S.dp, 4.dp, Space.S.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = Space.S.dp }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 12f
                layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = 4.dp }
            })
            addView(uiTextView(UiText.CAPTION, label, fgColor).apply {
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
        }

    // ─── Exam switch (smooth, no recreate) ───────────────────────────────────

    private fun switchExam(exam: String) {
        if (selectedExam == exam) return
        selectedExam = exam
        PrefManager.setSelectedExam(this, selectedExam)
        requiresHomeRefresh = true

        // If this exam has no cached vault (e.g. after reinstall), sync now so the
        // vault card shows questions immediately rather than "unavailable".
        if (PrefManager.getVaultCurrentGroupId(this, exam).isEmpty()) {
            lifecycleScope.launch {
                QuestionSyncManager(this@MainActivity).syncDailyVault()
            }
        }

        // Update header tab pills immediately so the user gets instant visual feedback
        val examBg = Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary))
        if (::examTabJEE.isInitialized && ::examTabNEET.isInitialized) {
            for ((tab, tabExam) in listOf(examTabJEE to "JEE", examTabNEET to "NEET")) {
                val active = tabExam == selectedExam
                tab.setTextColor(if (active) colorPrimary else textMuted)
                tab.typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                tab.background = if (active) roundedFill(examBg, Corner.PILL) else null
            }
        }
        if (::tvExamSubtitle.isInitialized) {
            tvExamSubtitle.text = "$headerLevelTitle · Let's crack $selectedExam!"
        }

        // Fade content out → show skeleton → fade new content in
        if (!::contentLayout.isInitialized) return
        contentLayout.animate().cancel()
        contentLayout.animate().alpha(0f).setDuration(150).withEndAction {
            contentLayout.alpha = 1f
            buildContent()
        }.start()
    }

    // ─── Content (rebuilt on resume / exam switch) ────────────────────────────

    private var isContentBuilding = false

    private fun buildContent() {
        val elapsed = System.currentTimeMillis() - lastContentBuildTime
        if (cachedTestResults != null && elapsed < CONTENT_CACHE_TTL) {
            renderContent()
            return
        }
        if (isContentBuilding) return
        isContentBuilding = true
        showSkeletonContent()
        lifecycleScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
            val db = MockTestDatabase.getInstance(this@MainActivity)
            hasSimulationCompleted = withContext(Dispatchers.IO) {
                db.testResultDao().hasCompletedSimulation(uid).first()
            }
            cachedTestResults = withContext(Dispatchers.IO) {
                db.testResultDao().getAllResultsOnce(uid)
            }
            lastContentBuildTime = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                isContentBuilding = false
                renderContent()
            }
        }
    }

    private fun renderContent() {
        // Only animate from blank on the initial load (skeleton → content).
        // On subsequent re-renders content is already visible, so skip the alpha=0
        // flash — swap views directly to avoid the double-flicker.
        val alreadyShowing = contentLayout.alpha > 0.5f && contentLayout.childCount > 0
        contentLayout.removeAllViews()
        if (!alreadyShowing) contentLayout.alpha = 0f

        if (::tvCoinsBalance.isInitialized) {
            tvCoinsBalance.text = "🪙 ${PrefManager.getCoins(this)}"
        }

        // ─── 1. Resume paused session ─────────────────────────────────────────
        buildMissionHeroCard()?.let { contentLayout.addView(it) }

        // ─── 2. Quick Actions — 4 icon tiles ─────────────────────────────────
        contentLayout.addView(buildQuickActionsGrid())

        // ─── 3. Today's Challenge (Daily Quiz) ───────────────────────────────
        contentLayout.addView(uiSectionLabel("Today's Challenge"))
        contentLayout.addView(buildDailyQuizCard())

        // ─── 5. Practice (Mock Tests + PYQs) ─────────────────────────────────
        contentLayout.addView(uiSectionLabel("Practice"))
        contentLayout.addView(buildMainTestCards())

        // ─── 5b. Special — Power 100 ──────────────────────────────────────────
        contentLayout.addView(uiSectionLabel("Special"))
        contentLayout.addView(buildPower100Card())

        // ─── 6. Daily Vault ───────────────────────────────────────────────────
        contentLayout.addView(uiSectionLabel("Daily Vault"))
        contentLayout.addView(buildDailyVaultCard())

        // ─── 7. Continue Practice (Subjects) ─────────────────────────────────
        contentLayout.addView(uiSectionLabel("Continue Practice"))
        contentLayout.addView(buildSubjectsSection())

        // ─── 8. Your Progress ────────────────────────────────────────────────
        contentLayout.addView(uiSectionLabel("Your Progress"))
        contentLayout.addView(buildYourProgressCard())

        // ─── 9. Weekly Mock + Full Mock Test ─────────────────────────────────
        contentLayout.addView(uiSectionLabel("Full Mock Test"))
        buildWeeklyNewTestBanner()?.let { contentLayout.addView(it) }
        contentLayout.addView(buildSimulationCard())

        // ─── 10. Recent Doubts carousel ───────────────────────────────────────
        buildRecentDoubtsCarousel()?.let { contentLayout.addView(it) }

        // ─── 11. Activity Heatmap ─────────────────────────────────────────────
        contentLayout.addView(buildActivityHeatmap())

        // ─── 12. Native ad ────────────────────────────────────────────────────
        contentLayout.addView(nativeAdContainer)
        AdManager.loadNativeAd(this, nativeAdContainer)

        contentLayout.animate().cancel()
        if (!alreadyShowing) {
            contentLayout.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun buildMainTestCards(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val examLabel = if (selectedExam == "JEE") "JEE" else "NEET"

        // Mock Tests Card - Primary CTA (Glows Heavily)
        val mockCard = uiCard(
            radius = Corner.L,
            elevation = Elev.L, // Higher elevation for glow
            strokeDp = 2,
            strokeColor = ContextCompat.getColor(this@MainActivity, R.color.color_primary),
            onClick = {
                requiresHomeRefresh = true
                MockTestListActivity.start(this@MainActivity, "mock_test", selectedExam)
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Space.M.dp }
        }

        val mockInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    ContextCompat.getColor(this@MainActivity, R.color.card_mock_test_start),
                    ContextCompat.getColor(this@MainActivity, R.color.card_mock_test_end)
                )
            ).apply { cornerRadius = Corner.L.dpF }
            setPadding(Space.L.dp, 16.dp, Space.L.dp, 16.dp) // richer spacing
        }

        mockInner.addView(uiIconBox(48, Color.parseColor("#33FFFFFF"),
            emoji = "📝", emojiSize = 22f, oval = true).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp
        })

        val mockText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mockText.addView(uiTextView(UiText.H3, "$examLabel Mock Tests", Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        mockText.addView(uiTextView(UiText.CAPTION, "Full syllabus timed tests with real exam pattern & negative marking", Color.parseColor("#EEFFFFFF")).apply {
            setPadding(0, 2.dp, 0, 0)
            textSize = 11f
        })
        mockInner.addView(mockText)

        mockInner.addView(TextView(this).apply {
            text = "›"; textSize = 24f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8.dp, 0, 4.dp, 0)
        })
        mockCard.addView(mockInner)
        container.addView(mockCard)

        // PYQs Card - Secondary Dark Card style with Orange Border Accent (hierarchy-aware)
        val pyqCard = uiCard(
            radius = Corner.L,
            elevation = Elev.M,
            background = bgSecondary,
            strokeDp = 0,
            onClick = {
                requiresHomeRefresh = true
                MockTestListActivity.start(this@MainActivity, "pyqs", selectedExam)
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Space.M.dp }
        }

        val pyqInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, 16.dp, Space.L.dp, 16.dp)
        }

        pyqInner.addView(uiIconBox(48, Color.parseColor("#1AF97316"), // subtle orange icon box
            emoji = "📚", emojiSize = 22f, oval = true).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp
        })

        val pyqText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pyqText.addView(uiTextView(UiText.H3, "$examLabel Past Year Papers", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        pyqText.addView(uiTextView(UiText.CAPTION, "Official $examLabel PYQs with detailed step-by-step solutions", textSecondary).apply {
            setPadding(0, 2.dp, 0, 0)
            textSize = 11f
        })
        pyqInner.addView(pyqText)

        pyqInner.addView(TextView(this).apply {
            text = "›"; textSize = 24f; setTextColor(Color.parseColor("#F97316"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8.dp, 0, 4.dp, 0)
        })
        pyqCard.addView(pyqInner)
        container.addView(pyqCard)


        return container
    }

    private fun buildSimulationCard(): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.M,
            background = bgSecondary,
            strokeDp = 2,
            strokeColor = Color.parseColor("#3B82F6"), // Modern Blue
            onClick = {
                requiresHomeRefresh = true
                startActivity(Intent(this, com.jeeneet.mocktest.ui.simulation.SimulationIntroActivity::class.java))
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(uiTextView(UiText.H2, "Full Length Mock", textPrimary).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(uiTextView(UiText.OVERLINE, "REAL PATTERN", Color.parseColor("#3B82F6")).apply {
            background = roundedFill(Color.argb(30, 59, 130, 246), Corner.S)
            setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        })
        inner.addView(topRow)

        inner.addView(uiTextView(UiText.CAPTION, "Exactly like the real $selectedExam exam. 3 hours, negative marking, and detailed AIR analysis.", textTertiary).apply {
            setPadding(0, Space.S.dp, 0, Space.L.dp)
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(uiTextView(UiText.BUTTON, "⏱ 180 Minutes", textSecondary).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val hasSaved = PrefManager.hasSavedTestSession(this)

        val btnText = when {
            hasSaved -> "Resume Simulation →"
            hasSimulationCompleted -> "Re-attempt Simulation →"
            else -> "Start Simulation →"
        }
        footer.addView(TextView(this).apply {
            text = btnText
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            
            // Beautiful Gradient background: Royal Purple to Indigo/Blue with slight elevation shadow
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#3B82F6"))
            ).apply {
                cornerRadius = Corner.M.dpF
            }
            elevation = Elev.S.dpF
            setPadding(18.dp, 10.dp, 18.dp, 10.dp)
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
                foreground = ContextCompat.getDrawable(context, tv.resourceId)
            }
            setOnClickListener {
                requiresHomeRefresh = true
                if (hasSaved) {
                    com.jeeneet.mocktest.ui.test.TestActivity.resume(this@MainActivity)
                } else {
                    startActivity(Intent(this@MainActivity, com.jeeneet.mocktest.ui.simulation.SimulationIntroActivity::class.java))
                }
            }
        })
        inner.addView(footer)

        card.addView(inner)
        return card
    }

    private fun buildPower100Card(): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.L,
            background = bgSecondary,
            strokeDp = 2,
            strokeColor = Color.parseColor("#F59E0B"),
            onClick = {
                requiresHomeRefresh = true
                Power100Activity.start(this, selectedExam)
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#92400E"), Color.parseColor("#B45309"))
            ).apply { cornerRadius = Corner.L.dpF }
            setPadding(Space.L.dp, 18.dp, Space.L.dp, 18.dp)
        }

        // Lightning icon box
        inner.addView(uiIconBox(52, Color.parseColor("#33FFFFFF"), emoji = "⚡", emojiSize = 24f, oval = true).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = Space.M.dp
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(uiTextView(UiText.H3, "Power 100", Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Space.S.dp }
        })
        titleRow.addView(TextView(this).apply {
            text = "NEW"; textSize = 9f; setTextColor(Color.parseColor("#92400E"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(goldPrimary, Corner.PILL)
            setPadding(Space.S.dp, 2.dp, Space.S.dp, 2.dp)
        })
        textCol.addView(titleRow)
        textCol.addView(uiTextView(UiText.CAPTION, "Fixed set of 100 must-know $selectedExam questions. Track your progress over time.", Color.parseColor("#FDE68A")).apply {
            textSize = 11f; setPadding(0, 4.dp, 0, 0)
        })
        inner.addView(textCol)

        inner.addView(TextView(this).apply {
            text = "›"; textSize = 24f; setTextColor(Color.parseColor("#FDE68A"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Space.S.dp, 0, 0, 0)
        })
        card.addView(inner)
        return card
    }

    private fun buildTodayProgressCard(): View {
        val card = uiCard(radius = Corner.L, background = bgSecondary, elevation = Elev.S).apply {
            layoutParams = lpRow(topDp = Space.S, bottomDp = Space.M)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        inner.addView(uiTextView(UiText.OVERLINE, "TODAY'S PROGRESS", textTertiary).apply {
            setPadding(0, 0, 0, Space.S.dp)
        })

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addStat(icon: String, label: String, current: Int, max: Int) {
            val stat = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            stat.addView(uiTextView(UiText.H2, "$current/$max", textPrimary))
            stat.addView(uiTextView(UiText.CAPTION, "$icon $label", textSecondary))
            statsRow.addView(stat)
        }

        val solved = PrefManager.getTotalQuestionsSolvedToday(this)
        val mocks = PrefManager.getAdUsageCount(this, "extra_mock")
        val hints = PrefManager.getAdUsageCount(this, "hint")

        addStat("📝", "Solved", solved, 50)
        addStat("📑", "Mocks", mocks, PrefManager.getMaxExtraMocks())
        addStat("💡", "Hints", hints, 10)

        inner.addView(statsRow)
        
        inner.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = lpRow(topDp = Space.M)
            max = 50
            progress = solved.coerceAtMost(50)
            progressTintList = ColorStateList.valueOf(if (solved >= 50) Color.parseColor("#22C55E") else colorPrimary)
        })

        card.addView(inner)
        return card
    }

    private fun checkStreakFreeze() {
        if (PrefManager.isStreakBroken(this)) {
            val streak = PrefManager.getStreak(this)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Streak Broken! 💔")
                .setMessage("You were on a $streak-day streak! Watch a short video to freeze your streak and keep it alive.")
                .setPositiveButton("Freeze Streak (Watch Ad)") { _, _ ->
                    AdManager.showRewarded(this, onRewarded = {
                        AnalyticsManager.streakFreezeWatched(this)
                        val todayMs = System.currentTimeMillis()
                        getSharedPreferences("mock_test_prefs", MODE_PRIVATE).edit()
                            .putLong("streak_last_update_${PrefManager.uid()}", todayMs)
                            .apply()
                        Toast.makeText(this, "Streak Restored! 🔥", Toast.LENGTH_SHORT).show()
                        requiresHomeRefresh = true
                        buildContent()
                    }, placement = "streak_freeze")
                }
                .setNegativeButton("Let it go", null)
                .show()
        }
    }

    // ─── Today section header (vault + challenge chips) ──────────────────────

    private fun buildTodaySectionHeader(): View {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val isVaultDone = PrefManager.isDailyVaultDoneToday(this, selectedExam)
        val isChallengeDone = PrefManager.isDailyQuizDoneToday(this)

        val now = java.util.Calendar.getInstance()
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 24)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        val diffMs = midnight.timeInMillis - now.timeInMillis
        val hLeft = diffMs / 3_600_000
        val mLeft = (diffMs % 3_600_000) / 60_000

        fun chip(emoji: String, label: String, sub: String, isDone: Boolean, action: () -> Unit): View {
            val fgColor = if (isDone) Color.parseColor("#10B981") else goldPrimary
            val bgColor = if (isDone) Color.parseColor("#1A10B981") else Color.parseColor("#1AF59E0B")
            val card = uiCard(
                radius = Corner.L, elevation = if (isDone) Elev.NONE else Elev.S,
                background = bgSecondary,
                strokeDp = 2, strokeColor = fgColor,
                onClick = action
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(Space.M.dp, Space.L.dp, Space.M.dp, Space.L.dp)
            }
            inner.addView(android.widget.TextView(this).apply {
                text = if (isDone) "✅" else emoji
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dp }
            })
            inner.addView(uiTextView(UiText.BODY, label, if (isDone) Color.parseColor("#10B981") else textPrimary, Gravity.CENTER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = 13f
            })
            inner.addView(uiTextView(UiText.CAPTION, sub, if (isDone) Color.parseColor("#10B981") else fgColor, Gravity.CENTER).apply {
                textSize = 11f
                setPadding(0, 2.dp, 0, 0)
            })
            card.addView(inner)
            return card
        }

        val vaultSub = if (isVaultDone) "Done ✓" else "${hLeft}h ${mLeft}m left · +50 🪙"
        val challengeSub = if (isChallengeDone) "Done ✓" else
            (if (fomoCount > 0) "${"%,d".format(fomoCount)} active" else "+15 🪙")

        val vaultAction: () -> Unit = {
            AnalyticsManager.vaultOpened(this@MainActivity)
            lifecycleScope.launch {
                if (isVaultDone) {
                    val lastQs = PrefManager.getLastDailyVaultQuestionsJson(this@MainActivity, selectedExam)
                    if (lastQs != null) {
                        withContext(Dispatchers.Main) {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("Daily Vault Completed!")
                                .setMessage("You've already completed today's vault.\nStart Revision Mode for extra practice!")
                                .setPositiveButton("Revision Mode") { _, _ ->
                                    TestActivity.startRevision(this@MainActivity, selectedExam, lastQs)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        return@launch
                    }
                }
                val vaultQs = withContext(Dispatchers.IO) {
                    val chipGroupId = PrefManager.getVaultCurrentGroupId(this@MainActivity, selectedExam)
                    if (chipGroupId.isNotEmpty()) {
                        MockTestDatabase.getInstance(this@MainActivity).questionDao()
                            .getVaultQuestionsByGroupId(chipGroupId)
                            .filter { it.examType == selectedExam }
                    } else {
                        MockTestDatabase.getInstance(this@MainActivity).questionDao()
                            .getDailyVaultQuestions(selectedExam, today)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (vaultQs.isNotEmpty()) {
                        val config = com.jeeneet.mocktest.data.model.ExamConfig(
                            examType = selectedExam, subject = null, chapter = "Daily Vault",
                            totalQuestions = vaultQs.size, durationMinutes = vaultQs.size,
                            correctMarks = 4f, negativeMarks = -1f, isDailyVault = true,
                            vaultGroupId = vaultQs.firstOrNull()?.vaultGroupId ?: ""
                        )
                        AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) {
                            TestActivity.startWithQuestions(this@MainActivity, config, vaultQs)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Daily Vault is updating. Check back in a few minutes!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val challengeAction: () -> Unit = if (isChallengeDone) ({
            Toast.makeText(this, "Challenge already completed today!", Toast.LENGTH_SHORT).show()
        }) else ({
            requiresHomeRefresh = true
            AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) {
                TestActivity.startDailyQuiz(this@MainActivity, selectedExam)
            }
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lpRow(topDp = Space.S, bottomDp = Space.M)
        }
        row.addView(chip("🔓", "Daily Vault", vaultSub, isVaultDone, vaultAction).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = Space.S.dp
        })
        row.addView(chip("🔥", "Challenge", challengeSub, isChallengeDone, challengeAction))
        return row
    }

    // ─── Hero Card Builders ───────────────────────────────────────────────────


    private fun buildDailyVaultCard(): View {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = sdf.format(java.util.Date())
        val isDone = PrefManager.isDailyVaultDoneToday(this, selectedExam)

        val savedGroupId = PrefManager.getVaultCurrentGroupId(this, selectedExam)
        val nextRefreshDate = PrefManager.getVaultNextRefreshDate(this, selectedExam)
        val snapshotDate = PrefManager.getVaultSnapshotDate(this, selectedExam)

        // Days until next refresh
        val daysUntilRefresh = if (nextRefreshDate.isNotEmpty()) {
            try {
                val next = sdf.parse(nextRefreshDate) ?: java.util.Date()
                ((next.time - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0)
            } catch (_: Exception) { 0 }
        } else 0

        val hasQuestions = savedGroupId.isNotEmpty()
        val isAwaitingFirstSync = !hasQuestions

        // Time until midnight (for same-day expiry)
        val now = java.util.Calendar.getInstance()
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 24); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }
        val diffMs = midnight.timeInMillis - now.timeInMillis
        val hours = diffMs / 3_600_000
        val minutes = (diffMs % 3_600_000) / 60_000

        val refreshLabel = when {
            isAwaitingFirstSync -> "Syncing vault questions…"
            daysUntilRefresh == 0 -> "Vault refresh available today"
            daysUntilRefresh == 1 -> "Next vault refresh tomorrow"
            else -> "Next vault refresh in $daysUntilRefresh days"
        }

        val snapshotLabel = if (snapshotDate.isNotEmpty() && snapshotDate != today) {
            val parts = snapshotDate.split("-")
            if (parts.size == 3) "Questions from ${parts[2]} ${monthAbbrev(parts[1].toIntOrNull() ?: 1)}" else ""
        } else ""

        val accentColor = when {
            isDone -> Color.parseColor("#10B981")
            isAwaitingFirstSync -> Color.parseColor("#64748B")
            else -> goldPrimary
        }

        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.M,
            background = bgSecondary,
            strokeDp = 2,
            strokeColor = accentColor,
            onClick = {
                AnalyticsManager.vaultOpened(this@MainActivity)
                val startVaultAction = {
                    lifecycleScope.launch {
                        if (isDone) {
                            val lastQs = PrefManager.getLastDailyVaultQuestionsJson(this@MainActivity, selectedExam)
                            if (lastQs != null) {
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("Daily Vault Completed!")
                                    .setMessage("You have already completed this Vault.\n\nRevision Mode lets you re-attempt these questions for practice. Your next fresh vault arrives in $daysUntilRefresh day${if (daysUntilRefresh != 1) "s" else ""}.")
                                    .setPositiveButton("Start Revision") { _, _ ->
                                        TestActivity.startRevision(this@MainActivity, selectedExam, lastQs)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                                return@launch
                            }
                        }

                        // Load by group ID first (persistent), fall back to today's date
                        val vaultQs = withContext(Dispatchers.IO) {
                            if (savedGroupId.isNotEmpty()) {
                                MockTestDatabase.getInstance(this@MainActivity).questionDao()
                                    .getVaultQuestionsByGroupId(savedGroupId)
                                    .filter { it.examType == selectedExam }
                            } else {
                                MockTestDatabase.getInstance(this@MainActivity).questionDao()
                                    .getDailyVaultQuestions(selectedExam, today)
                            }
                        }

                        if (vaultQs.isNotEmpty()) {
                            val config = com.jeeneet.mocktest.data.model.ExamConfig(
                                examType = selectedExam, subject = null, chapter = "Daily Vault",
                                totalQuestions = vaultQs.size, durationMinutes = vaultQs.size,
                                correctMarks = 4f, negativeMarks = -1f,
                                isDailyVault = true,
                                vaultGroupId = vaultQs.firstOrNull()?.vaultGroupId ?: ""
                            )
                            TestActivity.startWithQuestions(this@MainActivity, config, vaultQs)
                        } else {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("Vault Questions Unavailable")
                                .setMessage("Your vault questions are being prepared. Please check back in a few minutes — we'll notify you when they're ready!")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }

                AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) { startVaultAction() }
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, 20.dp, Space.L.dp, 20.dp)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleText = when {
            isDone -> "Vault Completed ✅"
            isAwaitingFirstSync -> "Daily Vault"
            else -> "Daily Vault — Questions Ready"
        }
        textCol.addView(uiTextView(UiText.H2, titleText, textPrimary))

        if (isDone) {
            textCol.addView(uiTextView(UiText.CAPTION, "🎉 Revision Mode Active", Color.parseColor("#10B981")).apply {
                setPadding(0, 4.dp, 0, 0)
            })
            textCol.addView(uiTextView(UiText.CAPTION, "🔄 $refreshLabel", textTertiary).apply {
                setPadding(0, 2.dp, 0, 0)
            })
        } else if (isAwaitingFirstSync) {
            textCol.addView(uiTextView(UiText.CAPTION, "⏳ $refreshLabel", textTertiary).apply {
                setPadding(0, 4.dp, 0, 0)
            })
        } else {
            if (snapshotLabel.isNotEmpty()) {
                textCol.addView(uiTextView(UiText.CAPTION, "📅 $snapshotLabel", textTertiary).apply {
                    setPadding(0, 4.dp, 0, 0)
                })
            }
            textCol.addView(uiTextView(UiText.CAPTION, "💰 +50 Coins Reward • 30 Curated PYQs", goldPrimary).apply {
                setPadding(0, if (snapshotLabel.isNotEmpty()) 2.dp else 4.dp, 0, 0)
            })
            textCol.addView(uiTextView(UiText.CAPTION, "🔄 $refreshLabel", textTertiary).apply {
                setPadding(0, 2.dp, 0, 0)
            })
        }

        inner.addView(textCol)
        inner.addView(TextView(this).apply {
            text = when {
                isAwaitingFirstSync -> "⏳"
                isDone -> "✅"
                else -> "🔓"
            }
            textSize = 24f
            setPadding(Space.M.dp, 0, 0, 0)
        })
        outer.addView(inner)

        // Discussion Row — clean divider + light row
        outer.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(-1, 1.dp)
        })
        val discussRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            setBackgroundColor(bgTertiary)
            isClickable = true; isFocusable = true
            val tv = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0)
                foreground = ContextCompat.getDrawable(context, tv.resourceId)
            setOnClickListener {
                com.jeeneet.mocktest.ui.vault.VaultDiscussionActivity.start(this@MainActivity, today)
            }
        }
        discussRow.addView(uiTextView(UiText.CAPTION, "💬  Discuss with aspirants", textSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            textSize = 12f
        })
        discussRow.addView(uiTextView(UiText.OVERLINE, "Open →", colorPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        outer.addView(discussRow)

        card.addView(outer)
        return card
    }

    private fun monthAbbrev(month: Int) = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").getOrElse(month) { "" }

    private fun buildDailyQuizCard(): View {
        val isDone    = PrefManager.isDailyQuizDoneToday(this)
        val accent    = if (isDone) Color.parseColor("#10B981") else colorPrimary
        val iconBg    = Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent))

        val now = java.util.Calendar.getInstance()
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 24)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        val diffMs  = midnight.timeInMillis - now.timeInMillis
        val hours   = diffMs / 3_600_000
        val minutes = (diffMs % 3_600_000) / 60_000

        val card = uiCard(
            radius = Corner.L, elevation = Elev.M, background = bgSecondary,
            strokeDp = 0,
            onClick = {
                if (isDone) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Daily Quiz Completed! 🎉")
                        .setMessage("You've already earned your 15 coins today. Come back tomorrow for a fresh set of 10 questions!")
                        .setPositiveButton("Got it", null).show()
                } else {
                    AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) {
                        TestActivity.startDailyQuiz(this@MainActivity, selectedExam)
                    }
                }
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.S) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        // Icon
        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(52.dp, 52.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply { cornerRadius = Corner.M.dpF; setColor(iconBg) }
            addView(TextView(this@MainActivity).apply {
                text = if (isDone) "✅" else "🎯"; textSize = 22f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })

        // Text
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        if (isDone) {
            textCol.addView(uiTextView(UiText.H3, "Daily Quiz Done!", Color.parseColor("#10B981")).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            textCol.addView(uiTextView(UiText.CAPTION, "🎉 +15 coins earned · resets in ${hours}h ${minutes}m", textMuted).apply {
                setPadding(0, 3.dp, 0, 0); textSize = 11f
            })
        } else {
            textCol.addView(uiTextView(UiText.H3, "Daily Quiz", textPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            textCol.addView(uiTextView(UiText.CAPTION, "10 Questions · 5 min · +15 🪙", textTertiary).apply {
                setPadding(0, 3.dp, 0, 0); textSize = 11f
            })
            textCol.addView(uiTextView(UiText.CAPTION, "⏰ Expires in ${hours}h ${minutes}m", Color.parseColor("#EF4444")).apply {
                setPadding(0, 2.dp, 0, 0); textSize = 10f
            })
        }
        inner.addView(textCol)

        // CTA pill
        inner.addView(TextView(this).apply {
            text = if (isDone) "Done ✓" else "Start →"
            textSize = 12f
            setTextColor(if (isDone) Color.parseColor("#10B981") else Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(if (isDone) Color.parseColor("#E8F5E9") else accent, Corner.XL)
            setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
        })

        card.addView(inner)
        return card
    }

    private fun buildActivityInsightsCard(): View? {
        val results = cachedTestResults ?: return null
        val streak = PrefManager.getStreak(this)
        val lastScore = PrefManager.getLastTestScoreInfo(this)
        val daysSince = PrefManager.daysSinceLastPractice(this)

        data class Chip(val text: String, val color: Int)
        val chips = mutableListOf<Chip>()

        if (streak > 1) chips.add(Chip("🔥 $streak day streak", Color.parseColor("#F97316")))

        val subject = lastScore?.subject?.ifEmpty { null }
        if (subject != null) {
            when {
                daysSince == 0 -> chips.add(Chip("📚 $subject today", Color.parseColor("#6366F1")))
                daysSince == 1 -> chips.add(Chip("📚 $subject yesterday", Color.parseColor("#6366F1")))
                daysSince in 2..6 -> chips.add(Chip("📚 $subject · $daysSince days ago", Color.parseColor("#6366F1")))
            }
        }

        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val testsThisWeek = results.count { it.completedAt >= weekAgo }
        if (testsThisWeek > 0) chips.add(Chip("📊 $testsThisWeek test${if (testsThisWeek > 1) "s" else ""} this week", Color.parseColor("#10B981")))

        val quizDone = PrefManager.isDailyQuizDoneToday(this)
        val vaultDone = PrefManager.isDailyVaultDoneToday(this, selectedExam)
        val pending = listOf(!quizDone, !vaultDone).count { it }
        if (pending > 0) chips.add(Chip("🎯 $pending task${if (pending > 1) "s" else ""} pending today", Color.parseColor("#EF4444")))

        if (chips.isEmpty()) return null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        container.addView(uiTextView(UiText.OVERLINE, "YOUR ACTIVITY", textTertiary).apply {
            setPadding(4.dp, 0, 0, 6.dp)
        })

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, Space.M.dp, 0)
        }
        chips.forEach { chip ->
            val r = Color.red(chip.color); val g = Color.green(chip.color); val b = Color.blue(chip.color)
            chipRow.addView(TextView(this).apply {
                text = chip.text
                textSize = 12f
                setTextColor(chip.color)
                background = roundedFill(Color.argb(30, r, g, b), Corner.XL)
                setPadding(Space.M.dp, 6.dp, Space.M.dp, 6.dp)
                layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = Space.S.dp }
            })
        }
        scroll.addView(chipRow)
        container.addView(scroll)
        return container
    }



    private fun buildWeeklyNewTestBanner(): View? {
        val calendar = java.util.Calendar.getInstance()
        val currentWeek = calendar.get(java.util.Calendar.WEEK_OF_YEAR)
        val lastSeenWeek = PrefManager.getLastSeenWeeklyTestWeek(this)
        if (currentWeek == lastSeenWeek) return null

        val green = Color.parseColor("#10B981")
        val greenBg = Color.argb(50, 16, 185, 129)

        val card = uiCard(
            radius = Corner.L, elevation = Elev.M,
            background = bgSecondary, strokeDp = 0,
            onClick = {
                PrefManager.setLastSeenWeeklyTestWeek(this, currentWeek)
                startActivity(Intent(this, com.jeeneet.mocktest.ui.simulation.SimulationIntroActivity::class.java))
                renderContent()
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(54.dp, 54.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply { cornerRadius = Corner.M.dpF; setColor(greenBg) }
            addView(TextView(this@MainActivity).apply {
                text = "🆕"; textSize = 26f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, "New Weekly Mock Test", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(uiTextView(UiText.CAPTION, "Fresh $selectedExam pattern test is live this week", textTertiary).apply {
            setPadding(0, 3.dp, 0, 0); textSize = 11f
        })
        inner.addView(textCol)

        inner.addView(TextView(this).apply {
            text = "Try →"; textSize = 12f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(green, Corner.XL)
            setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
        })

        card.addView(inner)

        if (lastSeenWeek == -1 || currentWeek != lastSeenWeek) {
            NotificationHelper.showNotification(this,
                "🆕 New Weekly Mock Test!",
                "The fresh $selectedExam simulation for this week is now live. Tap to start!")
        }
        return card
    }

    // ─── Comeback banner ──────────────────────────────────────────────────────

    private fun buildComebackBanner(): View? {
        val lastScore   = PrefManager.getLastTestScoreInfo(this) ?: return null
        val daysSince   = PrefManager.daysSinceLastPractice(this)
        val isDoneToday = PrefManager.isDailyQuizDoneToday(this)

        // Don't show if already done today or no gap
        if (isDoneToday || daysSince == 0) return null
        // Only show if there's a meaningful last score
        if (lastScore.scorePercent <= 0) return null

        val (titleText, bodyText, accentColor) = when {
            daysSince == 1 -> Triple(
                "Beat your yesterday's score! 🎯",
                "You scored ${lastScore.scorePercent}% yesterday. Can you top it today?",
                Color.parseColor("#0D9488")
            )
            daysSince in 2..6 -> Triple(
                "We've missed you! 👋",
                "Your last score was ${lastScore.scorePercent}%. ${daysSince} days ago — let's improve!",
                Color.parseColor("#7C3AED")
            )
            else -> Triple(
                "Welcome back! Time to practice 🔥",
                "Your best score: ${lastScore.scorePercent}%. Today's test is ready for you.",
                Color.parseColor("#D97706")
            )
        }

        val scoreBg = Color.argb(50, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val card = uiCard(
            radius = Corner.L, elevation = Elev.M,
            background = bgSecondary, strokeDp = 0,
            onClick = {
                com.jeeneet.mocktest.admob.AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) {
                    com.jeeneet.mocktest.ui.test.TestActivity.startDailyQuiz(this@MainActivity, selectedExam)
                }
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        // Score badge — soft tinted circle
        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(56.dp, 56.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(scoreBg)
            }
            addView(TextView(this@MainActivity).apply {
                text = "${lastScore.scorePercent}%"
                textSize = 13f; setTextColor(accentColor); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, titleText, textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(uiTextView(UiText.CAPTION, bodyText, textTertiary).apply {
            setPadding(0, 3.dp, 0, 0); textSize = 11f
        })
        inner.addView(textCol)

        inner.addView(TextView(this).apply {
            text = "Practice →"; textSize = 11f; setTextColor(accentColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(scoreBg, Corner.XL)
            setPadding(Space.S.dp, 4.dp, Space.S.dp, 4.dp)
        })

        card.addView(inner)
        return card
    }

    // ─── Streak milestone celebration card ────────────────────────────────────

    private fun checkAndAwardMilestones(): View? {
        val milestones = listOf(30, 7, 3) // Check highest first
        for (milestone in milestones) {
            val coins = PrefManager.awardMilestoneIfEligible(this, milestone)
            if (coins > 0) {
                // Update coin display immediately
                if (::tvCoinsBalance.isInitialized) {
                    tvCoinsBalance.text = "🪙 ${PrefManager.getCoins(this)}"
                }
                return buildMilestoneCard(milestone, coins)
            }
            // Also show (without awarding) if milestone already reached this cycle
            if (!PrefManager.shouldAwardMilestone(this, milestone) &&
                PrefManager.getStreak(this) >= milestone &&
                PrefManager.getStreakMilestoneAwardedAt(this, milestone) >= milestone) {
                // Already awarded in this cycle — show a persistent celebration card
                return buildMilestoneCard(milestone, 0)
            }
        }
        return null
    }

    private fun buildMilestoneCard(milestone: Int, coinsAwarded: Int): View {
        val (emoji, label, gradient) = when (milestone) {
            3    -> Triple("🌟", "3-Day Streak!",  Pair(Color.parseColor("#0D9488"), Color.parseColor("#14B8A6")))
            7    -> Triple("🏆", "7-Day Champion!", Pair(Color.parseColor("#7C3AED"), Color.parseColor("#A855F7")))
            else -> Triple("👑", "30-Day Legend!",  Pair(Color.parseColor("#D97706"), Color.parseColor("#F59E0B")))
        }
        val (startColor, endColor) = gradient

        val card = uiCard(radius = Corner.L, elevation = Elev.M).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(startColor, endColor)
                cornerRadius = Corner.L.dpF
            }
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
        }
        val emojiTv = android.widget.TextView(this).apply {
            text = emoji; textSize = 32f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = Space.M.dp }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, label, Color.WHITE))
        val subtext = if (coinsAwarded > 0)
            "You earned +$coinsAwarded 🪙 coins! Keep going!"
        else
            "Amazing consistency! Streak: ${PrefManager.getStreak(this)} days 🔥"
        textCol.addView(uiTextView(UiText.CAPTION, subtext, Color.parseColor("#CCFFFFFF")).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })
        inner.addView(emojiTv)
        inner.addView(textCol)
        card.addView(inner)
        return card
    }

    // ─── Resume card ──────────────────────────────────────────────────────────

    private fun buildResumeCard(): View? {
        val json = PrefManager.getSavedTestSessionJson(this) ?: return null
        val saved = runCatching { Gson().fromJson(json, SavedTestSession::class.java) }.getOrNull() ?: return null
        val config = runCatching {
            Gson().fromJson(saved.configJson, com.jeeneet.mocktest.data.model.ExamConfig::class.java)
        }.getOrNull() ?: return null

        val h = saved.timeLeftSeconds / 3600
        val m = (saved.timeLeftSeconds % 3600) / 60
        val s = saved.timeLeftSeconds % 60
        val timeStr = if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        val label = if (config.subject != null) "${config.examType} · ${config.subject}" else "${config.examType} Full Mock"

        val card = uiCard(
            radius = Corner.M,
            elevation = Elev.S,
            background = ContextCompat.getColor(this, R.color.card_full_test_start),
            onClick = { TestActivity.resume(this) }
        ).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(uiTextView(UiText.BUTTON, "▶  Resume Test", Color.WHITE))
        textCol.addView(uiTextView(UiText.CAPTION, "$label  ·  $timeStr remaining", Color.parseColor("#CCFFFFFF")).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })
        val btnDiscard = uiTextView(UiText.CAPTION, "Discard", Color.parseColor("#CCFFFFFF")).apply {
            setPadding(Space.M.dp, Space.S.dp, Space.XS.dp, Space.S.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                PrefManager.clearSavedTestSession(this@MainActivity)
                buildContent()
            }
        }
        inner.addView(textCol); inner.addView(btnDiscard)
        card.addView(inner)
        return card
    }

    // ─── Card builders ────────────────────────────────────────────────────────

    private fun homeCard(
        titleText: String, subtitleText: String, iconEmoji: String,
        startColor: Int, endColor: Int, onClick: () -> Unit
    ): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.S,
            onClick = onClick
        ).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(startColor, endColor)
                cornerRadius = Corner.L.dpF
            }
            setPadding(Space.L.dp, 18.dp, Space.L.dp, 18.dp)
        }

        inner.addView(uiIconBox(52, Color.parseColor("#33FFFFFF"),
            emoji = iconEmoji, emojiSize = 22f, oval = true).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 14.dp
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, titleText, Color.WHITE))
        textCol.addView(uiTextView(UiText.CAPTION, subtitleText, Color.parseColor("#CCFFFFFF")).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })

        inner.addView(textCol)
        inner.addView(TextView(this).apply {
            text = "›"; textSize = 28f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(inner)
        return card
    }

    private fun dailyQuizCard(): View {
        val done = PrefManager.isDailyQuizDoneToday(this)

        // Countdown until midnight
        val resetLabel = if (done) {
            val millisUntilMidnight = ((System.currentTimeMillis() / 86_400_000 + 1) * 86_400_000L) - System.currentTimeMillis()
            val h = millisUntilMidnight / 3_600_000
            val m = (millisUntilMidnight % 3_600_000) / 60_000
            "Resets in ${h}h ${m}m"
        } else ""

        val strokeColor = if (done) dividerColor else goldPrimary
        val card = uiCard(
            radius = Corner.L,
            elevation = if (done) Elev.NONE else Elev.S,
            strokeDp = 2,
            strokeColor = strokeColor,
            onClick = if (done) ({}) else ({
                requiresHomeRefresh = true
                AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) {
                    TestActivity.startDailyQuiz(this@MainActivity, selectedExam)
                }
            })
        ).apply {
            alpha = if (done) 0.6f else 1f
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconEmoji = if (done) "✅" else "🔥"
        inner.addView(uiIconBox(48, Color.parseColor(if (done) "#1A4ADE80" else "#26F97316"),
            emoji = iconEmoji, emojiSize = 20f, radius = Corner.M).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 14.dp
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val challengeTitle = if (done) "Daily Challenge" else "Today's $selectedExam Challenge"
        textCol.addView(uiTextView(UiText.H3, challengeTitle, if (done) textTertiary else textPrimary))
        val fomoText = if (fomoCount > 0) "%,d active today  •  ".format(fomoCount) else ""
        textCol.addView(uiTextView(UiText.CAPTION,
            if (done) "Completed today  •  $resetLabel"
            else "${fomoText}10 Questions  •  5 min",
            textTertiary).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })

        inner.addView(textCol)
        if (done) {
            inner.addView(TextView(this).apply {
                text = "DONE"; textSize = UiText.OVERLINE.size
                setTextColor(correctGreen)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(Color.parseColor("#1A4ADE80"), Corner.XL)
                setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            })
        } else {
            inner.addView(TextView(this).apply {
                text = "+15 🪙"; textSize = UiText.OVERLINE.size
                setTextColor(Color.parseColor("#1A1A1A"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(goldPrimary, Corner.XL)
                setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            })
        }
        card.addView(inner)
        return card
    }

    private fun scanQuestionCard(): View {
        val scanColor = Color.parseColor("#7C3AED")
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.S,
            strokeDp = 2,
            strokeColor = scanColor,
            onClick = { ScanActivity.start(this@MainActivity) }
        ).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
            gravity = Gravity.CENTER_VERTICAL
        }

        inner.addView(uiIconBox(48, Color.parseColor("#1A7C3AED"),
            emoji = "📷", emojiSize = 20f, radius = Corner.M).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 14.dp
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, "Scan & Solve", textPrimary))
        textCol.addView(uiTextView(UiText.CAPTION, "Scan any question • Get AI solution", textTertiary).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })

        inner.addView(textCol)
        inner.addView(TextView(this).apply {
            text = "✨ AI"; textSize = UiText.OVERLINE.size
            setTextColor(scanColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A7C3AED"))
                cornerRadius = Corner.XL.dpF
                setStroke(1.dp, Color.parseColor("#407C3AED"))
            }
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
        })
        card.addView(inner)
        return card
    }

    private fun comingSoonCard(icon: String, title: String, subtitle: String): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.S,
            strokeDp = 0,
            onClick = {
                Toast.makeText(this@MainActivity, "Coming Soon! Stay tuned.", Toast.LENGTH_SHORT).show()
            }
        ).apply {
            alpha = 0.7f
            layoutParams = lpRow(bottomDp = 10)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Space.L.dp, 14.dp, Space.L.dp, 14.dp)
            gravity = Gravity.CENTER_VERTICAL
        }
        inner.addView(TextView(this).apply {
            text = icon; textSize = 20f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).also {
                it.marginEnd = Space.M.dp
            }
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(uiTextView(UiText.BODY, title, textSecondary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(uiTextView(UiText.CAPTION, subtitle, textMuted).apply {
            textSize = 11f
            setPadding(0, Space.XS.dp, 0, 0)
        })

        inner.addView(textCol)
        inner.addView(TextView(this).apply {
            text = "Soon"; textSize = UiText.OVERLINE.size
            setTextColor(textMuted)
            background = roundedFill(dividerColor, Corner.XL)
            setPadding(Space.S.dp, Space.XS.dp, Space.S.dp, Space.XS.dp)
        })
        card.addView(inner)
        return card
    }

    /** 2-column grid of 6 test-category cards (3 rows × 2). */
    /** Full-width premium category cards matching the design screenshot. */
    private fun buildTestGrid(): View {
        val examLabel = if (selectedExam == "JEE") "JEE" else "NEET"
        val subject4  = if (selectedExam == "JEE") "Maths" else "Biology"
        val icon4     = if (selectedExam == "JEE") "📐" else "🧬"
        val sub4Text  = if (selectedExam == "JEE") "Sharpen problem-solving with chapter tests." else "Strengthen core concepts with chapter tests."

        data class CategoryItem(val icon: String, val title: String, val subtitle: String,
                                val start: Int, val end: Int, val action: () -> Unit)

        val items = listOf(
            CategoryItem("📝", "Mock Test", "Practice with precision for $selectedExam success.",
                ContextCompat.getColor(this, R.color.card_mock_test_start),
                ContextCompat.getColor(this, R.color.card_mock_test_end)
            ) { requiresHomeRefresh = true; MockTestListActivity.start(this, "mock_test", selectedExam) },
            CategoryItem("⚡", "Physics Chapterwise Test", "Master each chapter with targeted tests.",
                ContextCompat.getColor(this, R.color.card_physics_start),
                ContextCompat.getColor(this, R.color.card_physics_end)
            ) { requiresHomeRefresh = true; ChapterwiseListActivity.start(this, "Physics", selectedExam) },
            CategoryItem("🧪", "Chemistry Chapterwise Test", "Build fundamentals with chapter tests.",
                ContextCompat.getColor(this, R.color.card_chemistry_start),
                ContextCompat.getColor(this, R.color.card_chemistry_end)
            ) { requiresHomeRefresh = true; ChapterwiseListActivity.start(this, "Chemistry", selectedExam) },
            CategoryItem(icon4, "$subject4 Chapterwise Test", sub4Text,
                ContextCompat.getColor(this, R.color.card_maths_start),
                ContextCompat.getColor(this, R.color.card_maths_end)
            ) { requiresHomeRefresh = true; ChapterwiseListActivity.start(this, subject4, selectedExam) },
            CategoryItem("📋", "Full Series", "Curated full-length mock test series.",
                ContextCompat.getColor(this, R.color.card_full_test_start),
                ContextCompat.getColor(this, R.color.card_full_test_end)
            ) { requiresHomeRefresh = true; MockTestListActivity.start(this, "full_series", selectedExam) },
            CategoryItem("📚", "$examLabel PYQs", "Real past year question papers for practice.",
                ContextCompat.getColor(this, R.color.card_pyqs_start),
                ContextCompat.getColor(this, R.color.card_pyqs_end)
            ) { requiresHomeRefresh = true; MockTestListActivity.start(this, "pyqs", selectedExam) }
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.S)
        }

        items.forEach { item ->
            val card = uiCard(radius = Corner.L, elevation = Elev.S, onClick = item.action).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = Space.M.dp }
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(item.start, item.end)
                    cornerRadius = Corner.L.dpF
                }
                setPadding(Space.L.dp, 20.dp, Space.L.dp, 20.dp)
            }

            inner.addView(uiIconBox(52, Color.parseColor("#33FFFFFF"),
                emoji = item.icon, emojiSize = 24f, oval = true).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 16.dp
            })

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(uiTextView(UiText.H3, item.title, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            textCol.addView(uiTextView(UiText.CAPTION, item.subtitle, Color.parseColor("#EEFFFFFF")).apply {
                setPadding(0, Space.XS.dp, 0, 0)
                textSize = 12f
            })
            inner.addView(textCol)

            inner.addView(TextView(this).apply {
                text = "›"; textSize = 28f; setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(12.dp, 0, 4.dp, 0)
            })

            card.addView(inner)
            container.addView(card)
        }
        return container
    }

    /** Leaderboard + Streak Calendar side by side in a 2-col row. */
    private fun buildCommunityRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lpRow(bottomDp = Space.S)
        }

        fun communityCell(icon: String, title: String, sub: String,
                          start: Int, end: Int, action: () -> Unit): View {
            val cell = uiCard(radius = Corner.L, elevation = Elev.S, onClick = action).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(start, end)
                    cornerRadius = Corner.L.dpF
                }
                setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
            }
            inner.addView(TextView(this).apply { text = icon; textSize = 24f })
            inner.addView(uiTextView(UiText.H3, title, Color.WHITE).apply {
                setPadding(0, Space.XS.dp, 0, 0)
            })
            inner.addView(uiTextView(UiText.CAPTION, sub, Color.parseColor("#CCFFFFFF")).apply {
                setPadding(0, Space.XS.dp, 0, 0)
                maxLines = 2
            })
            cell.addView(inner)
            return cell
        }

        row.addView(communityCell(
            "🏆", "Leaderboard", "Top scorers\nthis week",
            Color.parseColor("#7C3AED"), Color.parseColor("#A855F7")
        ) { LeaderboardActivity.start(this) }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginEnd = (Space.M / 2).dp
        })

        row.addView(communityCell(
            "📅", "Streak", "${PrefManager.getStreak(this)}d  ·  ${PrefManager.getTotalPracticedDays(this)} practiced",
            Color.parseColor("#0D9488"), Color.parseColor("#14B8A6")
        ) { StreakCalendarActivity.start(this) }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = (Space.M / 2).dp
        })

        return row
    }



    private fun fetchFomoCount() {
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            FirebaseFirestore.getInstance()
                .document("dailyStats/$today")
                .get()
                .addOnSuccessListener { snap ->
                    if (isFinishing || isDestroyed) return@addOnSuccessListener
                    val count = snap.getLong("attemptCount")?.toInt() ?: 0
                    if (count != fomoCount) {
                        fomoCount = count
                        PrefManager.setCachedDailyAttempts(this, count)
                        if (::contentLayout.isInitialized) buildContent()
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("MainActivity", "FomoCount fetch failed: ${e.message}")
                }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "FomoCount init failed: ${e.message}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun buildStatsCard(): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.S,
            background = bgSecondary,
            onClick = { startActivity(android.content.Intent(this, com.jeeneet.mocktest.ui.analysis.AnalysisActivity::class.java)) }
        ).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
        }

        // Row 1: tests taken + last score
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Space.M.dp }
        }
        val testCountCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        testCountCol.addView(uiTextView(UiText.CAPTION, "Total Tests", textTertiary))
        val tvCount = TextView(this).apply {
            text = "--"; textSize = 36f
            setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        testCountCol.addView(tvCount)
        topRow.addView(testCountCol)

        // Divider
        topRow.addView(android.view.View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(1, 48.dp).also {
                it.marginStart = Space.L.dp; it.marginEnd = Space.L.dp
            }
        })

        // Last score column with delta
        val lastScoreInfo = PrefManager.getLastTestScoreInfo(this)
        val prevScorePct  = PrefManager.getPreviousScorePct(this)

        val lastScoreCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        lastScoreCol.addView(uiTextView(UiText.CAPTION, "Last Score", textTertiary))
        if (lastScoreInfo != null) {
            val scoreRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            scoreRow.addView(TextView(this).apply {
                text = "${lastScoreInfo.scorePercent}%"
                textSize = 36f
                setTextColor(when {
                    lastScoreInfo.scorePercent >= 70 -> correctGreen
                    lastScoreInfo.scorePercent >= 40 -> goldPrimary
                    else -> wrongRed
                })
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })

            // Improvement Delta 🔥
            if (prevScorePct > 0 && lastScoreInfo.scorePercent > prevScorePct) {
                val delta = lastScoreInfo.scorePercent - prevScorePct
                scoreRow.addView(TextView(this).apply {
                    text = " 🔥 +$delta%"; textSize = 11f; setTextColor(correctGreen)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(4.dp, 0, 0, 8.dp)
                })
            }
            lastScoreCol.addView(scoreRow)
        } else {
            lastScoreCol.addView(uiTextView(UiText.H2, "—", textMuted))
        }
        topRow.addView(lastScoreCol)
        inner.addView(topRow)

        inner.addView(uiTextView(UiText.CAPTION, "Tap to view full analytics →", textMuted).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                MockTestDatabase.getInstance(this@MainActivity).testResultDao()
                    .getTotalTestsTaken(uid).first()
            }
            tvCount.text = count.toString()
        }
        card.addView(inner)
        return card
    }

    // ─── Notification Permission Psychology Flow ─────────────────────────────

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            NotificationHelper.scheduleEveningReminder(this)
            recreate() // refresh toolbar to hide bell
        }
    }

    private fun checkNotificationPermissionFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationHelper.scheduleEveningReminder(this)
            return
        }

        val isGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!isGranted) {
            // High-intent trigger: Only prompt if they have a streak or have practiced at least once
            val shouldPrompt = (PrefManager.getStreak(this) > 0 || PrefManager.getTotalPracticedDays(this) > 0)
                    && !PrefManager.hasPromptedForNotifications(this)

            if (shouldPrompt) {
                showStreakProtectionDialog()
            }
        } else {
            NotificationHelper.scheduleEveningReminder(this)
        }
    }

    private fun buildRecentDoubtsCarousel(): View? {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow()
        }

        val loadingPlaceholder = uiTextView(UiText.CAPTION, "Checking history…", textMuted).apply {
            setPadding(Space.XS.dp, Space.M.dp, 0, Space.M.dp)
        }
        root.addView(loadingPlaceholder)

        lifecycleScope.launch {
            val recent = withContext(Dispatchers.IO) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                MockTestDatabase.getInstance(this@MainActivity).scanHistoryDao().getAllOnce(uid)
            }
            root.removeView(loadingPlaceholder)
            if (recent.isEmpty()) {
                return@launch
            }

            root.addView(uiSectionLabel("Recent Doubts"))

            val doubtsContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, 0, 0, Space.S.dp)
            }
            val horizontalScroll = android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                layoutParams = lpRow(bottomDp = Space.M)
                addView(doubtsContainer)
            }
            root.addView(horizontalScroll)

            val subjectIcon: (String) -> String = { s -> when (s) {
                "Physics"   -> "⚡"; "Chemistry" -> "🧪"
                "Maths"     -> "📐"; "Biology"   -> "🧬"
                else        -> "🔬"
            }}
            val subjectColor: (String) -> Int = { s -> Color.parseColor(when (s) {
                "Physics"   -> "#3B82F6"; "Chemistry" -> "#10B981"
                "Maths"     -> "#F59E0B"; "Biology"   -> "#22C55E"
                else        -> "#6366F1"
            })}

            // Width: fit exactly 2 cards in the available content area
            val cardWidthPx = (resources.displayMetrics.widthPixels - Space.L.dp * 2 - Space.S.dp) / 2

            recent.take(5).forEach { doubt ->
                val accentColor = subjectColor(doubt.subject)
                val doubtCard = uiCard(
                    radius = Corner.L, elevation = Elev.S,
                    background = bgSecondary,
                    onClick = {
                        AnalyticsManager.doubtReopened(this@MainActivity)
                        AISolutionActivity.start(this@MainActivity, doubt.solutionMarkdown)
                    }
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(cardWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.marginEnd = Space.S.dp
                    }
                }
                val inner = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
                }
                // Icon box — same style as Full Series
                inner.addView(FrameLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = Space.S.dp }
                    background = GradientDrawable().apply {
                        cornerRadius = Corner.S.dpF
                        setColor(Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                    }
                    addView(android.widget.TextView(this@MainActivity).apply {
                        text = subjectIcon(doubt.subject); textSize = 18f
                        gravity = android.view.Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                    })
                })
                // Text column
                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                textCol.addView(uiTextView(UiText.BODY, doubt.questionSnippet.ifEmpty { "AI Solution" }, textPrimary).apply {
                    textSize = 12f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val dateStr = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(doubt.scannedAt))
                textCol.addView(uiTextView(UiText.CAPTION, dateStr, textMuted).apply {
                    textSize = 10f; setPadding(0, 2.dp, 0, 0)
                })
                inner.addView(textCol)
                doubtCard.addView(inner)
                doubtsContainer.addView(doubtCard)
            }
        }
        return root
    }

    private fun buildActivityHeatmap(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        root.addView(uiSectionLabel("Activity"))

        val WEEKS = 15
        val GAP_PX = 3.dp
        val DAY_LABEL_PX = 20.dp
        val availWidthPx = resources.displayMetrics.widthPixels - Space.L.dp * 2 - DAY_LABEL_PX
        val cellPx = (availWidthPx - (WEEKS - 1) * GAP_PX) / WEEKS

        fun cellColor(count: Int): Int = when {
            count == 0 -> ContextCompat.getColor(this, R.color.bg_tertiary)
            count == 1 -> Color.parseColor("#C7D2FE")
            count <= 3 -> Color.parseColor("#818CF8")
            count <= 5 -> Color.parseColor("#6366F1")
            else       -> Color.parseColor("#4338CA")
        }

        // Build grid immediately with empty cells; fill in data async
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        // Align start to Monday WEEKS-1 weeks ago
        val cal = java.util.Calendar.getInstance()
        val daysFromMonday = (cal.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysFromMonday - (WEEKS - 1) * 7)

        // Month label row
        val monthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 4.dp }
        }
        monthRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(DAY_LABEL_PX, 1) })
        val calM = cal.clone() as java.util.Calendar
        var lastMonth = -1
        val monthFmt = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())
        for (w in 0 until WEEKS) {
            val m = calM.get(java.util.Calendar.MONTH)
            monthRow.addView(android.widget.TextView(this).apply {
                text = if (m != lastMonth) { lastMonth = m; monthFmt.format(calM.time) } else ""
                textSize = 8f
                setTextColor(textMuted)
                layoutParams = LinearLayout.LayoutParams(cellPx + (if (w < WEEKS - 1) GAP_PX else 0), -2)
            })
            calM.add(java.util.Calendar.DAY_OF_YEAR, 7)
        }
        root.addView(monthRow)

        // Grid (day-labels col + week cols)
        val gridRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // Day label column: show M / W / F only
        val dayLabelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(DAY_LABEL_PX, -2)
        }
        listOf("M", "", "W", "", "F", "", "S").forEach { lbl ->
            dayLabelCol.addView(android.widget.TextView(this).apply {
                text = lbl; textSize = 8f; setTextColor(textMuted)
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, cellPx + (if (lbl != "S") GAP_PX else 0))
            })
        }
        gridRow.addView(dayLabelCol)

        // Cell views keyed by date for async fill
        val cellViews = mutableMapOf<String, View>()
        val calG = cal.clone() as java.util.Calendar
        for (w in 0 until WEEKS) {
            val weekCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(cellPx, -2).also {
                    if (w < WEEKS - 1) it.marginEnd = GAP_PX
                }
            }
            for (d in 0 until 7) {
                val dayKey = sdf.format(calG.time)
                val cell = View(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 3f.dpF; setColor(cellColor(0))
                    }
                    layoutParams = LinearLayout.LayoutParams(cellPx, cellPx).also {
                        if (d < 6) it.bottomMargin = GAP_PX
                    }
                }
                cellViews[dayKey] = cell
                weekCol.addView(cell)
                calG.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            gridRow.addView(weekCol)
        }
        root.addView(gridRow)

        // Legend row
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Space.S.dp }
        }
        legendRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        legendRow.addView(android.widget.TextView(this).apply {
            text = "Less"; textSize = 9f; setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = 4.dp }
        })
        listOf(0, 1, 2, 4, 6).forEach { count ->
            legendRow.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 3f.dpF; setColor(cellColor(count)) }
                layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).also { it.marginEnd = 3.dp }
            })
        }
        legendRow.addView(android.widget.TextView(this).apply {
            text = "More"; textSize = 9f; setTextColor(textMuted)
        })
        root.addView(legendRow)

        // Load activity data and colour cells
        lifecycleScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
            val scans = withContext(Dispatchers.IO) {
                MockTestDatabase.getInstance(this@MainActivity).scanHistoryDao().getAllOnce(uid)
            }
            val activityMap = mutableMapOf<String, Int>()
            cachedTestResults?.forEach {
                val k = sdf.format(java.util.Date(it.completedAt))
                activityMap[k] = (activityMap[k] ?: 0) + 1
            }
            scans.forEach {
                val k = sdf.format(java.util.Date(it.scannedAt))
                activityMap[k] = (activityMap[k] ?: 0) + 1
            }
            cellViews.forEach { (day, cell) ->
                val count = activityMap[day] ?: 0
                (cell.background as? GradientDrawable)?.setColor(cellColor(count))
            }
        }

        return root
    }

    private fun showStreakProtectionDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Protect your progress! 🛡️")
            .setMessage("Enable notifications so we can alert you before your streak breaks. Don't lose your hard-earned coins!")
            .setPositiveButton("Protect Me") { _, _ ->
                PrefManager.setPromptedForNotifications(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("Maybe Later") { _, _ ->
                PrefManager.setPromptedForNotifications(this)
            }
            .show()
    }

    private fun showDeveloperAccessDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Access Code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(24.dp, 16.dp, 24.dp, 16.dp)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Developer Access 🛠️")
            .setMessage("Enter code to open secret menu.")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                if (input.text.toString() == "1337") showDeveloperMenu()
                else android.widget.Toast.makeText(this, "Wrong Code", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDeveloperMenu() {
        val options = arrayOf("💰 Add 500 Coins", "🏆 Unlock All Badges", "🔥 Set 7-Day Streak", "🚀 Simulate AI Demo Data", "🧹 Clear Test Data", "🔄 Refresh UI")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Developer Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { PrefManager.addCoins(this, 500); buildContent() }
                    1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = MockTestDatabase.getInstance(this@MainActivity)
                            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                            com.jeeneet.mocktest.data.repository.AchievementManager.ALL_BADGES.forEach { badge ->
                                db.achievementDao().insert(com.jeeneet.mocktest.data.repository.Achievement(badge.id, uid))
                            }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(this@MainActivity, "All badges unlocked!", android.widget.Toast.LENGTH_SHORT).show()
                                buildContent()
                            }
                        }
                    }
                    2 -> { PrefManager.setStreakForDebug(this, 7); buildContent() }
                    3 -> simulateAiDemoData()
                    4 -> { PrefManager.clearSavedTestSession(this); buildContent() }
                    5 -> buildContent()
                }
                android.widget.Toast.makeText(this, "Applied: ${options[which]}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    private fun buildReadinessMeterCard(): View? {
        val frame = FrameLayout(this).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val card = uiAiCard(radius = Corner.L, accentColor = goldPrimary).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
            gravity = Gravity.CENTER_VERTICAL
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(uiTextView(UiText.OVERLINE, "🎯 EXAM READINESS", textMuted).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        inner.addView(topRow)

        val scoreLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Space.S.dp, 0, Space.S.dp)
        }

        val scoreTv = TextView(this).apply {
            text = "0%"; textSize = 38f; setTextColor(goldPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        scoreLayout.addView(scoreTv)

        val levelTv = uiTextView(UiText.CAPTION, "ANALYZING...", textTertiary).apply {
            setPadding(Space.M.dp, 0, 0, 0)
        }
        scoreLayout.addView(levelTv)
        inner.addView(scoreLayout)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6.dp)
            progressDrawable = roundedFill(goldPrimary, 3f)
            max = 100
        }
        inner.addView(progress)

        inner.addView(uiTextView(UiText.CAPTION, "Based on accuracy, consistency & recent trends.", textMuted).apply {
            setPadding(0, Space.S.dp, 0, 0)
            textSize = 10f
        })

        card.addView(inner)
        frame.addView(card)

        lifecycleScope.launch {
            try {
                val repo = com.jeeneet.mocktest.data.repository.AiCounselorRepository(applicationContext)
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                val uid = user?.uid ?: "guest"
                val results = withContext(Dispatchers.IO) {
                    MockTestDatabase.getInstance(applicationContext).testResultDao().getAllResultsOnce(uid)
                }
                val score = repo.calculateReadinessScore(results)

                withContext(Dispatchers.Main) {
                    if (!isFinishing) {
                        scoreTv.text = "$score%"
                        progress.progress = score
                        levelTv.text = when {
                            score >= 85 -> "🔥 ELITE MODE"
                            score >= 70 -> "💪 ASPIRANT"
                            score >= 50 -> "📈 IMPROVING"
                            else -> "🌱 BEGINNER"
                        }
                        levelTv.setTextColor(if (score >= 70) this@MainActivity.correctGreen else this@MainActivity.goldPrimary)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    levelTv.text = "READY TO START"
                }
            }
        }

        return frame
    }

    private fun simulateAiDemoData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = MockTestDatabase.getInstance(this@MainActivity)
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"

            // Clear existing for clean demo
            db.testResultDao().deleteAll(uid)

            val now = System.currentTimeMillis()
            val dayMs = 86400000L

            val demoResults = listOf(
                // Physics: Strong & Improving
                createDemoResult("Physics", 25, 30, now - 5 * dayMs),
                createDemoResult("Physics", 28, 30, now - 1 * dayMs),

                // Chemistry: DECLINING (to trigger At-Risk Predictor)
                createDemoResult("Chemistry", 25, 30, now - 4 * dayMs),
                createDemoResult("Chemistry", 10, 30, now - 1 * dayMs),

                // General: For Readiness
                createDemoResult("Full Paper", 50, 100, now - 3 * dayMs),
                createDemoResult("Full Paper", 65, 100, now - 2 * dayMs)
            )

            demoResults.forEach { db.testResultDao().insertResult(it) }
            PrefManager.setStreakForDebug(this@MainActivity, 5)

            // Force AI Refresh (clear cache)
            PrefManager.setAiAdviceCache(this@MainActivity, "insights", "", 0)
            PrefManager.setAiAdviceCache(this@MainActivity, "strategy", "", 0)
            PrefManager.setAiAdviceCache(this@MainActivity, "plan_content", "", 0)
            PrefManager.setAiAdviceCache(this@MainActivity, "at_risk", "", 0)

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@MainActivity, "AI Demo Data Simulated! 🚀", android.widget.Toast.LENGTH_SHORT).show()
                buildContent()
            }
        }
    }

    private fun createDemoResult(subject: String, correct: Int, total: Int, time: Long): com.jeeneet.mocktest.data.model.TestResult {
        return com.jeeneet.mocktest.data.model.TestResult(
            userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest",
            examType = "JEE",
            subject = subject,
            totalQuestions = total,
            attempted = total,
            correct = correct,
            wrong = total - correct,
            score = (correct * 4).toFloat(),
            maxScore = (total * 4).toFloat(),
            percentile = 90f,
            timeTakenSeconds = 1800,
            completedAt = time,
            subjectBreakdown = "{}",
            questionsJson = "[]",
            answersJson = "[]",
            questionTimesJson = "{}",
            isSimulation = false
        )
    }

    // ─── New UI methods for redesigned home screen ────────────────────────────

    /** Purple-gradient mission card — resume saved test or show today's priority. */
    private fun buildMissionHeroCard(): View? {
        val savedJson = PrefManager.getSavedTestSessionJson(this)
        if (savedJson != null) {
            val saved = runCatching { Gson().fromJson(savedJson, com.jeeneet.mocktest.data.model.SavedTestSession::class.java) }.getOrNull() ?: return null
            val config = runCatching { Gson().fromJson(saved.configJson, com.jeeneet.mocktest.data.model.ExamConfig::class.java) }.getOrNull() ?: return null
            val h = saved.timeLeftSeconds / 3600
            val m = (saved.timeLeftSeconds % 3600) / 60
            val s = saved.timeLeftSeconds % 60
            val timeStr = if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            val testLabel = if (config.subject != null) "${config.subject}" else "${config.examType} Full Mock"
            val chapterLabel = config.chapter ?: testLabel

            val card = uiCard(radius = Corner.L, elevation = Elev.M, onClick = {
                TestActivity.resume(this)
            }).apply { layoutParams = lpRow(bottomDp = Space.M) }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED"))
                    cornerRadius = Corner.L.dpF
                }
                setPadding(Space.L.dp, Space.XL.dp, Space.L.dp, Space.L.dp)
            }
            inner.addView(uiTextView(UiText.OVERLINE, "Continue Your Journey", Color.parseColor("#CCFFFFFF")).apply {
                textSize = 10f
            })
            inner.addView(uiTextView(UiText.H2, testLabel, Color.WHITE).apply {
                setPadding(0, 4.dp, 0, 2.dp)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            inner.addView(uiTextView(UiText.CAPTION, "📚 $chapterLabel  ·  ⏱ $timeStr remaining", Color.parseColor("#CCE0E7FF")).apply {
                textSize = 12f; setPadding(0, 0, 0, Space.L.dp)
            })
            inner.addView(TextView(this).apply {
                text = "📋"; textSize = 24f
                setPadding(0, 0, 0, Space.S.dp)
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            })

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            btnRow.addView(TextView(this).apply {
                text = "→  Resume Test"
                textSize = 13f; setTextColor(Color.parseColor("#4F46E5"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(Color.WHITE, Corner.XL)
                setPadding(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
                setOnClickListener { TestActivity.resume(this@MainActivity) }
            })
            btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            btnRow.addView(TextView(this).apply {
                text = "Discard"; textSize = 12f; setTextColor(Color.parseColor("#AAFFFFFF"))
                setPadding(Space.M.dp, Space.S.dp, 0, Space.S.dp)
                setOnClickListener { PrefManager.clearSavedTestSession(this@MainActivity); buildContent() }
            })
            inner.addView(btnRow)
            card.addView(inner)
            return card
        }

        // No saved session — return null (daily challenge has its own dedicated card)
        val isVaultDone = PrefManager.isDailyVaultDoneToday(this, selectedExam)
        val isChallengeDone = PrefManager.isDailyQuizDoneToday(this)
        return null
    }

    private fun buildQuickActionsGrid(): View {
        data class QuickAction(val icon: String, val label: String, val color: Int, val action: () -> Unit)
        val actions = listOf(
            QuickAction("📝", "Mock Tests",      Color.parseColor("#3B82F6")) {
                requiresHomeRefresh = true
                MockTestListActivity.start(this, "mock_test", selectedExam)
            },
            QuickAction("📑", "Chapterwise",     Color.parseColor("#8B5CF6")) {
                val subjects = if (selectedExam == "JEE") listOf("Physics", "Chemistry", "Maths")
                               else listOf("Physics", "Chemistry", "Biology")
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Select Subject")
                    .setItems(subjects.toTypedArray()) { _, i ->
                        requiresHomeRefresh = true
                        ChapterwiseListActivity.start(this, subjects[i], selectedExam)
                    }.show()
            },
            QuickAction("📚", "PYQs",            Color.parseColor("#F59E0B")) {
                requiresHomeRefresh = true
                MockTestListActivity.start(this, "pyqs", selectedExam)
            },
            QuickAction("🔬", "Scan Doubt",      Color.parseColor("#EF4444")) {
                requiresHomeRefresh = true
                com.jeeneet.mocktest.ui.doubts.ScanActivity.start(this)
            }
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.S)
            clipChildren = false
            clipToPadding = false
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = Space.M.dp; it.bottomMargin = Space.S.dp
            }
        }
        headerRow.addView(uiTextView(UiText.H3, "Quick Actions", textPrimary).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        container.addView(headerRow)

        // 4 icon tiles in one row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        actions.forEach { a ->
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                isClickable = true; isFocusable = true
                setPadding(Space.XS.dp, Space.S.dp, Space.XS.dp, Space.S.dp)
                val tv = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true) && tv.resourceId != 0)
                    foreground = ContextCompat.getDrawable(context, tv.resourceId)
                setOnClickListener { a.action() }

                // Icon box — soft tinted square
                val iconBg = Color.argb(65, Color.red(a.color), Color.green(a.color), Color.blue(a.color))
                addView(FrameLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(54.dp, 54.dp).also {
                        it.gravity = Gravity.CENTER_HORIZONTAL
                        it.bottomMargin = Space.S.dp
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = Corner.M.dpF
                        setColor(iconBg)
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = a.icon; textSize = 24f; gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                    })
                })

                // Label
                addView(uiTextView(UiText.CAPTION, a.label, textSecondary, Gravity.CENTER).apply {
                    textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this, 7, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                    )
                })
            })
        }
        container.addView(row)

        // ── Secondary row: Full Series + Streak Calendar ──────────────────────
        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = Space.S.dp; it.bottomMargin = Space.S.dp
            }
            clipChildren = false
            clipToPadding = false
        }

        fun smallTile(icon: String, label: String, sub: String, color: Int, action: () -> Unit): View {
            val card = uiCard(radius = Corner.L, elevation = Elev.S, background = bgSecondary,
                strokeDp = 0, onClick = action).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
            }
            inner.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = Space.S.dp }
                background = GradientDrawable().apply {
                    cornerRadius = Corner.S.dpF
                    setColor(Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)))
                }
                addView(TextView(this@MainActivity).apply {
                    text = icon; textSize = 18f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                })
            })
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            textCol.addView(uiTextView(UiText.BODY, label, textPrimary).apply {
                textSize = 12f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            textCol.addView(uiTextView(UiText.CAPTION, sub, textMuted).apply {
                textSize = 10f; setPadding(0, 2.dp, 0, 0)
            })
            inner.addView(textCol)
            card.addView(inner)
            return card
        }

        secondaryRow.addView(smallTile("🎯", "Full Series", "Full-length tests",
            ContextCompat.getColor(this, R.color.card_full_test_start)) {
            requiresHomeRefresh = true; MockTestListActivity.start(this, "full_series", selectedExam)
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = (Space.S / 2).dp })

        secondaryRow.addView(smallTile("📅", "Streak Calendar", "Daily practice log",
            Color.parseColor("#10B981")) {
            StreakCalendarActivity.start(this)
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = (Space.S / 2).dp })

        container.addView(secondaryRow)
        return container
    }

    /** Daily Challenge section: vault chip + challenge card side by side. */
    private fun buildDailyChallengeSection(): View {
        // Reuse existing buildTodaySectionHeader logic but styled as "Daily Challenge"
        return buildTodaySectionHeader()
    }

    /** Horizontal carousel showing subject progress cards for "Continue Learning". */
    private fun buildContinueLearningCarousel(): View {
        val subjects = if (selectedExam == "JEE") listOf("Physics","Chemistry","Maths")
                       else listOf("Physics","Chemistry","Biology")
        val subjectColors = mapOf(
            "Physics"   to ContextCompat.getColor(this, R.color.card_physics_start),
            "Chemistry" to ContextCompat.getColor(this, R.color.card_chemistry_start),
            "Maths"     to ContextCompat.getColor(this, R.color.card_maths_start),
            "Biology"   to correctGreen
        )
        val subjectIcons = mapOf(
            "Physics" to "⚡", "Chemistry" to "🧪", "Maths" to "📐", "Biology" to "🧬"
        )

        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, Space.M.dp, 0)
        }

        subjects.forEach { subj ->
            val accent = subjectColors[subj] ?: colorPrimary
            val icon   = subjectIcons[subj] ?: "📚"
            val card = uiCard(radius = Corner.L, elevation = Elev.S, background = bgSecondary,
                strokeDp = 0, onClick = {
                    requiresHomeRefresh = true
                    ChapterwiseListActivity.start(this, subj, selectedExam)
                }).apply {
                layoutParams = LinearLayout.LayoutParams(160.dp, -2).also { it.marginEnd = Space.M.dp }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.S.dp }
            }
            headerRow.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(32.dp, 32.dp).also { it.marginEnd = Space.S.dp }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
                addView(TextView(this@MainActivity).apply {
                    text = icon; textSize = 14f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                })
            })
            headerRow.addView(uiTextView(UiText.BODY, subj, textPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            inner.addView(headerRow)

            val tvScore = uiTextView(UiText.CAPTION, "Loading…", textMuted).apply { textSize = 11f }
            inner.addView(tvScore)
            val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
                layoutParams = LinearLayout.LayoutParams(-1, 4.dp).also { it.topMargin = 4.dp }
            }
            inner.addView(progressBar)
            card.addView(inner)
            row.addView(card)

            lifecycleScope.launch(Dispatchers.IO) {
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                val best = MockTestDatabase.getInstance(this@MainActivity).testResultDao()
                    .getAllResultsOnce(uid).filter { it.examType == selectedExam && it.subject == subj }
                    .maxByOrNull { it.score }
                withContext(Dispatchers.Main) {
                    if (!isFinishing) {
                        if (best != null) {
                            val pct = if (best.maxScore > 0) (best.score / best.maxScore * 100).toInt() else 0
                            tvScore.text = "Best: $pct%"; tvScore.setTextColor(accent)
                            progressBar.progress = pct
                        } else {
                            tvScore.text = "Not started yet"
                        }
                    }
                }
            }
        }
        scroll.addView(row)
        return scroll
    }

    /** Compact performance stats row: accuracy, questions today, daily goal, time heatmap. */
    private fun buildTodayPerformanceSection(): View {
        val card = uiCard(radius = Corner.L, elevation = Elev.S, background = bgSecondary,
            onClick = { startActivity(android.content.Intent(this, com.jeeneet.mocktest.ui.analysis.AnalysisActivity::class.java)) }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        val solved   = PrefManager.getTotalQuestionsSolvedToday(this)
        val dailyGoal = PrefManager.getDailyGoal(this)
        val mocks    = PrefManager.getAdUsageCount(this, "extra_mock")
        val lastInfo  = PrefManager.getLastTestScoreInfo(this)
        val accuracy  = lastInfo?.scorePercent ?: 0

        // Stats row: 4 metrics
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun stat(value: String, label: String, color: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(uiTextView(UiText.H2, value, color).apply {
                textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(uiTextView(UiText.CAPTION, label, textMuted).apply {
                textSize = 10f; gravity = Gravity.CENTER; setPadding(0, 2.dp, 0, 0)
            })
        }
        val accuracyColor = when {
            accuracy >= 70 -> Color.parseColor("#10B981")
            accuracy >= 40 -> goldPrimary
            else           -> textSecondary
        }
        statsRow.addView(stat("$accuracy%", "Accuracy", accuracyColor))
        statsRow.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1, 40.dp) })
        statsRow.addView(stat("$solved", "Questions", colorPrimary))
        statsRow.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1, 40.dp) })
        statsRow.addView(stat("$mocks", "Mock Tests", goldPrimary))
        statsRow.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1, 40.dp) })
        statsRow.addView(stat("${PrefManager.getStreak(this)}d", "Streak 🔥", Color.parseColor("#F97316")))
        inner.addView(statsRow)

        // Daily goal progress bar
        val progress = (solved.toFloat() / dailyGoal).coerceIn(0f, 1f)
        val goalColor = if (solved >= dailyGoal) Color.parseColor("#10B981") else colorPrimary

        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Space.M.dp; it.bottomMargin = 4.dp }
            addView(uiTextView(UiText.CAPTION,
                if (solved >= dailyGoal) "✅ Daily goal done!" else "Daily goal: $solved / $dailyGoal",
                if (solved >= dailyGoal) Color.parseColor("#10B981") else textTertiary
            ).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(uiTextView(UiText.CAPTION, "%.0f%%".format(progress * 100), goalColor))
        })

        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 6.dp)
        }
        track.addView(View(this).apply {
            background = roundedFill(dividerColor, 3f)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })
        val fill = View(this).apply {
            background = roundedFill(goalColor, 3f)
            layoutParams = FrameLayout.LayoutParams(0, -1)
        }
        track.addView(fill)
        track.post {
            val target = (track.width * progress).toInt()
            android.animation.ValueAnimator.ofInt(0, target).apply {
                duration = 700
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { a ->
                    fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).also { it.width = a.animatedValue as Int }
                }
                start()
            }
        }
        inner.addView(track)
        inner.addView(uiTextView(UiText.CAPTION, "Tap to view full analytics →", textMuted).apply {
            textSize = 10f; setPadding(0, Space.S.dp, 0, 0)
        })

        // Compliment message
        if (lastInfo != null && accuracy > 0) {
            val firstName = try {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
                    ?.split(" ")?.firstOrNull()?.ifEmpty { "Aspirant" } ?: "Aspirant"
            } catch (_: Exception) { "Aspirant" }
            val msg = when {
                accuracy >= 80 -> "You're doing great, $firstName! 🔥 Top ${100 - accuracy}%"
                accuracy >= 60 -> "Good progress, $firstName! Keep improving."
                else -> "Keep going, $firstName! Consistency is key."
            }
            inner.addView(uiTextView(UiText.CAPTION, msg, textMuted).apply {
                textSize = 11f; setPadding(0, Space.XS.dp, 0, 0)
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            })
        }

        card.addView(inner)
        return card
    }

    private fun buildBottomNavBar(): View {
        data class NavTab(val icon: String, val label: String, val active: Boolean, val action: () -> Unit)
        val tabs = listOf(
            NavTab("🏠", "Home",      true)  { if (::mainScrollView.isInitialized) mainScrollView.smoothScrollTo(0, 0); buildContent() },
            NavTab("📝", "Tests",     false) { requiresHomeRefresh = true; MockTestListActivity.start(this, "mock_test", selectedExam) },
            NavTab("📊", "Analytics", false) { startActivity(android.content.Intent(this, com.jeeneet.mocktest.ui.analysis.AnalysisActivity::class.java)) },
            NavTab("👤", "Profile",   false) { requiresHomeRefresh = true; startActivity(android.content.Intent(this, com.jeeneet.mocktest.ui.profile.ProfileActivity::class.java)) }
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // 1 dp top border
        container.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(-1, 1.dp)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgSecondary)
            elevation = Elev.L.dpF
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val activeBg = Color.argb(18, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary))

        tabs.forEach { tab ->
            bar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(0, Space.S.dp, 0, Space.M.dp)
                isClickable = true; isFocusable = true
                val tv = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true) && tv.resourceId != 0)
                    foreground = ContextCompat.getDrawable(context, tv.resourceId)
                setOnClickListener { tab.action() }

                // Icon with active pill
                val iconWrap = FrameLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL }
                    if (tab.active) {
                        background = roundedFill(activeBg, Corner.PILL)
                        setPadding(Space.XL.dp, 3.dp, Space.XL.dp, 3.dp)
                    }
                }
                iconWrap.addView(TextView(this@MainActivity).apply {
                    text = tab.icon; textSize = 20f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                })
                addView(iconWrap)

                // Label
                addView(uiTextView(UiText.CAPTION, tab.label, if (tab.active) colorPrimary else textMuted).apply {
                    textSize = 10f; gravity = Gravity.CENTER
                    typeface = if (tab.active) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
                    layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                        it.gravity = Gravity.CENTER_HORIZONTAL
                        it.topMargin = 2.dp
                    }
                })
            })
        }

        container.addView(bar)
        return container
    }

    private fun buildAiStudyPlanCard(): View? {
        val frame = FrameLayout(this).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val card = uiAiCard(radius = Corner.L, accentColor = goldPrimary).apply {
            setCardBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        inner.addView(uiTextView(UiText.OVERLINE, "📅 TODAY'S AI PLAN", goldPrimary))

        val planContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Space.S.dp, 0, 0)
        }
        val loadingTv = uiTextView(UiText.CAPTION, "Generating your plan…", textMuted)
        planContent.addView(loadingTv)
        inner.addView(planContent)
        card.addView(inner)
        frame.addView(card)

        lifecycleScope.launch {
            try {
                val repo = com.jeeneet.mocktest.data.repository.AiCounselorRepository(applicationContext)
                repo.getDailyStudyPlan().onSuccess { plan ->
                    withContext(Dispatchers.Main) {
                        if (!isFinishing) {
                            planContent.removeAllViews()
                            plan.split("\n").filter { it.isNotBlank() }.forEach { line ->
                                planContent.addView(uiTextView(UiText.CAPTION, line.trim(), Color.WHITE).apply {
                                    setPadding(0, 4.dp, 0, 4.dp)
                                    textSize = 13f
                                })
                            }
                        }
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) { loadingTv.text = "Take a test to get your plan!" }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return frame
    }

    private fun buildFeatureCardsRow(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lpRow(topDp = Space.S, bottomDp = Space.M)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2.dp, Space.M.dp, 4.dp)
        }

        val isDailyQuizDone = PrefManager.isDailyQuizDoneToday(this)
        val isVaultDone = PrefManager.isDailyVaultDoneToday(this, selectedExam)

        // 1. Daily Quiz
        row.addView(buildFeatureCard(
            icon = "🎯", title = "Daily Quiz",
            subtitle = "10 Questions • 5 Min",
            badge = if (isDailyQuizDone) "DONE ✓" else "+10 Coins",
            badgeColor = if (isDailyQuizDone) Color.parseColor("#10B981") else goldPrimary,
            btnText = if (isDailyQuizDone) "Completed" else "Start Now",
            startColor = Color.parseColor("#1D4ED8"), endColor = Color.parseColor("#3B82F6"),
            onClick = if (isDailyQuizDone) ({
                Toast.makeText(this, "Challenge already completed today!", Toast.LENGTH_SHORT).show()
            }) else ({
                requiresHomeRefresh = true
                AdManager.showInterstitial(this, bypassCooldown = true) {
                    TestActivity.startDailyQuiz(this, selectedExam)
                }
            })
        ))

        // 2. Daily Vault
        row.addView(buildFeatureCard(
            icon = "🔒", title = "Daily Vault",
            subtitle = if (isVaultDone) "Completed today ✓" else "112 PYQs • 48% Access",
            badge = "Premium", badgeColor = goldPrimary,
            btnText = if (isVaultDone) "Revision" else "Unlock Now",
            startColor = Color.parseColor("#1A1A2E"), endColor = Color.parseColor("#16213E"),
            strokeColor = goldPrimary,
            onClick = {
                AnalyticsManager.vaultOpened(this@MainActivity)
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                lifecycleScope.launch {
                    if (isVaultDone) {
                        val lastQs = PrefManager.getLastDailyVaultQuestionsJson(this@MainActivity, selectedExam)
                        if (lastQs != null) {
                            withContext(Dispatchers.Main) {
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("Daily Vault Completed!")
                                    .setMessage("Start Revision Mode for extra practice!")
                                    .setPositiveButton("Revision Mode") { _, _ ->
                                        TestActivity.startRevision(this@MainActivity, selectedExam, lastQs)
                                    }
                                    .setNegativeButton("Cancel", null).show()
                            }
                            return@launch
                        }
                    }
                    val vaultQs = withContext(Dispatchers.IO) {
                        MockTestDatabase.getInstance(this@MainActivity).questionDao()
                            .getDailyVaultQuestions(selectedExam, today)
                    }
                    withContext(Dispatchers.Main) {
                        if (vaultQs.isNotEmpty()) {
                            val config = com.jeeneet.mocktest.data.model.ExamConfig(
                                examType = selectedExam, subject = null, chapter = "Daily Vault",
                                totalQuestions = vaultQs.size, durationMinutes = vaultQs.size,
                                correctMarks = 4f, negativeMarks = -1f, isDailyVault = true,
                                vaultGroupId = vaultQs.firstOrNull()?.vaultGroupId ?: "")
                            AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) {
                                TestActivity.startWithQuestions(this@MainActivity, config, vaultQs)
                            }
                        } else Toast.makeText(this@MainActivity, "Vault updating…", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ))

        // 3. My Performance
        row.addView(buildFeatureCard(
            icon = "📊", title = "My Performance",
            subtitle = "AI calls & patterns",
            badge = "Analytics", badgeColor = Color.parseColor("#8B5CF6"),
            btnText = "View Dashboard",
            startColor = Color.parseColor("#1E293B"), endColor = Color.parseColor("#0F172A"),
            strokeColor = Color.parseColor("#8B5CF6"),
            onClick = { startActivity(android.content.Intent(this, com.jeeneet.mocktest.ui.analysis.AnalysisActivity::class.java)) }
        ))

        // 4. AI Doubt Solver
        row.addView(buildFeatureCard(
            icon = "🤖", title = "AI Doubt Solver",
            subtitle = "Snap a question, get AI solution",
            badge = "✨ AI", badgeColor = Color.parseColor("#7C3AED"),
            btnText = "Ask Now",
            startColor = Color.parseColor("#1E1B4B"), endColor = Color.parseColor("#312E81"),
            strokeColor = Color.parseColor("#7C3AED"),
            onClick = { ScanActivity.start(this) }
        ))

        scroll.addView(row)
        return scroll
    }

    private fun buildFeatureCard(
        icon: String, title: String, subtitle: String,
        badge: String, badgeColor: Int, btnText: String,
        startColor: Int, endColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        onClick: () -> Unit
    ): View {
        val card = uiCard(
            radius = Corner.L, elevation = Elev.M,
            strokeDp = if (strokeColor != Color.TRANSPARENT) 1 else 0,
            strokeColor = strokeColor,
            onClick = onClick
        ).apply {
            layoutParams = LinearLayout.LayoutParams(148.dp, -2).also { it.marginEnd = Space.M.dp }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(startColor, endColor)
                cornerRadius = Corner.L.dpF
            }
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }
        inner.addView(TextView(this).apply {
            text = badge; textSize = 9f; setTextColor(badgeColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.argb(30, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                cornerRadius = Corner.PILL.dpF
                setStroke(1.dp, Color.argb(80, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
            }
            setPadding(8.dp, 3.dp, 8.dp, 3.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.bottomMargin = Space.S.dp }
        })
        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).also { it.bottomMargin = Space.S.dp }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#33FFFFFF")) }
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })
        inner.addView(uiTextView(UiText.H3, title, Color.WHITE).apply {
            textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, 4.dp)
        })
        inner.addView(uiTextView(UiText.CAPTION, subtitle, Color.parseColor("#AAFFFFFF")).apply {
            textSize = 10f; setPadding(0, 0, 0, Space.M.dp)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        inner.addView(TextView(this).apply {
            text = "→  $btnText"; textSize = 11f; setTextColor(Color.parseColor("#1A1A1A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.WHITE, Corner.PILL)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(Space.M.dp, 6.dp, Space.M.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
        card.addView(inner)
        return card
    }

    private fun buildCorePracticeSection(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }
        headerRow.addView(uiTextView(UiText.H2, "🔥 Core Practice", textPrimary).apply {
            textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "View All >"; textSize = 12f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setOnClickListener { requiresHomeRefresh = true; MockTestListActivity.start(this@MainActivity, "mock_test", selectedExam) }
        })
        container.addView(headerRow)

        val tabLabels = listOf("📄 Mocks", "📚 PYQs", "🎯 Chapters", "📋 Series")
        val tabViews = mutableListOf<TextView>()
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedFill(bgTertiary, Corner.PILL)
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }
        val cardsFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }

        fun selectTab(idx: Int) {
            selectedPracticeTab = idx
            tabViews.forEachIndexed { i, tv ->
                if (i == idx) { tv.background = roundedFill(colorPrimary, Corner.PILL); tv.setTextColor(Color.WHITE) }
                else { tv.background = null; tv.setTextColor(textTertiary) }
            }
            cardsFrame.removeAllViews()
            val type = when (idx) { 1 -> "pyqs"; 2 -> "chapter"; 3 -> "series"; else -> "mock" }
            cardsFrame.addView(buildPracticeTestCardsScroll(type))
        }

        tabLabels.forEachIndexed { i, lbl ->
            val tv = TextView(this).apply {
                text = lbl; textSize = 11f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(Space.S.dp, 8.dp, Space.S.dp, 8.dp)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(tv); tabBar.addView(tv)
        }
        container.addView(tabBar); container.addView(cardsFrame)
        selectTab(selectedPracticeTab)
        return container
    }

    private fun buildPracticeTestCardsScroll(type: String): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4.dp)
        }
        data class TestInfo(val icon: String, val title: String, val meta: String, val startColor: Int, val endColor: Int, val action: () -> Unit)
        val tests = when (type) {
            "pyqs" -> if (selectedExam == "NEET") listOf(
                TestInfo("🔬", "NEET\nPYQs", "180 Qs • 3 Hours", Color.parseColor("#065F46"), Color.parseColor("#059669")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "pyqs", "NEET") }
            ) else listOf(
                TestInfo("📚", "JEE Main\nPYQs", "90 Qs • 3 Hours", Color.parseColor("#C2410C"), Color.parseColor("#EA580C")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "pyqs", selectedExam) },
                TestInfo("🎯", "JEE Advanced\nPYQs", "54 Qs • 3 Hours", Color.parseColor("#7C2D12"), Color.parseColor("#92400E")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "pyqs", selectedExam) }
            )
            "chapter" -> {
                val subjects = if (selectedExam == "JEE")
                    listOf(Triple("⚡  Physics", Pair(Color.parseColor("#9D174D"), Color.parseColor("#BE185D")), "Physics"),
                           Triple("🧪  Chemistry", Pair(Color.parseColor("#065F46"), Color.parseColor("#047857")), "Chemistry"),
                           Triple("📐  Maths", Pair(Color.parseColor("#1E3A8A"), Color.parseColor("#1D4ED8")), "Maths"))
                else
                    listOf(Triple("⚡  Physics", Pair(Color.parseColor("#9D174D"), Color.parseColor("#BE185D")), "Physics"),
                           Triple("🧪  Chemistry", Pair(Color.parseColor("#065F46"), Color.parseColor("#047857")), "Chemistry"),
                           Triple("🧬  Biology", Pair(Color.parseColor("#4338CA"), Color.parseColor("#6D28D9")), "Biology"))
                subjects.map { (subj, colors, s) ->
                    val icon = subj.split("  ").firstOrNull() ?: "📖"
                    TestInfo(icon, "$s\nChapters", "100+ Questions", colors.first, colors.second) { requiresHomeRefresh = true; ChapterwiseListActivity.start(this, s, selectedExam) }
                }
            }
            "series" -> if (selectedExam == "NEET") listOf(
                TestInfo("🧬", "NEET Full\nTest Series", "180 Qs • 3 Hours", Color.parseColor("#065F46"), Color.parseColor("#059669")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "full_series", "NEET") }
            ) else listOf(
                TestInfo("📋", "JEE Main\nTest Series", "90 Qs • 3 Hours", Color.parseColor("#0F766E"), Color.parseColor("#0D9488")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "full_series", selectedExam) },
                TestInfo("🏆", "JEE Advanced\nTest Series", "54+114 Qs • 3 Hours", Color.parseColor("#6D28D9"), Color.parseColor("#7C3AED")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "full_series", selectedExam) }
            )
            else -> if (selectedExam == "NEET") listOf(
                TestInfo("🧬", "NEET\nMock Test", "180 Qs • 3 Hours", Color.parseColor("#065F46"), Color.parseColor("#059669")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "mock_test", "NEET") }
            ) else listOf(
                TestInfo("📝", "JEE Main\nMock Test", "180 Qs • 3 Hours", Color.parseColor("#D97706"), Color.parseColor("#F59E0B")) { requiresHomeRefresh = true; MockTestListActivity.start(this, "mock_test", selectedExam) },
                TestInfo("🏆", "JEE Advanced\nMock Test", "54+114 Qs • 3 Hours", Color.parseColor("#1E3A8A"), Color.parseColor("#1D4ED8")) { requiresHomeRefresh = true; startActivity(android.content.Intent(this, com.jeeneet.mocktest.ui.simulation.SimulationIntroActivity::class.java)) }
            )
        }
        tests.forEach { test ->
            val card = uiCard(radius = Corner.L, elevation = Elev.S, onClick = test.action
            ).apply { layoutParams = LinearLayout.LayoutParams(148.dp, -2).also { it.marginEnd = Space.S.dp } }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(test.startColor, test.endColor)
                    cornerRadius = Corner.L.dpF
                }
                setPadding(10.dp, 10.dp, 10.dp, 10.dp)
            }
            inner.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(34.dp, 34.dp).also { it.bottomMargin = 6.dp }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#33FFFFFF"))
                }
                addView(TextView(this@MainActivity).apply {
                    text = test.icon; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                })
            })
            inner.addView(uiTextView(UiText.H3, test.title, Color.WHITE).apply {
                textSize = 12f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, Space.XS.dp)
            })
            inner.addView(uiTextView(UiText.CAPTION, test.meta, Color.parseColor("#CCFFFFFF")).apply {
                textSize = 10f; setPadding(0, 0, 0, Space.S.dp)
            })
            inner.addView(TextView(this).apply {
                text = "Start Test >"; textSize = 10f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(Color.parseColor("#33FFFFFF"), Corner.M)
                gravity = Gravity.CENTER
                setPadding(Space.S.dp, 5.dp, Space.S.dp, 5.dp)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
            card.addView(inner); row.addView(card)
        }
        scroll.addView(row); return scroll
    }

    private fun buildSubjectsSection(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }
        headerRow.addView(uiTextView(UiText.H2, "📚 Continue Practice", textPrimary).apply {
            textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "Switch to ${if (selectedExam == "JEE") "NEET" else "JEE"}"
            textSize = 11f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)), Corner.PILL)
            setPadding(Space.S.dp, 4.dp, Space.S.dp, 4.dp)
            setOnClickListener {
                switchExam(if (selectedExam == "JEE") "NEET" else "JEE")
            }
        })
        container.addView(headerRow)

        data class SubjectInfo(val icon: String, val name: String, val color: Int, val count: String)
        val subjects = if (selectedExam == "JEE")
            listOf(SubjectInfo("⚡", "Physics", ContextCompat.getColor(this, R.color.card_physics_start), "120 Questions"),
                   SubjectInfo("🧪", "Chemistry", ContextCompat.getColor(this, R.color.card_chemistry_start), "100 Questions"),
                   SubjectInfo("📐", "Maths", ContextCompat.getColor(this, R.color.card_maths_start), "100 Questions"))
        else
            listOf(SubjectInfo("⚡", "Physics", ContextCompat.getColor(this, R.color.card_physics_start), "120 Questions"),
                   SubjectInfo("🧪", "Chemistry", ContextCompat.getColor(this, R.color.card_chemistry_start), "100 Questions"),
                   SubjectInfo("🧬", "Biology", correctGreen, "120 Questions"))

        subjects.forEach { subj ->
            val card = uiCard(radius = Corner.L, elevation = Elev.S, background = bgSecondary,
                strokeDp = 0,
                onClick = { requiresHomeRefresh = true; ChapterwiseListActivity.start(this, subj.name, selectedExam) }
            ).apply { layoutParams = lpRow(bottomDp = Space.S) }
            
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
                minimumHeight = 132.dp
            }

            // Row 1: Subject Header (Icon + Name/Count + Premium CTA)
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            
            row1.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(42.dp, 42.dp).also { it.marginEnd = Space.M.dp }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(30, Color.red(subj.color), Color.green(subj.color), Color.blue(subj.color)))
                }
                addView(TextView(this@MainActivity).apply {
                    text = subj.icon; textSize = 18f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                })
            })
            
            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleCol.addView(uiTextView(UiText.H3, subj.name, textPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            titleCol.addView(uiTextView(UiText.CAPTION, subj.count, textTertiary).apply { textSize = 11f })
            row1.addView(titleCol)
            
            row1.addView(TextView(this).apply {
                text = "Practice →"
                textSize = 10f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(subj.color, Corner.PILL)
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                gravity = Gravity.CENTER
            })
            inner.addView(row1)
            
            // Subtle horizontal separator
            inner.addView(View(this).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                    it.topMargin = Space.M.dp; it.bottomMargin = Space.M.dp
                }
            })
            
            // Row 2: Insights (Weak Topic indicator + Best Score)
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.S.dp }
            }
            
            val weakTopic = when (subj.name) {
                "Physics" -> "Mechanics weak"
                "Chemistry" -> "Organic weak"
                "Maths" -> "Calculus weak"
                "Biology" -> "Genetics weak"
                else -> "Fundamentals weak"
            }
            row2.addView(uiTextView(UiText.CAPTION, "⚡ $weakTopic", Color.parseColor("#F59E0B")).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            
            val tvPct = uiTextView(UiText.CAPTION, "Best: --", textTertiary).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            row2.addView(tvPct)
            inner.addView(row2)
            
            // Row 3: Rich progress bar
            val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(subj.color)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.argb(20, Color.red(subj.color), Color.green(subj.color), Color.blue(subj.color)))
                layoutParams = LinearLayout.LayoutParams(-1, 6.dp)
            }
            inner.addView(pb)
            
            card.addView(inner)
            container.addView(card)

            lifecycleScope.launch(Dispatchers.IO) {
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                val best = MockTestDatabase.getInstance(this@MainActivity).testResultDao()
                    .getAllResultsOnce(uid).filter { it.examType == selectedExam && it.subject == subj.name }
                    .maxByOrNull { it.score }
                withContext(Dispatchers.Main) {
                    if (!isFinishing && best != null) {
                        val pct = if (best.maxScore > 0) (best.score / best.maxScore * 100).toInt() else 0
                        pb.progress = pct; tvPct.text = "Best: $pct%"
                        tvPct.setTextColor(subj.color)
                    }
                }
            }
        }
        return container
    }

    private fun buildDailyChallengeCard(): View {
        val isDone = PrefManager.isDailyQuizDoneToday(this)
        val card = uiCard(radius = Corner.L, elevation = Elev.M, background = bgSecondary).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1A1A2E"), Color.parseColor("#16213E"))
            ).apply { cornerRadius = Corner.L.dpF }
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        }
        val leftSection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        leftSection.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(40, 245, 158, 11)) }
            addView(TextView(this@MainActivity).apply {
                text = "🎯"; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(uiTextView(UiText.H3, "Daily Challenge", Color.WHITE).apply {
            textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(uiTextView(UiText.CAPTION, "10 Questions  •  5 Min  •  +10 Coins", Color.parseColor("#AAFFFFFF")).apply {
            textSize = 10f; setPadding(0, 2.dp, 0, 2.dp)
        })
        if (isDone) {
            textCol.addView(uiTextView(UiText.CAPTION, "✓ Completed Today", Color.parseColor("#10B981")).apply { textSize = 9f })
        } else {
            textCol.addView(uiTextView(UiText.CAPTION, "Attempts Left: 3", textMuted).apply { textSize = 9f })
        }
        leftSection.addView(textCol)
        inner.addView(leftSection)
        inner.addView(TextView(this).apply {
            text = if (isDone) "Done ✓" else "Start Quiz >"
            textSize = 12f
            setTextColor(if (isDone) Color.parseColor("#10B981") else Color.parseColor("#1A1A1A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(if (isDone) Color.argb(40, 16, 185, 129) else goldPrimary, Corner.PILL)
            setPadding(Space.M.dp, 8.dp, Space.M.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener {
                if (isDone) Toast.makeText(this@MainActivity, "Challenge already completed today!", Toast.LENGTH_SHORT).show()
                else { requiresHomeRefresh = true; AdManager.showInterstitial(this@MainActivity, bypassCooldown = true) { TestActivity.startDailyQuiz(this@MainActivity, selectedExam) } }
            }
        })
        card.addView(inner)
        return card
    }

    private fun buildAiToolsSection(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }
        headerRow.addView(uiTextView(UiText.H2, "🤖 AI Tools", textPrimary).apply {
            textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        container.addView(headerRow)

        val card = uiCard(radius = Corner.L, elevation = Elev.M, background = Color.parseColor("#0F172A"), onClick = { ScanActivity.start(this) }).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(50, 99, 102, 241)) }
            addView(TextView(this@MainActivity).apply {
                text = "🤖"; textSize = 22f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, "AI Doubt Solver", Color.WHITE).apply {
            textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, 4.dp)
        })
        textCol.addView(uiTextView(UiText.CAPTION, "Snap a question, get instant AI solution", Color.parseColor("#94A3B8")).apply {
            textSize = 11f
        })
        inner.addView(textCol)
        inner.addView(TextView(this).apply {
            text = "Ask Now >"; textSize = 12f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.argb(100, 99, 102, 241), Corner.PILL)
            setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        card.addView(inner)
        container.addView(card)
        return container
    }

    private fun buildYourProgressCard(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = Space.M.dp }
        }
        headerRow.addView(uiTextView(UiText.H2, "📊 Dynamic Analytics", textPrimary).apply {
            textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "Full Analytics >"; textSize = 11f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, com.jeeneet.mocktest.ui.analysis.AnalysisActivity::class.java)) }
        })
        container.addView(headerRow)

        val card = uiCard(radius = Corner.L, elevation = Elev.S, background = bgSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        // Top Row: Circular Accuracy Indicator + AIR Prediction & Rank Trend
        val analysisMainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Circular accuracy ring representation
        val circularGauge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(76.dp, 76.dp).also { it.marginEnd = Space.L.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(20, 16, 185, 129)) // Dim emerald alpha bg
                setStroke(4.dp, Color.parseColor("#10B981")) // thick emerald border
            }
        }
        val tvAccPct = TextView(this).apply {
            text = "--"
            textSize = 18f
            setTextColor(Color.parseColor("#10B981"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        circularGauge.addView(tvAccPct)
        analysisMainRow.addView(circularGauge)

        // 2. Rank Trend & AIR Predictor
        val rankCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rankCol.addView(uiTextView(UiText.OVERLINE, "AIR PREDICTION", goldPrimary).apply { textSize = 9f })
        val tvAirPrediction = uiTextView(UiText.H3, "🏆 AIR ~1,250", Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 15f
        }
        rankCol.addView(tvAirPrediction)
        rankCol.addView(uiTextView(UiText.CAPTION, "📈 Rank Trend: Upwards (+12%)", Color.parseColor("#10B981")).apply {
            textSize = 10f
            setPadding(0, 2.dp, 0, 0)
        })
        analysisMainRow.addView(rankCol)
        inner.addView(analysisMainRow)

        // Middle: Custom Weekly Graph Bar representation (highly addictive dynamic chart)
        val chartRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 90.dp).also {
                it.topMargin = Space.L.dp
                it.bottomMargin = Space.M.dp
            }
            background = roundedFill(Color.parseColor("#0F172A"), Corner.M) // deep charcoal graph background
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }

        val weeklyData = listOf(
            Pair("Mon", 45), Pair("Tue", 60), Pair("Wed", 52),
            Pair("Thu", 72), Pair("Fri", 68), Pair("Sat", 84), Pair("Sun", 80)
        )
        weeklyData.forEach { (day, valPct) ->
            val barCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            // Vertical scaled bar representing score
            val barHeight = (valPct * 0.55).toInt().dp
            val bar = View(this).apply {
                background = roundedFill(if (valPct >= 70) Color.parseColor("#10B981") else colorPrimary, 3f)
                layoutParams = LinearLayout.LayoutParams(14.dp, barHeight).also {
                    it.bottomMargin = 4.dp
                }
            }
            barCol.addView(bar)
            barCol.addView(uiTextView(UiText.OVERLINE, day, textMuted).apply { textSize = 8f })
            chartRow.addView(barCol)
        }
        inner.addView(chartRow)

        // Bottom summary stats row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val tvTests = TextView(this).apply {
            text = "Total Tests: --"
            textSize = 11f
            setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val tvAvg = TextView(this).apply {
            text = "Avg: --"
            textSize = 11f
            setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        statsRow.addView(tvTests)
        statsRow.addView(tvAvg)
        inner.addView(statsRow)

        card.addView(inner)
        container.addView(card)

        // Load stats — use cache when available, fall back to async DB query
        val cached = cachedTestResults
        if (cached != null) {
            val count = cached.size
            val avgScore = if (cached.isNotEmpty()) cached.map { if (it.maxScore > 0) it.score / it.maxScore * 100 else 0f }.average().toInt() else 0
            val acc = if (cached.isNotEmpty()) cached.map { if (it.attempted > 0) it.correct.toFloat() / it.attempted * 100 else 0f }.average().toInt() else 0
            tvAccPct.text = "$acc%"
            tvTests.text = "Total Tests: $count"
            tvAvg.text = "Avg: $avgScore%"
            
            // Mock dynamic AIR prediction based on actual performance
            val predictedAir = if (acc > 0) (100_000 * (1.0 - (acc / 100.0) * 0.99)).toInt().coerceIn(45, 95000) else 99000
            tvAirPrediction.text = "🏆 AIR ~$predictedAir"
        } else {
            lifecycleScope.launch {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                val results = withContext(Dispatchers.IO) {
                    MockTestDatabase.getInstance(this@MainActivity).testResultDao().getAllResultsOnce(uid)
                }
                withContext(Dispatchers.Main) {
                    if (!isFinishing) {
                        val count = results.size
                        val avgScore = if (results.isNotEmpty()) results.map { if (it.maxScore > 0) it.score / it.maxScore * 100 else 0f }.average().toInt() else 0
                        val acc = if (results.isNotEmpty()) results.map { if (it.attempted > 0) it.correct.toFloat() / it.attempted * 100 else 0f }.average().toInt() else 0
                        tvAccPct.text = "$acc%"
                        tvTests.text = "Total Tests: $count"
                        tvAvg.text = "Avg: $avgScore%"
                        
                        val predictedAir = if (acc > 0) (100_000 * (1.0 - (acc / 100.0) * 0.99)).toInt().coerceIn(45, 95000) else 99000
                        tvAirPrediction.text = "🏆 AIR ~$predictedAir"
                    }
                }
            }
        }
        return container
    }

    // ─── Vault Discussion / Poll card ─────────────────────────────────────────

    private fun buildVaultDiscussionCard(): View {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val isVaultDone = PrefManager.isDailyVaultDoneToday(this, selectedExam)

        val card = uiCard(
            radius = Corner.L, elevation = Elev.M, background = bgSecondary,
            strokeDp = 0,
            onClick = { com.jeeneet.mocktest.ui.vault.VaultDiscussionActivity.start(this, today) }
        ).apply { layoutParams = lpRow(bottomDp = Space.M) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        }

        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(30, Color.red(goldPrimary), Color.green(goldPrimary), Color.blue(goldPrimary)))
            }
            addView(TextView(this@MainActivity).apply {
                text = "💬"; textSize = 18f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.H3, "Vault Discussion", textPrimary).apply { textSize = 14f })
        textCol.addView(uiTextView(UiText.CAPTION,
            if (isVaultDone) "Share your approach with other aspirants!"
            else "Discuss today's PYQs & strategies",
            textTertiary).apply { textSize = 11f; setPadding(0, 2.dp, 0, 0) })
        inner.addView(textCol)

        inner.addView(TextView(this).apply {
            text = "OPEN"; textSize = 10f; setTextColor(goldPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.argb(30, Color.red(goldPrimary), Color.green(goldPrimary), Color.blue(goldPrimary)), Corner.PILL)
            setPadding(Space.S.dp, 4.dp, Space.S.dp, 4.dp)
        })

        card.addView(inner)
        return card
    }

    // ─── Skeleton / lazy loading ──────────────────────────────────────────────

    private fun showSkeletonContent() {
        if (!::contentLayout.isInitialized) return
        contentLayout.removeAllViews()

        fun skeletonCard(heightDp: Int, widthDp: Int = LinearLayout.LayoutParams.MATCH_PARENT, radius: Float = Corner.M): View {
            val v = uiCard(radius = radius, elevation = Elev.NONE, background = dividerColor)
            v.layoutParams = LinearLayout.LayoutParams(if (widthDp == LinearLayout.LayoutParams.MATCH_PARENT) LinearLayout.LayoutParams.MATCH_PARENT else widthDp.dp, heightDp.dp).also { it.bottomMargin = Space.M.dp }
            android.animation.ObjectAnimator.ofFloat(v, "alpha", 0.25f, 0.6f).apply {
                duration = 900; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; start()
            }
            return v
        }

        // Hero/Resume card skeleton
        contentLayout.addView(skeletonCard(130, radius = Corner.L))

        // Main Mock test and PYQ card skeletons
        contentLayout.addView(skeletonCard(100, radius = Corner.L))
        contentLayout.addView(skeletonCard(100, radius = Corner.L))

        // Subjects list skeletons
        contentLayout.addView(skeletonCard(70, radius = Corner.L))
        contentLayout.addView(skeletonCard(70, radius = Corner.L))
        contentLayout.addView(skeletonCard(70, radius = Corner.L))

        // Progress card skeleton
        contentLayout.addView(skeletonCard(100, radius = Corner.L))
    }
}
