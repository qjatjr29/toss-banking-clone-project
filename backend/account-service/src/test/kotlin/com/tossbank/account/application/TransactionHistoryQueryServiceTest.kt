package com.tossbank.account.application

import AccountNotFoundException
import UnauthorizedAccountAccessException
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.domain.model.TransactionType
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import java.math.BigDecimal
import java.util.*

class TransactionHistoryQueryServiceTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerLeaf

    val accountRepository            = mockk<AccountRepository>()
    val transactionHistoryRepository = mockk<TransactionHistoryRepository>()
    val service = TransactionHistoryQueryService(
        accountRepository            = accountRepository,
        transactionHistoryRepository = transactionHistoryRepository,
        dbDispatcher                 = Dispatchers.Unconfined,
    )

    afterTest { clearAllMocks() }

    fun makeAccount(
        memberId: Long        = 1L,
        accountNumber: String = "1002-000-000001",
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(
        memberId      = memberId,
        accountNumber = accountNumber,
        holderName    = "김토스",
        balance       = BigDecimal("100000"),
        status        = status,
    )

    fun makeHistories(
        accountId: Long       = 1L,
        count: Int            = 1,
        type: TransactionType = TransactionType.DEPOSIT,
    ) = (1..count).map {
        TransactionHistory(
            accountId       = accountId,
            transactionType = type,
            amount          = BigDecimal("1000.00"),
            balanceAfterTx  = BigDecimal("10000.00"),
            description     = "${type.name} $it",
        )
    }

    Given("계좌 거래내역 조회를 요청할 때") {

        val memberId  = 1L
        val accountId = 1L
        val page      = 0
        val size      = 20
        val pageable  = PageRequest.of(page, size)

        When("정상 계좌이고 거래내역이 페이지 크기(20)보다 적으면(10개)") {
            every { accountRepository.findById(accountId) } returns Optional.of(makeAccount())
            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns SliceImpl(makeHistories(count = 10), pageable, false)

            Then("10개의 데이터가 반환된다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.content.size shouldBe 10
            }

            Then("hasNext가 false이다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.hasNext() shouldBe false
            }

            Then("Repository가 정확히 1번 호출된다") {
                service.getHistory(memberId, accountId, page, size)
                verify(exactly = 1) {
                    transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
                }
            }
        }

        When("거래내역이 정확히 20개이고 다음 페이지가 없으면") {
            every { accountRepository.findById(accountId) } returns Optional.of(makeAccount())
            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns SliceImpl(makeHistories(count = 20), pageable, false)

            Then("20개의 데이터가 반환된다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.content.size shouldBe 20
            }

            Then("hasNext가 false이다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.hasNext() shouldBe false
            }
        }

        When("거래내역이 충분히 많아서 다음 페이지가 있으면") {
            every { accountRepository.findById(accountId) } returns Optional.of(makeAccount())
            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns SliceImpl(makeHistories(count = 20), pageable, true)

            Then("20개의 데이터가 반환된다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.content.size shouldBe 20
            }

            Then("hasNext가 true이다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.hasNext() shouldBe true
            }
        }

        When("신규 계좌라서 거래내역이 없으면") {
            every { accountRepository.findById(accountId) } returns Optional.of(makeAccount())
            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns SliceImpl(emptyList(), pageable, false)

            Then("빈 리스트가 반환된다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.content.size shouldBe 0
            }

            Then("hasNext가 false이다") {
                val result = service.getHistory(memberId, accountId, page, size)
                result.hasNext() shouldBe false
            }
        }

        When("두 번째 페이지(page=1)를 조회하면") {
            val page2    = 1
            val pageable2 = PageRequest.of(page2, size)

            every { accountRepository.findById(accountId) } returns Optional.of(makeAccount())
            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable2)
            } returns SliceImpl(makeHistories(count = 5), pageable2, false)

            Then("두 번째 페이지의 데이터가 반환된다") {
                val result = service.getHistory(memberId, accountId, page2, size)
                result.content.size shouldBe 5
                result.hasNext() shouldBe false
            }

            Then("page=1, size=20으로 Repository가 호출된다") {
                service.getHistory(memberId, accountId, page2, size)
                verify(exactly = 1) {
                    transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(
                        accountId,
                        pageable2,
                    )
                }
            }
        }

        When("존재하지 않는 계좌 ID로 조회하면") {
            every { accountRepository.findById(accountId) } returns Optional.empty()

            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    service.getHistory(memberId, accountId, page, size)
                }
            }

            Then("거래내역 Repository는 호출되지 않는다") {
                runCatching { service.getHistory(memberId, accountId, page, size) }
                verify(exactly = 0) {
                    transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(any(), any())
                }
            }
        }

        When("타인의 계좌 거래내역을 조회하면") {
            // 계좌 소유자는 99L, 요청자는 memberId = 1L
            val otherAccount = makeAccount(memberId = 99L)
            every { accountRepository.findById(accountId) } returns Optional.of(otherAccount)

            Then("UnauthorizedAccountAccessException이 발생한다") {
                shouldThrow<UnauthorizedAccountAccessException> {
                    service.getHistory(memberId = 1L, accountId = accountId, page = page, size = size)
                }
            }

            Then("거래내역 Repository는 호출되지 않는다") {
                runCatching {
                    service.getHistory(memberId = 1L, accountId = accountId, page = page, size = size)
                }
                verify(exactly = 0) {
                    transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(any(), any())
                }
            }
        }
    }
})