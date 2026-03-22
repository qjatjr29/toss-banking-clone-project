package com.tossbank.account.domain.model

import InvalidTransferStateTransitionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.time.LocalDateTime

class InterbankTransferTest : BehaviorSpec({

    fun createTransfer(
        status: InterbankTransferStatus = InterbankTransferStatus.PENDING,
        retryCount: Int = 0,
    ) = InterbankTransfer(
        fromMemberId      = 1L,
        fromAccountId     = 1L,
        fromAccountNumber = "1000-0001",
        toAccountNumber   = "234-5678",
        toBankCode        = "004",
        toMemberName      = "홍길동",
        amount            = BigDecimal("10000"),
        description       = "테스트 이체",
        idempotencyKey    = "test-key-001",
        status            = status,
        retryCount        = retryCount,
    )

    Given("PENDING 상태") {
        When("markWithdrawCompleted()를 호출하면") {
            Then("WITHDRAW_COMPLETED 상태로 전이된다") {
                val transfer = createTransfer(InterbankTransferStatus.PENDING)
                transfer.markWithdrawCompleted()
                transfer.status shouldBe InterbankTransferStatus.WITHDRAW_COMPLETED
            }
        }
    }

    Given("PENDING이 아닌 상태") {
        When("markWithdrawCompleted()를 호출하면") {
            Then("InvalidTransferStateTransitionException이 발생한다") {
                val transfer = createTransfer(InterbankTransferStatus.WITHDRAW_COMPLETED)
                shouldThrow<InvalidTransferStateTransitionException> {
                    transfer.markWithdrawCompleted()
                }
            }
        }
    }

    Given("WITHDRAW_COMPLETED 상태") {
        When("markCompleted()를 호출하면") {
            Then("COMPLETED 상태로 전이되고 externalTransactionId가 저장된다") {
                val transfer = createTransfer(InterbankTransferStatus.WITHDRAW_COMPLETED)
                transfer.markCompleted("EXT-TX-001")
                transfer.status shouldBe InterbankTransferStatus.COMPLETED
                transfer.externalTransactionId shouldBe "EXT-TX-001"
            }
        }

        When("markFailed()를 호출하면") {
            Then("FAILED 상태로 전이된다") {
                val transfer = createTransfer(InterbankTransferStatus.WITHDRAW_COMPLETED)
                transfer.markFailed()
                transfer.status shouldBe InterbankTransferStatus.FAILED
            }
        }

        When("markUnknown()을 호출하면") {
            Then("UNKNOWN 상태로 전이되고 에러 메시지와 nextRetryAt이 설정된다") {
                val transfer = createTransfer(InterbankTransferStatus.WITHDRAW_COMPLETED)
                val before = LocalDateTime.now()

                transfer.markUnknown("5xx 오류")

                transfer.status shouldBe InterbankTransferStatus.UNKNOWN
                transfer.lastErrorMessage shouldBe "5xx 오류"
                transfer.retryCount shouldBe 1
                transfer.nextRetryAt shouldNotBe null
                transfer.nextRetryAt!! shouldBeGreaterThan before
            }
        }
    }

    Given("FAILED 상태") {
        When("markCompensated()를 호출하면") {
            Then("COMPENSATED 상태로 전이된다") {
                val transfer = createTransfer(InterbankTransferStatus.FAILED)
                transfer.markCompensated()
                transfer.status shouldBe InterbankTransferStatus.COMPENSATED
            }
        }

        When("markCompensationPending()을 호출하면") {
            Then("COMPENSATION_PENDING 상태로 전이되고 nextRetryAt이 설정된다") {
                val transfer = createTransfer(InterbankTransferStatus.FAILED)
                val before = LocalDateTime.now()

                transfer.markCompensationPending("DB 락 타임아웃")

                transfer.status shouldBe InterbankTransferStatus.COMPENSATION_PENDING
                transfer.lastErrorMessage shouldBe "DB 락 타임아웃"
                transfer.retryCount shouldBe 1
                transfer.nextRetryAt!! shouldBeGreaterThan before
            }
        }
    }

    Given("UNKNOWN 상태의 이체 건") {
        When("markManualRequired()를 호출하면") {
            Then("MANUAL_REQUIRED 상태로 전이되고 에러 메시지가 저장된다") {
                val transfer = createTransfer(InterbankTransferStatus.UNKNOWN)
                transfer.markManualRequired("최대 재시도 초과")
                transfer.status shouldBe InterbankTransferStatus.MANUAL_REQUIRED
                transfer.lastErrorMessage shouldBe "최대 재시도 초과"
            }
        }
    }

    Given("UNKNOWN 상태에서 scheduleNextRetry()를 반복 호출하면") {
        When("3회 연속 호출하면") {
            Then("nextRetryAt이 회차마다 지수적으로 증가한다") {
                val transfer = createTransfer(InterbankTransferStatus.UNKNOWN)

                transfer.scheduleNextRetry() // 1회: 30s
                val first = transfer.nextRetryAt!!

                transfer.scheduleNextRetry() // 2회: 60s
                val second = transfer.nextRetryAt!!

                transfer.scheduleNextRetry() // 3회: 120s
                val third = transfer.nextRetryAt!!

                second shouldBeGreaterThan first
                third shouldBeGreaterThan second
            }
        }
    }

    Given("COMPENSATION_PENDING 상태에서 scheduleNextRetry()를 반복 호출하면") {
        When("2회 연속 호출하면") {
            Then("nextRetryAt이 UNKNOWN보다 긴 간격으로 증가한다") {
                val transfer = createTransfer(InterbankTransferStatus.COMPENSATION_PENDING)

                transfer.scheduleNextRetry() // 1회
                val first = transfer.nextRetryAt!!

                transfer.scheduleNextRetry() // 2회
                val second = transfer.nextRetryAt!!

                second shouldBeGreaterThan first
            }
        }
    }

    Given("UNKNOWN 상태에서 retryCount가 MAX_RETRY_COUNT 미만인 경우") {
        When("isRetryExhausted()를 호출하면") {
            Then("false를 반환한다") {
                val transfer = createTransfer(
                    status     = InterbankTransferStatus.UNKNOWN,
                    retryCount = InterbankTransfer.MAX_RETRY_COUNT - 1,
                )
                transfer.isRetryExhausted() shouldBe false
            }
        }
    }

    Given("UNKNOWN 상태에서 retryCount가 MAX_RETRY_COUNT 이상인 경우") {
        When("isRetryExhausted()를 호출하면") {
            Then("true를 반환한다") {
                val transfer = createTransfer(
                    status     = InterbankTransferStatus.UNKNOWN,
                    retryCount = InterbankTransfer.MAX_RETRY_COUNT,
                )
                transfer.isRetryExhausted() shouldBe true
            }
        }
    }

    Given("COMPENSATION_PENDING 상태에서 retryCount가 MAX_COMPENSATION_RETRY 이상인 경우") {
        When("isRetryExhausted()를 호출하면") {
            Then("true를 반환한다") {
                val transfer = createTransfer(
                    status     = InterbankTransferStatus.COMPENSATION_PENDING,
                    retryCount = InterbankTransfer.MAX_COMPENSATION_RETRY,
                )
                transfer.isRetryExhausted() shouldBe true
            }
        }
    }
})