package dev.shebang.devboard.settings

import dev.shebang.devboard.glide.GlideAdaptation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagnostics file: what is in it, and what is scrubbed. */
class DiagnosticsExportTest {
    private val settings = Settings(
        barJson = """{"items":[{"type":"text","label":"pw","text":"hunter2-secret"}]}""",
        appBars = mapOf("com.example.bank" to """{"items":[]}"""),
    )
    private val recording = """{"version":1,"word":"hello","keyWidth":1.0,"keyHeight":1.0,"centerX":[],"centerY":[],"x":[1.0],"y":[2.0],"t":[0],"density":2.0,"device":"test"}"""

    private fun doc(): JsonObject = DiagnosticsExport.build(
        DiagnosticsExport.Device("0.1.0", "Maker Phone", 36, 1080, 2400, 2.6f),
        settings,
        DiagnosticsExport.Learned(words = 12, newWords = 3, pairs = 40, emails = 2),
        GlideAdaptation(null).exportJson(),
        GlideAdaptation(null).exportJson(),
        listOf(recording, "not json"),
        listOf(dev.shebang.devboard.CrashLog.Crash("0.5.0", listOf("java.lang.IllegalStateException", "  at a.B.c(B.kt:1)"), 2)),
    )

    @Test
    fun barsAndAppNamesAreLeftOut() {
        val text = Json.encodeToString(JsonObject.serializer(), doc())
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("com.example.bank"))
        val s = doc()["settings"]!!.jsonObject
        assertEquals("true", s["customBar"]!!.jsonPrimitive.content)
        assertEquals(1, s["appBars"]!!.jsonPrimitive.int)
    }

    @Test
    fun recentFieldsAreInOnlyWhenChosenAndNeverTheirText() {
        assertFalse(doc().containsKey("recentFields"))
        assertTrue(doc()["leftOut"]!!.jsonArray.any { it.jsonPrimitive.content == "names of apps" })
        val info = android.view.inputmethod.EditorInfo().apply {
            packageName = "com.android.vending"; inputType = android.text.InputType.TYPE_CLASS_TEXT
            hintText = "Search for apps"
        }
        val entry = dev.shebang.devboard.ime.RecentFields.Entry(info.packageName, info.inputType, info.imeOptions,
            dev.shebang.devboard.ime.FieldInfo.from(info.inputType, info.imeOptions, "Search for apps"))
        val withFields = DiagnosticsExport.build(
            DiagnosticsExport.Device("0.1.0", "Maker Phone", 36, 1080, 2400, 2.6f), settings,
            DiagnosticsExport.Learned(0, 0, 0, 0), GlideAdaptation(null).exportJson(), GlideAdaptation(null).exportJson(), emptyList(),
            recentFields = dev.shebang.devboard.ime.RecentFields.json(listOf(entry)),
        )
        val text = Json.encodeToString(JsonObject.serializer(), withFields)
        assertTrue(text.contains("com.android.vending"))
        assertFalse("the hint is never written", text.contains("Search for apps"))
        assertFalse(withFields["leftOut"]!!.jsonArray.any { it.jsonPrimitive.content == "names of apps" })
    }

    @Test
    fun wordListsAreCountsOnly() {
        val store = dev.shebang.devboard.dict.WordPackStore(java.nio.file.Files.createTempDirectory("packs").toFile())
        store.import("Secret project names", "Zorblaxian\nQuuxify\n".byteInputStream())
        store.setEnabled("names", false)
        val packs = DiagnosticsExport.wordPacksJson(store)
        val text = Json.encodeToString(JsonObject.serializer(), packs)
        assertFalse(text.contains("Zorblaxian"))
        assertFalse(text.contains("Secret"))
        assertEquals("false", packs["names"]!!.jsonPrimitive.content)
        assertEquals("true", packs["dev"]!!.jsonPrimitive.content)
        assertEquals(1, packs["importedLists"]!!.jsonPrimitive.int)
        assertEquals(2, packs["importedWords"]!!.jsonPrimitive.int)
    }

    @Test
    fun learningIsCountsAndAdaptationIsNumbers() {
        val d = doc()
        assertEquals(12, d["learned"]!!.jsonObject["words"]!!.jsonPrimitive.int)
        assertEquals(2, d["learned"]!!.jsonObject["emailAddresses"]!!.jsonPrimitive.int)
        assertEquals(26, d["tapAdaptation"]!!.jsonObject["offU"]!!.jsonArray.size)
        // Recordings of prompted words come along; a damaged line is skipped.
        assertEquals(1, d["glideRecordings"]!!.jsonArray.size)
        assertEquals(DiagnosticsExport.LEFT_OUT.size, d["leftOut"]!!.jsonArray.size)
        assertEquals(2, d["crashes"]!!.jsonArray.single().jsonObject["count"]!!.jsonPrimitive.int)
    }
}
