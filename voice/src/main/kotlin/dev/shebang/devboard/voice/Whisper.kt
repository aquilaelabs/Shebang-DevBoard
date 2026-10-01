package dev.shebang.devboard.voice

import java.io.Closeable

/**
 * A loaded Whisper model (whisper.cpp, on the CPU). [transcribe] takes 16 kHz mono samples in -1..1 and returns
 * the English text. Not thread-safe: one transcription at a time.
 */
class Whisper private constructor(private var handle: Long) : Closeable {
    fun transcribe(samples: FloatArray, threads: Int = defaultThreads()): String {
        check(handle != 0L) { "closed" }
        return nativeTranscribe(handle, samples, threads)?.trim() ?: throw IllegalStateException("transcription failed")
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("shebangvoice")
        }

        /** Loads the model file at [path]; null when it cannot be read as a Whisper model. */
        fun load(path: String): Whisper? = nativeInit(path).takeIf { it != 0L }?.let { Whisper(it) }

        /** Big cores only, roughly: half the processors, at least two, at most four. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int): String?
    }
}
