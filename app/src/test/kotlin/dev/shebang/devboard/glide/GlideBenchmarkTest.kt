package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.layout.FieldVariant
import dev.shebang.devboard.layout.KeyboardGeometry
import dev.shebang.devboard.layout.LayoutParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Benchmarks the streaming decoder against the original whole-word (SHARK2) decoder.
 *
 * Geometry matches a 1080 px wide phone at 420 dpi with 52 dp rows. Strokes come from [GestureSimulator],
 * except [originalHarnessTargets], which keeps the original harness's noise model and targets.
 * Every number is printed with a "GLIDE BENCH" prefix.
 */
class GlideBenchmarkTest {

    companion object {
        const val DENSITY = 2.625f
        private val layoutDef = LayoutParser.parse(File("src/main/assets/layouts/text_qwerty.json").readText())
        val geometry = KeyboardGeometry(
            layoutDef, FieldVariant.PLAIN, 1080, (4 * 52 * DENSITY).toInt(), false, 5 * DENSITY, 8 * DENSITY, 1,
        )
        val layout: KeyLayoutModel = KeyLayoutModel.build(1, geometry.letterKeyWidth, geometry.rowHeightPx) { c ->
            geometry.letterKey(c)?.let { it.centerX to it.centerY }
        }
        val dictionary: Dictionary by lazy {
            dev.shebang.devboard.dict.BuiltInWords.all()
        }
        val lm: NgramModel by lazy { File(System.getenv("DEVBOARD_NGRAMS") ?: "src/main/assets/dict/en_ngrams.bin").inputStream().use { NgramModel.load(it, dictionary) } }
        /** The learned reading of strokes the app ships (DEVBOARD_GLIDE_MODEL names another; "none" for none). */
        val glideModel: GlideModel? by lazy {
            val path = System.getenv("DEVBOARD_GLIDE_MODEL") ?: "src/main/assets/${GlideModel.ASSET}"
            if (path == "none") null else File(path).takeIf { it.isFile }?.inputStream()?.use { GlideModel.load(it) }
        }
        /** The next-word model the app ships (DEVBOARD_NEXT_WORD names another; "none" for none). */
        val nextWord: dev.shebang.devboard.dict.NextWordModel? by lazy {
            val path = System.getenv("DEVBOARD_NEXT_WORD") ?: "src/main/assets/${dev.shebang.devboard.dict.NextWordModel.ASSET}"
            if (path == "none") null else File(path).takeIf { it.isFile }?.inputStream()?.use { dev.shebang.devboard.dict.NextWordModel.load(it) }
        }
        val language: GlideLanguage by lazy { GlideLanguage.build(dictionary, lm, glideModel, nextWord) }

        /** The 1,000 most frequent glide-able words, by the n-gram model's unigram counts. */
        val commonWords: List<String> by lazy {
            val trie = language.trie
            val reps = (0 until trie.words.size).map { trie.words[it] }.distinct()
            reps.sortedBy { lm.unigramCost(it) }.take(1000).map { dictionary.lower[it] }
        }

        val heldOut: List<List<String>> by lazy {
            File("src/test/resources/glide/heldout_sentences.tsv").readLines()
                .filter { !it.startsWith("#") && it.isNotBlank() }
                .map { it.substringAfter('\t').split(' ') }
        }

        fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%%".format(100.0 * a / b)
    }

    val sim = GestureSimulator(layout, DENSITY)

    private fun feed(decoder: StreamingGlideDecoder, g: GestureSimulator.Gesture) {
        for (i in 0 until g.count) decoder.addPoint(g.x[i], g.y[i], g.t[i])
    }

    private fun decodeNew(decoder: StreamingGlideDecoder, g: GestureSimulator.Gesture, ctx: GlideContext): GlideResult? {
        decoder.begin(layout, ctx, g.t[0])
        feed(decoder, g)
        return decoder.finish()
    }

    private fun decodeOld(old: GlideDecoder, g: GestureSimulator.Gesture): List<String> {
        val pts = FloatArray(2 * g.count)
        for (i in 0 until g.count) {
            pts[2 * i] = g.x[i]
            pts[2 * i + 1] = g.y[i]
        }
        return old.decode(pts, g.count, layout).map { it.word.lowercase() }
    }

    private fun words(r: GlideResult?): List<String> = r?.alternatives?.map { dictionary.lower[it] } ?: emptyList()

    fun contextOf(word: String?): Int {
        if (word == null) return NgramModel.SENTENCE_START
        val i = dictionary.indexOfLower(word)
        return if (i < 0) NgramModel.UNKNOWN else lm.contextOf(i)
    }

    fun glideable(word: String) = sim.keys(word) != null

    @Test
    fun realisticIsolatedWords() {
        val rnd = Random(1)
        val decoder = StreamingGlideDecoder(language)
        val old = GlideDecoder(dictionary)
        var n = 0
        var old1 = 0
        var old3 = 0
        var new1 = 0
        var new3 = 0
        var finishNs = 0L
        var maxFinishNs = 0L
        var pointNs = 0L
        var points = 0
        val misses = ArrayList<String>()
        for (w in commonWords) {
            val g = sim.generate(w, rnd) ?: continue
            n++
            val o = decodeOld(old, g)
            if (o.firstOrNull() == w) old1++
            if (w in o.take(3)) old3++
            decoder.begin(layout, GlideContext(NgramModel.UNKNOWN), g.t[0])
            val t0 = System.nanoTime()
            feed(decoder, g)
            val t1 = System.nanoTime()
            val r = decoder.finish()
            val t2 = System.nanoTime()
            pointNs += t1 - t0
            points += g.count
            finishNs += t2 - t1
            if (t2 - t1 > maxFinishNs) maxFinishNs = t2 - t1
            val nw = words(r)
            if (nw.firstOrNull() == w) new1++ else if (misses.size < 12) misses.add("$w->${nw.take(3)}")
            if (w in nw.take(3)) new3++
        }
        println("GLIDE BENCH realistic isolated ($n most common words, no context)")
        println("GLIDE BENCH   whole-word decoder: top-1 ${pct(old1, n)}  top-3 ${pct(old3, n)}")
        println("GLIDE BENCH   streaming decoder:  top-1 ${pct(new1, n)}  top-3 ${pct(new3, n)}")
        println("GLIDE BENCH   streaming cost: %.3f ms per touch point, %.2f ms mean / %.2f ms max after lift".format(
            pointNs / 1e6 / points, finishNs / 1e6 / n, maxFinishNs / 1e6))
        println("GLIDE BENCH   sample misses: $misses")
        assertTrue("streaming decoder should not be worse than the whole-word decoder", new1 >= old1)
    }

    @Test
    fun contextSentences() {
        val rnd = Random(2)
        val decoder = StreamingGlideDecoder(language)
        val old = GlideDecoder(dictionary)
        var n = 0
        var old1 = 0
        var noCtx1 = 0
        var ctx1 = 0
        var ctx3 = 0
        for (sentence in heldOut.take(600)) {
            var prevWord: String? = null
            for (w in sentence) {
                if (glideable(w)) {
                    val g = sim.generate(w, rnd) ?: continue
                    n++
                    if (decodeOld(old, g).firstOrNull() == w) old1++
                    if (words(decodeNew(decoder, g, GlideContext(NgramModel.UNKNOWN))).firstOrNull() == w) noCtx1++
                    val c = words(decodeNew(decoder, g, GlideContext(contextOf(prevWord))))
                    if (c.firstOrNull() == w) ctx1++
                    if (w in c.take(3)) ctx3++
                }
                prevWord = w
            }
        }
        println("GLIDE BENCH held-out sentences ($n glided words, previous word known)")
        println("GLIDE BENCH   whole-word decoder:            top-1 ${pct(old1, n)}")
        println("GLIDE BENCH   streaming, no context:         top-1 ${pct(noCtx1, n)}")
        println("GLIDE BENCH   streaming, bigram context:     top-1 ${pct(ctx1, n)}  top-3 ${pct(ctx3, n)}")
        assertTrue("context should help", ctx1 >= noCtx1)
    }

    /** Result of gliding sentences word by word, each glide free to revise the recent glided words. */
    class RevisionStats(val words: Int, val rightWhenGlided: Int, val rightAtEnd: Int, val fixed: Int, val broken: Int)

    fun runRevisions(params: GlideParams, sentences: List<List<String>>, seed: Long): RevisionStats {
        val rnd = Random(seed)
        val decoder = StreamingGlideDecoder(language, params)
        var n = 0
        var whenGlided = 0
        var atEnd = 0
        var fixed = 0
        var broken = 0
        for (sentence in sentences) {
            val committed = arrayOfNulls<String>(sentence.size)
            val glided = BooleanArray(sentence.size)
            val history = ArrayList<GlideWord>()
            val historyPos = ArrayList<Int>()
            var anchor = NgramModel.SENTENCE_START
            for ((pos, w) in sentence.withIndex()) {
                val g = if (glideable(w)) sim.generate(w, rnd) else null
                if (g == null) {
                    // Typed words are fixed: they end the revisable run and become its context.
                    committed[pos] = w
                    history.clear()
                    historyPos.clear()
                    anchor = contextOf(w)
                    continue
                }
                glided[pos] = true
                n++
                val r = decodeNew(decoder, g, GlideContext(anchor, history.toList()))
                if (r == null) {
                    history.clear()
                    historyPos.clear()
                    anchor = NgramModel.UNKNOWN
                    continue
                }
                val got = dictionary.lower[r.words.last()]
                if (got == w) whenGlided++
                if (r.firstRevised >= 0) {
                    val off = history.size - r.history.size
                    for (h in r.history.indices) {
                        val entry = history[off + h]
                        if (r.history[h] == entry.word) continue
                        val p = historyPos[off + h]
                        val after = dictionary.lower[r.history[h]]
                        if (committed[p] != sentence[p] && after == sentence[p]) fixed++
                        if (committed[p] == sentence[p] && after != sentence[p]) broken++
                        committed[p] = after
                        history[off + h] = entry.revisedTo(r.history[h])
                    }
                }
                committed[pos] = got
                history.add(r.entries.last())
                historyPos.add(pos)
            }
            for (i in sentence.indices) if (glided[i] && committed[i] == sentence[i]) atEnd++
        }
        return RevisionStats(n, whenGlided, atEnd, fixed, broken)
    }

    @Test
    fun revisionSequences() {
        val s = runRevisions(GlideParams(), heldOut.drop(600).take(600), 3)
        println("GLIDE BENCH revision (sentences glided word by word; each glide may rewrite up to " +
            "${StreamingGlideDecoder.MAX_HISTORY} earlier glided words)")
        println("GLIDE BENCH   words right when glided: ${pct(s.rightWhenGlided, s.words)}   " +
            "at the end of the sentence: ${pct(s.rightAtEnd, s.words)}")
        println("GLIDE BENCH   rewrites that fixed a word: ${s.fixed}   that broke a right word: ${s.broken}")
        assertTrue("revision should fix more words than it breaks", s.fixed > s.broken)
        assertTrue("revision should not lower accuracy", s.rightAtEnd >= s.rightWhenGlided)
    }

    /**
     * One stroke of several words: after each word the finger travels down into the space bar (below its
     * middle, as the keyboard requires) and then up to the next word's first key. Points inside the space bar
     * are dropped, as KeyboardView drops them; the travel outside it is fed like any other point.
     */
    fun strokePhrase(decoder: StreamingGlideDecoder, gestures: List<GestureSimulator.Gesture>, ctx: GlideContext, rnd: Random): GlideResult? {
        val space = geometry.keys.first { it.action == dev.shebang.devboard.layout.KeyAction.SPACE }
        decoder.begin(layout, ctx, gestures[0].t[0], phrase = true)
        var offset = 0L
        var lastX = 0f
        var lastY = 0f
        var lastT = 0L
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, stopInSpace: Boolean): Long {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val steps = maxOf(1, (len / 8f).toInt())
            var t = t0
            for (s in 1..steps) {
                val x = x0 + (x1 - x0) * s / steps
                val y = y0 + (y1 - y0) * s / steps
                t += 8
                if (space.contains(x, y)) {
                    if (stopInSpace) return t
                    continue
                }
                decoder.addPoint(x, y, t)
            }
            return t
        }
        for ((k, g) in gestures.withIndex()) {
            val dipX = space.left + space.width * (0.2f + 0.6f * rnd.nextFloat())
            val dipY = space.centerY + space.height * 0.3f
            if (k > 0) {
                // Up from the dip to the word's first key.
                lastT = line(dipX, dipY, g.x[0], g.y[0], lastT + 150, stopInSpace = false)
                offset = lastT - g.t[0]
            }
            for (i in 0 until g.count) decoder.addPoint(g.x[i], g.y[i], g.t[i] + offset)
            lastX = g.x[g.count - 1]
            lastY = g.y[g.count - 1]
            lastT = g.t[g.count - 1] + offset
            if (k < gestures.size - 1) {
                // Down into the space bar: the word ends when the finger enters it.
                lastT = line(lastX, lastY, dipX, dipY, lastT, stopInSpace = true)
                decoder.boundary()
            }
        }
        return decoder.finish()
    }

    @Test
    fun phraseStrokes() {
        fun run(params: GlideParams): Triple<Int, Int, Int> {
            val rnd = Random(4)
            val decoder = StreamingGlideDecoder(language, params)
            var n = 0
            var joint = 0
            var separate = 0
            for (sentence in heldOut.drop(1200).take(600)) {
                // Runs of 2-4 glide-able words become one stroke.
                var i = 0
                var prevWord: String? = null
                while (i < sentence.size) {
                    if (!glideable(sentence[i])) {
                        prevWord = sentence[i]
                        i++
                        continue
                    }
                    var j = i
                    while (j < sentence.size && j - i < 4 && glideable(sentence[j])) j++
                    val run = sentence.subList(i, j)
                    if (run.size >= 2) {
                        val gestures = run.map { sim.generate(it, rnd) }
                        if (gestures.all { it != null }) {
                            val r = strokePhrase(decoder, gestures.map { it!! }, GlideContext(contextOf(prevWord)), rnd)
                            val got = r?.words?.map { dictionary.lower[it] } ?: emptyList()
                            var ctxWord = prevWord
                            for ((k, w) in run.withIndex()) {
                                n++
                                if (got.getOrNull(k) == w) joint++
                                val single = words(decodeNew(decoder, gestures[k]!!, GlideContext(contextOf(ctxWord))))
                                if (single.firstOrNull() == w) separate++
                                ctxWord = w
                            }
                        }
                    }
                    prevWord = sentence[j - 1]
                    i = j
                }
            }
            return Triple(n, joint, separate)
        }
        val (n, joint, separate) = run(GlideParams())
        val (_, naive, _) = run(GlideParams(leadInCost = 1e6f, leadOutCost = 1e6f))
        println("GLIDE BENCH phrase strokes (2-4 words per stroke with the travel to and from the space bar, $n words)")
        println("GLIDE BENCH   one stroke: ${pct(joint, n)}   same, travel treated as letters: ${pct(naive, n)}   " +
            "one stroke per word: ${pct(separate, n)}")
        assertTrue("approach handling should help", joint > naive)
    }

    /** The original harness: tier-10 words, ideal paths with Gaussian vertex jitter, no timing. */
    @Test
    fun originalHarnessTargets() {
        val rnd = Random(20240930)
        val tier10 = (0 until dictionary.size).filter {
            dictionary.tiers[it] == 10 && IdealPath.collapsedLength(dictionary.lower[it]) >= 2 &&
                dictionary.lower[it].all { c -> c in 'a'..'z' }
        }
        val words = tier10.shuffled(Random(7)).take(1000).map { dictionary.lower[it] }
        val decoder = StreamingGlideDecoder(language)
        var top1 = 0
        var top3 = 0
        for (w in words) {
            val (pts, count) = OriginalHarness.swipe(w, rnd, layout)
            decoder.begin(layout, GlideContext(NgramModel.UNKNOWN), 0L)
            // No timing in this model: points 8 ms apart.
            for (i in 0 until count) decoder.addPoint(pts[2 * i], pts[2 * i + 1], i * 8L)
            val r = words(decoder.finish())
            if (r.firstOrNull() == w) top1++
            if (w in r.take(3)) top3++
        }
        println("GLIDE BENCH original harness, streaming decoder: top-1 ${pct(top1, 1000)}  top-3 ${pct(top3, 1000)}")
        assertTrue("top-1 ${top1 / 10.0}% below 85%", top1 >= 850)
        assertTrue("top-3 ${top3 / 10.0}% below 95%", top3 >= 950)
    }
}

/** The original synthetic swipe model, shared with [GlideDecoderTest]. */
object OriginalHarness {
    fun swipe(word: String, rnd: Random, model: KeyLayoutModel, sigma: Float = 0.22f): Pair<FloatArray, Int> {
        val raw = FloatArray(2 * word.length)
        val n = IdealPath.points(word, model, raw)
        val kw = model.keyWidth
        for (i in 0 until n) {
            raw[2 * i] += (rnd.nextGaussian() * sigma * kw).toFloat()
            raw[2 * i + 1] += (rnd.nextGaussian() * sigma * model.keyHeight * 0.9).toFloat()
        }
        val out = FloatArray(2 * 512)
        var count = 0
        fun push(x: Float, y: Float) {
            if (count < 512) {
                out[2 * count] = x + (rnd.nextGaussian() * 0.04 * kw).toFloat()
                out[2 * count + 1] = y + (rnd.nextGaussian() * 0.04 * kw).toFloat()
                count++
            }
        }
        push(raw[0], raw[1])
        for (i in 1 until n) {
            val x0 = raw[2 * i - 2]
            val y0 = raw[2 * i - 1]
            val x1 = raw[2 * i]
            val y1 = raw[2 * i + 1]
            val len = kotlin.math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val steps = maxOf(2, (len / kw * 3).toInt())
            for (s in 1..steps) {
                if (rnd.nextFloat() < 0.08f) continue
                val t = s.toFloat() / steps
                val over = if (t > 0.85f && i < n - 1) 0.06f * kw else 0f
                push(x0 + (x1 - x0) * t + over * Math.signum(x1 - x0), y0 + (y1 - y0) * t)
            }
        }
        return out to count
    }
}
