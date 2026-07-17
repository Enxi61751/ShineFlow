package com.android.calendar.data.repo

import com.android.calendar.data.remote.client.NetworkModule
import com.android.calendar.data.remote.model.TranscribeResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class TranscribeRepository {

    private val api = NetworkModule.transcribeApi

    suspend fun transcribeAudio(
        audioFile: File,
        language: String = "zh",
        task: String = "transcribe"
    ): TranscribeResponse {
        val mime = "audio/*".toMediaTypeOrNull()
        val fileBody = audioFile.asRequestBody(mime)
        val filePart = MultipartBody.Part.createFormData(
            name = "file",                       // 手册字段名是 file:contentReference[oaicite:4]{index=4}
            filename = audioFile.name,
            body = fileBody
        )

        val langBody = language.toRequestBody("text/plain".toMediaTypeOrNull())
        val taskBody = task.toRequestBody("text/plain".toMediaTypeOrNull())

        return api.transcribe(
            file = filePart,
            language = langBody,
            task = taskBody
        )
    }
}
