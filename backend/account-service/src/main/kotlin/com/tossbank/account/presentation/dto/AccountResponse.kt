package com.tossbank.account.presentation.dto

import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import java.math.BigDecimal
import java.time.LocalDateTime

data class AccountResponse(
    val id: Long,
    val memberId: Long,
    val accountNumber: String,
    val holderName: String,
    val balance: BigDecimal,
    val status: AccountStatus,
    val alias: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(account: Account) = AccountResponse(
            id            = account.id,
            memberId      = account.memberId,
            accountNumber = account.accountNumber,
            holderName    = account.holderName,
            balance       = account.balance,
            status        = account.status,
            alias         = account.alias,
            createdAt     = account.createdAt,
        )
    }
}