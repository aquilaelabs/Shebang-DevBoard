package dev.shebang.devboard.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** What the keys along the bottom of a panel (emoji, clipboard) do. */
interface PanelKeyListener {
    fun onPanelSpace()
    /** [first]: the press itself, not a repeat while held. */
    fun onPanelBackspace(first: Boolean)
    fun onPanelClose()
}

/** The bottom row every panel shares: ABC (back to the keys), space with its cursor mark, and backspace. */
object PanelKeys {
    private const val REPEAT_DELAY_MS = 400L
    private const val REPEAT_MS = 60L

    fun fill(row: LinearLayout, theme: KeyboardTheme, listener: () -> PanelKeyListener?) {
        val context = row.context
        val density = context.resources.displayMetrics.density
        row.removeAllViews()
        fun key(label: String, weight: Float, functional: Boolean): TextView {
            val v = TextView(context)
            v.text = label
            v.gravity = Gravity.CENTER
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            v.setTextColor(theme.keyText)
            v.background = GradientDrawable().apply {
                setColor(if (functional) theme.keyFunctional else theme.key)
                cornerRadius = 8 * density
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            val gap = (3 * density).toInt()
            lp.setMargins(gap, gap, gap, gap)
            row.addView(v, lp)
            return v
        }
        key("ABC", 1.5f, true).apply {
            contentDescription = "Back to the keyboard"
            setOnClickListener { listener()?.onPanelClose() }
        }
        key("—", 5f, false).apply {
            setTextColor(theme.keyTextSecondary)
            contentDescription = "Space"
            setOnClickListener { listener()?.onPanelSpace() }
        }
        key("", 1.5f, true).apply {
            background = LayerDrawable(arrayOf(background, IconDrawable(KeyIcons.backspace, theme.keyText, 22 * density)))
            contentDescription = "Delete"
            repeatWhileHeld(this) { first -> listener()?.onPanelBackspace(first) }
        }
    }

    private fun repeatWhileHeld(v: View, onTap: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                onTap(false)
                handler.postDelayed(this, REPEAT_MS)
            }
        }
        // A touch acts on the way down and repeats while held; a click that comes without a touch (from
        // TalkBack or another accessibility service) acts once.
        var touched = false
        v.setOnClickListener { if (touched) touched = false else onTap(true) }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    onTap(true)
                    handler.postDelayed(tick, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    handler.removeCallbacks(tick)
                    touched = true
                    view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    handler.removeCallbacks(tick)
                }
            }
            true
        }
    }
}
