package dev.shebang.devboard.view

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import dev.shebang.devboard.settings.StripMode

/**
 * The area above the keys: the terminal bar, the suggestion strip, or both, per the strip setting.
 * In AUTO mode the bar is replaced by suggestions while a word is being composed. Autofill chips from a
 * password manager, and a chip for pasting what was just copied, take the bar's row while no word is being
 * composed, so the strip keeps its height.
 */
class TopStripView(context: Context) : LinearLayout(context) {
    val bar = TerminalBarView(context)
    val suggestions = SuggestionStripView(context)
    val autofill = AutofillStripView(context)
    /** Voice typing, at the end of the top row; hidden unless the Shebang Voice add-on is installed. */
    val mic = MicButton(context)
    val rowHeight = (44 * resources.displayMetrics.density).toInt()
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private var mode = StripMode.AUTO
    private var composing = false
    private var codeMode = false
    private var hasAutofill = false
    /** The user swiped the chips away in this field; new suggestions for it stay hidden. */
    private var autofillDismissed = false
    /** The chips were swiped away: the clipboard chip's clip counts as handled. */
    var onChipsDismissed: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        rows.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        rows.addView(suggestions, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        rows.addView(autofill, 0, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(rows, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // A little room between the bar's last chip and the mic.
        addView(mic, LayoutParams(rowHeight, rowHeight).apply { marginStart = (4 * resources.displayMetrics.density).toInt() })
        mic.visibility = View.GONE
        autofill.onDismiss = {
            autofillDismissed = true
            hasAutofill = false
            autofill.setClip(null)
            apply()
            autofill.clear()
            onChipsDismissed?.invoke()
        }
        apply()
    }

    fun setTheme(t: KeyboardTheme) {
        setBackgroundColor(t.background)
        bar.setTheme(t)
        suggestions.setTheme(t)
        mic.setTheme(t)
    }

    /** Shows the voice typing button (the add-on is installed and this field takes typed words). */
    fun setMicShown(shown: Boolean) {
        mic.visibility = if (shown) View.VISIBLE else View.GONE
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

    /** The clipboard chip, or null to remove it; it shares the autofill chips' row and its swipe. */
    fun setClip(chip: android.view.View?) {
        autofill.setClip(chip)
        apply()
    }

    /** A new field: its chips show even if the last field's were swiped away. */
    fun resetAutofill() {
        autofillDismissed = false
        setAutofill(emptyList())
    }

    /** Shows the autofill chips (or removes them when [chips] is empty). */
    fun setAutofill(chips: List<android.view.View>) {
        if (autofillDismissed) return
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
        val showAutofill = (hasAutofill || autofill.hasClip) && !composing && (showBar || showSuggestions)
        autofill.visibility = if (showAutofill) VISIBLE else GONE
        bar.visibility = if (showBar && !showAutofill) VISIBLE else GONE
        suggestions.visibility = if (showSuggestions && !(showAutofill && !showBar)) VISIBLE else GONE
        if (!showSuggestions) suggestions.clear()
    }
}
