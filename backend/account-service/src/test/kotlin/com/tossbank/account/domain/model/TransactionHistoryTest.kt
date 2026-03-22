package com.tossbank.account.domain.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal


class TransactionHistoryTest : BehaviorSpec({

    Given("입금 내역을 생성할 때") {
        When("ofDeposit()을 호출하면") {
            Then("DEPOSIT 타입, 기본 설명 '입금'으로 생성된다") {
                val history = TransactionHistory.ofDeposit(
                    accountId      = 1L,
                    amount         = BigDecimal("5000"),
                    balanceAfterTx = BigDecimal("15000"),
                )
                history.transactionType shouldBe TransactionType.DEPOSIT
                history.accountId shouldBe 1L
                history.amount shouldBe BigDecimal("5000")
                history.balanceAfterTx shouldBe BigDecimal("15000")
                history.description shouldBe "입금"
                history.idempotencyKey shouldBe null
            }
        }

        When("counterpartAccountNumber와 description을 함께 전달하면") {
            Then("해당 값이 저장된다") {
                val history = TransactionHistory.ofDeposit(
                    accountId                = 1L,
                    amount                   = BigDecimal("5000"),
                    balanceAfterTx           = BigDecimal("15000"),
                    counterpartAccountNumber = "234-5678",
                    description              = "용돈",
                )
                history.counterpartAccountNumber shouldBe "234-5678"
                history.description shouldBe "용돈"
            }
        }
    }

    Given("출금 내역을 생성할 때") {
        When("ofWithdrawal()을 기본 파라미터만으로 호출하면") {
            Then("WITHDRAWAL 타입, 기본 설명 '출금'으로 생성된다") {
                val history = TransactionHistory.ofWithdrawal(
                    accountId      = 1L,
                    amount         = BigDecimal("3000"),
                    balanceAfterTx = BigDecimal("7000"),
                )
                history.transactionType shouldBe TransactionType.WITHDRAWAL
                history.accountId shouldBe 1L
                history.amount shouldBe BigDecimal("3000")
                history.balanceAfterTx shouldBe BigDecimal("7000")
                history.description shouldBe "출금"
            }
        }
    }

    Given("이체 내역을 생성할 때") {
        When("isOutgoing = true(송금)로 ofTransfer()를 호출하면") {
            Then("TRANSFER 타입으로 생성된다") {
                val history = TransactionHistory.ofTransfer(
                    accountId                = 1L,
                    amount                   = BigDecimal("10000"),
                    balanceAfterTx           = BigDecimal.ZERO,
                    counterpartAccountNumber = "234-5678",
                    counterpartName          = "홍길동",
                    description              = "점심값",
                    isOutgoing               = true,
                    idempotencyKey           = "idem-001",
                )
                history.transactionType shouldBe TransactionType.TRANSFER
                history.counterpartAccountNumber shouldBe "234-5678"
                history.counterpartName shouldBe "홍길동"
                history.description shouldBe "점심값"
                history.idempotencyKey shouldBe "idem-001"
            }
        }

        When("isOutgoing = false(수신)로 ofTransfer()를 호출하면") {
            Then("TRANSFER_IN 타입으로 생성된다") {
                val history = TransactionHistory.ofTransfer(
                    accountId                = 2L,
                    amount                   = BigDecimal("10000"),
                    balanceAfterTx           = BigDecimal("10000"),
                    counterpartAccountNumber = "1000-0001",
                    counterpartName          = "김철수",
                    description              = "점심값",
                    isOutgoing               = false,
                )
                history.transactionType shouldBe TransactionType.TRANSFER_IN
                history.counterpartName shouldBe "김철수"
            }
        }

        When("idempotencyKey 없이 ofTransfer()를 호출하면") {
            Then("idempotencyKey가 null로 저장된다") {
                val history = TransactionHistory.ofTransfer(
                    accountId                = 1L,
                    amount                   = BigDecimal("5000"),
                    balanceAfterTx           = BigDecimal("5000"),
                    counterpartAccountNumber = "234-5678",
                    counterpartName          = "홍길동",
                    description              = "이체",
                    isOutgoing               = true,
                )
                history.idempotencyKey shouldBe null
            }
        }

        When("상대방 이름이 나중에 변경되더라도") {
            Then("거래 당시 이름이 history에 값으로 보존된다") {
                val originalName = "홍길동"
                val history = TransactionHistory.ofTransfer(
                    accountId                = 1L,
                    amount                   = BigDecimal("10000"),
                    balanceAfterTx           = BigDecimal.ZERO,
                    counterpartAccountNumber = "234-5678",
                    counterpartName          = originalName,
                    description              = "송금",
                    isOutgoing               = true,
                )
                history.counterpartName shouldBe originalName
            }
        }
    }
})