package com.tossbank.account.presentation.dto

import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.domain.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime

data class TransactionHistoryResponse(
    val transactionId: Long,
    val type: TransactionType,
    val amount: BigDecimal,
    val balanceAfterTx: BigDecimal,
    val counterpartAccountNumber: String?,
    val counterpartName: String?,
    val description: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(history: TransactionHistory) = TransactionHistoryResponse(
            transactionId            = history.id,
            type                     = history.transactionType,
            amount                   = history.amount,
            balanceAfterTx           = history.balanceAfterTx,
            counterpartAccountNumber = history.counterpartAccountNumber,
            counterpartName          = history.counterpartName,
            description              = history.description,
            createdAt                = history.createdAt,
        )
    }
}
