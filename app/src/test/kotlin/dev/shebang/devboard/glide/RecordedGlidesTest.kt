package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays glides recorded with the app's recorder (Settings > Record glides > Export). Put exported
 * .jsonl files in src/test/resources/glide/traces/ or point DEVBOARD_TRACES at a file or folder.
 * Skipped when there are none.
 */
class RecordedGlidesTest {
    @Test
    fun recordedGlides() {
        val sources = listOfNotNull(File("src/test/resources/glide/traces"), System.getenv("DEVBOARD_TRACES")?.let { File(it) })
        val files = sources.flatMap { src ->
            when {
                src.isDirectory -> src.listFiles { f -> f.name.endsWith(".jsonl") }?.toList().orEmpty()
                src.isFile -> listOf(src)
                else -> emptyList()
            }
        }
        val traces = files.flatMap { f -> f.readLines().filter { it.isNotBlank() }.map { GlideTrace.parseLine(it) } }
        assumeTrue("no recorded glides", traces.isNotEmpty())

        val dictionary = GlideBenchmarkTest.dictionary
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language)
        val baseline = GlideDecoder(dictionary)
        var n = 0
        var new1 = 0
        var new3 = 0
        var old1 = 0
        var old3 = 0
        for ((k, trace) in traces.withIndex()) {
            val layout = trace.layout(version = 1000 + k)
            val count = trace.x.size
            if (count < 2) continue
            n++
            decoder.begin(layout, GlideContext(NgramModel.UNKNOWN), trace.t[0])
            for (i in 0 until count) decoder.addPoint(trace.x[i], trace.y[i], trace.t[i])
            val r = decoder.finish()?.alternatives?.map { dictionary.lower[it] }.orEmpty()
            if (r.firstOrNull() == trace.word) new1++
            if (trace.word in r.take(3)) new3++
            val pts = FloatArray(2 * count) { if (it % 2 == 0) trace.x[it / 2] else trace.y[it / 2] }
            val o = baseline.decode(pts, count, layout).map { it.word.lowercase() }
            if (o.firstOrNull() == trace.word) old1++
            if (trace.word in o.take(3)) old3++
        }
        val devices = traces.map { it.device }.distinct()
        println("GLIDE BENCH recorded glides: $n from ${files.size} file(s), devices $devices")
        println("GLIDE BENCH   whole-word decoder: top-1 ${GlideBenchmarkTest.pct(old1, n)}  top-3 ${GlideBenchmarkTest.pct(old3, n)}")
        println("GLIDE BENCH   streaming decoder:  top-1 ${GlideBenchmarkTest.pct(new1, n)}  top-3 ${GlideBenchmarkTest.pct(new3, n)}")
    }
}
