package com.tossbank.transfer.infrastructure.scheduler

import com.tossbank.transfer.application.service.InternalTransferSagaOrchestrator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class OutboxPublishScheduler(
    private val orchestrator: InternalTransferSagaOrchestrator,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Qualifier("schedulerScope") private val scope: CoroutineScope,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    @Scheduled(fixedDelay = 10_000)
    fun publishPendingEvents() {
        scope.launch {
            val events = orchestrator.fetchPublishableEvents()
            if (events.isEmpty()) return@launch

            events.forEach { event ->
                runCatching {
                    // Kafka 발행
                    kafkaTemplate.send(
                        event.topic,
                        event.sagaId.toString(),
                        event.payload,
                    ).get()

                }.onSuccess {
                    // DB 업데이트
                    withContext(dbDispatcher) {
                        orchestrator.publishOutboxEvent(event)
                        // COMPENSATE_WITHDRAW 이면 내부에서 markCompensated() 처리
                    }
                    log.debug { "Outbox 발행 완료 — eventId=${event.id} topic=${event.topic}" }

                }.onFailure { e ->
                    // 발행 실패 → 다음 스케줄에 재시도 (OutboxEvent 상태 변경 없음)
                    log.error(e) { "Outbox 발행 실패 — eventId=${event.id} topic=${event.topic}" }
                }
            }
        }
    }
}