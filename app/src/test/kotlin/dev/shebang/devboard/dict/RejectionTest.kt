package dev.shebang.devboard.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Words moving down where they were rejected (the owner's design, 6 Oct): mostly after the word they followed,
 * a little everywhere once rejected after several words; fading with time, capped, and taken back by use.
 */
class RejectionTest {
    private var day = 20_000
    private fun store(file: File? = null) = PersonalWords(file) { day }
    private fun key(prev: String, word: String) = PersonalSnapshot.pairKey(prev, word)

    @Test
    fun aRejectionIsKeptForTheWordAfterTheWordBeforeIt() {
        val p = store()
        p.reject("lot", "a")
        p.reject("lot", "a")
        val r = p.snapshot().rejections
        assertEquals(2.0, r[key("a", "lot")]!!, 1e-9)
        assertNull(r[key("the", "lot")])
    }

    @Test
    fun rejectionsFadeAreCappedAndAreTakenBackByUse() {
        val p = store()
        repeat(9) { p.reject("lot", "a") }
        assertEquals("capped", PersonalWords.REJECT_CAP, p.snapshot().rejections[key("a", "lot")]!!, 1e-9)
        day += PersonalWords.REJECT_HALF_LIFE_DAYS.toInt()
        assertEquals("half after a half-life", PersonalWords.REJECT_CAP / 2, p.snapshot().rejections[key("a", "lot")]!!, 1e-6)
        // Writing "a lot" takes one back each time.
        p.learn("lot", "a", false) { true }
        assertEquals(PersonalWords.REJECT_CAP / 2 - 1, p.snapshot().rejections[key("a", "lot")]!!, 1e-6)
        repeat(3) { p.learn("lot", "a", false) { true } }
        assertNull("used enough, no longer rejected", p.snapshot().rejections[key("a", "lot")])
    }

    @Test
    fun rejectionsAreSavedAndGoWithDeleteAndClear() {
        val f = Files.createTempDirectory("devboard").toFile().resolve(PersonalWords.FILE)
        val p = store(f)
        p.reject("lot", "a")
        p.reject("ducking", "what")
        p.save()
        val again = store(f)
        assertEquals(setOf(key("a", "lot"), key("what", "ducking")), again.snapshot().rejections.keys)
        again.delete("ducking")
        assertEquals(setOf(key("a", "lot")), again.snapshot().rejections.keys)
        again.clear()
        assertTrue(again.snapshot().rejections.isEmpty())
    }

    @Test
    fun theWordModelLowersARejectedWordMostlyWhereItWasRejected() {
        val d = BuiltInWords.all()
        val data = File("src/main/assets/dict/en_ngrams.bin").inputStream().use { NgramData.load(it) }
        val lot = d.indexOf("lot")
        val a = d.indexOf("a")
        val the = d.indexOf("the")
        val plain = NgramModel.build(d, data, null)
        fun withRejections(vararg r: Pair<String, Double>) =
            NgramModel.build(d, data, PersonalSnapshot(emptyList(), emptyMap(), 0, mapOf(*r)))

        // Rejected twice after "a": costs ln(3) more after "a", and nothing more after "the" or with no context.
        val after = withRejections(key("a", "lot") to 2.0)
        assertEquals(kotlin.math.ln(3.0).toFloat(), after.cost(lot, after.contextOf(a)) - plain.cost(lot, plain.contextOf(a)), 1e-4f)
        assertEquals(plain.cost(lot, plain.contextOf(the)), after.cost(lot, after.contextOf(the)), 1e-5f)
        assertEquals(plain.unigramCost(lot), after.unigramCost(lot), 1e-5f)

        // Rejected after four different words: a little lower everywhere too (three or more contexts).
        val everywhere = withRejections(key("a", "lot") to 1.0, key("the", "lot") to 1.0, key("this", "lot") to 1.0, key("that", "lot") to 1.0)
        val drop = everywhere.unigramCost(lot) - plain.unigramCost(lot)
        assertEquals(-kotlin.math.ln(NgramModel.wordWideRejection(4)).toFloat(), drop, 1e-4f)
        assertTrue("never below half", NgramModel.wordWideRejection(100) >= 0.5)
        assertEquals(1.0, NgramModel.wordWideRejection(2), 0.0)
    }
}
