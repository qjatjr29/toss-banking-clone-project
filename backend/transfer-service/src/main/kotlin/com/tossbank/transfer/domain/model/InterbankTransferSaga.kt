package com.tossbank.transfer.domain.model

import com.tossbank.common.domain.BaseEntity
import com.tossbank.transfer.domain.exception.InvalidTransferStateTransitionException
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "interbank_transfer_saga",
    indexes = [
        Index(name = "idx_interbank_idempotency_key", columnList = "idempotency_key", unique = true),
        Index(name = "idx_interbank_status_updated",  columnList = "status, updated_at"),
        Index(name = "idx_interbank_from_member",     columnList = "from_member_id"),
    ]
)
class InterbankTransferSaga(

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

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    val idempotencyKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: InterbankTransferSagaStatus = InterbankTransferSagaStatus.PENDING,

    // 외부 은행 발급 트랜잭션 ID — 수동 처리 시 외부 은행과 대조용
    @Column(name = "external_transaction_id", length = 100)
    var externalTransactionId: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_retry_at")
    var nextRetryAt: LocalDateTime? = null,

    @Column(name = "remaining_balance", precision = 19, scale = 4)
    var remainingBalance: BigDecimal? = null,

    @Column(name = "last_error_message", length = 500)
    var lastErrorMessage: String? = null,
    ) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    fun markWithdrawCompleted(balance: BigDecimal) {
        if (status != InterbankTransferSagaStatus.PENDING) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferSagaStatus.WITHDRAW_COMPLETED
        remainingBalance = balance
        clearRetry()
    }

    /**
     * 외부 송금 5xx/timeout — 결과 불확실
     * WITHDRAW_COMPLETED(최초 실패) 또는 TRANSFER_UNKNOWN(재시도 중) 에서만 전이 가능
     */
    fun markTransferUnknown(errorMessage: String) {
        if (status != InterbankTransferSagaStatus.WITHDRAW_COMPLETED &&
            status != InterbankTransferSagaStatus.TRANSFER_UNKNOWN) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferSagaStatus.TRANSFER_UNKNOWN
        lastErrorMessage = errorMessage
        scheduleNextRetry()
    }

    /**
     * 외부 송금 성공 확인
     * WITHDRAW_COMPLETED(정상 흐름) 또는 TRANSFER_UNKNOWN(재조회 후 성공 확인) 에서만 전이 가능
     */
    fun markCompleted(externalTxId: String) {
        if (status != InterbankTransferSagaStatus.WITHDRAW_COMPLETED &&
            status != InterbankTransferSagaStatus.TRANSFER_UNKNOWN) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferSagaStatus.COMPLETED
        externalTransactionId = externalTxId
        clearRetry()
    }


    /**
     * 외부 송금 4xx 또는 재조회 후 FAILED 확인 → 보상 시작
     * WITHDRAW_COMPLETED(4xx 즉시) 또는 TRANSFER_UNKNOWN(재조회 후 실패 확인) 에서만 전이 가능
     */
    fun markCompensating() {
        if (status != InterbankTransferSagaStatus.WITHDRAW_COMPLETED &&
            status != InterbankTransferSagaStatus.TRANSFER_UNKNOWN) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferSagaStatus.COMPENSATING
        clearRetry()
    }

    fun markCompensated() {
        if (status != InterbankTransferSagaStatus.COMPENSATING) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferSagaStatus.COMPENSATED
    }

    fun markFailed() {
        if (status != InterbankTransferSagaStatus.PENDING) {
            throw InvalidTransferStateTransitionException()
        }
        status = InterbankTransferSagaStatus.FAILED
    }

    fun markManualRequired(errorMessage: String) {
        status = InterbankTransferSagaStatus.MANUAL_REQUIRED
        lastErrorMessage = errorMessage
    }

    fun isRetryExhausted(): Boolean = retryCount >= MAX_RETRY_COUNT

    fun scheduleNextRetry() {
        retryCount++
        nextRetryAt = LocalDateTime.now().plusSeconds(
            BACKOFF_SECONDS.getOrElse(retryCount - 1) { 1800L }
        )
    }

    private fun clearRetry() {
        retryCount = 0
        nextRetryAt = null
    }

    companion object {
        const val MAX_RETRY_COUNT = 5
        // 30s → 2m → 5m → 15m → 30m
        private val BACKOFF_SECONDS = listOf(30L, 120L, 300L, 900L, 1800L)
    }
}