package com.jeeneet.mocktest.data.repository

import android.content.Context
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.model.TestResult
import com.jeeneet.mocktest.utils.PrefManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiCounselorRepository(private val context: Context) {
    private val groq = GroqRepository(context)
    private val db = MockTestDatabase.getInstance(context)

    suspend fun getInsights(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        val results = db.testResultDao().getAllResultsOnce(uid)
        
        val cached = PrefManager.getAiAdviceCache(context, "insights")
        val cachedCount = PrefManager.getAiAdviceCacheTestCount(context, "insights")
        
        if (!forceRefresh && cached.isNotEmpty() && cachedCount == results.size) {
            return@withContext Result.success(cached)
        }

        val performanceData = aggregatePerformance(results)
        val prompt = buildInsightsPrompt(performanceData)
        
        groq.getInsights(prompt).onSuccess {
            PrefManager.setAiAdviceCache(context, "insights", it, results.size)
        }
    }

    suspend fun getDailyStudyPlan(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        
        // Cache plan by day (YYYY-MM-DD)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val cachedDay = PrefManager.getAiAdviceCache(context, "plan_day")
        val cachedPlan = PrefManager.getAiAdviceCache(context, "plan_content")
        
        if (!forceRefresh && cachedDay == today && cachedPlan.isNotEmpty()) {
            return@withContext Result.success(cachedPlan)
        }

        val results = db.testResultDao().getAllResultsOnce(uid)
        val weakChapters = findWeakChapters(results)
        val prompt = buildStudyPlanPrompt(weakChapters)
        
        groq.getInsights(prompt).onSuccess {
            PrefManager.setAiAdviceCache(context, "plan_day", today, 0)
            PrefManager.setAiAdviceCache(context, "plan_content", it, 0)
        }
    }

    suspend fun getAtRiskAnalysis(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        val results = db.testResultDao().getAllResultsOnce(uid)
        
        val cached = PrefManager.getAiAdviceCache(context, "at_risk")
        val cachedCount = PrefManager.getAiAdviceCacheTestCount(context, "at_risk")
        
        if (!forceRefresh && cached.isNotEmpty() && cachedCount == results.size) {
            return@withContext Result.success(cached)
        }

        val riskData = aggregateRiskData(results)
        val prompt = buildAtRiskPrompt(riskData)
        
        groq.getInsights(prompt).onSuccess {
            PrefManager.setAiAdviceCache(context, "at_risk", it, results.size)
        }
    }

    suspend fun getWeaknessDeepDive(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        val results = db.testResultDao().getAllResultsOnce(uid)
        
        val cached = PrefManager.getAiAdviceCache(context, "deep_dive")
        val cachedCount = PrefManager.getAiAdviceCacheTestCount(context, "deep_dive")
        if (!forceRefresh && cached.isNotEmpty() && cachedCount == results.size) {
            return@withContext Result.success(cached)
        }

        val weakChapters = findDetailedWeakness(results)
        if (weakChapters.isEmpty()) return@withContext Result.success("No significant weaknesses found yet. Keep practicing!")
        
        val prompt = buildDeepDivePrompt(weakChapters)
        groq.getInsights(prompt).onSuccess {
            PrefManager.setAiAdviceCache(context, "deep_dive", it, results.size)
        }
    }

    // New: Get the raw stats behind the AI's logic
    fun getDeepDiveStats(results: List<TestResult>): String {
        return findDetailedWeakness(results)
    }

    private fun findDetailedWeakness(results: List<TestResult>): String {
        val stats = mutableMapOf<String, Triple<Int, Int, Long>>() // Correct, Total, TimeMs
        val gson = Gson()
        val typeQ = object : TypeToken<List<Question>>() {}.type
        val typeA = object : TypeToken<Map<String, Int?>>() {}.type
        val typeT = object : TypeToken<Map<String, Long?>>() {}.type

        results.take(20).forEach { r ->
            runCatching {
                val qs = gson.fromJson<List<Question>>(r.questionsJson, typeQ) ?: return@forEach
                val ans = gson.fromJson<Map<String, Int?>>(r.answersJson, typeA) ?: return@forEach
                val times = gson.fromJson<Map<String, Long?>>(r.questionTimesJson, typeT) ?: emptyMap()
                
                qs.forEachIndexed { idx, q ->
                    val key = "${q.subject}: ${q.chapter}"
                    val cur = stats[key] ?: Triple(0, 0, 0L)
                    val correct = if (ans[idx.toString()] == q.correctOptionIndex) 1 else 0
                    val attempted = if (ans[idx.toString()] != null) 1 else 0
                    val time = times[idx.toString()] ?: 0L
                    
                    if (attempted == 1) {
                        stats[key] = Triple(cur.first + correct, cur.second + 1, cur.third + time)
                    }
                }
            }
        }

        return stats.entries
            .filter { it.value.second >= 3 }
            .sortedBy { it.value.first.toFloat() / it.value.second }
            .take(3)
            .joinToString("\n") { (chap, s) ->
                val acc = s.first.toFloat() / s.second * 100
                val avgTime = s.third / s.second / 1000
                "- $chap: ${"%.0f%%".format(acc)} accuracy, ${avgTime}s avg time"
            }
    }

    private fun buildDeepDivePrompt(data: String) = """
        Detailed Chapter Analysis:
        $data
        
        Analyze these weaknesses. 
        For each, say WHY they might be failing (e.g., "Slow speed" or "Low accuracy").
        Provide a 1-sentence specific advice for the weakest one.
        Keep it under 60 words.
    """.trimIndent()

    private fun aggregateRiskData(results: List<TestResult>): String {
        if (results.size < 3) return "Not enough data to predict risks."
        
        val subjectRisk = mutableMapOf<String, String>()
        val sorted = results.sortedBy { it.completedAt }
        
        val historical = sorted.dropLast(2)
        val recent = sorted.takeLast(2)
        
        val subjects = sorted.map { it.subject }.distinct()
        subjects.forEach { subj ->
            if (subj == "Full Paper") return@forEach
            val histAcc = historical.filter { it.subject == subj }.map { it.correct.toDouble() / (it.correct + it.wrong).coerceAtLeast(1) }.average()
            val recAcc = recent.filter { it.subject == subj }.map { it.correct.toDouble() / (it.correct + it.wrong).coerceAtLeast(1) }.average()
            
            if (recAcc < (histAcc - 0.15)) {
                subjectRisk[subj] = "DECLINING (was ${"%.0f%%".format(histAcc * 100)}, now ${"%.0f%%".format(recAcc * 100)})"
            }
        }
        
        return if (subjectRisk.isEmpty()) "No immediate risks detected." else subjectRisk.entries.joinToString("\n")
    }

    private fun buildAtRiskPrompt(riskData: String) = """
        Performance Risk Data:
        $riskData
        
        Identify the most critical "At-Risk" topic. 
        Format:
        ⚠️ [Subject]: [Reason for risk]
        💡 Recommendation: [1 specific action]
        
        Keep it very short (under 30 words total).
    """.trimIndent()

    private fun findWeakChapters(results: List<TestResult>): List<String> {
        val chapterStats = mutableMapOf<String, Pair<Int, Int>>() // Key -> (Correct, Total)
        val gson = Gson()
        val typeQ = object : TypeToken<List<Question>>() {}.type
        val typeA = object : TypeToken<Map<String, Int?>>() {}.type
        
        results.take(15).forEach { r ->
            runCatching {
                if (r.questionsJson.isEmpty() || r.answersJson.isEmpty()) return@forEach
                val qs = gson.fromJson<List<Question>>(r.questionsJson, typeQ) ?: return@forEach
                val ans = gson.fromJson<Map<String, Int?>>(r.answersJson, typeA) ?: return@forEach
                
                qs.forEachIndexed { idx, q ->
                    val key = "${q.subject}: ${q.chapter}"
                    val current = chapterStats[key] ?: Pair(0, 0)
                    val isCorrect = ans[idx.toString()] == q.correctOptionIndex
                    val isAttempted = ans[idx.toString()] != null
                    
                    if (isAttempted) {
                        chapterStats[key] = Pair(
                            current.first + (if (isCorrect) 1 else 0),
                            current.second + 1
                        )
                    }
                }
            }
        }
        
        // Filter chapters with at least 3 attempts and accuracy < 60%
        return chapterStats.entries
            .filter { it.value.second >= 3 }
            .map { it.key to (it.value.first.toFloat() / it.value.second) }
            .filter { it.second < 0.6f }
            .sortedBy { it.second }
            .map { it.first }
            .take(3)
    }

    private fun buildStudyPlanPrompt(weakTopics: List<String>) = """
        Student's Weakest Topics: ${weakTopics.joinToString(", ")}
        
        Create a "Daily Study Target" for today. 
        Format:
        1. 🌅 Morning: [Task]
        2. ☀️ Afternoon: [Task]
        3. 🌙 Evening: [Task]
        
        Focus on the weak topics. Keep tasks under 15 words each. Use emojis.
    """.trimIndent()

    suspend fun getStrategy(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        val results = db.testResultDao().getAllResultsOnce(uid)
        
        val cached = PrefManager.getAiAdviceCache(context, "strategy")
        val cachedCount = PrefManager.getAiAdviceCacheTestCount(context, "strategy")
        
        if (!forceRefresh && cached.isNotEmpty() && cachedCount == results.size) {
            return@withContext Result.success(cached)
        }

        val subjectData = aggregateSubjects(results)
        val prompt = buildStrategyPrompt(subjectData)
        
        groq.getInsights(prompt).onSuccess {
            PrefManager.setAiAdviceCache(context, "strategy", it, results.size)
        }
    }

    private fun aggregatePerformance(results: List<TestResult>): String {
        if (results.isEmpty()) return "No tests taken yet."
        
        val totalCorrect = results.sumOf { it.correct }
        val totalWrong = results.sumOf { it.wrong }
        val avgAccuracy = if (totalCorrect + totalWrong > 0) totalCorrect.toFloat() / (totalCorrect + totalWrong) * 100 else 0f
        val streak = PrefManager.getStreak(context)
        
        val readinessScore = calculateReadinessScore(results)
        
        return """
            Overall Accuracy: ${"%.1f%%".format(avgAccuracy)}
            Total Tests: ${results.size}
            Exam Readiness Index: $readinessScore%
            Current Streak: $streak days
            Recent Trend: ${if (results.size >= 2 && results[0].correct > results[1].correct) "Improving" else "Stable/Declining"}
        """.trimIndent()
    }

    private fun aggregateSubjects(results: List<TestResult>): String {
        val subjectMap = mutableMapOf<String, Pair<Int, Int>>()
        results.forEach { r ->
            val subj = r.subject.ifEmpty { "Full Paper" }
            val existing = subjectMap[subj] ?: Pair(0, 0)
            subjectMap[subj] = Pair(existing.first + r.correct, existing.second + r.totalQuestions)
        }
        
        return subjectMap.entries.joinToString("\n") { (subj, stats) ->
            val acc = if (stats.second > 0) stats.first.toFloat() / stats.second * 100 else 0f
            "$subj: ${"%.0f%%".format(acc)} accuracy"
        }
    }

    fun calculateReadinessScore(results: List<TestResult>): Int {
        if (results.isEmpty()) return 0
        
        // Weights: Accuracy (50%), Frequency (20%), Recent Improvement (30%)
        val totalCorrect = results.sumOf { it.correct }
        val totalAttempted = results.sumOf { it.attempted }
        val accuracy = if (totalAttempted > 0) totalCorrect.toFloat() / totalAttempted else 0f
        
        val frequencyScore = (results.size / 10f).coerceIn(0f, 1f)
        
        val improvement = if (results.size >= 3) {
            val recent = results.take(2).map { it.score.toDouble() / it.maxScore.coerceAtLeast(1f) }.average()
            val older = results.drop(2).take(3).map { it.score.toDouble() / it.maxScore.coerceAtLeast(1f) }.average()
            if (recent > older) 1f else 0.5f
        } else 0.5f
        
        val finalScore = (accuracy * 50 + frequencyScore * 20 + improvement * 30).toInt()
        return finalScore.coerceIn(5, 98) // Never 100%, always room to grow
    }

    private fun buildInsightsPrompt(data: String) = """
        User Performance Snapshot:
        $data
        
        Provide high-level behavior coaching. Mention the "Exam Readiness Index" and explain what it means.
    """.trimIndent()

    private fun buildStrategyPrompt(data: String) = """
        Subject Performance:
        $data
        
        Provide a competitive exam strategy. Tell the student which subject needs the most attention today.
    """.trimIndent()
}
