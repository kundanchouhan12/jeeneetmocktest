package com.jeeneet.mocktest.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager

class EveningReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext

        // Quiz already done today — no need to remind
        if (PrefManager.isDailyQuizDoneToday(ctx)) return Result.success()

        val streak      = PrefManager.getStreak(ctx)
        val daysSince   = PrefManager.daysSinceLastPractice(ctx)
        val lastScore   = PrefManager.getLastTestScoreInfo(ctx)
        val exam        = PrefManager.getSelectedExam(ctx)

        when {
            // Level 1: Friendly (Active today but quiz not done)
            daysSince == 0 -> {
                NotificationHelper.showDailyReminderNotif(ctx, exam)
            }
            // Level 2: Challenge (Missed 1 day, streak at risk)
            daysSince == 1 && streak > 0 -> {
                NotificationHelper.showStreakAlertNotif(ctx, streak)
            }
            // Level 3: Fear of Loss (Missed 2+ days)
            daysSince >= 2 -> {
                if (lastScore != null && lastScore.scorePercent > 0) {
                    NotificationHelper.showComebackWithScoreNotif(ctx, lastScore.scorePercent, exam)
                } else {
                    NotificationHelper.showComebackNotif(ctx)
                }
            }
            else -> {
                NotificationHelper.showDailyReminderNotif(ctx, exam)
            }
        }

        // Schedule 11 PM emergency alert if streak is at risk
        if (streak > 0) {
            NotificationHelper.scheduleStreakEmergency(ctx)
        }

        return Result.success()
    }
}
