package dev.shebang.devboard.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.drawToBitmap
import kotlin.math.hypot
import kotlin.math.max

/**
 * The emoji and clipboard panels grow out of the bar button that opens them and shrink back into it. A picture
 * of what showed before is laid over [host]: opening, the new panel shows through a circle widening from the
 * button; closing, the panel's picture is held inside a circle narrowing to it, the keys already beneath.
 * Nothing moves or resizes, touches reach what is new at once, and with the system's animations off it is a
 * plain switch.
 */
class PanelMorph(private val host: ViewGroup) {
    private var animator: ValueAnimator? = null
    private var shot: CircleShot? = null

    /**
     * Runs [change], [from] (the part of [host] showing now) giving way through a circle centred on
     * ([x], [y]) in [host]'s coordinates: widening from it when [opening], narrowing to it otherwise.
     */
    fun run(from: View?, x: Float, y: Float, opening: Boolean, change: () -> Unit) {
        finish()
        if (from == null || !from.isShown || from.width == 0 || from.height == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            change()
            return
        }
        val picture = runCatching { from.drawToBitmap() }.getOrNull()
        if (picture == null) {
            change()
            return
        }
        val at = IntArray(2)
        val hostAt = IntArray(2)
        from.getLocationInWindow(at)
        host.getLocationInWindow(hostAt)
        val left = at[0] - hostAt[0]
        val top = at[1] - hostAt[1]
        val d = CircleShot(picture, outside = opening)
        d.setBounds(left, top, left + from.width, top + from.height)
        d.cx = x
        d.cy = y
        // Far enough to clear the farthest corner of the area.
        val reach = max(
            max(hypot(left - x, top - y), hypot(left + from.width - x, top - y)),
            max(hypot(left - x, top + from.height - y), hypot(left + from.width - x, top + from.height - y)),
        )
        d.r = if (opening) 0f else reach
        host.overlay.add(d)
        shot = d
        change()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                d.r = if (opening) reach * t else reach * (1f - t)
                d.invalidateSelf()
                host.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finish()
                }
            })
            start()
        }
    }

    /** Ends a morph at once (the keyboard is closing, or another switch comes). */
    fun finish() {
        val a = animator
        animator = null
        a?.cancel()
        shot?.let {
            host.overlay.remove(it)
            it.bitmap.recycle()
        }
        shot = null
    }

    /** [bitmap] drawn outside a circle ([outside]) or inside it. */
    private class CircleShot(val bitmap: Bitmap, private val outside: Boolean) : Drawable() {
        var cx = 0f
        var cy = 0f
        var r = 0f
        private val path = Path()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            path.reset()
            path.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.save()
            if (outside) canvas.clipOutPath(path) else canvas.clipPath(path)
            canvas.drawBitmap(bitmap, null, bounds, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val DURATION_MS = 240L
    }
}
