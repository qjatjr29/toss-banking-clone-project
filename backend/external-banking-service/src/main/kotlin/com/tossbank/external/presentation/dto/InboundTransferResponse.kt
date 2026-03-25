package com.tossbank.external.presentation.dto

data class InboundTransferResponse(
    val externalTransactionId: String,
    val status: InboundTransferStatus
)

data class InboundTransferStatusResponse(
    val externalTransactionId: String,
    val status: InboundTransferStatus
)