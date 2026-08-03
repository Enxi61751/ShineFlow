package com.android.calendar.ml.stn

import android.content.Context
import android.graphics.Bitmap
import com.android.calendar.ml.common.ImageUtils
import com.android.calendar.ml.common.MlResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Expect a TFLite model:
 *  - input:  [1, 224, 224, 3] float32, range [0,1]
 *  - output: [1, 224, 224, 3] float32, range [0,1]
 *
 * The STN itself can be inside the model (localization net + grid sampler).
 * This wrapper only runs inference and converts bitmap <-> tensor.
 */
class StnRectifier(
    private val context: Context,
    private val modelAssetPath: String = "models/stn_rectify.tflite",
    private val inputSize: Int = 224
) : AutoCloseable {

    private val model = TFLiteModel(context, modelAssetPath)

    fun rectify(src: Bitmap): MlResult<Bitmap> {
        return try {
            val resized = ImageUtils.resize(src, inputSize, inputSize)
            val inBuf = ImageUtils.bitmapToFloatBufferNHWC(resized)

            val outBuf = ByteBuffer
                .allocateDirect(4 * 1 * inputSize * inputSize * 3)
                .order(ByteOrder.nativeOrder())

            // output tensor as ByteBuffer is acceptable for TFLite
            model.run(inBuf, outBuf)

            val outBmp = ImageUtils.floatBufferToBitmapNHWC(outBuf, inputSize, inputSize)
            MlResult.Ok(outBmp)
        } catch (t: Throwable) {
            MlResult.Err("STN rectify failed: ${t.message}", t)
        }
    }

    override fun close() {
        model.close()
    }
}
