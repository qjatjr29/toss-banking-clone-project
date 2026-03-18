package com.tossbank.account.domain.model

import AccountSuspendedException
import InsufficientBalanceException
import InvalidDepositAmountException
import InvalidWithdrawAmountException
import com.tossbank.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "account",
    uniqueConstraints = [UniqueConstraint(name = "uk_account_number", columnNames = ["account_number"])]
)
class Account(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "account_number", nullable = false, length = 30)
    val accountNumber: String,

    @Column(nullable = false)
    var balance: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccountStatus = AccountStatus.ACTIVE,

    @Column(name = "alias")
    var alias: String? = null
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Version
    @Column(nullable = false)
    var version: Long = 0L

    private fun verifyActiveStatus() {
        if (this.status == AccountStatus.SUSPENDED) {
            throw AccountSuspendedException()
        }
    }

    fun deposit(amount: BigDecimal) {
        verifyActiveStatus()
        if (amount <= BigDecimal.ZERO) {
            throw InvalidDepositAmountException()
        }
        this.balance += amount
    }

    fun withdraw(amount: BigDecimal) {
        verifyActiveStatus()
        if (amount <= BigDecimal.ZERO) {
            throw InvalidWithdrawAmountException()
        }
        if (this.balance < amount) {
            throw InsufficientBalanceException()
        }
        this.balance -= amount
    }
}

enum class AccountStatus { ACTIVE, SUSPENDED }
