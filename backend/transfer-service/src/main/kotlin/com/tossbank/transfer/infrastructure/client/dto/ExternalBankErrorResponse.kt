package com.tossbank.transfer.infrastructure.client.dto

data class ExternalBankErrorResponse(
    val code: String,
    val message: String
)