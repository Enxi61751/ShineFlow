package com.android.calendar.ml.common

import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageUtils {

    fun resize(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /**
     * Convert ARGB_8888 Bitmap to Float32 NCHW or NHWC.
     * Here we do NHWC: [1, H, W, 3], normalized to [0,1].
     */
    fun bitmapToFloatBufferNHWC(bitmap: Bitmap): ByteBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val buf = ByteBuffer.allocateDirect(4 * 1 * h * w * 3).order(ByteOrder.nativeOrder())

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[idx++]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                buf.putFloat(r)
                buf.putFloat(g)
                buf.putFloat(b)
            }
        }
        buf.rewind()
        return buf
    }

    /**
     * Float32 NHWC output -> Bitmap ARGB_8888
     */
    fun floatBufferToBitmapNHWC(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        buf.rewind()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (buf.getFloat().coerceIn(0f, 1f) * 255f).toInt()
                val g = (buf.getFloat().coerceIn(0f, 1f) * 255f).toInt()
                val b = (buf.getFloat().coerceIn(0f, 1f) * 255f).toInt()
                pixels[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
