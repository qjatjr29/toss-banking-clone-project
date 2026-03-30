package com.tossbank.transfer.domain.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class InternalTransferSagaTest : BehaviorSpec({

    fun createSaga(status: InternalTransferSagaStatus = InternalTransferSagaStatus.PENDING) =
        InternalTransferSaga(
            fromMemberId    = 1L,
            fromAccountId   = 100L,
            toAccountId     = 200L,
            toAccountNumber = "1002-000-000001",
            toMemberName    = "홍길동",
            fromMemberName  = "김철수",
            amount          = BigDecimal("10000"),
            description     = null,
            idempotencyKey  = "test-key-001",
        ).also {
            // 테스트용 상태 강제 설정 (리플렉션)
            val field = InternalTransferSaga::class.java.getDeclaredField("status")
            field.isAccessible = true
            field.set(it, status)
        }

    // ── 출금 관련 상태 전이 ──────────────────────────────────
    Given("PENDING 상태에서") {
        When("markWithdrawCompleted() 호출하면") {
            Then("WITHDRAW_COMPLETED 상태 + remainingBalance 저장 + retryCount 초기화") {
                val saga = createSaga(InternalTransferSagaStatus.PENDING)
                saga.markWithdrawCompleted(BigDecimal("90000"))

                saga.status shouldBe InternalTransferSagaStatus.WITHDRAW_COMPLETED
                saga.remainingBalance shouldBe BigDecimal("90000")
                saga.retryCount shouldBe 0
                saga.nextRetryAt shouldBe null
            }
        }

        When("markWithdrawFailed() 호출하면") {
            Then("FAILED 상태로 전이") {
                val saga = createSaga(InternalTransferSagaStatus.PENDING)
                saga.markWithdrawFailed()
                saga.status shouldBe InternalTransferSagaStatus.FAILED
            }
        }

        When("markWithdrawUnknown() 호출하면") {
            Then("WITHDRAW_UNKNOWN 상태 + nextRetryAt 설정") {
                val saga = createSaga(InternalTransferSagaStatus.PENDING)
                saga.markWithdrawUnknown()

                saga.status shouldBe InternalTransferSagaStatus.WITHDRAW_UNKNOWN
                saga.retryCount shouldBe 1
                saga.nextRetryAt.shouldNotBeNull()
            }
        }
    }

    // ── 입금 관련 상태 전이 ──────────────────────────────────
    Given("WITHDRAW_COMPLETED 상태에서") {
        When("markCompleted() 호출하면") {
            Then("COMPLETED 상태 + retryCount 초기화") {
                val saga = createSaga(InternalTransferSagaStatus.WITHDRAW_COMPLETED)
                saga.markCompleted()

                saga.status shouldBe InternalTransferSagaStatus.COMPLETED
                saga.retryCount shouldBe 0
            }
        }

        When("markDepositUnknown() 호출하면") {
            Then("DEPOSIT_UNKNOWN 상태 + nextRetryAt 설정") {
                val saga = createSaga(InternalTransferSagaStatus.WITHDRAW_COMPLETED)
                saga.markDepositUnknown()

                saga.status shouldBe InternalTransferSagaStatus.DEPOSIT_UNKNOWN
                saga.nextRetryAt.shouldNotBeNull()
            }
        }

        When("markCompensating() 호출하면") {
            Then("COMPENSATING 상태로 전이 (4xx 직접 보상)") {
                val saga = createSaga(InternalTransferSagaStatus.WITHDRAW_COMPLETED)
                saga.markCompensating()

                saga.status shouldBe InternalTransferSagaStatus.COMPENSATING
                saga.retryCount shouldBe 0
            }
        }
    }

    Given("DEPOSIT_UNKNOWN 상태에서") {
        When("markCompensating() 호출하면") {
            Then("COMPENSATING 상태로 전이 (재조회 NOT_FOUND 경로)") {
                val saga = createSaga(InternalTransferSagaStatus.DEPOSIT_UNKNOWN)
                saga.markCompensating()
                saga.status shouldBe InternalTransferSagaStatus.COMPENSATING
            }
        }
    }

    // ── 보상 상태 전이 ───────────────────────────────────────
    Given("COMPENSATING 상태에서") {
        When("markCompensated() 호출하면") {
            Then("COMPENSATED 상태로 전이") {
                val saga = createSaga(InternalTransferSagaStatus.COMPENSATING)
                saga.markCompensated()
                saga.status shouldBe InternalTransferSagaStatus.COMPENSATED
            }
        }
    }

    // ── 잘못된 상태 전이 방어 ────────────────────────────────
    Given("잘못된 상태에서 markCompensating() 호출하면") {
        val invalidStatuses = listOf(
            InternalTransferSagaStatus.PENDING,
            InternalTransferSagaStatus.COMPLETED,
            InternalTransferSagaStatus.COMPENSATED,
            InternalTransferSagaStatus.FAILED,
        )
        invalidStatuses.forEach { status ->
            Then("$status → IllegalStateException") {
                shouldThrow<IllegalStateException> {
                    createSaga(status).markCompensating()
                }
            }
        }
    }

    // ── 재시도 backoff ──────────────────────────────────────
    Given("retryCount가 MAX(5)에 도달하면") {
        When("isRetryExhausted() 호출하면") {
            Then("true 반환") {
                val saga = createSaga()
                repeat(5) { saga.markWithdrawUnknown() }
                saga.isRetryExhausted() shouldBe true
            }
        }
    }

    Given("재시도할수록 backoff 시간이 늘어나야 한다") {
        When("첫 번째 UNKNOWN") {
            Then("30초 후 재시도") {
                val saga = createSaga()
                saga.markWithdrawUnknown()
                val delay = java.time.Duration.between(
                    java.time.LocalDateTime.now(),
                    saga.nextRetryAt
                ).seconds
                delay shouldBe 30L
            }
        }
    }
})