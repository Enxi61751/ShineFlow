package com.android.calendar.ml.attention

import android.util.Log
import kotlin.random.Random

object CrossAttentionDemo {
    private const val TAG = "CrossAttentionDemo"

    private fun randMatrix(rows: Int, cols: Int, scale: Float = 0.02f): Array<FloatArray> {
        return Array(rows) { FloatArray(cols) { ((Random.nextFloat() - 0.5f) * 2f) * scale } }
    }

    private fun randVector(dim: Int, scale: Float = 0.02f): FloatArray {
        return FloatArray(dim) { ((Random.nextFloat() - 0.5f) * 2f) * scale }
    }

    @JvmStatic
    fun runOnce() {
        val dModel = 32
        val numHeads = 4

        val queryA = randMatrix(5, dModel)
        val keyB = randMatrix(7, dModel)
        val valueB = randMatrix(7, dModel)

        val wQ = randMatrix(dModel, dModel); val bQ = randVector(dModel)
        val wK = randMatrix(dModel, dModel); val bK = randVector(dModel)
        val wV = randMatrix(dModel, dModel); val bV = randVector(dModel)
        val wO = randMatrix(dModel, dModel); val bO = randVector(dModel)

        val attn = CrossModalAttention(
            dModel, numHeads,
            wQ, bQ, wK, bK, wV, bV, wO, bO
        )

        val out = attn.forward(queryA, keyB, valueB)

        Log.d(TAG, "Output shape = (${out.size} x ${out[0].size})")
        Log.d(TAG, "out[0][0..5] = ${out[0].take(6)}")
    }
}

