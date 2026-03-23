package com.tossbank.account.integration

import InsufficientBalanceException
import com.tossbank.account.application.TransferService
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.client.ExternalBankClient
import com.tossbank.account.infrastructure.client.MemberClient
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class TransferConcurrencyIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var transferService: TransferService
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var transactionHistoryRepository: TransactionHistoryRepository
    @Autowired lateinit var memberClient: MemberClient
    @Autowired lateinit var externalBankClient: ExternalBankClient

    fun createAccount(memberId: Long, balance: BigDecimal, accountNumber: String) =
        accountRepository.save(
            Account(
                memberId      = memberId,
                accountNumber = accountNumber,
                holderName    = "테스트유저$memberId",
                balance       = balance,
                status        = AccountStatus.ACTIVE,
            )
        )

    init {
        beforeEach {
            clearAllMocks()
            every { memberClient.getMemberName(any()) } returns "테스트유저"
        }
        afterEach { transactionHistoryRepository.deleteAll() }
        afterSpec  { accountRepository.deleteAll() }
    }

    init {

        Given("동시 이체 - 잔액 100,000원에서 10,000원씩 5번 동시 요청") {
            When("5개 코루틴이 동시에 이체하면") {
                Then("총 50,000원 정확히 차감 + 거래내역 10건") {
                    val from = createAccount(100L, BigDecimal("100000"), "1002-100-000001")
                    val to   = createAccount(101L, BigDecimal("0"),      "1002-100-000002")

                    withContext(Dispatchers.IO) {
                        (1..5).map {
                            async {
                                transferService.transfer(
                                    memberId = 100L,
                                    request  = TransferRequest(
                                        fromAccountId   = from.id,
                                        toAccountNumber = to.accountNumber,
                                        toBankCode      = "092",
                                        toMemberName    = "수취인",
                                        amount          = BigDecimal("10000"),
                                        idempotencyKey  = UUID.randomUUID().toString(),
                                    )
                                )
                            }
                        }.awaitAll()
                    }

                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("50000")
                    accountRepository.findById(to.id).get().balance   shouldBeEqualComparingTo BigDecimal("50000")

                    transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id || it.accountId == to.id }
                        .size shouldBe 10
                }
            }
        }

        Given("동시 이체 - 잔액 30,000원에서 10,000원씩 5번 동시 요청 (잔액 부족)") {
            When("5개 코루틴이 동시에 이체하면") {
                Then("최대 3건 성공 + 잔액 0 이상 보장 + 총액 보존") {
                    val from = createAccount(200L, BigDecimal("30000"), "1002-200-000001")
                    val to   = createAccount(201L, BigDecimal("0"),     "1002-200-000002")

                    val successCount = AtomicInteger(0)
                    val failCount    = AtomicInteger(0)

                    withContext(Dispatchers.IO) {
                        (1..5).map {
                            async {
                                try {
                                    transferService.transfer(
                                        memberId = 200L,
                                        request  = TransferRequest(
                                            fromAccountId   = from.id,
                                            toAccountNumber = to.accountNumber,
                                            toBankCode      = "092",
                                            toMemberName    = "수취인",
                                            amount          = BigDecimal("10000"),
                                            idempotencyKey  = UUID.randomUUID().toString(),
                                        )
                                    )
                                    successCount.incrementAndGet()
                                } catch (e: InsufficientBalanceException) {
                                    failCount.incrementAndGet()
                                }
                            }
                        }.awaitAll()
                    }

                    successCount.get() shouldBeLessThanOrEqual 3
                    (successCount.get() + failCount.get()) shouldBe 5

                    val remaining = accountRepository.findById(from.id).get().balance
                    val received  = accountRepository.findById(to.id).get().balance

                    remaining.compareTo(BigDecimal.ZERO) shouldBeGreaterThanOrEqualTo 0
                    remaining.add(received) shouldBeEqualComparingTo BigDecimal("30000")
                }
            }
        }

        Given("동시 멱등성 - 동일 키로 3번 동시 요청") {
            When("3개 코루틴이 동일 key로 동시에 이체하면") {
                Then("잔액 정확히 1번만 차감 + 거래내역 2건만 저장") {
                    val from     = createAccount(300L, BigDecimal("100000"), "1002-300-000001")
                    val to       = createAccount(301L, BigDecimal("0"),      "1002-300-000002")
                    val fixedKey = "concurrent-idem-key-001"

                    withContext(Dispatchers.IO) {
                        (1..3).map {
                            async {
                                runCatching {
                                    transferService.transfer(
                                        memberId = 300L,
                                        request  = TransferRequest(
                                            fromAccountId   = from.id,
                                            toAccountNumber = to.accountNumber,
                                            toBankCode      = "092",
                                            toMemberName    = "수취인",
                                            amount          = BigDecimal("20000"),
                                            idempotencyKey  = fixedKey,
                                        )
                                    )
                                }
                            }
                        }.awaitAll()
                    }

                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("80000")
                    accountRepository.findById(to.id).get().balance   shouldBeEqualComparingTo BigDecimal("20000")

                    transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id || it.accountId == to.id }
                        .size shouldBe 2
                }
            }
        }

        Given("교차 이체 - A→B, B→A 동시 (데드락 방지 검증)") {
            When("두 코루틴이 서로 반대 방향으로 동시에 이체하면") {
                Then("데드락 없이 모두 완료 + 총 잔액 보존") {
                    val accountA = createAccount(400L, BigDecimal("100000"), "1002-400-000001")
                    val accountB = createAccount(401L, BigDecimal("100000"), "1002-400-000002")

                    withContext(Dispatchers.IO) {
                        listOf(
                            async {
                                runCatching {
                                    transferService.transfer(
                                        memberId = 400L,
                                        request  = TransferRequest(
                                            fromAccountId   = accountA.id,
                                            toAccountNumber = accountB.accountNumber,
                                            toBankCode      = "092",
                                            toMemberName    = "B유저",
                                            amount          = BigDecimal("30000"),
                                            idempotencyKey  = UUID.randomUUID().toString(),
                                        )
                                    )
                                }
                            },
                            async {
                                runCatching {
                                    transferService.transfer(
                                        memberId = 401L,
                                        request  = TransferRequest(
                                            fromAccountId   = accountB.id,
                                            toAccountNumber = accountA.accountNumber,
                                            toBankCode      = "092",
                                            toMemberName    = "A유저",
                                            amount          = BigDecimal("50000"),
                                            idempotencyKey  = UUID.randomUUID().toString(),
                                        )
                                    )
                                }
                            }
                        ).awaitAll()
                    }

                    val finalA = accountRepository.findById(accountA.id).get().balance
                    val finalB = accountRepository.findById(accountB.id).get().balance
                    finalA.add(finalB) shouldBeEqualComparingTo BigDecimal("200000")
                }
            }
        }
    }
}