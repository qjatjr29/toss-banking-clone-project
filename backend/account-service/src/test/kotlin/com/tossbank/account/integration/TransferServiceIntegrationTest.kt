package com.tossbank.account.integration

import AccountNotFoundException
import ExternalTransferNotSupportedException
import InsufficientBalanceException
import TransferSameAccountException
import UnauthorizedAccountAccessException
import com.tossbank.account.application.TransferService
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.*

class TransferServiceIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var transferService: TransferService
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var transactionHistoryRepository: TransactionHistoryRepository

    fun createAccount(memberId: Long, balance: BigDecimal, accountNumber: String) =
        accountRepository.save(
            Account(memberId = memberId, accountNumber = accountNumber,
                holderName = "테스트유저$memberId", balance = balance, status = AccountStatus.ACTIVE)
        )

    fun makeRequest(
        fromAccountId: Long,
        toAccountNumber: String,
        amount: BigDecimal,
        toBankCode: String     = "092",
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) = TransferRequest(
        fromAccountId   = fromAccountId,
        toAccountNumber = toAccountNumber,
        toBankCode      = toBankCode,
        toMemberName    = "수취인",
        amount          = amount,
        idempotencyKey  = idempotencyKey,
    )

    // 테스트 간 데이터 격리 — 각 테스트 후 전체 정리
    init {
        afterEach { transactionHistoryRepository.deleteAll() }
        afterSpec { accountRepository.deleteAll() }
    }

    init {
        Given("정상 이체 조건이 갖춰졌을 때") {

            When("당행 계좌 간 정상 이체를 실행하면") {

                // ✅ 통합 테스트는 Then 하나에 연관 검증을 묶는 것이 효율적
                //    (매 Then마다 DB 셋업 반복 방지)
                Then("이체 결과 및 DB 잔액/거래내역이 모두 정확히 반영된다") {
                    val from    = createAccount(1L, BigDecimal("100000"), "1002-001-000001")
                    val to      = createAccount(2L, BigDecimal("50000"),  "1002-001-000002")
                    val request = makeRequest(from.id, to.accountNumber, BigDecimal("30000"))

                    val result = transferService.transfer(memberId = 1L, request = request)

                    // 응답 검증
                    result.fromAccountId shouldBe from.id
                    result.toMemberName  shouldBe "수취인"
                    result.amount shouldBeEqualComparingTo BigDecimal("30000")
                    result.remainingBalance shouldBeEqualComparingTo BigDecimal("70000")

                    // DB 잔액 검증
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("70000")
                    accountRepository.findById(to.id).get().balance   shouldBeEqualComparingTo BigDecimal("80000")

                    // 거래내역 저장 검증
                    val histories = transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id || it.accountId == to.id }
                    histories.size shouldBe 2
                }
            }
        }

        Given("이체가 실패해야 하는 조건일 때") {

            When("타행 코드로 이체를 요청하면") {
                Then("ExternalTransferNotSupportedException이 발생하고 잔액이 불변이다") {
                    val from    = createAccount(10L, BigDecimal("100000"), "1002-002-000001")
                    val request = makeRequest(from.id, "110-000-000001", BigDecimal("10000"), toBankCode = "004")

                    shouldThrow<ExternalTransferNotSupportedException> {
                        transferService.transfer(10L, request)
                    }
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("100000")
                }
            }

            When("잔액이 부족한 계좌에서 이체하면") {
                Then("InsufficientBalanceException이 발생하고 양 계좌 잔액이 불변이다") {
                    val from    = createAccount(20L, BigDecimal("5000"),  "1002-003-000001")
                    val to      = createAccount(21L, BigDecimal("10000"), "1002-003-000002")
                    val request = makeRequest(from.id, to.accountNumber, BigDecimal("10000"))

                    shouldThrow<InsufficientBalanceException> {
                        transferService.transfer(20L, request)
                    }
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("5000")
                    accountRepository.findById(to.id).get().balance   shouldBeEqualComparingTo BigDecimal("10000")
                }
            }

            When("존재하지 않는 수취 계좌번호로 이체하면") {
                Then("AccountNotFoundException이 발생하고 출금 계좌 잔액이 불변이다") {
                    val from    = createAccount(30L, BigDecimal("100000"), "1002-004-000001")
                    val request = makeRequest(from.id, "9999-999-999999", BigDecimal("10000"))

                    shouldThrow<AccountNotFoundException> {
                        transferService.transfer(30L, request)
                    }
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("100000")
                }
            }

            When("동일 계좌로 이체하면") {
                Then("TransferSameAccountException이 발생한다") {
                    val account = createAccount(40L, BigDecimal("100000"), "1002-005-000001")
                    val request = makeRequest(account.id, account.accountNumber, BigDecimal("10000"))

                    shouldThrow<TransferSameAccountException> {
                        transferService.transfer(40L, request)
                    }
                }
            }

            When("타인의 계좌에서 이체하면") {
                Then("UnauthorizedAccountAccessException이 발생한다") {
                    val from    = createAccount(50L, BigDecimal("100000"), "1002-006-000001")
                    val to      = createAccount(51L, BigDecimal("50000"),  "1002-006-000002")
                    val request = makeRequest(from.id, to.accountNumber, BigDecimal("10000"))

                    shouldThrow<UnauthorizedAccountAccessException> {
                        transferService.transfer(99L, request) // 99L = 비소유자
                    }
                }
            }
        }

        Given("동일한 idempotencyKey로 이체를 2번 요청할 때") {

            When("첫 번째 이체 성공 후 동일 key로 재요청하면") {
                Then("2차 요청도 성공하고 잔액은 1번만 차감된다") {
                    val from    = createAccount(60L, BigDecimal("100000"), "1002-007-000001")
                    val to      = createAccount(61L, BigDecimal("50000"),  "1002-007-000002")
                    val request = makeRequest(from.id, to.accountNumber, BigDecimal("20000"),
                        idempotencyKey = "idem-key-fixed-001")

                    transferService.transfer(60L, request) // 1차
                    val secondResult = transferService.transfer(60L, request) // 2차 재시도

                    secondResult.amount shouldBeEqualComparingTo BigDecimal("20000")
                    // 20000 딱 1번만 차감
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("80000")
                }

                Then("거래내역은 2건(1회분)만 저장된다") {
                    val from    = createAccount(62L, BigDecimal("100000"), "1002-007-000003")
                    val to      = createAccount(63L, BigDecimal("50000"),  "1002-007-000004")
                    val request = makeRequest(from.id, to.accountNumber, BigDecimal("20000"),
                        idempotencyKey = "idem-key-fixed-002")

                    transferService.transfer(62L, request)
                    transferService.transfer(62L, request)

                    val histories = transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id || it.accountId == to.id }
                    histories.size shouldBe 2
                }
            }
        }
    }
}