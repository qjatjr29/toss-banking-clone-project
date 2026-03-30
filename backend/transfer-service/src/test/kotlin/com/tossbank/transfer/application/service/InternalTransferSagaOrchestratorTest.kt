package com.tossbank.transfer.application.service

import com.tossbank.transfer.application.dto.InternalTransferRequest
import com.tossbank.transfer.application.dto.InternalTransferResult
import com.tossbank.transfer.domain.model.InternalTransferSaga
import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.dto.AccountDepositResponse
import com.tossbank.transfer.infrastructure.client.dto.AccountWithdrawResponse
import com.tossbank.transfer.infrastructure.client.exception.AccountServiceException
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import java.math.BigDecimal
import java.util.*

class InternalTransferSagaOrchestratorTest : BehaviorSpec({

    val sagaRepository          = mockk<InternalTransferSagaRepository>()
    val accountClient           = mockk<AccountServiceClient>()
    val sagaTransactionExecutor = mockk<SagaTransactionExecutor>()

    val orchestrator = InternalTransferSagaOrchestrator(
        sagaRepository          = sagaRepository,
        accountServiceClient    = accountClient,
        sagaTransactionExecutor = sagaTransactionExecutor,
        dbDispatcher            = Dispatchers.Unconfined,
    )

    fun pendingSaga() = InternalTransferSaga(
        fromMemberId    = 1L,
        fromAccountId   = 100L,
        toAccountId     = 200L,
        toAccountNumber = "1002-000-000001",
        toMemberName    = "홍길동",
        fromMemberName  = "김철수",
        amount          = BigDecimal("10000"),
        description     = null,
        idempotencyKey  = "test-key-001",
    )

    fun buildRequest() = InternalTransferRequest(
        fromAccountId   = 100L,
        toAccountId     = 200L,
        toAccountNumber = "1002-000-000001",
        toMemberName    = "홍길동",
        fromMemberName  = "김철수",
        amount          = BigDecimal("10000"),
        description     = null,
        idempotencyKey  = "test-key-001",
    )

    fun defaultExecutorStubs(saga: InternalTransferSaga) {
        every { sagaTransactionExecutor.findOrCreateSaga(any(), any()) } returns saga
        every { sagaTransactionExecutor.updateSagaAndClearOutbox(any(), any()) } answers {
            val mutate = secondArg<(InternalTransferSaga) -> Unit>()
            mutate.invoke(saga)
            saga
        }
        every { sagaTransactionExecutor.updateSagaWithOutbox(any(), any(), any()) } just Runs
    }


    beforeEach { clearAllMocks() }

    Given("신규 이체 요청 (PENDING → COMPLETED)") {
        When("출금 + 입금 모두 성공하면") {
            Then("COMPLETED 반환") {
                defaultExecutorStubs(pendingSaga())
                coEvery { accountClient.withdraw(any()) } returns
                        AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                coEvery { accountClient.deposit(any()) } returns
                        AccountDepositResponse(toAccountId = 200L, balanceAfterTx = BigDecimal("10000"))

                val result = orchestrator.internalTransfer(1L, buildRequest())

                result.shouldBeInstanceOf<InternalTransferResult.Completed>()
                // 출금 → 입금 각 1회씩만 호출
                coVerify(exactly = 1) { accountClient.withdraw(any()) }
                coVerify(exactly = 1) { accountClient.deposit(any()) }
                // Saga 상태 업데이트 2회 (출금완료, 이체완료)
                verify(exactly = 2) { sagaTransactionExecutor.updateSagaAndClearOutbox(any(), any()) }
            }
        }
    }

    Given("출금 4xx 실패") {
        When("잔액 부족 등 클라이언트 오류") {
            Then("FAILED 반환 + Outbox 저장 없음") {
                defaultExecutorStubs(pendingSaga())
                coEvery { accountClient.withdraw(any()) } throws
                        AccountServiceException(isClientError = true, message = "잔액 부족", statusCode = 400)

                val result = orchestrator.internalTransfer(1L, buildRequest())

                result.shouldBeInstanceOf<InternalTransferResult.Failed>()
                coVerify(exactly = 0) { accountClient.deposit(any()) }
                verify(exactly = 0) { sagaTransactionExecutor.updateSagaWithOutbox(any(), any(), any()) }
            }
        }
    }

    Given("출금 5xx → WITHDRAW_UNKNOWN") {
        When("서버 오류 발생") {
            Then("InProgress 반환 + WITHDRAW_INQUIRY Outbox 등록") {
                defaultExecutorStubs(pendingSaga())
                coEvery { accountClient.withdraw(any()) } throws
                        AccountServiceException(isClientError = false, message = "서버 오류", statusCode = 500)

                val result = orchestrator.internalTransfer(1L, buildRequest())

                result.shouldBeInstanceOf<InternalTransferResult.InProgress>()
                verify(exactly = 1) {
                    sagaTransactionExecutor.updateSagaWithOutbox(
                        sagaId    = any(),
                        eventType = OutboxEventType.WITHDRAW_INQUIRY,
                        mutate    = any(),
                    )
                }
                coVerify(exactly = 0) { accountClient.deposit(any()) }
            }
        }
    }

    Given("출금 성공 + 입금 4xx → COMPENSATING") {
        When("입금 계좌 정지 등 클라이언트 오류") {
            Then("Compensating 반환 + COMPENSATE_WITHDRAW Outbox 등록") {
                defaultExecutorStubs(pendingSaga())
                coEvery { accountClient.withdraw(any()) } returns
                        AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                coEvery { accountClient.deposit(any()) } throws
                        AccountServiceException(isClientError = true, message = "계좌 정지", statusCode = 422)

                val result = orchestrator.internalTransfer(1L, buildRequest())

                result.shouldBeInstanceOf<InternalTransferResult.Compensating>()
                verify(exactly = 1) {
                    sagaTransactionExecutor.updateSagaWithOutbox(
                        sagaId    = any(),
                        eventType = OutboxEventType.COMPENSATE_WITHDRAW,
                        mutate    = any(),
                    )
                }
            }
        }
    }

    Given("출금 성공 + 입금 5xx → DEPOSIT_UNKNOWN") {
        When("입금 서버 오류") {
            Then("InProgress 반환 + DEPOSIT_INQUIRY Outbox 등록") {
                defaultExecutorStubs(pendingSaga())
                coEvery { accountClient.withdraw(any()) } returns
                        AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                coEvery { accountClient.deposit(any()) } throws
                        AccountServiceException(isClientError = false, message = "서버 오류", statusCode = 500)

                val result = orchestrator.internalTransfer(1L, buildRequest())

                result.shouldBeInstanceOf<InternalTransferResult.InProgress>()
                verify(exactly = 1) {
                    sagaTransactionExecutor.updateSagaWithOutbox(
                        sagaId    = any(),
                        eventType = OutboxEventType.DEPOSIT_INQUIRY,
                        mutate    = any(),
                    )
                }
            }
        }
    }

    Given("이미 COMPLETED 상태의 Saga가 존재할 때") {
        When("동일 idempotencyKey로 재요청하면") {
            Then("출금/입금 재호출 없이 기존 결과 반환") {
                val completedSaga = pendingSaga().also {
                    it.markWithdrawCompleted(BigDecimal("90000"))
                    it.markCompleted()
                }
                every { sagaTransactionExecutor.findOrCreateSaga(any(), any()) } returns completedSaga

                val result = orchestrator.internalTransfer(1L, buildRequest())

                result.shouldBeInstanceOf<InternalTransferResult.Completed>()
                coVerify(exactly = 0) { accountClient.withdraw(any()) }
                coVerify(exactly = 0) { accountClient.deposit(any()) }
                verify(exactly = 0) { sagaTransactionExecutor.updateSagaAndClearOutbox(any(), any()) }
            }
        }
    }

    Given("WITHDRAW_COMPLETED 상태 Saga에서 proceedToDeposit 호출") {
        When("입금 성공") {
            Then("COMPLETED 반환") {
                val withdrawCompletedSaga = pendingSaga().also {
                    it.markWithdrawCompleted(BigDecimal("90000"))
                }
                every { sagaRepository.findById(any()) } returns Optional.of(withdrawCompletedSaga)
                every { sagaTransactionExecutor.updateSagaAndClearOutbox(any(), any()) } answers {
                    val mutate = secondArg<(InternalTransferSaga) -> Unit>()
                    mutate.invoke(withdrawCompletedSaga)
                    withdrawCompletedSaga
                }
                coEvery { accountClient.deposit(any()) } returns
                        AccountDepositResponse(toAccountId = 200L, balanceAfterTx = BigDecimal("10000"))

                val result = orchestrator.proceedToDeposit(withdrawCompletedSaga.id)

                result.shouldBeInstanceOf<InternalTransferResult.Completed>()
                coVerify(exactly = 1) { accountClient.deposit(any()) }
            }
        }
    }
})