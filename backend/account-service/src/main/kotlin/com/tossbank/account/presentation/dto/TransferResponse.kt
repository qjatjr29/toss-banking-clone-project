package com.tossbank.account.presentation.dto

import java.math.BigDecimal

data class TransferResponse(
    val fromAccountId: Long,
    val toMemberName: String,
    val amount: BigDecimal,
    val remainingBalance: BigDecimal,
)