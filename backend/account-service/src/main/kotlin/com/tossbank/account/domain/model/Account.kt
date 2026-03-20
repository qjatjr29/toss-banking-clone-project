package com.tossbank.account.domain.model

import AccountAlreadyClosedException
import AccountHasRemainingBalanceException
import AccountSuspendedException
import InsufficientBalanceException
import InvalidAccountStatusException
import InvalidDepositAmountException
import InvalidWithdrawAmountException
import UnauthorizedAccountAccessException
import com.tossbank.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "account",
    uniqueConstraints = [UniqueConstraint(name = "uk_account_number", columnNames = ["account_number"])],
    indexes = [
        Index(name = "idx_member_id", columnList = "member_id"),
        Index(name = "idx_account_number", columnList = "account_number"),
    ]
)
class Account(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "account_number", nullable = false, length = 30)
    val accountNumber: String,

    @Column(name = "holder_name", nullable = false, length = 50)
    val holderName: String,

    @Column(nullable = false, precision = 19, scale = 4)
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

    fun deposit(amount: BigDecimal) {
        verifyActive()
        if (amount <= BigDecimal.ZERO) throw InvalidDepositAmountException()
        balance = balance.add(amount)
    }

    fun withdraw(amount: BigDecimal) {
        verifyActive()
        if (amount <= BigDecimal.ZERO) throw InvalidWithdrawAmountException()
        if (balance < amount) throw InsufficientBalanceException()
        balance = balance.subtract(amount)
    }

    fun suspend() {
        if (status != AccountStatus.ACTIVE) throw InvalidAccountStatusException()
        status = AccountStatus.SUSPENDED
    }

    fun reactivate() {
        if (status != AccountStatus.SUSPENDED) throw InvalidAccountStatusException()
        status = AccountStatus.ACTIVE
    }

    fun close() {
        if (status == AccountStatus.CLOSED) throw AccountAlreadyClosedException()
        if (balance.compareTo(BigDecimal.ZERO) != 0) throw AccountHasRemainingBalanceException();
        status = AccountStatus.CLOSED
    }

    fun verifyOwner(memberId: Long) {
        if (this.memberId != memberId) throw UnauthorizedAccountAccessException()
    }

    private fun verifyActive() {
        when (status) {
            AccountStatus.SUSPENDED -> throw AccountSuspendedException()
            AccountStatus.CLOSED    -> throw AccountAlreadyClosedException()
            AccountStatus.ACTIVE    -> Unit
        }
    }
}

enum class AccountStatus { ACTIVE, SUSPENDED, CLOSED }
