package com.tossbank.transfer.infrastructure.persistence

import com.tossbank.transfer.domain.model.InterbankTransferSaga
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface InterbankTransferSagaRepository : JpaRepository<InterbankTransferSaga, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): InterbankTransferSaga?

    /**
     * 서버 크래시로 처리가 중단된 Saga 조회
     *
     * PENDING:            출금 시도 전 서버 크래시
     * WITHDRAW_COMPLETED: 출금 완료 후 외부 송금 시도 전 서버 크래시
     * WITHDRAW_UNKNOWN:   출금 재조회 Outbox 유실
     * TRANSFER_UNKNOWN:   외부 송금 재조회 Outbox 유실
     * COMPENSATING:       보상 Outbox 유실
     */
    @Query("""
        SELECT s FROM InterbankTransferSaga s
        WHERE s.status IN (
            'PENDING',
            'WITHDRAW_COMPLETED',
            'WITHDRAW_UNKNOWN',
            'TRANSFER_UNKNOWN',
            'COMPENSATING'
        )
        AND s.updatedAt <= :threshold
    """)
    fun findStuckSagas(@Param("threshold") threshold: LocalDateTime): List<InterbankTransferSaga>
}