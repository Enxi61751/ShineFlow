package com.android.calendar.review.network

import com.google.gson.annotations.SerializedName


data class ReviewEventRequest(
    @SerializedName("event_id") val eventId: String?,
    val title: String,
    @SerializedName("start_time") val startTime: String?,
    @SerializedName("end_time") val endTime: String?,
    val description: String = "",
    val location: String = "",
    @SerializedName("all_day") val allDay: Boolean = false,
    @SerializedName("completion_status") val completionStatus: String = "unknown",
    @SerializedName("completion_note") val completionNote: String = ""
)


data class AgentProfileRequest(
    @SerializedName("assistant_name") val assistantName: String,
    val personality: String,
    val tone: String,
    val goals: List<String>
)


data class ReviewGenerateRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("review_date") val reviewDate: String,
    val timezone: String,
    val reflection: String,
    val mood: String,
    @SerializedName("energy_level") val energyLevel: Int,
    @SerializedName("satisfaction_score") val satisfactionScore: Int,
    val events: List<ReviewEventRequest>,
    @SerializedName("agent_profile") val agentProfile: AgentProfileRequest
)


data class ReviewResultDto(
    val headline: String = "",
    val summary: String = "",
    val achievements: List<String> = emptyList(),
    @SerializedName("unfinished_items") val unfinishedItems: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    @SerializedName("tomorrow_focus") val tomorrowFocus: List<String> = emptyList(),
    val encouragement: String = "",
    @SerializedName("completion_score") val completionScore: Int = 0
)


data class ReviewMemoryDto(
    val id: String,
    @SerializedName("user_id") val userId: String,
    val kind: String,
    val content: String,
    val importance: Int,
    @SerializedName("source_review_id") val sourceReviewId: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)


data class ReviewGenerateResponse(
    val success: Boolean,
    @SerializedName("review_id") val reviewId: String,
    @SerializedName("generated_by") val generatedBy: String,
    val model: String,
    val result: ReviewResultDto,
    @SerializedName("used_memories") val usedMemories: List<ReviewMemoryDto> = emptyList(),
    @SerializedName("saved_memories") val savedMemories: List<ReviewMemoryDto> = emptyList(),
    @SerializedName("created_at") val createdAt: String
)


data class MemoryListResponse(
    val success: Boolean,
    val items: List<ReviewMemoryDto> = emptyList()
)


data class DeleteResponse(
    val success: Boolean,
    val deleted: Int
)
