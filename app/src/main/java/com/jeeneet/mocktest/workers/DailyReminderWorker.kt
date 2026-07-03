package com.jeeneet.mocktest.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager

class DailyReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!PrefManager.isDailyQuizDoneToday(ctx)) {
            val exam = PrefManager.getSelectedExam(ctx)
            NotificationHelper.showDailyReminderNotif(ctx, exam)
        }
        return Result.success()
    }
}
