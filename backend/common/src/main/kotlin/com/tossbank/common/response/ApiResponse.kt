package com.tossbank.common.response

data class ApiResponse<T> (
    val success: Boolean,
    val data: T?,
    val error: ErrorResponse? = null
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(success = true, data = data)
        }
        fun error(code: String, message: String): ApiResponse<Unit> {
            return ApiResponse(
                success = false,
                data = null,
                error = ErrorResponse(code, message)
            )
        }
    }
}