package com.tossbank.transfer.presentation.dto

import java.math.BigDecimal

data class InterbankTransferHttpRequest(
    val fromAccountId:     Long,
    val fromAccountNumber: String,
    val toAccountNumber:   String,
    val toBankCode:        String,
    val toMemberName:      String,
    val amount:            BigDecimal,
    val description:       String?,
    val idempotencyKey:    String,
)