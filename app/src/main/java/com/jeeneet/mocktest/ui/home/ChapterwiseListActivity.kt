package com.jeeneet.mocktest.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

class ChapterwiseListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SUBJECT = "extra_subject"
        private const val EXTRA_EXAM    = "extra_exam"

        fun start(context: Context, subject: String, exam: String) {
            context.startActivity(Intent(context, ChapterwiseListActivity::class.java).apply {
                putExtra(EXTRA_SUBJECT, subject)
                putExtra(EXTRA_EXAM, exam)
            })
        }
    }

    private lateinit var contentContainer: FrameLayout
    private var nativeAdContainer: FrameLayout? = null
    private lateinit var tvOverallProgress: TextView
    private lateinit var tvBottomSubtitle: TextView
    private var tabIndicators = mutableListOf<View>()
    private var tabLabels    = mutableListOf<TextView>()

    private val subject by lazy { intent.getStringExtra(EXTRA_SUBJECT) ?: "Physics" }
    private val exam    by lazy { intent.getStringExtra(EXTRA_EXAM) ?: "JEE" }

    // 8-gradient palette cycling for chapter number circles
    private val chapterGradients = arrayOf(
        intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#8B5CF6")),
        intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#6366F1")),
        intArrayOf(Color.parseColor("#EC4899"), Color.parseColor("#F43F5E")),
        intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#F97316")),
        intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#059669")),
        intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#F97316")),
        intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#EC4899")),
        intArrayOf(Color.parseColor("#06B6D4"), Color.parseColor("#3B82F6"))
    )

    private val subjectAccent: Int get() = when (subject) {
        "Physics"   -> ContextCompat.getColor(this, R.color.card_physics_start)
        "Chemistry" -> ContextCompat.getColor(this, R.color.card_chemistry_start)
        "Maths"     -> ContextCompat.getColor(this, R.color.card_maths_start)
        "Biology"   -> correctGreen
        else        -> colorPrimary
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        AdManager.loadRewarded(this)
        loadOverallProgress()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(buildHeader())
        root.addView(buildTabBar())
        root.addView(contentContainer)
        root.addView(buildBottomProgressBar())
        selectTab(1)   // default: Category
        return root
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Back arrow
        row.addView(TextView(this).apply {
            text = "‹"; textSize = 30f; setTextColor(textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, Space.M.dp, 0)
            setOnClickListener { finish() }
        })

        // Subject gradient circle with emoji
        row.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = chapterGradients[0]
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@ChapterwiseListActivity).apply {
                text = subjectIcon(); textSize = 22f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })

        // Subject name + subtitle
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.H2, subject, textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(uiTextView(UiText.CAPTION, subjectSubtitle(), textMuted).apply {
            setPadding(0, 2.dp, 0, 0)
        })
        row.addView(textCol)

        // Overall progress counter (right side)
        val progressCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tvOverallProgress = uiTextView(UiText.H2, "—", colorPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = 20f
            gravity = Gravity.END
        }
        progressCol.addView(tvOverallProgress)
        progressCol.addView(uiTextView(UiText.OVERLINE, "Overall\nProgress", textMuted).apply {
            gravity = Gravity.END; textSize = 9f
            setPadding(0, 2.dp, 0, 0)
        })
        row.addView(progressCol)
        header.addView(row)

        // Thin bottom divider
        header.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(-1, 1).also { it.topMargin = Space.M.dp }
        })
        return header
    }

    private fun loadOverallProgress() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val results = MockTestDatabase.getInstance(this@ChapterwiseListActivity)
                .testResultDao().getAllResultsOnce(uid)
                .filter { it.examType == exam && it.subject == subject }
            val attempted = results.size.coerceAtMost(chaptersForSubject(subject).size)
            val total = chaptersForSubject(subject).size
            withContext(Dispatchers.Main) {
                tvOverallProgress.text = "$attempted / $total"
                if (::tvBottomSubtitle.isInitialized) {
                    tvBottomSubtitle.text = "You've covered $attempted / $total topics in $subject"
                }
            }
        }
    }

    // ─── Tab bar ─────────────────────────────────────────────────────────────

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgSecondary)
        }
        listOf("LATEST", "CATEGORY", "RESULT").forEachIndexed { i, label ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                isClickable = true; isFocusable = true
                setPadding(0, Space.M.dp, 0, 0)
                setOnClickListener { selectTab(i) }
            }
            val tv = uiTextView(UiText.OVERLINE, label, textMuted).apply {
                textSize = 11f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, Space.S.dp)
            }
            cell.addView(tv)
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 3.dp)
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = 2f
                }
            }
            cell.addView(indicator)
            tabLabels.add(tv)
            tabIndicators.add(indicator)
            bar.addView(cell)
        }
        return bar
    }

    private fun selectTab(index: Int) {
        tabLabels.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == index) colorPrimary else textMuted)
        }
        tabIndicators.forEachIndexed { i, v ->
            (v.background as? GradientDrawable)?.setColor(if (i == index) colorPrimary else Color.TRANSPARENT)
        }
        // Tabs are rebuilt from scratch on every switch — release the previous tab's native
        // ad (if any) before its container is discarded, or it leaks.
        nativeAdContainer?.let { AdManager.destroyNativeAd(it) }
        nativeAdContainer = null
        contentContainer.removeAllViews()
        when (index) {
            0 -> showLatestTab()
            1 -> showCategoryTab()
            2 -> showResultTab()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeAdContainer?.let { AdManager.destroyNativeAd(it) }
    }

    // ─── Bottom floating progress bar ─────────────────────────────────────────

    private fun buildBottomProgressBar(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        // Top divider line
        container.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        }

        bar.addView(TextView(this).apply {
            text = "📋"; textSize = 20f
            setPadding(0, 0, Space.M.dp, 0)
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.BODY, "Chapterwise Progress", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = 13f
        })
        tvBottomSubtitle = uiTextView(UiText.CAPTION,
            "You've covered — / ${chaptersForSubject(subject).size} topics in $subject", textMuted).apply {
            textSize = 11f
        }
        textCol.addView(tvBottomSubtitle)
        bar.addView(textCol)

        bar.addView(TextView(this).apply {
            text = "View Summary ›"; textSize = 12f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setOnClickListener {
                startActivity(android.content.Intent(this@ChapterwiseListActivity,
                    com.jeeneet.mocktest.ui.analysis.AnalysisActivity::class.java))
            }
        })
        container.addView(bar)
        return container
    }

    private fun makeScrollList(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
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
        val chapters = chaptersForSubject(subject).take(10)
        if (chapters.isEmpty()) {
            list.addView(uiEmptyView(subjectIcon(), "Questions Coming Soon!",
                "Chapter-wise questions are being\nprepared. Please check back soon."))
        } else {
            chapters.forEachIndexed { index, chapter ->
                list.addView(buildLatestTestCard(chapter, 200 + (10 - index), index))
                // One native ad slot after the 6th item — not repeated further down (avoids
                // stacking concurrent native ad loads on one screen, which starve each other).
                if (index == 5 && chapters.size > 6) {
                    val adContainer = FrameLayout(this).apply { layoutParams = lpRow(bottomDp = 10) }
                    list.addView(adContainer)
                    nativeAdContainer = adContainer
                    AdManager.loadNativeAd(this, adContainer)
                }
            }
        }
        contentContainer.addView(scroll)
    }

    private fun buildLatestTestCard(chapter: String, testNum: Int, index: Int): View {
        val gradColors = chapterGradients[index % chapterGradients.size]
        val card = uiCard(
            radius = Corner.M, elevation = 1f, background = bgSecondary,
            strokeDp = 0,
            onClick = {
                AnalyticsManager.chapterClicked(this, subject, chapter)
                showChapterPreScreen(chapter, index)
            }
        ).apply { layoutParams = lpRow(bottomDp = 10) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.M.dp)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        // Gradient circle with subject icon
        topRow.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; colors = gradColors
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@ChapterwiseListActivity).apply {
                text = subjectIcon(); textSize = 18f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })
        topRow.addView(uiTextView(UiText.H3, "$subject Test $testNum", textPrimary).apply {
            textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        inner.addView(topRow)

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Space.S.dp, 0, 0)
        }
        badgeRow.addView(uiBadge("Not Attempted", Color.parseColor("#FFEF4444"), Color.parseColor("#1AEF4444")))
        badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val attemptsK = String.format("%.2fK", (300 + index * 89) / 1000.0)
        badgeRow.addView(uiBadge("$attemptsK Attempts", colorPrimary, tintedBg(colorPrimary, alpha = 26)))
        inner.addView(badgeRow)

        val extraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Space.XS.dp, 0, 0)
        }
        if (index < 3) extraRow.addView(uiBadge("🔥 Trending", Color.parseColor("#F59E0B"),
            tintedBg(Color.parseColor("#F59E0B"), alpha = 28)),
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp })
        if (index == 0) extraRow.addView(uiBadge("🆕 New", Color.parseColor("#10B981"),
            tintedBg(Color.parseColor("#10B981"), alpha = 28)),
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp })
        val qCount = when (index % 3) { 0 -> 30; 1 -> 45; else -> 60 }
        extraRow.addView(uiBadge("⏱ ${qCount / 6} min Quick Test", textTertiary, tintedBg(textTertiary, alpha = 20)))
        inner.addView(extraRow)

        card.addView(inner)
        return card
    }

    // ─── Category Tab ─────────────────────────────────────────────────────────

    private fun showCategoryTab() {
        val chapters = chaptersForSubject(subject)
        val (scroll, list) = makeScrollList()
        // Load subject-level results for stats display
        lifecycleScope.launch(Dispatchers.IO) {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val results = MockTestDatabase.getInstance(this@ChapterwiseListActivity)
                .testResultDao().getAllResultsOnce(uid)
                .filter { it.examType == exam && it.subject == subject }
                .sortedByDescending { it.score }
            withContext(Dispatchers.Main) {
                chapters.forEachIndexed { index, chapter ->
                    list.addView(buildChapterCard(index + 1, chapter, index, results))
                    // One native ad slot after the 6th item — not repeated further down.
                    if (index == 5 && chapters.size > 6) {
                        val adContainer = FrameLayout(this@ChapterwiseListActivity).apply { layoutParams = lpRow(bottomDp = 10) }
                        list.addView(adContainer)
                        nativeAdContainer = adContainer
                        AdManager.loadNativeAd(this@ChapterwiseListActivity, adContainer)
                    }
                }
            }
        }
        contentContainer.addView(scroll)
    }

    private val currentProductId: String by lazy {
        when (subject) {
            "Physics"   -> if (exam == "NEET") com.jeeneet.mocktest.data.model.IAPProducts.NEET_PHYSICS_PACK
                           else com.jeeneet.mocktest.data.model.IAPProducts.JEE_PHYSICS_PACK
            "Chemistry" -> if (exam == "NEET") com.jeeneet.mocktest.data.model.IAPProducts.NEET_CHEM_PACK
                           else com.jeeneet.mocktest.data.model.IAPProducts.JEE_CHEM_PACK
            "Maths"     -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_MATHS_PACK
            "Biology"   -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_BIO_PACK
            else        -> com.jeeneet.mocktest.data.model.IAPProducts.ALL_ACCESS_YEARLY
        }
    }

    private fun buildChapterCard(
        number: Int, chapter: String, index: Int, results: List<TestResult>
    ): View {
        val isFreeChapter = index < 3
        val gradColors = chapterGradients[index % chapterGradients.size]

        // Use subject-level best result as a chapter proxy (best available data without schema change)
        val result = results.getOrNull(index)
        val totalQs = 30
        val attemptedQs = result?.attempted ?: 0
        val scorePercent = if (result != null && result.maxScore > 0)
            (result.score / result.maxScore * 100).toInt() else 0
        val progressFraction = attemptedQs.toFloat() / totalQs

        val card = uiCard(
            radius = Corner.M, elevation = 0f, background = bgSecondary,
            strokeDp = 0,
            onClick = {
                AnalyticsManager.chapterClicked(this, subject, chapter)
                showChapterPreScreen(chapter, index)
            }
        ).apply { layoutParams = lpRow(bottomDp = Space.S) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        // Gradient numbered circle
        inner.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = Space.M.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; colors = gradColors
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@ChapterwiseListActivity).apply {
                text = number.toString(); textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        })

        // Text column: chapter name, attempted count, progress bar
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textCol.addView(uiTextView(UiText.BODY, chapter, textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = 14f
        })
        if (attemptedQs > 0) {
            textCol.addView(uiTextView(UiText.CAPTION,
                "Attempted: $attemptedQs / $totalQs questions", textMuted).apply {
                textSize = 11f; setPadding(0, 2.dp, 0, 4.dp)
            })
            textCol.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = (progressFraction * 100).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(gradColors[0])
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.argb(40, Color.red(gradColors[0]), Color.green(gradColors[0]), Color.blue(gradColors[0])))
                layoutParams = LinearLayout.LayoutParams(-1, 5.dp)
            })
        } else {
            textCol.addView(uiTextView(UiText.CAPTION, "Not attempted yet", textMuted).apply {
                textSize = 11f; setPadding(0, 2.dp, 0, 0)
            })
        }
        inner.addView(textCol)

        // Right column: percentage + FREE / lock
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = Space.M.dp }
        }
        if (scorePercent > 0) {
            rightCol.addView(uiTextView(UiText.H3, "$scorePercent%", gradColors[0]).apply {
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.END
            })
        }
        if (isFreeChapter) {
            rightCol.addView(TextView(this).apply {
                text = "FREE"; textSize = 10f
                setTextColor(Color.parseColor("#10B981"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A10B981"))
                    cornerRadius = Corner.S.dpF
                    setStroke(1.dp, Color.parseColor("#4010B981"))
                }
                setPadding(6.dp, 2.dp, 6.dp, 2.dp)
                gravity = Gravity.END
            })
        } else {
            val isOwned = PrefManager.isAllAccessUnlocked(this) || PrefManager.isPackUnlocked(this, currentProductId)
            rightCol.addView(TextView(this).apply {
                text = if (isOwned) "›" else "🔒"
                textSize = if (isOwned) 22f else 14f
                setTextColor(if (isOwned) gradColors[0] else textMuted)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
        }
        inner.addView(rightCol)
        card.addView(inner)
        return card
    }

    // ─── Result Tab ──────────────────────────────────────────────────────────

    private fun showResultTab() {
        val (scroll, list) = makeScrollList()
        lifecycleScope.launch(Dispatchers.IO) {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val results = MockTestDatabase.getInstance(this@ChapterwiseListActivity)
                .testResultDao().getAllResultsOnce(uid)
                .filter { it.examType == exam && it.subject == subject }
                .sortedByDescending { it.completedAt }
                .take(20)
            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    list.addView(uiEmptyView(subjectIcon(), "No Results Yet",
                        "Complete a $subject chapter test\nto see your scores here."))
                } else {
                    results.forEach { list.addView(buildResultCard(it)) }
                }
            }
        }
        contentContainer.addView(scroll)
    }

    private fun buildResultCard(result: TestResult): View {
        val accuracy = if (result.attempted > 0) result.correct * 100 / result.attempted else 0
        val perfColor = when {
            accuracy >= 70 -> Color.parseColor("#10B981")
            accuracy >= 50 -> Color.parseColor("#F59E0B")
            else           -> Color.parseColor("#EF4444")
        }
        val perfLabel = when {
            accuracy >= 70 -> "🟢 Good"
            accuracy >= 50 -> "🟡 Average"
            else           -> "🔴 Needs Work"
        }
        val card = uiCard(
            radius = Corner.M, elevation = 1f, background = bgSecondary,
            strokeDp = 1, strokeColor = tintedBg(perfColor, alpha = 60),
            onClick = { ResultActivity.start(this@ChapterwiseListActivity, result.id.toLong()) }
        ).apply { layoutParams = lpRow(bottomDp = 10) }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 14.dp, 14.dp, Space.M.dp)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = lpRow(bottomDp = 6)
        }
        topRow.addView(uiTextView(UiText.H3, result.subject.ifEmpty { "Full Mock" }, textPrimary).apply {
            textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        topRow.addView(uiTextView(UiText.H3, "%.0f / %.0f".format(result.score, result.maxScore), perfColor).apply {
            textSize = 16f
        })
        inner.addView(topRow)
        val scorePct = if (result.maxScore > 0) (result.score / result.maxScore * 100).toInt() else 0
        inner.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = scorePct
            progressTintList = android.content.res.ColorStateList.valueOf(perfColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(tintedBg(perfColor, alpha = 30))
            layoutParams = LinearLayout.LayoutParams(-1, 5.dp).also { it.bottomMargin = 8.dp }
        })
        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(uiBadge("✓ ${result.correct}", correctGreen, tintedBg(correctGreen, alpha = 26)),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp })
            addView(uiBadge("✗ ${result.wrong}", wrongRed, tintedBg(wrongRed, alpha = 26)),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp })
            addView(uiBadge("🎯 $accuracy%", perfColor, tintedBg(perfColor, alpha = 26)),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp })
            addView(uiBadge(perfLabel, perfColor, tintedBg(perfColor, alpha = 20)))
        })
        card.addView(inner)
        return card
    }

    // ─── Chapter pre-screen + test start ─────────────────────────────────────

    private fun showChapterPreScreen(chapter: String, index: Int) {
        AnalyticsManager.chapterPreScreenShown(this, subject, chapter)
        val qCount   = when (index % 3) { 0 -> 30; 1 -> 45; else -> 60 }
        val timeMins = qCount
        val difficulty = when {
            index < 5  -> "Easy–Medium"
            index < 12 -> "Medium"
            else       -> "Medium–Hard"
        }
        val dlgContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XXL.dp, Space.L.dp, Space.XXL.dp, Space.M.dp)
        }
        dlgContent.addView(uiTextView(UiText.CAPTION, "📚 $chapter", textTertiary).apply {
            setPadding(0, 0, 0, Space.L.dp)
        })
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(tintedBg(subjectAccent, alpha = 20), Corner.M)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            layoutParams = lpRow(bottomDp = Space.L)
        }
        fun infoRow(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutParams = lpRow(bottomDp = Space.XS)
            addView(uiTextView(UiText.CAPTION, label, textTertiary).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(uiTextView(UiText.CAPTION, value, textPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
        }
        grid.addView(infoRow("Questions", "$qCount"))
        grid.addView(infoRow("Time Limit", "$timeMins min"))
        grid.addView(infoRow("Difficulty", difficulty))
        val tvLastVal = uiTextView(UiText.CAPTION, "Loading…", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutParams = lpRow()
            addView(uiTextView(UiText.CAPTION, "Last Score", textTertiary).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(tvLastVal)
        })
        dlgContent.addView(grid)
        lifecycleScope.launch(Dispatchers.IO) {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val last = MockTestDatabase.getInstance(this@ChapterwiseListActivity)
                .testResultDao().getAllResultsOnce(uid)
                .filter { it.subject == subject }
                .maxByOrNull { it.completedAt }
            withContext(Dispatchers.Main) {
                tvLastVal.text = if (last != null)
                    "%.0f / %.0f".format(last.score, last.maxScore)
                else "Not attempted yet"
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("$subject · ${chapter.take(30)}")
            .setView(dlgContent)
            .setPositiveButton("▶  Start Test") { _, _ ->
                AnalyticsManager.chapterTestStarted(this, subject, chapter)
                startChapterTest(chapter, index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startChapterTest(chapter: String, index: Int = 0) {
        if (!NetworkUtils.isNetworkAvailable(this) && !PrefManager.isEffectivelyPremium(this)) {
            showNoInternetToast()
            return
        }
        val isFreeChapter = index < 3
        val isUnlocked = isFreeChapter ||
            PrefManager.isAllAccessUnlocked(this) ||
            PrefManager.isPackUnlocked(this, currentProductId)
        if (isUnlocked) {
            TestActivity.start(this, exam, subject, chapter)
            return
        }
        val alreadyUsedToday = PrefManager.hasUsedAdFreeUnlockToday(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock $subject Pack")
            .setMessage("Unlock the $subject pack for ₹49 to practice all chapters.\n\n💡 Tip: First 3 chapters are always FREE!")
            .setPositiveButton("Buy ₹49") { _, _ -> ShopActivity.start(this) }
            .setNeutralButton(if (alreadyUsedToday) "Free try used ✓" else "Watch Ad (Free Today)") { _, _ ->
                if (alreadyUsedToday) return@setNeutralButton
                AnalyticsManager.chapterAdUnlockAttempted(this, subject, chapter)
                AnalyticsManager.rewardedAdShown(this, "chapter_unlock")
                AdManager.showRewarded(
                    activity = this,
                    onRewarded = {
                        PrefManager.markAdFreeUnlockUsed(this)
                        TestActivity.start(this, exam, subject, chapter, adUnlocked = true)
                    },
                    onNotAvailable = { Toast.makeText(this, "No ad available. Try again later.", Toast.LENGTH_SHORT).show() },
                    onLoading = { Toast.makeText(this, "Loading ad…", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Try 10 Free Qs") { _, _ ->
                AdManager.showInterstitial(this, bypassCooldown = true) {
                    TestActivity.start(this, exam, subject, chapter = null, totalQuestions = 10)
                }
            }
            .show()
    }

    // ─── Chapter data ─────────────────────────────────────────────────────────

    private fun chaptersForSubject(subject: String): List<String> = when (subject) {
        "Physics" -> listOf(
            "Mathematics In Physics", "Units, Dimensions And Measurement",
            "Motion In One Dimension", "Motion In Two Dimension",
            "Newton's Laws Of Motion", "Friction",
            "Work, Energy, Power And Collision", "Rotational Motion",
            "Gravitation", "Simple Harmonic Motion",
            "Elasticity", "Fluid Mechanics",
            "Thermal Physics", "Kinetic Theory Of Gases",
            "Thermodynamics", "Wave Motion",
            "Electrostatics", "Current Electricity",
            "Magnetic Effect Of Current", "Electromagnetic Induction",
            "Optics", "Modern Physics"
        )
        "Chemistry" -> listOf(
            "Some Basic Concepts Of Chemistry", "Structure Of Atom",
            "Classification Of Elements", "Chemical Bonding",
            "States Of Matter", "Thermodynamics",
            "Equilibrium", "Redox Reactions",
            "Hydrogen", "S-Block Elements",
            "P-Block Elements", "Organic Chemistry Basics",
            "Hydrocarbons", "Environmental Chemistry",
            "Solid State", "Solutions",
            "Electrochemistry", "Chemical Kinetics",
            "Surface Chemistry", "Coordination Compounds",
            "Aldehydes, Ketones And Carboxylic Acids", "Biomolecules"
        )
        "Maths" -> listOf(
            "Sets, Relations, And Functions", "Complex Numbers & Quadratic Equations",
            "Matrices & Determinants", "Permutations And Combinations",
            "Binomial Theorem", "Sequence & Series",
            "Limit, Continuity & Differentiability", "Integral Calculus",
            "Coordinate Geometry", "Three Dimensional Geometry",
            "Vector Algebra", "Probability",
            "Trigonometry", "Mathematical Reasoning",
            "Statistics"
        )
        "Biology" -> listOf(
            "Cell Biology", "Genetics",
            "Evolution", "Human Physiology",
            "Plant Physiology", "Reproduction",
            "Ecology", "Biomolecules",
            "Microbes In Human Welfare", "Biotechnology",
            "Animal Kingdom", "Plant Kingdom",
            "Morphology Of Flowering Plants", "Anatomy Of Flowering Plants",
            "Structural Organisation In Animals"
        )
        else -> emptyList()
    }

    private fun subjectIcon(): String = when (subject) {
        "Physics"   -> "⚡"
        "Chemistry" -> "🧪"
        "Maths"     -> "📐"
        "Biology"   -> "🧬"
        else        -> "📚"
    }

    private fun subjectSubtitle(): String = when (subject) {
        "Physics"   -> "Master concepts. Solve with clarity."
        "Chemistry" -> "Master concepts. Solve with confidence."
        "Maths"     -> "Practice problems. Build precision."
        "Biology"   -> "Understand life. Ace every question."
        else        -> "Practice chapter by chapter."
    }

    private fun tintedBg(color: Int, alpha: Int = 30): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
