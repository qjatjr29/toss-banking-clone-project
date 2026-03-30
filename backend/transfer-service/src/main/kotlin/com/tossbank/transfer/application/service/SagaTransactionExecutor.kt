package com.tossbank.transfer.application.service

import com.tossbank.transfer.application.dto.InternalTransferRequest
import com.tossbank.transfer.domain.model.InternalTransferSaga
import com.tossbank.transfer.domain.model.InternalTransferSagaStatus
import com.tossbank.transfer.infrastructure.kafka.SagaEventPayload
import com.tossbank.transfer.infrastructure.kafka.WithdrawCancelMessagePayload
import com.tossbank.transfer.infrastructure.outbox.OutboxEvent
import com.tossbank.transfer.infrastructure.outbox.OutboxEventRepository
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.outbox.OutboxStatus
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@Service
class SagaTransactionExecutor(
    private val sagaRepository: InternalTransferSagaRepository,
    private val outboxRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun findOrCreateSaga(memberId: Long, request: InternalTransferRequest): InternalTransferSaga =
        sagaRepository.findByIdempotencyKey(request.idempotencyKey)
            ?: sagaRepository.save(
                InternalTransferSaga(
                    fromMemberId    = memberId,
                    fromAccountId   = request.fromAccountId,
                    toAccountId     = request.toAccountId,
                    toAccountNumber = request.toAccountNumber,
                    toMemberName    = request.toMemberName,
                    fromMemberName  = request.fromMemberName,
                    amount          = request.amount,
                    description     = request.description,
                    idempotencyKey  = request.idempotencyKey,
                )
            )

    @Transactional
    fun updateSagaWithOutbox(
        sagaId: Long,
        eventType: OutboxEventType,
        mutate: (InternalTransferSaga) -> Unit,
    ) {
        val saga = sagaRepository.findById(sagaId).orElseThrow()
        mutate(saga)
        val payload = when (eventType) {
            OutboxEventType.COMPENSATE_WITHDRAW ->
                objectMapper.writeValueAsString(
                    WithdrawCancelMessagePayload(
                        fromAccountId  = saga.fromAccountId,
                        amount         = saga.amount,
                        idempotencyKey = saga.idempotencyKey,
                    )
                )
            else -> objectMapper.writeValueAsString(SagaEventPayload(sagaId = sagaId))
        }
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

    @Transactional
    fun updateSagaAndClearOutbox(sagaId: Long, mutate: (InternalTransferSaga) -> Unit): InternalTransferSaga {
        val saga = sagaRepository.findById(sagaId).orElseThrow()
        mutate(saga)
        outboxRepository.deleteAllBySagaIdAndStatus(sagaId, OutboxStatus.PENDING)
        return saga
    }

    @Transactional
    fun ensureOutboxExists(saga: InternalTransferSaga) {
        val hasPendingOutbox = outboxRepository.existsBySagaIdAndStatus(saga.id, OutboxStatus.PENDING)
        if (hasPendingOutbox) return

        val eventType = when (saga.status) {
            InternalTransferSagaStatus.WITHDRAW_UNKNOWN -> OutboxEventType.WITHDRAW_INQUIRY
            InternalTransferSagaStatus.DEPOSIT_UNKNOWN  -> OutboxEventType.DEPOSIT_INQUIRY
            InternalTransferSagaStatus.COMPENSATING     -> OutboxEventType.COMPENSATE_WITHDRAW
            else -> return
        }
        val payload = when (eventType) {
            OutboxEventType.COMPENSATE_WITHDRAW ->
                objectMapper.writeValueAsString(
                    WithdrawCancelMessagePayload(
                        fromAccountId  = saga.fromAccountId,
                        amount         = saga.amount,
                        idempotencyKey = saga.idempotencyKey,
                    )
                )
            else -> objectMapper.writeValueAsString(SagaEventPayload(sagaId = saga.id))
        }
        outboxRepository.save(
            OutboxEvent(
                sagaId      = saga.id,
                eventType   = eventType,
                topic       = eventType.toKafkaTopic(),
                payload     = payload,
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
}