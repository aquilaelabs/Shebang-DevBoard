package dev.shebang.devboard.view

import android.content.Context
import android.widget.LinearLayout
import dev.shebang.devboard.settings.StripMode

/**
 * The area above the keys: the terminal bar, the suggestion strip, or both, per the strip setting.
 * In AUTO mode the bar is replaced by suggestions while a word is being composed. Autofill chips from a
 * password manager take the bar's row while no word is being composed, so the strip keeps its height.
 */
class TopStripView(context: Context) : LinearLayout(context) {
    val bar = TerminalBarView(context)
    val suggestions = SuggestionStripView(context)
    val autofill = AutofillStripView(context)
    val rowHeight = (44 * resources.displayMetrics.density).toInt()
    private var mode = StripMode.AUTO
    private var composing = false
    private var codeMode = false
    private var hasAutofill = false

    init {
        orientation = VERTICAL
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(suggestions, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(autofill, 0, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
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

    /** Shows the autofill chips (or removes them when [chips] is empty). */
    fun setAutofill(chips: List<android.view.View>) {
        hasAutofill = chips.isNotEmpty()
        // The row is shown before the chips go in: a chip attached while hidden gives up its surface for good.
        apply()
        if (chips.isEmpty()) autofill.clear() else autofill.show(chips)
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
        // Chips take the bar's row, or the suggestions' in Auto mode when no word is composed.
        val showAutofill = hasAutofill && !composing && (showBar || showSuggestions)
        autofill.visibility = if (showAutofill) VISIBLE else GONE
        bar.visibility = if (showBar && !showAutofill) VISIBLE else GONE
        suggestions.visibility = if (showSuggestions && !(showAutofill && !showBar)) VISIBLE else GONE
        if (!showSuggestions) suggestions.clear()
    }
}
