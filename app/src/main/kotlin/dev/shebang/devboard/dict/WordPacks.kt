package dev.shebang.devboard.dict

/**
 * The keyboard's word lists. The regular words are always on; the built-in packs and the lists the user imports
 * can each be turned off ([WordPackStore]). Glide and suggestions rank every word by how common the word model
 * says it is, whatever list it came from, and the user's own use moves it up (docs/decisions.md, "Word values
 * come from the model"). Words the model has no count for start from their tier: an imported list at the bottom
 * of the regular words (or by its own frequency column), and the slang pack below every other word
 * ([startsAtFloor]). Autocorrect only ever corrects to common regular words; a word typed exactly as a pack has
 * it is left as it is (given its capitals: "cpu" becomes "CPU").
 */
object WordPacks {
    const val REGULAR = 0
    const val NAMES = 1
    const val DEV = 2
    const val COMPUTER = 3
    /** Words from a list the user imported. */
    const val IMPORTED = 4
    const val SLANG = 5

    /** A built-in pack: its id, the key it is stored under, its asset, and how it is described. */
    class BuiltIn(val id: Int, val key: String, val asset: String, val title: String, val summary: String)

    val builtIn = listOf(
        BuiltIn(NAMES, "names", "dict/pack_names.txt", "Brands and names", "Places, people, brands and apps: Spotify, Netflix, Tokyo"),
        BuiltIn(DEV, "dev", "dict/pack_dev.txt", "Development and terminal", "Commands, tools and code words: git, sudo, grep, async, Kubernetes"),
        BuiltIn(COMPUTER, "computer", "dict/pack_computer.txt", "Computer terms", "Hardware, networks and files: USB, HDMI, SSD, VPN, PDF"),
        BuiltIn(SLANG, "slang", "dict/pack_slang.txt", "Slang and abbreviations", "Chat words, offered last until you use them: lol, idk, tbh, brb"),
    )

    const val REGULAR_ASSET = "dict/en_words.txt"

    /**
     * Words of [pack] start below every other word whatever the word model counted for them, and rise only with
     * the user's use: the slang pack, which the owner wants offered last.
     */
    fun startsAtFloor(pack: Int): Boolean = pack == SLANG

    /** Packs whose words, typed exactly but in lowercase, take their own capitals rather than being corrected. */
    fun keepsTypedForm(pack: Int): Boolean = pack == DEV || pack == COMPUTER || pack == IMPORTED || pack == SLANG
}
