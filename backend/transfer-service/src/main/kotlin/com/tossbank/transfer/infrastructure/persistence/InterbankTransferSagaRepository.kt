package com.tossbank.transfer.infrastructure.persistence

import com.tossbank.transfer.domain.model.InterbankTransferSaga
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface InterbankTransferSagaRepository : JpaRepository<InterbankTransferSaga, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): InterbankTransferSaga?

    /**
     * 서버 문제로 처리가 중단된 Saga 조회
     *
     * WITHDRAW_COMPLETED: 출금 완료 후 외부 송금 미시도
     * TRANSFER_UNKNOWN:   재조회 Outbox가 유실된 경우
     * COMPENSATING:       보상 Outbox가 유실된 경우
     */
    @Query("""
        SELECT s FROM InterbankTransferSaga s
        WHERE s.status IN (
            'WITHDRAW_COMPLETED',
            'TRANSFER_UNKNOWN',
            'COMPENSATING'
        )
        AND s.updatedAt <= :threshold
    """)
    fun findStuckSagas(@Param("threshold") threshold: LocalDateTime): List<InterbankTransferSaga>
}