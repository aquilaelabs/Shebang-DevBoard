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
 * service drew in the keyboard's style, after a chip for pasting what was just copied, centred in the strip (scrolling when there are more than fit). The
 * keyboard only places them: what they say and what tapping one fills in are the service's, and nothing
 * about them is read or learned. A sideways swipe carries them along with the finger, and past a third of
 * the strip (or flung) they slide off and go ([onDismiss]); when they scroll, only a swipe on past the end
 * of the row does.
 */
class AutofillStripView(context: Context) : HorizontalScrollView(context) {
    var onDismiss: (() -> Unit)? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val density = resources.displayMetrics.density
    private val gap = (6 * density).toInt()
    private var downX = 0f
    private var downY = 0f

    init {
        isHorizontalScrollBarEnabled = false
        // A row narrower than the strip is stretched to it, so its gravity centres the chips.
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private var autofillChips: List<View> = emptyList()
    private var clipChip: View? = null

    /** The autofill service's chips (after the clipboard chip, when there is one). */
    fun show(chips: List<View>) {
        autofillChips = chips
        rebuild()
    }

    /** A chip for pasting what was just copied, ahead of any autofill chips; null removes it. */
    fun setClip(chip: View?) {
        clipChip = chip
        rebuild()
    }

    val hasClip: Boolean get() = clipChip != null

    private fun rebuild() {
        row.removeAllViews()
        for (c in listOfNotNull(clipChip) + autofillChips) {
            // The platform sizes each chip to what the service drew; a chip has no content size of its own to wrap.
            val lp = c.layoutParams?.let { LinearLayout.LayoutParams(it.width, it.height) }
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = gap / 2
            lp.marginEnd = gap / 2
            row.addView(c, lp)
        }
        scrollTo(0, 0)
    }

    /** Removes the autofill chips (the clipboard chip stays). */
    fun clear() {
        autofillChips = emptyList()
        rebuild()
    }

    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val flingSpeed = 1000 * density
    private var swiping = false
    private var velocity: android.view.VelocityTracker? = null

    /** A sideways drag carries the chips along when the row cannot scroll any further that way. */
    private fun startsSwipe(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        // A finger moving right shows what lies to the left (direction -1), and the reverse.
        return abs(dx) > slop && abs(dx) > abs(dy) && !canScrollHorizontally(if (dx > 0) -1 else 1)
    }

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        swiping = false
        row.animate().cancel()
        velocity?.recycle()
        velocity = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    /** The row follows the finger and fades as it goes. */
    private fun follow(dx: Float) {
        row.translationX = dx
        row.alpha = 1f - 0.7f * minOf(1f, abs(dx) / maxOf(1, width))
    }

    /** Let go: past a third of the strip, or flung, the chips slide off and go; otherwise they come back. */
    private fun release(dx: Float, lifted: Boolean) {
        val v = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
        velocity?.recycle()
        velocity = null
        val away = lifted && (abs(dx) > width / 3f || (abs(v) > flingSpeed && v * dx > 0))
        if (away) {
            val sign = if (dx != 0f) kotlin.math.sign(dx) else kotlin.math.sign(v)
            row.animate().translationX(sign * width).alpha(0f).setDuration(SLIDE_MS).withEndAction {
                onDismiss?.invoke()
                row.translationX = 0f
                row.alpha = 1f
            }
        } else {
            row.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (startsSwipe(ev)) {
                    swiping = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility") // The chips are the service's own views and take the clicks.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (!swiping && startsSwipe(ev)) swiping = true
                if (swiping) {
                    follow(ev.x - downX)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                velocity?.addMovement(ev)
                swiping = false
                release(ev.x - downX, ev.actionMasked == MotionEvent.ACTION_UP)
                return true
            }
        }
        return super.onTouchEvent(ev) || true
    }

    private companion object {
        const val SLIDE_MS = 160L
    }
}
