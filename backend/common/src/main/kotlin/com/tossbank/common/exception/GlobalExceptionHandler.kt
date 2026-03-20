package com.tossbank.common.exception

import com.tossbank.common.response.ApiResponse
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    // 우리가 만든 CustomException이 터지면 여기서 캐치하여 일관된 ApiResponse로 반환
    @ExceptionHandler(CustomException::class)
    fun handleCustomException(e: CustomException): ResponseEntity<ApiResponse<Unit>> {
        log.warn { "비즈니스 예외: code=${e.errorCode.code}, message=${e.message}" }
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ApiResponse.error(e.errorCode.code, e.errorCode.message))
    }

    // 그 외 예상치 못한 모든 에러 (500)
    @ExceptionHandler(Exception::class)
    fun handleGeneralException(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        log.error(e) { "처리되지 않은 예외: ${e.message}" }

        return ResponseEntity
            .internalServerError()
            .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR.code,
                "서버 내부 오류가 발생했습니다. (원인: ${e.message})"))
    }
}