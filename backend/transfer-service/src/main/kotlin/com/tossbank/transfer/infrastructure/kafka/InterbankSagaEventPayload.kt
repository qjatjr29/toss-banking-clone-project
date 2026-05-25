package com.tossbank.transfer.infrastructure.kafka

import java.time.LocalDateTime

data class InterbankSagaEventPayload(
    val sagaId:   Long,
    val issuedAt: LocalDateTime = LocalDateTime.now(),
)