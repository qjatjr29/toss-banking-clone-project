package com.tossbank.transfer.infrastructure.client.dto

data class ExternalTransferResponse(
    val externalTransactionId: String,
)

data class ExternalTransferResultResponse(
    val idempotencyKey: String,
    val status: ExternalTransferResultStatus,
    val externalTransactionId: String? = null,
)

enum class ExternalTransferResultStatus {
    SUCCESS,
    FAILED,
    PROCESSING,
    NOT_FOUND,
}