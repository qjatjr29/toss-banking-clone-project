package com.tossbank.transfer.application.dto

import java.math.BigDecimal

data class InternalTransferRequest(
    val fromAccountId: Long,
    val toAccountId: Long,
    val toAccountNumber: String,
    val toMemberName: String,
    val fromMemberName: String,
    val amount: BigDecimal,
    val description: String?,
    val idempotencyKey: String,
)