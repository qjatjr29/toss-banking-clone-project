package com.tossbank.common.exception

import com.tossbank.common.response.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    // 우리가 만든 CustomException이 터지면 여기서 캐치하여 일관된 ApiResponse로 반환
    @ExceptionHandler(CustomException::class)
    fun handleCustomException(e: CustomException): ResponseEntity<ApiResponse<Unit>> {
        val response = ApiResponse.error(
            code = e.errorCode.code,
            message = e.errorCode.message
        )
        return ResponseEntity.status(e.errorCode.status).body(response)
    }

    // 그 외 예상치 못한 모든 에러 (500)
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        val response = ApiResponse.error(
            code = CommonErrorCode.INTERNAL_SERVER_ERROR.code,
            message = "서버 내부 오류가 발생했습니다. (원인: ${e.message})"
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }
}