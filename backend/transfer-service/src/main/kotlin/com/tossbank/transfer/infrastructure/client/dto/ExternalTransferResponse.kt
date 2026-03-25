package com.tossbank.transfer.infrastructure.client.dto

data class ExternalTransferResponse(
    val externalTransactionId: String,
    val status: String
)