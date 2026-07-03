package com.jeeneet.mocktest.ui.achievements

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jeeneet.mocktest.data.repository.AchievementManager
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.style.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AchievementsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
        }

        // Header
        root.addView(uiHeader("Badge Gallery", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = lpFill()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.HUGE.dp)
        }
        scroll.addView(content)
        root.addView(scroll)

        setContentView(root)

        loadAchievements(content)
    }

    private fun loadAchievements(container: LinearLayout) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        lifecycleScope.launch {
            val unlockedIds = withContext(Dispatchers.IO) {
                MockTestDatabase.getInstance(this@AchievementsActivity).achievementDao().getAll(uid).map { it.id }.toSet()
            }

            val grid = GridLayout(this@AchievementsActivity).apply {
                columnCount = 2
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            AchievementManager.ALL_BADGES.forEach { badge ->
                val isUnlocked = unlockedIds.contains(badge.id)
                grid.addView(buildBadgeCard(badge, isUnlocked))
            }

            container.addView(grid)
        }
    }

    private fun buildBadgeCard(badge: AchievementManager.Badge, isUnlocked: Boolean): android.view.View {
        val card = uiCard(
            radius = Corner.L,
            elevation = Elev.S,
            background = bgSecondary,
            strokeDp = if (isUnlocked) 2 else 0,
            strokeColor = goldPrimary
        ).apply {
            val params = GridLayout.LayoutParams().apply {
                width       = 0
                columnSpec  = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec     = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(Space.S.dp, Space.S.dp, Space.S.dp, Space.S.dp)
            }
            layoutParams = params
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp)
        }

        // Emoji circle
        val iconBg = if (isUnlocked)
            Color.argb(50, Color.red(goldPrimary), Color.green(goldPrimary), Color.blue(goldPrimary))
        else
            Color.argb(60, 100, 116, 139)

        val emojiFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp).also {
                it.gravity     = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = Space.M.dp
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(iconBg)
            }
        }
        emojiFrame.addView(TextView(this).apply {
            text    = if (isUnlocked) badge.emoji else "🔒"
            textSize = 26f
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        })
        inner.addView(emojiFrame)

        // Title
        inner.addView(uiTextView(UiText.H3, badge.title,
            if (isUnlocked) goldPrimary else textTertiary, Gravity.CENTER).apply {
            setPadding(0, 0, 0, 4.dp)
            textSize = 13f
        })

        // Description
        inner.addView(uiTextView(UiText.CAPTION, badge.description, textMuted, Gravity.CENTER).apply {
            textSize = 11f
        })

        // Status chip
        val chipText  = if (isUnlocked) "✓ Earned" else "Locked"
        val chipFg    = if (isUnlocked) goldDark else textMuted
        val chipBg    = if (isUnlocked)
            Color.argb(30, Color.red(goldPrimary), Color.green(goldPrimary), Color.blue(goldPrimary))
        else bgSecondary

        inner.addView(TextView(this).apply {
            text      = chipText
            textSize  = 10f
            setTextColor(chipFg)
            background = roundedFill(chipBg, Corner.PILL)
            setPadding(Space.S.dp, 3.dp, Space.S.dp, 3.dp)
            gravity   = Gravity.CENTER
            typeface  = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                it.gravity   = Gravity.CENTER_HORIZONTAL
                it.topMargin = Space.S.dp
            }
        })

        card.addView(inner)
        return card
    }
    
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AchievementsActivity::class.java))
        }
    }
}
