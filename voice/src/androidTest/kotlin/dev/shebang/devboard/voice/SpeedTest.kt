package dev.shebang.devboard.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long a piece of speech takes to become text, and whether it still comes out right, for the engine's
 * speed settings: the Kennedy sample cut at its pauses, each piece transcribed under each setting. Run with
 * -e speed 1 (adb shell am instrument ... -e speed 1, or the Gradle property below).
 */
@RunWith(AndroidJUnit4::class)
class SpeedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun speedSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("speed") != null)
        val target = instrumentation.targetContext
        val samples = Wav.read16kMono(instrumentation.context.assets.open("jfk.wav").use { it.readBytes() })
        val pieces = ArrayList<FloatArray>()
        val u = Utterances { pieces += it }
        var i = 0
        while (i + Utterances.FRAME <= samples.size) {
            u.feed(samples.copyOfRange(i, i + Utterances.FRAME))
            i += Utterances.FRAME
        }
        u.flush()
        val want = listOf("and so my fellow americans", "ask not", "what your country can do for you ask what you can do for your country")
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex(" +"), " ")
        // -e model NAME: a model file in the add-on's files instead of the shipped one.
        val path = InstrumentationRegistry.getArguments().getString("model")?.let { java.io.File(target.filesDir, it).path } ?: Models.file(target).path
        val lib = target.applicationInfo.nativeLibraryDir
        val flashes = if (InstrumentationRegistry.getArguments().getString("flash") != null) listOf(true) else listOf(false, true)
        Log.i("ShebangVoice", "SPEED model ${path.substringAfterLast('/')}")
        for (flash in flashes) {
            val w = Whisper.load(path, lib, flash)!!
            w.transcribe(pieces[1]) // warm up
            for ((name, ctxOf, fallback) in listOf(
                Triple("full window, fallback", { _: FloatArray -> 0 }, true),
                Triple("full window", { _: FloatArray -> 0 }, false),
                Triple("window = speech + 2 s", { a: FloatArray -> a.size / 320 + 100 }, false),
                Triple("window = speech + 1 s", { a: FloatArray -> a.size / 320 + 50 }, false),
                Triple("window = speech + 0.5 s", { a: FloatArray -> a.size / 320 + 25 }, false),
                Triple("window = speech", { a: FloatArray -> a.size / 320 }, false),
            )) {
                val times = ArrayList<Long>()
                var right = 0
                val texts = ArrayList<String>()
                for ((k, p) in pieces.withIndex()) {
                    val t0 = System.nanoTime()
                    val text = w.transcribe(p, audioCtx = minOf(1500, ctxOf(p)), fallback = fallback)
                    times += (System.nanoTime() - t0) / 1_000_000
                    texts += text
                    if (norm(text) == want.getOrNull(k)) right++
                }
                Log.i("ShebangVoice", "SPEED flash=$flash $name: ${times.joinToString("/")} ms (total ${times.sum()}), right $right/${pieces.size}: ${texts.joinToString(" | ")}")
            }
            w.close()
        }
    }
}
