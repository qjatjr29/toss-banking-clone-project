package com.tossbank.external.presentation.dto

data class ExternalTransferResponse(
    val externalTransactionId: String,
    val status: ExternalTransferStatus
)

data class ExternalTransferStatusResponse(
    val externalTransactionId: String,
    val status: ExternalTransferStatus
)

data class ExternalErrorResponse(
    val code: String,
    val message: String
)

enum class ExternalTransferStatus {
    COMPLETED,   // 입금 완료
    PENDING,     // 처리 중
    NOT_FOUND    // 거래 없음
}