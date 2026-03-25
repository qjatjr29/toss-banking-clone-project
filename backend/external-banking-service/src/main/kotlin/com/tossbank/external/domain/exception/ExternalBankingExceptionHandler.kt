package com.tossbank.external.domain.exception

import com.tossbank.external.presentation.dto.ExternalErrorResponse
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val log = KotlinLogging.logger {}

@RestControllerAdvice
@Order(1)  // common GlobalExceptionHandler보다 먼저 실행
class ExternalBankingExceptionHandler {

    // 4xx
    @ExceptionHandler(ExternalBankClientException::class)
    fun handleClientException(e: ExternalBankClientException): ResponseEntity<ExternalErrorResponse> {
        log.warn { "[MockBank] 클라이언트 오류: ${e.message}" }
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ExternalErrorResponse(code = e.errorCode.code, message = e.errorCode.message))
    }

    // 5xx
    @ExceptionHandler(ExternalBankServerException::class)
    fun handleServerException(e: ExternalBankServerException): ResponseEntity<ExternalErrorResponse> {
        log.error { "[MockBank] 서버 오류: ${e.message}" }
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ExternalErrorResponse(code = e.errorCode.code, message = e.errorCode.message))
    }
}