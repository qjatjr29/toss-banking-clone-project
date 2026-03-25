package com.tossbank.transfer.domain.exception

import com.tossbank.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class TransferErrorCode(
    override val status: HttpStatus,
    override val code: String,
    override val message: String
) : ErrorCode {

    // 계좌 관련
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "T001", "계좌를 찾을 수 없습니다"),
    SAME_ACCOUNT_TRANSFER(HttpStatus.BAD_REQUEST, "T002", "동일 계좌로는 이체할 수 없습니다"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "T003", "잔액이 부족합니다"),

    // 이체 관련
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "T004", "이체 내역을 찾을 수 없습니다"),
    DUPLICATE_TRANSFER(HttpStatus.CONFLICT, "T005", "이미 처리된 이체 요청입니다"),
    LOCK_ACQUISITION_FAILED(HttpStatus.CONFLICT, "T006", "현재 처리 중인 요청이 있습니다. 잠시 후 다시 시도해주세요"),

    // 타행 이체 관련
    EXTERNAL_TRANSFER_FAILED(HttpStatus.BAD_REQUEST, "T007", "타행 이체에 실패했습니다"),
    EXTERNAL_TRANSFER_IN_PROGRESS(HttpStatus.CONFLICT, "T008", "이체가 처리 중입니다. 잠시 후 다시 시도해주세요"),
    EXTERNAL_TRANSFER_MANUAL_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR, "T009", "이체 처리 중 오류가 발생했습니다. 고객센터에 문의해주세요"),
}