package com.tossbank.account.presentation.dto

import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import java.math.BigDecimal

data class AccountResponse(
    val accountId: Long,
    val accountNumber: String,
    val bankCode: String,
    val bankName: String,
    val balance: BigDecimal,
    val status: AccountStatus
) {
    companion object {
        fun from(account: Account): AccountResponse {
            return AccountResponse(
                accountId = account.id,
                accountNumber = account.accountNumber,
                bankCode = "092", // 토스뱅크 고정 코드
                bankName = "토스뱅크",
                balance = account.balance,
                status = account.status
            )
        }
    }
}
