package com.jeeneet.mocktest.data.repository

import com.jeeneet.mocktest.data.model.Question

/**
 * Shared Firestore → model coercion for Daily Vault / pack questions.
 * Firestore numbers arrive as Long or Double; older bank docs store option
 * values as numbers and answer indexes as strings.
 */
object QuestionFirestoreParser {

    /** Backend writes this many vault docs per exam per day (`vault_scheduler --count`). */
    const val EXPECTED_VAULT_COUNT = 30

    data class ParseResult(val question: Question?, val dropReason: String?)

    fun parseQuestion(data: Map<String, Any>): ParseResult {
        val examType = data["examType"] as? String
            ?: return ParseResult(null, "examType missing/not string (${typeName(data["examType"])})")
        if (examType !in DailyVaultContract.VALID_EXAMS) {
            return ParseResult(null, "examType '$examType' is not JEE or NEET")
        }
        val subject = data["subject"] as? String
            ?: return ParseResult(null, "subject missing/not string (${typeName(data["subject"])})")
        val chapter = data["chapter"] as? String
            ?: return ParseResult(null, "chapter missing/not string (${typeName(data["chapter"])})")
        val questionText = data["questionText"] as? String
            ?: return ParseResult(null, "questionText missing/not string (${typeName(data["questionText"])})")

        val options = parseOptions(data["options"])
            ?: return ParseResult(null, optionsDropReason(data["options"]))
        val correct = parseCorrectIndex(data)
            ?: return ParseResult(
                null,
                "correctOptionIndex/correctOption not 0–3 int " +
                    "(index=${data["correctOptionIndex"]?.let { "${it}/${typeName(it)}" } ?: "null"}, " +
                    "option=${data["correctOption"]?.let { "${it}/${typeName(it)}" } ?: "null"})"
            )

        val isDailyVault = data["isDailyVault"] as? Boolean ?: false
        val question = Question(
            id = 0,
            examType = examType,
            subject = subject,
            chapter = chapter,
            difficulty = data["difficulty"] as? String ?: "Medium",
            year = parseYear(data["year"]),
            questionText = questionText,
            options = options,
            correctOptionIndex = correct,
            explanation = data["explanation"] as? String ?: "",
            // Vault is free for every user; also keeps pack resync from matching these rows.
            isPremium = if (isDailyVault) false else (data["isPremium"] as? Boolean ?: true),
            isDailyVault = isDailyVault,
            vaultDate = data["vaultDate"] as? String ?: "",
            vaultGroupId = data["vaultGroupId"] as? String ?: ""
        )
        return ParseResult(question, null)
    }

    fun parseOptions(raw: Any?): List<String>? {
        val list = raw as? List<*> ?: return null
        val strings = list.map { item ->
            when (item) {
                null -> ""
                is String -> item
                is Number -> {
                    val d = item.toDouble()
                    if (d % 1.0 == 0.0) item.toLong().toString() else item.toString()
                }
                else -> item.toString()
            }
        }
        if (strings.size != 4) return null
        if (strings.any { it.isBlank() }) return null
        return strings
    }

    fun parseCorrectIndex(data: Map<String, Any>): Int? {
        val raw = data["correctOptionIndex"] ?: data["correctOption"]
        return coerceIndex(raw)
    }

    /**
     * Skip Firestore only when today's exam vault is already the full expected set.
     * A new calendar day always fetches so yesterday's 30 can be replaced.
     * Incomplete counts (18, 27, …) never count as complete.
     */
    fun shouldSkipVaultNetwork(
        snapshotDate: String,
        today: String,
        savedGroupId: String,
        localCount: Int,
        expectedCount: Int = EXPECTED_VAULT_COUNT,
        cacheIsCompleteToday: Boolean? = null
    ): Boolean {
        val complete = cacheIsCompleteToday
            ?: (savedGroupId.isNotEmpty() && snapshotDate == today && localCount == expectedCount)
        return DailyVaultContract.shouldSkipNetwork(snapshotDate, today, savedGroupId, complete)
    }

    private fun coerceIndex(raw: Any?): Int? {
        val value = when (raw) {
            null -> return null
            is Boolean -> return null
            is Int -> raw
            is Long -> raw.toInt()
            is Double -> if (raw % 1.0 == 0.0) raw.toInt() else return null
            is Float -> if (raw % 1f == 0f) raw.toInt() else return null
            is String -> raw.trim().toIntOrNull() ?: return null
            is Number -> {
                val d = raw.toDouble()
                if (d % 1.0 == 0.0) raw.toInt() else return null
            }
            else -> return null
        }
        return value.takeIf { it in 0..3 }
    }

    private fun parseYear(raw: Any?): Int = when (raw) {
        is Long -> raw.toInt()
        is Int -> raw
        is Double -> raw.toInt()
        else -> 0
    }

    private fun typeName(value: Any?): String = value?.javaClass?.simpleName ?: "null"

    private fun optionsDropReason(raw: Any?): String {
        val list = raw as? List<*>
            ?: return "options not a list (${typeName(raw)})"
        val parsed = list.map { item ->
            when (item) {
                null -> ""
                is String -> item
                is Number -> item.toString()
                else -> item.toString()
            }
        }
        return "options size=${parsed.size} blank=${parsed.count { it.isBlank() }} (need exactly 4 non-blank)"
    }
}
