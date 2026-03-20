package com.tossbank.account.application

import AccountNotFoundException
import InsufficientBalanceException
import UnauthorizedAccountAccessException
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.domain.model.TransactionType
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal

class TransferTransactionExecutorTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerLeaf

    val accountRepository            = mockk<AccountRepository>()
    val transactionHistoryRepository = mockk<TransactionHistoryRepository>()

    val executor = TransferTransactionExecutor(
        accountRepository            = accountRepository,
        transactionHistoryRepository = transactionHistoryRepository,
    )

    afterTest { clearAllMocks() }

    fun makeAccount(
        id: Long              = 1L,
        memberId: Long        = 1L,
        accountNumber: String = "1002-000-000001",
        balance: BigDecimal   = BigDecimal("100000"),
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(
        memberId      = memberId,
        accountNumber = accountNumber,
        holderName    = "김토스",
        balance       = balance,
        status        = status,
    ).also {
        Account::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(it, id)
        }
    }

    fun makeRequest(
        fromAccountId: Long    = 1L,
        toAccountNumber: String = "1002-000-000002",
        toMemberName: String   = "박토스",
        amount: BigDecimal     = BigDecimal("10000"),
        idempotencyKey: String = "idem-key-001",
        description: String?   = null,
    ) = TransferRequest(
        fromAccountId   = fromAccountId,
        toAccountNumber = toAccountNumber,
        toBankCode      = "092",
        toMemberName    = toMemberName,
        amount          = amount,
        idempotencyKey  = idempotencyKey,
        description     = description,
    )

    Given("멱등성 키가 이미 처리된 이체 요청일 때") {

        val request = makeRequest()
        val existingHistory = TransactionHistory.ofTransfer(
            accountId                = 1L,
            amount                   = BigDecimal("10000"),
            balanceAfterTx           = BigDecimal("90000"),
            counterpartAccountNumber = "1002-000-000002",
            counterpartName          = "박토스",
            description              = "박토스에게 이체",
            isOutgoing               = true,
            idempotencyKey           = "idem-key-001",
        )

        every {
            transactionHistoryRepository.findByIdempotencyKey("idem-key-001")
        } returns existingHistory

        When("동일한 idempotencyKey로 execute를 호출하면") {

            Then("기존 결과(TransferResponse)가 그대로 반환된다") {
                val result = executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                result.amount shouldBeEqualComparingTo BigDecimal("10000")
                result.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
            }

            Then("계좌 조회(findByIdWithLock)를 시도하지 않는다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                verify(exactly = 0) { accountRepository.findByIdWithLock(any()) }
            }

            Then("거래내역 저장(saveAll)을 시도하지 않는다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("정상 이체가 실행될 때") {
        val fromAccount = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000001")
        val toAccount   = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("50000"),  accountNumber = "1002-000-000002")
        val historiesSlot = slot<List<TransactionHistory>>()

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { transactionHistoryRepository.saveAll(capture(historiesSlot)) } returns emptyList()

        When("fromAccountId(1) < toAccountId(2) 순서로 요청하면") {
            val request = makeRequest(fromAccountId = 1L, amount = BigDecimal("10000"))

            // ID 오름차순 → firstId=1, secondId=2
            every { accountRepository.findByIdWithLock(1L) } returns fromAccount
            every { accountRepository.findByIdWithLock(2L) } returns toAccount

            Then("TransferResponse가 정상 반환된다") {
                val result = executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                result.fromAccountId shouldBe 1L
                result.toMemberName  shouldBe "박토스"
                result.amount shouldBeEqualComparingTo BigDecimal("10000")
            }

            Then("출금 계좌 잔액이 차감된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("90000")
            }

            Then("입금 계좌 잔액이 증가한다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                toAccount.balance shouldBeEqualComparingTo BigDecimal("60000")
            }

            Then("응답의 remainingBalance는 출금 후 잔액이다") {
                val result = executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                result.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
            }

            Then("거래내역이 2건 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                historiesSlot.captured.size shouldBe 2
            }

            Then("출금 측 거래내역의 타입이 TRANSFER이다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val outgoing = historiesSlot.captured.first { it.accountId == 1L }
                outgoing.transactionType shouldBe TransactionType.TRANSFER
            }

            Then("입금 측 거래내역의 타입이 TRANSFER_IN이다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val incoming = historiesSlot.captured.first { it.accountId == 2L }
                incoming.transactionType shouldBe TransactionType.TRANSFER_IN
            }

            Then("출금 측 거래내역에 idempotencyKey가 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val outgoing = historiesSlot.captured.first { it.accountId == 1L }
                outgoing.idempotencyKey shouldBe "idem-key-001"
            }

            Then("입금 측 거래내역의 idempotencyKey는 null이다 (수취측 중복 방지 불필요)") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val incoming = historiesSlot.captured.first { it.accountId == 2L }
                incoming.idempotencyKey.shouldBeNull()
            }

            Then("출금 측 거래내역의 상대방 정보가 수취 계좌번호/이름으로 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val outgoing = historiesSlot.captured.first { it.accountId == 1L }
                outgoing.counterpartAccountNumber shouldBe "1002-000-000002"
                outgoing.counterpartName          shouldBe "박토스"
            }

            Then("입금 측 거래내역의 상대방 정보가 송금인 계좌번호/이름으로 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val incoming = historiesSlot.captured.first { it.accountId == 2L }
                incoming.counterpartAccountNumber shouldBe "1002-000-000001"
                incoming.counterpartName          shouldBe "김토스"
            }
        }

        When("fromAccountId(3) > toAccountId(2) 순서로 요청하면 (ID 역순 정렬 케이스)") {
            // 데드락 방지를 위해 내부에서 작은 ID(2)를 먼저 잠가야 함
            val fromAccount3 = makeAccount(id = 3L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000003")
            val toAccount2   = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("50000"),  accountNumber = "1002-000-000002")
            val request = makeRequest(fromAccountId = 3L, amount = BigDecimal("10000"))

            // firstId=2(작은 것), secondId=3(큰 것) 순으로 잠금
            every { accountRepository.findByIdWithLock(2L) } returns toAccount2
            every { accountRepository.findByIdWithLock(3L) } returns fromAccount3

            Then("데드락 방지 정렬이 적용되어 작은 ID(2)를 먼저 잠근다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                // 잠금 순서: 2L → 3L
                verify(ordering = io.mockk.Ordering.ORDERED) {
                    accountRepository.findByIdWithLock(2L)
                    accountRepository.findByIdWithLock(3L)
                }
            }

            Then("from/to 방향이 올바르게 복원되어 출금 계좌 잔액이 차감된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                fromAccount3.balance shouldBeEqualComparingTo BigDecimal("90000")
            }

            Then("입금 계좌 잔액이 증가한다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                toAccount2.balance shouldBeEqualComparingTo BigDecimal("60000")
            }
        }

        When("커스텀 description을 지정해서 요청하면") {
            val request = makeRequest(fromAccountId = 1L, description = "생일 축하금")

            every { accountRepository.findByIdWithLock(1L) } returns fromAccount
            every { accountRepository.findByIdWithLock(2L) } returns toAccount

            Then("지정한 description이 거래내역에 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                historiesSlot.captured.forEach {
                    it.description shouldBe "생일 축하금"
                }
            }
        }

        When("description을 지정하지 않으면") {
            val request = makeRequest(fromAccountId = 1L, description = null)

            every { accountRepository.findByIdWithLock(1L) } returns fromAccount
            every { accountRepository.findByIdWithLock(2L) } returns toAccount

            Then("출금 측에 '수취인이름에게 이체' 기본 description이 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val outgoing = historiesSlot.captured.first { it.accountId == 1L }
                outgoing.description shouldBe "박토스에게 이체"
            }

            Then("입금 측에 '송금인이름으로부터 입금' 기본 description이 저장된다") {
                executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val incoming = historiesSlot.captured.first { it.accountId == 2L }
                incoming.description shouldBe "김토스으로부터 입금"
            }
        }
    }

    Given("비관적 락으로 계좌 조회 시 계좌가 없을 때") {

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null

        When("첫 번째 계좌(작은 ID) 조회에 실패하면") {
            every { accountRepository.findByIdWithLock(1L) } returns null

            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    executor.execute(1L, makeRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
            }

            Then("두 번째 계좌 조회를 시도하지 않는다") {
                runCatching {
                    executor.execute(1L, makeRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
                verify(exactly = 0) { accountRepository.findByIdWithLock(2L) }
            }
        }

        When("두 번째 계좌(큰 ID) 조회에 실패하면") {
            val fromAccount = makeAccount(id = 1L)
            every { accountRepository.findByIdWithLock(1L) } returns fromAccount
            every { accountRepository.findByIdWithLock(2L) } returns null

            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    executor.execute(1L, makeRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
            }

            Then("거래내역이 저장되지 않는다") {
                runCatching {
                    executor.execute(1L, makeRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("출금 계좌 소유자 검증이 실패할 때") {

        // fromAccount의 소유자는 99L, 요청자는 1L
        val fromAccount = makeAccount(id = 1L, memberId = 99L, balance = BigDecimal("100000"))
        val toAccount   = makeAccount(id = 2L, memberId = 2L)

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { accountRepository.findByIdWithLock(2L) } returns toAccount

        When("타인의 계좌에서 이체하려고 하면") {

            Then("UnauthorizedAccountAccessException이 발생한다") {
                shouldThrow<UnauthorizedAccountAccessException> {
                    executor.execute(
                        memberId       = 1L,   // 실제 소유자는 99L
                        request        = makeRequest(fromAccountId = 1L),
                        toAccountId    = 2L,
                        fromMemberName = "김토스",
                    )
                }
            }

            Then("거래내역이 저장되지 않는다") {
                runCatching {
                    executor.execute(1L, makeRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("출금 계좌 잔액이 부족할 때") {

        // ✅ 기본값(100000) 대신 잔액을 명시적으로 지정 → 어떤 값인지 명확하게
        val fromAccount = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("5000"))
        val toAccount   = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("30000"))

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { accountRepository.findByIdWithLock(2L) } returns toAccount

        When("잔액(5000)보다 큰 금액(10000)을 이체하려고 하면") {
            val request = makeRequest(fromAccountId = 1L, amount = BigDecimal("10000"))

            Then("InsufficientBalanceException이 발생한다") {
                shouldThrow<InsufficientBalanceException> {
                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                }
            }

            Then("출금 계좌 잔액은 변경되지 않는다") {
                runCatching {
                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                }
                // ✅ withdraw() 실패 → fromAccount 잔액 불변
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("5000")
            }

            Then("입금 계좌 잔액은 변경되지 않는다") {
                runCatching {
                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                }
                // ✅ withdraw() 예외 → deposit() 미호출 → toAccount 잔액 불변
                toAccount.balance shouldBeEqualComparingTo BigDecimal("30000")
            }

            Then("거래내역이 저장되지 않는다") {
                runCatching {
                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                }
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

//    Given("출금 계좌 잔액이 부족할 때") {
//
//        val fromAccount = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("5000"))
//        val toAccount   = makeAccount(id = 2L, memberId = 2L)
//
//        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
//        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
//        every { accountRepository.findByIdWithLock(2L) } returns toAccount
//
//        When("잔액(5000)보다 큰 금액(10000)을 이체하려고 하면") {
//            val request = makeRequest(fromAccountId = 1L, amount = BigDecimal("10000"))
//
//            Then("InsufficientBalanceException이 발생한다") {
//                shouldThrow<InsufficientBalanceException> {
//                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
//                }
//            }
//
//            Then("입금 계좌 잔액은 변경되지 않는다") {
//                runCatching {
//                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
//                }
//                toAccount.balance shouldBeEqualComparingTo BigDecimal("50000")
//            }
//
//            Then("거래내역이 저장되지 않는다") {
//                runCatching {
//                    executor.execute(1L, request, toAccountId = 2L, fromMemberName = "김토스")
//                }
//                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
//            }
//        }
//    }
})