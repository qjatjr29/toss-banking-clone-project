package com.tossbank.account.integration

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer

@SpringBootTest(
    classes = [com.tossbank.account.AccountServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
abstract class IntegrationTestBase : BehaviorSpec() {
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