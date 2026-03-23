package com.tossbank.account.infrastructure.client.dto

import java.math.BigDecimal

data class ExternalTransferRequest(
    val fromBankCode      : String,
    val fromAccountNumber : String,
    val toAccountNumber   : String,
    val toBankCode        : String,
    val toMemberName      : String,
    val amount            : BigDecimal,
    val idempotencyKey    : String,
)