package com.tossbank.transfer.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.tossbank.common.event.DomainEvent
import org.springframework.stereotype.Component

@Component
class OutboxEventStore(       // 이름 변경
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    fun store(topic: String, aggregateId: String, eventType: String, payload: Any) {
        val domainEvent = DomainEvent(
            eventType = eventType,
            payload   = objectMapper.writeValueAsString(payload),
        )
        outboxEventRepository.save(
            OutboxEvent(
                topic       = topic,
                aggregateId = aggregateId,
                payload     = objectMapper.writeValueAsString(domainEvent),
            )
        )
    }
}