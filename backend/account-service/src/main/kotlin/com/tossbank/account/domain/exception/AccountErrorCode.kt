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
    ACCOUNT_ALREADY_CLOSED(HttpStatus.BAD_REQUEST, "A007", "이미 해지된 계좌입니다."),
    INVALID_ACCOUNT_STATUS(HttpStatus.BAD_REQUEST, "A008", "현재 계좌 상태에서 허용되지 않는 작업입니다."),
    ACCOUNT_HAS_REMAINING_BALANCE(HttpStatus.BAD_REQUEST, "A009", "잔액이 남아있어 계좌를 해지할 수 없습니다."),

    // 이체
    TRANSFER_SAME_ACCOUNT(HttpStatus.BAD_REQUEST, "A010", "출금 계좌와 입금 계좌가 동일합니다."),
    LOCK_ACQUISITION_FAILED(HttpStatus.CONFLICT, "A011", "잠시 후 다시 시도해 주세요. (락 획득 실패)"),

    // 타행 관련 (추후 구현)
    EXTERNAL_TRANSFER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "A012", "타행 이체는 현재 지원하지 않습니다."),
    EXTERNAL_BANK_INQUIRY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "A013", "타행 계좌 실명조회는 현재 지원하지 않습니다."),

    INVALID_TRANSFER_STATE_TRANSITION(HttpStatus.INTERNAL_SERVER_ERROR, "A014", "이체 상태 전이가 올바르지 않습니다."),
    EXTERNAL_TRANSFER_FAILED(HttpStatus.BAD_REQUEST, "A015", "타행 입금이 거절됐습니다."),
    EXTERNAL_TRANSFER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "A016", "타행 은행 응답이 지연되고 있습니다."),
    EXTERNAL_TRANSFER_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "A017", "타행 은행 서버 오류가 발생했습니다."),
    EXTERNAL_TRANSFER_UNKNOWN(HttpStatus.ACCEPTED, "A018", "이체 결과를 확인 중입니다. 완료되면 알림을 보내드릴게요."),
}