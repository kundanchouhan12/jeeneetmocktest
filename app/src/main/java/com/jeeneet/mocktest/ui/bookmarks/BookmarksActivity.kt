package com.jeeneet.mocktest.ui.bookmarks

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.jeeneet.mocktest.data.model.Power100Question
import com.jeeneet.mocktest.data.model.Question
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.data.repository.MockTestRepository
import com.jeeneet.mocktest.ui.power100.Power100Activity
import com.jeeneet.mocktest.ui.style.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class BookmarkItem {
    data class Regular(val question: Question) : BookmarkItem()
    data class Power100Item(val question: Power100Question) : BookmarkItem()
}

class BookmarksActivity : AppCompatActivity() {

    private lateinit var repo: MockTestRepository
    private lateinit var contentContainer: FrameLayout
    private lateinit var bannerContainer: FrameLayout
    private var lastRegularBookmarks: List<Question> = emptyList()

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, BookmarksActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = MockTestRepository(this)
        setContentView(buildLayout())
        observeBookmarks()
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
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        root.addView(uiHeader("⭐ My Bookmarks", onBack = { finish() }))
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(contentContainer)
        showLoading()

        bannerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(bannerContainer)
        com.jeeneet.mocktest.admob.AdManager.showBanner(this, bannerContainer)

        return root
    }

    private fun showLoading() {
        contentContainer.removeAllViews()
        contentContainer.addView(ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.CENTER)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(colorPrimary)
        })
    }

    private fun observeBookmarks() {
        lifecycleScope.launch {
            repo.getBookmarkedQuestions().collectLatest { questions ->
                lastRegularBookmarks = questions
                renderCombined()
            }
        }
    }

    private suspend fun fetchPower100Bookmarks(): List<Power100Question> = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        MockTestDatabase.getInstance(this@BookmarksActivity).power100Dao().getBookmarkedQuestions(uid)
    }

    private suspend fun renderCombined() {
        val power100Items = fetchPower100Bookmarks()
        showBookmarks(
            lastRegularBookmarks.map { BookmarkItem.Regular(it) } +
                power100Items.map { BookmarkItem.Power100Item(it) }
        )
    }

    // Power100 bookmark changes don't come through the regular-bookmarks Flow (different
    // table) — re-render explicitly using the last known regular list instead of resubscribing.
    private fun refreshPower100Only() {
        lifecycleScope.launch { renderCombined() }
    }

    private fun showBookmarks(items: List<BookmarkItem>) {
        contentContainer.removeAllViews()

        if (items.isEmpty()) {
            contentContainer.addView(uiEmptyView(
                "⭐", "No bookmarks yet",
                "Tap ☆ on any question during a test to save it here for later review."
            ))
            return
        }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BookmarksActivity)
            setPadding(0, Space.S.dp, 0, Space.XXL.dp)
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        rv.adapter = BookmarkAdapter(
            items,
            onRemoveRegular = { q -> confirmRemove(q) },
            onRemovePower100 = { q -> confirmRemovePower100(q) },
            onOpenPower100 = { q -> Power100Activity.startAtPosition(this, q.examType, q.position) }
        )
        contentContainer.addView(rv)
    }

    private fun confirmRemove(question: Question) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Bookmark?")
            .setMessage("\"${question.questionText.take(80)}...\"")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch { repo.toggleBookmark(question) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemovePower100(question: Power100Question) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Bookmark?")
            .setMessage("\"${question.questionText.take(80)}...\"")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
                    withContext(Dispatchers.IO) {
                        MockTestDatabase.getInstance(this@BookmarksActivity).power100Dao()
                            .removeBookmark(uid, question.examType, question.position)
                    }
                    refreshPower100Only()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

private class BookmarkAdapter(
    private val items: List<BookmarkItem>,
    private val onRemoveRegular: (Question) -> Unit,
    private val onRemovePower100: (Power100Question) -> Unit,
    private val onOpenPower100: (Power100Question) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    private val answerColor = Color.parseColor("#10B981")
    private val power100Color = Color.parseColor("#F59E0B")

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val card = MaterialCardView(ctx).apply {
            radius = Corner.L.dpF
            cardElevation = 0f
            strokeWidth = 1.dp
            strokeColor = ctx.dividerColor
            setCardBackgroundColor(ctx.bgSecondary)
            layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply {
                setMargins(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.M.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }

        val tagRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.dp }
        }
        val tvBadge = TextView(ctx).apply {
            text = "POWER 100"; textSize = 9f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(power100Color, 4f)
            setPadding(6.dp, 2.dp, 6.dp, 2.dp)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp }
        }
        val tvSubject = ctx.uiTextView(UiText.OVERLINE, "", ctx.colorPrimary).apply {
            background = roundedFill(Color.argb(30, 139, 92, 246), 4f)
            setPadding(6.dp, 2.dp, 6.dp, 2.dp)
        }
        val tvChapter = ctx.uiTextView(UiText.CAPTION, "", ctx.textMuted)
        val spacer = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        val btnRemove = TextView(ctx).apply {
            text = "✕"; textSize = 14f; setTextColor(ctx.textMuted)
            setPadding(8.dp, 4.dp, 4.dp, 4.dp)
        }
        tagRow.addView(tvBadge)
        tagRow.addView(tvSubject)
        tagRow.addView(tvChapter)
        tagRow.addView(spacer)
        tagRow.addView(btnRemove)
        inner.addView(tagRow)

        val tvQuestion = ctx.uiTextView(UiText.BODY, "", ctx.textPrimary).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        inner.addView(tvQuestion)

        val tvAnswer = ctx.uiTextView(UiText.CAPTION, "", answerColor).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 6.dp, 0, 0)
            visibility = View.GONE
        }
        inner.addView(tvAnswer)

        card.addView(inner)
        return VH(card, tvBadge, tvSubject, tvChapter, btnRemove, tvQuestion, tvAnswer)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val item = items[position]) {
            is BookmarkItem.Regular -> {
                val q = item.question
                holder.tvBadge.visibility = View.GONE
                holder.tvSubject.text = q.subject
                holder.tvChapter.text = "  ·  ${q.chapter}"
                holder.root.setOnClickListener(null)
                holder.btnRemove.setOnClickListener { onRemoveRegular(q) }
                com.jeeneet.mocktest.utils.MathRenderer.render(holder.tvQuestion, q.questionText)

                if (q.options.isNotEmpty()) {
                    val correctLabel = listOf("A", "B", "C", "D").getOrNull(q.correctOptionIndex) ?: ""
                    val correctText = q.options.getOrNull(q.correctOptionIndex) ?: ""
                    com.jeeneet.mocktest.utils.MathRenderer.render(holder.tvAnswer, "Answer: ($correctLabel) $correctText")
                    holder.tvAnswer.visibility = View.VISIBLE
                } else {
                    holder.tvAnswer.visibility = View.GONE
                }
            }
            is BookmarkItem.Power100Item -> {
                val q = item.question
                holder.tvBadge.visibility = View.VISIBLE
                holder.tvSubject.text = q.subject
                holder.tvChapter.text = "  ·  ${q.chapter}"
                holder.root.setOnClickListener { onOpenPower100(q) }
                holder.btnRemove.setOnClickListener { onRemovePower100(q) }
                com.jeeneet.mocktest.utils.MathRenderer.render(holder.tvQuestion, q.questionText)

                val correctLabel = listOf("A", "B", "C", "D").getOrNull(q.correctOptionIndex) ?: ""
                val correctText = q.options.getOrNull(q.correctOptionIndex) ?: ""
                com.jeeneet.mocktest.utils.MathRenderer.render(holder.tvAnswer, "Answer: ($correctLabel) $correctText")
                holder.tvAnswer.visibility = View.VISIBLE
            }
        }
    }

    class VH(
        val root: View,
        val tvBadge: TextView,
        val tvSubject: TextView,
        val tvChapter: TextView,
        val btnRemove: TextView,
        val tvQuestion: TextView,
        val tvAnswer: TextView
    ) : RecyclerView.ViewHolder(root)
}
