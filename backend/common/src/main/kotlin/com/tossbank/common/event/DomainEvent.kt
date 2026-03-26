package com.tossbank.common.event

import java.time.LocalDateTime
import java.util.*

data class DomainEvent<T>(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val payload: T
)
