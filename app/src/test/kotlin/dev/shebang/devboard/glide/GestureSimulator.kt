package dev.shebang.devboard.glide

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Synthetic finger swipes that behave like a person's, for benchmarking glide decoders.
 *
 * Unlike the original harness (key centres plus Gaussian jitter), these strokes:
 *  - aim loosely: every letter is a noisy target, and the whole stroke can sit off-centre;
 *  - cut corners: the stroke bends toward the chord between neighbours, and reverses short of a key;
 *  - are smooth: a centripetal Catmull-Rom curve through the targets;
 *  - have human timing: speed follows the two-thirds power law on curvature (slower on tight turns),
 *    with extra slowdowns at some intended letters, a start from rest and a slowdown at the end;
 *  - overshoot the last key sometimes;
 *  - are sampled like a touchscreen: 120 Hz, sensor noise, and only points 2 dp apart are recorded,
 *    as KeyboardView does. The lift point is always included.
 *
 * Distances are in key pitches (centre-to-centre spacing), speeds in pitches per second.
 */
class GestureSimulator(private val layout: KeyLayoutModel, private val density: Float = 2.625f) {

    class Gesture(val x: FloatArray, val y: FloatArray, val t: LongArray, val count: Int)

    class Style(
        val cruise: Float,
        val aimNoise: Float,
        val offsetU: Float,
        val offsetV: Float,
        val cut: Float,
        val dipProbability: Float,
        val dipDepth: Float,
        val overshootProbability: Float,
    )

    private val pitchX: Float
    private val pitchY: Float

    init {
        val q = 'q' - 'a'
        val w = 'w' - 'a'
        val a = 'a' - 'a'
        pitchX = abs(layout.centerX[w] - layout.centerX[q])
        pitchY = abs(layout.centerY[a] - layout.centerY[q])
    }

    fun randomStyle(rnd: Random) = Style(
        cruise = 4f + 10f * rnd.nextFloat(),
        aimNoise = 0.10f + 0.15f * rnd.nextFloat(),
        offsetU = (rnd.nextGaussian() * 0.08).toFloat(),
        offsetV = (rnd.nextGaussian() * 0.08).toFloat(),
        cut = 0.2f + 0.7f * rnd.nextFloat(),
        dipProbability = 0.2f + 0.7f * rnd.nextFloat(),
        dipDepth = 0.3f + 0.5f * rnd.nextFloat(),
        overshootProbability = 0.5f,
    )

    /** Collapsed key sequence of [word] (apostrophes dropped), or null when it can't be glided. */
    fun keys(word: String): IntArray? {
        val out = IntArray(64)
        val n = LexiconTrie.keySequence(word.lowercase(), out)
        if (n < 2) return null
        for (i in 0 until n) if (!layout.hasLetter('a' + out[i])) return null
        return out.copyOf(n)
    }

    fun generate(word: String, rnd: Random, style: Style = randomStyle(rnd), startMs: Long = 0L): Gesture? {
        val keys = keys(word) ?: return null
        val n = keys.size
        // 1. Noisy targets in pitch units.
        val tu = FloatArray(n)
        val tv = FloatArray(n)
        for (i in 0 until n) {
            val c = keys[i]
            tu[i] = layout.centerX[c] / pitchX + style.offsetU + (rnd.nextGaussian() * style.aimNoise).toFloat()
            tv[i] = layout.centerY[c] / pitchY + style.offsetV + (rnd.nextGaussian() * style.aimNoise).toFloat()
        }
        // 2. Corner cutting: pull interior targets toward the chord of their neighbours, at most 0.35 pitch.
        val cu = tu.copyOf()
        val cv = tv.copyOf()
        for (i in 1 until n - 1) {
            val ax = tu[i - 1]
            val ay = tv[i - 1]
            val bx = tu[i + 1]
            val by = tv[i + 1]
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            var s = if (l2 > 1e-6f) ((tu[i] - ax) * dx + (tv[i] - ay) * dy) / l2 else 0f
            s = s.coerceIn(0f, 1f)
            var mx = ax + dx * s - tu[i]
            var my = ay + dy * s - tv[i]
            val ml = sqrt(mx * mx + my * my)
            val want = minOf(style.cut * ml, 0.35f)
            if (ml > 1e-6f) {
                mx = mx / ml * want
                my = my / ml * want
                cu[i] = tu[i] + mx
                cv[i] = tv[i] + my
            }
        }
        // 3. Smooth path: centripetal Catmull-Rom through the targets, densely sampled.
        val dense = ArrayList<FloatArray>()
        fun p(i: Int): FloatArray = floatArrayOf(cu[i.coerceIn(0, n - 1)], cv[i.coerceIn(0, n - 1)])
        dense.add(p(0))
        val viaIndex = IntArray(n)
        for (i in 0 until n - 1) {
            val p0 = if (i == 0) extrapolate(p(0), p(1)) else p(i - 1)
            val p1 = p(i)
            val p2 = p(i + 1)
            val p3 = if (i + 2 >= n) extrapolate(p(n - 1), p(n - 2)) else p(i + 2)
            val steps = 24
            for (k in 1..steps) dense.add(catmullRom(p0, p1, p2, p3, k.toFloat() / steps))
            viaIndex[i + 1] = dense.size - 1
        }
        // 4. Overshoot past the last target.
        if (rnd.nextFloat() < style.overshootProbability) {
            val last = dense[dense.size - 1]
            val before = dense[maxOf(0, dense.size - 4)]
            var dx = last[0] - before[0]
            var dy = last[1] - before[1]
            val l = sqrt(dx * dx + dy * dy)
            if (l > 1e-4f) {
                dx /= l
                dy /= l
                val over = 0.05f + abs(rnd.nextGaussian().toFloat()) * 0.15f
                for (k in 1..6) dense.add(floatArrayOf(last[0] + dx * over * k / 6f, last[1] + dy * over * k / 6f))
            }
        }
        // 5. Arc length and curvature along the dense path.
        val m = dense.size
        val s = FloatArray(m)
        for (i in 1 until m) s[i] = s[i - 1] + dist(dense[i - 1], dense[i])
        val total = s[m - 1]
        if (total < 0.3f) return null
        val speed = FloatArray(m)
        val dips = FloatArray(n) { if (it in 1 until n && rnd.nextFloat() < style.dipProbability) style.dipDepth * (0.5f + 0.5f * rnd.nextFloat()) else 0f }
        for (i in 0 until m) {
            val kappa = curvature(dense, i)
            // Two-thirds power law: v = K * R^(1/3), capped at the cruise speed for R >= 1 pitch.
            val r = if (kappa > 1e-4f) 1f / kappa else 1e4f
            var v = style.cruise * minOf(1f, r.pow(1f / 3f))
            for (vi in 1 until n) {
                if (dips[vi] == 0f) continue
                val d = s[i] - s[viaIndex[vi]]
                v *= 1f - dips[vi] * exp(-(d * d) / (2f * 0.25f * 0.25f))
            }
            v *= minOf(1f, 0.2f + 0.8f * s[i] / 0.5f)
            v *= minOf(1f, 0.3f + 0.7f * (total - s[i]) / 0.4f)
            speed[i] = maxOf(v, 0.05f * style.cruise)
        }
        // 6. Time along the path (ms), with a pause on landing and before lifting.
        val time = DoubleArray(m)
        val land = 30.0 + 50.0 * rnd.nextDouble()
        time[0] = land
        for (i in 1 until m) {
            val ds = (s[i] - s[i - 1]).toDouble()
            val v = 0.5 * (speed[i] + speed[i - 1])
            time[i] = time[i - 1] + ds / v * 1000.0
        }
        val end = time[m - 1] + 60.0 * rnd.nextDouble()
        // 7. Touchscreen sampling at 120 Hz, recording only 2 dp moves; the lift point is always kept.
        val minMove = 2f * density
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ts = ArrayList<Long>()
        var seg = 0
        var clock = 0.0
        var lastX = Float.NaN
        var lastY = Float.NaN
        while (true) {
            val atEnd = clock >= end
            val tt = minOf(clock, end)
            while (seg < m - 2 && time[seg + 1] < tt) seg++
            val (ux, uy) = if (tt <= time[0]) dense[0][0] to dense[0][1] else if (tt >= time[m - 1]) dense[m - 1][0] to dense[m - 1][1] else {
                val f = ((tt - time[seg]) / (time[seg + 1] - time[seg])).toFloat().coerceIn(0f, 1f)
                (dense[seg][0] + (dense[seg + 1][0] - dense[seg][0]) * f) to (dense[seg][1] + (dense[seg + 1][1] - dense[seg][1]) * f)
            }
            val px = (ux + rnd.nextGaussian().toFloat() * 0.015f) * pitchX
            val py = (uy + rnd.nextGaussian().toFloat() * 0.015f) * pitchY
            val moved = lastX.isNaN() || sqrt((px - lastX) * (px - lastX) + (py - lastY) * (py - lastY)) >= minMove
            if (moved || atEnd) {
                xs.add(px)
                ys.add(py)
                ts.add(startMs + tt.toLong())
                lastX = px
                lastY = py
            }
            if (atEnd) break
            clock += 1000.0 / 120.0
        }
        return Gesture(xs.toFloatArray(), ys.toFloatArray(), ts.toLongArray(), xs.size)
    }

    private fun extrapolate(a: FloatArray, b: FloatArray) = floatArrayOf(2 * a[0] - b[0], 2 * a[1] - b[1])

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun curvature(d: List<FloatArray>, i: Int): Float {
        if (i == 0 || i == d.size - 1) return 0f
        val a = d[i - 1]
        val b = d[i]
        val c = d[i + 1]
        val ab = dist(a, b)
        val bc = dist(b, c)
        val ac = dist(a, c)
        if (ab < 1e-5f || bc < 1e-5f || ac < 1e-5f) return 0f
        // Menger curvature: 4 * area / (ab * bc * ca).
        val area2 = abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]))
        return 2f * area2 / (ab * bc * ac)
    }

    /** Centripetal Catmull-Rom (alpha = 0.5) between p1 and p2. */
    private fun catmullRom(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, t: Float): FloatArray {
        fun knot(ti: Float, a: FloatArray, b: FloatArray): Float = ti + maxOf(dist(a, b), 1e-4f).pow(0.5f)
        val t0 = 0f
        val t1 = knot(t0, p0, p1)
        val t2 = knot(t1, p1, p2)
        val t3 = knot(t2, p2, p3)
        val tt = t1 + (t2 - t1) * t
        fun lerp(a: FloatArray, b: FloatArray, ta: Float, tb: Float): FloatArray {
            val w = if (tb - ta == 0f) 0f else (tt - ta) / (tb - ta)
            return floatArrayOf(a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w)
        }
        val a1 = lerp(p0, p1, t0, t1)
        val a2 = lerp(p1, p2, t1, t2)
        val a3 = lerp(p2, p3, t2, t3)
        val b1 = lerp(a1, a2, t0, t2)
        val b2 = lerp(a2, a3, t1, t3)
        return lerp(b1, b2, t1, t2)
    }
}
