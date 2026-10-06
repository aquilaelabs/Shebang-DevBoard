package dev.shebang.devboard.ime

import android.view.inputmethod.InputConnection
import dev.shebang.devboard.layout.FieldVariant
import java.util.concurrent.Executor

/**
 * Remembered email addresses, offered in email fields as they are typed again: which addresses match what is
 * being typed, what the field held (remembered when it is left), and filling an address in. A collaborator of
 * [TextInputController] (R27), which decides when to ask and what the strip shows.
 */
internal class EmailOffers(private val background: Executor) {
    /** Where addresses are remembered; null when the setting is off. */
    var memory: EmailMemory? = null

    /** The addresses on the strip now: picking one fills in the address being typed. */
    var offer: List<String> = emptyList()
        private set

    /**
     * What the email field held after the last edit, remembered when the field is left. Kept as it goes because
     * an app may turn the field into another kind of input before the keyboard hears it is left.
     */
    private var fieldText: String? = null

    fun isOffered(word: String) = word in offer

    fun clear() {
        offer = emptyList()
    }

    /**
     * The remembered addresses that begin with what is being typed (the most used while nothing is), now the
     * [offer]; null where none are offered at all: no memory, not an email field, no connection, a field that
     * does not compose, or a selection. With [edited] (not when the field starts), what the field holds is kept
     * for [remember]: only what the user typed there is remembered, not an address the field came with.
     */
    fun refresh(field: FieldInfo, ic: InputConnection?, edited: Boolean): List<String>? {
        offer = emptyList()
        val memory = memory ?: return null
        if (field.variant != FieldVariant.EMAIL || ic == null) return null
        if (edited) keep(field, ic)
        if (!field.allowsComposing || !ic.getSelectedText(0).isNullOrEmpty()) return null
        val typed = typed(ic) ?: return null
        offer = memory.matching(typed)
        return offer
    }

    /** Puts [address] in place of what was typed of it; the composing word, if any, is finished first. */
    fun fill(ic: InputConnection, address: String) {
        val typed = typed(ic).orEmpty()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(address, 1)
        offer = emptyList()
    }

    /** Keeps what an email field holds, for [remember] (not where the app asks for no learning). */
    fun keep(field: FieldInfo, ic: InputConnection) {
        if (field.variant != FieldVariant.EMAIL || field.isPassword || field.noPersonalizedLearning) return
        fieldText = (ic.getTextBeforeCursor(MAX_EMAIL, 0)?.toString() ?: "") + (ic.getTextAfterCursor(MAX_EMAIL, 0)?.toString() ?: "")
    }

    /** Leaving an email field (or the keyboard closing): the addresses it last held are remembered. */
    fun remember() {
        val text = fieldText ?: return
        fieldText = null
        val memory = memory ?: return
        if (text.isNotBlank()) background.execute { memory.record(text) }
    }

    /** What is being typed in an email field: the text before the cursor back to a space, comma or semicolon. */
    private fun typed(ic: InputConnection): String? {
        val before = ic.getTextBeforeCursor(MAX_EMAIL, 0)?.toString() ?: return null
        return before.split(EmailMemory.SEPARATORS).last()
    }

    private companion object {
        /** Text read around the cursor in an email field: a few addresses' worth. */
        const val MAX_EMAIL = 1000
    }
}
