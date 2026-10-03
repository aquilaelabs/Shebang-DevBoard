package dev.shebang.devboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Crash reports keep where the code failed and nothing of what it was handling. */
class CrashLogTest {
    private val dir = Files.createTempDirectory("crashes").toFile()
    private val file = File(dir, CrashLog.FILE)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun failing(text: String): Throwable =
        runCatching { throw IllegalStateException("could not read $text", NumberFormatException("For input string: \"$text\"")) }.exceptionOrNull()!!

    @Test
    fun messagesAreDroppedAndFramesKept() {
        CrashLog.record(file, "0.5.0", failing("my secret password"))
        val c = CrashLog.read(file).single()
        val text = c.trace.joinToString("\n")
        assertFalse(text.contains("secret"))
        assertEquals("java.lang.IllegalStateException", c.trace.first())
        assertTrue(c.trace.any { it == "Caused by: java.lang.NumberFormatException" })
        assertTrue(c.trace.any { it.contains("CrashLogTest.failing") })
        assertFalse(file.readText().contains("secret"))
    }

    @Test
    fun theSameCrashIsCountedAndAtMostFiveAreKept() {
        val e = failing("x")
        CrashLog.record(file, "0.5.0", e)
        CrashLog.record(file, "0.5.0", e)
        assertEquals(2, CrashLog.read(file).single().count)
        for (i in 0 until 7) CrashLog.record(file, "0.5.$i", e)
        assertEquals(CrashLog.MAX_CRASHES, CrashLog.read(file).size)
        assertEquals("0.5.6", CrashLog.read(file).first().version)
    }
}
