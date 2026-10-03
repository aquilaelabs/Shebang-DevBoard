package dev.shebang.devboard

import android.content.Context
import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where the app crashed, kept on the phone so a user can include it in Export diagnostics: there is no
 * crash reporting, so without this a crash leaves no trace. Only where the code failed is kept (the
 * exception types and their stack frames); messages are dropped, since they can carry text the app was
 * handling. At most [MAX_CRASHES] distinct crashes, newest first, each with how often it happened; no dates.
 */
object CrashLog {
    const val FILE = "crash_log.json"
    const val MAX_CRASHES = 5
    /** Frames kept of the exception itself, and of each cause, so a deep stack still shows its causes. */
    private const val TOP_FRAMES = 30
    private const val CAUSE_FRAMES = 12

    @Serializable
    data class Crash(val version: String, val trace: List<String>, val count: Int = 1)

    @Serializable
    private data class Stored(val crashes: List<Crash> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Records uncaught exceptions in this process, then lets the platform handle them as before. */
    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { record(file, version, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Adds [e] to the log in [file] (the same crash again counts once more and moves to the front). */
    @Synchronized
    fun record(file: File, version: String, e: Throwable) {
        val trace = trace(e)
        val crashes = read(file).toMutableList()
        val i = crashes.indexOfFirst { it.trace == trace && it.version == version }
        val crash = if (i >= 0) crashes.removeAt(i).let { it.copy(count = it.count + 1) } else Crash(version, trace)
        crashes.add(0, crash)
        JsonFile(file).write(Stored.serializer(), json, Stored(crashes.take(MAX_CRASHES)))
    }

    fun read(file: File): List<Crash> = JsonFile(file).read(Stored.serializer(), json)?.crashes.orEmpty()

    /** Exception types and stack frames of [e] and its causes, without any message. */
    fun trace(e: Throwable): List<String> {
        val out = ArrayList<String>()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            out += (if (depth == 0) "" else "Caused by: ") + t.javaClass.name
            for (f in t.stackTrace.take(if (depth == 0) TOP_FRAMES else CAUSE_FRAMES)) out += "  at $f"
            t = t.cause.takeIf { it !== t }
            depth++
        }
        return out
    }
}
