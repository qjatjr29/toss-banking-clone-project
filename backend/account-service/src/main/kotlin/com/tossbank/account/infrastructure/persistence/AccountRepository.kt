package com.tossbank.account.infrastructure.persistence

import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface AccountRepository: JpaRepository<Account, Long> {

    fun findAllByMemberIdAndStatus(memberId: Long, status: AccountStatus): List<Account>
    fun findAllByMemberId(memberId: Long): List<Account>
    fun findByAccountNumber(accountNumber: String): Account?
    fun findByAccountNumberAndStatus(accountNumber: String, status: AccountStatus): Account?

    /**
     * 비관적 쓰기 락 (SELECT FOR UPDATE)
     *
     * Redisson이 앱 서버 인스턴스 간 중복 요청을 선제 차단하고 DB 락을 통해서 두 번째 방어
     *
     * DB 락이 필요한 이유:
     * - Redisson 락 코드에 버그가 생겼을 때 최후 안전망
     * - Redisson의 leaseTime이 만료되어 락이 해제됐는데
     *   TX가 아직 끝나지 않은 극단적 상황 방어
     * - 뱅킹 시스템 특성상 이중 방어가 표준 관행
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    fun findByIdWithLock(id: Long): Account?

}