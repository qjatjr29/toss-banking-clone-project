package com.tossbank.transfer.domain.model

import com.tossbank.transfer.domain.exception.InvalidTransferStateTransitionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.date.shouldBeBefore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDateTime

class InterbankTransferSagaTest : BehaviorSpec({

    fun createSaga(status: InterbankTransferSagaStatus = InterbankTransferSagaStatus.PENDING) =
        InterbankTransferSaga(
            fromMemberId      = 1L,
            fromAccountId     = 100L,
            fromAccountNumber = "1000-0000-0001",
            toAccountNumber   = "2000-0000-0001",
            toBankCode        = "004",
            toMemberName      = "홍길동",
            amount            = BigDecimal("10000"),
            description       = null,
            idempotencyKey    = "test-key-001",
        ).also {
            val field = InterbankTransferSaga::class.java.getDeclaredField("status")
            field.isAccessible = true
            field.set(it, status)
        }

    // ── 출금 완료 ────────────────────────────────────────────
    Given("PENDING 상태에서") {
        When("markWithdrawCompleted() 호출하면") {
            Then("WITHDRAW_COMPLETED 상태 + remainingBalance 저장 + retryCount 초기화") {
                val saga = createSaga(InterbankTransferSagaStatus.PENDING)
                saga.markWithdrawCompleted(BigDecimal("90000"))

                saga.status shouldBe InterbankTransferSagaStatus.WITHDRAW_COMPLETED
                saga.remainingBalance shouldBe BigDecimal("90000")
                saga.retryCount shouldBe 0
                saga.nextRetryAt.shouldBeNull()
            }
        }

        When("markFailed() 호출하면") {
            Then("FAILED 상태로 전이") {
                val saga = createSaga(InterbankTransferSagaStatus.PENDING)
                saga.markFailed()
                saga.status shouldBe InterbankTransferSagaStatus.FAILED
            }
        }

        When("markWithdrawCompleted() 이미 호출된 상태에서 다시 호출하면") {
            Then("InvalidTransferStateTransitionException 발생") {
                val saga = createSaga(InterbankTransferSagaStatus.PENDING)
                saga.markWithdrawCompleted(BigDecimal("90000"))

                shouldThrow<InvalidTransferStateTransitionException> {
                    saga.markWithdrawCompleted(BigDecimal("90000"))
                }
            }
        }
    }

    // ── 외부 송금 성공 ────────────────────────────────────────
    Given("WITHDRAW_COMPLETED 상태에서") {
        When("markCompleted() 호출하면") {
            Then("COMPLETED 상태 + externalTransactionId 저장 + retryCount 초기화") {
                val saga = createSaga(InterbankTransferSagaStatus.WITHDRAW_COMPLETED)
                saga.markCompleted("ext-tx-001")

                saga.status shouldBe InterbankTransferSagaStatus.COMPLETED
                saga.externalTransactionId shouldBe "ext-tx-001"
                saga.retryCount shouldBe 0
                saga.nextRetryAt.shouldBeNull()
            }
        }

        When("markTransferUnknown() 호출하면") {
            Then("TRANSFER_UNKNOWN 상태 + nextRetryAt 설정 + retryCount 증가") {
                val saga = createSaga(InterbankTransferSagaStatus.WITHDRAW_COMPLETED)
                saga.markTransferUnknown("5xx error")

                saga.status shouldBe InterbankTransferSagaStatus.TRANSFER_UNKNOWN
                saga.lastErrorMessage shouldBe "5xx error"
                saga.retryCount shouldBe 1
                saga.nextRetryAt.shouldNotBeNull()
            }
        }

        When("markCompensating() 호출하면") {
            Then("COMPENSATING 상태로 전이 (4xx 직접 보상)") {
                val saga = createSaga(InterbankTransferSagaStatus.WITHDRAW_COMPLETED)
                saga.markCompensating()

                saga.status shouldBe InterbankTransferSagaStatus.COMPENSATING
                saga.retryCount shouldBe 0
                saga.nextRetryAt.shouldBeNull()
            }
        }
    }

    // ── 외부 송금 결과 불확실 (재조회 흐름) ──────────────────
    Given("TRANSFER_UNKNOWN 상태에서") {
        When("markTransferUnknown() 재호출하면") {
            Then("TRANSFER_UNKNOWN 유지 + retryCount 증가") {
                val saga = createSaga(InterbankTransferSagaStatus.TRANSFER_UNKNOWN)
                saga.markTransferUnknown("retry error")

                saga.status shouldBe InterbankTransferSagaStatus.TRANSFER_UNKNOWN
                saga.retryCount shouldBe 1
                saga.lastErrorMessage shouldBe "retry error"
            }
        }

        When("markCompleted() 호출하면 (재조회 후 성공 확인)") {
            Then("COMPLETED 상태 + externalTransactionId 저장") {
                val saga = createSaga(InterbankTransferSagaStatus.TRANSFER_UNKNOWN)
                saga.markCompleted("ext-tx-001")

                saga.status shouldBe InterbankTransferSagaStatus.COMPLETED
                saga.externalTransactionId shouldBe "ext-tx-001"
            }
        }

        When("markCompensating() 호출하면 (재조회 후 실패 확인)") {
            Then("COMPENSATING 상태로 전이") {
                val saga = createSaga(InterbankTransferSagaStatus.TRANSFER_UNKNOWN)
                saga.markCompensating()

                saga.status shouldBe InterbankTransferSagaStatus.COMPENSATING
            }
        }
    }

    // ── 보상 ─────────────────────────────────────────────────
    Given("COMPENSATING 상태에서") {
        When("markCompensated() 호출하면") {
            Then("COMPENSATED 상태로 전이") {
                val saga = createSaga(InterbankTransferSagaStatus.COMPENSATING)
                saga.markCompensated()

                saga.status shouldBe InterbankTransferSagaStatus.COMPENSATED
            }
        }

        When("markCompensated() 이미 호출된 상태에서 다시 호출하면") {
            Then("InvalidTransferStateTransitionException 발생 (이중 보상 방지)") {
                val saga = createSaga(InterbankTransferSagaStatus.COMPENSATING)
                saga.markCompensated()

                shouldThrow<InvalidTransferStateTransitionException> {
                    saga.markCompensated()
                }
            }
        }
    }

    // ── 잘못된 상태 전이 방어 ────────────────────────────────
    Given("잘못된 상태에서 markCompleted() 호출하면") {
        listOf(
            InterbankTransferSagaStatus.PENDING,
            InterbankTransferSagaStatus.COMPENSATING,
            InterbankTransferSagaStatus.COMPENSATED,
            InterbankTransferSagaStatus.FAILED,
        ).forEach { invalidStatus ->
            Then("$invalidStatus → InvalidTransferStateTransitionException") {
                shouldThrow<InvalidTransferStateTransitionException> {
                    createSaga(invalidStatus).markCompleted("ext-tx-001")
                }
            }
        }
    }

    Given("잘못된 상태에서 markCompensating() 호출하면") {
        listOf(
            InterbankTransferSagaStatus.PENDING,
            InterbankTransferSagaStatus.COMPLETED,
            InterbankTransferSagaStatus.COMPENSATED,
            InterbankTransferSagaStatus.FAILED,
        ).forEach { invalidStatus ->
            Then("$invalidStatus → InvalidTransferStateTransitionException") {
                shouldThrow<InvalidTransferStateTransitionException> {
                    createSaga(invalidStatus).markCompensating()
                }
            }
        }
    }

    Given("잘못된 상태에서 markFailed() 호출하면") {
        listOf(
            InterbankTransferSagaStatus.WITHDRAW_COMPLETED,
            InterbankTransferSagaStatus.TRANSFER_UNKNOWN,
            InterbankTransferSagaStatus.COMPLETED,
        ).forEach { invalidStatus ->
            Then("$invalidStatus → InvalidTransferStateTransitionException") {
                shouldThrow<InvalidTransferStateTransitionException> {
                    createSaga(invalidStatus).markFailed()
                }
            }
        }
    }

    // ── MANUAL_REQUIRED ──────────────────────────────────────
    Given("어떤 상태에서든 markManualRequired() 호출하면") {
        listOf(
            InterbankTransferSagaStatus.WITHDRAW_COMPLETED,
            InterbankTransferSagaStatus.TRANSFER_UNKNOWN,
            InterbankTransferSagaStatus.COMPENSATING,
            InterbankTransferSagaStatus.PENDING,
        ).forEach { anyStatus ->
            Then("$anyStatus → MANUAL_REQUIRED + errorMessage 저장") {
                val saga = createSaga(anyStatus)
                saga.markManualRequired("재시도 한계 초과")

                saga.status shouldBe InterbankTransferSagaStatus.MANUAL_REQUIRED
                saga.lastErrorMessage shouldBe "재시도 한계 초과"
            }
        }
    }

    // ── 재시도 backoff ───────────────────────────────────────
    Given("retryCount가 MAX(5)에 도달하면") {
        When("isRetryExhausted() 호출하면") {
            Then("true 반환") {
                val saga = createSaga(InterbankTransferSagaStatus.WITHDRAW_COMPLETED)
                repeat(InterbankTransferSaga.MAX_RETRY_COUNT) {
                    saga.markTransferUnknown("error")
                }
                saga.isRetryExhausted() shouldBe true
            }
        }
    }

    Given("재시도할수록 backoff 시간이 늘어나야 한다") {
        When("첫 번째 TRANSFER_UNKNOWN") {
            Then("30초 후 재시도") {
                val saga = createSaga(InterbankTransferSagaStatus.WITHDRAW_COMPLETED)
                val before = LocalDateTime.now().plusSeconds(30L)
                saga.markTransferUnknown("error")
                val after = LocalDateTime.now().plusSeconds(30L)

                saga.nextRetryAt.shouldNotBeNull()
                saga.nextRetryAt!!.shouldBeBefore(after)
                saga.nextRetryAt!!.shouldBeAfter(before.minusSeconds(1L))
            }
        }

        When("두 번째 TRANSFER_UNKNOWN") {
            Then("2분 후 재시도") {
                val saga = createSaga(InterbankTransferSagaStatus.TRANSFER_UNKNOWN)
                val field = InterbankTransferSaga::class.java.getDeclaredField("retryCount")
                field.isAccessible = true
                field.set(saga, 1)

                val before = LocalDateTime.now().plusSeconds(120L)
                saga.markTransferUnknown("error")
                val after = LocalDateTime.now().plusSeconds(120L)

                saga.nextRetryAt.shouldNotBeNull()
                saga.nextRetryAt!!.shouldBeBefore(after)
                saga.nextRetryAt!!.shouldBeAfter(before.minusSeconds(1L))
            }
        }
    }
})