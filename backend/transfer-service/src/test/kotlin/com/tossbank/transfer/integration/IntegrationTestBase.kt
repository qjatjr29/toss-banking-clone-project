package com.tossbank.transfer.integration

import com.tossbank.transfer.TransferServiceApplication
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer

@SpringBootTest(
    classes = [TransferServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(TransferTestMockBeans::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        val mysqlContainer = MySQLContainer<Nothing>("mysql:8.0").apply {
            withDatabaseName("toss_transfer_test")
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
            registry.add("spring.kafka.listener.auto-startup") { "false" }
            registry.add("spring.kafka.bootstrap-servers") { "localhost:9092" }
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getFirstMappedPort().toString() }
        }
    }
}