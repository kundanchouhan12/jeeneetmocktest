package com.jeeneet.mocktest.ui.auth

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.ui.style.*

/**
 * Shared UI pieces for Login + Signup screens. Uses [UiStyle] tokens so any
 * future palette change (colors, radius, spacing) propagates automatically.
 */

// ─── Brand section — logo + title + tagline on gradient ─────────────────────

fun Context.authBrandSection(title: String, subtitle: String): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(this@authBrandSection, R.drawable.bg_gradient_header)
        setPadding(Space.XXL.dp, Space.XXXL.dp, Space.XXL.dp, Space.XL.dp)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = Space.XXL.dp }

        addView(authLogo())
        addView(uiTextView(UiText.H1, title, Color.WHITE, Gravity.CENTER))
        addView(uiTextView(UiText.LABEL, subtitle, Color.parseColor("#CCE8FF"), Gravity.CENTER).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })
    }

private fun Context.authLogo(): FrameLayout {
    val size = 72.dp
    val wrapper = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(size, size).also {
            it.gravity = Gravity.CENTER
            it.bottomMargin = 14.dp
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(3.dp, Color.parseColor("#BFDBFE"))
        }
        clipToOutline = true
    }
    wrapper.addView(ImageView(this).apply {
        setImageResource(R.drawable.updated_logo)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(Space.S.dp, Space.S.dp, Space.S.dp, Space.S.dp)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    })
    return wrapper
}

// ─── Auth card container — rounded card with border ─────────────────────────

fun Context.authCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        setColor(bgSecondary)
        cornerRadius = Corner.XXL.dpF
        setStroke(1.dp, dividerColor)
    }
    setPadding(Space.XXL.dp, 28.dp, Space.XXL.dp, 28.dp)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}

// ─── Form field label ────────────────────────────────────────────────────────

fun Context.authFieldLabel(label: String): TextView = TextView(this).apply {
    text = label
    textSize = UiText.CAPTION.size
    setTextColor(textTertiary)
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    letterSpacing = 0.05f
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.bottomMargin = 6.dp }
}

// ─── Text field (single line) ────────────────────────────────────────────────

fun Context.authField(
    hint: String,
    inputType: Int,
    rightPadDp: Int = Space.L,
    bottomMarginDp: Int = Space.L
): EditText = EditText(this).apply {
    this.hint = hint
    this.inputType = inputType
    setHintTextColor(textMuted)
    setTextColor(textPrimary)
    textSize = 15f
    setPadding(Space.L.dp, Space.L.dp, rightPadDp.dp, Space.L.dp)
    background = GradientDrawable().apply {
        setColor(bgTertiary)
        cornerRadius = 14.dpF
        setStroke(1.dp, dividerColor)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.bottomMargin = bottomMarginDp.dp }
}

/**
 * Password field packaged with its eye-toggle. Returns the [EditText] as
 * the first element and the [FrameLayout] you addView to the parent.
 */
fun Context.authPasswordField(
    hint: String,
    bottomMarginDp: Int = Space.S
): Pair<EditText, FrameLayout> {
    var visible = false
    val field = authField(
        hint,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        rightPadDp = 52,
        bottomMarginDp = 0
    ).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val toggle = ImageView(this).apply {
        setImageResource(android.R.drawable.ic_menu_view)
        imageTintList = ColorStateList.valueOf(textMuted)
        setPadding(Space.M.dp, 14.dp, Space.M.dp, 14.dp)
        layoutParams = FrameLayout.LayoutParams(48.dp, 48.dp).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = 6.dp
        }
        setOnClickListener {
            visible = !visible
            field.transformationMethod = if (visible)
                HideReturnsTransformationMethod.getInstance()
            else
                PasswordTransformationMethod.getInstance()
            setImageResource(
                if (visible) android.R.drawable.ic_menu_close_clear_cancel
                else android.R.drawable.ic_menu_view
            )
            field.setSelection(field.text.length)
        }
    }

    val container = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = bottomMarginDp.dp }
        addView(field)
        addView(toggle)
    }
    return field to container
}

// ─── "Already have an account?" style footer ────────────────────────────────

fun Context.authFooter(prompt: String, ctaText: String, onCtaClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, 28.dp, 0, 0)

        addView(uiTextView(UiText.BODY, "$prompt  ", textMuted))
        addView(uiTextView(UiText.BODY, ctaText, colorPrimary).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setOnClickListener { onCtaClick() }
            isClickable = true
            isFocusable = true
        })
    }

// ─── "OR" divider for login screen ──────────────────────────────────────────

fun Context.authOrDivider(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = Space.XL.dp; it.bottomMargin = Space.XL.dp }

    val line = {
        View(this@authOrDivider).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(0, 1.dp, 1f)
        }
    }
    addView(line())
    addView(uiTextView(UiText.CAPTION, "  OR  ", textMuted))
    addView(line())
}

// ─── Google Sign-In button ──────────────────────────────────────────────────

fun Context.authGoogleButton(onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(bgSecondary)
            cornerRadius = 14.dpF
            setStroke(1.dp, dividerColor)
        }
        setPadding(Space.L.dp, 14.dp, Space.L.dp, 14.dp)
        elevation = Elev.S.dpF
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            54.dp
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        val gCircle = FrameLayout(this@authGoogleButton).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).also { it.marginEnd = 14.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgSecondary)
                setStroke(2.dp, dividerColor)
            }
            addView(TextView(this@authGoogleButton).apply {
                text = "G"; textSize = 18f
                setTextColor(Color.parseColor("#4285F4"))
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        addView(gCircle)
        addView(uiTextView(UiText.BUTTON, "Sign in with Google", textPrimary))
    }

// ─── Guest Mode prominent button ────────────────────────────────────────────

fun Context.authGuestButton(onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(bgSecondary)
            cornerRadius = 14.dpF
            setStroke(1.5f.dpF.toInt().coerceAtLeast(1), colorPrimary)
        }
        setPadding(Space.L.dp, 14.dp, Space.L.dp, 14.dp)
        elevation = Elev.S.dpF
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            54.dp
        ).also { it.topMargin = Space.M.dp }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        addView(TextView(this@authGuestButton).apply {
            text = "⚡  Continue as Guest"
            textSize = 15f
            setTextColor(colorPrimary)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        })
    }

