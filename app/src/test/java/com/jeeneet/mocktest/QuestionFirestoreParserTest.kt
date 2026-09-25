package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.repository.QuestionFirestoreParser
import org.junit.Assert.*
import org.junit.Test

class QuestionFirestoreParserTest {

    private fun baseMap(overrides: Map<String, Any> = emptyMap()): Map<String, Any> {
        val data = mutableMapOf<String, Any>(
            "examType" to "JEE",
            "subject" to "Physics",
            "chapter" to "Kinematics",
            "questionText" to "A particle starts from rest. Acceleration is?",
            "options" to listOf("2 m/s²", "4 m/s²", "6 m/s²", "8 m/s²"),
            "correctOptionIndex" to 1L,
            "correctOption" to 1L,
            "isDailyVault" to true,
            "vaultDate" to "2026-09-22",
            "vaultGroupId" to "jee_vault_2026-09-22"
        )
        data.putAll(overrides)
        return data
    }

    @Test
    fun `numeric options coerce to four strings`() {
        val result = QuestionFirestoreParser.parseQuestion(
            baseMap(mapOf("options" to listOf(1, 2, 3.5, 4L)))
        )
        assertNotNull(result.question)
        assertEquals(listOf("1", "2", "3.5", "4"), result.question!!.options)
    }

    @Test
    fun `double and string answer indexes parse`() {
        assertEquals(2, QuestionFirestoreParser.parseCorrectIndex(mapOf("correctOptionIndex" to 2.0)))
        assertEquals(3, QuestionFirestoreParser.parseCorrectIndex(mapOf("correctOption" to "3")))
        assertNull(QuestionFirestoreParser.parseCorrectIndex(mapOf("correctOptionIndex" to "x")))
    }

    @Test
    fun `vault copies are never marked premium`() {
        val result = QuestionFirestoreParser.parseQuestion(
            baseMap(mapOf("isPremium" to true, "isDailyVault" to true))
        )
        assertFalse(result.question!!.isPremium)
        assertTrue(result.question!!.isDailyVault)
    }

    @Test
    fun `drop reason is set when chapter missing`() {
        val data = baseMap().toMutableMap()
        data.remove("chapter")
        val result = QuestionFirestoreParser.parseQuestion(data)
        assertNull(result.question)
        assertTrue(result.dropReason!!.contains("chapter"))
    }

    @Test
    fun `incomplete local vault must not skip network`() {
        assertFalse(
            QuestionFirestoreParser.shouldSkipVaultNetwork(
                snapshotDate = "2026-09-25",
                today = "2026-09-25",
                savedGroupId = "jee_vault_2026-09-22",
                localCount = 18
            )
        )
        assertFalse(
            QuestionFirestoreParser.shouldSkipVaultNetwork(
                snapshotDate = "2026-09-25",
                today = "2026-09-25",
                savedGroupId = "jee_vault_2026-09-25",
                localCount = 27
            )
        )
    }

    @Test
    fun `complete vault for today skips network`() {
        assertTrue(
            QuestionFirestoreParser.shouldSkipVaultNetwork(
                snapshotDate = "2026-09-25",
                today = "2026-09-25",
                savedGroupId = "jee_vault_2026-09-25",
                localCount = 30
            )
        )
    }

    @Test
    fun `yesterday complete vault does not skip — new day must replace`() {
        assertFalse(
            QuestionFirestoreParser.shouldSkipVaultNetwork(
                snapshotDate = "2026-09-24",
                today = "2026-09-25",
                savedGroupId = "jee_vault_2026-09-24",
                localCount = 30
            )
        )
    }
}
