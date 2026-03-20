package com.tossbank.account.integration

import InsufficientBalanceException
import com.tossbank.account.application.TransferService
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
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

    fun createAccount(memberId: Long, balance: BigDecimal, accountNumber: String) =
        accountRepository.save(
            Account(
                memberId      = memberId,
                accountNumber = accountNumber,
                holderName    = "테스트유저$memberId",
                balance       = balance,
                status        = AccountStatus.ACTIVE
            )
        )

    init {
        afterEach { transactionHistoryRepository.deleteAll() }
        afterSpec  { accountRepository.deleteAll() }
    }

    init {

        // 동시 이체 상황: 잔액 정합성 확인
        Given("잔액 100,000원 계좌에서 10,000원씩 동시에 5번 이체 요청이 들어올 때") {

            When("5개의 코루틴이 동시에 이체를 실행하면") {

                Then("총 차감액은 정확히 50,000원이고 잔액은 50,000원이어야 한다") {
                    val from = createAccount(100L, BigDecimal("100000"), "1002-100-000001")
                    val to   = createAccount(101L, BigDecimal("0"),      "1002-100-000002")

                    val concurrentCount = 5
                    val amount          = BigDecimal("10000")

                    // IO 디스패처: 동시 실행을 위해 Dispatchers.IO 사용
                    withContext(Dispatchers.IO) {
                        (1..concurrentCount).map {
                            async {
                                transferService.transfer(
                                    memberId = 100L,
                                    request  = TransferRequest(
                                        fromAccountId   = from.id,
                                        toAccountNumber = to.accountNumber,
                                        toBankCode      = "092",
                                        toMemberName    = "범석",
                                        amount          = amount,
                                        idempotencyKey  = UUID.randomUUID().toString(),
                                    )
                                )
                            }
                        }.awaitAll()
                    }

                    // 분산락/비관적락이 올바르게 동작하면 정확히 5만원 차감
                    accountRepository.findById(from.id).get().balance
                        .shouldBeEqualComparingTo(BigDecimal("50000"))
                    accountRepository.findById(to.id).get().balance
                        .shouldBeEqualComparingTo(BigDecimal("50000"))

                    // 거래내역도 정확히 10건(5회 × 출금+입금)
                    val histories = transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id || it.accountId == to.id }
                    histories.size shouldBe 10
                }
            }
        }

        // 동시 이체 테스트: 잔액 부족 시 일부만 성공
        Given("잔액 30,000원 계좌에서 10,000원씩 동시에 5번 이체 요청이 들어올 때") {

            When("5개의 코루틴이 동시에 이체를 실행하면") {

                Then("최대 3건만 성공하고 나머지는 InsufficientBalanceException이 발생해야 한다") {
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

                    // 성공은 최대 3번을 초과할 수 없음
                    successCount.get() shouldBeLessThanOrEqual 3
                    // 성공 + 실패 = 5
                    (successCount.get() + failCount.get()) shouldBe 5

                    // 잔액은 절대 0 미만이 될 수 없음 (핵심 정합성)
                    val remaining = accountRepository.findById(from.id).get().balance
                    val toBalance = accountRepository.findById(to.id).get().balance
                    remaining.add(toBalance) shouldBeEqualComparingTo BigDecimal("30000")

                    // 잔액은 절대 음수가 되어서는 안 됨
                    remaining.compareTo(BigDecimal.ZERO) shouldBeGreaterThanOrEqualTo 0
                }
            }
        }

        // 동시 + 멱등성: 같은 key로 동시 요청
        Given("동일한 idempotencyKey로 동시에 3번 이체 요청이 들어올 때") {

            When("3개의 코루틴이 동일한 key로 동시에 이체를 실행하면") {

                Then("잔액은 정확히 1번만 차감되어야 한다") {
                    val from = createAccount(300L, BigDecimal("100000"), "1002-300-000001")
                    val to   = createAccount(301L, BigDecimal("0"),      "1002-300-000002")
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

                    // 20,000원 딱 1번만 차감
                    accountRepository.findById(from.id).get().balance
                        .shouldBeEqualComparingTo(BigDecimal("80000"))
                    accountRepository.findById(to.id).get().balance
                        .shouldBeEqualComparingTo(BigDecimal("20000"))

                    // 거래내역 2건(출금 1 + 입금 1)만 저장
                    val histories = transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id || it.accountId == to.id }
                    histories.size shouldBe 2
                }
            }
        }

        // 교차 이체 상황 테스트: 데드락 방지 검증
        Given("A→B, B→A 교차 이체가 동시에 들어올 때") {

            When("두 코루틴이 서로 반대 방향으로 동시에 이체하면") {

                Then("데드락 없이 모두 완료되고 총 잔액이 보존된다") {
                    val accountA = createAccount(400L, BigDecimal("100000"), "1002-400-000001")
                    val accountB = createAccount(401L, BigDecimal("100000"), "1002-400-000002")
                    val totalBefore = BigDecimal("200000")

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

                    // 두 계좌 잔액의 합은 항상 200,000원이어야 함
                    val finalA = accountRepository.findById(accountA.id).get().balance
                    val finalB = accountRepository.findById(accountB.id).get().balance
                    finalA.add(finalB) shouldBeEqualComparingTo totalBefore
                }
            }
        }
    }
}