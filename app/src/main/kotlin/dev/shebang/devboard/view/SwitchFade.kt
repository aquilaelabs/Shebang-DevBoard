package dev.shebang.devboard.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.view.drawToBitmap

/**
 * A quick fade when the keys' area changes what it shows (letters and code mode, the emoji and clipboard panels):
 * a picture of what was there is laid over [host] and fades out over the new content. Nothing moves or resizes,
 * touches go straight to the new keys, and with the system's animations off it is a plain switch.
 */
class SwitchFade(private val host: ViewGroup) {
    private var animator: ValueAnimator? = null
    private var shot: BitmapDrawable? = null

    /** Runs [change], fading out how [from] (the part of [host] showing now) looked before it. */
    fun run(from: View?, change: () -> Unit) {
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
        val d = BitmapDrawable(host.resources, picture)
        val at = IntArray(2)
        val hostAt = IntArray(2)
        from.getLocationInWindow(at)
        host.getLocationInWindow(hostAt)
        val left = at[0] - hostAt[0]
        val top = at[1] - hostAt[1]
        d.setBounds(left, top, left + from.width, top + from.height)
        host.overlay.add(d)
        shot = d
        change()
        animator = ValueAnimator.ofInt(255, 0).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { d.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finish()
                }
            })
            start()
        }
    }

    /** Ends a fade at once (the keyboard is closing, or another switch comes). */
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

    companion object {
        const val DURATION_MS = 120L
    }
}
