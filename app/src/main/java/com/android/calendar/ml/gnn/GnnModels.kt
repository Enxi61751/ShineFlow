package com.android.calendar.ml.gnn

data class GnnSuggestRequest(
    val userId: String,
    val recentEventTitles: List<String>,
    val recentEventLocations: List<String>,
    val recentEventHours: List<Int>,     // 0..23
    val topK: Int = 5
)

data class GnnSuggestResponse(
    val suggestedHours: List<Int>,       // 0..23
    val confidences: List<Float>         // same length
)
