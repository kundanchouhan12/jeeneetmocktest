package com.jeeneet.mocktest.utils

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable

object MathRenderer {

    // Matches $$...$$ (display math) first, then $...$ (inline math), non-greedy.
    // Order matters: $$...$$ must be tested before $...$ to avoid partial matches.
    private val MATH_PATTERN = Regex("""\$\$([\s\S]+?)\$\$|\$([^$\n]+?)\$""")

    /**
     * Synchronous render — safe to call from the main thread for short text.
     * Used by SolutionActivity where questions are already off-screen during load.
     */
    fun render(textView: TextView, text: String) {
        val normalized = normalize(text)
        if (!normalized.contains('$')) {
            textView.text = normalized
            return
        }
        try {
            textView.text = buildSpannable(normalized, textView.textSize.coerceAtLeast(12f), textView.currentTextColor)
        } catch (e: Exception) {
            android.util.Log.w("MathRenderer", "render failed: ${e.message}")
            textView.text = normalized
        }
    }

    /**
     * Async render — call from a lifecycleScope coroutine.
     *
     * Sets plain text immediately (no blank flicker), then renders all math
     * expressions on a background thread so the main thread stays free for
     * touch/click events (prevents option-selection lag).
     */
    suspend fun renderAsync(textView: TextView, text: String) {
        val normalized = normalize(text)
        textView.text = normalized               // instant plain-text — never a blank view
        if (!normalized.contains('$')) return    // no LaTeX — skip rendering entirely

        val sizePx = textView.textSize.coerceAtLeast(12f)
        // Capture text color on main thread before jumping to Default dispatcher
        val textColor = textView.currentTextColor
        try {
            val spanned = withContext(Dispatchers.Default) {
                buildSpannable(normalized, sizePx, textColor)
            }
            textView.text = spanned
        } catch (e: Throwable) {
            android.util.Log.e("MathRenderer", "renderAsync FAILED for text=[$text]", e)
            // plain text already showing — nothing more to do
        }
    }

    /**
     * Parses [text] for $...$ and $$...$$ math segments and replaces each with
     * a JLatexMathDrawable image span. Safe to call on any thread.
     */
    private fun buildSpannable(
        text: String,
        sizePx: Float,
        textColor: Int = android.graphics.Color.BLACK
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        var lastEnd = 0

        for (match in MATH_PATTERN.findAll(text)) {
            // Append the plain text that precedes this math segment
            sb.append(text, lastEnd, match.range.first)

            // Group 1 = $$...$$ content, Group 2 = $...$ content
            val latex = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()

            try {
                val drawable = JLatexMathDrawable.builder(latex)
                    .textSize(sizePx)
                    .color(textColor)   // ← respects dark/light mode text color
                    .padding(2)
                    .build()
                drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

                val start = sb.length
                sb.append(" ") // non-breaking space as image placeholder
                sb.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                    start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } catch (e: Throwable) {
                // Catches Error too (not just Exception) so a bad LaTeX expression can never
                // crash the app — logged so real failures are diagnosable, not silent.
                android.util.Log.e("MathRenderer", "FAILED latex=[$latex]", e)
                // Graceful fallback: show the raw LaTeX text rather than nothing
                sb.append(match.value)
            }

            lastEnd = match.range.last + 1
        }

        sb.append(text, lastEnd, text.length)
        return sb
    }

    private const val TOKEN_START = ''
    private const val TOKEN_END = ''
    private val TOKEN_PATTERN = Regex("$TOKEN_START(\\d+)$TOKEN_END")

    /**
     * For pipelines that transform text BEFORE it becomes a TextView's content (e.g. Markdown →
     * HTML → Spanned, as in AISolutionActivity) — such pipelines don't understand `$...$` LaTeX
     * and would show it raw or mangle it. Call this first to replace math segments with opaque
     * placeholder tokens (safe to pass through markdown/HTML unescaped), run your pipeline, then
     * call [restoreMathTokens] on the final result to swap tokens for real rendered LaTeX.
     */
    fun tokenizeMath(text: String): Pair<String, List<String>> {
        val normalized = normalize(text)
        val latexList = mutableListOf<String>()
        val tokenized = MATH_PATTERN.replace(normalized) { match ->
            val latex = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            latexList.add(latex)
            "$TOKEN_START${latexList.size - 1}$TOKEN_END"
        }
        return tokenized to latexList
    }

    /** Replaces tokens from [tokenizeMath] in an already-built Spanned/CharSequence with rendered LaTeX. */
    fun restoreMathTokens(
        rendered: CharSequence,
        latexList: List<String>,
        sizePx: Float,
        textColor: Int = android.graphics.Color.BLACK
    ): CharSequence {
        if (latexList.isEmpty()) return rendered
        val sb = SpannableStringBuilder(rendered)
        // Replace back-to-front so earlier match ranges stay valid as the builder mutates.
        for (match in TOKEN_PATTERN.findAll(rendered).toList().asReversed()) {
            val idx = match.groupValues[1].toIntOrNull() ?: continue
            val latex = latexList.getOrNull(idx) ?: continue
            try {
                val drawable = JLatexMathDrawable.builder(latex)
                    .textSize(sizePx)
                    .color(textColor)   // ← respects dark/light mode text color
                    .padding(2)
                    .build()
                drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                sb.replace(match.range.first, match.range.last + 1, " ")
                sb.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                    match.range.first, match.range.first + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } catch (e: Throwable) {
                android.util.Log.e("MathRenderer", "restoreMathTokens FAILED latex=[$latex]", e)
                sb.replace(match.range.first, match.range.last + 1, "$$latex$")
            }
        }
        return sb
    }

    private fun normalize(text: String): String = text
        .replace("\\(", "$").replace("\\)", "$")
        .replace("\\[", "$$").replace("\\]", "$$")
        .replace(Regex("\\[math\\](.*?)\\[/math\\]", RegexOption.DOT_MATCHES_ALL)) { "$$${it.groupValues[1]}$$" }
        .replace(Regex("<math>(.*?)</math>", RegexOption.DOT_MATCHES_ALL)) { "$$${it.groupValues[1]}$$" }
}
