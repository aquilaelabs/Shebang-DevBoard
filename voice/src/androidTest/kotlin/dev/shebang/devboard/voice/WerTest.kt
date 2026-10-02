package dev.shebang.devboard.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Word error rate and speed on LibriSpeech test-clean clips (CC BY 4.0) copied into the add-on's files as
 * one WAV per clip in wav/ with wav/refs.tsv (id, reference text). Arguments: -e wer 1 to run; -e model NAME (a file in the
 * add-on's files; default the shipped model); -e pad N (window = speech + N steps of 20 ms; -1, the default,
 * is Whisper's full 30 s window); -e limit N clips.
 */
@RunWith(AndroidJUnit4::class)
class WerTest {
    @Test
    fun wordErrorRate() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("wer") != null)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(target.filesDir, "wav")
        val refs = File(dir, "refs.tsv").readLines().filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
            .take(args.getString("limit")?.toInt() ?: Int.MAX_VALUE)
        val name = args.getString("model")
        val path = name?.let { File(target.filesDir, it).path } ?: Models.file(target).path
        val pad = args.getString("pad")?.toInt() ?: -1
        val w = Whisper.load(path, target.applicationInfo.nativeLibraryDir, flashAttn = true)!!
        var errors = 0
        var words = 0
        var audioMs = 0L
        var spentMs = 0L
        for ((id, ref) in refs) {
            val samples = Wav.read16kMono(File(dir, "$id.wav").readBytes())
            val ctx = if (pad < 0) 0 else minOf(1500, samples.size / 320 + pad)
            val t0 = System.nanoTime()
            val text = w.transcribe(samples, audioCtx = ctx, fallback = false)
            spentMs += (System.nanoTime() - t0) / 1_000_000
            audioMs += samples.size / 16
            val r = norm(ref)
            val h = norm(text)
            errors += distance(r, h)
            words += r.size
        }
        w.close()
        Log.i("ShebangVoice", "WER ${name ?: "shipped"} pad=$pad: %.2f%% of %d words, %.0f s of speech in %.0f s (%.2fx real time)".format(
            100.0 * errors / words, words, audioMs / 1000.0, spentMs / 1000.0, spentMs.toDouble() / audioMs))
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z' ]"), " ").trim().split(Regex(" +")).filter { it.isNotEmpty() }

    /** Word-level edit distance. */
    private fun distance(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }
}
