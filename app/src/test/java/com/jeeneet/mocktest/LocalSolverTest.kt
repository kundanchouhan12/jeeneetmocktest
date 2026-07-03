package com.jeeneet.mocktest

import com.jeeneet.mocktest.utils.LocalSolver
import org.junit.Assert.*
import org.junit.Test

class LocalSolverTest {

    @Test
    fun testLinearEquation() {
        val question = "Solve 2x + 5 = 11"
        val result = LocalSolver.solve(question)
        
        assertNotNull(result)
        assertEquals("Maths", result?.subject)
        assertTrue(result?.solution?.contains("x = 3") == true)
    }

    @Test
    fun testNegativeLinearEquation() {
        val question = "x - 10 = -5"
        val result = LocalSolver.solve(question)
        
        assertNotNull(result)
        assertTrue(result?.solution?.contains("x = 5") == true)
    }

    @Test
    fun testMotionFormula() {
        val question = "Find final velocity v if u=10, a=2, t=5"
        val result = LocalSolver.solve(question)
        
        assertNotNull(result)
        assertEquals("Physics", result?.subject)
        assertTrue(result?.solution?.contains("v = 20 m/s") == true)
    }

    @Test
    fun testForceFormula() {
        val question = "A body of mass 10kg has acceleration 3 m/s2. Find Force."
        val result = LocalSolver.solve(question)
        
        assertNotNull(result)
        assertTrue(result?.solution?.contains("Force (F) = 30 N") == true)
    }

    @Test
    fun testEnergyFormula() {
        val question = "Calculate energy for mass m=1kg"
        val result = LocalSolver.solve(question)
        
        assertNotNull(result)
        assertTrue(result?.solution?.contains("E = 9.00e+16 Joules") == true)
    }

    @Test
    fun testComplexQuestionFallsThrough() {
        val question = "What is the mechanism of SN1 reaction?"
        val result = LocalSolver.solve(question)
        
        assertNull(result)
    }
}
