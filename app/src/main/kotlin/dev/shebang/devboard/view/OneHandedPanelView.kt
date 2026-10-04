package dev.shebang.devboard.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The strip beside the keys in one-handed mode: a chevron that moves the keys to the other side, and a
 * two-headed arrow that turns one-handed mode off. Each takes half the height, so both are easy to reach.
 */
class OneHandedPanelView(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onSwitchSide()
        fun onFullWidth()
    }

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private val switchSide = button { listener?.onSwitchSide() }
    private val fullWidth = button { listener?.onFullWidth() }
    private var theme: KeyboardTheme? = null
    private var keysOnLeft = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = (6 * density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(switchSide, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = pad })
        addView(fullWidth, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        fullWidth.contentDescription = "Full-width keyboard"
    }

    private fun button(onClick: () -> Unit) = View(context).apply {
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        style()
    }

    /** Which side the keys are on: the chevron points to the other one. */
    fun setKeysOnLeft(left: Boolean) {
        keysOnLeft = left
        style()
    }

    private fun style() {
        val t = theme ?: return
        val size = 24 * density
        switchSide.background = layered(if (keysOnLeft) KeyIcons.dockRight else KeyIcons.dockLeft, t, size)
        switchSide.contentDescription = if (keysOnLeft) "Move the keyboard right" else "Move the keyboard left"
        fullWidth.background = layered(KeyIcons.fullWidth, t, size)
    }

    private fun layered(icon: android.graphics.Path, t: KeyboardTheme, size: Float) = LayerDrawable(
        arrayOf(
            GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(t.keyFunctional)
            },
            IconDrawable(icon, t.keyText, size),
        ),
    )
}
