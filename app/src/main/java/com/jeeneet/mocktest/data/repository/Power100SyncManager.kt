package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jeeneet.mocktest.data.model.Power100Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Syncs the fixed Power 100 question sets from Firestore into Room.
 *
 * Caching strategy:
 *   - Questions are persisted in Room (offline-first, survives app restarts).
 *   - A Firestore version check is skipped if questions are present AND the last
 *     check was less than CACHE_TTL_MS ago (default 24 h).
 *   - If the admin script increments the Firestore version, the next check after
 *     the TTL expires will detect it and re-download the full set.
 *   - Force-refresh is possible via forceSync().
 *
 * Firestore structure:
 *   standard_tests/{JEE|NEET}
 *     version:    3   ← increment via admin script to trigger refresh
 *     questions:  [ { subject, chapter, difficulty, questionText,
 *                     options:[4 strings], correctOptionIndex, explanation }, ... ]
 */
class Power100SyncManager(private val context: Context) {

    companion object {
        private const val TAG          = "Power100Sync"
        private const val PREFS        = "power100_prefs"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L   // 24 hours

        private fun versionKey(exam: String)     = "cached_version_$exam"
        private fun lastCheckedKey(exam: String) = "last_checked_$exam"
    }

    private val firestore = Firebase.firestore
    private val dao   = MockTestDatabase.getInstance(context).power100Dao()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Main entry point. Skips the Firestore network call if:
     *   1. All 100 questions are already in Room, AND
     *   2. The last successful check was less than 24 hours ago.
     */
    suspend fun checkAndSyncIfNeeded(exam: String): SyncState = withContext(Dispatchers.IO) {
        val localCount   = dao.getCount(exam)
        val lastChecked  = prefs.getLong(lastCheckedKey(exam), 0L)
        val cacheAge     = System.currentTimeMillis() - lastChecked
        val cacheIsWarm  = localCount == 100 && cacheAge < CACHE_TTL_MS

        if (cacheIsWarm) {
            Log.d(TAG, "$exam cache is fresh (${cacheAge / 60_000}m old). Skipping network check.")
            return@withContext SyncState.UP_TO_DATE
        }

        syncFromFirestoreState(exam)
    }

    /**
     * Bypasses the TTL and checks Firestore for new versions without wiping
     * the local version pointer. If Firestore version matches local, returns
     * SyncState.UP_TO_DATE without altering questions or user test progress.
     */
    suspend fun forceSyncState(exam: String): SyncState = withContext(Dispatchers.IO) {
        Log.i(TAG, "Force sync requested for $exam")
        prefs.edit()
            .remove(lastCheckedKey(exam))
            .apply()
        syncFromFirestoreState(exam)
    }

    suspend fun forceSync(exam: String): String? = withContext(Dispatchers.IO) {
        val state = forceSyncState(exam)
        if (state == SyncState.FAILED) "Failed to sync Power 100 questions from server." else null
    }

    fun getLocalVersion(exam: String): Long  = prefs.getLong(versionKey(exam), -1L)
    fun getCacheAgeMinutes(exam: String): Long {
        val last = prefs.getLong(lastCheckedKey(exam), 0L)
        return if (last == 0L) -1L else (System.currentTimeMillis() - last) / 60_000L
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private suspend fun syncFromFirestoreState(exam: String): SyncState {
        return try {
            val doc = firestore.collection("standard_tests").document(exam).get().await()

            if (!doc.exists()) {
                val msg = "No Power 100 data found in Firestore for $exam. Run the upload script first."
                Log.w(TAG, msg)
                return SyncState.FAILED
            }

            val remoteVersion = doc.getLong("version") ?: 0L
            val localVersion  = prefs.getLong(versionKey(exam), -1L)
            val localCount    = dao.getCount(exam)

            // Record that we checked — even if nothing changed, reset the TTL
            prefs.edit().putLong(lastCheckedKey(exam), System.currentTimeMillis()).apply()

            if (remoteVersion <= localVersion && localCount == 100) {
                Log.d(TAG, "$exam is up to date (v$localVersion, $localCount questions). Skipping Room replace.")
                return SyncState.UP_TO_DATE
            }

            @Suppress("UNCHECKED_CAST")
            val raw = doc.get("questions") as? List<Map<String, Any>>
            if (raw.isNullOrEmpty()) {
                val msg = "Firestore document exists but questions field is empty for $exam."
                Log.w(TAG, msg)
                return SyncState.FAILED
            }

            val questions = raw.mapIndexedNotNull { idx, map -> parseQuestion(exam, idx + 1, map) }

            if (questions.isEmpty()) {
                val msg = "All ${raw.size} questions failed to parse for $exam."
                Log.w(TAG, msg)
                return SyncState.FAILED
            }

            if (questions.size < raw.size) {
                Log.w(TAG, "${raw.size - questions.size} questions skipped due to parse errors for $exam.")
            }

            // Only reset progress if a newer version was actually published on the server!
            if (localVersion >= 0 && remoteVersion > localVersion) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                dao.resetProgress(uid, exam)
                Log.i(TAG, "Power100 progress reset for $exam (v$localVersion -> v$remoteVersion)")
            }

            dao.deleteForExam(exam)
            dao.insertQuestions(questions)
            prefs.edit().putLong(versionKey(exam), remoteVersion).apply()
            Log.i(TAG, "Power100 synced for $exam: v$remoteVersion, ${questions.size} questions")
            com.jeeneet.mocktest.utils.AnalyticsManager.syncCompleted(
                context,
                syncType = "power100_$exam",
                version = remoteVersion.toInt(),
                freshCount = questions.size,
                localTotal = questions.size,
                durationMs = 0L
            )
            SyncState.UPDATED

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Sync failed for $exam: $msg")
            // Don't update lastCheckedKey on failure — next launch will retry
            com.jeeneet.mocktest.utils.AnalyticsManager.syncFailed(context, "power100_$exam", msg)
            SyncState.FAILED
        }
    }

    private fun parseQuestion(exam: String, position: Int, map: Map<String, Any>): Power100Question? {
        return try {
            val options = QuestionFirestoreParser.parseOptions(map["options"]) ?: return null
            val imageUrl = (map["imageUrl"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            val solutionImageUrl = (map["solutionImageUrl"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            val optionImageUrls = (map["optionImageUrls"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

            Power100Question(
                examType = exam,
                position = position,
                subject = map["subject"] as? String ?: return null,
                chapter = map["chapter"] as? String ?: "",
                difficulty = map["difficulty"] as? String ?: "Medium",
                questionText = map["questionText"] as? String ?: return null,
                options = options,
                correctOptionIndex = QuestionFirestoreParser.parseCorrectIndex(map) ?: return null,
                explanation = map["explanation"] as? String ?: "",
                imageUrl = imageUrl,
                solutionImageUrl = solutionImageUrl,
                optionImageUrls = optionImageUrls
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse question at position $position: ${e.message}")
            null
        }
    }
}
