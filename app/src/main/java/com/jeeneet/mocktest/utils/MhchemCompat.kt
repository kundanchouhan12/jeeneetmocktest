package com.jeeneet.mocktest.utils

/**
 * JLaTeXMath has no mhchem package, so `\ce{K2Cr2O7}` is drawn as literal text.
 * Expand mhchem into ordinary TeX before rendering.
 */
object MhchemCompat {

    fun expand(text: String): String {
        if (!text.contains("ce{")) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val start = indexOfCe(text, i)
            if (start < 0) {
                sb.append(text, i, text.length)
                break
            }
            sb.append(text, i, start)
            val brace = text.indexOf('{', start)
            if (brace < 0) {
                sb.append(text, start, text.length)
                break
            }
            val close = matchingBrace(text, brace)
            if (close == null) {
                sb.append(text, start, text.length)
                break
            }
            val inner = text.substring(brace + 1, close)
            sb.append(mhchemInnerToLatex(inner))
            i = close + 1
        }
        return sb.toString()
    }

    private fun indexOfCe(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val slash = text.indexOf('\\', i)
            if (slash < 0) return -1
            var n = slash
            while (n < text.length && text[n] == '\\') n++
            if (text.startsWith("ce{", n)) return slash
            i = slash + 1
        }
        return -1
    }

    private fun matchingBrace(text: String, openIdx: Int): Int? {
        var depth = 0
        for (i in openIdx until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    internal fun mhchemInnerToLatex(inner: String): String {
        var s = inner.trim()
        s = s.replace("<->", """\leftrightarrow """)
        s = s.replace("<=>", """\leftrightarrow """)
        s = s.replace("->", """\rightarrow """)
        s = s.replace("<-", """\leftarrow """)
        // H2O / K2Cr2O7 / (NO3)2 → TeX subscripts. Skip digits already after _.
        s = SUBSCRIPT_DIGITS.replace(s) { m ->
            val prev = m.groupValues[1]
            val digits = m.groupValues[2]
            "${prev}_{$digits}"
        }
        return s
    }

    private val SUBSCRIPT_DIGITS = Regex("""([A-Za-z)])(?!_)(\d+)""")
}
