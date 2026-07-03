package com.jeeneet.mocktest.data.repository

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jeeneet.mocktest.data.model.SavedTestSession
import com.jeeneet.mocktest.utils.AnalyticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AchievementManager {

    // SupervisorJob: one failed award() doesn't cancel others.
    // Dispatchers.IO: DB work runs on IO thread directly without extra withContext wrappers.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Badge(
        val id: String,
        val title: String,
        val description: String,
        val emoji: String
    )

    val ALL_BADGES = listOf(
        Badge("streak_3", "Streak Starter", "Keep a 3-day consistency streak", "🔥"),
        Badge("streak_7", "Streak Master", "Keep a 7-day consistency streak", "🏆"),
        Badge("doubt_buster", "Doubt Buster", "Solve 10 doubts using AI", "🧠"),
        Badge("perfect_100", "Perfect Score", "Get 100% in any challenge", "🎯"),
        Badge("speed_demon", "Speed Demon", "Finish a test in under 2 mins with >80%", "⚡"),
        Badge("early_bird", "Early Bird", "Practice before 8 AM", "🌅"),
        Badge("vault_master", "Vault Master", "Complete all 50 questions in any Daily Vault", "🔓")
    )

    fun checkStreak(context: Context, streak: Int) {
        if (streak >= 3) award(context, "streak_3")
        if (streak >= 7) award(context, "streak_7")
    }

    fun checkDoubtSolved(context: Context, count: Int) {
        if (count >= 10) award(context, "doubt_buster")
    }

    fun checkTestResult(context: Context, score: Int, timeTakenSeconds: Long) {
        if (score == 100) award(context, "perfect_100")
        if (timeTakenSeconds < 120 && score >= 80) award(context, "speed_demon")
        
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour < 8) award(context, "early_bird")
    }
    fun checkVaultCompleted(context: Context, attempted: Int, total: Int) {
        if (attempted == total && total >= 50) {
            award(context, "vault_master")
        }
    }

    private fun award(context: Context, badgeId: String) {
        val badge = ALL_BADGES.find { it.id == badgeId } ?: return
        val db = MockTestDatabase.getInstance(context.applicationContext)

        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        scope.launch {
            val alreadyHas = db.achievementDao().hasAchievement(badgeId, uid)
            if (!alreadyHas) {
                db.achievementDao().insert(Achievement(badgeId, uid))
                AnalyticsManager.notificationClicked(context.applicationContext, "achievement_unlocked_$badgeId")
                withContext(Dispatchers.Main) {
                    if (context is Activity && !context.isFinishing && !context.isDestroyed) {
                        showUnlockDialog(context, badge)
                    } else {
                        Toast.makeText(context.applicationContext, "🎉 Achievement Unlocked: ${badge.title}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showUnlockDialog(context: Activity, badge: Badge) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Achievement Unlocked! 🎉")
            .setMessage("${badge.emoji} ${badge.title}\n${badge.description}")
            .setPositiveButton("Awesome", null)
            .show()
    }
}
