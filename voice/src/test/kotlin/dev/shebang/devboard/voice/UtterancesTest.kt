package dev.shebang.devboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Cutting audio at pauses: speech-like bursts in room noise, a cough, a long run-on. */
class UtterancesTest {
    private val rnd = java.util.Random(1)

    private fun noise(ms: Int, level: Float = 0.002f) = FloatArray(16 * ms) { (rnd.nextGaussian() * level).toFloat() }
    private fun voice(ms: Int) = FloatArray(16 * ms) { (0.2 * sin(2 * PI * 180 * it / 16000.0) * (0.6 + 0.4 * sin(2 * PI * 4 * it / 16000.0))).toFloat() }

    private fun run(vararg parts: FloatArray, pauseMs: Int = 700): List<Int> {
        val out = ArrayList<Int>()
        val u = Utterances(pauseMs) { out += it.size / 16 }
        val all = parts.reduce { a, b -> a + b }
        var i = 0
        while (i + Utterances.FRAME <= all.size) {
            u.feed(all.copyOfRange(i, i + Utterances.FRAME))
            i += Utterances.FRAME
        }
        u.flush()
        return out
    }

    @Test
    fun twoSentencesWithAPauseBetweenAreTwoUtterances() {
        val got = run(noise(1000), voice(1500), noise(1200), voice(2000), noise(1500))
        assertEquals(2, got.size)
        // Each keeps the lead-in and the pause that ended it.
        assertTrue(got[0] in 1500..2700)
    }

    @Test
    fun aShortBreathDoesNotSplitASentence() {
        assertEquals(1, run(noise(500), voice(800), noise(300), voice(800), noise(1500)).size)
    }

    @Test
    fun aCoughIsNotAnUtterance() {
        assertEquals(0, run(noise(500), voice(90), noise(1500)).size)
    }

    @Test
    fun silenceIsNothing() {
        assertEquals(0, run(noise(5000)).size)
    }

    @Test
    fun aRunOnIsCutAtWhispersWindow() {
        val got = run(noise(300), voice(32_000), noise(1000))
        assertEquals(2, got.size)
        assertTrue(got[0] <= 25_100)
    }

    @Test
    fun speechStillInProgressAtStopIsKept() {
        assertEquals(1, run(noise(300), voice(1200)).size)
    }
}
