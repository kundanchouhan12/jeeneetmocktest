package com.jeeneet.mocktest.services

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jeeneet.mocktest.MainActivity
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.NotificationHelper
import com.jeeneet.mocktest.services.NotificationRouter

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"] ?: NotificationRouter.TYPE_GENERAL
        
        AnalyticsManager.notificationReceived(this, type)

        val title = message.notification?.title ?: data["title"] ?: "Mock Test Update"
        val body  = message.notification?.body  ?: data["body"]  ?: "New challenge waiting for you!"
        val channel = data["channel"] ?: NotificationHelper.CHANNEL_GENERAL

        // Targeted logic based on 'type'
        val finalTitle = when(type) {
            NotificationRouter.TYPE_SCORE_DROP -> "⚠️ Performance Alert"
            NotificationRouter.TYPE_STREAK_ALERT -> "🔥 Streak Saving Zone"
            NotificationRouter.TYPE_DORMANT -> "We miss your potential! 👋"
            else -> title
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(NotificationRouter.EXTRA_TYPE, type)
            // Legacy support if needed
            putExtra("open_daily_quiz", type == NotificationRouter.TYPE_DAILY_TEST || type == NotificationRouter.TYPE_STREAK_ALERT)
        }
        
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notif = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(finalTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NotificationHelper.NOTIF_ID_GENERAL, notif)
        } catch (_: SecurityException) {}
    }

    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users")
            .document(uid)
            .update("fcmTokens", FieldValue.arrayUnion(token))
            .addOnFailureListener {
                // Fallback: create document with the token list if it doesn't exist
                FirebaseFirestore.getInstance().collection("users")
                    .document(uid)
                    .set(mapOf("fcmTokens" to listOf(token)), com.google.firebase.firestore.SetOptions.merge())
            }
    }
}
