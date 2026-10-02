package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStreamReader

data class CurriculumTopic(
    @SerializedName("topic") val topic: String,
    @SerializedName("allowed_modes") val allowedModes: List<String> = listOf("TEXT"),
    @SerializedName("preferred_mode") val preferredMode: String = "TEXT",
    @SerializedName("diagram_source") val diagramSource: String? = null,
    @SerializedName("ai_image_generation") val aiImageGeneration: Boolean = false,
    @SerializedName("ai_generation_allowed") val aiGenerationAllowed: Boolean = true,
    @SerializedName("difficulty_range") val difficultyRange: List<String> = listOf("Easy", "Medium", "Hard")
)

data class CurriculumUnit(
    @SerializedName("unit_number") val unitNumber: Int,
    @SerializedName("unit_name") val unitName: String,
    @SerializedName("ncert_chapters") val ncertChapters: List<String> = emptyList(),
    @SerializedName("topics") val topics: List<CurriculumTopic> = emptyList()
)

data class CurriculumSubject(
    @SerializedName("official_units_count") val officialUnitsCount: Int = 0,
    @SerializedName("units") val units: List<CurriculumUnit> = emptyList()
)

data class CurriculumRoot(
    @SerializedName("curriculum") val curriculum: Map<String, Map<String, CurriculumSubject>> = emptyMap()
)

/**
 * CurriculumRepository
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * Client-side interface to curriculum.json (Single Source of Truth for JEE & NEET).
 *
 * Provides instant in-memory access to:
 * - 550+ official NCERT sub-topics
 * - Allowed question modes (TEXT, NUMERICAL, DIAGRAM, STRUCTURE)
 * - Preferred modes and diagram specifications
 *
 * Loads lazily from assets/curriculum.json and caches statically in memory.
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
object CurriculumRepository {

    private const val TAG = "CurriculumRepository"
    private const val ASSET_FILE = "curriculum.json"

    @Volatile
    private var cachedData: Map<String, Map<String, CurriculumSubject>>? = null

    /**
     * Initializes and returns the in-memory curriculum cache.
     * Thread-safe and parses only once (~15ms cold start, 0ms subsequent).
     */
    fun getCurriculum(context: Context): Map<String, Map<String, CurriculumSubject>> {
        cachedData?.let { return it }
        synchronized(this) {
            cachedData?.let { return it }
            return try {
                context.assets.open(ASSET_FILE).use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val root = Gson().fromJson(reader, CurriculumRoot::class.java)
                        val map = root.curriculum
                        cachedData = map
                        Log.d(TAG, "Loaded curriculum with exams: ${map.keys.joinToString()}")
                        map
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load curriculum.json from assets: ${e.message}", e)
                emptyMap()
            }
        }
    }

    /**
     * Returns all units for the given exam and subject.
     */
    fun getUnits(context: Context, exam: String, subject: String): List<CurriculumUnit> {
        val curr = getCurriculum(context)
        val examKey = normalizeExamKey(exam)
        val subjectKey = normalizeSubjectKey(subject)
        return curr[examKey]?.get(subjectKey)?.units.orEmpty()
    }

    /**
     * Finds a specific unit by exact name or one of its NCERT chapter aliases.
     */
    fun findUnit(context: Context, exam: String, subject: String, unitOrChapter: String): CurriculumUnit? {
        val units = getUnits(context, exam, subject)
        val norm = normalizeString(unitOrChapter)
        // 1. Exact or normalized unit_name match
        units.firstOrNull { normalizeString(it.unitName) == norm }?.let { return it }
        // 2. NCERT chapter alias match
        units.firstOrNull { unit -> unit.ncertChapters.any { normalizeString(it) == norm } }?.let { return it }
        // 3. Substring match
        return units.firstOrNull { unit ->
            val uNorm = normalizeString(unit.unitName)
            norm.contains(uNorm) || uNorm.contains(norm)
        }
    }

    /**
     * Returns all granular topics for the given unit.
     */
    fun getTopics(context: Context, exam: String, subject: String, unitOrChapter: String): List<CurriculumTopic> {
        return findUnit(context, exam, subject, unitOrChapter)?.topics.orEmpty()
    }

    /**
     * Returns allowed modes (TEXT, NUMERICAL, DIAGRAM, STRUCTURE) for a topic or entire unit.
     */
    fun getAllowedModes(context: Context, exam: String, subject: String, unitOrChapter: String, topicName: String? = null): List<String> {
        val unit = findUnit(context, exam, subject, unitOrChapter) ?: return listOf("TEXT")
        if (topicName != null) {
            val tNorm = normalizeString(topicName)
            val topic = unit.topics.firstOrNull { normalizeString(it.topic) == tNorm }
            if (topic != null) return topic.allowedModes
        }
        return unit.topics.flatMap { it.allowedModes }.distinct().ifEmpty { listOf("TEXT") }
    }

    /**
     * Returns preferred question mode for a topic or entire unit.
     */
    fun getPreferredMode(context: Context, exam: String, subject: String, unitOrChapter: String, topicName: String? = null): String {
        val unit = findUnit(context, exam, subject, unitOrChapter) ?: return "TEXT"
        if (topicName != null) {
            val tNorm = normalizeString(topicName)
            val topic = unit.topics.firstOrNull { normalizeString(it.topic) == tNorm }
            if (topic != null) return topic.preferredMode
        }
        return unit.topics.firstOrNull()?.preferredMode ?: "TEXT"
    }

    /**
     * Checks if a topic is officially in the syllabus for this unit.
     */
    fun isTopicAllowed(context: Context, exam: String, subject: String, unitOrChapter: String, topicName: String): Boolean {
        val topics = getTopics(context, exam, subject, unitOrChapter)
        if (topics.isEmpty()) return false
        val tNorm = normalizeString(topicName)
        if (tNorm.isBlank()) return false
        return topics.any {
            val candNorm = normalizeString(it.topic)
            candNorm == tNorm || candNorm.contains(tNorm) || tNorm.contains(candNorm)
        }
    }

    private fun normalizeExamKey(exam: String): String = when {
        exam.contains("NEET", ignoreCase = true) -> "NEET"
        else -> "JEE"
    }

    private fun normalizeSubjectKey(subject: String): String = when {
        subject.contains("Physic", ignoreCase = true) -> "Physics"
        subject.contains("Chem", ignoreCase = true) -> "Chemistry"
        subject.contains("Math", ignoreCase = true) -> "Maths"
        subject.contains("Bio", ignoreCase = true) -> "Biology"
        else -> subject
    }

    private fun normalizeString(s: String): String {
        return s.lowercase()
            .replace("&", "and")
            .replace("-", " ")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
    }
}
