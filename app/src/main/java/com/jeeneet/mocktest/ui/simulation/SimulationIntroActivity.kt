package com.jeeneet.mocktest.ui.simulation

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.launch

class SimulationIntroActivity : AppCompatActivity() {

    private lateinit var startButton: MaterialButton
    private var lastSimulationResult: com.jeeneet.mocktest.data.model.TestResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        lifecycleScope.launch {
            lastSimulationResult = MockTestDatabase.getInstance(this@SimulationIntroActivity)
                .testResultDao()
                .getLastCompletedSimulation(uid)
            
            lastSimulationResult?.let {
                startButton.text = "Start Revision Mode (Free)"
            }
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colorPrimary)
            val statusBarHeight = with(resources) {
                val id = getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) getDimensionPixelSize(id) else 0
            }
            setPadding(Space.M.dp, statusBarHeight + Space.S.dp, Space.M.dp, Space.S.dp)
        }
        toolbar.addView(TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(Color.WHITE)
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
            setOnClickListener { finish() }
        })
        toolbar.addView(uiTextView(UiText.H2, "Exam Simulation", Color.WHITE))
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
        }

        // Header Illustration Placeholder / Icon
        content.addView(TextView(this).apply {
            text = "🎓"; textSize = 64f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, Space.L.dp)
        })

        content.addView(uiTextView(UiText.H1, "Real Exam Environment", textPrimary, Gravity.CENTER))
        content.addView(uiTextView(UiText.BODY, "This mode simulates the actual exam day pressure. Please read the instructions carefully.", textTertiary, Gravity.CENTER).apply {
            setPadding(0, Space.S.dp, 0, Space.XL.dp)
        })

        // Instructions Card
        val instrCard = uiCard(radius = Corner.L, background = bgSecondary, strokeDp = 0).apply {
            layoutParams = lpRow(bottomDp = Space.XL)
        }
        val instrInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }
        val rules = listOf(
            "⏱  Strict 180 Minutes timer (3 hours).",
            "🚫  Pause is DISABLED during the exam.",
            "📉  Standard Marking: +4 for correct, -1 for wrong.",
            "📱  Do not leave the app or your exam will be auto-submitted.",
            "📊  Detailed AIR Prediction available after submission."
        )
        rules.forEach { rule ->
            instrInner.addView(uiTextView(UiText.BODY, rule, textSecondary).apply {
                textSize = 14f
                setPadding(0, Space.S.dp, 0, Space.S.dp)
            })
        }
        instrCard.addView(instrInner)
        content.addView(instrCard)

        scroll.addView(content)
        root.addView(scroll)

        // Bottom Action Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgSecondary)
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
            elevation = 10f
        }

        startButton = MaterialButton(this).apply {
            text = "Start Simulation"
            textSize = 14f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
            isAllCaps = false; stateListAnimator = null
            cornerRadius = 12.dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 56.dp
            )
            setOnClickListener { handleStartClick() }
        }
        bottomBar.addView(startButton)
        root.addView(bottomBar)

        return root
    }

    private fun handleStartClick() {
        val result = lastSimulationResult
        if (result != null) {
            // Launch in revision mode, retaining simulation flags, with no ads!
            com.jeeneet.mocktest.ui.test.TestActivity.startRevision(
                this, 
                result.examType, 
                result.questionsJson, 
                isSimulation = true
            )
            finish()
        } else {
            startSimulation()
        }
    }

    private fun startSimulation() {
        val exam = PrefManager.getSelectedExam(this)
        // We will pass a flag to TestActivity to enable "Simulation Mode"
        com.jeeneet.mocktest.ui.test.TestActivity.startSimulation(this, exam)
        finish()
    }
}
