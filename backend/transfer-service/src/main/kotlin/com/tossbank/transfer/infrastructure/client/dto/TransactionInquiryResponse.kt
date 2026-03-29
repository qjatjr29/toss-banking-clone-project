package com.tossbank.transfer.infrastructure.client.dto

import java.math.BigDecimal

data class TransactionInquiryResponse(
    val status: InquiryStatus,
    val balance: BigDecimal? = null,
)

enum class InquiryStatus { SUCCESS, NOT_FOUND }