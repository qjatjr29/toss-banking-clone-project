package com.tossbank.account.presentation.dto

data class AccountHolderResponse(
    val accountNumber: String,
    val holderName: String,
    val bankCode: String,
)