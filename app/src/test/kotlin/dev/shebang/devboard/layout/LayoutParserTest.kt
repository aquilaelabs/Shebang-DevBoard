package dev.shebang.devboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LayoutParserTest {
    private fun asset(path: String) = File("src/main/assets/$path").readText()

    @Test
    fun bundledLayoutsParseAndValidate() {
        for (name in listOf("text_qwerty", "code", "numeric")) {
            val def = LayoutParser.parse(asset("layouts/$name.json"))
            assertEquals(name, def.name)
            assertTrue(def.rows.isNotEmpty())
        }
    }

    @Test
    fun codeLayoutHasEveryDigitAndAsciiSymbolWithoutShift() {
        val def = LayoutParser.parse(asset("layouts/code.json"))
        val texts = def.rows.flatMap { it.keys }.mapNotNull { it.text }.toSet()
        val expected = ("0123456789" + "!@#$%^&*()" + "`~-_=+[]{}" + "\\|;:'\",.<>" + "/?").map { it.toString() }.toSet()
        assertEquals(emptySet<String>(), expected - texts)
        val arrows = def.rows.last().keys.mapNotNull { it.code }
        assertEquals(listOf("DPAD_LEFT", "DPAD_UP", "DPAD_DOWN", "DPAD_RIGHT"), arrows)
        assertEquals("mode_text", def.rows.last().keys.first().action)
    }

    @Test
    fun codeLayoutBottomRowWidthsMatchSpec() {
        val def = LayoutParser.parse(asset("layouts/code.json"))
        val row = def.rows.last().keys
        val arrows = row.filter { it.code?.startsWith("DPAD") == true }
        assertTrue(arrows.all { it.width == 0.875f })
        assertEquals(1.5f, row.first { it.action == "space" }.width)
        assertEquals(10.0, row.sumOf { it.width.toDouble() }, 0.001)
    }

    @Test
    fun textLayoutTopRowGivesDigitsOnLongPress() {
        val def = LayoutParser.parse(asset("layouts/text_qwerty.json"))
        val top = def.rows[0].keys
        assertEquals("qwertyuiop".map { it.toString() }, top.map { it.text })
        assertEquals("1234567890".map { it.toString() }, top.map { it.alternates.first() })
        assertTrue(def.composing)
        assertNotNull(def.numberRow)
    }

    @Test
    fun textBottomRowSwapsCommaForAtAndSlashInEmailFields() {
        val def = LayoutParser.parse(asset("layouts/text_qwerty.json"))
        val bottom = def.rows.last().keys
        val plain = bottom.filter { it.visibleFor(FieldVariant.PLAIN) }.map { it.label ?: it.text ?: it.action }
        val email = bottom.filter { it.visibleFor(FieldVariant.EMAIL) }.map { it.label ?: it.text ?: it.action }
        assertEquals(listOf("#!", ",", "", ".", "⏎"), plain)
        assertEquals(listOf("#!", "/", "@", "", ".", "⏎"), email)
    }

    @Test
    fun rejectsUnknownAction() {
        try {
            LayoutParser.parse("""{"name":"x","mode":"text","rows":[{"keys":[{"action":"explode"}]}]}""")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("explode"))
        }
    }

    @Test
    fun rejectsRowWiderThanLayout() {
        try {
            LayoutParser.parse("""{"name":"x","mode":"text","widthUnits":2,"rows":[{"keys":[{"text":"a"},{"text":"b"},{"text":"c"}]}]}""")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("wider"))
        }
    }

    @Test
    fun rejectsUnknownKeycode() {
        try {
            LayoutParser.parse("""{"name":"x","mode":"code","rows":[{"keys":[{"code":"NOPE","label":"?"}]}]}""")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("NOPE"))
        }
    }

    @Test
    fun keyCodeNamesResolve() {
        assertEquals(android.view.KeyEvent.KEYCODE_ESCAPE, KeyCodeNames.lookup("ESCAPE"))
        assertEquals(android.view.KeyEvent.KEYCODE_F12, KeyCodeNames.lookup("f12"))
        assertEquals(android.view.KeyEvent.KEYCODE_C, KeyCodeNames.lookup("C"))
        assertNull(KeyCodeNames.lookup("BOGUS"))
    }
}
