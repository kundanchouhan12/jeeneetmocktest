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
    suspend fun checkAndSyncIfNeeded(exam: String) = withContext(Dispatchers.IO) {
        val localCount   = dao.getCount(exam)
        val lastChecked  = prefs.getLong(lastCheckedKey(exam), 0L)
        val cacheAge     = System.currentTimeMillis() - lastChecked
        val cacheIsWarm  = localCount == 100 && cacheAge < CACHE_TTL_MS

        if (cacheIsWarm) {
            Log.d(TAG, "$exam cache is fresh (${cacheAge / 60_000}m old). Skipping network check.")
            return@withContext
        }

        syncFromFirestore(exam)
    }

    /**
     * Bypasses the TTL and forces a full version check + re-download if needed.
     * Useful for a manual "refresh" button or after the admin pushes a new set.
     */
    /**
     * Bypasses the TTL and forces a full version check + re-download if needed.
     * Returns an error string on failure, or null on success.
     */
    suspend fun forceSync(exam: String): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Force sync requested for $exam")
        prefs.edit().remove(lastCheckedKey(exam)).apply()
        syncFromFirestore(exam)
    }

    fun getLocalVersion(exam: String): Long  = prefs.getLong(versionKey(exam), -1L)
    fun getCacheAgeMinutes(exam: String): Long {
        val last = prefs.getLong(lastCheckedKey(exam), 0L)
        return if (last == 0L) -1L else (System.currentTimeMillis() - last) / 60_000L
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    // Returns null on success, error message on failure
    private suspend fun syncFromFirestore(exam: String): String? {
        return try {
            val doc = firestore.collection("standard_tests").document(exam).get().await()

            if (!doc.exists()) {
                val msg = "No Power 100 data found in Firestore for $exam. Run the upload script first."
                Log.w(TAG, msg)
                return msg
            }

            val remoteVersion = doc.getLong("version") ?: 0L
            val localVersion  = prefs.getLong(versionKey(exam), -1L)
            val localCount    = dao.getCount(exam)

            // Record that we checked — even if nothing changed, reset the TTL
            prefs.edit().putLong(lastCheckedKey(exam), System.currentTimeMillis()).apply()

            if (remoteVersion <= localVersion && localCount > 0) {
                Log.d(TAG, "$exam is up to date (v$localVersion, $localCount questions).")
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val raw = doc.get("questions") as? List<Map<String, Any>>
            if (raw.isNullOrEmpty()) {
                val msg = "Firestore document exists but questions field is empty for $exam."
                Log.w(TAG, msg)
                return msg
            }

            val questions = raw.mapIndexedNotNull { idx, map -> parseQuestion(exam, idx + 1, map) }

            if (questions.isEmpty()) {
                val msg = "All ${raw.size} questions failed to parse for $exam."
                Log.w(TAG, msg)
                return msg
            }

            if (questions.size < raw.size) {
                Log.w(TAG, "${raw.size - questions.size} questions skipped due to parse errors for $exam.")
            }

            // The nightly rebuild swaps in a fresh 100 questions at the same fixed
            // positions — reset progress too, or a user's completed positions would
            // silently point at brand-new, unattempted questions.
            if (localVersion >= 0) {
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
            null // success

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Sync failed for $exam: $msg")
            // Don't update lastCheckedKey on failure — next launch will retry
            com.jeeneet.mocktest.utils.AnalyticsManager.syncFailed(context, "power100_$exam", msg)
            msg
        }
    }

    private fun parseQuestion(exam: String, position: Int, map: Map<String, Any>): Power100Question? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val options = (map["options"] as? List<*>)?.mapNotNull { it as? String }
            if (options == null || options.size != 4) return null
            Power100Question(
                examType = exam,
                position = position,
                subject = map["subject"] as? String ?: return null,
                chapter = map["chapter"] as? String ?: "",
                difficulty = map["difficulty"] as? String ?: "Medium",
                questionText = map["questionText"] as? String ?: return null,
                options = options,
                correctOptionIndex = (map["correctOptionIndex"] as? Long)?.toInt()
                                        ?: (map["correctOption"] as? Long)?.toInt()
                                        ?: (map["correctOptionIndex"] as? Int)
                                        ?: (map["correctOption"] as? Int)
                                        ?: return null,
                explanation = map["explanation"] as? String ?: ""
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse question at position $position: ${e.message}")
            null
        }
    }
}
