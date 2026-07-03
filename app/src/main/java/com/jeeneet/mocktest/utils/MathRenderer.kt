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
            textView.text = buildSpannable(normalized, textView.textSize.coerceAtLeast(12f))
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
        try {
            val spanned = withContext(Dispatchers.Default) {
                buildSpannable(normalized, sizePx)
            }
            textView.text = spanned
        } catch (e: Exception) {
            android.util.Log.w("MathRenderer", "renderAsync failed: ${e.message}")
            // plain text already showing — nothing more to do
        }
    }

    /**
     * Parses [text] for $...$ and $$...$$ math segments and replaces each with
     * a JLatexMathDrawable image span. Safe to call on any thread.
     */
    private fun buildSpannable(text: String, sizePx: Float): SpannableStringBuilder {
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
                    .padding(2)
                    .build()
                drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

                val start = sb.length
                sb.append(" ") // non-breaking space as image placeholder
                sb.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                    start, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } catch (e: Exception) {
                // Graceful fallback: show the raw LaTeX text rather than nothing
                sb.append(match.value)
            }

            lastEnd = match.range.last + 1
        }

        sb.append(text, lastEnd, text.length)
        return sb
    }

    private fun normalize(text: String): String = text
        .replace("\\(", "$").replace("\\)", "$")
        .replace("\\[", "$$").replace("\\]", "$$")
        .replace(Regex("\\[math\\](.*?)\\[/math\\]", RegexOption.DOT_MATCHES_ALL)) { "$$${it.groupValues[1]}$$" }
        .replace(Regex("<math>(.*?)</math>", RegexOption.DOT_MATCHES_ALL)) { "$$${it.groupValues[1]}$$" }
}
