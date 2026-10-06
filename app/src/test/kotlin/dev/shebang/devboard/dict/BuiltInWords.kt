package dev.shebang.devboard.dict

import java.io.File

/**
 * The bundled word lists for tests and benchmarks: the regular words and every built-in pack, merged as the
 * keyboard merges them with all packs on. DEVBOARD_WORDS names a single list to use instead (every word then
 * counts as a regular word), to measure against another or an older list.
 */
object BuiltInWords {
    private const val DIR = "src/main/assets/"

    fun all(): Dictionary {
        System.getenv("DEVBOARD_WORDS")?.let { path -> return File(path).useLines { Dictionary.parse(it) } }
        // PACKS_OFF=slang,names measures with those built-in packs turned off.
        val off = System.getenv("PACKS_OFF")?.split(',')?.map { it.trim() }.orEmpty()
        val parts = listOf(File(DIR + WordPacks.REGULAR_ASSET).useLines { Dictionary.parse(it) }) +
            WordPacks.builtIn.filter { it.key !in off }.map { p -> File(DIR + p.asset).useLines { Dictionary.parse(it, p.id) } }
        return Dictionary.merge(parts)
    }

    /** The regular words alone, with the packs listed in [packs] (ids from [WordPacks]). */
    fun with(vararg packs: Int): Dictionary {
        val parts = listOf(File(DIR + WordPacks.REGULAR_ASSET).useLines { Dictionary.parse(it) }) +
            WordPacks.builtIn.filter { it.id in packs }.map { p -> File(DIR + p.asset).useLines { Dictionary.parse(it, p.id) } }
        return Dictionary.merge(parts)
    }
}
