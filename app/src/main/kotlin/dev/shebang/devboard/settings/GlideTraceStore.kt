package dev.shebang.devboard.settings

import android.content.Context
import dev.shebang.devboard.glide.GlideTrace
import java.io.File
import java.io.OutputStream

/**
 * Recorded glides, one JSON object per line in the app's private files. They never leave the phone unless
 * the user exports them through the system file picker.
 */
class GlideTraceStore(context: Context) {
    private val file = File(context.filesDir, FILE)

    @Synchronized
    fun add(trace: GlideTrace) {
        file.appendText(trace.toJsonLine() + "\n")
    }

    @Synchronized
    fun count(): Int = if (file.exists()) file.useLines { lines -> lines.count { it.isNotBlank() } } else 0

    @Synchronized
    fun removeLast() {
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }
        file.writeText(lines.dropLast(1).joinToString("") { it + "\n" })
    }

    @Synchronized
    fun exportTo(out: OutputStream) {
        if (file.exists()) file.inputStream().use { it.copyTo(out) }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        const val FILE = "glide_traces.jsonl"
    }
}
