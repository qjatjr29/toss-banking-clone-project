package com.tossbank.transfer.infrastructure.outbox

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@Table(
    name = "outbox_events",
    indexes = [
        Index(name = "idx_outbox_status_scheduled", columnList = "status, scheduled_at"),
    ]
)
@EntityListeners(AuditingEntityListener::class)
class OutboxEvent(
    @Column(name = "saga_id",   nullable = false)
    val sagaId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    val eventType: OutboxEventType,

    // Kafka 발행 대상 토픽
    @Column(nullable = false, length = 100)
    val topic: String,

    // JSON 직렬화된 메시지 payload
    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    // 지연 발행 시각 (null = 즉시 발행 가능)
    @Column(name = "scheduled_at")
    var scheduledAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "fail_count", nullable = false)
    var failCount: Int = 0,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}

enum class OutboxStatus { PENDING, PUBLISHED, FAILED }
