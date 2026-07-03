package com.jeeneet.mocktest

import com.jeeneet.mocktest.utils.MathRenderer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Tests for MathRenderer.normalize() — the pure string function that converts
 * various LaTeX delimiter formats into the $...$ / $$...$$ form that
 * JLatexMathPlugin recognises.
 *
 * normalize() is private, accessed via reflection so the production API is
 * unchanged.
 */
class MathRendererNormalizeTest {

    private val normalizeMethod: Method = MathRenderer::class.java
        .getDeclaredMethod("normalize", String::class.java)
        .also { it.isAccessible = true }

    private fun normalize(input: String) = normalizeMethod.invoke(MathRenderer, input) as String

    // ─── \(...\) → $...$ ────────────────────────────────────────────────────────

    @Test
    fun `backslash-paren inline delimiters converted to single dollar signs`() {
        assertEquals("\$x^2\$", normalize("\\(x^2\\)"))
    }

    @Test
    fun `backslash-paren with fraction expression`() {
        assertEquals("\$\\frac{1}{x}\$", normalize("\\(\\frac{1}{x}\\)"))
    }

    @Test
    fun `multiple backslash-paren pairs in one string all converted`() {
        val result = normalize("If \\(x\\) then \\(y\\)")
        assertEquals("If \$x\$ then \$y\$", result)
    }

    // ─── \[...\] → $$...$$ ──────────────────────────────────────────────────────

    @Test
    fun `backslash-bracket display delimiters converted to double dollar signs`() {
        assertEquals("\$\$x^2\$\$", normalize("\\[x^2\\]"))
    }

    @Test
    fun `inline and display delimiters in same string both convert`() {
        assertEquals("Inline \$a+b\$ and display \$\$c+d\$\$",
            normalize("Inline \\(a+b\\) and display \\[c+d\\]"))
    }

    // ─── [math]...[/math] → $$...$$ ─────────────────────────────────────────────

    @Test
    fun `math-tag delimiters converted to double dollar signs`() {
        assertEquals("\$\$x^2\$\$", normalize("[math]x^2[/math]"))
    }

    @Test
    fun `math-tag with multiline content preserved`() {
        assertEquals("\$\$\\frac{a}{b}\n+ c\$\$", normalize("[math]\\frac{a}{b}\n+ c[/math]"))
    }

    // ─── <math>...</math> → $$...$$ ─────────────────────────────────────────────

    @Test
    fun `html math-tag delimiters converted to double dollar signs`() {
        assertEquals("\$\$x^2\$\$", normalize("<math>x^2</math>"))
    }

    @Test
    fun `html math-tag with multiline content preserved`() {
        assertEquals("\$\$a\nb\$\$", normalize("<math>a\nb</math>"))
    }

    // ─── Already in $...$ form — must be left unchanged ─────────────────────────

    @Test
    fun `existing inline dollar-sign delimiters are unchanged`() {
        val input = "\$x^2\$"
        assertEquals(input, normalize(input))
    }

    @Test
    fun `existing display double-dollar-sign delimiters are unchanged`() {
        val input = "\$\$\\frac{1}{2}\$\$"
        assertEquals(input, normalize(input))
    }

    // ─── Plain text ──────────────────────────────────────────────────────────────

    @Test
    fun `plain text with no math markers is unchanged`() {
        val input = "Consider a covalent molecule where bond order is 2.5"
        assertEquals(input, normalize(input))
    }

    @Test
    fun `empty string returns empty string`() {
        assertEquals("", normalize(""))
    }

    // ─── Mixed formats ───────────────────────────────────────────────────────────

    @Test
    fun `backslash-paren and math-tag in same string both convert`() {
        assertEquals("\$x\$ and \$\$y\$\$", normalize("\\(x\\) and [math]y[/math]"))
    }

    @Test
    fun `all four format variants in one string all convert`() {
        val input = "\\(a\\) \\[b\\] [math]c[/math] <math>d</math>"
        val result = normalize(input)
        assertFalse("\\( should not remain", result.contains("\\("))
        assertFalse("\\[ should not remain", result.contains("\\["))
        assertFalse("[math] should not remain", result.contains("[math]"))
        assertFalse("<math> should not remain", result.contains("<math>"))
    }
}
