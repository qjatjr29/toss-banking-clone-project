package com.tossbank.account.presentation.controller

import com.tossbank.account.application.AccountQueryService
import com.tossbank.account.presentation.dto.AccountResponse
import com.tossbank.common.response.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountQueryService: AccountQueryService
) {
    @GetMapping()
    suspend fun getMyAccounts(
        @RequestHeader("X-User-Id") memberId: Long
    ): ApiResponse<List<AccountResponse>> {
        val accounts = accountQueryService.getActiveAccounts(memberId);
        return ApiResponse.success(accounts);
    }
}