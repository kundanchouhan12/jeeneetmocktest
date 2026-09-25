package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jeeneet.mocktest.data.model.IAPProducts
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val EXPECTED_VAULT_COUNT = QuestionFirestoreParser.EXPECTED_VAULT_COUNT

        // A NEW QuestionSyncManager(context) is constructed at every call site (MainActivity
        // onResume, the IAP-restore callback, switchExam, ...). An instance-level Mutex would
        // give each of those its own lock and serialize nothing — two of those coroutines can
        // genuinely overlap (e.g. onResume's syncDailyVault() racing the restore-complete
        // callback's syncDailyVault() right after cold start) and interleave
        // delete-then-insert, doubling up vault rows. Sharing one Mutex per process across
        // every instance is what actually serializes concurrent syncDailyVault() calls.
        private val vaultMutex = Mutex()
    }

    private val firestore    = Firebase.firestore
    private val questionDao  = MockTestDatabase.getInstance(context).questionDao()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Checks the Firestore version field. If it's newer than local, re-syncs
     * all packs the user has already unlocked. Call this on app start (background).
     */
    suspend fun checkAndSyncIfNeeded() = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            AnalyticsManager.syncStarted(context, "unlocked_packs")
            val meta = firestore.collection(COL_METADATA)
                .document(DOC_QUESTION_BANK).get().await()
            val remoteVersion = (meta.getLong("version") ?: 0L).toInt()
            val localVersion  = PrefManager.getLastSyncedVersion(context)

            if (remoteVersion > localVersion) {
                Log.d(TAG, "Remote v$remoteVersion > local v$localVersion — syncing all unlocked packs")
                syncAllUnlockedPacks()
                PrefManager.setLastSyncedVersion(context, remoteVersion)
                val totalLocal = questionDao.getTotalCount()
                AnalyticsManager.syncCompleted(
                    context,
                    syncType = "unlocked_packs",
                    version = remoteVersion,
                    freshCount = 0,
                    localTotal = totalLocal,
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                Log.d(TAG, "Questions up to date at v$localVersion")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check skipped (offline?): ${e.message}")
            AnalyticsManager.syncFailed(context, "unlocked_packs", e.message ?: "network_error")
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
     * Syncs today's Daily Vault for the selected exam only.
     * Yesterday's Room vault is deleted only after a full 30-question set parses.
     */
    suspend fun syncDailyVault() = withContext(Dispatchers.IO) {
        vaultMutex.withLock { syncDailyVaultLocked() }
    }

    private suspend fun syncDailyVaultLocked() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val today = sdf.format(java.util.Date())
        val exam = PrefManager.getSelectedExam(context)

        val savedGroupId = PrefManager.getVaultCurrentGroupId(context, exam)
        val snapshotDate = PrefManager.getVaultSnapshotDate(context, exam)

        val localRows = if (savedGroupId.isNotEmpty()) {
            questionDao.getVaultQuestionsByGroupId(savedGroupId)
        } else emptyList()
        val cacheCompleteToday = DailyVaultContract.isCompleteCache(
            localRows, exam, today, savedGroupId
        )

        Log.d(
            TAG,
            "Vault check: today=$today exam=$exam savedGroupId='$savedGroupId' " +
                "snapshotDate='$snapshotDate' roomCount=${localRows.size} " +
                "cacheCompleteToday=$cacheCompleteToday"
        )

        if (DailyVaultContract.shouldSkipNetwork(
                snapshotDate, today, savedGroupId, cacheCompleteToday
            )
        ) {
            Log.d(TAG, "Daily Vault for $today ($exam) already complete (${DailyVaultContract.EXPECTED_COUNT}) — skipping Firestore")
            return
        }

        if (localRows.isNotEmpty() && !cacheCompleteToday) {
            Log.w(TAG, "Daily Vault cache incomplete (${localRows.size}/$EXPECTED_VAULT_COUNT for $exam) — retrying Firestore")
        }

        try {
            Log.d(TAG, "Syncing Daily Vault for $today ($exam)…")
            val snapshot = firestore.collection(COL_QUESTIONS)
                .whereEqualTo("vaultDate", today)
                .whereEqualTo("examType", exam)
                .whereEqualTo("isDailyVault", true)
                .get().await()

            var questions = parseVaultDocuments(snapshot.documents, "today=$today exam=$exam")
                .filter { it.examType == exam && it.isDailyVault }

            // Reinstall / first-launch fallback: no cached vault and no vault for today —
            // fetch the latest vault for this exam only.
            if (questions.isEmpty() && savedGroupId.isEmpty()) {
                try {
                    val fallbackSnapshot = firestore.collection(COL_QUESTIONS)
                        .whereEqualTo("examType", exam)
                        .whereEqualTo("isDailyVault", true)
                        .orderBy("vaultDate", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(EXPECTED_VAULT_COUNT.toLong())
                        .get().await()
                    questions = parseVaultDocuments(fallbackSnapshot.documents, "fallback exam=$exam")
                        .filter { it.examType == exam && it.isDailyVault }
                    val latestDate = questions.maxByOrNull { it.vaultDate }?.vaultDate
                    if (latestDate != null) {
                        questions = questions.filter { it.vaultDate == latestDate }
                    }
                    Log.d(TAG, "Vault fallback ($exam): ${questions.size} questions (date=$latestDate)")
                } catch (e: Exception) {
                    Log.w(TAG, "Vault fallback query failed: ${e.message}")
                }
            }

            val incomingDate = questions.firstOrNull()?.vaultDate.orEmpty()
            val replaceToday = DailyVaultContract.isCompleteIncoming(questions, exam, today)
            val replaceFallback = !replaceToday &&
                savedGroupId.isEmpty() &&
                incomingDate.isNotEmpty() &&
                DailyVaultContract.isCompleteIncoming(questions, exam, incomingDate)

            if (replaceToday || replaceFallback) {
                val sourceDate = questions.first().vaultDate.ifEmpty { today }
                val newGroupId = questions.first().vaultGroupId.ifEmpty { today }
                questionDao.replaceDailyVault(exam, questions)

                // Defense-in-depth: the in-memory `questions` list was already proven
                // complete by isCompleteIncoming() above, so this should always pass —
                // but if a future Room/DAO change ever broke that guarantee, silently
                // pointing PrefManager at a broken group would be worse than surfacing it.
                val writtenRows = questionDao.getVaultQuestionsByGroupId(newGroupId)
                if (!DailyVaultContract.isCompleteCache(writtenRows, exam, sourceDate, newGroupId)) {
                    Log.e(
                        TAG,
                        "Vault write verification FAILED for $exam/$sourceDate group=$newGroupId " +
                            "(read back ${writtenRows.size} rows, expected $EXPECTED_VAULT_COUNT) — " +
                            "not updating pointers, will retry on next sync"
                    )
                    AnalyticsManager.syncFailed(context, "daily_vault", "post_write_verification_failed")
                    return
                }

                val refreshCal = java.util.Calendar.getInstance()
                refreshCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val nextRefresh = sdf.format(refreshCal.time)

                PrefManager.setVaultCurrentGroupId(context, exam, newGroupId)
                PrefManager.setVaultSnapshotDate(context, exam, sourceDate)
                PrefManager.setVaultNextRefreshDate(context, exam, nextRefresh)
                PrefManager.setLastVaultSyncDate(context, today)

                if (!PrefManager.isDailyVaultDoneToday(context, exam)) {
                    com.jeeneet.mocktest.utils.NotificationHelper.showDailyVaultNotif(context, exam)
                }
                Log.d(TAG, "Daily Vault replaced for $exam: ${questions.size} questions, next refresh: $nextRefresh")
                val localTotal = questionDao.getTotalCount()
                AnalyticsManager.syncCompleted(
                    context,
                    syncType = "daily_vault",
                    version = 0,
                    freshCount = questions.size,
                    localTotal = localTotal,
                    durationMs = 0L
                )
            } else if (questions.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Ignoring incomplete $exam vault (${questions.size}/$EXPECTED_VAULT_COUNT parseable) — keeping previous day"
                )
            } else {
                // No new vault uploaded yet for today — keep serving cached vault and retry tomorrow
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
            PrefManager.setVaultLastCheckedMs(context, exam, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Daily Vault sync failed: ${e.message}")
            AnalyticsManager.syncFailed(context, "daily_vault", e.message ?: "network_error")
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

    private fun parseVaultDocuments(
        documents: List<com.google.firebase.firestore.DocumentSnapshot>,
        label: String
    ): List<Question> {
        val parsed = ArrayList<Question>(documents.size)
        var empty = 0
        documents.forEach { doc ->
            val data = doc.data
            if (data == null) {
                empty++
                Log.w(TAG, "parseQuestion drop ${doc.id}: empty data")
                return@forEach
            }
            val result = QuestionFirestoreParser.parseQuestion(data)
            val question = result.question
            if (question == null) {
                val examDrop = data["examType"]
                val dateDrop = data["vaultDate"]
                Log.w(TAG, "parseQuestion drop ${doc.id} exam=$examDrop vaultDate=$dateDrop: ${result.dropReason}")
            } else {
                parsed += question
            }
        }
        val jee = parsed.count { it.examType == "JEE" }
        val neet = parsed.count { it.examType == "NEET" }
        Log.d(
            TAG,
            "Vault $label: raw=${documents.size} empty=$empty " +
                "parseable=${parsed.size} (JEE=$jee NEET=$neet) dropped=${documents.size - empty - parsed.size}"
        )
        return parsed
    }

    private fun parseQuestion(data: Map<String, Any>): Question? =
        QuestionFirestoreParser.parseQuestion(data).question

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
