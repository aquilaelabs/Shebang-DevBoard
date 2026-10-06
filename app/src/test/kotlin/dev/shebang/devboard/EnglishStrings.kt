package dev.shebang.devboard

import java.io.File

/**
 * The app's English text, read from strings.xml, for tests that check what a resource says without an Android
 * runtime: [of] gives the text of a string resource id.
 */
object EnglishStrings {
    private val texts: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { m ->
            m.groupValues[1] to m.groupValues[2].removeSurrounding("\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace(Regex("""\\(.)""")) { if (it.groupValues[1] == "n") "\n" else it.groupValues[1] }
        }
    }

    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    fun of(id: Int): String {
        val name = names[id] ?: error("no string resource $id")
        return texts[name] ?: error("string $name is not in strings.xml")
    }
}
