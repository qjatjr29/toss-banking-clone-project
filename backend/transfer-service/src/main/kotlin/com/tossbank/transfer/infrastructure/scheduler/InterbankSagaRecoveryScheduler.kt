package com.tossbank.transfer.infrastructure.scheduler

import com.tossbank.transfer.application.service.InterbankTransferSagaOrchestrator
import com.tossbank.transfer.domain.model.InterbankTransferSagaStatus
import com.tossbank.transfer.infrastructure.persistence.InterbankTransferSagaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Component
class InterbankSagaRecoveryScheduler(
    private val sagaRepository: InterbankTransferSagaRepository,
    private val orchestrator:   InterbankTransferSagaOrchestrator,
    @Qualifier("schedulerScope") private val scope: CoroutineScope,
    @Qualifier("dbDispatcher")   private val dbDispatcher: CoroutineDispatcher,
) {

    @Scheduled(fixedDelay = 60_000)
    fun recoverStuckInterbankSagas() {
        scope.launch {
            val threshold = LocalDateTime.now().minusMinutes(5)
            val stuckSagas = withContext(dbDispatcher) {
                sagaRepository.findStuckSagas(threshold)
            }
            if (stuckSagas.isEmpty()) return@launch

            log.info { "타행 이체 방치 Saga 복구 대상: ${stuckSagas.size}건" }

            stuckSagas.map { saga ->
                launch {
                    runCatching {
                        when (saga.status) {

                            /**
                             * PENDING 방치
                             * 출금 시도 전 서버 크래시
                             * → callWithdraw() 재시도
                             * → 성공 시 callExternalTransfer() 진행
                             */
                            InterbankTransferSagaStatus.PENDING -> {
                                log.warn { "PENDING 방치 Saga 복구 — sagaId=${saga.id}" }
                                val withdrawOk = orchestrator.callWithdraw(saga)
                                if (withdrawOk) {
                                    val updatedSaga = withContext(dbDispatcher) {
                                        sagaRepository.findById(saga.id).orElseThrow()
                                    }
                                    orchestrator.callExternalTransfer(updatedSaga)
                                }
                            }

                            /**
                             * WITHDRAW_COMPLETED 방치
                             * 출금 완료 후 외부 송금 시도 전 서버 크래시
                             * → callExternalTransfer() 재시도
                             */
                            InterbankTransferSagaStatus.WITHDRAW_COMPLETED -> {
                                log.warn { "WITHDRAW_COMPLETED 방치 Saga 복구 — sagaId=${saga.id}" }
                                orchestrator.callExternalTransfer(saga)
                            }

                            /**
                             * WITHDRAW_UNKNOWN / TRANSFER_UNKNOWN / COMPENSATING 방치
                             * Outbox가 유실된 경우
                             * → ensureOutboxExists()로 Outbox 재등록
                             * → OutboxPublishScheduler가 Kafka 발행
                             */
                            InterbankTransferSagaStatus.WITHDRAW_UNKNOWN,
                            InterbankTransferSagaStatus.TRANSFER_UNKNOWN,
                            InterbankTransferSagaStatus.COMPENSATING -> {
                                log.warn { "${saga.status} 방치 Saga Outbox 재등록 — sagaId=${saga.id}" }
                                withContext(dbDispatcher) {
                                    orchestrator.ensureOutboxExists(saga)
                                }
                            }

                            else -> Unit
                        }
                    }.onFailure { e ->
                        log.error(e) { "타행 이체 Saga 복구 실패 — sagaId=${saga.id} status=${saga.status}" }
                    }
                }
            }.forEach { it.join() }
        }
    }
}