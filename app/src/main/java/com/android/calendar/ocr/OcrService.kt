package com.android.calendar.ocr

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

import ws.xsoh.etar.BuildConfig


object OcrService {

    // 你在 build.gradle.kts 里用 buildConfigField 定义 OCR_BASE_URL
    // 例如 "http://10.0.2.2:8000/"
    private val baseUrl: String = BuildConfig.OCR_BASE_URL

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val api: OcrApi by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OcrApi::class.java)
    }

    /**
     * 传入图片文件，返回识别结果
     */
    suspend fun recognize(file: File): OcrResp {
        val part = OcrUploader.fileToPart(file, partName = "file")
        return api.recognize(part)
    }
}
