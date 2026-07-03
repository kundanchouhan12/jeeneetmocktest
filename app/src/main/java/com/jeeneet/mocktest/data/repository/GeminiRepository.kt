package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GeminiRepository(private val context: Context) {

    companion object {
        // 🔥 Prioritize stable models for Free Tier
        private val MODELS = listOf(
            "gemini-flash-latest",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-pro-latest",
            "gemini-2.5-pro"
        )
        private fun endpointFor(model: String) =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"


        // 🔥 Short cooldown
        @Volatile private var rateLimitedUntilMs: Long = 0

        // 🔥 Prevent parallel calls
        private val mutex = Mutex()

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .build()

        private val SOLVE_PROMPT = """
            Solve this JEE/NEET question.
            
            IMPORTANT: Your FIRST line MUST be a short (max 6 words) summary of the problem inside brackets. 
            Example: [SUMMARY: Kinetic Energy Calculation]
            
            Format:
            1. Final Answer
            2. Steps
            3. Concept
            4. Mistake

            IMPORTANT: DO NOT use LaTeX. Use plain, readable text for all math and formulas (e.g., type "sin(theta) = 1/1.5" instead of "\sin \theta = \frac{1}{1.5}"). Be concise.
        """.trimIndent()
    }

    private val db = MockTestDatabase.getInstance(context)

    // ─────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────

    suspend fun solveQuestion(
        bitmap: Bitmap,
        ocrText: String? = null,
        onStatus: (String) -> Unit = {}
    ): Result<SolveResult> = withContext(Dispatchers.IO) {
        runCatching {

            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
            val base64 = bitmapToBase64(bitmap)
            val imageHash = computeMd5(base64)

            // 1. Try Cache with Image Hash
            // 1. Check Cache
            db.scanHistoryDao().findByHash(uid, imageHash)?.let {
                return@runCatching SolveResult(it.solutionMarkdown, CacheStatus.HIT, "Cache")
            }

            // 2. Try Local Solver with OCR Text (Hybrid AI)
            if (!ocrText.isNullOrBlank()) {
                val textHash = computeMd5(normalizeText(ocrText))
                
                // Secondary cache hit
                db.scanHistoryDao().findByHash(uid, textHash)?.let {
                    onStatus("Found in question memory! 🧠")
                    return@runCatching SolveResult(it.solutionMarkdown, CacheStatus.HIT, "Cache")
                }

                com.jeeneet.mocktest.utils.LocalSolver.solve(ocrText)?.let { local ->
                    onStatus("Solved locally! ✨")
                    val solution = local.solution
                    db.scanHistoryDao().insert(ScanHistory(
                        userId = uid,
                        imageHash = textHash,
                        questionSnippet = extractSnippet(solution).first,
                        subject = local.subject,
                        solutionMarkdown = solution
                    ))
                    return@runCatching SolveResult(solution, CacheStatus.MISS, "Local Solver")
                }

                // 2.5 Try Local Database Search
                val queryForSearch = if (ocrText.length > 30) ocrText.take(50) else ocrText
                db.questionDao().findSimilar(queryForSearch)?.let { matched ->
                    onStatus("Match found in local bank! 📚")
                    val solution = """
                        [SUMMARY: Local Database Match]
                        ### Question
                        ${matched.questionText}
                        
                        ### Final Answer
                        **Option ${matched.correctOptionIndex + 1}**
                        
                        ### Explanation
                        ${matched.explanation}
                    """.trimIndent()
                    
                    db.scanHistoryDao().insert(ScanHistory(
                        userId = uid,
                        imageHash = textHash,
                        questionSnippet = queryForSearch,
                        subject = matched.subject,
                        solutionMarkdown = solution
                    ))
                    return@runCatching SolveResult(solution, CacheStatus.MISS, "Local Bank")
                }
            }

            // 3. Try Gemini Vision API (Primary)
            var finalModel = "Gemini 1.5 Flash"
            val result = try {
                callWithRetry(onStatus) {
                    finalModel = it
                    callVisionApi(base64, it)
                }
            } catch (e: Exception) {
                // 4. Final Fallback to Groq Text (If OCR was available)
                if (!ocrText.isNullOrBlank()) {
                    onStatus("Vision busy. Trying Groq Text... ⚡")
                    finalModel = "Llama 3 (Groq)"
                    GroqRepository(context).solve(ocrText).getOrThrow()
                } else {
                    throw e
                }
            }
            
            val (snippet, cleaned) = extractSnippet(result)
            db.scanHistoryDao().insert(ScanHistory(
                userId = uid,
                imageHash = imageHash,
                questionSnippet = snippet,
                solutionMarkdown = cleaned
            ))
            
            // Also save with OCR hash if available to link them
            if (!ocrText.isNullOrBlank()) {
                val textHash = computeMd5(normalizeText(ocrText))
                db.scanHistoryDao().insert(ScanHistory(
                    userId = uid,
                    imageHash = textHash,
                    questionSnippet = snippet,
                    solutionMarkdown = cleaned
                ))
            }
            
            db.scanHistoryDao().pruneOldHistory(uid, 50)
            
            val totalSolved = db.scanHistoryDao().getAllOnce(uid).size
            AchievementManager.checkDoubtSolved(context, totalSolved)
            
            SolveResult(cleaned, CacheStatus.MISS, finalModel)
        }
    }

    suspend fun solveTextQuestion(
        question: String,
        onStatus: (String) -> Unit = {}
    ): Result<SolveResult> = withContext(Dispatchers.IO) {
        runCatching {

            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
            val normalized = normalizeText(question)
            val hash = computeMd5(normalized)

            // 1. Check Cache (Question Memory)
            db.scanHistoryDao().findByHash(uid, hash)?.let {
                return@runCatching SolveResult(it.solutionMarkdown, CacheStatus.HIT, "Cache")
            }

            // 2. Try Local Solver (Hybrid AI - Rules)
            com.jeeneet.mocktest.utils.LocalSolver.solve(question)?.let { local ->
                onStatus("Solved locally! ✨")
                val solution = local.solution
                db.scanHistoryDao().insert(ScanHistory(
                    userId = uid,
                    imageHash = hash,
                    questionSnippet = extractSnippet(solution).first,
                    subject = local.subject,
                    solutionMarkdown = solution
                ))
                return@runCatching SolveResult(solution, CacheStatus.MISS, "Local Solver")
            }

            // 3. Try Local Database Search (100% Free & Offline)
            val queryForSearch = if (question.length > 30) question.take(50) else question
            db.questionDao().findSimilar(queryForSearch)?.let { matched ->
                onStatus("Match found in local bank! 📚")
                val solution = """
                    [SUMMARY: Local Database Match]
                    ### Question
                    ${matched.questionText}
                    
                    ### Final Answer
                    **Option ${matched.correctOptionIndex + 1}**
                    
                    ### Explanation
                    ${matched.explanation}
                """.trimIndent()
                
                db.scanHistoryDao().insert(ScanHistory(
                    userId = uid,
                    imageHash = hash,
                    questionSnippet = queryForSearch,
                    subject = matched.subject,
                    solutionMarkdown = solution
                ))
                return@runCatching SolveResult(solution, CacheStatus.MISS, "Local Bank")
            }

            // 4. Try Gemini AI (Primary)
            var finalModel = "Gemini 1.5 Flash"
            val result = try {
                callWithRetry(onStatus) {
                    finalModel = it
                    callTextApi(question, it)
                }
            } catch (e: Exception) {
                // 5. Final Fallback to Groq AI (Free Tier, High Limits)
                onStatus("Gemini busy. Trying Groq... ⚡")
                finalModel = "Llama 3 (Groq)"
                GroqRepository(context).solve(question).getOrThrow()
            }

            val (snippet, cleaned) = extractSnippet(result)
            if (!cleaned.contains("AI Service Busy")) {
                db.scanHistoryDao().insert(ScanHistory(
                    userId = uid,
                    imageHash = hash,
                    questionSnippet = snippet,
                    solutionMarkdown = cleaned
                ))
                db.scanHistoryDao().pruneOldHistory(uid, 50)
            }
            
            val totalSolved = db.scanHistoryDao().getAllOnce(uid).size
            AchievementManager.checkDoubtSolved(context, totalSolved)
            
            SolveResult(cleaned, CacheStatus.MISS, finalModel)
        }
    }

    suspend fun askFollowUp(
        previousSolution: String,
        followUpQuestion: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = """
                Previous solution:
                $previousSolution

                Follow-up question: $followUpQuestion

                Answer concisely. DO NOT use LaTeX. Use plain, readable text for all math.
            """.trimIndent()
            callWithRetry({}) { model -> callTextApi(prompt, model) }
        }
    }

    suspend fun saveScanHistory(solutionMarkdown: String) = withContext(Dispatchers.IO) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        val hash = computeMd5(solutionMarkdown)
        val existing = db.scanHistoryDao().findByHash(uid, hash)
        if (existing == null) {
            val (snippet, cleaned) = extractSnippet(solutionMarkdown)
            db.scanHistoryDao().insert(
                ScanHistory(
                    userId = uid,
                    imageHash = hash,
                    questionSnippet = snippet,
                    solutionMarkdown = cleaned
                )
            )
            db.scanHistoryDao().pruneOldHistory(uid, 50)
            
            val totalSolved = db.scanHistoryDao().getAllOnce(uid).size
            AchievementManager.checkDoubtSolved(context, totalSolved)
        }
    }

    private fun extractSnippet(raw: String): Pair<String, String> {
        val regex = Regex("\\[SUMMARY:\\s*(.*?)\\]")
        val match = regex.find(raw)
        return if (match != null) {
            val snippet = match.groupValues[1].trim()
            val cleaned = raw.replace(match.value, "").trim()
            if (cleaned.isBlank()) Pair(snippet, raw) else Pair(snippet, cleaned)
        } else {
            Pair("Solved Doubt", raw)
        }
    }

    // ─────────────────────────────────────────────
    // 🔥 CORE RETRY ENGINE (MAIN FIX)
    // ─────────────────────────────────────────────

    private suspend fun callWithRetry(
        onStatus: (String) -> Unit,
        apiCall: suspend (String) -> String
    ): String {

        // Wait out any active rate-limit cooldown before attempting
        val cooldownRemaining = rateLimitedUntilMs - System.currentTimeMillis()
        if (cooldownRemaining > 0) {
            val secs = (cooldownRemaining / 1000).coerceAtLeast(1)
            onStatus("Rate limited — waiting ${secs}s…")
            delay(cooldownRemaining)
        }

        var lastError: Exception? = null

        for (model in MODELS) {

            var attempt = 0

            while (attempt < 2) {
                try {
                    return mutex.withLock {
                        apiCall(model)
                    }

                } catch (e: GeminiApiException) {

                    lastError = e

                    if (e.httpCode == 429) {
                        rateLimitedUntilMs = System.currentTimeMillis() + 62_000L
                        onStatus("Quota hit — waiting or switching…")
                        delay(3000)
                        break
                    }

                    if (e.httpCode >= 500) {
                        onStatus("AI Server error — retrying…")
                        delay(4000)
                        attempt++
                        continue
                    }

                    if (e.httpCode == 404 || e.httpCode == 400 || e.httpCode == 403) {
                        onStatus("Error ${e.httpCode} on $model. Trying next…")
                        delay(2000)
                        break 
                    }

                    throw e
                } catch (e: SocketTimeoutException) {
                    lastError = e
                    onStatus("Timeout — retrying…")
                    delay(4000)
                    attempt++
                } catch (e: Exception) {
                    lastError = e
                    onStatus("Network error — retrying…")
                    delay(4000)
                    attempt++
                }
            }
        }

        val isRateLimit = lastError is GeminiApiException && (lastError as GeminiApiException).httpCode == 429
        if (!isRateLimit) rateLimitedUntilMs = System.currentTimeMillis() + 15_000
        throw lastError ?: Exception("Failed. Try again.")
    }

    // ─────────────────────────────────────────────
    // API CALLS
    // ─────────────────────────────────────────────

    private fun buildSafetySettings(): JSONArray = JSONArray().apply {
        val categories = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )
        categories.forEach { cat ->
            put(JSONObject().apply {
                put("category", cat)
                put("threshold", "BLOCK_ONLY_HIGH") // Prevent false positives for study questions
            })
        }
    }

    private fun callVisionApi(base64: String, model: String): String {

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", SOLVE_PROMPT))
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64)
                            })
                        })
                    })
                })
            })
            put("safetySettings", buildSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1536)
            })
        }

        return execute(body, model)
    }

    private fun callTextApi(question: String, model: String): String {

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", SOLVE_PROMPT))
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", question))
                    })
                })
            })
            put("safetySettings", buildSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
            })
        }

        return execute(body, model)
    }

    private fun execute(body: JSONObject, model: String): String {

        val request = Request.Builder()
            .url("${endpointFor(model)}?key=${com.jeeneet.mocktest.BuildConfig.GEMINI_API_KEY}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val raw = response.body?.string() ?: throw Exception("Empty response from AI server")

        if (!response.isSuccessful) {
            val errorMsg = when (response.code) {
                401, 403 -> "Invalid API Key or Region restricted. Check Google AI Studio settings."
                404 -> "Model '$model' not found. Falling back…"
                429 -> "Rate limit hit. Free tier allows 15 requests per minute."
                else -> "AI error (${response.code}). Please try again."
            }
            throw GeminiApiException(response.code, "$errorMsg\n$raw")
        }

        val json = JSONObject(raw)
        val candidates = json.optJSONArray("candidates")
        
        if (candidates == null || candidates.length() == 0) {
            // Check for safety block or other reason
            val promptFeedback = json.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason", "") ?: ""
            if (blockReason == "SAFETY") {
                return "⚠️ **AI Safety Block**: This question was flagged as potentially harmful by the AI. Please try re-scanning a clearer portion of the text."
            }
            throw Exception("AI returned no solution. Reason: ${blockReason.ifBlank { "Unknown" }}")
        }

        val first = candidates.getJSONObject(0)
        val finishReason = first.optString("finishReason", "")
        if (finishReason != "STOP" && finishReason != "") {
            if (finishReason == "SAFETY") return "⚠️ **AI Safety Block**: The solution was partially generated but blocked for safety. Please try a different question."
            throw Exception("AI stopped early. Reason: $finishReason")
        }

        return first.getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    // ─────────────────────────────────────────────
    // IMAGE OPTIMIZATION
    // ─────────────────────────────────────────────

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        if (scaled != bitmap) scaled.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun computeMd5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Normalize text for fuzzy cache matching:
     * - Lowercase
     * - Remove extra whitespace
     * - Remove punctuation/symbols (optional, keeping it simple for now)
     */
    private fun normalizeText(text: String): String {
        return text.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove non-alphanumeric except spaces
            .replace(Regex("\\s+"), " ")        // Collapse whitespace
            .trim()
    }
}

class GeminiApiException(val httpCode: Int, message: String) : Exception(message)

data class SolveResult(
    val markdown: String,
    val cacheStatus: CacheStatus,
    val modelUsed: String = "Gemini 1.5 Flash"
)

enum class CacheStatus { HIT, MISS }