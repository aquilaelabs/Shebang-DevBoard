package dev.shebang.devboard.glide

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Letters turning into spaces stops for someone who keeps taking those spaces back. */
class SpaceHabitTest {
    private val dir = Files.createTempDirectory("habit").toFile()
    private val file = File(dir, SpaceHabit.FILE)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun keptSpacesLeaveItOn() {
        val h = SpaceHabit(file)
        repeat(40) { h.record(undone = it % 25 == 0) }
        assertTrue(h.enabled())
    }

    @Test
    fun spacesTakenBackTurnItOffAndItStaysOffUntilReset() {
        val h = SpaceHabit(file)
        repeat(6) { h.record(undone = false) }
        repeat(3) { h.record(undone = true) }
        assertTrue("three, faded, are not yet enough", h.enabled())
        assertFalse(h.record(undone = true))
        h.save()
        assertFalse(SpaceHabit(file).enabled())
        h.reset()
        assertTrue(SpaceHabit(file).enabled())
        assertEquals(0f, SpaceHabit(file).summary().first)
    }
}
