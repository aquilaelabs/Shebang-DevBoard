package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel

/** A glide to replay: the word meant, the words before it, the keyboard it was made on and its points. */
class ReplayGlide(
    val word: String, val context: Int, val context2: Int, val layout: KeyLayoutModel, val x: FloatArray, val y: FloatArray, val t: LongArray,
    /** The words of its sentence before it, as typed (for the dumps the model scripts read). */
    val before: String = "",
)

/**
 * Replays glides through the decoder and searches its parameters (coordinate descent, one parameter at a
 * time, shrinking steps). Shared by the FUTO tuning and the tuning on the user's own recorded glides.
 */
object GlideTuner {
    class Score(var n: Int = 0, var top1: Int = 0, var top3: Int = 0, var empty: Int = 0) {
        val rate: Double get() = if (n == 0) 0.0 else top1.toDouble() / n
    }

    fun run(glides: List<ReplayGlide>, params: GlideParams, useContext: Boolean = true): Score {
        val dictionary = GlideBenchmarkTest.dictionary
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, params)
        val s = Score()
        for (g in glides) {
            decoder.begin(g.layout, if (useContext) GlideContext(g.context, context2 = g.context2) else GlideContext(NgramModel.UNKNOWN), g.t[0])
            for (i in g.x.indices) decoder.addPoint(g.x[i], g.y[i], g.t[i])
            val r = decoder.finish()?.alternatives?.map { dictionary.lower[it] }.orEmpty()
            s.n++
            if (r.isEmpty()) s.empty++
            if (r.firstOrNull() == g.word) s.top1++
            if (g.word in r.take(3)) s.top3++
        }
        return s
    }

    class Knob(val name: String, val get: (GlideParams) -> Float, val set: (GlideParams, Float) -> GlideParams)

    val knobs = listOf(
        Knob("sigmaVertex", { it.sigmaVertex }, { p, v -> p.copy(sigmaVertex = v) }),
        Knob("sigmaMid", { it.sigmaMid }, { p, v -> p.copy(sigmaMid = v) }),
        Knob("sigmaStart", { it.sigmaStart }, { p, v -> p.copy(sigmaStart = v) }),
        Knob("startRadius", { it.startRadius }, { p, v -> p.copy(startRadius = v) }),
        Knob("stayCost", { it.stayCost }, { p, v -> p.copy(stayCost = v) }),
        Knob("skipCost", { it.skipCost }, { p, v -> p.copy(skipCost = v) }),
        Knob("endCost", { it.endCost }, { p, v -> p.copy(endCost = v) }),
        Knob("slowWeight", { it.slowWeight }, { p, v -> p.copy(slowWeight = v) }),
        Knob("vertexBias", { it.vertexBias }, { p, v -> p.copy(vertexBias = v) }),
        Knob("turnWeight", { it.turnWeight }, { p, v -> p.copy(turnWeight = v) }),
        Knob("turnMidWeight", { it.turnMidWeight }, { p, v -> p.copy(turnMidWeight = v) }),
        Knob("lmWeight", { it.lmWeight }, { p, v -> p.copy(lmWeight = v) }),
        Knob("lookaheadWeight", { it.lookaheadWeight }, { p, v -> p.copy(lookaheadWeight = v) }),
    )

    /**
     * Coordinate descent from [start], keeping a change only when [score] (higher is better) rises by more
     * than [minGain]; [knobs] limits the parameters searched. Logs each change it keeps.
     */
    fun tune(
        start: GlideParams,
        score: (GlideParams) -> Double,
        log: (String) -> Unit,
        knobs: List<Knob> = this.knobs,
        rounds: Int = 3,
        minGain: Double = 0.0005,
    ): GlideParams {
        var best = start
        var bestScore = score(best)
        log("start: %.2f%%".format(100 * bestScore))
        var step = 0.5f
        repeat(rounds) { round ->
            for (k in knobs) {
                val cur = k.get(best)
                val tries = if (cur == 0f) listOf(0.25f, 0.5f) else listOf(cur * (1 - step), cur * (1 + step), cur * (1 + 2 * step))
                for (v in tries) {
                    val p = k.set(best, v)
                    val sc = score(p)
                    if (sc > bestScore + minGain) {
                        bestScore = sc
                        best = p
                        log("round ${round + 1}: ${k.name} ${"%.3f".format(cur)} -> ${"%.3f".format(v)}  %.2f%%".format(100 * sc))
                    }
                }
            }
            step /= 2
        }
        return best
    }
}
