package dev.shebang.devboard.settings

import android.content.Context
import android.os.Build
import dev.shebang.devboard.CrashLog
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.glide.GlideAdaptation
import dev.shebang.devboard.ime.EmailMemory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.OutputStream

/**
 * A file the user can save and send to the developer, for looking into accuracy on their phone: the app and
 * device, the settings, how taps and glides lean on each key, how much has been learned (counts only), and
 * the recorder's glides of prompted words. Scrubbed of everything personal: no learned words or word pairs,
 * no email addresses, nothing from the clipboard, no terminal-bar keys or snippets, no app names, nothing
 * typed. Written only through the system file picker; the keyboard sends nothing anywhere.
 */
object DiagnosticsExport {
    /** What is left out, written into the file so whoever reads it knows. */
    val LEFT_OUT = listOf(
        "learned words and word pairs (counts only)",
        "email addresses (count only)",
        "clipboard history",
        "terminal bar keys and snippets",
        "names of apps",
        "anything typed",
        "error messages (crash reports keep only where the code failed)",
    )

    data class Device(val app: String, val model: String, val android: Int, val widthPx: Int, val heightPx: Int, val density: Float)

    data class Learned(val words: Int, val newWords: Int, val pairs: Int, val emails: Int)

    fun build(
        device: Device,
        settings: Settings,
        learned: Learned,
        taps: JsonObject,
        glides: JsonObject,
        recordings: List<String>,
        crashes: List<CrashLog.Crash> = emptyList(),
        spaceHabit: Triple<Float, Float, Boolean>? = null,
        glideOutcomes: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("format", JsonPrimitive("shebang-devboard-diagnostics"))
        put("version", JsonPrimitive(1))
        put("leftOut", JsonArray(LEFT_OUT.map { JsonPrimitive(it) }))
        put("app", JsonPrimitive(device.app))
        put("device", buildJsonObject {
            put("model", JsonPrimitive(device.model))
            put("android", JsonPrimitive(device.android))
            put("widthPx", JsonPrimitive(device.widthPx))
            put("heightPx", JsonPrimitive(device.heightPx))
            put("density", JsonPrimitive(device.density))
        })
        put("settings", settingsJson(settings))
        put("learned", buildJsonObject {
            put("words", JsonPrimitive(learned.words))
            put("newWords", JsonPrimitive(learned.newWords))
            put("pairs", JsonPrimitive(learned.pairs))
            put("emailAddresses", JsonPrimitive(learned.emails))
        })
        put("tapAdaptation", taps)
        put("glideAdaptation", glides)
        glideOutcomes?.let { put("glideOutcomes", it) }
        spaceHabit?.let { (undone, kept, on) ->
            put("spaceFromLetters", buildJsonObject {
                put("undone", JsonPrimitive(undone))
                put("kept", JsonPrimitive(kept))
                put("on", JsonPrimitive(on))
            })
        }
        put("glideRecordings", JsonArray(recordings.mapNotNull { line -> runCatching { json.parseToJsonElement(line) }.getOrNull() }))
        put("crashes", JsonArray(crashes.map { c ->
            buildJsonObject {
                put("version", JsonPrimitive(c.version))
                put("count", JsonPrimitive(c.count))
                put("trace", JsonArray(c.trace.map { JsonPrimitive(it) }))
            }
        }))
    }

    /** Every setting, except the terminal bars themselves (their keys, snippets and app names). */
    private fun settingsJson(s: Settings): JsonObject {
        val values = linkedMapOf<String, JsonElement>(
            "palette" to JsonPrimitive(s.palette),
            "heightScale" to JsonPrimitive(s.heightScale),
            "numberRow" to JsonPrimitive(s.numberRow),
            "keyPreview" to JsonPrimitive(s.keyPreview),
            "haptics" to JsonPrimitive(s.haptics),
            "hapticStrength" to JsonPrimitive(s.hapticStrength),
            "keySounds" to JsonPrimitive(s.keySounds),
            "glide" to JsonPrimitive(s.glide),
            "glideTrail" to JsonPrimitive(s.glideTrail),
            "holdDeletesWords" to JsonPrimitive(s.holdDeletesWords),
            "phraseGlide" to JsonPrimitive(s.phraseGlide),
            "learnWords" to JsonPrimitive(s.learnWords),
            "rememberEmails" to JsonPrimitive(s.rememberEmails),
            "adaptGlide" to JsonPrimitive(s.adaptGlide),
            "adaptTaps" to JsonPrimitive(s.adaptTaps),
            "autocorrect" to JsonPrimitive(s.autocorrect),
            "autoCaps" to JsonPrimitive(s.autoCaps),
            "doubleSpacePeriod" to JsonPrimitive(s.doubleSpacePeriod),
            "fixPreviousGlide" to JsonPrimitive(s.fixPreviousGlide),
            "tidyDictation" to JsonPrimitive(s.tidyDictation),
            "nextWord" to JsonPrimitive(s.nextWord),
            "pairBrackets" to JsonPrimitive(s.pairBrackets),
            "stripMode" to JsonPrimitive(s.stripMode.name),
            "customBar" to JsonPrimitive(s.barJson != null),
            "appBars" to JsonPrimitive(s.appBars.size),
        )
        return JsonObject(values)
    }

    /** Gathers everything on this phone and writes the file to [out]. Off the main thread. */
    fun write(context: Context, settings: Settings, out: OutputStream) {
        val dir = context.filesDir
        val personal = PersonalWords.get(dir).also { it.load() }
        val words = personal.list()
        val metrics = context.resources.displayMetrics
        val app = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val traces = File(dir, GlideTraceStore.FILE).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        val doc = build(
            Device(app, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, metrics.widthPixels, metrics.heightPixels, metrics.density),
            settings,
            Learned(words.size, words.count { !it.known }, personal.snapshot().pairs.size, EmailMemory.get(dir).list().size),
            GlideAdaptation.getTaps(dir).exportJson(),
            GlideAdaptation.get(dir).exportJson(),
            traces,
            CrashLog.read(File(dir, CrashLog.FILE)),
            dev.shebang.devboard.glide.SpaceHabit.get(dir).summary(),
            dev.shebang.devboard.glide.GlideOutcomes.get(dir).summary(),
        )
        out.write(pretty.encodeToString(JsonObject.serializer(), doc).toByteArray())
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }
}
