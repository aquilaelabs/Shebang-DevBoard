package dev.shebang.devboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks on strings.xml that the compiler does not make. */
class StringResourcesTest {
    private val xml = File("src/main/res/values/strings.xml").readText()

    @Test
    fun noUnquotedSpaceAtAStringsEnds() {
        // Android trims spaces at either end of a value unless it is in double quotes: "62,394 words ·" lost the
        // space before "everyday English".
        val values = Regex("""<(string|item)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }
        val trimmed = values.filter { v -> !(v.startsWith("\"") && v.endsWith("\"")) && (v.startsWith(" ") || v.endsWith(" ")) }.toList()
        assertTrue("these would lose a space: $trimmed", trimmed.isEmpty())
    }
}
