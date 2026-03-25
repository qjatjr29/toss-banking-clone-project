package com.tossbank.transfer.infrastructure.client.dto

data class ExternalTransferStatusResponse(
    val externalTransactionId: String,
    val status: String
)