package com.jeeneet.mocktest.ui.streak

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.PrefManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class StreakCalendarActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, StreakCalendarActivity::class.java))
    }

    private val todayCal = Calendar.getInstance()
    private val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(todayCal.time)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgPrimary)
        }
        root.addView(uiHeader("📅 Streak Calendar", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.L.dp, Space.L.dp, Space.XXL.dp)
        }

        inner.addView(buildStatsCard())
        inner.addView(buildLegend())

        // Show last 3 months: current + 2 previous
        for (monthsBack in 0..2) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -monthsBack)
            }
            inner.addView(buildMonthSection(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)))
        }

        scroll.addView(inner)
        root.addView(scroll)
        return root
    }

    // ─── Stats card ───────────────────────────────────────────────────────────

    private fun buildStatsCard(): View {
        val streak = PrefManager.getStreak(this)
        val totalDays = PrefManager.getTotalPracticedDays(this)

        val card = uiCard(radius = Corner.L, elevation = Elev.NONE, strokeDp = 0).apply {
            layoutParams = lpRow(bottomDp = Space.M)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
        }

        // Streak stat
        val streakCol = statCell("🔥 $streak", "Current streak")
        val divider = View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(1, 40.dp).also {
                it.marginStart = Space.M.dp; it.marginEnd = Space.M.dp
            }
        }
        val totalCol = statCell("📅 $totalDays", "Days practiced")

        inner.addView(streakCol)
        inner.addView(divider)
        inner.addView(totalCol)
        card.addView(inner)
        return card
    }

    private fun statCell(value: String, label: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(uiTextView(UiText.H2, value, textPrimary, Gravity.CENTER))
            addView(uiTextView(UiText.CAPTION, label, textMuted, Gravity.CENTER).apply {
                setPadding(0, Space.XS.dp, 0, 0)
            })
        }

    // ─── Legend ───────────────────────────────────────────────────────────────

    private fun buildLegend(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Space.S.dp)
        }
        row.addView(legendItem(Color.parseColor("#4ADE80"), "Practiced"))
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(Space.M.dp, 1) })
        row.addView(legendItem(Color.parseColor("#F97316"), "Today"))
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(Space.M.dp, 1) })
        row.addView(legendItem(Color.parseColor("#374151"), "Missed"))
        return row
    }

    private fun legendItem(color: Int, label: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@StreakCalendarActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).also {
                    it.marginEnd = 4.dp
                }
            })
            addView(uiTextView(UiText.CAPTION, label, textMuted))
        }

    // ─── Month section ────────────────────────────────────────────────────────

    private fun buildMonthSection(year: Int, month: Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Space.M.dp, 0, Space.M.dp)
        }

        val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            .format(Calendar.getInstance().apply { set(year, month, 1) }.time)
        container.addView(uiTextView(UiText.LABEL, monthName.uppercase(), colorPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, Space.S.dp)
        })

        // Day-of-week headers
        container.addView(buildDayHeaders())

        // Calendar grid
        val cal = Calendar.getInstance().apply {
            set(year, month, 1)
            firstDayOfWeek = Calendar.MONDAY
        }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Monday=0, Tuesday=1, ..., Sunday=6
        val firstDow = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var col = 0
        var currentRow = newWeekRow()

        // Leading empty cells
        repeat(firstDow) {
            currentRow.addView(buildDayCell("", CellType.EMPTY))
            col++
        }

        for (day in 1..daysInMonth) {
            if (col == 7) {
                container.addView(currentRow)
                currentRow = newWeekRow()
                col = 0
            }
            val dateCal = Calendar.getInstance().apply { set(year, month, day) }
            val dateStr = sdf.format(dateCal.time)
            val isPracticed = PrefManager.hasPracticedOn(this, dateStr)
            val isToday = dateStr == todayStr
            val isFuture = dateCal.timeInMillis > todayCal.apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            }.timeInMillis

            val type = when {
                isToday && isPracticed -> CellType.TODAY_DONE
                isToday               -> CellType.TODAY
                isFuture              -> CellType.FUTURE
                isPracticed           -> CellType.PRACTICED
                else                  -> CellType.MISSED
            }
            currentRow.addView(buildDayCell(day.toString(), type))
            col++
        }

        // Trailing empty cells
        while (col > 0 && col < 7) {
            currentRow.addView(buildDayCell("", CellType.EMPTY))
            col++
        }
        if (currentRow.childCount > 0) container.addView(currentRow)

        return container
    }

    private fun buildDayHeaders(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                addView(TextView(this@StreakCalendarActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(textMuted)
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, 28.dp, 1f)
                })
            }
        }

    private fun newWeekRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lpRow(bottomDp = 4)
    }

    private enum class CellType { PRACTICED, TODAY_DONE, TODAY, MISSED, FUTURE, EMPTY }

    private fun buildDayCell(label: String, type: CellType): View {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 36.dp, 1f).also {
                it.marginStart = 2.dp; it.marginEnd = 2.dp
            }
            when (type) {
                CellType.PRACTICED -> {
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#4ADE80"))
                    }
                }
                CellType.TODAY_DONE -> {
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#F97316"))
                    }
                }
                CellType.TODAY -> {
                    setTextColor(Color.parseColor("#F97316"))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(2.dp, Color.parseColor("#F97316"))
                    }
                }
                CellType.MISSED -> {
                    setTextColor(textTertiary)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#1AFFFFFF"))
                    }
                }
                CellType.FUTURE -> {
                    setTextColor(textMuted)
                    background = null
                }
                CellType.EMPTY -> {
                    setTextColor(Color.TRANSPARENT)
                    background = null
                }
            }
        }
    }
}
