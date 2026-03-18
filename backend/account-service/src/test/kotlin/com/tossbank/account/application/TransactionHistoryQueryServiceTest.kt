package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.domain.model.TransactionType
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.SliceImpl
import java.math.BigDecimal
import java.util.*

class TransactionHistoryQueryServiceBddTest : BehaviorSpec({

    val transactionHistoryRepository = mockk<TransactionHistoryRepository>()
    val accountRepository = mockk<AccountRepository>()
    val queryService = TransactionHistoryQueryService(accountRepository, transactionHistoryRepository)

    Given("계좌 Id와 페이징 정보(크기 20)가 주어졌을 때") {
        val accountId = 1L
        val size = 20
        val page = 0
        val pageable = PageRequest.of(page, size)
        val validAccount = Account(memberId = 1L, accountNumber = "123-456", alias = "정상계좌")

        And("거래내역이 페이징 크기보다 적게(10개) 존재하는 경우") {
            every { accountRepository.findById(accountId) } returns Optional.of(validAccount)
            val mockHistories = (1..10).map {
                TransactionHistory(
                    accountId = accountId,
                    transactionType = TransactionType.DEPOSIT,
                    amount = BigDecimal("1000.00"),
                    balanceAfterTx = BigDecimal("10000.00"),
                    description = "입금 $it"
                )
            }
            val mockSlice = SliceImpl(mockHistories, pageable, false)

            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns mockSlice

            When("거래내역 조회를 요청하면") {
                val result = queryService.getHistory(accountId, page, size)

                Then("10개의 데이터가 반환되고 다음 페이지(hasNext)는 false여야 한다") {
                    result.content.size shouldBe 10
                    result.hasNext() shouldBe false
                }
            }
        }

        And("거래내역이 페이징 크기와 동일하게(20개) 존재하지만, 다음 페이지는 없는 경우") {
            every { accountRepository.findById(accountId) } returns Optional.of(validAccount)
            // 딱 20개까지만 있는 경우
            val mockHistories = (1..20).map {
                TransactionHistory(
                    accountId = accountId,
                    transactionType = TransactionType.WITHDRAWAL,
                    amount = BigDecimal("1000.00"),
                    balanceAfterTx = BigDecimal("10000.00"),
                    description = "출금 $it"
                )
            }
            val mockSlice = SliceImpl(mockHistories, pageable, false)

            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns mockSlice

            When("거래내역 조회를 요청하면") {
                val result = queryService.getHistory(accountId, page, size)

                Then("20개의 데이터가 반환되고 다음 페이지(hasNext)는 false여야 한다") {
                    result.content.size shouldBe 20
                    result.hasNext() shouldBe false
                }
            }
        }

        And("거래내역이 많아서(30개 이상) 다음 페이지가 존재하는 경우") {
            every { accountRepository.findById(accountId) } returns Optional.of(validAccount)

            val mockHistories = (1..20).map {
                TransactionHistory(
                    accountId = accountId,
                    transactionType = TransactionType.TRANSFER,
                    amount = BigDecimal("1000.00"),
                    balanceAfterTx = BigDecimal("10000.00"),
                    description = "이체 $it"
                )
            }
            val mockSlice = SliceImpl(mockHistories, pageable, true)

            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns mockSlice

            When("거래내역 조회를 요청하면") {
                val result = queryService.getHistory(accountId, page, size)

                Then("20개의 데이터가 반환되고 다음 페이지(hasNext)는 true여야 한다") {
                    result.content.size shouldBe 20
                    result.hasNext() shouldBe true
                }
            }
        }

        And("신규 계좌라서 거래내역이 아예 없는 경우") {
            every { accountRepository.findById(accountId) } returns Optional.of(validAccount)
            val mockSlice = SliceImpl(emptyList<TransactionHistory>(), pageable, false)

            every {
                transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            } returns mockSlice

            When("거래내역 조회를 요청하면") {
                val result = queryService.getHistory(accountId, page, size)

                Then("빈 리스트가 반환되고 다음 페이지(hasNext)는 false여야 한다") {
                    result.content.size shouldBe 0
                    result.hasNext() shouldBe false
                }
            }
        }

        And("존재하지 않는 계좌 번호를 요청한 경우") {
            val wrongAccountId = 999L
            every { accountRepository.findById(wrongAccountId) } returns Optional.empty()

            When("잘못된 계좌 번호로 조회를 요청하면") {
                Then("AccountNotFoundException 예외가 발생해야 한다") {
                    val exception = shouldThrow<AccountNotFoundException> {
                        queryService.getHistory(wrongAccountId, page, size)
                    }
                    exception.message shouldBe "요청하신 계좌를 찾을 수 없습니다."
                }
            }
        }
    }
})