package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Battery-safe periodic background sync using WorkManager.
 * Runs once per day when network is available.
 * Replaces the raw coroutine launch in MockTestApplication.
 */
class QuestionSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Running scheduled question sync…")
            QuestionSyncManager(applicationContext).checkAndSyncIfNeeded()
            Log.d(TAG, "Sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed (attempt $runAttemptCount): ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG       = "QuestionSyncWorker"
        private const val WORK_NAME = "question_bank_sync"

        /**
         * Schedules a daily sync. Uses KEEP policy so rescheduling on each launch
         * doesn't cancel an already-queued run.
         * Requires network — WorkManager waits for connectivity automatically.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<QuestionSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** One-time sync — use when user just purchased a pack and needs immediate content. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<QuestionSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
