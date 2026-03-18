package com.tossbank.account.presentation.dto

import com.tossbank.account.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime

data class TransactionHistoryResponse(
    val transactionId: Long,
    val type: TransactionType,
    val amount: BigDecimal,
    val balanceAfterTx: BigDecimal,
    val description: String,
    val createdAt: LocalDateTime
)
