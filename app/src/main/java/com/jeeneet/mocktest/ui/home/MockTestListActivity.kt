package com.jeeneet.mocktest.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.data.model.TestResult
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.result.ResultActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.NetworkUtils
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MockTestListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_EXAM = "extra_exam"
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"

        fun start(context: Context, type: String, exam: String) {
            context.startActivity(Intent(context, MockTestListActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_EXAM, exam)
            })
        }
    }

    private lateinit var contentContainer: FrameLayout
    private lateinit var tabLayout: TabLayout
    private var nativeAdContainer: FrameLayout? = null
    private val type by lazy { intent.getStringExtra(EXTRA_TYPE) ?: "mock_test" }
    private val exam by lazy { intent.getStringExtra(EXTRA_EXAM) ?: "JEE" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0)
        showTab(initialTab)
        if (initialTab != 0) tabLayout.getTabAt(initialTab)?.select()
        AdManager.loadRewarded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0)
        showTab(tab)
        tabLayout.getTabAt(tab)?.select()
    }

    private fun screenTitle(): String = when (type) {
        "full_series" -> "$exam Full Test Series"
        "pyqs"        -> "$exam PYQs"
        else          -> "$exam Mock Test"
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(uiColoredToolbar(screenTitle(), colorPrimary, onBack = { finish() }))
        root.addView(buildTabs())

        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(contentContainer)
        return root
    }

    private fun buildTabs(): TabLayout = TabLayout(this).also { tabLayout = it }.apply {
        setBackgroundColor(colorPrimary)
        setTabTextColors(Color.parseColor("#AAFFFFFF"), Color.WHITE)
        setSelectedTabIndicatorColor(Color.WHITE)
        tabMode = TabLayout.MODE_FIXED
        tabGravity = TabLayout.GRAVITY_FILL
        addTab(newTab().setText("Latest"))
        addTab(newTab().setText("Category"))
        addTab(newTab().setText("Result"))
        layoutParams = lpRow()
        addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showTab(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showTab(position: Int) {
        // Tabs are rebuilt from scratch on every switch — release the previous tab's native
        // ad (if any) before its container is discarded, or it leaks (same class of bug as the
        // banner/native-ad lifecycle issues fixed earlier).
        nativeAdContainer?.let { AdManager.destroyNativeAd(it) }
        nativeAdContainer = null
        contentContainer.removeAllViews()
        when (position) {
            0 -> showLatestTab()
            1 -> showCategoryTab()
            2 -> showResultTab()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeAdContainer?.let { AdManager.destroyNativeAd(it) }
    }

    private fun makeScrollList(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.XXL.dp)
        }
        scroll.addView(list)
        return scroll to list
    }

    // ─── Latest Tab ──────────────────────────────────────────────────────────

    private fun showLatestTab() {
        val (scroll, list) = makeScrollList()
        val testSeries = buildTestSeries()
        if (testSeries.isEmpty()) {
            list.addView(uiEmptyView("📋", "Questions Coming Soon!",
                "Our team is adding fresh questions.\nPlease check back in a little while."))
        } else {
            testSeries.forEachIndexed { index, (subject, testNum) ->
                list.addView(buildTestItemCard(subject, testNum, index))
                // One native ad slot after the 6th item — only on lists long enough that it
                // doesn't dominate the screen. Not repeated further down (avoids stacking
                // concurrent native ad loads, which starve each other — see AdManager notes).
                if (index == 5 && testSeries.size > 6) {
                    val adContainer = FrameLayout(this).apply { layoutParams = lpRow(bottomDp = 10) }
                    list.addView(adContainer)
                    nativeAdContainer = adContainer
                    AdManager.loadNativeAd(this, adContainer)
                }
            }
        }
        contentContainer.addView(scroll)
    }

    private fun buildTestSeries(): List<Pair<String, Int>> {
        val subjects = when {
            exam == "JEE" && type == "mock_test"   -> listOf("Chemistry", "Physics", "Mathematics")
            exam == "JEE" && type == "full_series" -> listOf("JEE Mains", "JEE Advanced")
            exam == "JEE" && type == "pyqs"        -> listOf("Chemistry", "Physics", "Mathematics")
            exam == "NEET" && type == "mock_test"  -> listOf("Biology", "Chemistry", "Physics")
            exam == "NEET" && type == "full_series"-> listOf("NEET Full Test")
            exam == "NEET" && type == "pyqs"       -> listOf("Biology", "Chemistry", "Physics")
            else                                    -> listOf("Physics", "Chemistry", "Mathematics")
        }
        val result = mutableListOf<Pair<String, Int>>()
        val startNum = 200
        repeat(10) { i ->
            result.add(Pair(subjects[i % subjects.size], startNum + (10 - i)))
        }
        return result
    }

    private fun buildTestItemCard(subject: String, testNum: Int, index: Int): View {
        val subjectForTest = when (subject) {
            "Mathematics" -> "Maths"
            "JEE Mains", "JEE Advanced", "NEET Full Test" -> null
            else -> subject
        }
        val (iconColor, iconEmoji) = subjectStyle(subject)

        val card = uiCard(
            radius = Corner.M,
            elevation = 1f,
            background = bgSecondary,
            strokeDp = 1,
            strokeColor = dividerColor,
            onClick = { startTestWithPaywall(subjectForTest) }
        ).apply {
            layoutParams = lpRow(bottomDp = 10)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 14.dp, 14.dp, Space.M.dp)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(uiIconBox(40, tintedBg(iconColor), emoji = iconEmoji, emojiSize = 16f, radius = 10f).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = Space.M.dp
        })
        topRow.addView(uiTextView(UiText.H3, "$subject Test $testNum", textPrimary).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            imageTintList = android.content.res.ColorStateList.valueOf(textMuted)
            layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp)
        })
        inner.addView(topRow)

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Space.S.dp, 0, 0)
        }
        badgeRow.addView(uiBadge("Not Attempted",
            Color.parseColor("#FFEF4444"), Color.parseColor("#1AEF4444")))
        badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val attemptsK = String.format("%.2fK", (500 + index * 137) / 1000.0)
        badgeRow.addView(uiBadge("$attemptsK Attempts", colorPrimary, tintedBg(colorPrimary, alpha = 26)))
        inner.addView(badgeRow)

        card.addView(inner)
        return card
    }

    // ─── Category Tab ─────────────────────────────────────────────────────────

    private fun showCategoryTab() {
        val subjects = when (exam) {
            "NEET" -> listOf(
                Triple("🧬", "Biology",   "Master chapters from Botany & Zoology"),
                Triple("🧪", "Chemistry", "Organic, Inorganic & Physical Chemistry"),
                Triple("⚡", "Physics",   "Mechanics, Optics, Modern Physics & more")
            )
            else -> listOf(
                Triple("⚡", "Physics",   "Mechanics, Optics, Modern Physics & more"),
                Triple("🧪", "Chemistry", "Organic, Inorganic & Physical Chemistry"),
                Triple("📐", "Maths",     "Calculus, Algebra, Coordinate Geometry & more")
            )
        }

        val (scroll, list) = makeScrollList()
        subjects.forEach { (icon, subject, desc) ->
            val (iconColor, _) = subjectStyle(subject)
            val card = uiCard(
                radius = Corner.M,
                elevation = 1f,
                background = bgSecondary,
                strokeDp = 1,
                strokeColor = dividerColor,
                onClick = { ChapterwiseListActivity.start(this@MockTestListActivity, subject, exam) }
            ).apply {
                layoutParams = lpRow(bottomDp = 10)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, Space.L.dp, 14.dp, Space.L.dp)
            }
            inner.addView(uiIconBox(44, tintedBg(iconColor), emoji = icon, emojiSize = 18f, radius = 10f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 14.dp
            })
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(uiTextView(UiText.H3, subject, textPrimary))
            textCol.addView(uiTextView(UiText.CAPTION, desc, textTertiary).apply {
                setPadding(0, Space.XS.dp, 0, 0)
            })
            inner.addView(textCol)
            inner.addView(TextView(this).apply {
                text = "›"; textSize = 22f; setTextColor(colorPrimary)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(inner)
            list.addView(card)
        }
        contentContainer.addView(scroll)
    }

    // ─── Result Tab ──────────────────────────────────────────────────────────

    private fun showResultTab() {
        val (scroll, list) = makeScrollList()
        lifecycleScope.launch(Dispatchers.IO) {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val results = MockTestDatabase.getInstance(this@MockTestListActivity)
                .testResultDao().getAllResultsOnce(uid)
                .filter { it.examType == exam }
                .sortedByDescending { it.completedAt }
                .take(20)
            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    list.addView(uiEmptyView("📋", "No Results Yet",
                        "Complete a test to see your\nscores and analysis here."))
                } else {
                    results.forEach { list.addView(buildResultItemCard(it)) }
                }
            }
        }
        contentContainer.addView(scroll)
    }

    private fun buildResultItemCard(result: TestResult): View {
        val card = uiCard(
            radius = Corner.M,
            elevation = 1f,
            background = bgSecondary,
            strokeDp = 1,
            strokeColor = dividerColor,
            onClick = { ResultActivity.start(this@MockTestListActivity, result.id.toLong()) }
        ).apply {
            layoutParams = lpRow(bottomDp = 10)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 14.dp, 14.dp, Space.M.dp)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val subjectLabel = if (result.subject.isEmpty() || result.subject == "null") "Full Mock" else result.subject
        topRow.addView(uiTextView(UiText.H3, "${result.examType} – $subjectLabel", textPrimary).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(uiTextView(UiText.H3, "%.0f / %.0f".format(result.score, result.maxScore), colorPrimary).apply {
            textSize = 16f
        })
        inner.addView(topRow)

        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6.dp, 0, 0)
            addView(uiBadge("✓ ${result.correct} Correct", correctGreen, tintedBg(correctGreen, alpha = 26)))
            addView(View(this@MockTestListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(Space.S.dp, 1)
            })
            addView(uiBadge("✗ ${result.wrong} Wrong", wrongRed, tintedBg(wrongRed, alpha = 26)))
        })

        card.addView(inner)
        return card
    }

    // ─── Paywall / start test ─────────────────────────────────────────────────

    private fun startTestWithPaywall(subject: String?) {
        if (!NetworkUtils.isNetworkAvailable(this) && !PrefManager.isEffectivelyPremium(this)) {
            showNoInternetToast()
            return
        }

        if (PrefManager.hasSavedTestSession(this)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Incomplete Test Found")
                .setMessage("You have an incomplete test in progress. Would you like to resume it, or discard it to start a new one?")
                .setPositiveButton("Resume Test") { _, _ -> TestActivity.resume(this) }
                .setNegativeButton("Discard & Start New") { _, _ ->
                    PrefManager.clearSavedTestSession(this)
                    startTestWithPaywall(subject) // Retry after clearing
                }
                .show()
            return
        }

        // Phase 3: Daily Question Quota (Subject/Chapter Tests)
        val isPremium = PrefManager.isAllAccessUnlocked(this)
        val solvedToday = PrefManager.getTotalQuestionsSolvedToday(this)
        val isChapterTest = subject != null && subject != "JEE Mains" && subject != "JEE Advanced" && subject != "NEET Full Test"

        if (isChapterTest && !isPremium && solvedToday >= 50) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Daily Question Quota Reached")
                .setMessage("You have solved your 50 free questions for today. Watch a short video to unlock another 50 questions, or upgrade to Premium for unlimited access!")
                .setPositiveButton("Watch Ad") { _, _ ->
                    if (!PrefManager.canWatchAdForExtraQuestions(this)) {
                        Toast.makeText(this, "Daily extra question limit reached. Upgrade to Premium for unlimited access!", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    AdManager.showRewarded(this, onRewarded = {
                        PrefManager.incrementAdUsageCount(this, "extra_questions")
                        // Temporarily bypass the check for this specific launch
                        TestActivity.start(this, exam, subject, adUnlocked = true)
                    }, onNotAvailable = {
                        Toast.makeText(this, "Ad not available. Try again later.", Toast.LENGTH_SHORT).show()
                    }, placement = "extra_questions")
                }
                .setNeutralButton("Upgrade to Premium") { _, _ ->
                    startActivity(Intent(this, ShopActivity::class.java))
                }
                .setNegativeButton("Maybe Later", null)
                .show()
            return
        }

        val productId = when {
            subject == "Physics"   && exam == "NEET" -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_PHYSICS_PACK
            subject == "Physics"                     -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_PHYSICS_PACK
            subject == "Chemistry" && exam == "NEET" -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_CHEM_PACK
            subject == "Chemistry"                   -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_CHEM_PACK
            subject == "Maths"                       -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_MATHS_PACK
            subject == "Biology"                     -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_BIO_PACK
            else                                     -> com.jeeneet.mocktest.data.model.IAPProducts.ALL_ACCESS_YEARLY
        }
        val isUnlocked = PrefManager.isAllAccessUnlocked(this) ||
            (subject != null && PrefManager.isPackUnlocked(this, productId))

        if (isUnlocked) {
            TestActivity.start(this, exam, subject)
            return
        }

        // If subject is null (Full Mock), it definitely requires purchase if not already unlocked via All Access
        if (subject == null && !isUnlocked) {
            // Fall through to paywall below
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XXL.dp, Space.S.dp, Space.XXL.dp, Space.S.dp)
        }
        dialogView.addView(uiTextView(UiText.BODY,
            "Unlock the $subject pack for ₹49 and get 200+ exam-ready questions.",
            textPrimary
        ).apply {
            layoutParams = lpRow(bottomDp = Space.L)
        })
        val adContainer = FrameLayout(this).apply { layoutParams = lpRow() }
        AdManager.showBanner(this, adContainer)
        dialogView.addView(adContainer)

        val alreadyUsedToday = PrefManager.hasUsedAdFreeUnlockToday(this)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Unlock $subject Pack")
            .setView(dialogView)
            .setPositiveButton("Buy ₹49") { _, _ -> ShopActivity.start(this) }
            .setNeutralButton(if (alreadyUsedToday) "Free try used ✓" else "Watch Ad (Free Today)") { _, _ ->
                if (alreadyUsedToday) return@setNeutralButton
                AnalyticsManager.rewardedAdShown(this, "mock_test_unlock")
                AdManager.showRewarded(
                    activity = this,
                    onRewarded = {
                        PrefManager.markAdFreeUnlockUsed(this)
                        TestActivity.start(this, exam, subject, adUnlocked = true)
                    },
                    onNotAvailable = { Toast.makeText(this, "No ad available. Try again later.", Toast.LENGTH_SHORT).show() },
                    onLoading = { Toast.makeText(this, "Loading ad…", Toast.LENGTH_SHORT).show() },
                    placement = "unlock_pack"
                )
            }
            .setNegativeButton("Try 10 Free Qs") { _, _ ->
                // Limit to 10 questions for the free trial path
                AdManager.showInterstitial(this, bypassCooldown = true) {
                    TestActivity.start(this, exam, subject, totalQuestions = 10)
                }
            }
            .create()
        // Dialog banners are never reshown — destroy on dismiss so the AdView doesn't leak
        // its WebView across repeated paywall triggers.
        dialog.setOnDismissListener { AdManager.destroyBanner(adContainer) }
        dialog.show()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun subjectStyle(subject: String): Pair<Int, String> = when (subject) {
        "Physics"           -> Pair(ContextCompat.getColor(this, R.color.card_physics_start), "⚡")
        "Chemistry"         -> Pair(ContextCompat.getColor(this, R.color.card_chemistry_start), "🧪")
        "Maths", "Mathematics" -> Pair(ContextCompat.getColor(this, R.color.card_maths_start), "📐")
        "Biology"           -> Pair(correctGreen, "🧬")
        else                -> Pair(colorPrimary, "📋")
    }

    private fun tintedBg(color: Int, alpha: Int = 30): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
