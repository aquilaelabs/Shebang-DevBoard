package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.sqrt

/** Tunable constants of [StreamingGlideDecoder]. Distances are in key pitches (the spacing between key centres). */
data class GlideParams(
    /** Spacing of resampled gesture points along the path. */
    val spacing: Float = 0.25f,
    val sigmaVertex: Float = 0.84f,
    val sigmaMid: Float = 1.125f,
    val sigmaStart: Float = 0.40f,
    /** A first letter farther than this from the touch-down point is not considered. */
    val startRadius: Float = 1.6f,
    val stayCost: Float = 0.4f,
    val skipCost: Float = 0.2625f,
    /** Lifting before reaching the last key: cost per state short of it. */
    val endCost: Float = 1.8f,
    /** Weight of ln(slowness) in the evidence that a point is a letter the finger meant. */
    val slowWeight: Float = 1.0f,
    /**
     * Weight of the turning angle (radians) in the same evidence. Off: on the simulator it lowered accuracy,
     * because the position alignment already places letters at corners. Kept for tuning on recorded glides.
     */
    val turnWeight: Float = 0.5f,
    /** Penalty per radian of turning beyond 0.6 on a stretch between letters, where the path should be straight. */
    val turnMidWeight: Float = 0f,
    /** Evidence a cruising, straight point needs to overcome to count as a letter. */
    val vertexBias: Float = 1.0f,
    val lmWeight: Float = 0.75f,
    val lookaheadWeight: Float = 0.5f,
    val beamWidth: Float = 10f,
    val maxTokens: Int = 3000,
    /** Candidates re-aligned exactly after the finger lifts. */
    val rescore: Int = 48,
    /** Candidates kept per word for context decoding and the suggestion strip. */
    val keep: Int = 16,
    /** How much better (in cost) the rewritten reading of recent glided words must be before they change. */
    val reviseMargin: Float = 2.0f,
    /**
     * Phrase gliding: cost per point of the approach from the space bar to the next word's first letter, which
     * belongs to no letter. A word after a dip may start anywhere on its first [leadInLimit] points.
     */
    val leadInCost: Float = 0f,
    val leadInLimit: Int = 40,
    /** Phrase gliding: cost per point of coasting from a word's last letter down into the space bar. */
    val leadOutCost: Float = 0f,
    /**
     * At a sharp turn the finger stops short of the key (FUTO: 0.16 pitches on average where the stroke turns
     * back, 0 where it passes through): how far short it is expected, in pitches along the way it came, at a
     * full turn; scaled down for gentler ones.
     */
    val turnShort: Float = 0f,
    /** Spread along the way the finger came, at a turn, against [sigmaVertex] across it (1: the same). */
    val alongStretch: Float = 2f,
    /**
     * How much the learned reading of the stroke ([GlideModel]) counts against the decoder's own alignment
     * (0: not used). Chosen on FUTO's dev split; see docs/decisions.md.
     */
    val modelWeight: Float = 0.75f,
    /**
     * How much the next-word model ([dev.shebang.devboard.dict.NextWordModel]), reading the whole sentence
     * before the glide, counts for the glide's first word (0: not used). Chosen on FUTO's dev split.
     */
    val nextWordWeight: Float = 0.5f,
)

/** What came before a glide in the text. */
class GlideContext(
    /**
     * Context before the first [history] word, or before the glide when there is no history:
     * [NgramModel.SENTENCE_START], [NgramModel.UNKNOWN] or a value from [NgramModel.contextOf].
     */
    val context: Int,
    /** Recent glided words still intact right before the cursor, oldest first. A new glide may revise them. */
    val history: List<GlideWord> = emptyList(),
    /** The word before [context] (same kinds of value), for the trigram; [NgramModel.UNKNOWN] when not known. */
    val context2: Int = NgramModel.UNKNOWN,
    /**
     * The sentence before the glide, for the next-word model ([dev.shebang.devboard.ime.GlideText.sentenceWords]);
     * null when not known.
     */
    val sentence: List<String?>? = null,
)

/** A glided word as it stands in the text, with its runners-up so later glides can revise it. */
class GlideWord(
    /** Dictionary index of the word as committed. */
    val word: Int,
    /** Candidate dictionary indices, including [word]. */
    val candidates: IntArray,
    /** Gesture cost of each candidate. */
    val acoustic: FloatArray,
    /** Picked from the strip or otherwise settled: context for later words, never revised. */
    val locked: Boolean = false,
) {
    /** The same word settled as [chosen]. */
    fun lockedAs(chosen: Int) = GlideWord(chosen, intArrayOf(chosen), floatArrayOf(0f), locked = true)

    /** The same candidates with [chosen] as the committed word (after a revision). */
    fun revisedTo(chosen: Int) = GlideWord(chosen, candidates, acoustic, locked)
}

class GlideResult(
    /** The new words: one dictionary index per word glided in this stroke. */
    val words: IntArray,
    /** Per new word: its stroke, resampled, as x,y pairs in key pitches (for re-aligning after a correction). */
    val strokes: List<FloatArray?>,
    /**
     * Per new word: where the stroke passed each letter relative to the key centre used, as triples
     * (letter, du, dv) in key pitches; null when the word was not the stroke's own best reading.
     */
    val observations: List<FloatArray?>,
    /** The new words with their runners-up, for revision by later glides. */
    val entries: List<GlideWord>,
    /** Ranked candidates for the last new word, the chosen one first. */
    val alternatives: IntArray,
    /** How the history should now read: one dictionary index per history word passed in. */
    val history: IntArray,
    /** Index of the first history word that changes, or -1 when the history stands. */
    val firstRevised: Int,
    /** Resampled points in the gesture. */
    val points: Int,
)

/**
 * Streaming shape-writing decoder.
 *
 * Touch points arrive while the finger moves. They are resampled to evenly spaced points, each carrying how
 * long the finger took around it (slowness) and how sharply the path turns there. A beam search walks the
 * [LexiconTrie]: each hypothesis is a partial word aligned with the path so far, where every segment between
 * two letter keys is a chain of states. A state's cost is the point's distance from the state's position,
 * plus a kinematic term: letter keys (vertices) are cheaper where the finger slowed or turned, the stretches
 * between letters are cheaper where it cruised. The beam is pruned with a unigram lookahead, and its work per
 * point is bounded.
 *
 * When the finger lifts, the best candidates are re-aligned exactly (dynamic time warping over the same
 * model, with the gesture's final speed statistics) and scored with the bigram model. Words glided in one
 * stroke (phrase gliding) and the previous glide's word are decoded jointly, so a later word can change an
 * earlier one.
 *
 * Single-threaded: one instance per decoding thread. Allocation-free per point; results allocate.
 */
class StreamingGlideDecoder(private val lang: GlideLanguage, val params: GlideParams = GlideParams()) {
    private val trie = lang.trie
    private val lm = lang.lm
    /** This decoder's own copy of the learned reading (its working memory is per decoder). */
    private val model = if (params.modelWeight != 0f) lang.model?.let { GlideModel.copyOf(it) } else null
    private val modelKeys = IntArray(64)
    private var modelU = FloatArray(0)
    private var modelV = FloatArray(0)
    /** This decoder's own next-word model, and its costs for the glide under way (null: none). */
    private val nextWord = if (params.nextWordWeight != 0f) lang.nextWord?.let { dev.shebang.devboard.dict.NextWordModel.copyOf(it) } else null
    private var nextWordCosts: FloatArray? = null

    // ---- Geometry ------------------------------------------------------------------------------------
    /** Key centres in pitches: [baseU]/[baseV] from the geometry, [keyU]/[keyV] moved by the user's offsets. */
    private val baseU = FloatArray(26)
    private val baseV = FloatArray(26)
    private val keyU = FloatArray(26)
    private val keyV = FloatArray(26)
    private val hasKey = BooleanArray(26)
    private var pitchX = 1f
    private var pitchY = 1f
    private var geometryVersion = Int.MIN_VALUE
    /** States per trie node for the current geometry; 0 = unreachable (a letter without a key). */
    private val subCount = ByteArray(trie.nodeCount)

    // ---- Resampled points of the current word (segment) --------------------------------------------------
    private val cap = 2048
    private val pu = FloatArray(cap)
    private val pv = FloatArray(cap)
    private val pt = FloatArray(cap)
    private val pd = FloatArray(cap)
    private val pz = FloatArray(cap)
    private val pturn = FloatArray(cap)
    private var n = 0
    private var ready = 0
    private var hasRaw = false
    private var rawU = 0f
    private var rawV = 0f
    private var rawT = 0f
    private var acc = 0f
    private var startMs = 0L
    private val sortedDwell = FloatArray(cap)
    private var nDwell = 0

    // ---- Beam ------------------------------------------------------------------------------------------
    private val tokCap = 1 shl 16
    private var curNode = IntArray(tokCap)
    private var curSub = IntArray(tokCap)
    private var curCost = FloatArray(tokCap)
    private var curCount = 0
    private var nxtNode = IntArray(tokCap)
    private var nxtSub = IntArray(tokCap)
    private var nxtCost = FloatArray(tokCap)
    private var nxtCount = 0
    private val hashBits = 17
    private val hashKeys = IntArray(1 shl hashBits)
    private val hashVals = IntArray(1 shl hashBits)
    private val hashStamp = IntArray(1 shl hashBits)
    private var stamp = 0
    private val pruneScratch = FloatArray(tokCap)

    // ---- Candidates ------------------------------------------------------------------------------------
    private val slotOfWord = IntArray(lang.dictionary.size)
    private val slotStamp = IntArray(lang.dictionary.size)
    private var candStamp = 0
    private val candWord = IntArray(4096)
    private val candNode = IntArray(4096)
    private val candCost = FloatArray(4096)
    private var candCount = 0
    /** The last preview's candidates for the current word: the fallback if the finished search finds none. */
    private val previewWord = IntArray(64)
    private val previewNode = IntArray(64)
    private val previewCost = FloatArray(64)
    private var previewCount = 0

    // ---- Segments: recent glided words (history), then the words of this stroke ------------------------
    private val maxSeg = MAX_HISTORY + 13
    private val segWords = Array(maxSeg) { IntArray(64) }
    private val segCost = Array(maxSeg) { FloatArray(64) }
    private val segCount = IntArray(maxSeg)
    private val segStroke = arrayOfNulls<FloatArray>(maxSeg)
    private val segObs = arrayOfNulls<FloatArray>(maxSeg)
    private val segObsWord = IntArray(maxSeg)
    private var segments = 0
    /** The first [historyCount] segments are earlier glides, [historyWord] as they stand in the text. */
    private var historyCount = 0
    private val historyWord = IntArray(MAX_HISTORY)
    private var baseContext = NgramModel.SENTENCE_START
    /** The word before [baseContext], for the trigram of the first segment. */
    private var baseContext2 = NgramModel.UNKNOWN
    private var totalPoints = 0
    /** Set while re-running the beam wider for a word that found no candidate. */
    private var wide = false
    /** Phrase gliding is on: words may end early and coast into the space bar ([DONE] states). */
    private var phrase = false
    /** The current word follows a dip into the space bar: it may start anywhere on its approach. */
    private var leadIn = false

    // ---- DTW scratch -----------------------------------------------------------------------------------
    private val pathNodes = IntArray(64)
    private val dtwA = FloatArray(4096)
    private val dtwB = FloatArray(4096)
    private val stateNode = IntArray(4096)
    private val stateSub = IntArray(4096)
    private val stateVertex = BooleanArray(4096)

    private val invTwoSigV2 = 1f / (2f * params.sigmaVertex * params.sigmaVertex)
    private val invTwoSigAlong2 = invTwoSigV2 / (params.alongStretch * params.alongStretch)
    private val turnModel = params.turnShort != 0f || params.alongStretch != 1f

    /** Unit direction of travel from key a to key b ([a * 26 + b]), in pitches; 0 when they coincide. */
    private val arriveU = FloatArray(26 * 26)
    private val arriveV = FloatArray(26 * 26)
    private val invTwoSigM2 = 1f / (2f * params.sigmaMid * params.sigmaMid)
    private val invTwoSigS2 = 1f / (2f * params.sigmaStart * params.sigmaStart)

    fun setGeometry(layout: KeyLayoutModel) {
        if (layout.version == geometryVersion) return
        val q = 'q' - 'a'
        val w = 'w' - 'a'
        val a = 'a' - 'a'
        pitchX = if (layout.hasLetter('q') && layout.hasLetter('w')) abs(layout.centerX[w] - layout.centerX[q]) else layout.keyWidth
        pitchY = if (layout.hasLetter('q') && layout.hasLetter('a')) abs(layout.centerY[a] - layout.centerY[q]) else layout.keyHeight
        if (pitchX <= 0f) pitchX = layout.keyWidth
        if (pitchY <= 0f) pitchY = layout.keyHeight
        for (c in 0..25) {
            hasKey[c] = !layout.centerX[c].isNaN()
            baseU[c] = if (hasKey[c]) layout.centerX[c] / pitchX else 0f
            baseV[c] = if (hasKey[c]) layout.centerY[c] / pitchY else 0f
            keyU[c] = baseU[c]
            keyV[c] = baseV[c]
        }
        subCount[LexiconTrie.ROOT] = 1
        for (node in 1 until trie.nodeCount) {
            val c = trie.letter[node].toInt()
            val p = trie.parent[node]
            subCount[node] = when {
                !hasKey[c] -> 0
                p == LexiconTrie.ROOT -> 1
                subCount[p].toInt() == 0 -> 0
                else -> {
                    val pc = trie.letter[p].toInt()
                    val du = keyU[c] - keyU[pc]
                    val dv = keyV[c] - keyV[pc]
                    val len = sqrt(du * du + dv * dv)
                    (len / params.spacing + 0.5f).toInt().coerceIn(1, MAX_SUB).toByte()
                }
            }
        }
        geometryVersion = layout.version
    }

    /** Starts a glide. [startMs] is the touch-down time; [phrase] when phrase gliding is on. */
    fun begin(layout: KeyLayoutModel, context: GlideContext, startMs: Long, phrase: Boolean = false, offsets: FloatArray? = null) {
        setGeometry(layout)
        applyOffsets(offsets)
        this.startMs = startMs
        this.phrase = phrase
        leadIn = false
        baseContext = context.context
        baseContext2 = context.context2
        // Read while the finger is still moving, so lifting it waits for nothing.
        nextWordCosts = context.sentence?.let { s -> nextWord?.logProbs(s) }
        segments = 0
        totalPoints = 0
        val all = context.history
        val first = maxOf(0, all.size - MAX_HISTORY)
        // History beyond the window still sets the context of the window's first word.
        if (first > 0) {
            baseContext = lm.contextOf(all[first - 1].word)
            baseContext2 = if (first > 1) lm.contextOf(all[first - 2].word) else context.context
        }
        historyCount = 0
        for (h in first until all.size) {
            val e = all[h]
            val s = segments
            var k = 0
            if (e.locked) {
                segWords[s][0] = e.word
                segCost[s][0] = 0f
                k = 1
            } else {
                var worst = 0f
                var hasWord = false
                for (i in 0 until minOf(e.candidates.size, segWords[s].size - 1)) {
                    segWords[s][k] = e.candidates[i]
                    segCost[s][k] = e.acoustic[i]
                    if (e.acoustic[i] > worst) worst = e.acoustic[i]
                    if (e.candidates[i] == e.word) hasWord = true
                    k++
                }
                if (!hasWord) {
                    segWords[s][k] = e.word
                    segCost[s][k] = worst
                    k++
                }
            }
            segCount[s] = k
            segStroke[s] = null
            segObs[s] = null
            historyWord[historyCount++] = e.word
            segments++
        }
        resetSegment()
    }

    /** Moves key centres by the user's offsets (26 horizontal then 26 vertical, in pitches), or back to base. */
    private fun applyOffsets(offsets: FloatArray?) {
        for (c in 0..25) {
            keyU[c] = baseU[c] + (offsets?.getOrNull(c) ?: 0f)
            keyV[c] = baseV[c] + (offsets?.getOrNull(26 + c) ?: 0f)
        }
        if (turnModel) for (a in 0..25) for (b in 0..25) {
            val du = keyU[b] - keyU[a]
            val dv = keyV[b] - keyV[a]
            val len = sqrt(du * du + dv * dv)
            arriveU[a * 26 + b] = if (len > 1e-3f) du / len else 0f
            arriveV[a * 26 + b] = if (len > 1e-3f) dv / len else 0f
        }
    }

    private fun resetSegment() {
        n = 0
        ready = 0
        previewCount = 0
        hasRaw = false
        acc = 0f
        nDwell = 0
        curCount = 0
    }

    /** Adds a raw touch point (pixels, uptime milliseconds). */
    fun addPoint(xPx: Float, yPx: Float, tMs: Long) {
        val u = xPx / pitchX
        val v = yPx / pitchY
        val t = (tMs - startMs).toFloat()
        if (!hasRaw) {
            hasRaw = true
            rawU = u
            rawV = v
            rawT = t
            acc = 0f
            emit(u, v, t)
            return
        }
        var su = rawU
        var sv = rawV
        var st = rawT
        var du = u - su
        var dv = v - sv
        var len = sqrt(du * du + dv * dv)
        while (len > 0f && acc + len >= params.spacing) {
            val f = (params.spacing - acc) / len
            val eu = su + du * f
            val ev = sv + dv * f
            val et = st + (t - st) * f
            emit(eu, ev, et)
            su = eu
            sv = ev
            st = et
            du = u - su
            dv = v - sv
            len = sqrt(du * du + dv * dv)
            acc = 0f
        }
        acc += len
        rawU = u
        rawV = v
        rawT = t
    }

    private fun emit(u: Float, v: Float, t: Float) {
        if (n >= cap) return
        pu[n] = u
        pv[n] = v
        pt[n] = t
        pd[n] = 0f
        pturn[n] = 0f
        n++
        totalPoints++
        val k = n - 1
        // Dwell of the previous point: time around it, centred.
        if (k >= 1) {
            val i = k - 1
            pd[i] = if (i == 0) pt[1] - pt[0] else (pt[k] - pt[i - 1]) * 0.5f
            if (i >= 1) insertDwell(pd[i])
        }
        // Points two back now have their full turning window.
        if (k >= 2) finishFeatures(k - 2, k)
    }

    private fun insertDwell(d: Float) {
        var i = nDwell
        while (i > 0 && sortedDwell[i - 1] > d) {
            sortedDwell[i] = sortedDwell[i - 1]
            i--
        }
        sortedDwell[i] = d
        nDwell++
    }

    private fun medianDwell(): Float {
        if (nDwell == 0) return 1f
        val m = sortedDwell[nDwell / 2]
        return if (m > 0.5f) m else 0.5f
    }

    /** Computes turning and slowness for point [i] (window up to [last]) and feeds ready points to the beam. */
    private fun finishFeatures(i: Int, last: Int) {
        val a = maxOf(0, i - 2)
        val b = minOf(last, i + 2)
        pturn[i] = turnAngle(a, i, b)
        pz[i] = slowness(pd[i], medianDwell())
        while (ready <= i) {
            step(ready)
            ready++
        }
    }

    private fun turnAngle(a: Int, i: Int, b: Int): Float {
        if (a == i || b == i) return 0f
        val ux = pu[i] - pu[a]
        val uy = pv[i] - pv[a]
        val vx = pu[b] - pu[i]
        val vy = pv[b] - pv[i]
        val lu = sqrt(ux * ux + uy * uy)
        val lv = sqrt(vx * vx + vy * vy)
        if (lu < 1e-4f || lv < 1e-4f) return 0f
        val c = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f)
        return acos(c)
    }

    private fun slowness(d: Float, ref: Float): Float {
        if (d <= 0f) return 0f
        return ln(d / ref).coerceIn(-1.5f, 2.5f)
    }

    private fun vertexEvidence(i: Int): Float =
        params.slowWeight * pz[i] + params.turnWeight * pturn[i] - params.vertexBias

    /** Extra cost of a point on a straight stretch when the gesture turns sharply there. */
    private fun midTurnPenalty(i: Int): Float =
        if (params.turnMidWeight == 0f) 0f else params.turnMidWeight * maxOf(0f, pturn[i] - 0.6f)

    // ---- Beam search -----------------------------------------------------------------------------------

    private fun step(i: Int) {
        if (i == 0) {
            startTokens()
            return
        }
        if (curCount == 0) return
        stamp++
        nxtCount = 0
        val stay = params.stayCost
        val skip = params.skipCost
        for (k in 0 until curCount) {
            val node = curNode[k]
            val j = curSub[k]
            val c = curCost[k]
            if (j == DONE) {
                relax(node, DONE, c + params.leadOutCost)
                continue
            }
            val m = subCount[node].toInt()
            relax(node, j, c + stay)
            // A finished word may coast from here into the space bar.
            if (phrase && j >= m && trie.hasWords(node)) relax(node, DONE, c)
            if (j < m) {
                relax(node, j + 1, c)
                if (j + 2 <= m) relax(node, j + 2, c + skip)
            } else {
                for (e in trie.childStart[node] until trie.childEnd[node]) {
                    val child = trie.children[e]
                    val mc = subCount[child].toInt()
                    if (mc == 0) continue
                    relax(child, 1, c)
                    if (mc >= 2) relax(child, 2, c + skip)
                }
            }
        }
        // After a dip, the word may begin at any point of its approach.
        if (leadIn && i <= params.leadInLimit) startAt(i, params.leadInCost * i)
        val ev = vertexEvidence(i)
        val mt = midTurnPenalty(i)
        val u = pu[i]
        val v = pv[i]
        val turn = pturn[i]
        for (s in 0 until nxtCount) nxtCost[s] += emission(nxtNode[s], nxtSub[s], u, v, ev, mt, turn)
        prune()
    }

    private fun startTokens() {
        curCount = 0
        val u = pu[0]
        val v = pv[0]
        val limit = params.startRadius * params.startRadius * invTwoSigS2
        for (e in trie.childStart[LexiconTrie.ROOT] until trie.childEnd[LexiconTrie.ROOT]) {
            val child = trie.children[e]
            if (subCount[child].toInt() == 0) continue
            val c = trie.letter[child].toInt()
            val du = u - keyU[c]
            val dv = v - keyV[c]
            val cost = (du * du + dv * dv) * invTwoSigS2
            if (cost > limit) continue
            curNode[curCount] = child
            curSub[curCount] = 1
            curCost[curCount] = cost
            curCount++
        }
    }

    /** Offers every first letter near point [i] as a word start, at [base] cost before the point's own. */
    private fun startAt(i: Int, base: Float) {
        val u = pu[i]
        val v = pv[i]
        val limit = params.startRadius * params.startRadius * invTwoSigV2
        for (e in trie.childStart[LexiconTrie.ROOT] until trie.childEnd[LexiconTrie.ROOT]) {
            val child = trie.children[e]
            if (subCount[child].toInt() == 0) continue
            val c = trie.letter[child].toInt()
            val du = u - keyU[c]
            val dv = v - keyV[c]
            if ((du * du + dv * dv) * invTwoSigV2 > limit) continue
            relax(child, 1, base)
        }
    }

    private fun relax(node: Int, sub: Int, cost: Float) {
        val key = (node shl 6) or sub
        val mask = (1 shl hashBits) - 1
        var h = (key * -0x61c88647) ushr (32 - hashBits)
        while (true) {
            if (hashStamp[h] != stamp) {
                if (nxtCount >= tokCap) return
                hashStamp[h] = stamp
                hashKeys[h] = key
                hashVals[h] = nxtCount
                nxtNode[nxtCount] = node
                nxtSub[nxtCount] = sub
                nxtCost[nxtCount] = cost
                nxtCount++
                return
            }
            if (hashKeys[h] == key) {
                val idx = hashVals[h]
                if (cost < nxtCost[idx]) nxtCost[idx] = cost
                return
            }
            h = (h + 1) and mask
        }
    }

    /**
     * Cost of point (u, v) at state [sub] of [node]; [evidence] is the point's letter evidence and [midTurn] its
     * penalty for sitting on a straight stretch.
     */
    private fun emission(node: Int, sub: Int, u: Float, v: Float, evidence: Float, midTurn: Float, turn: Float = 0f): Float {
        if (sub == DONE) return 0f
        val m = subCount[node].toInt()
        val c = trie.letter[node].toInt()
        val ku = keyU[c]
        val kv = keyV[c]
        if (sub >= m) {
            var du = u - ku
            var dv = v - kv
            val p = trie.parent[node]
            if (turnModel && p != LexiconTrie.ROOT) {
                // At a turn the finger is expected short of the key along the way it came, and looser that way.
                val k = trie.letter[p].toInt() * 26 + c
                val au = arriveU[k]
                val av = arriveV[k]
                val t = (turn / TURN_FULL).coerceIn(0f, 1f)
                du += params.turnShort * t * au
                dv += params.turnShort * t * av
                val along = du * au + dv * av
                val across = -du * av + dv * au
                val inv = invTwoSigV2 + (invTwoSigAlong2 - invTwoSigV2) * t
                return along * along * inv + across * across * invTwoSigV2 - 0.5f * evidence
            }
            return (du * du + dv * dv) * invTwoSigV2 - 0.5f * evidence
        }
        val pc = trie.letter[trie.parent[node]].toInt()
        val f = sub.toFloat() / m
        val su = keyU[pc] + (ku - keyU[pc]) * f
        val sv = keyV[pc] + (kv - keyV[pc]) * f
        val du = u - su
        val dv = v - sv
        return (du * du + dv * dv) * invTwoSigM2 + 0.5f * evidence + midTurn
    }

    private fun prune() {
        if (nxtCount == 0) {
            curCount = 0
            return
        }
        val la = params.lookaheadWeight
        var best = Float.MAX_VALUE
        for (s in 0 until nxtCount) {
            val p = nxtCost[s] + la * trie.lookahead[nxtNode[s]]
            pruneScratch[s] = p
            if (p < best) best = p
        }
        val width = if (wide) params.beamWidth * 3f else params.beamWidth
        val maxTokens = if (wide) minOf(params.maxTokens * 4, tokCap / 2) else params.maxTokens
        var threshold = best + width
        var survivors = 0
        for (s in 0 until nxtCount) if (pruneScratch[s] <= threshold) survivors++
        if (survivors > maxTokens) {
            // The k-th smallest prune score among survivors becomes the threshold.
            var m = 0
            for (s in 0 until nxtCount) if (pruneScratch[s] <= threshold) pruneScratch[m++] = pruneScratch[s]
            threshold = select(pruneScratch, m, maxTokens - 1)
        }
        var out = 0
        // Scores are recomputed because select() reordered the scratch array.
        for (s in 0 until nxtCount) {
            val p = nxtCost[s] + la * trie.lookahead[nxtNode[s]]
            if (p <= threshold && out < maxTokens) {
                curNode[out] = nxtNode[s]
                curSub[out] = nxtSub[s]
                curCost[out] = nxtCost[s]
                out++
            }
        }
        curCount = out
    }

    /** Hoare quickselect: the [k]-th smallest of the first [size] values (reorders them). */
    private fun select(a: FloatArray, size: Int, k: Int): Float {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]
                    a[i] = a[j]
                    a[j] = t
                    i++
                    j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }

    // ---- Words from the beam ---------------------------------------------------------------------------

    /**
     * Collects word candidates from tokens at (or short of) a word's last key; with [coasted], also words that
     * finished earlier and coasted into the space bar.
     */
    private fun collectCandidates(coasted: Boolean = false) {
        candStamp++
        candCount = 0
        for (k in 0 until curCount) {
            val node = curNode[k]
            if (!trie.hasWords(node)) continue
            val m = subCount[node].toInt()
            val j = curSub[k]
            if (j == DONE && !coasted) continue
            // Short of the last key: a penalty per missing state, so a word is found even if the lift came early.
            val cost = if (j >= m) curCost[k] else curCost[k] + (m - j) * params.endCost
            for (e in trie.wordStart[node] until trie.wordEnd[node]) {
                val word = trie.words[e]
                if (slotStamp[word] == candStamp) {
                    val s = slotOfWord[word]
                    if (cost < candCost[s]) candCost[s] = cost
                } else if (candCount < candWord.size) {
                    slotStamp[word] = candStamp
                    slotOfWord[word] = candCount
                    candWord[candCount] = word
                    candNode[candCount] = node
                    candCost[candCount] = cost
                    candCount++
                }
            }
        }
    }

    /** Sorts candidates by [key] ascending, keeping the first [limit]. Insertion sort on a small prefix. */
    private inline fun keepBest(limit: Int, key: (Int) -> Float) {
        // Selection of the best `limit` by repeated partial insertion.
        val keys = pruneScratch
        for (i in 0 until candCount) keys[i] = key(i)
        val n = candCount
        val take = minOf(limit, n)
        for (i in 0 until take) {
            var best = i
            for (j in i + 1 until n) if (keys[j] < keys[best]) best = j
            if (best != i) {
                swapCand(i, best)
                val t = keys[i]
                keys[i] = keys[best]
                keys[best] = t
            }
        }
        candCount = take
    }

    private fun swapCand(a: Int, b: Int) {
        val w = candWord[a]
        candWord[a] = candWord[b]
        candWord[b] = w
        val nd = candNode[a]
        candNode[a] = candNode[b]
        candNode[b] = nd
        val c = candCost[a]
        candCost[a] = candCost[b]
        candCost[b] = c
    }

    /**
     * Finishes the current word: flushes points, re-aligns candidates exactly and stores them as a segment.
     * [coasted] when the word ended in a dip into the space bar rather than at a lift.
     */
    private fun closeSegment(coasted: Boolean = false): Boolean {
        if (n < 2) return false
        // Tail: the last points get truncated windows; the last point also gets the time until lift.
        if (n >= 2) {
            val last = n - 1
            pd[last] = (pt[last] - pt[last - 1]) + maxOf(0f, rawT - pt[last])
            insertDwell(pd[last])
        }
        for (i in maxOf(0, n - 2) until n) {
            if (i < ready) continue
            pturn[i] = turnAngle(maxOf(0, i - 2), i, minOf(n - 1, i + 2))
            pz[i] = slowness(pd[i], medianDwell())
        }
        while (ready < n) {
            step(ready)
            ready++
        }
        collectCandidates(coasted)
        if (candCount == 0) {
            // The beam lost every word: search this word again, three times wider.
            wide = true
            curCount = 0
            for (i in 0 until n) step(i)
            wide = false
            collectCandidates(coasted)
        }
        if (candCount == 0 && previewCount > 0) {
            // Still nothing: fall back to what the preview last showed, rather than dropping the gesture.
            for (c in 0 until previewCount) {
                candWord[c] = previewWord[c]
                candNode[c] = previewNode[c]
                candCost[c] = previewCost[c]
            }
            candCount = previewCount
        }
        if (candCount == 0) return false
        val lmW = params.lmWeight
        keepBest(params.rescore) { candCost[it] + lmW * lm.unigramCost(candWord[it]) }
        // Final speed statistics for exact re-alignment.
        val ref = medianDwell()
        for (i in 0 until n) pz[i] = slowness(pd[i], ref)
        for (c in 0 until candCount) candCost[c] = align(candNode[c], coasted)
        keepBest(params.keep) { candCost[it] + lmW * lm.unigramCost(candWord[it]) }
        // The next-word model weighs in on the glide's first word, from the whole sentence before it.
        val nw = nextWordCosts
        if (nw != null && segments == historyCount) {
            val ids = lang.nextWordIds
            val unknown = nextWord!!.unknownCost + UNKNOWN_WORD_COST
            for (c in 0 until candCount) {
                val id = ids[candWord[c]]
                candCost[c] += params.nextWordWeight * (if (id >= 0) nw[id] else unknown)
            }
        }
        // The learned reading of the stroke weighs in on the words kept, for a stroke that is one word
        // whole (not a word of a phrase, whose stroke runs in from or out to the space bar).
        val m = model
        if (m != null && !coasted && !leadIn) {
            if (modelU.size < n) {
                modelU = FloatArray(n)
                modelV = FloatArray(n)
            }
            // In pitches with q's key centre at (0.5, 0.5), as the model was trained (no number row).
            val q = 'q' - 'a'
            val du = if (hasKey[q]) baseU[q] - 0.5f else 0f
            val dv = if (hasKey[q]) baseV[q] - 0.5f else 0f
            for (i in 0 until n) {
                modelU[i] = pu[i] - du
                modelV[i] = pv[i] - dv
            }
            m.read(modelU, modelV, pt, n)
            for (c in 0 until candCount) {
                val len = LexiconTrie.keySequence(lang.dictionary.lower[candWord[c]], modelKeys)
                if (len > 0) candCost[c] += params.modelWeight * m.cost(modelKeys, len)
            }
        }
        if (segments >= maxSeg) return false
        val s = segments++
        for (c in 0 until candCount) {
            segWords[s][c] = candWord[c]
            segCost[s][c] = candCost[c]
        }
        segCount[s] = candCount
        segStroke[s] = FloatArray(2 * n) { if (it % 2 == 0) pu[it / 2] else pv[it / 2] }
        // Where the stroke passed each letter of its best reading, for adaptation (not after coasting).
        segObs[s] = if (coasted) null else observe(candNode[0])
        segObsWord[s] = candWord[0]
        return true
    }

    /**
     * Exact alignment cost of the current segment's points against the word ending at [node], with the same
     * approach ([leadIn]) and coasting ([coasted]) allowances as the beam.
     */
    /** Lays out the states of the word ending at [node] in path order; returns how many. */
    private fun buildStates(node: Int): Int {
        var depth = 0
        var x = node
        while (x != LexiconTrie.ROOT && depth < pathNodes.size) {
            pathNodes[depth++] = x
            x = trie.parent[x]
        }
        // First letter's vertex, then each later segment's chain.
        var s = 0
        for (d in depth - 1 downTo 0) {
            val nd = pathNodes[d]
            val m = subCount[nd].toInt()
            for (j in 1..m) {
                if (s >= stateNode.size) break
                stateNode[s] = nd
                stateSub[s] = j
                stateVertex[s] = j == m
                s++
            }
        }
        return s
    }

    private fun align(node: Int, coasted: Boolean): Float {
        val states = buildStates(node)
        if (states == 0) return Float.MAX_VALUE
        val inf = Float.MAX_VALUE / 4
        var prev = dtwA
        var next = dtwB
        for (k in 0 until states) prev[k] = inf
        run {
            val c = trie.letter[stateNode[0]].toInt()
            val du = pu[0] - keyU[c]
            val dv = pv[0] - keyV[c]
            prev[0] = (du * du + dv * dv) * invTwoSigS2
        }
        // Best cost of having finished the word and coasted since (phrase gliding).
        var finished = inf
        for (i in 1 until n) {
            val ev = vertexEvidence(i)
            val mt = midTurnPenalty(i)
            val u = pu[i]
            val v = pv[i]
            if (coasted) finished = minOf(finished + params.leadOutCost, prev[states - 1])
            for (k in 0 until states) {
                var best = prev[k] + params.stayCost
                if (k >= 1 && prev[k - 1] < best) best = prev[k - 1]
                if (k >= 2 && !stateVertex[k - 1]) {
                    val sk = prev[k - 2] + params.skipCost
                    if (sk < best) best = sk
                }
                if (k == 0 && leadIn && i <= params.leadInLimit) {
                    val start = params.leadInCost * i
                    if (start < best) best = start
                }
                next[k] = if (best >= inf) inf else best + emission(stateNode[k], stateSub[k], u, v, ev, mt, pturn[i])
            }
            val t = prev
            prev = next
            next = t
        }
        var result = minOf(prev[states - 1], finished)
        // Ending early within the last segment, as in candidate collection.
        var k = states - 2
        var missing = 1
        while (k >= 0 && !stateVertex[k]) {
            val early = prev[k] + missing * params.endCost
            if (early < result) result = early
            k--
            missing++
        }
        return result
    }

    // ---- Where the stroke passed each letter (adaptation) ----------------------------------------------

    private val backMoves = ByteArray(1 shl 18)
    private val obsSumU = FloatArray(4096)
    private val obsSumV = FloatArray(4096)
    private val obsCount = IntArray(4096)

    /**
     * Aligns the current points with the word ending at [node] and returns, per letter, the mean offset of the
     * points aligned to its key from the key centre used: triples (letter, du, dv). Null if it cannot align.
     */
    private fun observe(node: Int): FloatArray? {
        val states = buildStates(node)
        if (states == 0 || n < 2 || n.toLong() * states > backMoves.size) return null
        val inf = Float.MAX_VALUE / 4
        var prev = dtwA
        var next = dtwB
        for (k in 0 until states) prev[k] = inf
        run {
            val c = trie.letter[stateNode[0]].toInt()
            val du = pu[0] - keyU[c]
            val dv = pv[0] - keyV[c]
            prev[0] = (du * du + dv * dv) * invTwoSigS2
        }
        for (i in 1 until n) {
            val ev = vertexEvidence(i)
            val mt = midTurnPenalty(i)
            for (k in 0 until states) {
                var best = prev[k] + params.stayCost
                var move = 0
                if (k >= 1 && prev[k - 1] < best) {
                    best = prev[k - 1]
                    move = 1
                }
                if (k >= 2 && !stateVertex[k - 1] && prev[k - 2] + params.skipCost < best) {
                    best = prev[k - 2] + params.skipCost
                    move = 2
                }
                if (k == 0 && leadIn && i <= params.leadInLimit && params.leadInCost * i < best) {
                    best = params.leadInCost * i
                    move = 3
                }
                backMoves[i * states + k] = move.toByte()
                // Learning measures where the stroke passed each key, so it aligns without the turn model.
                next[k] = if (best >= inf) inf else best + emission(stateNode[k], stateSub[k], pu[i], pv[i], ev, mt)
            }
            val t = prev
            prev = next
            next = t
        }
        if (prev[states - 1] >= inf / 2) return null
        for (k in 0 until states) {
            obsSumU[k] = 0f
            obsSumV[k] = 0f
            obsCount[k] = 0
        }
        var k = states - 1
        var i = n - 1
        while (i >= 0) {
            if (stateVertex[k] || k == 0) {
                obsSumU[k] += pu[i]
                obsSumV[k] += pv[i]
                obsCount[k]++
            }
            if (i == 0) break
            when (backMoves[i * states + k].toInt()) {
                1 -> k -= 1
                2 -> k -= 2
                3 -> break
            }
            i--
        }
        var letters = 0
        for (s in 0 until states) if ((stateVertex[s] || s == 0) && obsCount[s] > 0) letters++
        val out = FloatArray(3 * letters)
        var o = 0
        for (s in 0 until states) {
            if (!(stateVertex[s] || s == 0) || obsCount[s] == 0) continue
            val c = trie.letter[stateNode[s]].toInt()
            out[o++] = c.toFloat()
            out[o++] = obsSumU[s] / obsCount[s] - keyU[c]
            out[o++] = obsSumV[s] / obsCount[s] - keyV[c]
        }
        return out
    }

    /**
     * Re-aligns an earlier [stroke] (from [GlideResult.strokes]) with [word], for learning from a correction:
     * the stroke was meant as [word]. Uses the geometry and offsets given. Returns observations or null.
     */
    fun observeWord(layout: KeyLayoutModel, offsets: FloatArray?, stroke: FloatArray, word: Int): FloatArray? {
        setGeometry(layout)
        applyOffsets(offsets)
        val seq = IntArray(64)
        val len = LexiconTrie.keySequence(lang.dictionary.lower[word], seq)
        if (len < 2) return null
        var node = LexiconTrie.ROOT
        for (q in 0 until len) {
            var next = -1
            for (e in trie.childStart[node] until trie.childEnd[node]) {
                if (trie.letter[trie.children[e]].toInt() == seq[q]) next = trie.children[e]
            }
            if (next < 0) return null
            node = next
        }
        val count = minOf(stroke.size / 2, cap)
        for (i in 0 until count) {
            pu[i] = stroke[2 * i]
            pv[i] = stroke[2 * i + 1]
            pz[i] = 0f
            pturn[i] = 0f
        }
        val saved = n
        val savedLeadIn = leadIn
        n = count
        leadIn = false
        val out = observe(node)
        n = saved
        leadIn = savedLeadIn
        return out
    }

    // ---- Joint decoding across words -------------------------------------------------------------------

    private val vitCost = Array(maxSeg) { FloatArray(64) }
    private val vitBack = Array(maxSeg) { IntArray(64) }
    private var bestLast = -1

    /**
     * Viterbi over segments with bigram context: segment 0 follows [baseContext], every later segment follows
     * the word chosen before it. With [fixHistory], history segments are held to the words in the text.
     * Returns the best total cost; the path ends at [bestLast] and is read back with [backtrack].
     */
    private fun viterbi(count: Int, fixHistory: Boolean): Float {
        val lmW = params.lmWeight
        val blocked = Float.MAX_VALUE / 4
        for (a in 0 until segCount[0]) {
            val w = segWords[0][a]
            vitCost[0][a] = if (fixHistory && historyCount > 0 && w != historyWord[0]) blocked
            else segCost[0][a] + lmW * lm.cost3(w, baseContext2, baseContext)
            vitBack[0][a] = -1
        }
        for (s in 1 until count) {
            val fixed = if (fixHistory && s < historyCount) historyWord[s] else -1
            for (a in 0 until segCount[s]) {
                val w = segWords[s][a]
                if (fixed >= 0 && w != fixed) {
                    vitCost[s][a] = blocked
                    vitBack[s][a] = -1
                    continue
                }
                var best = Float.MAX_VALUE
                var arg = -1
                for (b in 0 until segCount[s - 1]) {
                    val pc = vitCost[s - 1][b]
                    if (pc >= blocked) continue
                    // The word before that one: on the best path into b (exact would need pair states).
                    val prev2 = if (s == 1) baseContext else lm.contextOf(segWords[s - 2][vitBack[s - 1][b].coerceAtLeast(0)])
                    val c = pc + lmW * lm.cost3(w, prev2, lm.contextOf(segWords[s - 1][b]))
                    if (c < best) {
                        best = c
                        arg = b
                    }
                }
                vitCost[s][a] = if (arg < 0) blocked else best + segCost[s][a]
                vitBack[s][a] = arg
            }
        }
        var best = Float.MAX_VALUE
        bestLast = -1
        for (a in 0 until segCount[count - 1]) {
            if (vitCost[count - 1][a] < best) {
                best = vitCost[count - 1][a]
                bestLast = a
            }
        }
        return best
    }

    private fun backtrack(count: Int): IntArray {
        val path = IntArray(count)
        var a = bestLast
        for (s in count - 1 downTo 0) {
            path[s] = a
            a = if (s > 0 && a >= 0) vitBack[s][a] else -1
        }
        return path
    }

    /**
     * Decodes history plus this stroke's [count] - history segments. The history is rewritten only when the
     * best reading beats the text as it stands by [GlideParams.reviseMargin]; otherwise the new words are
     * decoded given the history as it stands.
     */
    private fun decodeSegments(count: Int): GlideResult? {
        if (count <= historyCount) return null
        val free = viterbi(count, false)
        if (bestLast < 0) return null
        var path = backtrack(count)
        var firstRevised = -1
        if (historyCount > 0) {
            var changed = -1
            for (s in 0 until historyCount) {
                if (segWords[s][path[s]] != historyWord[s]) {
                    changed = s
                    break
                }
            }
            if (changed >= 0) {
                val fixed = viterbi(count, true)
                if (fixed - free >= params.reviseMargin || bestLast < 0) {
                    firstRevised = changed
                } else {
                    path = backtrack(count)
                }
            }
        }
        val history = IntArray(historyCount) { segWords[it][path[it]] }
        val newCount = count - historyCount
        val words = IntArray(newCount) { segWords[historyCount + it][path[historyCount + it]] }
        val strokes = List(newCount) { segStroke[historyCount + it] }
        val observations = List(newCount) { i ->
            val s = historyCount + i
            if (segObsWord[s] == words[i]) segObs[s] else null
        }
        val entries = List(newCount) { i ->
            val s = historyCount + i
            GlideWord(words[i], segWords[s].copyOf(segCount[s]), segCost[s].copyOf(segCount[s]))
        }
        // Alternatives for the last word, given the words before it.
        val last = count - 1
        val lastCtx = if (last > 0) lm.contextOf(segWords[last - 1][path[last - 1]]) else baseContext
        val lastCtx2 = when {
            last > 1 -> lm.contextOf(segWords[last - 2][path[last - 2]])
            last == 1 -> baseContext
            else -> baseContext2
        }
        val k = segCount[last]
        val order = (0 until k).sortedBy { segCost[last][it] + params.lmWeight * lm.cost3(segWords[last][it], lastCtx2, lastCtx) }
        val alternatives = IntArray(minOf(5, k)) { segWords[last][order[it]] }
        return GlideResult(words, strokes, observations, entries, alternatives, history, firstRevised, totalPoints)
    }

    /** Ends the current word and starts the next one in the same stroke (phrase gliding). */
    fun boundary(): Boolean {
        val closed = closeSegment(coasted = true)
        resetSegment()
        leadIn = true
        return closed
    }

    /**
     * The decode if the finger lifted now, from the beam as it stands (no exact re-alignment). Cheap enough to
     * call every few points. Returns null when nothing matches yet.
     */
    fun preview(): GlideResult? {
        if (n < 2 || curCount == 0 || segments >= maxSeg) return null
        collectCandidates()
        if (candCount == 0) return null
        val lmW = params.lmWeight
        keepBest(params.keep) { candCost[it] + lmW * lm.unigramCost(candWord[it]) }
        val s = segments
        previewCount = minOf(candCount, previewWord.size)
        for (c in 0 until candCount) {
            segWords[s][c] = candWord[c]
            segCost[s][c] = candCost[c]
            if (c < previewCount) {
                previewWord[c] = candWord[c]
                previewNode[c] = candNode[c]
                previewCost[c] = candCost[c]
            }
        }
        segCount[s] = candCount
        return decodeSegments(s + 1)
    }

    /**
     * Finishes the glide. [trailingSpace] when the finger lifted in the space bar after a dip, so the last word
     * coasted there. Returns null when no word fits.
     */
    fun finish(trailingSpace: Boolean = false): GlideResult? {
        closeSegment(coasted = trailingSpace)
        val result = decodeSegments(segments)
        resetSegment()
        leadIn = false
        return result
    }

    /** Hypotheses alive in the beam, for tests and diagnostics. */
    val activeTokens: Int get() = curCount

    companion object {
        /** Recent glided words a new glide may revise. */
        const val MAX_HISTORY = 4
        /** Most states per segment; the next value marks a finished word coasting into the space bar. */
        private const val MAX_SUB = 62
        /** A word the next-word model does not know: as likely as its unknown word, less a little. */
        private const val UNKNOWN_WORD_COST = 2f
        /** A turn of this many radians or more counts in full for [GlideParams.turnShort]. */
        private const val TURN_FULL = 2.5f
        private const val DONE = 63
    }
}
