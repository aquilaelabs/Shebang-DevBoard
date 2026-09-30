package dev.shebang.devboard.input

import android.os.Handler
import android.os.Looper

/**
 * Hold-to-repeat with acceleration: after [initialDelayMs] the action fires every [startIntervalMs],
 * and the interval shrinks by [accelerate] each time down to [minIntervalMs].
 * One repeat runs at a time (a second finger cancels the first).
 */
class RepeatController(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val initialDelayMs: Long = 400,
    private val startIntervalMs: Long = 90,
    private val minIntervalMs: Long = 28,
    private val accelerate: Float = 0.88f,
) {
    private var action: (() -> Unit)? = null
    private var interval = startIntervalMs
    var isRepeating = false
        private set
    /** True once at least one repeat fired for the current hold, so the release does not fire the key again. */
    var fired = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            val a = action ?: return
            isRepeating = true
            fired = true
            a()
            interval = (interval * accelerate).toLong().coerceAtLeast(minIntervalMs)
            handler.postDelayed(this, interval)
        }
    }

    fun start(action: () -> Unit) {
        stop()
        this.action = action
        interval = startIntervalMs
        fired = false
        handler.postDelayed(tick, initialDelayMs)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        action = null
        isRepeating = false
    }
}
