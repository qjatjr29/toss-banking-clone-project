package com.tossbank.transfer.domain.model

import com.tossbank.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "internal_transfer_saga",
    indexes = [
        Index(name = "idx_idempotency_key",  columnList = "idempotency_key", unique = true),
        Index(name = "idx_status_next_retry", columnList = "status, next_retry_at"),
        Index(name = "idx_from_member_id",   columnList = "from_member_id"),
    ]
)
class InternalTransferSaga(
    @Column(name = "from_member_id",     nullable = false)
    val fromMemberId: Long,

    @Column(name = "from_account_id",    nullable = false)
    val fromAccountId: Long,

    @Column(name = "to_account_id",      nullable = false)
    val toAccountId: Long,

    @Column(name = "to_account_number",  nullable = false, length = 30)
    val toAccountNumber: String,

    @Column(name = "to_member_name",     nullable = false, length = 50)
    val toMemberName: String,

    @Column(name = "from_member_name",   nullable = false, length = 50)
    val fromMemberName: String,

    @Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    @Column(length = 200)
    val description: String?,

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    val idempotencyKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: InternalTransferSagaStatus = InternalTransferSagaStatus.PENDING,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_retry_at")
    var nextRetryAt: LocalDateTime? = null,

    @Column(name = "remaining_balance", precision = 19, scale = 4)
    var remainingBalance: BigDecimal? = null,
) : BaseEntity() {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun markWithdrawCompleted(balance: BigDecimal) {
        status = InternalTransferSagaStatus.WITHDRAW_COMPLETED
        remainingBalance = balance
        clearRetry()
    }

    fun markWithdrawUnknown() {
        status = InternalTransferSagaStatus.WITHDRAW_UNKNOWN
        scheduleNextRetry()
    }

    fun markWithdrawFailed() {
        status = InternalTransferSagaStatus.FAILED
    }

    fun markCompleted() {
        status = InternalTransferSagaStatus.COMPLETED
        clearRetry()
    }

    fun markDepositUnknown() {
        status = InternalTransferSagaStatus.DEPOSIT_UNKNOWN
        scheduleNextRetry()
    }

    fun markCompensating() {
        check(status == InternalTransferSagaStatus.WITHDRAW_COMPLETED ||
              status == InternalTransferSagaStatus.DEPOSIT_UNKNOWN) {
            "markCompensating 호출 불가 상태: $status"
        }
        status = InternalTransferSagaStatus.COMPENSATING
        clearRetry()
    }

    fun markCompensated() {
        check(status == InternalTransferSagaStatus.COMPENSATING) { "markCompensated 호출 불가 상태: $status" }
        status = InternalTransferSagaStatus.COMPENSATED
    }

    fun markManualRequired() {
        status = InternalTransferSagaStatus.MANUAL_REQUIRED
    }

    fun isRetryExhausted() = retryCount >= MAX_RETRY_COUNT

    fun scheduleNextRetry() {
        retryCount++
        nextRetryAt = LocalDateTime.now().plusSeconds(
            BACKOFF_SECONDS.getOrElse(retryCount - 1) { 1800L }
        )
    }

    private fun clearRetry() { retryCount = 0; nextRetryAt = null }

    companion object {
        const val MAX_RETRY_COUNT = 5
        private val BACKOFF_SECONDS = listOf(30L, 120L, 300L, 900L, 1800L)
    }
}