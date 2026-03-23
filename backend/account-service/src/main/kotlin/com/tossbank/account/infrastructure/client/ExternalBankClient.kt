package com.tossbank.account.infrastructure.client

import com.tossbank.account.infrastructure.client.dto.ExternalTransferRequest
import com.tossbank.account.infrastructure.client.dto.ExternalTransferResponse

interface ExternalBankClient {
    suspend fun transfer(request: ExternalTransferRequest): ExternalTransferResponse
    suspend fun inquireTransferResult(idempotencyKey: String): ExternalTransferResponse
}