package dev.shebang.devboard.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.shebang.devboard.input.ModState
import dev.shebang.devboard.input.Modifier
import dev.shebang.devboard.input.ModifierState
import dev.shebang.devboard.layout.BarConfig
import dev.shebang.devboard.layout.BarItem

/** The horizontally scrolling row of terminal keys, modifiers and snippets. */
class TerminalBarView(context: Context) : HorizontalScrollView(context) {

    interface Listener {
        fun onBarKey(item: BarItem)
        fun onBarKeyRepeat(item: BarItem)
        fun onBarModifier(item: BarItem, modifier: Modifier)
        fun onBarSnippet(item: BarItem)
        fun onBarPress()
    }

    var listener: Listener? = null
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val density = resources.displayMetrics.density
    private var theme: KeyboardTheme = KeyboardTheme.build(context, dev.shebang.devboard.settings.Settings())
    private var config: BarConfig? = null
    private val modifierViews = HashMap<Modifier, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // Chips fade out where there is more to scroll, instead of being cut off (at the mic, or the edge).
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((28 * density).toInt())
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        config?.let { setConfig(it) }
    }

    fun setConfig(c: BarConfig) {
        config = c
        row.removeAllViews()
        modifierViews.clear()
        for (item in c.items) row.addView(makeChip(item), chipParams())
    }

    /** Highlights modifiers: one-shot in the active tint, locked in the accent. */
    fun updateModifiers(state: ModifierState) {
        for ((m, v) in modifierViews) styleModifier(v, state.state(m))
    }

    private fun chipParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
        val m = (3 * density).toInt()
        setMargins(m, (5 * density).toInt(), m, (5 * density).toInt())
    }

    private fun chipBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 8 * density
        setColor(color)
    }

    private fun makeChip(item: BarItem): TextView {
        val v = TextView(context)
        v.text = item.label
        v.gravity = Gravity.CENTER
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        v.typeface = android.graphics.Typeface.MONOSPACE
        v.minWidth = (44 * density).toInt()
        v.setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        v.isClickable = true
        v.isFocusable = false
        v.contentDescription = item.label
        when {
            item.isModifier -> {
                val mod = Modifier.parse(item.mod ?: "")
                if (mod != null) modifierViews[mod] = v
                styleModifier(v, ModState.OFF)
                v.setOnClickListener {
                    listener?.onBarPress()
                    if (mod != null) listener?.onBarModifier(item, mod)
                }
            }
            item.isSnippet -> {
                v.background = chipBackground(theme.key)
                v.setTextColor(theme.accent)
                v.setOnClickListener {
                    listener?.onBarPress()
                    listener?.onBarSnippet(item)
                }
            }
            else -> {
                v.background = chipBackground(theme.keyFunctional)
                v.setTextColor(theme.stripText)
                if (item.repeat) attachRepeat(v, item) else v.setOnClickListener {
                    listener?.onBarPress()
                    listener?.onBarKey(item)
                }
            }
        }
        return v
    }

    private fun styleModifier(v: TextView, state: ModState) {
        when (state) {
            ModState.OFF -> {
                v.background = chipBackground(theme.keyFunctional)
                v.setTextColor(theme.stripText)
            }
            ModState.ONESHOT -> {
                v.background = chipBackground(theme.modifierActive)
                v.setTextColor(theme.keyText)
            }
            ModState.LOCKED -> {
                v.background = chipBackground(theme.modifierLocked)
                v.setTextColor(theme.onAccent)
            }
        }
    }

    /** Tap fires once; holding repeats with acceleration. Scrolling cancels (the ScrollView intercepts). */
    @Suppress("ClickableViewAccessibility") // ACTION_UP performs the click; performClick is also wired below.
    private fun attachRepeat(v: View, item: BarItem) {
        v.setOnClickListener { listener?.onBarKey(item) }
        var fired = false
        var interval = 80L
        val tick = object : Runnable {
            override fun run() {
                fired = true
                listener?.onBarKeyRepeat(item)
                interval = (interval * 0.85f).toLong().coerceAtLeast(25L)
                handler.postDelayed(this, interval)
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    interval = 80L
                    view.isPressed = true
                    listener?.onBarPress()
                    handler.postDelayed(tick, 380L)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(tick)
                    view.isPressed = false
                    if (!fired) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(tick)
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
