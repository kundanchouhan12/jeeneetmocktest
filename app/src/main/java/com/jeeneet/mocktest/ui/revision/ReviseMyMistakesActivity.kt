package com.jeeneet.mocktest.ui.revision

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.WrongQuestionResult
import com.jeeneet.mocktest.data.repository.MockTestRepository
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.test.TestActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Revise My Mistakes — aggregates all incorrectly answered questions across
 * completed tests, deduplicates by Question.id, and lets the user launch a
 * 30-question revision test (random or weakest-subject focused).
 *
 * Heavy JSON processing runs on IO dispatcher via MockTestRepository.getWrongQuestions().
 */
class ReviseMyMistakesActivity : AppCompatActivity() {

    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private var examType = "JEE"
    private var wrongResult: WrongQuestionResult? = null

    companion object {
        private const val REVISION_SESSION_SIZE = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        examType = intent.getStringExtra("exam_type") ?: PrefManager.getSelectedExam(this)
        setContentView(buildLayout())
        loadWrongQuestions()

        try {
            AnalyticsManager.screenView(this, "ReviseMyMistakes")
        } catch (_: Exception) {}
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
        }

        // ─── Toolbar ─────────────────────────────────────────────────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
            elevation = Elev.S
        }
        toolbar.addView(TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, Space.L.dp, 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "🎯 Revise My Mistakes"
            textSize = 18f; setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(toolbar)

        // ─── Loading ─────────────────────────────────────────────────────────
        loadingIndicator = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.CENTER
                it.topMargin = Space.HUGE.dp
            }
        }

        // ─── Scrollable content ──────────────────────────────────────────────
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Space.L.dp
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(contentContainer)

        contentContainer.addView(loadingIndicator)
        root.addView(scroll)

        return root
    }

    private fun loadWrongQuestions() {
        lifecycleScope.launch {
            loadingIndicator.visibility = View.VISIBLE
            val repo = MockTestRepository(this@ReviseMyMistakesActivity)
            wrongResult = withContext(Dispatchers.IO) {
                try {
                    repo.getWrongQuestions(examType)
                } catch (e: Exception) {
                    android.util.Log.e("ReviseMyMistakes", "Failed: ${e.message}")
                    null
                }
            }
            loadingIndicator.visibility = View.GONE
            renderContent()
        }
    }

    private fun renderContent() {
        contentContainer.removeAllViews()
        val result = wrongResult

        if (result == null || result.questions.isEmpty()) {
            renderEmptyState()
            return
        }

        val totalWrong = result.questions.size

        // ─── Hero summary card ──────────────────────────────────────────────
        val heroCard = uiCard(radius = Corner.L, elevation = Elev.M).apply {
            layoutParams = lpRow(bottomDp = Space.L)
        }
        val heroInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(Color.parseColor("#EA580C"), Color.parseColor("#DC2626"))
                cornerRadius = Corner.L.dpF
            }
            setPadding(Space.XL.dp, Space.XXL.dp, Space.XL.dp, Space.XXL.dp)
        }
        heroInner.addView(TextView(this).apply {
            text = "🎯"; textSize = 40f; gravity = Gravity.CENTER
        })
        heroInner.addView(TextView(this).apply {
            text = "$totalWrong Wrong Questions"
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, Space.S.dp, 0, 0)
        })
        heroInner.addView(TextView(this).apply {
            text = "from ${result.fromTestCount} completed tests"
            textSize = 13f; setTextColor(Color.parseColor("#DDFFFFFF")); gravity = Gravity.CENTER
            setPadding(0, 4.dp, 0, 0)
        })
        heroCard.addView(heroInner)
        contentContainer.addView(heroCard)

        // ─── Subject breakdown ──────────────────────────────────────────────
        contentContainer.addView(uiSectionLabel("Subject Breakdown"))

        result.bySubject.entries.sortedByDescending { it.value }.forEach { (subject, count) ->
            val pct = (count.toFloat() / totalWrong * 100).toInt()
            val subjectColor = when (subject) {
                "Physics"   -> Color.parseColor("#3B82F6")
                "Chemistry" -> Color.parseColor("#10B981")
                "Maths"     -> Color.parseColor("#8B5CF6")
                "Biology"   -> Color.parseColor("#F59E0B")
                else        -> Color.parseColor("#6B7280")
            }

            val card = uiCard(radius = Corner.M, elevation = Elev.S, background = bgSecondary).apply {
                layoutParams = lpRow(bottomDp = Space.S)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            }

            // Subject name + count row
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(subjectColor); cornerRadius = 100f
                }
                layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).also {
                    it.marginEnd = Space.S.dp
                }
            })
            row.addView(TextView(this).apply {
                text = subject; textSize = 15f; setTextColor(textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "$count questions ($pct%)"
                textSize = 12f; setTextColor(textSecondary)
            })
            inner.addView(row)

            // Progress bar
            val progressBg = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A000000")); cornerRadius = 100f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 6.dp
                ).also { it.topMargin = Space.S.dp }
            }
            inner.addView(progressBg)

            val progressFill = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(subjectColor); cornerRadius = 100f
                }
                layoutParams = LinearLayout.LayoutParams(0, 6.dp).also {
                    it.topMargin = (-6).dp // overlap the bg
                }
            }
            inner.addView(progressFill)

            // Animate bar width after layout
            progressFill.post {
                val targetWidth = (progressBg.width * pct / 100f).toInt()
                val anim = android.animation.ValueAnimator.ofInt(0, targetWidth)
                anim.duration = 600
                anim.addUpdateListener { va ->
                    progressFill.layoutParams = progressFill.layoutParams.also { lp ->
                        lp.width = va.animatedValue as Int
                    }
                    progressFill.requestLayout()
                }
                anim.start()
            }

            // Chapter tags
            val chapters = result.byChapter.filter { it.key.startsWith("$subject:") }
                .entries.sortedByDescending { it.value }.take(5)
            if (chapters.isNotEmpty()) {
                val chipRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, Space.S.dp, 0, 0)
                }
                chapters.forEach { (chapterKey, chCount) ->
                    val chName = chapterKey.substringAfter(": ")
                    chipRow.addView(TextView(this).apply {
                        text = "$chName ($chCount)"
                        textSize = 9f; setTextColor(textSecondary)
                        background = GradientDrawable().apply {
                            setColor(bgTertiary); cornerRadius = Corner.PILL.dpF
                        }
                        setPadding(Space.S.dp, 3.dp, Space.S.dp, 3.dp)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.marginEnd = 4.dp }
                    })
                }
                inner.addView(chipRow)
            }

            card.addView(inner)
            contentContainer.addView(card)
        }

        // ─── Action Buttons ─────────────────────────────────────────────────
        contentContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp
            ).also { it.topMargin = Space.L.dp; it.bottomMargin = Space.M.dp }
            setBackgroundColor(dividerColor)
        })

        val sessionSize = minOf(REVISION_SESSION_SIZE, totalWrong)

        // Primary: Start Revision
        contentContainer.addView(MaterialButton(this).apply {
            text = "🎯  Start Revision ($sessionSize Qs)"
            textSize = 15f; setTextColor(Color.WHITE); isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EA580C"))
            stateListAnimator = null
            cornerRadius = Corner.M.toInt().dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 54.dp
            ).also { it.bottomMargin = Space.S.dp }
            setOnClickListener { launchRevision(result.questions, sessionSize) }
        })

        // Secondary: Focus on Weakest Subject
        val weakestSubject = result.bySubject.maxByOrNull { it.value }?.key
        if (weakestSubject != null) {
            val weakCount = result.questions.count { it.subject == weakestSubject }
            val weakSessionSize = minOf(REVISION_SESSION_SIZE, weakCount)
            contentContainer.addView(MaterialButton(this).apply {
                text = "⚡  Focus: $weakestSubject ($weakSessionSize Qs)"
                textSize = 14f; setTextColor(colorPrimary); isAllCaps = false
                stateListAnimator = null
                background = GradientDrawable().apply {
                    cornerRadius = Corner.M.dpF
                    setColor(Color.TRANSPARENT)
                    setStroke(2.dp, colorPrimary)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 50.dp
                ).also { it.bottomMargin = Space.XL.dp }
                setOnClickListener {
                    val subjectQuestions = result.questions.filter { q -> q.subject == weakestSubject }
                    launchRevision(subjectQuestions, weakSessionSize)
                }
            })
        }
    }

    private fun renderEmptyState() {
        contentContainer.removeAllViews()

        val emptyCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.XL.dp, Space.HUGE.dp, Space.XL.dp, Space.HUGE.dp)
        }
        emptyCol.addView(TextView(this).apply {
            text = "🎉"; textSize = 48f; gravity = Gravity.CENTER
        })
        emptyCol.addView(TextView(this).apply {
            text = "No Mistakes Found!"
            textSize = 22f; setTextColor(textPrimary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, Space.L.dp, 0, Space.S.dp)
        })
        emptyCol.addView(TextView(this).apply {
            text = "You haven't gotten any questions wrong yet.\nKeep taking tests and your mistakes will appear here for revision."
            textSize = 14f; setTextColor(textSecondary); gravity = Gravity.CENTER
            setPadding(0, 0, 0, Space.XL.dp)
        })
        emptyCol.addView(MaterialButton(this).apply {
            text = "← Back to Home"; textSize = 14f; isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
            stateListAnimator = null
            cornerRadius = Corner.M.toInt().dp
            setOnClickListener { finish() }
        })
        contentContainer.addView(emptyCol)
    }

    private fun launchRevision(questions: List<Question>, sessionSize: Int) {
        val selected = questions.shuffled().take(sessionSize)
        if (selected.isEmpty()) {
            Toast.makeText(this, "No questions to revise", Toast.LENGTH_SHORT).show()
            return
        }
        TestActivity.startRevision(this, examType, Gson().toJson(selected))
    }
}
