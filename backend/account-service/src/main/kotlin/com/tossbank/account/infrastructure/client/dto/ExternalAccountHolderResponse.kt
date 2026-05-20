package com.tossbank.account.infrastructure.client.dto

data class ExternalAccountHolderResponse(
    val accountNumber: String,
    val holderName: String,
)