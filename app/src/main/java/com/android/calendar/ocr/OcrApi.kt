package com.android.calendar.ocr

import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface OcrApi {

    @Multipart
    @POST("ocr") // 如果你的服务是 /ocr
    suspend fun recognize(
        @Part file: MultipartBody.Part
    ): OcrResp
}
