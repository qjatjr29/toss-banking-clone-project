package com.tossbank.account.presentation.controller

import com.tossbank.account.application.AccountQueryService
import com.tossbank.account.application.TransactionHistoryQueryService
import com.tossbank.account.presentation.dto.AccountResponse
import com.tossbank.account.presentation.dto.SliceResponse
import com.tossbank.account.presentation.dto.TransactionHistoryResponse
import com.tossbank.common.response.ApiResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountQueryService: AccountQueryService,
    private val transactionHistoryQueryService: TransactionHistoryQueryService
) {
    @GetMapping()
    suspend fun getMyAccounts(
        @RequestHeader("X-User-Id") memberId: Long
    ): ApiResponse<List<AccountResponse>> {
        val accounts = accountQueryService.getActiveAccounts(memberId);
        return ApiResponse.success(accounts);
    }

    @GetMapping("/{accountId}/history")
    suspend fun getHistory(
        @PathVariable accountId: Long,
        @RequestHeader("X-User-Id") memberId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ApiResponse<SliceResponse<TransactionHistoryResponse>> {

        // TODO: memberId가 해당 accountId의 소유자인지 검증하는 로직 추가 필요

        val slice = transactionHistoryQueryService.getHistory(accountId, page, size)

        val content = slice.content.map {
            TransactionHistoryResponse(
                transactionId = it.id,
                type = it.transactionType,
                amount = it.amount,
                balanceAfterTx = it.balanceAfterTx,
                description = it.description,
                createdAt = it.createdAt
            )
        }

        return ApiResponse.success(SliceResponse(content, slice.hasNext()))
    }
}