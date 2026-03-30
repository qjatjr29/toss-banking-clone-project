package com.tossbank.transfer.infrastructure.persistence

import com.tossbank.transfer.domain.model.InternalTransferSaga
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface InternalTransferSagaRepository : JpaRepository<InternalTransferSaga, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): InternalTransferSaga?

    // 배치 복구: 일정 시간 이상 PENDING/WITHDRAW_COMPLETED 방치 → 서버 크래시로 처리 중단된 건
    @Query("""
        SELECT s FROM InternalTransferSaga s
        WHERE s.status IN (
            'PENDING',
            'WITHDRAW_COMPLETED',
            'WITHDRAW_UNKNOWN',
            'DEPOSIT_UNKNOWN',
            'COMPENSATING'
        )
        AND s.updatedAt <= :threshold
    """)
    fun findStuckSagas(@Param("threshold") threshold: LocalDateTime): List<InternalTransferSaga>
}