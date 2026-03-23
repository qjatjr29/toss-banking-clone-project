package com.tossbank.account.application.dto

import com.tossbank.account.presentation.dto.TransferResponse

data class InterbankTransferContext(
    val transferResponse    : TransferResponse,
    val interbankTransferId : Long,
    val fromAccountId       : Long,
    val fromAccountNumber   : String,
)