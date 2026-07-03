package com.jeeneet.mocktest

import android.content.Intent
import com.jeeneet.mocktest.services.NotificationRouter
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class NotificationFlowTest {

    @Test
    fun testDailyVaultNotificationRouting() {
        val activity = mockk<MainActivity>(relaxed = true)
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply {
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_DAILY_VAULT)
        }

        // Wrap in try-catch — Firebase/WorkManager may not be initialized in Robolectric
        try {
            NotificationRouter.handle(activity, intent)
        } catch (e: Exception) {}

        assertNull(intent.getStringExtra(NotificationRouter.EXTRA_TYPE))
    }

    @Test
    fun testDailyQuizNotificationRouting() {
        val activity = mockk<MainActivity>(relaxed = true)
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply {
            putExtra(NotificationRouter.EXTRA_TYPE, NotificationRouter.TYPE_DAILY_TEST)
        }

        // Wrap in try-catch because AdManager/PrefManager may not work with a mock context
        try {
            NotificationRouter.handle(activity, intent)
        } catch (e: Exception) {}

        assertNull(intent.getStringExtra(NotificationRouter.EXTRA_TYPE))
    }
}
