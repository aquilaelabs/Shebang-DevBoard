package dev.shebang.devboard.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideTraceTest {
    @Test
    fun roundTripsAndRebuildsTheLayout() {
        val cx = List<Float?>(26) { if (it == 1) null else it * 10f }
        val cy = List<Float?>(26) { if (it == 1) null else 5f }
        val t = GlideTrace(word = "hello", keyWidth = 40f, keyHeight = 60f, centerX = cx, centerY = cy,
            x = listOf(1f, 2f), y = listOf(3f, 4f), t = listOf(0L, 16L), density = 2.625f, device = "test")
        val back = GlideTrace.parseLine(t.toJsonLine())
        assertEquals(t, back)
        val layout = back.layout()
        assertTrue(layout.centerX[1].isNaN())
        assertEquals(20f, layout.centerX[2], 0f)
    }
}
