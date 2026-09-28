package com.jeeneet.mocktest

import com.jeeneet.mocktest.utils.MhchemCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class MhchemCompatTest {

    @Test
    fun `dichromate option from NEET vault screenshot`() {
        val out = MhchemCompat.expand("""$ \ce{K2Cr2O7} $""")
        assertEquals("""$ K_{2}Cr_{2}O_{7} $""", out)
        assertEquals(false, out.contains("\\ce"))
    }

    @Test
    fun `redox arrow reaction from NEET vault screenshot`() {
        val out = MhchemCompat.mhchemInnerToLatex("""ClO3^- -> ClO4^- + Cl^-""")
        assertEquals("""ClO_{3}^- \rightarrow ClO_{4}^- + Cl^-""", out)
    }

    @Test
    fun `copper silver nitrate reaction`() {
        val out = MhchemCompat.mhchemInnerToLatex("""Cu + 2AgNO3 -> Cu(NO3)2 + 2Ag""")
        assertEquals(
            """Cu + 2AgNO_{3} \rightarrow Cu(NO_{3})_{2} + 2Ag""",
            out
        )
    }

    @Test
    fun `hydrogen and oxygen simple formulas`() {
        assertEquals("""H_{2}""", MhchemCompat.mhchemInnerToLatex("H2"))
        assertEquals("""O_{2}""", MhchemCompat.mhchemInnerToLatex("O2"))
    }

    @Test
    fun `plain text without ce is unchanged`() {
        assertEquals("Which is a reducing agent?", MhchemCompat.expand("Which is a reducing agent?"))
    }
}
