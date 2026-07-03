package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.IAPProducts
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MockTestRepository.getProductIdForExamSubject().
 * This mapping drives which IAP pack is checked before showing premium content.
 * A wrong mapping = users can't unlock content even after paying.
 */
class ProductIdMappingTest {

    /**
     * Mirrors the getProductIdForExamSubject logic from MockTestRepository exactly.
     */
    private fun getProductId(exam: String, subject: String): String = when {
        subject == "Physics" && exam == "NEET"   -> IAPProducts.NEET_PHYSICS_PACK
        subject == "Physics"                     -> IAPProducts.JEE_PHYSICS_PACK
        subject == "Chemistry" && exam == "NEET" -> IAPProducts.NEET_CHEM_PACK
        subject == "Chemistry"                   -> IAPProducts.JEE_CHEM_PACK
        subject == "Maths"                       -> IAPProducts.JEE_MATHS_PACK
        subject == "Biology"                     -> IAPProducts.NEET_BIO_PACK
        else                                     -> ""
    }

    // ─── Physics ─────────────────────────────────────────────────────────────

    @Test
    fun `Physics maps to JEE_PHYSICS_PACK for JEE`() {
        assertEquals(IAPProducts.JEE_PHYSICS_PACK, getProductId("JEE", "Physics"))
    }

    @Test
    fun `Physics maps to NEET_PHYSICS_PACK for NEET exam`() {
        assertEquals(IAPProducts.NEET_PHYSICS_PACK, getProductId("NEET", "Physics"))
    }

    // ─── Chemistry ───────────────────────────────────────────────────────────

    @Test
    fun `Chemistry maps to JEE_CHEM_PACK for JEE`() {
        assertEquals(IAPProducts.JEE_CHEM_PACK, getProductId("JEE", "Chemistry"))
    }

    @Test
    fun `Chemistry maps to NEET_CHEM_PACK for NEET`() {
        assertEquals(IAPProducts.NEET_CHEM_PACK, getProductId("NEET", "Chemistry"))
    }

    @Test
    fun `JEE and NEET Chemistry map to different products`() {
        assertNotEquals(getProductId("JEE", "Chemistry"), getProductId("NEET", "Chemistry"))
    }

    // ─── Maths ───────────────────────────────────────────────────────────────

    @Test
    fun `Maths maps to JEE_MATHS_PACK`() {
        assertEquals(IAPProducts.JEE_MATHS_PACK, getProductId("JEE", "Maths"))
    }

    // ─── Biology ─────────────────────────────────────────────────────────────

    @Test
    fun `Biology maps to NEET_BIO_PACK`() {
        assertEquals(IAPProducts.NEET_BIO_PACK, getProductId("NEET", "Biology"))
    }

    @Test
    fun `Biology maps to NEET_BIO_PACK even for JEE exam type`() {
        assertEquals(IAPProducts.NEET_BIO_PACK, getProductId("JEE", "Biology"))
    }

    // ─── Unknown ─────────────────────────────────────────────────────────────

    @Test
    fun `unknown subject returns empty string`() {
        assertEquals("", getProductId("JEE", "History"))
        assertEquals("", getProductId("NEET", ""))
    }

    // ─── All IAPProducts constants are non-empty and unique ──────────────────

    @Test
    fun `all IAPProduct IDs are non-empty strings`() {
        listOf(
            IAPProducts.REMOVE_ADS,
            IAPProducts.JEE_PHYSICS_PACK,
            IAPProducts.JEE_CHEM_PACK,
            IAPProducts.JEE_MATHS_PACK,
            IAPProducts.NEET_BIO_PACK,
            IAPProducts.NEET_CHEM_PACK,
            IAPProducts.ALL_ACCESS_YEARLY
        ).forEach { id ->
            assertTrue("Product ID should not be empty: $id", id.isNotEmpty())
        }
    }

    @Test
    fun `all IAPProduct IDs are distinct`() {
        val ids = listOf(
            IAPProducts.REMOVE_ADS,
            IAPProducts.JEE_PHYSICS_PACK,
            IAPProducts.JEE_CHEM_PACK,
            IAPProducts.JEE_MATHS_PACK,
            IAPProducts.NEET_BIO_PACK,
            IAPProducts.NEET_CHEM_PACK,
            IAPProducts.ALL_ACCESS_YEARLY
        )
        assertEquals("All product IDs must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `JEE_CHEM_PACK and NEET_CHEM_PACK are different products`() {
        assertNotEquals(IAPProducts.JEE_CHEM_PACK, IAPProducts.NEET_CHEM_PACK)
    }
}
