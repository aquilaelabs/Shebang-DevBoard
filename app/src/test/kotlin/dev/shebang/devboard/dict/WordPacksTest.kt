package dev.shebang.devboard.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The word packs: how they merge, how much their words count, and how autocorrect treats them. */
class WordPacksTest {
    private val data: NgramData by lazy { File("src/main/assets/dict/en_ngrams.bin").inputStream().use { NgramData.load(it) } }

    @Test
    fun theListsMergeInSearchOrderWithEachWordsPack() {
        val all = BuiltInWords.all()
        // Each spelling once, in search order, so lookups by lowercase find the spelling to write first.
        for (i in 1 until all.size) {
            val c = all.lower[i - 1].compareTo(all.lower[i])
            assertTrue("${all.words[i - 1]} before ${all.words[i]}", c < 0 || (c == 0 && all.words[i - 1] != all.words[i]))
        }
        assertEquals(WordPacks.REGULAR, all.packs[all.indexOf("the")].toInt())
        assertEquals(WordPacks.DEV, all.packs[all.indexOf("sudo")].toInt())
        assertEquals(WordPacks.COMPUTER, all.packs[all.indexOf("hdmi")].toInt())
        assertEquals(WordPacks.NAMES, all.packs[all.indexOf("spotify")].toInt())
        // Both spellings of a word and a name stay ("wood" and "Wood"), the word first.
        val wood = all.indexOfLower("wood")
        assertEquals("wood", all.words[wood])
        assertEquals("Wood", all.words[wood + 1])
    }

    @Test
    fun turningAPackOffTakesOnlyItsWords() {
        val regular = BuiltInWords.with()
        assertFalse(regular.contains("sudo"))
        assertFalse(regular.contains("HDMI"))
        // Everyday words a pack also lists stay regular words.
        for (w in listOf("terminal", "kernel", "python", "login", "username", "Monday", "English", "Christmas")) assertTrue(w, regular.contains(w))
        val dev = BuiltInWords.with(WordPacks.DEV)
        assertTrue(dev.contains("sudo"))
        assertFalse(dev.contains("HDMI"))
    }

    @Test
    fun noWordIsInTwoLists() {
        val seen = HashMap<String, String>()
        for (f in listOf(WordPacks.REGULAR_ASSET) + WordPacks.builtIn.map { it.asset }) {
            File("src/main/assets/$f").forEachLine { line ->
                val w = line.substringBefore('\t')
                val before = seen.put(w, f)
                assertNull("$w is in $before and $f", before)
            }
        }
    }

    @Test
    fun aPacksWordIsValuedByTheModelAsARegularWordWouldBe() {
        // The owner's design (6 Oct): values come from the word model whatever list a word is in; no pack weights.
        val all = BuiltInWords.all()
        val flat = Dictionary(all.words, all.lower, all.tiers)
        val withPacks = NgramModel.build(all, data, null)
        val asRegular = NgramModel.build(flat, data, null)
        for (w in listOf("GPU", "sudo", "Spotify", "the")) {
            val i = all.indexOf(w)
            assertEquals(w, asRegular.unigramCost(i), withPacks.unigramCost(i), 1e-4f)
        }
    }

    @Test
    fun slangStartsBelowEveryOtherWord() {
        val all = BuiltInWords.all()
        val lm = NgramModel.build(all, data, null)
        val slang = (0 until all.size).filter { all.packs[it].toInt() == WordPacks.SLANG }
        val others = (0 until all.size).filter { all.packs[it].toInt() != WordPacks.SLANG }
        assertTrue("the slang pack is loaded", slang.size > 50)
        val cheapestSlang = slang.minOf { lm.unigramCost(it) }
        val dearestOther = others.maxOf { lm.unigramCost(it) }
        assertTrue("slang $cheapestSlang below every other word $dearestOther", cheapestSlang > dearestOther)
        // Typed as spelled, a slang word is kept, not corrected to a common word a slip away ("lol" is not "lot").
        val s = suggester(all)
        assertNull(s.autocorrect("lol"))
        assertNull(s.autocorrect("idk"))
        assertNull(s.autocorrect("tbh"))
    }

    private fun suggester(d: Dictionary): Suggester {
        val lm = NgramModel.build(d, data, null)
        return Suggester(d, null, FloatArray(d.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm)
    }

    @Test
    fun aPackWordTypedAsSpelledIsKept() {
        val s = suggester(BuiltInWords.all())
        // Written in capitals: typed in lowercase it takes them, instead of becoming the everyday word a slip away.
        assertEquals("CPU", s.autocorrect("cpu"))
        assertEquals("SSD", s.autocorrect("ssd"))
        assertEquals("DNS", s.autocorrect("dns"))
        // Spelled in lowercase: a known word, left alone.
        assertNull(s.autocorrect("git"))
        assertNull(s.autocorrect("grep"))
        // Without the packs those were corrected away.
        val regular = suggester(BuiltInWords.with())
        assertNotEquals("CPU", regular.autocorrect("cpu"))
        assertNotEquals(null, regular.autocorrect("git"))
        // A name typed in lowercase still competes with the words a slip away, as before.
        assertEquals("the", s.autocorrect("thw"))
    }
}
