package com.tossbank.transfer.infrastructure.kafka

import com.tossbank.transfer.application.service.InternalTransferSagaOrchestrator
import com.tossbank.transfer.application.service.SagaTransactionExecutor
import com.tossbank.transfer.domain.model.InternalTransferSaga
import com.tossbank.transfer.domain.model.InternalTransferSagaStatus
import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.dto.InquiryStatus
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private val log = KotlinLogging.logger {}

@Component
class SagaConsumer(
    private val orchestrator:            InternalTransferSagaOrchestrator,
    private val sagaTransactionExecutor: SagaTransactionExecutor,
    private val accountServiceClient:    AccountServiceClient,
    private val sagaRepository:          InternalTransferSagaRepository,
    private val objectMapper:            ObjectMapper,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
    @Qualifier("schedulerScope") private val scope: CoroutineScope,  // 추가
) {

    @KafkaListener(
        topics = [Topics.WITHDRAW_INQUIRY],
        groupId = "transfer-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleWithdrawInquiry(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        scope.launch {
            runCatching {
                processWithdrawInquiry(record)
            }.onSuccess {
                ack.acknowledge()
            }.onFailure { e ->
                log.error(e) { "출금 재조회 처리 실패 — offset=${record.offset()}" }
                ack.acknowledge()
            }
        }
    }

    @KafkaListener(
        topics = [Topics.DEPOSIT_INQUIRY],
        groupId = "transfer-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleDepositInquiry(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        scope.launch {
            runCatching {
                processDepositInquiry(record)
            }.onSuccess {
                ack.acknowledge()
            }.onFailure { e ->
                log.error(e) { "입금 재조회 처리 실패 — offset=${record.offset()}" }
                ack.acknowledge()
            }
        }
    }

    private suspend fun processWithdrawInquiry(record: ConsumerRecord<String, String>) {
        val payload = runCatching {
            objectMapper.readValue(record.value(), SagaEventPayload::class.java)
        }.getOrElse {
            log.error { "페이로드 파싱 실패 — value=${record.value()}" }
            return
        }

        val saga = withContext(dbDispatcher) {
            sagaRepository.findById(payload.sagaId).orElse(null)
        } ?: run {
            log.warn { "Saga를 찾을 수 없음 — sagaId=${payload.sagaId}" }
            return
        }

        if (saga.status != InternalTransferSagaStatus.WITHDRAW_UNKNOWN) {
            log.warn { "WITHDRAW_UNKNOWN 상태 아님 — status=${saga.status} sagaId=${saga.id} 스킵" }
            return
        }

        runCatching {
            accountServiceClient.inquireTransaction("${saga.idempotencyKey}-withdraw")
        }.onSuccess { result ->
            when (result.status) {
                InquiryStatus.SUCCESS -> {
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaAndClearOutbox(saga.id) {
                            it.markWithdrawCompleted(result.balance!!)
                        }
                    }
                    runCatching { orchestrator.proceedToDeposit(saga.id) }
                        .onFailure { e ->
                            log.error(e) {
                                "입금 진행 실패 — WITHDRAW_COMPLETED 유지, RecoveryScheduler 복구 예정: sagaId=${saga.id}"
                            }
                        }
                }
                InquiryStatus.NOT_FOUND -> {
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaAndClearOutbox(saga.id) {
                            it.markWithdrawFailed()
                        }
                    }
                    log.warn { "출금 미발생 확인 → FAILED — sagaId=${saga.id}" }
                }
            }
        }.onFailure { e ->
            log.error(e) { "출금 재조회 실패 — sagaId=${saga.id}" }
            handleRetryOrEscalate(
                sagaId         = saga.id,
                retryType      = OutboxEventType.WITHDRAW_INQUIRY,
                retryMutate    = { it.markWithdrawUnknown() },
                escalateMutate = { it.markManualRequired() },
            )
        }
    }

    private suspend fun processDepositInquiry(record: ConsumerRecord<String, String>) {
        val payload = runCatching {
            objectMapper.readValue(record.value(), SagaEventPayload::class.java)
        }.getOrElse {
            log.error { "페이로드 파싱 실패 — value=${record.value()}" }
            return
        }

        val saga = withContext(dbDispatcher) {
            sagaRepository.findById(payload.sagaId).orElse(null)
        } ?: run {
            log.warn { "Saga 없음 — sagaId=${payload.sagaId} 스킵" }
            return
        }

        if (saga.status != InternalTransferSagaStatus.DEPOSIT_UNKNOWN) {
            log.warn { "DEPOSIT_UNKNOWN 상태 아님 — status=${saga.status} sagaId=${saga.id} 스킵" }
            return
        }

        runCatching {
            accountServiceClient.inquireTransaction("${saga.idempotencyKey}-deposit")
        }.onSuccess { result ->
            when (result.status) {
                InquiryStatus.SUCCESS -> {
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaAndClearOutbox(saga.id) {
                            it.markCompleted()
                        }
                    }
                    log.info { "입금 완료 확인 → COMPLETED — sagaId=${saga.id}" }
                }
                InquiryStatus.NOT_FOUND -> {
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaWithOutbox(
                            sagaId    = saga.id,
                            eventType = OutboxEventType.COMPENSATE_WITHDRAW,
                        ) { it.markCompensating() }
                    }
                    log.warn { "입금 미발생 확인 → COMPENSATING — sagaId=${saga.id}" }
                }
            }
        }.onFailure { e ->
            log.error(e) { "입금 재조회 실패 — sagaId=${saga.id}" }
            handleRetryOrEscalate(
                sagaId         = saga.id,
                retryType      = OutboxEventType.DEPOSIT_INQUIRY,
                retryMutate    = { it.markDepositUnknown() },
                escalateMutate = { it.markManualRequired() },
            )
        }
    }

    private suspend fun handleRetryOrEscalate(
        sagaId:         Long,
        retryType:      OutboxEventType,
        retryMutate:    (InternalTransferSaga) -> Unit,
        escalateMutate: (InternalTransferSaga) -> Unit,
    ) {
        withContext(dbDispatcher) {
            val freshSaga = sagaRepository.findById(sagaId).orElse(null)
                ?: return@withContext

            if (freshSaga.isRetryExhausted()) {
                sagaTransactionExecutor.updateSagaAndClearOutbox(freshSaga.id, escalateMutate)
                log.error { "재조회 MANUAL_REQUIRED — sagaId=$sagaId" }
            } else {
                sagaTransactionExecutor.updateSagaWithOutbox(
                    sagaId    = freshSaga.id,
                    eventType = retryType,
                    mutate    = retryMutate,
                )
                log.warn { "재조회 실패 → 재시도 예약 — sagaId=$sagaId" }
            }
        }
    }
}