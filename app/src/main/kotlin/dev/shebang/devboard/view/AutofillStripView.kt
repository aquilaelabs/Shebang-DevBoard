package dev.shebang.devboard.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Autofill suggestions from the user's password manager or autofill service (Android 11+), as the chips the
 * service drew in the keyboard's style, centred in the strip (scrolling when there are more than fit). The
 * keyboard only places them: what they say and what tapping one fills in are the service's, and nothing
 * about them is read or learned. A sideways swipe puts them away ([onDismiss]); when they scroll, only a
 * swipe on past the end of the row does.
 */
class AutofillStripView(context: Context) : HorizontalScrollView(context) {
    var onDismiss: (() -> Unit)? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val density = resources.displayMetrics.density
    private val gap = (6 * density).toInt()
    private val swipe = 24 * density
    private var downX = 0f
    private var downY = 0f
    private var dismissed = false

    init {
        isHorizontalScrollBarEnabled = false
        // A row narrower than the strip is stretched to it, so its gravity centres the chips.
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun show(chips: List<View>) {
        row.removeAllViews()
        for (c in chips) {
            // The platform sizes each chip to what the service drew; a chip has no content size of its own to wrap.
            val lp = c.layoutParams?.let { LinearLayout.LayoutParams(it.width, it.height) }
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = gap / 2
            lp.marginEnd = gap / 2
            row.addView(c, lp)
        }
        scrollTo(0, 0)
    }

    fun clear() = row.removeAllViews()

    /**
     * Whether the swipe so far puts the chips away: sideways, past a short distance, in a direction the row
     * cannot scroll any further (always, when the chips all fit).
     */
    private fun isDismissSwipe(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) <= 2 * swipe || abs(dx) <= abs(dy)) return false
        // A finger moving right shows what lies to the left (direction -1), and the reverse.
        return !canScrollHorizontally(if (dx > 0) -1 else 1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dismissed = false
            }
            MotionEvent.ACTION_MOVE -> if (isDismissSwipe(ev)) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility") // The chips are the service's own views and take the clicks.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dismissed = false
            }
            MotionEvent.ACTION_MOVE -> if (!dismissed && isDismissSwipe(ev)) {
                dismissed = true
                onDismiss?.invoke()
                return true
            }
        }
        return super.onTouchEvent(ev) || true
    }
}
