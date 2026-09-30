package dev.shebang.devboard.layout

import kotlinx.serialization.json.Json

/** Parses layout JSON and validates it so a bad asset fails loudly at load time, not at draw time. */
object LayoutParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): LayoutDef {
        val def = json.decodeFromString(LayoutDef.serializer(), text)
        validate(def)
        return def
    }

    fun validate(def: LayoutDef) {
        require(def.rows.isNotEmpty()) { "layout ${def.name} has no rows" }
        require(def.widthUnits > 0f) { "layout ${def.name} width must be positive" }
        require(def.mode in setOf("text", "code", "numeric")) { "layout ${def.name} has unknown mode ${def.mode}" }
        (def.rows + listOfNotNull(def.numberRow)).forEachIndexed { i, row ->
            require(row.keys.isNotEmpty()) { "layout ${def.name} row $i is empty" }
            row.keys.forEach { key ->
                key.keyAction // throws on an unknown action
                require(key.width > 0f) { "layout ${def.name} row $i has a key with width ${key.width}" }
                val roles = listOfNotNull(key.text, key.code, key.action).size
                require(key.spacer || roles >= 1) { "layout ${def.name} row $i key '${key.label}' does nothing" }
                require(!(key.text != null && key.code != null)) { "key '${key.label}' has both text and code" }
                if (key.code != null) require(KeyCodeNames.lookup(key.code) != null) { "unknown keycode ${key.code}" }
            }
            for (variant in FieldVariant.entries) {
                val visible = row.keys.filter { it.visibleFor(variant) }
                val fixed = visible.filter { !it.flex }.sumOf { it.width.toDouble() }.toFloat()
                require(fixed <= def.widthUnits + 0.001f || visible.any { it.flex }) {
                    "layout ${def.name} row $i is $fixed units wide for $variant, wider than ${def.widthUnits}"
                }
                require(visible.count { it.flex } <= 1) { "layout ${def.name} row $i has more than one flex key" }
            }
        }
    }
}
