package com.tossbank.account.domain.model

import InsufficientBalanceException
import InvalidDepositAmountException
import InvalidWithdrawAmountException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class AccountTest : BehaviorSpec({

    Given("새로운 계좌가 생성되었을 때") {
        val account = Account(
            memberId = 1L,
            accountNumber = "1000-1234-5678"
        )
        // 잔액을 임의로 10000 세팅
        account.balance = BigDecimal("10000.00")

        When("정상적인 금액(5000원)을 입금하면") {
            val depositAmount = BigDecimal("5000.00")
            account.deposit(depositAmount)

            Then("잔액이 입금한 금액만큼 증가해야 한다.") {
                account.balance shouldBe BigDecimal("15000.00")
            }
        }

        When("0 이하의 금액을 입금하려고 시도하면") {
            val invalidAmount = BigDecimal("-1000.00")

            Then("InvalidDepositAmountException 예외가 발생해야 한다.") {
                val exception = shouldThrow<InvalidDepositAmountException> {
                    account.deposit(invalidAmount)
                }
                exception.message shouldBe "입금액은 0보다 커야 합니다."
            }
        }

        When("정상적인 금액(3000원)을 출금하면") {
            val withdrawAmount = BigDecimal("3000.00")
            // 현재 잔액 15000원 상태
            account.withdraw(withdrawAmount)

            Then("잔액이 출금한 금액만큼 감소해야 한다.") {
                account.balance shouldBe BigDecimal("12000.00")
            }
        }

        When("0 이하의 금액을 출금하려고 시도하면") {
            val invalidAmount = BigDecimal("-1000.00")

            Then("InvalidWithdrawAmountException 예외가 발생해야 한다.") {
                val exception = shouldThrow<InvalidWithdrawAmountException> {
                    account.withdraw(invalidAmount)
                }
                exception.message shouldBe "출금액은 0보다 커야 합니다."
            }
        }

        When("현재 잔액(12000원)보다 많은 금액(20000원)을 출금하려고 시도하면") {
            val overAmount = BigDecimal("20000.00")

            Then("잔액 부족 InsufficientBalanceException 예외가 발생해야 한다.") {
                val exception = shouldThrow<InsufficientBalanceException> {
                    account.withdraw(overAmount)
                }
                exception.message shouldBe "계좌 잔액이 부족합니다."
            }
        }
    }
})