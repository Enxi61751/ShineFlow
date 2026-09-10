package com.android.calendar.review.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query


interface ReviewApi {
    @POST("api/reviews/generate")
    suspend fun generateReview(@Body request: ReviewGenerateRequest): ReviewGenerateResponse

    @GET("api/reviews/memories/{userId}")
    suspend fun getMemories(
        @Path("userId") userId: String,
        @Query("limit") limit: Int = 30
    ): MemoryListResponse

    @DELETE("api/reviews/memories/{userId}")
    suspend fun clearMemories(@Path("userId") userId: String): DeleteResponse
}
