package com.tossbank.external.presentation.dto

import java.math.BigDecimal

data class InboundTransferRequest(
    val fromBankCode: String,
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val amount: BigDecimal,
)

data class InboundTransferResponse(
    val externalTransactionId: String,
    val status: String = "SUCCESS",
)

data class InboundTransferResultResponse(
    val idempotencyKey: String,
    val status: String,
    val externalTransactionId: String? = null,
)

data class AccountHolderResponse(
    val accountNumber: String,
    val holderName: String,
)