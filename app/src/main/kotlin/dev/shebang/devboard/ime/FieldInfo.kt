package dev.shebang.devboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.shebang.devboard.layout.FieldVariant

/** What the keyboard may do in the current field, derived once per [EditorInfo]. */
data class FieldInfo(
    val variant: FieldVariant,
    /** inputType TYPE_NULL: a terminal or a raw key consumer. */
    val isTerminal: Boolean,
    val isPassword: Boolean,
    val noSuggestions: Boolean,
    val multiline: Boolean,
    /** IME_ACTION_* to perform on Enter, or [EditorInfo.IME_ACTION_NONE]/UNSPECIFIED for a plain Enter. */
    val editorAction: Int,
    /** Enter inserts a newline rather than performing the action. */
    val enterIsNewline: Boolean,
    val inputType: Int,
    /** The app asked for no personalised learning (IME_FLAG_NO_PERSONALIZED_LEARNING, e.g. incognito tabs). */
    val noPersonalizedLearning: Boolean = false,
) {
    val isNumeric: Boolean get() = variant == FieldVariant.NUMBER || variant == FieldVariant.PHONE || variant == FieldVariant.DATE

    /** Composing text, suggestions and autocorrect. */
    val allowsComposing: Boolean get() = !isTerminal && !isPassword && !noSuggestions && !isNumeric
    val allowsGlide: Boolean get() = allowsComposing
    /** Words typed here may be learned: ordinary text fields that did not ask for no learning. */
    val allowsLearning: Boolean get() = allowsComposing && variant == FieldVariant.PLAIN && !noPersonalizedLearning
    val allowsAutoCaps: Boolean get() = !isTerminal && !isPassword && !isNumeric
    /**
     * A web address or an email address: typed exactly, so no autocorrect, no spaces the keyboard adds by
     * itself (around glides, after a strip pick, the double-space period) and no next-word suggestions.
     */
    val isAddress: Boolean get() = variant == FieldVariant.URL || variant == FieldVariant.EMAIL
    /** Enter should be sent as a KeyEvent (terminals, and fields without an action). */
    val enterIsKeyEvent: Boolean
        get() = isTerminal || (!enterIsNewline && (editorAction == EditorInfo.IME_ACTION_NONE || editorAction == EditorInfo.IME_ACTION_UNSPECIFIED))

    companion object {
        fun from(info: EditorInfo?): FieldInfo = from(
            info?.inputType ?: InputType.TYPE_NULL,
            info?.imeOptions ?: 0,
            listOfNotNull(info?.hintText, info?.label, info?.fieldName).joinToString(" "),
        )

        /** Words in a field's hint, label or name that say it wants an email address ("Email", "Your e-mail"). */
        private val EMAIL_HINT = Regex("\\be-?mail", RegexOption.IGNORE_CASE)
        /** ... or a web address: a browser's address bar ("Search or enter address", "Search or type URL"). */
        private val URL_HINT = Regex("\\burl\\b|web address|website address|enter address|type address", RegexOption.IGNORE_CASE)

        /**
         * [hint] is the field's hint text, label and name: a plain one-line text field that asks for an email
         * address there, without saying so in its input type (common in web forms), is taken as an email field.
         */
        fun from(inputType: Int, imeOptions: Int, hint: CharSequence = ""): FieldInfo {
            val klass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS
            val isTerminal = inputType == InputType.TYPE_NULL
            val isPassword = when (klass) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
            val variantByType = when (klass) {
                InputType.TYPE_CLASS_NUMBER -> FieldVariant.NUMBER
                InputType.TYPE_CLASS_PHONE -> FieldVariant.PHONE
                InputType.TYPE_CLASS_DATETIME -> FieldVariant.DATE
                InputType.TYPE_CLASS_TEXT -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldVariant.EMAIL
                    InputType.TYPE_TEXT_VARIATION_URI -> FieldVariant.URL
                    else -> FieldVariant.PLAIN
                }
                else -> FieldVariant.PLAIN
            }
            val noSuggestions = klass == InputType.TYPE_CLASS_TEXT && (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
            val multiline = klass == InputType.TYPE_CLASS_TEXT &&
                (flags and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)) != 0
            // Only a plain one-line text field: not a subject line, a message, a name or a password.
            val plainText = variation == InputType.TYPE_TEXT_VARIATION_NORMAL || variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            val byHint = variantByType == FieldVariant.PLAIN && klass == InputType.TYPE_CLASS_TEXT && plainText && !multiline
            val variant = when {
                byHint && EMAIL_HINT.containsMatchIn(hint) -> FieldVariant.EMAIL
                byHint && URL_HINT.containsMatchIn(hint) -> FieldVariant.URL
                else -> variantByType
            }
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val enterIsNewline = !isTerminal && (multiline || noEnterAction)
            return FieldInfo(
                variant = variant,
                isTerminal = isTerminal,
                isPassword = isPassword,
                noSuggestions = noSuggestions,
                multiline = multiline,
                editorAction = action,
                enterIsNewline = enterIsNewline,
                inputType = inputType,
                noPersonalizedLearning = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
            )
        }
    }
}
