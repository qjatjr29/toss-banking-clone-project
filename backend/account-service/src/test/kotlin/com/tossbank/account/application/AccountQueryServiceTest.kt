package com.tossbank.account.application

import AccountNotFoundException
import UnauthorizedAccountAccessException
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
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
import java.util.*

class AccountQueryServiceTest : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf
    val accountRepository = mockk<AccountRepository>()
    val service = AccountQueryService(
        accountRepository = accountRepository,
        dbDispatcher      = Dispatchers.Unconfined,
    )

    afterTest { clearAllMocks() }

    fun makeAccount(
        id: Long            = 1L,
        memberId: Long      = 1L,
        accountNumber: String = "1002-000-000001",
        balance: BigDecimal = BigDecimal("100000"),
        holderName: String  = "김토스",
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(
        memberId      = memberId,
        accountNumber = accountNumber,
        holderName    = holderName,
        balance       = balance,
        status        = status,
    ).also { account ->
        Account::class.java.getDeclaredField("id").apply {
            isAccessible = true
            set(account, id)
        }
    }

    Given("회원의 활성 계좌 목록을 조회할 때") {

        When("활성 계좌가 2개 있으면") {
            val accounts = listOf(
                makeAccount(id = 1L, accountNumber = "1002-000-000001", balance = BigDecimal("1000")),
                makeAccount(id = 2L, accountNumber = "1002-000-000002", balance = BigDecimal("2000")),
            )
            every {
                accountRepository.findAllByMemberIdAndStatus(1L, AccountStatus.ACTIVE)
            } returns accounts

            Then("2개의 계좌 목록이 반환되고 정보가 정확히 매핑된다") {
                val result = service.getActiveAccounts(1L)

                result.size shouldBe 2
                result[0].accountNumber shouldBe "1002-000-000001"
                result[0].balance shouldBe BigDecimal("1000")
                result[1].accountNumber shouldBe "1002-000-000002"
                result[1].balance shouldBe BigDecimal("2000")
            }

            Then("Repository가 정확히 1번 호출된다") {
                service.getActiveAccounts(1L)
                verify(exactly = 1) {
                    accountRepository.findAllByMemberIdAndStatus(1L, AccountStatus.ACTIVE)
                }
            }
        }

        When("활성 계좌가 없으면") {
            every {
                accountRepository.findAllByMemberIdAndStatus(1L, AccountStatus.ACTIVE)
            } returns emptyList()

            Then("빈 리스트가 반환된다") {
                val result = service.getActiveAccounts(1L)
                result.isEmpty() shouldBe true
            }
        }
    }

    Given("특정 계좌를 조회할 때") {

        When("본인 계좌를 조회하면") {
            val account = makeAccount(id = 1L, memberId = 1L, balance = BigDecimal("50000"))
            every { accountRepository.findById(1L) } returns Optional.of(account)

            Then("계좌 정보가 정확히 반환된다") {
                val result = service.getAccount(memberId = 1L, accountId = 1L)
                result.id shouldBe 1L
                result.balance shouldBe BigDecimal("50000")
                result.accountNumber shouldBe "1002-000-000001"
            }
        }

        When("타인의 계좌를 조회하면") {
            val account = makeAccount(id = 1L, memberId = 99L)
            every { accountRepository.findById(1L) } returns Optional.of(account)

            Then("UnauthorizedAccountAccessException이 발생한다") {
                shouldThrow<UnauthorizedAccountAccessException> {
                    service.getAccount(memberId = 1L, accountId = 1L)
                }
            }
        }

        When("존재하지 않는 계좌를 조회하면") {
            every { accountRepository.findById(999L) } returns Optional.empty()

            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    service.getAccount(memberId = 1L, accountId = 999L)
                }
            }
        }
    }

    Given("계좌 실명조회를 할 때 (이체 전 수취인 확인)") {

        When("당행(092)의 존재하는 활성 계좌를 조회하면") {
            every {
                accountRepository.findByAccountNumberAndStatus("1002-000-000001", AccountStatus.ACTIVE)
            } returns makeAccount(holderName = "김토스")

            Then("마스킹 없이 이름이 그대로 반환된다") {
                val result = service.inquireAccountHolder("1002-000-000001", "092")
                result.holderName shouldBe "김토스"
            }

            Then("계좌번호와 은행코드가 일치해야 한다") {
                val result = service.inquireAccountHolder("1002-000-000001", "092")
                result.accountNumber shouldBe "1002-000-000001"
                result.bankCode shouldBe "092"
            }
        }

        When("당행(092)의 존재하지 않는 계좌번호로 조회하면") {
            every {
                accountRepository.findByAccountNumberAndStatus(any(), AccountStatus.ACTIVE)
            } returns null

            Then("AccountNotFoundException이 발생한다") {
                shouldThrow<AccountNotFoundException> {
                    service.inquireAccountHolder("9999-000-000000", "092")
                }
            }
        }

        When("타행 은행코드(예: 004 국민은행)로 조회하면") {
            // 외부 API 호출 구간 (현재는 임시로 Mock 응답 반환 중)
            // TODO: 수정 예정.
            Then("현재 임시 구현된 외부 API Mock 응답(홍길동)이 반환된다") {
                val result = service.inquireAccountHolder("110-000-000001", "004")

                result.holderName shouldBe "홍길동"
                result.accountNumber shouldBe "110-000-000001"
                result.bankCode shouldBe "004"
            }

            Then("내부 DB 조회를 시도하지 않는다") {
                service.inquireAccountHolder("110-000-000001", "004")
                verify(exactly = 0) {
                    accountRepository.findByAccountNumberAndStatus(any(), any())
                }
            }
        }
    }
})
