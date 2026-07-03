package com.jeeneet.mocktest.data.repository

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jeeneet.mocktest.data.model.Converters
import com.jeeneet.mocktest.data.model.Note
import com.jeeneet.mocktest.data.model.Power100Progress
import com.jeeneet.mocktest.data.model.Power100Question
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import kotlinx.coroutines.flow.Flow

// ─── Bookmarked Questions Entity ──────────────────────────────────────────────

@Entity(tableName = "bookmarks", primaryKeys = ["questionId", "userId"])
data class BookmarkedQuestion(
    val questionId: Int,
    val userId: String,
    val bookmarkedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun bookmark(b: BookmarkedQuestion)

    @Query("DELETE FROM bookmarks WHERE questionId = :qId AND userId = :uid")
    suspend fun unbookmark(qId: Int, uid: String)

    @Query("""
        SELECT q.* FROM questions q
        INNER JOIN bookmarks b ON q.id = b.questionId
        WHERE b.userId = :uid
        ORDER BY b.bookmarkedAt DESC
    """)
    fun getBookmarkedQuestions(uid: String): Flow<List<Question>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE questionId = :qId AND userId = :uid)")
    suspend fun isBookmarked(qId: Int, uid: String): Boolean

    @Query("SELECT COUNT(*) FROM bookmarks WHERE userId = :uid")
    fun getBookmarkCount(uid: String): Flow<Int>
}

// ─── Scan History Entity ──────────────────────────────────────────────────────

@Entity(tableName = "scan_history")
data class ScanHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String = "",             // ID of the user who scanned this
    val imageHash: String = "",          // MD5 of content — used for cache lookup
    val questionSnippet: String = "",    // Short summary of the question
    val subject: String = "General",     // Physics/Chemistry/etc
    val solutionMarkdown: String,
    val scannedAt: Long = System.currentTimeMillis()
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface QuestionDao {

    @Query("SELECT * FROM questions WHERE examType = :exam ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomQuestions(exam: String, limit: Int): List<Question>

    @Query("""
        SELECT * FROM questions 
        WHERE examType = :exam AND subject = :subject 
        ORDER BY RANDOM() LIMIT :limit
    """)
    suspend fun getQuestionsBySubject(exam: String, subject: String, limit: Int): List<Question>

    @Query("""
        SELECT * FROM questions 
        WHERE examType = :exam AND subject = :subject AND chapter = :chapter 
        ORDER BY RANDOM() LIMIT :limit
    """)
    suspend fun getQuestionsByChapter(exam: String, subject: String, chapter: String, limit: Int): List<Question>

    @Query("""
        SELECT * FROM questions 
        WHERE examType = :exam AND subject = :subject AND chapter = :chapter
    """)
    suspend fun getQuestionsByChapterOnce(exam: String, subject: String, chapter: String): List<Question>

    @Query("""
        SELECT * FROM questions
        WHERE examType = :exam AND isPremium = 0
        ORDER BY RANDOM() LIMIT :limit
    """)
    suspend fun getFreeQuestions(exam: String, limit: Int): List<Question>

    @Query("""
        SELECT * FROM questions
        WHERE examType = :exam AND subject = :subject AND isPremium = 0
        ORDER BY RANDOM() LIMIT :limit
    """)
    suspend fun getFreeQuestionsBySubject(exam: String, subject: String, limit: Int): List<Question>

    @Query("SELECT DISTINCT chapter FROM questions WHERE examType = :exam AND subject = :subject")
    fun getChapters(exam: String, subject: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM questions WHERE examType = :exam")
    fun getQuestionCount(exam: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM questions WHERE examType = :exam")
    suspend fun getExamQuestionCount(exam: String): Int

    @Query("SELECT COUNT(*) FROM questions WHERE examType = :exam AND isPremium = 0")
    suspend fun getFreeExamQuestionCount(exam: String): Int

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun getTotalCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuestions(questions: List<Question>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: Question)

    /** Wipe cached premium questions for a pack before re-syncing fresh ones. */
    @Query("DELETE FROM questions WHERE examType = :exam AND subject = :subject AND isPremium = 1")
    suspend fun deletePremiumQuestionsBySubject(exam: String, subject: String)

    /** Upsert — replaces existing row only when firestoreId matches (used for updates). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuestions(questions: List<Question>)

    @Query("""
        SELECT * FROM questions
        WHERE examType = :exam AND subject = :subject AND chapter IN (:chapters)
        ORDER BY RANDOM() LIMIT :limit
    """)
    suspend fun getQuestionsByChapters(exam: String, subject: String, chapters: List<String>, limit: Int): List<Question>

    @Query("SELECT * FROM questions WHERE id IN (:ids) ORDER BY RANDOM()")
    suspend fun getQuestionsByIds(ids: List<Int>): List<Question>

    @Query("SELECT * FROM questions WHERE questionText LIKE '%' || :query || '%' OR explanation LIKE '%' || :query || '%' LIMIT 1")
    suspend fun findSimilar(query: String): Question?

    @Query("SELECT * FROM questions WHERE isDailyVault = 1 AND vaultDate = :date AND examType = :exam ORDER BY id ASC")
    suspend fun getDailyVaultQuestions(exam: String, date: String): List<Question>

    @Query("DELETE FROM questions WHERE isDailyVault = 1 AND vaultDate = :date AND examType = :exam")
    suspend fun deleteDailyVaultQuestions(exam: String, date: String)

    @Query("DELETE FROM questions WHERE isDailyVault = 1 AND vaultDate < :beforeDate")
    suspend fun deleteOldVaultQuestions(beforeDate: String)

    @Query("SELECT * FROM questions WHERE vaultGroupId = :groupId ORDER BY id ASC")
    suspend fun getVaultQuestionsByGroupId(groupId: String): List<Question>
}

@Dao
interface TestResultDao {
    @Insert
    suspend fun insertResult(result: TestResult): Long

    @Query("SELECT * FROM test_results WHERE userId = :uid ORDER BY completedAt DESC")
    fun getAllResults(uid: String): Flow<List<TestResult>>

    @Query("SELECT * FROM test_results WHERE id = :id")
    suspend fun getResultById(id: Long): TestResult?

    @Query("SELECT * FROM test_results WHERE userId = :uid AND examType = :exam ORDER BY completedAt DESC LIMIT 20")
    fun getResultsByExam(uid: String, exam: String): Flow<List<TestResult>>

    @Query("SELECT AVG(score / maxScore * 100) FROM test_results WHERE userId = :uid AND examType = :exam")
    fun getAverageScorePercent(uid: String, exam: String): Flow<Float?>

    @Query("SELECT COUNT(*) FROM test_results WHERE userId = :uid")
    fun getTotalTestsTaken(uid: String): Flow<Int>

    @Query("SELECT * FROM test_results WHERE userId = :uid ORDER BY completedAt DESC")
    suspend fun getAllResultsOnce(uid: String): List<TestResult>

    @Query("SELECT * FROM test_results ORDER BY completedAt DESC")
    suspend fun getAllResultsAll(): List<TestResult>

    @Query("DELETE FROM test_results WHERE userId = :uid")
    suspend fun deleteAll(uid: String)

    @Query("SELECT EXISTS(SELECT 1 FROM test_results WHERE userId = :uid AND isSimulation = 1)")
    fun hasCompletedSimulation(uid: String): Flow<Boolean>

    @Query("SELECT * FROM test_results WHERE userId = :uid AND isSimulation = 1 ORDER BY completedAt DESC LIMIT 1")
    suspend fun getLastCompletedSimulation(uid: String): TestResult?
}

// ─── Notes DAO ───────────────────────────────────────────────────────────────

@Entity(tableName = "achievements", primaryKeys = ["id", "userId"])
data class Achievement(
    val id: String,             // e.g. "streak_3", "perfect_100"
    val userId: String = "",    // UID of the owner
    @ColumnInfo(name = "unlockedAt") val unlockedAt: Long = System.currentTimeMillis()
)

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements WHERE userId = :uid")
    suspend fun getAll(uid: String): List<Achievement>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(achievement: Achievement)

    @Query("SELECT EXISTS(SELECT 1 FROM achievements WHERE id = :id AND userId = :uid)")
    suspend fun hasAchievement(id: String, uid: String): Boolean
}

@Dao
interface NoteDao {
    @Query("""
        SELECT * FROM notes
        WHERE userId = :uid
        AND (:subject = 'All' OR subject = :subject)
        AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC
    """)
    fun searchNotes(uid: String, subject: String, query: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)
}

// ─── Scan History DAO ─────────────────────────────────────────────────────────

@Dao
interface ScanHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: ScanHistory): Long

    /** Cache lookup — returns non-null if this exact image was solved before. */
    @Query("SELECT * FROM scan_history WHERE userId = :uid AND imageHash = :hash LIMIT 1")
    suspend fun findByHash(uid: String, hash: String): ScanHistory?

    @Query("SELECT * FROM scan_history WHERE userId = :uid ORDER BY scannedAt DESC LIMIT 50")
    fun getRecentFlow(uid: String): Flow<List<ScanHistory>>

    @Query("SELECT * FROM scan_history WHERE userId = :uid ORDER BY scannedAt DESC LIMIT 50")
    suspend fun getAllOnce(uid: String): List<ScanHistory>

    @Query("DELETE FROM scan_history WHERE userId = :uid AND id NOT IN (SELECT id FROM (SELECT id FROM scan_history WHERE userId = :uid ORDER BY scannedAt DESC LIMIT :limit))")
    suspend fun pruneOldHistory(uid: String, limit: Int)

    @Query("SELECT * FROM scan_history WHERE id = :id")
    suspend fun getById(id: Int): ScanHistory?

    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM scan_history WHERE scannedAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}

// ─── Room Database ────────────────────────────────────────────────────────────

// ─── Power 100 DAO ────────────────────────────────────────────────────────────

@Dao
interface Power100Dao {
    @Query("SELECT * FROM power100_questions WHERE examType = :exam ORDER BY position ASC")
    suspend fun getQuestionsForExam(exam: String): List<Power100Question>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<Power100Question>)

    @Query("DELETE FROM power100_questions WHERE examType = :exam")
    suspend fun deleteForExam(exam: String)

    @Query("SELECT COUNT(*) FROM power100_questions WHERE examType = :exam")
    suspend fun getCount(exam: String): Int

    @Query("SELECT * FROM power100_progress WHERE userId = :uid AND examType = :exam ORDER BY position ASC")
    suspend fun getProgress(uid: String, exam: String): List<Power100Progress>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: Power100Progress)

    @Query("DELETE FROM power100_progress WHERE userId = :uid AND examType = :exam")
    suspend fun resetProgress(uid: String, exam: String)

    @Query("SELECT * FROM power100_progress WHERE userId = :uid AND examType = :exam AND position = :pos")
    suspend fun getProgressForPosition(uid: String, exam: String, pos: Int): Power100Progress?
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE test_results ADD COLUMN questionTimesJson TEXT NOT NULL DEFAULT ''"
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE notes ADD COLUMN userId TEXT NOT NULL DEFAULT ''"
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE scan_history ADD COLUMN questionSnippet TEXT NOT NULL DEFAULT ''"
        )
        database.execSQL(
            "ALTER TABLE scan_history ADD COLUMN subject TEXT NOT NULL DEFAULT 'General'"
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Placeholder to maintain version chain
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE scan_history ADD COLUMN userId TEXT NOT NULL DEFAULT ''"
        )
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE achievements_new (id TEXT NOT NULL, userId TEXT NOT NULL, unlockedAt INTEGER NOT NULL, PRIMARY KEY(id, userId))")
        database.execSQL("INSERT INTO achievements_new (id, userId, unlockedAt) SELECT id, '', unlockedAt FROM achievements")
        database.execSQL("DROP TABLE achievements")
        database.execSQL("ALTER TABLE achievements_new RENAME TO achievements")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Placeholder for missing migration
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE questions ADD COLUMN isDailyVault INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE questions ADD COLUMN vaultDate TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE questions ADD COLUMN vaultGroupId TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE bookmarks (
                questionId INTEGER NOT NULL,
                userId TEXT NOT NULL,
                bookmarkedAt INTEGER NOT NULL,
                PRIMARY KEY(questionId, userId)
            )
        """)
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE power100_questions (
                examType TEXT NOT NULL,
                position INTEGER NOT NULL,
                subject TEXT NOT NULL,
                chapter TEXT NOT NULL,
                difficulty TEXT NOT NULL,
                questionText TEXT NOT NULL,
                options TEXT NOT NULL,
                correctOptionIndex INTEGER NOT NULL,
                explanation TEXT NOT NULL,
                PRIMARY KEY(examType, position)
            )
        """)
        database.execSQL("""
            CREATE TABLE power100_progress (
                userId TEXT NOT NULL,
                examType TEXT NOT NULL,
                position INTEGER NOT NULL,
                selectedOption INTEGER NOT NULL DEFAULT -1,
                isBookmarked INTEGER NOT NULL DEFAULT 0,
                timeTakenMs INTEGER NOT NULL DEFAULT 0,
                answeredAt INTEGER NOT NULL,
                PRIMARY KEY(userId, examType, position)
            )
        """)
    }
}

@Database(
    entities = [
        Question::class, TestResult::class, ScanHistory::class, Note::class,
        Achievement::class, BookmarkedQuestion::class,
        Power100Question::class, Power100Progress::class
    ],
    version = 16,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MockTestDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun testResultDao(): TestResultDao
    abstract fun scanHistoryDao(): ScanHistoryDao
    abstract fun noteDao(): NoteDao
    abstract fun achievementDao(): AchievementDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun power100Dao(): Power100Dao

    companion object {
        @Volatile private var INSTANCE: MockTestDatabase? = null

        fun getInstance(context: Context): MockTestDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MockTestDatabase::class.java,
                    "mocktest_db"
                )
                .addMigrations(
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15, MIGRATION_15_16
                )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
    }
}
