package dev.shebang.devboard.ime

import android.content.Context
import dev.shebang.devboard.layout.BarConfig
import dev.shebang.devboard.layout.LayoutDef
import dev.shebang.devboard.layout.LayoutParser

/** Parses the bundled layout and bar assets once. */
class LayoutRepository(private val context: Context) {
    val text: LayoutDef by lazy { load("layouts/text_qwerty.json") }
    val code: LayoutDef by lazy { load("layouts/code.json") }
    val numeric: LayoutDef by lazy { load("layouts/numeric.json") }
    val defaultBar: BarConfig by lazy { BarConfig.parse(read("bar/default.json")) }

    private fun load(path: String): LayoutDef = LayoutParser.parse(read(path))
    private fun read(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
}
