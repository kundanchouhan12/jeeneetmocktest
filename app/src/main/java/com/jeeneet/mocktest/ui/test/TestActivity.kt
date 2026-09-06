package com.jeeneet.mocktest.ui.test

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.data.model.ExamConfig
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.repository.MockTestRepository
import com.jeeneet.mocktest.ui.result.ResultActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.admob.AdManager
import kotlinx.coroutines.launch

class TestActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EXAM           = "extra_exam"
        private const val EXTRA_SUBJECT        = "extra_subject"
        private const val EXTRA_CHAPTER        = "extra_chapter"
        private const val EXTRA_RESUME         = "extra_resume"
        private const val EXTRA_AD_UNLOCKED    = "extra_ad_unlocked"
        private const val EXTRA_DAILY_QUIZ     = "extra_daily_quiz"
        private const val EXTRA_QUESTIONS_JSON = "extra_questions_json"
        private const val EXTRA_SMART_PRACTICE = "extra_smart_practice"
        private const val EXTRA_SIMULATION     = "extra_simulation"
        private const val EXTRA_TOTAL_QUESTIONS = "extra_total_questions"

        fun start(context: Context, exam: String, subject: String? = null, chapter: String? = null, 
                  adUnlocked: Boolean = false, totalQuestions: Int = 0) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
                putExtra(EXTRA_SUBJECT, subject)
                putExtra(EXTRA_CHAPTER, chapter)
                putExtra(EXTRA_AD_UNLOCKED, adUnlocked)
                if (totalQuestions > 0) putExtra(EXTRA_TOTAL_QUESTIONS, totalQuestions)
            })
        }

        fun startDailyQuiz(context: Context, exam: String) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
                putExtra(EXTRA_DAILY_QUIZ, true)
            })
        }
 
        fun startSimulation(context: Context, exam: String) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
                putExtra(EXTRA_SIMULATION, true)
            })
        }

        fun resume(context: Context) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_RESUME, true)
            })
        }

        fun startRevision(context: Context, exam: String, questionsJson: String, isSimulation: Boolean = false) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
                putExtra(EXTRA_QUESTIONS_JSON, questionsJson)
                putExtra(EXTRA_SIMULATION, isSimulation)
            })
        }

        fun startSmartPractice(context: Context, exam: String) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
                putExtra(EXTRA_SMART_PRACTICE, true)
            })
        }

        fun startWithQuestions(context: Context, config: ExamConfig, questions: List<Question>) {
            context.startActivity(Intent(context, TestActivity::class.java).apply {
                putExtra(EXTRA_EXAM, config.examType)
                putExtra(EXTRA_CHAPTER, config.chapter)
                putExtra(EXTRA_DAILY_QUIZ, config.isDailyQuiz)
                val gson = com.google.gson.Gson()
                putExtra(EXTRA_QUESTIONS_JSON, gson.toJson(questions))
            })
        }
    }

    // Theme-aware colors
    // Color accessors come from UiStyle as Context extensions — no local duplication needed.

    private lateinit var viewModel: TestViewModel
    private lateinit var repo: MockTestRepository
    private var isFreeSession = false
    private var isSimulationMode = false
    private var isFullMock = false
    private var lastExitAdShownMs = 0L

    private lateinit var tvTimer: TextView
    private lateinit var tvQuestionNumber: TextView
    private lateinit var tvBookmarkStar: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var btnPrevious: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnMarkReview: MaterialButton
    private lateinit var btnHint: MaterialButton
    private lateinit var btnSubmit: MaterialButton
    private lateinit var btnPause: View
    private lateinit var rvPalette: RecyclerView
    private lateinit var paletteAdapter: QuestionPaletteAdapter

    // Drawables/views kept alive between taps so updateOptionHighlight() can mutate colors
    // without rebuilding the view tree or re-running MathRenderer.
    private val optionCardDrawables = mutableListOf<GradientDrawable>()
    private val optionLabelDrawables = mutableListOf<GradientDrawable>()
    private val optionLabelViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        repo = MockTestRepository(this)
        viewModel = ViewModelProvider(this, TestViewModelFactory(application, repo))[TestViewModel::class.java]

        setupObservers()
        setupBackPress()

        if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            val restored = viewModel.restoreSession()
            if (restored) {
                paletteAdapter.updateCount(viewModel.session.value?.questions?.size ?: 0)
                return
            }
        }
        loadAndStartTest()
        if (isSimulationMode) enableSimulationModeUI()
    }
 
    private fun enableSimulationModeUI() {
        btnPause.visibility = View.GONE
        // Force immersive full screen for distraction-free exam
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        Toast.makeText(this, "Focus Mode: No Pause | No Distractions", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // AdManager is an app-wide singleton — do NOT call AdManager.cleanup() here.
        // TestActivity finishes on every single test (Full Mock, Power100, Daily Vault,
        // Daily Quiz, chapter practice), and ResultActivity's onCreate() (which loads its
        // own banner/rewarded/native ad) typically runs before this onDestroy() fires.
        // Calling cleanup() here previously wiped/destroyed ads app-wide mid-load — including
        // tearing down a native ad ResultActivity had just rendered — right after every test.
    }

    private fun loadAndStartTest() {
        val exam       = intent.getStringExtra(EXTRA_EXAM) ?: "JEE"
        val subject    = intent.getStringExtra(EXTRA_SUBJECT)
        val chapter    = intent.getStringExtra(EXTRA_CHAPTER)
        val adUnlocked = intent.getBooleanExtra(EXTRA_AD_UNLOCKED, false)
        isSimulationMode = intent.getBooleanExtra(EXTRA_SIMULATION, false)

        // ─── Direct Questions mode (Vault, Revision, etc.) ──────
        val questionsJson = intent.getStringExtra(EXTRA_QUESTIONS_JSON)
        if (questionsJson != null) {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<Question>>() {}.type
            val questions = runCatching { gson.fromJson<List<Question>>(questionsJson, type) }.getOrNull() ?: emptyList()
            if (questions.isEmpty()) { finish(); return }
            
            // Check if it's Daily Vault
            val isVault = chapter == "Daily Vault"
            val config = if (isVault) {
                ExamConfig(exam, null, "Daily Vault", questions.size, questions.size, 4f, -1f, isDailyVault = true)
            } else {
                ExamConfig(exam, questions.first().subject, chapter ?: "Revision", questions.size, 40, 4f, -1f, isSimulation = isSimulationMode)
            }
            
            viewModel.startTest(config, questions, isSimulationMode)
            paletteAdapter.updateCount(questions.size)
            AdManager.loadRewarded(this)
            return
        }

        // ─── Smart Practice: AI-weighted question mix ─────────────────────────
        if (intent.getBooleanExtra(EXTRA_SMART_PRACTICE, false)) {
            lifecycleScope.launch {
                val questions = repo.buildSmartPracticeQuestions(this@TestActivity, exam)
                if (questions.isEmpty()) {
                    Toast.makeText(this@TestActivity,
                        "Take a few tests first — Smart Practice learns from your history!",
                        Toast.LENGTH_LONG).show()
                    finish(); return@launch
                }
                val config = ExamConfig(exam, null, null, questions.size, 40, 4f, -1f)
                viewModel.startTest(config, questions, isSimulationMode)
                paletteAdapter.updateCount(questions.size)
                AdManager.loadRewarded(this@TestActivity)
            }
            return
        }

        val isDailyQuiz = intent.getBooleanExtra(EXTRA_DAILY_QUIZ, false)
        val config = when {
            isSimulationMode && exam == "NEET" -> ExamConfig.neetFull()
            isSimulationMode                  -> ExamConfig.jeeMainsFull()
            isDailyQuiz -> ExamConfig.dailyQuiz(exam)
            chapter != null && subject != null -> ExamConfig.chapterWise(exam, subject, chapter)
            subject != null                    -> ExamConfig.subjectMock(exam, subject)
            exam == "NEET"                     -> ExamConfig.neetFull()
            else                               -> ExamConfig.jeeMainsFull()
        }

        // Apply total questions override if passed (e.g. for free trial "Try 10 Qs")
        val overrideTotal = intent.getIntExtra(EXTRA_TOTAL_QUESTIONS, 0)
        val finalConfig = if (overrideTotal > 0) {
            config.copy(totalQuestions = overrideTotal)
        } else config

        isFreeSession = !adUnlocked &&
            !com.jeeneet.mocktest.utils.PrefManager.isAllAccessUnlocked(this) &&
            (subject == null || !com.jeeneet.mocktest.utils.PrefManager.isPackUnlocked(
                this, repo.getProductIdForExamSubject(exam, subject)))

        isFullMock = subject == null && chapter == null && !isDailyQuiz && questionsJson == null && !intent.getBooleanExtra(EXTRA_SMART_PRACTICE, false)

        if (isFullMock && !isSimulationMode) {
            val generatedToday = PrefManager.getFullMocksGeneratedToday(this)
            val isPremium = PrefManager.isAllAccessUnlocked(this)
            
            if (!isPremium && generatedToday >= 1 && !adUnlocked) {
                val lastQs = PrefManager.getLastFullMockQuestionsJson(this)
                if (lastQs != null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Daily Limit Reached")
                        .setMessage("You have used your 1 free fresh mock test for today.\n\nYou can re-attempt today's test (Revision Mode) or watch an ad to unlock another fresh test.")
                        .setPositiveButton("Revision Mode") { _, _ ->
                            val gson = com.google.gson.Gson()
                            val type = object : com.google.gson.reflect.TypeToken<List<Question>>() {}.type
                            val qList = gson.fromJson<List<Question>>(lastQs, type).shuffled()
                            viewModel.startTest(finalConfig, qList, isSimulationMode)
                            paletteAdapter.updateCount(qList.size)
                            AnalyticsManager.testStarted(this@TestActivity, exam)
                            AdManager.loadRewarded(this@TestActivity)
                        }
                        .setNeutralButton("Watch Ad") { _, _ ->
                            if (!PrefManager.canWatchAdForExtraMock(this)) {
                                Toast.makeText(this, "Daily extra test limit reached. Upgrade to Premium for unlimited access!", Toast.LENGTH_LONG).show()
                                return@setNeutralButton
                            }
                            AdManager.showRewarded(this, onRewarded = {
                                PrefManager.incrementAdUsageCount(this, "extra_mock")
                                fetchAndStartTest(finalConfig, true, true, exam)
                            }, onNotAvailable = {
                                Toast.makeText(this, "Ad not available. Try again later.", Toast.LENGTH_SHORT).show()
                                finish()
                            }, onLoading = {
                                Toast.makeText(this, "Loading ad...", Toast.LENGTH_SHORT).show()
                            }, placement = "extra_mock")
                        }
                        .setNegativeButton("Cancel") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                    return
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Daily Limit Reached")
                        .setMessage("You have used your 1 free fresh mock test for today.\n\nWatch a short ad to unlock another fresh test.")
                        .setPositiveButton("Watch Ad") { _, _ ->
                            if (!PrefManager.canWatchAdForExtraMock(this)) {
                                Toast.makeText(this, "Daily extra test limit reached. Upgrade to Premium for unlimited access!", Toast.LENGTH_LONG).show()
                                return@setPositiveButton
                            }
                            AdManager.showRewarded(this, onRewarded = {
                                PrefManager.incrementAdUsageCount(this, "extra_mock")
                                fetchAndStartTest(finalConfig, true, true, exam)
                            }, onNotAvailable = {
                                Toast.makeText(this, "Ad not available. Try again later.", Toast.LENGTH_SHORT).show()
                                finish()
                            }, onLoading = {
                                Toast.makeText(this, "Loading ad...", Toast.LENGTH_SHORT).show()
                            }, placement = "extra_mock")
                        }
                        .setNegativeButton("Cancel") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                    return
                }
            }
            fetchAndStartTest(finalConfig, adUnlocked, true, exam)
        } else {
            fetchAndStartTest(finalConfig, adUnlocked, false, exam)
        }
    }

    private fun fetchAndStartTest(config: ExamConfig, adUnlocked: Boolean, isFullMock: Boolean, exam: String) {
        lifecycleScope.launch {
            val (questions, recycled) = repo.getQuestionsForConfig(this@TestActivity, config, adUnlocked)
            if (questions.isEmpty()) {
                Toast.makeText(this@TestActivity, "Questions will be added soon. Please check back later!", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (recycled) {
                Toast.makeText(
                    this@TestActivity,
                    "You've covered all the current questions here — we're adding more soon!",
                    Toast.LENGTH_LONG
                ).show()
            }
            if (isFullMock) {
                PrefManager.incrementFullMocksGeneratedToday(this@TestActivity)
                PrefManager.saveLastFullMockQuestionsJson(this@TestActivity, com.google.gson.Gson().toJson(questions))
            }
            viewModel.startTest(config, questions, isSimulationMode)
            paletteAdapter.updateCount(questions.size)
            AnalyticsManager.testStarted(this@TestActivity, exam)
            AdManager.loadRewarded(this@TestActivity)
        }
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.currentIndex.observe(this) { idx ->
            renderQuestion(idx)
            paletteAdapter.setCurrentIndex(idx)
            rvPalette.scrollToPosition(idx)
        }

        viewModel.timeLeftSeconds.observe(this) { secs ->
            val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
            val timeStr = if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            tvTimer.text = "⏱ $timeStr"
            tvTimer.setTextColor(if (secs < 300) Color.parseColor("#EF4444") else colorPrimary)
        }

        viewModel.session.observe(this) {
            val idx = viewModel.currentIndex.value ?: 0
            updateOptionHighlight(idx)
            paletteAdapter.notifyItemChanged(idx)
        }

        viewModel.testFinished.observe(this) { finished ->
            if (finished) {
                viewModel.result.value?.let { result ->
                    AnalyticsManager.testCompleted(this, result.examType, result.subject)
                    PrefManager.incrementTotalTestsCompleted(this)
                    
                    // Check for achievements
                    val scorePercent = (result.score.toFloat() / (result.totalQuestions * 4) * 100).toInt()
                    com.jeeneet.mocktest.data.repository.AchievementManager.checkTestResult(
                        this, scorePercent, result.timeTakenSeconds
                    )

                    val isDailyQuiz = intent.getBooleanExtra(EXTRA_DAILY_QUIZ, false)

                    AdManager.showInterstitial(
                        activity = this,
                        // Full mock / simulation completion is a high-value, infrequent moment —
                        // always show, same as Daily Quiz. Chapter/subject practice tests stay
                        // cooldown-gated since those can be taken back-to-back quickly.
                        bypassCooldown = isDailyQuiz || isFullMock || isSimulationMode,
                        onDismissed = {
                            ResultActivity.start(this, result.id.toLong())
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun renderQuestion(index: Int) {
        val sess = viewModel.session.value ?: return
        val q = if (index < sess.questions.size) sess.questions[index] else return

        tvQuestionNumber.text = "${q.subject}  ${index + 1} / ${sess.questions.size}"

        lifecycleScope.launch {
            val bookmarked = repo.isBookmarked(q.id)
            tvBookmarkStar.text = if (bookmarked) "★" else "☆"
            tvBookmarkStar.setTextColor(if (bookmarked) colorPrimary else textTertiary)
        }
        // Render question text async so LaTeX doesn't block the main thread
        lifecycleScope.launch {
            com.jeeneet.mocktest.utils.MathRenderer.renderAsync(tvQuestionText, q.questionText)
        }

        radioGroup.clearCheck()
        radioGroup.removeAllViews()
        optionCardDrawables.clear()
        optionLabelDrawables.clear()
        optionLabelViews.clear()

        // Question header with number + marking scheme
        val markingText = "Question ${index + 1}/${sess.questions.size}"
        val markingScheme = "+${sess.config.correctMarks.toInt()} / ${sess.config.negativeMarks.toInt()}"
        val qHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 6.dp }
        }
        qHeaderRow.addView(TextView(this).apply {
            text = markingText; textSize = 13f; setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        qHeaderRow.addView(TextView(this).apply {
            text = markingScheme; textSize = 12f; setTextColor(textTertiary)
        })
        // Remove previously added question header if any (tag = "qheader")
        val questionContainer = radioGroup.parent as? LinearLayout
        questionContainer?.findViewWithTag<View>("qheader")?.let { questionContainer.removeView(it) }
        qHeaderRow.tag = "qheader"
        questionContainer?.addView(qHeaderRow, questionContainer.indexOfChild(tvQuestionText))

        val selectedColor = colorPrimary
        val selectedBg = Color.argb(20, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))

        q.options.forEachIndexed { i, option ->
            val isSelected = sess.answers[index] == i

            val cardDrawable = GradientDrawable().apply {
                setColor(if (isSelected) selectedBg else bgSecondary)
                cornerRadius = Corner.M.dpF
                setStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) selectedColor else dividerColor)
            }
            optionCardDrawables.add(cardDrawable)

            val optionCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 14.dp, 14.dp, 14.dp)
                isClickable = true; isFocusable = true
                background = cardDrawable
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 10.dp }
            }

            // Circular A/B/C/D label
            val labelDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isSelected) selectedColor else bgTertiary)
            }
            optionLabelDrawables.add(labelDrawable)

            val tvLabel = TextView(this).apply {
                text = ('A' + i).toString()
                textSize = 13f
                setTextColor(if (isSelected) Color.WHITE else textTertiary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = labelDrawable
                layoutParams = LinearLayout.LayoutParams(34.dp, 34.dp).also { it.marginEnd = 12.dp }
            }
            optionLabelViews.add(tvLabel)
            val tvOption = TextView(this).apply {
                textSize = 14f
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            // Launch async so LaTeX parsing on Dispatchers.Default doesn't block the main thread.
            // Plain text shows instantly; math replaces it once rendered.
            lifecycleScope.launch {
                com.jeeneet.mocktest.utils.MathRenderer.renderAsync(tvOption, option)
            }

            optionCard.addView(tvLabel)
            optionCard.addView(tvOption)
            optionCard.setOnClickListener {
                // ① Instantly paint this option selected — no LiveData round-trip needed.
                //    This runs immediately even if setMarkdown() is still rendering on the
                //    main thread somewhere, because this executes in the same message.
                val prev = viewModel.session.value?.answers?.get(index) ?: -1
                val selBg = Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary))
                if (prev != i) {
                    if (prev in optionCardDrawables.indices) {
                        optionCardDrawables[prev].setColor(bgSecondary)
                        optionCardDrawables[prev].setStroke(1.dp, dividerColor)
                        optionLabelDrawables[prev].setColor(bgTertiary)
                        optionLabelViews[prev].setTextColor(textTertiary)
                    }
                    optionCardDrawables[i].setColor(selBg)
                    optionCardDrawables[i].setStroke(2.dp, colorPrimary)
                    optionLabelDrawables[i].setColor(colorPrimary)
                    optionLabelViews[i].setTextColor(Color.WHITE)
                }
                // ② Persist to ViewModel (triggers LiveData → updateOptionHighlight — redundant but correct)
                viewModel.selectAnswer(i)
            }
            radioGroup.addView(optionCard)
        }

        // Report button
        val existingReport = radioGroup.parent?.let { (it as? LinearLayout)?.findViewWithTag<View>("report_btn") }
        if (existingReport == null) {
            (radioGroup.parent as? LinearLayout)?.addView(TextView(this).apply {
                text = "⚑ Report"; textSize = 13f; setTextColor(textMuted)
                tag = "report_btn"
                setPadding(0, 8.dp, 0, 4.dp)
                setOnClickListener {
                    Toast.makeText(this@TestActivity, "Question reported. Thank you!", Toast.LENGTH_SHORT).show()
                }
            })
        }

        // Override radio group behavior - use direct click handling
        radioGroup.setOnCheckedChangeListener(null)

        val isMarked = sess.isMarkedForReview(index)
        btnMarkReview.text = if (isMarked) "Unmark" else "Mark Review"
    }

    // Mutates only the option highlight colors — no view rebuild, no MathRenderer calls.
    private fun updateOptionHighlight(index: Int) {
        val sess = viewModel.session.value ?: return
        val selectedAnswer = sess.answers[index]
        val selectedColor = colorPrimary
        val selectedBg = Color.argb(20, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))

        optionCardDrawables.forEachIndexed { i, d ->
            val sel = selectedAnswer == i
            d.setColor(if (sel) selectedBg else bgSecondary)
            d.setStroke(if (sel) 2.dp else 1.dp, if (sel) selectedColor else dividerColor)
        }
        optionLabelDrawables.forEachIndexed { i, d ->
            d.setColor(if (selectedAnswer == i) selectedColor else bgTertiary)
        }
        optionLabelViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (selectedAnswer == i) Color.WHITE else textTertiary)
        }
        val isMarked = sess.isMarkedForReview(index)
        btnMarkReview.text = if (isMarked) "Unmark" else "Mark Review"
    }

    // ─── Button actions ───────────────────────────────────────────────────────

    private fun setupButtonListeners() {
        btnNext.setOnClickListener { viewModel.nextQuestion() }
        btnPrevious.setOnClickListener { viewModel.previousQuestion() }
        btnMarkReview.setOnClickListener { viewModel.toggleMarkForReview(); updateOptionHighlight(viewModel.currentIndex.value ?: 0) }
        btnClear.setOnClickListener { viewModel.clearAnswer(); updateOptionHighlight(viewModel.currentIndex.value ?: 0) }
    }

    private fun toggleBookmark() {
        val sess = viewModel.session.value ?: return
        val idx = viewModel.currentIndex.value ?: return
        val q = sess.questions.getOrNull(idx) ?: return
        lifecycleScope.launch {
            val nowBookmarked = repo.toggleBookmark(q)
            tvBookmarkStar.text = if (nowBookmarked) "★" else "☆"
            tvBookmarkStar.setTextColor(if (nowBookmarked) colorPrimary else textTertiary)
            val msg = if (nowBookmarked) "Bookmarked!" else "Bookmark removed"
            android.widget.Toast.makeText(this@TestActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmSubmit() {
        val sess = viewModel.session.value ?: return
        val attempted = sess.answers.values.count { it != null }
        val total = sess.questions.size
        MaterialAlertDialogBuilder(this)
            .setTitle("Submit Test?")
            .setMessage("Attempted: $attempted / $total\nUnattempted: ${total - attempted}\n\nAre you sure you want to submit?")
            .setPositiveButton("Submit") { _, _ -> viewModel.finishTest() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Back press ───────────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showExitDialog()
        })
    }

    private fun showExitDialog() {
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 4.dp)
        }
        dialogContent.addView(TextView(this).apply {
            text = "Your progress will be saved. You can resume this test later from the home screen."
            textSize = 14f
            setTextColor(textTertiary)
            setPadding(0, 0, 0, 14.dp)
        })
        val adContainer = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Frequency Cap: Only show native ad in exit dialog once every 15 minutes
        val now = System.currentTimeMillis()
        if (now - lastExitAdShownMs > 15 * 60 * 1000L) {
            lastExitAdShownMs = now
            com.jeeneet.mocktest.admob.AdManager.loadNativeAd(this, adContainer)
            dialogContent.addView(adContainer)
        }

        val exitDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Exit Test?")
            .setView(dialogContent)
            .setPositiveButton("Save & Exit") { _, _ ->
                viewModel.saveSession()
                finish()
            }
            .setNeutralButton("Discard Test") { _, _ ->
                com.jeeneet.mocktest.utils.PrefManager.clearSavedTestSession(this)
                finish()
            }
            .setNegativeButton("Continue Test", null)
            .create()
        // This dialog's native ad is never reshown — release it on dismiss so it doesn't
        // linger as this container's "tag" (AdManager.destroyNativeAd only frees on next render).
        exitDialog.setOnDismissListener { AdManager.destroyNativeAd(adContainer) }
        exitDialog.show()
    }

    // ─── Programmatic layout ──────────────────────────────────────────────────

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgPrimary)
        }

        // ── Header row 1: Pause | Timer | SUBMIT ──
        val statusBarHeight = with(resources) {
            val id = getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) getDimensionPixelSize(id) else 0
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp, statusBarHeight + 10.dp, 16.dp, 10.dp)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgPrimary)
        }
        // Pause button
        btnPause = TextView(this).apply {
            text = "⏸ Pause"; textSize = 13f; setTextColor(textTertiary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(bgSecondary); cornerRadius = 20.dpF
            }
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            setOnClickListener { showExitDialog() }
        }
        // Timer with alarm icon
        tvTimer = TextView(this).apply {
            textSize = 15f; setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Submit button
        btnSubmit = com.google.android.material.button.MaterialButton(this).apply {
            text = "SUBMIT"; textSize = 13f; setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
            isAllCaps = true; stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40.dp)
            setOnClickListener { confirmSubmit() }
        }
        header.addView(btnPause); header.addView(tvTimer); header.addView(btnSubmit)
        root.addView(header)

        // ── Header row 2: test name | bookmark | grid ──
        val headerRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 6.dp, 16.dp, 6.dp)
            setBackgroundColor(bgPrimary)
        }
        tvQuestionNumber = TextView(this).apply {
            textSize = 13f; setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow2.addView(tvQuestionNumber)
        // Mark for review
        headerRow2.addView(TextView(this).apply {
            text = "🔖"; textSize = 18f
            setPadding(8.dp, 0, 8.dp, 0)
            setOnClickListener { viewModel.toggleMarkForReview(); renderQuestion(viewModel.currentIndex.value ?: 0) }
        })
        // Bookmark (save for later)
        tvBookmarkStar = TextView(this).apply {
            text = "☆"; textSize = 20f; setTextColor(textTertiary)
            setPadding(8.dp, 0, 8.dp, 0)
            setOnClickListener { toggleBookmark() }
        }
        headerRow2.addView(tvBookmarkStar)
        root.addView(headerRow2)

        // Thin divider
        root.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Question palette (horizontal scroll) ──
        rvPalette = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TestActivity, LinearLayoutManager.HORIZONTAL, false)
            setPadding(10.dp, 20.dp, 10.dp, 20.dp)  // centers 34dp+6dp-margin items in 80dp container
        }
        paletteAdapter = QuestionPaletteAdapter(
            count = 0,
            statusProvider = { idx -> viewModel.questionStatus(idx) },
            onClick = { idx -> viewModel.goToQuestion(idx) }
        )
        rvPalette.adapter = paletteAdapter
        val paletteContainer = FrameLayout(this).apply {
            setBackgroundColor(bgSecondary)
            addView(rvPalette)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80.dp)
        }
        root.addView(paletteContainer)

        // ── Question text (scrollable) ──
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val questionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 18.dp, 18.dp, 10.dp)
        }
        tvQuestionText = TextView(this).apply {
            textSize = 16f
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            androidx.core.widget.TextViewCompat.setLineHeight(this, (24 * resources.displayMetrics.density).toInt())
            setPadding(0, 0, 0, 20.dp)
        }
        radioGroup = RadioGroup(this)
        questionContainer.addView(tvQuestionText)
        questionContainer.addView(radioGroup)
        scrollView.addView(questionContainer)
        root.addView(scrollView)

        // ── Divider ──
        root.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Bottom bar: [Hint] [Mark Review] | [Previous] [Clear] [Next] ──
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 16.dp)
            setBackgroundColor(bgPrimary)
        }
        
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 8.dp }
        }

        btnHint = MaterialButton(this).apply {
            text = "💡 Hint"; textSize = 11f
            setTextColor(textSecondary)
            isAllCaps = false; stateListAnimator = null
            insetTop = 0; insetBottom = 0
            background = GradientDrawable().apply {
                cornerRadius = 8.dpF
                setColor(bgSecondary)
                setStroke(1.dp, dividerColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44.dp)
                .also { it.marginEnd = 8.dp }
            setOnClickListener { showHint() }
        }
        topRow.addView(btnHint)

        btnMarkReview = MaterialButton(this).apply {
            text = "Mark Review"; textSize = 12f
            setTextColor(colorPrimary)
            isAllCaps = false; stateListAnimator = null
            insetTop = 0; insetBottom = 0
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8.dpF
                setColor(android.graphics.Color.argb(20,
                    android.graphics.Color.red(colorPrimary),
                    android.graphics.Color.green(colorPrimary),
                    android.graphics.Color.blue(colorPrimary)))
                setStroke(1.dp, colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44.dp)
        }
        topRow.addView(btnMarkReview)
        bottomBar.addView(topRow)

        // Navigation row: ← | Clear Selection | →
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnPrevious = MaterialButton(this).apply {
            text = "←"; textSize = 26f
            setTextColor(textPrimary)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(bgSecondary)
                setStroke(1.dp, dividerColor)
            }
            layoutParams = LinearLayout.LayoutParams(64.dp, 52.dp).also { it.marginEnd = 8.dp }
        }
        btnClear = MaterialButton(this).apply {
            text = "Clear Selection"; textSize = 12f
            setTextColor(textSecondary)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(bgSecondary)
                setStroke(1.dp, dividerColor)
            }
            layoutParams = LinearLayout.LayoutParams(0, 44.dp, 1f).also { it.marginEnd = 8.dp }
        }
        btnNext = MaterialButton(this).apply {
            text = "→"; textSize = 26f
            setTextColor(Color.WHITE)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(64.dp, 52.dp)
        }
        navRow.addView(btnPrevious); navRow.addView(btnClear); navRow.addView(btnNext)
        bottomBar.addView(navRow)
        root.addView(bottomBar)

        setupButtonListeners()
        return root
    }

    private fun showHint() {
        val questions = viewModel.session.value?.questions ?: return
        val index = viewModel.currentIndex.value ?: 0
        val q: com.jeeneet.mocktest.data.model.Question = questions.getOrNull(index) ?: return
        
        val fullHint = q.explanation.ifEmpty { "No hint available for this question." }
        val shortHint = if (fullHint.length > 120) fullHint.take(120) + "..." else fullHint
        val qId = q.id

        if (PrefManager.isAllAccessUnlocked(this)) {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Question Hint")
                .setMessage(shortHint)
                .setPositiveButton("OK") { _, _ -> }
                .show()
            dialog.findViewById<TextView>(android.R.id.message)?.let {
                com.jeeneet.mocktest.utils.MathRenderer.render(it, shortHint)
            }
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unlock Hint")
                .setMessage("Watch a short video to see a hint for this question.")
                .setPositiveButton("Watch Ad") { _, _ ->
                    AdManager.showRewarded(this@TestActivity, onRewarded = {
                        AnalyticsManager.hintUsed(this@TestActivity, qId)
                        val dialog = MaterialAlertDialogBuilder(this@TestActivity)
                            .setTitle("Question Hint")
                            .setMessage(shortHint)
                            .setPositiveButton("OK") { _, _ -> }
                            .show()
                        dialog.findViewById<TextView>(android.R.id.message)?.let {
                            com.jeeneet.mocktest.utils.MathRenderer.render(it, shortHint)
                        }
                    }, onNotAvailable = {
                        Toast.makeText(this@TestActivity, "Ad not available.", Toast.LENGTH_SHORT).show()
                    }, placement = "hint")
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        }
    }

    // Int.dp / Int.dpF come from UiStyle.
}

// ─── Question Palette Adapter ─────────────────────────────────────────────────

class QuestionPaletteAdapter(
    private var count: Int,
    private val statusProvider: (Int) -> TestViewModel.QuestionStatus,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<QuestionPaletteAdapter.PaletteVH>() {

    private var currentIndex = 0

    private val greenColor  = Color.parseColor("#22C55E")
    private val orangeColor = Color.parseColor("#FB923C")
    private val purpleColor = Color.parseColor("#A855F7")

    private var colorPrimary = 0
    private var bgTertiary   = 0
    private var textMuted    = 0
    private var colorsInitialized = false

    fun updateCount(n: Int) { count = n; notifyDataSetChanged() }
    fun setCurrentIndex(idx: Int) {
        val old = currentIndex; currentIndex = idx
        notifyItemChanged(old); notifyItemChanged(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaletteVH {
        if (!colorsInitialized) {
            val ctx = parent.context
            colorPrimary = ContextCompat.getColor(ctx, R.color.color_primary)
            bgTertiary   = ContextCompat.getColor(ctx, R.color.bg_tertiary)
            textMuted    = ContextCompat.getColor(ctx, R.color.text_muted)
            colorsInitialized = true
        }
        val tv = TextView(parent.context).apply {
            val size = (34 * parent.context.resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(size, size).also {
                it.setMargins(3, 3, 3, 3)
            }
            textSize = 11f; textAlignment = View.TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val drawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        tv.background = drawable
        return PaletteVH(tv, drawable)
    }

    override fun onBindViewHolder(holder: PaletteVH, position: Int) {
        val tv = holder.itemView as TextView
        tv.text = (position + 1).toString()

        val (bgColor, fgColor) = when {
            position == currentIndex -> Pair(colorPrimary, Color.WHITE)
            statusProvider(position) == TestViewModel.QuestionStatus.ANSWERED -> Pair(greenColor, Color.WHITE)
            statusProvider(position) == TestViewModel.QuestionStatus.MARKED_FOR_REVIEW -> Pair(orangeColor, Color.WHITE)
            statusProvider(position) == TestViewModel.QuestionStatus.ANSWERED_MARKED -> Pair(purpleColor, Color.WHITE)
            else -> Pair(bgTertiary, textMuted)
        }

        holder.drawable.setColor(bgColor)
        tv.setTextColor(fgColor)
        tv.setOnClickListener { onClick(position) }
    }

    override fun getItemCount() = count
    class PaletteVH(view: View, val drawable: GradientDrawable) : RecyclerView.ViewHolder(view)
}

private fun FrameLayout(context: Context, init: FrameLayout.() -> Unit) =
    FrameLayout(context).also(init)
