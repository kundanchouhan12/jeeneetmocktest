package com.jeeneet.mocktest.ui.result

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jeeneet.mocktest.MainActivity
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_RESULT_ID = "extra_result_id"

        fun start(context: Context, resultId: Long) {
            context.startActivity(Intent(context, ResultActivity::class.java).apply {
                putExtra(EXTRA_RESULT_ID, resultId)
            })
        }
    }

    private var currentResult: TestResult? = null
    private var examType = "JEE"

    private lateinit var tvScore: TextView
    private lateinit var tvMaxScore: TextView
    private lateinit var tvCorrect: TextView
    private lateinit var tvWrong: TextView
    private lateinit var tvSkipped: TextView
    private lateinit var tvTimeTaken: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var progressAccuracy: android.widget.ProgressBar
    private lateinit var questionGridContainer: LinearLayout
    private var isAnalysisUnlocked = false
    private lateinit var bannerContainer: FrameLayout
    private lateinit var nativeAdContainer: FrameLayout
    private lateinit var rankPredictionContainer: FrameLayout
    private lateinit var btnPracticeWrong: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val resultId = intent.getLongExtra(EXTRA_RESULT_ID, -1L)
            if (resultId < 0L) {
                android.util.Log.e("ResultActivity", "Invalid resultId received: $resultId")
                finish()
                return
            }
            setContentView(buildLayout())
            loadResult(resultId)
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateUp()
            })
            
            AdManager.loadRewarded(this)
            AdManager.showBanner(this, bannerContainer)
            AdManager.loadNativeAd(this, nativeAdContainer)
        } catch (e: Exception) {
            android.util.Log.e("ResultActivity", "Error in onCreate: ${e.message}")
            android.widget.Toast.makeText(this, "Failed to load result screen", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    private fun loadResult(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MockTestDatabase.getInstance(this@ResultActivity)
                .testResultDao().getResultById(id)
            withContext(Dispatchers.Main) {
                if (result != null) {
                    currentResult = result
                    displayResult(result)
                }
            }
        }
    }

    private fun navigateUp() {
        val intent = Intent(this, com.jeeneet.mocktest.ui.home.MockTestListActivity::class.java).apply {
            putExtra("extra_type", "mock_test")
            putExtra("extra_exam", examType)
            putExtra(com.jeeneet.mocktest.ui.home.MockTestListActivity.EXTRA_INITIAL_TAB, 2)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun displayResult(r: TestResult) {
        try {
            examType = r.examType
        awardCoinsIfFirstView(r)

        val accuracy = if (r.attempted > 0) r.correct * 100 / r.attempted else 0
        tvAccuracy.text = "$accuracy%"

        tvScore.text = "%.0f".format(r.score)
        tvMaxScore.text = "/ %.0f".format(r.maxScore)

        if (::progressAccuracy.isInitialized) {
            progressAccuracy.progress = accuracy
        }

        tvCorrect.text = "✓ ${r.correct}"
        tvWrong.text = "✕ ${r.wrong}"
        tvSkipped.text = "○ ${r.totalQuestions - r.attempted}"

        val mins = r.timeTakenSeconds / 60
        val secs = r.timeTakenSeconds % 60
        tvTimeTaken.text = "Time Taken : %02d min, %02d sec".format(mins, secs)

        val wrongCount = r.wrong
        btnPracticeWrong.text = if (wrongCount > 0) "🎯  Retry $wrongCount Wrong Questions" else "🎯  Retry Wrong Questions"
        btnPracticeWrong.visibility = if (wrongCount > 0) View.VISIBLE else View.GONE

        buildQuestionGrid(r)
        rankPredictionContainer.removeAllViews()
        rankPredictionContainer.addView(buildRankPredictionCard(r))
    } catch (e: Exception) {
        android.util.Log.e("ResultActivity", "Error displaying result: ${e.message}", e)
        android.widget.Toast.makeText(this, "Error rendering performance data", android.widget.Toast.LENGTH_SHORT).show()
    }
}

    // ─── Coins awarding ──────────────────────────────────────────────────────────

    private fun awardCoinsIfFirstView(r: TestResult) {
        if (PrefManager.hasAwardedCoinsForResult(this, r.id)) return
        val earned = if (r.totalQuestions == 10) PrefManager.COINS_DAILY_QUIZ
                     else PrefManager.COINS_COMPLETE_TEST
        PrefManager.addCoins(this, earned)
        PrefManager.markCoinsAwardedForResult(this, r.id)
        Toast.makeText(this, "🪙 +$earned coins earned!", Toast.LENGTH_SHORT).show()
    }

    // ─── Question analysis grid ───────────────────────────────────────────────

    private fun buildQuestionGrid(r: TestResult) {
        questionGridContainer.removeAllViews()
        if (r.questionsJson.isEmpty() || r.answersJson.isEmpty()) return

        val gson = Gson()
        val questions = runCatching {
            gson.fromJson<List<Question>>(r.questionsJson, object : TypeToken<List<Question>>() {}.type)
        }.getOrNull() ?: return
        val answers = runCatching {
            gson.fromJson<Map<Int, Int?>>(r.answersJson, object : TypeToken<Map<Int, Int?>>() {}.type)
        }.getOrNull() ?: return

        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lpRow(bottomDp = 10)
        }
        legendRow.addView(legendDot(correctGreen, "Correct – ${r.correct}"))
        legendRow.addView(spacerH(16))
        legendRow.addView(legendDot(wrongRed, "Wrong – ${r.wrong}"))
        legendRow.addView(spacerH(16))
        legendRow.addView(legendDot(reviewOrange, "Skipped – ${r.totalQuestions - r.attempted}"))
        questionGridContainer.addView(legendRow)

        val cells = questions.mapIndexed { idx, q ->
            val ans = answers[idx]
            val color = when {
                ans == null -> reviewOrange
                ans == q.correctOptionIndex -> correctGreen
                else -> wrongRed
            }
            TextView(this).apply {
                text = (idx + 1).toString()
                textSize = 12f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedFill(color, Corner.S - 2f)
                layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp).also {
                    it.marginEnd = 6.dp; it.bottomMargin = 6.dp
                }
            }
        }

        cells.chunked(5).forEach { rowCells ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lpRow()
            }
            rowCells.forEach { row.addView(it) }
            questionGridContainer.addView(row)
        }
    }

    private fun spacerH(widthDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(widthDp.dp, 1)
    }

    private fun legendDot(color: Int, label: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@ResultActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(12.dp, 12.dp).also { it.marginEnd = 6.dp }
            })
            addView(uiTextView(UiText.CAPTION, label, textTertiary).apply { textSize = 11f })
        }

    // ─── Layout ───────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val outerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        root.addView(uiHeader("Test Result", onBack = { navigateUp() }))

        // Main content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.XXL.dp)
        }

        content.addView(buildScoreCard())

        rankPredictionContainer = FrameLayout(this).apply {
            layoutParams = lpRow(bottomDp = Space.L)
        }
        content.addView(rankPredictionContainer)

        nativeAdContainer = FrameLayout(this).apply {
            layoutParams = lpRow(bottomDp = Space.L)
        }
        content.addView(nativeAdContainer)

        content.addView(buildAnalysisCard())
        content.addView(buildActionRow())

        // Back to home
        content.addView(MaterialButton(this).apply {
            text = "Back to Home"; textSize = 14f
            setTextColor(textTertiary)
            isAllCaps = false; stateListAnimator = null
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 50.dp
            )
            setOnClickListener {
                val intent = Intent(this@ResultActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent); finish()
            }
        })

        // ─── Rate Us Card (Happiness Peak Logic) ───
        val scorePercent = (currentResult?.score ?: 0f) * 100 / (currentResult?.maxScore ?: 1f)
        val totalTestsDone = PrefManager.getTotalTestsCompleted(this)
        val hasRated = PrefManager.hasRatedApp(this)
        
        if (scorePercent >= 70f && totalTestsDone >= 3 && !hasRated) {
            content.addView(uiCard(radius = Corner.M, elevation = Elev.S, background = bgSecondary,
                strokeDp = 0).apply {
                layoutParams = lpRow(bottomDp = Space.XXL)
                val inner = LinearLayout(this@ResultActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
                }
                inner.addView(TextView(this@ResultActivity).apply {
                    text = "🌟 Amazing Score! Happy with the app?"; textSize = 14f
                    setTextColor(textPrimary)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                inner.addView(MaterialButton(this@ResultActivity).apply {
                    text = "Rate Us"; textSize = 12f
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981")) // Emerald Green
                    isAllCaps = false; stateListAnimator = null
                    cornerRadius = 12.dp
                    setOnClickListener { PrefManager.openPlayStore(this@ResultActivity) }
                })
                addView(inner)
            })
        }

        root.addView(content)

        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 60.dp
            )
            setBackgroundColor(bgSecondary)
        }
        outerRoot.addView(scroll)
        outerRoot.addView(bannerContainer)
        return outerRoot
    }

    private fun buildScoreCard(): View {
        val card = uiCard(radius = Corner.L, elevation = Elev.M, background = bgSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = -Space.XXL.dp; it.bottomMargin = Space.L.dp }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Space.L.dp, Space.XL.dp, Space.L.dp, Space.XL.dp)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left: Accuracy Circular Meter
        val meterContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(100.dp, 100.dp).also { it.marginEnd = Space.L.dp }
        }
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RING
                setStroke(8.dp, Color.parseColor("#E2E8F0"))
                setSize(100.dp, 100.dp)
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        // Overlay actual progress with a custom drawable or just use two progress bars
        progressAccuracy = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RING
                setStroke(8.dp, correctGreen)
                setSize(100.dp, 100.dp)
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        
        val accTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        tvAccuracy = uiTextView(UiText.H2, "0%", textPrimary, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        accTextCol.addView(tvAccuracy)
        accTextCol.addView(uiTextView(UiText.OVERLINE, "ACCURACY", textMuted, Gravity.CENTER))
        
        meterContainer.addView(progress)
        meterContainer.addView(progressAccuracy)
        meterContainer.addView(accTextCol)
        inner.addView(meterContainer)

        // Right: Score & Stats
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tvScore = TextView(this).apply {
            text = "0"; textSize = 32f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        tvMaxScore = uiTextView(UiText.BODY, "/ 0", textMuted).apply {
            setPadding(4.dp, 0, 0, 8.dp)
        }
        scoreRow.addView(tvScore)
        scoreRow.addView(tvMaxScore)
        rightCol.addView(scoreRow)

        val statsChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 0)
        }
        tvCorrect = statChip(correctGreen)
        tvWrong = statChip(wrongRed)
        tvSkipped = statChip(reviewOrange)
        
        statsChipRow.addView(tvCorrect)
        statsChipRow.addView(spacerH(8))
        statsChipRow.addView(tvWrong)
        statsChipRow.addView(spacerH(8))
        statsChipRow.addView(tvSkipped)
        rightCol.addView(statsChipRow)
        
        tvTimeTaken = uiTextView(UiText.CAPTION, "Time Taken : --", textTertiary).apply {
            setPadding(0, 8.dp, 0, 0)
        }
        rightCol.addView(tvTimeTaken)
        
        inner.addView(rightCol)
        card.addView(inner)
        return card
    }

    private fun statChip(color: Int) = TextView(this).apply {
        text = "0"; textSize = 11f; setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = roundedFill(color, Corner.PILL)
        setPadding(10.dp, 2.dp, 10.dp, 2.dp)
    }

    private fun statValueView(color: Int) = TextView(this).apply {
        text = "--"; textSize = 20f
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun buildAnalysisCard(): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.S,
            background = bgSecondary
        ).apply {
            layoutParams = lpRow(bottomDp = Space.L)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lpRow(bottomDp = 10)
        }
        titleRow.addView(uiTextView(UiText.H3, "📋 Question Analysis", textPrimary).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        inner.addView(titleRow)
        questionGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Add placeholder text — will be replaced in buildQuestionGrid once data loads
        questionGridContainer.addView(uiTextView(UiText.CAPTION, "Loading question data…", textMuted).apply {
            setPadding(0, Space.S.dp, 0, Space.S.dp)
        })
        inner.addView(questionGridContainer)
        card.addView(inner)
        return card
    }

    private fun buildRankPredictionCard(r: TestResult): View {
        val scorePercent = if (r.maxScore > 0) (r.score / r.maxScore * 100f).toInt().coerceIn(0, 100) else 0
        val isJee = r.examType.contains("JEE", ignoreCase = true)

        // Simple formula-based AIR estimate
        // JEE: ~2M applicants. NEET: ~2.3M applicants.
        val (airLow, airHigh, label, labelColor) = when {
            scorePercent >= 95 -> Quadruple(1, if (isJee) 1_000 else 500, "🏆 Outstanding!", Color.parseColor("#10B981"))
            scorePercent >= 90 -> Quadruple(if (isJee) 1_000 else 500, if (isJee) 10_000 else 2_000, "🥇 Excellent!", Color.parseColor("#10B981"))
            scorePercent >= 80 -> Quadruple(if (isJee) 10_000 else 2_000, if (isJee) 60_000 else 15_000, "💪 Very Good", colorPrimary)
            scorePercent >= 70 -> Quadruple(if (isJee) 60_000 else 15_000, if (isJee) 2_00_000 else 60_000, "📈 Good", colorPrimary)
            scorePercent >= 60 -> Quadruple(if (isJee) 2_00_000 else 60_000, if (isJee) 5_00_000 else 1_50_000, "🎯 Average", goldPrimary)
            else -> Quadruple(if (isJee) 5_00_000 else 1_50_000, if (isJee) 12_00_000 else 20_00_000, "📚 Keep Practicing", reviewOrange)
        }

        val card = uiCard(radius = Corner.L, elevation = Elev.S, background = bgSecondary).apply {
            layoutParams = lpRow()
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        // Header row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        headerRow.addView(uiTextView(UiText.H3, "🏆 AIR Prediction", textPrimary).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(labelColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.argb(15, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor)), Corner.PILL)
            setPadding(Space.M.dp, 4.dp, Space.M.dp, 4.dp)
        })
        inner.addView(headerRow)

        // Rank range row
        val rankRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lpRow(bottomDp = Space.S)
        }

        // Score box
        rankRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(uiTextView(UiText.H1, "$scorePercent%", labelColor, Gravity.CENTER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(uiTextView(UiText.CAPTION, "Score %", textMuted, Gravity.CENTER).apply { textSize = 11f })
        })

        // Divider
        rankRow.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(1.dp, 48.dp).also {
                it.marginStart = Space.M.dp; it.marginEnd = Space.M.dp
            }
        })

        // AIR box
        val airText = if (airLow <= 1) "< ${"%,d".format(airHigh)}" else "${"%,d".format(airLow)} – ${"%,d".format(airHigh)}"
        rankRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(uiTextView(UiText.H2, airText, textPrimary, Gravity.CENTER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = 16f
            })
            addView(uiTextView(UiText.CAPTION, "Estimated AIR", textMuted, Gravity.CENTER).apply { textSize = 11f })
        })
        inner.addView(rankRow)

        inner.addView(uiTextView(UiText.CAPTION,
            "* Based on ${r.examType} historical cutoffs. Improve score to boost rank.",
            textMuted).apply { textSize = 10f; setPadding(0, Space.S.dp, 0, 0) })

        card.addView(inner)
        return card
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun buildActionRow(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        btnPracticeWrong = MaterialButton(this).apply {
            text = "🎯  Retry Wrong Questions"; textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D97706"))
            isAllCaps = false; stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 52.dp
            ).also { it.bottomMargin = Space.S.dp }
            setOnClickListener { practiceWrongQuestions() }
        }
        col.addView(btnPracticeWrong)
        col.addView(MaterialButton(this).apply {
            text = "View Solutions"; textSize = 13f
            setTextColor(colorPrimary)
            isAllCaps = false; stateListAnimator = null
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(Color.TRANSPARENT)
                setStroke(2.dp, colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp)
            setOnClickListener { unlockSolutions() }
        })
        return col
    }

    private fun practiceWrongQuestions() {
        val r = currentResult ?: return
        if (r.questionsJson.isEmpty() || r.answersJson.isEmpty()) {
            Toast.makeText(this, "No question data available.", Toast.LENGTH_SHORT).show()
            return
        }
        val gson = Gson()
        val questions = runCatching {
            gson.fromJson<List<Question>>(r.questionsJson, object : TypeToken<List<Question>>() {}.type)
        }.getOrNull() ?: return
        val answers = runCatching {
            gson.fromJson<Map<Int, Int?>>(r.answersJson, object : TypeToken<Map<Int, Int?>>() {}.type)
        }.getOrNull() ?: return

        val wrongQuestions = questions.filterIndexed { idx, q ->
            val ans = answers[idx]
            ans != null && ans != q.correctOptionIndex
        }
        if (wrongQuestions.isEmpty()) {
            Toast.makeText(this, "No wrong answers to practice! Great job! 🎉", Toast.LENGTH_SHORT).show()
            return
        }
        TestActivity.startRevision(this, r.examType, gson.toJson(wrongQuestions), r.isSimulation)
    }

    private fun buildSmallStatBox(valueView: TextView, label: String, color: Int): View {
        val card = uiCard(
            radius = Corner.S,
            elevation = Elev.NONE,
            background = Color.argb(55, Color.red(color), Color.green(color), Color.blue(color))
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.S.dp, 10.dp, Space.S.dp, 10.dp)
        }
        inner.addView(valueView)
        inner.addView(uiTextView(UiText.OVERLINE, label, textMuted, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 10f
            setPadding(0, 2.dp, 0, 0)
        })
        card.addView(inner)
        return card
    }

    // ─── Unlock / retry ──────────────────────────────────────────────────────

    private fun retrySameTest() {
        val result = currentResult ?: return
        if (result.questionsJson.isEmpty()) {
            Toast.makeText(this, "Question data missing.", Toast.LENGTH_SHORT).show()
            return
        }
        val gson = Gson()
        val qs = runCatching {
            gson.fromJson<List<Question>>(result.questionsJson, object : TypeToken<List<Question>>() {}.type)
        }.getOrNull() ?: return
        
        // Shuffle questions for revision mode
        val shuffledJson = gson.toJson(qs.shuffled())
        com.jeeneet.mocktest.ui.test.TestActivity.startRevision(this, result.examType, shuffledJson, result.isSimulation)
        finish()
    }

    private fun unlockSolutions() {
        val res = currentResult ?: run {
            Toast.makeText(this, "Session data missing", Toast.LENGTH_SHORT).show()
            return
        }
        if (PrefManager.isAdsRemoved(this) || PrefManager.isAllAccessUnlocked(this)) {
            SolutionActivity.start(this, res.questionsJson, res.answersJson)
            return
        }
        AnalyticsManager.rewardedAdShown(this, "solution_unlock")
        AdManager.showRewarded(
            activity = this,
            onRewarded = { SolutionActivity.start(this, res.questionsJson, res.answersJson) },
            onNotAvailable = {
                Toast.makeText(this, "No ad available. Connect to internet or purchase to unlock.", Toast.LENGTH_SHORT).show()
            },
            onLoading = { Toast.makeText(this, "Loading ad, please wait…", Toast.LENGTH_SHORT).show() }
        )
    }
}
