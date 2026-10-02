package dev.shebang.devboard.dict

import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.glide.KeyLayoutModel
import dev.shebang.devboard.glide.TapModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Autocorrect on real taps: the TSI dataset's copy-typed English phrases (CC BY 4.0; 16 people, Pixel 6
 * Pro), each word typed as the keys nearest its taps (each letter's first tap, before any deletion), then
 * corrected from the strip's candidates with and without the taps' positions. Skipped unless TSI_DIR names
 * a folder with the dataset's touch_data.csv, prompt_data.csv and keyboard_data.json.
 *
 *     TSI_DIR=/path/tsi ./gradlew testDebugUnitTest --tests '*TapBenchmarkTest*' -i | grep TAPS
 */
class TapBenchmarkTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val suggester by lazy {
        val lm = GlideBenchmarkTest.lm
        Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm)
    }

    /** A phrase word: what was meant, the keys nearest its taps, the taps, and the two words meant before it. */
    class Word(
        val meant: String, val typed: String, val xs: FloatArray, val ys: FloatArray, val before1: String? = null, val before2: String? = null,
        val person: String = "", val task: Int = 0,
        /** TSI's own language model's odds for a..z at each tap (null where it has none). */
        val lmScores: List<FloatArray?> = emptyList(),
    )

    companion object {
        /** TSI's Pixel 6 Pro: 1440 px across 411 dp. */
        const val TSI_DENSITY = 3.5f

        fun layout(dir: File): KeyLayoutModel {
            val keys = Json.parseToJsonElement(File(dir, "keyboard_data.json").readText()).jsonObject["keys_info"]!!.jsonObject
            val cx = FloatArray(26) { Float.NaN }
            val cy = FloatArray(26) { Float.NaN }
            for (c in 'a'..'z') keys[c.toString()]?.jsonObject?.let {
                cx[c - 'a'] = it["key_center_x"]!!.jsonPrimitive.float
                cy[c - 'a'] = it["key_center_y"]!!.jsonPrimitive.float
            }
            return KeyLayoutModel(cx, cy, 135f, 206f, 1)
        }

        /** Splits a CSV line, honouring quotes. */
        private fun fields(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var q = false
            for (ch in line) when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            out += sb.toString()
            return out
        }

        /** The phrase words with their first taps, typed as the nearest keys. */
        fun words(dir: File, layout: KeyLayoutModel): List<Word> {
            val prompts = HashMap<String, String>()
            File(dir, "prompt_data.csv").readLines().drop(1).forEach { l ->
                val f = fields(l)
                if (f[3] == "phrase") prompts["${f[0]}/${f[1]}/${f[2]}"] = f[4]
            }
            // First tap per character of each phrase trial.
            val taps = HashMap<String, HashMap<Int, Pair<Float, Float>>>()
            val scores = HashMap<String, HashMap<Int, FloatArray?>>()
            File(dir, "touch_data.csv").bufferedReader().useLines { lines ->
                for (l in lines.drop(1)) {
                    val f = fields(l)
                    val trial = "${f[0]}/${f[1]}/${f[2]}"
                    if (trial !in prompts) continue
                    val at = f[5].toInt()
                    taps.getOrPut(trial) { HashMap() }.putIfAbsent(at, f[6].toFloat() to f[7].toFloat())
                    val lm = f[14].trim().removePrefix("[").removeSuffix("]").split(',').mapNotNull { it.trim().toFloatOrNull() }
                    scores.getOrPut(trial) { HashMap() }.putIfAbsent(at, if (lm.size >= 26) FloatArray(26) { lm[it] } else null)
                }
            }
            fun nearest(x: Float, y: Float): Char = ('a'..'z').minBy { c ->
                val i = c - 'a'
                if (layout.centerX[i].isNaN()) Float.MAX_VALUE else (x - layout.centerX[i]).let { it * it } + (y - layout.centerY[i]).let { it * it }
            }
            val out = ArrayList<Word>()
            // In the order each person typed them.
            for ((trial, prompt) in prompts.entries.sortedBy { e -> e.key.split('/').let { "${it[0]}/${it[1]}/" + it[2].padStart(3, '0') } }.map { it.key to it.value }) {
                val t = taps[trial] ?: continue
                var i = 0
                while (i < prompt.length) {
                    if (!prompt[i].isLetter()) { i++; continue }
                    var j = i
                    while (j < prompt.length && prompt[j].isLetter()) j++
                    val pts = (i until j).map { t[it] }
                    if (pts.all { it != null }) {
                        val xs = FloatArray(j - i) { pts[it]!!.first }
                        val ys = FloatArray(j - i) { pts[it]!!.second }
                        val typed = String(CharArray(j - i) { nearest(xs[it], ys[it]) })
                        val prev = Regex("[A-Za-z]+").findAll(prompt.substring(0, i)).map { it.value.lowercase() }.toList()
                        // A sentence start counts as no word before (null); a phrase is one sentence.
                        val parts = trial.split('/')
                        out += Word(prompt.substring(i, j).lowercase(), typed, xs, ys, prev.getOrNull(prev.size - 1), prev.getOrNull(prev.size - 2),
                            parts[0], parts[1].removePrefix("task").toIntOrNull() ?: 0, (i until j).map { scores[trial]?.get(it) })
                    }
                    i = j
                }
            }
            return out
        }
    }

    private fun ctxId(w: String?): Int = when (w) {
        null -> NgramModel.SENTENCE_START
        else -> dictionary.indexOfLower(w).let { if (it < 0) NgramModel.UNKNOWN else GlideBenchmarkTest.lm.contextOf(it) }
    }

    private fun contextOf(w: Word) = Suggester.Context(if (w.before1 == null) NgramModel.UNKNOWN else ctxId(w.before2), ctxId(w.before1))

    @Test
    fun autocorrectOnRealTaps() {
        val dir = System.getenv("TSI_DIR")?.let { File(it) }
        assumeTrue("TSI_DIR not set", dir != null && File(dir, "touch_data.csv").isFile)
        val layout = layout(dir!!)
        val model = TapModel(layout, TSI_DENSITY)
        val words = words(dir, layout).filter { it.meant.length >= 2 && dictionary.contains(it.meant) }
        val typos = words.filter { it.typed != it.meant }
        val right = words.filter { it.typed == it.meant }
        println("TAPS ${words.size} phrase words: ${typos.size} typed wrong (${GlideBenchmarkTest.pct(typos.size, words.size)}), ${right.size} right")
        val realWord = typos.count { dictionary.contains(it.typed) }
        val notOffered = typos.count { w -> !dictionary.contains(w.typed) && suggester.suggest(w.typed, Suggester.AUTOCORRECT_CANDIDATES).none { it.word.equals(w.meant, ignoreCase = true) } }
        println("TAPS   of the typos: ${realWord} are themselves words, ${notOffered} more lack the word meant among the candidates")
        val weights = if (System.getenv("AUTOCORRECT_SWEEP") != null) listOf(0f, 0.5f, 0.75f, 1f) else listOf(Suggester.CONTEXT_WEIGHT)
        for ((useTaps, useContext, weight) in listOf(Triple(false, false, 0f), Triple(true, false, 0f)) + weights.map { Triple(true, true, it) }) {
            Suggester.CONTEXT_WEIGHT = weight
            var fixed = 0
            var wrong = 0
            var left = 0
            for (w in typos) {
                val taps = if (useTaps) SlipCost.Taps { i, a, b -> model.cost(w.xs[i], w.ys[i], a, b) } else null
                val ctx = if (useContext) contextOf(w) else null
                val fix = suggester.autocorrectFrom(w.typed, suggester.suggest(w.typed, Suggester.AUTOCORRECT_CANDIDATES, taps, ctx), taps, ctx)
                when {
                    fix == null -> left++
                    fix.equals(w.meant, ignoreCase = true) -> fixed++
                    else -> wrong++
                }
            }
            var changed = 0
            for (w in right) {
                val taps = if (useTaps) SlipCost.Taps { i, a, b -> model.cost(w.xs[i], w.ys[i], a, b) } else null
                val ctx = if (useContext) contextOf(w) else null
                if (suggester.autocorrectFrom(w.typed, suggester.suggest(w.typed, Suggester.AUTOCORRECT_CANDIDATES, taps, ctx), taps, ctx) != null) changed++
            }
            val pct = GlideBenchmarkTest::pct
            val label = when {
                useContext -> "taps and words before (weight $weight):"
                useTaps -> "with tap positions:  "
                else -> "keys only (before):  "
            }
            println("TAPS $label typos fixed ${pct(fixed, typos.size)}, made another word ${pct(wrong, typos.size)}, left ${pct(left, typos.size)}; right words changed ${pct(changed, right.size)}")
        }
        Suggester.CONTEXT_WEIGHT = 0.75f
    }

    /**
     * Which key a tap hits, on the same phrase words: the key under the finger, the nearest after the overall
     * lean, the nearest after the lean and each person's own offsets (learned as they type, from words that
     * came out right, one day per task block), and that with the next letter's odds: from word frequency
     * alone, with the words before, and TSI's own language model's (for reference).
     */
    @Test
    fun keysOnRealTaps() {
        val dir = System.getenv("TSI_DIR")?.let { File(it) }
        assumeTrue("TSI_DIR not set", dir != null && File(dir, "touch_data.csv").isFile)
        val layout = layout(dir!!)
        val words = words(dir, layout)
        val lm = GlideBenchmarkTest.lm
        val lean = TapModel(layout, TSI_DENSITY)
        val uniPrior = LetterPrior(dictionary) { kotlin.math.exp(-lm.unigramCost(it).toDouble()) }
        val weights = if (System.getenv("AUTOCORRECT_SWEEP") != null) listOf(0.5, 1.0, 1.5, 2.0) else listOf(TapModel.PRIOR_WEIGHT)
        val names = mutableListOf("key under the finger (before)", "nearest after the overall lean", "lean and own offsets (R14)")
        for (k in weights) names += listOf("+ next letter by frequency, weight $k", "+ next letter with the words before, $k", "+ TSI's own letter odds, weight $k")
        val right = IntArray(names.size)
        val wordsRight = IntArray(names.size)
        // Words the dictionary lacks (names, odd spellings): the letter odds must not bend them.
        val unknownRight = IntArray(names.size)
        var unknown = 0
        var letters = 0
        val t0 = System.nanoTime()
        var priors = 0
        for ((_, theirs) in words.groupBy { it.person }) {
            var day = 0
            val adaptation = dev.shebang.devboard.glide.GlideAdaptation(null) { day }
            for (w in theirs) {
                day = w.task
                val own = TapModel(layout, TSI_DENSITY, adaptation.offsets())
                val c1 = w.before1?.let { dictionary.indexOfLower(it) }?.let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) } ?: NgramModel.SENTENCE_START
                val c2 = if (w.before1 == null) NgramModel.UNKNOWN else w.before2?.let { dictionary.indexOfLower(it) }?.let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) } ?: NgramModel.SENTENCE_START
                val ctxPrior = LetterPrior(dictionary) { kotlin.math.exp(-(0.75 * lm.cost3(it, c2, c1) + 0.25 * lm.unigramCost(it)).toDouble()) }
                fun decode(prior: ((String, Int) -> FloatArray?)?, weight: Double): String {
                    val sb = StringBuilder()
                    for (i in w.meant.indices) {
                        val p = prior?.invoke(sb.toString(), i)
                        sb.append(own.nearestLetter(w.xs[i], w.ys[i], prior = p, priorWeight = weight) ?: '?')
                    }
                    return sb.toString()
                }
                val reads = mutableListOf(
                    w.typed,
                    String(CharArray(w.meant.length) { lean.nearestLetter(w.xs[it], w.ys[it]) ?: '?' }),
                    decode(null, 0.0),
                )
                for (k in weights) {
                    reads += decode({ pre, _ -> priors++; uniPrior.next(pre) }, k)
                    reads += decode({ pre, _ -> ctxPrior.next(pre) }, k)
                    reads += decode({ _, i -> w.lmScores.getOrNull(i) }, k)
                }
                letters += w.meant.length
                val known = dictionary.contains(w.meant)
                if (!known) unknown++
                for (v in reads.indices) {
                    right[v] += w.meant.indices.count { reads[v][it] == w.meant[it] }
                    if (reads[v] == w.meant) {
                        wordsRight[v]++
                        if (!known) unknownRight[v]++
                    }
                }
                if (reads[2] == w.meant && dictionary.contains(w.meant)) {
                    val obs = w.meant.indices.flatMap { i -> own.observation(w.xs[i], w.ys[i], w.meant[i])!!.toList() }.toFloatArray()
                    adaptation.learn(obs)
                }
            }
        }
        val pct = GlideBenchmarkTest::pct
        for (v in names.indices) println("TAPS KEYS ${names[v].padEnd(44)} letters ${pct(right[v], letters)}, words typed right ${pct(wordsRight[v], words.size)}; of $unknown not in the dictionary ${pct(unknownRight[v], unknown)}")
        println("TAPS KEYS frequency prior: %.2f ms per letter".format((System.nanoTime() - t0) / 1e6 / maxOf(1, priors) / 4))
    }

    /** Autocorrect's slip weight on real taps with their positions and the words before. TAPS_SWEEP=1. */
    @Test
    fun slipWeightOnRealTaps() {
        assumeTrue(System.getenv("TAPS_SWEEP") != null)
        val savedSlipWeight = Suggester.SLIP_WEIGHT
        val dir = File(System.getenv("TSI_DIR") ?: return)
        val layout = layout(dir)
        val model = TapModel(layout, TSI_DENSITY)
        val words = words(dir, layout).filter { it.meant.length >= 2 && dictionary.contains(it.meant) }
        val typos = words.filter { it.typed != it.meant }
        val right = words.filter { it.typed == it.meant }
        for (k in listOf(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0)) {
            Suggester.SLIP_WEIGHT = k
            var fixed = 0
            var wrong = 0
            for (w in typos) {
                val taps = SlipCost.Taps { i, a, b -> model.cost(w.xs[i], w.ys[i], a, b) }
                val ctx = contextOf(w)
                val fix = suggester.autocorrectFrom(w.typed, suggester.suggest(w.typed, Suggester.AUTOCORRECT_CANDIDATES, taps, ctx), taps, ctx)
                if (fix.equals(w.meant, ignoreCase = true)) fixed++ else if (fix != null) wrong++
            }
            var changed = 0
            for (w in right) {
                val taps = SlipCost.Taps { i, a, b -> model.cost(w.xs[i], w.ys[i], a, b) }
                val ctx = contextOf(w)
                if (suggester.autocorrectFrom(w.typed, suggester.suggest(w.typed, Suggester.AUTOCORRECT_CANDIDATES, taps, ctx), taps, ctx) != null) changed++
            }
            val pct = GlideBenchmarkTest::pct
            println("TAPS SWEEP slip weight $k: typos fixed ${pct(fixed, typos.size)}, another word ${pct(wrong, typos.size)}, right changed ${pct(changed, right.size)}")
        }
        Suggester.SLIP_WEIGHT = savedSlipWeight
    }

    /** The letter odds' floor and weight for key resolution, with learned offsets. TAPS_SWEEP=1. */
    @Test
    fun letterOddsSweep() {
        assumeTrue(System.getenv("TAPS_SWEEP") != null)
        val savedFloor = LetterPrior.FLOOR
        val dir = File(System.getenv("TSI_DIR") ?: return)
        val layout = layout(dir)
        val words = words(dir, layout)
        val lm = GlideBenchmarkTest.lm
        for (floor in listOf(0.01f, 0.02f, 0.05f, 0.1f, 0.2f)) for (weight in listOf(0.75, 1.0, 1.25)) {
            LetterPrior.FLOOR = floor
            var letters = 0
            var right = 0
            var wordsRight = 0
            var unknown = 0
            var unknownRight = 0
            for ((_, theirs) in words.groupBy { it.person }) {
                var day = 0
                val adaptation = dev.shebang.devboard.glide.GlideAdaptation(null) { day }
                for (w in theirs) {
                    day = w.task
                    val own = TapModel(layout, TSI_DENSITY, adaptation.offsets())
                    val c1 = w.before1?.let { dictionary.indexOfLower(it) }?.let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) } ?: NgramModel.SENTENCE_START
                    val c2 = if (w.before1 == null) NgramModel.UNKNOWN else w.before2?.let { dictionary.indexOfLower(it) }?.let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) } ?: NgramModel.SENTENCE_START
                    val prior = LetterPrior(dictionary) { kotlin.math.exp(-(0.75 * lm.cost3(it, c2, c1) + 0.25 * lm.unigramCost(it)).toDouble()) }
                    val sb = StringBuilder()
                    for (i in w.meant.indices) sb.append(own.nearestLetter(w.xs[i], w.ys[i], prior = prior.next(sb.toString()), priorWeight = weight) ?: '?')
                    val read = sb.toString()
                    letters += w.meant.length
                    right += w.meant.indices.count { read[it] == w.meant[it] }
                    val known = dictionary.contains(w.meant)
                    if (!known) unknown++
                    if (read == w.meant) {
                        wordsRight++
                        if (!known) unknownRight++
                    }
                    // Learn from what the keys alone (with offsets) got right, as in keysOnRealTaps.
                    val plain = String(CharArray(w.meant.length) { own.nearestLetter(w.xs[it], w.ys[it]) ?: '?' })
                    if (plain == w.meant && known) adaptation.learn(w.meant.indices.flatMap { i -> own.observation(w.xs[i], w.ys[i], w.meant[i])!!.toList() }.toFloatArray())
                }
            }
            val pct = GlideBenchmarkTest::pct
            println("TAPS SWEEP floor $floor weight $weight: letters ${pct(right, letters)}, words ${pct(wordsRight, words.size)}, not in the dictionary ${pct(unknownRight, unknown)}")
        }
        LetterPrior.FLOOR = savedFloor
    }

    /**
     * Space or letter, on every phrase tap that landed on the bottom letter row or the space bar: by the drawn
     * edges (before), by where taps meant for each land, and with the word's odds of ending there (from the
     * words before and the letters typed so far, as the keyboard has them). A tap on the bar always stays a
     * space. TAPS_SWEEP=1 also varies the space's odds.
     */
    @Test
    fun spaceOnRealTaps() {
        val dir = System.getenv("TSI_DIR")?.let { File(it) }
        assumeTrue("TSI_DIR not set", dir != null && File(dir, "touch_data.csv").isFile)
        val layout = layout(dir!!)
        val keys = Json.parseToJsonElement(File(dir, "keyboard_data.json").readText()).jsonObject["keys_info"]!!.jsonObject
        val rects = keys.entries.associate { (k, v) ->
            val o = v.jsonObject
            val cx = o["key_center_x"]!!.jsonPrimitive.float
            val cy = o["key_center_y"]!!.jsonPrimitive.float
            val w = o["key_width"]!!.jsonPrimitive.float / 2
            val h = o["key_height"]!!.jsonPrimitive.float / 2
            k to floatArrayOf(cx - w, cy - h, cx + w, cy + h)
        }
        fun hit(x: Float, y: Float) = rects.entries.firstOrNull { (_, r) -> x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3] }?.key
        val sr = rects["SPACE"]!!
        val bar = TapModel.Bar(sr[0], sr[2], (sr[1] + sr[3]) / 2)
        val prompts = HashMap<String, String>()
        File(dir, "prompt_data.csv").readLines().drop(1).forEach { l ->
            val f = fields(l)
            if (f[3] == "phrase") prompts["${f[0]}/${f[1]}/${f[2]}"] = f[4]
        }
        class Tap(val meant: Char, val x: Float, val y: Float, val hit: String, val prefix: String, val c1: Int, val c2: Int)
        val lm = GlideBenchmarkTest.lm
        fun ctx(w: String?) = if (w == null) NgramModel.SENTENCE_START else dictionary.indexOfLower(w).let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) }
        val first = HashMap<String, HashMap<Int, Pair<Float, Float>>>()
        File(dir, "touch_data.csv").bufferedReader().useLines { lines ->
            for (l in lines.drop(1)) {
                val f = fields(l)
                val trial = "${f[0]}/${f[1]}/${f[2]}"
                if (trial in prompts) first.getOrPut(trial) { HashMap() }.putIfAbsent(f[5].toInt(), f[6].toFloat() to f[7].toFloat())
            }
        }
        val taps = ArrayList<Tap>()
        val bottom = "zxcvbnm"
        for ((trial, prompt) in prompts) {
            val t = first[trial] ?: continue
            for ((i, ch) in prompt.withIndex()) {
                val meant = if (ch == ' ') ' ' else if (ch.isLetter()) ch.lowercaseChar() else continue
                val (x, y) = t[i] ?: continue
                val h = hit(x, y) ?: continue
                if (h != "SPACE" && (h.length != 1 || h[0] !in bottom)) continue
                var b = i
                while (b > 0 && prompt[b - 1].isLetter()) b--
                val prefix = prompt.substring(b, i).lowercase()
                val before = Regex("[A-Za-z]+").findAll(prompt.substring(0, b)).map { it.value.lowercase() }.toList()
                val c1 = ctx(before.getOrNull(before.size - 1))
                val c2 = if (before.isEmpty()) NgramModel.UNKNOWN else ctx(before.getOrNull(before.size - 2))
                taps += Tap(meant, x, y, h, prefix, c1, c2)
            }
        }
        var model = TapModel(layout, TSI_DENSITY)
        val priors = HashMap<String, FloatArray?>()
        fun prior(tp: Tap): FloatArray? = priors.getOrPut("${tp.c2}/${tp.c1}/${tp.prefix}") {
            LetterPrior(dictionary) { kotlin.math.exp(-(0.75 * lm.cost3(it, tp.c2, tp.c1) + 0.25 * lm.unigramCost(it)).toDouble()) }.next(tp.prefix)
        }
        val spaces = taps.count { it.meant == ' ' }
        val letters = taps.size - spaces
        val pct = GlideBenchmarkTest::pct
        println("TAPS SPACE ${taps.size} taps on the bottom row or the bar: $spaces meant for space, $letters for letters")
        fun score(label: String, decide: (Tap) -> Boolean) {
            var spaceRight = 0
            var letterWrong = 0
            for (tp in taps) {
                val space = decide(tp)
                if (tp.meant == ' ' && space) spaceRight++
                if (tp.meant != ' ' && space) letterWrong++
            }
            println("TAPS SPACE ${label.padEnd(40)} spaces typed ${pct(spaceRight, spaces)}, letters made spaces ${pct(letterWrong, letters)} ($letterWrong); wrong ${spaces - spaceRight + letterWrong}")
        }
        fun resolved(tp: Tap, p: FloatArray?) = tp.hit == "SPACE" || model.meansSpace(tp.x, tp.y, model.nearestLetter(tp.x, tp.y, prior = p) ?: tp.hit[0], bar, p)
        score("drawn edges (before)") { it.hit == "SPACE" }
        score("where taps land") { resolved(it, null) }
        score("+ the word's odds of ending") { resolved(it, prior(it)) }
        if (System.getenv("TAPS_SWEEP") == null) return
        val saved = Triple(TapModel.SPACE_BASE, TapModel.SPACE_FLOOR, TapModel.SPACE_LEAN_Y_DP)
        for (base in listOf(0.1, 0.18, 0.3)) for (floor in listOf(0.005, 0.02, 0.05, 0.1)) {
            TapModel.SPACE_BASE = base
            TapModel.SPACE_FLOOR = floor
            score("sweep base $base floor $floor") { resolved(it, prior(it)) }
        }
        TapModel.SPACE_BASE = saved.first
        TapModel.SPACE_FLOOR = saved.second
        val savedSigma = TapModel.SPACE_SIGMA_Y_DP
        for (lean in listOf(-8f, -12.3f, -16f)) for (sigma in listOf(11f, 13.8f, 17f)) {
            TapModel.SPACE_LEAN_Y_DP = lean
            TapModel.SPACE_SIGMA_Y_DP = sigma
            model = TapModel(layout, TSI_DENSITY)
            score("sweep lean $lean sigma $sigma") { resolved(it, prior(it)) }
        }
        TapModel.SPACE_LEAN_Y_DP = saved.third
        TapModel.SPACE_SIGMA_Y_DP = savedSigma
    }
}
