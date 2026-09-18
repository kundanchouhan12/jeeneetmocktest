package com.jeeneet.mocktest.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager

class MorningReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext

        // Skip if quiz already done today
        if (PrefManager.isDailyQuizDoneToday(ctx)) return Result.success()

        val daysSince = PrefManager.daysSinceLastPractice(ctx)
        val exam      = PrefManager.getSelectedExam(ctx)

        when {
            // Dormant 3-7 days: social-proof win-back (high urgency)
            daysSince in 3..7 -> {
                NotificationHelper.showSocialProofWinbackNotif(ctx, exam)
            }
            // Dormant 1-2 days: gentle morning nudge
            daysSince in 1..2 -> {
                NotificationHelper.showDailyReminderNotif(ctx, exam)
            }
            // Active user (daysSince == 0): no morning notification
            // Avoid spamming daily users — they get the evening reminder if quiz not done
        }

        // Schedule streak emergency for tonight if user has a streak to protect
        val streak = PrefManager.getStreak(ctx)
        if (streak > 0) {
            NotificationHelper.scheduleStreakEmergency(ctx)
        }

        return Result.success()
    }
}
