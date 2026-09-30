package dev.shebang.devboard.view

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Three candidate slots. Views are created once; showing new words only sets text. */
class SuggestionStripView(context: Context) : LinearLayout(context) {
    var onSuggestion: ((String) -> Unit)? = null
    private val slots = Array(3) { TextView(context) }
    private var theme: KeyboardTheme = KeyboardTheme.build(context, dev.shebang.devboard.settings.Settings())

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, s) in slots.withIndex()) {
            s.gravity = Gravity.CENTER
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            s.maxLines = 1
            s.ellipsize = android.text.TextUtils.TruncateAt.END
            s.isClickable = true
            s.setOnClickListener { v -> (v as TextView).text.toString().takeIf { it.isNotEmpty() }?.let { onSuggestion?.invoke(it) } }
            addView(s, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            if (i < slots.size - 1) {
                val divider = View(context)
                divider.tag = "divider"
                addView(divider, LayoutParams(1, (24 * resources.displayMetrics.density).toInt()))
            }
        }
        applyTheme()
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        applyTheme()
    }

    private fun applyTheme() {
        setBackgroundColor(theme.background)
        for (s in slots) s.setTextColor(theme.stripText)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.tag == "divider") v.setBackgroundColor(theme.keyTextSecondary and 0x60FFFFFF)
        }
    }

    fun show(words: List<String>) {
        for (i in slots.indices) {
            slots[i].text = words.getOrNull(i) ?: ""
            // The middle slot (best candidate) is bold, like most keyboards.
            slots[i].setTypeface(null, if (i == 1 && words.size > 1) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    fun clear() = show(emptyList())

    /** Reorders so the best candidate sits in the middle: [2nd, 1st, 3rd]. */
    companion object {
        fun arrange(ranked: List<String>): List<String> = when (ranked.size) {
            0 -> emptyList()
            1 -> listOf("", ranked[0], "")
            2 -> listOf(ranked[1], ranked[0], "")
            else -> listOf(ranked[1], ranked[0], ranked[2])
        }
    }
}
