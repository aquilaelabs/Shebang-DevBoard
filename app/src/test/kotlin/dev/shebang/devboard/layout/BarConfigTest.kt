package dev.shebang.devboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BarConfigTest {
    @Test
    fun defaultBarMatchesSpec() {
        val bar = BarConfig.parse(File("src/main/assets/bar/default.json").readText())
        // The emoji and clipboard panels come first, then the settings gear; the keys follow.
        assertEquals(listOf(BarItem.TYPE_EMOJI, BarItem.TYPE_CLIPBOARD), bar.items.take(2).map { it.type })
        assertTrue(bar.items.take(2).all { it.isPanel })
        assertEquals(BarItem.ACTION_SETTINGS, bar.items[2].action)
        assertTrue(bar.items.take(3).all { it.hasGlyph })
        val labels = bar.items.drop(3).map { it.label }
        val expectedStart = listOf("Esc", "Tab", "Ctrl", "Alt", "Shift", "^C", "^D", "^Z", "^L", "^R", "←", "↑", "↓", "→", "Home", "End", "PgUp", "PgDn", "Del")
        assertEquals(expectedStart, labels.take(expectedStart.size))
        assertEquals((1..12).map { "F$it" }, labels.drop(expectedStart.size).take(12))
        assertEquals(listOf("sudo ", "&&", "| grep ", "2>&1"), bar.items.filter { it.isSnippet }.map { it.text })
        // Editing goes through the app's own undo, selection and clipboard, never a Ctrl key a terminal would act on.
        assertEquals(
            listOf("undo", "redo", "select_all", "cut", "copy", "paste"),
            bar.items.filter { it.isAction && !it.hasGlyph }.map { it.action },
        )
        val ctrlC = bar.items.first { it.label == "^C" }
        assertEquals("C", ctrlC.code)
        assertEquals(listOf("ctrl"), ctrlC.mods)
        assertTrue(bar.items.first { it.label == "→" }.repeat)
        assertTrue(bar.items.first { it.label == "Del" }.repeat)
        assertEquals("FORWARD_DEL", bar.items.first { it.label == "Del" }.code)
    }

    @Test
    fun roundTripsThroughJson() {
        val bar = BarConfig(
            listOf(
                BarItem.key("Esc", "ESCAPE"), BarItem.modifier("Ctrl", "ctrl"), BarItem.snippet("sudo", "sudo "),
                BarItem.action(BarItem.ACTION_PASTE), BarItem.action(BarItem.ACTION_ONE_HANDED),
            ),
        )
        assertEquals(bar, BarConfig.parse(bar.toJson()))
    }

    @Test
    fun rejectsBadItems() {
        for (json in listOf(
            """{"items":[{"type":"key","label":"x"}]}""",
            """{"items":[{"type":"key","label":"x","code":"NOPE"}]}""",
            """{"items":[{"type":"snippet","label":"x"}]}""",
            """{"items":[{"type":"laser","label":"x"}]}""",
            """{"items":[{"type":"action","label":"x"}]}""",
            """{"items":[{"type":"action","label":"x","action":"teleport"}]}""",
            """{"items":[]}""",
        )) {
            try {
                BarConfig.parse(json)
                fail("expected $json to fail")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
