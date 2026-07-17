package com.android.calendar.ml.attention

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Self-contained Cross-Modal Multi-Head Cross-Attention (no external MatrixOps dependency)
 */
class CrossModalAttention(
    private val dModel: Int,
    private val numHeads: Int,
    private val wQ: Array<FloatArray>, private val bQ: FloatArray,
    private val wK: Array<FloatArray>, private val bK: FloatArray,
    private val wV: Array<FloatArray>, private val bV: FloatArray,
    private val wO: Array<FloatArray>, private val bO: FloatArray
) {

    init {
        require(dModel % numHeads == 0) { "dModel must be divisible by numHeads" }
        checkShape(wQ, dModel, dModel, "wQ")
        checkShape(wK, dModel, dModel, "wK")
        checkShape(wV, dModel, dModel, "wV")
        checkShape(wO, dModel, dModel, "wO")
        require(bQ.size == dModel && bK.size == dModel && bV.size == dModel && bO.size == dModel) {
            "bias size must equal dModel"
        }
    }

    /**
     * Cross-Attention:
     *  Query from modality A
     *  Key/Value from modality B
     *
     * Shapes:
     *  queryA: [Tq, D]
     *  keyB:   [Tk, D]
     *  valueB: [Tk, D]
     * Output:
     *  [Tq, D]
     */
    fun forward(
        queryA: Array<FloatArray>,
        keyB: Array<FloatArray>,
        valueB: Array<FloatArray>
    ): Array<FloatArray> {

        val q = addBias(matMul(queryA, wQ), bQ) // [Tq, D]
        val k = addBias(matMul(keyB,   wK), bK) // [Tk, D]
        val v = addBias(matMul(valueB, wV), bV) // [Tk, D]

        val qh = splitHeads(q, numHeads) // [H][Tq, Dh]
        val kh = splitHeads(k, numHeads) // [H][Tk, Dh]
        val vh = splitHeads(v, numHeads) // [H][Tk, Dh]

        val outHeads: Array<Array<FloatArray>> = Array(numHeads) { h ->
            scaledDotProductAttention(qh[h], kh[h], vh[h]) // [Tq, Dh]
        }

        val concat = concatHeads(outHeads)               // [Tq, D]
        return addBias(matMul(concat, wO), bO)           // [Tq, D]
    }

    // ==========================
    // Below are local matrix ops
    // ==========================

    private fun matMul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val m = a.size
        val k = a[0].size
        val n = b[0].size
        require(b.size == k) { "matMul mismatch: a(${m}x${k}) b(${b.size}x${n})" }

        val out = Array(m) { FloatArray(n) }
        for (i in 0 until m) {
            for (t in 0 until k) {
                val av = a[i][t]
                for (j in 0 until n) out[i][j] += av * b[t][j]
            }
        }
        return out
    }

    private fun addBias(x: Array<FloatArray>, bias: FloatArray): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        require(bias.size == c) { "bias mismatch: bias=${bias.size}, cols=$c" }
        val out = Array(r) { FloatArray(c) }
        for (i in 0 until r) for (j in 0 until c) out[i][j] = x[i][j] + bias[j]
        return out
    }

    private fun transpose(x: Array<FloatArray>): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        val out = Array(c) { FloatArray(r) }
        for (i in 0 until r) for (j in 0 until c) out[j][i] = x[i][j]
        return out
    }

    private fun softmaxRows(x: Array<FloatArray>): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        val out = Array(r) { FloatArray(c) }
        for (i in 0 until r) {
            var mx = -Float.MAX_VALUE
            for (j in 0 until c) mx = max(mx, x[i][j])

            var sum = 0.0
            val tmp = DoubleArray(c)
            for (j in 0 until c) {
                val e = exp((x[i][j] - mx).toDouble())
                tmp[j] = e
                sum += e
            }
            for (j in 0 until c) out[i][j] = (tmp[j] / sum).toFloat()
        }
        return out
    }

    private fun scale(x: Array<FloatArray>, s: Float): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        val out = Array(r) { FloatArray(c) }
        for (i in 0 until r) for (j in 0 until c) out[i][j] = x[i][j] * s
        return out
    }

    private fun splitHeads(x: Array<FloatArray>, numHeads: Int): Array<Array<FloatArray>> {
        val t = x.size
        val d = x[0].size
        require(d % numHeads == 0) { "D must be divisible by numHeads" }
        val dh = d / numHeads
        return Array(numHeads) { h ->
            Array(t) { i ->
                FloatArray(dh) { j -> x[i][h * dh + j] }
            }
        }
    }

    private fun concatHeads(heads: Array<Array<FloatArray>>): Array<FloatArray> {
        val h = heads.size
        val t = heads[0].size
        val dh = heads[0][0].size
        val d = h * dh
        val out = Array(t) { FloatArray(d) }
        for (hi in 0 until h) {
            for (ti in 0 until t) {
                for (j in 0 until dh) out[ti][hi * dh + j] = heads[hi][ti][j]
            }
        }
        return out
    }

    private fun scaledDotProductAttention(
        q: Array<FloatArray>, // [Tq, Dh]
        k: Array<FloatArray>, // [Tk, Dh]
        v: Array<FloatArray>  // [Tk, Dh]
    ): Array<FloatArray> {
        val dh = q[0].size
        val kt = transpose(k)                       // [Dh, Tk]
        var scores = matMul(q, kt)                  // [Tq, Tk]
        scores = scale(scores, 1.0f / sqrt(dh.toFloat()))
        val attn = softmaxRows(scores)              // [Tq, Tk]
        return matMul(attn, v)                      // [Tq, Dh]
    }

    private fun checkShape(w: Array<FloatArray>, r: Int, c: Int, name: String) {
        require(w.size == r && w[0].size == c) { "$name must be (${r}x${c}), got (${w.size}x${w[0].size})" }
    }
}
