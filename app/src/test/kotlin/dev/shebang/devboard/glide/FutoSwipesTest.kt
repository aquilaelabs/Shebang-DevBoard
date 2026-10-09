package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.ime.GlideText
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays real swipes from the FUTO swipe dataset (swipe.futo.org, MIT licence; see THIRD_PARTY_NOTICES.md),
 * each on the keyboard geometry it was swiped on, with the word before it in its sentence as context.
 * Skipped unless FUTO_SWIPES names a .jsonl file of it (for example the dataset's test.jsonl); the QWERTY
 * layout is read from FUTO_LAYOUT or qwerty.json beside it. FUTO_LIMIT caps the swipes read (default 5000);
 * FUTO_SWEEP=1 also compares decoder parameters.
 *
 *     FUTO_SWIPES=/path/test.jsonl ./gradlew testDebugUnitTest --tests '*FutoSwipesTest*' -i | grep FUTO
 *
 * The data is not in the repository: download it from https://huggingface.co/datasets/futo-org/swipe.futo.org
 */
class FutoSwipesTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm

    /** Swipes from [path] whose word is in the dictionary, and how many were skipped as out of vocabulary. */
    fun load(path: String? = System.getenv("FUTO_SWIPES"), limit: Int = System.getenv("FUTO_LIMIT")?.toIntOrNull() ?: 5000, sessions: MutableList<String>? = null): Pair<List<ReplayGlide>, Int>? {
        if (path == null) return null
        val file = File(path)
        if (!file.isFile) return null
        val layoutFile = System.getenv("FUTO_LAYOUT")?.let { File(it) } ?: File(file.parentFile, "qwerty.json")
        val out = ArrayList<ReplayGlide>()
        var outOfVocabulary = 0
        // FUTO_ONLY_WORDS: only swipes whose word is in that word list, to compare dictionaries on the same swipes.
        val only = System.getenv("FUTO_ONLY_WORDS")?.let { f -> File(f).readLines().map { it.substringBefore('\t').lowercase() }.toHashSet() }
        // Read more than asked for: words the test cannot use are skipped.
        for (r in FutoData.read(file, limit * 2, layoutFile)) {
            if (out.size >= limit) break
            if (r.word.length < 2 || !r.word.all { it.isLetter() || it == '\'' }) continue
            if (only != null && r.word !in only) continue
            if (dictionary.indexOfLower(r.word) < 0) {
                outOfVocabulary++
                continue
            }
            out += ReplayGlide(r.word, contextOf(r), context2Of(r), r.layout, r.x, r.y, r.t, if (r.sentence.isEmpty() || r.wordIdx < 0) "" else beforeOf(r))
            sessions?.add(r.session)
        }
        return out to outOfVocabulary
    }

    private fun beforeOf(r: FutoData.Record): String {
        val tokens = r.sentence.split(' ').filter { it.isNotEmpty() }
        return tokens.take(r.wordIdx).joinToString(" ") + if (r.wordIdx > 0) " " else ""
    }

    /** The word before the context word, for the trigram. */
    private fun context2Of(r: FutoData.Record): Int {
        if (r.sentence.isEmpty() || r.wordIdx < 0) return NgramModel.UNKNOWN
        val w2 = GlideText.contextWord2(beforeOf(r))
        return if (w2.isEmpty()) NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
    }

    /** The word before this one in its sentence, as the keyboard would read it from the field. */
    private fun contextOf(r: FutoData.Record): Int {
        if (r.sentence.isEmpty() || r.wordIdx < 0) return NgramModel.UNKNOWN
        val tokens = r.sentence.split(' ').filter { it.isNotEmpty() }
        val before = tokens.take(r.wordIdx).joinToString(" ") + if (r.wordIdx > 0) " " else ""
        return GlideText.contextId(GlideText.contextWord(before), dictionary, lm)
    }

    private class Score(var n: Int = 0, var top1: Int = 0, var top3: Int = 0, var empty: Int = 0)

    private fun run(swipes: List<ReplayGlide>, params: GlideParams, useContext: Boolean, confusions: MutableMap<String, Int>? = null, byLength: MutableMap<Int, Score>? = null): Score {
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, params)
        val s = Score()
        for (sw in swipes) {
            decoder.begin(sw.layout, if (useContext) GlideContext(sw.context, context2 = sw.context2, sentence = GlideText.sentenceWords(sw.before)) else GlideContext(NgramModel.UNKNOWN), sw.t[0])
            for (i in sw.x.indices) decoder.addPoint(sw.x[i], sw.y[i], sw.t[i])
            val r = decoder.finish()?.alternatives?.map { dictionary.lower[it] }.orEmpty()
            s.n++
            val bucket = byLength?.getOrPut(minOf(sw.word.length, 10)) { Score() }
            if (bucket != null) bucket.n++
            if (r.isEmpty()) s.empty++
            if (r.firstOrNull() == sw.word) {
                s.top1++
                if (bucket != null) bucket.top1++
            } else if (confusions != null && r.isNotEmpty()) {
                val k = "${sw.word} -> ${r[0]}"
                confusions[k] = (confusions[k] ?: 0) + 1
            }
            if (sw.word in r.take(3)) s.top3++
        }
        return s
    }

    /**
     * Two-letter words on real swipes, split by how far the stroke reached from where it came down (in key widths,
     * as the keyboard's diagnostics count it) and by how far apart the word's two keys are: whether neighbouring
     * pairs ("as", "we") and short strokes lose more, and what they are read as (R23).
     */
    @Test
    fun futoTwoLetterWords() {
        val loaded = load()
        assumeTrue("FUTO_SWIPES not set or not a file", loaded != null)
        val swipes = loaded!!.first.filter { it.word.length == 2 }
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, GlideParams())
        val byReach = sortedMapOf<Int, Score>()
        val byApart = sortedMapOf<Int, Score>()
        val confusions = HashMap<String, Int>()
        for (sw in swipes) {
            decoder.begin(sw.layout, GlideContext(sw.context, context2 = sw.context2, sentence = GlideText.sentenceWords(sw.before)), sw.t[0])
            for (i in sw.x.indices) decoder.addPoint(sw.x[i], sw.y[i], sw.t[i])
            val r = decoder.finish()?.alternatives?.map { dictionary.lower[it] }.orEmpty()
            val kw = sw.layout.keyWidth
            var reach = 0f
            for (i in sw.x.indices) reach = maxOf(reach, kotlin.math.hypot(sw.x[i] - sw.x[0], sw.y[i] - sw.y[0]))
            val a = sw.word[0]
            val b = sw.word[1]
            val apart = if (sw.layout.hasLetter(a) && sw.layout.hasLetter(b)) kotlin.math.hypot(sw.layout.x(a) - sw.layout.x(b), sw.layout.y(a) - sw.layout.y(b)) / kw else -1f
            val reachBand = when { reach < kw -> 0; reach < 2 * kw -> 1; reach < 4 * kw -> 2; else -> 3 }
            val apartBand = when { apart < 0f -> -1; apart < 0.01f -> 0; apart < 1.6f -> 1; apart < 3f -> 2; else -> 3 }
            val right = r.firstOrNull() == sw.word
            for ((map, band) in listOf(byReach to reachBand, byApart to apartBand)) {
                val sc = map.getOrPut(band) { Score() }
                sc.n++
                if (right) sc.top1++
                if (sw.word in r.take(3)) sc.top3++
            }
            if (!right && r.isNotEmpty()) confusions.merge("${sw.word} -> ${r[0]}", 1, Int::plus)
        }
        val pct = GlideBenchmarkTest::pct
        val reachNames = listOf("under 1 key", "1-2 keys", "2-4 keys", "4+ keys")
        val apartNames = mapOf(-1 to "no key", 0 to "same key", 1 to "neighbours", 2 to "1.6-3 keys", 3 to "3+ keys")
        println("FUTO2 two-letter swipes: ${swipes.size}")
        for ((band, sc) in byReach) println("FUTO2   reach ${reachNames[band].padEnd(12)} ${sc.n} swipes, top-1 ${pct(sc.top1, sc.n)}  top-3 ${pct(sc.top3, sc.n)}")
        for ((band, sc) in byApart) println("FUTO2   keys ${apartNames[band]!!.padEnd(12)} ${sc.n} swipes, top-1 ${pct(sc.top1, sc.n)}  top-3 ${pct(sc.top3, sc.n)}")
        for ((k, v) in confusions.entries.sortedByDescending { it.value }.take(25)) println("FUTO2   %4d  %s".format(v, k))
    }

    /**
     * The personal glide adaptation on real swipers: each FUTO session replayed in order with its own adaptation,
     * learning as the keyboard does (a glide read right teaches where its letters were touched; one read wrong is
     * corrected, its stroke re-aligned to the word meant), a day passing every 60 glides. Top-1 with and without
     * the adaptation on each session's later half, by word length: whether adapting costs short words (R23).
     */
    @Test
    fun futoAdaptationByLength() {
        val sessions = ArrayList<String>()
        val loaded = load(sessions = sessions)
        assumeTrue("FUTO_SWIPES not set or not a file", loaded != null)
        val swipes = loaded!!.first
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, GlideParams())
        fun read(sw: ReplayGlide, offsets: FloatArray?): GlideResult? {
            decoder.begin(sw.layout, GlideContext(sw.context, context2 = sw.context2, sentence = GlideText.sentenceWords(sw.before)), sw.t[0], offsets = offsets)
            for (i in sw.x.indices) decoder.addPoint(sw.x[i], sw.y[i], sw.t[i])
            return decoder.finish()
        }
        val plain = sortedMapOf<String, Score>()
        val adapted = sortedMapOf<String, Score>()
        fun bucket(w: String) = when (w.length) { 2 -> "2"; 3 -> "3"; 4 -> "4"; in 5..7 -> "5-7"; else -> "8+" }
        var sessionCount = 0
        for ((_, idx) in swipes.indices.groupBy { sessions[it] }) {
            if (idx.size < 40) continue
            sessionCount++
            val day = intArrayOf(0)
            val a = GlideAdaptation(null) { day[0] }
            for ((k, i) in idx.withIndex()) {
                if (k % 60 == 0) day[0]++
                val sw = swipes[i]
                val target = dictionary.indexOfLower(sw.word)
                val r = read(sw, a.offsets())
                val right = r != null && r.words.lastOrNull() == target
                if (k >= idx.size / 2) {
                    val p = read(sw, null)
                    for ((map, ok) in listOf(plain to (p != null && p.words.lastOrNull() == target), adapted to right)) {
                        val sc = map.getOrPut(bucket(sw.word)) { Score() }
                        sc.n++
                        if (ok) sc.top1++
                        val all = map.getOrPut("all") { Score() }
                        all.n++
                        if (ok) all.top1++
                    }
                }
                if (r == null) continue
                if (right) {
                    r.observations.lastOrNull()?.let { a.learn(it) }
                } else {
                    val stroke = r.strokes.lastOrNull() ?: continue
                    decoder.observeWord(sw.layout, a.offsets(), stroke, target)?.let { a.learnCorrection(it) }
                }
            }
        }
        val pct = GlideBenchmarkTest::pct
        println("FUTOADAPT sessions of 40+ swipes: $sessionCount, scored on each one's later half")
        for ((k, p) in plain) {
            val q = adapted[k]!!
            println("FUTOADAPT   ${k.padEnd(4)} ${p.n} swipes  top-1 ${pct(p.top1, p.n)} without, ${pct(q.top1, q.n)} adapted")
        }
    }

    @Test
    fun futoSwipes() {
        val loaded = load()
        assumeTrue("FUTO_SWIPES not set or not a file", loaded != null)
        val (swipes, oov) = loaded!!
        val pct = GlideBenchmarkTest::pct
        println("FUTO swipes: ${swipes.size} in the dictionary, $oov skipped as out of vocabulary")
        val confusions = HashMap<String, Int>()
        val byLength = sortedMapOf<Int, Score>()
        val t0 = System.nanoTime()
        val ctx = run(swipes, GlideParams(), true, confusions, byLength)
        val ms = (System.nanoTime() - t0) / 1e6 / swipes.size
        val noCtx = run(swipes, GlideParams(), false)
        println("FUTO with context:    top-1 ${pct(ctx.top1, ctx.n)}  top-3 ${pct(ctx.top3, ctx.n)}  empty ${ctx.empty}  (${"%.2f".format(ms)} ms per swipe)")
        println("FUTO without context: top-1 ${pct(noCtx.top1, noCtx.n)}  top-3 ${pct(noCtx.top3, noCtx.n)}")
        for ((len, s) in byLength) println("FUTO   length ${if (len == 10) "10+" else len}: ${s.n} swipes, top-1 ${pct(s.top1, s.n)}")
        println("FUTO most common confusions:")
        for ((k, v) in confusions.entries.sortedByDescending { it.value }.take(40)) println("FUTO   %4d  %s".format(v, k))

        if (System.getenv("FUTO_SWEEP") == null) return
        val base = GlideParams()
        val variants = linkedMapOf(
            "defaults" to base,
            "sigmaVertex 0.50" to base.copy(sigmaVertex = 0.50f),
            "sigmaVertex 0.36" to base.copy(sigmaVertex = 0.36f),
            "sigmaMid 0.55" to base.copy(sigmaMid = 0.55f),
            "sigmaMid 0.38" to base.copy(sigmaMid = 0.38f),
            "stayCost 0.5" to base.copy(stayCost = 0.5f),
            "skipCost 0.6" to base.copy(skipCost = 0.6f),
            "slowWeight 0" to base.copy(slowWeight = 0f),
            "slowWeight 2" to base.copy(slowWeight = 2f),
            "turnWeight 0.5" to base.copy(turnWeight = 0.5f),
            "lmWeight 0.7" to base.copy(lmWeight = 0.7f),
            "lmWeight 1.4" to base.copy(lmWeight = 1.4f),
            "beam wide" to base.copy(beamWidth = 20f, maxTokens = 12000),
        )
        for ((name, p) in variants) {
            val s = run(swipes, p, true)
            println("FUTO SWEEP ${name.padEnd(18)} top-1 ${pct(s.top1, s.n)}  top-3 ${pct(s.top3, s.n)}  empty ${s.empty}")
        }
    }

    /**
     * Writes each swipe with the decoder's candidates and their costs, one JSON object per line, for the glide
     * model's scripts (tools/glide_model/score.py re-ranks the candidates with the model's own reading of the
     * stroke). FUTO_DUMP names the file to write; FUTO_SWIPES and FUTO_LIMIT choose the swipes as for
     * [futoSwipes]. Points are in key pitches (u across, v down) with times in ms from the first.
     */
    @Test
    fun futoDump() {
        val out = System.getenv("FUTO_DUMP")
        assumeTrue("FUTO_DUMP not set", out != null)
        val loaded = load()
        assumeTrue("FUTO_SWIPES not set or not a file", loaded != null)
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language)
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        File(out!!).bufferedWriter().use { w ->
            for (sw in loaded!!.first) {
                decoder.begin(sw.layout, GlideContext(sw.context, context2 = sw.context2), sw.t[0])
                for (i in sw.x.indices) decoder.addPoint(sw.x[i], sw.y[i], sw.t[i])
                val r = decoder.finish()
                val entry = r?.entries?.lastOrNull()
                val sb = StringBuilder()
                sb.append("{\"word\":").append(q(sw.word))
                sb.append(",\"before\":").append(q(sw.before))
                sb.append(",\"top\":").append(q(r?.alternatives?.firstOrNull()?.let { dictionary.lower[it] } ?: ""))
                sb.append(",\"u\":[").append(sw.x.joinToString(",") { "%.4f".format(it / sw.layout.keyWidth) }).append("]")
                sb.append(",\"v\":[").append(sw.y.joinToString(",") { "%.4f".format(it / sw.layout.keyHeight) }).append("]")
                sb.append(",\"t\":[").append(sw.t.joinToString(",") { (it - sw.t[0]).toString() }).append("]")
                sb.append(",\"cands\":[")
                if (entry != null) {
                    sb.append(entry.candidates.indices.joinToString(",") { k ->
                        val c = entry.candidates[k]
                        "{\"w\":${q(dictionary.lower[c])},\"ac\":${"%.4f".format(entry.acoustic[k])},\"lm\":${"%.4f".format(lm.cost3(c, sw.context2, sw.context))},\"uni\":${"%.4f".format(lm.unigramCost(c))}}"
                    })
                }
                sb.append("]}")
                w.write(sb.toString())
                w.newLine()
            }
        }
    }

    /**
     * Coordinate descent over the decoder's parameters on the dataset's dev split (FUTO_TUNE names it),
     * scored by top-1 with context; the result is then checked on FUTO_SWIPES (the test split), which the
     * search never saw. FUTO_TUNE_LIMIT caps the dev swipes (default 4000).
     */
    @Test
    fun futoTune() {
        val devPath = System.getenv("FUTO_TUNE")
        assumeTrue("FUTO_TUNE not set", devPath != null)
        val dev = load(devPath, System.getenv("FUTO_TUNE_LIMIT")?.toIntOrNull() ?: 4000)!!.first
        val test = load()?.first
        val pct = GlideBenchmarkTest::pct
        fun score(p: GlideParams, set: List<ReplayGlide>) = run(set, p, true).let { it.top1.toDouble() / it.n }
        println("FUTO TUNE on ${dev.size} dev swipes")
        val best = GlideTuner.tune(GlideParams(), { score(it, dev) }, { println("FUTO TUNE $it") })
        println("FUTO TUNE best: $best")
        if (test != null) {
            val before = run(test, GlideParams(), true)
            val after = run(test, best, true)
            println("FUTO TUNE test split (${test.size} swipes): defaults top-1 ${pct(before.top1, before.n)} top-3 ${pct(before.top3, before.n)}; tuned top-1 ${pct(after.top1, after.n)} top-3 ${pct(after.top3, after.n)}")
            println("FUTO TUNE (check the simulator benchmark with these values too before adopting them)")
        }
    }

    /**
     * Trade-offs between how loosely strokes are matched: each setting scored on real swipes (FUTO_SWIPES),
     * on the simulator's strokes, and on ideal paths through the key centres. Run with FUTO_GRID=1.
     */
    @Test
    fun futoGrid() {
        assumeTrue("FUTO_GRID not set", System.getenv("FUTO_GRID") != null)
        val real = load()!!.first
        val sim = GestureSimulator(GlideBenchmarkTest.layout)
        val simWords = GlideBenchmarkTest.commonWords.shuffled(java.util.Random(11)).take(1000)
        val ideal = listOf("hello", "world", "keyboard", "terminal", "quick", "you", "the", "there", "three", "house", "off", "our", "too", "does")
        fun simTop1(p: GlideParams): Double {
            val d = StreamingGlideDecoder(GlideBenchmarkTest.language, p)
            val rnd = java.util.Random(11)
            var n = 0
            var ok = 0
            for (w in simWords) {
                val g = sim.generate(w, rnd) ?: continue
                d.begin(GlideBenchmarkTest.layout, GlideContext(NgramModel.UNKNOWN), g.t[0])
                for (i in 0 until g.count) d.addPoint(g.x[i], g.y[i], g.t[i])
                n++
                if (d.finish()?.alternatives?.firstOrNull()?.let { dictionary.lower[it] } == w) ok++
            }
            return 100.0 * ok / n
        }
        fun idealMisses(p: GlideParams): List<String> {
            val d = StreamingGlideDecoder(GlideBenchmarkTest.language, p)
            val layout = GlideBenchmarkTest.layout
            return ideal.filter { w ->
                d.begin(layout, GlideContext(NgramModel.UNKNOWN), 0L)
                var t = 0L
                var px = Float.NaN
                var py = Float.NaN
                for (ch in w) {
                    val k = ch - 'a'
                    val x = layout.centerX[k]
                    val y = layout.centerY[k]
                    if (px.isNaN()) d.addPoint(x, y, t) else {
                        val steps = maxOf(1, (kotlin.math.hypot(x - px, y - py) / 10f).toInt())
                        for (s in 1..steps) {
                            t += 10
                            d.addPoint(px + (x - px) * s / steps, py + (y - py) * s / steps, t)
                        }
                    }
                    px = x
                    py = y
                }
                d.finish()?.alternatives?.firstOrNull()?.let { dictionary.lower[it] } != w
            }
        }
        val tuned = GlideParams()
        val grid = linkedMapOf(
            "old" to GlideParams(sigmaVertex = 0.42f, sigmaMid = 0.45f, stayCost = 0.8f, skipCost = 0.35f, endCost = 0.8f, turnWeight = 0f, vertexBias = 0.5f, lmWeight = 1.0f),
            "tuned" to tuned,
            "vertex .6 mid .8" to tuned.copy(sigmaVertex = 0.6f, sigmaMid = 0.8f),
            "vertex .6 mid 1.1" to tuned.copy(sigmaVertex = 0.6f),
            "vertex .7 mid .9" to tuned.copy(sigmaVertex = 0.7f, sigmaMid = 0.9f),
            "vertex .84 mid .8" to tuned.copy(sigmaMid = 0.8f),
            "tuned, end 1.2" to tuned.copy(endCost = 1.2f),
            "tuned, lm 1.0" to tuned.copy(lmWeight = 1.0f),
            "vertex .6 mid .8 end 1.2" to tuned.copy(sigmaVertex = 0.6f, sigmaMid = 0.8f, endCost = 1.2f),
        )
        for ((name, p) in grid) {
            val r = run(real, p, true)
            println("FUTO GRID ${name.padEnd(26)} real top-1 ${GlideBenchmarkTest.pct(r.top1, r.n)} top-3 ${GlideBenchmarkTest.pct(r.top3, r.n)}   simulated ${"%.1f".format(simTop1(p))}%   ideal misses ${idealMisses(p)}")
        }
    }
}
