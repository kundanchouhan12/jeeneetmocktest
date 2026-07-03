package com.jeeneet.mocktest.ui.result

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.ui.style.*

class SolutionActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_QUESTIONS_JSON = "questions_json"
        private const val EXTRA_ANSWERS_JSON   = "answers_json"

        fun start(context: Context, questionsJson: String, answersJson: String) {
            context.startActivity(Intent(context, SolutionActivity::class.java).apply {
                putExtra(EXTRA_QUESTIONS_JSON, questionsJson)
                putExtra(EXTRA_ANSWERS_JSON, answersJson)
            })
        }
    }

    private var questions: List<Question> = emptyList()
    private var answers: Map<Int, Int?> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parseData()
        setContentView(buildLayout())
    }

    private fun parseData() {
        val qJson = intent.getStringExtra(EXTRA_QUESTIONS_JSON)
        val aJson = intent.getStringExtra(EXTRA_ANSWERS_JSON)
        val gson = com.google.gson.Gson()

        if (!qJson.isNullOrEmpty()) {
            val type = object : com.google.gson.reflect.TypeToken<List<Question>>() {}.type
            questions = runCatching { gson.fromJson<List<Question>>(qJson, type) }.getOrNull() ?: emptyList()
        }
        if (!aJson.isNullOrEmpty()) {
            val type = object : com.google.gson.reflect.TypeToken<Map<Int, Int?>>() {}.type
            answers = runCatching { gson.fromJson<Map<Int, Int?>>(aJson, type) }.getOrNull() ?: emptyMap()
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgPrimary)
        }

        root.addView(uiHeader("Solutions", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.XL.dp, Space.L.dp, Space.XXL.dp)
        }

        questions.forEachIndexed { i, q ->
            val userAnswer = answers[i]
            val isCorrect = userAnswer != null && userAnswer == q.correctOptionIndex
            val correctText = q.options.getOrNull(q.correctOptionIndex) ?: "N/A"

            container.addView(buildSolutionCard(
                number = i + 1,
                questionText = q.questionText,
                correctAnswer = correctText,
                explanation = q.explanation,
                isCorrect = isCorrect
            ))
        }

        if (questions.isEmpty()) {
            container.addView(uiTextView(UiText.BODY, "No questions found for this session.", textTertiary, Gravity.CENTER).apply {
                setPadding(0, 40.dp, 0, 0)
            })
        }

        scroll.addView(container)
        root.addView(scroll)
        return root
    }

    private fun buildSolutionCard(
        number: Int,
        questionText: String,
        correctAnswer: String,
        explanation: String,
        isCorrect: Boolean
    ): View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.M,
            background = bgSecondary
        ).apply {
            layoutParams = lpRow(bottomDp = 14)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 18.dp, 18.dp, 18.dp)
        }

        // Header: "Q1" + status badge
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Space.M.dp)
        }
        headerRow.addView(uiTextView(UiText.LABEL, "Q$number", textMuted).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val statusColor = if (isCorrect) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
        val statusBgColor = if (isCorrect) Color.parseColor("#1A22C55E") else Color.parseColor("#1AEF4444")
        headerRow.addView(TextView(this).apply {
            text = if (isCorrect) "✓ Correct" else "✗ Wrong"
            textSize = UiText.CAPTION.size
            setTextColor(statusColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(statusBgColor, Corner.L)
            setPadding(Space.M.dp, 5.dp, Space.M.dp, 5.dp)
        })
        inner.addView(headerRow)

        // Question
        val tvQuestion = uiTextView(UiText.BODY, "", textSecondary).apply {
            lineHeight = (22 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, 14.dp)
        }
        com.jeeneet.mocktest.utils.MathRenderer.render(tvQuestion, questionText)
        inner.addView(tvQuestion)

        // Correct answer callout
        val answerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(Color.parseColor("#0D22C55E"), Corner.M)
            setPadding(14.dp, Space.M.dp, 14.dp, Space.M.dp)
            layoutParams = lpRow(bottomDp = 10)
        }
        answerBox.addView(TextView(this).apply {
            text = "CORRECT ANSWER"; textSize = 10f
            setTextColor(Color.parseColor("#22C55E"))
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        val tvAnswer = uiTextView(UiText.BODY, "", Color.parseColor("#4ADE80")).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        }
        com.jeeneet.mocktest.utils.MathRenderer.render(tvAnswer, correctAnswer)
        answerBox.addView(tvAnswer)
        inner.addView(answerBox)

        // Explanation callout
        val expBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(bgTertiary, Corner.M)
            setPadding(14.dp, Space.M.dp, 14.dp, Space.M.dp)
        }
        expBox.addView(TextView(this).apply {
            text = "EXPLANATION"; textSize = 10f
            setTextColor(textMuted)
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        val tvExplanation = uiTextView(UiText.LABEL, "", textTertiary).apply {
            lineHeight = (20 * resources.displayMetrics.density).toInt()
            setPadding(0, Space.XS.dp, 0, 0)
        }
        com.jeeneet.mocktest.utils.MathRenderer.render(tvExplanation, explanation)
        expBox.addView(tvExplanation)
        inner.addView(expBox)

        card.addView(inner)
        return card
    }
}
