package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.glide.FutoData
import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.glide.GlideParams
import dev.shebang.devboard.glide.StreamingGlideDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * Gliding on without fixing anything: whole FUTO sentences, each word glided with the swipe made for it and
 * punctuation typed, then the words compared with what was meant. Run with the previous word's fixing off
 * and on (at a few decoder margins), it shows what the fixing gains and what it breaks. Skipped unless
 * FUTO_SWIPES names the dataset's test split.
 *
 *     FUTO_SWIPES=/path/test.jsonl ./gradlew testDebugUnitTest --tests '*GlideOnTest*' -i | grep GLIDE-ON
 */
class GlideOnTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm

    private class Score(var words: Int = 0, var right: Int = 0, var fixed: Int = 0, var broke: Int = 0)

    @Test
    fun glideOn() {
        val path = System.getenv("FUTO_SWIPES")
        assumeTrue("FUTO_SWIPES not set", path != null && File(path).isFile)
        val records = FutoData.read(File(path!!), System.getenv("FUTO_LIMIT")?.toIntOrNull() ?: 50_000)
        val sentences = records.groupBy { it.session to it.sentence }.values.filter { it.first().sentence.isNotBlank() }
            .take(System.getenv("FRICTION_SENTENCES")?.toIntOrNull() ?: 400)
        val off = run(sentences, GlideParams(), false, null)
        println("GLIDE-ON ${off.words} glided words: right at the end ${GlideBenchmarkTest.pct(off.right, off.words)} without fixing the word before")
        for (margin in listOf(2f, 3f, 4f, 6f)) {
            val on = run(sentences, GlideParams(reviseMargin = margin), true, off)
            println("GLIDE-ON   margin $margin: right ${GlideBenchmarkTest.pct(on.right, on.words)}; words fixed ${on.fixed}, broken ${on.broke}")
        }
    }

    /** Words right at the end; against [baseline] (same sentences), how many it fixed and broke. */
    private val finals = HashMap<Pair<Boolean, Float>, List<List<Boolean>>>()

    private fun run(sentences: Collection<List<FutoData.Record>>, params: GlideParams, fix: Boolean, baseline: Score?): Score {
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, params)
        val s = Score()
        val results = ArrayList<List<Boolean>>()
        for (rs in sentences) {
            val ok = writeSentence(rs, decoder, fix)
            results += ok
            s.words += ok.size
            s.right += ok.count { it }
        }
        if (baseline != null) {
            val base = finals[false to 0f]!!
            for ((a, b) in base.zip(results)) for ((x, y) in a.zip(b)) {
                if (!x && y) s.fixed++
                if (x && !y) s.broke++
            }
        } else {
            finals[false to 0f] = results
        }
        return s
    }

    /** Per glided word of the sentence: whether it reads as meant at the end. */
    private fun writeSentence(rs: List<FutoData.Record>, decoder: StreamingGlideDecoder, fix: Boolean): List<Boolean> {
        val ic = FakeInputConnection()
        var now = 100_000L
        val c = TextInputController(
            { ic },
            object : TextInputController.Ui {
                override fun showCandidates(words: List<String>) = Unit
                override fun setComposing(composing: Boolean) = Unit
            },
            Executor { it.run() },
            Handler(),
        )
        c.clock = { now }
        c.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        c.settings = c.settings.copy(fixPreviousGlide = fix, autocorrect = false)
        c.predictionModel = dictionary to lm
        val swipeAt = rs.associateBy { it.wordIdx }
        val tokens = rs.first().sentence.split(' ').filter { it.isNotEmpty() }
        val glidedAt = ArrayList<Int>()
        for ((i, tok) in tokens.withIndex()) {
            val lead = tok.takeWhile { !it.isLetterOrDigit() }
            val trail = if (lead.length == tok.length) "" else tok.takeLastWhile { !it.isLetterOrDigit() }
            val core = tok.substring(lead.length, tok.length - trail.length)
            val record = swipeAt[i]?.takeIf { it.word == core.lowercase() }
            val glidable = record != null && core.length >= 2 && core.all { it.isLetter() || it == '\'' } && dictionary.indexOfLower(core.lowercase()) >= 0
            now += 400
            if (!glidable) {
                for (ch in "$tok ") if (ch == ' ') c.space() else c.typeText(ch.toString())
                continue
            }
            for (ch in lead) c.typeText(ch.toString())
            val ctx = c.glideContext(dictionary, lm)
            decoder.begin(record!!.layout, ctx, record.t[0])
            for (k in record.x.indices) decoder.addPoint(record.x[k], record.y[k], record.t[k])
            val r = decoder.finish()
            if (r != null) c.commitGlide(r, dictionary, false, false)
            glidedAt += i
            for (ch in trail) c.typeText(ch.toString())
            if (trail.isNotEmpty()) c.space()
        }
        c.finishComposing()
        val got = ic.toString().split(' ').filter { it.isNotEmpty() }.map { w -> w.filter { it.isLetter() || it == '\'' }.lowercase() }
        val want = tokens.map { w -> w.filter { it.isLetter() || it == '\'' }.lowercase() }
        // Only sentences that end up with as many words as meant can be compared word by word.
        if (got.size != want.size) return glidedAt.map { false }
        return glidedAt.map { got[it] == want[it] }
    }
}
