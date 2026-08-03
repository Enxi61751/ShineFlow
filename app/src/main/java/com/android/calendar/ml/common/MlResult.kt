package com.android.calendar.ml.common

sealed class MlResult<out T> {
    data class Ok<T>(val value: T) : MlResult<T>()
    data class Err(val message: String, val throwable: Throwable? = null) : MlResult<Nothing>()
}
