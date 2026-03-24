package com.tossbank.account.integration

import com.tossbank.account.infrastructure.client.ExternalBankClient
import com.tossbank.account.infrastructure.client.MemberClient
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class TestMockBeans {
    @Bean @Primary
    fun memberClient(): MemberClient = mockk(relaxed = false)

    @Bean @Primary
    fun externalBankClient(): ExternalBankClient = mockk(relaxed = false)
}