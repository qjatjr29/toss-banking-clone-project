package com.tossbank.transfer.application.service

import com.tossbank.transfer.application.dto.InterbankTransferRequest
import com.tossbank.transfer.domain.model.InterbankTransferSaga
import com.tossbank.transfer.infrastructure.kafka.InterbankSagaEventPayload
import com.tossbank.transfer.infrastructure.kafka.WithdrawCancelMessagePayload
import com.tossbank.transfer.infrastructure.outbox.OutboxEvent
import com.tossbank.transfer.infrastructure.outbox.OutboxEventRepository
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.outbox.OutboxStatus
import com.tossbank.transfer.infrastructure.persistence.InterbankTransferSagaRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Component
class InterbankSagaTransactionExecutor(
    private val sagaRepository:   InterbankTransferSagaRepository,
    private val outboxRepository: OutboxEventRepository,
    private val objectMapper:     ObjectMapper,
) {

    @Transactional
    fun findOrCreateSaga(
        memberId: Long,
        request:  InterbankTransferRequest,
    ): InterbankTransferSaga =
        sagaRepository.findByIdempotencyKey(request.idempotencyKey)
            ?: sagaRepository.save(
                InterbankTransferSaga(
                    fromMemberId      = memberId,
                    fromAccountId     = request.fromAccountId,
                    fromAccountNumber = request.fromAccountNumber,
                    toAccountNumber   = request.toAccountNumber,
                    toBankCode        = request.toBankCode,
                    toMemberName      = request.toMemberName,
                    fromMemberName    = request.fromMemberName,
                    amount            = request.amount,
                    description       = request.description,
                    idempotencyKey    = request.idempotencyKey,
                )
            )

    /**
     * Saga 상태 변경 + Outbox 등록 — 단일 트랜잭션
     * DB 커밋과 Outbox 저장이 원자적으로 처리됨
     * OutboxPublishScheduler가 Outbox를 읽어 Kafka 발행
     */
    @Transactional
    fun updateSagaWithOutbox(
        sagaId:    Long,
        eventType: OutboxEventType,
        mutate:    (InterbankTransferSaga) -> Unit,
    ) {
        val saga = sagaRepository.findById(sagaId).orElseThrow()
        mutate(saga)

        val payload = buildPayload(eventType, saga)

        outboxRepository.save(
            OutboxEvent(
                sagaId      = sagaId,
                eventType   = eventType,
                topic       = eventType.toKafkaTopic(),
                payload     = payload,
                scheduledAt = saga.nextRetryAt,
            )
        )
    }

    /**
     * Saga 상태 변경 + 미발행 Outbox 제거 — 단일 트랜잭션
     * 성공/실패 확정 시 불필요한 Outbox 제거 (중복 발행 방지)
     */
    @Transactional
    fun updateSagaAndClearOutbox(
        sagaId: Long,
        mutate: (InterbankTransferSaga) -> Unit,
    ): InterbankTransferSaga {
        val saga = sagaRepository.findById(sagaId).orElseThrow()
        mutate(saga)
        outboxRepository.deleteAllBySagaIdAndStatus(sagaId, OutboxStatus.PENDING)
        return saga
    }

    /**
     * 복구 스케줄러에서 사용
     * PENDING Outbox가 없으면 상태에 맞는 Outbox를 새로 등록
     */
    @Transactional
    fun ensureOutboxExists(saga: InterbankTransferSaga) {
        val hasPendingOutbox = outboxRepository
            .existsBySagaIdAndStatus(saga.id, OutboxStatus.PENDING)
        if (hasPendingOutbox) return

        val eventType = when (saga.status) {
            com.tossbank.transfer.domain.model.InterbankTransferSagaStatus.TRANSFER_UNKNOWN ->
                OutboxEventType.INTERBANK_TRANSFER_INQUIRY
            com.tossbank.transfer.domain.model.InterbankTransferSagaStatus.COMPENSATING ->
                OutboxEventType.COMPENSATE_WITHDRAW
            else -> return
        }

        outboxRepository.save(
            OutboxEvent(
                sagaId      = saga.id,
                eventType   = eventType,
                topic       = eventType.toKafkaTopic(),
                payload     = buildPayload(eventType, saga),
                scheduledAt = LocalDateTime.now(),
            )
        )
    }

    @Transactional
    fun publishOutboxEvent(event: OutboxEvent) {
        event.status = OutboxStatus.PUBLISHED
        if (event.eventType == OutboxEventType.COMPENSATE_WITHDRAW) {
            val saga = sagaRepository.findById(event.sagaId).orElse(null) ?: return
            saga.markCompensated()
        }
    }

    fun fetchPublishableEvents(): List<OutboxEvent> =
        outboxRepository.findPublishableEvents(LocalDateTime.now())

    private fun buildPayload(
        eventType: OutboxEventType,
        saga:      InterbankTransferSaga,
    ): String = when (eventType) {
        OutboxEventType.COMPENSATE_WITHDRAW ->
            objectMapper.writeValueAsString(
                WithdrawCancelMessagePayload(
                    fromAccountId  = saga.fromAccountId,
                    amount         = saga.amount,
                    idempotencyKey = saga.idempotencyKey,
                )
            )
        else ->
            objectMapper.writeValueAsString(
                InterbankSagaEventPayload(sagaId = saga.id)
            )
    }
}