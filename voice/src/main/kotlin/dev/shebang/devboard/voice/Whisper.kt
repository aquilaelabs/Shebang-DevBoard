package dev.shebang.devboard.voice

import java.io.Closeable

/**
 * A loaded Whisper model (whisper.cpp, on the CPU). [transcribe] takes 16 kHz mono samples in -1..1 and returns
 * the English text. Not thread-safe: one transcription at a time.
 */
class Whisper private constructor(private var handle: Long) : Closeable {
    /**
     * [audioCtx]: the encoder's window in steps of 20 ms (0: Whisper's full 30 s); [fallback]: decode an unsure
     * piece again at higher temperatures.
     */
    fun transcribe(samples: FloatArray, threads: Int = defaultThreads(), audioCtx: Int = 0, fallback: Boolean = true): String {
        check(handle != 0L) { "closed" }
        return nativeTranscribe(handle, samples, threads, audioCtx, fallback)?.trim() ?: throw IllegalStateException("transcription failed")
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("shebangvoice")
        }

        /**
         * Loads the model file at [path]; null when it cannot be read as a Whisper model. [libDir] is the app's
         * native library folder, where phones find the CPU variants of the engine.
         */
        fun load(path: String, libDir: String, flashAttn: Boolean = false): Whisper? =
            nativeInit(path, libDir, flashAttn).takeIf { it != 0L }?.let { Whisper(it) }

        /** Big cores only, roughly: half the processors, at least two, at most four. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        @JvmStatic private external fun nativeInit(path: String, libDir: String, flashAttn: Boolean): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int, audioCtx: Int, fallback: Boolean): String?
    }
}
