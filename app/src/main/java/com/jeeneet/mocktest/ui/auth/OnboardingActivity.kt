package com.jeeneet.mocktest.ui.auth

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import kotlin.math.abs

class OnboardingActivity : AppCompatActivity() {

    private data class Page(val animRaw: Int, val title: String, val subtitle: String)

    private val pages = listOf(
        Page(
            animRaw  = R.raw.onboarding_study,
            title    = "Master JEE & NEET",
            subtitle = "Practice with thousands of questions\ncurated by expert educators"
        ),
        Page(
            animRaw  = R.raw.onboarding_progress,
            title    = "Track Your Progress",
            subtitle = "Detailed analytics reveal your\nstrengths and weak areas"
        ),
        Page(
            animRaw  = R.raw.onboarding_success,
            title    = "Achieve Your Goal",
            subtitle = "Join thousands of students\nwho cracked JEE & NEET"
        )
    )

    private var currentPage = 0
    private lateinit var pageContainer: FrameLayout
    private lateinit var dotViews: List<View>
    private lateinit var btnNext: TextView
    private lateinit var btnSkip: TextView
    private val pageViews = mutableListOf<View>()

    private var selectedExam = "JEE"
    private var selectedGoal = 20

    // Onboarding uses a fixed dark palette (not theme-based) for consistent branding across light/dark.
    private val onboardingBg  = Color.parseColor("#0F172A")
    private val accent  get() = colorPrimary
    private val accentDark get() = androidx.core.content.ContextCompat.getColor(this, R.color.color_primary_dark)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages.forEach { pageViews.add(buildPageView(it)) }
        pageViews.add(buildGoalPageView())
        setContentView(buildLayout())
        showPage(0, animated = false)
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(onboardingBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        pageContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.bottomMargin = 168.dp }
        }

        root.addView(pageContainer)
        root.addView(buildBottomNav())

        // Swipe left/right to change pages
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (abs(vX) > abs(vY) && abs(vX) > 300) {
                    if (vX < 0 && currentPage < pages.lastIndex) navigate(currentPage + 1, forward = true)
                    else if (vX > 0 && currentPage > 0)          navigate(currentPage - 1, forward = false)
                    return true
                }
                return false
            }
        })
        root.setOnTouchListener { _, e -> gesture.onTouchEvent(e); true }

        return root
    }

    private fun buildPageView(page: Page): View {
        val screenW = resources.displayMetrics.widthPixels
        val animSize = (screenW * 0.72f).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(40.dp, 48.dp, 40.dp, 0)

            // Lottie
            addView(LottieAnimationView(this@OnboardingActivity).apply {
                try {
                    setAnimation(page.animRaw)
                    repeatCount = LottieDrawable.INFINITE
                    playAnimation()
                } catch (e: Exception) {
                    android.util.Log.e("Onboarding", "Lottie load failed for page: ${e.message}")
                }
                layoutParams = LinearLayout.LayoutParams(animSize, animSize).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                    it.bottomMargin = 48.dp
                }
            })

            // Title
            addView(TextView(this@OnboardingActivity).apply {
                text = page.title
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 14.dp }
            })

            // Gold accent line
            addView(View(this@OnboardingActivity).apply {
                background = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(accent, accentDark)
                    cornerRadius = 4.dpF
                }
                layoutParams = LinearLayout.LayoutParams(48.dp, 4.dp).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = 18.dp
                }
            })

            // Subtitle
            addView(TextView(this@OnboardingActivity).apply {
                text = page.subtitle
                textSize = 16f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.55f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 168.dp
            ).apply { gravity = Gravity.BOTTOM }
            setPadding(32.dp, 0, 32.dp, 36.dp)
        }

        // Dot indicators
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 28.dp }
        }
        dotViews = pageViews.indices.map { i ->
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == 0) accent else Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(8.dp, 8.dp).also {
                    if (i > 0) it.marginStart = 8.dp
                }
            }.also { dotsRow.addView(it) }
        }
        nav.addView(dotsRow)

        // Skip + Next buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54.dp)
        }

        btnSkip = TextView(this).apply {
            text = "Skip"
            textSize = 15f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 27.dpF
            }
            setOnClickListener { finishOnboarding() }
        }

        btnNext = TextView(this).apply {
            text = "Next"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(accent, accentDark)
                cornerRadius = 27.dpF
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                if (currentPage < pageViews.lastIndex) navigate(currentPage + 1, forward = true)
                else finishOnboarding()
            }
        }

        btnRow.addView(btnSkip)
        btnRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(12.dp, 1)
        })
        btnRow.addView(btnNext)
        nav.addView(btnRow)

        return nav
    }

    // ─── Page switching ───────────────────────────────────────────────────────

    private fun showPage(index: Int, animated: Boolean, forward: Boolean = true) {
        val incoming = pageViews[index]
        val outgoing = pageContainer.getChildAt(0)
        val w = pageContainer.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels

        // Detach incoming from any previous parent before adding to container
        (incoming.parent as? ViewGroup)?.removeView(incoming)

        if (animated && outgoing != null && outgoing !== incoming) {
            // Add incoming behind outgoing first
            pageContainer.addView(incoming, 0)
            incoming.translationX = if (forward) w.toFloat() else -w.toFloat()

            // Slide incoming in
            ObjectAnimator.ofFloat(incoming, "translationX", incoming.translationX, 0f).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }

            // Slide outgoing out, then remove it so it doesn't stay in the hierarchy
            ObjectAnimator.ofFloat(outgoing, "translationX", 0f,
                if (forward) -w.toFloat() else w.toFloat()
            ).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        pageContainer.removeView(outgoing)
                        outgoing.translationX = 0f   // reset for next time it's shown
                    }
                })
                start()
            }
        } else {
            // No animation — just swap directly
            pageContainer.removeAllViews()
            pageContainer.addView(incoming)
            incoming.translationX = 0f
        }

        // Animate dots — use ValueAnimator to update layoutParams.width
        dotViews.forEachIndexed { i, dot ->
            val active = i == index
            (dot.background as GradientDrawable).setColor(
                if (active) accent else Color.parseColor("#334155")
            )
            val fromW = dot.layoutParams.width
            val toW   = if (active) 24.dp else 8.dp
            if (fromW != toW) {
                ValueAnimator.ofInt(fromW, toW).apply {
                    duration = 200
                    addUpdateListener {
                        dot.layoutParams.width = it.animatedValue as Int
                        dot.requestLayout()
                    }
                    start()
                }
            }
        }

        // Buttons
        btnNext.text = if (index == pageViews.lastIndex) "Get Started" else "Next"
        btnSkip.visibility = if (index == pageViews.lastIndex) View.INVISIBLE else View.VISIBLE
    }

    private fun navigate(index: Int, forward: Boolean) {
        currentPage = index
        showPage(index, animated = true, forward = forward)
    }

    private fun buildGoalPageView(): View {
        val examCardViews = mutableMapOf<String, FrameLayout>()
        val goalChipViews = mutableMapOf<Int, TextView>()

        val refreshExamCards: () -> Unit = {
            examCardViews.forEach { (exam, card) ->
                val selected = exam == selectedExam
                (card.background as GradientDrawable).apply {
                    setColor(if (selected) Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))
                             else Color.parseColor("#1E293B"))
                    setStroke(2.dp, if (selected) accent else Color.parseColor("#334155"))
                }
            }
        }

        val refreshGoalChips: () -> Unit = {
            goalChipViews.forEach { (goal, chip) ->
                val selected = goal == selectedGoal
                (chip.background as GradientDrawable).apply {
                    setColor(if (selected) Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))
                             else Color.parseColor("#1E293B"))
                    setStroke(2.dp, if (selected) accent else Color.parseColor("#334155"))
                }
                chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#94A3B8"))
                chip.typeface = Typeface.create("sans-serif-medium",
                    if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(40.dp, 48.dp, 40.dp, 0)

            addView(TextView(this@OnboardingActivity).apply {
                text = "🎯"; textSize = 48f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 20.dp }
            })

            addView(TextView(this@OnboardingActivity).apply {
                text = "Personalize Your Prep"
                textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 10.dp }
            })

            addView(TextView(this@OnboardingActivity).apply {
                text = "Choose your exam and daily target"
                textSize = 15f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
                setLineSpacing(0f, 1.55f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 40.dp }
            })

            // ── Exam selector ─────────────────────────────────────────────────
            addView(TextView(this@OnboardingActivity).apply {
                text = "I AM PREPARING FOR"
                textSize = 11f; setTextColor(Color.parseColor("#64748B"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 12.dp }
            })

            val examRow = LinearLayout(this@OnboardingActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 32.dp }
            }
            listOf("JEE" to "⚛️", "NEET" to "🧬").forEach { (exam, icon) ->
                val isSelected = exam == selectedExam
                val card = FrameLayout(this@OnboardingActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (isSelected) Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))
                                 else Color.parseColor("#1E293B"))
                        cornerRadius = 16.dpF
                        setStroke(2.dp, if (isSelected) accent else Color.parseColor("#334155"))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, 72.dp, 1f).also {
                        if (exam == "NEET") it.marginStart = 12.dp
                    }
                    setOnClickListener {
                        selectedExam = exam
                        refreshExamCards()
                    }
                }
                val inner = LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                inner.addView(TextView(this@OnboardingActivity).apply {
                    text = icon; textSize = 22f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 10.dp }
                })
                inner.addView(TextView(this@OnboardingActivity).apply {
                    text = exam; textSize = 17f; setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                card.addView(inner)
                examCardViews[exam] = card
                examRow.addView(card)
            }
            addView(examRow)

            // ── Daily goal selector ───────────────────────────────────────────
            addView(TextView(this@OnboardingActivity).apply {
                text = "MY DAILY TARGET"
                textSize = 11f; setTextColor(Color.parseColor("#64748B"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 12.dp }
            })

            val goalRow = LinearLayout(this@OnboardingActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            listOf(10 to "Casual", 20 to "Focused", 30 to "Intense").forEach { (goal, label) ->
                val isSelected = goal == selectedGoal
                val chip = TextView(this@OnboardingActivity).apply {
                    text = "$goal Qs"
                    textSize = 14f; gravity = Gravity.CENTER
                    setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#94A3B8"))
                    typeface = Typeface.create("sans-serif-medium",
                        if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        setColor(if (isSelected) Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))
                                 else Color.parseColor("#1E293B"))
                        cornerRadius = 24.dpF
                        setStroke(2.dp, if (isSelected) accent else Color.parseColor("#334155"))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, 52.dp, 1f).also {
                        if (goal > 10) it.marginStart = 10.dp
                    }
                    setOnClickListener {
                        selectedGoal = goal
                        refreshGoalChips()
                    }
                }
                goalChipViews[goal] = chip
                goalRow.addView(chip)
            }
            addView(goalRow)
        }
    }

    private fun finishOnboarding() {
        PrefManager.saveOnboardingPending(this, selectedExam, selectedGoal)
        PrefManager.setOnboardingDone(this)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

}
