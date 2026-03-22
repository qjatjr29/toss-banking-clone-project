package com.tossbank.account.domain.model

import AccountAlreadyClosedException
import AccountHasRemainingBalanceException
import AccountSuspendedException
import InsufficientBalanceException
import InvalidAccountStatusException
import InvalidDepositAmountException
import InvalidWithdrawAmountException
import UnauthorizedAccountAccessException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class AccountTest : BehaviorSpec({

    fun createAccount(
        memberId: Long = 1L,
        balance: BigDecimal = BigDecimal("10000"),
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(
        memberId      = memberId,
        accountNumber = "1000-0001",
        holderName    = "홍길동",
        balance       = balance,
        status        = status,
    )

    Given("활성 상태의 계좌에 입금할 때") {
        When("제대로된 금액을 입금하면") {
            Then("잔액이 증가한다") {
                val account = createAccount(balance = BigDecimal("10000"))
                account.deposit(BigDecimal("5000"))
                account.balance shouldBe BigDecimal("15000")
            }
        }

        When("0원을 입금하면") {
            Then("InvalidDepositAmountException이 발생한다") {
                val account = createAccount()
                shouldThrow<InvalidDepositAmountException> {
                    account.deposit(BigDecimal.ZERO)
                }
            }
        }

        When("음수 금액을 입금하면") {
            Then("InvalidDepositAmountException이 발생한다") {
                val account = createAccount()
                shouldThrow<InvalidDepositAmountException> {
                    account.deposit(BigDecimal("-1000"))
                }
            }
        }
    }

    Given("SUSPENDED 계좌에 입금할 때") {
        When("입금을 시도하면") {
            Then("AccountSuspendedException이 발생한다") {
                val account = createAccount(status = AccountStatus.SUSPENDED)
                shouldThrow<AccountSuspendedException> {
                    account.deposit(BigDecimal("1000"))
                }
            }
        }
    }

    Given("CLOSED 계좌에 입금할 때") {
        When("입금을 시도하면") {
            Then("AccountAlreadyClosedException이 발생한다") {
                val account = createAccount(status = AccountStatus.CLOSED)
                shouldThrow<AccountAlreadyClosedException> {
                    account.deposit(BigDecimal("1000"))
                }
            }
        }
    }

    Given("잔액이 10,000원인 ACTIVE 계좌에서 출금할 때") {
        When("잔액 이하의 금액을 출금하면") {
            Then("잔액이 차감된다") {
                val account = createAccount(balance = BigDecimal("10000"))
                account.withdraw(BigDecimal("3000"))
                account.balance shouldBe BigDecimal("7000")
            }
        }

        When("잔액과 동일한 금액을 출금하면") {
            Then("잔액이 0이 된다") {
                val account = createAccount(balance = BigDecimal("10000"))
                account.withdraw(BigDecimal("10000"))
                account.balance shouldBe BigDecimal.ZERO
            }
        }

        When("잔액을 초과하는 금액을 출금하면") {
            Then("InsufficientBalanceException이 발생하고 잔액에 변화가 없다") {
                val account = createAccount(balance = BigDecimal("10000"))
                shouldThrow<InsufficientBalanceException> {
                    account.withdraw(BigDecimal("10001"))
                }
                account.balance shouldBe BigDecimal("10000")
            }
        }

        When("0원을 출금하면") {
            Then("InvalidWithdrawAmountException이 발생한다") {
                val account = createAccount()
                shouldThrow<InvalidWithdrawAmountException> {
                    account.withdraw(BigDecimal.ZERO)
                }
            }
        }

        When("음수 금액을 출금하면") {
            Then("InvalidWithdrawAmountException이 발생한다") {
                val account = createAccount()
                shouldThrow<InvalidWithdrawAmountException> {
                    account.withdraw(BigDecimal("-1000"))
                }
            }
        }
    }

    Given("SUSPENDED 계좌에서 출금할 때") {
        When("출금을 시도하면") {
            Then("AccountSuspendedException이 발생한다") {
                val account = createAccount(status = AccountStatus.SUSPENDED)
                shouldThrow<AccountSuspendedException> {
                    account.withdraw(BigDecimal("1000"))
                }
            }
        }
    }

    Given("CLOSED 계좌에서 출금할 때") {
        When("출금을 시도하면") {
            Then("AccountAlreadyClosedException이 발생한다") {
                val account = createAccount(status = AccountStatus.CLOSED)
                shouldThrow<AccountAlreadyClosedException> {
                    account.withdraw(BigDecimal("1000"))
                }
            }
        }
    }

    Given("ACTIVE 계좌의 상태를 변경할 때") {
        When("suspend()를 호출하면") {
            Then("SUSPENDED 상태가 된다") {
                val account = createAccount(status = AccountStatus.ACTIVE)
                account.suspend()
                account.status shouldBe AccountStatus.SUSPENDED
            }
        }

        When("reactivate()를 호출하면") {
            Then("InvalidAccountStatusException이 발생한다") {
                val account = createAccount(status = AccountStatus.ACTIVE)
                shouldThrow<InvalidAccountStatusException> {
                    account.reactivate()
                }
            }
        }
    }

    Given("SUSPENDED 계좌의 상태를 변경할 때") {
        When("suspend()를 호출하면") {
            Then("InvalidAccountStatusException이 발생한다") {
                val account = createAccount(status = AccountStatus.SUSPENDED)
                shouldThrow<InvalidAccountStatusException> {
                    account.suspend()
                }
            }
        }

        When("reactivate()를 호출하면") {
            Then("ACTIVE 상태가 된다") {
                val account = createAccount(status = AccountStatus.SUSPENDED)
                account.reactivate()
                account.status shouldBe AccountStatus.ACTIVE
            }
        }
    }

    Given("CLOSED 계좌의 상태를 변경할 때") {
        When("suspend()를 호출하면") {
            Then("InvalidAccountStatusException이 발생한다") {
                val account = createAccount(status = AccountStatus.CLOSED)
                shouldThrow<InvalidAccountStatusException> {
                    account.suspend()
                }
            }
        }

        When("reactivate()를 호출하면") {
            Then("InvalidAccountStatusException이 발생한다") {
                val account = createAccount(status = AccountStatus.CLOSED)
                shouldThrow<InvalidAccountStatusException> {
                    account.reactivate()
                }
            }
        }
    }

    Given("계좌를 해지할 때") {
        When("잔액이 0인 ACTIVE 계좌를 close()하면") {
            Then("CLOSED 상태가 된다") {
                val account = createAccount(balance = BigDecimal.ZERO)
                account.close()
                account.status shouldBe AccountStatus.CLOSED
            }
        }

        When("잔액이 남아있는 계좌를 close()하면") {
            Then("AccountHasRemainingBalanceException이 발생한다") {
                val account = createAccount(balance = BigDecimal("1000"))
                shouldThrow<AccountHasRemainingBalanceException> {
                    account.close()
                }
            }
        }

        When("이미 CLOSED된 계좌를 close()하면") {
            Then("AccountAlreadyClosedException이 발생한다") {
                val account = createAccount(
                    balance = BigDecimal.ZERO,
                    status  = AccountStatus.CLOSED,
                )
                shouldThrow<AccountAlreadyClosedException> {
                    account.close()
                }
            }
        }
    }

    Given("계좌 소유자 검증 시") {
        When("계좌 소유자 memberId와 동일한 memberId로 verifyOwner()를 호출하면") {
            Then("예외가 발생하지 않는다") {
                val account = createAccount(memberId = 1L)
                account.verifyOwner(1L)
            }
        }

        When("계좌 소유자 memberId와 다른 memberId로 verifyOwner()를 호출하면") {
            Then("UnauthorizedAccountAccessException이 발생한다") {
                val account = createAccount(memberId = 1L)
                shouldThrow<UnauthorizedAccountAccessException> {
                    account.verifyOwner(2L)
                }
            }
        }
    }
})