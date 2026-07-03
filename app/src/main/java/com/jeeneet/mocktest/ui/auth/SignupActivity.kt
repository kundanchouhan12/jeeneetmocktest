package com.jeeneet.mocktest.ui.auth

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.jeeneet.mocktest.data.model.User
import com.jeeneet.mocktest.ui.style.*

class SignupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var etUsername: EditText
    private lateinit var etEmail: EditText
    private lateinit var etAge: EditText
    private lateinit var etPass: EditText
    private lateinit var etConfirmPass: EditText
    private lateinit var cbTerms: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Space.XXL.dp, 0, Space.XXL.dp, Space.HUGE.dp - Space.S.dp)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(authBrandSection("Create Account", "Join thousands of JEE & NEET aspirants"))

        val card = authCard()

        card.addView(authFieldLabel("Username"))
        etUsername = authField("Choose a username", InputType.TYPE_CLASS_TEXT)
        card.addView(etUsername)

        card.addView(authFieldLabel("Email Address"))
        etEmail = authField("Enter your email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        card.addView(etEmail)

        card.addView(authFieldLabel("Age"))
        etAge = authField("Your age", InputType.TYPE_CLASS_NUMBER)
        card.addView(etAge)

        card.addView(authFieldLabel("Password"))
        val (passField, passContainer) = authPasswordField("Minimum 6 characters", bottomMarginDp = Space.L)
        etPass = passField
        card.addView(passContainer)

        card.addView(authFieldLabel("Confirm Password"))
        val (confirmField, confirmContainer) = authPasswordField("Re-enter your password", bottomMarginDp = Space.L)
        etConfirmPass = confirmField
        card.addView(confirmContainer)

        cbTerms = CheckBox(this).apply {
            text = "I agree to the Terms and Conditions"
            setTextColor(textTertiary)
            textSize = UiText.LABEL.size
            buttonTintList = ColorStateList.valueOf(colorPrimary)
            layoutParams = lpRow(topDp = Space.XS, bottomDp = Space.XL)
        }
        card.addView(cbTerms)

        card.addView(uiPrimaryButton("Create Account", onClick = { performSignup() }))

        container.addView(card)
        container.addView(authFooter("Already have an account?", "Login") {
            startActivity(Intent(this@SignupActivity, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        })

        root.addView(container)
        return root
    }

    // ─── Auth logic ──────────────────────────────────────────────────────────

    private fun performSignup() {
        if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)) {
            showNoInternetToast()
            return
        }
        val username    = etUsername.text.toString().trim()
        val email       = etEmail.text.toString().trim()
        val ageStr      = etAge.text.toString().trim()
        val pass        = etPass.text.toString().trim()
        val confirmPass = etConfirmPass.text.toString().trim()

        if (username.isEmpty())                                { toast("Enter a username"); return }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { toast("Enter a valid email"); return }
        if (ageStr.isEmpty())                                  { toast("Enter your age"); return }
        if ((ageStr.toIntOrNull() ?: 0) <= 10)                 { toast("Age must be greater than 10"); return }
        if (pass.length < 6)                                   { toast("Password must be at least 6 characters"); return }
        if (pass != confirmPass)                               { toast("Passwords do not match"); return }
        if (!cbTerms.isChecked)                                { toast("Please agree to Terms and Conditions"); return }

        auth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser ?: run {
                    toast("Signup failed: Could not retrieve user. Please try again.")
                    return@addOnCompleteListener
                }

                user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(username).build())

                db.collection("users").document(user.uid).set(
                    User(uid = user.uid, username = username,
                         email = email, age = ageStr.toIntOrNull() ?: 0, termsAccepted = true)
                )

                user.sendEmailVerification()

                Toast.makeText(
                    this,
                    "Account created! Please verify your email, then login.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                finish()
            } else {
                toast("Signup failed: ${task.exception?.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
