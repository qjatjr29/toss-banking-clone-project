package com.tossbank.transfer.infrastructure.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    // scheduledAt이 null이거나 현재 이전인 PENDING 이벤트만 조회 → 지연 구현
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PENDING'
          AND (o.scheduledAt IS NULL OR o.scheduledAt <= :now)
        ORDER BY o.createdAt ASC
        LIMIT 100
    """)
    fun findPublishableEvents(@Param("now") now: LocalDateTime): List<OutboxEvent>

    // Saga 완료/실패 시 미발행 Outbox 제거 (중복 발행 방지 용)
    fun deleteAllBySagaIdAndStatus(sagaId: Long, status: OutboxStatus)

    // 복구 스케줄러에서 사용: 해당 Saga의 PENDING Outbox가 이미 있는지 확인
    fun existsBySagaIdAndStatus(sagaId: Long, status: OutboxStatus): Boolean
}