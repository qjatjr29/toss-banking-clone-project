package com.tossbank.external.domain.model

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "inbound_transfers",
    indexes = [
        Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true)
    ]
)
class InboundTransfer(
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    val idempotencyKey: String,

    @Column(name = "external_transaction_id", nullable = false, length = 100)
    val externalTransactionId: String,

    @Column(name = "from_bank_code", nullable = false, length = 10)
    val fromBankCode: String,

    @Column(name = "from_account_number", nullable = false, length = 30)
    val fromAccountNumber: String,

    @Column(name = "to_account_number", nullable = false, length = 30)
    val toAccountNumber: String,

    @Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: InboundTransferStatus = InboundTransferStatus.SUCCESS,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L
}