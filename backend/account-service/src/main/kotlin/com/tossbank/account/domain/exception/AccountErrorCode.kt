package com.tossbank.account.domain.exception

import com.tossbank.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class AccountErrorCode(
    override val status: HttpStatus,
    override val code: String,
    override val message: String
) : ErrorCode {
    // 조회 관련 에러
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "요청하신 계좌를 찾을 수 없습니다."),
    UNAUTHORIZED_ACCOUNT_ACCESS(HttpStatus.FORBIDDEN, "A002", "해당 계좌에 접근할 권한이 없습니다."),

    // 비즈니스 룰 (입출금) 에러
    INVALID_DEPOSIT_AMOUNT(HttpStatus.BAD_REQUEST, "A003", "입금액은 0보다 커야 합니다."),
    INVALID_WITHDRAW_AMOUNT(HttpStatus.BAD_REQUEST, "A004", "출금액은 0보다 커야 합니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "A005", "계좌 잔액이 부족합니다."),

    // 계좌 상태 관련 에러
    ACCOUNT_SUSPENDED(HttpStatus.BAD_REQUEST, "A006", "정지된 계좌입니다. 거래가 불가능합니다."),
    ACCOUNT_ALREADY_CLOSED(HttpStatus.BAD_REQUEST, "A007", "이미 해지된 계좌입니다.")
}