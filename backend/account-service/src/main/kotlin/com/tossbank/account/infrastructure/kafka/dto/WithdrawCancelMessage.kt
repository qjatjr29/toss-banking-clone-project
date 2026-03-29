package com.tossbank.account.infrastructure.kafka.dto

import java.math.BigDecimal

data class WithdrawCancelMessage(
    val fromAccountId: Long,
    val amount: BigDecimal,
    val idempotencyKey: String,
)