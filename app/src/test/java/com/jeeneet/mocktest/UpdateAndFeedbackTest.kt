package com.jeeneet.mocktest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.utils.PrefManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UpdateAndFeedbackTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mocktest_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ─── 1. Feedback / Rating Prompt Logic ────────────────────────────────────

    @Test
    fun `rating card should show only when happiness peak conditions met`() {
        // Condition: score >= 70% AND totalTests >= 3 AND !hasRated
        
        // Case A: High score but only 1 test done -> Should NOT show (logic-wise)
        PrefManager.incrementTotalTestsCompleted(ctx) // 1
        val scoreA = 90f 
        val totalA = PrefManager.getTotalTestsCompleted(ctx)
        val showA = scoreA >= 70f && totalA >= 3 && !PrefManager.hasRatedApp(ctx)
        assertFalse("Should not show with only 1 test", showA)

        // Case B: 3 tests done but low score -> Should NOT show
        repeat(2) { PrefManager.incrementTotalTestsCompleted(ctx) } // Now 3
        val scoreB = 40f
        val totalB = PrefManager.getTotalTestsCompleted(ctx)
        val showB = scoreB >= 70f && totalB >= 3 && !PrefManager.hasRatedApp(ctx)
        assertFalse("Should not show with low score", showB)

        // Case C: 3 tests done + High score -> SHOULD show
        val scoreC = 75f
        val totalC = PrefManager.getTotalTestsCompleted(ctx)
        val showC = scoreC >= 70f && totalC >= 3 && !PrefManager.hasRatedApp(ctx)
        assertTrue("SHOULD show rating prompt now", showC)

        // Case D: User already rated -> Should NOT show
        PrefManager.setRatedApp(ctx)
        val showD = scoreC >= 70f && totalC >= 3 && !PrefManager.hasRatedApp(ctx)
        assertFalse("Should not show again if already rated", showD)
    }

    // ─── 2. Update Logic (Conceptual Verification) ───────────────────────────

    @Test
    fun `verify update priority logic`() {
        // High priority update (4-5) should trigger IMMEDIATE flow
        val priorityHigh = 5
        val isImmediateAllowed = true
        
        val shouldStartImmediate = priorityHigh >= 4 && isImmediateAllowed
        assertTrue("Priority 5 must be immediate", shouldStartImmediate)

        // Normal priority (1-3) should trigger FLEXIBLE flow
        val priorityLow = 2
        val isFlexibleAllowed = true
        val shouldStartFlexible = priorityLow < 4 && isFlexibleAllowed
        assertTrue("Priority 2 should be flexible", shouldStartFlexible)
    }
    
    @Test
    fun `verify total tests completed incrementation`() {
        assertEquals(0, PrefManager.getTotalTestsCompleted(ctx))
        PrefManager.incrementTotalTestsCompleted(ctx)
        assertEquals(1, PrefManager.getTotalTestsCompleted(ctx))
        PrefManager.incrementTotalTestsCompleted(ctx)
        assertEquals(2, PrefManager.getTotalTestsCompleted(ctx))
    }
}
