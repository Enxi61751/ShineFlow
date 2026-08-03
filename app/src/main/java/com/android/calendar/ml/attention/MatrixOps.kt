package com.android.calendar.ml.attention
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt


class MatrixOps {
    fun matMul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val m = a.size
        val k = a[0].size
        val n = b[0].size
        require(b.size == k) { "matMul shape mismatch: a is (${m}x${k}), b is (${b.size}x${n})" }

        val out = Array(m) { FloatArray(n) }
        for (i in 0 until m) {
            for (t in 0 until k) {
                val av = a[i][t]
                for (j in 0 until n) out[i][j] += av * b[t][j]
            }
        }
        return out
    }

    fun transpose(x: Array<FloatArray>): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        val out = Array(c) { FloatArray(r) }
        for (i in 0 until r) for (j in 0 until c) out[j][i] = x[i][j]
        return out
    }

    fun addBias(x: Array<FloatArray>, bias: FloatArray): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        require(bias.size == c) { "bias size mismatch: bias=${bias.size}, cols=$c" }
        val out = Array(r) { FloatArray(c) }
        for (i in 0 until r) {
            for (j in 0 until c) out[i][j] = x[i][j] + bias[j]
        }
        return out
    }

    fun softmaxRows(x: Array<FloatArray>): Array<FloatArray> {
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

    fun scale(x: Array<FloatArray>, s: Float): Array<FloatArray> {
        val r = x.size
        val c = x[0].size
        val out = Array(r) { FloatArray(c) }
        for (i in 0 until r) for (j in 0 until c) out[i][j] = x[i][j] * s
        return out
    }

    fun splitHeads(x: Array<FloatArray>, numHeads: Int): Array<Array<FloatArray>> {
        // x: [T, D]  -> heads: [H][T, Dh]
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

    fun concatHeads(heads: Array<Array<FloatArray>>): Array<FloatArray> {
        // heads: [H][T, Dh] -> [T, D]
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

    fun scaledDotProductAttention(
        q: Array<FloatArray>, // [Tq, Dh]
        k: Array<FloatArray>, // [Tk, Dh]
        v: Array<FloatArray>  // [Tk, Dh]
    ): Array<FloatArray> {
        val dh = q[0].size
        val kt = transpose(k)                   // [Dh, Tk]
        var scores = matMul(q, kt)              // [Tq, Tk]
        scores = scale(scores, (1.0f / sqrt(dh.toFloat())))
        val attn = softmaxRows(scores)          // [Tq, Tk]
        return matMul(attn, v)                  // [Tq, Dh]
    }

    companion object
}
