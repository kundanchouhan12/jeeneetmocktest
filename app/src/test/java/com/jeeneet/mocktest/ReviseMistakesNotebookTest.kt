package com.jeeneet.mocktest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.data.repository.MockTestRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReviseMistakesNotebookTest {

    private lateinit var ctx: Context
    private lateinit var repo: MockTestRepository
    private val gson = Gson()

    @Before
    fun setUp() {
        MockTestDatabase.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        repo = MockTestRepository(ctx)
    }

    private fun makeQuestion(id: Int, subject: String, chapter: String, text: String, correct: Int = 0) = Question(
        id = id,
        examType = "JEE",
        subject = subject,
        chapter = chapter,
        difficulty = "Medium",
        year = 2024,
        questionText = text,
        options = listOf("Option A", "Option B", "Option C", "Option D"),
        correctOptionIndex = correct,
        explanation = "Explanation for $text"
    )

    @Test
    fun `getWrongQuestions does not collide multiple wrong questions having id 0`() = runBlocking {
        val db = MockTestDatabase.getInstance(ctx)
        val uid = "guest"

        // Daily Vault questions or parsed JSON questions typically have id = 0
        val q1 = makeQuestion(id = 0, subject = "Physics", chapter = "Kinematics", text = "What is velocity?", correct = 0)
        val q2 = makeQuestion(id = 0, subject = "Physics", chapter = "Laws of Motion", text = "What is force?", correct = 1)
        val q3 = makeQuestion(id = 0, subject = "Chemistry", chapter = "Thermodynamics", text = "What is entropy?", correct = 2)

        val questions = listOf(q1, q2, q3)
        // User answered q1 wrong (selected 1 instead of 0), q2 wrong (selected 2 instead of 1), q3 correct (selected 2)
        val answers = mapOf("0" to 1, "1" to 2, "2" to 2)

        val testResult = TestResult(
            userId = uid,
            examType = "JEE",
            subject = "Full Paper",
            totalQuestions = 3,
            attempted = 3,
            correct = 1,
            wrong = 2,
            score = 2f,
            maxScore = 12f,
            percentile = 75f,
            timeTakenSeconds = 120,
            questionsJson = gson.toJson(questions),
            answersJson = gson.toJson(answers)
        )
        db.testResultDao().insertResult(testResult)

        val wrongResult = repo.getWrongQuestions("JEE")

        assertEquals("Should extract both wrong questions even though both had id=0", 2, wrongResult.questions.size)
        assertEquals("Physics wrong questions count", 2, wrongResult.bySubject["Physics"])
        assertTrue("Contains q1", wrongResult.questions.any { it.questionText == "What is velocity?" })
        assertTrue("Contains q2", wrongResult.questions.any { it.questionText == "What is force?" })
        assertEquals("From 1 completed test", 1, wrongResult.fromTestCount)
    }

    @Test
    fun `getWrongQuestions deduplicates identical questions across multiple tests`() = runBlocking {
        val db = MockTestDatabase.getInstance(ctx)
        val uid = "guest"

        val q1 = makeQuestion(id = 0, subject = "Maths", chapter = "Calculus", text = "Evaluate integral x dx", correct = 0)
        val questions = listOf(q1)
        val answers = mapOf("0" to 3) // wrong

        val r1 = TestResult(
            userId = uid, examType = "JEE", subject = "Full Paper", totalQuestions = 1, attempted = 1,
            correct = 0, wrong = 1, score = -1f, maxScore = 4f, percentile = 20f, timeTakenSeconds = 30,
            questionsJson = gson.toJson(questions), answersJson = gson.toJson(answers)
        )
        val r2 = TestResult(
            userId = uid, examType = "JEE", subject = "Full Paper", totalQuestions = 1, attempted = 1,
            correct = 0, wrong = 1, score = -1f, maxScore = 4f, percentile = 20f, timeTakenSeconds = 30,
            questionsJson = gson.toJson(questions), answersJson = gson.toJson(answers)
        )
        db.testResultDao().insertResult(r1)
        db.testResultDao().insertResult(r2)

        val wrongResult = repo.getWrongQuestions("JEE")
        assertEquals("Identical mistake should only appear once in revision notebook", 1, wrongResult.questions.size)
        assertEquals("Recorded across 2 tests", 2, wrongResult.fromTestCount)
    }

    @Test
    fun `getWrongQuestions handles integer-keyed answersJson map gracefully`() = runBlocking {
        val db = MockTestDatabase.getInstance(ctx)
        val uid = "guest"

        val q1 = makeQuestion(id = 101, subject = "Biology", chapter = "Cell", text = "Mitochondria role?", correct = 0)
        val questions = listOf(q1)
        // Serialized as Map<Int, Int?>: {101: 2}
        val intKeyAnswers = mapOf(0 to 2)

        val r = TestResult(
            userId = uid, examType = "NEET", subject = "Biology", totalQuestions = 1, attempted = 1,
            correct = 0, wrong = 1, score = -1f, maxScore = 4f, percentile = 10f, timeTakenSeconds = 45,
            questionsJson = gson.toJson(questions), answersJson = gson.toJson(intKeyAnswers)
        )
        db.testResultDao().insertResult(r)

        val wrongResult = repo.getWrongQuestions("NEET")
        assertEquals(1, wrongResult.questions.size)
        assertEquals("Mitochondria role?", wrongResult.questions.first().questionText)
    }
}
