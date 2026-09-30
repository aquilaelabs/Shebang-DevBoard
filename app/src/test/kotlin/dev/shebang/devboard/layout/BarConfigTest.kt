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
        val labels = bar.items.map { it.label }
        val expectedStart = listOf("Esc", "Tab", "Ctrl", "Alt", "Shift", "^C", "^D", "^Z", "^L", "^R", "←", "↑", "↓", "→", "Home", "End", "PgUp", "PgDn", "Del")
        assertEquals(expectedStart, labels.take(expectedStart.size))
        assertEquals((1..12).map { "F$it" }, labels.drop(expectedStart.size).take(12))
        assertEquals(listOf("sudo ", "&&", "| grep ", "2>&1"), bar.items.filter { it.isSnippet }.map { it.text })
        val ctrlC = bar.items.first { it.label == "^C" }
        assertEquals("C", ctrlC.code)
        assertEquals(listOf("ctrl"), ctrlC.mods)
        assertTrue(bar.items.first { it.label == "→" }.repeat)
        assertTrue(bar.items.first { it.label == "Del" }.repeat)
        assertEquals("FORWARD_DEL", bar.items.first { it.label == "Del" }.code)
    }

    @Test
    fun roundTripsThroughJson() {
        val bar = BarConfig(listOf(BarItem.key("Esc", "ESCAPE"), BarItem.modifier("Ctrl", "ctrl"), BarItem.snippet("sudo", "sudo ")))
        assertEquals(bar, BarConfig.parse(bar.toJson()))
    }

    @Test
    fun rejectsBadItems() {
        for (json in listOf(
            """{"items":[{"type":"key","label":"x"}]}""",
            """{"items":[{"type":"key","label":"x","code":"NOPE"}]}""",
            """{"items":[{"type":"snippet","label":"x"}]}""",
            """{"items":[{"type":"laser","label":"x"}]}""",
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
