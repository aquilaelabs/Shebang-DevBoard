package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * Diagnostics and parameter sweeps for the streaming decoder. Skipped unless GLIDE_TUNE is set:
 *
 *     GLIDE_TUNE=1 ./gradlew testDebugUnitTest --tests '*GlideTuningTest*' -i | grep GLIDE
 *
 * Sweeps run on the realistic simulator, so they tune to its assumptions: confirm any change on recorded
 * glides (see [RecordedGlidesTest]) before trusting it.
 */
class GlideTuningTest {
    private val sim = GestureSimulator(GlideBenchmarkTest.layout)
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val language get() = GlideBenchmarkTest.language

    private fun top1(params: GlideParams, words: List<String>, seed: Long): Pair<Int, Int> {
        val rnd = Random(seed)
        val decoder = StreamingGlideDecoder(language, params)
        var n = 0
        var ok = 0
        for (w in words) {
            val g = sim.generate(w, rnd) ?: continue
            n++
            decoder.begin(GlideBenchmarkTest.layout, GlideContext(NgramModel.UNKNOWN), g.t[0])
            for (i in 0 until g.count) decoder.addPoint(g.x[i], g.y[i], g.t[i])
            val r = decoder.finish()
            if (r != null && dictionary.lower[r.alternatives[0]] == w) ok++
        }
        return ok to n
    }

    @Test
    fun diagnose() {
        assumeTrue(System.getenv("GLIDE_TUNE") != null)
        val words = GlideBenchmarkTest.commonWords
        val base = GlideParams()
        fun report(name: String, p: GlideParams) {
            val (ok, n) = top1(p, words, 1)
            println("GLIDE TUNE %-44s top-1 %.1f%%".format(name, 100.0 * ok / n))
        }
        report("default", base)
        report("wide beam (search errors removed)", GlideParams(beamWidth = 30f, maxTokens = 20000, lookaheadWeight = 0f))
        report("no timing (slow=0, turn=0)", GlideParams(slowWeight = 0f, turnWeight = 0f))
        report("no turning", GlideParams(turnWeight = 0f))
        report("no slowness", GlideParams(slowWeight = 0f))
        report("no word frequency (lm weight 0)", GlideParams(lmWeight = 0f, lookaheadWeight = 0f))
    }

    /** Candidate configurations on 2,000 strokes (two seeds) and 400 held-out sentences with context. */
    @Test
    fun compare() {
        assumeTrue(System.getenv("GLIDE_TUNE") != null)
        val configs = linkedMapOf(
            "current defaults" to GlideParams(),
            "turn 0" to GlideParams(turnWeight = 0f),
            "turn 0, stay .8, skip .35" to GlideParams(turnWeight = 0f, stayCost = 0.8f, skipCost = 0.35f),
            "turn mid-only 0.5" to GlideParams(turnWeight = 0f, turnMidWeight = 0.5f),
            "turn mid-only 1.0" to GlideParams(turnWeight = 0f, turnMidWeight = 1f),
            "turn 0, slow 1.5, bias .75" to GlideParams(turnWeight = 0f, slowWeight = 1.5f, vertexBias = 0.75f),
            "turn 0, no timing" to GlideParams(turnWeight = 0f, slowWeight = 0f, vertexBias = 0f),
        )
        val words = GlideBenchmarkTest.commonWords
        for ((name, p) in configs) {
            val (a, n1) = top1(p, words, 11)
            val (b, n2) = top1(p, words, 12)
            val (c, n3, empty) = contextTop1(p, 400)
            println("GLIDE TUNE %-30s isolated %.1f%%   sentences %.1f%%   empty %d".format(
                name, 100.0 * (a + b) / (n1 + n2), 100.0 * c / n3, empty))
        }
    }

    private fun contextTop1(params: GlideParams, sentences: Int): Triple<Int, Int, Int> {
        val rnd = Random(21)
        val decoder = StreamingGlideDecoder(language, params)
        val lm = GlideBenchmarkTest.lm
        var n = 0
        var ok = 0
        var empty = 0
        for (sentence in GlideBenchmarkTest.heldOut.takeLast(sentences)) {
            var prevWord: String? = null
            for (w in sentence) {
                val g = sim.generate(w, rnd)
                if (g != null) {
                    val ctx = prevWord?.let { pw -> dictionary.indexOfLower(pw).let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) } }
                        ?: NgramModel.SENTENCE_START
                    decoder.begin(GlideBenchmarkTest.layout, GlideContext(ctx), g.t[0])
                    for (i in 0 until g.count) decoder.addPoint(g.x[i], g.y[i], g.t[i])
                    val r = decoder.finish()
                    n++
                    if (r == null) empty++ else if (dictionary.lower[r.words.last()] == w) ok++
                }
                prevWord = w
            }
        }
        return Triple(ok, n, empty)
    }

    /** Revision margin: how much better the rewritten reading must be. */
    @Test
    fun reviseMargin() {
        assumeTrue(System.getenv("GLIDE_TUNE") != null)
        val bench = GlideBenchmarkTest()
        val sentences = GlideBenchmarkTest.heldOut.take(600)
        for (m in listOf(0f, 0.5f, 1f, 2f, 3f, 5f)) {
            val s = bench.runRevisions(GlideParams(reviseMargin = m), sentences, 31)
            println("GLIDE TUNE margin %.1f  when glided %.1f%%  at end %.1f%%  fixed %d  broken %d".format(
                m, 100.0 * s.rightWhenGlided / s.words, 100.0 * s.rightAtEnd / s.words, s.fixed, s.broken))
        }
    }

    /** Phrase gliding: costs of the travel to and from the space bar. */
    @Test
    fun leadCosts() {
        assumeTrue(System.getenv("GLIDE_TUNE") != null)
        val bench = GlideBenchmarkTest()
        val sentences = GlideBenchmarkTest.heldOut.drop(1800).take(300)
        val costs = listOf(0f, 0.02f, 0.05f, 0.1f)
        for (lin in costs) {
            val line = costs.joinToString("  ") { lout ->
                val rnd = Random(41)
                val decoder = StreamingGlideDecoder(language, GlideParams(leadInCost = lin, leadOutCost = lout))
                var n = 0
                var ok = 0
                for (sentence in sentences) {
                    val words = sentence.filter { bench.glideable(it) }
                    for (start in words.indices step 3) {
                        val run = words.subList(start, minOf(words.size, start + 3))
                        if (run.size < 2) continue
                        val gs = run.map { bench.sim.generate(it, rnd) }
                        if (gs.any { it == null }) continue
                        val r = bench.strokePhrase(decoder, gs.map { it!! }, GlideContext(NgramModel.UNKNOWN), rnd)
                        val got = r?.words?.map { dictionary.lower[it] }.orEmpty()
                        for ((k, w) in run.withIndex()) {
                            n++
                            if (got.getOrNull(k) == w) ok++
                        }
                    }
                }
                "out %.2f=%.1f%%".format(lout, 100.0 * ok / n)
            }
            println("GLIDE TUNE lead-in %.2f  %s".format(lin, line))
        }
    }

    @Test
    fun sweep() {
        assumeTrue(System.getenv("GLIDE_TUNE") != null)
        val words = GlideBenchmarkTest.commonWords.shuffled(Random(9)).take(500)
        val grid = mapOf<String, (Float) -> GlideParams>(
            "sigmaVertex" to { v -> GlideParams(sigmaVertex = v) },
            "sigmaMid" to { v -> GlideParams(sigmaMid = v) },
            "stayCost" to { v -> GlideParams(stayCost = v) },
            "skipCost" to { v -> GlideParams(skipCost = v) },
            "slowWeight" to { v -> GlideParams(slowWeight = v) },
            "turnWeight" to { v -> GlideParams(turnWeight = v) },
            "vertexBias" to { v -> GlideParams(vertexBias = v) },
            "lmWeight" to { v -> GlideParams(lmWeight = v) },
            "beamWidth" to { v -> GlideParams(beamWidth = v) },
        )
        val values = mapOf(
            "sigmaVertex" to listOf(0.32f, 0.38f, 0.42f, 0.48f, 0.55f),
            "sigmaMid" to listOf(0.35f, 0.40f, 0.45f, 0.52f, 0.60f),
            "stayCost" to listOf(0.2f, 0.35f, 0.5f, 0.8f),
            "skipCost" to listOf(0.2f, 0.35f, 0.5f, 0.8f),
            "slowWeight" to listOf(0f, 0.5f, 1f, 1.5f, 2f),
            "turnWeight" to listOf(0f, 0.5f, 1f, 1.5f),
            "vertexBias" to listOf(0f, 0.25f, 0.5f, 1f),
            "lmWeight" to listOf(0.5f, 0.75f, 1f, 1.5f, 2f),
            "beamWidth" to listOf(6f, 8f, 10f, 14f),
        )
        for ((name, make) in grid) {
            val line = values.getValue(name).joinToString("  ") { v ->
                val (ok, n) = top1(make(v), words, 5)
                "%s=%.1f%%".format(v, 100.0 * ok / n)
            }
            println("GLIDE TUNE %-12s %s".format(name, line))
        }
    }
}
