package com.jeeneet.mocktest.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.jeeneet.mocktest.BuildConfig
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.ui.style.*

/**
 * InAppUpdateManager handles remote version verification against Firestore
 * (app_config/version_control and metadata/app_version) and displays a rich,
 * modern dark-mode modal dialog when a new version is released.
 */
object InAppUpdateManager {

    private const val TAG = "InAppUpdateManager"

    data class AppUpdateConfig(
        val latestVersionCode: Int = 0,
        val latestVersionName: String = "",
        val minRequiredVersionCode: Int = 0,
        val isForceUpdate: Boolean = false,
        val title: String = "",
        val message: String = "",
        val releaseNotes: List<String> = emptyList(),
        val playStoreUrl: String = ""
    )

    /**
     * Checks Firestore for app updates.
     * @param activity Hosting activity (used for UI lifecycle and dialog presentation).
     * @param firestore FirebaseFirestore instance.
     * @param onNoUpdate Optional callback invoked when no Firestore update prompt is required.
     */
    fun checkForUpdate(
        activity: Activity,
        firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
        onNoUpdate: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        // Check app_config/version_control first with Source.SERVER to bypass stale cache
        firestore.collection("app_config").document("version_control")
            .get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (doc != null && doc.exists()) {
                    processVersionDoc(activity, doc, onNoUpdate)
                } else {
                    // Fallback to metadata/app_version
                    checkMetadataCollection(activity, firestore, onNoUpdate)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Server query to app_config/version_control failed (${e.message}), trying cache/fallback...")
                checkMetadataCollection(activity, firestore, onNoUpdate)
            }
    }

    private fun checkMetadataCollection(
        activity: Activity,
        firestore: FirebaseFirestore,
        onNoUpdate: (() -> Unit)?
    ) {
        firestore.collection("metadata").document("app_version")
            .get()
            .addOnSuccessListener { doc ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (doc != null && doc.exists()) {
                    processVersionDoc(activity, doc, onNoUpdate)
                } else {
                    onNoUpdate?.invoke()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Update check failed completely: ${e.message}")
                onNoUpdate?.invoke()
            }
    }

    private fun processVersionDoc(
        activity: Activity,
        doc: DocumentSnapshot,
        onNoUpdate: (() -> Unit)?
    ) {
        try {
            val latestVersionCode = (doc.get("latestVersionCode") as? Number)?.toInt()
                ?: (doc.get("latest_version_code") as? Number)?.toInt()
                ?: 0
            val latestVersionName = doc.getString("latestVersionName")
                ?: doc.getString("latest_version_name")
                ?: ""
            val minRequiredVersionCode = (doc.get("minRequiredVersionCode") as? Number)?.toInt()
                ?: (doc.get("min_required_version_code") as? Number)?.toInt()
                ?: 0
            val isForceUpdate = doc.getBoolean("forceUpdateEnabled")
                ?: doc.getBoolean("isForceUpdate")
                ?: false
            val title = doc.getString("title") ?: ""
            val message = doc.getString("message") ?: ""
            val playStoreUrl = doc.getString("playStoreUrl") ?: ""

            // Parse release notes (can be a List<String> or a newline-separated String)
            val releaseNotes: List<String> = when (val raw = doc.get("releaseNotes")) {
                is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is String -> raw.split("\n", ";").map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }

            val config = AppUpdateConfig(
                latestVersionCode = latestVersionCode,
                latestVersionName = latestVersionName,
                minRequiredVersionCode = minRequiredVersionCode,
                isForceUpdate = isForceUpdate,
                title = title,
                message = message,
                releaseNotes = releaseNotes,
                playStoreUrl = playStoreUrl
            )

            val currentVersionCode = BuildConfig.VERSION_CODE

            // 1. Mandatory / Force Update Check:
            // Either forceUpdateEnabled is true and user is behind latest/minRequired,
            // or user's version is strictly below minRequiredVersionCode.
            val isMandatory = (config.isForceUpdate && currentVersionCode < config.latestVersionCode) ||
                    (config.minRequiredVersionCode > 0 && currentVersionCode < config.minRequiredVersionCode)

            if (isMandatory) {
                Log.i(TAG, "🚨 Mandatory update detected (current: $currentVersionCode, minRequired: ${config.minRequiredVersionCode}, latest: ${config.latestVersionCode})")
                activity.runOnUiThread {
                    showUpdateDialog(activity, config, isMandatory = true)
                }
                return
            }

            // 2. Optional / Recommended Update Check:
            if (config.latestVersionCode > currentVersionCode) {
                if (PrefManager.shouldShowUpdatePrompt(activity, config.latestVersionCode)) {
                    Log.i(TAG, "✨ Optional update available (current: $currentVersionCode, latest: ${config.latestVersionCode})")
                    activity.runOnUiThread {
                        showUpdateDialog(activity, config, isMandatory = false)
                    }
                } else {
                    Log.d(TAG, "Optional update available but currently in 24h dismissal cooldown.")
                    onNoUpdate?.invoke()
                }
            } else {
                Log.d(TAG, "App is up to date (current: $currentVersionCode, latest: ${config.latestVersionCode})")
                onNoUpdate?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating update configuration: ${e.message}", e)
            onNoUpdate?.invoke()
        }
    }

    /**
     * Renders a custom dark-mode in-app update popup dialog matching the app's design system.
     */
    fun showUpdateDialog(
        activity: Activity,
        config: AppUpdateConfig,
        isMandatory: Boolean
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(!isMandatory)
        dialog.setCanceledOnTouchOutside(!isMandatory)

        // Block hardware back button if mandatory
        if (isMandatory) {
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    activity.finishAffinity()
                    true
                } else {
                    false
                }
            }
        }

        // Setup dialog container and styling
        val ctx = activity
        val isDark = true

        val card = MaterialCardView(ctx).apply {
            radius = Corner.XL.dpF
            cardElevation = 18.dpF
            strokeWidth = 1.dp
            strokeColor = if (isMandatory) Color.parseColor("#4DEF4444") else Color.parseColor("#4D3B82F6")
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_secondary))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(-1, -2)
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Space.XXL.dp, Space.XXL.dp, Space.XXL.dp, Space.XL.dp)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // 1. Icon Badge Squircle
        val iconBadge = FrameLayout(ctx).apply {
            val size = 64.dp
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.bottomMargin = Space.M.dp
            }
            background = GradientDrawable().apply {
                cornerRadius = 20.dpF
                if (isMandatory) {
                    colors = intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#B91C1C"))
                } else {
                    colors = intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#8B5CF6"))
                }
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }

        val iconTv = TextView(ctx).apply {
            text = if (isMandatory) "🚨" else "🚀"
            textSize = 28f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        iconBadge.addView(iconTv)
        content.addView(iconBadge)

        // 2. Title
        val dialogTitle = if (config.title.isNotBlank()) {
            config.title
        } else if (isMandatory) {
            "Critical Update Required"
        } else {
            "New Update Available! 🎉"
        }

        val titleTv = TextView(ctx).apply {
            text = dialogTitle
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = Space.S.dp
            }
        }
        content.addView(titleTv)

        // 3. Version comparison pill badge
        val currentVersionName = BuildConfig.VERSION_NAME
        val targetVersionName = config.latestVersionName.ifEmpty { "v${config.latestVersionCode}" }

        val badgeContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = Corner.PILL.dpF
                setColor(if (isMandatory) Color.parseColor("#26EF4444") else Color.parseColor("#263B82F6"))
                setStroke(1.dp, if (isMandatory) Color.parseColor("#4DEF4444") else Color.parseColor("#4D3B82F6"))
            }
            setPadding(Space.M.dp, 4.dp, Space.M.dp, 4.dp)
            layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                it.bottomMargin = Space.M.dp
            }
        }

        val badgeTv = TextView(ctx).apply {
            text = "v$currentVersionName  ➔  $targetVersionName"
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (isMandatory) Color.parseColor("#FCA5A5") else Color.parseColor("#93C5FD"))
        }
        badgeContainer.addView(badgeTv)
        content.addView(badgeContainer)

        // 4. Description / Message
        val dialogMessage = if (config.message.isNotBlank()) {
            config.message
        } else if (isMandatory) {
            "A critical update is required to continue preparing for JEE & NEET with the latest question bank and features."
        } else {
            "An improved version of JEE & NEET Mock Test is here with new features, diagram questions, and performance upgrades!"
        }

        val messageTv = TextView(ctx).apply {
            text = dialogMessage
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = Space.L.dp
            }
        }
        content.addView(messageTv)

        // 5. "What's New" Section (if releaseNotes has entries)
        val notes = if (config.releaseNotes.isNotEmpty()) {
            config.releaseNotes
        } else if (!isMandatory) {
            listOf(
                "Visual STEM & Diagram questions with Pinch-to-Zoom",
                "High-speed CDN & offline diagram rendering",
                "Enhanced KaTeX formula and chemical notation",
                "Stability, speed, and practice test refinements"
            )
        } else {
            emptyList()
        }

        if (notes.isNotEmpty()) {
            val notesCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Corner.M.dpF
                    setColor(ContextCompat.getColor(ctx, R.color.bg_tertiary))
                    setStroke(1.dp, ContextCompat.getColor(ctx, R.color.divider))
                }
                setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
                layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                    it.bottomMargin = Space.XL.dp
                }
            }

            val notesHeader = TextView(ctx).apply {
                text = "WHAT'S NEW"
                textSize = 11f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.gold_primary))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                    it.bottomMargin = Space.S.dp
                }
            }
            notesCard.addView(notesHeader)

            notes.forEach { note ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                        it.bottomMargin = 5.dp
                    }
                }
                val bullet = TextView(ctx).apply {
                    text = "✦ "
                    textSize = 11f
                    setTextColor(Color.parseColor("#60A5FA"))
                }
                val noteTv = TextView(ctx).apply {
                    text = note
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                row.addView(bullet)
                row.addView(noteTv)
                notesCard.addView(row)
            }

            content.addView(notesCard)
        }

        // 6. Action Buttons
        val btnContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // Update Now Button
        val btnUpdate = MaterialButton(ctx).apply {
            text = "Update Now 📲"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            cornerRadius = Corner.L.toInt().dp
            background = GradientDrawable().apply {
                cornerRadius = Corner.L.dpF
                if (isMandatory) {
                    colors = intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#DC2626"))
                } else {
                    colors = intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#1D4ED8"))
                }
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            layoutParams = LinearLayout.LayoutParams(-1, 52.dp).also {
                it.bottomMargin = if (isMandatory) 0 else Space.S.dp
            }
            setOnClickListener {
                openPlayStore(ctx, config.playStoreUrl)
                if (!isMandatory) {
                    PrefManager.setUpdatePromptDismissed(ctx, config.latestVersionCode)
                    dialog.dismiss()
                }
            }
        }
        btnContainer.addView(btnUpdate)

        // Maybe Later Button (shown ONLY for optional updates)
        if (!isMandatory) {
            val btnLater = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Maybe Later"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                textSize = 13.5f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(-1, 44.dp)
                setOnClickListener {
                    PrefManager.setUpdatePromptDismissed(ctx, config.latestVersionCode)
                    dialog.dismiss()
                }
            }
            btnContainer.addView(btnLater)
        }

        content.addView(btnContainer)
        scroll.addView(content)
        card.addView(scroll)

        dialog.setContentView(card)

        // Set window dimension and transparent frame
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.75f)
            val displayMetrics = ctx.resources.displayMetrics
            val maxDialogWidth = 440.dp
            val screenWidth = displayMetrics.widthPixels
            val dialogWidth = (screenWidth * 0.90).toInt().coerceAtMost(maxDialogWidth)
            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display update dialog: ${e.message}")
        }
    }

    /**
     * Dispatches intent to Play Store with robust web fallback.
     */
    fun openPlayStore(context: Context, customUrl: String = "") {
        val packageName = context.packageName
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = if (customUrl.isNotBlank()) {
            Uri.parse(customUrl)
        } else {
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        }

        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not open Play Store or browser: ${e2.message}")
            }
        }
    }
}
