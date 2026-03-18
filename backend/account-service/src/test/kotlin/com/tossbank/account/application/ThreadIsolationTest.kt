package com.tossbank.account.application

import com.tossbank.account.infrastructure.persistence.AccountRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk

class ThreadIsolationTest : BehaviorSpec({

    val accountRepository = mockk<AccountRepository>()
    val service = AccountQueryService(accountRepository)

    afterTest { clearAllMocks() }

    Given("WebFlux Netty 이벤트 루프 스레드 환경에서") {
        val memberId = 1L

        When("Dispatchers.IO 없이 JPA를 직접 호출하면 (문제 상황)") {
            var dbThreadName = ""
            val callerThreadName = Thread.currentThread().name

            every { accountRepository.findAllByMemberIdAndStatus(any(), any()) } answers {
                dbThreadName = Thread.currentThread().name
                emptyList()
            }

            service.getActiveAccountsBlocking(memberId)

            Then("JPA가 호출 스레드(Netty)와 동일한 스레드에서 실행된다 → Netty가 멈춤") {
                println("\n[문제 상황]")
                println("  호출 스레드:    $callerThreadName")
                println("  JPA 실행 스레드: $dbThreadName")
                println("  ⚠️ 같은 스레드! Netty가 DB 응답까지 멈춰버립니다\n")

                // 같은 스레드에서 실행 → Thread Starvation 유발
                dbThreadName shouldBe callerThreadName
                // Netty 스레드(또는 테스트 워커 스레드)가 직접 JPA 처리
                dbThreadName shouldNotContain "DefaultDispatcher-worker"
            }
        }

        When("Dispatchers.IO로 격리하여 JPA를 호출하면 (해결책)") {
            var dbThreadName = ""
            val callerThreadName = Thread.currentThread().name

            every { accountRepository.findAllByMemberIdAndStatus(any(), any()) } answers {
                dbThreadName = Thread.currentThread().name
                emptyList()
            }

            service.getActiveAccounts(memberId)

            Then("JPA가 별도의 IO 전용 스레드에서 실행된다 → Netty는 자유롭게 다른 요청 처리") {
                println("\n[해결 상황]")
                println("  호출 스레드:    $callerThreadName")
                println("  JPA 실행 스레드: $dbThreadName")
                println("  ✅ 다른 스레드! Netty는 DB를 기다리지 않고 다른 요청을 처리합니다\n")

                // 다른 스레드에서 실행 → Thread Starvation 방어!
                dbThreadName shouldNotBe callerThreadName
                // 반드시 Dispatchers.IO 워커 스레드에서 실행되어야 함
                dbThreadName shouldContain "DefaultDispatcher-worker"
            }
        }
    }
})