package com.tossbank.transfer.application.dto

import java.math.BigDecimal

data class InterbankTransferRequest(
    val fromAccountId:   Long,
    val fromAccountNumber: String,
    val toAccountNumber: String,
    val toBankCode:      String,
    val toMemberName:    String,
    val fromMemberName:  String,
    val amount:          BigDecimal,
    val description:     String?,
    val idempotencyKey:  String,
)