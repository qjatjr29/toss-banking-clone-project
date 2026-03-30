package com.tossbank.transfer.application.service

import com.tossbank.transfer.application.dto.InternalTransferRequest
import com.tossbank.transfer.application.dto.InternalTransferResult
import com.tossbank.transfer.application.dto.WithdrawResult
import com.tossbank.transfer.domain.exception.TransferNotFoundException
import com.tossbank.transfer.domain.model.InternalTransferSaga
import com.tossbank.transfer.domain.model.InternalTransferSagaStatus
import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.dto.AccountDepositRequest
import com.tossbank.transfer.infrastructure.client.dto.AccountWithdrawRequest
import com.tossbank.transfer.infrastructure.client.exception.AccountServiceException
import com.tossbank.transfer.infrastructure.outbox.OutboxEvent
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger {}

@Service
class InternalTransferSagaOrchestrator(
    private val sagaRepository: InternalTransferSagaRepository,
    private val accountServiceClient: AccountServiceClient,
    private val sagaTransactionExecutor: SagaTransactionExecutor,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    // 당행 이체 시작
    suspend fun internalTransfer(
        memberId: Long,
        request: InternalTransferRequest,
    ): InternalTransferResult {

        // 멱등성 체크 + Saga 저장 (atomic)
        val saga = withContext(dbDispatcher) {
            sagaTransactionExecutor.findOrCreateSaga(memberId, request)
        }

        // 이미 완료된 경우 기존 결과 반환
        if (saga.status == InternalTransferSagaStatus.COMPLETED) {
            return InternalTransferResult.completed(saga)
        }

        // 출금 요청 (동기 HTTP)
        if (saga.status == InternalTransferSagaStatus.PENDING) {
            val withdrawResult = callWithdraw(saga)
            if (withdrawResult !is WithdrawResult.Success) {
                return withdrawResult.toTransferResult(saga.id)
            }
            return callDeposit(saga)
        }

        // 입금 요청 (동기 HTTP) — WITHDRAW_COMPLETED 상태에서만
        if (saga.status == InternalTransferSagaStatus.WITHDRAW_COMPLETED) {
            return callDeposit(saga)
        }

        // WITHDRAW_UNKNOWN: 재조회 Outbox 등록 완료된 상태 → 202 반환
        return InternalTransferResult.inProgress(saga.id)
    }

    // 출금
    internal suspend fun callWithdraw(saga: InternalTransferSaga): WithdrawResult {
        return try {
            val response = accountServiceClient.withdraw(
                AccountWithdrawRequest(
                    fromAccountId  = saga.fromAccountId,
                    fromMemberId   = saga.fromMemberId,
                    amount         = saga.amount,
                    idempotencyKey = "${saga.idempotencyKey}-withdraw",
                )
            )
            val updatedSaga = withContext(dbDispatcher) {
                sagaTransactionExecutor.updateSagaAndClearOutbox(saga.id) {
                    it.markWithdrawCompleted(response.remainingBalance)
                }
            }
            WithdrawResult.Success(updatedSaga.remainingBalance!!)

        } catch (e: AccountServiceException) {
            when {
                e.isClientError -> {
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaAndClearOutbox(saga.id) { it.markWithdrawFailed() }
                    }
                    log.warn { "출금 4xx 실패 — sagaId=${saga.id}" }
                    WithdrawResult.Failed(e.message)
                }
                else -> {
                    // 5xx / timeout → UNKNOWN → Outbox에 재조회 이벤트 저장
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaWithOutbox(saga.id, OutboxEventType.WITHDRAW_INQUIRY) {
                            it.markWithdrawUnknown()
                        }
                    }
                    log.error { "출금 UNKNOWN — sagaId=${saga.id}" }
                    WithdrawResult.Unknown
                }
            }
        }
    }

    // 입금
    internal suspend fun callDeposit(saga: InternalTransferSaga): InternalTransferResult {
        return try {
            accountServiceClient.deposit(
                AccountDepositRequest(
                    toAccountId    = saga.toAccountId,
                    amount         = saga.amount,
                    fromMemberId   = saga.fromMemberId,
                    fromMemberName = saga.fromMemberName,
                    idempotencyKey = "${saga.idempotencyKey}-deposit",
                    description    = saga.description,
                )
            )
            val updatedSaga = withContext(dbDispatcher) {
                sagaTransactionExecutor.updateSagaAndClearOutbox(saga.id) { it.markCompleted() }
            }
            log.info { "당행 이체 완료 — sagaId=${saga.id}" }
            InternalTransferResult.completed(updatedSaga)

        } catch (e: AccountServiceException) {
            when {
                e.isClientError -> {
                    // 4xx → 출금 취소 보상 트랜잭션
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaWithOutbox(saga.id, OutboxEventType.COMPENSATE_WITHDRAW) {
                            it.markCompensating()
                        }
                    }
                    log.warn { "입금 4xx 실패 → 보상 Outbox 등록 — sagaId=${saga.id}" }
                    InternalTransferResult.compensating(saga.id)
                }
                else -> {
                    // 5xx / timeout → UNKNOWN → 재조회
                    withContext(dbDispatcher) {
                        sagaTransactionExecutor.updateSagaWithOutbox(saga.id, OutboxEventType.DEPOSIT_INQUIRY) {
                            it.markDepositUnknown()
                        }
                    }
                    log.error { "입금 UNKNOWN → 재조회 Outbox 등록 — sagaId=${saga.id}" }
                    InternalTransferResult.inProgress(saga.id)
                }
            }
        }
    }

    internal suspend fun proceedToDeposit(sagaId: Long): InternalTransferResult {
        val saga = withContext(dbDispatcher) {
            sagaRepository.findById(sagaId).orElseThrow { TransferNotFoundException() }
        }
        // 이미 완료된 경우 중복 처리 방지
        if (saga.status == InternalTransferSagaStatus.COMPLETED) {
            return InternalTransferResult.completed(saga)
        }
        check(saga.status == InternalTransferSagaStatus.WITHDRAW_COMPLETED) {
            "proceedToDeposit 호출 불가 상태: ${saga.status}"
        }
        return callDeposit(saga)
    }

    fun ensureOutboxExists(saga: InternalTransferSaga) = sagaTransactionExecutor.ensureOutboxExists(saga)

    suspend fun fetchPublishableEvents(): List<OutboxEvent> = withContext(dbDispatcher) {
        sagaTransactionExecutor.fetchPublishableEvents()
    }

    fun publishOutboxEvent(event: OutboxEvent) = sagaTransactionExecutor.publishOutboxEvent(event)
}