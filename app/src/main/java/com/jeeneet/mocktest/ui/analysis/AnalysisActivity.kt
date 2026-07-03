package com.jeeneet.mocktest.ui.analysis

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.Gson
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.data.model.IAPProducts
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.home.ShopActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager
import com.jeeneet.mocktest.data.repository.GroqRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AnalysisActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, AnalysisActivity::class.java))
    }

    private lateinit var containerPerformance: LinearLayout
    private lateinit var tvTotalTests: TextView
    private lateinit var tvAvgScore: TextView
    private lateinit var containerWeakTopics: LinearLayout
    private lateinit var containerContent: LinearLayout
    private val subjectExamMap = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadAnalysis()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgPrimary)
        }
        root.addView(uiHeader("📊 Performance Analysis", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        containerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.XL.dp, Space.L.dp, Space.XXL.dp)
        }
        scroll.addView(containerContent)

        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lpRow(bottomDp = Space.XL)
        }
        tvTotalTests = TextView(this).apply {
            text = "--"; textSize = 26f; setTextColor(goldLight)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        tvAvgScore = TextView(this).apply {
            text = "-- / --"; textSize = 26f; setTextColor(correctGreen)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        summaryRow.addView(buildStatCard("Tests Taken", tvTotalTests))
        summaryRow.addView(buildStatCard("Avg Marks", tvAvgScore))
        containerContent.addView(summaryRow)

        containerContent.addView(sectionLabel("Subject Performance"))
        containerPerformance = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        containerContent.addView(containerPerformance)

        containerContent.addView(sectionLabel("Weak Topics (< 50% accuracy)"))
        containerWeakTopics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        containerContent.addView(containerWeakTopics)

        root.addView(scroll)
        return root
    }

    private fun buildStatCard(label: String, valueView: TextView): View {
        val card = uiCard(
            radius = 14f,
            elevation = Elev.M,
            background = bgSecondary
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginEnd = 10.dp }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(14.dp, Space.XL.dp, 14.dp, Space.XL.dp)
        }
        inner.addView(valueView)
        inner.addView(uiTextView(UiText.CAPTION, label, textMuted, Gravity.CENTER).apply {
            setPadding(0, 6.dp, 0, 0)
        })
        card.addView(inner)
        return card
    }

    private fun loadAnalysis() {
        val db = MockTestDatabase.getInstance(this)
        val gson = Gson()
        val questionListType = object : TypeToken<List<Question>>() {}.type
        val answerMapType    = object : TypeToken<Map<String, Int?>>() {}.type
        lifecycleScope.launch {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val allResults = db.testResultDao().getAllResults(uid).first()
            tvTotalTests.text = allResults.size.toString()
            if (allResults.isNotEmpty()) {
                val validResults = allResults.filter { it.maxScore > 0 }
                val avgScore    = if (validResults.isEmpty()) 0.0 else validResults.map { it.score.toDouble() }.average()
                val avgMaxScore = if (validResults.isEmpty()) 0.0 else validResults.map { it.maxScore.toDouble() }.average()
                tvAvgScore.text = "%.0f / %.0f".format(avgScore.coerceAtLeast(0.0), avgMaxScore)

                subjectExamMap.clear()
                val subjectMap = mutableMapOf<String, Triple<Int, Int, Int>>()
                allResults.forEach { r ->
                    if (r.subject != "Full Paper") {
                        val curr = subjectMap[r.subject] ?: Triple(0, 0, 0)
                        subjectMap[r.subject] = Triple(curr.first + r.correct, curr.second + r.wrong, curr.third + r.totalQuestions)
                        subjectExamMap[r.subject] = r.examType
                    } else if (r.questionsJson.isNotEmpty() && r.answersJson.isNotEmpty()) {
                        val questions = runCatching {
                            gson.fromJson<List<Question>>(r.questionsJson, questionListType)
                        }.getOrNull() ?: return@forEach
                        val answers = runCatching {
                            gson.fromJson<Map<String, Int?>>(r.answersJson, answerMapType)
                        }.getOrNull() ?: return@forEach
                        questions.forEachIndexed { idx, q ->
                            val subj = q.subject.ifEmpty { "Unknown" }
                            val curr = subjectMap[subj] ?: Triple(0, 0, 0)
                            val ans = answers[idx.toString()]
                            subjectMap[subj] = Triple(
                                curr.first  + (if (ans != null && ans == q.correctOptionIndex) 1 else 0),
                                curr.second + (if (ans != null && ans != q.correctOptionIndex) 1 else 0),
                                curr.third  + 1
                            )
                            if (!subjectExamMap.containsKey(subj)) subjectExamMap[subj] = r.examType
                        }
                    }
                }

                // Build recent (last 2) vs historical subject accuracy for trend badges
                val recentResults = allResults.take(2)
                val recentSubjectMap = mutableMapOf<String, Triple<Int, Int, Int>>()
                recentResults.forEach { r ->
                    if (r.subject != "Full Paper") {
                        val c = recentSubjectMap[r.subject] ?: Triple(0, 0, 0)
                        recentSubjectMap[r.subject] = Triple(c.first + r.correct, c.second + r.wrong, c.third + r.totalQuestions)
                    } else if (r.questionsJson.isNotEmpty() && r.answersJson.isNotEmpty()) {
                        val qs = runCatching { gson.fromJson<List<Question>>(r.questionsJson, questionListType) }.getOrNull() ?: return@forEach
                        val ans = runCatching { gson.fromJson<Map<String, Int?>>(r.answersJson, answerMapType) }.getOrNull() ?: return@forEach
                        qs.forEachIndexed { idx, q ->
                            val subj = q.subject.ifEmpty { "Unknown" }
                            val c = recentSubjectMap[subj] ?: Triple(0, 0, 0)
                            val a = ans[idx.toString()]
                            recentSubjectMap[subj] = Triple(
                                c.first  + (if (a != null && a == q.correctOptionIndex) 1 else 0),
                                c.second + (if (a != null && a != q.correctOptionIndex) 1 else 0),
                                c.third  + 1
                            )
                        }
                    }
                }

                containerPerformance.removeAllViews()
                containerWeakTopics.removeAllViews()

                var hasWeak = false
                subjectMap.forEach { (subject, stats) ->
                    val (correct, wrong, total) = stats
                    val accuracy = if (correct + wrong > 0) correct.toFloat() / (correct + wrong) * 100 else 0f

                    val trend = run {
                        val rec = recentSubjectMap[subject]
                        if (rec != null && allResults.size >= 3 && rec.first + rec.second >= 2) {
                            val recAcc  = rec.first.toFloat() / (rec.first + rec.second) * 100
                            when {
                                recAcc > accuracy + 8f  -> "📈"
                                recAcc < accuracy - 8f  -> "📉"
                                else                    -> null
                            }
                        } else null
                    }

                    containerPerformance.addView(buildSubjectRow(subject, correct, wrong, total, accuracy, trend))
                    if (accuracy < 50f && (correct + wrong) > 0) {
                        val examType = subjectExamMap[subject] ?: "JEE"
                        containerWeakTopics.addView(buildWeakTopicRow(subject, accuracy, examType))
                        hasWeak = true
                    }
                }

                if (!hasWeak) {
                    containerWeakTopics.addView(uiTextView(UiText.LABEL,
                        "No weak topics identified yet. Keep practicing!", textTertiary).apply {
                        setPadding(0, 10.dp, 0, 0)
                    })
                }
            }
        }
    }

    private fun buildWeakTopicRow(subject: String, accuracy: Float, examType: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        background = roundedFill(Color.parseColor("#1AEF4444"), Corner.M)
        layoutParams = lpRow(bottomDp = Space.S)
        isClickable = true
        isFocusable = true
        setOnClickListener { startPracticeWithPaywall(examType, subject) }

        addView(uiTextView(UiText.BODY, "⚠️ $subject", Color.parseColor("#EF4444")).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        addView(uiTextView(UiText.CAPTION,
            "%.0f%% · Tap to Practice →".format(accuracy),
            Color.parseColor("#EF4444")))
    }

    private fun buildSubjectRow(
        subject: String, correct: Int, wrong: Int, total: Int, accuracy: Float, trend: String? = null
    ): View {
        val card = uiCard(
            radius = 14f,
            elevation = 3f,
            background = bgSecondary
        ).apply {
            layoutParams = lpRow(bottomDp = 10)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val subjectLabel = if (trend != null) "$trend $subject" else subject
        topRow.addView(uiTextView(UiText.H3, subjectLabel, textPrimary).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val accColor = accuracyColor(accuracy)
        topRow.addView(uiTextView(UiText.H3, "%.0f%%".format(accuracy), accColor))

        val progressContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Space.S.dp
            ).also { it.topMargin = 10.dp; it.bottomMargin = Space.S.dp }
        }
        val trackView = View(this).apply {
            background = roundedFill(bgTertiary, 4f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progressContainer.addView(trackView)

        val fillView = View(this).apply {
            background = roundedFill(accColor, 4f)
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        progressContainer.addView(fillView)
        progressContainer.post {
            val targetWidth = (progressContainer.width * (accuracy / 100f)).toInt()
            ValueAnimator.ofInt(0, targetWidth).apply {
                duration = 700
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val params = fillView.layoutParams
                    params.width = anim.animatedValue as Int
                    fillView.layoutParams = params
                }
                start()
            }
        }

        inner.addView(topRow)
        inner.addView(progressContainer)
        inner.addView(uiTextView(UiText.CAPTION,
            "Correct: $correct  •  Wrong: $wrong  •  Total: $total", textMuted).apply {
            textSize = 11f
        })
        card.addView(inner)
        return card
    }

    private fun accuracyColor(accuracy: Float) = when {
        accuracy >= 70 -> correctGreen
        accuracy >= 50 -> goldPrimary
        else           -> wrongRed
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = UiText.CAPTION.size
        setTextColor(textMuted)
        isAllCaps = true; letterSpacing = 0.15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, Space.L.dp, 0, 10.dp)
    }

    private fun startPracticeWithPaywall(examType: String, subject: String) {
        val productId = when {
            subject == "Physics"   && examType == "NEET" -> IAPProducts.NEET_PHYSICS_PACK
            subject == "Physics"                         -> IAPProducts.JEE_PHYSICS_PACK
            subject == "Chemistry" && examType == "NEET" -> IAPProducts.NEET_CHEM_PACK
            subject == "Chemistry"                       -> IAPProducts.JEE_CHEM_PACK
            subject == "Maths"                           -> IAPProducts.JEE_MATHS_PACK
            subject == "Biology"                         -> IAPProducts.NEET_BIO_PACK
            else                                         -> IAPProducts.ALL_ACCESS_YEARLY
        }
        val isUnlocked = PrefManager.isAllAccessUnlocked(this) || PrefManager.isPackUnlocked(this, productId)

        if (isUnlocked) {
            TestActivity.start(this, examType, subject)
            return
        }

        val alreadyUsedToday = PrefManager.hasUsedAdFreeUnlockToday(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock $subject")
            .setMessage("Unlock the full $subject pack to practice your weak topics and boost your score.")
            .setPositiveButton("Buy ₹49") { _, _ -> ShopActivity.start(this) }
            .setNeutralButton(if (alreadyUsedToday) "Free try used ✓" else "Watch Ad (Free Today)") { _, _ ->
                if (alreadyUsedToday) return@setNeutralButton
                AdManager.showRewarded(this,
                    onRewarded = {
                        PrefManager.markAdFreeUnlockUsed(this)
                        TestActivity.start(this, examType, subject, adUnlocked = true)
                    },
                    onNotAvailable = { Toast.makeText(this, "Ad not available", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Try 10 Free Qs") { _, _ ->
                TestActivity.start(this, examType, subject, totalQuestions = 10)
            }
            .show()
    }


}
