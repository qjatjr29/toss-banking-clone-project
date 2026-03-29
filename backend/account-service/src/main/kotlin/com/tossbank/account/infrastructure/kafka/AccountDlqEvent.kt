package com.tossbank.account.infrastructure.kafka

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class AccountDlqStatus { PENDING, RESOLVED, EXHAUSTED }

@Entity
@Table(
    name = "account_dlq_events",
    indexes = [Index(name = "idx_account_dlq_status", columnList = "status, created_at")]
)
@EntityListeners(AuditingEntityListener::class)
class AccountDlqEvent(
    @Column(nullable = false, length = 200)
    val topic: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(nullable = false, length = 200)
    val messageKey: String,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccountDlqStatus = AccountDlqStatus.PENDING,

    @Column(name = "last_error", length = 500)
    var lastError: String? = null,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}