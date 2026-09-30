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

    /** One line across the whole strip: the words a glide will write if the finger lifts now. */
    fun showPreview(text: String) {
        setSideSlotsVisible(false)
        slots[1].text = text
        slots[1].setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun setSideSlotsVisible(visible: Boolean) {
        val v = if (visible) VISIBLE else GONE
        if (slots[0].visibility == v) return
        slots[0].visibility = v
        slots[2].visibility = v
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.tag == "divider") c.visibility = v
        }
    }

    fun show(words: List<String>) {
        setSideSlotsVisible(true)
        for (i in slots.indices) {
            slots[i].text = words.getOrNull(i) ?: ""
            // The middle slot (best candidate) is bold, like most keyboards.
            slots[i].setTypeface(null, if (i == 1 && words.size > 1) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    fun clear() = show(emptyList())
}
