package dev.shebang.devboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardGeometryTest {
    private val text = LayoutParser.parse(File("src/main/assets/layouts/text_qwerty.json").readText())
    private val code = LayoutParser.parse(File("src/main/assets/layouts/code.json").readText())

    private fun geometry(layout: LayoutDef, variant: FieldVariant = FieldVariant.PLAIN, numberRow: Boolean = false) =
        KeyboardGeometry(layout, variant, 1000, 400, numberRow, 4f, 6f, 1)

    @Test
    fun rowsSpanTheFullWidthInEveryVariant() {
        for (variant in listOf(FieldVariant.PLAIN, FieldVariant.EMAIL, FieldVariant.URL)) {
            val g = geometry(text, variant)
            for (row in 0 until g.rowCount) {
                val keys = g.keys.filter { it.row == row }
                val left = keys.minOf { it.left } - 2f
                val right = keys.maxOf { it.right } + 2f
                if (row == 1) {
                    // Home row is indented half a key on each side.
                    assertEquals(50f, left, 1f)
                    assertEquals(950f, right, 1f)
                } else {
                    assertEquals(0f, left, 1f)
                    assertEquals(1000f, right, 1f)
                }
            }
        }
    }

    @Test
    fun spaceAbsorbsTheDifferenceWhenEmailKeysAppear() {
        val plainSpace = geometry(text, FieldVariant.PLAIN).keys.first { it.action == KeyAction.SPACE }
        val emailSpace = geometry(text, FieldVariant.EMAIL).keys.first { it.action == KeyAction.SPACE }
        assertEquals(plainSpace.width - 100f, emailSpace.width, 1f)
        assertTrue(geometry(text, FieldVariant.EMAIL).keys.any { it.def.text == "@" })
        assertTrue(geometry(text, FieldVariant.PLAIN).keys.none { it.def.text == "@" })
    }

    @Test
    fun hitTestingFindsKeysAndGaps() {
        val g = geometry(text)
        val q = g.keys.first { it.letter == 'q' }
        assertEquals(q, g.keyAt(q.centerX, q.centerY))
        // A point in the gap between q and w goes to the nearer key.
        val w = g.keys.first { it.letter == 'w' }
        val gapX = (q.right + w.left) / 2f + 0.5f
        assertEquals(w, g.keyAt(gapX, q.centerY))
        assertNull(g.keyAt(-50f, -50f))
        assertNotNull(g.letterKey('z'))
        assertNull(g.letterKey('1'))
    }

    @Test
    fun numberRowAddsARowAndShrinksTheOthers() {
        val without = geometry(text)
        val with = geometry(text, numberRow = true)
        assertEquals(4, without.rowCount)
        assertEquals(5, with.rowCount)
        assertEquals(100f, without.rowHeightPx, 0.01f)
        assertEquals(80f, with.rowHeightPx, 0.01f)
        assertEquals("1", with.keys.first().def.text)
    }

    @Test
    fun codeLayoutBottomRowArrowWidths() {
        val g = geometry(code)
        val arrows = g.keys.filter { it.def.code?.startsWith("DPAD") == true }
        assertEquals(4, arrows.size)
        for (a in arrows) assertEquals(87.5f - 4f, a.width, 0.1f)
        assertEquals(26, g.letterKeys.size + 26) // code mode has no letter keys
        assertTrue(g.letterKeys.isEmpty())
    }
}
