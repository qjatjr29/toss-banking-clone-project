package com.tossbank.transfer.application.service

import com.tossbank.common.domain.BankConstants
import com.tossbank.transfer.application.dto.InterbankTransferRequest
import com.tossbank.transfer.application.dto.InterbankTransferResult
import com.tossbank.transfer.domain.exception.ExternalBankClientException
import com.tossbank.transfer.domain.exception.ExternalBankServerException
import com.tossbank.transfer.domain.exception.ExternalBankTimeoutException
import com.tossbank.transfer.domain.exception.TransferNotFoundException
import com.tossbank.transfer.domain.model.InterbankTransferSaga
import com.tossbank.transfer.domain.model.InterbankTransferSagaStatus
import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.ExternalBankingClient
import com.tossbank.transfer.infrastructure.client.dto.AccountWithdrawRequest
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferRequest
import com.tossbank.transfer.infrastructure.client.exception.AccountServiceException
import com.tossbank.transfer.infrastructure.outbox.OutboxEvent
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.persistence.InterbankTransferSagaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger {}

@Service
class InterbankTransferSagaOrchestrator(
    private val sagaRepository:   InterbankTransferSagaRepository,
    private val executor:         InterbankSagaTransactionExecutor,
    private val accountClient:    AccountServiceClient,
    private val externalClient:   ExternalBankingClient,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    suspend fun interbankTransfer(
        memberId: Long,
        request:  InterbankTransferRequest,
    ): InterbankTransferResult {

        val saga = withContext(dbDispatcher) {
            executor.findOrCreateSaga(memberId, request)
        }

        return when (saga.status) {
            // 이미 완료된 경우 기존 결과 반환 (멱등성)
            InterbankTransferSagaStatus.COMPLETED ->
                InterbankTransferResult.completed(saga)

            // 출금 완료 후 서버 크래시 → 외부 송금 재시도
            InterbankTransferSagaStatus.WITHDRAW_COMPLETED ->
                callExternalTransfer(saga)

            // 결과 불확실 → 재조회 진행 중
            InterbankTransferSagaStatus.TRANSFER_UNKNOWN ->
                InterbankTransferResult.inProgress(saga.id)

            // 보상 진행 중
            InterbankTransferSagaStatus.COMPENSATING,
            InterbankTransferSagaStatus.COMPENSATED ->
                InterbankTransferResult.compensating(saga.id)

            // 확정 실패
            InterbankTransferSagaStatus.FAILED ->
                InterbankTransferResult.failed("이체가 실패했습니다")

            // PENDING — 최초 요청, 출금부터 시작
            else -> {
                val withdrawOk = callWithdraw(saga)
                if (!withdrawOk) return InterbankTransferResult.failed("출금에 실패했습니다")
                // 출금 성공 → 외부 송금
                val updatedSaga = withContext(dbDispatcher) {
                    sagaRepository.findById(saga.id).orElseThrow { TransferNotFoundException() }
                }
                callExternalTransfer(updatedSaga)
            }
        }
    }

    /**
     * 출금 요청 — AccountServiceClient 재사용 (당행 이체와 동일)
     *
     * 4xx → markFailed (잔액 부족, 계좌 정지 등 확정 실패)
     * 5xx/timeout → WITHDRAW_UNKNOWN + Outbox(WITHDRAW_INQUIRY)
     *   당행 이체 SagaConsumer의 handleWithdrawInquiry 재사용
     */
    internal suspend fun callWithdraw(saga: InterbankTransferSaga): Boolean {
        return try {
            val response = accountClient.withdraw(
                AccountWithdrawRequest(
                    fromAccountId  = saga.fromAccountId,
                    fromMemberId   = saga.fromMemberId,
                    amount         = saga.amount,
                    idempotencyKey = "${saga.idempotencyKey}-withdraw",
                )
            )
            withContext(dbDispatcher) {
                executor.updateSagaAndClearOutbox(saga.id) {
                    it.markWithdrawCompleted(response.remainingBalance)
                }
            }
            log.info { "타행 이체 출금 완료 — sagaId=${saga.id}" }
            true

        } catch (e: AccountServiceException) {
            when {
                e.isClientError -> {
                    withContext(dbDispatcher) {
                        executor.updateSagaAndClearOutbox(saga.id) { it.markFailed() }
                    }
                    log.warn { "타행 이체 출금 4xx 실패 — sagaId=${saga.id}" }
                    false
                }
                else -> {
                    withContext(dbDispatcher) {
                        executor.updateSagaWithOutbox(saga.id, OutboxEventType.WITHDRAW_INQUIRY) {
                            it.markWithdrawUnknown()
                        }
                    }
                    log.error { "타행 이체 출금 UNKNOWN — sagaId=${saga.id}" }
                    false
                }
            }
        }
    }

    /**
     * 외부 송금 요청 — 타행 이체의 핵심
     *
     * 성공 → markCompleted(externalTransactionId)
     * 4xx → markCompensating + Outbox(COMPENSATE_WITHDRAW)
     *   입금 안 됐으니 출금 취소 가능
     * 5xx/timeout → markTransferUnknown + Outbox(INTERBANK_TRANSFER_INQUIRY)
     *   입금 여부 불확실 — 보상 금지, 재조회 필요
     *
     * 5xx는 retryWithBackoff로 3회 재시도 후에도 실패하면 TRANSFER_UNKNOWN
     */
    internal suspend fun callExternalTransfer(saga: InterbankTransferSaga): InterbankTransferResult {
        return try {
            val response = retryWithBackoff(
                retryOn = { it is ExternalBankServerException || it is ExternalBankTimeoutException },
            ) {
                externalClient.transfer(
                    idempotencyKey = saga.idempotencyKey,
                    request        = ExternalTransferRequest(
                        fromBankCode      = BankConstants.TOSS_BANK_CODE,
                        fromAccountNumber = saga.fromAccountNumber,
                        toAccountNumber   = saga.toAccountNumber,
                        amount            = saga.amount,
                    ),
                )
            }

            val updatedSaga = withContext(dbDispatcher) {
                executor.updateSagaAndClearOutbox(saga.id) {
                    it.markCompleted(response.externalTransactionId)
                }
            }
            log.info { "타행 이체 완료 — sagaId=${saga.id} externalTxId=${response.externalTransactionId}" }
            InterbankTransferResult.completed(updatedSaga)

        } catch (e: ExternalBankClientException) {
            // 4xx — 확정 실패, 입금 안 됨 → 출금 취소
            withContext(dbDispatcher) {
                executor.updateSagaWithOutbox(saga.id, OutboxEventType.COMPENSATE_WITHDRAW) {
                    it.markCompensating()
                }
            }
            log.warn { "타행 이체 4xx → COMPENSATING — sagaId=${saga.id}" }
            InterbankTransferResult.compensating(saga.id)

        } catch (e: Exception) {
            // 5xx/timeout 3회 재시도 모두 실패 — 결과 불확실, 보상 금지
            withContext(dbDispatcher) {
                executor.updateSagaWithOutbox(saga.id, OutboxEventType.INTERBANK_TRANSFER_INQUIRY) {
                    it.markTransferUnknown(e.message ?: "unknown error")
                }
            }
            log.error(e) { "타행 이체 TRANSFER_UNKNOWN — sagaId=${saga.id}" }
            InterbankTransferResult.inProgress(saga.id)
        }
    }

    internal suspend fun proceedToExternalTransfer(sagaId: Long): InterbankTransferResult {
        val saga = withContext(dbDispatcher) {
            sagaRepository.findById(sagaId).orElseThrow { TransferNotFoundException() }
        }
        if (saga.status == InterbankTransferSagaStatus.COMPLETED) {
            return InterbankTransferResult.completed(saga)
        }
        check(saga.status == InterbankTransferSagaStatus.WITHDRAW_COMPLETED) {
            "proceedToExternalTransfer 호출 불가 상태: ${saga.status}"
        }
        return callExternalTransfer(saga)
    }

    fun ensureOutboxExists(saga: InterbankTransferSaga) =
        executor.ensureOutboxExists(saga)

    suspend fun fetchPublishableEvents(): List<OutboxEvent> =
        withContext(dbDispatcher) { executor.fetchPublishableEvents() }

    fun publishOutboxEvent(event: OutboxEvent) =
        executor.publishOutboxEvent(event)

    /**
     * 지수 백오프 재시도 — account-service TransferService에서 이전
     *
     * retryOn(e) = true  → delay 후 재시도 (5xx/timeout)
     * retryOn(e) = false → 즉시 throw (4xx)
     * 500ms → 1000ms → 마지막 시도
     */
    private suspend fun <T> retryWithBackoff(
        maxAttempts:  Int                     = 3,
        initialDelay: Long                    = 500L,
        maxDelay:     Long                    = 5_000L,
        factor:       Double                  = 2.0,
        retryOn:      (Exception) -> Boolean  = { true },
        block:        suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (!retryOn(e)) throw e
                log.warn { "외부 은행 API 재시도 (${attempt + 1}/${maxAttempts - 1}) — ${currentDelay}ms 후" }
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }
        return block()
    }
}