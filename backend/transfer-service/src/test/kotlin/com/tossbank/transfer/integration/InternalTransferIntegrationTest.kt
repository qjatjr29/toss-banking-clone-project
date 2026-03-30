package com.tossbank.transfer.integration

import com.tossbank.transfer.application.dto.InternalTransferRequest
import com.tossbank.transfer.application.dto.InternalTransferResult
import com.tossbank.transfer.application.service.InternalTransferSagaOrchestrator
import com.tossbank.transfer.domain.model.InternalTransferSaga
import com.tossbank.transfer.domain.model.InternalTransferSagaStatus
import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.dto.AccountDepositResponse
import com.tossbank.transfer.infrastructure.client.dto.AccountWithdrawResponse
import com.tossbank.transfer.infrastructure.client.exception.AccountServiceException
import com.tossbank.transfer.infrastructure.outbox.OutboxEventRepository
import com.tossbank.transfer.infrastructure.outbox.OutboxEventType
import com.tossbank.transfer.infrastructure.outbox.OutboxStatus
import com.tossbank.transfer.infrastructure.persistence.InternalTransferSagaRepository
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.*

class InternalTransferIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var orchestrator: InternalTransferSagaOrchestrator
    @Autowired lateinit var sagaRepository: InternalTransferSagaRepository
    @Autowired lateinit var outboxRepository: OutboxEventRepository
    @Autowired lateinit var accountServiceClient: AccountServiceClient

    fun buildRequest(key: String = UUID.randomUUID().toString()) = InternalTransferRequest(
        fromAccountId   = 100L,
        toAccountId     = 200L,
        toAccountNumber = "1002-000-000001",
        toMemberName    = "홍길동",
        fromMemberName  = "김철수",
        amount          = BigDecimal("10000"),
        description     = null,
        idempotencyKey  = key,
    )

    fun clientError(status: Int, msg: String) =
        AccountServiceException(isClientError = true,  message = msg, statusCode = status)

    fun serverError(status: Int, msg: String) =
        AccountServiceException(isClientError = false, message = msg, statusCode = status)

    init {
        beforeEach { clearAllMocks() }
        afterEach {
            outboxRepository.deleteAll()
            sagaRepository.deleteAll()
        }
    }

    init {
        Given("정상 이체") {
            When("출금 + 입금 모두 성공") {
                Then("Saga COMPLETED + Outbox 없음") {
                    coEvery { accountServiceClient.withdraw(any()) } returns
                            AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                    coEvery { accountServiceClient.deposit(any()) } returns
                            AccountDepositResponse(toAccountId = 200L, balanceAfterTx = BigDecimal("10000"))

                    val result = orchestrator.internalTransfer(1L, buildRequest("key-success-001"))

                    result.shouldBeInstanceOf<InternalTransferResult.Completed>()

                    val saga = sagaRepository.findByIdempotencyKey("key-success-001")!!
                    saga.status shouldBe InternalTransferSagaStatus.COMPLETED
                    saga.remainingBalance.shouldNotBeNull() shouldBeEqualComparingTo BigDecimal("90000")
                    // 성공 후 PENDING Outbox 없음
                    outboxRepository.existsBySagaIdAndStatus(saga.id, OutboxStatus.PENDING) shouldBe false
                }
            }
        }

        Given("출금 4xx 실패") {
            When("잔액 부족") {
                Then("Saga FAILED + Outbox 없음") {
                    coEvery { accountServiceClient.withdraw(any()) } throws
                            clientError(400, "잔액 부족")

                    orchestrator.internalTransfer(1L, buildRequest("key-withdraw-fail-001"))

                    val saga = sagaRepository.findByIdempotencyKey("key-withdraw-fail-001")!!
                    saga.status shouldBe InternalTransferSagaStatus.FAILED
                    outboxRepository.existsBySagaIdAndStatus(saga.id, OutboxStatus.PENDING) shouldBe false
                }
            }
        }

        Given("출금 5xx → WITHDRAW_UNKNOWN") {
            When("서버 오류") {
                Then("Saga WITHDRAW_UNKNOWN + WITHDRAW_INQUIRY Outbox 저장") {
                    coEvery { accountServiceClient.withdraw(any()) } throws
                            serverError(500, "서버 오류")

                    orchestrator.internalTransfer(1L, buildRequest("key-withdraw-unknown-001"))

                    val saga = sagaRepository.findByIdempotencyKey("key-withdraw-unknown-001")!!
                    saga.status shouldBe InternalTransferSagaStatus.WITHDRAW_UNKNOWN
                    saga.retryCount shouldBe 1
                    saga.nextRetryAt.shouldNotBeNull()

                    val outbox = outboxRepository.findAll().first { it.sagaId == saga.id }
                    outbox.eventType shouldBe OutboxEventType.WITHDRAW_INQUIRY
                    outbox.status    shouldBe OutboxStatus.PENDING
                }
            }
        }

        Given("입금 4xx → COMPENSATING") {
            When("계좌 정지") {
                Then("Saga COMPENSATING + COMPENSATE_WITHDRAW Outbox 저장 (payload에 fromAccountId 포함)") {
                    coEvery { accountServiceClient.withdraw(any()) } returns
                            AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                    coEvery { accountServiceClient.deposit(any()) } throws
                            clientError(422, "계좌 정지")

                    orchestrator.internalTransfer(1L, buildRequest("key-deposit-4xx-001"))

                    val saga = sagaRepository.findByIdempotencyKey("key-deposit-4xx-001")!!
                    saga.status shouldBe InternalTransferSagaStatus.COMPENSATING

                    val outbox = outboxRepository.findAll().first { it.sagaId == saga.id }
                    outbox.eventType shouldBe OutboxEventType.COMPENSATE_WITHDRAW
                    outbox.topic     shouldBe "account.withdraw.cancel"
                    // ✅ Kafka payload에 출금 계좌 정보 포함 확인
                    outbox.payload   shouldContain "fromAccountId"
                    outbox.payload   shouldContain "100"
                }
            }
        }

        Given("입금 5xx → DEPOSIT_UNKNOWN") {
            When("서버 오류") {
                Then("Saga DEPOSIT_UNKNOWN + DEPOSIT_INQUIRY Outbox 저장") {
                    coEvery { accountServiceClient.withdraw(any()) } returns
                            AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                    coEvery { accountServiceClient.deposit(any()) } throws
                            serverError(500, "서버 오류")

                    orchestrator.internalTransfer(1L, buildRequest("key-deposit-5xx-001"))

                    val saga = sagaRepository.findByIdempotencyKey("key-deposit-5xx-001")!!
                    saga.status shouldBe InternalTransferSagaStatus.DEPOSIT_UNKNOWN

                    outboxRepository.findAll()
                        .any { it.sagaId == saga.id && it.eventType == OutboxEventType.DEPOSIT_INQUIRY } shouldBe true
                }
            }
        }

        Given("COMPLETED 상태에서 동일 키 재요청") {
            When("2번째 요청") {
                Then("출금/입금 재호출 없이 기존 결과 반환") {
                    coEvery { accountServiceClient.withdraw(any()) } returns
                            AccountWithdrawResponse(fromAccountId = 100L, remainingBalance = BigDecimal("90000"))
                    coEvery { accountServiceClient.deposit(any()) } returns
                            AccountDepositResponse(toAccountId = 200L, balanceAfterTx = BigDecimal("10000"))

                    val req = buildRequest("key-idem-001")
                    orchestrator.internalTransfer(1L, req)   // 1회차 — 실제 이체
                    orchestrator.internalTransfer(1L, req)   // 2회차 — 멱등성 처리

                    // 2회 요청했어도 실제 HTTP는 각 1회만
                    coVerify(exactly = 1) { accountServiceClient.withdraw(any()) }
                    coVerify(exactly = 1) { accountServiceClient.deposit(any()) }
                    // Saga는 1건만 존재
                    sagaRepository.findAll()
                        .filter { it.idempotencyKey == "key-idem-001" }.size shouldBe 1
                }
            }
        }

        Given("서버 크래시 후 WITHDRAW_COMPLETED 상태 방치") {
            When("proceedToDeposit() 재시도") {
                Then("입금 성공 → COMPLETED") {
                    // 출금은 성공했지만 서버가 죽어 Saga가 WITHDRAW_COMPLETED로 DB에 남아있는 상황 직접 생성
                    val crashedSaga = sagaRepository.save(
                        InternalTransferSaga(
                            fromMemberId    = 1L,
                            fromAccountId   = 100L,
                            toAccountId     = 200L,
                            toAccountNumber = "1002-000-000001",
                            toMemberName    = "홍길동",
                            fromMemberName  = "김철수",
                            amount          = BigDecimal("10000"),
                            description     = null,
                            idempotencyKey  = "key-crash-001",
                        ).also { it.markWithdrawCompleted(BigDecimal("90000")) }
                    )

                    coEvery { accountServiceClient.deposit(any()) } returns
                            AccountDepositResponse(toAccountId = 200L, balanceAfterTx = BigDecimal("10000"))

                    // SagaRecoveryScheduler가 호출하는 경로 직접 실행
                    orchestrator.proceedToDeposit(crashedSaga.id)

                    val recovered = sagaRepository.findById(crashedSaga.id).get()
                    recovered.status shouldBe InternalTransferSagaStatus.COMPLETED
                    // 멱등성 키 "-deposit" suffix — Account Service가 중복 입금 방지
                    coVerify(exactly = 1) { accountServiceClient.deposit(any()) }
                }
            }
        }
    }
}