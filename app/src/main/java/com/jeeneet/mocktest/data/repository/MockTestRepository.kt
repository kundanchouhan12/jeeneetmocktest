package com.jeeneet.mocktest.data.repository

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jeeneet.mocktest.data.model.QuestionExposure
import com.jeeneet.mocktest.data.model.ExamConfig
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [recycled] is true when the pool of not-recently-seen questions for this scope
 * was too small to fill the request, so previously-seen questions had to be
 * reused (or the test is simply shorter than requested). Callers should use this
 * to tell the user more content is coming rather than silently repeating.
 */
data class QuestionFetchResult(val questions: List<Question>, val recycled: Boolean = false)

class MockTestRepository(context: Context) {

    private val db = MockTestDatabase.getInstance(context)
    private val questionDao = db.questionDao()
    private val resultDao = db.testResultDao()
    private val bookmarkDao = db.bookmarkDao()

    // ─── Bookmarks ──────────────────────────────────────────────────────────

    suspend fun toggleBookmark(question: com.jeeneet.mocktest.data.model.Question): Boolean {
        val uid = currentUid()
        if (uid == "guest") return false
        val already = bookmarkDao.isBookmarked(question.id, uid)
        if (already) bookmarkDao.unbookmark(question.id, uid)
        else bookmarkDao.bookmark(BookmarkedQuestion(question.id, uid))
        return !already
    }

    suspend fun isBookmarked(questionId: Int): Boolean {
        val uid = currentUid()
        if (uid == "guest") return false
        return bookmarkDao.isBookmarked(questionId, uid)
    }

    fun getBookmarkedQuestions(): kotlinx.coroutines.flow.Flow<List<com.jeeneet.mocktest.data.model.Question>> =
        bookmarkDao.getBookmarkedQuestions(currentUid())

    // ─── Questions ──────────────────────────────────────────────────────────

    suspend fun getQuestionsForConfig(context: Context, config: ExamConfig, adUnlocked: Boolean = false): QuestionFetchResult =
        withContext(Dispatchers.IO) {
            val isUnlocked = adUnlocked || config.isDailyVault ||
                com.jeeneet.mocktest.utils.PrefManager.isAllAccessUnlocked(context) || when {
                config.chapter != null -> com.jeeneet.mocktest.utils.PrefManager.isPackUnlocked(context, getProductIdForExamSubject(config.examType, config.subject!!))
                config.subject != null -> com.jeeneet.mocktest.utils.PrefManager.isPackUnlocked(context, getProductIdForExamSubject(config.examType, config.subject))
                else -> false
            }

            if (config.isSimulation) {
                return@withContext fetchSimulationQuestions(context, config, isUnlocked)
            }

            if (config.isDailyQuiz) {
                return@withContext fetchDailyQuizQuestions(context, config, isUnlocked)
            }

            // Chapter tests: cache the daily question set so reopening the same chapter
            // shows the same questions instead of a new random draw each time
            if (config.chapter != null) {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val cacheKey = "chapter_daily_${config.examType}_${config.subject}_${config.chapter}_$today"
                val prefs = context.getSharedPreferences("mock_test_prefs", android.content.Context.MODE_PRIVATE)
                val cachedIds = prefs.getString(cacheKey, null)

                if (cachedIds != null) {
                    val ids = cachedIds.split(",").mapNotNull { it.toIntOrNull() }
                    val qs = questionDao.getQuestionsByIds(ids)
                    if (qs.size >= config.totalQuestions * 0.8)
                        return@withContext QuestionFetchResult(qs.padTo(config.totalQuestions))
                }

                val (questions, recycled) = if (isUnlocked) fetchAdaptiveQuestions(config) else run {
                    val totalAvailable = questionDao.getChapterQuestionCount(config.examType, config.subject!!, config.chapter)
                    val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
                    val candidates = questionDao.getQuestionsByChapter(config.examType, config.subject!!, config.chapter, candidateCount)
                    pickWithSeenTracking(
                        context, "chapter_${config.examType}_${config.subject}_${config.chapter}",
                        candidates, totalAvailable, config.totalQuestions
                    )
                }

                if (questions.isNotEmpty())
                    prefs.edit().putString(cacheKey, questions.joinToString(",") { it.id.toString() }).apply()

                return@withContext QuestionFetchResult(questions.padTo(config.totalQuestions), recycled)
            }

            if (!isUnlocked) {
                // Free User Full Mock -> Weekly Seed Logic
                if (config.subject == null && config.chapter == null) {
                    val weekKey = getWeekKey()
                    val prefKey = "weekly_free_mock_${config.examType}_$weekKey"
                    val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
                    val cachedIds = prefs.getString(prefKey, null)

                    if (cachedIds != null) {
                        val ids = cachedIds.split(",").mapNotNull { it.toIntOrNull() }
                        val qs = questionDao.getQuestionsByIds(ids)
                        if (qs.size >= config.totalQuestions * 0.8)
                            return@withContext QuestionFetchResult(qs.padTo(config.totalQuestions).shuffled())
                    }

                    val totalAvailable = questionDao.getFreeExamQuestionCount(config.examType)
                    val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
                    val candidates = questionDao.getFreeQuestions(config.examType, candidateCount)
                    val (newQs, recycled) = pickWithSeenTracking(
                        context, "free_${config.examType}", candidates, totalAvailable, config.totalQuestions
                    )
                    prefs.edit().putString(prefKey, newQs.joinToString(",") { it.id.toString() }).apply()
                    return@withContext QuestionFetchResult(newQs.padTo(config.totalQuestions), recycled)
                }

                return@withContext if (config.subject != null) {
                    val totalAvailable = questionDao.getFreeSubjectQuestionCount(config.examType, config.subject)
                    val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
                    val candidates = questionDao.getFreeQuestionsBySubject(config.examType, config.subject, candidateCount)
                    val (qs, recycled) = pickWithSeenTracking(
                        context, "free_subject_${config.examType}_${config.subject}", candidates, totalAvailable, config.totalQuestions
                    )
                    QuestionFetchResult(qs.padTo(config.totalQuestions), recycled)
                } else {
                    val totalAvailable = questionDao.getFreeExamQuestionCount(config.examType)
                    val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
                    val candidates = questionDao.getFreeQuestions(config.examType, candidateCount)
                    val (qs, recycled) = pickWithSeenTracking(
                        context, "free_${config.examType}", candidates, totalAvailable, config.totalQuestions
                    )
                    QuestionFetchResult(qs.padTo(config.totalQuestions), recycled)
                }
            }

            when {
                config.subject != null -> {
                    val totalAvailable = questionDao.getSubjectQuestionCount(config.examType, config.subject)
                    val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
                    val candidates = questionDao.getQuestionsBySubject(config.examType, config.subject, candidateCount)
                    val (qs, recycled) = pickWithSeenTracking(
                        context, "premium_subject_${config.examType}_${config.subject}", candidates, totalAvailable, config.totalQuestions
                    )
                    QuestionFetchResult(qs.padTo(config.totalQuestions), recycled)
                }
                else -> {
                    // Premium User Full Mock -> Weekly Seed Logic
                    val weekKey = getWeekKey()
                    val prefKey = "weekly_premium_mock_${config.examType}_$weekKey"
                    val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
                    val cachedIds = prefs.getString(prefKey, null)

                    if (cachedIds != null) {
                        val ids = cachedIds.split(",").mapNotNull { it.toIntOrNull() }
                        val qs = questionDao.getQuestionsByIds(ids)
                        if (qs.size >= config.totalQuestions * 0.8)
                            return@withContext QuestionFetchResult(qs.padTo(config.totalQuestions).shuffled())
                    }

                    val totalAvailable = questionDao.getExamQuestionCount(config.examType)
                    val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
                    val candidates = questionDao.getRandomQuestions(config.examType, candidateCount)
                    val (newQs, recycled) = pickWithSeenTracking(
                        context, "premium_full_${config.examType}", candidates, totalAvailable, config.totalQuestions
                    )
                    prefs.edit().putString(prefKey, newQs.joinToString(",") { it.id.toString() }).apply()
                    QuestionFetchResult(newQs.padTo(config.totalQuestions), recycled)
                }
            }
        }

    /**
     * Fills up to [n] questions from [candidates], preferring ones not already served
     * recently under [scopeKey]. Persists newly-served ids into that window and resets
     * it once [totalAvailable] distinct questions have all been shown, so a pool only
     * recirculates after being genuinely exhausted — never before.
     *
     * Returns the selection plus whether previously-seen (or simply too few) questions
     * had to be used, so callers can tell the user more content is coming instead of
     * silently repeating.
     */
    private fun pickWithSeenTracking(
        context: Context, scopeKey: String, candidates: List<Question>, totalAvailable: Int, n: Int
    ): Pair<List<Question>, Boolean> {
        val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
        val seenKey = "seen_ids_$scopeKey"
        var seenIds = (prefs.getString(seenKey, "") ?: "").split(",").mapNotNull { it.toIntOrNull() }.toSet()

        // If the whole pool was already shown, resetting makes it look "fresh" again below —
        // but the user is still about to see already-seen content, so remember that here.
        val poolJustExhausted = totalAvailable > 0 && seenIds.size >= totalAvailable
        if (poolJustExhausted) seenIds = emptySet()

        val distinct = candidates.distinctBy { it.id }
        val fresh = distinct.filter { it.id !in seenIds }
        val stale = distinct.filter { it.id in seenIds }
        val recycled = poolJustExhausted || fresh.size < n

        val selected = if (fresh.size >= n) fresh.shuffled().take(n)
            else (fresh + stale.shuffled()).take(n)

        val windowSize = totalAvailable.coerceAtLeast(n)
        val updatedSeen = (selected.map { it.id } + seenIds.toList()).distinct().take(windowSize)
        prefs.edit().putString(seenKey, updatedSeen.joinToString(",")).apply()

        return selected to recycled
    }

    private suspend fun fetchSimulationQuestions(context: Context, config: ExamConfig, isUnlocked: Boolean): QuestionFetchResult = withContext(Dispatchers.IO) {
        val weekKey = getWeekKey()
        val prefKey = "weekly_simulation_${config.examType}_${if (isUnlocked) "premium" else "free"}_$weekKey"
        val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
        val cachedIds = prefs.getString(prefKey, null)

        if (cachedIds != null) {
            val ids = cachedIds.split(",").mapNotNull { it.toIntOrNull() }
            val qs = questionDao.getQuestionsByIds(ids)
            if (qs.size >= config.totalQuestions * 0.8)
                return@withContext QuestionFetchResult(qs.padTo(config.totalQuestions).shuffled())
        }

        val scopeKey = "simulation_${config.examType}_${if (isUnlocked) "premium" else "free"}"
        val totalAvailable = if (isUnlocked) questionDao.getExamQuestionCount(config.examType)
                             else questionDao.getFreeExamQuestionCount(config.examType)
        val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
        val candidates = if (isUnlocked) {
            questionDao.getRandomQuestions(config.examType, candidateCount)
        } else {
            questionDao.getFreeQuestions(config.examType, candidateCount)
        }
        val (newQs, recycled) = pickWithSeenTracking(context, scopeKey, candidates, totalAvailable, config.totalQuestions)

        prefs.edit().putString(prefKey, newQs.joinToString(",") { it.id.toString() }).apply()
        QuestionFetchResult(newQs.padTo(config.totalQuestions), recycled)
    }

    private suspend fun fetchDailyQuizQuestions(context: Context, config: ExamConfig, isUnlocked: Boolean): QuestionFetchResult = withContext(Dispatchers.IO) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val prefKey = "daily_quiz_${config.examType}_$today"
        val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
        val cachedIds = prefs.getString(prefKey, null)

        if (cachedIds != null) {
            val ids = cachedIds.split(",").mapNotNull { it.toIntOrNull() }
            val qs = questionDao.getQuestionsByIds(ids)
            if (qs.size >= config.totalQuestions * 0.8) return@withContext QuestionFetchResult(qs.shuffled())
        }

        val recentPrefKey = "recent_daily_quiz_ids_${config.examType}"
        val recentIdsString = prefs.getString(recentPrefKey, "") ?: ""
        var recentIds = recentIdsString.split(",").mapNotNull { it.toIntOrNull() }.toSet()

        // Full-cycle exhaustion check: if all available questions have been seen, reset and re-circulate.
        // Remember that this happened — after resetting, the pool looks "fresh" again below, but the
        // user is still about to see already-seen content, so recycled must stay true regardless.
        val totalAvailable = if (isUnlocked) questionDao.getExamQuestionCount(config.examType)
                             else questionDao.getFreeExamQuestionCount(config.examType)
        val poolJustExhausted = totalAvailable > 0 && recentIds.size >= totalAvailable
        if (poolJustExhausted) {
            prefs.edit().remove(recentPrefKey).apply()
            recentIds = emptySet()
        }

        // Fetch a larger candidate pool to select from to avoid repeats
        val candidateCount = (config.totalQuestions * 5).coerceAtLeast(50)
        val candidates = if (isUnlocked) {
            questionDao.getRandomQuestions(config.examType, candidateCount)
        } else {
            questionDao.getFreeQuestions(config.examType, candidateCount)
        }

        val filtered = candidates.filter { it.id !in recentIds }
        val recycled = poolJustExhausted || filtered.size < config.totalQuestions
        val finalQuestions = when {
            filtered.size >= config.totalQuestions -> filtered.take(config.totalQuestions)
            filtered.isNotEmpty() -> {
                // Partial fresh set — pad with recycled questions to reach target count
                val seenInCandidates = candidates.filter { it.id in recentIds }
                (filtered + seenInCandidates).take(config.totalQuestions)
            }
            else -> {
                // All candidates recently seen — reset window and re-circulate
                prefs.edit().remove(recentPrefKey).apply()
                recentIds = emptySet()
                candidates.take(config.totalQuestions)
            }
        }

        // Save today's selection
        val todayIds = finalQuestions.map { it.id }
        prefs.edit().putString(prefKey, todayIds.joinToString(",")).apply()

        // Update recently used window; size tracks up to (totalAvailable - batchSize) so the
        // window naturally clears itself one full cycle before repeating questions
        val windowSize = (totalAvailable - config.totalQuestions).coerceAtLeast(50)
        val updatedRecentIds = (todayIds + recentIds.toList()).distinct().take(windowSize)
        prefs.edit().putString(recentPrefKey, updatedRecentIds.joinToString(",")).apply()

        QuestionFetchResult(finalQuestions.padTo(config.totalQuestions), recycled)
    }

    private suspend fun fetchAdaptiveQuestions(config: ExamConfig): Pair<List<Question>, Boolean> {
        val uid = currentUid()
        val allChapterQs = questionDao.getQuestionsByChapterOnce(config.examType, config.subject!!, config.chapter!!)
        if (allChapterQs.isEmpty()) return emptyList<Question>() to false

        val exposureMap = fetchExposureHistory(uid)
        
        val wrongIds = mutableListOf<Question>()
        val seenIds = mutableListOf<Question>()
        val unseenIds = mutableListOf<Question>()

        allChapterQs.forEach { q ->
            val exp = exposureMap[q.id.toString()]
            when {
                exp == null -> unseenIds.add(q)
                exp.correctCount < exp.seenCount -> wrongIds.add(q)
                else -> seenIds.add(q)
            }
        }

        val total = config.totalQuestions
        val wrongCount = (total * 0.4).toInt()
        val revisionCount = (total * 0.2).toInt()
        val unseenCount = total - wrongCount - revisionCount

        val result = mutableListOf<Question>()
        result.addAll(unseenIds.shuffled().take(unseenCount))
        result.addAll(wrongIds.shuffled().take(wrongCount))
        result.addAll(seenIds.shuffled().take(revisionCount))

        // Fill remaining slots from any unseen chapter questions
        if (result.size < total) {
            val remaining = allChapterQs.filter { it !in result }.shuffled()
            result.addAll(remaining.take(total - result.size))
        }

        // padTo now trims to distinct questions rather than looping through the pool,
        // so a chapter pool smaller than total simply yields a shorter, non-repeating test
        return result.padTo(total).shuffled() to (allChapterQs.size < total)
    }

    private suspend fun fetchExposureHistory(uid: String): Map<String, QuestionExposure> = withContext(Dispatchers.IO) {
        if (uid == "guest") return@withContext emptyMap()
        try {
            val task = FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("question_history")
                .get()
            
            val snapshot = Tasks.await(task)
            snapshot.documents.associate { doc ->
                doc.id to QuestionExposure(
                    questionId = doc.id.toIntOrNull() ?: 0,
                    seenCount = doc.getLong("seenCount")?.toInt() ?: 0,
                    correctCount = doc.getLong("correctCount")?.toInt() ?: 0,
                    avgTimeTakenMs = doc.getLong("avgTimeTakenMs") ?: 0L
                )
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getProductIdForSubject(subject: String): String =
        getProductIdForExamSubject("JEE", subject)

    fun getProductIdForExamSubject(exam: String, subject: String): String = when {
        subject == "Physics"   && exam == "NEET" -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_PHYSICS_PACK
        subject == "Physics"                     -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_PHYSICS_PACK
        subject == "Chemistry" && exam == "NEET" -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_CHEM_PACK
        subject == "Chemistry"                   -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_CHEM_PACK
        subject == "Maths"                       -> com.jeeneet.mocktest.data.model.IAPProducts.JEE_MATHS_PACK
        subject == "Biology"                     -> com.jeeneet.mocktest.data.model.IAPProducts.NEET_BIO_PACK
        else                                     -> ""
    }

    fun updateStreak(context: Context) {
        com.jeeneet.mocktest.utils.PrefManager.updateStreak(context)
    }

    fun getChapters(exam: String, subject: String): Flow<List<String>> =
        questionDao.getChapters(exam, subject)

    fun getQuestionCount(exam: String): Flow<Int> =
        questionDao.getQuestionCount(exam)

    // ─── Results ────────────────────────────────────────────────────────────

    suspend fun saveResult(context: Context, result: TestResult): Long = withContext(Dispatchers.IO) {
        val id = resultDao.insertResult(result)
        com.jeeneet.mocktest.utils.PrefManager.markPracticedToday(context)
        // Persist last score for comeback notifications + home screen banner
        val scorePercent = if (result.maxScore > 0f)
            ((result.score / result.maxScore) * 100f).toInt().coerceIn(0, 100)
        else 0
        com.jeeneet.mocktest.utils.PrefManager.saveLastTestScore(
            context, scorePercent, result.examType, result.subject
        )
        incrementDailyAttemptCount()
        updateLeaderboard(context, result)
        updateQuestionExposure(result)
        com.jeeneet.mocktest.utils.PrefManager.incrementTotalQuestionsSolved(context, result.attempted)
        id
    }

    private fun updateQuestionExposure(result: TestResult) {
        val uid = currentUid()
        if (uid == "guest") return

        val gson = Gson()
        val questions = gson.fromJson<List<Question>>(result.questionsJson, object : TypeToken<List<Question>>() {}.type)
        val answers = gson.fromJson<Map<String, Int?>>(result.answersJson, object : TypeToken<Map<String, Int?>>() {}.type)
        val times = gson.fromJson<Map<String, Long>>(result.questionTimesJson, object : TypeToken<Map<String, Long>>() {}.type)

        val batch = com.google.firebase.firestore.FirebaseFirestore.getInstance().batch()
        val historyRef = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("question_history")

        questions.forEachIndexed { idx, q ->
            val qId = q.id.toString()
            val isCorrect = answers[idx.toString()] == q.correctOptionIndex
            val timeTaken = times[idx.toString()] ?: 0L

            val docRef = historyRef.document(qId)
            batch.set(docRef, hashMapOf(
                "seenCount" to com.google.firebase.firestore.FieldValue.increment(1L),
                "correctCount" to com.google.firebase.firestore.FieldValue.increment(if (isCorrect) 1L else 0L),
                "lastSeenAt" to System.currentTimeMillis(),
                "avgTimeTakenMs" to timeTaken // Simplification: replacing instead of moving average for now
            ), com.google.firebase.firestore.SetOptions.merge())
        }
        batch.commit()
    }

    private fun getWeekKey(): String {
        // Use Locale.UK to get ISO 8601 weeks (Monday-start, 4-day minimum) consistently
        // across all device locales. YYYY = week-year, ww = week-of-week-year.
        val sdf = java.text.SimpleDateFormat("YYYY-'W'ww", java.util.Locale.UK)
        return sdf.format(java.util.Date())
    }

    private fun updateLeaderboard(context: Context, result: TestResult) {
        try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
            val weekKey = getWeekKey()
            val name = user.displayName?.ifEmpty { "Aspirant" } ?: "Aspirant"
            val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
            val scoreToAdd = maxOf(0f, result.score).toLong()
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("leaderboard").document(weekKey)
                .collection("scores").document(user.uid)
                .set(hashMapOf(
                    "displayName"    to name,
                    "initial"        to initial,
                    "totalScore"     to com.google.firebase.firestore.FieldValue.increment(scoreToAdd),
                    "testsCompleted" to com.google.firebase.firestore.FieldValue.increment(1L),
                    "streak"         to com.jeeneet.mocktest.utils.PrefManager.getStreak(context),
                    "updatedAt"      to System.currentTimeMillis()
                ), com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener { e ->
                    android.util.Log.e("Leaderboard", "Score write failed for week $weekKey", e)
                }
        } catch (e: Exception) {
            android.util.Log.e("Leaderboard", "updateLeaderboard error", e)
        }
    }

    private fun incrementDailyAttemptCount() {
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .document("dailyStats/$today")
                .set(
                    hashMapOf("attemptCount" to com.google.firebase.firestore.FieldValue.increment(1L)),
                    com.google.firebase.firestore.SetOptions.merge()
                )
        } catch (_: Exception) {}
    }

    private fun currentUid(): String =
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"

    fun getAllResults(): Flow<List<TestResult>> = resultDao.getAllResults(currentUid())

    fun getResultsByExam(exam: String): Flow<List<TestResult>> =
        resultDao.getResultsByExam(currentUid(), exam)

    fun getAverageScore(exam: String): Flow<Float?> =
        resultDao.getAverageScorePercent(currentUid(), exam)

    fun getTotalTestsTaken(): Flow<Int> = resultDao.getTotalTestsTaken(currentUid())

    // ─── Smart Practice Engine ───────────────────────────────────────────────
    // Builds a weighted question set: 60% weak topics, 30% medium, 10% strong

    suspend fun buildSmartPracticeQuestions(
        context: Context, exam: String, totalQuestions: Int = 30
    ): List<Question> = withContext(Dispatchers.IO) {
        val uid = currentUid()
        val allResults = resultDao.getAllResultsOnce(uid)
        if (allResults.isEmpty()) return@withContext emptyList()

        val gson = Gson()
        val questionListType = object : TypeToken<List<Question>>() {}.type
        val answerMapType    = object : TypeToken<Map<String, Int?>>() {}.type

        data class ChapterPerf(val subject: String, var correct: Int = 0, var wrong: Int = 0) {
            val accuracy get() = if (correct + wrong == 0) 0f else correct.toFloat() / (correct + wrong)
            val attempted get() = correct + wrong
        }
        val chapterMap = mutableMapOf<String, ChapterPerf>()

        allResults.filter { it.examType == exam }.forEach { r ->
            if (r.questionsJson.isEmpty() || r.answersJson.isEmpty()) return@forEach
            val questions = runCatching {
                gson.fromJson<List<Question>>(r.questionsJson, questionListType)
            }.getOrNull() ?: return@forEach
            val answers = runCatching {
                gson.fromJson<Map<String, Int?>>(r.answersJson, answerMapType)
            }.getOrNull() ?: return@forEach
            questions.forEachIndexed { idx, q ->
                val key  = "${q.subject}::${q.chapter}"
                val perf = chapterMap.getOrPut(key) { ChapterPerf(q.subject) }
                when (answers[idx.toString()]) {
                    null                  -> {}
                    q.correctOptionIndex  -> perf.correct++
                    else                  -> perf.wrong++
                }
            }
        }

        val qualified = chapterMap.filter { it.value.attempted >= 2 }
        val weak   = qualified.filter { it.value.accuracy < 0.40f }
        val medium = qualified.filter { it.value.accuracy in 0.40f..0.70f }
        val strong = qualified.filter { it.value.accuracy > 0.70f }

        val weakCount   = (totalQuestions * 0.60f).toInt()
        val mediumCount = (totalQuestions * 0.30f).toInt()
        val strongCount = totalQuestions - weakCount - mediumCount

        // Filter out questions seen in the last 7 days so Smart Practice always feels fresh
        val exposure = fetchExposureHistory(uid)
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val recentIds = exposure
            .filter { (_, e) -> e.lastSeenAt > sevenDaysAgo }
            .keys.mapNotNull { it.toIntOrNull() }
            .toSet()

        suspend fun fetchFromTier(tier: Map<String, ChapterPerf>, count: Int): List<Question> {
            if (tier.isEmpty() || count <= 0) return emptyList()
            val result = mutableListOf<Question>()
            val grouped = tier.entries.groupBy { it.value.subject }
            val perSubject = (count / grouped.size).coerceAtLeast(1)
            grouped.forEach { (subject, entries) ->
                val chapters = entries.mapNotNull { it.key.split("::", limit = 2).getOrNull(1) }
                if (chapters.isNotEmpty()) {
                    // Fetch 3x to have room after filtering recently-seen questions
                    val fetched = questionDao.getQuestionsByChapters(exam, subject, chapters, perSubject * 3)
                    val fresh = fetched.filter { it.id !in recentIds }
                    result.addAll(if (fresh.size >= perSubject) fresh.take(perSubject) else fetched.take(perSubject))
                }
            }
            return result
        }

        val picked = mutableListOf<Question>()
        picked.addAll(fetchFromTier(weak, weakCount))
        picked.addAll(fetchFromTier(medium, mediumCount))
        picked.addAll(fetchFromTier(strong, strongCount))

        if (picked.size < totalQuestions / 2)
            picked.addAll(questionDao.getRandomQuestions(exam, totalQuestions))

        picked.distinctBy { it.id }.shuffled().padTo(totalQuestions)
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Deduplicates [this] by question id, capped at [n]. Previously this re-circulated
     * the pool to always reach [n] entries, which inserted duplicate questions into a
     * single test whenever the underlying pool was smaller than [n]. A shorter test with
     * only distinct questions is strictly better than a full-length test with repeats.
     */
    private fun List<Question>.padTo(n: Int): List<Question> =
        distinctBy { it.id }.take(n)

    // ─── Seed sample questions (called once on first launch) ────────────────

    suspend fun seedSampleQuestions() = withContext(Dispatchers.IO) {
        questionDao.insertQuestions(sampleQuestions())
    }

    private fun sampleQuestions(): List<Question> = physicsQuestions() + chemistryQuestions() + mathsQuestions() + biologyQuestions()

    // ── JEE + NEET Physics ───────────────────────────────────────────────────

    private fun physicsQuestions(): List<Question> = listOf(

        // Mathematics In Physics
        Question(examType="JEE", subject="Physics", chapter="Mathematics In Physics", difficulty="Easy", year=2022,
            questionText="If a physical quantity X depends on mass M, length L and time T as X = M^a L^b T^c, and dimensionally X = [ML²T⁻²], what are a, b, c?",
            options=listOf("1, 2, -2", "2, 1, -2", "1, -2, 2", "2, -1, 2"), correctOptionIndex=0,
            explanation="Matching powers: a=1, b=2, c=-2. This is the dimension of energy."),
        Question(examType="JEE", subject="Physics", chapter="Mathematics In Physics", difficulty="Medium", year=2023,
            questionText="The slope of a velocity-time graph gives:",
            options=listOf("Displacement", "Speed", "Acceleration", "Momentum"), correctOptionIndex=2,
            explanation="slope = Δv/Δt = acceleration by definition."),
        Question(examType="NEET", subject="Physics", chapter="Mathematics In Physics", difficulty="Easy", year=2022,
            questionText="Area under a velocity-time graph represents:",
            options=listOf("Acceleration", "Force", "Displacement", "Power"), correctOptionIndex=2,
            explanation="Area = v × t = displacement (for uniform motion); generally equals displacement for any motion."),
        Question(examType="NEET", subject="Physics", chapter="Mathematics In Physics", difficulty="Medium", year=2023,
            questionText="Which of the following is a dimensionless quantity?",
            options=listOf("Strain", "Stress", "Pressure", "Force"), correctOptionIndex=0,
            explanation="Strain = change in length / original length — both have dimension [L], so ratio is dimensionless."),

        // Units, Dimensions And Measurement
        Question(examType="JEE", subject="Physics", chapter="Units, Dimensions And Measurement", difficulty="Medium", year=2023,
            questionText="The dimensional formula of universal gravitational constant G is:",
            options=listOf("[M⁻¹L³T⁻²]", "[ML³T⁻²]", "[M⁻¹L³T²]", "[ML⁻¹T⁻²]"), correctOptionIndex=0,
            explanation="From F = Gm₁m₂/r², G = Fr²/m² → [MLT⁻²][L²]/[M²] = [M⁻¹L³T⁻²]."),
        Question(examType="JEE", subject="Physics", chapter="Units, Dimensions And Measurement", difficulty="Hard", year=2022,
            questionText="A student measures the radius of a circular plate as 1.06 cm using a vernier calliper with least count 0.01 cm. The percentage error in the area is approximately:",
            options=listOf("0.94%", "1.88%", "0.5%", "2.5%"), correctOptionIndex=1,
            explanation="Error in area = 2 × (error in radius / radius) × 100 = 2 × (0.01/1.06) × 100 ≈ 1.88%."),
        Question(examType="NEET", subject="Physics", chapter="Units, Dimensions And Measurement", difficulty="Easy", year=2022,
            questionText="The SI unit of luminous intensity is:",
            options=listOf("Lumen", "Candela", "Lux", "Watt"), correctOptionIndex=1,
            explanation="Candela (cd) is the SI base unit of luminous intensity."),
        Question(examType="NEET", subject="Physics", chapter="Units, Dimensions And Measurement", difficulty="Medium", year=2023,
            questionText="Which pair has the same dimensional formula?",
            options=listOf("Work and Power", "Torque and Energy", "Momentum and Impulse", "Both B and C"), correctOptionIndex=3,
            explanation="Torque [ML²T⁻²] = Energy [ML²T⁻²]; Momentum [MLT⁻¹] = Impulse [MLT⁻¹]. Both pairs match."),

        // Motion In One Dimension
        Question(examType="JEE", subject="Physics", chapter="Motion In One Dimension", difficulty="Medium", year=2023,
            questionText="A particle moves with uniform acceleration. Its velocity changes from 20 m/s to 60 m/s in 4 s. The acceleration is:",
            options=listOf("5 m/s²", "10 m/s²", "15 m/s²", "20 m/s²"), correctOptionIndex=1,
            explanation="a = (v - u)/t = (60 - 20)/4 = 10 m/s²."),
        Question(examType="JEE", subject="Physics", chapter="Motion In One Dimension", difficulty="Hard", year=2022,
            questionText="A ball thrown vertically upward with 20 m/s from the top of a 25 m building. Time to reach ground (g=10 m/s²):",
            options=listOf("2 s", "4 s", "5 s", "6 s"), correctOptionIndex=2,
            explanation="Using -25 = 20t - 5t², → t² - 4t - 5 = 0 → t = 5 s."),
        Question(examType="NEET", subject="Physics", chapter="Motion In One Dimension", difficulty="Medium", year=2023,
            questionText="A body starts from rest with acceleration 2 m/s². Distance covered in the 5th second is:",
            options=listOf("8 m", "9 m", "10 m", "12 m"), correctOptionIndex=1,
            explanation="Sₙ = u + a(n - ½) = 0 + 2(5 - 0.5) = 9 m."),
        Question(examType="NEET", subject="Physics", chapter="Motion In One Dimension", difficulty="Easy", year=2022,
            questionText="A car decelerates uniformly from 30 m/s to rest in 5 s. Deceleration is:",
            options=listOf("3 m/s²", "6 m/s²", "10 m/s²", "15 m/s²"), correctOptionIndex=1,
            explanation="a = (0 - 30)/5 = -6 m/s², magnitude 6 m/s²."),

        // Motion In Two Dimension
        Question(examType="JEE", subject="Physics", chapter="Motion In Two Dimension", difficulty="Medium", year=2023,
            questionText="A projectile is launched at 45° with speed 20 m/s. Maximum height reached (g=10 m/s²):",
            options=listOf("5 m", "10 m", "15 m", "20 m"), correctOptionIndex=1,
            explanation="H = u²sin²θ/(2g) = 400×0.5/20 = 10 m."),
        Question(examType="JEE", subject="Physics", chapter="Motion In Two Dimension", difficulty="Hard", year=2022,
            questionText="Two vectors A=3î+4ĵ and B=4î-3ĵ. The angle between them is:",
            options=listOf("0°", "45°", "90°", "180°"), correctOptionIndex=2,
            explanation="A·B = 3×4 + 4×(-3) = 12 - 12 = 0, so vectors are perpendicular (90°)."),
        Question(examType="NEET", subject="Physics", chapter="Motion In Two Dimension", difficulty="Medium", year=2022,
            questionText="Range of a projectile is maximum when angle of projection is:",
            options=listOf("30°", "45°", "60°", "90°"), correctOptionIndex=1,
            explanation="R = u²sin2θ/g is maximum when sin2θ=1, i.e., 2θ=90°, θ=45°."),
        Question(examType="NEET", subject="Physics", chapter="Motion In Two Dimension", difficulty="Easy", year=2023,
            questionText="A particle moves in a circle of radius 2 m with speed 4 m/s. Centripetal acceleration is:",
            options=listOf("2 m/s²", "4 m/s²", "8 m/s²", "16 m/s²"), correctOptionIndex=2,
            explanation="a = v²/r = 16/2 = 8 m/s²."),

        // Newton's Laws Of Motion
        Question(examType="JEE", subject="Physics", chapter="Newton's Laws Of Motion", difficulty="Medium", year=2023,
            questionText="A block of mass 5 kg on a rough surface, μ=0.4. Minimum force to move it (g=10):",
            options=listOf("10 N", "15 N", "20 N", "25 N"), correctOptionIndex=2,
            explanation="F = μmg = 0.4 × 5 × 10 = 20 N."),
        Question(examType="JEE", subject="Physics", chapter="Newton's Laws Of Motion", difficulty="Medium", year=2022,
            questionText="A 3 kg and 2 kg block are connected by a string over a frictionless pulley (Atwood). Acceleration is:",
            options=listOf("1 m/s²", "2 m/s²", "5 m/s²", "10 m/s²"), correctOptionIndex=1,
            explanation="a = (m₁-m₂)g/(m₁+m₂) = (3-2)×10/5 = 2 m/s²."),
        Question(examType="NEET", subject="Physics", chapter="Newton's Laws Of Motion", difficulty="Medium", year=2022,
            questionText="A 10 kg object is pulled by 50 N on a frictionless surface. Its acceleration is:",
            options=listOf("0.5 m/s²", "5 m/s²", "50 m/s²", "500 m/s²"), correctOptionIndex=1,
            explanation="F = ma → a = F/m = 50/10 = 5 m/s²."),
        Question(examType="NEET", subject="Physics", chapter="Newton's Laws Of Motion", difficulty="Easy", year=2023,
            questionText="Newton's third law states that action and reaction forces are:",
            options=listOf("Equal and in same direction", "Equal and opposite, on same body", "Equal and opposite, on different bodies", "Unequal and opposite"), correctOptionIndex=2,
            explanation="Action-reaction pairs are equal in magnitude, opposite in direction, and act on different bodies."),

        // Friction
        Question(examType="JEE", subject="Physics", chapter="Friction", difficulty="Medium", year=2023,
            questionText="A block slides down an incline of 30° with μk=0.2. Acceleration (g=10):",
            options=listOf("3.27 m/s²", "5 m/s²", "6.73 m/s²", "8.27 m/s²"), correctOptionIndex=0,
            explanation="a = g(sinθ - μk cosθ) = 10(0.5 - 0.2×0.866) = 10(0.5-0.173) ≈ 3.27 m/s²."),
        Question(examType="JEE", subject="Physics", chapter="Friction", difficulty="Easy", year=2022,
            questionText="Coefficient of static friction is always __ coefficient of kinetic friction.",
            options=listOf("Less than", "Equal to", "Greater than or equal to", "Independent of"), correctOptionIndex=2,
            explanation="Static friction ≥ kinetic friction; it's harder to start motion than maintain it."),
        Question(examType="NEET", subject="Physics", chapter="Friction", difficulty="Medium", year=2022,
            questionText="A 10 kg block on a surface with μs=0.5. Maximum static friction force (g=10):",
            options=listOf("25 N", "50 N", "75 N", "100 N"), correctOptionIndex=1,
            explanation="fs_max = μs × N = 0.5 × 10 × 10 = 50 N."),
        Question(examType="NEET", subject="Physics", chapter="Friction", difficulty="Easy", year=2023,
            questionText="Friction is a __ force.",
            options=listOf("Conservative", "Non-conservative", "Fundamental", "Central"), correctOptionIndex=1,
            explanation="Friction dissipates energy as heat — it is a non-conservative force."),

        // Work, Energy, Power And Collision
        Question(examType="JEE", subject="Physics", chapter="Work, Energy, Power And Collision", difficulty="Medium", year=2023,
            questionText="A 2 kg ball moving at 3 m/s collides elastically with an equal stationary ball. After collision, first ball's speed is:",
            options=listOf("0 m/s", "1.5 m/s", "3 m/s", "6 m/s"), correctOptionIndex=0,
            explanation="In a perfectly elastic collision between equal masses, the first ball stops and the second moves with the initial speed."),
        Question(examType="JEE", subject="Physics", chapter="Work, Energy, Power And Collision", difficulty="Medium", year=2022,
            questionText="A spring of constant k=200 N/m is compressed by 0.1 m. Elastic potential energy stored:",
            options=listOf("0.5 J", "1 J", "2 J", "10 J"), correctOptionIndex=1,
            explanation="PE = ½kx² = ½ × 200 × 0.01 = 1 J."),
        Question(examType="NEET", subject="Physics", chapter="Work, Energy, Power And Collision", difficulty="Easy", year=2022,
            questionText="Work done by a force F on displacement d at angle θ between them:",
            options=listOf("Fd sinθ", "Fd cosθ", "Fd tanθ", "F/d"), correctOptionIndex=1,
            explanation="W = F·d·cosθ (dot product of force and displacement vectors)."),
        Question(examType="NEET", subject="Physics", chapter="Work, Energy, Power And Collision", difficulty="Medium", year=2023,
            questionText="A body of mass 2 kg at height 10 m has potential energy (g=10 m/s²):",
            options=listOf("10 J", "20 J", "100 J", "200 J"), correctOptionIndex=3,
            explanation="PE = mgh = 2 × 10 × 10 = 200 J."),

        // Rotational Motion
        Question(examType="JEE", subject="Physics", chapter="Rotational Motion", difficulty="Hard", year=2023,
            questionText="A solid sphere and a hollow sphere of equal mass and radius roll down an incline from rest. Which reaches the bottom first?",
            options=listOf("Solid sphere", "Hollow sphere", "Both together", "Depends on incline angle"), correctOptionIndex=0,
            explanation="Solid sphere (I=2/5 mr²) has less moment of inertia than hollow sphere (I=2/3 mr²), giving it greater acceleration."),
        Question(examType="JEE", subject="Physics", chapter="Rotational Motion", difficulty="Medium", year=2022,
            questionText="A disc of radius 0.5 m rotates at 120 rpm. Linear velocity of a point on rim:",
            options=listOf("π m/s", "2π m/s", "4π m/s", "π/2 m/s"), correctOptionIndex=1,
            explanation="ω = 2πn = 2π×2 = 4π rad/s. v = ωr = 4π × 0.5 = 2π m/s."),
        Question(examType="NEET", subject="Physics", chapter="Rotational Motion", difficulty="Medium", year=2022,
            questionText="The moment of inertia of a thin uniform rod of mass M and length L about its centre is:",
            options=listOf("ML²/12", "ML²/6", "ML²/3", "ML²"), correctOptionIndex=0,
            explanation="I = ML²/12 for a thin rod about its centre of mass."),
        Question(examType="NEET", subject="Physics", chapter="Rotational Motion", difficulty="Easy", year=2023,
            questionText="Torque is defined as:",
            options=listOf("Force × mass", "Force × velocity", "Force × perpendicular distance", "Mass × acceleration"), correctOptionIndex=2,
            explanation="τ = r × F; torque = force multiplied by the perpendicular distance from the pivot."),

        // Gravitation
        Question(examType="JEE", subject="Physics", chapter="Gravitation", difficulty="Medium", year=2023,
            questionText="Escape velocity from Earth's surface (R=6400 km, g=9.8 m/s²) is approximately:",
            options=listOf("7.9 km/s", "11.2 km/s", "15 km/s", "3 km/s"), correctOptionIndex=1,
            explanation="ve = √(2gR) = √(2 × 9.8 × 6.4×10⁶) ≈ 11,200 m/s = 11.2 km/s."),
        Question(examType="JEE", subject="Physics", chapter="Gravitation", difficulty="Medium", year=2022,
            questionText="A satellite orbits at height h above Earth. Its orbital velocity is proportional to:",
            options=listOf("(R+h)", "1/(R+h)", "√(1/(R+h))", "√(R+h)"), correctOptionIndex=2,
            explanation="v = √(GM/(R+h)) — orbital velocity ∝ 1/√(R+h)."),
        Question(examType="NEET", subject="Physics", chapter="Gravitation", difficulty="Easy", year=2022,
            questionText="At the centre of the Earth, the value of g is:",
            options=listOf("Maximum", "9.8 m/s²", "Zero", "Infinite"), correctOptionIndex=2,
            explanation="At Earth's centre, gravitational pull from all sides cancels out, so g = 0."),
        Question(examType="NEET", subject="Physics", chapter="Gravitation", difficulty="Medium", year=2023,
            questionText="Gravitational force between two bodies is doubled if the distance between them is:",
            options=listOf("Doubled", "Halved", "Reduced to 1/√2", "Quadrupled"), correctOptionIndex=2,
            explanation="F ∝ 1/r². For F to double, r² must halve → r = r₀/√2."),

        // Simple Harmonic Motion
        Question(examType="JEE", subject="Physics", chapter="Simple Harmonic Motion", difficulty="Medium", year=2023,
            questionText="A spring-mass system (k=100 N/m, m=0.25 kg) executes SHM. Its time period is:",
            options=listOf("π/10 s", "π/5 s", "π s", "2π s"), correctOptionIndex=1,
            explanation="T = 2π√(m/k) = 2π√(0.25/100) = 2π×0.05 = π/10 s. Wait: = 2π/√400 = 2π/20 = π/10 s."),
        Question(examType="JEE", subject="Physics", chapter="Simple Harmonic Motion", difficulty="Hard", year=2022,
            questionText="In SHM, when the displacement is half of amplitude, ratio of KE to PE is:",
            options=listOf("1:3", "3:1", "1:1", "2:1"), correctOptionIndex=1,
            explanation="KE = ½mω²(A²-x²), PE = ½mω²x². At x=A/2: KE/PE = (A²-A²/4)/(A²/4) = (3/4)/(1/4) = 3:1."),
        Question(examType="NEET", subject="Physics", chapter="Simple Harmonic Motion", difficulty="Easy", year=2022,
            questionText="In simple harmonic motion, the restoring force is:",
            options=listOf("Constant", "Proportional to displacement", "Proportional to velocity", "Independent of displacement"), correctOptionIndex=1,
            explanation="F = -kx; restoring force ∝ displacement and directed toward equilibrium."),
        Question(examType="NEET", subject="Physics", chapter="Simple Harmonic Motion", difficulty="Medium", year=2023,
            questionText="A pendulum of length 1 m on Earth has period T. On Moon (g_moon = g_earth/6), its period is:",
            options=listOf("T/6", "T/√6", "T√6", "6T"), correctOptionIndex=2,
            explanation="T = 2π√(L/g). T_moon/T_earth = √(g_earth/g_moon) = √6."),

        // Elasticity
        Question(examType="JEE", subject="Physics", chapter="Elasticity", difficulty="Medium", year=2023,
            questionText="A wire of Young's modulus Y, length L, cross-section A is stretched by force F. Extension is:",
            options=listOf("FL/AY", "FAY/L", "FY/AL", "AL/FY"), correctOptionIndex=0,
            explanation="From Y = (F/A)/(ΔL/L), extension ΔL = FL/(AY)."),
        Question(examType="JEE", subject="Physics", chapter="Elasticity", difficulty="Easy", year=2022,
            questionText="Beyond the elastic limit, a material shows:",
            options=listOf("Elastic behavior", "Plastic deformation", "Fracture immediately", "No change"), correctOptionIndex=1,
            explanation="Beyond elastic limit, deformation is permanent (plastic deformation)."),
        Question(examType="NEET", subject="Physics", chapter="Elasticity", difficulty="Medium", year=2022,
            questionText="Bulk modulus is defined as the ratio of:",
            options=listOf("Longitudinal stress to strain", "Shear stress to strain", "Volumetric stress to volumetric strain", "Tensile stress to lateral strain"), correctOptionIndex=2,
            explanation="Bulk modulus K = -P/(ΔV/V) = volumetric stress / volumetric strain."),
        Question(examType="NEET", subject="Physics", chapter="Elasticity", difficulty="Easy", year=2023,
            questionText="The SI unit of Young's modulus is:",
            options=listOf("N", "N/m", "N/m²", "N·m"), correctOptionIndex=2,
            explanation="Young's modulus = stress/strain = (N/m²)/dimensionless = N/m² (Pascal)."),

        // Fluid Mechanics
        Question(examType="JEE", subject="Physics", chapter="Fluid Mechanics", difficulty="Medium", year=2023,
            questionText="A body floats in a liquid with 60% of its volume submerged. The density of the body relative to liquid is:",
            options=listOf("0.4", "0.6", "1.0", "1.6"), correctOptionIndex=1,
            explanation="For floating: ρ_body × V × g = ρ_liquid × 0.6V × g → ρ_body/ρ_liquid = 0.6."),
        Question(examType="JEE", subject="Physics", chapter="Fluid Mechanics", difficulty="Medium", year=2022,
            questionText="By Bernoulli's theorem, in a flowing fluid, where velocity is high, pressure is:",
            options=listOf("High", "Low", "Zero", "Independent of velocity"), correctOptionIndex=1,
            explanation="Bernoulli's principle: P + ½ρv² = constant → high velocity means lower pressure."),
        Question(examType="NEET", subject="Physics", chapter="Fluid Mechanics", difficulty="Easy", year=2022,
            questionText="Archimedes' principle states that a body immersed in fluid experiences a buoyant force equal to:",
            options=listOf("Weight of body", "Weight of fluid displaced", "Volume of body", "Density of fluid"), correctOptionIndex=1,
            explanation="Buoyant force = weight of fluid displaced by the body."),
        Question(examType="NEET", subject="Physics", chapter="Fluid Mechanics", difficulty="Medium", year=2023,
            questionText="Pascal's law states that pressure applied to an enclosed fluid is transmitted:",
            options=listOf("Only upward", "Only downward", "Equally in all directions", "In the direction of gravity"), correctOptionIndex=2,
            explanation="Pascal's law: pressure in a confined fluid is transmitted equally in all directions."),

        // Thermal Physics
        Question(examType="JEE", subject="Physics", chapter="Thermal Physics", difficulty="Medium", year=2023,
            questionText="A steel rod of length 1 m, α=12×10⁻⁶/°C is heated by 50°C. Its increase in length:",
            options=listOf("0.06 mm", "0.6 mm", "6 mm", "60 mm"), correctOptionIndex=1,
            explanation="ΔL = LαΔT = 1 × 12×10⁻⁶ × 50 = 600×10⁻⁶ m = 0.6 mm."),
        Question(examType="JEE", subject="Physics", chapter="Thermal Physics", difficulty="Medium", year=2022,
            questionText="Heat transferred by radiation depends on temperature as:",
            options=listOf("T", "T²", "T³", "T⁴"), correctOptionIndex=3,
            explanation="Stefan's law: P = εσAT⁴, radiated power ∝ T⁴."),
        Question(examType="NEET", subject="Physics", chapter="Thermal Physics", difficulty="Easy", year=2022,
            questionText="The temperature at which Celsius and Fahrenheit scales coincide is:",
            options=listOf("-40°", "0°", "32°", "100°"), correctOptionIndex=0,
            explanation="Setting °C = °F in F = 9C/5 + 32 gives C = -40."),
        Question(examType="NEET", subject="Physics", chapter="Thermal Physics", difficulty="Medium", year=2023,
            questionText="Which mode of heat transfer does not require a medium?",
            options=listOf("Conduction", "Convection", "Radiation", "All require medium"), correctOptionIndex=2,
            explanation="Radiation travels as electromagnetic waves and requires no medium."),

        // Kinetic Theory Of Gases
        Question(examType="JEE", subject="Physics", chapter="Kinetic Theory Of Gases", difficulty="Medium", year=2023,
            questionText="RMS speed of gas molecules is proportional to:",
            options=listOf("√T", "T", "T²", "1/√T"), correctOptionIndex=0,
            explanation="v_rms = √(3RT/M) ∝ √T."),
        Question(examType="JEE", subject="Physics", chapter="Kinetic Theory Of Gases", difficulty="Hard", year=2022,
            questionText="The ratio of specific heats Cp/Cv for a diatomic ideal gas is:",
            options=listOf("1.4", "1.67", "1.0", "1.2"), correctOptionIndex=0,
            explanation="Diatomic gas: Cv=5R/2, Cp=7R/2. γ = Cp/Cv = 7/5 = 1.4."),
        Question(examType="NEET", subject="Physics", chapter="Kinetic Theory Of Gases", difficulty="Easy", year=2022,
            questionText="According to kinetic theory, temperature of a gas is proportional to:",
            options=listOf("Average KE of molecules", "Average PE of molecules", "Total energy", "Pressure"), correctOptionIndex=0,
            explanation="T ∝ average translational KE of gas molecules."),
        Question(examType="NEET", subject="Physics", chapter="Kinetic Theory Of Gases", difficulty="Medium", year=2023,
            questionText="For an ideal gas, PV = nRT. At constant T, if V doubles, P becomes:",
            options=listOf("2P", "4P", "P/2", "P/4"), correctOptionIndex=2,
            explanation="Boyle's law: PV = constant at constant T → P₂ = P₁V₁/V₂ = P/2."),

        // Thermodynamics
        Question(examType="JEE", subject="Physics", chapter="Thermodynamics", difficulty="Hard", year=2022,
            questionText="An ideal gas undergoes isothermal expansion. During this process:",
            options=listOf("Internal energy increases", "Temperature increases", "Work is done by the gas", "Heat is released by the gas"), correctOptionIndex=2,
            explanation="Isothermal: ΔU=0, Q=W. Since gas expands, W>0, so heat is absorbed."),
        Question(examType="JEE", subject="Physics", chapter="Thermodynamics", difficulty="Medium", year=2023,
            questionText="Efficiency of a Carnot engine operating between 227°C and 27°C is:",
            options=listOf("25%", "40%", "50%", "75%"), correctOptionIndex=2,
            explanation="η = 1 - T_L/T_H = 1 - 300/500 = 0.40 = 40%. (T in Kelvin: 500 K and 300 K.)"),
        Question(examType="NEET", subject="Physics", chapter="Thermodynamics", difficulty="Medium", year=2023,
            questionText="In an adiabatic process:",
            options=listOf("Temperature remains constant", "Pressure remains constant", "No heat exchange with surroundings", "Volume remains constant"), correctOptionIndex=2,
            explanation="Adiabatic: Q = 0; no heat exchange. Work done changes internal energy."),
        Question(examType="NEET", subject="Physics", chapter="Thermodynamics", difficulty="Easy", year=2022,
            questionText="First law of thermodynamics is a statement of conservation of:",
            options=listOf("Momentum", "Energy", "Charge", "Mass"), correctOptionIndex=1,
            explanation="ΔU = Q - W is the first law; it expresses conservation of energy."),

        // Wave Motion
        Question(examType="JEE", subject="Physics", chapter="Wave Motion", difficulty="Medium", year=2023,
            questionText="Frequency of a wave is 500 Hz and speed is 340 m/s. Wavelength is:",
            options=listOf("0.34 m", "0.68 m", "1.7 m", "3.4 m"), correctOptionIndex=1,
            explanation="λ = v/f = 340/500 = 0.68 m."),
        Question(examType="JEE", subject="Physics", chapter="Wave Motion", difficulty="Medium", year=2022,
            questionText="The Doppler effect relates to change in observed __ when source or observer moves:",
            options=listOf("Amplitude", "Speed", "Frequency", "Wavelength only"), correctOptionIndex=2,
            explanation="Doppler effect causes a change in the observed frequency due to relative motion."),
        Question(examType="NEET", subject="Physics", chapter="Wave Motion", difficulty="Easy", year=2022,
            questionText="Sound travels fastest in:",
            options=listOf("Vacuum", "Air", "Water", "Steel"), correctOptionIndex=3,
            explanation="Speed of sound increases with density and elasticity; it is fastest in solids like steel (~5000 m/s)."),
        Question(examType="NEET", subject="Physics", chapter="Wave Motion", difficulty="Medium", year=2023,
            questionText="In a stationary wave, nodes are points of:",
            options=listOf("Maximum displacement", "Zero displacement", "Maximum pressure", "Minimum pressure"), correctOptionIndex=1,
            explanation="Nodes are points that remain at rest (zero displacement) in a stationary wave."),

        // Electrostatics
        Question(examType="JEE", subject="Physics", chapter="Electrostatics", difficulty="Medium", year=2023,
            questionText="Two charges 2 μC and 8 μC are 0.1 m apart. Force between them (k=9×10⁹):",
            options=listOf("1.44 N", "14.4 N", "0.144 N", "144 N"), correctOptionIndex=0,
            explanation="F = kq₁q₂/r² = 9×10⁹×2×10⁻⁶×8×10⁻⁶/0.01 = 9×10⁹×16×10⁻¹²/0.01 = 1.44 N."),
        Question(examType="JEE", subject="Physics", chapter="Electrostatics", difficulty="Hard", year=2022,
            questionText="A parallel plate capacitor has capacitance C. On inserting a dielectric (k=3), new capacitance is:",
            options=listOf("C/3", "C", "3C", "9C"), correctOptionIndex=2,
            explanation="C' = kC = 3C when dielectric constant k=3 is inserted."),
        Question(examType="NEET", subject="Physics", chapter="Electrostatics", difficulty="Easy", year=2022,
            questionText="Electric field lines originate from:",
            options=listOf("Negative charge", "Neutral point", "Positive charge", "Both charges"), correctOptionIndex=2,
            explanation="Electric field lines start from positive charges and end at negative charges."),
        Question(examType="NEET", subject="Physics", chapter="Electrostatics", difficulty="Medium", year=2023,
            questionText="Work done in moving a charge Q through potential difference V is:",
            options=listOf("Q/V", "V/Q", "QV", "Q²V"), correctOptionIndex=2,
            explanation="W = QV; work = charge × potential difference."),

        // Current Electricity
        Question(examType="JEE", subject="Physics", chapter="Current Electricity", difficulty="Medium", year=2023,
            questionText="Three resistors of 2Ω each are connected in parallel. Equivalent resistance:",
            options=listOf("6 Ω", "2/3 Ω", "3 Ω", "1/6 Ω"), correctOptionIndex=1,
            explanation="1/R = 1/2 + 1/2 + 1/2 = 3/2, R = 2/3 Ω."),
        Question(examType="JEE", subject="Physics", chapter="Current Electricity", difficulty="Hard", year=2022,
            questionText="A Wheatstone bridge is balanced when P/Q = R/S. If P=10Ω, Q=5Ω, R=20Ω, then S=",
            options=listOf("5 Ω", "10 Ω", "20 Ω", "40 Ω"), correctOptionIndex=1,
            explanation="S = Q×R/P = 5×20/10 = 10 Ω."),
        Question(examType="NEET", subject="Physics", chapter="Current Electricity", difficulty="Easy", year=2022,
            questionText="Ohm's law states V = IR. If V=12V and R=4Ω, current I is:",
            options=listOf("48 A", "0.33 A", "3 A", "8 A"), correctOptionIndex=2,
            explanation="I = V/R = 12/4 = 3 A."),
        Question(examType="NEET", subject="Physics", chapter="Current Electricity", difficulty="Medium", year=2023,
            questionText="Power dissipated in a resistor R carrying current I is:",
            options=listOf("IR", "I²R", "I/R", "IR²"), correctOptionIndex=1,
            explanation="P = I²R (also written as V²/R or VI)."),

        // Magnetic Effect Of Current
        Question(examType="JEE", subject="Physics", chapter="Magnetic Effect Of Current", difficulty="Medium", year=2023,
            questionText="A long straight wire carries 5 A current. Magnetic field at 10 cm from it (μ₀=4π×10⁻⁷):",
            options=listOf("10⁻⁵ T", "10⁻⁶ T", "10⁻⁴ T", "10⁻³ T"), correctOptionIndex=0,
            explanation="B = μ₀I/(2πr) = 4π×10⁻⁷×5/(2π×0.1) = 10⁻⁵ T."),
        Question(examType="JEE", subject="Physics", chapter="Magnetic Effect Of Current", difficulty="Medium", year=2022,
            questionText="A charged particle moves perpendicular to a magnetic field. Its path is:",
            options=listOf("Straight line", "Parabola", "Circle", "Ellipse"), correctOptionIndex=2,
            explanation="When v⊥B, force is always perpendicular to motion → circular path."),
        Question(examType="NEET", subject="Physics", chapter="Magnetic Effect Of Current", difficulty="Easy", year=2022,
            questionText="The unit of magnetic field (B) in SI is:",
            options=listOf("Oersted", "Gauss", "Tesla", "Weber"), correctOptionIndex=2,
            explanation="SI unit of magnetic field intensity B is Tesla (T)."),
        Question(examType="NEET", subject="Physics", chapter="Magnetic Effect Of Current", difficulty="Medium", year=2023,
            questionText="A current-carrying conductor in a magnetic field experiences a force. This is used in:",
            options=listOf("Transformer", "Generator", "Electric motor", "Capacitor"), correctOptionIndex=2,
            explanation="Electric motors use the force on a current-carrying conductor in a magnetic field."),

        // Electromagnetic Induction
        Question(examType="JEE", subject="Physics", chapter="Electromagnetic Induction", difficulty="Medium", year=2023,
            questionText="A coil of 100 turns has flux changing from 0.01 Wb to 0.04 Wb in 0.01 s. EMF induced:",
            options=listOf("3 V", "30 V", "300 V", "0.3 V"), correctOptionIndex=2,
            explanation="EMF = N × ΔΦ/Δt = 100 × (0.04-0.01)/0.01 = 100 × 3 = 300 V."),
        Question(examType="JEE", subject="Physics", chapter="Electromagnetic Induction", difficulty="Medium", year=2022,
            questionText="Lenz's law states that induced current opposes the:",
            options=listOf("Applied voltage", "Change in flux causing it", "Temperature change", "Resistance"), correctOptionIndex=1,
            explanation="Lenz's law: induced EMF opposes the change in magnetic flux that produces it."),
        Question(examType="NEET", subject="Physics", chapter="Electromagnetic Induction", difficulty="Easy", year=2022,
            questionText="Faraday's law of electromagnetic induction states that induced EMF is proportional to:",
            options=listOf("Flux itself", "Rate of change of flux", "Square of flux", "Inverse of flux"), correctOptionIndex=1,
            explanation="EMF = -dΦ/dt; magnitude of induced EMF equals rate of change of magnetic flux."),
        Question(examType="NEET", subject="Physics", chapter="Electromagnetic Induction", difficulty="Medium", year=2023,
            questionText="A transformer steps up voltage from 220 V to 2200 V. Turns ratio (secondary:primary) is:",
            options=listOf("1:10", "10:1", "1:100", "100:1"), correctOptionIndex=1,
            explanation="V_s/V_p = N_s/N_p → N_s/N_p = 2200/220 = 10:1."),

        // Optics
        Question(examType="JEE", subject="Physics", chapter="Optics", difficulty="Medium", year=2023,
            questionText="A convex lens has focal length 20 cm. For an object at 30 cm, image distance by lens formula:",
            options=listOf("60 cm", "-60 cm", "12 cm", "-12 cm"), correctOptionIndex=0,
            explanation="1/v - 1/u = 1/f → 1/v = 1/20 + 1/(-30) = 3/60 - 2/60 = 1/60 → v = 60 cm."),
        Question(examType="JEE", subject="Physics", chapter="Optics", difficulty="Hard", year=2022,
            questionText="Critical angle for glass-air interface (n_glass=1.5) is:",
            options=listOf("30°", "41.8°", "45°", "60°"), correctOptionIndex=1,
            explanation="sin(C) = 1/n = 1/1.5 = 0.667 → C = 41.8°."),
        Question(examType="NEET", subject="Physics", chapter="Optics", difficulty="Easy", year=2022,
            questionText="The phenomenon responsible for formation of rainbow is:",
            options=listOf("Reflection only", "Refraction and dispersion", "Diffraction", "Polarization"), correctOptionIndex=1,
            explanation="Rainbow is formed by refraction, internal reflection, and dispersion of sunlight in water droplets."),
        Question(examType="NEET", subject="Physics", chapter="Optics", difficulty="Medium", year=2023,
            questionText="A concave mirror of focal length 15 cm forms a real image of an object. If image is at infinity, object distance is:",
            options=listOf("15 cm", "30 cm", "7.5 cm", "Infinity"), correctOptionIndex=0,
            explanation="When image is at infinity, object is at focus: u = f = 15 cm."),

        // Modern Physics
        Question(examType="JEE", subject="Physics", chapter="Modern Physics", difficulty="Hard", year=2023,
            questionText="Energy of a photon of wavelength 600 nm (h=6.6×10⁻³⁴, c=3×10⁸) is approximately:",
            options=listOf("2.07 eV", "3.1 eV", "1.5 eV", "4.2 eV"), correctOptionIndex=0,
            explanation="E = hc/λ = 6.6×10⁻³⁴×3×10⁸/600×10⁻⁹ = 3.3×10⁻¹⁹ J ≈ 2.07 eV."),
        Question(examType="JEE", subject="Physics", chapter="Modern Physics", difficulty="Medium", year=2022,
            questionText="Half-life of a radioactive element is 5 years. After 20 years, fraction remaining is:",
            options=listOf("1/4", "1/8", "1/16", "1/32"), correctOptionIndex=2,
            explanation="n = 20/5 = 4 half-lives. Remaining = (1/2)⁴ = 1/16."),
        Question(examType="NEET", subject="Physics", chapter="Modern Physics", difficulty="Medium", year=2022,
            questionText="Photoelectric effect proves the particle nature of:",
            options=listOf("Electrons", "Protons", "Light", "Neutrons"), correctOptionIndex=2,
            explanation="Photoelectric effect shows light behaves as packets of energy (photons) — particle nature of light."),
        Question(examType="NEET", subject="Physics", chapter="Modern Physics", difficulty="Easy", year=2023,
            questionText="In nuclear fission, energy is released due to:",
            options=listOf("Mass defect converted to energy (E=mc²)", "Chemical bonds breaking", "Electron transitions", "Photon emission"), correctOptionIndex=0,
            explanation="Nuclear fission releases energy because the products have less mass than reactants (mass defect), per E=mc².")
    )

    // ── JEE + NEET Chemistry ─────────────────────────────────────────────────

    private fun chemistryQuestions(): List<Question> = listOf(

        // Some Basic Concepts Of Chemistry
        Question(examType="JEE", subject="Chemistry", chapter="Some Basic Concepts Of Chemistry", difficulty="Medium", year=2023,
            questionText="Molar mass of H₂SO₄ is:",
            options=listOf("96 g/mol", "98 g/mol", "100 g/mol", "94 g/mol"), correctOptionIndex=1,
            explanation="H₂SO₄: 2(1) + 32 + 4(16) = 2 + 32 + 64 = 98 g/mol."),
        Question(examType="JEE", subject="Chemistry", chapter="Some Basic Concepts Of Chemistry", difficulty="Easy", year=2022,
            questionText="Number of atoms in 1 mole of any substance (Avogadro's number) is:",
            options=listOf("6.022×10²²", "6.022×10²³", "6.022×10²⁴", "3.011×10²³"), correctOptionIndex=1,
            explanation="Avogadro's number NA = 6.022×10²³ particles per mole."),
        Question(examType="NEET", subject="Chemistry", chapter="Some Basic Concepts Of Chemistry", difficulty="Medium", year=2022,
            questionText="Empirical formula of glucose (C₆H₁₂O₆) is:",
            options=listOf("C₆H₁₂O₆", "CH₂O", "C₂H₄O₂", "C₃H₆O₃"), correctOptionIndex=1,
            explanation="Divide subscripts by GCD=6: CH₂O is the simplest whole-number ratio."),
        Question(examType="NEET", subject="Chemistry", chapter="Some Basic Concepts Of Chemistry", difficulty="Easy", year=2023,
            questionText="In a chemical reaction, the law of conservation of mass states:",
            options=listOf("Mass is created", "Mass is destroyed", "Total mass of reactants = total mass of products", "Atoms change identity"), correctOptionIndex=2,
            explanation="Mass is conserved in chemical reactions; reactant mass equals product mass."),

        // Structure Of Atom
        Question(examType="JEE", subject="Chemistry", chapter="Structure Of Atom", difficulty="Hard", year=2023,
            questionText="The de Broglie wavelength of a particle of mass m moving with velocity v is:",
            options=listOf("mv/h", "h/mv", "hmv", "h/(2mv)"), correctOptionIndex=1,
            explanation="de Broglie relation: λ = h/p = h/mv."),
        Question(examType="JEE", subject="Chemistry", chapter="Structure Of Atom", difficulty="Medium", year=2022,
            questionText="The number of radial nodes in 3s orbital is:",
            options=listOf("0", "1", "2", "3"), correctOptionIndex=2,
            explanation="Radial nodes = n - l - 1 = 3 - 0 - 1 = 2."),
        Question(examType="NEET", subject="Chemistry", chapter="Structure Of Atom", difficulty="Easy", year=2022,
            questionText="The maximum number of electrons in a shell with principal quantum number n=3 is:",
            options=listOf("6", "8", "18", "32"), correctOptionIndex=2,
            explanation="Max electrons = 2n² = 2×9 = 18."),
        Question(examType="NEET", subject="Chemistry", chapter="Structure Of Atom", difficulty="Medium", year=2023,
            questionText="Heisenberg uncertainty principle states that we cannot simultaneously determine with precision:",
            options=listOf("Mass and volume", "Charge and mass", "Position and momentum", "Energy and charge"), correctOptionIndex=2,
            explanation="Δx × Δp ≥ h/4π; position and momentum cannot both be precisely determined."),

        // Classification Of Elements
        Question(examType="JEE", subject="Chemistry", chapter="Classification Of Elements", difficulty="Medium", year=2023,
            questionText="Elements in the same group of the periodic table have the same:",
            options=listOf("Atomic mass", "Number of shells", "Valence electrons", "Atomic number"), correctOptionIndex=2,
            explanation="Elements in the same group have the same number of valence electrons, giving similar properties."),
        Question(examType="JEE", subject="Chemistry", chapter="Classification Of Elements", difficulty="Easy", year=2022,
            questionText="Electronegativity generally increases as you move:",
            options=listOf("Down a group", "Left across a period", "Right across a period", "Down and right"), correctOptionIndex=2,
            explanation="Electronegativity increases left to right across a period as nuclear charge increases."),
        Question(examType="NEET", subject="Chemistry", chapter="Classification Of Elements", difficulty="Medium", year=2022,
            questionText="Which of the following has the smallest atomic radius?",
            options=listOf("Na", "K", "Li", "Cs"), correctOptionIndex=2,
            explanation="Atomic radius decreases up a group. Li is at the top of Group 1, so it has the smallest radius."),
        Question(examType="NEET", subject="Chemistry", chapter="Classification Of Elements", difficulty="Easy", year=2023,
            questionText="The period number of an element equals its:",
            options=listOf("Number of valence electrons", "Number of electron shells", "Atomic number", "Mass number"), correctOptionIndex=1,
            explanation="Period number = number of occupied electron shells (energy levels)."),

        // Chemical Bonding
        Question(examType="JEE", subject="Chemistry", chapter="Chemical Bonding", difficulty="Medium", year=2023,
            questionText="The shape of PCl₅ molecule is:",
            options=listOf("Tetrahedral", "Trigonal planar", "Trigonal bipyramidal", "Octahedral"), correctOptionIndex=2,
            explanation="PCl₅ has 5 bonding pairs and no lone pairs → trigonal bipyramidal geometry."),
        Question(examType="JEE", subject="Chemistry", chapter="Chemical Bonding", difficulty="Hard", year=2022,
            questionText="Bond order of O₂ using molecular orbital theory:",
            options=listOf("1", "1.5", "2", "3"), correctOptionIndex=2,
            explanation="O₂ MO config: bond order = (8-4)/2 = 2."),
        Question(examType="NEET", subject="Chemistry", chapter="Chemical Bonding", difficulty="Easy", year=2022,
            questionText="NaCl is an example of:",
            options=listOf("Covalent bonding", "Metallic bonding", "Ionic bonding", "Hydrogen bonding"), correctOptionIndex=2,
            explanation="NaCl is formed by electron transfer from Na to Cl — ionic bonding."),
        Question(examType="NEET", subject="Chemistry", chapter="Chemical Bonding", difficulty="Medium", year=2023,
            questionText="Water has higher boiling point than H₂S due to:",
            options=listOf("Ionic bonding", "Covalent bonding", "Hydrogen bonding", "Van der Waals forces"), correctOptionIndex=2,
            explanation="Water molecules form strong hydrogen bonds (O-H···O), requiring more energy to separate."),

        // States Of Matter
        Question(examType="JEE", subject="Chemistry", chapter="States Of Matter", difficulty="Medium", year=2023,
            questionText="At STP, 1 mole of an ideal gas occupies:",
            options=listOf("22.4 L", "11.2 L", "44.8 L", "24 L"), correctOptionIndex=0,
            explanation="Molar volume at STP (0°C, 1 atm) = 22.4 L for any ideal gas."),
        Question(examType="JEE", subject="Chemistry", chapter="States Of Matter", difficulty="Easy", year=2022,
            questionText="Viscosity of a liquid generally __ with increase in temperature.",
            options=listOf("Increases", "Decreases", "Remains same", "First increases then decreases"), correctOptionIndex=1,
            explanation="Higher temperature gives molecules more kinetic energy, reducing intermolecular forces and viscosity."),
        Question(examType="NEET", subject="Chemistry", chapter="States Of Matter", difficulty="Medium", year=2022,
            questionText="Graham's law of diffusion states that rate of diffusion is inversely proportional to:",
            options=listOf("Molar mass", "√(molar mass)", "Density", "√(density)"), correctOptionIndex=1,
            explanation="r ∝ 1/√M (Graham's law); lighter gases diffuse faster."),
        Question(examType="NEET", subject="Chemistry", chapter="States Of Matter", difficulty="Easy", year=2023,
            questionText="The critical temperature of a gas is the temperature above which it:",
            options=listOf("Cannot be liquefied", "Becomes solid", "Has zero pressure", "Expands indefinitely"), correctOptionIndex=0,
            explanation="Above critical temperature, gas cannot be liquefied by pressure alone."),

        // Thermodynamics (Chemistry)
        Question(examType="JEE", subject="Chemistry", chapter="Thermodynamics", difficulty="Medium", year=2023,
            questionText="For a spontaneous process at constant T and P, Gibbs free energy change ΔG is:",
            options=listOf("ΔG > 0", "ΔG = 0", "ΔG < 0", "ΔG > 1"), correctOptionIndex=2,
            explanation="Spontaneous processes have ΔG < 0 (decrease in Gibbs free energy)."),
        Question(examType="JEE", subject="Chemistry", chapter="Thermodynamics", difficulty="Hard", year=2022,
            questionText="ΔH = ΔU + ΔnRT where Δn is:",
            options=listOf("Total moles of reactants", "Moles of gaseous products minus gaseous reactants", "Total moles of products", "Moles of solid products"), correctOptionIndex=1,
            explanation="Δn = moles of gaseous products − moles of gaseous reactants in the balanced equation."),
        Question(examType="NEET", subject="Chemistry", chapter="Thermodynamics", difficulty="Easy", year=2022,
            questionText="Enthalpy of combustion is always:",
            options=listOf("Positive", "Negative", "Zero", "Can be either"), correctOptionIndex=1,
            explanation="Combustion is exothermic; heat is released, so ΔH is negative."),
        Question(examType="NEET", subject="Chemistry", chapter="Thermodynamics", difficulty="Medium", year=2023,
            questionText="Entropy is a measure of:",
            options=listOf("Energy", "Temperature", "Disorder or randomness", "Enthalpy"), correctOptionIndex=2,
            explanation="Entropy (S) measures the degree of disorder or randomness of a system."),

        // Equilibrium
        Question(examType="JEE", subject="Chemistry", chapter="Equilibrium", difficulty="Medium", year=2022,
            questionText="pH of 0.001 M HCl at 25°C is:",
            options=listOf("1", "2", "3", "4"), correctOptionIndex=2,
            explanation="HCl fully dissociates: [H⁺]=10⁻³ M → pH = -log(10⁻³) = 3."),
        Question(examType="JEE", subject="Chemistry", chapter="Equilibrium", difficulty="Hard", year=2023,
            questionText="For N₂+3H₂⇌2NH₃, Kp relates to Kc by: Kp = Kc(RT)^Δn. Δn equals:",
            options=listOf("2", "-2", "1", "-1"), correctOptionIndex=1,
            explanation="Δn = moles of gaseous products - reactants = 2 - (1+3) = -2."),
        Question(examType="NEET", subject="Chemistry", chapter="Equilibrium", difficulty="Easy", year=2022,
            questionText="pH of pure water at 25°C is:",
            options=listOf("0", "7", "14", "1"), correctOptionIndex=1,
            explanation="At 25°C, [H⁺]=[OH⁻]=10⁻⁷ M in pure water → pH = 7."),
        Question(examType="NEET", subject="Chemistry", chapter="Equilibrium", difficulty="Medium", year=2023,
            questionText="According to Le Chatelier's principle, increasing pressure in the reaction N₂+3H₂⇌2NH₃ shifts equilibrium:",
            options=listOf("Left", "Right", "No shift", "Cannot be determined"), correctOptionIndex=1,
            explanation="Increased pressure favours the side with fewer gas moles (right, 2 moles vs 4 moles)."),

        // Redox Reactions
        Question(examType="JEE", subject="Chemistry", chapter="Redox Reactions", difficulty="Medium", year=2023,
            questionText="Oxidation state of Cr in K₂Cr₂O₇ is:",
            options=listOf("+3", "+6", "+4", "+7"), correctOptionIndex=1,
            explanation="2(+1) + 2x + 7(-2) = 0 → 2 + 2x - 14 = 0 → x = +6."),
        Question(examType="JEE", subject="Chemistry", chapter="Redox Reactions", difficulty="Easy", year=2022,
            questionText="In a redox reaction, the reducing agent is:",
            options=listOf("Oxidised and gains electrons", "Reduced and loses electrons", "Oxidised and loses electrons", "Reduced and gains electrons"), correctOptionIndex=2,
            explanation="A reducing agent gets oxidised (loses electrons) and reduces the other substance."),
        Question(examType="NEET", subject="Chemistry", chapter="Redox Reactions", difficulty="Medium", year=2022,
            questionText="Oxidation state of Mn in KMnO₄ is:",
            options=listOf("+4", "+5", "+6", "+7"), correctOptionIndex=3,
            explanation="K(+1) + Mn + 4O(-2×4) = 0 → Mn = +7."),
        Question(examType="NEET", subject="Chemistry", chapter="Redox Reactions", difficulty="Easy", year=2023,
            questionText="Which species is oxidised in: 2Fe³⁺ + Sn²⁺ → 2Fe²⁺ + Sn⁴⁺?",
            options=listOf("Fe³⁺", "Fe²⁺", "Sn²⁺", "Sn⁴⁺"), correctOptionIndex=2,
            explanation="Sn²⁺ loses electrons (oxidation state +2 → +4), so it is oxidised."),

        // Hydrogen
        Question(examType="JEE", subject="Chemistry", chapter="Hydrogen", difficulty="Easy", year=2022,
            questionText="Heavy water is:",
            options=listOf("H₂O with dissolved salts", "D₂O (deuterium oxide)", "H₂O₂", "Water at high pressure"), correctOptionIndex=1,
            explanation="Heavy water is D₂O, where hydrogen is replaced by deuterium (²H)."),
        Question(examType="JEE", subject="Chemistry", chapter="Hydrogen", difficulty="Medium", year=2023,
            questionText="H₂O₂ acts as both oxidising and reducing agent. In which reaction is it reducing?",
            options=listOf("Oxidising PbS", "Decolourising KMnO₄ in acid", "Oxidising Fe²⁺", "Bleaching"), correctOptionIndex=1,
            explanation="With acidic KMnO₄, H₂O₂ reduces permanganate; H₂O₂ is itself oxidised to O₂."),
        Question(examType="NEET", subject="Chemistry", chapter="Hydrogen", difficulty="Easy", year=2022,
            questionText="The isotope of hydrogen with mass number 3 is called:",
            options=listOf("Protium", "Deuterium", "Tritium", "Helium"), correctOptionIndex=2,
            explanation="Tritium (³H) has 1 proton and 2 neutrons, mass number 3."),
        Question(examType="NEET", subject="Chemistry", chapter="Hydrogen", difficulty="Medium", year=2023,
            questionText="Which property makes hydrogen unique among all elements?",
            options=listOf("It is a gas at room temperature", "It can act as both metal and non-metal", "It has no neutron in its most common isotope", "All of the above"), correctOptionIndex=3,
            explanation="Hydrogen is unique: it's a gas, has no neutron in ¹H, and can donate or accept electrons."),

        // S-Block Elements
        Question(examType="JEE", subject="Chemistry", chapter="S-Block Elements", difficulty="Medium", year=2023,
            questionText="Which alkali metal reacts most violently with water?",
            options=listOf("Lithium", "Sodium", "Potassium", "Caesium"), correctOptionIndex=3,
            explanation="Reactivity increases down Group 1. Cs reacts most violently due to largest atomic size and lowest IE."),
        Question(examType="JEE", subject="Chemistry", chapter="S-Block Elements", difficulty="Easy", year=2022,
            questionText="Plaster of Paris is:",
            options=listOf("CaSO₄", "CaSO₄·H₂O", "CaSO₄·½H₂O", "CaSO₄·2H₂O"), correctOptionIndex=2,
            explanation="Plaster of Paris is CaSO₄·½H₂O (hemihydrate of calcium sulphate)."),
        Question(examType="NEET", subject="Chemistry", chapter="S-Block Elements", difficulty="Easy", year=2022,
            questionText="Na is stored in kerosene because:",
            options=listOf("It is soluble in kerosene", "It reacts violently with air and water", "Kerosene improves its properties", "It glows in kerosene"), correctOptionIndex=1,
            explanation="Sodium reacts explosively with moisture and oxygen in air; kerosene prevents this."),
        Question(examType="NEET", subject="Chemistry", chapter="S-Block Elements", difficulty="Medium", year=2023,
            questionText="Diagonal relationship is observed between Li and:",
            options=listOf("Na", "Mg", "Be", "K"), correctOptionIndex=1,
            explanation="Li shows diagonal relationship with Mg due to similar charge/radius ratio (charge density)."),

        // P-Block Elements
        Question(examType="JEE", subject="Chemistry", chapter="P-Block Elements", difficulty="Medium", year=2023,
            questionText="Which of the following is not a property of noble gases?",
            options=listOf("Monatomic", "Chemically inert", "Colourless", "High reactivity with halogens"), correctOptionIndex=3,
            explanation="Noble gases have completely filled valence shells and are chemically inert (with very few exceptions)."),
        Question(examType="JEE", subject="Chemistry", chapter="P-Block Elements", difficulty="Hard", year=2022,
            questionText="Hybridisation of S in SF₆ is:",
            options=listOf("sp³", "sp³d", "sp³d²", "sp²"), correctOptionIndex=2,
            explanation="SF₆ has 6 bond pairs and no lone pairs → octahedral, sp³d² hybridisation."),
        Question(examType="NEET", subject="Chemistry", chapter="P-Block Elements", difficulty="Easy", year=2022,
            questionText="Which element is most electronegative?",
            options=listOf("Oxygen", "Chlorine", "Nitrogen", "Fluorine"), correctOptionIndex=3,
            explanation="Fluorine (F) is the most electronegative element (3.98 on Pauling scale)."),
        Question(examType="NEET", subject="Chemistry", chapter="P-Block Elements", difficulty="Medium", year=2023,
            questionText="Ozone layer in stratosphere protects Earth from:",
            options=listOf("Infrared radiation", "Visible light", "UV radiation", "Radio waves"), correctOptionIndex=2,
            explanation="Ozone (O₃) absorbs harmful UV radiation from the Sun."),

        // Organic Chemistry Basics
        Question(examType="JEE", subject="Chemistry", chapter="Organic Chemistry Basics", difficulty="Medium", year=2023,
            questionText="IUPAC name of CH₃-CH(OH)-CH₂-CH₃ is:",
            options=listOf("2-butanol", "1-methylpropanol", "Butan-2-ol", "Both A and C"), correctOptionIndex=3,
            explanation="The compound is butan-2-ol (2-butanol is also acceptable). Both A and C are correct."),
        Question(examType="JEE", subject="Chemistry", chapter="Organic Chemistry Basics", difficulty="Hard", year=2022,
            questionText="In an SN1 reaction, the rate depends on:",
            options=listOf("Concentration of nucleophile only", "Concentration of substrate only", "Both substrate and nucleophile", "Temperature only"), correctOptionIndex=1,
            explanation="SN1 is unimolecular; rate = k[substrate]. Only substrate concentration matters."),
        Question(examType="NEET", subject="Chemistry", chapter="Organic Chemistry Basics", difficulty="Easy", year=2022,
            questionText="Functional group -OH (hydroxyl) is present in:",
            options=listOf("Aldehyde", "Ketone", "Alcohol", "Ether"), correctOptionIndex=2,
            explanation="-OH is the functional group of alcohols (e.g., ethanol CH₃CH₂OH)."),
        Question(examType="NEET", subject="Chemistry", chapter="Organic Chemistry Basics", difficulty="Medium", year=2023,
            questionText="Inductive effect through C-C bonds is:",
            options=listOf("Permanent and transmitted through space", "Temporary and transmitted through bonds", "Permanent and decreases with distance", "Constant throughout the chain"), correctOptionIndex=2,
            explanation="Inductive effect is a permanent effect transmitted through bonds; it diminishes with distance."),

        // Hydrocarbons
        Question(examType="JEE", subject="Chemistry", chapter="Hydrocarbons", difficulty="Medium", year=2023,
            questionText="Benzene undergoes electrophilic substitution rather than addition because:",
            options=listOf("It has low electron density", "Substitution preserves aromatic stability", "Addition is slower for ring compounds", "It has no pi bonds"), correctOptionIndex=1,
            explanation="Aromatic stability (delocalised π electrons) is preserved in substitution but lost in addition."),
        Question(examType="JEE", subject="Chemistry", chapter="Hydrocarbons", difficulty="Easy", year=2022,
            questionText="General formula of alkynes is:",
            options=listOf("CₙH₂ₙ₊₂", "CₙH₂ₙ", "CₙH₂ₙ₋₂", "CₙHₙ"), correctOptionIndex=2,
            explanation="Alkynes have one triple bond; general formula CₙH₂ₙ₋₂."),
        Question(examType="NEET", subject="Chemistry", chapter="Hydrocarbons", difficulty="Easy", year=2022,
            questionText="Methane (CH₄) is an example of:",
            options=listOf("Alkene", "Alkyne", "Alkane", "Aromatic hydrocarbon"), correctOptionIndex=2,
            explanation="CH₄ (methane) is the simplest alkane — a saturated hydrocarbon."),
        Question(examType="NEET", subject="Chemistry", chapter="Hydrocarbons", difficulty="Medium", year=2023,
            questionText="Markovnikov's rule predicts that in addition of HX to an unsymmetrical alkene, H adds to the carbon:",
            options=listOf("With fewer hydrogen atoms", "With more hydrogen atoms", "At the end of chain always", "That is most substituted"), correctOptionIndex=1,
            explanation="H adds to the carbon already bearing more H atoms (less substituted C)."),

        // Environmental Chemistry
        Question(examType="JEE", subject="Chemistry", chapter="Environmental Chemistry", difficulty="Easy", year=2022,
            questionText="BOD (Biological Oxygen Demand) is a measure of:",
            options=listOf("Atmospheric oxygen", "Water pollution by organic matter", "Soil acidity", "Air quality"), correctOptionIndex=1,
            explanation="High BOD indicates more organic matter in water, leading to greater oxygen depletion by bacteria."),
        Question(examType="JEE", subject="Chemistry", chapter="Environmental Chemistry", difficulty="Medium", year=2023,
            questionText="Acid rain is primarily caused by oxides of:",
            options=listOf("Carbon and hydrogen", "Sulphur and nitrogen", "Oxygen and helium", "Phosphorus and calcium"), correctOptionIndex=1,
            explanation="SO₂ and NOₓ react with atmospheric water to form H₂SO₄ and HNO₃, causing acid rain."),
        Question(examType="NEET", subject="Chemistry", chapter="Environmental Chemistry", difficulty="Easy", year=2022,
            questionText="The main greenhouse gas responsible for global warming is:",
            options=listOf("O₂", "N₂", "CO₂", "Ar"), correctOptionIndex=2,
            explanation="CO₂ is the primary anthropogenic greenhouse gas causing global warming."),
        Question(examType="NEET", subject="Chemistry", chapter="Environmental Chemistry", difficulty="Medium", year=2023,
            questionText="CFCs (chlorofluorocarbons) cause depletion of ozone by releasing:",
            options=listOf("Fluorine radicals", "Chlorine radicals", "Carbon radicals", "Bromine radicals"), correctOptionIndex=1,
            explanation="UV radiation breaks CFCs to release Cl radicals, which catalytically destroy O₃."),

        // Solid State
        Question(examType="JEE", subject="Chemistry", chapter="Solid State", difficulty="Hard", year=2023,
            questionText="In an FCC unit cell, number of atoms per unit cell is:",
            options=listOf("1", "2", "4", "6"), correctOptionIndex=2,
            explanation="FCC: 8 corners × 1/8 + 6 faces × 1/2 = 1 + 3 = 4 atoms per unit cell."),
        Question(examType="JEE", subject="Chemistry", chapter="Solid State", difficulty="Medium", year=2022,
            questionText="Schottky defect in an ionic crystal results in:",
            options=listOf("Increase in density", "Decrease in density", "No change in density", "Change in colour"), correctOptionIndex=1,
            explanation="Schottky defect involves missing ion pairs → fewer particles, lower density."),
        Question(examType="NEET", subject="Chemistry", chapter="Solid State", difficulty="Easy", year=2022,
            questionText="Which type of solid conducts electricity?",
            options=listOf("Molecular solid", "Ionic solid (in solid state)", "Metallic solid", "Covalent solid"), correctOptionIndex=2,
            explanation="Metallic solids have delocalised electrons enabling electrical conduction."),
        Question(examType="NEET", subject="Chemistry", chapter="Solid State", difficulty="Medium", year=2023,
            questionText="Coordination number of Na⁺ in NaCl crystal structure is:",
            options=listOf("4", "6", "8", "12"), correctOptionIndex=1,
            explanation="In NaCl (rock salt) structure, each Na⁺ is surrounded by 6 Cl⁻ ions."),

        // Solutions
        Question(examType="JEE", subject="Chemistry", chapter="Solutions", difficulty="Medium", year=2023,
            questionText="Molarity of a solution is defined as:",
            options=listOf("Moles of solute per kg of solvent", "Moles of solute per litre of solution", "Grams of solute per litre", "Mole fraction of solute"), correctOptionIndex=1,
            explanation="Molarity M = moles of solute / volume of solution in litres."),
        Question(examType="JEE", subject="Chemistry", chapter="Solutions", difficulty="Hard", year=2022,
            questionText="Osmotic pressure of a 0.1 M glucose solution at 27°C (R=0.082 L·atm/mol·K) is:",
            options=listOf("2.46 atm", "0.246 atm", "24.6 atm", "0.0246 atm"), correctOptionIndex=0,
            explanation="π = MRT = 0.1 × 0.082 × 300 = 2.46 atm."),
        Question(examType="NEET", subject="Chemistry", chapter="Solutions", difficulty="Easy", year=2022,
            questionText="Raoult's law states that vapour pressure of a solvent above a solution is:",
            options=listOf("Equal to that of pure solvent", "Greater than pure solvent", "Proportional to mole fraction of solvent", "Independent of composition"), correctOptionIndex=2,
            explanation="P = P° × x_solvent (Raoult's law); vapour pressure ∝ mole fraction of solvent."),
        Question(examType="NEET", subject="Chemistry", chapter="Solutions", difficulty="Medium", year=2023,
            questionText="Which colligative property is used to determine molar mass of a solute?",
            options=listOf("Colour change", "Boiling point elevation", "Solubility", "Conductance"), correctOptionIndex=1,
            explanation="ΔTb = Kb × m; measuring boiling point elevation gives molality, then molar mass."),

        // Electrochemistry
        Question(examType="JEE", subject="Chemistry", chapter="Electrochemistry", difficulty="Hard", year=2022,
            questionText="Standard EMF of Daniell cell (Zn/Zn²⁺||Cu²⁺/Cu, E°Zn=-0.76V, E°Cu=+0.34V):",
            options=listOf("0.42 V", "1.10 V", "0.76 V", "0.34 V"), correctOptionIndex=1,
            explanation="EMF = E°cathode - E°anode = 0.34 - (-0.76) = 1.10 V."),
        Question(examType="JEE", subject="Chemistry", chapter="Electrochemistry", difficulty="Medium", year=2023,
            questionText="By Faraday's law, mass deposited during electrolysis is proportional to:",
            options=listOf("Voltage", "Resistance", "Charge passed", "Temperature"), correctOptionIndex=2,
            explanation="Faraday's first law: m = (M/nF) × Q; mass deposited ∝ charge Q passed."),
        Question(examType="NEET", subject="Chemistry", chapter="Electrochemistry", difficulty="Medium", year=2022,
            questionText="Standard electrode potential (E°) of Zn²⁺/Zn is -0.76 V and Cu²⁺/Cu is +0.34 V. The EMF of Daniell cell is:",
            options=listOf("0.42 V", "1.10 V", "0.76 V", "0.34 V"), correctOptionIndex=1,
            explanation="E_cell = E_cathode - E_anode = 0.34 - (-0.76) = 1.10 V."),
        Question(examType="NEET", subject="Chemistry", chapter="Electrochemistry", difficulty="Easy", year=2023,
            questionText="In electrolysis, oxidation occurs at the:",
            options=listOf("Cathode", "Anode", "Both electrodes", "Neither electrode"), correctOptionIndex=1,
            explanation="Anode: oxidation (loss of electrons); Cathode: reduction (gain of electrons)."),

        // Chemical Kinetics
        Question(examType="JEE", subject="Chemistry", chapter="Chemical Kinetics", difficulty="Medium", year=2023,
            questionText="For a first-order reaction, the half-life is:",
            options=listOf("Proportional to initial concentration", "Independent of initial concentration", "Inversely proportional to rate constant", "Zero"), correctOptionIndex=1,
            explanation="t½ = 0.693/k for first-order reactions; independent of initial concentration."),
        Question(examType="JEE", subject="Chemistry", chapter="Chemical Kinetics", difficulty="Hard", year=2022,
            questionText="Activation energy of a reaction can be determined from:",
            options=listOf("Rate law", "Arrhenius equation (ln k vs 1/T plot)", "Order of reaction", "Stoichiometry"), correctOptionIndex=1,
            explanation="Arrhenius: ln k = ln A - Ea/RT → slope of ln k vs 1/T = -Ea/R."),
        Question(examType="NEET", subject="Chemistry", chapter="Chemical Kinetics", difficulty="Easy", year=2022,
            questionText="Rate of a reaction generally increases with:",
            options=listOf("Decreasing temperature", "Increasing temperature", "Decreasing concentration", "Adding inert gas"), correctOptionIndex=1,
            explanation="Higher temperature increases molecular kinetic energy and collision frequency, increasing rate."),
        Question(examType="NEET", subject="Chemistry", chapter="Chemical Kinetics", difficulty="Medium", year=2023,
            questionText="A catalyst increases reaction rate by:",
            options=listOf("Increasing activation energy", "Decreasing activation energy", "Changing the products", "Increasing ΔH"), correctOptionIndex=1,
            explanation="A catalyst provides an alternative pathway with lower activation energy."),

        // Surface Chemistry
        Question(examType="JEE", subject="Chemistry", chapter="Surface Chemistry", difficulty="Medium", year=2023,
            questionText="Tyndall effect is shown by:",
            options=listOf("True solutions", "Colloidal solutions", "Suspensions only", "All solutions"), correctOptionIndex=1,
            explanation="Colloidal particles scatter light (Tyndall effect); true solutions don't show this."),
        Question(examType="JEE", subject="Chemistry", chapter="Surface Chemistry", difficulty="Easy", year=2022,
            questionText="Adsorption of a gas on a solid surface is generally:",
            options=listOf("Endothermic", "Exothermic", "Isothermal", "Adiabatic"), correctOptionIndex=1,
            explanation="Adsorption releases energy (exothermic) as gas molecules bond to the surface."),
        Question(examType="NEET", subject="Chemistry", chapter="Surface Chemistry", difficulty="Easy", year=2022,
            questionText="Which of the following is a lyophilic colloid?",
            options=listOf("Gold sol", "Silver sol", "Starch sol", "Arsenic sulphide sol"), correctOptionIndex=2,
            explanation="Lyophilic colloids (e.g., starch, gelatin) have affinity for the dispersion medium."),
        Question(examType="NEET", subject="Chemistry", chapter="Surface Chemistry", difficulty="Medium", year=2023,
            questionText="Coagulation of a colloid is caused by:",
            options=listOf("Dilution", "Adding electrolyte", "Heating to 50°C", "Adding more colloid"), correctOptionIndex=1,
            explanation="Electrolytes neutralise the charge on colloidal particles, causing them to aggregate (coagulate)."),

        // Coordination Compounds
        Question(examType="JEE", subject="Chemistry", chapter="Coordination Compounds", difficulty="Hard", year=2023,
            questionText="IUPAC name of [Co(NH₃)₆]Cl₃ is:",
            options=listOf("Cobalt hexaamminechloride", "Hexaamminecobalt(III) chloride", "Hexaammocobalt chloride", "Cobalt(III) hexaammine trichloride"), correctOptionIndex=1,
            explanation="Ligands named first alphabetically, then metal with oxidation state: hexaamminecobalt(III) chloride."),
        Question(examType="JEE", subject="Chemistry", chapter="Coordination Compounds", difficulty="Medium", year=2022,
            questionText="According to Werner's theory, secondary valency of the central metal is satisfied by:",
            options=listOf("Ionisable ligands", "Coordinated ligands", "Counter ions only", "Solvent molecules"), correctOptionIndex=1,
            explanation="Secondary valency = coordination number; satisfied by coordinated ligands in the coordination sphere."),
        Question(examType="NEET", subject="Chemistry", chapter="Coordination Compounds", difficulty="Easy", year=2022,
            questionText="EDTA is a:",
            options=listOf("Monodentate ligand", "Bidentate ligand", "Hexadentate ligand", "Bridging ligand"), correctOptionIndex=2,
            explanation="EDTA (ethylenediaminetetraacetate) can donate 6 electron pairs — hexadentate."),
        Question(examType="NEET", subject="Chemistry", chapter="Coordination Compounds", difficulty="Medium", year=2023,
            questionText="Coordination number of Co in [Co(en)₂Cl₂]⁺ is:",
            options=listOf("4", "5", "6", "8"), correctOptionIndex=2,
            explanation="en is bidentate (2 bonds each × 2) + 2 Cl = 4 + 2 = 6. Coordination number = 6."),

        // Aldehydes, Ketones And Carboxylic Acids
        Question(examType="JEE", subject="Chemistry", chapter="Aldehydes, Ketones And Carboxylic Acids", difficulty="Medium", year=2023,
            questionText="Aldehydes can be distinguished from ketones using:",
            options=listOf("Fehling's solution", "NaOH", "Conc. H₂SO₄", "Bromine water"), correctOptionIndex=0,
            explanation="Aldehydes reduce Fehling's solution (Cu²⁺→Cu₂O red ppt); ketones do not."),
        Question(examType="JEE", subject="Chemistry", chapter="Aldehydes, Ketones And Carboxylic Acids", difficulty="Hard", year=2022,
            questionText="The order of reactivity of carbonyl compounds towards nucleophilic addition is:",
            options=listOf("Ketone > Aldehyde > RCOOH", "RCOOH > Ketone > Aldehyde", "Aldehyde > Ketone > RCOOH", "Aldehyde > RCOOH > Ketone"), correctOptionIndex=2,
            explanation="Aldehydes are most reactive (less steric/electronic hindrance), ketones next, carboxylic acids least."),
        Question(examType="NEET", subject="Chemistry", chapter="Aldehydes, Ketones And Carboxylic Acids", difficulty="Easy", year=2022,
            questionText="The functional group in carboxylic acids is:",
            options=listOf("-CHO", "-CO-", "-COOH", "-OH"), correctOptionIndex=2,
            explanation="-COOH (carboxyl group) is the functional group of carboxylic acids."),
        Question(examType="NEET", subject="Chemistry", chapter="Aldehydes, Ketones And Carboxylic Acids", difficulty="Medium", year=2023,
            questionText="Acetic acid reacts with ethanol in presence of H₂SO₄ to form:",
            options=listOf("Acetaldehyde", "Ethyl acetate (ester)", "Acetone", "Diethyl ether"), correctOptionIndex=1,
            explanation="CH₃COOH + C₂H₅OH → CH₃COOC₂H₅ + H₂O (Fischer esterification)."),

        // Biomolecules
        Question(examType="JEE", subject="Chemistry", chapter="Biomolecules", difficulty="Medium", year=2023,
            questionText="Which of the following is an essential amino acid?",
            options=listOf("Glycine", "Alanine", "Leucine", "Serine"), correctOptionIndex=2,
            explanation="Essential amino acids cannot be synthesised by the body. Leucine is one of the 9 essential amino acids."),
        Question(examType="JEE", subject="Chemistry", chapter="Biomolecules", difficulty="Easy", year=2022,
            questionText="DNA double helix is held together by:",
            options=listOf("Covalent bonds", "Ionic bonds", "Hydrogen bonds between base pairs", "Peptide bonds"), correctOptionIndex=2,
            explanation="The two strands of DNA are held by hydrogen bonds between complementary base pairs (A-T, G-C)."),
        Question(examType="NEET", subject="Chemistry", chapter="Biomolecules", difficulty="Medium", year=2022,
            questionText="Which is an example of an essential amino acid?",
            options=listOf("Glycine", "Alanine", "Leucine", "Serine"), correctOptionIndex=2,
            explanation="Leucine is one of the essential amino acids that must be obtained from diet."),
        Question(examType="NEET", subject="Chemistry", chapter="Biomolecules", difficulty="Easy", year=2023,
            questionText="Glucose belongs to the class of:",
            options=listOf("Disaccharides", "Polysaccharides", "Monosaccharides", "Amino acids"), correctOptionIndex=2,
            explanation="Glucose (C₆H₁₂O₆) is a monosaccharide — the simplest unit of carbohydrates.")
    )

    // ── JEE Maths ────────────────────────────────────────────────────────────

    private fun mathsQuestions(): List<Question> = listOf(

        // Sets, Relations, And Functions
        Question(examType="JEE", subject="Maths", chapter="Sets, Relations, And Functions", difficulty="Easy", year=2022,
            questionText="If A={1,2,3} and B={2,3,4}, then A∩B is:",
            options=listOf("{1,2,3,4}", "{2,3}", "{1,4}", "{1}"), correctOptionIndex=1,
            explanation="A∩B = elements common to both sets = {2,3}."),
        Question(examType="JEE", subject="Maths", chapter="Sets, Relations, And Functions", difficulty="Medium", year=2023,
            questionText="A relation R on set A is symmetric if for all a,b ∈ A: aRb implies:",
            options=listOf("bRa", "aRa", "aRc for some c", "None of these"), correctOptionIndex=0,
            explanation="Symmetric: aRb ⟹ bRa for all a, b ∈ A."),

        // Complex Numbers & Quadratic Equations
        Question(examType="JEE", subject="Maths", chapter="Complex Numbers & Quadratic Equations", difficulty="Medium", year=2023,
            questionText="If z = 3 + 4i, then |z| equals:",
            options=listOf("3", "4", "5", "7"), correctOptionIndex=2,
            explanation="|z| = √(3² + 4²) = √(9+16) = √25 = 5."),
        Question(examType="JEE", subject="Maths", chapter="Complex Numbers & Quadratic Equations", difficulty="Medium", year=2022,
            questionText="The roots of x² - 5x + 6 = 0 are:",
            options=listOf("2, 3", "1, 6", "-2, -3", "3, 4"), correctOptionIndex=0,
            explanation="x² - 5x + 6 = (x-2)(x-3) = 0 → x = 2 or 3."),

        // Matrices & Determinants
        Question(examType="JEE", subject="Maths", chapter="Matrices & Determinants", difficulty="Medium", year=2023,
            questionText="Determinant of [[2,3],[4,5]] is:",
            options=listOf("-2", "2", "10", "-10"), correctOptionIndex=0,
            explanation="det = 2×5 - 3×4 = 10 - 12 = -2."),
        Question(examType="JEE", subject="Maths", chapter="Matrices & Determinants", difficulty="Hard", year=2022,
            questionText="If A is a 3×3 matrix with det(A)=5, then det(2A) is:",
            options=listOf("10", "25", "40", "5"), correctOptionIndex=2,
            explanation="det(kA) = k³ det(A) for n×n matrix → det(2A) = 8×5 = 40."),

        // Permutations And Combinations
        Question(examType="JEE", subject="Maths", chapter="Permutations And Combinations", difficulty="Easy", year=2022,
            questionText="Number of ways to arrange 5 different books on a shelf:",
            options=listOf("10", "25", "120", "60"), correctOptionIndex=2,
            explanation="5! = 5×4×3×2×1 = 120."),
        Question(examType="JEE", subject="Maths", chapter="Permutations And Combinations", difficulty="Medium", year=2023,
            questionText="C(10, 3) equals:",
            options=listOf("30", "120", "720", "10"), correctOptionIndex=1,
            explanation="C(10,3) = 10!/(3!×7!) = (10×9×8)/(3×2×1) = 120."),

        // Binomial Theorem
        Question(examType="JEE", subject="Maths", chapter="Binomial Theorem", difficulty="Medium", year=2023,
            questionText="The general term in expansion of (x+y)ⁿ is:",
            options=listOf("C(n,r)xʳyⁿ⁻ʳ", "C(n,r)xⁿ⁻ʳyʳ", "C(n,r)xⁿyʳ", "C(n,r)xʳyʳ"), correctOptionIndex=1,
            explanation="Tᵣ₊₁ = C(n,r) × xⁿ⁻ʳ × yʳ."),
        Question(examType="JEE", subject="Maths", chapter="Binomial Theorem", difficulty="Easy", year=2022,
            questionText="In the expansion of (1+x)⁵, coefficient of x² is:",
            options=listOf("5", "10", "15", "20"), correctOptionIndex=1,
            explanation="C(5,2) = 10 is the coefficient of x²."),

        // Sequence & Series
        Question(examType="JEE", subject="Maths", chapter="Sequence & Series", difficulty="Medium", year=2023,
            questionText="Sum of first 10 terms of AP: 2, 5, 8, 11,...",
            options=listOf("145", "155", "125", "165"), correctOptionIndex=1,
            explanation="S = n/2(2a + (n-1)d) = 10/2(4 + 9×3) = 5(4+27) = 5×31 = 155."),
        Question(examType="JEE", subject="Maths", chapter="Sequence & Series", difficulty="Medium", year=2022,
            questionText="Sum of infinite GP: 1 + 1/2 + 1/4 + ... (r=1/2) is:",
            options=listOf("1", "2", "3", "4"), correctOptionIndex=1,
            explanation="S∞ = a/(1-r) = 1/(1-1/2) = 1/(1/2) = 2."),

        // Limit, Continuity & Differentiability
        Question(examType="JEE", subject="Maths", chapter="Limit, Continuity & Differentiability", difficulty="Medium", year=2023,
            questionText="lim(x→0) sin(x)/x equals:",
            options=listOf("0", "∞", "1", "x"), correctOptionIndex=2,
            explanation="Standard limit: lim(x→0) sin(x)/x = 1."),
        Question(examType="JEE", subject="Maths", chapter="Limit, Continuity & Differentiability", difficulty="Medium", year=2022,
            questionText="Derivative of sin(x) with respect to x is:",
            options=listOf("-cos(x)", "cos(x)", "sin(x)", "-sin(x)"), correctOptionIndex=1,
            explanation="d/dx[sin(x)] = cos(x)."),

        // Integral Calculus
        Question(examType="JEE", subject="Maths", chapter="Integral Calculus", difficulty="Hard", year=2023,
            questionText="Value of ∫₀^π sin(x) dx is:",
            options=listOf("0", "1", "2", "π"), correctOptionIndex=2,
            explanation="∫₀^π sin(x) dx = [-cos(x)]₀^π = -cos(π)+cos(0) = 1+1 = 2."),
        Question(examType="JEE", subject="Maths", chapter="Integral Calculus", difficulty="Medium", year=2022,
            questionText="∫ x² dx equals:",
            options=listOf("2x", "x³/3 + C", "x³ + C", "3x²"), correctOptionIndex=1,
            explanation="∫ xⁿ dx = xⁿ⁺¹/(n+1) + C → x³/3 + C."),

        // Coordinate Geometry
        Question(examType="JEE", subject="Maths", chapter="Coordinate Geometry", difficulty="Medium", year=2022,
            questionText="Distance between (3,4) and (-1,-2) is:",
            options=listOf("√40", "√52", "√60", "√72"), correctOptionIndex=1,
            explanation="d = √[(3-(-1))² + (4-(-2))²] = √[16+36] = √52."),
        Question(examType="JEE", subject="Maths", chapter="Coordinate Geometry", difficulty="Medium", year=2023,
            questionText="Equation of a circle with centre (1,2) and radius 3 is:",
            options=listOf("(x-1)²+(y-2)²=9", "(x+1)²+(y+2)²=9", "(x-1)²+(y-2)²=3", "x²+y²=9"), correctOptionIndex=0,
            explanation="Standard form: (x-h)²+(y-k)²=r² → (x-1)²+(y-2)²=9."),

        // Three Dimensional Geometry
        Question(examType="JEE", subject="Maths", chapter="Three Dimensional Geometry", difficulty="Medium", year=2023,
            questionText="Direction cosines of x-axis are:",
            options=listOf("(0,0,1)", "(0,1,0)", "(1,0,0)", "(1,1,1)"), correctOptionIndex=2,
            explanation="x-axis has direction ratios (1,0,0); direction cosines are (1,0,0)."),
        Question(examType="JEE", subject="Maths", chapter="Three Dimensional Geometry", difficulty="Hard", year=2022,
            questionText="Distance from origin to plane 2x+3y+6z=7 is:",
            options=listOf("1", "7/7", "7/√49", "1 unit"), correctOptionIndex=0,
            explanation="d = |2(0)+3(0)+6(0)-7|/√(4+9+36) = 7/√49 = 7/7 = 1."),

        // Vector Algebra
        Question(examType="JEE", subject="Maths", chapter="Vector Algebra", difficulty="Easy", year=2022,
            questionText="If |a⃗|=3 and |b⃗|=4, and they are perpendicular, then |a⃗+b⃗| is:",
            options=listOf("5", "7", "1", "12"), correctOptionIndex=0,
            explanation="|a⃗+b⃗|² = |a⃗|²+|b⃗|² (perpendicular) = 9+16 = 25 → |a⃗+b⃗|=5."),
        Question(examType="JEE", subject="Maths", chapter="Vector Algebra", difficulty="Medium", year=2023,
            questionText="Cross product of two parallel vectors is:",
            options=listOf("A unit vector", "Zero vector", "The vectors themselves", "A scalar"), correctOptionIndex=1,
            explanation="a⃗ × b⃗ = |a||b|sinθ n̂; for parallel vectors θ=0°, sin0°=0 → zero vector."),

        // Probability
        Question(examType="JEE", subject="Maths", chapter="Probability", difficulty="Easy", year=2022,
            questionText="Probability of getting head when a fair coin is tossed:",
            options=listOf("1", "0", "1/2", "2"), correctOptionIndex=2,
            explanation="Two equally likely outcomes (H,T); P(H) = 1/2."),
        Question(examType="JEE", subject="Maths", chapter="Probability", difficulty="Medium", year=2023,
            questionText="P(A∪B) = P(A) + P(B) - P(A∩B). If P(A)=0.4, P(B)=0.3, P(A∩B)=0.1, then P(A∪B)=",
            options=listOf("0.6", "0.7", "0.8", "1.0"), correctOptionIndex=0,
            explanation="P(A∪B) = 0.4 + 0.3 - 0.1 = 0.6."),

        // Trigonometry
        Question(examType="JEE", subject="Maths", chapter="Trigonometry", difficulty="Easy", year=2022,
            questionText="Value of sin(30°) + cos(60°) is:",
            options=listOf("1", "0", "√3", "1/2"), correctOptionIndex=0,
            explanation="sin30°=1/2, cos60°=1/2 → sum = 1."),
        Question(examType="JEE", subject="Maths", chapter="Trigonometry", difficulty="Medium", year=2023,
            questionText="sin²θ + cos²θ equals:",
            options=listOf("0", "2", "1", "sinθ cosθ"), correctOptionIndex=2,
            explanation="Fundamental trigonometric identity: sin²θ + cos²θ = 1."),

        // Mathematical Reasoning
        Question(examType="JEE", subject="Maths", chapter="Mathematical Reasoning", difficulty="Easy", year=2022,
            questionText="The negation of 'All integers are rational numbers' is:",
            options=listOf("No integer is rational", "Some integers are not rational", "All rationals are integers", "Integers are irrational"), correctOptionIndex=1,
            explanation="Negation of 'All P are Q' is 'Some P are not Q'."),
        Question(examType="JEE", subject="Maths", chapter="Mathematical Reasoning", difficulty="Medium", year=2023,
            questionText="p ∧ q is TRUE only when:",
            options=listOf("Both p and q are true", "p is true only", "q is true only", "At least one is true"), correctOptionIndex=0,
            explanation="Conjunction (AND) p ∧ q is true only when both p and q are true."),

        // Statistics
        Question(examType="JEE", subject="Maths", chapter="Statistics", difficulty="Easy", year=2022,
            questionText="Mean of 2, 4, 6, 8, 10 is:",
            options=listOf("4", "5", "6", "8"), correctOptionIndex=2,
            explanation="Mean = (2+4+6+8+10)/5 = 30/5 = 6."),
        Question(examType="JEE", subject="Maths", chapter="Statistics", difficulty="Medium", year=2023,
            questionText="Standard deviation is the square root of:",
            options=listOf("Mean", "Median", "Variance", "Range"), correctOptionIndex=2,
            explanation="Standard deviation σ = √variance.")
    )

    // ── NEET Biology ─────────────────────────────────────────────────────────

    private fun biologyQuestions(): List<Question> = listOf(

        // Cell Biology
        Question(examType="NEET", subject="Biology", chapter="Cell Biology", difficulty="Easy", year=2023,
            questionText="Which organelle is the 'powerhouse of the cell'?",
            options=listOf("Nucleus", "Ribosome", "Mitochondria", "Golgi apparatus"), correctOptionIndex=2,
            explanation="Mitochondria produce ATP via oxidative phosphorylation — the cell's main energy source."),
        Question(examType="NEET", subject="Biology", chapter="Cell Biology", difficulty="Medium", year=2022,
            questionText="Which organelle is the site of protein synthesis?",
            options=listOf("Lysosome", "Ribosome", "Golgi body", "Vacuole"), correctOptionIndex=1,
            explanation="Ribosomes translate mRNA into polypeptide chains (protein synthesis)."),

        // Genetics
        Question(examType="NEET", subject="Biology", chapter="Genetics", difficulty="Hard", year=2023,
            questionText="In a dihybrid cross AaBb × AaBb, fraction of offspring showing both dominant traits (A_B_):",
            options=listOf("1/4", "3/4", "9/16", "1/16"), correctOptionIndex=2,
            explanation="3/4 × 3/4 = 9/16 (product of independent monohybrid ratios)."),
        Question(examType="NEET", subject="Biology", chapter="Genetics", difficulty="Medium", year=2022,
            questionText="In Mendel's law of segregation, a hybrid Aa produces gametes in ratio:",
            options=listOf("3A:1a", "1A:1a", "2A:1a", "1A:2a"), correctOptionIndex=1,
            explanation="Alleles segregate equally → 50% A gametes, 50% a gametes (1:1 ratio)."),

        // Evolution
        Question(examType="NEET", subject="Biology", chapter="Evolution", difficulty="Medium", year=2022,
            questionText="Hardy-Weinberg equilibrium is maintained when:",
            options=listOf("Mutations occur frequently", "Natural selection operates", "Random mating, large population, no selection", "Migration occurs"), correctOptionIndex=2,
            explanation="HWE requires: random mating, large population, no mutation, no migration, no selection."),
        Question(examType="NEET", subject="Biology", chapter="Evolution", difficulty="Easy", year=2023,
            questionText="Darwin's theory of evolution is based on:",
            options=listOf("Inheritance of acquired characters", "Natural selection and survival of the fittest", "Mutation theory", "Punctuated equilibrium"), correctOptionIndex=1,
            explanation="Darwin proposed that organisms better adapted to their environment survive and reproduce more."),

        // Human Physiology
        Question(examType="NEET", subject="Biology", chapter="Human Physiology", difficulty="Medium", year=2022,
            questionText="Enzyme that converts fibrinogen to fibrin during blood clotting:",
            options=listOf("Thrombin", "Thromboplastin", "Prothrombin", "Heparin"), correctOptionIndex=0,
            explanation="Thrombin is the active enzyme that converts soluble fibrinogen to insoluble fibrin threads."),
        Question(examType="NEET", subject="Biology", chapter="Human Physiology", difficulty="Easy", year=2023,
            questionText="Insulin is secreted by:",
            options=listOf("Alpha cells of Islets of Langerhans", "Beta cells of Islets of Langerhans", "Adrenal cortex", "Thyroid gland"), correctOptionIndex=1,
            explanation="Beta cells of the Islets of Langerhans in the pancreas secrete insulin."),

        // Plant Physiology
        Question(examType="NEET", subject="Biology", chapter="Plant Physiology", difficulty="Medium", year=2022,
            questionText="Correct sequence in the Calvin cycle:",
            options=listOf("CO₂ fixation → Reduction → Regeneration of RuBP", "Reduction → CO₂ fixation → Regeneration", "Regeneration → Reduction → CO₂ fixation", "CO₂ fixation → Regeneration → Reduction"), correctOptionIndex=0,
            explanation="Calvin cycle: 1) CO₂ fixation by RuBisCO, 2) Reduction using ATP+NADPH, 3) Regeneration of RuBP."),
        Question(examType="NEET", subject="Biology", chapter="Plant Physiology", difficulty="Easy", year=2023,
            questionText="Transpiration primarily occurs through:",
            options=listOf("Root hairs", "Stomata", "Lenticels", "Cuticle"), correctOptionIndex=1,
            explanation="About 90% of water loss in plants occurs through stomata (transpiration)."),

        // Reproduction
        Question(examType="NEET", subject="Biology", chapter="Reproduction", difficulty="Medium", year=2023,
            questionText="Which of the following is an example of vegetative propagation?",
            options=listOf("Seed germination", "Spore formation", "Grafting", "Fertilisation"), correctOptionIndex=2,
            explanation="Grafting is an artificial method of vegetative (asexual) propagation."),
        Question(examType="NEET", subject="Biology", chapter="Reproduction", difficulty="Easy", year=2022,
            questionText="In humans, the site of fertilisation is usually:",
            options=listOf("Uterus", "Ovary", "Fallopian tube", "Cervix"), correctOptionIndex=2,
            explanation="Fertilisation normally occurs in the ampulla region of the fallopian tube."),

        // Ecology
        Question(examType="NEET", subject="Biology", chapter="Ecology", difficulty="Medium", year=2023,
            questionText="Which trophic level has the maximum energy in a food chain?",
            options=listOf("Secondary consumers", "Primary consumers", "Producers", "Decomposers"), correctOptionIndex=2,
            explanation="Producers (plants) fix solar energy; they have the maximum energy (10% is transferred per level)."),
        Question(examType="NEET", subject="Biology", chapter="Ecology", difficulty="Easy", year=2022,
            questionText="Symbiosis between Rhizobium and legumes is an example of:",
            options=listOf("Parasitism", "Commensalism", "Mutualism", "Competition"), correctOptionIndex=2,
            explanation="Both benefit: Rhizobium gets nutrients, plant gets fixed nitrogen — mutualism."),

        // Biomolecules (Biology)
        Question(examType="NEET", subject="Biology", chapter="Biomolecules", difficulty="Medium", year=2023,
            questionText="Which enzyme is responsible for DNA replication?",
            options=listOf("RNA polymerase", "DNA polymerase", "Ligase", "Helicase alone"), correctOptionIndex=1,
            explanation="DNA polymerase III (in prokaryotes) synthesises new DNA strands using existing strands as templates."),
        Question(examType="NEET", subject="Biology", chapter="Biomolecules", difficulty="Easy", year=2022,
            questionText="Peptide bond is formed between:",
            options=listOf("Two sugars", "Amino acids", "Fatty acids", "Nucleotides"), correctOptionIndex=1,
            explanation="Peptide bonds (-CO-NH-) link amino acids together in polypeptide chains."),

        // Microbes In Human Welfare
        Question(examType="NEET", subject="Biology", chapter="Microbes In Human Welfare", difficulty="Easy", year=2022,
            questionText="Penicillin is obtained from:",
            options=listOf("Bacteria", "Virus", "Penicillium notatum (fungus)", "Algae"), correctOptionIndex=2,
            explanation="Penicillin was discovered from the mould Penicillium notatum by Alexander Fleming."),
        Question(examType="NEET", subject="Biology", chapter="Microbes In Human Welfare", difficulty="Medium", year=2023,
            questionText="Biogas is mainly composed of:",
            options=listOf("CO₂", "H₂", "Methane (CH₄)", "N₂"), correctOptionIndex=2,
            explanation="Biogas contains mainly methane (~60-70%) produced by methanogenic bacteria."),

        // Biotechnology
        Question(examType="NEET", subject="Biology", chapter="Biotechnology", difficulty="Medium", year=2023,
            questionText="Restriction endonucleases are used in genetic engineering to:",
            options=listOf("Join DNA fragments", "Cut DNA at specific sequences", "Replicate DNA", "Transcribe DNA"), correctOptionIndex=1,
            explanation="Restriction enzymes are 'molecular scissors' that cut DNA at specific palindromic sequences."),
        Question(examType="NEET", subject="Biology", chapter="Biotechnology", difficulty="Easy", year=2022,
            questionText="Bt toxin used in Bt cotton is derived from:",
            options=listOf("Bacillus subtilis", "Bacillus thuringiensis", "Bacillus cereus", "Pseudomonas"), correctOptionIndex=1,
            explanation="Bt toxin (Cry protein) is produced by Bacillus thuringiensis and is toxic to insect pests."),

        // Animal Kingdom
        Question(examType="NEET", subject="Biology", chapter="Animal Kingdom", difficulty="Medium", year=2022,
            questionText="Which phylum has animals with a water vascular system?",
            options=listOf("Mollusca", "Annelida", "Echinodermata", "Arthropoda"), correctOptionIndex=2,
            explanation="Echinoderms (starfish, sea urchins) have a unique water vascular system for locomotion."),
        Question(examType="NEET", subject="Biology", chapter="Animal Kingdom", difficulty="Easy", year=2023,
            questionText="Insects belong to phylum:",
            options=listOf("Annelida", "Mollusca", "Arthropoda", "Chordata"), correctOptionIndex=2,
            explanation="Insects are arthropods — they have jointed appendages and an exoskeleton."),

        // Plant Kingdom
        Question(examType="NEET", subject="Biology", chapter="Plant Kingdom", difficulty="Easy", year=2022,
            questionText="Algae are placed in division:",
            options=listOf("Bryophyta", "Thallophyta", "Pteridophyta", "Gymnospermae"), correctOptionIndex=1,
            explanation="Algae, lichens, and fungi are classified under Thallophyta (simple thallus-like body)."),
        Question(examType="NEET", subject="Biology", chapter="Plant Kingdom", difficulty="Medium", year=2023,
            questionText="Which of the following is a non-vascular land plant?",
            options=listOf("Fern", "Moss", "Pine", "Wheat"), correctOptionIndex=1,
            explanation="Mosses (Bryophyta) lack vascular tissue (xylem and phloem) — they are non-vascular."),

        // Morphology Of Flowering Plants
        Question(examType="NEET", subject="Biology", chapter="Morphology Of Flowering Plants", difficulty="Easy", year=2022,
            questionText="Tap root system is found in:",
            options=listOf("Wheat", "Maize", "Mango", "Rice"), correctOptionIndex=2,
            explanation="Mango (dicot) has a tap root system with a main root and lateral branches."),
        Question(examType="NEET", subject="Biology", chapter="Morphology Of Flowering Plants", difficulty="Medium", year=2023,
            questionText="Petiole is the part of a leaf that:",
            options=listOf("Manufactures food", "Connects leaf blade to stem", "Absorbs water", "Exchanges gases"), correctOptionIndex=1,
            explanation="Petiole is the stalk that attaches the leaf blade (lamina) to the stem."),

        // Anatomy Of Flowering Plants
        Question(examType="NEET", subject="Biology", chapter="Anatomy Of Flowering Plants", difficulty="Medium", year=2022,
            questionText="Xylem transports:",
            options=listOf("Food from leaves", "Water and minerals from roots", "Hormones", "Oxygen"), correctOptionIndex=1,
            explanation="Xylem conducts water and dissolved minerals upward from roots to leaves."),
        Question(examType="NEET", subject="Biology", chapter="Anatomy Of Flowering Plants", difficulty="Easy", year=2023,
            questionText="Phloem is responsible for:",
            options=listOf("Transport of water", "Transport of food (sugars)", "Gas exchange", "Structural support"), correctOptionIndex=1,
            explanation="Phloem transports photosynthates (sugars) from leaves to other parts of the plant."),

        // Structural Organisation In Animals
        Question(examType="NEET", subject="Biology", chapter="Structural Organisation In Animals", difficulty="Easy", year=2022,
            questionText="Cartilage is a type of:",
            options=listOf("Epithelial tissue", "Muscular tissue", "Connective tissue", "Nervous tissue"), correctOptionIndex=2,
            explanation="Cartilage is a specialised connective tissue with a flexible matrix of collagen and proteoglycans."),
        Question(examType="NEET", subject="Biology", chapter="Structural Organisation In Animals", difficulty="Medium", year=2023,
            questionText="Cockroach's respiratory organ is:",
            options=listOf("Lungs", "Gills", "Tracheae", "Book lungs"), correctOptionIndex=2,
            explanation="Cockroaches (and other insects) respire through tracheae — a network of air tubes.")
    )
}
