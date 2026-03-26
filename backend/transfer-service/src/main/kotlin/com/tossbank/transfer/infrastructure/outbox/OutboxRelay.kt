package com.tossbank.transfer.infrastructure.outbox

import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

@Component
class OutboxRelay(
    private val outboxEventRepository: OutboxEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun relay() {
        val events = outboxEventRepository
            .findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)

        if (events.isEmpty()) return

        events.forEach { event ->
            runCatching {
                kafkaTemplate.send(event.topic, event.aggregateId, event.payload).get()
                event.markPublished()
                log.info { "Outbox 발행 성공: id=${event.id} topic=${event.topic}" }
            }.onFailure { e ->
                event.incrementRetry()
                log.warn(e) { "Outbox 발행 실패: id=${event.id} retry=${event.retryCount}" }
                // TODO: Slack 알림
                if (event.status == OutboxStatus.DEAD) {
                    log.error { "Outbox DEAD: id=${event.id} — 수동 처리 필요" }
                }
            }
            outboxEventRepository.save(event)
        }
    }
}