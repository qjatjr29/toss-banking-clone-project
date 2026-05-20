package com.tossbank.transfer.infrastructure.client.dto

import java.math.BigDecimal

data class ExternalTransferRequest(
    val fromBankCode: String,
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val amount: BigDecimal,
)
