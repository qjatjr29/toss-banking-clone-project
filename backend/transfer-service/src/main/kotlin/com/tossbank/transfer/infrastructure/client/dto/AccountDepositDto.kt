package com.tossbank.transfer.infrastructure.client.dto

import java.math.BigDecimal

data class AccountDepositRequest(
    val toAccountId: Long,
    val amount: BigDecimal,
    val fromMemberId: Long,
    val fromMemberName: String,
    val idempotencyKey: String,
    val description: String?,
)

data class AccountDepositResponse(
    val toAccountId: Long,
    val balanceAfterTx: BigDecimal,
)