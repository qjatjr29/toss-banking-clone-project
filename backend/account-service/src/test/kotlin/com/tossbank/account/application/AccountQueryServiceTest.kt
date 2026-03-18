import com.tossbank.account.application.AccountQueryService
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal

class AccountQueryServiceTest : BehaviorSpec({

    // Repository를 Mocking (가짜 객체) 생성
    val accountRepository = mockk<AccountRepository>()
    // Service에 Mock Repository 주입
    val accountQueryService = AccountQueryService(accountRepository)

    // 각 테스트가 끝날 때마다 Mock 객체 초기화 (상태 전이 방지)
    afterTest {
        clearAllMocks()
    }

    Given("회원(memberId = 1)의 활성 계좌 조회를 요청할 때") {
        val memberId = 1L

        When("회원이 보유한 활성 상태의 계좌가 2개 존재한다면") {
            val account1 = Account(memberId = memberId, accountNumber = "111-1111").apply { balance = BigDecimal("1000") }
            val account2 = Account(memberId = memberId, accountNumber = "222-2222").apply { balance = BigDecimal("2000") }

            // 호출되면 위 2개의 계좌 리스트를 반환하도록 설정
            every { accountRepository.findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE) } returns listOf(account1, account2)
            val result = accountQueryService.getActiveAccounts(memberId)

            Then("DTO(AccountResponse)로 변환된 2개의 계좌 리스트가 반환되어야 한다.") {
                result.size shouldBe 2

                result[0].accountNumber shouldBe "111-1111"
                result[0].balance shouldBe BigDecimal("1000")
                result[0].bankCode shouldBe "092" // DTO 변환 시 들어가는 고정값 확인

                result[1].accountNumber shouldBe "222-2222"

                // Repository의 해당 메서드가 정확히 1번 호출되었는지 검증
                verify(exactly = 1) { accountRepository.findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE) }
            }
        }

        When("회원이 보유한 계좌가 하나도 없다면") {
            // 빈 리스트 반환 설정
            every { accountRepository.findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE) } returns emptyList()
            val result = accountQueryService.getActiveAccounts(memberId)

            Then("빈 리스트가 반환되어야 한다.") {
                result.isEmpty() shouldBe true
            }
        }
    }
})