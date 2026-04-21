package com.tossbank.account.domain.model

import InvalidTransferStateTransitionException
import com.tossbank.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "interbank_transfer",
    indexes = [
        Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true),
        Index(name = "idx_status_next_retry", columnList = "status, next_retry_at"),
        Index(name = "idx_status_created_at", columnList = "status, created_at"),
        Index(name = "idx_from_member_id",    columnList = "from_member_id"),
    ]
)
class InterbankTransfer(

    @Column(name = "from_member_id", nullable = false)
    val fromMemberId: Long,

    @Column(name = "from_account_id", nullable = false)
    val fromAccountId: Long,

    @Column(name = "from_account_number", nullable = false, length = 30)
    val fromAccountNumber: String,

    @Column(name = "to_account_number", nullable = false, length = 30)
    val toAccountNumber: String,

    @Column(name = "to_bank_code", nullable = false, length = 10)
    val toBankCode: String,

    @Column(name = "to_member_name", nullable = false, length = 50)
    val toMemberName: String,

    @Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    @Column(length = 200)
    val description: String?,

    // 멱등성 키 - 외부 은행 API 호출 시 헤더 값으로 전달
    @Column(name = "idempotency_key", nullable = true, length = 100, unique = true)
    var idempotencyKey: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: InterbankTransferStatus = InterbankTransferStatus.PENDING,

    // 외부 은행에서 발급한 트랜잭션 ID — 성공 시 저장
    @Column(name = "external_transaction_id", length = 100)
    var externalTransactionId: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    // 다음 재시도 예정
    @Column(name = "next_retry_at")
    var nextRetryAt: LocalDateTime? = null,

    @Column(name = "last_error_message", length = 500)
    var lastErrorMessage: String? = null,

    ) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun markWithdrawCompleted() {
        if (status != InterbankTransferStatus.PENDING) {
            throw InvalidTransferStateTransitionException();
        }
        status = InterbankTransferStatus.WITHDRAW_COMPLETED
    }

    fun markCompleted(externalTxId: String) {
        if (status != InterbankTransferStatus.WITHDRAW_COMPLETED &&
            status != InterbankTransferStatus.UNKNOWN) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferStatus.COMPLETED
        externalTransactionId = externalTxId
    }

    fun markFailed() {
        status = InterbankTransferStatus.FAILED
    }

    fun markCompensated() {
        status = InterbankTransferStatus.COMPENSATED
        idempotencyKey = null  // UNIQUE 제약 해제 → 동일 키로 새 이체 가능
    }

    // 타행 입금 시 5xx/timeout 상황
    fun markUnknown(errorMessage: String) {
        status = InterbankTransferStatus.UNKNOWN
        lastErrorMessage = errorMessage
        scheduleNextRetry()
    }

    // 보상 트랜잭션 실패한 경우
    // 스케줄러를 통해서 재시도
    fun markCompensationPending(errorMessage: String) {
        status = InterbankTransferStatus.COMPENSATION_PENDING
        lastErrorMessage = errorMessage
        scheduleNextRetry()
    }

    fun markManualRequired(errorMessage: String) {
        status = InterbankTransferStatus.MANUAL_REQUIRED
        lastErrorMessage = errorMessage
    }

    /**
     * 재시도 횟수를 올리고 다음 재시도 시각을 지수 백오프로 설정
     *
     * UNKNOWN 재조회:   30s → 1m → 2m → 4m → ... 최대 30m
     * COMPENSATION:     1m  → 5m → 30m → 1h → ... 최대 2h
     *
     * 두 케이스 모두 이 메서드를 공유 — status에 따라 간격 결정
     */
    fun scheduleNextRetry() {
        retryCount++
        nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds())
    }

    fun isRetryExhausted(): Boolean =
        retryCount >= when (status) {
            InterbankTransferStatus.COMPENSATION_PENDING -> MAX_COMPENSATION_RETRY
            else                                         -> MAX_RETRY_COUNT
        }

    private fun backoffSeconds(): Long =
        when (status) {
            InterbankTransferStatus.COMPENSATION_PENDING ->
                COMPENSATION_BACKOFF_SECONDS.getOrElse(retryCount - 1) { 900L }
            else ->
                UNKNOWN_BACKOFF_SECONDS.getOrElse(retryCount - 1) { 1800L }
        }

    companion object {
        const val MAX_RETRY_COUNT        = 5
        const val MAX_COMPENSATION_RETRY = 5
        // UNKNOWN 재조회: 30s → 2m → 5m → 15m → 30m
        private val UNKNOWN_BACKOFF_SECONDS =  listOf(30L, 120L, 300L, 900L, 1800L)
        // COMPENSATION: 1m → 3m → 5m → 10m → 15m
        private val COMPENSATION_BACKOFF_SECONDS = listOf(60L, 180L, 300L, 600L, 900L)
    }
}