package com.android.calendar.data.remote.client

import com.android.calendar.data.remote.api.TranscribeApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {

    // TODO: 换成你服务器实际 IP（手册里是 http://服务器IP:8000/） :contentReference[oaicite:3]{index=3}
    private const val BASE_URL = "https://u836809-92e6-37d8b4ba.bjb2.seetacloud.com:8443/"

    private val okHttpClient: OkHttpClient by lazy {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()
    }

    val transcribeApi: TranscribeApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TranscribeApi::class.java)
    }
}
