package com.tossbank.account.application.scheduler

import com.tossbank.account.application.TransferTransactionExecutor
import com.tossbank.account.domain.model.InterbankTransfer
import com.tossbank.account.infrastructure.client.ExternalBankClient
import com.tossbank.account.infrastructure.client.dto.ExternalTransferResultStatus
import com.tossbank.account.infrastructure.persistence.InterbankTransferRepository
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Component
class InterbankTransferScheduler(
    private val interbankTransferRepository : InterbankTransferRepository,
    private val transferTransactionExecutor : TransferTransactionExecutor,
    private val externalBankClient          : ExternalBankClient,
    @Qualifier("schedulerScope") private val scope: CoroutineScope,
) {

    /**
     * @Scheduled 자체는 즉시 반환 (논블로킹)
     * 실제 처리는 CoroutineScope 안에서 비동기 실행
     */
    @Scheduled(fixedDelay = 30_000)
    fun processUnknownTransfers() {
        scope.launch {
            val targets = interbankTransferRepository.findUnknownRetryTargets(
                now      = LocalDateTime.now(),
                maxRetry = InterbankTransfer.MAX_RETRY_COUNT,
            )
            if (targets.isEmpty()) return@launch

            log.info { "UNKNOWN 재조회 대상: ${targets.size}건" }

            // 병렬 처리
            targets.map { transfer ->
                launch {
                    runCatching { processUnknown(transfer) }
                        .onFailure { e -> log.error(e) { "UNKNOWN 재조회 실패: id=${transfer.id}" } }
                }
            }.joinAll()
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun processCompensationPendingTransfers() {
        scope.launch {
            val targets = interbankTransferRepository.findCompensationRetryTargets(
                now      = LocalDateTime.now(),
                maxRetry = InterbankTransfer.MAX_COMPENSATION_RETRY,
            )
            if (targets.isEmpty()) return@launch

            targets.map { transfer ->
                launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            transferTransactionExecutor.compensateInterbank(transfer.id)
                        }
                    }.onFailure { e -> log.error(e) { "보상 트랜잭션 재시도 실패: id=${transfer.id}" } }
                }
            }.joinAll()
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun processStuckWithdrawals() {
        scope.launch {
            val threshold = LocalDateTime.now().minusMinutes(5)
            val targets   = interbankTransferRepository.findStuckWithdrawals(threshold)
            if (targets.isEmpty()) return@launch

            targets.map { transfer ->
                launch {
                    runCatching { processUnknown(transfer) }
                        .onFailure { e ->  log.error(e) { "출금 처리 건 복구 실패: id=${transfer.id}" } }
                }
            }.joinAll()
        }
    }

    private suspend fun processUnknown(transfer: InterbankTransfer) {
        if (transfer.isRetryExhausted()) {
            withContext(Dispatchers.IO) {
                transferTransactionExecutor.markInterbankManualRequired(
                    interbankTransferId = transfer.id,
                    errorMessage        = "재시도 한계 초과",
                )
            }
            log.error { "MANUAL_REQUIRED: id=${transfer.id}" }
            return
        }

        val idempotencyKey = transfer.idempotencyKey
        if (idempotencyKey == null) {
            log.error { "idempotencyKey null — 데이터 정합성 이상: id=${transfer.id}" }
            withContext(Dispatchers.IO) {
                transferTransactionExecutor.markInterbankManualRequired(
                    interbankTransferId = transfer.id,
                    errorMessage        = "idempotencyKey null — 데이터 정합성 이상",
                )
            }
            return
        }

        runCatching {
            externalBankClient.inquireTransferResult(idempotencyKey) // 외부 은행 이체 확인
        }.onSuccess { response ->
            withContext(Dispatchers.IO) {
                when (response.status) {
                    ExternalTransferResultStatus.SUCCESS -> {
                        transferTransactionExecutor.markInterbankCompleted(
                            interbankTransferId = transfer.id,
                            externalTxId        = response.externalTransactionId,
                        )
                        log.info { "UNKNOWN → COMPLETED: id=${transfer.id}" }
                    }
                    ExternalTransferResultStatus.FAILED -> {
                        transferTransactionExecutor.compensateInterbank(transfer.id)
                        log.warn { "UNKNOWN → 보상 트랜잭션: id=${transfer.id}" }
                    }
                    ExternalTransferResultStatus.PROCESSING -> {
                        transferTransactionExecutor.scheduleNextRetryForUnknown(transfer.id)
                        log.info { "UNKNOWN → 아직 처리 중, 재시도 예약: id=${transfer.id}" }
                    }
                }
            }
        }.onFailure { e ->
            withContext(Dispatchers.IO) {
                transferTransactionExecutor.scheduleNextRetryForUnknown(
                    interbankTransferId = transfer.id,
                    errorMessage        = e.message ?: "inquiry failed",
                )
            }
            log.error(e) { "UNKNOWN 재조회 실패 → 재시도 예약: id=${transfer.id}" }
        }
    }

}