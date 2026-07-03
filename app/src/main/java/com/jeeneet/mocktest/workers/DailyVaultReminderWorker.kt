package com.jeeneet.mocktest.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.utils.PrefManager

class DailyVaultReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!PrefManager.isDailyVaultDoneToday(ctx, PrefManager.getSelectedExam(ctx))) {
            val exam = PrefManager.getSelectedExam(ctx)
            NotificationHelper.showDailyVaultNotif(ctx, exam)
        }
        return Result.success()
    }
}
