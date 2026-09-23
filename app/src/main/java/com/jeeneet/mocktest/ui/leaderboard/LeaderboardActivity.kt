package com.jeeneet.mocktest.ui.leaderboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.jeeneet.mocktest.ui.auth.LoginActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class LeaderboardActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, LeaderboardActivity::class.java))
    }

    private data class Entry(
        val uid: String,
        val displayName: String,
        val initial: String,
        val totalScore: Long,
        val testsCompleted: Long,
        val streak: Int,
        val rank: Int,
        val isMe: Boolean
    )

    private lateinit var contentContainer: LinearLayout
    private lateinit var bannerContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        fetchLeaderboard()
    }

    override fun onPause() {
        super.onPause()
        com.jeeneet.mocktest.admob.AdManager.pauseBanner(bannerContainer)
    }

    override fun onResume() {
        super.onResume()
        com.jeeneet.mocktest.admob.AdManager.resumeBanner(bannerContainer)
    }

    override fun onDestroy() {
        com.jeeneet.mocktest.admob.AdManager.destroyBanner(bannerContainer)
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgPrimary)
        }
        root.addView(uiHeader("🏆 Weekly Leaderboard", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.XXL.dp)
        }
        showLoading()
        scroll.addView(contentContainer)
        root.addView(scroll)

        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(bannerContainer)
        com.jeeneet.mocktest.admob.AdManager.showBanner(this, bannerContainer)

        return root
    }

    private fun showLoading() {
        contentContainer.removeAllViews()
        contentContainer.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300.dp)
            addView(ProgressBar(this@LeaderboardActivity).apply {
                layoutParams = FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.CENTER)
                indeterminateTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
            })
        })
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(net)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION") cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun fetchLeaderboard() {
        if (!isOnline()) { displayError(); return }
        showLoading()
        val weekKey = getWeekKey()
        val isGuest = PrefManager.isGuestMode(this) || FirebaseAuth.getInstance().currentUser == null
        val currentUid = if (isGuest) "" else (FirebaseAuth.getInstance().currentUser?.uid ?: "")
        val cal = Calendar.getInstance()
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        val weekStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }
        val weekEnd = Calendar.getInstance().apply {
            time = weekStart.time; add(Calendar.DAY_OF_YEAR, 6)
        }
        val weekLabel = "${sdf.format(weekStart.time)} – ${sdf.format(weekEnd.time)}, ${cal.get(Calendar.YEAR)}"

        FirebaseFirestore.getInstance()
            .collection("leaderboard").document(weekKey)
            .collection("scores")
            .orderBy("totalScore", Query.Direction.DESCENDING)
            .limit(100)
            .get()
            .addOnSuccessListener { snapshot ->
                val MIN_QUALIFYING_SCORE = 100L
                val filteredDocs = snapshot.documents.filter { (it.getLong("totalScore") ?: 0L) >= MIN_QUALIFYING_SCORE }
                val entries = filteredDocs.mapIndexed { idx, doc ->
                    Entry(
                        uid            = doc.id,
                        displayName    = doc.getString("displayName") ?: "Aspirant",
                        initial        = doc.getString("initial") ?: "A",
                        totalScore     = doc.getLong("totalScore") ?: 0L,
                        testsCompleted = doc.getLong("testsCompleted") ?: 0L,
                        streak         = (doc.getLong("streak") ?: 0L).toInt(),
                        rank           = idx + 1,
                        isMe           = !isGuest && doc.id == currentUid
                    )
                }
                val myDoc = if (isGuest || currentUid.isEmpty()) null else snapshot.documents.firstOrNull { it.id == currentUid }
                val myScore = myDoc?.getLong("totalScore") ?: 0L
                displayLeaderboard(weekLabel, weekKey, entries, currentUid, myScore, isGuest)
            }
            .addOnFailureListener { e ->
                android.util.Log.e("Leaderboard", "Fetch failed", e)
                displayError()
            }
    }

    private fun displayLeaderboard(
        weekLabel: String,
        weekKey: String,
        entries: List<Entry>,
        currentUid: String,
        myScore: Long = 0L,
        isGuest: Boolean = false
    ) {
        contentContainer.removeAllViews()

        // Guest Mode Top Banner
        if (isGuest) {
            val guestBanner = uiCard(
                radius = Corner.L,
                elevation = Elev.S,
                background = bgSecondary,
                strokeDp = 1,
                strokeColor = colorPrimary
            ).apply {
                layoutParams = lpRow(bottomDp = Space.M)
            }
            val guestInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            }
            guestInner.addView(uiTextView(UiText.H3, "🔒 Guest Mode (Read-Only)", colorPrimary))
            guestInner.addView(uiTextView(
                UiText.LABEL,
                "You are viewing live rankings. Please login to enter the weekly leaderboard, record your score, and compete with peers!",
                textSecondary
            ).apply {
                setPadding(0, Space.XS.dp, 0, Space.M.dp)
            })
            guestInner.addView(uiPrimaryButton("⚡  Login / Sign Up to Join", heightDp = 44) {
                startActivity(Intent(this@LeaderboardActivity, LoginActivity::class.java))
            }.apply {
                textSize = 14f
                stateListAnimator = null
            })
            guestBanner.addView(guestInner)
            contentContainer.addView(guestBanner)
        }

        // Week info card
        val infoCard = uiCard(radius = Corner.L, elevation = Elev.NONE, strokeDp = 0).apply {
            layoutParams = lpRow(bottomDp = Space.L)
        }
        val infoInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        }
        infoInner.addView(uiTextView(UiText.OVERLINE, "THIS WEEK", textTertiary, Gravity.CENTER))
        infoInner.addView(uiTextView(UiText.H3, weekLabel, textPrimary, Gravity.CENTER).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })
        val resetInfo = buildResetLabel()
        infoInner.addView(uiTextView(UiText.CAPTION, resetInfo, textMuted, Gravity.CENTER).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })
        infoInner.addView(uiTextView(UiText.CAPTION, "🎯 Min. 100 pts required to qualify", colorPrimary, Gravity.CENTER).apply {
            setPadding(0, Space.XS.dp, 0, 0)
        })
        infoCard.addView(infoInner)
        contentContainer.addView(infoCard)

        if (entries.isEmpty()) {
            val emptyMsg = if (isGuest) {
                "No aspirants have qualified yet this week. Take tests to claim the top spot!"
            } else if (myScore > 0L) {
                "Your current score is $myScore pts. Reach 100 pts to qualify for the leaderboard!"
            } else {
                "Score at least 100 points this week to claim the top spot on the leaderboard!"
            }
            contentContainer.addView(uiEmptyView(
                "🏆", "No qualified rankings yet",
                emptyMsg
            ))
            return
        }

        // Top 25 ranked list
        val top25 = entries.take(25)
        if (top25.isNotEmpty()) {
            contentContainer.addView(uiSectionLabel("🏅 Top 25 Qualified Aspirants"))
            top25.forEach { contentContainer.addView(buildRankRow(it)) }
        }

        // User's own card if not in top 25
        val meEntry = if (isGuest) null else entries.firstOrNull { it.isMe }
        val meRank = meEntry?.rank ?: -1
        if (meEntry == null || meRank > 25) {
            contentContainer.addView(uiSectionLabel("Your Position"))
            if (meEntry != null) {
                contentContainer.addView(buildRankRow(meEntry, isHighlighted = true))
            } else if (isGuest) {
                contentContainer.addView(uiCard(
                    radius = Corner.L, elevation = Elev.NONE,
                    strokeDp = 0
                ).apply {
                    layoutParams = lpRow(bottomDp = Space.S)
                    val cardCol = LinearLayout(this@LeaderboardActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
                    }
                    cardCol.addView(uiTextView(UiText.BODY, "Login to record your tests and see your rank.", textSecondary, Gravity.CENTER))
                    cardCol.addView(uiPrimaryButton("Login / Sign Up", heightDp = 40) {
                        startActivity(Intent(this@LeaderboardActivity, LoginActivity::class.java))
                    }.apply {
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 40.dp
                        ).also { it.topMargin = Space.S.dp }
                    })
                    addView(cardCol)
                })
            } else {
                val statusText = if (myScore > 0L) {
                    "Your current score: $myScore pts. Need ${100 - myScore} more pts to qualify!"
                } else {
                    "Score at least 100 points this week to appear on the leaderboard!"
                }
                contentContainer.addView(uiCard(
                    radius = Corner.L, elevation = Elev.NONE,
                    strokeDp = 0
                ).apply {
                    layoutParams = lpRow(bottomDp = Space.S)
                    addView(uiTextView(UiText.BODY,
                        statusText,
                        textSecondary, Gravity.CENTER).apply {
                        setPadding(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
                    })
                })
            }
        }
    }

    private fun buildPodium(top3: List<Entry>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = lpRow(bottomDp = Space.L)
        }

        // Order: 2nd | 1st | 3rd for the classic podium look
        val ordered = when (top3.size) {
            1 -> listOf(null, top3[0], null)
            2 -> listOf(top3[1], top3[0], null)
            else -> listOf(top3[1], top3[0], top3[2])
        }
        val medals = listOf("🥈", "🥇", "🥉")
        val heights = listOf(100, 130, 80)  // dp heights for the podium base
        val bgColors = listOf(
            Color.parseColor("#C0C0C0"),   // silver
            Color.parseColor("#F59E0B"),   // gold
            Color.parseColor("#CD7F32")    // bronze
        )

        ordered.forEachIndexed { i, entry ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(Space.XS.dp, 0, Space.XS.dp, 0)
            }
            if (entry == null) {
                row.addView(col)
                return@forEachIndexed
            }

            // Avatar circle
            col.addView(TextView(this).apply {
                text = entry.initial
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgColors[i])
                }
                layoutParams = LinearLayout.LayoutParams(52.dp, 52.dp).also {
                    it.bottomMargin = Space.XS.dp
                }
            })

            col.addView(uiTextView(UiText.CAPTION, medals[i], textPrimary, Gravity.CENTER))
            col.addView(uiTextView(UiText.OVERLINE, "${entry.totalScore} pts", textSecondary, Gravity.CENTER).apply {
                setPadding(0, 2.dp, 0, 4.dp)
            })
            col.addView(uiTextView(UiText.CAPTION,
                entry.displayName,
                textPrimary, Gravity.CENTER).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 2
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            })

            // Podium base
            col.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(bgColors[i])
                    cornerRadii = floatArrayOf(
                        Corner.S.dpF, Corner.S.dpF, Corner.S.dpF, Corner.S.dpF, 0f, 0f, 0f, 0f
                    )
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heights[i].dp).also {
                    it.topMargin = Space.S.dp
                }
            })
            row.addView(col)
        }
        return row
    }

    private fun buildRankRow(entry: Entry, isHighlighted: Boolean = false): View {
        val strokeColor = if (entry.isMe || isHighlighted) colorPrimary else dividerColor
        val strokeDp = if (entry.isMe || isHighlighted) 2 else 1
        val card = uiCard(
            radius = Corner.L,
            elevation = if (entry.isMe || isHighlighted) Elev.S else Elev.NONE,
            strokeDp = strokeDp,
            strokeColor = strokeColor
        ).apply {
            layoutParams = lpRow(bottomDp = Space.S)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        }

        // Rank number
        inner.addView(uiTextView(UiText.BODY, "#${entry.rank}", textMuted).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(36.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Avatar
        inner.addView(TextView(this).apply {
            text = entry.initial
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorPrimary)
            }
            layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp).also {
                it.marginEnd = Space.M.dp
            }
        })

        // Name + stats
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameLabel = if (entry.isMe) "${entry.displayName} (You)" else entry.displayName
        nameCol.addView(uiTextView(UiText.BODY, nameLabel,
            if (entry.isMe) colorPrimary else textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        nameCol.addView(uiTextView(UiText.CAPTION,
            "${entry.testsCompleted} tests  ·  🔥 ${entry.streak} streak", textMuted).apply {
            setPadding(0, 2.dp, 0, 0)
        })
        inner.addView(nameCol)

        // Score
        inner.addView(uiTextView(UiText.H3, "${entry.totalScore}", colorPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        card.addView(inner)
        return card
    }

    private fun displayError() {
        contentContainer.removeAllViews()
        contentContainer.addView(uiEmptyView(
            "📶", "Couldn't load rankings",
            "Check your internet connection and try again."
        ))
        contentContainer.addView(
            uiPrimaryButton("↻  Retry", heightDp = 48) { fetchLeaderboard() }.apply {
                layoutParams = lpRow(topDp = Space.L, leftDp = Space.XXXL, rightDp = Space.XXXL)
            }
        )
    }

    private fun buildResetLabel(): String {
        // Calculate exact ms until next Monday 00:00:00
        val now = Calendar.getInstance()
        val nextMonday = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If today IS Monday and it's past midnight, advance to next Monday
            if (before(now) || compareTo(now) == 0) add(Calendar.WEEK_OF_YEAR, 1)
        }
        val diffMs   = nextMonday.timeInMillis - now.timeInMillis
        val diffMins = (diffMs / 60_000).toInt()
        val days  = diffMins / (60 * 24)
        val hours = (diffMins % (60 * 24)) / 60
        val mins  = diffMins % 60

        return when {
            days > 0  -> "Resets in ${days}d ${hours}h — scores reset every Monday"
            hours > 0 -> "⚡ Resets in ${hours}h ${mins}m — push for a higher rank!"
            else      -> "⚡ Resets in under an hour — final push time!"
        }
    }

    private fun getWeekKey(): String {
        // Locale.UK gives ISO 8601 weeks (Monday-start) consistently across all devices.
        val sdf = java.text.SimpleDateFormat("YYYY-'W'ww", Locale.UK)
        return sdf.format(java.util.Date())
    }
}
