package com.tossbank.transfer.infrastructure.kafka

import java.math.BigDecimal
import java.time.LocalDateTime

data class SagaEventPayload(
    val sagaId: Long,
    val issuedAt: LocalDateTime = LocalDateTime.now(),
)

data class WithdrawCancelMessagePayload(
    val fromAccountId: Long,
    val amount: BigDecimal,
    val idempotencyKey: String,
)