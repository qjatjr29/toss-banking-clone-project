package com.tossbank.account.domain.model

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "transaction_histories",
    indexes = [
        Index(name = "idx_account_created_at_desc", columnList = "account_id, created_at DESC") ,
        Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true)
    ]
)
@EntityListeners(AuditingEntityListener::class)
class TransactionHistory(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val transactionType: TransactionType,

    @Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    // 거래 후 잔액
    @Column(name = "balance_after_tx", nullable = false, precision = 19, scale = 4)
    val balanceAfterTx: BigDecimal,

    // 이체 상대의 계좌번호 (이체 타입일때만 사용)
    @Column(name = "counterpart_account_number", length = 30)
    val counterpartAccountNumber: String? = null,

    /**
     * 이체 상대 이름 (이체 타입일때만 사용)
     * 정규화(FK로 설정)하지 않는 이유: 상대방이 이름을 변경하거나 탈퇴해도 거래 당시 이름을 보존하기 위함
     * 정규화를 하게되면 상대방이 탈퇴했을 때 내역을 조회할 수 없움
     * 다른 은행 계좌의 경우에는 fk로 설정할 수 없기 때문.. - 가장 중요
    **/
    @Column(name = "counterpart_name", length = 50)
    val counterpartName: String? = null,

    @Column(name = "idempotency_key", length = 64, unique = true)
    val idempotencyKey: String? = null,

    @Column(nullable = false, length = 100)
    val description: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set

    companion object {
        fun ofDeposit(
            accountId: Long,
            amount: BigDecimal,
            balanceAfterTx: BigDecimal,
            counterpartAccountNumber: String? = null,
            description: String = "입금",
        ) = TransactionHistory(
            accountId                = accountId,
            transactionType          = TransactionType.DEPOSIT,
            amount                   = amount,
            balanceAfterTx           = balanceAfterTx,
            counterpartAccountNumber = counterpartAccountNumber,
            description              = description,
        )

        fun ofWithdrawal(
            accountId: Long,
            amount: BigDecimal,
            balanceAfterTx: BigDecimal,
            counterpartAccountNumber: String? = null,
            description: String = "출금",
        ) = TransactionHistory(
            accountId                = accountId,
            transactionType          = TransactionType.WITHDRAWAL,
            amount                   = amount,
            balanceAfterTx           = balanceAfterTx,
            counterpartAccountNumber = counterpartAccountNumber,
            description              = description,
        )

        fun ofTransfer(
            accountId: Long,
            amount: BigDecimal,
            balanceAfterTx: BigDecimal,
            counterpartAccountNumber: String,
            counterpartName: String,
            description: String,
            isOutgoing: Boolean,
            idempotencyKey: String? = null,
        ) = TransactionHistory(
            accountId                = accountId,
            transactionType          = if (isOutgoing) TransactionType.TRANSFER
            else TransactionType.TRANSFER_IN,
            amount                   = amount,
            balanceAfterTx           = balanceAfterTx,
            counterpartAccountNumber = counterpartAccountNumber,
            counterpartName          = counterpartName,
            description              = description,
            idempotencyKey           = idempotencyKey,
        )

        fun ofInterbankWithdraw(
            accountId: Long,
            amount: BigDecimal,
            balanceAfterTx: BigDecimal,
            toAccountNumber: String,
            toMemberName: String,
            toBankCode: String,
            description: String?,
            idempotencyKey: String,
        ) = TransactionHistory(
            accountId                = accountId,
            transactionType          = TransactionType.INTERBANK_WITHDRAW,
            amount                   = amount,
            balanceAfterTx           = balanceAfterTx,
            counterpartAccountNumber = toAccountNumber,
            counterpartName          = toMemberName,
            description              = "[${toBankCode}] ${description ?: "타행 이체"}",
            idempotencyKey           = idempotencyKey,
        )

        fun ofInterbankWithdrawCancel(
            accountId: Long,
            amount: BigDecimal,
            balanceAfterTx: BigDecimal,
            toAccountNumber: String,
            toBankCode: String,
            idempotencyKey: String,
        ) = TransactionHistory(
            accountId                = accountId,
            transactionType          = TransactionType.INTERBANK_WITHDRAW_CANCEL,
            amount                   = amount,
            balanceAfterTx           = balanceAfterTx,
            counterpartAccountNumber = toAccountNumber,
            description              = "[${toBankCode}] 타행 이체 취소",
            // 보상 트랜잭션은 원본 키에 "-cancel" suffix
            idempotencyKey           = "$idempotencyKey-cancel",
        )
    }
}