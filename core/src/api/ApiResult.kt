package com.salahoard.mustnotify.api

import com.salahoard.mustnotify.domain.InverterSnapshot

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** HTTP 200 but body code 401 ("Authentication failed"): the token is missing, wrong or expired. */
    data object AuthRequired : ApiResult<Nothing>

    /** Body code 115140: wrong account or password (HTTP status is still 200). */
    data object BadCredentials : ApiResult<Nothing>

    data class ServerError(val code: Int, val message: String?) : ApiResult<Nothing>
    data class BadResponse(val reason: String) : ApiResult<Nothing>
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>
}

/** The one call the poller needs; lets tests (and a future LAN client) stand in for the cloud. */
interface ReadingsSource {
    suspend fun readDevice(device: DeviceRef, token: String? = null): ApiResult<InverterSnapshot>
}
