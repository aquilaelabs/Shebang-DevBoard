package dev.shebang.devboard.view

import android.content.Context
import android.widget.LinearLayout
import dev.shebang.devboard.settings.StripMode

/**
 * The area above the keys: the terminal bar, the suggestion strip, or both, per the strip setting.
 * In AUTO mode the bar is replaced by suggestions while a word is being composed.
 */
class TopStripView(context: Context) : LinearLayout(context) {
    val bar = TerminalBarView(context)
    val suggestions = SuggestionStripView(context)
    /** Glided words waiting to go into the field; shown instead of the other rows while it has any. */
    val staging = StagingStripView(context)
    private val rowHeight = (44 * resources.displayMetrics.density).toInt()
    private var mode = StripMode.AUTO
    private var composing = false
    private var codeMode = false
    private var stagingShown = false

    init {
        orientation = VERTICAL
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(suggestions, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(staging, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        apply()
    }

    fun setTheme(t: KeyboardTheme) {
        setBackgroundColor(t.background)
        bar.setTheme(t)
        suggestions.setTheme(t)
        staging.setTheme(t)
    }

    fun setMode(m: StripMode) {
        mode = m
        apply()
    }

    /** Code mode always shows the bar only. */
    fun setCodeMode(code: Boolean) {
        codeMode = code
        apply()
    }

    /** Shows the preview row with these words, or hides it when [texts] is empty. */
    fun showStaging(texts: List<String>, selected: Int, previewStart: Int, previewCount: Int, alternatives: List<String>) {
        val shown = texts.isNotEmpty()
        if (shown) staging.show(texts, selected, previewStart, previewCount, alternatives)
        if (shown != stagingShown) {
            stagingShown = shown
            apply()
        }
    }

    fun setComposing(c: Boolean) {
        if (composing == c) return
        composing = c
        apply()
    }

    private fun apply() {
        val showSuggestions: Boolean
        val showBar: Boolean
        when {
            codeMode -> {
                showBar = true
                showSuggestions = false
            }
            mode == StripMode.ALWAYS_BAR -> {
                showBar = true
                showSuggestions = false
            }
            mode == StripMode.TWO_ROWS -> {
                showBar = true
                showSuggestions = true
            }
            else -> {
                showBar = !composing
                showSuggestions = composing
            }
        }
        if (stagingShown && !codeMode) {
            // The preview row takes the strip: in Two rows mode the bar stays above it.
            staging.visibility = VISIBLE
            bar.visibility = if (mode == StripMode.TWO_ROWS) VISIBLE else GONE
            suggestions.visibility = GONE
            return
        }
        staging.visibility = GONE
        bar.visibility = if (showBar) VISIBLE else GONE
        suggestions.visibility = if (showSuggestions) VISIBLE else GONE
        if (!showSuggestions) suggestions.clear()
    }
}
