package com.jeeneet.mocktest.ui.auth

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.jeeneet.mocktest.MainActivity
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.data.model.User
import com.jeeneet.mocktest.ui.style.*

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var etEmail: EditText
    private lateinit var etPass: EditText

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED && result.data == null) {
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)
            val idToken = account.idToken
            if (idToken == null) {
                Toast.makeText(this, "Sign-In failed: missing token. Check Firebase SHA-1.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            firebaseAuthWithGoogle(idToken)
        } catch (e: com.google.android.gms.common.api.ApiException) {
            android.util.Log.e("LoginActivity", "Google Sign-In ApiException: ${e.statusCode} ${e.message}")
            val msg = when (e.statusCode) {
                10    -> "Config error: Add your SHA-1 fingerprint to Firebase Console and re-download google-services.json"
                12501 -> null
                12502 -> "Sign-In already in progress, please wait"
                else  -> "Google Sign-In failed (code ${e.statusCode})"
            }
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.e("LoginActivity", "Google Sign-In exception", e)
            Toast.makeText(this, "Google Sign-In error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val currentUser = auth.currentUser
        if (currentUser != null) {
            val isDemoAccount = currentUser.email == "demo@jeeneet.com"
            if (currentUser.isEmailVerified || isDemoAccount) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

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

        container.addView(authBrandSection("JEE NEET Mock Test", "JEE & NEET Preparation"))

        val card = authCard()
        card.addView(uiTextView(UiText.H1, "Welcome Back", textPrimary))
        card.addView(uiTextView(UiText.LABEL, "Sign in to continue your preparation", textMuted).apply {
            setPadding(0, Space.XS.dp, 0, Space.XXL.dp)
        })

        // Email
        card.addView(authFieldLabel("Email Address"))
        etEmail = authField("Enter your email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        card.addView(etEmail)

        // Password with eye toggle
        card.addView(authFieldLabel("Password"))
        val (passField, passContainer) = authPasswordField("Enter your password")
        etPass = passField
        card.addView(passContainer)

        // Forgot password
        card.addView(uiTextView(UiText.LABEL, "Forgot Password?", colorPrimary, Gravity.END).apply {
            setPadding(0, Space.S.dp, 0, Space.XXL.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@LoginActivity, ForgotPasswordActivity::class.java))
            }
        })

        card.addView(uiPrimaryButton("Login", onClick = { performLogin() }))
        card.addView(authOrDivider())
        card.addView(authGoogleButton(onClick = { startGoogleSignIn() }))
        // Guest mode hidden from UI as per requirements, preserving underlying functions for future if needed:
        // card.addView(authGuestButton(onClick = {
        //     com.jeeneet.mocktest.utils.PrefManager.setGuestMode(this@LoginActivity, true)
        //     startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        //     finish()
        // }))

        container.addView(card)
        container.addView(authFooter("Don't have an account?", "Sign Up") {
            startActivity(Intent(this@LoginActivity, SignupActivity::class.java))
        })

        root.addView(container)
        return root
    }

    // ─── Auth logic ──────────────────────────────────────────────────────────

    private fun performLogin() {
        if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)) {
            showNoInternetToast()
            return
        }
        val email = etEmail.text.toString().trim()
        val pass = etPass.text.toString().trim()

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Enter valid email", Toast.LENGTH_SHORT).show()
            return
        }
        if (pass.isEmpty()) {
            Toast.makeText(this, "Enter password", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser
                if (user?.isEmailVerified == true) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "Please verify your email to login.", Toast.LENGTH_LONG).show()
                    auth.signOut()
                }
            } else {
                Toast.makeText(this, "Login Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startGoogleSignIn() {
        if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)) {
            showNoInternetToast()
            return
        }
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser ?: run {
                    Toast.makeText(this, "Sign-in failed: user not found", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }
                val isNewUser = task.result?.additionalUserInfo?.isNewUser == true
                if (isNewUser) {
                    showUsernameSetupDialog(
                        uid = user.uid,
                        email = user.email ?: "",
                        suggestedName = user.displayName ?: ""
                    )
                } else {
                    goToMain()
                }
            } else {
                Toast.makeText(this, "Google Auth Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToMain() {
        com.jeeneet.mocktest.utils.PrefManager.clearGuestMode(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showUsernameSetupDialog(uid: String, email: String, suggestedName: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, Space.XXL.dp, 28.dp, Space.S.dp)
        }

        // Gold avatar circle with user initial
        val initial = suggestedName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
        val avatar = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp).also {
                it.gravity = Gravity.CENTER
                it.bottomMargin = 14.dp
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(colorPrimary, colorPrimary)
            }
            addView(TextView(this@LoginActivity).apply {
                text = initial; textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        content.addView(avatar)

        content.addView(uiTextView(UiText.H2, "Complete Your Profile", textPrimary, Gravity.CENTER).apply {
            layoutParams = lpRow(bottomDp = Space.XS)
        })
        content.addView(uiTextView(UiText.CAPTION, "Signed in as $email", textMuted, Gravity.CENTER).apply {
            layoutParams = lpRow(bottomDp = Space.XL)
        })

        content.addView(authFieldLabel("Username"))
        val etUsername = authField("Enter your username", InputType.TYPE_CLASS_TEXT).apply {
            setText(suggestedName)
            setSelection(suggestedName.length)
        }
        content.addView(etUsername)

        val btnConfirm = uiPrimaryButton("Get Started").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 54.dp
            ).also { it.topMargin = Space.S.dp; it.bottomMargin = Space.S.dp }
        }
        content.addView(btnConfirm)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(content)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(bgSecondary)
            cornerRadius = Corner.XXL.dpF
            setStroke(1.dp, dividerColor)
        })

        btnConfirm.setOnClickListener {
            val username = etUsername.text.toString().trim()
            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnConfirm.isEnabled = false
            btnConfirm.text = "Saving…"
            db.collection("users").document(uid)
                .set(User(uid = uid, username = username, email = email, age = 0, termsAccepted = true))
                .addOnSuccessListener {
                    dialog.dismiss()
                    goToMain()
                }
                .addOnFailureListener {
                    dialog.dismiss()
                    goToMain()
                }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
