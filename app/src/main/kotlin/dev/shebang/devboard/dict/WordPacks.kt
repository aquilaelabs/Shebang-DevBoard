package dev.shebang.devboard.dict

/**
 * The keyboard's word lists and how much each counts. The regular words are always on; the built-in packs and
 * the lists the user imports can each be turned off ([WordPackStore]). Glide and suggestions rank a word by how
 * common it is, scaled by its pack's [weight], so where two readings are close the regular word wins, then a
 * name or a word from the user's own lists, then a development word, then a computer term. Autocorrect only
 * ever corrects to common regular words; a word typed exactly as a pack has it is left as it is (given its
 * capitals: "cpu" becomes "CPU").
 */
object WordPacks {
    const val REGULAR = 0
    const val NAMES = 1
    const val DEV = 2
    const val COMPUTER = 3
    /** Words from a list the user imported. */
    const val IMPORTED = 4

    /** A built-in pack: its id, the key it is stored under, its asset, and how it is described. */
    class BuiltIn(val id: Int, val key: String, val asset: String, val title: String, val summary: String)

    val builtIn = listOf(
        BuiltIn(NAMES, "names", "dict/pack_names.txt", "Brands and names", "Places, people, brands and apps: Spotify, Netflix, Tokyo"),
        BuiltIn(DEV, "dev", "dict/pack_dev.txt", "Development and terminal", "Commands, tools and code words: git, sudo, grep, async, Kubernetes"),
        BuiltIn(COMPUTER, "computer", "dict/pack_computer.txt", "Computer terms", "Hardware, networks and files: USB, HDMI, SSD, VPN, PDF"),
    )

    const val REGULAR_ASSET = "dict/en_words.txt"

    /**
     * How much a word of [pack] counts against a regular word as common as it, for glide and suggestions: a
     * light tie-breaker that keeps the owner's order, now that the word model has real counts for the packs'
     * words (from technical documentation, docs/decisions.md "The word model counts the packs' words"). The
     * imported lists have no counts, so their words rank by their tier; they share the names' weight.
     */
    fun weight(pack: Int): Double = when (pack) {
        NAMES -> NAMES_WEIGHT
        IMPORTED -> IMPORTED_WEIGHT
        DEV -> DEV_WEIGHT
        COMPUTER -> COMPUTER_WEIGHT
        else -> 1.0
    }

    var NAMES_WEIGHT = 0.9
    var IMPORTED_WEIGHT = 0.9
    var DEV_WEIGHT = 0.85
    var COMPUTER_WEIGHT = 0.8

    /** Packs whose words, typed exactly but in lowercase, take their own capitals rather than being corrected. */
    fun keepsTypedForm(pack: Int): Boolean = pack == DEV || pack == COMPUTER || pack == IMPORTED
}
