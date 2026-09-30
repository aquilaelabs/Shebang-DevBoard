package dev.shebang.devboard.dict

/** Open-addressing map from Long to Int, without boxing: lookups allocate nothing. */
class LongIntMap(expected: Int = 16) {
    private var keys = LongArray(capacityFor(expected))
    private var values = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    operator fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return 0
    }

    fun add(key: Long, delta: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                values[i] += delta
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = delta
        size++
    }

    /** Calls [action] with every key (in no particular order). */
    inline fun forEachKey(action: (Long) -> Unit) {
        for (i in usedSlots.indices) if (usedSlots[i]) action(keySlots[i])
    }

    @PublishedApi internal val usedSlots: BooleanArray get() = used
    @PublishedApi internal val keySlots: LongArray get() = keys

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(keys.size)
        used = BooleanArray(keys.size)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) add(oldKeys[i], oldValues[i])
    }

    companion object {
        private fun capacityFor(n: Int): Int {
            var c = 16
            while (c < n * 2) c = c shl 1
            return c
        }

        private fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 32)
            return h.toInt()
        }

        fun pair(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
    }
}
