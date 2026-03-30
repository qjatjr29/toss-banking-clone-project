package com.tossbank.transfer.integration

import com.tossbank.transfer.infrastructure.client.AccountServiceClient
import com.tossbank.transfer.infrastructure.client.MemberClient
import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate

@TestConfiguration
class TransferTestMockBeans {

    @Bean @Primary
    fun accountServiceClient(): AccountServiceClient = mockk(relaxed = false)

    @Bean @Primary
    fun kafkaTemplate(): KafkaTemplate<String, String> = mockk(relaxed = true)

    @Bean @Primary
    fun memberClient(): MemberClient = mockk<MemberClient>().also {
        coEvery { it.getMemberName(any()) } returns "김철수"
    }
}