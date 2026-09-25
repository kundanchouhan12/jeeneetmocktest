package com.jeeneet.mocktest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.data.model.Power100Question
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.repository.DailyVaultContract
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.data.repository.QuestionFirestoreParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DailyVaultContractTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        MockTestDatabase.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun vaultQ(
        exam: String,
        date: String,
        group: String,
        n: Int,
        isVault: Boolean = true
    ) = Question(
        examType = exam,
        subject = "Physics",
        chapter = "Kinematics",
        difficulty = "Medium",
        year = 0,
        questionText = "$exam vault stem $n on $date",
        options = listOf("A", "B", "C", "D"),
        correctOptionIndex = 0,
        explanation = "exp",
        isPremium = false,
        isDailyVault = isVault,
        vaultDate = date,
        vaultGroupId = group
    )

    private fun thirty(exam: String, date: String, group: String) =
        (1..30).map { vaultQ(exam, date, group, it) }

    @Test
    fun test1_thirtyValidMapsParseToThirty() {
        val parsed = (1..30).mapNotNull { n ->
            QuestionFirestoreParser.parseQuestion(
                mapOf(
                    "examType" to "JEE",
                    "subject" to "Physics",
                    "chapter" to "Kinematics",
                    "questionText" to "Stem $n",
                    "options" to listOf("A", "B", "C", "D"),
                    "correctOptionIndex" to 1L,
                    "isDailyVault" to true,
                    "vaultDate" to "2026-09-25",
                    "vaultGroupId" to "jee_vault_2026-09-25"
                )
            ).question
        }
        assertEquals(30, parsed.size)
        assertTrue(DailyVaultContract.isCompleteIncoming(parsed, "JEE", "2026-09-25"))
    }

    @Test
    fun test2_twelveMalformedMeansIncomplete() {
        val maps = (1..30).map { n ->
            val m = mutableMapOf<String, Any>(
                "examType" to "JEE",
                "subject" to "Physics",
                "chapter" to "Kinematics",
                "questionText" to "Stem $n",
                "options" to listOf("A", "B", "C", "D"),
                "correctOptionIndex" to 1L,
                "isDailyVault" to true,
                "vaultDate" to "2026-09-25",
                "vaultGroupId" to "jee_vault_2026-09-25"
            )
            if (n > 18) m.remove("chapter")
            m
        }
        val parsed = maps.mapNotNull { QuestionFirestoreParser.parseQuestion(it).question }
        assertEquals(18, parsed.size)
        assertFalse(DailyVaultContract.isCompleteIncoming(parsed, "JEE", "2026-09-25"))
        assertFalse(
            DailyVaultContract.shouldSkipNetwork(
                snapshotDate = "2026-09-25",
                today = "2026-09-25",
                savedGroupId = "jee_vault_2026-09-25",
                cacheIsCompleteToday = false
            )
        )
    }

    @Test
    fun test3_localEighteenMustRetry() {
        val rows = (1..18).map { vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", it) }
        assertFalse(DailyVaultContract.isCompleteCache(rows, "JEE", "2026-09-25", "jee_vault_2026-09-25"))
        assertFalse(
            DailyVaultContract.shouldSkipNetwork("2026-09-25", "2026-09-25", "jee_vault_2026-09-25", false)
        )
    }

    @Test
    fun test3b_localTwentySevenMustRetry() {
        val rows = (1..27).map { vaultQ("NEET", "2026-09-25", "neet_vault_2026-09-25", it) }
        assertFalse(DailyVaultContract.isCompleteCache(rows, "NEET", "2026-09-25", "neet_vault_2026-09-25"))
        assertFalse(
            DailyVaultContract.shouldSkipNetwork("2026-09-25", "2026-09-25", "neet_vault_2026-09-25", false)
        )
    }

    @Test
    fun test3c_duplicateQuestionsMeansIncomplete() {
        val rows = (1..30).map { vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", if (it == 30) 1 else it) }
        assertFalse(DailyVaultContract.isCompleteCache(rows, "JEE", "2026-09-25", "jee_vault_2026-09-25"))
        assertFalse(DailyVaultContract.isCompleteIncoming(rows, "JEE", "2026-09-25"))
    }

    @Test
    fun test4_localThirtyTodaySkipsNetwork() {
        val rows = thirty("JEE", "2026-09-25", "jee_vault_2026-09-25")
        assertTrue(DailyVaultContract.isCompleteCache(rows, "JEE", "2026-09-25", "jee_vault_2026-09-25"))
        assertTrue(
            DailyVaultContract.shouldSkipNetwork("2026-09-25", "2026-09-25", "jee_vault_2026-09-25", true)
        )
    }

    @Test
    fun test5_yesterdayThirtyKeptWhenTodayIncomplete() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        dao.insertQuestions(thirty("JEE", "2026-09-24", "jee_vault_2026-09-24"))
        val todayIncomplete = (1..18).map { vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", it) }
        assertFalse(DailyVaultContract.isCompleteIncoming(todayIncomplete, "JEE", "2026-09-25"))
        val kept = dao.getVaultQuestionsByGroupId("jee_vault_2026-09-24")
        assertEquals(30, kept.size)
    }

    @Test
    fun test6_todayThirtyReplacesYesterday() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        dao.insertQuestions(thirty("JEE", "2026-09-24", "jee_vault_2026-09-24"))
        val today = thirty("JEE", "2026-09-25", "jee_vault_2026-09-25")
        assertTrue(DailyVaultContract.isCompleteIncoming(today, "JEE", "2026-09-25"))
        dao.deleteAllDailyVaultForExam("JEE")
        dao.insertQuestions(today)
        assertEquals(0, dao.getVaultQuestionsByGroupId("jee_vault_2026-09-24").size)
        assertEquals(30, dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25").size)
    }

    @Test
    fun test7_jeeAndNeetStayIsolated() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        dao.insertQuestions(thirty("JEE", "2026-09-25", "jee_vault_2026-09-25"))
        dao.insertQuestions(thirty("NEET", "2026-09-25", "neet_vault_2026-09-25"))
        val jee = dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25")
            .filter { it.examType == "JEE" }
        val neet = dao.getVaultQuestionsByGroupId("neet_vault_2026-09-25")
            .filter { it.examType == "NEET" }
        assertEquals(30, jee.size)
        assertEquals(30, neet.size)
        assertTrue(jee.none { it.examType == "NEET" })
        assertTrue(neet.none { it.examType == "JEE" })
    }

    @Test
    fun test8_packWipeDoesNotDeleteVault() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        dao.insertQuestions(thirty("JEE", "2026-09-25", "jee_vault_2026-09-25"))
        dao.deletePremiumQuestionsBySubject("JEE", "Physics")
        assertEquals(30, dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25").size)
    }

    @Test
    fun test9_twoCompleteReplacesLeaveExactlyThirty() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        val a = thirty("JEE", "2026-09-25", "jee_vault_2026-09-25")
        val b = thirty("JEE", "2026-09-25", "jee_vault_2026-09-25")
        dao.deleteAllDailyVaultForExam("JEE")
        dao.insertQuestions(a)
        dao.deleteAllDailyVaultForExam("JEE")
        dao.insertQuestions(b)
        assertEquals(30, dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25").size)
    }

    @Test
    fun test12_power100TableUntouchedByVaultDeletes() = runBlocking {
        val db = MockTestDatabase.getInstance(ctx)
        db.power100Dao().insertQuestions(
            listOf(
                Power100Question(
                    examType = "JEE",
                    position = 1,
                    subject = "Physics",
                    chapter = "Kinematics",
                    difficulty = "Easy",
                    questionText = "P100 stem",
                    options = listOf("A", "B", "C", "D"),
                    correctOptionIndex = 0,
                    explanation = "e"
                )
            )
        )
        db.questionDao().insertQuestions(thirty("JEE", "2026-09-25", "jee_vault_2026-09-25"))
        db.questionDao().deleteAllDailyVaultForExam("JEE")
        db.questionDao().deletePremiumQuestionsBySubject("JEE", "Physics")
        db.questionDao().deleteOldVaultQuestions("2026-09-26")
        assertEquals(1, db.power100Dao().getCount("JEE"))
        assertEquals("P100 stem", db.power100Dao().getQuestionsForExam("JEE").first().questionText)
    }

    @Test
    fun rejectsNonJeeNeetExamType() {
        val result = QuestionFirestoreParser.parseQuestion(
            mapOf(
                "examType" to "JEE Main",
                "subject" to "Physics",
                "chapter" to "Kinematics",
                "questionText" to "Stem",
                "options" to listOf("A", "B", "C", "D"),
                "correctOptionIndex" to 0L
            )
        )
        assertNull(result.question)
        assertTrue(result.dropReason!!.contains("JEE or NEET"))
    }

    // ─── Deeper root-cause audit additions (2026-09-25) ─────────────────────────

    @Test
    fun test13_twentyNineIsIncomplete() {
        val rows = (1..29).map { vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", it) }
        assertFalse(DailyVaultContract.isCompleteCache(rows, "JEE", "2026-09-25", "jee_vault_2026-09-25"))
        assertFalse(DailyVaultContract.isCompleteIncoming(rows, "JEE", "2026-09-25"))
    }

    @Test
    fun test14_thirtyOneIsInvalidNotComplete() {
        val rows = (1..31).map { vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", it) }
        assertFalse(DailyVaultContract.isCompleteCache(rows, "JEE", "2026-09-25", "jee_vault_2026-09-25"))
        assertFalse(DailyVaultContract.isCompleteIncoming(rows, "JEE", "2026-09-25"))
    }

    @Test
    fun test15_oneWrongVaultDateAmongThirtyMakesIncomplete() {
        val rows = (1..30).map { n ->
            vaultQ("JEE", if (n == 15) "2026-09-24" else "2026-09-25", "jee_vault_2026-09-25", n)
        }
        assertFalse(DailyVaultContract.isCompleteIncoming(rows, "JEE", "2026-09-25"))
    }

    @Test
    fun test16_oneNonVaultFlaggedRowAmongThirtyMakesIncomplete() {
        val rows = (1..30).map { n ->
            vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", n, isVault = (n != 20))
        }
        assertFalse(DailyVaultContract.isCompleteIncoming(rows, "JEE", "2026-09-25"))
    }

    @Test
    fun test17_retryAfterIncompleteSucceedsWhenFullSetArrives() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        // A prior failed/partial sync left only 18 rows cached under today's group.
        dao.insertQuestions((1..18).map { vaultQ("JEE", "2026-09-25", "jee_vault_2026-09-25", it) })
        assertFalse(
            DailyVaultContract.isCompleteCache(
                dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25"), "JEE", "2026-09-25", "jee_vault_2026-09-25"
            )
        )

        // Firestore now has a genuinely complete 30 for today — retry must succeed.
        val fullSet = thirty("JEE", "2026-09-25", "jee_vault_2026-09-25_retry")
        assertTrue(DailyVaultContract.isCompleteIncoming(fullSet, "JEE", "2026-09-25"))
        dao.replaceDailyVault("JEE", fullSet)

        val finalRows = dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25_retry")
        assertEquals(30, finalRows.size)
        assertTrue(DailyVaultContract.isCompleteCache(finalRows, "JEE", "2026-09-25", "jee_vault_2026-09-25_retry"))
    }

    @Test
    fun test18_replaceDailyVaultAtomicSwap() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        dao.insertQuestions(thirty("JEE", "2026-09-24", "jee_vault_2026-09-24"))
        dao.replaceDailyVault("JEE", thirty("JEE", "2026-09-25", "jee_vault_2026-09-25"))
        assertEquals(0, dao.getVaultQuestionsByGroupId("jee_vault_2026-09-24").size)
        assertEquals(30, dao.getVaultQuestionsByGroupId("jee_vault_2026-09-25").size)
    }

    /**
     * Reproduces the real-world race: QuestionSyncManager is constructed fresh at every
     * call site (onResume, the IAP-restore callback, switchExam, ...), so two coroutines
     * can genuinely call replaceDailyVault() for the same exam at the same time. Each
     * @Transaction call must still be serialized by Room — never interleaved into a
     * doubled/mixed row set.
     */
    @Test
    fun test19_concurrentReplaceDailyVaultNeverDoublesOrMixesRows() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        val batchA = thirty("JEE", "2026-09-27", "jee_vault_2026-09-27_A")
        val batchB = thirty("JEE", "2026-09-27", "jee_vault_2026-09-27_B")

        val jobA = async(Dispatchers.IO) { dao.replaceDailyVault("JEE", batchA) }
        val jobB = async(Dispatchers.IO) { dao.replaceDailyVault("JEE", batchB) }
        jobA.await()
        jobB.await()

        val all = dao.getDailyVaultQuestions("JEE", "2026-09-27")
        assertEquals(
            "two racing replaceDailyVault() calls must never leave a mixed/doubled 60-row state",
            30, all.size
        )
        assertEquals(1, all.map { it.vaultGroupId }.toSet().size)
    }

    @Test
    fun test20_concurrentJeeAndNeetReplaceStayIsolated() = runBlocking {
        val dao = MockTestDatabase.getInstance(ctx).questionDao()
        val jee = thirty("JEE", "2026-09-27", "jee_vault_2026-09-27")
        val neet = thirty("NEET", "2026-09-27", "neet_vault_2026-09-27")

        val jobJee = async(Dispatchers.IO) { dao.replaceDailyVault("JEE", jee) }
        val jobNeet = async(Dispatchers.IO) { dao.replaceDailyVault("NEET", neet) }
        jobJee.await()
        jobNeet.await()

        val jeeRows = dao.getDailyVaultQuestions("JEE", "2026-09-27")
        val neetRows = dao.getDailyVaultQuestions("NEET", "2026-09-27")
        assertEquals(30, jeeRows.size)
        assertEquals(30, neetRows.size)
        assertTrue(jeeRows.none { it.examType == "NEET" })
        assertTrue(neetRows.none { it.examType == "JEE" })
    }
}
