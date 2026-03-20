package com.tossbank.account.infrastructure.persistence

import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AccountRepository: JpaRepository<Account, Long> {

    fun findAllByMemberIdAndStatus(memberId: Long, status: AccountStatus): List<Account>
    fun findAllByMemberId(memberId: Long): List<Account>
    fun findByAccountNumber(accountNumber: String): Account?
    fun findByAccountNumberAndStatus(accountNumber: String, status: AccountStatus): Account?

    // SELECT FOR UPDATE — DB 레벨 비관적 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    fun findByIdWithLock(@Param("id") id: Long): Account?

}