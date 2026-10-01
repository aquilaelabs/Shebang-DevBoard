package dev.shebang.devboard.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine end to end on a device: the shipped model transcribes a known recording (whisper.cpp's sample
 * of President Kennedy's 1961 inaugural address, a US government work in the public domain).
 */
@RunWith(AndroidJUnit4::class)
class WhisperTest {
    @Test
    fun transcribesAKnownRecording() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val samples = Wav.read16kMono(testAssets.open("jfk.wav").use { it.readBytes() })
        val whisper = Whisper.load(Models.file(target).path)
        assertNotNull("model did not load", whisper)
        val t0 = System.nanoTime()
        val text = whisper!!.use { it.transcribe(samples) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("ShebangVoice", "transcribed %.1f s of speech in %d ms: %s".format(samples.size / 16000.0, ms, text))
        val words = text.lowercase().replace(Regex("[^a-z ]"), " ").split(Regex(" +")).toSet()
        for (w in listOf("fellow", "americans", "ask", "not", "what", "your", "country", "can", "do", "for", "you")) {
            assertTrue("'$w' missing from: $text", w in words)
        }
    }
}
