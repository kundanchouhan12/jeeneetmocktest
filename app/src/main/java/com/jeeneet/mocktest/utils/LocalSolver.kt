package com.jeeneet.mocktest.utils

import java.util.*
import kotlin.math.*

/**
 * A lightweight rule-based engine to solve simple math and physics problems locally.
 * This reduces API dependency and provides instant responses for standard formulas.
 */
object LocalSolver {

    data class LocalResult(
        val subject: String,
        val formula: String,
        val solution: String
    )

    fun solve(text: String): LocalResult? {
        val input = text.lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ").trim()

        // 1. Math: Linear Equations (e.g., 2x + 5 = 11)
        val linearMatch = Regex("""([-+]?\d*)x\s*([-+]\s*\d+)\s*=\s*([-+]?\d+)""").find(input)
        if (linearMatch != null) {
            return solveLinear(linearMatch)
        }

        // 2. Physics: v = u + at
        if (input.contains("v = u + at") || 
            (input.contains("initial velocity") && input.contains("acceleration")) ||
            (input.contains("u=") && input.contains("a=") && input.contains("t="))) {
            return solveMotion1(input)
        }

        // 3. Physics: F = ma
        if (input.contains("f = ma") || 
            (input.contains("force") && input.contains("mass") && input.contains("acceleration")) ||
            (input.contains("m=") && input.contains("a=") && input.contains("force"))) {
            return solveForce(input)
        }

        // 4. Physics: E = mc^2
        if (input.contains("e = mc^2") || input.contains("mass energy equivalence") || 
            (input.contains("energy") && input.contains("m="))) {
            return solveEnergy(input)
        }

        return null
    }

    private fun solveLinear(match: MatchResult): LocalResult {
        val (aStr, bStr, cStr) = match.destructured
        val a = if (aStr.isEmpty() || aStr == "+") 1.0 else if (aStr == "-") -1.0 else aStr.toDouble()
        val b = bStr.replace(" ", "").toDouble()
        val c = cStr.toDouble()

        val x = (c - b) / a
        val xFormatted = if (x % 1.0 == 0.0) x.toInt().toString() else "%.2f".format(x)

        val solution = """
            [SUMMARY: Linear Equation Solving]
            ### Final Answer
            **x = $xFormatted**

            ### Steps
            1.  Given equation: `${a.toInt()}x + ${b.toInt()} = ${c.toInt()}`
            2.  Subtract ${b.toInt()} from both sides: `${a.toInt()}x = ${c.toInt()} - ${b.toInt()}`
            3.  `${a.toInt()}x = ${(c - b).toInt()}`
            4.  Divide by ${a.toInt()}: `x = ${(c - b).toInt()} / ${a.toInt()}`
            5.  **x = $xFormatted**

            ### Concept
            This is a first-degree linear equation in one variable. The goal is to isolate 'x' using inverse operations.

            ### Mistake to avoid
            Ensure you perform the same operation on both sides of the equation. Watch out for sign changes when moving terms across the '=' sign.
        """.trimIndent()

        return LocalResult("Maths", "Linear Equation", solution)
    }

    private fun solveMotion1(input: String): LocalResult {
        // Simple extraction logic for "find v if u=5, a=2, t=3"
        val u = extractValue(input, listOf("u", "initial velocity")) ?: 0.0
        val a = extractValue(input, listOf("a", "acceleration")) ?: 0.0
        val t = extractValue(input, listOf("t", "time")) ?: 0.0

        val v = u + (a * t)
        val vFormatted = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)

        val solution = """
            [SUMMARY: Equations of Motion]
            ### Final Answer
            **v = $vFormatted m/s**

            ### Steps
            1.  Identify given values:
                *   Initial velocity (u) = $u m/s
                *   Acceleration (a) = $a m/s²
                *   Time (t) = $t s
            2.  Use the first equation of motion: `v = u + at`
            3.  Substitute values: `v = $u + ($a × $t)`
            4.  `v = $u + ${(a * t)}`
            5.  **v = $vFormatted m/s**

            ### Concept
            First equation of motion for constant acceleration. It relates velocity, acceleration, and time.

            ### Mistake to avoid
            Check if units are consistent (e.g., all in SI units like m, s, kg).
        """.trimIndent()

        return LocalResult("Physics", "Kinematics", solution)
    }

    private fun solveForce(input: String): LocalResult {
        val m = extractValue(input, listOf("m", "mass")) ?: 1.0
        val a = extractValue(input, listOf("a", "acceleration")) ?: 0.0

        val f = m * a
        val fFormatted = if (f % 1.0 == 0.0) f.toInt().toString() else "%.2f".format(f)

        val solution = """
            [SUMMARY: Newton's Second Law]
            ### Final Answer
            **Force (F) = $fFormatted N**

            ### Steps
            1.  Given:
                *   Mass (m) = $m kg
                *   Acceleration (a) = $a m/s²
            2.  Formula: `F = m × a`
            3.  Substitute: `F = $m × $a`
            4.  **F = $fFormatted Newton**

            ### Concept
            Newton's Second Law states that force equals mass times acceleration.

            ### Mistake to avoid
            Ensure mass is in kg and acceleration is in m/s² to get the result in Newtons (N).
        """.trimIndent()

        return LocalResult("Physics", "Newton's Laws", solution)
    }

    private fun solveEnergy(input: String): LocalResult {
        val m = extractValue(input, listOf("m", "mass")) ?: 1.0
        val c = 3.0e8

        val e = m * c * c
        val eFormatted = "%.2e".format(e)

        val solution = """
            [SUMMARY: Mass-Energy Equivalence]
            ### Final Answer
            **E = $eFormatted Joules**

            ### Steps
            1.  Given: Mass (m) = $m kg
            2.  Speed of light (c) ≈ 3 × 10⁸ m/s
            3.  Formula: `E = mc²`
            4.  Substitute: `E = $m × (3 × 10⁸)²`
            5.  `E = $m × 9 × 10¹⁶`
            6.  **E = $eFormatted J**

            ### Concept
            Einstein's mass-energy equivalence formula shows that mass can be converted into energy and vice-versa.

            ### Mistake to avoid
            'c' must be squared. The energy released is extremely high even for small masses.
        """.trimIndent()

        return LocalResult("Physics", "Modern Physics", solution)
    }

    private fun extractValue(input: String, keywords: List<String>): Double? {
        for (key in keywords) {
            // Match pattern like "u = 5" or "initial velocity is 10" or "a=2" or "mass 10kg"
            // Using a more flexible regex that allows optional '=' or 'is' and handles units
            val regex = Regex("""$key\s*(?:=|\bis\b)?\s*(\d+(?:\.\d+)?)""")
            val match = regex.find(input)
            if (match != null) {
                return match.groupValues[1].toDoubleOrNull()
            }
        }
        return null
    }
}
