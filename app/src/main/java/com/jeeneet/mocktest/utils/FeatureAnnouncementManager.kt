package com.jeeneet.mocktest.utils

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
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
import com.google.firebase.firestore.FirebaseFirestore
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.ui.style.*

/**
 * FeatureAnnouncementManager checks Firestore metadata/app_announcement for instant
 * feature announcements broadcast by admin scripts, triggering a rich in-app modal popup
 * on any app version regardless of versionCode checks.
 */
object FeatureAnnouncementManager {

    private const val TAG = "FeatureAnnouncement"
    private const val DEFAULT_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.jeeneet.mocktest&pli=1"

    data class AnnouncementConfig(
        val broadcastId: String = "",
        val title: String = "",
        val message: String = "",
        val releaseNotes: List<String> = emptyList(),
        val buttonText: String = "",
        val actionUrl: String = "",
        val active: Boolean = true
    )

    fun checkAndShow(
        activity: Activity,
        firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        firestore.collection("metadata").document("app_announcement")
            .get()
            .addOnSuccessListener { doc ->
                if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (doc == null || !doc.exists()) return@addOnSuccessListener

                val active = doc.getBoolean("active") ?: true
                if (!active) return@addOnSuccessListener

                val broadcastId = doc.getString("broadcastId") ?: doc.getString("broadcast_id") ?: ""
                if (broadcastId.isBlank()) return@addOnSuccessListener

                val lastSeenId = PrefManager.getLastSeenBroadcastId(activity)
                if (broadcastId == lastSeenId) {
                    Log.d(TAG, "Broadcast $broadcastId already seen by user. Skipping dialog.")
                    return@addOnSuccessListener
                }

                val title = doc.getString("title") ?: "New Features Released! 🚀"
                val message = doc.getString("message") ?: "Check out what's new in JEE & NEET Mock Test!"
                val buttonText = doc.getString("buttonText") ?: "Update on Play Store"
                val actionUrl = doc.getString("actionUrl") ?: doc.getString("playStoreUrl") ?: DEFAULT_PLAY_STORE_URL

                val releaseNotes: List<String> = when (val raw = doc.get("releaseNotes")) {
                    is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                    is String -> raw.split("\n", ";").map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }
                    else -> emptyList()
                }

                val config = AnnouncementConfig(
                    broadcastId = broadcastId,
                    title = title,
                    message = message,
                    releaseNotes = releaseNotes,
                    buttonText = buttonText,
                    actionUrl = if (actionUrl.isNotBlank()) actionUrl else DEFAULT_PLAY_STORE_URL,
                    active = active
                )

                activity.runOnUiThread {
                    showAnnouncementDialog(activity, config)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Announcement fetch failed: ${e.message}")
            }
    }

    private fun showAnnouncementDialog(activity: Activity, config: AnnouncementConfig) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val ctx = activity

        val card = MaterialCardView(ctx).apply {
            radius = Corner.XL.dpF
            cardElevation = 18.dpF
            strokeWidth = 1.dp
            strokeColor = Color.parseColor("#4D3B82F6")
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

        // 1. Icon Badge
        val iconBadge = FrameLayout(ctx).apply {
            val sz = 60.dp
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                bottomMargin = Space.MD.dp
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Corner.L.dpF
                setColor(Color.parseColor("#1F3B82F6"))
                setStroke(1.dp, Color.parseColor("#4D3B82F6"))
            }

            addView(ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(32.dp, 32.dp).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.ic_stars)
                setColorFilter(Color.parseColor("#3B82F6"))
            })
        }
        content.addView(iconBadge)

        // 2. Title
        val tvTitle = TextView(ctx).apply {
            text = config.title
            applyTypography(Typography.TITLE_LARGE)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Space.XS.dp
            }
        }
        content.addView(tvTitle)

        // 3. Subtitle / Message
        if (config.message.isNotBlank()) {
            val tvMsg = TextView(ctx).apply {
                text = config.message
                applyTypography(Typography.BODY_MEDIUM)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = Space.LG.dp
                }
            }
            content.addView(tvMsg)
        }

        // 4. Release Notes Container
        if (config.releaseNotes.isNotEmpty()) {
            val notesCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Space.MD.dp, Space.MD.dp, Space.MD.dp, Space.MD.dp)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Corner.MD.dpF
                    setColor(Color.parseColor("#0F172A"))
                    setStroke(1.dp, Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = Space.XL.dp
                }
            }

            config.releaseNotes.forEach { noteText ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = Space.XS.dp
                    }
                }

                val bullet = TextView(ctx).apply {
                    text = "•"
                    applyTypography(Typography.BODY_MEDIUM)
                    setTextColor(Color.parseColor("#3B82F6"))
                    setPadding(0, 0, Space.SM.dp, 0)
                }

                val note = TextView(ctx).apply {
                    text = noteText
                    applyTypography(Typography.BODY_MEDIUM)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                }

                row.addView(bullet)
                row.addView(note, LinearLayout.LayoutParams(-1, -2))
                notesCard.addView(row)
            }
            content.addView(notesCard)
        }

        // 5. Action Button ("Update on Play Store")
        val btnAction = MaterialButton(ctx).apply {
            text = config.buttonText.ifBlank { "Update on Play Store" }
            applyTypography(Typography.LABEL_LARGE)
            setTextColor(Color.WHITE)
            cornerRadius = Corner.LG.dp
            elevation = 4.dpF
            backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.brand_blue)
            layoutParams = LinearLayout.LayoutParams(-1, 48.dp).apply {
                bottomMargin = Space.SM.dp
            }

            setOnClickListener {
                PrefManager.setLastSeenBroadcastId(ctx, config.broadcastId)
                dialog.dismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.actionUrl))
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open action URL: ${e.message}")
                }
            }
        }
        content.addView(btnAction)

        // 6. Secondary Dismiss Button
        val btnLater = TextView(ctx).apply {
            text = "Dismiss"
            applyTypography(Typography.LABEL_MEDIUM)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(Space.MD.dp, Space.SM.dp, Space.MD.dp, Space.SM.dp)
            background = RippleDrawable(
                ContextCompat.getColorStateList(ctx, R.color.bg_tertiary)!!,
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Corner.SM.dpF
                }
            )

            setOnClickListener {
                PrefManager.setLastSeenBroadcastId(ctx, config.broadcastId)
                dialog.dismiss()
            }
        }
        content.addView(btnLater)

        scroll.addView(content)
        card.addView(scroll)

        val rootWrapper = FrameLayout(ctx).apply {
            setPadding(Space.LG.dp, Space.LG.dp, Space.LG.dp, Space.LG.dp)
            addView(card)
        }

        dialog.setContentView(rootWrapper)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        dialog.show()
    }
}
