package com.tossbank.account.presentation.controller

import com.tossbank.account.application.AccountQueryService
import com.tossbank.account.application.TransactionHistoryQueryService
import com.tossbank.account.application.TransferService
import com.tossbank.account.presentation.dto.*
import com.tossbank.common.response.ApiResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountQueryService: AccountQueryService,
    private val transactionHistoryQueryService: TransactionHistoryQueryService,
    private val transferService: TransferService,
) {
    @GetMapping()
    suspend fun getMyAccounts(
        @RequestHeader("X-User-Id") memberId: Long
    ): ApiResponse<List<AccountResponse>> =
        ApiResponse.success(accountQueryService.getActiveAccounts(memberId))

    @GetMapping("/{accountId}")
    suspend fun getAccount(
        @RequestHeader("X-User-Id") memberId: Long,
        @PathVariable accountId: Long,
    ): ApiResponse<AccountResponse> =
        ApiResponse.success(accountQueryService.getAccount(memberId, accountId))

    @GetMapping("/holder")
    suspend fun inquireAccountHolder(
        @RequestParam accountNumber: String,
        @RequestParam bankCode: String,
    ): ApiResponse<AccountHolderResponse> =
        ApiResponse.success(accountQueryService.inquireAccountHolder(accountNumber, bankCode))

    // TODO: cursor 방식으로 페이징 방식 변경
    // 현재: offset 페이징 (page/size)
    @GetMapping("/{accountId}/history")
    suspend fun getHistory(
        @RequestHeader("X-User-Id") memberId: Long,
        @PathVariable accountId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<SliceResponse<TransactionHistoryResponse>> {
        val slice = transactionHistoryQueryService.getHistory(memberId, accountId, page, size)

        val content = slice.content.map { TransactionHistoryResponse.from(it) }

        return ApiResponse.success(
            SliceResponse(
                content = content,
                hasNext = slice.hasNext(),
                page    = page,
                size    = size,
            )
        )
    }

    @PostMapping("/transfer")
    suspend fun transfer(
        @RequestHeader("X-User-Id") memberId: Long,
        @RequestBody request: TransferRequest,
    ): ApiResponse<TransferResponse> =
        ApiResponse.success(transferService.transfer(memberId, request))
}