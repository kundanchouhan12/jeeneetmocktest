package com.jeeneet.mocktest.ui.auth

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jeeneet.mocktest.MainActivity
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // White rounded card so the logo's white background blends on dark bg
        val logoCard = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 28.dpF
            }
            layoutParams = LinearLayout.LayoutParams(200.dp, 200.dp).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }
        logoCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.updated_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        root.addView(logoCard)

        val quotes = listOf(
            "Prepare your strategy,\nConquer your success.",
            "Every mock test is a step\ncloser to your dream.",
            "Success is where preparation\nmeets opportunity.",
            "Dream it. Prepare for it.\nAchieve it.",
            "One test at a time,\none rank at a time.",
            "Your rank is decided\nbefore exam day.",
            "Hard work beats talent\nwhen talent doesn't work hard."
        )
        val quote = quotes.random()

        // Decorative quote mark
        root.addView(TextView(this).apply {
            text = "\u201C"
            textSize = 48f
            setTextColor(Color.parseColor("#F59E0B"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 28.dp }
        })

        // Quote text
        root.addView(TextView(this).apply {
            text = quote
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.ITALIC)
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.leftMargin = 40.dp; it.rightMargin = 40.dp; it.topMargin = Space.XS.dp
            }
        })

        // Tagline
        root.addView(TextView(this).apply {
            text = "— JEE NEET Mock Test"
            textSize = UiText.CAPTION.size
            setTextColor(Color.parseColor("#F59E0B"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 10.dp }
        })

        setContentView(root)

        lifecycleScope.launch {
            delay(2000)
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val dest = when {
                !PrefManager.isOnboardingDone(this@SplashActivity) -> OnboardingActivity::class.java
                user != null && user.isEmailVerified               -> {
                    PrefManager.clearGuestMode(this@SplashActivity)
                    MainActivity::class.java
                }
                else                                               -> {
                    // Allow guest access — user will see home screen without logging in.
                    // Login is prompted only when they attempt a restricted action (e.g. start test).
                    PrefManager.setGuestMode(this@SplashActivity, true)
                    MainActivity::class.java
                }
            }
            startActivity(Intent(this@SplashActivity, dest))
            finish()
        }
    }
}
