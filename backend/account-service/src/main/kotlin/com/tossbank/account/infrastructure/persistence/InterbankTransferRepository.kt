package com.tossbank.account.infrastructure.persistence

import com.tossbank.account.domain.model.InterbankTransfer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface InterbankTransferRepository : JpaRepository<InterbankTransfer, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): InterbankTransfer?

    // 입금 확인 재조회 대상을 찾기
    @Query("""
        SELECT t FROM InterbankTransfer t
        WHERE t.status = 'UNKNOWN'
          AND t.retryCount < :maxRetry
          AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= :now)
        ORDER BY t.nextRetryAt ASC NULLS FIRST
    """)
    fun findUnknownRetryTargets(
        @Param("now") now: LocalDateTime,
        @Param("maxRetry") maxRetry: Int,
    ): List<InterbankTransfer>

    // 보상 트랜잭션 재시도 대상
    @Query("""
        SELECT t FROM InterbankTransfer t
        WHERE t.status = 'COMPENSATION_PENDING'
          AND t.retryCount < :maxRetry
          AND t.nextRetryAt <= :now
        ORDER BY t.nextRetryAt ASC
    """)
    fun findCompensationRetryTargets(
        @Param("now") now: LocalDateTime,
        @Param("maxRetry") maxRetry: Int,
    ): List<InterbankTransfer>

    // 출금 완료 상태로 일정 시간 방치된 경우
    // 출금 완료 이후 서버가 죽어서 이후 처리가 되지 못한 케이스
    @Query("""
        SELECT t FROM InterbankTransfer t
        WHERE t.status = 'WITHDRAW_COMPLETED'
          AND t.createdAt <= :threshold
    """)
    fun findStuckWithdrawals(@Param("threshold") threshold: LocalDateTime): List<InterbankTransfer>
}