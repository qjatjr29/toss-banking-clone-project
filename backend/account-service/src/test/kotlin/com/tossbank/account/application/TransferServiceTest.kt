package com.tossbank.account.application

import AccountNotFoundException
import CompensationFailedException
import ExternalBankApiException
import ExternalTransferFailedException
import ExternalTransferUnknownException
import com.tossbank.account.application.dto.InterbankTransferContext
import com.tossbank.account.infrastructure.client.ExternalBankClient
import com.tossbank.account.infrastructure.client.dto.ExternalTransferResponse
import com.tossbank.account.infrastructure.lock.RedissonLockManager
import com.tossbank.account.presentation.dto.TransferRequest
import com.tossbank.account.presentation.dto.TransferResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import java.math.BigDecimal

class TransferServiceTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerLeaf

    val lockManager                 = mockk<RedissonLockManager>()
    val transferTransactionExecutor = mockk<TransferTransactionExecutor>()
    val externalBankClient          = mockk<ExternalBankClient>()

    val service = TransferService(
        lockManager                 = lockManager,
        transferTransactionExecutor = transferTransactionExecutor,
        externalBankClient          = externalBankClient,
        dbDispatcher                = Dispatchers.Unconfined,
    )

    afterTest { clearAllMocks() }

    fun makeRequest(
        fromAccountId: Long     = 1L,
        toAccountNumber: String = "1002-000-000002",
        toBankCode: String      = "092",      // 당행 코드
        toMemberName: String    = "박토스",
        amount: BigDecimal      = BigDecimal("10000"),
        idempotencyKey: String  = "idem-key-001",
        description: String?    = null,
    ) = TransferRequest(
        fromAccountId   = fromAccountId,
        toAccountNumber = toAccountNumber,
        toBankCode      = toBankCode,
        toMemberName    = toMemberName,
        amount          = amount,
        idempotencyKey  = idempotencyKey,
        description     = description,
    )

    fun makeInterbankContext(
        interbankTransferId: Long  = 1L,
        fromAccountId: Long        = 1L,
        fromAccountNumber: String  = "1002-000-000001",
    ) = InterbankTransferContext(
        transferResponse    = TransferResponse(
            fromAccountId    = fromAccountId,
            toMemberName     = "박토스",
            amount           = BigDecimal("10000"),
            remainingBalance = BigDecimal("90000"),
        ),
        interbankTransferId = interbankTransferId,
        fromAccountId       = fromAccountId,
        fromAccountNumber   = fromAccountNumber,
    )

    fun makeExternalResponse(externalTxId: String = "ext-tx-001") =
        mockk<ExternalTransferResponse> {
            every { externalTransactionId } returns externalTxId
        }

    fun make4xxException(statusCode: Int = 400) = mockk<ExternalBankApiException> {
        every { isClientError } returns true
        every { isServerError } returns false
        every { this@mockk.statusCode } returns statusCode
        every { message } returns "Bad Request"
    }

    fun make5xxException(statusCode: Int = 500) = mockk<ExternalBankApiException> {
        every { isClientError } returns false
        every { isServerError } returns true
        every { this@mockk.statusCode } returns statusCode
        every { message } returns "Internal Server Error"
    }

    fun setupSingleLock() {
        every {
            lockManager.withSingleLock<InterbankTransferContext>(
                accountId    = any(),
                waitSeconds  = any(),
                leaseSeconds = any(),
                block        = any(),
            )
        } answers { lastArg<() -> InterbankTransferContext>()() }
    }

    Given("타행 이체 - 출금 계좌 없음") {
        // executeInterbankWithdraw 내부에서 AccountNotFoundException 발생
        val request = makeRequest(toBankCode = "011")
        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } throws AccountNotFoundException()

        When("transfer를 호출하면") {
            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> { service.transfer(1L, request) }
            }
            Then("externalBankClient를 호출하지 않는다") {
                runCatching { service.transfer(1L, request) }
                coVerify(exactly = 0) { externalBankClient.transfer(any()) }
            }
        }
    }

    Given("타행 이체 - 외부 API 200 성공") {
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val externalResponse = makeExternalResponse("ext-tx-001")

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(any()) } returns externalResponse
        every { transferTransactionExecutor.markInterbankCompleted(1L, "ext-tx-001") } just Runs

        When("transfer를 호출하면") {
            Then("TransferResponse가 정상 반환된다") {
                val result = service.transfer(1L, request)
                result.fromAccountId    shouldBe 1L
                result.toMemberName     shouldBe "박토스"
                result.amount           shouldBe BigDecimal("10000")
                result.remainingBalance shouldBe BigDecimal("90000")
            }
            Then("markInterbankCompleted가 externalTxId와 함께 호출된다") {
                service.transfer(1L, request)
                verify(exactly = 1) {
                    transferTransactionExecutor.markInterbankCompleted(1L, "ext-tx-001")
                }
            }
            Then("externalBankClient가 정확히 1회 호출된다 (재시도 없음)") {
                service.transfer(1L, request)
                coVerify(exactly = 1) { externalBankClient.transfer(any()) }
            }
            Then("보상 트랜잭션이 호출되지 않는다") {
                service.transfer(1L, request)
                verify(exactly = 0) { transferTransactionExecutor.compensateInterbank(any()) }
            }
        }
    }

    Given("타행 이체 - 외부 API 4xx 확정 실패 → 보상 트랜잭션 성공") {
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val ex4xx            = make4xxException(400)

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(any()) } throws ex4xx
        every { transferTransactionExecutor.compensateInterbank(1L) } just Runs

        When("transfer를 호출하면") {
            Then("ExternalTransferFailedException이 발생한다") {
                shouldThrow<ExternalTransferFailedException> { service.transfer(1L, request) }
            }
            Then("externalBankClient가 정확히 1회만 호출된다 (4xx는 재시도 안 함)") {
                runCatching { service.transfer(1L, request) }
                coVerify(exactly = 1) { externalBankClient.transfer(any()) }
            }
            Then("compensateInterbank가 호출된다") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 1) { transferTransactionExecutor.compensateInterbank(1L) }
            }
            Then("markInterbankCompleted/markInterbankUnknown은 호출되지 않는다") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 0) { transferTransactionExecutor.markInterbankCompleted(any(), any()) }
                verify(exactly = 0) { transferTransactionExecutor.markInterbankUnknown(any(), any()) }
            }
        }
    }

    Given("타행 이체 - 외부 API 4xx 확정 실패 → 보상 트랜잭션 자체도 실패") {
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val ex4xx            = make4xxException(400)

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(any()) } throws ex4xx
        // 보상 트랜잭션 실패 → COMPENSATION_PENDING 저장(REQUIRES_NEW) 후 예외 throw
        every { transferTransactionExecutor.compensateInterbank(1L) } throws CompensationFailedException(
            RuntimeException("DB error")
        )

        When("transfer를 호출하면") {
            Then("사용자에게 ExternalTransferFailedException이 반환된다 (보상 실패여도 동일 응답)") {
                shouldThrow<ExternalTransferFailedException> { service.transfer(1L, request) }
            }
            Then("compensateInterbank가 호출되었다") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 1) { transferTransactionExecutor.compensateInterbank(1L) }
            }
            Then("markInterbankUnknown은 호출되지 않는다 (보상 실패는 UNKNOWN 아님)") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 0) { transferTransactionExecutor.markInterbankUnknown(any(), any()) }
            }
        }
    }

    Given("타행 이체 - 외부 API 5xx 3회 모두 실패 → UNKNOWN") {
        // delay가 실제로 발생하므로 테스트 소요 시간 약 1.5초
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val ex5xx            = make5xxException(500)

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(any()) } throws ex5xx
        every { transferTransactionExecutor.markInterbankUnknown(1L, any()) } just Runs

        When("transfer를 호출하면") {
            Then("ExternalTransferUnknownException이 발생한다") {
                shouldThrow<ExternalTransferUnknownException> { service.transfer(1L, request) }
            }
            Then("externalBankClient가 정확히 3회 호출된다 (maxAttempts=3)") {
                runCatching { service.transfer(1L, request) }
                coVerify(exactly = 3) { externalBankClient.transfer(any()) }
            }
            Then("markInterbankUnknown이 호출된다") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 1) { transferTransactionExecutor.markInterbankUnknown(1L, any()) }
            }
            Then("보상 트랜잭션이 호출되지 않는다 (UNKNOWN은 입금 여부 불확실)") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 0) { transferTransactionExecutor.compensateInterbank(any()) }
            }
        }
    }

    Given("타행 이체 - 외부 API 5xx 2회 실패 후 3번째 성공 (재시도)") {
        // delay 500ms 발생
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val ex5xx            = make5xxException(500)
        val externalResponse = makeExternalResponse("ext-tx-retry-001")

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        // 1, 2번 실패 → 3번 성공
        coEvery { externalBankClient.transfer(any()) } throws ex5xx andThenThrows ex5xx andThen externalResponse
        every { transferTransactionExecutor.markInterbankCompleted(1L, "ext-tx-retry-001") } just Runs

        When("transfer를 호출하면") {
            Then("TransferResponse가 정상 반환된다") {
                val result = service.transfer(1L, request)
                result.remainingBalance shouldBe BigDecimal("90000")
            }
            Then("externalBankClient가 3회 호출된다") {
                service.transfer(1L, request)
                coVerify(exactly = 3) { externalBankClient.transfer(any()) }
            }
            Then("markInterbankCompleted가 호출된다") {
                service.transfer(1L, request)
                verify(exactly = 1) {
                    transferTransactionExecutor.markInterbankCompleted(1L, "ext-tx-retry-001")
                }
            }
            Then("markInterbankUnknown이 호출되지 않는다") {
                service.transfer(1L, request)
                verify(exactly = 0) { transferTransactionExecutor.markInterbankUnknown(any(), any()) }
            }
        }
    }

    Given("타행 이체 - 외부 API 5xx 1회 실패 후 2번째 성공 (2회차 내 성공)") {
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val ex5xx            = make5xxException(500)
        val externalResponse = makeExternalResponse("ext-tx-002")

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(any()) } throws ex5xx andThen externalResponse
        every { transferTransactionExecutor.markInterbankCompleted(1L, "ext-tx-002") } just Runs

        When("transfer를 호출하면") {
            Then("2회 호출 후 성공 응답이 반환된다") {
                val result = service.transfer(1L, request)
                result.fromAccountId shouldBe 1L
            }
            Then("externalBankClient가 2회 호출된다") {
                service.transfer(1L, request)
                coVerify(exactly = 2) { externalBankClient.transfer(any()) }
            }
        }
    }

    Given("타행 이체 - 예상치 못한 예외 발생 (네트워크 단절 등) → UNKNOWN") {
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        // ExternalBankApiException이 아닌 순수 RuntimeException (네트워크 단절)
        coEvery { externalBankClient.transfer(any()) } throws RuntimeException("Connection reset by peer")
        every { transferTransactionExecutor.markInterbankUnknown(1L, any()) } just Runs

        When("transfer를 호출하면") {
            Then("ExternalTransferUnknownException이 발생한다") {
                shouldThrow<ExternalTransferUnknownException> { service.transfer(1L, request) }
            }
            Then("markInterbankUnknown이 호출된다 (결과 불확실 → UNKNOWN)") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 1) { transferTransactionExecutor.markInterbankUnknown(1L, any()) }
            }
            Then("보상 트랜잭션이 호출되지 않는다") {
                runCatching { service.transfer(1L, request) }
                verify(exactly = 0) { transferTransactionExecutor.compensateInterbank(any()) }
            }
            Then("externalBankClient가 1회만 호출된다 (RuntimeException은 retryOn 조건 false)") {
                // retryOn = { it is ExternalBankApiException && it.isServerError }
                // RuntimeException은 ExternalBankApiException이 아니므로 즉시 throw
                runCatching { service.transfer(1L, request) }
                coVerify(exactly = 1) { externalBankClient.transfer(any()) }
            }
        }
    }

    Given("타행 이체 - memberClient를 호출하지 않는다 (타행은 fromMemberName 불필요)") {
        val request          = makeRequest(toBankCode = "011")
        val interbankContext = makeInterbankContext()
        val externalResponse = makeExternalResponse()

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(any()) } returns externalResponse
        every { transferTransactionExecutor.markInterbankCompleted(any(), any()) } just Runs

        When("transfer를 호출하면") {
            Then("MultiLock이 아닌 SingleLock을 사용한다") {
                service.transfer(1L, request)
                verify(exactly = 1) {
                    lockManager.withSingleLock<InterbankTransferContext>(
                        accountId = 1L, waitSeconds = any(), leaseSeconds = any(), block = any(),
                    )
                }
                verify(exactly = 0) {
                    lockManager.withTransferLocks<Any>(any(), any(), any(), any(), any())
                }
            }
        }
    }

    Given("타행 이체 - 외부 API 성공 시 idempotencyKey가 그대로 외부 API 요청에 전달된다") {
        val request          = makeRequest(toBankCode = "011", idempotencyKey = "idem-key-999")
        val interbankContext = makeInterbankContext()
        val externalResponse = makeExternalResponse()
        val requestSlot      = slot<com.tossbank.account.infrastructure.client.dto.ExternalTransferRequest>()

        setupSingleLock()
        every {
            transferTransactionExecutor.executeInterbankWithdraw(
                memberId = any(), fromAccountId = any(), toAccountNumber = any(),
                toBankCode = any(), toMemberName = any(), amount = any(),
                description = any(), idempotencyKey = any(),
            )
        } returns interbankContext
        coEvery { externalBankClient.transfer(capture(requestSlot)) } returns externalResponse
        every { transferTransactionExecutor.markInterbankCompleted(any(), any()) } just Runs

        When("transfer를 호출하면") {
            Then("외부 API 요청에 idempotencyKey가 포함된다 (외부 은행 중복 전송 방지)") {
                service.transfer(1L, request)
                requestSlot.captured.idempotencyKey shouldBe "idem-key-999"
            }
        }
    }
})