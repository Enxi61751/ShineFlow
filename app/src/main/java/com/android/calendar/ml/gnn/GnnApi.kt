package com.android.calendar.ml.gnn

import retrofit2.http.Body
import retrofit2.http.POST

interface GnnApi {
    @POST("/gnn/suggest_time")
    suspend fun suggestTime(@Body req: GnnSuggestRequest): GnnSuggestResponse
}
