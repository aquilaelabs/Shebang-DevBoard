package dev.shebang.devboard.voice

/**
 * Messages between the keyboard and this add-on, over a Messenger. The keyboard keeps a copy of these
 * values in dev.shebang.devboard.ime.VoiceClient; change both together.
 */
object VoiceProtocol {
    const val SERVICE_ACTION = "dev.shebang.devboard.voice.LISTEN"

    // Keyboard to add-on.
    const val START = 1
    const val STOP = 2
    const val CANCEL = 3

    // Add-on to keyboard.
    /** arg1: one of the STATE_ values; arg2: input level 0..100 while listening. */
    const val STATE = 10
    /** data: KEY_TEXT, a piece of what was said (a sentence or so, after a pause). */
    const val TEXT = 11
    /** arg1: one of the ERROR_ values. */
    const val ERROR = 12

    const val STATE_IDLE = 0
    const val STATE_LISTENING = 1
    const val STATE_HEARING = 2
    const val STATE_TRANSCRIBING = 3

    const val ERROR_NO_PERMISSION = 1
    const val ERROR_MODEL = 2
    const val ERROR_MIC = 3
    const val ERROR_NOT_ALLOWED = 4

    const val KEY_TEXT = "text"
}
