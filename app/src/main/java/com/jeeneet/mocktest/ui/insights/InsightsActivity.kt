package com.jeeneet.mocktest.ui.insights

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.google.android.material.button.MaterialButton
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import com.jeeneet.mocktest.data.repository.GroqRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InsightsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadInsights()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(uiHeader("💡 Preparation Insights", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.XXL.dp, Space.L.dp, Space.XXL.dp)
        }
        content.addView(uiTextView(UiText.BODY, "Loading insights…", textMuted, Gravity.CENTER).apply {
            setPadding(0, 40.dp, 0, 0)
        })
        scroll.addView(content)
        root.addView(scroll)

        val bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(bannerContainer)
        com.jeeneet.mocktest.admob.AdManager.showBanner(this, bannerContainer)

        return root
    }

    private fun loadInsights() {
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                    val dao = MockTestDatabase.getInstance(this@InsightsActivity).testResultDao()
                    dao.getAllResultsOnce(uid).sortedByDescending { it.completedAt }
                }
                renderInsights(results)
            } catch (e: Exception) {
                android.util.Log.e("InsightsActivity", "loadInsights failed", e)
                content.removeAllViews()
                content.addView(uiTextView(UiText.BODY,
                    "Unable to load insights.\nPlease try again later.",
                    textMuted, Gravity.CENTER).apply {
                    setLineSpacing(0f, 1.3f)
                    setPadding(Space.XXL.dp, 60.dp, Space.XXL.dp, 0)
                })
            }
        }
    }

    private data class ChapterStats(
        val chapter: String, val subject: String,
        var correct: Int = 0, var wrong: Int = 0
    ) {
        val accuracy: Float get() = if (correct + wrong == 0) 0f else correct.toFloat() / (correct + wrong)
        val total: Int get() = correct + wrong
    }

    private fun renderInsights(results: List<TestResult>) {
        content.removeAllViews()

        if (results.isEmpty()) {
            content.addView(uiTextView(UiText.BODY,
                "📝 No test data yet.\nTake your first test to see real insights!",
                textMuted, Gravity.CENTER).apply {
                textSize = 15f
                setLineSpacing(0f, 1.2f)
                setPadding(Space.XXL.dp, 60.dp, Space.XXL.dp, 0)
            })
            return
        }

        // ── Step 1: Aggregate data (all safely guarded) ───────────────────────
        var totalCorrect = 0; var totalWrong = 0
        var totalTimeSecs = 0L; var totalAttempted = 0
        val chapterMap = mutableMapOf<String, ChapterStats>()
        val gson = Gson()
        val questionListType = object : TypeToken<List<Question>>() {}.type
        val answerMapType    = object : TypeToken<Map<String, Int?>>() {}.type

        results.forEach { r ->
            totalCorrect   += r.correct
            totalWrong     += r.wrong
            totalTimeSecs  += r.timeTakenSeconds
            totalAttempted += r.attempted

            runCatching {
                if (r.questionsJson.isNotEmpty() && r.answersJson.isNotEmpty()) {
                    val questions = gson.fromJson<List<Question>>(r.questionsJson, questionListType)
                        ?: return@runCatching
                    val answers = gson.fromJson<Map<String, Int?>>(r.answersJson, answerMapType)
                        ?: return@runCatching
                    questions.forEachIndexed { idx, q ->
                        val subj  = runCatching { q.subject }.getOrElse { "General" }
                        val chap  = runCatching { q.chapter }.getOrElse { "Unknown" }
                        val key   = "$subj::$chap"
                        val stats = chapterMap.getOrPut(key) { ChapterStats(chap, subj) }
                        when (val ans = answers[idx.toString()]) {
                            null                  -> { /* skipped */ }
                            q.correctOptionIndex  -> stats.correct++
                            else                  -> stats.wrong++
                        }
                    }
                }
            } // silently skip malformed records
        }

        val overallAccuracy = if (totalCorrect + totalWrong > 0)
            totalCorrect.toFloat() / (totalCorrect + totalWrong) * 100 else 0f
        val totalStudyMins = totalTimeSecs / 60
        val streak = runCatching { PrefManager.getStreak(this) }.getOrDefault(0)
        val validChapters = chapterMap.values.filter { it.total >= 2 }
        val weakChapter   = validChapters.minByOrNull { it.accuracy }
        val strongChapter = validChapters.maxByOrNull { it.accuracy }

        val avgTimePerQMs = runCatching {
            val timesType = object : TypeToken<Map<String, Long?>>() {}.type
            var totalMs = 0L; var totalQs = 0
            results.forEach { r ->
                if (r.questionTimesJson.isNotEmpty()) {
                    runCatching {
                        val map = gson.fromJson<Map<String, Long?>>(r.questionTimesJson, timesType)
                        totalMs += map.values.filterNotNull().sum()
                        totalQs += map.size
                    }
                }
            }
            if (totalQs > 0) totalMs / totalQs else 0L
        }.getOrDefault(0L)

        // ── Step 2: Render each section with individual guards ─────────────────
        fun safeAdd(tag: String, block: () -> View?) {
            runCatching {
                block()?.let { content.addView(it) }
            }.onFailure { e ->
                android.util.Log.e("InsightsActivity", "Section '$tag' failed", e)
            }
        }

        safeAdd("performance_label")   { sectionLabel("📊 YOUR PERFORMANCE") }
        safeAdd("performance_card")    { performanceOverviewCard(overallAccuracy, results.size, totalAttempted, avgTimePerQMs) }

        // 🔥 AI COUNSELOR INTEGRATION (REFACTORED)
        val aiCardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        content.addView(aiCardContainer, 0)
        
        lifecycleScope.launch {
            try {
                val repository = com.jeeneet.mocktest.data.repository.AiCounselorRepository(applicationContext)
                val readiness = repository.calculateReadinessScore(results)
                
                repository.getInsights().onSuccess { advice ->
                    runOnUiThread {
                        if (!isFinishing) {
                            aiCardContainer.removeAllViews()
                            aiCardContainer.addView(buildAiCounselorCard(advice, readiness))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // --- NEW: AI Weakness Deep-Dive with Ad-gate ---
        val deepDiveContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        content.addView(deepDiveContainer)

        fun showDeepDive() {
            lifecycleScope.launch {
                val repository = com.jeeneet.mocktest.data.repository.AiCounselorRepository(applicationContext)
                deepDiveContainer.removeAllViews()
                deepDiveContainer.addView(uiTextView(UiText.BODY, "Analyzing patterns...", textMuted, Gravity.CENTER))
                
                val rawStats = repository.getDeepDiveStats(results)
                repository.getWeaknessDeepDive().onSuccess { analysis ->
                    runOnUiThread {
                        if (!isFinishing) {
                            deepDiveContainer.removeAllViews()
                            deepDiveContainer.addView(sectionLabel("🔍 AI WEAKNESS DEEP-DIVE"))
                            
                            val card = uiAiCard(accentColor = colorPrimary, radius = Corner.L)
                            val inner = LinearLayout(this@InsightsActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
                            }
                            
                            inner.addView(uiTextView(UiText.H3, "Diagnostic Insight", textPrimary))
                            inner.addView(uiTextView(UiText.BODY, analysis, textSecondary).apply {
                                setPadding(0, 8.dp, 0, 12.dp)
                                setLineSpacing(0f, 1.2f)
                            })

                            // --- Heatmap Table ---
                            val table = LinearLayout(this@InsightsActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setBackgroundColor(Color.argb(20, 255, 255, 255))
                                setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
                            }
                            table.addView(uiTextView(UiText.OVERLINE, "TOPIC • ACCURACY • SPEED", textTertiary).apply {
                                setPadding(0, 0, 0, 8.dp)
                            })
                            
                            rawStats.split("\n").forEach { line ->
                                if (line.trim().isEmpty()) return@forEach
                                val row = LinearLayout(this@InsightsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setPadding(0, 4.dp, 0, 4.dp)
                                }
                                // Parse "- Physics: Chapter: 40% accuracy, 90s avg time"
                                val topic = line.substringAfter("- ").substringBefore(":")
                                val accuracy = line.substringAfter(": ").substringBefore(" accuracy")
                                val speed = line.substringAfter(", ").substringBefore(" avg time")
                                
                                row.addView(uiTextView(UiText.CAPTION, topic, textPrimary).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, -2, 1.2f)
                                })
                                row.addView(uiTextView(UiText.CAPTION, accuracy, goldPrimary).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, -2, 0.8f)
                                    gravity = Gravity.CENTER
                                })
                                row.addView(uiTextView(UiText.CAPTION, speed, textTertiary).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, -2, 0.8f)
                                    gravity = Gravity.END
                                })
                                table.addView(row)
                            }
                            inner.addView(table)

                            // --- Expandable 'Why am I seeing this?' ---
                            val whyHeader = LinearLayout(this@InsightsActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, 16.dp, 0, 0)
                            }
                            whyHeader.addView(uiTextView(UiText.LABEL, "Why am I seeing this?", textMuted))
                            val chevron = uiTextView(UiText.CAPTION, " ▼", textMuted)
                            whyHeader.addView(chevron)
                            
                            val whyContent = uiTextView(UiText.CAPTION, "Based on your last 20 test sessions. AI detected declining accuracy and high time-per-question in these specific chapters compared to your global average.", textMuted).apply {
                                visibility = View.GONE
                                setPadding(0, 4.dp, 0, 0)
                            }
                            
                            whyHeader.setOnClickListener {
                                val isVisible = whyContent.visibility == View.VISIBLE
                                whyContent.visibility = if (isVisible) View.GONE else View.VISIBLE
                                chevron.text = if (isVisible) " ▼" else " ▲"
                            }
                            
                            inner.addView(whyHeader)
                            inner.addView(whyContent)
                            
                            card.addView(inner)
                            deepDiveContainer.addView(card)
                        }
                    }
                }.onFailure {
                    runOnUiThread { deepDiveContainer.removeAllViews() }
                }
            }
        }

        if (results.size >= 3) {
            deepDiveContainer.addView(sectionLabel("🔍 AI WEAKNESS DEEP-DIVE"))
            deepDiveContainer.addView(uiCard(radius = Corner.L, background = bgSecondary).apply {
                val inner = LinearLayout(this@InsightsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(Space.L.dp, 30.dp, Space.L.dp, 30.dp)
                }
                inner.addView(uiTextView(UiText.H3, "Unlock Deep-Dive Analysis", textPrimary, Gravity.CENTER))
                inner.addView(uiTextView(UiText.BODY, "Our AI will analyze your speed vs accuracy across every chapter to find your hidden gaps.", textTertiary, Gravity.CENTER).apply {
                    setPadding(0, 8.dp, 0, 16.dp)
                })
                
                val btn = MaterialButton(this@InsightsActivity).apply {
                    text = "Unlock with Ad"; isAllCaps = false
                    backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
                    setOnClickListener {
                        com.jeeneet.mocktest.admob.AdManager.showRewarded(this@InsightsActivity, onRewarded = {
                            showDeepDive()
                        }, placement = "ai_deep_dive")
                    }
                }
                inner.addView(btn)
                addView(inner)
            })
        }


        if (weakChapter != null) {
            safeAdd("weak_label") { sectionLabel("📉 WEAK TOPIC") }
            safeAdd("weak_card") {
                insightCard(
                    label       = "${weakChapter.subject} — ${weakChapter.chapter}",
                    value       = "%.0f%% Accuracy".format(weakChapter.accuracy * 100),
                    description = "${weakChapter.correct} correct out of ${weakChapter.total} attempted. Focus here!",
                    accentColor = wrongRed
                )
            }
        }

        if (strongChapter != null &&
            "${strongChapter.subject}::${strongChapter.chapter}" != "${weakChapter?.subject}::${weakChapter?.chapter}") {
            safeAdd("strong_label") { sectionLabel("💪 STRONG TOPIC") }
            safeAdd("strong_card") {
                insightCard(
                    label       = "${strongChapter.subject} — ${strongChapter.chapter}",
                    value       = "%.0f%% Accuracy".format(strongChapter.accuracy * 100),
                    description = "${strongChapter.correct} correct out of ${strongChapter.total} attempted. Excellent!",
                    accentColor = correctGreen
                )
            }
        }

        val studyTimeValue = when {
            totalStudyMins == 0L  -> "-- min"
            totalStudyMins >= 60  -> "%.1f hrs".format(totalStudyMins / 60.0)
            else                  -> "${totalStudyMins} min"
        }
        val studyTimeDesc = when {
            totalStudyMins == 0L  -> "Study time is tracked automatically each test session. Complete a test to start!"
            totalStudyMins >= 300 -> "Over 5 hours of focused practice — you're putting in real work!"
            totalStudyMins >= 60  -> "${"%.0f".format(totalStudyMins / 60.0)} hours invested so far. Keep building momentum."
            totalStudyMins >= 10  -> "${totalStudyMins} minutes of practice. Build a daily habit to grow this!"
            else                  -> "Just getting started. Every minute of practice counts."
        }
        safeAdd("study_label") { sectionLabel("⏱ STUDY TIME") }
        safeAdd("study_card")  { insightCard("Total Study Time", studyTimeValue, studyTimeDesc, Color.parseColor("#3B82F6")) }

        safeAdd("streak_label") { sectionLabel("🔥 STREAK") }
        safeAdd("streak_card")  {
            insightCard(
                label       = "Day Streak",
                value       = "$streak ${if (streak == 1) "Day" else "Days"}",
                description = when {
                    streak >= 7  -> "Amazing! $streak days straight. Consistency is your superpower!"
                    streak >= 3  -> "$streak day streak! Keep going — you're building a habit."
                    streak == 1  -> "Great start! Practice every day to build your streak."
                    else         -> "Come back every day to build a winning streak."
                },
                accentColor = goldPrimary
            )
        }

        val dailyGoal      = runCatching { PrefManager.getDailyGoal(this) }.getOrDefault(50)
        val dailyAttempted = runCatching { PrefManager.getDailyQuestionsAttempted(this) }.getOrDefault(0)
        safeAdd("goal_label") { sectionLabel("🎯 TODAY'S GOAL") }
        safeAdd("goal_card")  { buildDailyGoalCard(dailyAttempted, dailyGoal) }

        // Performance Trend — guarded separately (LineChart can crash)
        val last10 = results.take(10).reversed()
        if (last10.size >= 2) {
            safeAdd("trend_label") { sectionLabel("📈 PERFORMANCE TREND (LAST ${last10.size} TESTS)") }
            safeAdd("trend_chart") { buildImprovementLineChart(last10) }
        }

        // 🔥 AI AT-RISK PREDICTOR (REFACTORED)
        val riskContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lpRow(bottomDp = Space.M)
        }
        content.addView(riskContainer)
        
        lifecycleScope.launch {
            try {
                val repository = com.jeeneet.mocktest.data.repository.AiCounselorRepository(applicationContext)
                repository.getAtRiskAnalysis().onSuccess { analysis ->
                    if (!analysis.contains("No immediate risks detected")) {
                        runOnUiThread {
                            if (!isFinishing) {
                                riskContainer.removeAllViews()
                                riskContainer.addView(sectionLabel("🤖 AI PREDICTION — AT RISK TOPICS"))
                                riskContainer.addView(uiAiCard(
                                    accentColor = Color.parseColor("#F97316"), // Orange
                                    radius = Corner.M
                                ).apply {
                                    val cardInner = LinearLayout(this@InsightsActivity).apply {
                                        orientation = LinearLayout.VERTICAL
                                        setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
                                    }
                                    cardInner.addView(uiTextView(UiText.OVERLINE, "🚨 URGENT ATTENTION", Color.parseColor("#F97316")))
                                    cardInner.addView(uiTextView(UiText.H3, "Topics at Risk", textPrimary))
                                    cardInner.addView(uiTextView(UiText.BODY, analysis, textSecondary).apply {
                                        setPadding(0, 4.dp, 0, 0)
                                        textSize = 13f
                                    })
                                    addView(cardInner)
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Chapter rank card
        if (validChapters.isNotEmpty()) {
            safeAdd("rank_label") { sectionLabel("🏅 YOUR CHAPTER RANK") }
            safeAdd("rank_card")  { buildChapterRankCard(validChapters) }
        }
    }

    private data class AtRiskEntry(
        val chapter: String, val subject: String,
        val recentAccuracy: Float, val overallAccuracy: Float
    )

    private fun buildAtRiskChapters(results: List<TestResult>): List<AtRiskEntry> {
        val gson = Gson()
        val questionListType = object : TypeToken<List<Question>>() {}.type
        val answerMapType    = object : TypeToken<Map<String, Int?>>() {}.type

        data class Slot(var correct: Int = 0, var wrong: Int = 0)
        val historical = mutableMapOf<String, Slot>() // all but last 2
        val recent     = mutableMapOf<String, Slot>() // last 2 results

        val sorted = results.sortedBy { it.completedAt }
        sorted.forEachIndexed { resultIdx, r ->
            val isRecent = resultIdx >= sorted.size - 2
            if (r.questionsJson.isEmpty() || r.answersJson.isEmpty()) return@forEachIndexed
            val questions = runCatching {
                gson.fromJson<List<Question>>(r.questionsJson, questionListType)
            }.getOrNull() ?: return@forEachIndexed
            val answers = runCatching {
                gson.fromJson<Map<String, Int?>>(r.answersJson, answerMapType)
            }.getOrNull() ?: return@forEachIndexed
            questions.forEachIndexed { idx, q ->
                val key = "${q.subject}::${q.chapter}"
                val ans = answers[idx.toString()]
                val map = if (isRecent) recent else historical
                val slot = map.getOrPut(key) { Slot() }
                when {
                    ans == null -> {}
                    ans == q.correctOptionIndex -> slot.correct++
                    else -> slot.wrong++
                }
            }
        }

        val atRisk = mutableListOf<AtRiskEntry>()
        historical.forEach { (key, hist) ->
            val rec = recent[key] ?: return@forEach
            val histTotal = hist.correct + hist.wrong
            val recTotal  = rec.correct + rec.wrong
            if (histTotal < 3 || recTotal < 2) return@forEach
            
            val histAcc = hist.correct.toFloat() / histTotal
            val recAcc  = rec.correct.toFloat()  / recTotal
            if (histAcc - recAcc >= 0.15f) {
                val parts = key.split("::", limit = 2)
                if (parts.size < 2) return@forEach
                atRisk.add(AtRiskEntry(parts[1], parts[0], recAcc, histAcc))
            }
        }
        return atRisk.sortedBy { it.recentAccuracy }
    }

    private fun buildImprovementLineChart(tests: List<TestResult>): View {
        val scores = tests.map { r ->
            if (r.maxScore > 0f) (r.score / r.maxScore * 100f).coerceIn(0f, 100f) else 0f
        }

        // Determine trend colour: green if last score >= first, red if declining
        val trendColor = if (scores.last() >= scores.first())
            Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
        val chartFillColor = if (scores.last() >= scores.first())
            Color.parseColor("#204ADE80") else Color.parseColor("#20F87171")

        val entries = scores.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val labels  = tests.mapIndexed { i, r ->
            val sdf = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            sdf.format(java.util.Date(r.completedAt))
        }

        val dataSet = LineDataSet(entries, "Score %").apply {
            color = trendColor
            setCircleColor(trendColor)
            circleRadius = 4f
            circleHoleRadius = 2f
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(true)
            valueTextSize = 9f
            valueTextColor = Color.parseColor("#CCFFFFFF")
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float) = value.toInt().toString()
            }
            setDrawFilled(true)
            setFillColor(chartFillColor)
            fillAlpha = 80
            highLightColor = Color.TRANSPARENT
        }

        val lineChart = LineChart(this).apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setBackgroundColor(Color.TRANSPARENT)
            setNoDataText("No data yet")

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                setDrawGridLines(false)
                textColor = Color.parseColor("#99FFFFFF")
                textSize = 9f
                granularity = 1f
                labelCount = labels.size
                isGranularityEnabled = true
                labelRotationAngle = -45f
            }
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 100f
                textColor = Color.parseColor("#99FFFFFF")
                textSize = 9f
                setDrawGridLines(true)
                gridColor = Color.parseColor("#20FFFFFF")
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}%"
                }
            }
            axisRight.isEnabled = false
            extraBottomOffset = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 240.dp
            ).also { it.bottomMargin = Space.S.dp }
            animateX(700)
        }

        val card = uiCard(radius = Corner.L, elevation = Elev.NONE, background = bgSecondary).apply {
            layoutParams = lpRow(bottomDp = Space.S)
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)

            // Trend indicator row
            val trendRow = LinearLayout(this@InsightsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = Space.XS.dp }
            }
            val delta = scores.last() - scores.first()
            val trendText = when {
                delta > 0 -> "📈 Trending Up  (+%.0f%% from first test)".format(delta)
                delta < 0 -> "📉 Trending Down  (%.0f%% from first test)".format(delta)
                else -> "➖ Flat Trend  (No change from first test)"
            }
            val trendColorTv = when {
                delta > 0 -> correctGreen
                delta < 0 -> wrongRed
                else -> textMuted
            }
            trendRow.addView(uiTextView(UiText.CAPTION, trendText, trendColorTv))
            addView(trendRow)
            addView(lineChart)
        })
        return card
    }

    private fun buildChapterRankCard(chapters: List<ChapterStats>): View {
        val card = uiCard(radius = Corner.L, elevation = Elev.NONE, background = bgSecondary).apply {
            layoutParams = lpRow(bottomDp = Space.S)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
        }

        // Take top 4 chapters by attempts
        val top4 = chapters.sortedByDescending { it.total }.take(4)
        top4.forEach { stats ->
            // Estimate rank: accuracy 90%+ = top 5%, 80%+ = top 15%, 70%+ = top 30%,
            // 60%+ = top 45%, 50%+ = top 60%, else bottom 40%
            val topPercent = when {
                stats.accuracy >= 0.90f -> 5
                stats.accuracy >= 0.80f -> 15
                stats.accuracy >= 0.70f -> 30
                stats.accuracy >= 0.60f -> 45
                stats.accuracy >= 0.50f -> 60
                else                    -> 80
            }
            val rankColor = when {
                topPercent <= 15 -> correctGreen
                topPercent <= 45 -> goldPrimary
                else             -> wrongRed
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = Space.S.dp }
            }
            val labelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            labelCol.addView(uiTextView(UiText.BODY,
                "${stats.subject} — ${stats.chapter}", textPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            
            // Calculate "Questions away" nudge
            val nextThreshold = when {
                stats.accuracy >= 0.90f -> 1.00f
                stats.accuracy >= 0.80f -> 0.90f
                stats.accuracy >= 0.70f -> 0.80f
                stats.accuracy >= 0.60f -> 0.70f
                stats.accuracy >= 0.50f -> 0.60f
                else                    -> 0.50f
            }
            val targetCorrect = Math.ceil((nextThreshold * stats.total).toDouble()).toInt()
            val currentCorrect = Math.round(stats.accuracy * stats.total)
            val diff = targetCorrect - currentCorrect
            val nextBracket = when (nextThreshold) {
                1.00f -> "Mastery"
                0.90f -> "Top 5%"
                0.80f -> "Top 15%"
                0.70f -> "Top 30%"
                0.60f -> "Top 45%"
                else  -> "Top 60%"
            }

            val nudgeText = if (diff > 0 && diff <= 5) 
                "%.0f%% accuracy  ·  Only $diff correct away from $nextBracket!".format(stats.accuracy * 100)
            else 
                "%.0f%% accuracy  ·  ${stats.total} questions".format(stats.accuracy * 100)

            labelCol.addView(uiTextView(UiText.CAPTION, nudgeText, 
                if (diff > 0 && diff <= 5) goldPrimary else textMuted
            ))
            row.addView(labelCol)

            // Rank badge
            val rankBadge = TextView(this).apply {
                text = "Top $topPercent%"
                textSize = UiText.OVERLINE.size
                setTextColor(rankColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(
                    Color.argb(30, Color.red(rankColor), Color.green(rankColor), Color.blue(rankColor)),
                    Corner.XL
                )
                setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            }
            row.addView(rankBadge)
            inner.addView(row)
        }

        if (top4.isEmpty()) {
            inner.addView(uiTextView(UiText.BODY, "Complete more tests to see your rank.", textMuted))
        }
        card.addView(inner)
        return card
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 10f
        setTextColor(goldPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.15f
        isAllCaps = true
        setPadding(Space.XS.dp, Space.XL.dp, 0, Space.S.dp)
    }

    private fun performanceOverviewCard(
        accuracy: Float, totalTests: Int, totalAttempted: Int, avgTimePerQMs: Long
    ): View {
        val card = uiCard(
            radius = 18f,
            elevation = Elev.NONE,
            background = bgSecondary,
            strokeDp = 1,
            strokeColor = Color.parseColor("#30F59E0B")
        ).apply {
            layoutParams = lpRow(bottomDp = Space.XS)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accuracyColor = when {
            accuracy >= 70 -> correctGreen
            accuracy >= 40 -> goldPrimary
            else           -> wrongRed
        }
        topRow.addView(statBox("Accuracy",    "%.0f%%".format(accuracy), accuracyColor))
        topRow.addView(dividerView())
        topRow.addView(statBox("Tests Taken", "$totalTests",             Color.parseColor("#3B82F6")))
        topRow.addView(dividerView())
        topRow.addView(statBox("Qs Attempted","$totalAttempted",         Color.parseColor("#8B5CF6")))
        col.addView(topRow)

        if (avgTimePerQMs > 0) {
            val avgSecs = avgTimePerQMs / 1000
            val timeStr = if (avgSecs >= 60) "%dm %ds".format(avgSecs / 60, avgSecs % 60)
                          else "${avgSecs}s"
            col.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#15FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.topMargin = Space.M.dp; it.bottomMargin = Space.M.dp }
            })
            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            bottomRow.addView(statBox("Avg Time / Q", timeStr, Color.parseColor("#06B6D4")))
            col.addView(bottomRow)
        }

        card.addView(col)
        return card
    }

    private fun statBox(label: String, value: String, color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@InsightsActivity).apply {
            text = value; textSize = 24f; setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        addView(uiTextView(UiText.OVERLINE, label, textMuted, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, Space.XS.dp, 0, 0)
        })
    }

    private fun dividerView(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#20FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT).also {
            it.marginStart = Space.XS.dp; it.marginEnd = Space.XS.dp
        }
    }

    private fun insightCard(label: String, value: String, description: String, accentColor: Int): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.NONE,
            background = bgSecondary
        ).apply {
            layoutParams = lpRow(bottomDp = Space.S)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, 18.dp, Space.XL.dp, 18.dp)
        }
        inner.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(accentColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = true; letterSpacing = 0.08f
        })
        inner.addView(TextView(this).apply {
            text = value; textSize = 22f; setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, Space.XS.dp, 0, Space.XS.dp)
        })
        inner.addView(uiTextView(UiText.LABEL, description, textTertiary).apply {
            setLineSpacing(0f, 1.1f)
        })
        card.addView(inner)
        return card
    }

    private fun buildDailyGoalCard(attempted: Int, goal: Int): View {
        val progress = (attempted.toFloat() / goal).coerceIn(0f, 1f)
        val done = attempted >= goal
        val accentColor = if (done) correctGreen else Color.parseColor("#3B82F6")

        val card = uiCard(radius = Corner.L, elevation = Elev.NONE, background = bgSecondary).apply {
            layoutParams = lpRow(bottomDp = Space.S)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = Space.S.dp }
        }
        topRow.addView(uiTextView(UiText.BODY,
            if (done) "✅ Goal Complete!" else "$attempted / $goal questions",
            if (done) correctGreen else textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(uiTextView(UiText.CAPTION, "%.0f%%".format(progress * 100), accentColor))
        inner.addView(topRow)

        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8.dp)
        }
        track.addView(View(this).apply {
            background = roundedFill(bgTertiary, 4f)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val fill = View(this).apply {
            background = roundedFill(accentColor, 4f)
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        track.addView(fill)
        track.post {
            val targetW = (track.width * progress).toInt()
            ValueAnimator.ofInt(0, targetW).apply {
                duration = 700; interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).also {
                        it.width = anim.animatedValue as Int
                    }
                }
                start()
            }
        }
        inner.addView(track)
        inner.addView(uiTextView(UiText.CAPTION,
            if (done) "Amazing consistency! 🔥 Keep going!"
            else "Daily goal: $goal questions  •  ${(goal - attempted).coerceAtLeast(0)} remaining",
            textMuted).apply { setPadding(0, Space.XS.dp, 0, 0) })
        card.addView(inner)
        return card
    }

    private fun buildAiCounselorCard(advice: String, readiness: Int): View {
        var expanded = false

        val adviceText = uiTextView(UiText.BODY, advice, textPrimary).apply {
            setLineSpacing(0f, 1.3f)
            setPadding(0, Space.S.dp, 0, 0)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val chevron = TextView(this).apply {
            text = "▼"; textSize = 11f
            setTextColor(goldPrimary)
            setPadding(Space.S.dp, 0, 0, 0)
        }

        val toggleHint = uiTextView(UiText.CAPTION, "Tap to read more", goldPrimary).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        }

        val card = uiAiCard(
            radius = Corner.L,
            accentColor = goldPrimary,
            onClick = {
                expanded = !expanded
                if (expanded) {
                    adviceText.maxLines = Int.MAX_VALUE
                    adviceText.ellipsize = null
                    toggleHint.text = "Show less ▲"
                    chevron.text = "▲"
                } else {
                    adviceText.maxLines = 2
                    adviceText.ellipsize = TextUtils.TruncateAt.END
                    toggleHint.text = "Tap to read more"
                    chevron.text = "▼"
                }
            }
        )

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@InsightsActivity).apply {
                text = "✨"; textSize = 20f
            })
            addView(uiTextView(UiText.H3, " AI Personal Counselor", goldPrimary).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@InsightsActivity).apply {
                text = "$readiness% READY"
                textSize = 9f
                setTextColor(bgPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(goldPrimary, 4f)
                setPadding(8.dp, 2.dp, 8.dp, 2.dp)
            })
            addView(chevron)
        }
        inner.addView(header)
        inner.addView(adviceText)
        inner.addView(toggleHint)

        adviceText.post {
            val layout = adviceText.layout ?: return@post
            val lastLine = layout.lineCount - 1
            if (lastLine < 0 || layout.getEllipsisCount(lastLine) == 0) {
                toggleHint.visibility = View.GONE
                chevron.visibility = View.GONE
            }
        }

        card.addView(inner)
        return card
    }
}
