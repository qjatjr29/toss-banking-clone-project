package com.tossbank.account.integration

import com.tossbank.account.infrastructure.client.ExternalBankClient
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

@TestConfiguration
class TestMockBeans {
    @Bean @Primary
    fun externalBankClient(): ExternalBankClient = mockk(relaxed = false)

    @Bean @Primary
    fun kafkaTemplate(): KafkaTemplate<String, String> = mockk(relaxed = true)

    @Bean
    fun objectMapper(): JsonMapper =
        JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()
}