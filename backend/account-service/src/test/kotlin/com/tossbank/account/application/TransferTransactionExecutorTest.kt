package com.tossbank.account.application

import AccountNotFoundException
import CompensationFailedException
import ExternalTransferFailedException
import InsufficientBalanceException
import InterbankTransferNotFoundException
import UnauthorizedAccountAccessException
import com.tossbank.account.domain.model.*
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.InterbankTransferRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal
import java.util.*

class TransferTransactionExecutorTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerLeaf

    val accountRepository            = mockk<AccountRepository>()
    val transactionHistoryRepository = mockk<TransactionHistoryRepository>()
    val interbankTransferRepository = mockk<InterbankTransferRepository>()

    val executor = TransferTransactionExecutor(
        accountRepository            = accountRepository,
        transactionHistoryRepository = transactionHistoryRepository,
        interbankTransferRepository = interbankTransferRepository,
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

    fun makeInterbankTransfer(
        id: Long                        = 1L,
        fromAccountId: Long             = 1L,
        fromAccountNumber: String       = "1002-000-000001",
        toAccountNumber: String         = "3333-000-000001",
        toBankCode: String              = "011",
        amount: BigDecimal              = BigDecimal("10000"),
        idempotencyKey: String?         = "idem-key-001",
        status: InterbankTransferStatus = InterbankTransferStatus.WITHDRAW_COMPLETED,
        retryCount: Int                 = 0,
    ) = InterbankTransfer(
        fromMemberId      = 1L,
        fromAccountId     = fromAccountId,
        fromAccountNumber = fromAccountNumber,
        toAccountNumber   = toAccountNumber,
        toBankCode        = toBankCode,
        toMemberName      = "박토스",
        amount            = amount,
        description       = null,
        idempotencyKey    = idempotencyKey,
    ).also { transfer ->
        InterbankTransfer::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(transfer, id)
        }
        transfer.status     = status
        transfer.retryCount = retryCount
    }

    fun makeWithdrawHistory(
        accountId: Long        = 1L,
        amount: BigDecimal     = BigDecimal("10000"),
        balanceAfterTx: BigDecimal = BigDecimal("90000"),
        idempotencyKey: String = "idem-key-001",
    ) = TransactionHistory.ofInterbankWithdraw(
        accountId       = accountId,
        amount          = amount,
        balanceAfterTx  = balanceAfterTx,
        toAccountNumber = "3333-000-000001",
        toMemberName    = "박토스",
        toBankCode      = "011",
        description     = null,
        idempotencyKey  = idempotencyKey,
    )

    fun makeTransferRequest(
        fromAccountId: Long     = 1L,
        toAccountNumber: String = "1002-000-000002",
        toMemberName: String    = "박토스",
        amount: BigDecimal      = BigDecimal("10000"),
        idempotencyKey: String  = "idem-key-001",
        description: String?    = null,
    ) = TransferRequest(
        fromAccountId   = fromAccountId,
        toAccountNumber = toAccountNumber,
        toBankCode      = "092",
        toMemberName    = toMemberName,
        amount          = amount,
        idempotencyKey  = idempotencyKey,
        description     = description,
    )

    Given("당행 이체 - 멱등성 키가 이미 처리된 요청일 때") {
        val request = makeTransferRequest()
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
        every { transactionHistoryRepository.findByIdempotencyKey("idem-key-001") } returns existingHistory

        When("동일한 idempotencyKey로 executeInternalTransfer를 호출하면") {
            Then("기존 TransferResponse가 그대로 반환된다") {
                val result = executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                result.amount            shouldBeEqualComparingTo BigDecimal("10000")
                result.remainingBalance  shouldBeEqualComparingTo BigDecimal("90000")
            }
            Then("계좌 조회를 시도하지 않는다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                verify(exactly = 0) { accountRepository.findByIdWithLock(any()) }
            }
            Then("거래내역 저장을 시도하지 않는다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("당행 이체 - 정상 이체 (fromId < toId)") {
        val fromAccount   = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000001")
        val toAccount     = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("50000"),  accountNumber = "1002-000-000002")
        val historiesSlot = slot<List<TransactionHistory>>()

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { accountRepository.findByIdWithLock(2L) } returns toAccount
        every { transactionHistoryRepository.saveAll(capture(historiesSlot)) } returns emptyList()

        val request = makeTransferRequest(fromAccountId = 1L, amount = BigDecimal("10000"))

        When("executeInternalTransfer를 호출하면") {
            Then("TransferResponse가 정상 반환된다") {
                val result = executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                result.fromAccountId     shouldBe 1L
                result.toMemberName      shouldBe "박토스"
                result.amount            shouldBeEqualComparingTo BigDecimal("10000")
                result.remainingBalance  shouldBeEqualComparingTo BigDecimal("90000")
            }
            Then("출금 계좌 잔액이 차감된다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("90000")
            }
            Then("입금 계좌 잔액이 증가한다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                toAccount.balance shouldBeEqualComparingTo BigDecimal("60000")
            }
            Then("거래내역 2건이 저장된다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                historiesSlot.captured.size shouldBe 2
            }
            Then("출금 측 거래내역 타입이 TRANSFER이고 idempotencyKey가 저장된다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val outgoing = historiesSlot.captured.first { it.accountId == 1L }
                outgoing.transactionType shouldBe TransactionType.TRANSFER
                outgoing.idempotencyKey  shouldBe "idem-key-001"
                outgoing.counterpartAccountNumber shouldBe "1002-000-000002"
                outgoing.counterpartName          shouldBe "박토스"
            }
            Then("입금 측 거래내역 타입이 TRANSFER_IN이고 idempotencyKey가 null이다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                val incoming = historiesSlot.captured.first { it.accountId == 2L }
                incoming.transactionType shouldBe TransactionType.TRANSFER_IN
                incoming.idempotencyKey.shouldBeNull()
                incoming.counterpartAccountNumber shouldBe "1002-000-000001"
                incoming.counterpartName          shouldBe "김토스"
            }
        }
    }

    Given("당행 이체 - 정상 이체 (fromId > toId, 데드락 방지 정렬)") {
        val fromAccount3  = makeAccount(id = 3L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000003")
        val toAccount2    = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("50000"),  accountNumber = "1002-000-000002")
        val historiesSlot = slot<List<TransactionHistory>>()

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(2L) } returns toAccount2
        every { accountRepository.findByIdWithLock(3L) } returns fromAccount3
        every { transactionHistoryRepository.saveAll(capture(historiesSlot)) } returns emptyList()

        val request = makeTransferRequest(fromAccountId = 3L, amount = BigDecimal("10000"))

        When("executeInternalTransfer를 호출하면") {
            Then("작은 ID를 먼저 잠그는 순서로 조회된다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                verify(ordering = Ordering.ORDERED) {
                    accountRepository.findByIdWithLock(2L)
                    accountRepository.findByIdWithLock(3L)
                }
            }
            Then("from/to 방향이 올바르게 복원되어 출금 계좌 잔액이 차감된다") {
                executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                fromAccount3.balance shouldBeEqualComparingTo BigDecimal("90000")
                toAccount2.balance   shouldBeEqualComparingTo BigDecimal("60000")
            }
        }
    }

    Given("당행 이체 - description 처리") {
        val fromAccount   = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000001")
        val toAccount     = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("50000"),  accountNumber = "1002-000-000002")
        val historiesSlot = slot<List<TransactionHistory>>()

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { accountRepository.findByIdWithLock(2L) } returns toAccount
        every { transactionHistoryRepository.saveAll(capture(historiesSlot)) } returns emptyList()

        When("커스텀 description을 지정하면") {
            Then("지정한 description이 양측 거래내역에 저장된다") {
                executor.executeInternalTransfer(1L, makeTransferRequest(description = "생일 축하금"), toAccountId = 2L, fromMemberName = "김토스")
                historiesSlot.captured.forEach { it.description shouldBe "생일 축하금" }
            }
        }
        When("description을 지정하지 않으면") {
            Then("출금 측에 '수취인이름에게 이체' 기본값이 저장된다") {
                executor.executeInternalTransfer(1L, makeTransferRequest(description = null), toAccountId = 2L, fromMemberName = "김토스")
                historiesSlot.captured.first { it.accountId == 1L }.description shouldBe "박토스에게 이체"
            }
            Then("입금 측에 '송금인이름으로부터 입금' 기본값이 저장된다") {
                executor.executeInternalTransfer(1L, makeTransferRequest(description = null), toAccountId = 2L, fromMemberName = "김토스")
                historiesSlot.captured.first { it.accountId == 2L }.description shouldBe "김토스으로부터 입금"
            }
        }
    }

    Given("당행 이체 - 계좌 조회 실패") {
        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null

        When("첫 번째 계좌(작은 ID) 조회 실패 시") {
            every { accountRepository.findByIdWithLock(1L) } returns null
            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    executor.executeInternalTransfer(1L, makeTransferRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
            }
            Then("두 번째 계좌 조회를 시도하지 않는다") {
                runCatching { executor.executeInternalTransfer(1L, makeTransferRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스") }
                verify(exactly = 0) { accountRepository.findByIdWithLock(2L) }
            }
        }

        When("두 번째 계좌(큰 ID) 조회 실패 시") {
            every { accountRepository.findByIdWithLock(1L) } returns makeAccount(id = 1L)
            every { accountRepository.findByIdWithLock(2L) } returns null
            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    executor.executeInternalTransfer(1L, makeTransferRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
            }
            Then("거래내역이 저장되지 않는다") {
                runCatching { executor.executeInternalTransfer(1L, makeTransferRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스") }
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("당행 이체 - 소유자 검증 실패") {
        val fromAccount = makeAccount(id = 1L, memberId = 99L)
        val toAccount   = makeAccount(id = 2L, memberId = 2L)

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { accountRepository.findByIdWithLock(2L) } returns toAccount

        When("타인의 계좌에서 이체하려고 하면") {
            Then("UnauthorizedAccountAccessException이 발생한다") {
                shouldThrow<UnauthorizedAccountAccessException> {
                    executor.executeInternalTransfer(1L, makeTransferRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스")
                }
            }
            Then("거래내역이 저장되지 않는다") {
                runCatching { executor.executeInternalTransfer(1L, makeTransferRequest(fromAccountId = 1L), toAccountId = 2L, fromMemberName = "김토스") }
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("당행 이체 - 잔액 부족") {
        val fromAccount = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("5000"))
        val toAccount   = makeAccount(id = 2L, memberId = 2L, balance = BigDecimal("30000"))

        every { transactionHistoryRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { accountRepository.findByIdWithLock(2L) } returns toAccount

        val request = makeTransferRequest(fromAccountId = 1L, amount = BigDecimal("10000"))

        When("잔액(5000)보다 큰 금액(10000)을 이체하면") {
            Then("InsufficientBalanceException이 발생한다") {
                shouldThrow<InsufficientBalanceException> {
                    executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스")
                }
            }
            Then("출금/입금 계좌 잔액이 변경되지 않는다") {
                runCatching { executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스") }
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("5000")
                toAccount.balance   shouldBeEqualComparingTo BigDecimal("30000")
            }
            Then("거래내역이 저장되지 않는다") {
                runCatching { executor.executeInternalTransfer(1L, request, toAccountId = 2L, fromMemberName = "김토스") }
                verify(exactly = 0) { transactionHistoryRepository.saveAll(any<List<TransactionHistory>>()) }
            }
        }
    }

    Given("타행 이체 - WITHDRAW_COMPLETED 상태 건에 동일 키로 재요청") {
        val existing = makeInterbankTransfer(status = InterbankTransferStatus.WITHDRAW_COMPLETED)
        val history  = makeWithdrawHistory()
        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns existing
        every { transactionHistoryRepository.findByIdempotencyKey("idem-key-001") } returns history

        When("executeInterbankWithdraw를 호출하면") {
            Then("기존 InterbankTransferContext가 반환된다") {
                val result = executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                result.interbankTransferId                     shouldBe 1L
                result.transferResponse.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
            }
            Then("계좌 조회 및 출금 처리를 하지 않는다") {
                executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                verify(exactly = 0) { accountRepository.findByIdWithLock(any()) }
                verify(exactly = 0) { transactionHistoryRepository.save(any()) }
            }
        }
    }

    Given("타행 이체 - COMPLETED 상태 건에 동일 키로 재요청") {
        val existing = makeInterbankTransfer(status = InterbankTransferStatus.COMPLETED)
        val history  = makeWithdrawHistory()
        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns existing
        every { transactionHistoryRepository.findByIdempotencyKey("idem-key-001") } returns history

        When("executeInterbankWithdraw를 호출하면") {
            Then("재처리 없이 기존 결과가 반환된다") {
                val result = executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                result.transferResponse.amount shouldBeEqualComparingTo BigDecimal("10000")
                verify(exactly = 0) { accountRepository.findByIdWithLock(any()) }
            }
        }
    }

    Given("타행 이체 - UNKNOWN 상태 건에 동일 키로 재요청") {
        val existing = makeInterbankTransfer(status = InterbankTransferStatus.UNKNOWN)
        val history  = makeWithdrawHistory()
        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns existing
        every { transactionHistoryRepository.findByIdempotencyKey("idem-key-001") } returns history

        When("executeInterbankWithdraw를 호출하면") {
            Then("스케줄러 처리 중이므로 재처리 없이 기존 결과가 반환된다") {
                val result = executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                result.interbankTransferId shouldBe 1L
                verify(exactly = 0) { accountRepository.findByIdWithLock(any()) }
            }
        }
    }

    Given("타행 이체 - COMPENSATION_PENDING 상태 건에 동일 키로 재요청") {
        val existing = makeInterbankTransfer(status = InterbankTransferStatus.COMPENSATION_PENDING)
        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns existing

        When("executeInterbankWithdraw를 호출하면") {
            Then("보상 진행 중이므로 ExternalTransferFailedException이 발생한다") {
                shouldThrow<ExternalTransferFailedException> {
                    executor.executeInterbankWithdraw(
                        memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                        toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                        description = null, idempotencyKey = "idem-key-001",
                    )
                }
            }
        }
    }

    Given("타행 이체 - MANUAL_REQUIRED 상태 건에 동일 키로 재요청") {
        val existing = makeInterbankTransfer(status = InterbankTransferStatus.MANUAL_REQUIRED)
        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns existing

        When("executeInterbankWithdraw를 호출하면") {
            Then("수동 처리 대기 중이므로 ExternalTransferFailedException이 발생한다") {
                shouldThrow<ExternalTransferFailedException> {
                    executor.executeInterbankWithdraw(
                        memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                        toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                        description = null, idempotencyKey = "idem-key-001",
                    )
                }
            }
        }
    }

    Given("타행 이체 - COMPENSATED 이후 동일 키 재시도 (idempotencyKey=null → 신규 처리)") {
        // markCompensated() 후 idempotencyKey=null이므로 findByIdempotencyKey 조회 안 됨
        val fromAccount  = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000001")
        val historySlot  = slot<TransactionHistory>()
        val transferSlot = slot<InterbankTransfer>()

        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { transactionHistoryRepository.save(capture(historySlot)) } answers { firstArg() }
        every { interbankTransferRepository.save(capture(transferSlot)) } answers { firstArg() }

        When("executeInterbankWithdraw를 호출하면") {
            Then("신규 이체로 처리되어 출금이 실행된다") {
                val result = executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                result.transferResponse.amount shouldBeEqualComparingTo BigDecimal("10000")
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("90000")
            }
        }
    }

    Given("타행 이체 - 정상 출금") {
        val fromAccount  = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("100000"), accountNumber = "1002-000-000001")
        val historySlot  = slot<TransactionHistory>()
        val transferSlot = slot<InterbankTransfer>()

        every { interbankTransferRepository.findByIdempotencyKey("idem-key-001") } returns null
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { transactionHistoryRepository.save(capture(historySlot)) } answers { firstArg() }
        every { interbankTransferRepository.save(capture(transferSlot)) } answers { firstArg() }

        When("executeInterbankWithdraw를 호출하면") {
            Then("InterbankTransferContext가 반환된다") {
                val result = executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                result.fromAccountId             shouldBe 1L
                result.fromAccountNumber         shouldBe "1002-000-000001"
                result.transferResponse.amount   shouldBeEqualComparingTo BigDecimal("10000")
                result.transferResponse.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
            }
            Then("출금 계좌 잔액이 차감된다") {
                executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("90000")
            }
            Then("TransactionHistory가 INTERBANK_WITHDRAW 타입으로 idempotencyKey와 함께 저장된다") {
                executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                historySlot.captured.transactionType shouldBe TransactionType.INTERBANK_WITHDRAW
                historySlot.captured.idempotencyKey  shouldBe "idem-key-001"
            }
            Then("InterbankTransfer가 WITHDRAW_COMPLETED 상태로 저장된다") {
                executor.executeInterbankWithdraw(
                    memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                    toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                    description = null, idempotencyKey = "idem-key-001",
                )
                transferSlot.captured.status         shouldBe InterbankTransferStatus.WITHDRAW_COMPLETED
                transferSlot.captured.idempotencyKey shouldBe "idem-key-001"
            }
        }
    }

    Given("타행 이체 출금 - 계좌 없음") {
        every { interbankTransferRepository.findByIdempotencyKey(any()) } returns null
        every { accountRepository.findByIdWithLock(1L) } returns null

        When("executeInterbankWithdraw를 호출하면") {
            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    executor.executeInterbankWithdraw(
                        memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                        toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                        description = null, idempotencyKey = "idem-key-001",
                    )
                }
            }
            Then("TransactionHistory 저장을 시도하지 않는다") {
                runCatching {
                    executor.executeInterbankWithdraw(
                        memberId = 1L, fromAccountId = 1L, toAccountNumber = "3333-000-000001",
                        toBankCode = "011", toMemberName = "박토스", amount = BigDecimal("10000"),
                        description = null, idempotencyKey = "idem-key-001",
                    )
                }
                verify(exactly = 0) { transactionHistoryRepository.save(any()) }
            }
        }
    }

    Given("타행 이체 완료 처리") {
        val transfer = makeInterbankTransfer(status = InterbankTransferStatus.WITHDRAW_COMPLETED)
        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)

        When("markInterbankCompleted를 호출하면") {
            Then("상태가 COMPLETED로 변경되고 externalTransactionId가 저장된다") {
                executor.markInterbankCompleted(1L, "ext-tx-001")
                transfer.status                shouldBe InterbankTransferStatus.COMPLETED
                transfer.externalTransactionId shouldBe "ext-tx-001"
            }
        }
    }

    Given("보상 트랜잭션 - 정상 처리") {
        val transfer    = makeInterbankTransfer(id = 1L, status = InterbankTransferStatus.WITHDRAW_COMPLETED, idempotencyKey = "idem-key-001")
        val fromAccount = makeAccount(id = 1L, balance = BigDecimal("90000"))
        val withdrawHistory = makeWithdrawHistory()
        val historySlot = slot<TransactionHistory>()

        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { transactionHistoryRepository.findByIdempotencyKey("idem-key-001") } returns withdrawHistory
        every { transactionHistoryRepository.save(capture(historySlot)) } answers { firstArg() }

        When("compensateInterbank를 호출하면") {
            Then("출금 계좌에 금액이 환불된다") {
                executor.compensateInterbank(1L)
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("100000")
            }
            Then("INTERBANK_WITHDRAW_CANCEL 타입의 TransactionHistory가 저장된다") {
                executor.compensateInterbank(1L)
                historySlot.captured.transactionType shouldBe TransactionType.INTERBANK_WITHDRAW_CANCEL
                historySlot.captured.idempotencyKey  shouldBe "idem-key-001-cancel"
            }
            Then("InterbankTransfer 상태가 COMPENSATED로 변경된다") {
                executor.compensateInterbank(1L)
                transfer.status shouldBe InterbankTransferStatus.COMPENSATED
            }
            Then("markCompensated 후 idempotencyKey가 null로 초기화된다 (UNIQUE 제약 해제 → 동일 키 재이체 허용)") {
                executor.compensateInterbank(1L)
                transfer.idempotencyKey.shouldBeNull()
            }
            Then("원본 출금 내역의 idempotencyKey가 해제된다") {
                executor.compensateInterbank(1L)
                withdrawHistory.idempotencyKey.shouldBeNull()
            }
        }
    }

    Given("보상 트랜잭션 - 계좌 없음으로 실패 (retryCount=0, MAX_RETRY 미초과)") {
        val transfer = makeInterbankTransfer(id = 1L, status = InterbankTransferStatus.WITHDRAW_COMPLETED, retryCount = 0)

        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)
        every { accountRepository.findByIdWithLock(1L) } returns null

        When("compensateInterbank를 호출하면") {
            Then("CompensationFailedException이 발생한다") {
                shouldThrow<CompensationFailedException> {
                    executor.compensateInterbank(1L)
                }
            }
            Then("상태가 COMPENSATION_PENDING으로 변경되고 nextRetryAt이 설정된다") {
                runCatching { executor.compensateInterbank(1L) }
                transfer.status shouldBe InterbankTransferStatus.COMPENSATION_PENDING
                transfer.nextRetryAt.shouldNotBeNull()
            }
            Then("retryCount가 1 증가한다") {
                runCatching { executor.compensateInterbank(1L) }
                transfer.retryCount shouldBe 1
            }
        }
    }

    Given("보상 트랜잭션 - MAX_COMPENSATION_RETRY 초과 상태에서 실패") {
        val transfer = makeInterbankTransfer(
            id         = 1L,
            status     = InterbankTransferStatus.COMPENSATION_PENDING,
            retryCount = InterbankTransfer.MAX_COMPENSATION_RETRY,
        )

        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)
        every { accountRepository.findByIdWithLock(1L) } returns null

        When("compensateInterbank를 호출하면") {
            Then("CompensationFailedException이 발생한다") {
                shouldThrow<CompensationFailedException> {
                    executor.compensateInterbank(1L)
                }
            }
            Then("상태가 MANUAL_REQUIRED로 변경된다") {
                runCatching { executor.compensateInterbank(1L) }
                transfer.status shouldBe InterbankTransferStatus.MANUAL_REQUIRED
            }
        }
    }

    Given("보상 트랜잭션 - COMPENSATION_PENDING 상태에서 재시도 성공") {
        // 이전에 보상 실패했던 건 → 스케줄러가 재시도
        val transfer    = makeInterbankTransfer(
            id         = 1L,
            status     = InterbankTransferStatus.COMPENSATION_PENDING,
            retryCount = 2,
        )
        val fromAccount = makeAccount(id = 1L, balance = BigDecimal("90000"))
        val withdrawHistory = makeWithdrawHistory()
        val historySlot = slot<TransactionHistory>()

        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)
        every { accountRepository.findByIdWithLock(1L) } returns fromAccount
        every { transactionHistoryRepository.findByIdempotencyKey("idem-key-001") } returns withdrawHistory
        every { transactionHistoryRepository.save(capture(historySlot)) } answers { firstArg() }

        When("compensateInterbank를 호출하면") {
            Then("출금 계좌에 금액이 환불된다") {
                executor.compensateInterbank(1L)
                fromAccount.balance shouldBeEqualComparingTo BigDecimal("100000")
            }
            Then("상태가 COMPENSATED로 변경된다") {
                executor.compensateInterbank(1L)
                transfer.status shouldBe InterbankTransferStatus.COMPENSATED
            }
            Then("보상 취소 내역의 idempotencyKey에 -cancel suffix가 붙는다") {
                executor.compensateInterbank(1L)
                historySlot.captured.idempotencyKey shouldBe "idem-key-001-cancel"
            }
            Then("markCompensated 후 idempotencyKey가 null로 초기화된다") {
                executor.compensateInterbank(1L)
                transfer.idempotencyKey.shouldBeNull()
            }
            Then("원본 출금 내역의 idempotencyKey가 해제된다") {
                executor.compensateInterbank(1L)
                withdrawHistory.idempotencyKey.shouldBeNull()
            }
        }
    }

    Given("타행 이체 UNKNOWN 처리") {
        val transfer = makeInterbankTransfer(status = InterbankTransferStatus.WITHDRAW_COMPLETED)
        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)

        When("markInterbankUnknown을 호출하면") {
            Then("상태가 UNKNOWN으로 변경되고 retryCount가 증가하며 nextRetryAt이 설정된다") {
                executor.markInterbankUnknown(1L, "5xx error")
                transfer.status      shouldBe InterbankTransferStatus.UNKNOWN
                transfer.retryCount  shouldBe 1
                transfer.lastErrorMessage shouldBe "5xx error"
                transfer.nextRetryAt.shouldNotBeNull()
            }
        }
    }

    Given("MANUAL_REQUIRED 처리") {
        val transfer = makeInterbankTransfer(status = InterbankTransferStatus.UNKNOWN)
        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)

        When("markInterbankManualRequired를 호출하면") {
            Then("상태가 MANUAL_REQUIRED로 변경된다") {
                executor.markInterbankManualRequired(1L, "재시도 한계 초과")
                transfer.status shouldBe InterbankTransferStatus.MANUAL_REQUIRED
            }
        }
    }

    Given("UNKNOWN 재조회 실패 - 다음 재시도 예약") {
        val transfer = makeInterbankTransfer(status = InterbankTransferStatus.UNKNOWN, retryCount = 1)
        every { interbankTransferRepository.findById(1L) } returns Optional.of(transfer)

        When("scheduleNextRetryForUnknown을 호출하면") {
            Then("retryCount가 증가하고 nextRetryAt이 갱신되며 errorMessage가 저장된다") {
                executor.scheduleNextRetryForUnknown(1L, "inquiry failed")
                transfer.retryCount       shouldBe 2
                transfer.lastErrorMessage shouldBe "inquiry failed"
                transfer.nextRetryAt.shouldNotBeNull()
            }
        }
    }

    Given("InterbankTransfer가 존재하지 않을 때") {
        every { interbankTransferRepository.findById(999L) } returns Optional.empty()

        When("markInterbankCompleted를 호출하면") {
            Then("InterbankTransferNotFoundException이 발생한다") {
                shouldThrow<InterbankTransferNotFoundException> { executor.markInterbankCompleted(999L, "ext-tx") }
            }
        }
        When("compensateInterbank를 호출하면") {
            Then("InterbankTransferNotFoundException이 발생한다") {
                shouldThrow<InterbankTransferNotFoundException> { executor.compensateInterbank(999L) }
            }
        }
        When("markInterbankUnknown을 호출하면") {
            Then("InterbankTransferNotFoundException이 발생한다") {
                shouldThrow<InterbankTransferNotFoundException> { executor.markInterbankUnknown(999L, "error") }
            }
        }
        When("scheduleNextRetryForUnknown을 호출하면") {
            Then("InterbankTransferNotFoundException이 발생한다") {
                shouldThrow<InterbankTransferNotFoundException> { executor.scheduleNextRetryForUnknown(999L) }
            }
        }
        When("markInterbankManualRequired를 호출하면") {
            Then("InterbankTransferNotFoundException이 발생한다") {
                shouldThrow<InterbankTransferNotFoundException> { executor.markInterbankManualRequired(999L, "error") }
            }
        }
    }
})