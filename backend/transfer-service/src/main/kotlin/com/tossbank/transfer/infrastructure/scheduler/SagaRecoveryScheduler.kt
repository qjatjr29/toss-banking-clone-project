package com.tossbank.transfer.infrastructure.scheduler

import com.tossbank.transfer.application.dto.WithdrawResult
import com.tossbank.transfer.application.service.InternalTransferSagaOrchestrator
import com.tossbank.transfer.domain.model.InternalTransferSagaStatus
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Component
class SagaRecoveryScheduler(
    private val sagaRepository: InternalTransferSagaRepository,
    private val orchestrator: InternalTransferSagaOrchestrator,
    @Qualifier("schedulerScope") private val scope: CoroutineScope,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    @Scheduled(fixedDelay = 60_000)
    fun recoverStuckSagas() {
        scope.launch {
            val threshold = LocalDateTime.now().minusMinutes(5)
            val stuckSagas = withContext(dbDispatcher) {
                sagaRepository.findStuckSagas(threshold)
            }

            stuckSagas.map { saga ->
                launch {
                    runCatching {
                        when (saga.status) {

                            InternalTransferSagaStatus.PENDING -> {
                                log.warn { "PENDING 방치 Saga 복구 — sagaId=${saga.id}" }
                                val withdrawResult = orchestrator.callWithdraw(saga)
                                if (withdrawResult is WithdrawResult.Success) {
                                    // TODO: 커스텀 예외
                                    val saga = withContext(dbDispatcher) {
                                        sagaRepository.findById(saga.id).orElseThrow()
                                    }
                                    orchestrator.callDeposit(saga)
                                }
                            }

                            InternalTransferSagaStatus.WITHDRAW_COMPLETED ->
                                orchestrator.callDeposit(saga)

                            InternalTransferSagaStatus.WITHDRAW_UNKNOWN,
                            InternalTransferSagaStatus.DEPOSIT_UNKNOWN,
                            InternalTransferSagaStatus.COMPENSATING ->
                                withContext(dbDispatcher) {
                                    orchestrator.ensureOutboxExists(saga)
                                }

                            else -> Unit
                        }
                    }.onFailure { e ->
                        log.error(e) { "Saga 복구 실패 — sagaId=${saga.id}" }
                    }
                }
            }.joinAll()
        }
    }
}

