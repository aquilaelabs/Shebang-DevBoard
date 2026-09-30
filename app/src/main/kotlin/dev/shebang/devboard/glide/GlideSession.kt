package dev.shebang.devboard.glide

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Runs the streaming decoder on its own thread while the finger moves.
 *
 * The main thread writes touch records into a single-producer, single-consumer ring and wakes the decoder
 * thread with one reusable Runnable, so feeding a point allocates nothing on the main thread. The decoder
 * posts previews (throttled) and the final result back to the main thread, tagged with the glide's id so a
 * late result for an abandoned glide is ignored.
 */
class GlideSession(private val listener: Listener) {

    interface Listener {
        /** The decode if the finger lifted now. Main thread. */
        fun onGlidePreview(id: Int, result: GlideResult)
        /** The final decode, or null when no word fits. Main thread. */
        fun onGlideResult(id: Int, result: GlideResult?, decodeMs: Float)
    }

    private class Start(val layout: KeyLayoutModel, val context: GlideContext, val id: Int, val phrase: Boolean)

    /** Set once the language model has loaded; read on the decoder thread. */
    @Volatile
    var language: GlideLanguage? = null

    private val thread = HandlerThread("devboard-glide", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Ring of records: type, x, y, time. START records carry a slot in x.
    private val cap = 1 shl 13
    private val mask = cap - 1
    private val type = IntArray(cap)
    private val xs = FloatArray(cap)
    private val ys = FloatArray(cap)
    private val ts = LongArray(cap)
    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)
    private val scheduled = AtomicBoolean(false)
    private val starts = AtomicReferenceArray<Start?>(8)
    private var nextId = 0

    // Decoder-thread state.
    private var decoder: StreamingGlideDecoder? = null
    private var decoderLanguage: GlideLanguage? = null
    private var activeId = -1
    private var lastPreviewMs = 0L
    private var lastPreviewKey = 0

    // ---- Main thread ---------------------------------------------------------------------------------

    /** Starts a glide and returns its id. [phrase] when phrase gliding is on. */
    fun start(layout: KeyLayoutModel, context: GlideContext, tMs: Long, phrase: Boolean): Int {
        val id = ++nextId
        val slot = id and 7
        starts.set(slot, Start(layout, context, id, phrase))
        push(START, slot.toFloat(), 0f, tMs)
        return id
    }

    fun point(x: Float, y: Float, tMs: Long) = push(POINT, x, y, tMs)

    /** Word boundary inside one stroke (phrase gliding). */
    fun boundary() = push(BOUNDARY, 0f, 0f, 0L)

    /** Finger lifted at (x, y); pass NaN when the lift point should not count. */
    fun end(x: Float, y: Float, tMs: Long) = push(END, x, y, tMs)

    fun cancel() = push(CANCEL, 0f, 0f, 0L)

    fun release() {
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun push(kind: Int, x: Float, y: Float, t: Long) {
        val h = head.get()
        // A full ring drops points; control records always get through because points stop first.
        if (h - tail.get() >= cap - (if (kind == POINT) 64 else 0)) return
        val i = h and mask
        type[i] = kind
        xs[i] = x
        ys[i] = y
        ts[i] = t
        head.lazySet(h + 1)
        if (scheduled.compareAndSet(false, true)) worker.post(drain)
    }

    // ---- Decoder thread ------------------------------------------------------------------------------

    private val drain = Runnable {
        scheduled.set(false)
        var t = tail.get()
        val h = head.get()
        while (t != h) {
            process(t and mask)
            t++
        }
        tail.lazySet(t)
    }

    private fun process(i: Int) {
        when (type[i]) {
            START -> {
                val s = starts.get(xs[i].toInt()) ?: return
                val lang = language ?: return
                val d = decoder.takeIf { decoderLanguage === lang } ?: StreamingGlideDecoder(lang).also {
                    decoder = it
                    decoderLanguage = lang
                }
                d.begin(s.layout, s.context, ts[i], s.phrase)
                activeId = s.id
                lastPreviewMs = 0L
                lastPreviewKey = 0
            }
            POINT -> {
                val d = decoder ?: return
                if (activeId < 0) return
                d.addPoint(xs[i], ys[i], ts[i])
                maybePreview(d, ts[i])
            }
            BOUNDARY -> {
                val d = decoder ?: return
                if (activeId < 0) return
                d.boundary()
            }
            END -> {
                val d = decoder ?: return
                val id = activeId
                if (id < 0) return
                // NaN: lifted in the space bar after a dip; the lift point belongs to no word.
                val trailingSpace = xs[i].isNaN()
                if (!trailingSpace) d.addPoint(xs[i], ys[i], ts[i])
                val t0 = System.nanoTime()
                val r = d.finish(trailingSpace)
                val ms = (System.nanoTime() - t0) / 1e6f
                activeId = -1
                main.post { listener.onGlideResult(id, r, ms) }
            }
            CANCEL -> activeId = -1
        }
    }

    private fun maybePreview(d: StreamingGlideDecoder, tMs: Long) {
        if (tMs - lastPreviewMs < PREVIEW_INTERVAL_MS) return
        lastPreviewMs = tMs
        val r = d.preview() ?: return
        // Only post when the words shown would change.
        var key = r.firstRevised * 31
        for (w in r.words) key = key * 31 + w
        for (w in r.history) key = key * 31 + w
        if (key == lastPreviewKey) return
        lastPreviewKey = key
        val id = activeId
        main.post { listener.onGlidePreview(id, r) }
    }

    companion object {
        private const val START = 1
        private const val POINT = 2
        private const val BOUNDARY = 3
        private const val END = 4
        private const val CANCEL = 5
        private const val PREVIEW_INTERVAL_MS = 40L
    }
}
