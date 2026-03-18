package com.tossbank.account.integration

import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.domain.model.TransactionType
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import java.math.BigDecimal

@SpringBootTest(classes = [com.tossbank.account.AccountServiceApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TransactionHistoryTest(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository
) : StringSpec({

    "거래내역 조회 시 복합 인덱스(account_id, created_at DESC)를 타며 Slice 페이징이 정상 동작한다" {
        // Given
        val account = accountRepository.save(Account(memberId = 1L, accountNumber = "123-456", alias = "테스트"))

        val histories = (1..50).map {
            TransactionHistory(
                accountId = account.id,
                transactionType = TransactionType.DEPOSIT,
                amount = BigDecimal("1000.00"),
                balanceAfterTx = BigDecimal(it * 1000).setScale(4),
                description = "테스트 입금 $it"
            )
        }
        transactionHistoryRepository.saveAll(histories)

        // When: 첫 번째 페이지(10개) 조회
        val pageable = PageRequest.of(0, 10)
        val slice = transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(account.id, pageable)

        // Then
        slice.content.size shouldBe 10
        slice.hasNext() shouldBe true
    }
}) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        val mysqlContainer = MySQLContainer<Nothing>("mysql:8.0").apply {
            withDatabaseName("toss_account_test")
            withUsername("testuser")
            withPassword("testpass")
            start()
        }

        val redisContainer = GenericContainer<Nothing>("redis:7-alpine").apply {
            withExposedPorts(6379)
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "update" }

            // Hibernate Show SQL 옵션을 켜서 로그에서 쿼리와 인덱스(제약조건) 생성 확인
            registry.add("spring.jpa.show-sql") { "true" }
            registry.add("spring.jpa.properties.hibernate.format_sql") { "true" }

            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getFirstMappedPort().toString() }
        }
    }
}