package dev.shebang.devboard.ime

import dev.shebang.devboard.dict.NgramData
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.PersonalSnapshot
import dev.shebang.devboard.dict.PersonalWord
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.glide.GestureSimulator
import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.glide.GlideContext
import dev.shebang.devboard.glide.StreamingGlideDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/** The learned user dictionary: what is kept, how it is written, and how it reaches glide and suggestions. */
class PersonalLearningTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val inDictionary: (String) -> Boolean = { dictionary.indexOfLower(it) >= 0 }

    private fun tempFile(): File = Files.createTempDirectory("devboard").toFile().resolve(PersonalWords.FILE)

    private val data: NgramData by lazy { File("src/main/assets/dict/en_ngrams.bin").inputStream().use { NgramData.load(it) } }

    @Test
    fun aNewWordBecomesKnownOnItsSecondUse() {
        val p = PersonalWords(null)
        val v0 = p.vocabularyVersion
        p.learn("kubectl", null, false, inDictionary)
        assertFalse(p.list().single().known)
        assertEquals(v0, p.vocabularyVersion)
        p.learn("kubectl", "run", false, inDictionary)
        assertTrue(p.list().single().known)
        assertEquals(v0 + 1, p.vocabularyVersion)
    }

    @Test
    fun dictionaryWordsAreKnownAtOnceAndOnlyCounted() {
        val p = PersonalWords(null)
        val v0 = p.vocabularyVersion
        p.learn("held", null, true, inDictionary)
        assertTrue(p.list().single().known)
        assertEquals(v0, p.vocabularyVersion)
    }

    @Test
    fun casing() {
        assertEquals("GitHub", PersonalWords.displayForm("GitHub", true))
        assertEquals("NASA", PersonalWords.displayForm("NASA", false))
        assertEquals("Tokyo", PersonalWords.displayForm("Tokyo", false))
        assertEquals("tokyo", PersonalWords.displayForm("Tokyo", true))
        assertEquals("hello", PersonalWords.displayForm("hello", false))
        val p = PersonalWords(null)
        p.learn("Zorbly", null, true, inDictionary)
        // Seen mid-sentence with its capital: a name.
        p.learn("Zorbly", "met", false, inDictionary)
        assertEquals("Zorbly", p.list().single().display)
    }

    @Test
    fun onlyWordsAreLearned() {
        for (w in listOf("a", "x1", "abc123", "hunter2", "--", "http", "e-mail", "don't")) {
            val expected = w in setOf("http", "e-mail", "don't")
            assertEquals(w, expected, PersonalWords.isLearnable(w))
        }
        val p = PersonalWords(null)
        p.learn("p4ssw0rd", null, false, inDictionary)
        assertTrue(p.list().isEmpty())
    }

    @Test
    fun savesLoadsDeletesAndClears() {
        val f = tempFile()
        val p = PersonalWords(f)
        p.learn("kubectl", null, false, inDictionary)
        p.learn("kubectl", "run", false, inDictionary)
        p.learn("pods", "kubectl", false, inDictionary)
        p.save()
        val q = PersonalWords(f)
        assertEquals(p.list(), q.list())
        assertEquals(1, q.snapshot().pairs[PersonalSnapshot.pairKey("kubectl", "pods")])
        q.delete("kubectl")
        assertEquals(listOf("pods"), q.list().map { it.lower })
        // Pairs with a deleted word go with it.
        assertNull(q.snapshot().pairs[PersonalSnapshot.pairKey("kubectl", "pods")])
        q.clear()
        q.save()
        assertTrue(PersonalWords(f).list().isEmpty())
    }

    @Test
    fun evictsTheLeastUsedOnceFull() {
        var day = 0
        val p = PersonalWords(null) { day }
        repeat(20) { p.learn("keepme", null, false, inDictionary) }
        for (i in 0..PersonalWords.MAX_WORDS) {
            day = i / 500
            p.learn("zz" + i.toString(26).map { 'a' + it.digitToInt(26) }.joinToString(""), null, false, inDictionary)
        }
        val words = p.list()
        assertTrue(words.size <= PersonalWords.MAX_WORDS)
        assertTrue(words.any { it.lower == "keepme" })
    }

    @Test
    fun personalPairsAndCountsShiftTheModel() {
        val base = NgramModel.build(dictionary, data, null)
        fun idx(w: String) = dictionary.indexOfLower(w)
        val personal = PersonalSnapshot(
            listOf(PersonalWord("helm", "helm", 400, true), PersonalWord("take", "take", 400, true)),
            mapOf(PersonalSnapshot.pairKey("take", "helm") to 40),
            800,
        )
        val mine = NgramModel.build(dictionary, data, personal)
        assertTrue(mine.unigramCost(idx("helm")) < base.unigramCost(idx("helm")))
        val ctx = mine.contextOf(idx("take"))
        assertTrue(mine.cost(idx("helm"), ctx) < base.cost(idx("helm"), ctx))
        // Other words keep roughly what they had.
        assertEquals(base.unigramCost(idx("the")), mine.unigramCost(idx("the")), 0.5f)
    }

    @Test
    fun aLearnedWordBecomesGlidableAndSuggested() {
        val p = PersonalWords(null)
        repeat(2) { p.learn("kubectl", null, false, inDictionary) }
        val bundle = LanguageBuilder.build(dictionary, data, p.snapshot(), emptyList(), p.vocabularyVersion, p.countsVersion)
        val w = bundle.dictionary.indexOfLower("kubectl")
        assertTrue(w >= 0)
        assertEquals("kubectl", bundle.suggester.suggest("kubect", 3).first().word)

        val sim = GestureSimulator(GlideBenchmarkTest.layout)
        val decoder = StreamingGlideDecoder(bundle.glide)
        val rnd = Random(3)
        var ok = 0
        repeat(10) {
            val g = sim.generate("kubectl", rnd)!!
            decoder.begin(GlideBenchmarkTest.layout, GlideContext(NgramModel.UNKNOWN), g.t[0])
            for (i in 0 until g.count) decoder.addPoint(g.x[i], g.y[i], g.t[i])
            if (decoder.finish()?.words?.lastOrNull() == w) ok++
        }
        assertTrue("kubectl decoded $ok of 10", ok >= 8)
    }

    @Test
    fun systemDictionaryWordsJoinTheVocabulary() {
        val system = listOf(SystemUserDictionary.Word("Zorblax", 200), SystemUserDictionary.Word("x2", 250))
        val bundle = LanguageBuilder.build(dictionary, data, PersonalSnapshot.EMPTY, system, 0, 0)
        assertTrue(bundle.dictionary.indexOfLower("zorblax") >= 0)
        assertTrue(bundle.dictionary.indexOfLower("x2") < 0)
        assertEquals(1, bundle.systemWords)
    }
}
