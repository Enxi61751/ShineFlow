package com.android.calendar.data.remote.api

import com.android.calendar.data.remote.model.TranscribeResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface TranscribeApi {

    @Multipart
    @POST("transcribe")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("language") language: RequestBody,
        @Part("task") task: RequestBody
    ): TranscribeResponse
}
