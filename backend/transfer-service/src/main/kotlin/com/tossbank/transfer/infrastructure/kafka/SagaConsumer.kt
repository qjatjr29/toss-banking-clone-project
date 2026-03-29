package com.tossbank.transfer.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.tossbank.transfer.application.service.InternalTransferSagaOrchestrator
import com.tossbank.transfer.domain.model.InternalTransferSagaStatus
import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.dto.InquiryStatus
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class SagaConsumer(
    private val orchestrator: InternalTransferSagaOrchestrator,
    private val accountServiceClient: AccountServiceClient,
    private val sagaRepository: InternalTransferSagaRepository,
    private val objectMapper: ObjectMapper,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    // 출금 여부 재조회
    @KafkaListener(
        topics = [Topics.WITHDRAW_INQUIRY],
        groupId = "transfer-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleWithdrawInquiry(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        runBlocking {
            val payload = runCatching {
                objectMapper.readValue(record.value(), SagaEventPayload::class.java)
            }.getOrElse { e ->
                ack.acknowledge()
                return@runBlocking
            }

            val saga = withContext(dbDispatcher) {
                sagaRepository.findById(payload.sagaId).orElse(null)
            } ?: run {
                log.warn { "Saga를 찾을 수 없음 — sagaId=${payload.sagaId}" }
                ack.acknowledge()
                return@runBlocking
            }
            if (saga.status != InternalTransferSagaStatus.WITHDRAW_UNKNOWN) {
                log.warn { "WITHDRAW_UNKNOWN 상태 아님 — status=${saga.status} sagaId=${saga.id} 스킵" }
                ack.acknowledge()
                return@runBlocking
            }

            runCatching {
                accountServiceClient.inquireTransaction("${saga.idempotencyKey}-withdraw")
            }.onSuccess { result ->
                when (result.status) {
                    InquiryStatus.SUCCESS -> {
                        withContext(dbDispatcher) {
                            orchestrator.updateSagaAndClearOutbox(saga.id) {
                                it.markWithdrawCompleted(result.balance!!)
                            }
                        }
                        // WITHDRAW_COMPLETED 상태로 DB 커밋 완료 후 즉시 입금 진행
                        // proceedToDeposit 실패 시 → SagaRecoveryScheduler가 WITHDRAW_COMPLETED 방치 건 복구
                        runCatching { orchestrator.proceedToDeposit(saga.id) }
                            .onFailure { e ->
                                log.error(e) {
                                    "입금 진행 실패 — WITHDRAW_COMPLETED 유지, RecoveryScheduler 복구 예정: sagaId=${saga.id}"
                                }
                            }
                    }
                    InquiryStatus.NOT_FOUND -> {
                        withContext(dbDispatcher) {
                            orchestrator.updateSagaAndClearOutbox(saga.id) {
                                it.markWithdrawFailed()
                            }
                        }
                        log.warn { "출금 미발생 확인 → FAILED — sagaId=${saga.id}" }
                    }
                }
                ack.acknowledge()

            }.onFailure { e ->
                log.error(e) { "출금 재조회 실패 — sagaId=${saga.id}" }

                withContext(dbDispatcher) {
                    val freshSaga = sagaRepository.findById(saga.id).orElse(null)
                        ?: return@withContext

                    if (freshSaga.isRetryExhausted()) {
                        // TODO: Slack 알림
                        orchestrator.updateSagaAndClearOutbox(freshSaga.id) {
                            it.markManualRequired()
                        }
                        log.error { "출금 재조회 MANUAL_REQUIRED — sagaId=${saga.id} cause=${e.message}" }
                    } else {
                        orchestrator.updateSagaWithOutbox(freshSaga.id, OutboxEventType.WITHDRAW_INQUIRY) {
                            it.markWithdrawUnknown()
                        }
                        log.warn { "출금 재조회 실패 → 재시도 예약 — sagaId=${saga.id} cause=${e.message}" }
                    }
                }
                ack.acknowledge()
            }
        }
    }

    // 입금 여부 재조회
    @KafkaListener(
        topics = [Topics.DEPOSIT_INQUIRY],
        groupId = "transfer-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleDepositInquiry(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        runBlocking {
            val payload = runCatching {
                objectMapper.readValue(record.value(), SagaEventPayload::class.java)
            }.getOrElse { e ->
                ack.acknowledge()
                return@runBlocking
            }
            val saga = withContext(dbDispatcher) {
                sagaRepository.findById(payload.sagaId).orElse(null)
            } ?: run {
                log.warn { "Saga 없음 — sagaId=${payload.sagaId} 스킵" }
                ack.acknowledge()
                return@runBlocking
            }

            if (saga.status != InternalTransferSagaStatus.DEPOSIT_UNKNOWN) {
                log.warn { "DEPOSIT_UNKNOWN 상태 아님 — status=${saga.status} sagaId=${saga.id} 스킵" }
                ack.acknowledge()
                return@runBlocking
            }

            runCatching {
                accountServiceClient.inquireTransaction("${saga.idempotencyKey}-deposit")
            }.onSuccess { result ->
                when (result.status) {
                    InquiryStatus.SUCCESS -> {
                        // 입금 완료 확인 → COMPLETED
                        withContext(dbDispatcher) {
                            orchestrator.updateSagaAndClearOutbox(saga.id) {
                                it.markCompleted()
                            }
                        }
                        log.info { "입금 완료 확인 → COMPLETED — sagaId=${saga.id}" }
                    }
                    InquiryStatus.NOT_FOUND -> {
                        withContext(dbDispatcher) {
                            orchestrator.updateSagaWithOutbox(saga.id, OutboxEventType.COMPENSATE_WITHDRAW) {
                                it.markCompensating()
                            }
                        }
                        log.warn { "입금 미발생 확인 → COMPENSATING — sagaId=${saga.id}" }
                    }
                }
                ack.acknowledge()

            }.onFailure { e ->
                log.error(e) { "입금 재조회 실패 — sagaId=${saga.id}" }

                withContext(dbDispatcher) {
                    val freshSaga = sagaRepository.findById(saga.id).orElse(null)
                        ?: return@withContext

                    if (freshSaga.isRetryExhausted()) {
                        // TODO: Slack 알림
                        orchestrator.updateSagaAndClearOutbox(freshSaga.id) {
                            it.markManualRequired()
                        }
                        log.error { "입금 재조회 MANUAL_REQUIRED — sagaId=${saga.id} cause=${e.message}" }
                    } else {
                        orchestrator.updateSagaWithOutbox(freshSaga.id, OutboxEventType.DEPOSIT_INQUIRY) {
                            it.markDepositUnknown()
                        }
                        log.warn { "입금 재조회 실패 → 재시도 예약 — sagaId=${saga.id} cause=${e.message}" }
                    }
                }
                ack.acknowledge()
            }
        }
    }
}