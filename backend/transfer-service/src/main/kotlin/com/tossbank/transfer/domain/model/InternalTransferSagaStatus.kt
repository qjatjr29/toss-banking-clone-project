package com.tossbank.transfer.domain.model

enum class InternalTransferSagaStatus {
    PENDING,
    WITHDRAW_COMPLETED,
    WITHDRAW_UNKNOWN,
    DEPOSIT_UNKNOWN,
    COMPENSATING,
    COMPENSATED,
    COMPLETED,
    FAILED,
    MANUAL_REQUIRED,
}