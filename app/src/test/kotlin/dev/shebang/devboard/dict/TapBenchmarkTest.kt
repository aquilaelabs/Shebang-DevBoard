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
    class Word(val meant: String, val typed: String, val xs: FloatArray, val ys: FloatArray, val before1: String? = null, val before2: String? = null, val person: String = "", val task: Int = 0)

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
            File(dir, "touch_data.csv").bufferedReader().useLines { lines ->
                for (l in lines.drop(1)) {
                    val f = fields(l)
                    val trial = "${f[0]}/${f[1]}/${f[2]}"
                    if (trial !in prompts) continue
                    val at = f[5].toInt()
                    taps.getOrPut(trial) { HashMap() }.putIfAbsent(at, f[6].toFloat() to f[7].toFloat())
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
                            parts[0], parts[1].removePrefix("task").toIntOrNull() ?: 0)
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
     * lean, and the nearest after the lean and each person's own offsets, learned as they type (from words
     * that came out right, through the same adaptation as the phone's, one day per task block).
     */
    @Test
    fun keysOnRealTaps() {
        val dir = System.getenv("TSI_DIR")?.let { File(it) }
        assumeTrue("TSI_DIR not set", dir != null && File(dir, "touch_data.csv").isFile)
        val layout = layout(dir!!)
        val words = words(dir, layout)
        val lean = TapModel(layout, TSI_DENSITY)
        var letters = 0
        val right = IntArray(3)
        val wordsRight = IntArray(3)
        for ((person, theirs) in words.groupBy { it.person }) {
            var day = 0
            val adaptation = dev.shebang.devboard.glide.GlideAdaptation(null) { day }
            for (w in theirs) {
                day = w.task
                val own = TapModel(layout, TSI_DENSITY, adaptation.offsets())
                val reads = arrayOf(
                    w.typed,
                    String(CharArray(w.meant.length) { lean.nearestLetter(w.xs[it], w.ys[it]) ?: '?' }),
                    String(CharArray(w.meant.length) { own.nearestLetter(w.xs[it], w.ys[it]) ?: '?' }),
                )
                letters += w.meant.length
                for (k in 0..2) {
                    right[k] += w.meant.indices.count { reads[k][it] == w.meant[it] }
                    if (reads[k] == w.meant) wordsRight[k]++
                }
                // The phone learns from words that came out right and that it knows.
                if (reads[2] == w.meant && dictionary.contains(w.meant)) {
                    val obs = w.meant.indices.flatMap { i -> own.observation(w.xs[i], w.ys[i], w.meant[i])!!.toList() }.toFloatArray()
                    adaptation.learn(obs)
                }
            }
            if (System.getenv("TAPS_PEOPLE") != null) {
                val o = adaptation.offsets()
                println("TAPS   $person learned lean: across %+.2f, down %+.2f (key pitches, mean of letters)".format(o.take(26).average(), o.drop(26).average()))
            }
        }
        val pct = GlideBenchmarkTest::pct
        val names = listOf("key under the finger (before)", "nearest after the overall lean", "nearest after lean and own offsets")
        for (k in 0..2) println("TAPS KEYS ${names[k].padEnd(36)} letters ${pct(right[k], letters)}, words typed right ${pct(wordsRight[k], words.size)}")
    }
}
