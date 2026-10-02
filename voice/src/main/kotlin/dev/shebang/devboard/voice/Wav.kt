package dev.shebang.devboard.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads 16-bit PCM WAV (mono, 16 kHz, as Whisper wants) into samples in -1..1. */
object Wav {
    fun read16kMono(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(channels == 1 && rate == 16000 && bits == 16) { "want 16 kHz mono 16-bit, got $rate Hz, $channels ch, $bits bit" }
                val n = minOf(size, bytes.size - body) / 2
                return FloatArray(n) { b.getShort(body + 2 * it) / 32768f }
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk")
    }
}
