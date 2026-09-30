package dev.shebang.devboard.layout

/**
 * Computes pixel geometry for a [LayoutDef] at a given size.
 *
 * Every row is exactly [LayoutDef.widthUnits] units wide: fixed keys keep their declared width and the
 * one flex key (space) absorbs the rest, so field variants that add or remove keys stay aligned.
 */
class KeyboardGeometry(
    val layout: LayoutDef,
    val variant: FieldVariant,
    val widthPx: Int,
    val heightPx: Int,
    val numberRow: Boolean,
    val horizontalGapPx: Float,
    val verticalGapPx: Float,
    /** Increments whenever geometry changes, so caches keyed on key positions can invalidate. */
    val version: Int,
) {
    val keys: List<Key>
    val rowCount: Int
    val unitWidthPx: Float
    val rowHeightPx: Float
    /** Letter keys only, in a separate array so glide code does not filter per touch. */
    val letterKeys: List<Key>
    private val letterByChar = arrayOfNulls<Key>(26)

    init {
        val rows = (if (numberRow && layout.numberRow != null) listOf(layout.numberRow) else emptyList()) + layout.rows
        rowCount = rows.size
        val totalHeightUnits = rows.sumOf { it.height.toDouble() }.toFloat()
        rowHeightPx = heightPx / totalHeightUnits
        unitWidthPx = widthPx / layout.widthUnits
        val out = ArrayList<Key>()
        var y = 0f
        rows.forEachIndexed { rowIndex, row ->
            val visible = row.keys.filter { it.visibleFor(variant) }
            val fixed = visible.filter { !it.flex }.sumOf { it.width.toDouble() }.toFloat()
            val flexWidth = (layout.widthUnits - fixed).coerceAtLeast(0f)
            val rowH = row.height * rowHeightPx
            // Center rows that are narrower than the layout and have no flex key.
            val rowUnits = if (visible.any { it.flex }) layout.widthUnits else fixed
            var x = (layout.widthUnits - rowUnits) / 2f * unitWidthPx
            for (def in visible) {
                val w = (if (def.flex) flexWidth else def.width) * unitWidthPx
                if (!def.spacer) {
                    out.add(
                        Key(
                            def, rowIndex,
                            left = x + horizontalGapPx / 2f,
                            top = y + verticalGapPx / 2f,
                            right = x + w - horizontalGapPx / 2f,
                            bottom = y + rowH - verticalGapPx / 2f,
                        )
                    )
                }
                x += w
            }
            y += rowH
        }
        keys = out
        letterKeys = out.filter { it.letter != 0.toChar() }
        for (k in letterKeys) {
            val i = k.letter - 'a'
            if (i in 0..25 && letterByChar[i] == null) letterByChar[i] = k
        }
    }

    /** The key under a point, including the gaps around it (a touch in a gap goes to the nearest key of that row). */
    fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        val list = keys
        for (i in list.indices) {
            val k = list[i]
            if (k.contains(x, y)) return k
            val d = k.distanceSq(x, y)
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        // Only snap to a neighbour within half a gap; outside the keyboard is nothing.
        val slack = maxOf(horizontalGapPx, verticalGapPx) + 1f
        return if (bestD <= slack * slack) best else null
    }

    fun letterKey(c: Char): Key? {
        val i = c.lowercaseChar() - 'a'
        return if (i in 0..25) letterByChar[i] else null
    }

    /** Average letter key width; the unit for glide thresholds and noise. */
    val letterKeyWidth: Float = letterKeys.firstOrNull()?.width ?: unitWidthPx
}
