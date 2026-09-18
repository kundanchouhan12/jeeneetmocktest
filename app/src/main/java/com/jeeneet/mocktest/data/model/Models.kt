package com.jeeneet.mocktest.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─── Note (stored in Room DB) ────────────────────────────────────────────────

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "") val userId: String = "",
    val title: String,
    val content: String,
    val subject: String = "General",   // "Physics"|"Chemistry"|"Maths"|"Biology"|"General"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ─── Question (stored in Room DB) ───────────────────────────────────────────

@Entity(tableName = "questions")
@TypeConverters(Converters::class)
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val examType: String,        // "JEE" | "NEET"
    val subject: String,         // "Physics" | "Chemistry" | "Biology" | "Maths"
    val chapter: String,         // "Kinematics" | "Organic Chemistry" | "Genetics" etc.
    val difficulty: String,      // "Easy" | "Medium" | "Hard"
    val year: Int,               // PYQ year, 0 = original
    val questionText: String,
    val options: List<String>,   // Always 4 options
    val correctOptionIndex: Int, // 0–3
    val explanation: String,
    val isPremium: Boolean = false,
    val isDailyVault: Boolean = false,
    val vaultDate: String = "", // Format: yyyy-MM-dd
    val vaultGroupId: String = ""
)

// ─── Exam configuration ───────────────────────────────────────────────────────

data class ExamConfig(
    val examType: String,
    val subject: String?,        // null = full paper (all subjects)
    val chapter: String?,        // null = full subject
    val totalQuestions: Int,
    val durationMinutes: Int,
    val correctMarks: Float,
    val negativeMarks: Float,
    val isPremium: Boolean = false,
    val difficultyScoring: Boolean = false,  // Easy=correctMarks-1, Hard=correctMarks+1
    val isDailyQuiz: Boolean = false,
    val isDailyVault: Boolean = false,
    val vaultGroupId: String = "",
    val isSimulation: Boolean = false
) {
    companion object {
        fun jeeMainsFull() = ExamConfig(
            examType = "JEE", subject = null, chapter = null,
            totalQuestions = 90, durationMinutes = 180,
            correctMarks = 4f, negativeMarks = -1f,
            isSimulation = true
        )
        fun jeeAdvancedFull() = ExamConfig(
            examType = "JEE", subject = null, chapter = null,
            totalQuestions = 54, durationMinutes = 180,
            correctMarks = 3f, negativeMarks = -2f
        )
        fun jeeAdvancedSubject(subject: String) = ExamConfig(
            examType = "JEE", subject = subject, chapter = null,
            totalQuestions = 20, durationMinutes = 60,
            correctMarks = 3f, negativeMarks = -2f
        )
        fun neetFull() = ExamConfig(
            examType = "NEET", subject = null, chapter = null,
            totalQuestions = 200, durationMinutes = 200,
            correctMarks = 4f, negativeMarks = -1f,
            isSimulation = true
        )
        fun subjectMock(exam: String, subject: String) = ExamConfig(
            examType = exam, subject = subject, chapter = null,
            totalQuestions = 45, durationMinutes = 60,
            correctMarks = 4f, negativeMarks = -1f
        )
        fun chapterWise(exam: String, subject: String, chapter: String) = ExamConfig(
            examType = exam, subject = subject, chapter = chapter,
            totalQuestions = 30, durationMinutes = 40,
            correctMarks = 4f, negativeMarks = -1f,
            difficultyScoring = true
        )
        fun dailyQuiz(exam: String) = ExamConfig(
            examType = exam, subject = null, chapter = null,
            totalQuestions = 10, durationMinutes = 5,
            correctMarks = 4f, negativeMarks = -1f,
            isDailyQuiz = true
        )
    }
}

// ─── Active test session ──────────────────────────────────────────────────────

data class TestSession(
    val config: ExamConfig,
    val questions: List<Question>,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    val answers: MutableMap<Int, Int?> = mutableMapOf() // questionIndex -> selectedOption (null=unattempted)
    val markedForReview: MutableSet<Int> = mutableSetOf()
    val questionTimeMs: MutableMap<Int, Long> = mutableMapOf() // questionIndex -> ms spent

    fun answerQuestion(index: Int, optionIndex: Int) { answers[index] = optionIndex }
    fun toggleReview(index: Int) {
        if (index in markedForReview) markedForReview.remove(index) else markedForReview.add(index)
    }
    fun isAnswered(index: Int) = answers.containsKey(index) && answers[index] != null
    fun isMarkedForReview(index: Int) = index in markedForReview

    fun elapsedSeconds(): Long = (System.currentTimeMillis() - startTimeMs) / 1000
    fun avgTimePerQuestionMs(): Long =
        if (questionTimeMs.isEmpty()) 0L else questionTimeMs.values.average().toLong()
}

// ─── Test result ──────────────────────────────────────────────────────────────

@Entity(tableName = "test_results")
data class TestResult(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String = "",           // Firebase UID — isolates results per account
    val examType: String,
    val subject: String,
    val totalQuestions: Int,
    val attempted: Int,
    val correct: Int,
    val wrong: Int,
    val score: Float,
    val maxScore: Float,
    val percentile: Float,
    val timeTakenSeconds: Long,
    val completedAt: Long = System.currentTimeMillis(),
    val subjectBreakdown: String = "", // JSON
    val questionsJson: String = "",    // Full questions list (JSON)
    val answersJson: String = "",      // User answers map (JSON)
    @androidx.room.ColumnInfo(defaultValue = "")
    val questionTimesJson: String = "", // Map<Int, Long> ms per question (JSON)
    @androidx.room.ColumnInfo(defaultValue = "0")
    val isSimulation: Boolean = false
)

// ─── Subject performance (for weak topic detection) ───────────────────────────

data class SubjectPerformance(
    val subject: String,
    val chapter: String,
    val correct: Int,
    val wrong: Int,
    val unattempted: Int
) {
    val accuracy: Float get() = if (correct + wrong == 0) 0f else correct.toFloat() / (correct + wrong)
    val totalAttempted: Int get() = correct + wrong
}

// ─── Saved (paused) test session ─────────────────────────────────────────────
// Serialised to JSON and stored in SharedPreferences so users can resume later.

data class SavedTestSession(
    val configJson: String,
    val questionsJson: String,
    val answersJson: String,          // Map<Int, Int?>
    val markedForReviewJson: String,  // Set<Int>
    val currentIndex: Int,
    val timeLeftSeconds: Long,
    val questionTimesJson: String = "",  // Map<Int, Long> ms per question
    val savedAt: Long = System.currentTimeMillis()
)

// ─── Room Type Converters ─────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Gson().toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)
}

// ─── IAP Product IDs ─────────────────────────────────────────────────────────

object IAPProducts {
    const val REMOVE_ADS          = "remove_ads"
    const val JEE_PHYSICS_PACK    = "jee_physics_pack"
    const val JEE_CHEM_PACK       = "jee_chemistry_pack"
    const val JEE_MATHS_PACK      = "jee_maths_pack"
    const val NEET_BIO_PACK       = "neet_biology_pack"
    const val NEET_CHEM_PACK      = "neet_chemistry_pack"
    const val NEET_PHYSICS_PACK   = "neetphysicspack"
    const val ALL_ACCESS_YEARLY   = "allaccessyearly"

    val ALL_PACKS = listOf(
        JEE_PHYSICS_PACK, JEE_CHEM_PACK, JEE_MATHS_PACK,
        NEET_BIO_PACK, NEET_CHEM_PACK, NEET_PHYSICS_PACK
    )
}

// ─── AdMob Ad Unit IDs ───────────────────────────────────────────────────────
// Replace TEST IDs with real ones before publishing

object AdUnitIds {
    // Use these during development
    const val BANNER_TEST       = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL_TEST = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED_TEST     = "ca-app-pub-3940256099942544/5224354917"
    const val NATIVE_TEST       = "ca-app-pub-3940256099942544/2247696110"
    const val APP_OPEN_TEST     = "ca-app-pub-3940256099942544/9257395921"

    // Real AdMob ad unit IDs
    const val BANNER_PROD       = "ca-app-pub-3519378438987564/5102309165"
    const val INTERSTITIAL_PROD = "ca-app-pub-3519378438987564/1745314821"
    const val REWARDED_PROD     = "ca-app-pub-3519378438987564/3789227495"
    const val NATIVE_PROD       = "ca-app-pub-3519378438987564/4020124372"
    const val APP_OPEN_PROD     = "ca-app-pub-3519378438987564/1896621971"

    // Automatically true for debug builds, false for release builds
    val IS_DEBUG get() = com.jeeneet.mocktest.BuildConfig.DEBUG

    val BANNER       get() = if (IS_DEBUG) BANNER_TEST else BANNER_PROD
    val INTERSTITIAL get() = if (IS_DEBUG) INTERSTITIAL_TEST else INTERSTITIAL_PROD
    val REWARDED     get() = if (IS_DEBUG) REWARDED_TEST else REWARDED_PROD
    val NATIVE       get() = if (IS_DEBUG) NATIVE_TEST else NATIVE_PROD
    val APP_OPEN     get() = if (IS_DEBUG) APP_OPEN_TEST else APP_OPEN_PROD
}

// ─── Power 100 Entities ───────────────────────────────────────────────────────

@Entity(tableName = "power100_questions", primaryKeys = ["examType", "position"])
@TypeConverters(Converters::class)
data class Power100Question(
    val examType: String,
    val position: Int,          // 1–100, fixed order
    val subject: String,
    val chapter: String,
    val difficulty: String,     // "Easy" | "Medium" | "Hard"
    val questionText: String,
    val options: List<String>,  // always 4 options
    val correctOptionIndex: Int,
    val explanation: String
)

@Entity(tableName = "power100_progress", primaryKeys = ["userId", "examType", "position"])
data class Power100Progress(
    val userId: String,
    val examType: String,
    val position: Int,
    val selectedOption: Int = -1,       // -1 = unattempted
    val isBookmarked: Boolean = false,
    val timeTakenMs: Long = 0L,
    val answeredAt: Long = System.currentTimeMillis()
)

// ─── Vault Discussion Models ────────────────────────────────────────────────

data class VaultComment(
    val id: String = "",
    val userId: String = "",
    val username: String = "Aspirant",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val likes: Int = 0,
    val isReported: Boolean = false
)

data class VaultMetadata(
    val vaultDate: String = "",
    val globalAttempts: Int = 0,
    val avgScore: Float = 0f,
    val toughestQuestionId: Int = -1
)

// ─── Wrong Question Aggregation (Revise My Mistakes) ─────────────────────────

data class WrongQuestionResult(
    val questions: List<Question>,           // deduplicated wrong questions
    val bySubject: Map<String, Int>,          // "Physics" → 5
    val byChapter: Map<String, Int>,          // "Kinematics" → 3
    val fromTestCount: Int                    // "from 8 tests"
)

