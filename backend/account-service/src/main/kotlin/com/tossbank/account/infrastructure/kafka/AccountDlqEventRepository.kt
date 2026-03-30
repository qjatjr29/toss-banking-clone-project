package com.tossbank.account.infrastructure.kafka

import org.springframework.data.jpa.repository.JpaRepository

interface AccountDlqEventRepository : JpaRepository<AccountDlqEvent, Long> {
    fun findByStatusAndRetryCountLessThan(
        status: AccountDlqStatus,
        maxRetry: Int,
    ): List<AccountDlqEvent>
}