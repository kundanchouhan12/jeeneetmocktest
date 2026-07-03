package com.jeeneet.mocktest.ui.notes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jeeneet.mocktest.data.model.Note
import com.jeeneet.mocktest.data.repository.MockTestDatabase
import com.jeeneet.mocktest.ui.style.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NotesActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, NotesActivity::class.java))
    }

    private val subjects = listOf("All", "Physics", "Chemistry", "Maths", "Biology", "General")
    private val subjectColors = mapOf(
        "All"       to Color.parseColor("#6366F1"),
        "Physics"   to Color.parseColor("#EC4899"),
        "Chemistry" to Color.parseColor("#10B981"),
        "Maths"     to Color.parseColor("#3B82F6"),
        "Biology"   to Color.parseColor("#22C55E"),
        "General"   to Color.parseColor("#F59E0B")
    )

    private var selectedSubject = "All"
    private var searchQuery = ""
    private lateinit var notesContainer: LinearLayout
    private lateinit var emptyView: TextView
    private val db by lazy { MockTestDatabase.getInstance(this) }
    private val uid by lazy { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    private var notesJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        observeNotes()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Gradient header with trailing "New" button
        val btnAdd = TextView(this).apply {
            text = "+  New"; textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(goldPrimary, Corner.XL)
            setPadding(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            setOnClickListener { showNoteDialog(null) }
        }
        root.addView(uiHeader("📓  My Notes", onBack = { finish() }, trailing = btnAdd))

        // Search bar
        root.addView(EditText(this).apply {
            hint = "🔍  Search notes..."; textSize = 14f
            setTextColor(textPrimary); setHintTextColor(textTertiary)
            background = GradientDrawable().apply {
                setColor(bgSecondary); cornerRadius = Corner.M.dpF
                setStroke(1.dp, dividerColor)
            }
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = lpRow(topDp = Space.L, leftDp = Space.L, rightDp = Space.L)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""; observeNotes()
                }
            })
        })

        // Subject filter chips
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lpRow(topDp = Space.M, leftDp = Space.M, rightDp = Space.M)
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Space.XS.dp, 0, Space.XS.dp, 0)
        }
        subjects.forEach { chipRow.addView(buildChip(it)) }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // Notes list
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val listWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.M.dp, Space.L.dp, 80.dp)
        }

        emptyView = uiTextView(UiText.BODY,
            "No notes yet.\nTap '+ New' to create your first note.",
            textTertiary, Gravity.CENTER
        ).apply {
            setPadding(0, 60.dp, 0, 0)
            visibility = View.GONE
        }
        listWrapper.addView(emptyView)

        notesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listWrapper.addView(notesContainer)
        scroll.addView(listWrapper)
        root.addView(scroll)

        return root
    }

    private fun buildChip(subject: String): View {
        val color = subjectColors[subject] ?: goldPrimary
        return TextView(this).apply {
            text = subject; textSize = 12f
            val isSelected = subject == selectedSubject
            setTextColor(if (isSelected) Color.WHITE else color)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = Corner.XL.dpF
                if (isSelected) setColor(color)
                else { setColor(Color.TRANSPARENT); setStroke(1.dp, color) }
            }
            setPadding(14.dp, 7.dp, 14.dp, 7.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(Space.XS.dp, 0, Space.XS.dp, 0) }
            setOnClickListener {
                selectedSubject = subject
                val chipRow = (parent as LinearLayout)
                chipRow.removeAllViews()
                subjects.forEach { chipRow.addView(buildChip(it)) }
                observeNotes()
            }
        }
    }

    private fun observeNotes() {
        notesJob?.cancel()
        notesJob = lifecycleScope.launch {
            db.noteDao().searchNotes(uid, selectedSubject, searchQuery).collect { notes ->
                renderNotes(notes)
            }
        }
    }

    private fun renderNotes(notes: List<Note>) {
        notesContainer.removeAllViews()
        if (notes.isEmpty()) {
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
            notes.forEach { note -> notesContainer.addView(buildNoteCard(note)) }
        }
    }

    private fun buildNoteCard(note: Note): View {
        val color = subjectColors[note.subject] ?: goldPrimary
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgSecondary); cornerRadius = 14.dpF
                setStroke(1.dp, dividerColor)
            }
            setPadding(Space.L.dp, 14.dp, Space.L.dp, 14.dp)
            layoutParams = lpRow(bottomDp = Space.M)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = note.subject; textSize = 10f
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedFill(
                Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
                Corner.S
            )
            setPadding(Space.S.dp, 3.dp, Space.S.dp, 3.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = Space.S.dp }
        })
        topRow.addView(uiTextView(UiText.CAPTION, formatDate(note.updatedAt), textMuted).apply {
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        topRow.addView(TextView(this).apply {
            text = "✏️"; textSize = 16f
            setPadding(Space.S.dp, 0, Space.S.dp, 0)
            setOnClickListener { showNoteDialog(note) }
        })
        topRow.addView(TextView(this).apply {
            text = "🗑️"; textSize = 16f
            setPadding(Space.XS.dp, 0, 0, 0)
            setOnClickListener { confirmDelete(note) }
        })
        card.addView(topRow)

        card.addView(uiTextView(UiText.H3, note.title, textPrimary).apply {
            setPadding(0, Space.S.dp, 0, 6.dp)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        if (note.content.isNotBlank()) {
            card.addView(uiTextView(UiText.LABEL, note.content, textSecondary).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(4f.dpF, 1f)
            })
        }

        card.setOnClickListener { showNoteDialog(note) }
        return card
    }

    private fun showNoteDialog(existing: Note?) {
        val isEdit = existing != null
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XXL.dp, Space.XL.dp, Space.XXL.dp, Space.S.dp)
            setBackgroundColor(bgSecondary)
        }

        val spinnerBg = GradientDrawable().apply {
            setColor(bgTertiary); cornerRadius = 10.dpF; setStroke(1.dp, dividerColor)
        }
        val spinner = Spinner(this).apply {
            background = spinnerBg
            layoutParams = lpRow(bottomDp = 14).also { it.height = 48.dp }
        }
        val spinnerSubjects = subjects.drop(1)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerSubjects)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(spinnerSubjects.indexOf(existing?.subject ?: "General").coerceAtLeast(0))

        val etTitle = EditText(this).apply {
            hint = "Note title"; textSize = 15f
            setTextColor(textPrimary); setHintTextColor(textTertiary)
            background = GradientDrawable().apply {
                setColor(bgTertiary); cornerRadius = 10.dpF; setStroke(1.dp, dividerColor)
            }
            setPadding(14.dp, Space.M.dp, 14.dp, Space.M.dp)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.title ?: "")
            layoutParams = lpRow(bottomDp = 14)
        }

        val etContent = EditText(this).apply {
            hint = "Write your notes here..."; textSize = 14f
            setTextColor(textPrimary); setHintTextColor(textTertiary)
            background = GradientDrawable().apply {
                setColor(bgTertiary); cornerRadius = 10.dpF; setStroke(1.dp, dividerColor)
            }
            setPadding(14.dp, Space.M.dp, 14.dp, Space.M.dp)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false; minLines = 5; gravity = Gravity.TOP
            setText(existing?.content ?: "")
            layoutParams = lpRow()
        }

        fun label(t: String) = uiTextView(UiText.CAPTION, t, textMuted).apply {
            setPadding(0, 0, 0, 6.dp)
        }

        dialogView.addView(label("Subject")); dialogView.addView(spinner)
        dialogView.addView(label("Title")); dialogView.addView(etTitle)
        dialogView.addView(label("Content")); dialogView.addView(etContent)

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "Edit Note" else "New Note")
            .setView(dialogView)
            .setPositiveButton(if (isEdit) "Save" else "Create") { _, _ ->
                val title   = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()
                val subject = spinnerSubjects[spinner.selectedItemPosition]
                if (title.isEmpty()) {
                    Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (isEdit && existing != null) {
                        db.noteDao().updateNote(
                            existing.copy(title = title, content = content,
                                subject = subject, updatedAt = System.currentTimeMillis())
                        )
                    } else {
                        db.noteDao().insertNote(Note(userId = uid, title = title, content = content, subject = subject))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Delete \"${note.title}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { db.noteDao().deleteNote(note) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDate(ms: Long): String {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        return sdf.format(Date(ms))
    }
}
