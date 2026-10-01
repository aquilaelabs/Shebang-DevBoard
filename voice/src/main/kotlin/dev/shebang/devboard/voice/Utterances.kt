package dev.shebang.devboard.voice

import kotlin.math.sqrt

/**
 * Cuts a stream of 16 kHz samples into utterances at pauses, by loudness: a running estimate of the room's
 * quiet, speech as frames well above it, and an utterance ending after [pauseMs] of quiet following speech
 * (or at [maxMs], Whisper's window being 30 s). Each utterance keeps a little audio before its first loud
 * frame so soft starts are not clipped. Feed it 30 ms frames; it calls [onUtterance] with each one.
 */
class Utterances(
    private val pauseMs: Int = 700,
    private val maxMs: Int = 25_000,
    private val onUtterance: (FloatArray) -> Unit,
) {
    private val frame = FRAME
    private var noise = 0.003f
    private val pre = ArrayDeque<FloatArray>()
    private val current = ArrayList<FloatArray>()
    private var speaking = false
    private var quietFrames = 0
    private var voicedFrames = 0

    /** Loudness of the last frame against the room's quiet, 0..100, for a level meter. */
    var level = 0
        private set
    /** Whether speech is being heard now. */
    val hearing: Boolean get() = speaking

    /** Takes the next frame; it is copied, so the caller may reuse its buffer. */
    fun feed(frameIn: FloatArray) {
        require(frameIn.size == frame)
        val samples = frameIn.copyOf()
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = sqrt(sum / frame).toFloat()
        val loud = rms > maxOf(noise * SPEECH_RATIO, MIN_SPEECH)
        level = (100 * rms / maxOf(noise * SPEECH_RATIO * 4, MIN_SPEECH * 4)).toInt().coerceIn(0, 100)
        if (!loud) noise = 0.95f * noise + 0.05f * rms
        if (!speaking) {
            pre.addLast(samples)
            if (pre.size > PRE_FRAMES) pre.removeFirst()
            if (loud) {
                speaking = true
                current.addAll(pre)
                pre.clear()
                quietFrames = 0
                voicedFrames = 1
            }
            return
        }
        current += samples
        if (loud) {
            quietFrames = 0
            voicedFrames++
        } else {
            quietFrames++
        }
        val ms = current.size * FRAME_MS
        if (quietFrames * FRAME_MS >= pauseMs || ms >= maxMs) finish()
    }

    /** Ends the utterance in progress, if it had any speech. */
    fun flush() {
        if (speaking) finish()
    }

    private fun finish() {
        val out = FloatArray(current.size * frame)
        for ((i, f) in current.withIndex()) System.arraycopy(f, 0, out, i * frame, frame)
        val enough = voicedFrames * FRAME_MS >= MIN_SPEECH_MS
        current.clear()
        speaking = false
        quietFrames = 0
        voicedFrames = 0
        if (enough) onUtterance(out)
    }

    companion object {
        const val RATE = 16_000
        const val FRAME_MS = 30
        const val FRAME = RATE * FRAME_MS / 1000
        private const val PRE_FRAMES = 10
        private const val SPEECH_RATIO = 3f
        private const val MIN_SPEECH = 0.01f
        /** Less voiced audio than this is a cough or a click, not words. */
        private const val MIN_SPEECH_MS = 200
    }
}
