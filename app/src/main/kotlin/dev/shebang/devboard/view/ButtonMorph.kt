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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.drawToBitmap
import kotlin.math.hypot
import kotlin.math.min

/**
 * The keys' area turning into another (letters and code mode, the emoji and clipboard panels) by way of the
 * button that asked for it. Opening, the button lifts off, travels in an arc to the middle of the area becoming
 * a circle while what showed there fades away, and the circle widens into the new screen. Closing runs it back:
 * the screen narrows into the circle, which flies home into its button.
 *
 * It is all drawn over [host] from a picture of what showed before: nothing moves or resizes, touches reach what
 * is new at once, and with the system's animations off it is a plain switch.
 */
class ButtonMorph(private val host: ViewGroup) {
    private var animator: ValueAnimator? = null
    private var shot: Shot? = null

    /**
     * Runs [change], [area] (the part of [host] showing now; what comes takes its place) giving way to it by way
     * of [button] (in [host]'s coordinates, corners of [buttonRadius]), filled with [color] over [background]:
     * out of the button when [opening], back into it otherwise.
     */
    fun run(area: View?, button: RectF?, buttonRadius: Float, color: Int, background: Int, opening: Boolean, change: () -> Unit) {
        finish()
        if (area == null || button == null || !area.isShown || area.width == 0 || area.height == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            change()
            return
        }
        val picture = runCatching { area.drawToBitmap() }.getOrNull()
        if (picture == null) {
            change()
            return
        }
        val at = IntArray(2)
        val hostAt = IntArray(2)
        area.getLocationInWindow(at)
        host.getLocationInWindow(hostAt)
        val left = (at[0] - hostAt[0]).toFloat()
        val top = (at[1] - hostAt[1]).toFloat()
        val d = Shot(picture, RectF(left, top, left + area.width, top + area.height), RectF(button), buttonRadius, color, background, opening)
        d.setBounds(0, 0, host.width, host.height)
        host.overlay.add(d)
        shot = d
        change()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            addUpdateListener {
                d.t = it.animatedValue as Float
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
            it.picture.recycle()
        }
        shot = null
    }

    /** The morph at [t] (0 to 1), drawn over the host; the new screen is the real one beneath. */
    private class Shot(
        val picture: Bitmap,
        private val area: RectF,
        private val button: RectF,
        private val buttonRadius: Float,
        color: Int,
        background: Int,
        private val opening: Boolean,
    ) : Drawable() {
        var t = 0f
        private val pictureSrc = Rect(0, 0, picture.width, picture.height)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val bg = Paint().apply { this.color = background }
        private val image = Paint(Paint.FILTER_BITMAP_FLAG)
        private val circle = Path()
        private val blob = RectF()
        /** The circle the button becomes, at the area's middle, before it widens. */
        private val small = min(area.width(), area.height()) * 0.2f
        private val cx = area.centerX()
        private val cy = area.centerY()
        /** Far enough from the middle to clear the area's corners. */
        private val reach = hypot(area.width() / 2f, area.height() / 2f)

        override fun draw(canvas: Canvas) {
            if (opening) {
                if (t < TRAVEL) {
                    // The old screen fades to the background as the button travels in.
                    val p = EASE.getInterpolation(t / TRAVEL)
                    canvas.drawRect(area, bg)
                    image.alpha = ((1f - p) * 255).toInt()
                    canvas.drawBitmap(picture, pictureSrc, area, image)
                    drawBlob(canvas, p, 255)
                } else {
                    // The circle widens; inside it the new screen shows through as the fill fades.
                    val q = EASE.getInterpolation((t - TRAVEL) / (1f - TRAVEL))
                    val r = small + (reach - small) * q
                    circle.reset()
                    circle.addCircle(cx, cy, r, Path.Direction.CW)
                    canvas.save()
                    canvas.clipRect(area)
                    canvas.clipOutPath(circle)
                    canvas.drawRect(area, bg)
                    canvas.restore()
                    fill.alpha = ((1f - q * 1.6f).coerceIn(0f, 1f) * 255).toInt()
                    canvas.save()
                    canvas.clipRect(area)
                    canvas.drawPath(circle, fill)
                    canvas.restore()
                    fill.alpha = 255
                }
            } else {
                val narrow = 1f - TRAVEL
                if (t < narrow) {
                    // The old screen narrows into the circle, fading into its fill; the new one comes up around it.
                    val q = EASE.getInterpolation(t / narrow)
                    val r = reach + (small - reach) * q
                    circle.reset()
                    circle.addCircle(cx, cy, r, Path.Direction.CW)
                    canvas.save()
                    canvas.clipRect(area)
                    canvas.clipOutPath(circle)
                    bg.alpha = ((1f - q) * 255).toInt()
                    canvas.drawRect(area, bg)
                    bg.alpha = 255
                    canvas.restore()
                    canvas.save()
                    canvas.clipRect(area)
                    canvas.clipPath(circle)
                    canvas.drawPath(circle, fill)
                    image.alpha = ((1f - q * 1.6f).coerceIn(0f, 1f) * 255).toInt()
                    canvas.drawBitmap(picture, pictureSrc, area, image)
                    canvas.restore()
                } else {
                    // The circle flies home and becomes the button again, fading onto the real one.
                    val p = EASE.getInterpolation((t - narrow) / TRAVEL)
                    drawBlob(canvas, 1f - p, ((1f - (p - 0.7f) / 0.3f).coerceIn(0f, 1f) * 255).toInt())
                }
            }
        }

        /** The button on its way: at [p] 0 its own shape and place, at 1 the circle in the area's middle. */
        private fun drawBlob(canvas: Canvas, p: Float, alpha: Int) {
            // An arc: across first, then in, like a thrown thing settling.
            val sx = button.centerX()
            val sy = button.centerY()
            val x = (1 - p) * (1 - p) * sx + 2 * (1 - p) * p * cx + p * p * cx
            val y = (1 - p) * (1 - p) * sy + 2 * (1 - p) * p * sy + p * p * cy
            val w = button.width() + (2 * small - button.width()) * p
            val h = button.height() + (2 * small - button.height()) * p
            val radius = buttonRadius + (small - buttonRadius) * p
            blob.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            fill.alpha = alpha
            canvas.drawRoundRect(blob, radius, radius, fill)
            fill.alpha = 255
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val DURATION_MS = 300L
        /** The share of the time the button spends travelling (opening: first; closing: last). */
        private const val TRAVEL = 0.4f
        private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
