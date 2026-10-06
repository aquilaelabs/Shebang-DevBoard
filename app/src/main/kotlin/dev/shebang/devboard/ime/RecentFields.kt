package dev.shebang.devboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The last few fields the keyboard opened in, for Export diagnostics when the user chooses to include them (R35):
 * the app, what the field asked for (its input type and options, decoded), and how the keyboard read it. Never
 * the text, the hint or anything typed. Kept in memory only, so it is gone when the keyboard's process ends.
 * Thread-safe.
 */
object RecentFields {
    const val MAX = 10

    /** One field: [app] is its package name; the rest is what [EditorInfo] and [FieldInfo] say about it. */
    data class Entry(val app: String, val inputType: Int, val imeOptions: Int, val field: FieldInfo)

    private val entries = ArrayDeque<Entry>()

    fun record(info: EditorInfo?, field: FieldInfo) {
        val e = Entry(info?.packageName.orEmpty(), info?.inputType ?: InputType.TYPE_NULL, info?.imeOptions ?: 0, field)
        synchronized(entries) {
            // The same field opening again (the app restarting input) is one entry, moved to the front.
            entries.removeAll { it.app == e.app && it.inputType == e.inputType && it.imeOptions == e.imeOptions }
            entries.addFirst(e)
            while (entries.size > MAX) entries.removeLast()
        }
    }

    /** Newest first. */
    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    internal fun clear() = synchronized(entries) { entries.clear() }

    fun json(list: List<Entry> = snapshot()): JsonArray = JsonArray(list.map { entryJson(it) })

    fun entryJson(e: Entry): JsonObject = buildJsonObject {
        put("app", JsonPrimitive(e.app))
        put("inputType", JsonPrimitive("0x" + Integer.toHexString(e.inputType)))
        put("class", JsonPrimitive(className(e.inputType)))
        put("variation", JsonPrimitive(variationName(e.inputType)))
        put("flags", JsonArray(typeFlags(e.inputType).map { JsonPrimitive(it) }))
        put("imeOptions", JsonPrimitive("0x" + Integer.toHexString(e.imeOptions)))
        put("action", JsonPrimitive(actionName(e.imeOptions and EditorInfo.IME_MASK_ACTION)))
        put("imeFlags", JsonArray(imeFlags(e.imeOptions).map { JsonPrimitive(it) }))
        val f = e.field
        put("keyboard", buildJsonObject {
            put("variant", JsonPrimitive(f.variant.name.lowercase()))
            put("terminal", JsonPrimitive(f.isTerminal))
            put("multiline", JsonPrimitive(f.multiline))
            put("enter", JsonPrimitive(when {
                f.enterIsNewline -> "newline"
                f.enterIsKeyEvent -> "key event"
                else -> "action"
            }))
            put("enterGlyph", JsonPrimitive(f.enterKind.name.lowercase()))
            put("composing", JsonPrimitive(f.allowsComposing))
            put("learning", JsonPrimitive(f.allowsLearning))
        })
    }

    fun className(type: Int): String = when (type and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> "text"
        InputType.TYPE_CLASS_NUMBER -> "number"
        InputType.TYPE_CLASS_PHONE -> "phone"
        InputType.TYPE_CLASS_DATETIME -> "datetime"
        else -> if (type == InputType.TYPE_NULL) "null" else "class${type and InputType.TYPE_MASK_CLASS}"
    }

    fun variationName(type: Int): String {
        val v = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (v) {
                InputType.TYPE_TEXT_VARIATION_NORMAL -> "normal"
                InputType.TYPE_TEXT_VARIATION_URI -> "uri"
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "emailAddress"
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "emailSubject"
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "shortMessage"
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "longMessage"
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "personName"
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "postalAddress"
                InputType.TYPE_TEXT_VARIATION_PASSWORD -> "password"
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "visiblePassword"
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "webEditText"
                InputType.TYPE_TEXT_VARIATION_FILTER -> "filter"
                InputType.TYPE_TEXT_VARIATION_PHONETIC -> "phonetic"
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "webEmailAddress"
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "webPassword"
                else -> "variation$v"
            }
            InputType.TYPE_CLASS_NUMBER -> if (v == InputType.TYPE_NUMBER_VARIATION_PASSWORD) "password" else "normal"
            InputType.TYPE_CLASS_DATETIME -> when (v) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> "date"
                InputType.TYPE_DATETIME_VARIATION_TIME -> "time"
                else -> "normal"
            }
            else -> "normal"
        }
    }

    fun typeFlags(type: Int): List<String> {
        val out = ArrayList<String>()
        when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> {
                if (type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) out += "capCharacters"
                if (type and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) out += "capWords"
                if (type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) out += "capSentences"
                if (type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0) out += "autoCorrect"
                if (type and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) out += "autoComplete"
                if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) out += "multiLine"
                if (type and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0) out += "imeMultiLine"
                if (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) out += "noSuggestions"
                if (type and InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_CONVERSION_SUGGESTIONS != 0) out += "textConversionSuggestions"
            }
            InputType.TYPE_CLASS_NUMBER -> {
                if (type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0) out += "signed"
                if (type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) out += "decimal"
            }
        }
        return out
    }

    fun actionName(action: Int): String = when (action) {
        EditorInfo.IME_ACTION_UNSPECIFIED -> "unspecified"
        EditorInfo.IME_ACTION_NONE -> "none"
        EditorInfo.IME_ACTION_GO -> "go"
        EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_SEND -> "send"
        EditorInfo.IME_ACTION_NEXT -> "next"
        EditorInfo.IME_ACTION_DONE -> "done"
        EditorInfo.IME_ACTION_PREVIOUS -> "previous"
        else -> "action$action"
    }

    fun imeFlags(options: Int): List<String> {
        val out = ArrayList<String>()
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) out += "noEnterAction"
        if (options and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0) out += "noExtractUi"
        if (options and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0) out += "noFullscreen"
        if (options and EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION != 0) out += "noAccessoryAction"
        if (options and EditorInfo.IME_FLAG_NAVIGATE_NEXT != 0) out += "navigateNext"
        if (options and EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS != 0) out += "navigatePrevious"
        if (options and EditorInfo.IME_FLAG_FORCE_ASCII != 0) out += "forceAscii"
        if (options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) out += "noPersonalizedLearning"
        return out
    }
}
