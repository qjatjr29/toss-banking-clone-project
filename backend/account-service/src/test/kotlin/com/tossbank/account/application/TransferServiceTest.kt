package com.tossbank.account.application

import AccountNotFoundException
import ExternalTransferNotSupportedException
import TransferSameAccountException
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.client.MemberClient
import com.tossbank.account.infrastructure.lock.RedissonLockManager
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.presentation.dto.TransferRequest
import com.tossbank.account.presentation.dto.TransferResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import java.math.BigDecimal

class TransferServiceTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerLeaf

    val accountRepository           = mockk<AccountRepository>()
    val lockManager                 = mockk<RedissonLockManager>()
    val transferTransactionExecutor = mockk<TransferTransactionExecutor>()
    val memberClient                = mockk<MemberClient>()

    val service = TransferService(
        accountRepository           = accountRepository,
        lockManager                 = lockManager,
        transferTransactionExecutor = transferTransactionExecutor,
        memberClient                = memberClient,
        dbDispatcher                = Dispatchers.Unconfined,
    )

    afterTest { clearAllMocks() }

    fun makeAccount(
        id: Long              = 1L,
        memberId: Long        = 1L,
        accountNumber: String = "1002-000-000001",
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(
        memberId      = memberId,
        accountNumber = accountNumber,
        holderName    = "김토스",
        balance       = BigDecimal("100000"),
        status        = status,
    ).also { account ->
        Account::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(account, id)
        }
    }

    fun makeRequest(
        fromAccountId: Long     = 1L,
        toAccountNumber: String = "1002-000-000002",
        toBankCode: String      = "092",
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

    fun setupLockManager(mockResponse: TransferResponse) {
        every {
            lockManager.withTransferLocks<TransferResponse>(
                accountId1   = any(),
                accountId2   = any(),
                waitSeconds  = any(),
                leaseSeconds = any(),
                block        = any(),
            )
        } answers {
            // 다섯 번째 인자(index=4)가 실제 실행할 람다
            val block = arg<() -> TransferResponse>(4)
            block()
        }
    }

    Given("이체 요청이 들어올 때") {

        val memberId = 1L

        When("당행(092) 정상 이체를 요청하면") {
            val request     = makeRequest()
            val toAccount   = makeAccount(id = 2L, accountNumber = "1002-000-000002")
            val mockResponse = TransferResponse(
                fromAccountId    = 1L,
                toMemberName     = "박토스",
                amount           = BigDecimal("10000"),
                remainingBalance = BigDecimal("90000"),
            )

            every { accountRepository.findByAccountNumber("1002-000-000002") } returns toAccount
            every { memberClient.getMemberName(memberId) } returns "김토스"
            setupLockManager(mockResponse)
            every {
                transferTransactionExecutor.execute(
                    memberId       = memberId,
                    request        = request,
                    toAccountId    = 2L,
                    fromMemberName = "김토스",
                )
            } returns mockResponse

            Then("TransferResponse가 정상 반환된다") {
                val result = service.transfer(memberId, request)
                result.fromAccountId    shouldBe 1L
                result.toMemberName     shouldBe "박토스"
                result.amount           shouldBe BigDecimal("10000")
                result.remainingBalance shouldBe BigDecimal("90000")
            }

            Then("memberClient로 송금인 이름을 조회한다") {
                service.transfer(memberId, request)
                verify(exactly = 1) { memberClient.getMemberName(memberId) }
            }

            Then("lockManager로 분산 락을 획득한다") {
                service.transfer(memberId, request)
                verify(exactly = 1) {
                    lockManager.withTransferLocks<TransferResponse>(
                        accountId1   = 1L,
                        accountId2   = 2L,
                        waitSeconds  = any(),
                        leaseSeconds = any(),
                        block        = any(),
                    )
                }
            }

            Then("TransferTransactionExecutor가 정확한 인자로 실행된다") {
                service.transfer(memberId, request)
                verify(exactly = 1) {
                    transferTransactionExecutor.execute(
                        memberId       = memberId,
                        request        = request,
                        toAccountId    = 2L,
                        fromMemberName = "김토스",
                    )
                }
            }
        }

        // 타행 이체
        When("타행 은행코드(004 국민은행)로 이체를 요청하면") {
            val request = makeRequest(toBankCode = "004")

            Then("ExternalTransferNotSupportedException이 발생한다") {
                shouldThrow<ExternalTransferNotSupportedException> {
                    service.transfer(memberId, request)
                }
            }

            Then("수취 계좌 조회를 시도하지 않는다") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) { accountRepository.findByAccountNumber(any()) }
            }

            Then("memberClient를 호출하지 않는다") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) { memberClient.getMemberName(any()) }
            }

            Then("분산 락을 획득하지 않는다") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) {
                    lockManager.withTransferLocks<TransferResponse>(any(), any(), any(), any(), any())
                }
            }
        }

        // 없는 계좌에 이체하는 경우
        When("존재하지 않는 수취 계좌번호로 이체를 요청하면") {
            val request = makeRequest(toAccountNumber = "9999-000-000000")
            every { accountRepository.findByAccountNumber("9999-000-000000") } returns null

            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    service.transfer(memberId, request)
                }
            }

            Then("memberClient를 호출하지 않는다 (계좌 확인 후 조기 종료)") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) { memberClient.getMemberName(any()) }
            }

            Then("분산 락을 획득하지 않는다") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) {
                    lockManager.withTransferLocks<TransferResponse>(any(), any(), any(), any(), any())
                }
            }
        }

        // 동일 계좌에 이체하는 경우
        When("출금 계좌와 수취 계좌가 동일하면") {
            val request     = makeRequest(fromAccountId = 1L, toAccountNumber = "1002-000-000001")
            val sameAccount = makeAccount(id = 1L, accountNumber = "1002-000-000001")
            every { accountRepository.findByAccountNumber("1002-000-000001") } returns sameAccount

            Then("TransferSameAccountException이 발생한다") {
                shouldThrow<TransferSameAccountException> {
                    service.transfer(memberId, request)
                }
            }

            Then("memberClient를 호출하지 않는다 (동일 계좌 확인 후 조기 종료)") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) { memberClient.getMemberName(any()) }
            }

            Then("분산 락을 획득하지 않는다") {
                runCatching { service.transfer(memberId, request) }
                verify(exactly = 0) {
                    lockManager.withTransferLocks<TransferResponse>(any(), any(), any(), any(), any())
                }
            }
        }
    }
})