package com.android.calendar.ml.gnn

import com.android.calendar.ml.common.MlResult
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GnnRepository(baseUrl: String) {

    private val api: GnnApi

    init {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logger)
            .build()

        api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GnnApi::class.java)
    }

    suspend fun suggestHours(req: GnnSuggestRequest): MlResult<GnnSuggestResponse> {
        return try {
            MlResult.Ok(api.suggestTime(req))
        } catch (t: Throwable) {
            MlResult.Err("GNN request failed: ${t.message}", t)
        }
    }
}
