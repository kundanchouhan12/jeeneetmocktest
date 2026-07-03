package com.jeeneet.mocktest.ui.style

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.jeeneet.mocktest.R

// ─── DP / DPF — defined once, removes the per-activity duplication ──────────

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()
val Int.dpF: Float get() = this * Resources.getSystem().displayMetrics.density
val Float.dpF: Float get() = this * Resources.getSystem().displayMetrics.density

// ─── 8dp spacing grid ────────────────────────────────────────────────────────

object Space {
    const val XS   = 4
    const val S    = 8
    const val M    = 12
    const val L    = 16
    const val XL   = 20
    const val XXL  = 24
    const val XXXL = 32
    const val HUGE = 48
}

// ─── Corner radius scale ─────────────────────────────────────────────────────

object Corner {
    const val S   = 10f
    const val M   = 14f
    const val L   = 20f
    const val XL  = 24f
    const val XXL = 28f
    const val PILL = 999f
}

// ─── Elevation scale ─────────────────────────────────────────────────────────

object Elev {
    const val NONE = 0f
    const val S    = 2f
    const val M    = 4f
    const val L    = 8f
}

// ─── Theme color accessors — resolve via ContextCompat so day/night works ───

val Context.bgPrimary: Int     get() = ContextCompat.getColor(this, R.color.bg_primary)
val Context.bgSecondary: Int   get() = ContextCompat.getColor(this, R.color.bg_secondary)
val Context.bgTertiary: Int    get() = ContextCompat.getColor(this, R.color.bg_tertiary)
val Context.textPrimary: Int   get() = ContextCompat.getColor(this, R.color.text_primary)
val Context.textSecondary: Int get() = ContextCompat.getColor(this, R.color.text_secondary)
val Context.textTertiary: Int  get() = ContextCompat.getColor(this, R.color.text_tertiary)
val Context.textMuted: Int     get() = ContextCompat.getColor(this, R.color.text_muted)
val Context.goldPrimary: Int   get() = ContextCompat.getColor(this, R.color.gold_primary)
val Context.goldLight: Int     get() = ContextCompat.getColor(this, R.color.gold_light)
val Context.goldDark: Int      get() = ContextCompat.getColor(this, R.color.gold_dark)
val Context.colorPrimary: Int  get() = ContextCompat.getColor(this, R.color.color_primary)
val Context.dividerColor: Int  get() = ContextCompat.getColor(this, R.color.divider)

// Status colors (correct/wrong/skipped/review)
val Context.correctGreen: Int   get() = ContextCompat.getColor(this, R.color.correct_green)
val Context.wrongRed: Int       get() = ContextCompat.getColor(this, R.color.wrong_red)
val Context.reviewOrange: Int   get() = ContextCompat.getColor(this, R.color.review_orange)
val Context.markedPurple: Int   get() = ContextCompat.getColor(this, R.color.marked_purple)
val Context.skippedGray: Int    get() = ContextCompat.getColor(this, R.color.skipped_gray)

// ─── Typography system ───────────────────────────────────────────────────────

enum class UiText(val size: Float, val bold: Boolean) {
    DISPLAY (28f, true),    // hero titles, empty states
    H1      (22f, true),    // screen / header titles
    H2      (18f, true),    // section headers
    H3      (16f, true),    // card titles
    BODY    (14f, false),   // paragraphs, list items
    LABEL   (13f, false),   // form labels, metadata
    CAPTION (12f, false),   // secondary info, hints
    OVERLINE(10f, true),    // badges, tags
    BUTTON  (15f, true)     // button labels
}

/** Build a TextView styled with the given token. */
fun Context.uiTextView(
    style: UiText,
    text: CharSequence = "",
    color: Int = textPrimary,
    gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    this.text = text
    textSize = style.size
    setTextColor(color)
    this.gravity = gravity
    typeface = when (style) {
        UiText.DISPLAY, UiText.H1 -> Typeface.create("sans-serif-black", Typeface.BOLD) // ExtraBold
        UiText.H2, UiText.OVERLINE -> Typeface.create("sans-serif", Typeface.BOLD) // Bold
        UiText.H3, UiText.LABEL, UiText.BUTTON -> Typeface.create("sans-serif-medium", Typeface.NORMAL) // Medium
        else -> Typeface.create("sans-serif", Typeface.NORMAL) // Regular
    }
    setLineSpacing(0f, 1.25f) // Increased line spacing slightly for premium readability
}

// ─── Cards ───────────────────────────────────────────────────────────────────

/**
 * Material card with sensible defaults. Pass [onClick] to auto-apply ripple
 * feedback without extra wiring.
 */
fun Context.uiCard(
    radius: Float = Corner.L,
    elevation: Float = Elev.M,   // slightly higher default — needed for shadow on white bg
    background: Int = bgSecondary,
    strokeDp: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
    startColor: Int = Color.TRANSPARENT,
    endColor: Int = Color.TRANSPARENT,
    onClick: (() -> Unit)? = null
): MaterialCardView = MaterialCardView(this).apply {
    this.radius = radius.dpF
    this.cardElevation = elevation.dpF
    if (startColor != Color.TRANSPARENT && endColor != Color.TRANSPARENT) {
        // Gradient card — use a GradientDrawable as the card foreground overlay
        setCardBackgroundColor(startColor)
        val grad = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(startColor, endColor)
        ).apply { cornerRadius = radius.dpF }
        // Can't apply gradient to MaterialCardView background directly; set via background field
        this.background = grad
    } else {
        setCardBackgroundColor(background)
    }
    this.strokeWidth = if (strokeDp > 0) strokeDp.dp else 0
    if (strokeDp > 0) this.strokeColor = strokeColor
    if (onClick != null) {
        isClickable = true
        isFocusable = true
        val tv = TypedValue()
        val hasAttr = context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (hasAttr && tv.resourceId != 0) {
            foreground = ContextCompat.getDrawable(context, tv.resourceId)
        }
        setOnClickListener { onClick() }
    }
}

/** 🚀 Special card for AI features with a subtle premium glow. */
fun Context.uiAiCard(
    radius: Float = Corner.L,
    elevation: Float = Elev.M,
    accentColor: Int = goldPrimary,
    onClick: (() -> Unit)? = null
): MaterialCardView = uiCard(
    radius = radius,
    elevation = elevation,
    strokeDp = 1,
    strokeColor = Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
    onClick = onClick
).apply {
    // Optional: add a tiny internal glow if needed, but stroke is usually enough
}

// ─── Buttons ─────────────────────────────────────────────────────────────────

/** Full-width primary CTA — blue (light) / gold (dark) gradient. */
fun Context.uiPrimaryButton(
    text: String,
    heightDp: Int = 54,
    onClick: () -> Unit = {}
): MaterialButton = MaterialButton(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    textSize = UiText.BUTTON.size
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    isAllCaps = false
    background = ContextCompat.getDrawable(context, R.drawable.bg_button_primary)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, heightDp.dp
    )
    setOnClickListener { onClick() }
}

/** Full-width outlined button — transparent fill, colored border + text. */
fun Context.uiOutlinedButton(
    text: String,
    heightDp: Int = 54,
    color: Int = colorPrimary,
    onClick: () -> Unit = {}
): MaterialButton = MaterialButton(this).apply {
    this.text = text
    setTextColor(color)
    textSize = UiText.BUTTON.size
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    isAllCaps = false
    background = GradientDrawable().apply {
        cornerRadius = Corner.XXL.dpF
        setColor(Color.TRANSPARENT)
        setStroke(2.dp, color)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, heightDp.dp
    )
    setOnClickListener { onClick() }
}

/** Danger action — solid red, for destructive operations (logout, delete). */
fun Context.uiDangerButton(
    text: String,
    heightDp: Int = 54,
    onClick: () -> Unit = {}
): MaterialButton = MaterialButton(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    textSize = UiText.BUTTON.size
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    isAllCaps = false
    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, heightDp.dp
    )
    setOnClickListener { onClick() }
}

// ─── Header / toolbar ────────────────────────────────────────────────────────

/**
 * Standard gradient header with back button + title, matching the existing
 * bg_gradient_header drawable used across the app.
 */
fun Context.uiHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: View? = null
): LinearLayout {
    val activity = this as android.app.Activity
    // White header — dark status bar icons (light status bar)
    activity.window.statusBarColor = ContextCompat.getColor(this, R.color.bg_secondary)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        activity.window.decorView.systemUiVisibility =
            activity.window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(Space.L.dp, Space.HUGE.dp, Space.L.dp, Space.M.dp)
        setBackgroundColor(bgSecondary)
        elevation = Elev.S.dpF
        gravity = Gravity.CENTER_VERTICAL

        if (onBack != null) {
            addView(FrameLayout(this@uiHeader).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = Space.S.dp }
                background = GradientDrawable().apply {
                    cornerRadius = Corner.M.dpF
                    setColor(ContextCompat.getColor(context, R.color.bg_tertiary))
                }
                val tv = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true) && tv.resourceId != 0)
                    foreground = ContextCompat.getDrawable(context, tv.resourceId)
                isClickable = true; isFocusable = true
                setOnClickListener { onBack() }
                addView(TextView(this@uiHeader).apply {
                    text = "←"; textSize = 20f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                })
            })
        }

        addView(TextView(this@uiHeader).apply {
            this.text = title
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        trailing?.let { addView(it) }
    }
}

// ─── Section label — small caps heading above content groups ───────────────

fun Context.uiSectionLabel(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 11f
    setTextColor(colorPrimary)
    setPadding(Space.XS.dp, Space.XL.dp, 0, Space.S.dp)
    isAllCaps = true
    letterSpacing = 0.15f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
}

// ─── Icon box — rounded square or circle containing a centered emoji ────────

fun Context.uiIconBox(
    sizeDp: Int,
    background: Int,
    emoji: String? = null,
    emojiSize: Float = 20f,
    oval: Boolean = false,
    radius: Float = Corner.M,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1
): FrameLayout = FrameLayout(this).apply {
    this.background = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL
        else cornerRadius = radius.dpF
        setColor(background)
        if (strokeColor != null) setStroke(strokeWidthDp.dp, strokeColor)
    }
    layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
    if (emoji != null) {
        addView(TextView(this@uiIconBox).apply {
            text = emoji
            textSize = emojiSize
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
    }
}

// ─── Small pill badge — rounded tag for status/metadata ─────────────────────

fun Context.uiBadge(
    text: String,
    textColor: Int,
    bgColor: Int,
    radius: Float = 6f
): TextView = TextView(this).apply {
    this.text = text
    textSize = 11f
    setTextColor(textColor)
    background = roundedFill(bgColor, radius)
    setPadding(Space.S.dp, Space.XS.dp, Space.S.dp, Space.XS.dp)
}

// ─── Empty state — big emoji + title + subtitle ─────────────────────────────

fun Context.uiEmptyView(
    emoji: String,
    title: String,
    subtitle: String,
    heightDp: Int = 400
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, heightDp.dp
    )
    addView(TextView(this@uiEmptyView).apply {
        text = emoji; textSize = 52f; gravity = Gravity.CENTER
    })
    addView(uiTextView(UiText.H3, title, textPrimary, Gravity.CENTER).apply {
        setPadding(Space.XXL.dp, 14.dp, Space.XXL.dp, 0)
    })
    addView(uiTextView(UiText.LABEL, subtitle, textMuted, Gravity.CENTER).apply {
        setPadding(Space.XXXL.dp, Space.S.dp, Space.XXXL.dp, 0)
    })
}

// ─── Colored status-bar-aware toolbar (solid color, back button + title) ────

fun Context.uiColoredToolbar(
    title: String,
    bgColor: Int,
    onBack: (() -> Unit)? = null
): LinearLayout {
    val activity = this as android.app.Activity
    activity.window.statusBarColor = bgColor
    val statusBarHeight = with(activity.resources) {
        val id = getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) getDimensionPixelSize(id) else 0
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bgColor)
        setPadding(Space.XS.dp, statusBarHeight + Space.XS.dp, Space.L.dp, Space.XS.dp)
        elevation = Elev.M.dpF

        if (onBack != null) {
            addView(android.widget.ImageButton(this@uiColoredToolbar).apply {
                setImageResource(android.R.drawable.ic_media_previous)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp)
                setOnClickListener { onBack() }
            })
        }
        addView(TextView(this@uiColoredToolbar).apply {
            this.text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginStart = Space.S.dp }
        })
    }
}

// ─── Layout helpers ──────────────────────────────────────────────────────────

/** Vertical spacer — invisible View with a fixed height. */
fun Context.uiSpacer(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, heightDp.dp
    )
}

/** 1dp horizontal rule using the theme divider color. */
fun Context.uiDivider(leftDp: Int = 0, rightDp: Int = 0): View = View(this).apply {
    setBackgroundColor(dividerColor)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 1.dp
    ).also { it.marginStart = leftDp.dp; it.marginEnd = rightDp.dp }
}

/** MATCH_PARENT × WRAP_CONTENT LinearLayout params with optional margins. */
fun lpRow(topDp: Int = 0, bottomDp: Int = 0, leftDp: Int = 0, rightDp: Int = 0)
    : LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).also {
        it.topMargin    = topDp.dp
        it.bottomMargin = bottomDp.dp
        it.leftMargin   = leftDp.dp
        it.rightMargin  = rightDp.dp
    }

/** MATCH_PARENT × MATCH_PARENT LinearLayout params. */
fun lpFill(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.MATCH_PARENT
    )

// ─── Drawable helpers ────────────────────────────────────────────────────────

/** Solid-color rounded background — for chips, tags, inline fills. */
fun roundedFill(color: Int, radius: Float = Corner.M): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.dpF
    }

/** Outlined rounded background — transparent fill with stroke. */
fun roundedStroke(strokeColor: Int, widthDp: Int = 1, radius: Float = Corner.M): GradientDrawable =
    GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(widthDp.dp, strokeColor)
        cornerRadius = radius.dpF
    }

// ─── Premium Error UI ─────────────────────────────────────────────────────────

/** Displays a sleek, custom toast when there is no internet connection. */
fun Context.showNoInternetToast() {
    val toast = Toast(this)
    toast.duration = Toast.LENGTH_LONG
    
    val view = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedFill(Color.parseColor("#E11D48"), Corner.PILL) // Rose-600
        setPadding(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
        
        addView(TextView(this@showNoInternetToast).apply {
            text = "🔌"
            textSize = 18f
            setPadding(0, 0, Space.M.dp, 0)
        })
        
        addView(TextView(this@showNoInternetToast).apply {
            text = "No internet connection"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
    }
    
    toast.view = view
    toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 100.dp)
    toast.show()
}
