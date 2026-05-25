package com.tossbank.transfer.infrastructure.kafka

import com.tossbank.transfer.application.service.InterbankSagaTransactionExecutor
import com.tossbank.transfer.domain.model.InterbankTransferSagaStatus
import com.tossbank.transfer.infrastructure.client.ExternalBankingClient
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferResultStatus
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.persistence.InterbankTransferSagaRepository
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
class InterbankSagaConsumer(
    private val interbankSagaRepository: InterbankTransferSagaRepository,
    private val interbankSagaExecutor:   InterbankSagaTransactionExecutor,
    private val externalBankingClient:   ExternalBankingClient,
    private val objectMapper:            ObjectMapper,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
    @Qualifier("schedulerScope") private val scope: CoroutineScope,
) {

    @KafkaListener(
        topics = [Topics.INTERBANK_TRANSFER_INQUIRY],
        groupId = "transfer-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleInterbankTransferInquiry(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        // @Scheduled처럼 즉시 반환 — 실제 처리는 scope 안에서 비동기 실행
        scope.launch {
            runCatching {
                processInterbankTransferInquiry(record)
            }.onSuccess {
                ack.acknowledge()
            }.onFailure { e ->
                log.error(e) { "타행 이체 재조회 처리 실패 — offset=${record.offset()}" }
                ack.acknowledge()  // 실패해도 ack — 재시도는 Outbox 스케줄러가 담당
            }
        }
    }

    private suspend fun processInterbankTransferInquiry(
        record: ConsumerRecord<String, String>,
    ) {
        val payload = runCatching {
            objectMapper.readValue(record.value(), InterbankSagaEventPayload::class.java)
        }.getOrElse {
            log.error { "페이로드 파싱 실패 — value=${record.value()}" }
            return
        }

        val saga = withContext(dbDispatcher) {
            interbankSagaRepository.findById(payload.sagaId).orElse(null)
        } ?: run {
            log.warn { "InterbankTransferSaga 없음 — sagaId=${payload.sagaId}" }
            return
        }

        if (saga.status != InterbankTransferSagaStatus.TRANSFER_UNKNOWN) {
            log.warn { "TRANSFER_UNKNOWN 상태 아님 — status=${saga.status} sagaId=${saga.id} 스킵" }
            return
        }

        runCatching {
            externalBankingClient.inquireTransferResult(saga.idempotencyKey)
        }.onSuccess { result ->
            withContext(dbDispatcher) {
                when (result.status) {
                    ExternalTransferResultStatus.SUCCESS -> {
                        interbankSagaExecutor.updateSagaAndClearOutbox(saga.id) {
                            it.markCompleted(result.externalTransactionId!!)
                        }
                        log.info { "TRANSFER_UNKNOWN → COMPLETED — sagaId=${saga.id}" }
                    }
                    ExternalTransferResultStatus.FAILED,
                    ExternalTransferResultStatus.NOT_FOUND -> {
                        interbankSagaExecutor.updateSagaWithOutbox(
                            sagaId    = saga.id,
                            eventType = OutboxEventType.COMPENSATE_WITHDRAW,
                        ) { it.markCompensating() }
                        log.warn { "TRANSFER_UNKNOWN → COMPENSATING — sagaId=${saga.id} status=${result.status}" }
                    }
                    ExternalTransferResultStatus.PROCESSING -> {
                        interbankSagaExecutor.updateSagaWithOutbox(
                            sagaId    = saga.id,
                            eventType = OutboxEventType.INTERBANK_TRANSFER_INQUIRY,
                        ) { it.markTransferUnknown("processing — 재시도 예약") }
                        log.info { "TRANSFER_UNKNOWN → PROCESSING 재시도 예약 — sagaId=${saga.id}" }
                    }
                }
            }
        }.onFailure { e ->
            log.error(e) { "타행 이체 재조회 실패 — sagaId=${saga.id}" }
            handleRetryOrEscalate(saga.id)
        }
    }

    private suspend fun handleRetryOrEscalate(sagaId: Long) {
        withContext(dbDispatcher) {
            val freshSaga = interbankSagaRepository.findById(sagaId).orElse(null)
                ?: return@withContext

            if (freshSaga.isRetryExhausted()) {
                interbankSagaExecutor.updateSagaAndClearOutbox(freshSaga.id) {
                    it.markManualRequired("재조회 한계 초과")
                }
                log.error { "타행 이체 재조회 MANUAL_REQUIRED — sagaId=$sagaId" }
            } else {
                interbankSagaExecutor.updateSagaWithOutbox(
                    sagaId    = freshSaga.id,
                    eventType = OutboxEventType.INTERBANK_TRANSFER_INQUIRY,
                ) { it.markTransferUnknown("재조회 실패 — 재시도 예약") }
                log.warn { "타행 이체 재조회 실패 → 재시도 예약 — sagaId=$sagaId" }
            }
        }
    }
}