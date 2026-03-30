package com.tossbank.account.presentation.controller

import com.tossbank.account.application.InternalTransferService
import com.tossbank.account.presentation.dto.*
import com.tossbank.common.response.ApiResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/internal/accounts")
class InternalAccountController(
    private val internalTransferService: InternalTransferService
) {

    @PostMapping("/withdraw")
    suspend fun withdraw(
        @RequestBody request: InternalWithdrawRequest,
    ): ApiResponse<InternalWithdrawResponse> =
        ApiResponse.success(internalTransferService.withdraw(request))

    @PostMapping("/deposit")
    suspend fun deposit(
        @RequestBody request: InternalDepositRequest,
    ): ApiResponse<InternalDepositResponse> =
        ApiResponse.success(internalTransferService.deposit(request))


    @GetMapping("/transactions/{idempotencyKey}")
    suspend fun inquireTransaction(
        @PathVariable idempotencyKey: String,
    ): ApiResponse<TransactionInquiryResponse> =
        ApiResponse.success(internalTransferService.inquireTransaction(idempotencyKey))
}