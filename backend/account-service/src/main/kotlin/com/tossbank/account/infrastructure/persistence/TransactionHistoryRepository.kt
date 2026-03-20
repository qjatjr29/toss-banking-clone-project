package com.tossbank.account.infrastructure.persistence

import com.tossbank.account.domain.model.TransactionHistory
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionHistoryRepository : JpaRepository<TransactionHistory, Long> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: Long, pageable: Pageable): Slice<TransactionHistory>
    fun findByIdempotencyKey(idempotencyKey: String): TransactionHistory?
}