package com.tossbank.account.infrastructure.persistence

import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import org.springframework.data.jpa.repository.JpaRepository

interface AccountRepository: JpaRepository<Account, Long> {
    fun findAllByMemberIdAndStatus(memberId: Long, status: AccountStatus): List<Account>
}