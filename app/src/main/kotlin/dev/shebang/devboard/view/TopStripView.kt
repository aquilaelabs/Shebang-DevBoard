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
    private val rowHeight = (44 * resources.displayMetrics.density).toInt()
    private var mode = StripMode.AUTO
    private var composing = false
    private var codeMode = false

    init {
        orientation = VERTICAL
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(suggestions, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        apply()
    }

    fun setTheme(t: KeyboardTheme) {
        setBackgroundColor(t.background)
        bar.setTheme(t)
        suggestions.setTheme(t)
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
        bar.visibility = if (showBar) VISIBLE else GONE
        suggestions.visibility = if (showSuggestions) VISIBLE else GONE
        if (!showSuggestions) suggestions.clear()
    }
}
