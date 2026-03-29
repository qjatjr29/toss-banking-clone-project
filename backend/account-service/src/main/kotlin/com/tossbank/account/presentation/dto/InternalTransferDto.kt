package com.tossbank.account.presentation.dto

import java.math.BigDecimal

data class InternalWithdrawRequest(
    val fromAccountId: Long,
    val fromMemberId: Long,
    val amount: BigDecimal,
    val idempotencyKey: String,
)

data class InternalWithdrawResponse(
    val fromAccountId: Long,
    val remainingBalance: BigDecimal,
)

data class InternalDepositRequest(
    val toAccountId: Long,
    val amount: BigDecimal,
    val fromMemberId: Long,
    val fromMemberName: String,
    val idempotencyKey: String,
    val description: String?,
)

data class InternalDepositResponse(
    val toAccountId: Long,
    val balanceAfterTx: BigDecimal,
)

data class TransactionInquiryResponse(
    val status: InquiryStatus,
    val balance: BigDecimal? = null,
)

enum class InquiryStatus { SUCCESS, NOT_FOUND }