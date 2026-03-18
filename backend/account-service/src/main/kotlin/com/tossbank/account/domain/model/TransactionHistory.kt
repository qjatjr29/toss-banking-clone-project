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
        Index(name = "idx_account_created_at_desc", columnList = "account_id, created_at DESC")
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
    @Column(nullable = false, precision = 19, scale = 4)
    val balanceAfterTx: BigDecimal,

    @Column(nullable = false)
    val description: String
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set
}

enum class TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}