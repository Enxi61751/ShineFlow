package com.android.calendar.ml.stn

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer

class TFLiteModel(
    context: Context,
    assetModelPath: String,
    numThreads: Int = 2
) : AutoCloseable {

    private val interpreter: Interpreter

    init {
        val model = FileUtil.loadMappedFile(context, assetModelPath)
        val options = Interpreter.Options().apply { setNumThreads(numThreads) }
        interpreter = Interpreter(model, options)
    }

    fun run(input: Any, output: Any) {
        interpreter.run(input, output)
    }

    fun runForMultipleInputsOutputs(inputs: Array<Any>, outputs: MutableMap<Int, Any>) {
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
    }

    fun allocateDirectFloatBuffer(sizeBytes: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(sizeBytes)
    }

    override fun close() {
        interpreter.close()
    }
}
