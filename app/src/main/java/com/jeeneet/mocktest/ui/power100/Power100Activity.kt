package com.jeeneet.mocktest.ui.power100

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jeeneet.mocktest.data.model.Power100Progress
import com.jeeneet.mocktest.data.model.Power100Question
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.data.repository.Power100SyncManager
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.style.Space
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Power100Activity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EXAM = "extra_exam"
        private const val EXTRA_JUMP_TO_POSITION = "extra_jump_to_position"

        fun start(context: Context, exam: String) {
            context.startActivity(Intent(context, Power100Activity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
            })
        }

        fun startAtPosition(context: Context, exam: String, position: Int) {
            context.startActivity(Intent(context, Power100Activity::class.java).apply {
                putExtra(EXTRA_EXAM, exam)
                putExtra(EXTRA_JUMP_TO_POSITION, position)
            })
        }
    }

    private enum class State { GRID, QUESTION }

    private lateinit var exam: String
    private lateinit var uid: String
    private var state = State.GRID

    private var questions: List<Power100Question> = emptyList()
    private var progressMap: MutableMap<Int, Power100Progress> = mutableMapOf() // key = position 1-100
    private var currentIndex = 0   // 0-based index into questions
    private var currentSubjectFilter: String? = null

    // Layouts
    private lateinit var rootFrame: FrameLayout
    private lateinit var gridLayout: ScrollView
    private lateinit var questionLayout: LinearLayout

    // Grid UI
    private lateinit var tvAttempted: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvCoins: TextView
    private lateinit var gridRecycler: RecyclerView
    private lateinit var btnContinue: MaterialButton
    private lateinit var subjectTabsRow: LinearLayout

    // Question UI
    private lateinit var tvGridBack: TextView
    private lateinit var tvQNumber: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvDifficulty: TextView
    private lateinit var tvQuestionText: TextView
    private lateinit var optionsGroup: RadioGroup
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnClearQ: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var tvBookmark: TextView
    private lateinit var tvSavedIndicator: TextView

    // Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var questionStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - questionStartMs) / 1000
            tvTimer.text = "%d:%02d".format(elapsed / 60, elapsed % 60)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exam = intent.getStringExtra(EXTRA_EXAM) ?: "JEE"
        uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        setContentView(rootFrame)

        gridLayout = buildGridLayout()
        questionLayout = buildQuestionLayout()
        rootFrame.addView(gridLayout)
        rootFrame.addView(questionLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state == State.QUESTION) showGrid() else confirmExit()
            }
        })

        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (::uid.isInitialized && questions.isNotEmpty()) {
            lifecycleScope.launch {
                val progressList = withContext(Dispatchers.IO) {
                    MockTestDatabase.getInstance(this@Power100Activity).power100Dao().getProgress(uid, exam)
                }
                progressMap = progressList.associateBy { it.position }.toMutableMap()
                withContext(Dispatchers.Main) { refreshGridUi() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
    }

    // ─── Exit confirmation ────────────────────────────────────────────────────

    private fun confirmExit() {
        val attempted = progressMap.values.count { it.selectedOption >= 0 }
        MaterialAlertDialogBuilder(this)
            .setTitle("Progress Auto-Saved ✓")
            .setMessage(
                if (attempted > 0)
                    "You've answered $attempted/100 questions. Your progress is saved — continue anytime from where you left off."
                else
                    "Your progress is saved automatically as you answer each question."
            )
            .setPositiveButton("Exit") { _, _ -> finish() }
            .setNegativeButton("Keep Practicing", null)
            .show()
    }

    // ─── Loading overlay ──────────────────────────────────────────────────────

    private var loadingOverlay: FrameLayout? = null

    private fun showLoadingOverlay(message: String) {
        if (loadingOverlay?.parent != null) return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(220,
                android.graphics.Color.red(bgPrimary),
                android.graphics.Color.green(bgPrimary),
                android.graphics.Color.blue(bgPrimary)))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        inner.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply { bottomMargin = Space.M.dp }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
        })
        inner.addView(uiTextView(UiText.BODY, message, textSecondary, Gravity.CENTER))
        overlay.addView(inner)
        rootFrame.addView(overlay)
        loadingOverlay = overlay
    }

    private fun hideLoadingOverlay() {
        loadingOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        loadingOverlay = null
    }

    // ─── Data Loading ─────────────────────────────────────────────────────────

    private var lastSyncError: String? = null

    private fun loadData() {
        lifecycleScope.launch {
            val db = MockTestDatabase.getInstance(this@Power100Activity)
            val syncMgr = Power100SyncManager(this@Power100Activity)

            // Decide overlay before any network call
            val localCount = withContext(Dispatchers.IO) { db.power100Dao().getCount(exam) }
            val needsOverlay = localCount == 0
            if (needsOverlay) {
                withContext(Dispatchers.Main) { showLoadingOverlay("Loading Power 100 questions…") }
            }

            // Sync: force-download when empty, TTL-check otherwise
            val error: String? = withContext(Dispatchers.IO) {
                if (needsOverlay) syncMgr.forceSync(exam)
                else { syncMgr.checkAndSyncIfNeeded(exam); null }
            }
            lastSyncError = error

            // Always re-read from Room so the UI reflects any sync updates
            questions = withContext(Dispatchers.IO) { db.power100Dao().getQuestionsForExam(exam) }

            if (needsOverlay) {
                withContext(Dispatchers.Main) { hideLoadingOverlay() }
            }

            val progressList = withContext(Dispatchers.IO) { db.power100Dao().getProgress(uid, exam) }
            progressMap = progressList.associateBy { it.position }.toMutableMap()
            withContext(Dispatchers.Main) {
                refreshGridUi()
                val jumpToPosition = intent.getIntExtra(EXTRA_JUMP_TO_POSITION, -1)
                if (jumpToPosition > 0) {
                    val idx = questions.indexOfFirst { it.position == jumpToPosition }
                    if (idx >= 0) showQuestionView(idx)
                }
            }
        }
    }

    private fun saveProgress(position: Int, selectedOption: Int, timeTakenMs: Long) {
        val existing = progressMap[position]
        val progress = Power100Progress(
            userId = uid,
            examType = exam,
            position = position,
            selectedOption = selectedOption,
            isBookmarked = existing?.isBookmarked ?: false,
            timeTakenMs = timeTakenMs,
            answeredAt = System.currentTimeMillis()
        )
        progressMap[position] = progress
        lifecycleScope.launch(Dispatchers.IO) {
            MockTestDatabase.getInstance(this@Power100Activity).power100Dao().upsertProgress(progress)
        }
        // Award coins for correct answer (first time correct only)
        val q = questions.getOrNull(position - 1) ?: return
        if (selectedOption == q.correctOptionIndex && existing?.selectedOption != q.correctOptionIndex) {
            PrefManager.addCoins(this, 4)
        }
    }

    private fun saveBookmark(position: Int, isBookmarked: Boolean) {
        val existing = progressMap[position] ?: Power100Progress(
            userId = uid, examType = exam, position = position
        )
        val updated = existing.copy(isBookmarked = isBookmarked, answeredAt = System.currentTimeMillis())
        progressMap[position] = updated
        lifecycleScope.launch(Dispatchers.IO) {
            MockTestDatabase.getInstance(this@Power100Activity).power100Dao().upsertProgress(updated)
        }
    }

    // ─── Grid Layout ──────────────────────────────────────────────────────────

    private fun buildGridLayout(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, 0, Space.L.dp, Space.XL.dp)
        }
        scroll.addView(root)

        // Header
        root.addView(buildGridHeader())

        // Stats bar
        root.addView(buildStatsBar())

        // Subject tabs
        subjectTabsRow = buildSubjectTabs()
        root.addView(subjectTabsRow)

        // Question grid
        gridRecycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@Power100Activity, 10)
            adapter = GridAdapter()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Space.M.dp }
            isNestedScrollingEnabled = false
        }
        root.addView(gridRecycler)

        // Legend
        root.addView(buildLegend())

        // Continue button
        btnContinue = MaterialButton(this).apply {
            text = "Continue"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(-1, 52.dp).apply { topMargin = Space.L.dp }
            setOnClickListener { jumpToFirstUnattempted() }
        }
        root.addView(btnContinue)

        // Results button
        val btnResults = MaterialButton(this).apply {
            text = "View Results"
            textSize = 14f
            setTextColor(colorPrimary)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(Color.TRANSPARENT)
                setStroke(2.dp, colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(-1, 52.dp).apply { topMargin = Space.S.dp }
            setOnClickListener { openResults() }
        }
        root.addView(btnResults)

        // Reset All Progress button
        val btnReset = TextView(this).apply {
            text = "Reset All Progress"
            textSize = 13f
            setTextColor(wrongRed)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Space.S.dp
                bottomMargin = Space.S.dp
            }
            setPadding(0, Space.S.dp, 0, Space.S.dp)
            setOnClickListener { confirmResetProgress() }
        }
        root.addView(btnReset)

        return scroll
    }

    private fun buildGridHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Space.L.dp, 0, Space.M.dp)
        }
        row.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(textSecondary)
            setPadding(0, 0, Space.M.dp, 0)
            setOnClickListener { confirmExit() }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleCol.addView(uiTextView(UiText.H2, "⚡ Power 100", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(uiTextView(UiText.CAPTION, "Master the 100 most important $exam questions", textTertiary).apply {
            textSize = 11f; setPadding(0, 2.dp, 0, 0)
        })
        row.addView(titleCol)

        // Exam badge
        row.addView(TextView(this).apply {
            text = exam; textSize = 11f
            setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)), Corner.PILL)
            setPadding(Space.M.dp, 4.dp, Space.M.dp, 4.dp)
        })
        return row
    }

    private fun buildStatsBar(): View {
        val attempted = progressMap.values.count { it.selectedOption >= 0 }
        val correct = progressMap.values.count { p ->
            p.selectedOption >= 0 && questions.getOrNull(p.position - 1)?.correctOptionIndex == p.selectedOption
        }
        val accuracy = if (attempted > 0) (correct * 100f / attempted).toInt() else 0
        val coinsEarned = correct * 4

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedFill(bgSecondary, Corner.M)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.M.dp }
        }

        fun stat(label: String, value: String, color: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(uiTextView(UiText.H2, value, color).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 18f; gravity = Gravity.CENTER
            })
            addView(uiTextView(UiText.CAPTION, label, textTertiary).apply { textSize = 10f; gravity = Gravity.CENTER; setPadding(0, 2.dp, 0, 0) })
        }

        tvAttempted = uiTextView(UiText.H2, "$attempted/100", colorPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 18f; gravity = Gravity.CENTER
        }
        tvAccuracy = uiTextView(UiText.H2, "$accuracy%", Color.parseColor("#22C55E")).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 18f; gravity = Gravity.CENTER
        }
        tvCoins = uiTextView(UiText.H2, "+$coinsEarned 🪙", goldPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = 18f; gravity = Gravity.CENTER
        }

        fun statWrap(tv: TextView, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(tv)
            addView(uiTextView(UiText.CAPTION, label, textTertiary).apply { textSize = 10f; gravity = Gravity.CENTER; setPadding(0, 2.dp, 0, 0) })
        }

        row.addView(statWrap(tvAttempted, "Attempted"))
        row.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1.dp, 36.dp) })
        row.addView(statWrap(tvAccuracy, "Accuracy"))
        row.addView(View(this).apply { setBackgroundColor(dividerColor); layoutParams = LinearLayout.LayoutParams(1.dp, 36.dp) })
        row.addView(statWrap(tvCoins, "Coins Earned"))
        return row
    }

    private fun buildSubjectTabs(): LinearLayout {
        val subjects = if (exam == "JEE") listOf("All", "Physics", "Chemistry", "Maths")
                       else listOf("All", "Physics", "Chemistry", "Biology")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Space.S.dp }
        }
        subjects.forEach { sub ->
            val isAll = sub == "All"
            val active = if (isAll) currentSubjectFilter == null else currentSubjectFilter == sub
            row.addView(TextView(this).apply {
                text = sub; textSize = 12f
                setTextColor(if (active) colorPrimary else textMuted)
                typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                background = if (active) roundedFill(Color.argb(20, Color.red(colorPrimary), Color.green(colorPrimary), Color.blue(colorPrimary)), Corner.PILL) else null
                setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Space.XS.dp }
                setOnClickListener {
                    currentSubjectFilter = if (isAll) null else sub
                    val newTabs = buildSubjectTabs()
                    val parent = this@Power100Activity.subjectTabsRow.parent as? LinearLayout ?: return@setOnClickListener
                    val idx = parent.indexOfChild(this@Power100Activity.subjectTabsRow)
                    parent.removeView(this@Power100Activity.subjectTabsRow)
                    subjectTabsRow = newTabs
                    parent.addView(newTabs, idx)
                    gridRecycler.adapter?.notifyDataSetChanged()
                }
            })
        }
        return row
    }

    private fun buildLegend(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Space.M.dp }
        }
        fun dot(color: Int) = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).apply { marginEnd = 4.dp }
        }
        fun label(text: String) = uiTextView(UiText.CAPTION, text, textTertiary).apply {
            textSize = 10f; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Space.M.dp }
        }
        row.addView(dot(Color.parseColor("#E2E8F0"))); row.addView(label("Not Attempted"))
        row.addView(dot(goldPrimary)); row.addView(label("Bookmarked"))
        row.addView(dot(correctGreen)); row.addView(label("Correct"))
        row.addView(dot(wrongRed)); row.addView(label("Wrong"))
        return row
    }

    private fun refreshGridUi() {
        if (!::gridRecycler.isInitialized) return

        // ── Stats ─────────────────────────────────────────────────────────────
        val attempted = progressMap.values.count { it.selectedOption >= 0 }
        val correct   = progressMap.values.count { p ->
            p.selectedOption >= 0 && questions.getOrNull(p.position - 1)?.correctOptionIndex == p.selectedOption
        }
        val accuracy = if (attempted > 0) (correct * 100f / attempted).toInt() else 0
        if (::tvAttempted.isInitialized) {
            tvAttempted.text = "$attempted/100"
            tvAccuracy.text  = "$accuracy%"
            tvCoins.text     = "+${correct * 4} 🪙"
        }

        // ── Continue button ───────────────────────────────────────────────────
        if (::btnContinue.isInitialized) {
            when {
                questions.isEmpty() -> {
                    val hint = lastSyncError?.take(80) ?: "No questions found. Check Firestore rules & upload script."
                    btnContinue.text      = "Tap to retry sync"
                    btnContinue.isEnabled = true
                    btnContinue.setOnClickListener { lastSyncError = null; loadData() }
                    // Show the actual error so it's diagnosable
                    Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
                }
                else -> {
                    val firstUnattempted = questions.indexOfFirst { q ->
                        val sel = progressMap[q.position]?.selectedOption
                        sel == null || sel == -1
                    }
                    btnContinue.text    = if (firstUnattempted >= 0) "Continue → Q.${firstUnattempted + 1}"
                                         else "All Done! View Results →"
                    btnContinue.isEnabled = true
                    btnContinue.setOnClickListener {
                        if (firstUnattempted >= 0) showQuestionView(firstUnattempted) else openResults()
                    }
                }
            }
        }

        gridRecycler.adapter?.notifyDataSetChanged()
    }

    private fun showGrid() {
        timerHandler.removeCallbacks(timerRunnable)
        state = State.GRID
        gridLayout.visibility = View.VISIBLE
        questionLayout.visibility = View.GONE
        refreshGridUi()
    }

    private fun showQuestionView(index: Int) {
        if (questions.isEmpty() || index < 0 || index >= questions.size) return
        state = State.QUESTION
        currentIndex = index
        gridLayout.visibility = View.GONE
        questionLayout.visibility = View.VISIBLE
        bindQuestion()
    }

    private fun jumpToFirstUnattempted() {
        val idx = questions.indexOfFirst { q -> progressMap[q.position]?.selectedOption == null || progressMap[q.position]?.selectedOption == -1 }
        if (idx >= 0) showQuestionView(idx) else openResults()
    }

    private fun openResults() {
        val actualAttempts = progressMap.values.count { it.selectedOption >= 0 }
        if (actualAttempts == 0) {
            Toast.makeText(this, "Answer some questions first!", Toast.LENGTH_SHORT).show()
            return
        }
        com.jeeneet.mocktest.admob.AdManager.showInterstitial(
            activity = this,
            onDismissed = { Power100ResultActivity.start(this, exam) }
        )
    }

    private fun confirmResetProgress() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset All Progress?")
            .setMessage("This will clear all your answers and progress for Power 100 ($exam). This cannot be undone.")
            .setPositiveButton("Reset") { _, _ -> resetAllProgress() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAllProgress() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MockTestDatabase.getInstance(this@Power100Activity).power100Dao().resetProgress(uid, exam)
            }
            progressMap.clear()
            withContext(Dispatchers.Main) {
                refreshGridUi()
                Toast.makeText(this@Power100Activity, "Progress reset successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Question Layout ──────────────────────────────────────────────────────

    private fun buildQuestionLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility = View.GONE
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            elevation = Elev.S.dpF
        }
        tvGridBack = TextView(this).apply {
            text = "← Grid"; textSize = 13f; setTextColor(colorPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, Space.M.dp, 0)
            setOnClickListener { showGrid() }
        }
        tvQNumber = uiTextView(UiText.BODY, "Q.1/100", textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f); gravity = Gravity.CENTER
        }
        tvTimer = uiTextView(UiText.CAPTION, "0:00", textTertiary).apply { textSize = 12f }
        tvBookmark = TextView(this).apply {
            text = "☆"; textSize = 20f; setTextColor(goldPrimary)
            setPadding(Space.M.dp, 0, 0, 0)
            setOnClickListener { toggleBookmark() }
        }
        topBar.addView(tvGridBack); topBar.addView(tvQNumber); topBar.addView(tvTimer); topBar.addView(tvBookmark)
        root.addView(topBar)

        // Scrollable question area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        scroll.addView(content)

        // Difficulty badge
        tvDifficulty = TextView(this).apply {
            text = "Medium"; textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Space.M.dp }
            setPadding(Space.S.dp, 4.dp, Space.S.dp, 4.dp)
        }
        content.addView(tvDifficulty)

        // Question text
        tvQuestionText = uiTextView(UiText.BODY, "", textPrimary).apply {
            textSize = 15f; setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.L.dp }
        }
        content.addView(tvQuestionText)

        // Options
        optionsGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        content.addView(optionsGroup)
        root.addView(scroll)

        // Bottom nav — two rows
        val bottomWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.L.dp, Space.S.dp, Space.L.dp, Space.M.dp)
            elevation = Elev.S.dpF
        }

        // Row 1: Previous | Clear | Next
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Space.S.dp }
        }
        btnPrev = MaterialButton(this).apply {
            text = "◀ Prev"; textSize = 12f
            setTextColor(textSecondary)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(bgTertiary)
            }
            layoutParams = LinearLayout.LayoutParams(-2, 44.dp)
            setOnClickListener { navigateQuestion(-1) }
        }
        btnClearQ = MaterialButton(this).apply {
            text = "Clear"; textSize = 12f
            setTextColor(wrongRed)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(Color.argb(20, 239, 68, 68))
            }
            layoutParams = LinearLayout.LayoutParams(-2, 44.dp).apply { marginStart = Space.S.dp }
            setOnClickListener { clearCurrentAnswer() }
        }
        btnNext = MaterialButton(this).apply {
            text = "Next ▶"; textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false; stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = Corner.M.dpF
                setColor(colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginStart = Space.S.dp }
            setOnClickListener { navigateQuestion(+1) }
        }
        navRow.addView(btnPrev); navRow.addView(btnClearQ); navRow.addView(btnNext)

        // Row 2: Saved indicator (left) + Save & Exit (right)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        tvSavedIndicator = TextView(this).apply {
            text = ""; textSize = 11f
            setTextColor(correctGreen)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnSaveExit = TextView(this).apply {
            text = "💾 Save & Exit"; textSize = 12f
            setTextColor(textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(Space.M.dp, Space.S.dp, 0, Space.S.dp)
            setOnClickListener { confirmExit() }
        }
        actionRow.addView(tvSavedIndicator)
        actionRow.addView(btnSaveExit)

        bottomWrapper.addView(navRow)
        bottomWrapper.addView(actionRow)
        root.addView(bottomWrapper)
        return root
    }

    private fun bindQuestion() {
        val q = questions.getOrNull(currentIndex) ?: return
        val pos = q.position
        val prog = progressMap[pos]

        tvQNumber.text = "Q.$pos/100"
        tvDifficulty.text = q.difficulty
        tvDifficulty.background = roundedFill(
            when (q.difficulty) {
                "Easy" -> Color.parseColor("#22C55E")
                "Hard" -> Color.parseColor("#EF4444")
                else   -> Color.parseColor("#F59E0B")
            }, Corner.PILL
        )
        lifecycleScope.launch {
            com.jeeneet.mocktest.utils.MathRenderer.renderAsync(tvQuestionText, q.questionText)
        }
        tvBookmark.text = if (prog?.isBookmarked == true) "★" else "☆"

        optionsGroup.removeAllViews()
        q.options.forEachIndexed { idx, optText ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                textSize = 14f
                setTextColor(textPrimary)
                buttonTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = Space.S.dp
                    setPadding(Space.S.dp, Space.M.dp, Space.S.dp, Space.M.dp)
                }
                background = roundedFill(bgSecondary, Corner.M)
                isChecked = prog?.selectedOption == idx
            }
            lifecycleScope.launch {
                com.jeeneet.mocktest.utils.MathRenderer.renderAsync(rb, "${('A' + idx)}. $optText")
            }
            optionsGroup.addView(rb)
        }

        // Restore checked state
        if (prog != null && prog.selectedOption >= 0) {
            optionsGroup.getChildAt(prog.selectedOption)?.let {
                (it as? RadioButton)?.isChecked = true
            }
        }

        optionsGroup.setOnCheckedChangeListener { group, checkedId ->
            val selectedIdx = (0 until group.childCount).firstOrNull { group.getChildAt(it).id == checkedId } ?: return@setOnCheckedChangeListener
            val elapsed = System.currentTimeMillis() - questionStartMs
            saveProgress(pos, selectedIdx, elapsed)
            refreshOptionColors(q, selectedIdx)
            showSavedIndicator()
        }

        // Start per-question timer
        timerHandler.removeCallbacks(timerRunnable)
        questionStartMs = System.currentTimeMillis()
        timerHandler.post(timerRunnable)

        btnPrev.isEnabled = currentIndex > 0
        btnNext.text = if (currentIndex == questions.size - 1) "Done" else "Next"
    }

    private fun refreshOptionColors(q: Power100Question, selectedIdx: Int) {
        for (i in 0 until optionsGroup.childCount) {
            val rb = optionsGroup.getChildAt(i) as? RadioButton ?: continue
            rb.background = when {
                i == q.correctOptionIndex -> roundedFill(Color.argb(40, 34, 197, 94), Corner.M) // green tint
                i == selectedIdx && selectedIdx != q.correctOptionIndex -> roundedFill(Color.argb(40, 239, 68, 68), Corner.M) // red tint
                else -> roundedFill(bgSecondary, Corner.M)
            }
        }
    }

    private fun navigateQuestion(delta: Int) {
        val newIndex = currentIndex + delta
        when {
            newIndex < 0 -> showGrid()
            newIndex >= questions.size -> showGrid()
            else -> showQuestionView(newIndex)
        }
    }

    private fun showSavedIndicator() {
        if (!::tvSavedIndicator.isInitialized) return
        tvSavedIndicator.text = "✓ Answer saved"
        timerHandler.postDelayed({ tvSavedIndicator.text = "" }, 1500)
    }

    private fun clearCurrentAnswer() {
        val q = questions.getOrNull(currentIndex) ?: return
        val pos = q.position
        val existing = progressMap[pos] ?: return
        val cleared = existing.copy(selectedOption = -1, answeredAt = System.currentTimeMillis())
        progressMap[pos] = cleared
        lifecycleScope.launch(Dispatchers.IO) {
            MockTestDatabase.getInstance(this@Power100Activity).power100Dao().upsertProgress(cleared)
        }
        optionsGroup.clearCheck()
        for (i in 0 until optionsGroup.childCount) {
            optionsGroup.getChildAt(i)?.background = roundedFill(bgSecondary, Corner.M)
        }
    }

    private fun toggleBookmark() {
        val q = questions.getOrNull(currentIndex) ?: return
        val pos = q.position
        val isNowBookmarked = progressMap[pos]?.isBookmarked != true
        saveBookmark(pos, isNowBookmarked)
        tvBookmark.text = if (isNowBookmarked) "★" else "☆"
        gridRecycler.adapter?.notifyItemChanged(currentIndex)
    }

    // ─── Grid RecyclerView Adapter ────────────────────────────────────────────

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun getItemCount() = questions.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val size = 30.dp
            val tv = TextView(this@Power100Activity).apply {
                layoutParams = RecyclerView.LayoutParams(size, size).apply { setMargins(2.dp, 2.dp, 2.dp, 2.dp) }
                gravity = Gravity.CENTER; textSize = 9f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val q = questions[position]
            val prog = progressMap[q.position]
            val isFiltered = currentSubjectFilter != null && q.subject != currentSubjectFilter
            val isAttempted = prog != null && prog.selectedOption >= 0
            val isCorrect = isAttempted && prog!!.selectedOption == q.correctOptionIndex
            val isBookmarked = prog?.isBookmarked == true

            val baseColor = when {
                isCorrect -> correctGreen
                isAttempted -> wrongRed
                isBookmarked -> goldPrimary
                else -> Color.parseColor("#CBD5E1")
            }
            val cellColor = if (isFiltered) Color.argb(60, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)) else baseColor
            val textColor = if (isFiltered) Color.argb(80, 100, 116, 139) else if (isAttempted || isBookmarked) Color.WHITE else Color.parseColor("#475569")

            holder.tv.text = (position + 1).toString()
            holder.tv.setTextColor(textColor)
            holder.tv.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cellColor) }
            holder.tv.setOnClickListener { showQuestionView(position) }
        }
    }
}
