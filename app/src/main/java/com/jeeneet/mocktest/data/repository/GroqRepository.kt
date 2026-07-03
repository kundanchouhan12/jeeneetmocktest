package com.jeeneet.mocktest.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Groq AI Repository
 * Fast AI solving for JEE/NEET questions
 */
class GroqRepository(private val context: Context) {

    companion object {

        /**
         * IMPORTANT:
         * Move this key to BuildConfig or backend in production.
         */
        private const val API_KEY =
            "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"

        private const val BASE_URL =
            "https://api.groq.com/openai/v1/chat/completions"

        /**
         * Latest working Groq model
         */
        private const val MODEL =
            "llama-3.3-70b-versatile"

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private const val SOLVE_PROMPT = """
You are an expert JEE/NEET AI tutor.

Solve the given question accurately.

IMPORTANT:
- First line MUST be:
[SUMMARY: short topic name]

Response Format:
1. Final Answer
2. Step-by-step Solution
3. Concept Used
4. Common Mistake

Rules:
- Use concise explanations
- Use LaTeX for formulas
- Keep formatting clean
- Do not generate unnecessary text
"""
    }

    suspend fun solve(question: String): Result<String> =
        withContext(Dispatchers.IO) {

            runCatching {

                if (API_KEY.isBlank() || API_KEY == "YOUR_GROQ_API_KEY") {
                    throw Exception("Groq API Key missing")
                }

                val messages = JSONArray().apply {

                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", SOLVE_PROMPT)
                        }
                    )

                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", question)
                        }
                    )
                }

                val requestBody = JSONObject().apply {
                    put("model", MODEL)
                    put("messages", messages)
                    put("temperature", 0.2)
                    put("max_tokens", 1024)
                    put("top_p", 1)
                    put("stream", false)
                }

                val request = Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .post(
                        requestBody
                            .toString()
                            .toRequestBody("application/json".toMediaType())
                    )
                    .build()

                val response = client.newCall(request).execute()

                val responseBody =
                    response.body?.string()
                        ?: throw Exception("Empty response from Groq")

                if (!response.isSuccessful) {

                    val errorMessage = try {
                        JSONObject(responseBody)
                            .optJSONObject("error")
                            ?.optString("message")
                            ?: responseBody
                    } catch (e: Exception) {
                        responseBody
                    }

                    throw Exception(
                        "Groq Error (${response.code}): $errorMessage"
                    )
                }

                val json = JSONObject(responseBody)

                val content =
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                if (content.isBlank()) {
                    throw Exception("Empty AI response")
                }

                content
            }
        }

    private val INSIGHTS_PROMPT = """
You are an expert JEE/NEET Academic Counselor.

Analyze the student's performance data and provide:
1. A brief encouraging summary (1 sentence).
2. 3 specific, actionable study tips based on their data.
3. A "Focus Priority" topic.

Rules:
- Be encouraging but realistic.
- Keep tips concise and practical.
- Use emojis for readability.
- Use clean formatting.
- Do NOT use LaTeX unless necessary for a specific topic name.
"""

    /**
     * POST-TEST ANALYSIS — Highly personalized, non-generic tips.
     *
     * Uses a strict prompt that forces Groq to reference the student's
     * EXACT numbers (score, accuracy, chapter names, wrong counts).
     * Temperature 0.85 ensures every response is uniquely worded.
     */
    private val TEST_ANALYSIS_PROMPT = """
You are a world-class JEE/NEET performance coach giving a post-test review.

The student just completed a test. You have their EXACT performance data below.

YOUR TASK — produce a SHORT, PERSONAL post-test report with these 4 sections:

📊 Quick Verdict (1 sentence using their actual score and accuracy %)
🎯 Chapter Focus (name the EXACT weak chapters they got wrong — no generic subjects)
⚡ 3 Smart Action Steps (specific, different for every student — reference their data)
🔮 Next Test Goal (set a concrete target score or accuracy % higher than today)

STRICT RULES:
- NEVER give generic advice like "study harder" or "revise concepts"
- EVERY sentence MUST reference the student's actual data (score, %, chapter names, wrong counts)
- Each action step must be unique and tied to their specific weak chapters
- Keep the entire response under 200 words
- Use emojis sparingly, formatting must be clean and easy to read on mobile
- Write in second person ("You scored…", "Your weakest area…")
- Do NOT use markdown headers like ## or **bold**, use emojis as visual separators only
"""

    suspend fun getTestAnalysis(
        score: Int, maxScore: Int, accuracy: Int,
        correct: Int, wrong: Int, skipped: Int,
        timeMins: Int, examType: String, subject: String,
        weakChapters: List<Pair<String, Int>>,   // chapter → wrong count
        subjectBreakdown: Map<String, Pair<Int,Int>> // subject → (correct, wrong)
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (API_KEY.isBlank() || API_KEY == "YOUR_GROQ_API_KEY") {
                throw Exception("Groq API Key missing")
            }

            // Build a rich, structured data string that leaves no room for generic answers
            val chapterList = if (weakChapters.isEmpty()) "None (great job!)"
            else weakChapters.joinToString("\n") { (ch, cnt) ->
                "  • $ch — $cnt wrong answer${if (cnt > 1) "s" else ""}"
            }
            val subjectList = if (subjectBreakdown.isEmpty()) "N/A"
            else subjectBreakdown.entries.joinToString("\n") { (subj, pair) ->
                "  • $subj: ${pair.first} correct, ${pair.second} wrong"
            }
            val attemptRate = if ((correct + wrong + skipped) > 0)
                ((correct + wrong) * 100 / (correct + wrong + skipped)) else 0

            val userData = buildString {
                appendLine("=== STUDENT TEST DATA ===")
                appendLine("Exam: $examType | Subject: ${subject.ifEmpty { "Full Paper" }}")
                appendLine("Score: $score / $maxScore")
                appendLine("Accuracy: $accuracy% | Attempt rate: $attemptRate%")
                appendLine("Correct: $correct | Wrong: $wrong | Skipped: $skipped")
                appendLine("Time taken: $timeMins min")
                appendLine()
                appendLine("WEAK CHAPTERS (wrong answers):")
                appendLine(chapterList)
                appendLine()
                if (subjectBreakdown.isNotEmpty()) {
                    appendLine("SUBJECT BREAKDOWN:")
                    appendLine(subjectList)
                }
                appendLine("=========================")
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", TEST_ANALYSIS_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userData)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("temperature", 0.85)   // high enough for unique phrasing every time
                put("max_tokens", 450)
                put("top_p", 0.95)
            }

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) throw Exception("Groq Error: $responseBody")

            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    suspend fun getInsights(performanceData: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (API_KEY.isBlank() || API_KEY == "YOUR_GROQ_API_KEY") {
                    throw Exception("Groq API Key missing")
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", INSIGHTS_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Here is my performance data:\n${performanceData}")
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", MODEL)
                    put("messages", messages)
                    put("temperature", 0.5)
                    put("max_tokens", 800)
                }

                val request = Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                if (!response.isSuccessful) throw Exception("Groq Error: $responseBody")

                JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }
}