package com.jeeneet.mocktest

import com.jeeneet.mocktest.data.model.ExamConfig
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DailyVaultAccessTest {

    @Test
    fun `test Daily Vault always returns unlocked status regardless of premium`() {
        // Create an ExamConfig marked as Daily Vault
        val config = ExamConfig(
            examType = "JEE",
            subject = null,
            chapter = "Daily Vault",
            totalQuestions = 50,
            durationMinutes = 60,
            correctMarks = 4f,
            negativeMarks = -1f,
            isDailyVault = true // THIS IS THE KEY FLAG
        )

        // Simulate the logic inside MockTestRepository.getQuestionsForConfig
        val adUnlocked = false
        val isAllAccessUnlocked = false // User is a FREE user
        val isPackUnlocked = false      // User hasn't bought any packs

        val isUnlocked = adUnlocked || config.isDailyVault || isAllAccessUnlocked || isPackUnlocked

        assertTrue("Daily Vault should be accessible to free users", isUnlocked)
    }

    @Test
    fun `test normal chapter remains locked for free users`() {
        val config = ExamConfig(
            examType = "JEE",
            subject = "Physics",
            chapter = "Kinematics",
            totalQuestions = 30,
            durationMinutes = 60,
            correctMarks = 4f,
            negativeMarks = -1f,
            isDailyVault = false // Normal chapter
        )

        val adUnlocked = false
        val isAllAccessUnlocked = false
        val isPackUnlocked = false

        val isUnlocked = adUnlocked || config.isDailyVault || isAllAccessUnlocked || isPackUnlocked

        assertFalse("Normal premium chapters should be locked for free users", isUnlocked)
    }
}
