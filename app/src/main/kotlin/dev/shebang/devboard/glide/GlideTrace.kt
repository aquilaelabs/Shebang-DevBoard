package dev.shebang.devboard.glide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One recorded glide of a prompted word, with the key positions it was made on, so a benchmark can replay it
 * against any decoder. Written by the settings app's recorder; never contains anything the user typed.
 */
@Serializable
data class GlideTrace(
    val version: Int = 1,
    /** The prompted word. */
    val word: String,
    val keyWidth: Float,
    val keyHeight: Float,
    /** Letter key centres a..z in pixels; null for a letter without a key. */
    val centerX: List<Float?>,
    val centerY: List<Float?>,
    /** Touch points in pixels and milliseconds since touch-down; the last is the lift. */
    val x: List<Float>,
    val y: List<Float>,
    val t: List<Long>,
    val density: Float,
    val device: String,
) {
    fun layout(version: Int = 1): KeyLayoutModel = KeyLayoutModel(
        FloatArray(26) { centerX.getOrNull(it) ?: Float.NaN },
        FloatArray(26) { centerY.getOrNull(it) ?: Float.NaN },
        keyWidth,
        keyHeight,
        version,
    )

    fun toJsonLine(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseLine(line: String): GlideTrace = json.decodeFromString(serializer(), line)
    }
}
