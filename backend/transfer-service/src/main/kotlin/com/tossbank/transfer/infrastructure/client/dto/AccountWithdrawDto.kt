package com.tossbank.transfer.infrastructure.client.dto

import java.math.BigDecimal

data class AccountWithdrawRequest(
    val fromAccountId: Long,
    val fromMemberId: Long,
    val amount: BigDecimal,
    val idempotencyKey: String,
)

data class AccountWithdrawResponse(
    val fromAccountId: Long,
    val remainingBalance: BigDecimal,
)