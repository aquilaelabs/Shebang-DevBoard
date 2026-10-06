package dev.shebang.devboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The recent fields kept for diagnostics: what each records, newest first, at most ten. */
class RecentFieldsTest {
    @After
    fun clear() = RecentFields.clear()

    private fun open(app: String, type: Int, options: Int) {
        val info = EditorInfo().apply { packageName = app; inputType = type; imeOptions = options }
        RecentFields.record(info, FieldInfo.from(type, options))
    }

    @Test
    fun aFieldIsDecodedWithTheKeyboardsReadingOfIt() {
        // The Play Store's search box (B14): multi-line, asking for Search.
        open("com.android.vending", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEARCH)
        val e = RecentFields.json()[0].jsonObject
        assertEquals("com.android.vending", e["app"]!!.jsonPrimitive.content)
        assertEquals("0x20001", e["inputType"]!!.jsonPrimitive.content)
        assertEquals("text", e["class"]!!.jsonPrimitive.content)
        assertEquals("normal", e["variation"]!!.jsonPrimitive.content)
        assertEquals(listOf("multiLine"), e["flags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("search", e["action"]!!.jsonPrimitive.content)
        val k = e["keyboard"]!!.jsonObject
        assertEquals("action", k["enter"]!!.jsonPrimitive.content)
        assertEquals("search", k["enterGlyph"]!!.jsonPrimitive.content)
        assertEquals("true", k["multiline"]!!.jsonPrimitive.content)
    }

    @Test
    fun passwordsTerminalsAndFlagsAreNamed() {
        open("a.b", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        open("com.termux", InputType.TYPE_NULL, 0)
        val (terminal, password) = RecentFields.json().map { it.jsonObject }
        assertEquals("null", terminal["class"]!!.jsonPrimitive.content)
        assertEquals("key event", terminal["keyboard"]!!.jsonObject["enter"]!!.jsonPrimitive.content)
        assertEquals("webPassword", password["variation"]!!.jsonPrimitive.content)
        assertEquals(listOf("noPersonalizedLearning"), password["imeFlags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", password["keyboard"]!!.jsonObject["composing"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSameFieldAgainMovesToTheFrontAndOnlyTenAreKept() {
        for (i in 0 until 12) open("app$i", InputType.TYPE_CLASS_TEXT, 0)
        open("app5", InputType.TYPE_CLASS_TEXT, 0)
        val apps = RecentFields.snapshot().map { it.app }
        assertEquals(RecentFields.MAX, apps.size)
        assertEquals(listOf("app5", "app11", "app10", "app9", "app8", "app7", "app6", "app4", "app3", "app2"), apps)
    }
}
