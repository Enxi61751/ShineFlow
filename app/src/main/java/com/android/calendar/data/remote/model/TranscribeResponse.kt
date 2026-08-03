package com.android.calendar.data.remote.model

data class TranscribeResponse(
    val text: String,
    val language: String? = null,
    val task: String? = null
)
