package com.jeeneet.mocktest.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jeeneet.mocktest.MainActivity
import com.jeeneet.mocktest.services.NotificationRouter
import com.jeeneet.mocktest.workers.DailyReminderWorker
import com.jeeneet.mocktest.workers.DailyVaultReminderWorker
import com.jeeneet.mocktest.workers.EveningReminderWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NotificationHelper {

    const val CHANNEL_DAILY   = "daily_challenge"
    const val CHANNEL_STREAK  = "streak_alert"
    const val CHANNEL_GENERAL = "general"
    const val CHANNEL_VAULT   = "daily_vault"

    const val NOTIF_ID_DAILY   = 1001
    const val NOTIF_ID_STREAK  = 1002
    const val NOTIF_ID_GENERAL = 1003
    const val NOTIF_ID_VAULT   = 1004

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        listOf(
            NotificationChannel(CHANNEL_DAILY, "Daily Challenge", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily JEE/NEET challenge reminder"
            },
            NotificationChannel(CHANNEL_STREAK, "Streak Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Don't lose your streak!"
            },
            NotificationChannel(CHANNEL_GENERAL, "General", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "App updates and announcements"
            },
            NotificationChannel(CHANNEL_VAULT, "Daily Vault", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily Vault unlock reminder"
            }
        ).forEach { nm.createNotificationChannel(it) }
    }

    fun scheduleDailyReminder(context: Context) {
        val work = PeriodicWorkRequestBuilder<DailyReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilHour(8), TimeUnit.MILLISECONDS)
            .addTag("daily_reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "daily_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )
    }

    fun scheduleEveningReminder(context: Context) {
        val work = PeriodicWorkRequestBuilder<EveningReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilHour(20), TimeUnit.MILLISECONDS)
            .addTag("evening_reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "evening_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )
    }

    fun scheduleDailyVaultReminder(context: Context) {
        val work = PeriodicWorkRequestBuilder<DailyVaultReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilHour(10), TimeUnit.MILLISECONDS)
            .addTag("vault_reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "vault_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )
    }

    fun showDailyVaultNotif(context: Context, examType: String = "JEE") {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_DAILY_VAULT)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_VAULT, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_VAULT)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Today's $examType Vault is unlocked! 🔐")
            .setContentText("30 curated questions await — solve now and earn 50 coins! 🪙")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("30 curated questions await — solve now and earn 50 coins! 🪙"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(context, NOTIF_ID_VAULT, notif)
    }

    fun showDailyReminderNotif(context: Context, examType: String = "JEE") {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_DAILY_TEST)
            putExtra("open_daily_quiz", true)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_DAILY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build a personalized body using the last known score
        val lastScore = com.jeeneet.mocktest.utils.PrefManager.getLastTestScoreInfo(context)
        val body = when {
            lastScore != null && lastScore.scorePercent > 0 ->
                "Your last score: ${lastScore.scorePercent}% — can you beat it today? 🎯"
            else ->
                "Attempt now and earn 15 coins! Solve in just 5 minutes ⚡"
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Today's $examType Challenge is ready! 🔥")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(context, NOTIF_ID_DAILY, notif)
    }

    fun showStreakAlertNotif(context: Context, streak: Int) {
        if (streak == 0) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_STREAK_ALERT)
            putExtra("open_daily_quiz", true)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_STREAK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lastScore = com.jeeneet.mocktest.utils.PrefManager.getLastTestScoreInfo(context)
        val body = when {
            lastScore != null && lastScore.scorePercent > 0 ->
                "Your ${streak}-day streak is at risk! Last score: ${lastScore.scorePercent}% — keep climbing 💪"
            streak >= 7 ->
                "Don't break your $streak-day streak! You're on a roll 🔥 Complete today's challenge!"
            else ->
                "Attempt today's challenge to keep your $streak-day streak alive!"
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_STREAK)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Don't lose your $streak-day streak! 🔥")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(context, NOTIF_ID_STREAK, notif)
    }

    fun showComebackNotif(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_DORMANT)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_GENERAL, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("We miss you! 👋")
            .setContentText("New mock tests added. Come back and practice!")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(context, NOTIF_ID_GENERAL, notif)
    }

    fun showComebackWithScoreNotif(context: Context, lastScorePercent: Int, examType: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_DAILY_TEST)
            putExtra("open_daily_quiz", true)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_GENERAL + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "Your last score was $lastScorePercent% — today's $examType test is ready. Can you beat it? 🎯"
        val notif = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Beat your last score: $lastScorePercent% 🔥")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(context, NOTIF_ID_GENERAL + 1, notif)
    }

    fun showNotification(context: Context, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_GENERAL)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_GENERAL, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(context, NOTIF_ID_GENERAL, notif)
    }

    private fun safeNotify(context: Context, id: Int, notif: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted — silently skip
        }
    }

    private fun delayUntilHour(hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - System.currentTimeMillis()
    }
}
