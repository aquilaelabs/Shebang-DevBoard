package dev.shebang.devboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.shebang.devboard.layout.Key

/**
 * Key-press preview and long-press alternates, drawn by one overlay view that covers the whole IME window
 * (strip and keyboard). Nothing is created per tap: no PopupWindow, no child views, no allocation.
 *
 * The popup sits just above the key, overlapping its upper part so that the top row's popup still fits
 * inside the strip's height rather than needing a window of its own.
 */
class KeyPopup(context: Context) : View(context) {
    private var theme: KeyboardTheme? = null
    private var label: String = ""
    private var alternates: List<String>? = null
    private var selected = 0
    private var cellWidth = 0f
    private val rect = RectF()
    private val cell = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val density = context.resources.displayMetrics.density
    private val radius = 10 * density
    private val anchorLocation = IntArray(2)
    private val ownLocation = IntArray(2)
    private var showing = false

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    val isShowing: Boolean get() = showing
    val isAlternates: Boolean get() = showing && alternates != null

    fun setTheme(t: KeyboardTheme) {
        theme = t
        bg.color = t.popup
        hi.color = t.accent
        text.color = t.popupText
        outline.color = t.keyTextSecondary and 0x60FFFFFF.toInt()
        outline.strokeWidth = 1f * density
        invalidate()
    }

    fun showPreview(anchor: View, key: Key, label: String) {
        alternates = null
        this.label = label
        val w = maxOf(key.width * 1.1f, 44 * density)
        place(anchor, key, w, key.centerX - w / 2f)
    }

    /** Shows [alternates] in a row, the first (leftmost) entry selected. */
    fun showAlternates(anchor: View, key: Key, alternates: List<String>) {
        this.alternates = alternates
        selected = 0
        cellWidth = maxOf(key.width, 40 * density)
        val w = cellWidth * alternates.size
        var left = key.left
        if (left + w > anchor.width) left = (anchor.width - w).coerceAtLeast(0f)
        place(anchor, key, w, left)
    }

    /** Updates the highlighted alternate from an x coordinate in anchor (keyboard) space. */
    fun updateSelection(anchorX: Float) {
        val alts = alternates ?: return
        val x = anchorX + offsetX
        val i = ((x - rect.left) / cellWidth).toInt().coerceIn(0, alts.size - 1)
        if (i != selected) {
            selected = i
            invalidate()
        }
    }

    fun selectedAlternate(): String? = alternates?.getOrNull(selected)

    private var offsetX = 0f

    private fun place(anchor: View, key: Key, w: Float, anchorLeft: Float) {
        anchor.getLocationInWindow(anchorLocation)
        getLocationInWindow(ownLocation)
        offsetX = (anchorLocation[0] - ownLocation[0]).toFloat()
        val offsetY = (anchorLocation[1] - ownLocation[1]).toFloat()
        val h = key.height * 1.15f
        // Bottom edge a third of the way down the key, so the top row's popup stays inside the window.
        val bottom = offsetY + key.top + key.height * 0.35f
        var top = bottom - h
        if (top < 0f) top = 0f
        rect.set(anchorLeft + offsetX, top, anchorLeft + offsetX + w, bottom)
        showing = true
        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    fun dismiss() {
        if (!showing) return
        showing = false
        alternates = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        val t = theme ?: return
        canvas.drawRoundRect(rect, radius, radius, bg)
        canvas.drawRoundRect(rect, radius, radius, outline)
        text.textSize = rect.height() * 0.42f
        val baseline = rect.centerY() - (text.descent() + text.ascent()) / 2f
        val alts = alternates
        if (alts == null) {
            text.color = t.popupText
            canvas.drawText(label, rect.centerX(), baseline, text)
            return
        }
        for (i in alts.indices) {
            val left = rect.left + i * cellWidth
            if (i == selected) {
                cell.set(left + 3f, rect.top + 3f, left + cellWidth - 3f, rect.bottom - 3f)
                canvas.drawRoundRect(cell, radius, radius, hi)
                text.color = t.onAccent
            } else {
                text.color = t.popupText
            }
            canvas.drawText(alts[i], left + cellWidth / 2f, baseline, text)
        }
    }
}
