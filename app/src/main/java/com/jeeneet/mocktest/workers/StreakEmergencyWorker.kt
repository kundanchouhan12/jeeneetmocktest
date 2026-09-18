package com.jeeneet.mocktest.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager

class StreakEmergencyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext

        // If daily quiz was completed in the meantime, no emergency!
        if (PrefManager.isDailyQuizDoneToday(ctx)) return Result.success()

        val streak = PrefManager.getStreak(ctx)
        val exam = PrefManager.getSelectedExam(ctx)

        // Only fire if there is an active streak to protect
        if (streak > 0) {
            NotificationHelper.showStreakEmergencyNotif(ctx, streak, exam)
        }

        return Result.success()
    }
}
