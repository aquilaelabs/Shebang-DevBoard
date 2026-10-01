package dev.shebang.devboard.voice

import android.content.Context
import java.io.File

/** The speech model shipped in the add-on's assets, copied once to its private files for whisper.cpp to read. */
object Models {
    const val ASSET = "models/ggml-base.en-q5_1.bin"

    fun file(context: Context): File {
        val out = File(context.filesDir, ASSET.substringAfterLast('/'))
        val size = context.assets.openFd(ASSET).use { it.length }
        if (out.length() != size) {
            val tmp = File(out.path + ".tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            tmp.renameTo(out)
        }
        return out
    }
}
