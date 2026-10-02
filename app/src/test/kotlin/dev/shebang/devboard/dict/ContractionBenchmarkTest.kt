package dev.shebang.devboard.dict

import dev.shebang.devboard.glide.FutoData
import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.ime.GlideText
import org.junit.Test
import java.io.File

/**
 * "its" or "it's"? A word typed without its apostrophe where both readings are words ("its", "were",
 * "well", "ill", "cant"), decided from the words before, on sentences the language model never counted: the
 * held-out Tatoeba sentences, and the FUTO test split's sentences when FUTO_SWIPES names it. Every use of
 * either form is typed without the apostrophe; scored by how many come out as meant, how many contractions
 * are recovered, and how many words meant without an apostrophe get one wrongly (the costly mistake).
 * Prints "CONTRACTIONS" lines; CONTRACTIONS_SWEEP=1 tries more ratios.
 */
class ContractionBenchmarkTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm

    private fun sentences(): List<String> {
        val out = ArrayList<String>()
        File("src/test/resources/glide/heldout_sentences.tsv").readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .forEach { out += it.substringAfter('\t') }
        val futo = System.getenv("FUTO_SWIPES")?.let { File(it) }
        if (futo != null && futo.isFile) {
            FutoData.read(futo, System.getenv("FUTO_LIMIT")?.toIntOrNull() ?: 50_000).map { it.sentence }.distinct()
                .forEach { out += it }
        }
        return out
    }

    private class Case(val meant: String, val typed: String, val context: Suggester.Context)

    @Test
    fun contractionsFromTheWordsBefore() {
        // Contractions whose letters alone are also a word: the only ones where the apostrophe is in doubt.
        val contractions = (0 until dictionary.size).map { dictionary.lower[it] }
            .filter { '\'' in it && dictionary.indexOfLower(it.replace("'", "")) >= 0 }.toSet()
        val plain = contractions.associateBy { it.replace("'", "") }
        val cases = ArrayList<Case>()
        for (sentence in sentences()) {
            val tokens = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*").findAll(sentence).map { it.value.lowercase().replace('’', '\'') }.toList()
            for ((i, t) in tokens.withIndex()) {
                val typed = when {
                    t in contractions -> t.replace("'", "")
                    t in plain -> t
                    else -> continue
                }
                val before = tokens.take(i).joinToString(" ") + if (i > 0) " " else ""
                val w1 = GlideText.contextWord(before)
                val c1 = GlideText.contextId(w1, dictionary, lm)
                val w2 = GlideText.contextWord2(before)
                val c2 = if (w1 == GlideText.SENTENCE_START || w2.isEmpty()) dev.shebang.devboard.dict.NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
                cases += Case(t, typed, Suggester.Context(c2, c1))
            }
        }
        val s = Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm)
        val wantContraction = cases.count { '\'' in it.meant }
        val pct = GlideBenchmarkTest::pct
        println("CONTRACTIONS ${cases.size} uses: $wantContraction meant with the apostrophe, ${cases.size - wantContraction} without")
        val saved = Suggester.CONTRACTION_RATIO
        val ratios = if (System.getenv("CONTRACTIONS_SWEEP") != null) listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0) else listOf(saved)
        for (useContext in listOf(false, true)) for (r in ratios) {
            Suggester.CONTRACTION_RATIO = r
            var right = 0
            var recovered = 0
            var wronglyAdded = 0
            val byWord = HashMap<String, IntArray>()
            for (c in cases) {
                val ctx = if (useContext) c.context else null
                val out = (s.autocorrectFrom(c.typed, s.suggest(c.typed, Suggester.AUTOCORRECT_CANDIDATES, context = ctx), context = ctx) ?: c.typed).lowercase()
                val stat = byWord.getOrPut(c.typed) { IntArray(3) }
                if (out == c.meant) { right++; stat[0]++ }
                if ('\'' in c.meant && out == c.meant) recovered++
                if ('\'' !in c.meant && '\'' in out) { wronglyAdded++; stat[1]++ }
                stat[2]++
            }
            println("CONTRACTIONS ${if (useContext) "words before" else "word alone  "} ratio $r: as meant ${pct(right, cases.size)}, apostrophes recovered ${pct(recovered, wantContraction)}, added wrongly $wronglyAdded of ${cases.size - wantContraction}")
            if (useContext && r == saved) for ((w, st) in byWord.entries.sortedByDescending { it.value[2] }.take(12)) {
                println("CONTRACTIONS     $w: ${st[2]} uses, as meant ${pct(st[0], st[2])}, apostrophe added wrongly ${st[1]}")
            }
        }
        Suggester.CONTRACTION_RATIO = saved
    }
}
