package dev.shebang.devboard.layout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One item on the terminal bar. Flat shape so the editor and import/export stay simple. */
@Serializable
data class BarItem(
    /** "key", "modifier" or "snippet". */
    val type: String,
    val label: String,
    /** key: keycode name from [KeyCodeNames]. */
    val code: String? = null,
    /** key: modifier names always applied ("ctrl" for ^C). */
    val mods: List<String> = emptyList(),
    /** modifier: which sticky modifier ("ctrl", "alt", "shift", "meta"). */
    val mod: String? = null,
    /** snippet: literal text to insert. */
    val text: String? = null,
    /** key: repeats while held (arrows, Del). */
    val repeat: Boolean = false,
) {
    val isKey: Boolean get() = type == TYPE_KEY
    val isModifier: Boolean get() = type == TYPE_MODIFIER
    val isSnippet: Boolean get() = type == TYPE_SNIPPET

    fun validate() {
        when (type) {
            TYPE_KEY -> {
                requireNotNull(code) { "key '$label' needs a code" }
                requireNotNull(KeyCodeNames.lookup(code)) { "key '$label' has unknown code $code" }
            }
            TYPE_MODIFIER -> requireNotNull(mod) { "modifier '$label' needs a mod" }
            TYPE_SNIPPET -> requireNotNull(text) { "snippet '$label' needs text" }
            else -> throw IllegalArgumentException("unknown bar item type '$type'")
        }
        require(label.isNotBlank()) { "bar item has an empty label" }
    }

    companion object {
        const val TYPE_KEY = "key"
        const val TYPE_MODIFIER = "modifier"
        const val TYPE_SNIPPET = "snippet"

        fun key(label: String, code: String, vararg mods: String, repeat: Boolean = false) =
            BarItem(TYPE_KEY, label, code = code, mods = mods.toList(), repeat = repeat)

        fun modifier(label: String, mod: String) = BarItem(TYPE_MODIFIER, label, mod = mod)
        fun snippet(label: String, text: String) = BarItem(TYPE_SNIPPET, label, text = text)
    }
}

@Serializable
data class BarConfig(val items: List<BarItem>) {
    fun validate() {
        require(items.isNotEmpty()) { "bar has no items" }
        items.forEach { it.validate() }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = false
        }

        fun parse(text: String): BarConfig = json.decodeFromString(serializer(), text).also { it.validate() }
    }
}
