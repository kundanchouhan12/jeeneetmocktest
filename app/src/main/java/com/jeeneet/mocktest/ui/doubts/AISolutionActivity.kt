package com.jeeneet.mocktest.ui.doubts

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.jeeneet.mocktest.data.repository.GeminiRepository
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.AnalyticsManager
import kotlinx.coroutines.launch
import org.json.JSONObject

class AISolutionActivity : AppCompatActivity() {

    private lateinit var scrollView: androidx.core.widget.NestedScrollView
    private lateinit var contentContainer: LinearLayout
    private var typingIndicator: View? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var followUpInputLayout: LinearLayout
    private lateinit var etFollowUp: EditText
    private lateinit var btnSend: TextView
    private lateinit var actionBar: LinearLayout

    private var originalSolution = ""
    private var pageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("AISolution", "Activity Started!")
        try {
            originalSolution = intent.getStringExtra(EXTRA_SOLUTION) ?: ""
            val modelUsed = intent.getStringExtra(EXTRA_MODEL) ?: "Gemini"
            
            setContentView(buildLayout(modelUsed))
            renderSolution(originalSolution)
        } catch (e: Exception) {
            android.util.Log.e("AISolution", "CRASH in onCreate", e)
        }
    }

    private fun buildLayout(modelName: String): View {
        android.util.Log.d("AISolution", "Building Layout...")
        val root = android.widget.RelativeLayout(this).apply {
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = buildHeader(modelName).apply {
            id = View.generateViewId()
        }
        root.addView(header)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = View.generateViewId()
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT, 3.dp
            ).apply {
                addRule(android.widget.RelativeLayout.BELOW, header.id)
            }
            progressDrawable.setColorFilter(goldPrimary, android.graphics.PorterDuff.Mode.SRC_IN)
            visibility = View.VISIBLE
            isIndeterminate = true
        }
        root.addView(progressBar)

        scrollView = androidx.core.widget.NestedScrollView(this).apply {
            id = View.generateViewId()
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT, 
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(android.widget.RelativeLayout.BELOW, progressBar.id)
            }
            isFillViewport = true
        }
        root.addView(scrollView)

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            visibility = View.VISIBLE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(contentContainer)

        followUpInputLayout = buildFollowUpInput().apply {
            id = View.generateViewId()
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }
        root.addView(followUpInputLayout)

        // Adjust ScrollView to be ABOVE the input layout
        (scrollView.layoutParams as android.widget.RelativeLayout.LayoutParams).apply {
            addRule(android.widget.RelativeLayout.ABOVE, followUpInputLayout.id)
        }

        return root
    }

    private fun buildHeader(modelName: String): View = RelativeLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 80.dp
        )
        setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        setBackgroundColor(bgSecondary)

        val backBtn = ImageView(this@AISolutionActivity).apply {
            id = View.generateViewId()
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(textPrimary)
            layoutParams = RelativeLayout.LayoutParams(24.dp, 24.dp).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { finish() }
        }
        addView(backBtn)

        val titleContainer = LinearLayout(this@AISolutionActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.RIGHT_OF, backBtn.id)
                leftMargin = 16.dp
                addRule(RelativeLayout.CENTER_VERTICAL)
            }

            addView(TextView(this@AISolutionActivity).apply {
                text = "AI Solution"
                setTextColor(textPrimary)
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })

            addView(TextView(this@AISolutionActivity).apply {
                text = modelName
                setTextColor(textSecondary)
                textSize = 12f
            })
        }
        addView(titleContainer)

        addView(TextView(this@AISolutionActivity).apply {
            text = "✨ AI"; textSize = 11f; setTextColor(goldPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 16.dpF; setColor(Color.parseColor("#26F59E0B"))
                setStroke(1.dp, Color.parseColor("#40F59E0B"))
            }
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        })
    }

    // WebView removed completely for native reliability

    private fun buildFollowUpInput(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bgSecondary)
        visibility = View.GONE
        layoutParams = lpRow()

        addView(View(this@AISolutionActivity).apply {
            setBackgroundColor(Color.parseColor("#26F59E0B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp
            )
        })

        val row = LinearLayout(this@AISolutionActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, 10.dp, Space.M.dp, 10.dp)
        }

        etFollowUp = EditText(this@AISolutionActivity).apply {
            hint = "Ask a follow-up doubt…"
            setHintTextColor(textTertiary)
            setTextColor(textPrimary)
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = 22.dpF
                setColor(bgTertiary)
                setStroke(1.dp, Color.parseColor("#20FFFFFF"))
            }
            setPadding(Space.L.dp, 10.dp, Space.L.dp, 10.dp)
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginEnd = 10.dp }
            addTextChangedListener { text ->
                btnSend.alpha = if (text.isNullOrBlank()) 0.4f else 1.0f
            }
        }

        btnSend = TextView(this@AISolutionActivity).apply {
            text = "↑"; textSize = 20f; setTextColor(Color.parseColor("#1A2540"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(goldPrimary, goldDark)
                orientation = GradientDrawable.Orientation.TL_BR
                gradientType = GradientDrawable.LINEAR_GRADIENT
            }
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp)
            alpha = 0.4f
            setOnClickListener { sendFollowUp() }
        }

        row.addView(etFollowUp)
        row.addView(btnSend)
        addView(row)
    }

    private fun buildDisclaimer(): View = TextView(this).apply {
        text = "⚠️  AI-generated solution — verify before relying on it."
        textSize = 11f; setTextColor(textTertiary)
        gravity = Gravity.CENTER
        setPadding(Space.L.dp, 6.dp, Space.L.dp, 6.dp)
        setBackgroundColor(bgSecondary)
    }

    private fun buildActionBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bgSecondary)
        setPadding(Space.M.dp, 10.dp, Space.M.dp, 10.dp)
        layoutParams = lpRow()

        addView(actionBtn(icon = "📑", label = "Save") { saveSolution() })
        addView(View(this@AISolutionActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(actionBtn(icon = "↩", label = "Retry") { finish() })
        addView(View(this@AISolutionActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(actionBtn(icon = "❓", label = "Ask Follow-up", accent = true) {
            toggleFollowUpInput()
        })
    }

    private fun actionBtn(icon: String, label: String, accent: Boolean = false, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.L.dp, 6.dp, Space.L.dp, 6.dp)
            isClickable = true; isFocusable = true
            val tv = TypedValue()
            val hasAttr = theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            if (hasAttr && tv.resourceId != 0) {
                background = ContextCompat.getDrawable(this@AISolutionActivity, tv.resourceId)
            }
            setOnClickListener { onClick() }

            addView(TextView(this@AISolutionActivity).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
            })
            addView(TextView(this@AISolutionActivity).apply {
                text = label; textSize = 10f
                setTextColor(if (accent) goldPrimary else textMuted)
                gravity = Gravity.CENTER
                setPadding(0, 2.dp, 0, 0)
                typeface = if (accent) Typeface.create("sans-serif-medium", Typeface.BOLD)
                           else Typeface.DEFAULT
            })
        }

    // ─── WebView rendering ────────────────────────────────────────────────────

    private fun renderSolution(markdown: String) {
        android.util.Log.d("AISolution", "Received markdown length = ${markdown.length}")

        // 🔥 USE POST TO ENSURE UI IS READY
        contentContainer.post {
            contentContainer.removeAllViews()

            val safeMarkdown = if (markdown.isBlank()) {
                """
                ⚠️ No explanation generated.

                Possible reasons:
                • AI response failed
                • Empty OCR text
                • Network interruption
                • AI model timeout

                Please try again.
                """.trimIndent()
            } else {
                markdown
            }

            android.util.Log.d("AISolution", "Safe markdown = $safeMarkdown")

            // Convert markdown → HTML
            val html = mdToHtml(safeMarkdown)
            android.util.Log.d("AISolution", "Generated HTML = $html")

            // 🔥 MAIN SOLUTION VIEW
            val solutionView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also {
                        it.bottomMargin = 16.dp
                    }

                    setTextColor(textPrimary)
                    textSize = 15f
                    setLineSpacing(0f, 1.35f)

                    setPadding(16.dp, 16.dp, 16.dp, 16.dp)

                    background = GradientDrawable().apply {
                        cornerRadius = 18.dpF
                        setColor(bgSecondary)
                        setStroke(1.dp, Color.parseColor("#20FFFFFF"))
                    }

                    val parsed = try {
                        androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } catch (e: Exception) { null }

                    // 🔥 FALLBACK SYSTEM
                    text = if (parsed != null && parsed.toString().trim().isNotEmpty()) {
                        parsed
                    } else {
                        safeMarkdown
                    }
                    
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }

                // 🔥 ADD VIEW
                contentContainer.addView(solutionView)

                // 🔥 SHOW MODEL INFO
                contentContainer.addView(
                    TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also {
                            it.topMargin = 6.dp
                        }
                        text = "✨ AI generated response"
                        textSize = 11f
                        setTextColor(textTertiary)
                    }
                )

                progressBar.visibility = View.GONE
                pageReady = true

                // 🔥 AUTO SCROLL TOP
                scrollView.post {
                    scrollView.scrollTo(0, 0)
                }

                android.util.Log.d("AISolution", "Solution rendered successfully")
            }
    }

    // Converts Gemini markdown to HTML on the Kotlin side — no JS/CDN needed for initial render
    private fun mdToHtml(md: String): String {
        val sb = StringBuilder()
        var inOl = false
        var inUl = false
        var inPre = false

        fun closeList() {
            if (inOl) { sb.append("</ol>"); inOl = false }
            if (inUl) { sb.append("</ul>"); inUl = false }
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun inline(raw: String): String {
            var s = esc(raw)
            s = s.replace(Regex("`([^`]+)`"), "<code>$1</code>")
            s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
            s = s.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
            return s
        }

        for (line in md.lines()) {
            val t = line.trim()
            if (t.startsWith("```")) {
                closeList()
                if (inPre) { sb.append("</code></pre>"); inPre = false }
                else { sb.append("<pre><code>"); inPre = true }
                continue
            }
            if (inPre) { sb.append(esc(line)).append('\n'); continue }
            when {
                t.isEmpty() -> { closeList(); sb.append("<br>") }
                t.startsWith("### ") -> { closeList(); sb.append("<h3>${inline(t.drop(4))}</h3>") }
                t.startsWith("## ") -> { closeList(); sb.append("<h2>${inline(t.drop(3))}</h2>") }
                t.startsWith("# ") -> { closeList(); sb.append("<h1>${inline(t.drop(2))}</h1>") }
                t.startsWith("---") -> { closeList(); sb.append("<hr>") }
                t.startsWith("> ") -> { closeList(); sb.append("<blockquote>${inline(t.drop(2))}</blockquote>") }
                t.startsWith("- ") || t.startsWith("* ") -> {
                    if (inOl) { sb.append("</ol>"); inOl = false }
                    if (!inUl) { sb.append("<ul>"); inUl = true }
                    sb.append("<li>${inline(t.drop(2))}</li>")
                }
                t.matches(Regex("^\\d+\\.\\s.*")) -> {
                    if (inUl) { sb.append("</ul>"); inUl = false }
                    if (!inOl) { sb.append("<ol>"); inOl = true }
                    sb.append("<li>${inline(t.substringAfter(". "))}</li>")
                }
                else -> { closeList(); sb.append("<p>${inline(t)}</p>") }
            }
        }
        closeList()
        if (inPre) sb.append("</code></pre>")
        return sb.toString()
    }

    // buildBaseHtml removed completely for native rendering

    // ─── Follow-up chat ───────────────────────────────────────────────────────

    private fun toggleFollowUpInput() {
        if (followUpInputLayout.visibility == View.GONE) {
            followUpInputLayout.visibility = View.VISIBLE
            etFollowUp.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etFollowUp, InputMethodManager.SHOW_IMPLICIT)
        } else {
            followUpInputLayout.visibility = View.GONE
            hideKeyboard()
        }
    }

    private fun sendFollowUp() {
        if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)) {
            showNoInternetToast()
            return
        }
        val question = etFollowUp.text.toString().trim()
        if (question.isBlank()) return

        etFollowUp.setText("")
        btnSend.alpha = 0.4f
        hideKeyboard()
        followUpInputLayout.visibility = View.GONE
        AnalyticsManager.followUpSent(this)

        showTyping()

        lifecycleScope.launch {
            GeminiRepository(this@AISolutionActivity)
                .askFollowUp(originalSolution, question)
                .onSuccess { answer ->
                    runOnUiThread {
                        removeTyping()
                        appendFollowUpNative(question, answer)
                    }
                }
                .onFailure { error ->
                    runOnUiThread { removeTyping() }
                    Toast.makeText(
                        this@AISolutionActivity,
                        "Follow-up failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun saveSolution() {
        AnalyticsManager.solutionSaved(this)
        lifecycleScope.launch {
            GeminiRepository(this@AISolutionActivity).saveScanHistory(originalSolution)
            Toast.makeText(this@AISolutionActivity, "Solution saved ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etFollowUp.windowToken, 0)
    }

    private fun showTyping() {
        runOnUiThread {
            typingIndicator = TextView(this@AISolutionActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 16.dp; it.bottomMargin = 16.dp }
                setTextColor(textTertiary)
                text = "🧠 Thinking…"
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            }
            contentContainer.addView(typingIndicator)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun removeTyping() {
        typingIndicator?.let { contentContainer.removeView(it) }
        typingIndicator = null
    }

    private fun appendFollowUpNative(q: String, a: String) {
        // add Q
        contentContainer.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 24.dp; it.bottomMargin = 8.dp }
            background = GradientDrawable().apply {
                setColor(bgTertiary)
                cornerRadius = 24.dpF
            }
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            setTextColor(goldPrimary)
            setTypeface(null, Typeface.ITALIC)
            text = "❓ $q"
        })
        
        // add A
        contentContainer.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16.dp }
            background = GradientDrawable().apply {
                setColor(bgSecondary)
                cornerRadius = 24.dpF
                setStroke(1.dp, Color.parseColor("#20FFFFFF"))
            }
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            setTextColor(textPrimary)
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            text = androidx.core.text.HtmlCompat.fromHtml(mdToHtml(a), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        })
        
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val EXTRA_SOLUTION = "extra_solution"
        private const val EXTRA_MODEL = "extra_model"

        fun start(context: Context, solution: String, modelName: String = "Gemini 1.5 Flash") {
            val intent = Intent(context, AISolutionActivity::class.java).apply {
                putExtra(EXTRA_SOLUTION, solution)
                putExtra(EXTRA_MODEL, modelName)
            }
            context.startActivity(intent)
        }
    }
}
