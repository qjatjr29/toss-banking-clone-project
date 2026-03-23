package com.tossbank.account.infrastructure.client.dto

data class ExternalTransferResponse(
    val externalTransactionId : String,
    val status                : ExternalTransferResultStatus,
    val message               : String? = null,
)