package dev.shebang.devboard.glide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * Reads swipes from the FUTO swipe dataset (swipe.futo.org, MIT licence; see THIRD_PARTY_NOTICES.md): each
 * swipe on the keyboard geometry it was made on, in canvas pixels, with its sentence and place in it. The
 * data is not in the repository; download it from https://huggingface.co/datasets/futo-org/swipe.futo.org.
 */
object FutoData {
    class Record(
        val session: String,
        val sentence: String,
        val wordIdx: Int,
        /** The word as prompted, punctuation and capitals included. */
        val raw: String,
        val layout: KeyLayoutModel,
        val x: FloatArray,
        val y: FloatArray,
        val t: LongArray,
    ) {
        /** The letters of [raw], lowercase: what the decoder should produce. */
        val word: String = raw.trim { !it.isLetter() && it != '\'' }.lowercase()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads up to [limit] swipes from [file]; the QWERTY layout comes from [layoutFile] (qwerty.json beside it by default). */
    fun read(file: File, limit: Int, layoutFile: File = File(file.parentFile, "qwerty.json")): List<Record> {
        val keys = json.parseToJsonElement(layoutFile.readText()).jsonObject["keys"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["letter"]!!.jsonPrimitive.content[0] to (o["cx"]!!.jsonPrimitive.double to o["cy"]!!.jsonPrimitive.double)
        }
        val layouts = HashMap<Pair<Double, Double>, KeyLayoutModel>()
        val out = ArrayList<Record>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (out.size >= limit) break
                val o = json.parseToJsonElement(line).jsonObject
                val w = o["canvas_width"]!!.jsonPrimitive.double
                val h = o["canvas_height"]!!.jsonPrimitive.double
                val layout = layouts.getOrPut(w to h) {
                    // Three letter rows filling the canvas; keys a tenth of its width.
                    KeyLayoutModel.build(2000 + layouts.size, (0.1 * w).toFloat(), (h / 3).toFloat()) { c ->
                        keys[c]?.let { (cx, cy) -> (cx * w).toFloat() to (cy * h).toFloat() }
                    }
                }
                val pts = o["data"]!!.jsonArray.map { it.jsonObject }
                if (pts.size < 2) continue
                out += Record(
                    o["session"]?.jsonPrimitive?.content ?: "",
                    o["sentence"]?.jsonPrimitive?.content ?: "",
                    o["word_idx"]?.jsonPrimitive?.int ?: -1,
                    o["word"]!!.jsonPrimitive.content,
                    layout,
                    FloatArray(pts.size) { (pts[it]["x"]!!.jsonPrimitive.double * w).toFloat() },
                    FloatArray(pts.size) { (pts[it]["y"]!!.jsonPrimitive.double * h).toFloat() },
                    LongArray(pts.size) { pts[it]["t"]!!.jsonPrimitive.long },
                )
            }
        }
        return out
    }
}
