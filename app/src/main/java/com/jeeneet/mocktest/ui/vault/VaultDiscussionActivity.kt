package com.jeeneet.mocktest.ui.vault

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.jeeneet.mocktest.data.model.VaultComment
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.ui.style.Space
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class VaultDiscussionActivity : AppCompatActivity() {

    private lateinit var vaultDate: String
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private lateinit var commentsList: RecyclerView
    private lateinit var commentAdapter: CommentAdapter
    private lateinit var etComment: EditText
    private lateinit var btnSend: View
    private lateinit var pollContainer: LinearLayout
    private lateinit var bannerContainer: FrameLayout

    private var myVote: Int? = null
    private val voteCounts = mutableMapOf<Int, Int>()

    companion object {
        private const val EXTRA_DATE = "extra_date"
        fun start(context: Context, date: String) {
            context.startActivity(Intent(context, VaultDiscussionActivity::class.java).apply {
                putExtra(EXTRA_DATE, date)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vaultDate = intent.getStringExtra(EXTRA_DATE) ?: "2026-05-16"
        setContentView(buildLayout())

        checkIfArchived()
        listenToVotes()
        loadComments()
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

    private fun checkIfArchived() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        try {
            val date = sdf.parse(vaultDate) ?: return
            val diff = System.currentTimeMillis() - date.time
            val fortyEightHours = 48 * 3600 * 1000L
            if (diff > fortyEightHours) {
                etComment.isEnabled = false
                etComment.hint = "Discussion archived (Read-only)"
                btnSend.visibility = View.GONE
            }
        } catch (e: Exception) {}
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
        }

        // --- Toolbar ---
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            setBackgroundColor(bgSecondary)
        }
        toolbar.addView(uiTextView(UiText.H2, "Daily Discussion", textPrimary).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        toolbar.addView(uiTextView(UiText.CAPTION, vaultDate, textTertiary))
        root.addView(toolbar)

        // --- Toughest Question Poll ---
        val pollCard = uiCard(radius = Corner.M, background = bgSecondary).apply {
            layoutParams = lpRow(topDp = Space.M, leftDp = Space.L, rightDp = Space.L)
        }
        val pollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }
        pollInner.addView(uiTextView(UiText.OVERLINE, "WHICH WAS THE TOUGHEST QUESTION?", textTertiary))
        
        val scrollPoll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        pollContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scrollPoll.addView(pollContainer)
        pollInner.addView(scrollPoll)
        pollCard.addView(pollInner)
        root.addView(pollCard)

        // --- Comments List ---
        commentsList = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            layoutManager = LinearLayoutManager(this@VaultDiscussionActivity)
            setPadding(0, Space.M.dp, 0, Space.M.dp)
            clipToPadding = false
        }
        commentAdapter = CommentAdapter { comment -> showCommentOptions(comment) }
        commentsList.adapter = commentAdapter
        root.addView(commentsList)

        // --- Bottom Bar ---
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            setBackgroundColor(bgSecondary)
            elevation = 10f
        }
        etComment = EditText(this).apply {
            hint = "Discuss with fellow aspirants..."
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            textSize = 14f
        }
        btnSend = uiTextView(UiText.BUTTON, "POST", colorPrimary).apply {
            setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
            setOnClickListener { postComment() }
        }
        bottomBar.addView(etComment)
        bottomBar.addView(btnSend)
        root.addView(bottomBar)

        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(bannerContainer)
        com.jeeneet.mocktest.admob.AdManager.showBanner(this, bannerContainer)

        return root
    }

    private fun listenToVotes() {
        val uid = auth.currentUser?.uid ?: ""
        db.collection("daily_vaults").document(vaultDate)
            .collection("question_votes")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                voteCounts.clear()
                myVote = null
                
                for (doc in snapshot.documents) {
                    val qIndex = doc.getLong("qIndex")?.toInt() ?: continue
                    voteCounts[qIndex] = (voteCounts[qIndex] ?: 0) + 1
                    if (doc.id == uid) {
                        myVote = qIndex
                    }
                }
                setupPoll()
            }
    }

    private fun setupPoll() {
        pollContainer.removeAllViews()
        for (i in 1..30) {
            val count = voteCounts[i] ?: 0
            val isSelected = (myVote == i)
            
            val btn = MaterialCardView(this).apply {
                radius = 12.dp.toFloat()
                strokeWidth = if (isSelected) 2 else 1
                strokeColor = if (isSelected) colorPrimary else dividerColor
                setCardBackgroundColor(if (isSelected) Color.parseColor("#2E1065") else bgTertiary)
                
                val params = LinearLayout.LayoutParams(56.dp, 56.dp)
                params.setMargins(0, 8.dp, 12.dp, 8.dp)
                layoutParams = params
                
                val inner = LinearLayout(this@VaultDiscussionActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                
                inner.addView(TextView(this@VaultDiscussionActivity).apply {
                    text = "Q$i"
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) colorPrimary else textPrimary)
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                
                inner.addView(TextView(this@VaultDiscussionActivity).apply {
                    text = if (count > 0) "🔥 $count" else "0"
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) colorPrimary else textTertiary)
                    textSize = 10f
                })
                
                addView(inner)
                setOnClickListener { voteForToughest(i) }
            }
            pollContainer.addView(btn)
        }
    }

    private fun voteForToughest(qNum: Int) {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val voteDoc = db.collection("daily_vaults").document(vaultDate)
                    .collection("question_votes").document(uid)
                
                voteDoc.set(mapOf("qIndex" to qNum, "timestamp" to System.currentTimeMillis())).await()
                Toast.makeText(this@VaultDiscussionActivity, "Voted for Q$qNum! 🎯", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@VaultDiscussionActivity, "Failed to vote: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadComments() {
        db.collection("daily_vaults").document(vaultDate)
            .collection("discussions")
            .orderBy("likes", Query.Direction.DESCENDING) // Sort by likes for Top Insight
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val list = snapshot.documents.mapNotNull { it.toObject(VaultComment::class.java)?.copy(id = it.id) }
                commentAdapter.submitList(list)
            }
    }

    private fun postComment() {
        if (!PrefManager.canPostComment(this)) {
            Toast.makeText(this, "Slow down! Wait 15s between comments.", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = etComment.text.toString().trim()
        if (msg.isEmpty()) return
        if (msg.length > 300) {
            Toast.makeText(this, "Comment too long (max 300 chars)", Toast.LENGTH_SHORT).show()
            return
        }

        val user = auth.currentUser ?: return
        val anonymousName = "Aspirant_" + user.uid.takeLast(4).uppercase()

        val comment = VaultComment(
            userId = user.uid,
            username = anonymousName,
            message = msg,
            timestamp = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                db.collection("daily_vaults").document(vaultDate)
                    .collection("discussions")
                    .add(comment).await()
                PrefManager.updateLastCommentTimestamp(this@VaultDiscussionActivity)
                etComment.setText("")
                commentsList.scrollToPosition(0)
            } catch (e: Exception) {
                Toast.makeText(this@VaultDiscussionActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCommentOptions(comment: VaultComment) {
        val items = mutableListOf("Report Spam")
        if (comment.userId == auth.currentUser?.uid) {
            items.add("Delete")
        }

        MaterialAlertDialogBuilder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Delete" -> deleteComment(comment)
                    "Report Spam" -> reportComment(comment)
                }
            }
            .show()
    }

    private fun deleteComment(comment: VaultComment) {
        db.collection("daily_vaults").document(vaultDate)
            .collection("discussions").document(comment.id)
            .delete()
    }

    private fun reportComment(comment: VaultComment) {
        db.collection("daily_vaults").document(vaultDate)
            .collection("discussions").document(comment.id)
            .update("isReported", true)
        Toast.makeText(this, "Comment reported for moderation.", Toast.LENGTH_SHORT).show()
    }
}

// --- Adapter ---

class CommentAdapter(private val onLongClick: (VaultComment) -> Unit) :
    ListAdapter<VaultComment, CommentAdapter.CommentVH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VaultComment>() {
            override fun areItemsTheSame(a: VaultComment, b: VaultComment) = a.id == b.id
            override fun areContentsTheSame(a: VaultComment, b: VaultComment) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentVH {
        val ctx = parent.context
        val card = MaterialCardView(ctx).apply {
            radius = 12.dp.toFloat(); cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply {
                setMargins(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.M.dp, Space.S.dp, Space.M.dp, Space.S.dp)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvUsername = ctx.uiTextView(UiText.OVERLINE, "", ctx.colorPrimary)
        val tvTime = ctx.uiTextView(UiText.CAPTION, "", ctx.textTertiary).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = 8.dp }
        }
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            visibility = View.GONE
        }
        val topInsightBadge = ctx.uiTextView(UiText.OVERLINE, "💡 TOP INSIGHT", ctx.goldPrimary).apply {
            background = roundedFill(Color.argb(40, 245, 158, 11), 4f)
            setPadding(6.dp, 2.dp, 6.dp, 2.dp)
            visibility = View.GONE
        }
        header.addView(tvUsername)
        header.addView(tvTime)
        header.addView(spacer)
        header.addView(topInsightBadge)

        val tvMessage = ctx.uiTextView(UiText.BODY, "", ctx.textPrimary).apply {
            setPadding(0, 4.dp, 0, 0)
        }

        inner.addView(header)
        inner.addView(tvMessage)
        card.addView(inner)

        return CommentVH(card, tvUsername, tvTime, spacer, topInsightBadge, tvMessage)
    }

    override fun onBindViewHolder(holder: CommentVH, position: Int) {
        val item = getItem(position)
        val timeStr = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(item.timestamp))

        holder.tvUsername.text = item.username
        holder.tvTime.text = " • $timeStr"

        val isTopInsight = position == 0 && item.likes >= 5
        val badgeVis = if (isTopInsight) View.VISIBLE else View.GONE
        holder.spacer.visibility = badgeVis
        holder.topInsightBadge.visibility = badgeVis
        holder.tvMessage.text = item.message
        holder.tvMessage.typeface = if (isTopInsight)
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else
            Typeface.DEFAULT

        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    class CommentVH(
        v: View,
        val tvUsername: TextView,
        val tvTime: TextView,
        val spacer: View,
        val topInsightBadge: TextView,
        val tvMessage: TextView
    ) : RecyclerView.ViewHolder(v)
}
