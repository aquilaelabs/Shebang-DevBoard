package dev.shebang.devboard.glide

/**
 * LRU cache of resampled ideal paths per word. The whole cache is dropped when the key geometry version changes
 * (rotation, height setting, layout variant), since every path depends on key centres.
 */
class IdealPathCache(private val capacity: Int = 4096) {
    private var version = Int.MIN_VALUE
    private val map = object : LinkedHashMap<String, FloatArray>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean = size > capacity
    }
    var hits = 0L
        private set
    var misses = 0L
        private set

    val size: Int get() = map.size

    /** Returns the cached resampled path for [word] under [model], computing it on a miss. */
    fun get(word: String, model: KeyLayoutModel, n: Int = PathResampler.N): FloatArray? {
        if (model.version != version) {
            map.clear()
            version = model.version
        }
        map[word]?.let {
            hits++
            return it
        }
        misses++
        val path = IdealPath.resampled(word, model, n) ?: return null
        map[word] = path
        return path
    }

    fun clear() = map.clear()
}

object IdealPath {
    /**
     * Key-centre polyline through a word's letters with consecutive repeats collapsed ("hello" -> h,e,l,o).
     * Returns null if a letter has no key. Result is interleaved x,y with `count` points.
     */
    fun points(word: String, model: KeyLayoutModel, out: FloatArray): Int {
        var count = 0
        var prev = 0.toChar()
        for (ch in word) {
            val c = ch.lowercaseChar()
            if (c == prev) continue
            if (!model.hasLetter(c)) return -1
            if (2 * count + 1 >= out.size) break
            out[2 * count] = model.x(c)
            out[2 * count + 1] = model.y(c)
            count++
            prev = c
        }
        return count
    }

    fun resampled(word: String, model: KeyLayoutModel, n: Int): FloatArray? {
        val raw = FloatArray(2 * maxOf(word.length, 1))
        val count = points(word, model, raw)
        if (count <= 0) return null
        val out = FloatArray(2 * n)
        PathResampler.resample(raw, count, n, out)
        return out
    }

    /** Distinct-letter count after collapsing repeats; a glide needs at least two keys. */
    fun collapsedLength(word: String): Int {
        var count = 0
        var prev = 0.toChar()
        for (ch in word) {
            val c = ch.lowercaseChar()
            if (c != prev) count++
            prev = c
        }
        return count
    }
}
