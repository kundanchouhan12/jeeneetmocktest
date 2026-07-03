package com.jeeneet.mocktest.ui.auth

import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.jeeneet.mocktest.ui.style.*

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var etEmail: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bgPrimary)
            setPadding(Space.XXXL.dp, 60.dp, Space.XXXL.dp, Space.XXXL.dp)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(uiTextView(UiText.DISPLAY, "Reset Password", textPrimary).apply {
            textSize = 30f
            setPadding(0, 0, 0, Space.S.dp)
        })
        root.addView(uiTextView(UiText.BODY, "Enter your email to receive a password reset link", textTertiary).apply {
            setPadding(0, 0, 0, Space.HUGE.dp)
        })

        etEmail = authField(
            hint = "Email Address",
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        root.addView(etEmail)

        root.addView(uiPrimaryButton("Send Reset Link", onClick = { performReset() }).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Space.XXL.dp
        })

        root.addView(uiTextView(UiText.BODY, "Back to Login", colorPrimary, Gravity.CENTER).apply {
            setPadding(0, Space.XXL.dp, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })

        return root
    }

    private fun performReset() {
        val email = etEmail.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Enter valid email", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Reset link sent to your email", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
