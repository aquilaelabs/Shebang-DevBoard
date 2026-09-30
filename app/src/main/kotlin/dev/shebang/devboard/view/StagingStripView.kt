package dev.shebang.devboard.view

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The preview row for glided words that are not in the field yet. Each word is a chip: tap one to select it
 * (the next glide replaces it); the selected word's alternatives follow as outlined chips. A glide in
 * progress shows its words in a lighter style. Chips are pooled, so updates while gliding create no views.
 */
class StagingStripView(context: Context) : HorizontalScrollView(context) {

    interface Listener {
        fun onStagedWordTapped(index: Int)
        fun onStagedAlternativeTapped(index: Int)
    }

    var listener: Listener? = null
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val density = resources.displayMetrics.density
    private val words = ArrayList<TextView>()
    private val alternatives = ArrayList<TextView>()
    private val divider = View(context)
    private var theme: KeyboardTheme = KeyboardTheme.build(context, dev.shebang.devboard.settings.Settings())

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        divider.setBackgroundColor(t.keyTextSecondary)
    }

    private fun chip(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        maxLines = 1
        minWidth = (40 * density).toInt()
        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        isClickable = true
        isFocusable = false
    }

    private fun params() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
        val m = (3 * density).toInt()
        setMargins(m, (6 * density).toInt(), m, (6 * density).toInt())
    }

    private fun background(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 10 * density
        setColor(fill)
        if (stroke != 0) setStroke((1.5f * density).toInt(), stroke)
    }

    /**
     * Shows [texts]; [selected] is highlighted; words from [previewStart] for [previewCount] are a glide in
     * progress; [alts] are the selected word's alternatives.
     */
    fun show(texts: List<String>, selected: Int, previewStart: Int, previewCount: Int, alts: List<String>) {
        row.removeAllViews()
        for ((i, text) in texts.withIndex()) {
            while (words.size <= i) {
                val index = words.size
                words.add(chip().also { v -> v.setOnClickListener { listener?.onStagedWordTapped(index) } })
            }
            val v = words[i]
            v.text = text
            val inPreview = i >= previewStart && i < previewStart + previewCount
            when {
                i == selected -> {
                    v.background = background(theme.accent)
                    v.setTextColor(theme.onAccent)
                    v.setTypeface(null, Typeface.BOLD)
                }
                inPreview -> {
                    v.background = background(theme.background, theme.keyTextSecondary)
                    v.setTextColor(theme.keyTextSecondary)
                    v.setTypeface(null, Typeface.ITALIC)
                }
                else -> {
                    v.background = background(theme.key)
                    v.setTextColor(theme.keyText)
                    v.setTypeface(null, Typeface.NORMAL)
                }
            }
            v.isClickable = !inPreview
            row.addView(v, params())
        }
        if (alts.isNotEmpty()) {
            row.addView(divider, LinearLayout.LayoutParams((1 * density).toInt(), (24 * density).toInt()).apply {
                val m = (6 * density).toInt()
                setMargins(m, 0, m, 0)
            })
            for ((i, text) in alts.withIndex()) {
                while (alternatives.size <= i) {
                    val index = alternatives.size
                    alternatives.add(chip().also { v -> v.setOnClickListener { listener?.onStagedAlternativeTapped(index) } })
                }
                val v = alternatives[i]
                v.text = text
                v.background = background(theme.background, theme.accent)
                v.setTextColor(theme.accent)
                v.setTypeface(null, Typeface.NORMAL)
                row.addView(v, params())
            }
        }
        // Keep the newest word in view.
        if (selected < 0 && alts.isEmpty()) post { fullScroll(FOCUS_RIGHT) }
    }
}
