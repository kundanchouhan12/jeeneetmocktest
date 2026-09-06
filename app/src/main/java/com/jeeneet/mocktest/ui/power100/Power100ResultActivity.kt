package com.jeeneet.mocktest.ui.power100

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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.button.MaterialButton
import com.jeeneet.mocktest.data.model.Power100Progress
import com.jeeneet.mocktest.data.model.Power100Question
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.style.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Power100ResultActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EXAM = "extra_exam"

        fun start(context: Context, exam: String) {
            context.startActivity(Intent(context, Power100ResultActivity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
            })
        }
    }

    private lateinit var exam: String
    private lateinit var uid: String

    private var questions: List<Power100Question> = emptyList()
    private var progressMap: Map<Int, Power100Progress> = emptyMap()
    private var currentWrongFilter: String = "All"

    // Wrong questions RecyclerView
    private lateinit var wrongRecycler: RecyclerView
    private lateinit var wrongFilterRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exam = intent.getStringExtra(EXTRA_EXAM) ?: "JEE"
        uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"

        val root = ScrollView(this).apply {
            setBackgroundColor(bgPrimary)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, 0, Space.L.dp, Space.XL.dp)
        }
        root.addView(content)
        setContentView(root)

        lifecycleScope.launch {
            val db = MockTestDatabase.getInstance(this@Power100ResultActivity)
            questions = withContext(Dispatchers.IO) { db.power100Dao().getQuestionsForExam(exam) }
            val progressList = withContext(Dispatchers.IO) { db.power100Dao().getProgress(uid, exam) }
            progressMap = progressList.associateBy { it.position }
            withContext(Dispatchers.Main) { renderResult(content) }
        }
    }

    private fun renderResult(content: LinearLayout) {
        val attempted = progressMap.values.count { it.selectedOption >= 0 }
        val correct = progressMap.values.count { p ->
            p.selectedOption >= 0 && questions.getOrNull(p.position - 1)?.correctOptionIndex == p.selectedOption
        }
        val wrong = attempted - correct
        val powerScore = (correct * 1000 / 100).coerceAtMost(1000)
        val coinsEarned = correct * 4

        // Header
        content.addView(buildHeader())

        // Trophy + score card
        content.addView(buildScoreCard(correct, wrong, attempted, powerScore, coinsEarned))

        // Subject breakdown
        content.addView(uiSectionLabel("Performance by Subject").apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = Space.L.dp }
        })
        content.addView(buildSubjectBreakdown())

        // Action buttons
        content.addView(buildActionButtons())

        // Wrong questions section
        if (wrong > 0) {
            content.addView(uiSectionLabel("Wrong Questions ($wrong)").apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = Space.L.dp }
            })
            wrongFilterRow = buildWrongFilterTabs(wrong)
            content.addView(wrongFilterRow)
            wrongRecycler = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@Power100ResultActivity)
                isNestedScrollingEnabled = false
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Space.S.dp }
            }
            content.addView(wrongRecycler)
            refreshWrongList()

            val btnRetryAll = MaterialButton(this).apply {
                text = "Retry All Wrong ($wrong)"
                textSize = 13f; setTextColor(Color.WHITE)
                isAllCaps = false; stateListAnimator = null
                background = GradientDrawable().apply {
                    cornerRadius = Corner.M.dpF
                    setColor(Color.parseColor("#EF4444"))
                }
                layoutParams = LinearLayout.LayoutParams(-1, 52.dp).apply { topMargin = Space.M.dp }
                setOnClickListener { retryWrong() }
            }
            content.addView(btnRetryAll)
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Space.L.dp, 0, Space.S.dp)
        }
        row.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(textSecondary)
            setPadding(0, 0, Space.M.dp, 0)
            setOnClickListener { finish() }
        })
        row.addView(uiTextView(UiText.H2, "Power 100 Results", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(TextView(this).apply {
            text = exam; textSize = 11f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)), Corner.PILL)
            setPadding(Space.M.dp, 4.dp, Space.M.dp, 4.dp)
        })
        return row
    }

    private fun buildScoreCard(correct: Int, wrong: Int, attempted: Int, powerScore: Int, coins: Int): View {
        val card = uiCard(radius = Corner.L, elevation = Elev.M, background = bgSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Space.M.dp }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.L.dp, Space.XL.dp, Space.L.dp, Space.XL.dp)
        }

        // Trophy emoji
        inner.addView(TextView(this).apply {
            text = "🏆"; textSize = 48f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.M.dp }
        })

        val grade = when {
            powerScore >= 900 -> "Outstanding!"
            powerScore >= 750 -> "Excellent!"
            powerScore >= 600 -> "Good Work!"
            powerScore >= 400 -> "Keep Going!"
            else -> "Keep Practising!"
        }
        inner.addView(uiTextView(UiText.H2, grade, textPrimary, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 22f
        })
        inner.addView(uiTextView(UiText.CAPTION, "You have completed Power 100", textTertiary, Gravity.CENTER).apply {
            setPadding(0, 4.dp, 0, Space.L.dp)
        })

        // Power Score
        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED"))
            ).apply { cornerRadius = Corner.M.dpF }
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.L.dp }
        }
        scoreRow.addView(uiTextView(UiText.H2, "⚡ $powerScore", Color.WHITE, Gravity.CENTER).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 28f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        scoreRow.addView(uiTextView(UiText.BODY, "/1000\nPower Score", Color.parseColor("#C4B5FD"), Gravity.END).apply {
            textSize = 12f
        })
        inner.addView(scoreRow)

        // Stats row: Correct | Wrong | Coins
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        fun stat(value: String, label: String, color: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(uiTextView(UiText.H2, value, color, Gravity.CENTER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 20f
            })
            addView(uiTextView(UiText.CAPTION, label, textTertiary, Gravity.CENTER).apply { textSize = 10f; setPadding(0, 2.dp, 0, 0) })
        }
        statsRow.addView(stat("$correct", "Correct", correctGreen))
        statsRow.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1.dp, 40.dp) })
        statsRow.addView(stat("$wrong", "Wrong", wrongRed))
        statsRow.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1.dp, 40.dp) })
        statsRow.addView(stat("+$coins 🪙", "Coins", goldPrimary))
        inner.addView(statsRow)

        card.addView(inner)
        return card
    }

    private fun buildSubjectBreakdown(): View {
        val subjects = if (exam == "JEE") listOf("Physics", "Chemistry", "Maths")
                       else listOf("Physics", "Chemistry", "Biology")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(bgSecondary, Corner.L)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        }

        subjects.forEach { subject ->
            val subQuestions = questions.filter { it.subject == subject }
            val total = subQuestions.size
            if (total == 0) return@forEach
            val subCorrect = subQuestions.count { q ->
                progressMap[q.position]?.selectedOption == q.correctOptionIndex
            }
            val accuracy = if (total > 0) subCorrect * 100 / total else 0

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.M.dp }
            }
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp }
            }
            labelRow.addView(uiTextView(UiText.BODY, subject, textPrimary).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            labelRow.addView(uiTextView(UiText.BODY, "$subCorrect/$total ($accuracy%)", textSecondary).apply {
                textSize = 12f
            })
            row.addView(labelRow)

            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = total; progress = subCorrect
                layoutParams = LinearLayout.LayoutParams(-1, 8.dp).apply { bottomMargin = 2.dp }
                progressTintList = ColorStateList.valueOf(
                    when { accuracy >= 80 -> correctGreen; accuracy >= 50 -> goldPrimary; else -> wrongRed }
                )
                progressBackgroundTintList = ColorStateList.valueOf(bgTertiary)
            }
            row.addView(progressBar)
            container.addView(row)
        }
        return container
    }

    private fun buildActionButtons(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Space.L.dp }
        }
        val btnBack = MaterialButton(this).apply {
            text = "← Back"; textSize = 13f; setTextColor(textSecondary)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(bgTertiary)
            }
            layoutParams = LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginEnd = Space.S.dp }
            setOnClickListener { finish() }
        }
        val btnRevive = MaterialButton(this).apply {
            text = "⚡ Revive (+1 Life)"; textSize = 12f; setTextColor(Color.parseColor("#1A2540"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                colors = intArrayOf(goldPrimary, goldDark)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            layoutParams = LinearLayout.LayoutParams(0, 52.dp, 1.2f).apply { marginEnd = Space.S.dp }
            setOnClickListener {
                com.jeeneet.mocktest.admob.AdManager.showRewarded(
                    activity = this@Power100ResultActivity,
                    onRewarded = {
                        Toast.makeText(this@Power100ResultActivity, "Extra Life Granted! Challenge Revived 🎉", Toast.LENGTH_SHORT).show()
                        Power100Activity.start(this@Power100ResultActivity, exam)
                        finish()
                    },
                    onNotAvailable = {
                        Toast.makeText(this@Power100ResultActivity, "Ad not ready yet. Retrying...", Toast.LENGTH_SHORT).show()
                        com.jeeneet.mocktest.admob.AdManager.loadRewarded(this@Power100ResultActivity)
                    },
                    placement = "power100_revive"
                )
            }
        }
        val btnReattempt = MaterialButton(this).apply {
            text = "Re-attempt"; textSize = 13f; setTextColor(Color.WHITE)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(0, 52.dp, 1.2f)
            setOnClickListener {
                Power100Activity.start(this@Power100ResultActivity, exam)
                finish()
            }
        }
        row.addView(btnBack); row.addView(btnRevive); row.addView(btnReattempt)
        return row
    }

    // ─── Wrong Questions ──────────────────────────────────────────────────────

    private fun buildWrongFilterTabs(totalWrong: Int): LinearLayout {
        val subjects = mutableListOf("All")
        if (exam == "JEE") subjects.addAll(listOf("Physics", "Chemistry", "Maths"))
        else subjects.addAll(listOf("Physics", "Chemistry", "Biology"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = Space.S.dp }
        }
        subjects.forEach { sub ->
            val wrongCount = if (sub == "All") totalWrong else countWrongForSubject(sub)
            if (sub != "All" && wrongCount == 0) return@forEach
            val active = currentWrongFilter == sub
            row.addView(TextView(this).apply {
                text = if (sub == "All") "All ($totalWrong)" else "$sub ($wrongCount)"
                textSize = 11f
                setTextColor(if (active) colorPrimary else textMuted)
                typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                background = if (active) roundedFill(Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)), Corner.PILL) else null
                setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Space.XS.dp }
                setOnClickListener {
                    currentWrongFilter = sub
                    val parent = wrongFilterRow.parent as? LinearLayout ?: return@setOnClickListener
                    val idx = parent.indexOfChild(wrongFilterRow)
                    parent.removeView(wrongFilterRow)
                    wrongFilterRow = buildWrongFilterTabs(totalWrong)
                    parent.addView(wrongFilterRow, idx)
                    refreshWrongList()
                }
            })
        }
        return row
    }

    private fun countWrongForSubject(subject: String): Int =
        questions.count { q ->
            q.subject == subject &&
            progressMap[q.position]?.selectedOption.let { sel -> sel != null && sel >= 0 && sel != q.correctOptionIndex }
        }

    private fun refreshWrongList() {
        val wrongItems = questions.filter { q ->
            val sel = progressMap[q.position]?.selectedOption ?: -1
            sel >= 0 && sel != q.correctOptionIndex &&
            (currentWrongFilter == "All" || q.subject == currentWrongFilter)
        }
        wrongRecycler.adapter = WrongAdapter(wrongItems)
    }

    private fun retryWrong() {
        // Clear only wrong answers and reopen Power100Activity
        val wrongPositions = questions.filter { q ->
            val sel = progressMap[q.position]?.selectedOption ?: -1
            sel >= 0 && sel != q.correctOptionIndex
        }.map { it.position }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = MockTestDatabase.getInstance(this@Power100ResultActivity)
            wrongPositions.forEach { pos ->
                val existing = db.power100Dao().getProgressForPosition(uid, exam, pos)
                if (existing != null) {
                    db.power100Dao().upsertProgress(existing.copy(selectedOption = -1))
                }
            }
            withContext(Dispatchers.Main) {
                Power100Activity.start(this@Power100ResultActivity, exam)
                finish()
            }
        }
    }

    // ─── Wrong Questions Adapter ──────────────────────────────────────────────

    private inner class WrongAdapter(private val items: List<Power100Question>) :
        RecyclerView.Adapter<WrongAdapter.VH>() {

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(this@Power100ResultActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedFill(bgSecondary, Corner.M)
                setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Space.S.dp }
            }
            return VH(root)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val q = items[position]
            holder.root.removeAllViews()

            val headerRow = LinearLayout(this@Power100ResultActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.S.dp }
            }
            headerRow.addView(TextView(this@Power100ResultActivity).apply {
                text = "Q.${q.position}"; textSize = 12f; setTextColor(textSecondary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Space.S.dp }
            })
            headerRow.addView(TextView(this@Power100ResultActivity).apply {
                text = q.subject; textSize = 10f; setTextColor(colorPrimary)
                background = roundedFill(Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)), Corner.PILL)
                setPadding(Space.S.dp, 2.dp, Space.S.dp, 2.dp)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Space.XS.dp }
            })
            headerRow.addView(TextView(this@Power100ResultActivity).apply {
                text = q.difficulty; textSize = 10f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedFill(
                    when (q.difficulty) { "Easy" -> Color.parseColor("#22C55E"); "Hard" -> Color.parseColor("#EF4444"); else -> Color.parseColor("#F59E0B") },
                    Corner.PILL
                )
                setPadding(Space.S.dp, 2.dp, Space.S.dp, 2.dp)
            })
            holder.root.addView(headerRow)

            holder.root.addView(uiTextView(UiText.BODY, "", textPrimary).apply {
                textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }.also { com.jeeneet.mocktest.utils.MathRenderer.render(it, q.questionText) })

            val prog = progressMap[q.position]
            if (prog != null && prog.selectedOption >= 0) {
                val yourAnswerText = "Your answer: ${('A' + prog.selectedOption)}. ${q.options.getOrNull(prog.selectedOption) ?: ""}"
                val correctAnswerText = "Correct: ${('A' + q.correctOptionIndex)}. ${q.options.getOrNull(q.correctOptionIndex) ?: ""}"
                holder.root.addView(uiTextView(UiText.CAPTION, "", wrongRed).apply {
                    textSize = 11f; setPadding(0, Space.S.dp, 0, 2.dp)
                }.also { com.jeeneet.mocktest.utils.MathRenderer.render(it, yourAnswerText) })
                holder.root.addView(uiTextView(UiText.CAPTION, "", correctGreen).apply {
                    textSize = 11f
                }.also { com.jeeneet.mocktest.utils.MathRenderer.render(it, correctAnswerText) })
            }
        }
    }
}
