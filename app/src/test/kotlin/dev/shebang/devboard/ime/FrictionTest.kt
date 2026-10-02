package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.glide.FutoData
import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.glide.GlideParams
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.StreamingGlideDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * Writes whole sentences through the keyboard's text logic the way a person would, with real swipes from the
 * FUTO dataset, to find where the friction is. Words are glided with the swipe made for them; punctuation,
 * digits and one-letter words are tapped; shift is tapped for a capital the keyboard would not give. A word
 * that comes out wrong is fixed the cheapest way that works: from the strip, else by tapping inside it and
 * gliding it again (another person's real swipe of it, up to twice), else by selecting it and typing it.
 * Space is tapped only where the keyboard would not add one. It prints the actions each word took, how words
 * got right, and every sentence whose text differs from what was meant, sorted into kinds of difference.
 *
 *     FUTO_SWIPES=/path/test.jsonl ./gradlew testDebugUnitTest --tests '*FrictionTest*' -i | grep FRICTION
 *
 * FRICTION_SENTENCES caps the sentences (default 400).
 */
class FrictionTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm

    private class Stats {
        var sentences = 0
        var exact = 0
        var words = 0
        var glided = 0
        var firstTry = 0
        var fromStrip = 0
        var reglided = 0
        var typedFix = 0
        var typedWords = 0
        var shift = 0
        var spaces = 0
        var actions = 0
        var typedFallbackLetters = 0
        val kinds = HashMap<String, Int>()
        val examples = HashMap<String, MutableList<String>>()
    }

    /** The spelling a glide writes for [lower]: the lowest tier, then the all-lowercase one (as LexiconTrie picks). */
    private fun spellingGlided(lower: String): String {
        val d = dictionary
        var best = d.indexOfLower(lower)
        var i = best
        while (i < d.size && d.lower[i] == lower) {
            if (d.tiers[i] < d.tiers[best] || (d.tiers[i] == d.tiers[best] && d.words[i] == lower)) best = i
            i++
        }
        return d.words[best]
    }

    @Test
    fun friction() {
        val path = System.getenv("FUTO_SWIPES")
        assumeTrue("FUTO_SWIPES not set", path != null && File(path).isFile)
        val records = FutoData.read(File(path!!), System.getenv("FUTO_LIMIT")?.toIntOrNull() ?: 50_000)
        val maxSentences = System.getenv("FRICTION_SENTENCES")?.toIntOrNull() ?: 400
        // Swipes of each word, for gliding a word again with someone else's swipe.
        val byWord = records.groupBy { it.word }
        // Sentences as one person swiped them, word by word.
        val sentences = records.groupBy { it.session to it.sentence }.values
            .filter { rs -> rs.first().sentence.isNotBlank() }
            .take(maxSentences)
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, GlideParams())
        val s = Stats()
        for (rs in sentences) writeSentence(rs, byWord, decoder, s)

        fun pct(a: Int, b: Int) = GlideBenchmarkTest.pct(a, b)
        println("FRICTION ${s.sentences} sentences, ${s.words} words (${s.glided} glided, ${s.typedWords} tapped out)")
        println("FRICTION actions per word: ${"%.2f".format(s.actions.toDouble() / s.words)} (glides, taps and key presses; ${s.spaces} spaces and ${s.shift} shifts tapped)")
        println("FRICTION glided words right first time ${pct(s.firstTry, s.glided)}, fixed from the strip ${pct(s.fromStrip, s.glided)}, by gliding again ${pct(s.reglided, s.glided)}, only by typing ${pct(s.typedFix, s.glided)} (${s.typedFallbackLetters} letters)")
        println("FRICTION sentences exactly as meant: ${pct(s.exact, s.sentences)}")
        for ((k, v) in s.kinds.entries.sortedByDescending { it.value }) {
            println("FRICTION   $v  $k")
            for (e in s.examples[k].orEmpty().take(6)) println("FRICTION        $e")
        }
    }

    private fun writeSentence(rs: List<FutoData.Record>, byWord: Map<String, List<FutoData.Record>>, decoder: StreamingGlideDecoder, s: Stats) {
        val sentence = rs.first().sentence
        val swipeAt = rs.associateBy { it.wordIdx }
        val ic = FakeInputConnection()
        var strip: List<String> = emptyList()
        var now = 100_000L
        val c = TextInputController(
            { ic },
            object : TextInputController.Ui {
                override fun showCandidates(words: List<String>) {
                    strip = words
                }
                override fun setComposing(composing: Boolean) = Unit
            },
            Executor { it.run() },
            Handler(),
        )
        c.clock = { now }
        c.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0))
        var lastWasGlide = false

        fun act(n: Int = 1) {
            s.actions += n
            now += 300
        }
        fun type(text: String) {
            for (ch in text) {
                if (ch == ' ') c.space() else c.typeText(ch.toString())
                act()
            }
        }
        fun sentenceStart(): Boolean = GlideText.contextWord(ic.getTextBeforeCursor(64, 0)) == GlideText.SENTENCE_START
        fun decode(r: FutoData.Record): GlideResult? {
            val ctx = c.glideContext(dictionary, lm)
            decoder.begin(r.layout, ctx, r.t[0])
            for (i in r.x.indices) decoder.addPoint(r.x[i], r.y[i], r.t[i])
            return decoder.finish()
        }
        /** The word just written, at the end of the field. */
        fun lastWord(): String {
            val before = ic.toString()
            var i = before.length
            while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
            return before.substring(i)
        }

        val tokens = sentence.split(' ').filter { it.isNotEmpty() }
        val trace = System.getenv("FRICTION_TRACE")?.let { sentence.contains(it) } == true
        for ((idx, tok) in tokens.withIndex()) {
            if (trace) println("FRICTION TRACE before «$tok»: field «$ic» strip $strip")
            s.words++
            val lead = tok.takeWhile { !it.isLetterOrDigit() }
            // A token of punctuation only (a dash) is all lead.
            val trail = if (lead.length == tok.length) "" else tok.takeLastWhile { !it.isLetterOrDigit() }
            val core = tok.substring(lead.length, tok.length - trail.length)
            val lower = core.lowercase()
            val glidable = core.length >= 2 && core.all { it.isLetter() || it == '\'' } && dictionary.indexOfLower(lower) >= 0
            val record = swipeAt[idx]?.takeIf { it.word == lower } ?: byWord[lower]?.firstOrNull()
            // Space is tapped only where the keyboard adds none: before anything but a glide, unless a glide
            // just went in and a letter follows (the keyboard spaces that too).
            val text = ic.toString()
            val needSpace = idx > 0 && text.isNotEmpty() && !text.last().isWhitespace()
            val startsWithLetter = (lead + core).firstOrNull()?.isLetter() == true
            val keyboardSpaces = (glidable && record != null && lead.isEmpty()) || (lastWasGlide && lead.isEmpty() && startsWithLetter)
            if (needSpace && !keyboardSpaces) {
                type(" ")
                s.spaces++
            }
            type(lead)
            if (glidable && record != null) {
                s.glided++
                // Shift for a capital the keyboard would not give.
                val atStart = sentenceStart()
                val display = spellingGlided(lower)
                val wantCap = core[0].isUpperCase()
                val capitalize = atStart || (wantCap && display[0].isLowerCase())
                if (!atStart && wantCap && display[0].isLowerCase()) {
                    s.shift++
                    act()
                }
                val r = decode(record)
                act()
                if (trace) println("FRICTION TRACE   glide «$core»: atStart $atStart wantCap $wantCap display «$display» capitalize $capitalize decoded ${r?.words?.map { dictionary.words[it] }}")
                if (r != null) c.commitGlide(r, dictionary, capitalize, false)
                lastWasGlide = true
                if (lastWord().equals(core, ignoreCase = true)) {
                    s.firstTry++
                } else if (strip.any { it.equals(core, ignoreCase = true) }) {
                    c.pickCandidate(strip.first { it.equals(core, ignoreCase = true) })
                    act()
                    s.fromStrip++
                } else {
                    var fixed = false
                    val others = byWord[lower].orEmpty().filter { it !== record }.take(2)
                    for (other in others) {
                        // Tap inside the word, then glide it again.
                        val end = ic.toString().length
                        val w = lastWord().length
                        if (w < 2) break
                        now += 1000
                        ic.setSelection(end - w + 1, end - w + 1)
                        c.onSelectionChanged(end, end, end - w + 1, end - w + 1, -1, -1)
                        act()
                        val again = decode(other)
                        act()
                        if (again != null) c.commitGlide(again, dictionary, false, false)
                        if (lastWord().equals(core, ignoreCase = true)) {
                            fixed = true
                            break
                        }
                    }
                    if (fixed) {
                        s.reglided++
                    } else {
                        // Select the word and type it.
                        val end = ic.toString().length
                        val w = lastWord().length
                        now += 1000
                        ic.setSelection(end - w, end)
                        c.onSelectionChanged(end, end, end - w, end, -1, -1)
                        act()
                        type(core)
                        s.typedFallbackLetters += core.length
                        ic.setSelection(ic.toString().length, ic.toString().length)
                        c.onSelectionChanged(end, end, ic.toString().length, ic.toString().length, -1, -1)
                        s.typedFix++
                        lastWasGlide = false
                    }
                }
            } else {
                s.typedWords++
                if (core.isNotEmpty() && core[0].isUpperCase() && !sentenceStart()) {
                    s.shift++
                    act()
                }
                type(core)
                lastWasGlide = false
            }
            if (trail.isNotEmpty()) {
                type(trail)
                lastWasGlide = false
            }
        }
        c.finishComposing()
        s.sentences++
        val got = ic.toString().trimEnd()
        val want = tokens.joinToString(" ")
        if (got == want) {
            s.exact++
            return
        }
        val kind = when {
            got.replace(" ", "") == want.replace(" ", "") -> "spacing differs"
            got.equals(want, ignoreCase = true) -> "capitals differ"
            got.lowercase().replace(" ", "") == want.lowercase().replace(" ", "") -> "spacing and capitals differ"
            else -> "words differ"
        }
        s.kinds[kind] = (s.kinds[kind] ?: 0) + 1
        s.examples.getOrPut(kind) { ArrayList() } += "want «$want»  got «$got»"
    }
}
