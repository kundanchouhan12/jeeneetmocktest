package com.jeeneet.mocktest.ui.profile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.jeeneet.mocktest.BuildConfig
import com.jeeneet.mocktest.ui.auth.LoginActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvName: TextView
    private lateinit var tvEmail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        updateUserInfo()
    }

    override fun onResume() {
        super.onResume()
        updateUserInfo()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(uiHeader("My Profile", onBack = { finish() }))

        val profileCard = uiCard(
            radius = Corner.XXL,
            elevation = Elev.L,
            background = bgSecondary
        ).apply {
            layoutParams = lpRow(
                topDp = Space.XXXL,
                leftDp = Space.XXL,
                rightDp = Space.XXL
            )
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Space.XXL.dp, Space.XXXL.dp, Space.XXL.dp, Space.XXXL.dp)
        }

        // Avatar — gold tinted icon in a soft-gold circle
        val ivAvatar = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            imageTintList = ColorStateList.valueOf(goldPrimary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1AF59E0B"))
            }
            setPadding(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
            layoutParams = LinearLayout.LayoutParams(100.dp, 100.dp).also {
                it.bottomMargin = Space.XXL.dp
            }
        }

        tvName = uiTextView(UiText.H1, "Loading…", textPrimary, Gravity.CENTER)
        tvEmail = uiTextView(UiText.BODY, "loading…", textTertiary, Gravity.CENTER).apply {
            setPadding(0, Space.XS.dp, 0, Space.XXXL.dp)
        }

        val btnEdit = uiPrimaryButton("Edit Details", onClick = { showEditDialog() })

        val btnLogout = uiDangerButton("Logout", onClick = { confirmLogout() }).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).also {
                it.topMargin = Space.M.dp
            }
        }

        inner.addView(ivAvatar)
        inner.addView(tvName)
        inner.addView(tvEmail)
        inner.addView(btnEdit)
        inner.addView(btnLogout)

        if (BuildConfig.DEBUG) {
            inner.addView(buildDebugFreeModeButton())
        }

        profileCard.addView(inner)
        root.addView(profileCard)

        return root
    }

    private fun buildDebugFreeModeButton(): View {
        fun label(on: Boolean) =
            if (on) "DEV: Free Mode ON (tap to disable)" else "DEV: Force Free Mode (test ads)"
        fun bg(on: Boolean) =
            if (on) Color.parseColor("#7C3AED") else Color.parseColor("#374151")

        val isFreeMode = PrefManager.isDebugFreeMode(this)

        return com.google.android.material.button.MaterialButton(this).apply {
            text = label(isFreeMode)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(bg(isFreeMode))
            isAllCaps = false
            textSize = UiText.CAPTION.size
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 46.dp
            ).also { it.topMargin = Space.XL.dp }

            setOnClickListener {
                val newState = !PrefManager.isDebugFreeMode(this@ProfileActivity)
                PrefManager.setDebugFreeMode(this@ProfileActivity, newState)
                text = label(newState)
                backgroundTintList = ColorStateList.valueOf(bg(newState))
                Toast.makeText(
                    this@ProfileActivity,
                    if (newState) "Free mode ON — ads will now show"
                    else "Free mode OFF — premium restored",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateUserInfo() {
        val user = FirebaseAuth.getInstance().currentUser
        tvName.text = user?.displayName ?: "No name set"
        tvEmail.text = user?.email
    }

    private fun showEditDialog() {
        val dialog = android.app.AlertDialog.Builder(
            this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        ).create()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XXL.dp, Space.XXL.dp, Space.XXL.dp, Space.XXL.dp)
            setBackgroundColor(bgSecondary)
        }

        val tvLabel = uiTextView(UiText.LABEL, "Update Name", textPrimary).apply {
            setPadding(0, 0, 0, Space.M.dp)
        }
        val etName = EditText(this).apply {
            hint = "Enter full name"
            setTextColor(textPrimary)
            setHintTextColor(textTertiary)
            setText(FirebaseAuth.getInstance().currentUser?.displayName)
        }
        container.addView(tvLabel)
        container.addView(etName)

        dialog.setView(container)
        dialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "Done") { _, _ ->
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                val user = FirebaseAuth.getInstance().currentUser
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(newName).build()
                user?.updateProfile(profileUpdates)?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        user.uid.let { uid ->
                            FirebaseFirestore.getInstance()
                                .collection("users").document(uid)
                                .update("username", newName)
                        }
                        updateUserInfo()
                        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "Cancel") { d, _ -> d.dismiss() }
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(Color.parseColor("#16A34A"))
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(Color.parseColor("#EF4444"))
    }

    private fun confirmLogout() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                PrefManager.clearIAPState(this)
                FirebaseAuth.getInstance().signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
