package com.jeeneet.mocktest.data.repository

import com.jeeneet.mocktest.data.model.Question

/**
 * Hard Daily Vault contract shared by parser, Room gates, and tests.
 *
 * For date D and exam E: exactly [EXPECTED_VAULT_COUNT] parseable docs with
 * vaultDate == D, examType == E, isDailyVault == true, same vaultGroupId.
 */
object DailyVaultContract {
    const val EXPECTED_COUNT = QuestionFirestoreParser.EXPECTED_VAULT_COUNT
    val VALID_EXAMS = setOf("JEE", "NEET")

    fun qualifying(
        questions: List<Question>,
        exam: String,
        date: String,
        groupId: String
    ): List<Question> {
        if (groupId.isEmpty() || date.isEmpty() || exam !in VALID_EXAMS) return emptyList()
        return questions.filter {
            it.examType == exam &&
                it.isDailyVault &&
                it.vaultDate == date &&
                it.vaultGroupId == groupId
        }
    }

    fun isCompleteCache(
        questions: List<Question>,
        exam: String,
        date: String,
        groupId: String
    ): Boolean {
        val rows = qualifying(questions, exam, date, groupId)
        if (rows.size != EXPECTED_COUNT) return false
        return rows.map { it.questionText.trim() }.toSet().size == EXPECTED_COUNT
    }

    fun isCompleteIncoming(questions: List<Question>, exam: String, date: String): Boolean {
        if (questions.size != EXPECTED_COUNT) return false
        if (questions.any { it.examType != exam || !it.isDailyVault || it.vaultDate != date }) return false
        val groups = questions.map { it.vaultGroupId }.toSet()
        if (groups.size != 1 || groups.first().isEmpty()) return false
        return questions.map { it.questionText.trim() }.toSet().size == EXPECTED_COUNT
    }

    /**
     * Skip Firestore only for a complete *today* cache.
     * Incomplete counts and yesterday's snapshot never skip.
     */
    fun shouldSkipNetwork(
        snapshotDate: String,
        today: String,
        savedGroupId: String,
        cacheIsCompleteToday: Boolean
    ): Boolean = savedGroupId.isNotEmpty() &&
        snapshotDate == today &&
        cacheIsCompleteToday
}
