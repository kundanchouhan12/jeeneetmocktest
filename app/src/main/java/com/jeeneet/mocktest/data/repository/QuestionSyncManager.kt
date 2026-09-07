package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jeeneet.mocktest.data.model.IAPProducts
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Syncs questions from Firestore into the local Room DB cache.
 *
 * Firestore structure expected:
 *   /questions/{auto-id}
 *     examType:           "JEE" | "NEET"
 *     subject:            "Physics" | "Chemistry" | "Maths" | "Biology"
 *     chapter:            "Kinematics" (etc.)
 *     difficulty:         "Easy" | "Medium" | "Hard"
 *     year:               2024  (0 = original, >0 = PYQ)
 *     questionText:       "..."
 *     options:            ["A text", "B text", "C text", "D text"]
 *     correctOptionIndex: 2
 *     explanation:        "..."
 *     isPremium:          true
 *     packId:             "jee_physics_pack"   ← must match IAPProducts constant
 *
 *   /metadata/question_bank
 *     version:            5    ← increment whenever you add/update questions
 *     lastUpdated:        Timestamp
 */
class QuestionSyncManager(private val context: Context) {

    companion object {
        private const val TAG               = "QuestionSyncManager"
        private const val COL_QUESTIONS     = "questions"
        private const val COL_METADATA      = "metadata"
        private const val DOC_QUESTION_BANK = "question_bank"
        // Skip Firestore vault check if we already checked within this window
        private const val VAULT_CHECK_TTL_MS = 6 * 60 * 60 * 1000L  // 6 hours
    }

    private val firestore    = Firebase.firestore
    private val questionDao  = MockTestDatabase.getInstance(context).questionDao()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Checks the Firestore version field. If it's newer than local, re-syncs
     * all packs the user has already unlocked. Call this on app start (background).
     */
    suspend fun checkAndSyncIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val meta = firestore.collection(COL_METADATA)
                .document(DOC_QUESTION_BANK).get().await()
            val remoteVersion = (meta.getLong("version") ?: 0L).toInt()
            val localVersion  = PrefManager.getLastSyncedVersion(context)

            if (remoteVersion > localVersion) {
                Log.d(TAG, "Remote v$remoteVersion > local v$localVersion — syncing all unlocked packs")
                syncAllUnlockedPacks()
                PrefManager.setLastSyncedVersion(context, remoteVersion)
            } else {
                Log.d(TAG, "Questions up to date at v$localVersion")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check skipped (offline?): ${e.message}")
        }
    }

    /**
     * Fetches all questions for [packId] from Firestore and caches them in Room.
     * Deletes stale cached questions for that pack first so duplicates can't build up.
     * Call this immediately after a successful IAP purchase.
     */
    suspend fun syncPack(packId: String) = withContext(Dispatchers.IO) {
        val (exam, subject) = packToExamSubject(packId) ?: run {
            // ALL_ACCESS — sync every individual pack
            if (packId == IAPProducts.ALL_ACCESS_YEARLY) {
                syncAllUnlockedPacks()
            }
            return@withContext
        }

        try {
            Log.d(TAG, "Fetching pack '$packId' ($exam / $subject) from Firestore…")
            val snapshot = firestore.collection(COL_QUESTIONS)
                .whereEqualTo("packId", packId)
                .get().await()

            // Vault documents are full copies of pack questions (same packId) tagged
            // with isDailyVault — exclude them here or they'd double up as duplicate
            // rows in the local pool that full/chapter tests draw from.
            val questions = snapshot.documents
                .filterNot { it.getBoolean("isDailyVault") == true }
                .mapNotNull { doc ->
                    parseQuestion(doc.data ?: return@mapNotNull null)
                }

            if (questions.isNotEmpty()) {
                questionDao.deletePremiumQuestionsBySubject(exam, subject)
                questionDao.insertQuestions(questions)
                Log.d(TAG, "Cached ${questions.size} questions for '$packId'")
            } else {
                Log.w(TAG, "No questions found for packId='$packId' in Firestore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for '$packId': ${e.message}")
        }
    }

    /**
     * Syncs today's curated questions (the Daily Vault) from Firestore.
     * Questions are frozen until the next scheduled refresh date (7 days after last sync).
     * Accessible to all users regardless of IAP status.
     */
    suspend fun syncDailyVault() = withContext(Dispatchers.IO) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = sdf.format(java.util.Date())
        val exam = PrefManager.getSelectedExam(context)

        val savedGroupId = PrefManager.getVaultCurrentGroupId(context, exam)
        val nextRefreshDate = PrefManager.getVaultNextRefreshDate(context, exam)

        // Gate 1 — 7-day freeze: if we have questions and refresh date hasn't arrived, use cache
        if (savedGroupId.isNotEmpty() && nextRefreshDate.isNotEmpty() && today < nextRefreshDate) {
            val localCount = questionDao.getVaultQuestionsByGroupId(savedGroupId).size
            if (localCount > 0) {
                Log.d(TAG, "Vault frozen until $nextRefreshDate — skipping sync ($localCount questions cached)")
                return@withContext
            }
        }

        // Gate 2 — TTL: even after the freeze expires, only hit Firestore once per 6 hours
        // (mirrors Power100SyncManager's 24h TTL to avoid redundant network calls)
        val lastCheckedMs = PrefManager.getVaultLastCheckedMs(context, exam)
        val cacheAge = System.currentTimeMillis() - lastCheckedMs
        if (savedGroupId.isNotEmpty() && cacheAge < VAULT_CHECK_TTL_MS) {
            val localCount = questionDao.getVaultQuestionsByGroupId(savedGroupId).size
            if (localCount > 0) {
                Log.d(TAG, "Vault TTL warm (${cacheAge / 60_000}m old) — skipping Firestore check")
                return@withContext
            }
        }

        try {
            Log.d(TAG, "Syncing Daily Vault for $today…")
            val snapshot = firestore.collection(COL_QUESTIONS)
                .whereEqualTo("vaultDate", today)
                .get().await()

            var questions = snapshot.documents.mapNotNull { doc ->
                parseQuestion(doc.data ?: return@mapNotNull null)
            }

            // Reinstall / first-launch fallback: no cached vault and no vault for today —
            // fetch the most recently uploaded vault from Firestore regardless of age so
            // users always see vault questions even if the script wasn't run this week.
            if (questions.isEmpty() && savedGroupId.isEmpty()) {
                try {
                    val fallbackSnapshot = firestore.collection(COL_QUESTIONS)
                        .whereGreaterThan("vaultDate", "")
                        .orderBy("vaultDate", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(120L)
                        .get().await()
                    val allFallback = fallbackSnapshot.documents.mapNotNull { doc ->
                        parseQuestion(doc.data ?: return@mapNotNull null)
                    }
                    if (allFallback.isNotEmpty()) {
                        // For each exam keep only questions from their most recent vault date
                        val jeeLatest = allFallback.filter { it.examType == "JEE" && it.vaultDate.isNotEmpty() }
                            .maxByOrNull { it.vaultDate }?.vaultDate
                        val neetLatest = allFallback.filter { it.examType == "NEET" && it.vaultDate.isNotEmpty() }
                            .maxByOrNull { it.vaultDate }?.vaultDate
                        questions = allFallback.filter { q ->
                            (q.examType == "JEE" && q.vaultDate == jeeLatest) ||
                            (q.examType == "NEET" && q.vaultDate == neetLatest)
                        }
                        Log.d(TAG, "Vault fallback: ${questions.size} questions (JEE: $jeeLatest, NEET: $neetLatest)")
                    } else {
                        Log.d(TAG, "Vault fallback: no vault questions found in Firestore")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Vault fallback query failed: ${e.message}")
                }
            }

            if (questions.isNotEmpty()) {
                val newGroupId = questions.firstOrNull { it.examType == exam }?.vaultGroupId ?: today
                // Only replace questions if the group is different (new vault cycle)
                if (newGroupId != savedGroupId) {
                    // Tidy up: remove vault questions older than 14 days
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -14)
                    val oldDate = sdf.format(cal.time)
                    questionDao.deleteOldVaultQuestions(oldDate)

                    questionDao.deleteDailyVaultQuestions("JEE", today)
                    questionDao.deleteDailyVaultQuestions("NEET", today)
                    questionDao.insertQuestions(questions)

                    // Calculate next refresh date (7 days from today)
                    val refreshCal = java.util.Calendar.getInstance()
                    refreshCal.add(java.util.Calendar.DAY_OF_YEAR, 7)
                    val nextRefresh = sdf.format(refreshCal.time)

                    PrefManager.setVaultCurrentGroupId(context, exam, newGroupId)
                    PrefManager.setVaultSnapshotDate(context, exam, today)
                    PrefManager.setVaultNextRefreshDate(context, exam, nextRefresh)
                    PrefManager.setLastVaultSyncDate(context, today)

                    // Notify for the current exam immediately — don't wait for the 10 AM worker
                    if (!PrefManager.isDailyVaultDoneToday(context, exam)) {
                        com.jeeneet.mocktest.utils.NotificationHelper.showDailyVaultNotif(context, exam)
                    }

                    // If the batch has questions for both exams, save prefs for the other exam too
                    val otherExam = if (exam == "JEE") "NEET" else "JEE"
                    if (questions.any { it.examType == otherExam }) {
                        val otherGroupId = questions.firstOrNull { it.examType == otherExam }?.vaultGroupId ?: newGroupId
                        PrefManager.setVaultCurrentGroupId(context, otherExam, otherGroupId)
                        PrefManager.setVaultSnapshotDate(context, otherExam, today)
                        PrefManager.setVaultNextRefreshDate(context, otherExam, nextRefresh)
                        Log.d(TAG, "Also saved vault prefs for $otherExam")
                        // Notify for the other exam too if it's not done
                        if (!PrefManager.isDailyVaultDoneToday(context, otherExam)) {
                            com.jeeneet.mocktest.utils.NotificationHelper.showDailyVaultNotif(context, otherExam)
                        }
                    }
                    Log.d(TAG, "Daily Vault synced: ${questions.size} questions, next refresh: $nextRefresh")
                } else {
                    PrefManager.setLastVaultSyncDate(context, today)
                    Log.d(TAG, "Daily Vault group unchanged — updated sync date only")
                }
            } else {
                // No new vault uploaded yet — keep serving the cached vault and retry tomorrow
                if (savedGroupId.isNotEmpty()) {
                    val refreshCal = java.util.Calendar.getInstance()
                    refreshCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    val retryDate = sdf.format(refreshCal.time)
                    PrefManager.setVaultNextRefreshDate(context, exam, retryDate)
                    Log.d(TAG, "No new vault found for $today — kept existing vault '$savedGroupId', retry on $retryDate")
                } else {
                    Log.d(TAG, "No vault questions found for $today and no cached vault available")
                }
            }
            // Stamp the TTL timestamp on every successful Firestore call — mirrors Power100
            PrefManager.setVaultLastCheckedMs(context, exam, System.currentTimeMillis())
        } catch (e: Exception) {
            // Don't stamp lastCheckedMs on failure so the next launch retries
            Log.w(TAG, "Daily Vault sync failed: ${e.message}")
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private suspend fun syncAllUnlockedPacks() {
        IAPProducts.ALL_PACKS.forEach { packId ->
            if (PrefManager.isPackUnlocked(context, packId)) {
                syncPack(packId)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseQuestion(data: Map<String, Any>): Question? {
        return try {
            Question(
                id                 = 0,   // Room auto-generates the local PK
                examType           = data["examType"]           as? String ?: return null,
                subject            = data["subject"]            as? String ?: return null,
                chapter            = data["chapter"]            as? String ?: return null,
                difficulty         = data["difficulty"]         as? String ?: "Medium",
                year               = (data["year"]              as? Long)?.toInt() ?: 0,
                questionText       = data["questionText"]       as? String ?: return null,
                options            = (data["options"]           as? List<*>)
                                        ?.filterIsInstance<String>()
                                        ?.takeIf { it.size == 4 }  ?: return null,
                correctOptionIndex = (data["correctOptionIndex"] as? Long)?.toInt() ?: return null,
                explanation        = data["explanation"]        as? String ?: "",
                isPremium          = data["isPremium"]          as? Boolean ?: true,
                isDailyVault       = data["isDailyVault"]       as? Boolean ?: false,
                vaultDate          = data["vaultDate"]          as? String ?: "",
                vaultGroupId       = data["vaultGroupId"]       as? String ?: ""
            )
        } catch (e: Exception) {
            Log.w(TAG, "Skipping malformed question: ${e.message}")
            null
        }
    }

    private fun packToExamSubject(packId: String): Pair<String, String>? = when (packId) {
        IAPProducts.JEE_PHYSICS_PACK  -> "JEE"  to "Physics"
        IAPProducts.JEE_CHEM_PACK     -> "JEE"  to "Chemistry"
        IAPProducts.JEE_MATHS_PACK    -> "JEE"  to "Maths"
        IAPProducts.NEET_BIO_PACK     -> "NEET" to "Biology"
        IAPProducts.NEET_CHEM_PACK    -> "NEET" to "Chemistry"
        IAPProducts.NEET_PHYSICS_PACK -> "NEET" to "Physics"
        else                          -> null
    }
}
