package dev.shebang.devboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.PopupWindow
import dev.shebang.devboard.layout.Key

/**
 * Key-press preview and long-press alternates, in one PopupWindow above the key.
 * The content view draws itself; no child views, so showing and moving the popup allocates nothing.
 */
class KeyPopup(context: Context) {
    private val content = PopupView(context)
    private val window = PopupWindow(content).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        animationStyle = 0
        setBackgroundDrawable(null)
    }
    private val density = context.resources.displayMetrics.density
    private val location = IntArray(2)

    val isShowing: Boolean get() = window.isShowing
    val isAlternates: Boolean get() = window.isShowing && content.alternates != null

    fun setTheme(theme: KeyboardTheme) = content.applyTheme(theme)

    fun showPreview(anchor: View, key: Key, label: String) {
        content.alternates = null
        content.label = label
        val w = maxOf(key.width * 1.1f, 44 * density)
        val h = key.height * 1.15f
        show(anchor, key, w, h, key.centerX - w / 2f)
    }

    /** Shows [alternates] in a row; [initialLabel] is the first (leftmost) entry. Returns the popup's left edge in anchor coordinates. */
    fun showAlternates(anchor: View, key: Key, alternates: List<String>) {
        content.alternates = alternates
        content.selected = 0
        val cell = maxOf(key.width, 40 * density)
        val w = cell * alternates.size
        val h = key.height * 1.15f
        // Keep inside the anchor horizontally.
        var left = key.left
        if (left + w > anchor.width) left = (anchor.width - w).coerceAtLeast(0f)
        content.cellWidth = cell
        popupLeft = left
        show(anchor, key, w, h, left)
    }

    private var popupLeft = 0f

    /** Updates the highlighted alternate from an x coordinate in anchor space. */
    fun updateSelection(x: Float) {
        val alts = content.alternates ?: return
        val i = ((x - popupLeft) / content.cellWidth).toInt().coerceIn(0, alts.size - 1)
        if (i != content.selected) {
            content.selected = i
            content.invalidate()
        }
    }

    fun selectedAlternate(): String? = content.alternates?.getOrNull(content.selected)

    private fun show(anchor: View, key: Key, w: Float, h: Float, left: Float) {
        anchor.getLocationInWindow(location)
        val x = location[0] + left.toInt()
        val y = location[1] + (key.top - h - 6 * density).toInt()
        if (window.isShowing) {
            window.update(x, y, w.toInt(), h.toInt())
        } else {
            window.width = w.toInt()
            window.height = h.toInt()
            window.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y)
        }
        content.invalidate()
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
        content.alternates = null
    }

    private class PopupView(context: Context) : View(context) {
        var theme: KeyboardTheme? = null
            private set
        var label: String = ""
        var alternates: List<String>? = null
        var selected = 0
        var cellWidth = 0f
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        private val radius = 10 * context.resources.displayMetrics.density

        fun applyTheme(t: KeyboardTheme?) {
            theme = t ?: return
            bg.color = t.popup
            hi.color = t.accent
            text.color = t.popupText
            bg.setShadowLayer(6f, 0f, 2f, 0x55000000)
        }

        override fun onDraw(canvas: Canvas) {
            val t = theme ?: return
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, bg)
            text.textSize = height * 0.42f
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
            val alts = alternates
            if (alts == null) {
                canvas.drawText(label, width / 2f, baseline, text)
                return
            }
            for (i in alts.indices) {
                val left = i * cellWidth
                if (i == selected) {
                    rect.set(left + 3f, 3f, left + cellWidth - 3f, height - 3f)
                    canvas.drawRoundRect(rect, radius, radius, hi)
                    text.color = t.onAccent
                } else {
                    text.color = t.popupText
                }
                canvas.drawText(alts[i], left + cellWidth / 2f, baseline, text)
            }
        }
    }
}
