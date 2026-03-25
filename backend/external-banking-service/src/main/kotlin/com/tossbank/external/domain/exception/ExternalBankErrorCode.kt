package com.tossbank.external.domain.exception

import com.tossbank.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class ExternalBankErrorCode(
    override val status: HttpStatus,
    override val code: String,
    override val message: String
) : ErrorCode {
    INVALID_ACCOUNT(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT", "존재하지 않는 계좌번호입니다"),
    TRANSFER_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "TRANSFER_LIMIT_EXCEEDED", "이체 한도를 초과했습니다"),
    EXTERNAL_BANK_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "EXTERNAL_BANK_ERROR", "외부 은행 내부 오류가 발생했습니다")
}