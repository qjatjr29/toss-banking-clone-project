package com.tossbank.external.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mock")
data class MockAccountProperties(
    val accounts: List<MockAccount> = emptyList(),
) {
    data class MockAccount(
        val accountNumber: String,
        val holderName: String,
    )

    fun findHolderName(accountNumber: String): String? =
        accounts.find { it.accountNumber == accountNumber }?.holderName
}