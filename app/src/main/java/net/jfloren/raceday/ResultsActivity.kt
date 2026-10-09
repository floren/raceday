package net.jfloren.raceday

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import net.jfloren.raceday.timer.StepType
import java.util.Locale
import kotlin.math.ceil

/**
 * Card with the finished race's times: the total, then each leg, filling the left column top to
 * bottom before moving on to the next. Swiping down (back) returns to the main screen.
 */
class ResultsActivity : ComponentActivity() {

    private lateinit var columnsLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        setContentView(R.layout.activity_results)
        columnsLayout = findViewById(R.id.resultsColumns)

        val state = (application as RacedayApp).startSequenceTimer.state.value
        // Nothing to show once a new sequence has been started
        if (state.stepType != StepType.FINISHED) {
            finish()
            return
        }

        val entries = listOf(getString(R.string.results_total) to state.raceElapsedSeconds) +
                state.legDurations.mapIndexed { i, seconds -> getString(R.string.leg_label_results, i + 1) to seconds }
        // Sizing needs the space available, so wait for the first layout
        columnsLayout.doOnLayout { showEntries(entries) }
    }

    private fun showEntries(entries: List<Pair<String, Int>>) {
        val height = columnsLayout.height - columnsLayout.paddingTop - columnsLayout.paddingBottom
        // Largest text that fits everything in two columns; at the smallest size, add columns instead
        var textSizeSp = TEXT_SIZES_SP.last()
        var rows = rowsThatFit(height, textSizeSp)
        for (size in TEXT_SIZES_SP) {
            val fit = rowsThatFit(height, size)
            if (fit * 2 >= entries.size) {
                textSizeSp = size
                rows = fit
                break
            }
        }
        val columns = maxOf(2, ceil(entries.size / rows.toDouble()).toInt())

        columnsLayout.removeAllViews()
        for (column in 0 until columns) {
            val columnLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, if (column < columns - 1) dp(COLUMN_GAP_DP) else 0, 0)
            }
            columnsLayout.addView(columnLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            for ((label, seconds) in entries.drop(column * rows).take(rows)) {
                columnLayout.addView(entryRow(label, formatRaceTime(seconds), textSizeSp))
            }
        }
    }

    private fun entryRow(label: String, time: String, textSizeSp: Float): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            entryText(label, textSizeSp, R.color.gray_light, bold = false),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(entryText(time, textSizeSp, R.color.white, bold = true))
        return row
    }

    private fun entryText(text: String, textSizeSp: Float, colorRes: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTextColor(ContextCompat.getColor(this@ResultsActivity, colorRes))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
    }

    private fun rowsThatFit(heightPx: Int, textSizeSp: Float): Int {
        val sample = entryText("0", textSizeSp, R.color.white, bold = true)
        sample.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return maxOf(1, heightPx / sample.measuredHeight)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val TEXT_SIZES_SP = listOf(26f, 22f, 18f, 15f, 13f)
        private const val COLUMN_GAP_DP = 16

        /** H:MM:SS from an hour on, M:SS before. */
        fun formatRaceTime(totalSeconds: Int): String {
            val hours = totalSeconds / 3600
            val mins = totalSeconds / 60 % 60
            val secs = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
            } else {
                String.format(Locale.US, "%d:%02d", mins, secs)
            }
        }
    }
}
