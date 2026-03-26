package com.tossbank.transfer.infrastructure.outbox

import com.tossbank.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(
    name = "outbox_event",
    indexes = [Index(name = "idx_status_created_at", columnList = "status, created_at")]
)
class OutboxEvent(
    @Column(nullable = false, length = 100)
    val topic: String,

    @Column(name = "aggregate_id", nullable = false, length = 50)
    val aggregateId: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun markPublished() {
        status = OutboxStatus.PUBLISHED
    }

    fun incrementRetry() {
        retryCount++
        if (retryCount >= 5) status = OutboxStatus.DEAD
    }
}

enum class OutboxStatus { PENDING, PUBLISHED, DEAD }